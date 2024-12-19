package com.danielflower.apprunner.router.lib.web;

import com.danielflower.apprunner.router.lib.mgmt.Cluster;
import com.danielflower.apprunner.router.lib.mgmt.MapManager;
import io.muserver.*;
import io.muserver.rest.CORSConfig;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.ServerErrorException;
import java.util.*;

import static java.util.Arrays.asList;

public class AppsCallAggregator implements RouteHandler {
    private static final Logger log = LoggerFactory.getLogger(AppsCallAggregator.class);
    private final MapManager mapManager;
    private final Cluster cluster;
    private final CORSConfig corsConfig;

    public AppsCallAggregator(MapManager mapManager, Cluster cluster, CORSConfig corsConfig) {
        this.mapManager = mapManager;
        this.cluster = cluster;
        this.corsConfig = corsConfig;
    }

    @Override
    public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
        try {
            corsConfig.writeHeaders(request, response, new HashSet<>(asList(Method.GET, Method.POST, Method.PUT, Method.DELETE)));

            MapManager.Result results = mapManager.loadAllApps(request, cluster.getRunners());
            JSONObject all = new JSONObject();
            List<JSONObject> unsorted = new ArrayList<>();

            for (JSONObject result : results.appsJsonFromEachRunner) {
                JSONArray singleApps = result.getJSONArray("apps");
                for (Object singleApp : singleApps) {
                    JSONObject appJson = (JSONObject) singleApp;
                    appJson.put("appRunnerInstanceId", result.getString("appRunnerInstanceId"));
                    unsorted.add(appJson);
                }
            }
            unsorted.sort(Comparator.comparing(o -> o.getString("name").toLowerCase()));
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

            response.status(200);
            response.contentType(ContentTypes.APPLICATION_JSON);
            response.write(all.toString(4));
        } catch (Exception e) {
            log.error("Error while aggregating the " + request + " call", e);
            throw new ServerErrorException("Error while aggregating the calls.", 502);
        }
    }
}
