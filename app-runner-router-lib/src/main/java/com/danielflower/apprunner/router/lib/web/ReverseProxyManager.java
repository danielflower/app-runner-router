package com.danielflower.apprunner.router.lib.web;

import com.danielflower.apprunner.router.lib.mgmt.Cluster;
import com.danielflower.apprunner.router.lib.mgmt.Runner;
import com.danielflower.apprunner.router.lib.monitoring.AppRequestListener;
import com.danielflower.apprunner.router.lib.monitoring.RequestInfo;
import io.muserver.Method;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.Mutils;
import io.muserver.murp.ProxyCompleteListener;
import io.muserver.murp.UriMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReverseProxyManager implements UriMapper, ProxyCompleteListener {
    public static final Logger log = LoggerFactory.getLogger(ReverseProxyManager.class);

    private static final Pattern APP_WEB_REQUEST = Pattern.compile("/([^/?]+)(.*)");
    private static final Pattern APP_API_REQUEST = Pattern.compile("/api/v1/apps/([^/?]+)(.*)");
    private static final String REQUEST_INFO_NAME = "info";

    private final ProxyMap proxyMap;
    private final Cluster cluster;
    private final AppRequestListener appRequestListener;

    public ReverseProxyManager(Cluster cluster, ProxyMap proxyMap, AppRequestListener appRequestListener) {
        this.cluster = cluster;
        this.proxyMap = proxyMap;
        this.appRequestListener = appRequestListener;
    }

    public URI mapFrom(MuRequest clientRequest) {
        RequestInfo requestInfo = attachInfoForMonitoring(clientRequest);

        String uri = clientRequest.uri().getRawPath();
        String query = Mutils.nullOrEmpty(clientRequest.uri().getRawQuery()) ? "" : "?" + clientRequest.uri().getRawQuery();

        requestInfo.url = clientRequest.uri().toString();

        if (uri.startsWith("/api/")) {
            URI target = apiTargetUri(uri, query);
            requestInfo.appName = "api";
            if (target != null) {
                requestInfo.targetHost = target.getAuthority();
                log.info("Proxying to " + target);
                return target;
            }
        } else {
            Matcher appMatcher = APP_WEB_REQUEST.matcher(uri);
            if (appMatcher.matches()) {
                String prefix = appMatcher.group(1);
                requestInfo.appName = prefix;
                URI url = proxyMap.get(prefix);
                if (url != null) {
                    requestInfo.targetHost = url.getAuthority();
                    URI newTarget = url.resolve("/" + prefix + appMatcher.group(2) + query);
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

    private static RequestInfo attachInfoForMonitoring(MuRequest clientRequest) {
        RequestInfo info = new RequestInfo();
        info.startTime = System.currentTimeMillis();
        info.remoteAddr = clientRequest.remoteAddress();
        info.method = clientRequest.method().name();
        clientRequest.attribute(REQUEST_INFO_NAME, info);
        return info;
    }
    static RequestInfo getInfo(MuRequest request) {
        return (RequestInfo) request.attribute(REQUEST_INFO_NAME);
    }


    private static boolean isAppDeletionPost(MuRequest clientRequest) {
        String uri = clientRequest.uri().getPath();
        String prefix = "/api/v1/apps/";
        return clientRequest.method() == Method.DELETE
            && uri.startsWith(prefix) && uri.lastIndexOf('/') == (prefix.length() - 1);
    }

    @Override
    public void onComplete(MuRequest clientRequest, MuResponse clientResponse, URI targetUri, long durationInMillis) throws Exception {
        int status = clientResponse.status();
        if (isAppDeletionPost(clientRequest) && status == 200) {
            String path = clientRequest.uri().getPath();
            String appName = path.substring(path.lastIndexOf('/') + 1);
            proxyMap.remove(appName);
            cluster.getRunnerByURL(targetUri).ifPresent(runner -> {
                log.info("Decrementing app count for " + runner.id + " because " + appName + " was deleted.");
                runner.refreshRunnerCountCache(proxyMap.getAll());
            });
        }
        if (appRequestListener != null) {
            RequestInfo info = getInfo(clientRequest);
            if (info != null) {
                info.endTime = System.currentTimeMillis();
                info.responseStatus = status;
                appRequestListener.onRequestComplete(info);
            }
        }
    }
}
