package com.danielflower.apprunner.router.lib.mgmt;

import com.danielflower.apprunner.router.lib.web.ProxyMap;
import io.muserver.MuRequest;
import io.muserver.murp.ReverseProxy;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

public class ClusterQueryingMapManager implements MapManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterQueryingMapManager.class);
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    private final ProxyMap proxyMap;
    private final HttpClient httpClient;

    public ClusterQueryingMapManager(ProxyMap proxyMap, HttpClient httpClient) {
        this.proxyMap = proxyMap;
        this.httpClient = httpClient;
    }

    @Override
    public ConcurrentHashMap<String, URI> getCurrentMapping() {
        return proxyMap.getAll();
    }

    @Override
    public Result loadAllApps(MuRequest clientRequest, List<Runner> runners) throws InterruptedException {
        Result result = new Result();
        log.info("Looking up app info from " + runners);
        List<RunnerFuture> futures = new ArrayList<>();
        for (Runner runner : runners) {
            futures.add(new RunnerFuture(runner, executorService.submit(() -> loadRunner(clientRequest, runner))));
        }
        for (RunnerFuture rf : futures) {
            try {
                JSONObject appJson = rf.future.get();
                appJson.put("appRunnerInstanceId", rf.runner.id);
                result.appsJsonFromEachRunner.add(appJson);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                log.error(rf.runner.id + " app lookup error: " + cause.getMessage());
                result.errors.add(rf.runner.id + ": " + cause.getMessage());
            }
        }
        log.info("Got " + result.appsJsonFromEachRunner.size() + " results");
        return result;
    }

    @Override
    public JSONObject loadRunner(MuRequest clientRequest, Runner runner) throws Exception {
        URI uri = runner.url.resolve("/api/v1/apps");
        JSONObject info = getJSONResponse(clientRequest, uri);
        List<String> addedNames = new ArrayList<>();
        for (Object app : info.getJSONArray("apps")) {
            String name = ((JSONObject) app).getString("name");
            addedNames.add(name);
            proxyMap.add(name, uri.resolve("/" + name));
        }
        for (Map.Entry<String, URI> entry : proxyMap.getAll().entrySet()) {
            if (entry.getValue().getAuthority().equals(runner.url.getAuthority())
                && !addedNames.contains(entry.getKey())) {
                log.info("Detected a missing app, so will remove it from the proxy map: " + entry.getKey() + " at " + entry.getValue());
                proxyMap.remove(entry.getKey());
            }
        }
        return info;
    }

    @Override
    public JSONObject loadRunnerSystemInfo(MuRequest clientRequest, Runner runner) throws Exception {
        URI uri = runner.url.resolve("/api/v1/system");
        return getJSONResponse(clientRequest, uri);
    }


    private JSONObject getJSONResponse(MuRequest clientRequest, URI uri) throws InterruptedException, ExecutionException, TimeoutException {
        HttpResponse<String> resp;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10));
            if (clientRequest != null) {
                ReverseProxy.setForwardedHeaders(clientRequest, request, false, true);
            }
            resp = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Unable to load apps from " + uri + " - message was " + resp.body());
        }
        return new JSONObject(resp.body());
    }


    @Override
    public void removeRunner(Runner runner) {
        for (Map.Entry<String, URI> entry : proxyMap.getAll().entrySet()) {
            if (entry.getValue().getAuthority().equals(runner.url.getAuthority())) {
                proxyMap.remove(entry.getKey());
            }
        }
    }


    private static class RunnerFuture {
        public final Runner runner;
        public final Future<JSONObject> future;

        private RunnerFuture(Runner runner, Future<JSONObject> future) {
            this.runner = runner;
            this.future = future;
        }
    }
}
