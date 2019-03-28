package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.App;
import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import io.muserver.HeaderNames;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.RouteHandler;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ServerErrorException;
import java.net.URI;
import java.util.*;

public class CreateAppHandler implements RouteHandler {
    private static final Logger log = LoggerFactory.getLogger(CreateAppHandler.class);
    private final ProxyMap proxyMap;
    private final Cluster cluster;
    private final HttpClient client;


    public CreateAppHandler(ProxyMap proxyMap, Cluster cluster, HttpClient client) {
        this.proxyMap = proxyMap;
        this.cluster = cluster;
        this.client = client;
    }

    @Override
    public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) {
        try {
            log.info("Going to create an app");

            List<String> excludedRunnerIDs = new ArrayList<>(request.headers().getAll("X-Excluded-Runner"));
            String createBody = request.readBodyAsString();

            boolean finished = false;
            while (!finished) {
                Optional<Runner> targetRunner = cluster.allocateRunner(proxyMap.getAll(), excludedRunnerIDs);
                if (targetRunner.isPresent()) {
                    URI targetAppRunner = targetRunner.get().url.resolve("/api/v1/apps");
                    org.eclipse.jetty.client.api.Request creationReq = client.POST(targetAppRunner)
                        .content(new StringContentProvider(createBody));

                    io.muserver.murp.ReverseProxy.setRequestHeaders(request, creationReq, false, true, App.VIA_VALUE);

                    ContentResponse creationResp;
                    try {
                        creationResp = creationReq.send();
                        if ((creationResp.getStatus() / 100) == 5) {
                            String contentAsString = creationResp.getContentAsString();
                            throw new RuntimeException("Got a " + creationResp.getStatus() + " response - " + contentAsString);
                        }
                        log.info("Proxying app creation with " + creationResp);

                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        targetRunner.get().refreshRunnerCountCache(proxyMap.getAll());
                        excludedRunnerIDs.add(targetRunner.get().id);
                        log.warn("Error while calling POST " + targetAppRunner + " to create a new app. Will retry if" +
                            " there are more runners. Error was " + e.getClass().getName() + " " + e.getMessage());
                        continue;
                    }
                    response.status(creationResp.getStatus());
                    response.headers().remove(HeaderNames.DATE);

                    Set<String> hopHeaders = io.muserver.murp.ReverseProxy.HOP_BY_HOP_HEADERS;
                    for (HttpField header : creationResp.getHeaders()) {
                        String hn = header.getName().toLowerCase();
                        if (!hopHeaders.contains(hn) && !hn.equals("content-encoding")) {
                            response.headers().add(header.getName(), header.getValue());
                        }
                    }
                    String content = creationResp.getContentAsString();
                    if (creationResp.getStatus() == 201) {
                        log.info("Created new app: " + content);
                        JSONObject resp = new JSONObject(content);
                        String appName = resp.getString("name");
                        proxyMap.add(appName, targetAppRunner.resolve("/" + appName));
                    }
                    finished = true;
                    response.write(content);

                } else {
                    finished = true;
                    log.error("There are no app runner instances available! Add another instance or change the maxApps value of an existing one.");
                    response.status(503);
                    response.write("There are no App Runner instances with free capacity");
                }
            }
        } catch (Exception e) {
            String errorID = "ERR" + UUID.randomUUID();
            log.error("Error creating an app. Error ID=" + errorID, e);
            throw new ServerErrorException("Error while creating app. Error ID=" + errorID, 502);
        }
    }
}
