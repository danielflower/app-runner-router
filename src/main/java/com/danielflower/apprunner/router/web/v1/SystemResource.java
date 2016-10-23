package com.danielflower.apprunner.router.web.v1;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
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
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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

    public List<JSONObject> loadAllSystems(URI forwardedHost, List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException {
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
        ContentResponse resp = httpClient.newRequest(uri)
            .method(HttpMethod.GET)
            .header(HttpHeader.HOST, forwardedHost.getAuthority())
            .send();
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
            systems = loadAllSystems(uri.getRequestUri(), cluster.getRunners());
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
    public Response samples(@PathParam("name") String name) throws IOException {
        List<String> names = new ArrayList<>();
        if (!names.contains(name)) {
            return Response.status(404).entity("Invalid sample app name. Valid names: " + names).build();
        }

        try (InputStream zipStream = getClass().getResourceAsStream("/sample-apps/" + name)) {
            return Response.ok(IOUtils.toByteArray(zipStream))
                .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
                .build();
        }
    }

}
