package e2e;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.AppRunnerRouterSettings;
import com.danielflower.apprunner.router.lib.mgmt.SystemInfo;
import io.muserver.MuServerBuilder;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.*;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import scaffolding.*;

import java.io.File;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.Photocopier.projectRoot;

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
        router = new App(AppRunnerRouterSettings.appRunnerRouterSettings()
            .withDataDir(new File(projectRoot(), "target/e2e/router/" + System.currentTimeMillis()))
            .withMuServerBuilder(MuServerBuilder.muServer().withHttpPort(routerHttpPort))
            .build());
        router.start();
        String host = SystemInfo.create().hostName;
        httpClient = RestClient.create("http://" + host + ":" + routerHttpPort);

        httpClient.registerRunner(healthyRunner.id(), healthyRunner.httpUrl(), 10);
        AppRepo appOnHealthyRunner = AppRepo.create("maven");
        httpClient.createApp(appOnHealthyRunner.gitUrl(), "app1");

        httpClient.registerRunner(unhealthyRunner.id(), unhealthyRunner.httpUrl(), 10);

        httpClient.deploy("app1");
        Waiter.waitForApp(httpClient.targetURI(), "app1").close();
        assertThat(httpClient.get("/app1/"), ContentResponseMatcher.equalTo(200, containsString("My Maven App")));

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
        var appsResponse = httpClient.get("/api/v1/apps");
        JSONObject actual = new JSONObject(appsResponse.body());
        JSONAssert.assertEquals("{ " +
            "'appCount': 1," +
            "'apps': [ { 'name': 'app1', 'url': '" + httpClient.targetURI().resolve("/app1/") + "', 'appRunnerInstanceId': 'healthy-app-runner', } ]" +
            "}", actual, JSONCompareMode.STRICT_ORDER);

        assertThat(actual.getJSONArray("errors").length(), is(1));
        assertThat(actual.getJSONArray("errors").get(0).toString(), startsWith("unhealthy-app-runner: "));
    }

    @Test
    public void systemCallStillReturnsEvenWhenRunnersAreUnavailable() throws Exception {
        var systemResponse = httpClient.get("/api/v1/system");
        JSONObject all = new JSONObject(systemResponse.body());
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
