package com.danielflower.apprunner.router.mgmt;

import io.muserver.MuRequest;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public interface MapManager {
    ConcurrentHashMap<String, URI> getCurrentMapping();

    Result loadAllApps(MuRequest clientRequest, List<Runner> runners) throws InterruptedException;

    JSONObject loadRunner(MuRequest clientRequest, Runner runner) throws Exception;

    JSONObject loadRunnerSystemInfo(MuRequest clientRequest, Runner runner) throws Exception;

    void removeRunner(Runner runner);

    class Result {
        public final List<JSONObject> appsJsonFromEachRunner = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }
}
