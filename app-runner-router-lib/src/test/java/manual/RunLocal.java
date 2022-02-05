package manual;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.Config;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jetty.client.api.ContentResponse;
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
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static com.danielflower.apprunner.router.lib.Config.dirPath;
import static com.danielflower.apprunner.router.lib.web.v1.SystemResource.getSampleID;
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

    public static void main(String[] args) throws Exception {
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
        appRunner2 = AppRunnerInstance.versionOne("app-runner-2").start();
        appRunner3 = AppRunnerInstance.latest("app-runner-3").start();

        int routerPort = 8443;
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("apprunner.enable.http2", "false");
        env.put("appserver.https.port", String.valueOf(routerPort));
        env.put("appserver.data.dir", dirPath(new File(projectRoot(), "target/e2e/router/" + System.currentTimeMillis())));
        env.put("apprunner.keystore.path", dirPath(new File(projectRoot(), "local/test.keystore")));
        env.put("apprunner.keystore.password", "password");
        env.put("apprunner.keymanager.password", "password");
        env.put("apprunner.udp.listener.host", "localhost");
        env.put("apprunner.udp.listener.port", "12888");
        env.put("appserver.default.app.name", "app-runner-home");

        router = new App(new Config(env));
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
        log.info("Creating git repos for apps at " + dirPath(repoRoot));
        FileUtils.forceMkdir(repoRoot);
        JSONObject system = new JSONObject(client.getSystem().getContentAsString());
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
            ContentResponse zipResponse = client.getAbsolute(zipUrl);
            FileUtils.writeByteArrayToFile(zip, zipResponse.getContent());
            File target = new File(repoRoot, id);
            if (!target.mkdir()) {
                throw new RuntimeException("Could not make " + dirPath(target));
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
        ContentResponse contentResponse = client.registerRunner(runner.id(), runner.httpUrl(), maxInstances);
        if (contentResponse.getStatus() != 201) {
            throw new RuntimeException("Could not register " + runner.httpUrl() + ": " + contentResponse.getStatus() + " - " + contentResponse.getContentAsString());
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
