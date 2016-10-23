package scaffolding;

import com.danielflower.apprunner.router.problems.AppRunnerException;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FilenameUtils;
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
    private final Logger log;
    private final String id;
    private Killer killer;
    private URI url;

    public AppRunnerInstance(String id) {
        this.log = LoggerFactory.getLogger(id);
        this.id = id;
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
        int port = getAFreePort();
        File uberJar = new File(FilenameUtils.separatorsToSystem("target/e2e/app-runner.jar"));
        if (!uberJar.isFile()) {
            throw new RuntimeException("Could not find the app-runner jar. Try running mvn compile first.");
        }

        CommandLine command = new CommandLine("java")
            .addArgument("-Dlogback.configurationFile=" + dirPath(new File("src/test/resources/logback-test.xml")))
            .addArgument("-Dappserver.port=" + port)
            .addArgument("-Dappserver.data.dir=" + dirPath(new File(dir, "data")))
            .addArgument("-jar")
            .addArgument(dirPath(uberJar));
        url = URI.create("http://localhost:" + port + "/");
        log.info("Starting " + id + " at " + url);
        this.killer = new ProcessStarter(log).startDaemon(env, command, dir, Waiter.waitFor(id, url, 60, TimeUnit.SECONDS));
        return this;
    }

    public URI url() {
        return this.url;
    }

    public void shutDown() {
        killer.destroyProcess();
    }

    public String id() {
        return id;
    }
}
