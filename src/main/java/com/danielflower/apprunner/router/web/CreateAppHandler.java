package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

class CreateAppHandler extends AbstractHandler {
    private static final Logger log = LoggerFactory.getLogger(CreateAppHandler.class);
    private final ProxyMap proxyMap;
    private final Cluster cluster;
    private final HttpClient client;
    private final ReverseProxy reverseProxy;


    public CreateAppHandler(ProxyMap proxyMap, Cluster cluster, HttpClient client, ReverseProxy reverseProxy) {
        this.proxyMap = proxyMap;
        this.cluster = cluster;
        this.client = client;
        this.reverseProxy = reverseProxy;
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (!canHandle(target, request)) {
            return;
        }
        try {
            log.info("Going to create an app");

            List<String> excludedRunnerIDs = Collections.list(request.getHeaders("X-Excluded-Runner"));
            String createBody = IOUtils.toString(request.getInputStream(), StandardCharsets.UTF_8);

            boolean finished = false;
            while (!finished) {
                Optional<Runner> targetRunner = cluster.allocateRunner(proxyMap.getAll(), excludedRunnerIDs);
                if (targetRunner.isPresent()) {
                    URI targetAppRunner = targetRunner.get().url.resolve(target);
                    org.eclipse.jetty.client.api.Request creationReq = client.POST(targetAppRunner)
                        .content(new StringContentProvider(createBody));

                    reverseProxy.copyRequestHeadersForProxying(request, creationReq);

                    ContentResponse creationResp;
                    try {
                        creationResp = creationReq.send();
                        if ((creationResp.getStatus() / 100) == 5) {
                            throw new RuntimeException("Got a " + creationResp.getStatus() + " response - " + creationResp.getContentAsString());
                        }
                        log.info("Proxying app creation with " + creationResp);

                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        targetRunner.get().refreshRunnerCountCache(proxyMap.getAll());
                        excludedRunnerIDs.add(targetRunner.get().id);
                        log.warn("Error while calling POST " + targetAppRunner + " to create a new app. Will retry if there are more runners. Error was " + e.getClass().getName() + " " + e.getMessage());
                        continue;
                    }
                    response.setStatus(creationResp.getStatus());

                    Set<String> hopHeaders = ReverseProxy.hopByHopHeaders();
                    for (HttpField header : creationResp.getHeaders()) {
                        String hn = header.getName().toLowerCase();
                        if (!hopHeaders.contains(hn) && !hn.equals("content-encoding")) {
                            response.setHeader(header.getName(), header.getValue());
                        }
                    }
                    try (PrintWriter writer = response.getWriter()) {
                        String content = creationResp.getContentAsString();
                        if (creationResp.getStatus() == 201) {
                            log.info("Created new app: " + content);
                            JSONObject resp = new JSONObject(content);
                            String appName = resp.getString("name");
                            proxyMap.add(appName, targetAppRunner.resolve("/" + appName));
                        }
                        finished = true;
                        writer.write(content);
                    }

                } else {
                    finished = true;
                    log.error("There are no app runner instances available! Add another instance or change the maxApps value of an existing one.");
                    response.sendError(503, "There are no App Runner instances with free capacity");
                }
            }
        } catch (Exception e) {
            String errorID = "ERR" + UUID.randomUUID();
            log.error("Error creating an app. Error ID=" + errorID, e);
            response.sendError(502, "Error while creating app. Error ID=" + errorID);
        }
        baseRequest.setHandled(true);
    }

    public static boolean canHandle(String target, HttpServletRequest request) {
        return "/api/v1/apps".equals(target) && request.getMethod().equals("POST");
    }
}