package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import com.danielflower.apprunner.router.monitoring.AppRequestListener;
import com.danielflower.apprunner.router.monitoring.RequestInfo;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.isEmpty;

public class ReverseProxy extends AsyncProxyServlet {
    public static final Logger log = LoggerFactory.getLogger(ReverseProxy.class);

    public static Set<String> hopByHopHeaders() {
        return HOP_HEADERS;
    }

    public void copyRequestHeadersForProxying(HttpServletRequest clientRequest, Request proxyRequest) {
        copyRequestHeaders(clientRequest, proxyRequest);
        addProxyHeaders(clientRequest, proxyRequest);
    }

    private static final Pattern APP_WEB_REQUEST = Pattern.compile("/([^/?]+)(.*)");
    private static final Pattern APP_API_REQUEST = Pattern.compile("/api/v1/apps/([^/?]+)(.*)");
    private static final String REQUEST_INFO_NAME = "com.danielflower.apprunner.router.monitoring.RequestInfo";

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
            URI target = apiTargetUri(uri, query);
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

    private URI apiTargetUri(String uri, String query) {
        if (uri.equals("/api/v1/swagger.json") || uri.startsWith("/api/v1/system")) {
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
        clientRequest.setAttribute(REQUEST_INFO_NAME, info);
        return info;
    }
    static RequestInfo getInfo(HttpServletRequest request) {
        return (RequestInfo) request.getAttribute(REQUEST_INFO_NAME);
    }

    @Override
    protected void onServerResponseHeaders(HttpServletRequest clientRequest, HttpServletResponse proxyResponse, Response serverResponse) {
        super.onServerResponseHeaders(clientRequest, proxyResponse, serverResponse);
        if (isAppDeletionPost(clientRequest) && proxyResponse.getStatus() == 200) {
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
        if (appRequestListener != null) {
            RequestInfo info = getInfo(clientRequest);
            if (info != null) {
                // This seems to be null now. Not sure why. Will fix by migrating off Jetty.
                info.endTime = System.currentTimeMillis();
                info.responseStatus = serverResponse.getStatus();
                appRequestListener.onRequestComplete(info);
            }
        }
    }

    private static boolean isAppDeletionPost(HttpServletRequest clientRequest) {
        String uri = clientRequest.getRequestURI();
        String prefix = "/api/v1/apps/";
        return clientRequest.getMethod().toUpperCase().equals("DELETE")
            && uri.startsWith(prefix) && uri.lastIndexOf('/') == (prefix.length() - 1);
    }

    protected void onProxyRewriteFailed(HttpServletRequest clientRequest, HttpServletResponse proxyResponse) {
        // this is called if rewriteTarget returns null
        try {
            if (!proxyResponse.isCommitted()) {
                proxyResponse.resetBuffer();
                proxyResponse.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
            }
            proxyResponse.sendError(404, "404 Not Found");
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
