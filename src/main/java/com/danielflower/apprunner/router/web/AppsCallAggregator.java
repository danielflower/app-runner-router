package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.MapManager;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class AppsCallAggregator extends AbstractHandler {
    private static final Logger log = LoggerFactory.getLogger(AppsCallAggregator.class);
    private final MapManager mapManager;
    private final Cluster cluster;

    public AppsCallAggregator(MapManager mapManager, Cluster cluster) {
        this.mapManager = mapManager;
        this.cluster = cluster;
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (canHandle(target, request)) {
            try {
                MapManager.Result results = mapManager.loadAllApps(request, cluster.getRunners());
                JSONObject all = new JSONObject();
                List<JSONObject> unsorted = new ArrayList<>();

                for (JSONObject result : results.appsJsonFromEachRunner) {
                    JSONArray singleApps = result.getJSONArray("apps");
                    for (Object singleApp : singleApps) {
                        unsorted.add((JSONObject) singleApp);
                    }
                }
                unsorted.sort(Comparator.comparing(o -> o.getString("name")));
                JSONArray apps = new JSONArray();
                for (JSONObject jsonObject : unsorted) {
                    apps.put(jsonObject);
                }
                all.put("appCount", apps.length());
                all.put("apps", apps);

                JSONArray errors = new JSONArray();
                for (String error : results.errors) {
                    errors.put(error);
                }
                all.put("errors", errors);

                response.setStatus(200);
                response.setHeader("Content-Type", "application/json");
                response.getWriter().append(all.toString(4)).close();
            } catch (Exception e) {
                log.error("Error while aggregating the " + target + " call", e);
                response.sendError(502, "Error while aggregating the calls.");
            }
            baseRequest.setHandled(true);
        }
    }

    public static boolean canHandle(String target, HttpServletRequest request) {
        return "/api/v1/apps".equals(target) && request.getMethod().equals("GET");
    }
}
