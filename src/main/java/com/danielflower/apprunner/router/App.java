package com.danielflower.apprunner.router;

import com.danielflower.apprunner.router.mgmt.*;
import com.danielflower.apprunner.router.monitoring.AppRequestListener;
import com.danielflower.apprunner.router.monitoring.BlockingUdpSender;
import com.danielflower.apprunner.router.web.*;
import com.danielflower.apprunner.router.web.v1.RunnerResource;
import com.danielflower.apprunner.router.web.v1.SystemResource;
import io.muserver.*;
import io.muserver.murp.HttpClientBuilder;
import io.muserver.murp.ReverseProxyBuilder;
import io.muserver.rest.CORSConfig;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotFoundException;
import java.io.File;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.Http2ConfigBuilder.http2Config;
import static io.muserver.MuServerBuilder.muServer;
import static io.muserver.murp.ReverseProxyBuilder.reverseProxy;
import static io.muserver.rest.CORSConfigBuilder.corsConfig;
import static io.muserver.rest.RestHandlerBuilder.restHandler;

public class App {
    public static final Logger log = LoggerFactory.getLogger(App.class);
    public static final String VIA_VALUE = "apprunnerrouter";

    private final Config config;
    private MuServer muServer;

    public App(Config config) {
        this.config = config;
    }

