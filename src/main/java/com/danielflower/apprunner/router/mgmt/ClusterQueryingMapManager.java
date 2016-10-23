package com.danielflower.apprunner.router.mgmt;

import com.danielflower.apprunner.router.web.ProxyMap;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
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
    public List<JSONObject> loadAllApps(URI forwardedHost, List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException {
        List<JSONObject> results = new ArrayList<>();
        log.info("Looking up app info from " + runners);
        List<Future<JSONObject>> futures = new ArrayList<>();
        for (Runner runner : runners) {
            futures.add(executorService.submit(() -> loadRunner(forwardedHost, runner)));
        }
        for (Future<JSONObject> future : futures) {
            results.add(future.get(45, TimeUnit.SECONDS));
        }
        log.info("Got " + results.size() + " results");
        return results;
    }

    @Override
    public JSONObject loadRunner(URI forwardedHost, Runner runner) throws Exception {
        URI uri = runner.url.resolve("/api/v1/apps");
        ContentResponse resp = httpClient.newRequest(uri)
            .method(HttpMethod.GET)
            .header(HttpHeader.HOST, forwardedHost.getAuthority())
            .send();
        if (resp.getStatus() != 200) {
            throw new RuntimeException("Unable to load apps from " + uri + " - message was " + resp.getContentAsString());
        }
        JSONObject info = new JSONObject(resp.getContentAsString());
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
    public void removeRunner(Runner runner) {
        for (Map.Entry<String, URI> entry : proxyMap.getAll().entrySet()) {
            if (entry.getValue().getAuthority().equals(runner.url.getAuthority())) {
                proxyMap.remove(entry.getKey());
            }
        }
    }
}
