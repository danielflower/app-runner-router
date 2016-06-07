package com.danielflower.apprunner.router.mgmt;

import org.json.JSONObject;

import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public interface MapManager {
    List<JSONObject> loadAllApps(URI forwardedHost, List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException;

    JSONObject loadRunner(URI forwardedHost, Runner runner) throws Exception;

    void removeRunner(Runner runner);
}
