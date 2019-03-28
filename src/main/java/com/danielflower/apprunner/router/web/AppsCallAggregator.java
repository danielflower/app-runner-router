package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.MapManager;
import io.muserver.ContentTypes;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.RouteHandler;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ServerErrorException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class AppsCallAggregator implements RouteHandler {
    private static final Logger log = LoggerFactory.getLogger(AppsCallAggregator.class);
    private final MapManager mapManager;
    private final Cluster cluster;

    public AppsCallAggregator(MapManager mapManager, Cluster cluster) {
        this.mapManager = mapManager;
        this.cluster = cluster;
    }

    @Override
    public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
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

            response.status(200);
            response.contentType(ContentTypes.APPLICATION_JSON);
            response.write(all.toString(4));
        } catch (Exception e) {
            log.error("Error while aggregating the " + request + " call", e);
            throw new ServerErrorException("Error while aggregating the calls.", 502);
        }
    }
}
