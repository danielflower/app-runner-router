package e2e;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.AppRunnerRouterSettings;
import io.muserver.MuServerBuilder;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.ContentResponseMatcher;
import scaffolding.RestClient;

import java.io.File;
import java.util.concurrent.ExecutionException;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static scaffolding.Photocopier.projectRoot;

public class HttpsTest {
    private App router;
    private final int httpPort = 20080;
    private final int httpsPort = 20443;

    private final RestClient httpClient = RestClient.create("http://localhost:" + httpPort);
    private final RestClient httpsClient = RestClient.create("https://localhost:" + httpsPort);

    @Test
    public void httpOnlyIsSupported() throws Exception {
        startRouter(muServer().withHttpPort(httpPort));
        assertWorks(httpClient);
        assertDoesNotWork(httpsClient);
    }

    @Test
    public void httpsOnlyIsSupported() throws Exception {
        startRouter(muServer().withHttpsPort(httpsPort));
        assertWorks(httpsClient);
        assertDoesNotWork(httpClient);
    }

    @Test
    public void httpAndHttpsTogetherAreSupported() throws Exception {
        startRouter(muServer().withHttpPort(httpPort).withHttpsPort(httpsPort));
        assertWorks(httpClient);
        assertWorks(httpsClient);
    }

    private void startRouter(MuServerBuilder muServerBuilder) throws Exception {
        router = new App(AppRunnerRouterSettings.appRunnerRouterSettings()
            .withDataDir(new File(projectRoot(), "target/e2e/router/" + System.currentTimeMillis()))
            .withMuServerBuilder(muServerBuilder)
            .build());
        router.start();
    }

    private static void assertWorks(RestClient client) throws Exception {
        assertThat(client.getAppRunners(), ContentResponseMatcher.equalTo(200, containsString("runners")));
    }

    private static void assertDoesNotWork(RestClient client) throws Exception {
        try {
            client.getAppRunners();
            Assert.fail("Should not have worked");
        } catch (ExecutionException e) {
            // correct behaviour
        }
    }

    @After
    public void destroy() {
        if (router != null) {
            router.shutdown();
        }
    }

}
