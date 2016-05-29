package com.danielflower.apprunner.router.mgmt;

import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public interface MapManager {
    List<JSONObject> loadAll(List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException;

    JSONObject loadRunner(Runner runner) throws Exception;

    void removeRunner(Runner runner);
}
