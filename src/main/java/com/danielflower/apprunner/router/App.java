package com.danielflower.apprunner.router;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.ClusterQueryingMapManager;
import com.danielflower.apprunner.router.mgmt.MapManager;
import com.danielflower.apprunner.router.web.ProxyMap;
import com.danielflower.apprunner.router.web.WebServer;
import com.danielflower.apprunner.router.web.v1.RunnerResource;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.danielflower.apprunner.router.Config.dirPath;

public class App {
    public static final Logger log = LoggerFactory.getLogger(App.class);

    private final Config config;
    private WebServer webServer;

    public App(Config config) {
        this.config = config;
    }

    public void start() throws Exception {
        File dataDir = config.getOrCreateDir(Config.DATA_DIR);

        ProxyMap proxyMap = new ProxyMap();


        Server jettyServer = new Server();
        List<ServerConnector> serverConnectorList = new ArrayList<>();
        int httpPort = config.getInt(Config.SERVER_HTTP_PORT, -1);
        if (httpPort > -1) {
            ServerConnector httpConnector = new ServerConnector(jettyServer);
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
            ServerConnector httpConnector = new ServerConnector(jettyServer, sslContextFactory);
            httpConnector.setPort(httpsPort);
            serverConnectorList.add(httpConnector);
        }
        jettyServer.setConnectors(serverConnectorList.toArray(new Connector[0]));


        String defaultAppName = config.get(Config.DEFAULT_APP_NAME, null);
        MapManager mapManager = ClusterQueryingMapManager.create(proxyMap);
        Cluster cluster = Cluster.load(new File(dataDir, "cluster.json"), mapManager);
        mapManager.loadAllApps(URI.create("/"), cluster.getRunners());

        String accessLogFilename = config.get("access.log.path", null);
        boolean allowUntrustedInstances = config.getBoolean("allow.untrusted.instances", false);
        webServer = new WebServer(jettyServer, cluster, mapManager, proxyMap, defaultAppName, new RunnerResource(cluster), accessLogFilename, allowUntrustedInstances);
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
