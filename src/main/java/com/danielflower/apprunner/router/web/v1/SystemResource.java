package com.danielflower.apprunner.router.web.v1;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.ForwardedHeadersAdder;
import com.danielflower.apprunner.router.mgmt.Runner;
import com.danielflower.apprunner.router.mgmt.SystemInfo;
import org.apache.commons.lang3.ObjectUtils;
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

import javax.servlet.http.HttpServletRequest;
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
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);
    private final ForwardedHeadersAdder forwardedHeadersAdder = new ForwardedHeadersAdder();
    private final SystemInfo systemInfo;
    private final String routerVersion = ObjectUtils.firstNonNull(SystemResource.class.getPackage().getImplementationVersion(), "master");

    private final Cluster cluster;

    private org.eclipse.jetty.client.HttpClient httpClient;

    public SystemResource(SystemInfo systemInfo, Cluster cluster, HttpClient httpClient) {
        this.systemInfo = systemInfo;
        this.cluster = cluster;
        this.httpClient = httpClient;
    }

    private List<JSONObject> loadAllRunnersWithSystems(HttpServletRequest clientRequest, List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException {
        List<JSONObject> results = new ArrayList<>();
        log.info("Looking up app info from " + runners);
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (Runner runner : runners) {
            futures.add(executorService.submit(() -> loadSystemInfoForRunner(clientRequest, runner)));
        }
        for (Future<JSONObject> future : futures) {
            results.add(future.get(45, TimeUnit.SECONDS));
        }
        log.info("Got " + results.size() + " results");
        return results;
    }

    private JSONObject loadSystemInfoForRunner(HttpServletRequest clientRequest, Runner runner) throws Exception {
        URI uri = runner.url.resolve("/api/v1/system");
        Request req = httpClient.newRequest(uri)
            .method(HttpMethod.GET);
        forwardedHeadersAdder.addHeaders(clientRequest, req);
        ContentResponse resp = req.send();
        if (resp.getStatus() != 200) {
            throw new RuntimeException("Unable to load system from " + uri + " - message was " + resp.getContentAsString());
        }
        JSONObject runnerJson = runner.toJSON();
        runnerJson.put("system", new JSONObject(resp.getContentAsString()));
        return runnerJson;
    }

    @GET
    @Produces("application/json")
    public Response systemInfo(@Context HttpServletRequest clientRequest) throws IOException, ExecutionException, InterruptedException {
        JSONObject result = new JSONObject();
        result.put("host", systemInfo.hostName);
        result.put("user", systemInfo.user);
        result.put("appRunnerVersion", routerVersion);

        JSONObject os = new JSONObject();
        result.put("os", os);
        os.put("osName", systemInfo.osName);
        os.put("numCpus", systemInfo.numCpus);
        os.put("uptimeInSeconds", systemInfo.uptimeInMillis()  / 1000L);
        os.put("appRunnerPid", systemInfo.pid);

        List<JSONObject> runners;
        try {
            runners = loadAllRunnersWithSystems(clientRequest, cluster.getRunners());
        } catch (TimeoutException e) {
            return Response.serverError().status(Response.Status.GATEWAY_TIMEOUT).build();
        }

        result.put("runners", runners);
        result.put("publicKeys", getAggregatedPublicKeys(runners));

        boolean allStarted = addSamples(result, runners);
        result.put("appRunnerStarted", allStarted);
        return Response.ok(result.toString(4)).build();
    }

    private static JSONArray getAggregatedPublicKeys(List<JSONObject> runners) {
        JSONArray aggregatedKeys = new JSONArray();
        Set<String> added = new HashSet<>();
        for (JSONObject runner : runners) {
            JSONObject system = runner.getJSONObject("system");
            if (system.has("publicKeys")) {
                JSONArray keys = system.getJSONArray("publicKeys");
                for (Object keyObj : keys) {
                    String key = (String)keyObj;
                    if (added.add(key)) {
                        aggregatedKeys.put(key);
                    }
                }
            }
        }
        return aggregatedKeys;
    }

    private static boolean addSamples(JSONObject result, List<JSONObject> runners) {
        boolean allStarted = true;
        JSONArray apps = new JSONArray();
        result.put("samples", apps);
        Set<String> addedSamples = new HashSet<>();
        for (JSONObject runner : runners) {
            JSONObject system = runner.getJSONObject("system");
            allStarted = allStarted && system.getBoolean("appRunnerStarted");
            JSONArray samples = system.getJSONArray("samples");
            for (Object sampleObj : samples) {
                JSONObject sample = (JSONObject)sampleObj;
                String id = getSampleID(sample);
                if (!addedSamples.contains(id)) {
                    apps.put(sample);
                    addedSamples.add(id);
                }
            }
        }
        return allStarted;
    }

    public static String getSampleID(JSONObject sample) {
        return sample.has("id") ? sample.getString("id") : sample.getString("name");
    }

    @GET
    @Path("/samples/{name}")
    @Produces("application/zip")
    public Response samples(@Context UriInfo uri, @PathParam("name") String name) throws IOException, ExecutionException, InterruptedException {
        Set<String> names = new HashSet<>();

        List<JSONObject> runners;
        try {
            runners = loadAllRunnersWithSystems(null /* Keep URLs as instance URLs so they can be called directly by the code below */, cluster.getRunners());
        } catch (TimeoutException e) {
            return Response.serverError().status(Response.Status.GATEWAY_TIMEOUT).build();
        }

        for (JSONObject runner : runners) {
            JSONObject system = runner.getJSONObject("system");
            JSONArray samples = system.getJSONArray("samples");
            for (Object sampleObj : samples) {
                JSONObject sample = (JSONObject)sampleObj;
                String id = getSampleID(sample);
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
