package com.danielflower.apprunner.router.mgmt;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Cluster {

    private final File config;
    private final List<Runner> runners = new CopyOnWriteArrayList<>();

    private Cluster(File config, List<Runner> runners) {
        this.config = config;
        this.runners.addAll(runners);
    }

    public static Cluster load(File config) throws IOException {
        ArrayList<Runner> runners = new ArrayList<>();
        if (config.exists()) {
            JSONObject json = new JSONObject(FileUtils.readFileToString(config));
            for (Object o : json.getJSONArray("runners")) {
                runners.add(Runner.fromJSON((JSONObject) o));
            }
        } else {
            config.getParentFile().mkdirs();
            config.createNewFile();
        }
        return new Cluster(config, runners);
    }

    public List<Runner> getRunners() {
        return runners;
    }

    public synchronized void addRunner(Runner runner) throws IOException {
        if (!runners.contains(runner)) {
            runners.add(runner);
        }
        save();
    }

    public synchronized void deleteRunner(Runner runner) throws IOException {
        runners.remove(runner);
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

    public Optional<Runner> allocateRunner(ConcurrentHashMap<String, URL> currentMapping) {
        Runner leastContended = null;
        for (Runner runner : runners) {
            int num = (int)currentMapping.values().stream()
                .filter(url ->  url.getAuthority().equals(runner.url.getAuthority()))
                .count();
            runner.setNumberOfApps(num);
            if (leastContended == null || leastContended.getNumberOfApps() > num) {
                leastContended = runner;
            }
        }
        return Optional.ofNullable(leastContended);
    }
}
