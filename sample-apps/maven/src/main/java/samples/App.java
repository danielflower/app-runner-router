package samples;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.io.*;

import io.muserver.*;
import io.muserver.handlers.*;

public class App {
    public static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        Map<String, String> settings = System.getenv();

        // When run from app-runner, you must use the port set in the environment variable APP_PORT
        int port = Integer.parseInt(settings.getOrDefault("APP_PORT", "8081"));
        // All URLs must be prefixed with the app name, which is got via the APP_NAME env var.
        String appName = settings.getOrDefault("APP_NAME", "my-app");
        String env = settings.getOrDefault("APP_ENV", "local"); // "prod" or "local"
        boolean isLocal = "local".equals(env);
        log.info("Starting " + appName + " in " + env + " on port " + port);

        MuServer server = MuServerBuilder.muServer()
            .withHttpPort(port)
            .addHandler(
                ContextHandlerBuilder.context(appName)
                    .addHandler(Method.GET, "/slow", (req, resp, pp) -> {
                        try {
                            Thread.sleep(Long.parseLong(req.query().get("millis")));
                        } catch (InterruptedException e) {
                            Thread.interrupted();
                        }
                        resp.write("This was slow");
                    })
                    .addHandler(Method.GET, "/headers", (req, resp, pp) -> {
                        resp.contentType("text/plain;charset=utf-8");
                        String someQuery = req.query().get("some-query", "");
                        try (PrintWriter writer = resp.writer()) {
                            if (!Mutils.nullOrEmpty(someQuery)) {
                                writer.append("some-query: " + someQuery + "\r\n\r\n");
                            }
                            for (Map.Entry<String, String> header : req.headers()) {
                                writer.append(header.getKey()).append(":").append(header.getValue()).append("\r\n");
                            }
                        }
                    })
                    .addHandler(ResourceHandlerBuilder.classpathHandler("/web"))
            )
            .addShutdownHook(true)
            .start();

        log.info("Started app at " + server.uri().resolve("/" + appName));
    }

}