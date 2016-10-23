package com.danielflower.apprunner.router.web.v1;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Path("/system")
public class SystemResource {
    public static final Logger log = LoggerFactory.getLogger(SystemResource.class);
    public static final String HOST_NAME = System.getenv("COMPUTERNAME");
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);

    private final Cluster cluster;

    private org.eclipse.jetty.client.HttpClient httpClient;

    public SystemResource(Cluster cluster, HttpClient httpClient) {
        this.cluster = cluster;
        this.httpClient = httpClient;
    }

    private List<JSONObject> loadAllSystems(URI forwardedHost, List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException {
        List<JSONObject> results = new ArrayList<>();
        log.info("Looking up app info from " + runners);
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (Runner runner : runners) {
            futures.add(executorService.submit(() -> loadSystemInfoForRunner(forwardedHost, runner)));
        }
        for (Future<JSONObject> future : futures) {
            results.add(future.get(45, TimeUnit.SECONDS));
        }
        log.info("Got " + results.size() + " results");
        return results;
    }

    private JSONObject loadSystemInfoForRunner(URI forwardedHost, Runner runner) throws Exception {
        URI uri = runner.url.resolve("/api/v1/system");
        Request req = httpClient.newRequest(uri)
            .method(HttpMethod.GET);
        if (forwardedHost != null) {
            // If null, then URLs returned in the JSON will be the app-runner instance's URL.
            // If not null, it should be the router URL, in which case URLs will be based on the router.
            req = req.header(HttpHeader.HOST, forwardedHost.getAuthority());
        }
        ContentResponse resp = req.send();
        if (resp.getStatus() != 200) {
            throw new RuntimeException("Unable to load system from " + uri + " - message was " + resp.getContentAsString());
        }
        return new JSONObject(resp.getContentAsString());
    }

    @GET
    @Produces("application/json")
    public Response systemInfo(@Context UriInfo uri) throws IOException, ExecutionException, InterruptedException {
        JSONObject result = new JSONObject();
        boolean allStarted = true;
        result.put("host", HOST_NAME);

        List<JSONObject> systems;
        try {
            systems = loadAllSystems(uri.getRequestUri() /* Make URLs be router URLs */, cluster.getRunners());
        } catch (TimeoutException e) {
            return Response.serverError().status(Response.Status.GATEWAY_TIMEOUT).build();
        }

        JSONArray apps = new JSONArray();
        result.put("samples", apps);
        Set<String> addedSamples = new HashSet<>();
        for (JSONObject system : systems) {
            allStarted = allStarted && system.getBoolean("appRunnerStarted");
            JSONArray samples = system.getJSONArray("samples");
            for (Object sampleObj : samples) {
                JSONObject sample = (JSONObject)sampleObj;
                String id = sample.getString("id");
                if (!addedSamples.contains(id)) {
                    apps.put(sample);
                    addedSamples.add(id);
                }
            }
        }
        result.put("appRunnerStarted", allStarted);
        return Response.ok(result.toString(4)).build();
    }

    @GET
    @Path("/samples/{name}")
    @Produces("application/zip")
    public Response samples(@Context UriInfo uri, @PathParam("name") String name) throws IOException, ExecutionException, InterruptedException {
        Set<String> names = new HashSet<>();

        List<JSONObject> systems;
        try {
            systems = loadAllSystems(null /* Keep URLs as instance URLs so they can be called directly by the code below */, cluster.getRunners());
        } catch (TimeoutException e) {
            return Response.serverError().status(Response.Status.GATEWAY_TIMEOUT).build();
        }

        for (JSONObject system : systems) {
            JSONArray samples = system.getJSONArray("samples");
            for (Object sampleObj : samples) {
                JSONObject sample = (JSONObject)sampleObj;
                String id = sample.getString("id");
                names.add(id);
                if ((id + ".zip").equalsIgnoreCase(name)) {
                    URI zipUri = URI.create(sample.getString("url"));
                    try {
                        Request targetRequest = httpClient.newRequest(zipUri)
                            .method(HttpMethod.GET)
                            .header(HttpHeader.HOST, uri.getRequestUri().getAuthority())
                            .timeout(30, TimeUnit.SECONDS);
                        ContentResponse targetResponse = targetRequest.send();
                        if (targetResponse.getStatus() == 200) {
                            Response.ResponseBuilder clientResponse = Response.ok(targetResponse.getContent());
                            for (HttpField httpField : targetResponse.getHeaders()) {
                                clientResponse.header(httpField.getName(), httpField.getValue());
                            }
                            log.info("Return sample for " + name + " from " + zipUri);
                            return clientResponse.build();
                        }
                    } catch (Exception e) {
                        log.warn("Error while trying to download " + zipUri, e);
                    }
                }

            }
        }

        return Response.status(404).entity("Invalid sample app name. Valid names: " + names).build();
    }

}
