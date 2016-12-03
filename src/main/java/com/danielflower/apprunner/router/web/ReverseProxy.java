package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.isEmpty;

public class ReverseProxy extends AsyncProxyServlet {
    public static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);

    private static final Pattern APP_WEB_REQUEST = Pattern.compile("/([^/?]+)(.*)");
    private static final Pattern APP_API_REQUEST = Pattern.compile("/api/v1/apps/([^/?]+)(.*)");

    private final ProxyMap proxyMap;
    private final Cluster cluster;
    private final boolean allowUntrustedInstances;

    public ReverseProxy(Cluster cluster, ProxyMap proxyMap, boolean allowUntrustedInstances) {
        this.cluster = cluster;
        this.proxyMap = proxyMap;
        this.allowUntrustedInstances = allowUntrustedInstances;
    }

    protected String filterServerResponseHeader(HttpServletRequest clientRequest, Response serverResponse, String headerName, String headerValue) {
        if (headerName.equalsIgnoreCase("location")) {
            URI targetUri = serverResponse.getRequest().getURI();
            String toReplace = targetUri.getScheme() + "://" + targetUri.getAuthority();
            if (headerValue.startsWith(toReplace)) {
                headerValue = clientRequest.getScheme() + "://" + clientRequest.getHeader("host")
                    + headerValue.substring(toReplace.length());
                log.info("Rewrote location header to " + headerValue);
                return headerValue;
            }
        }
        return super.filterServerResponseHeader(clientRequest, serverResponse, headerName, headerValue);
    }

    protected String rewriteTarget(HttpServletRequest clientRequest) {
        String uri = clientRequest.getRequestURI();
        String query = isEmpty(clientRequest.getQueryString()) ? "" : "?" + clientRequest.getQueryString();

        log.debug(clientRequest.getMethod() + " " + uri);
        if (uri.startsWith("/api/")) {
            if (isAppCreationOrUpdatePost(clientRequest)) {
                Optional<Runner> targetRunner = cluster.allocateRunner(proxyMap.getAll());
                if (targetRunner.isPresent()) {
                    URI targetAppRunner = targetRunner.get().url;
                    return targetAppRunner.resolve(uri) + query;
                } else {
                    log.error("There are no app runner instances available! Add another instance or change the maxApps value of an existing one.");
                    return null;
                }
            } else if (uri.equals("/api/v1/swagger.json") || uri.startsWith("/api/v1/system")) {
                List<Runner> runners = cluster.getRunners();
                if (runners.size() > 0) {
                    return runners.get(0).url.resolve(uri).toString();
                }
            } else {
                Matcher appMatcher = APP_API_REQUEST.matcher(uri);
                if (appMatcher.matches()) {
                    String appName = appMatcher.group(1);
                    URI url = proxyMap.get(appName);
                    if (url != null) {
                        String newTarget = url.resolve(uri + query).toString();
                        log.info("Proxying to " + newTarget);
                        return newTarget;
                    }
                }
            }
        } else {
            Matcher appMatcher = APP_WEB_REQUEST.matcher(uri);
            if (appMatcher.matches()) {
                String prefix = appMatcher.group(1);
                URI url = proxyMap.get(prefix);
                if (url != null) {
                    String newTarget = url.toString() + appMatcher.group(2) + query;
                    log.info("Proxying to " + newTarget);
                    return newTarget;
                }
            }
        }

        log.info("No proxy target configured for " + uri);
        return null;
    }

    @Override
    protected void onServerResponseHeaders(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse) {
        super.onServerResponseHeaders(clientRequest, proxyResponse, serverResponse);
        if (isAppCreationOrUpdatePost(clientRequest)) {
            URI runnerURI = serverResponse.getRequest().getURI();
            String locationHeader = proxyResponse.getHeader("Location");
            if (proxyResponse.getStatus() >= 200 && proxyResponse.getStatus() <= 299) {
                String appName = locationHeader.substring(locationHeader.lastIndexOf("/") + 1);
                URI targetAppRunnerURI = runnerURI.resolve("/" + appName);
                proxyMap.add(appName, targetAppRunnerURI);
            } else {
                cluster.getRunnerByURL(runnerURI).ifPresent(runner -> {
                    log.info("Decrementing app count for " + runner.id + " because " + locationHeader + " was deleted.");
                    runner.refreshRunnerCountCache(proxyMap.getAll());
                });
            }
        } else if (isAppDeletionPost(clientRequest) && proxyResponse.getStatus() == 200) {
            URI runnerURI = serverResponse.getRequest().getURI();
            String appName = clientRequest.getRequestURI().substring(clientRequest.getRequestURI().lastIndexOf('/') + 1);
            proxyMap.remove(appName);
            cluster.getRunnerByURL(runnerURI).ifPresent(runner -> {
                log.info("Decrementing app count for " + runner.id + " because " + appName + " was deleted.");
                runner.refreshRunnerCountCache(proxyMap.getAll());
            });
        }
    }

    private static boolean isAppCreationOrUpdatePost(HttpServletRequest clientRequest) {
        // Don't know if it's a creation or update, and can't access the form params to get the app name as it will cause the proxy servlet to crash.
        // So, if someone POSTs with an existing app ID, it will either update if it happens to hit the existing server it's on,
        // otherwise it will result in two instances running. This is undefined behaviour as the router doesn't really
        return clientRequest.getMethod().toUpperCase().equals("POST") && clientRequest.getRequestURI().equals("/api/v1/apps");
    }

    private static boolean isAppDeletionPost(HttpServletRequest clientRequest) {
        String uri = clientRequest.getRequestURI();
        String prefix = "/api/v1/apps/";
        return clientRequest.getMethod().toUpperCase().equals("DELETE")
            && uri.startsWith(prefix) && uri.lastIndexOf('/') == (prefix.length() - 1);
    }

    protected void onProxyRewriteFailed(HttpServletRequest clientRequest, HttpServletResponse proxyResponse) {
        // this is called if rewriteTarget returns null
        int status;
        String message;
        if (isAppCreationOrUpdatePost(clientRequest)) {
            status = 503;
            message = "There are no App Runner instances with free capacity";
        } else {
            status = 404;
            message = "404 Not Found";
        }
        sendProxyResponseError(clientRequest, proxyResponse, status);
        try {
            proxyResponse.getWriter().write(message);
        } catch (IOException e) {
            log.info("Could not write error", e);
        }
    }

    @Override
    protected HttpClient newHttpClient() {
        return new HttpClient(new SslContextFactory(allowUntrustedInstances));
    }
}
