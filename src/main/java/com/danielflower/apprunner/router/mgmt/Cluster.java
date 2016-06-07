package com.danielflower.apprunner.router.mgmt;

import com.danielflower.apprunner.router.web.ProxyMap;
import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Cluster {

    private final File config;
    private final List<Runner> runners = new CopyOnWriteArrayList<>();
    private final MapManager querier;

    private Cluster(File config, MapManager querier, List<Runner> runners) {
        this.config = config;
        this.querier = querier;
        this.runners.addAll(runners);
    }

    public static Cluster load(File config, MapManager mapManager) throws IOException {
        ArrayList<Runner> runners = new ArrayList<>();
        boolean isNew = !config.exists();
        if (config.exists()) {
            JSONObject json = new JSONObject(FileUtils.readFileToString(config));
            for (Object o : json.getJSONArray("runners")) {
                runners.add(Runner.fromJSON((JSONObject) o));
            }
        } else {
            config.getParentFile().mkdirs();
            config.createNewFile();
        }
        Cluster cluster = new Cluster(config, mapManager, runners);
        if (isNew) {
            cluster.save();
        }
        return cluster;
    }

    public List<Runner> getRunners() {
        return runners;
    }

    public synchronized void addRunner(URI forwardedForHost, Runner runner) throws Exception {
        if (!runners.contains(runner)) {
            runners.add(runner);
        }
        querier.loadRunner(forwardedForHost, runner);
        save();
    }

    public synchronized void deleteRunner(Runner runner) throws IOException {
        runners.remove(runner);
        querier.removeRunner(runner);
        save();
    }

    public void save() throws IOException {
        FileUtils.write(config, toJSON().toString(4), "UTF-8", false);
    }

    public JSONObject toJSON() {
        JSONArray all = new JSONArray();
        for (Runner runner : runners) {
            all.put(runner.toJSON());
        }
        return new JSONObject()
            .put("runners", all);
    }

    public Optional<Runner> runner(String id) {
        return runners.stream()
            .filter(runner -> runner.id.equals(id))
            .findFirst();
    }

    public Optional<Runner> allocateRunner(ConcurrentHashMap<String, URI> currentMapping) {
        Runner leastContended = null;
        for (Runner runner : runners) {
            if (!runner.hasCapacity()) {
                continue;
            }
            int num = (int)currentMapping.values().stream()
                .filter(url ->  url.getAuthority().equals(runner.url.getAuthority()))
                .count();
            runner.numberOfApps.set(num);
            if (leastContended == null || leastContended.numberOfApps.get() > num) {
                leastContended = runner;
            }
        }
        if (leastContended != null) {
            leastContended.numberOfApps.incrementAndGet();
        }
        return Optional.ofNullable(leastContended);
    }

    public Optional<Runner> getRunnerByURL(URI url) {
        for (Runner runner : runners) {
            if (runner.url.getAuthority().equals(url.getAuthority())) {
                return Optional.of(runner);
            }
        }
        return Optional.empty();
    }

    public void updateProxyMap(Runner runner, ProxyMap proxyMap) {

    }
}
