package com.danielflower.apprunner.router.mgmt;

import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

public interface MapManager {
    List<JSONObject> loadAllApps(HttpServletRequest clientRequest, List<Runner> runners) throws InterruptedException, TimeoutException, ExecutionException;

    JSONObject loadRunner(HttpServletRequest clientRequest, Runner runner) throws Exception;

    void removeRunner(Runner runner);
}
