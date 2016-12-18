package e2e;

import com.danielflower.apprunner.router.App;
import com.danielflower.apprunner.router.Config;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.ContentResponseMatcher;
import scaffolding.RestClient;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.danielflower.apprunner.router.Config.dirPath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class HttpsTest {
    private App router;
    private final int httpPort = 20080;
    private final int httpsPort = 20443;

    private final RestClient httpClient = RestClient.create("http://localhost:" + httpPort);
    private final RestClient httpsClient = RestClient.create("https://localhost:" + httpsPort);

    @Test
    public void httpOnlyIsSupported() throws Exception {
        Map<String, String> env = env();
        env.put("appserver.port", String.valueOf(httpPort));
        router = new App(new Config(env));
        router.start();

        assertWorks(httpClient);
        assertDoesNotWork(httpsClient);
    }

    @Test
    public void httpsOnlyIsSupported() throws Exception {
        Map<String, String> env = env();
        env.put("appserver.https.port", String.valueOf(httpsPort));
        env.put("apprunner.keystore.path", dirPath(new File("local/test.keystore")));
        env.put("apprunner.keystore.password", "password");
        env.put("apprunner.keymanager.password", "password");
        router = new App(new Config(env));
        router.start();

        assertWorks(httpsClient);
        assertDoesNotWork(httpClient);
    }

    @Test
    public void httpAndHttpsTogetherAreSupported() throws Exception {
        Map<String, String> env = env();
        env.put("appserver.port", String.valueOf(httpPort));
        env.put("appserver.https.port", String.valueOf(httpsPort));
        env.put("apprunner.keystore.path", dirPath(new File("local/test.keystore")));
        env.put("apprunner.keystore.password", "password");
        env.put("apprunner.keymanager.password", "password");
        router = new App(new Config(env));
        router.start();

        assertWorks(httpClient);
        assertWorks(httpsClient);
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

    private static Map<String, String> env() {
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("appserver.data.dir", dirPath(new File("target/e2e/router/" + System.currentTimeMillis())));
        return env;
    }

    @After
    public void destroy() throws Exception {
        try {
            if (router != null) {
                router.shutdown();
            }
        } finally {
            if (httpClient != null) {
                httpClient.close();
            }
            if (httpsClient != null) {
                httpsClient.close();
            }
        }
    }


}
