package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import com.danielflower.apprunner.router.monitoring.AppRequestListener;
import com.danielflower.apprunner.router.monitoring.RequestInfo;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.*;
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
    private final AppRequestListener appRequestListener;

    public ReverseProxy(Cluster cluster, ProxyMap proxyMap, boolean allowUntrustedInstances, AppRequestListener appRequestListener) {
        this.cluster = cluster;
        this.proxyMap = proxyMap;
        this.allowUntrustedInstances = allowUntrustedInstances;
        this.appRequestListener = appRequestListener;
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
        RequestInfo requestInfo = attachInfoForMonitoring(clientRequest);

        String uri = clientRequest.getRequestURI();
        String query = isEmpty(clientRequest.getQueryString()) ? "" : "?" + clientRequest.getQueryString();

        requestInfo.url = clientRequest.getRequestURL().append(query).toString();

        if (log.isDebugEnabled()) log.debug(clientRequest.getMethod() + " " + uri);

        if (uri.startsWith("/api/")) {
            URI target = apiTargetUri(clientRequest, uri, query);
            requestInfo.appName = "api";
            if (target != null) {
                String targetString = target.toString();
                requestInfo.targetHost = target.getAuthority();
                log.info("Proxying to " + targetString);
                return targetString;
            }
        } else {
            Matcher appMatcher = APP_WEB_REQUEST.matcher(uri);
            if (appMatcher.matches()) {
                String prefix = appMatcher.group(1);
                requestInfo.appName = prefix;
                URI url = proxyMap.get(prefix);
                if (url != null) {
                    requestInfo.targetHost = url.getAuthority();
                    String newTarget = url.toString() + appMatcher.group(2) + query;
                    log.info("Proxying to " + newTarget);
                    return newTarget;
                }
            }
        }

        log.info("No proxy target configured for " + uri);
        return null;
    }

    private URI apiTargetUri(HttpServletRequest clientRequest, String uri, String query) {
        if (isAppCreationOrUpdatePost(clientRequest)) {
            List<String> excludedRunnerIDs = Collections.list(clientRequest.getHeaders("X-Excluded-Runner"));
            Optional<Runner> targetRunner = cluster.allocateRunner(proxyMap.getAll(), excludedRunnerIDs);
            if (targetRunner.isPresent()) {
                URI targetAppRunner = targetRunner.get().url;
                return targetAppRunner.resolve(uri + query);
            } else {
                log.error("There are no app runner instances available! Add another instance or change the maxApps value of an existing one.");
                return null;
            }
        } else if (uri.equals("/api/v1/swagger.json") || uri.startsWith("/api/v1/system")) {
            List<Runner> runners = cluster.getRunners();
            if (runners.size() > 0) {
                return runners.get(0).url.resolve(uri);
            }
        } else {
            Matcher appMatcher = APP_API_REQUEST.matcher(uri);
            if (appMatcher.matches()) {
                String appName = appMatcher.group(1);
                URI url = proxyMap.get(appName);
                if (url != null) {
                    return url.resolve(uri + query);
                }
            }
        }
        return null;
    }

    private static RequestInfo attachInfoForMonitoring(HttpServletRequest clientRequest) {
        RequestInfo info = new RequestInfo();
        info.startTime = System.currentTimeMillis();
        info.remoteAddr = clientRequest.getRemoteAddr();
        info.method = clientRequest.getMethod();
        clientRequest.setAttribute("com.danielflower.apprunner.router.monitoring.RequestInfo", info);
        return info;
    }
    static RequestInfo getInfo(HttpServletRequest request) {
        return (RequestInfo) request.getAttribute("com.danielflower.apprunner.router.monitoring.RequestInfo");
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

    @Override
    protected void onProxyResponseSuccess(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse) {
        super.onProxyResponseSuccess(clientRequest, proxyResponse, serverResponse);
        RequestInfo info = getInfo(clientRequest);
        info.endTime = System.currentTimeMillis();
        info.responseStatus = serverResponse.getStatus();
        if (appRequestListener != null) appRequestListener.onRequestComplete(info);
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
        try {
            if (!proxyResponse.isCommitted()) {
                proxyResponse.resetBuffer();
                proxyResponse.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
            }
            proxyResponse.sendError(status, message);
        } catch(Exception e) {
            _log.ignore(e);
        } finally {
            if (clientRequest.isAsyncStarted())
                clientRequest.getAsyncContext().complete();
        }

    }

    @Override
    protected HttpClient newHttpClient() {
        return new HttpClient(new SslContextFactory(allowUntrustedInstances));
    }
}
