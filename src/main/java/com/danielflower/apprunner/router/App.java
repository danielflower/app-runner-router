package com.danielflower.apprunner.router;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.ClusterQueryingMapManager;
import com.danielflower.apprunner.router.mgmt.MapManager;
import com.danielflower.apprunner.router.mgmt.SystemInfo;
import com.danielflower.apprunner.router.monitoring.AppRequestListener;
import com.danielflower.apprunner.router.monitoring.BlockingUdpSender;
import com.danielflower.apprunner.router.web.ProxyMap;
import com.danielflower.apprunner.router.web.WebServer;
import com.danielflower.apprunner.router.web.v1.RunnerResource;
import com.danielflower.apprunner.router.web.v1.SystemResource;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.danielflower.apprunner.router.Config.dirPath;
import static java.util.Arrays.asList;

public class App {
    public static final Logger log = LoggerFactory.getLogger(App.class);

    private final Config config;
    private WebServer webServer;

    public App(Config config) {
        this.config = config;
    }

    public void start() throws Exception {
        SystemInfo systemInfo = SystemInfo.create();
        log.info(systemInfo.toString());

        File dataDir = config.getOrCreateDir(Config.DATA_DIR);

        ProxyMap proxyMap = new ProxyMap();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setOutputBufferSize(128);
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        httpConfig.addCustomizer(new ForwardedRequestCustomizer()); // must come last so the protocol doesn't get overwritten

        Server jettyServer = new Server();
        List<ServerConnector> serverConnectorList = new ArrayList<>();
        int httpPort = config.getInt(Config.SERVER_HTTP_PORT, -1);
        if (httpPort > -1) {
            ServerConnector httpConnector = new ServerConnector(jettyServer, new HttpConnectionFactory(httpConfig));
            httpConnector.setPort(httpPort);
            serverConnectorList.add(httpConnector);
        }
        int httpsPort = config.getInt(Config.SERVER_HTTPS_PORT, -1);
        SslContextFactory sslContextFactory = null;
        if (httpsPort > -1) {
            sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath(dirPath(config.getFile("apprunner.keystore.path")));
            sslContextFactory.setKeyStorePassword(config.get("apprunner.keystore.password"));
            sslContextFactory.setKeyManagerPassword(config.get("apprunner.keymanager.password"));
            sslContextFactory.setKeyStoreType(config.get("apprunner.keystore.type", "JKS"));
            ServerConnector httpConnector = new ServerConnector(jettyServer, sslContextFactory, new HttpConnectionFactory(httpConfig));

            httpConnector.setPort(httpsPort);
            serverConnectorList.add(httpConnector);
        }
        jettyServer.setConnectors(serverConnectorList.toArray(new Connector[0]));


        String defaultAppName = config.get(Config.DEFAULT_APP_NAME, null);

        HttpClient httpClient = new HttpClient(new SslContextFactory(true));
        httpClient.start();

        MapManager mapManager = new ClusterQueryingMapManager(proxyMap, httpClient);
        Cluster cluster = Cluster.load(new File(dataDir, "cluster.json"), mapManager);
        mapManager.loadAllApps(null, cluster.getRunners());

        String accessLogFilename = config.get("access.log.path", null);
        boolean allowUntrustedInstances = config.getBoolean("allow.untrusted.instances", false);

        AppRequestListener appRequestListener = getAppRequestListener();
        List<Object> localRestResources = asList(new RunnerResource(cluster), new SystemResource(systemInfo, cluster, httpClient));
        webServer = new WebServer(jettyServer, cluster, mapManager, proxyMap, defaultAppName, localRestResources, accessLogFilename, allowUntrustedInstances, appRequestListener,
            config.getInt("apprunner.proxy.idle.timeout", 30000), config.getInt("apprunner.proxy.total.timeout", 20*60000));
        webServer.start();

        if (sslContextFactory != null) {
            log.info("Supported SSL protocols: " + Arrays.toString(sslContextFactory.getSelectedProtocols()));
            log.info("Supported Cipher suites: " + Arrays.toString(sslContextFactory.getSelectedCipherSuites()));

            int maxKeyLen = Cipher.getMaxAllowedKeyLength("AES");
            if (maxKeyLen < 8192) {
                log.warn("The current java version (" + System.getProperty("java.home") + ") limits key length to " + maxKeyLen + " bits so modern browsers may have issues connecting. Install the JCE Unlimited Strength Jurisdiction Policy to allow high strength SSL connections.");
            }
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
        if (webServer != null) {
            log.info("Stopping web server");
            try {
                webServer.close();
            } catch (Exception e) {
                log.info("Error while stopping", e);
            }
            log.info("Shutdown complete");
            webServer = null;
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
