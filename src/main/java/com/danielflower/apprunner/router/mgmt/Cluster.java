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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.danielflower.apprunner.router.Config.dirPath;

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
            JSONObject json = new JSONObject(FileUtils.readFileToString(config));
            for (Object o : json.getJSONArray("runners")) {
                runners.add(Runner.fromJSON((JSONObject) o));
            }
        } else {
            if (!config.getParentFile().mkdirs() || !config.createNewFile()) {
                log.warn("Couldn't create " + dirPath(config) + " apparently, which is a bit worrying but maybe it's okay?");
            }
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

    public synchronized Optional<Runner> allocateRunner(ConcurrentHashMap<String, URI> currentMapping) {
        Runner leastContended = null;
        for (Runner runner : runners) {
            int num = updateNumberOfAppsForRunner(currentMapping, runner);
            if (!runner.hasCapacity()) {
                continue;
            }
            if (leastContended == null || leastContended.numberOfApps.get() > num) {
                leastContended = runner;
            }
        }
        if (leastContended != null) {
            log.info("Incrementing app count for " + leastContended.id + " because apparently it is the least contended with "
                + leastContended.numberOfApps + " apps (with max capacity of " + leastContended.maxApps + "). The full mapping is: " + currentMapping);
            leastContended.numberOfApps.incrementAndGet();
            return Optional.of(leastContended);
        }
        log.info("Could not allocate a runner because it seems there is no capacity. Current mapping: " + currentMapping);
        return Optional.empty();
    }

    private static int updateNumberOfAppsForRunner(ConcurrentHashMap<String, URI> currentMapping, Runner runner) {
        int num = (int)currentMapping.values().stream()
            .filter(url ->  url.getAuthority().equals(runner.url.getAuthority()))
            .count();
        runner.numberOfApps.set(num);
        return num;
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
