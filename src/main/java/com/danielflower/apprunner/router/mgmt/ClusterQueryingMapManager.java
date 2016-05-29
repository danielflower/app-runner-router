package com.danielflower.apprunner.router.mgmt;

import com.danielflower.apprunner.router.web.ProxyMap;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ClusterQueryingMapManager implements MapManager {
    private static final Logger log = LoggerFactory.getLogger(ClusterQueryingMapManager.class);

    private final ProxyMap proxyMap;
    private final HttpClient httpClient;

    public ClusterQueryingMapManager(ProxyMap proxyMap, HttpClient httpClient) {
        this.proxyMap = proxyMap;
        this.httpClient = httpClient;
    }

    public static MapManager create(ProxyMap proxyMap) {
        HttpClient httpClient = new HttpClient(new SslContextFactory(true));
        try {
            httpClient.start();
        } catch (Exception e) {
            throw new RuntimeException("Could not start http client", e);
        }
        return new ClusterQueryingMapManager(proxyMap, httpClient);
    }

    @Override
    public void loadAll(List<Runner> runners) throws InterruptedException {
        if (runners.size() > 0) {
            log.info("Looking up app info from " + runners);
            ExecutorService executorService = Executors.newFixedThreadPool(runners.size());
            for (Runner runner : runners) {
                executorService.submit(() -> {
                    try {
                        loadRunner(runner);
                    } catch (Exception e) {
                        throw new RuntimeException("Error while loading " + runner.url, e);
                    }
                });
            }
            executorService.shutdown();
            boolean finished = executorService.awaitTermination(30, TimeUnit.SECONDS);
            if (finished) {
                log.info("Lookups complete.");
            } else {
                log.info("Timed out waiting for all the stuff to load");
            }
        }
    }

    @Override
    public void loadRunner(Runner runner) throws Exception {
        URI uri = runner.url.resolve("/api/v1/apps");
        ContentResponse resp = httpClient.GET(uri);
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
