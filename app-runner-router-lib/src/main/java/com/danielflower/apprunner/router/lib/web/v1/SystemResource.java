package com.danielflower.apprunner.router.lib.web.v1;

import com.danielflower.apprunner.router.lib.mgmt.Cluster;
import com.danielflower.apprunner.router.lib.mgmt.Runner;
import com.danielflower.apprunner.router.lib.mgmt.SystemInfo;
import io.muserver.MuRequest;
import io.muserver.MuStats;
import io.muserver.murp.ReverseProxy;
import org.apache.commons.lang3.ObjectUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

@Path("/system")
public class SystemResource {
    public static final Logger log = LoggerFactory.getLogger(SystemResource.class);
    private final ExecutorService executorService = Executors.newFixedThreadPool(2);
    private final SystemInfo systemInfo;
    private final String routerVersion = ObjectUtils.firstNonNull(SystemResource.class.getPackage().getImplementationVersion(), "master");

    private final Cluster cluster;

    private final HttpClient httpClient;

    public SystemResource(SystemInfo systemInfo, Cluster cluster, HttpClient httpClient) {
        this.systemInfo = systemInfo;
        this.cluster = cluster;
        this.httpClient = httpClient;
    }

    private List<JSONObject> loadAllRunnersWithSystems(MuRequest clientRequest, List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException {
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

    private JSONObject loadSystemInfoForRunner(MuRequest clientRequest, Runner runner) throws Exception {
        URI uri = runner.url.resolve("/api/v1/system");
        HttpRequest.Builder req = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(5));
        if (clientRequest != null) {
            ReverseProxy.setForwardedHeaders(clientRequest, req, false, true);
        }
        JSONObject runnerJson = runner.toJSON();
        try {
            var resp = httpClient.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                runnerJson.put("system", new JSONObject(resp.body()));
            } else {
                runnerJson.put("system", erroredSystemJson())
                    .put("error", "Unable to load system from " + uri + " - message was " + resp.body());
            }
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Error looking up system info for " + uri + " - " + e.getMessage());
            runnerJson.put("system", erroredSystemJson())
                .put("error", "ERROR: " + e.getMessage());
        }
        return runnerJson;
    }

    private JSONObject erroredSystemJson() {
        return new JSONObject()
            .put("appRunnerStarted", false)
            .put("samples", new JSONArray());
    }

    @GET
    @Produces("application/json")
    public Response systemInfo(@Context MuRequest clientRequest) throws ExecutionException, InterruptedException {
        JSONObject result = new JSONObject();
        result.put("host", systemInfo.hostName);
        result.put("user", systemInfo.user);
        result.put("appRunnerVersion", routerVersion);

        MuStats stats = clientRequest.server().stats();
        result.put("serverStats",
            new JSONObject()
                .put("activeRequests", stats.activeRequests().size())
                .put("completedRequests", stats.completedRequests())
                .put("activeConnections", stats.activeConnections())
                .put("completedConnections", stats.completedConnections())
                .put("invalidHttpRequests", stats.invalidHttpRequests())
                .put("failedToConnect", stats.failedToConnect())
                .put("rejectedDueToOverload", stats.rejectedDueToOverload())
                .put("bytesRead", stats.bytesRead())
                .put("bytesSent", stats.bytesSent())
        );

        JSONObject os = new JSONObject();
        result.put("os", os);
        os.put("osName", systemInfo.osName);
        os.put("numCpus", systemInfo.numCpus);
        os.put("uptimeInSeconds", systemInfo.uptimeInMillis() / 1000L);
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
                    String key = (String) keyObj;
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
                JSONObject sample = (JSONObject) sampleObj;
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
    public Response samples(@Context MuRequest muRequest, @Context UriInfo uri, @PathParam("name") String name) throws ExecutionException, InterruptedException {
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
                JSONObject sample = (JSONObject) sampleObj;
                String id = getSampleID(sample);
                names.add(id);
                if ((id + ".zip").equalsIgnoreCase(name)) {
                    URI zipUri = URI.create(sample.getString("url"));
                    try {
                        HttpRequest targetRequest = HttpRequest.newBuilder(zipUri)
                            .timeout(Duration.ofSeconds(30))
                            .build();
                        var targetResponse = httpClient.send(targetRequest, HttpResponse.BodyHandlers.ofInputStream());
                        if (targetResponse.statusCode() == 200) {
                            Response.ResponseBuilder clientResponse = Response.ok(targetResponse.body());
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
