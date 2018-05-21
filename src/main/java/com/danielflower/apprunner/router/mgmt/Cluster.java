package com.danielflower.apprunner.router.mgmt;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Cluster {
    private static final Logger log = LoggerFactory.getLogger(Cluster.class);

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
            JSONObject json = new JSONObject(FileUtils.readFileToString(config, StandardCharsets.UTF_8));
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

    public synchronized void addRunner(HttpServletRequest clientRequest, Runner runner) throws Exception {
        if (!runners.contains(runner)) {
            runners.add(runner);
        }
        querier.loadRunner(clientRequest, runner);
        refreshRunnerCountCache(querier.getCurrentMapping());
        save();
    }

    public synchronized void deleteRunner(Runner runner) throws IOException {
        runners.remove(runner);
        querier.removeRunner(runner);
        save();
    }

    private void save() throws IOException {
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

    public synchronized Optional<Runner> allocateRunner(ConcurrentHashMap<String, URI> currentMapping, Collection<String> excludedRunnerIDs) {
        refreshRunnerCountCache(currentMapping);
        Runner leastContended = null;
        for (Runner runner : runners) {
            if (!runner.hasCapacity()) {
                continue;
            }
            if (leastContended == null || leastContended.numberOfApps() > runner.numberOfApps()) {
                boolean isBanned = excludedRunnerIDs.contains(runner.id);
                if (isBanned) {
                    log.info("Not allocating to " + runner.id + " because it was requested to be excluded.");
                } else {
                    leastContended = runner;
                }
            }
        }
        if (leastContended != null) {
            log.info("Incrementing app count for " + leastContended.id + " because apparently it is the least contended with "
                + leastContended.numberOfApps() + " apps (with max capacity of " + leastContended.maxApps + "). The full mapping is: " + currentMapping);
            leastContended.incrementNumberOfApps();
            return Optional.of(leastContended);
        }
        log.info("Could not allocate a runner because it seems there is no capacity. Current mapping: " + currentMapping);
        return Optional.empty();
    }

    public void refreshRunnerCountCache(ConcurrentHashMap<String, URI> currentMapping) {
        for (Runner runner : runners) {
            runner.refreshRunnerCountCache(currentMapping);
        }
    }

    public Optional<Runner> getRunnerByURL(URI url) {
        for (Runner runner : runners) {
            if (runner.url.getAuthority().equals(url.getAuthority())) {
                return Optional.of(runner);
            }
        }
        return Optional.empty();
    }

}
