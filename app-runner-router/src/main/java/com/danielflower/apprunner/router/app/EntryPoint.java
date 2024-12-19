package com.danielflower.apprunner.router.app;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.AppRunnerRouterSettings;
import com.danielflower.apprunner.router.lib.monitoring.AppRequestListener;
import com.danielflower.apprunner.router.lib.monitoring.BlockingUdpSender;
import io.muserver.HttpsConfigBuilder;
import io.muserver.MuServerBuilder;
import io.muserver.Mutils;
import io.muserver.murp.ReverseProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static com.danielflower.apprunner.router.lib.AppRunnerRouterSettings.appRunnerRouterSettings;
import static io.muserver.Http2ConfigBuilder.http2Config;
import static io.muserver.rest.CORSConfigBuilder.corsConfig;

public class EntryPoint {
    private static final Logger log = LoggerFactory.getLogger(EntryPoint.class);

    public static void main(String[] args) {
        try {
            Config config = Config.load(System.getenv(), args);

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

            int idleTimeout = config.getInt(Config.PROXY_IDLE_TIMEOUT, 30000);
            int totalTimeout = config.getInt(Config.PROXY_TOTAL_TIMEOUT, 20 * 60000);
            long maxRequestSize = config.getLong(Config.REQUEST_MAX_SIZE_BYTES, 500 * 1024 * 1024L);
            int maxHeadersSize = 24 * 1024;
            boolean allowUntrustedInstances = config.getBoolean(Config.ALLOW_UNTRUSTED_APPRUNNER_INSTANCES, true);
            boolean http2Enabled = config.getBoolean("apprunner.enable.http2", false);

            String defaultAppName = config.get(Config.DEFAULT_APP_NAME, null);
            if (Mutils.nullOrEmpty(defaultAppName)) {
                log.info("No default app name set. You can set one with the " + Config.DEFAULT_APP_NAME + " property value.");
            }
            var reverseProxyClient = ReverseProxyBuilder.createHttpClientBuilder(allowUntrustedInstances).build();
            AppRunnerRouterSettings settings = appRunnerRouterSettings()
                .withMuServerBuilder(MuServerBuilder.muServer()
                    .withHttp2Config(http2Config().enabled(http2Enabled))
                    .withHttpPort(httpPort)
                    .withHttpsPort(httpsPort)
                    .withHttpsConfig(httpsConfigBuilder)
                    .withMaxHeadersSize(maxHeadersSize)
                    .withIdleTimeout(idleTimeout + 5000, TimeUnit.MILLISECONDS)
                    .withRequestTimeout(totalTimeout + 5000, TimeUnit.MILLISECONDS)
                    .withMaxRequestSize(maxRequestSize)
                )
                .withReverseProxyHttpClient(reverseProxyClient)
                .withCorsConfig(corsConfig().withAllOriginsAllowed()
                    .withExposedHeaders("content-type", "accept", "authorization")
                    .build())
                .withAppRequestListener(getAppRequestListener(config))
                .withDataDir(config.getOrCreateDir(Config.DATA_DIR))
                .withDiscardClientForwarded(config.getBoolean(Config.PROXY_DISCARD_CLIENT_FORWARDED_HEADERS, false))
                .withDefaultAppName(defaultAppName)
                .withAllowUntrustedInstances(allowUntrustedInstances)
                .withProxyTimeoutMillis(config.getInt(Config.PROXY_TOTAL_TIMEOUT, 20 * 60000))
                .build();
            App app = new App(settings);
            app.start();
            Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
        } catch (Throwable t) {
            log.error("Error during startup", t);
            System.exit(1);
        }
    }

    private static AppRequestListener getAppRequestListener(Config config) {
        String host = config.get(Config.UDP_LISTENER_HOST, null);
        int port = config.getInt(Config.UDP_LISTENER_PORT, 0);
        if (host == null || port < 1) {
            return null;
        }
        log.info("Will publish request metrics over UDP to " + host + ":" + port);
        return BlockingUdpSender.create(host, port);
    }
}
