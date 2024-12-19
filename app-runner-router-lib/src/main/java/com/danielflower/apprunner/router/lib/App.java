package com.danielflower.apprunner.router.lib;

import com.danielflower.apprunner.router.lib.mgmt.*;
import com.danielflower.apprunner.router.lib.web.*;
import com.danielflower.apprunner.router.lib.web.v1.RunnerResource;
import com.danielflower.apprunner.router.lib.web.v1.SystemResource;
import io.muserver.HeaderNames;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.Mutils;
import io.muserver.murp.ReverseProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.NotFoundException;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static io.muserver.rest.RestHandlerBuilder.restHandler;

public class App {
    public static final Logger log = LoggerFactory.getLogger(App.class);
    public static final String VIA_VALUE = "apprunnerrouter";

    private final AppRunnerRouterSettings settings;
    private MuServer muServer;
    private HttpClient standardHttpClient;

    public App(AppRunnerRouterSettings settings) {
        this.settings = settings;
    }

    public void start() throws Exception {
        log.info("Starting router with " + settings);
        SystemInfo systemInfo = SystemInfo.create();
        log.info(systemInfo.toString());

        ProxyMap proxyMap = new ProxyMap();

        this.standardHttpClient = ReverseProxyBuilder.createHttpClientBuilder(settings.allowUntrustedInstances()).build();

        MapManager mapManager = new ClusterQueryingMapManager(proxyMap, standardHttpClient);
        Cluster cluster = Cluster.load(new File(settings.dataDir(), "cluster.json"), mapManager);
        mapManager.loadAllApps(null, cluster.getRunners());
        cluster.refreshRunnerCountCache(mapManager.getCurrentMapping());

        ReverseProxyManager reverseProxyManager = new ReverseProxyManager(cluster, proxyMap, settings.appRequestListener());

        AppsCallAggregator appsCallAggregator = new AppsCallAggregator(mapManager, cluster, settings.corsConfig());

        Pattern proxyPattern = Pattern.compile("/(?<id>[^/]+)(/(?<targetPath>.*))?");

        muServer = settings.muServerBuilder()
            .addHandler(Method.GET, "/favicon.ico", new FavIconHandler())
            .addHandler(Method.GET, "/", new HomeRedirectHandler(settings.defaultAppName()))
            .addHandler(context("/api/v1")
                .addHandler(Method.GET, "/apps", appsCallAggregator)
                .addHandler(Method.HEAD, "/apps", appsCallAggregator)
                .addHandler(Method.OPTIONS, "/apps", (request, response, pathParams) -> response.headers().set(HeaderNames.ALLOW, "GET, POST, HEAD, OPTIONS"))
                .addHandler(Method.POST, "/apps", new CreateAppHandler(proxyMap, mapManager, cluster, standardHttpClient))
                .addHandler(restHandler()
                    .addResource(new RunnerResource(cluster, mapManager, settings.runnerUrlVerifier()))
                    .addResource(new SystemResource(systemInfo, cluster, standardHttpClient))
                    .withCORS(settings.corsConfig())
                    .withOpenApiJsonUrl("/router-openapi.json")
                    .withOpenApiHtmlUrl("/router-api.html")
                )
                .addHandler(context("runner-proxy")
                    .addHandler(ReverseProxyBuilder.reverseProxy()
                        .withUriMapper(req -> {
                            Matcher matcher = proxyPattern.matcher(req.relativePath());
                            if (matcher.matches()) {
                                String runnerId = matcher.group("id");
                                Runner runner = cluster.runner(runnerId)
                                    .orElseThrow(() -> new NotFoundException("No runner with that ID exists"));
                                String rel = matcher.groupCount() == 1 ? "/" : "/" + matcher.group("targetPath");
                                String qs = req.uri().getRawQuery();
                                if (!Mutils.nullOrEmpty(qs)) {
                                    rel += "?" + qs;
                                }
                                URI target = runner.url.resolve(rel);
                                log.info("Proxying to runner " + runnerId + ": " + target);
                                return target;
                            } else {
                                return null;
                            }
                        })
                        .withTotalTimeout(settings.proxyTimeoutMillis())
                        .withViaName(VIA_VALUE)
                        .sendLegacyForwardedHeaders(true)
                        .discardClientForwardedHeaders(settings.discardClientForwarded())
                        .withHttpClient(settings.reverseProxyHttpClient())
                        .build())
                )
            )
            .addHandler(reverseProxy()
                .withTotalTimeout(settings.proxyTimeoutMillis())
                .withViaName(VIA_VALUE)
                .sendLegacyForwardedHeaders(true)
                .discardClientForwardedHeaders(settings.discardClientForwarded())
                .withUriMapper(reverseProxyManager)
                .addProxyCompleteListener(reverseProxyManager)
                .withHttpClient(settings.reverseProxyHttpClient())
            )
            .start();

        if (muServer.httpUri() != null && muServer.httpsUri() != null) {
            log.info("Started web server at " + muServer.httpsUri() + " and " + muServer.httpUri());
        } else {
            log.info("Started web server at " + muServer.uri());
        }
    }

    public void shutdown() {
        log.info("Shutdown invoked");
        if (muServer != null) {
            log.info("Stopping web server");
            muServer.stop();
            log.info("Shutdown complete");
            muServer = null;
        }
    }
}
