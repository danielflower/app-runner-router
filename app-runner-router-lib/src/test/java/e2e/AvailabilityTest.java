package e2e;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.Config;
import com.danielflower.apprunner.router.lib.mgmt.SystemInfo;
import org.eclipse.jetty.client.api.ContentResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import scaffolding.AppRepo;
import scaffolding.AppRunnerInstance;
import scaffolding.RestClient;
import scaffolding.Waiter;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static com.danielflower.apprunner.router.lib.Config.dirPath;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static scaffolding.ContentResponseMatcher.equalTo;

public class AvailabilityTest {
    private static AppRunnerInstance healthyRunner;
    private static AppRunnerInstance unhealthyRunner;
    private App router;
    private RestClient httpClient;

    @BeforeClass
    public static void createRunners() {
        healthyRunner = AppRunnerInstance.latest("healthy-app-runner").start();
        unhealthyRunner = AppRunnerInstance.latest("unhealthy-app-runner").start();
    }

    @Before
    public void create() throws Exception {
        int routerHttpPort = AppRunnerInstance.getAFreePort();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("appserver.port", String.valueOf(routerHttpPort));
        env.put("appserver.data.dir", dirPath(new File("target/e2e/router/" + System.currentTimeMillis())));
        router = new App(new Config(env));
        router.start();
        String host = SystemInfo.create().hostName;
        httpClient = RestClient.create("http://" + host + ":" + routerHttpPort);

        httpClient.registerRunner(healthyRunner.id(), healthyRunner.httpUrl(), 10);
        AppRepo appOnHealthyRunner = AppRepo.create("maven");
        httpClient.createApp(appOnHealthyRunner.gitUrl(), "app1");

        httpClient.registerRunner(unhealthyRunner.id(), unhealthyRunner.httpUrl(), 10);

        httpClient.deploy("app1");
        Waiter.waitForApp(httpClient.targetURI(), "app1");
        assertThat(httpClient.get("/app1/"), equalTo(200, containsString("My Maven App")));

        unhealthyRunner.shutDown();
    }

    @After
    public void destroy() throws Exception {
        healthyRunner.clearApps();
        router.shutdown();
    }

    @AfterClass
    public static void deleteRunners() {
        healthyRunner.shutDown();
    }


    @Test
    public void appsReturnsPartialListWhenAnAppRunnerIsAvailableAndHasErrorMessages() throws Exception {

        // querying for all the apps returns a combined list
        ContentResponse appsResponse = httpClient.get("/api/v1/apps");
        JSONObject actual = new JSONObject(appsResponse.getContentAsString());
        JSONAssert.assertEquals("{ " +
            "'appCount': 1," +
            "'apps': [ { 'name': 'app1', 'url': '" + httpClient.targetURI().resolve("/app1/") + "' } ]" +
            "}", actual, JSONCompareMode.STRICT_ORDER);

        assertThat(actual.getJSONArray("errors").length(), is(1));

    }

    @Test
    public void systemCallStillReturnsEvenWhenRunnersAreUnavailable() throws Exception {
        ContentResponse systemResponse = httpClient.get("/api/v1/system");
        JSONObject all = new JSONObject(systemResponse.getContentAsString());
        assertThat(all.getBoolean("appRunnerStarted"), equalTo(false));
        JSONArray runners = all.getJSONArray("runners");
        assertThat(runners.length(), equalTo(2));
        for (Object runnerO : runners) {
            JSONObject runner = (JSONObject) runnerO;
            if (runner.getJSONObject("system").getBoolean("appRunnerStarted")) {
                assertThat(runner.has("error"), is(false));
            } else {
                assertThat(runner.has("error"), is(true));
            }
        }

    }
}
