package manual;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.AppRunnerRouterSettings;
import com.danielflower.apprunner.router.lib.monitoring.BlockingUdpSender;
import io.muserver.HttpsConfigBuilder;
import io.muserver.MuServerBuilder;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.AppRepo;
import scaffolding.AppRunnerInstance;
import scaffolding.RestClient;
import scaffolding.RestClientThatThrows;

import java.awt.*;
import java.io.*;
import java.net.URI;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.danielflower.apprunner.router.lib.web.v1.SystemResource.getSampleID;
import static io.muserver.Mutils.fullPath;
import static scaffolding.Photocopier.projectRoot;

/**
 * An entry point for running the server locally that sets up a router with two app-runner instances and adds
 * some apps to it.
 */
public class RunLocal {
    private static final Logger log = LoggerFactory.getLogger(RunLocal.class);

    private AppRunnerInstance appRunner1;
    private AppRunnerInstance appRunner2;
    private AppRunnerInstance appRunner3;
    private App router;

    public static void main(String[] args) {
        RunLocal runLocal = new RunLocal();
        Runtime.getRuntime().addShutdownHook(new Thread(runLocal::stop));
        try {
            runLocal.start();
        } catch (Exception e) {
            log.error("Error during startup", e);
            runLocal.stop();
            System.exit(1);
        }
    }

    private void start() throws Exception {
        AppRunnerInstance instanceWithoutNode = AppRunnerInstance.latest("app-runner-1");
        instanceWithoutNode.env.put("NODE_EXEC", "target/invalid-path");
        appRunner1 = instanceWithoutNode.start();
        appRunner2 = AppRunnerInstance.oldVersion("app-runner-2").start();
        appRunner3 = AppRunnerInstance.latest("app-runner-3").start();

        int routerPort = 8443;
        router = new App(AppRunnerRouterSettings.appRunnerRouterSettings()
            .withDataDir(new File(projectRoot(), "target/e2e/router/" + System.currentTimeMillis()))
            .withDefaultAppName("app-runner-home")
            .withAppRequestListener(BlockingUdpSender.create("localhost", 12888))
            .withMuServerBuilder(MuServerBuilder.muServer()
                .withHttpsPort(routerPort)
                .withHttpsConfig(HttpsConfigBuilder.httpsConfig()
                    .withKeystore(new File(projectRoot(), "local/test.keystore"))
                    .withKeyPassword("password")
                    .withKeystorePassword("password")
                ))
            .build());
        router.start();
        URI routerUri = new URI("https://localhost:" + routerPort);
        RestClientThatThrows client = new RestClientThatThrows(RestClient.create(routerUri.toString()));

        registerRunner(client, appRunner1, 50);
        registerRunner(client, appRunner2, 50);
        registerRunner(client, appRunner3, 50);

        client.createApp("git@github.com:danielflower/app-runner-home.git", "app-runner-home");
        client.deploy("app-runner-home");
        setupSampleApps(client);

        log.info("*********** Ready to go at " + routerUri + " ***************");
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(routerUri.resolve("/api/v1/apps"));
        }
    }

    private static void setupSampleApps(RestClientThatThrows client) throws Exception {
        File repoRoot = new File(projectRoot(), "target/local/repos/" + System.currentTimeMillis());
        log.info("Creating git repos for apps at " + fullPath(repoRoot));
        FileUtils.forceMkdir(repoRoot);
        JSONObject system = new JSONObject(client.getSystem().body());
        JSONArray samples = system.getJSONArray("samples");
        for (Object sampleObj : samples) {
            JSONObject sample = (JSONObject) sampleObj;
            String id = getSampleID(sample);
            String zipUrl = sample.getString("url");
            if (zipUrl.startsWith("http://")) {
                // hack to handle bug in older app-runner when using HTTPS
                zipUrl = "https://" + zipUrl.substring("http://".length());
            }
            log.info("Going to download " + zipUrl);
            File zip = new File(repoRoot, id + ".zip");
            var zipResponse = client.getAbsoluteByteArray(zipUrl);
            FileUtils.writeByteArrayToFile(zip, zipResponse.body());
            File target = new File(repoRoot, id);
            if (!target.mkdir()) {
                throw new RuntimeException("Could not make " + fullPath(target));
            }
            unzip(zip, target);
            AppRepo app = AppRepo.create(id, target);
            try {
                client.createApp(app.gitUrl(), app.name);
            } catch (Exception e) {
                log.warn("Error while creating " + app.name + " so will just skip it. Reason: " + e.getMessage());
            }
        }
    }

    private static void unzip(File zip, File outputDir) throws IOException {
        try (ZipFile zipFile = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                File entryDestination = new File(outputDir, entry.getName());
                if (entry.isDirectory()) {
                    entryDestination.mkdirs();
                } else {
                    entryDestination.getParentFile().mkdirs();
                    try (InputStream in = zipFile.getInputStream(entry);
                    OutputStream out = new FileOutputStream(entryDestination)) {
                        IOUtils.copy(in, out);
                    }
                }
            }
        }
    }

    private static void registerRunner(RestClientThatThrows client, AppRunnerInstance runner, int maxInstances) throws Exception {
        log.info("Registering " + runner.httpUrl() + " with the router");
        var contentResponse = client.registerRunner(runner.id(), runner.httpUrl(), maxInstances);
        if (contentResponse.statusCode() != 201) {
            throw new RuntimeException("Could not register " + runner.httpUrl() + ": " + contentResponse.statusCode() + " - " + contentResponse.body());
        }
    }

    private void stop() {
        try {
            if (router != null) router.shutdown();
        } finally {
            if (appRunner1 != null) appRunner1.shutDown();
            if (appRunner2 != null) appRunner2.shutDown();
            if (appRunner3 != null) appRunner3.shutDown();
        }
    }

}
