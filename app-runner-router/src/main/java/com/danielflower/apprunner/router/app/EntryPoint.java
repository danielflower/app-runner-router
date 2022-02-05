package com.danielflower.apprunner.router.app;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EntryPoint {
    private static final Logger log = LoggerFactory.getLogger(EntryPoint.class);
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
