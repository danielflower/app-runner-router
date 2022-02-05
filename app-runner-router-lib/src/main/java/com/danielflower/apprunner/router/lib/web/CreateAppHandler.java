package com.danielflower.apprunner.router.lib.web;

import com.danielflower.apprunner.router.lib.mgmt.Cluster;
import com.danielflower.apprunner.router.lib.mgmt.MapManager;
import com.danielflower.apprunner.router.lib.mgmt.Runner;
import io.muserver.*;
import io.muserver.murp.ReverseProxy;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ServerErrorException;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

public class CreateAppHandler implements RouteHandler {
    private static final Logger log = LoggerFactory.getLogger(CreateAppHandler.class);
    private final ProxyMap proxyMap;
    private final MapManager mapManager;
    private final Cluster cluster;
    private final HttpClient client;


    public CreateAppHandler(ProxyMap proxyMap, MapManager mapManager, Cluster cluster, HttpClient client) {
        this.proxyMap = proxyMap;
        this.mapManager = mapManager;
        this.cluster = cluster;
        this.client = client;
    }

    public static String getNameFromBody(String rawBody) {
        String name = null;
        String[] bits = rawBody.split("&");
        for (String encBit : bits) {
            String[] nvp = encBit.split("=");
            if (nvp[0].equals("appName") && nvp.length > 1) {
                name = Mutils.urlDecode(nvp[1]);
                break;
            } else if (nvp[0].equals("gitUrl")) {
                String temp = StringUtils.removeEndIgnoreCase(StringUtils.removeEnd(Mutils.urlDecode(nvp[1]), "/"), ".git");
                temp = temp.substring(Math.max(temp.lastIndexOf('/'), temp.lastIndexOf('\\')) + 1);
                name = temp;
            }
        }
        return name;
    }

    @Override
    public void handle(MuRequest request, MuResponse clientResp, Map<String, String> pathParams) {
        try {
            List<String> excludedRunnerIDs = new ArrayList<>(request.headers().getAll("X-Excluded-Runner"));
            String createBody = request.readBodyAsString();

            String nameFromBody = getNameFromBody(createBody);

            // This refreshes the proxyMap's view of which apps are on which runners
            mapManager.loadAllApps(request, cluster.getRunners());

            if (proxyMap.get(nameFromBody) != null) {
                log.info("Was asked to create " + nameFromBody + " but it is already an existing app");
                clientResp.status(409);
                clientResp.contentType(ContentTypes.APPLICATION_JSON);
                clientResp.write(new JSONObject()
                    .put("message", "There is already an app with that ID")
                    .toString(4));
                return;
            }

            log.info("Going to create " + nameFromBody);

            boolean finished = false;
            Set<CreationError> creationErrors = new HashSet<>();
            while (!finished) {
                Optional<Runner> optTargetRunner = cluster.allocateRunner(proxyMap.getAll(), excludedRunnerIDs);
                if (optTargetRunner.isPresent()) {
                    Runner targetRunner = optTargetRunner.get();
                    URI targetAppRunner = targetRunner.url.resolve("/api/v1/apps");
                    org.eclipse.jetty.client.api.Request creationReq = client.POST(targetAppRunner)
                        .content(new StringContentProvider(createBody));

                    ReverseProxy.setForwardedHeaders(request, creationReq, false, true);
                    creationReq.header("Accept", "*/*"); // for old apprunner instances
                    creationReq.header("Content-Type", request.headers().get("Content-Type"));

                    log.info("Sending " + creationReq.getMethod() + " " + creationReq.getURI() + " with " + creationReq.getHeaders() + " and body " + createBody);

                    ContentResponse creationResp;
                    String content;
                    try {
                        creationResp = creationReq.send();
                        content = creationResp.getContentAsString();
                        log.info("Received " + creationResp.getStatus() + " with headers " + creationResp.getHeaders() + " and content " + content);
                        if ((creationResp.getStatus() / 100) == 5) {
                            log.warn("Got a " + creationResp.getStatus() + " from " + targetRunner.id);
                            creationErrors.add(new CreationError(creationResp.getStatus(), content));
                            throw new TargetServerErrorException();
                        }
                        log.info("Proxying app creation with " + creationResp);

                    } catch (InterruptedException e) {
                        break;
                    } catch (Exception e) {
                        if (!(e instanceof TargetServerErrorException)) {
                            creationErrors.add(new CreationError(0, "Error talking to " + targetRunner.id + ": " + e));
                            log.warn("Error while calling POST " + targetAppRunner + " to create a new app. Will retry if" +
                                " there are more runners. Error was " + e.getClass().getName() + " " + e.getMessage());
                        }
                        targetRunner.refreshRunnerCountCache(proxyMap.getAll());
                        excludedRunnerIDs.add(targetRunner.id);
                        continue;
                    }
                    clientResp.status(creationResp.getStatus());
                    clientResp.headers().add("Content-Type", creationResp.getHeaders().get("Content-Type"));
                    if (creationResp.getStatus() == 201) {
                        log.info("Created new app: " + content);
                        JSONObject resp = new JSONObject(content);
                        String appName = resp.getString("name");
                        proxyMap.add(appName, targetAppRunner.resolve("/" + appName));
                    }
                    finished = true;
                    clientResp.write(content);

                } else {
                    finished = true;
                    if (creationErrors.size() == 1) {
                        CreationError creationError = creationErrors.stream().findAny().get();
                        clientResp.status(creationError.status);
                        clientResp.write(creationError.message);
                    } else {
                        String message = creationErrors.isEmpty()
                            ? "There are no App Runner instances with free capacity"
                            : "No available AppRunner instances available! Errors returned were: "
                            + creationErrors.stream().map(e -> e.message + " (" + e.status + ")").collect(Collectors.joining("; "));
                        log.error(message);
                        clientResp.status(503);
                        clientResp.write(message);
                    }
                }

            }
        } catch (Exception e) {
            String errorID = "ERR" + UUID.randomUUID();
            log.error("Error creating an app. Error ID=" + errorID, e);
            throw new ServerErrorException("Error while creating app. Error ID=" + errorID, 502);
        }
    }

    private static class CreationError {
        public final int status;
        public final String message;

        private CreationError(int status, String message) {
            this.status = status;
            this.message = message;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            CreationError that = (CreationError) o;
            if (status != that.status) return false;
            return message.equals(that.message);
        }

        @Override
        public int hashCode() {
            int result = status;
            result = 31 * result + message.hashCode();
            return result;
        }
    }

    private static final class TargetServerErrorException extends RuntimeException {
    }

}
