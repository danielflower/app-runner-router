package com.danielflower.apprunner.router.app;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.Config;
import io.muserver.HttpsConfigBuilder;
import io.muserver.MuServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.muserver.Http2ConfigBuilder.http2Config;

public class EntryPoint {
    private static final Logger log = LoggerFactory.getLogger(EntryPoint.class);

    public static void main(String[] args) {
        try {
            Config config = Config.load(System.getenv(), args);
            App app = new App(config);

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

            app.start(
                MuServerBuilder.muServer()
                    .withHttp2Config(http2Config().enabled(config.getBoolean("apprunner.enable.http2", false)))
                    .withHttpPort(httpPort)
                    .withHttpsPort(httpsPort)
                    .withHttpsConfig(httpsConfigBuilder)
            );
            Runtime.getRuntime().addShutdownHook(new Thread(app::shutdown));
        } catch (Throwable t) {
            log.error("Error during startup", t);
            System.exit(1);
        }
    }
}
