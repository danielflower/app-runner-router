package scaffolding;

import com.danielflower.apprunner.router.io.Waiter;
import com.danielflower.apprunner.router.web.WebServer;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URI;
import java.util.concurrent.TimeUnit;

import static com.danielflower.apprunner.router.FileSandbox.dirPath;
import static org.junit.Assert.assertTrue;

public class AppRunnerInstance {

    private final Logger log;
    private final String id;
    private Killer killer;
    private URI url;

    public AppRunnerInstance(String id) {
        this.log = LoggerFactory.getLogger(id);
        this.id = id;
    }

    public AppRunnerInstance start() {
        File dir = new File("target/e2e/" + id + "/" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());
        int port = WebServer.getAFreePort();
        File uberJar = new File(FilenameUtils.separatorsToSystem("target/e2e/app-runner.jar"));
        if (!uberJar.isFile()) {
            throw new RuntimeException("Could not find the app-runner jar. Try running mvn compile first.");
        }

        CommandLine command = new CommandLine("java")
            .addArgument("-Dappserver.port=" + port)
            .addArgument("-Dappserver.data.dir=" + dirPath(new File(dir, "data")))
            .addArgument("-jar")
            .addArgument(dirPath(uberJar));
        url = URI.create("http://localhost:" + port + "/");
        log.info("Starting " + id + " at " + url);
        this.killer = new ProcessStarter(log).startDaemon(System.getenv(), command, dir, Waiter.waitFor(id, url, 60, TimeUnit.SECONDS));
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