    public void start() throws Exception {
        SystemInfo systemInfo = SystemInfo.create();
        log.info(systemInfo.toString());

        File dataDir = config.getOrCreateDir(Config.DATA_DIR);

        ProxyMap proxyMap = new ProxyMap();


        int httpPort = config.getInt(Config.SERVER_HTTP_PORT, -1);
        int httpsPort = config.getInt(Config.SERVER_HTTPS_PORT, -1);
        HttpsConfigBuilder httpsConfigBuilder = null;
        if (httpsPort > -1) {
            httpsConfigBuilder = HttpsConfigBuilder.httpsConfig()
                .withKeystore(config.getFile("apprunner.keystore.path"))
                .withKeystorePassword(config.get("apprunner.keystore.password"))
                .withKeyPassword(config.get("apprunner.keymanager.password"))
                .withKeystoreType(config.get("apprunner.keystore.type", "JKS"));
        }

        boolean discardClientFowarded = config.getBoolean("apprunner.proxy.discard.client.forwarded.headers", false);

        String defaultAppName = config.get(Config.DEFAULT_APP_NAME, null);
        boolean allowUntrustedInstances = config.getBoolean("allow.untrusted.instances", true);

        int idleTimeout = config.getInt("apprunner.proxy.idle.timeout", 30000);

        HttpClient standardHttpClient = new HttpClient(new SslContextFactory.Client(allowUntrustedInstances));
        standardHttpClient.start();

        MapManager mapManager = new ClusterQueryingMapManager(proxyMap, standardHttpClient);
        Cluster cluster = Cluster.load(new File(dataDir, "cluster.json"), mapManager);
        mapManager.loadAllApps(null, cluster.getRunners());
        cluster.refreshRunnerCountCache(mapManager.getCurrentMapping());

        AppRequestListener appRequestListener = getAppRequestListener();
        int totalTimeout = config.getInt("apprunner.proxy.total.timeout", 20 * 60000);

        ReverseProxyManager reverseProxyManager = new ReverseProxyManager(cluster, proxyMap, appRequestListener);
        CORSConfig corsConfig = corsConfig().withAllOriginsAllowed()
            .withAllowCredentials(true)
            .withExposedHeaders("content-type", "accept", "authorization")
            .build();
        AppsCallAggregator appsCallAggregator = new AppsCallAggregator(mapManager, cluster, corsConfig);

        long maxRequestSize = config.getLong("apprunner.request.max.size.bytes", 500 * 1024 * 1024L);

        int maxHeadersSize = 24 * 1024;
        HttpClient rpHttpClient = HttpClientBuilder.httpClient()
            .withIdleTimeoutMillis(idleTimeout)
            .withMaxRequestHeadersSize(maxHeadersSize)
            .withMaxConnectionsPerDestination(1024)
            .withSslContextFactory(new SslContextFactory.Client(allowUntrustedInstances))
            .build();
        muServer = muServer()
            .withHttpPort(httpPort)
            .withHttpsPort(httpsPort)
            .withHttpsConfig(httpsConfigBuilder)
            .withMaxRequestSize(maxRequestSize)
            .withIdleTimeout(idleTimeout + 5000 /* let the proxy timeout first */, TimeUnit.MILLISECONDS)
            .withHttp2Config(http2Config().enabled(config.getBoolean("apprunner.enable.http2", false)))
            .withMaxHeadersSize(maxHeadersSize)
            .addHandler(Method.GET, "/favicon.ico", new FavIconHandler())
            .addHandler(Method.GET, "/", new HomeRedirectHandler(defaultAppName))
            .addHandler(context("/api/v1")
                .addHandler(Method.GET, "/apps", appsCallAggregator)
                .addHandler(Method.HEAD, "/apps", appsCallAggregator)
                .addHandler(Method.OPTIONS, "/apps", (request, response, pathParams) -> response.headers().set(HeaderNames.ALLOW, "GET, POST, HEAD, OPTIONS"))
                .addHandler(Method.POST, "/apps", new CreateAppHandler(proxyMap, mapManager, cluster, standardHttpClient))
                .addHandler(restHandler()
                    .addResource(new RunnerResource(cluster, mapManager))
                    .addResource(new SystemResource(systemInfo, cluster, standardHttpClient))
                    .withCORS(corsConfig)
                    .withOpenApiJsonUrl("/router-openapi.json")
                    .withOpenApiHtmlUrl("/router-api.html")
                )
                .addHandler(context("runner-proxy")
                    .addHandler(new ReverseProxyBuilder()
                        .withUriMapper(req -> {
                            Pattern proxyPattern = Pattern.compile("/(?<id>[^/]+)(/(?<targetPath>.*))?");
                            Matcher matcher = proxyPattern.matcher(req.relativePath());
                            if (matcher.matches()) {
                                String runnerId = matcher.group("id");
                                Runner runner = cluster.runner(runnerId)
                                    .orElseThrow(() -> new NotFoundException("No runner with that ID exists"));
                                String rel = matcher.groupCount() == 1 ? "/" : matcher.group("targetPath");
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
                        .withTotalTimeout(totalTimeout)
                        .withViaName(VIA_VALUE)
                        .sendLegacyForwardedHeaders(true)
                        .discardClientForwardedHeaders(discardClientFowarded)
                        .withHttpClient(rpHttpClient)
                        .build())
                )
            )
            .addHandler(reverseProxy()
                .withTotalTimeout(totalTimeout)
                .withViaName(VIA_VALUE)
                .sendLegacyForwardedHeaders(true)
                .discardClientForwardedHeaders(discardClientFowarded)
                .withUriMapper(reverseProxyManager)
                .addProxyCompleteListener(reverseProxyManager)
                .withHttpClient(rpHttpClient)
            )
            .start();

        if (httpPort >= 0 && httpsPort >= 0) {
            log.info("Started web server at " + muServer.httpsUri() + " and " + muServer.httpUri());
        } else {
            log.info("Started web server at " + muServer.uri());
        }
    }

    private AppRequestListener getAppRequestListener() {
        String host = config.get("apprunner.udp.listener.host", null);
        int port = config.getInt("apprunner.udp.listener.port", 0);
        if (host == null || port < 1) {
            return null;
        }
        log.info("Will publish request metrics over UDP to " + host + ":" + port);
        return BlockingUdpSender.create(host, port);
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

    public static void main(String[] args) {
        try {
            App app = new App(Config.load(System.getenv(), args));
            app.start();
            Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
        } catch (Throwable t) {
            log.error("Error during startup", t);
            System.exit(1);
        }
    }
}
