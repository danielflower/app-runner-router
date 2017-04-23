package com.danielflower.apprunner.router.mgmt;

import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

public interface MapManager {
    Result loadAllApps(HttpServletRequest clientRequest, List<Runner> runners) throws InterruptedException;

    JSONObject loadRunner(HttpServletRequest clientRequest, Runner runner) throws Exception;

    void removeRunner(Runner runner);

    public static class Result {
        public final List<JSONObject> appsJsonFromEachRunner = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
    }
}
