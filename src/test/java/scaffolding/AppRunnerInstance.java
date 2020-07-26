package scaffolding;

import com.danielflower.apprunner.router.problems.AppRunnerException;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FilenameUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.danielflower.apprunner.router.Config.dirPath;
import static org.junit.Assert.assertTrue;

public class AppRunnerInstance {

    public final Map<String, String> env = new HashMap<>(System.getenv());
    private final String jarName;
    private final Logger log;
    private final String id;
    private Killer killer;
    private URI httpUrl;
    private URI httpsUrl;

    private AppRunnerInstance(String jarName, String id) {
        this.jarName = jarName;
        this.log = LoggerFactory.getLogger(id);
        this.id = id;
    }

    public static AppRunnerInstance versionOne(String id) {
        return new AppRunnerInstance("app-runner-1.0.jar", id);
    }

    public static AppRunnerInstance latest(String id) {
        return new AppRunnerInstance("app-runner-latest.jar", id);
    }

    public static int getAFreePort() {
        try {
            try (ServerSocket serverSocket = new ServerSocket(0)) {
                return serverSocket.getLocalPort();
            }
        } catch (IOException e) {
            throw new AppRunnerException("Unable to get a port", e);
        }
    }

    public AppRunnerInstance start() {
        File dir = new File("target/e2e/" + id + "/" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());
        int httpPort = getAFreePort();
        int httpsPort = getAFreePort();
        File uberJar = new File(FilenameUtils.separatorsToSystem("target/e2e/" + jarName));
        if (!uberJar.isFile()) {
            throw new RuntimeException("Could not find the app-runner jar. Try running mvn compile first.");
        }

        CommandLine command = new CommandLine("java")
            .addArgument("-Dlogback.configurationFile=" + dirPath(new File("src/test/resources/logback-test.xml")))
            .addArgument("-Dappserver.port=" + httpPort)
            .addArgument("-Dappserver.https.port=" + httpsPort)
            .addArgument("-Dappserver.data.dir=" + dirPath(new File(dir, "data")))
            .addArgument("-Dapprunner.keystore.path=" + dirPath(new File("local/test.keystore")))
            .addArgument("-Dapprunner.keystore.password=password")
            .addArgument("-Dapprunner.keymanager.password=password")
            .addArgument("-jar")
            .addArgument(dirPath(uberJar));
        httpUrl = URI.create("http://localhost:" + httpPort + "/");
        httpsUrl = URI.create("https://localhost:" + httpsPort + "/");
        log.info("Starting " + id + " at " + httpUrl);
        this.killer = new ProcessStarter(log).startDaemon(env, command, dir, Waiter.waitFor(id, httpUrl, 60, TimeUnit.SECONDS));
        return this;
    }

    public URI httpUrl() {
        return this.httpUrl;
    }

    public URI httpsUrl() {
        return this.httpsUrl;
    }

    public void shutDown() {
        killer.destroyProcess();
    }

    public String id() {
        return id;
    }

    public void clearApps() throws Exception {
        RestClient client = RestClient.create(httpUrl().toString());
        while (true) {
            JSONArray apps = new JSONObject(client.get("/api/v1/apps").getContentAsString())
                .getJSONArray("apps");
            if (apps.isEmpty()) {
                break;
            }
            for (Object o : apps) {
                JSONObject app = (JSONObject) o;
                client.deleteApp(app.getString("name"));
            }
        }
    }
}
