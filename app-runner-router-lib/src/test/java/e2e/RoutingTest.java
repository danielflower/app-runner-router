package e2e;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.AppRunnerRouterSettings;
import com.danielflower.apprunner.router.lib.RunnerUrlVerifier;
import com.danielflower.apprunner.router.lib.mgmt.SystemInfo;
import com.danielflower.apprunner.router.lib.web.v1.SystemResource;
import io.muserver.MuServerBuilder;
import io.muserver.Mutils;
import io.muserver.murp.ReverseProxyBuilder;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
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
import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ContentResponseMatcher.equalTo;
import static scaffolding.Photocopier.projectRoot;

public class RoutingTest {

    private static AppRunnerInstance latestAppRunnerWithoutNode;
    private static AppRunnerInstance oldAppRunner;
    private App router;
    private RestClient httpClient;
    private RestClient httpsClient;
    private RestClient proxyToOldClient;
    private RestClient proxyToLatestWithoutNodeClient;
    private final int routerHttpPort = AppRunnerInstance.getAFreePort();
    private final int routerHttpsPort = AppRunnerInstance.getAFreePort();
    private final File dataDir = new File(projectRoot(), "target/e2e/router/" + System.currentTimeMillis());

    @BeforeClass
    public static void createRunners() {
        AppRunnerInstance instanceWithoutNode = AppRunnerInstance.latest("app-runner-1");
        instanceWithoutNode.env.put("NODE_EXEC", "target/invalid-path");
        latestAppRunnerWithoutNode = instanceWithoutNode.start();
        oldAppRunner = AppRunnerInstance.oldVersion("app-runner-2").start();
    }

    @Before
    public void create() throws Exception {
        router = createStartedRouter();
        String host = SystemInfo.create().hostName;
        httpClient = RestClient.create("http://" + host + ":" + routerHttpPort);
        httpsClient = RestClient.create("https://" + host + ":" + routerHttpsPort);
        proxyToLatestWithoutNodeClient = RestClient.create("https://" + host + ":" + routerHttpsPort + "/api/v1/runner-proxy/" + latestAppRunnerWithoutNode.id());
        proxyToOldClient = RestClient.create("https://" + host + ":" + routerHttpsPort + "/api/v1/runner-proxy/" + oldAppRunner.id());
    }

    private App createStartedRouter() throws Exception {
        App router = new App(AppRunnerRouterSettings.appRunnerRouterSettings()
            .withDataDir(dataDir)
            .withRunnerUrlVerifier(new RunnerUrlVerifier() {
                @Override
                public void verify(String url) throws WebApplicationException {
                    if (url.contains("badurl"))
                        throw new BadRequestException("That is an invalid runner URL");
                }
            })
            .withAllowUntrustedInstances(true)
            .withReverseProxyHttpClient(ReverseProxyBuilder.createHttpClientBuilder(true).build())
            .withMuServerBuilder(MuServerBuilder.muServer().withHttpPort(routerHttpPort).withHttpsPort(routerHttpsPort))
            .build());
        router.start();
        return router;
    }

    @After
    public void destroy() throws Exception {
        latestAppRunnerWithoutNode.clearApps();
        oldAppRunner.clearApps();
        router.shutdown();
    }

    @AfterClass
    public static void deleteRunners() {
        oldAppRunner.shutDown();
        latestAppRunnerWithoutNode.shutDown();
    }

    @Test
    public void appRunnersCanBeRegisteredAndDeregisteredWithTheRestAPIWithAnHTTPRouter() throws Exception {
        assertThat(httpClient.get("/"), equalTo(404, containsString("Welcome to AppRunner. No application has been set as the homepage.")));

        URI urlOfLatest = latestAppRunnerWithoutNode.httpsUrl();
        assertThat(httpClient.registerRunner(latestAppRunnerWithoutNode.id(), urlOfLatest, 1), equalTo(201, containsString(latestAppRunnerWithoutNode.id())));
        assertThat(httpClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 2), equalTo(201, containsString(oldAppRunner.id())));

        var appRunners = httpClient.getAppRunners();
        assertThat(appRunners.statusCode(), is(200));
        JSONAssert.assertEquals("{ 'runners': [" +
            "  { 'id': 'app-runner-1', 'url': '" + urlOfLatest + "', 'appCount': 0, 'maxApps': 1, " + "'systemUrl': '" + urlOfLatest.resolve("/api/v1/system") + "', 'appsUrl': '" + urlOfLatest.resolve("/api/v1/apps") + "' }," +
            "  { 'id': 'app-runner-2', 'url': '" + oldAppRunner.httpUrl().toString() + "', 'appCount': 0, 'maxApps': 2 }" +
            "]}", appRunners.body(), JSONCompareMode.LENIENT);

        var appRunner = httpClient.getRunner("app-runner-2");
        assertThat(appRunner.statusCode(), is(200));
        JSONAssert.assertEquals("{ 'id': 'app-runner-2', 'url': '" + oldAppRunner.httpUrl().toString() + "', 'maxApps': 2 }"
            , appRunner.body(), JSONCompareMode.LENIENT);

        assertThat(httpClient.deleteRunner(oldAppRunner.id()), equalTo(200, containsString(oldAppRunner.id())));
        assertThat(httpClient.getRunner(oldAppRunner.id()), equalTo(404, containsString(oldAppRunner.id())));
        assertThat(httpClient.deleteRunner(latestAppRunnerWithoutNode.id()), equalTo(200, containsString(latestAppRunnerWithoutNode.id())));
    }

    @Test
    public void runnersWithInvalidUrlsAreRejected() throws Exception {
        var resp = httpsClient.registerRunner("someid", URI.create("https://badurl.example.org"), 1);
        assertThat(resp, equalTo(400, containsString("That is an invalid runner URL")));
    }


    @Test
    public void proxyHeadersAreForwardedCorrectlyToTheApp() throws Exception {
        httpsClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        httpsClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 1);

        AppRepo app1 = AppRepo.create("maven");
        AppRepo app2 = AppRepo.create("maven");

        httpsClient.createApp(app1.gitUrl(), "app1");
        httpsClient.deploy("app1");
        Waiter.waitForApp(httpsClient.targetURI(), "app1");

        String headersFromOne = httpsClient.get("/app1/headers").body();

        httpsClient.createApp(app2.gitUrl(), "app2");
        httpsClient.deploy("app2");
        Waiter.waitForApp(httpsClient.targetURI(), "app2");

        String headersFromTwo = httpsClient.get("/app2/headers").body();

        assertThat(headersFromOne, containsString(";proto=https\r\n"));
        assertThat(headersFromOne, containsString(";proto=http\r\n"));
        assertThat(headersFromOne.indexOf(";proto=https\r\n"), Matchers.lessThan(headersFromOne.lastIndexOf(";proto=http\r\n")));

        assertThat(headersFromOne, containsString("X-Forwarded-Proto:https\r\n"));
        assertThat(headersFromTwo, containsString("X-Forwarded-Proto:https\r\n"));
        assertThat(headersFromOne, containsString("Host:" + httpsClient.targetURI().getAuthority() + "\r\n"));
        assertThat(headersFromTwo, containsString("Host:" + httpsClient.targetURI().getAuthority() + "\r\n"));

        httpsClient.stop("app1");
        httpsClient.stop("app2");

    }

    @Test
    public void appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunnersWithAnHTTPRouterAndHTTPInstances() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        httpClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 1);
        appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunners(httpClient, latestAppRunnerWithoutNode, oldAppRunner);
    }

    @Test
    public void appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunnersWithAnHTTPSRouterAndHTTPInstances() throws Exception {
        httpsClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        httpsClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 1);
        appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunners(httpsClient, latestAppRunnerWithoutNode, oldAppRunner);
    }

    @Test
    public void appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunnersWithAnHTTPRouterAndHTTPSInstances() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpsUrl(), 1);
        httpClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpsUrl(), 1);
        appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunners(httpClient, latestAppRunnerWithoutNode, oldAppRunner);
    }

    @Test
    public void appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunnersWithAnHTTPSRouterAndHTTPSInstances() throws Exception {
        httpsClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpsUrl(), 1);
        httpsClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpsUrl(), 1);
        appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunners(httpsClient, latestAppRunnerWithoutNode, oldAppRunner);
    }

    @Test
    public void slowRequestsTimeOut() throws Exception {
        httpsClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpsUrl(), 1);
        httpsClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpsUrl(), 1);
        int waitTime = 35000;
        for (String appName : asList("app1", "app2")) {
            AppRepo app1 = AppRepo.create("maven");
            httpsClient.createApp(app1.gitUrl(), appName);
            httpsClient.deploy(appName);
            Waiter.waitForApp(httpsClient.targetURI(), appName);
            assertThat(httpsClient.get("/" + appName + "/slow?millis=" + waitTime).statusCode(), is(504));
            httpsClient.stop(appName);
        }
    }

    private static void appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunners(RestClient client, AppRunnerInstance latestAppRunner, AppRunnerInstance oldAppRunner) throws Exception {
        AppRepo app1 = AppRepo.create("maven");
        AppRepo app2 = AppRepo.create("maven");

        var app1Creation = client.createApp(app1.gitUrl(), "app1");
        assertThat("Error returned: " + app1Creation.body(), app1Creation.statusCode(), is(201));

        var app1DuplicateCreation = client.createApp(app1.gitUrl(), "app1");
        assertThat("Body returned: " + app1DuplicateCreation.body(), app1DuplicateCreation.statusCode(), is(409));

        JSONObject app1Json = new JSONObject(app1Creation.body());
        assertThat(app1Json.getString("url"), startsWith(client.routerUrl));

        client.deploy("app1");
        Waiter.waitForApp(client.targetURI(), "app1");
        assertThat(client.get("/app1/"), equalTo(200, containsString("My Maven App")));

        client.createApp(app2.gitUrl(), "app2");
        client.deploy("app2");

        assertThat(client.get("/app2/?blah=aws&what=wat"), equalTo(200, containsString("My Maven App")));

        // the apps should be evenly distributed
        assertThat(numberOfApps(latestAppRunner), is(1));
        assertThat(numberOfApps(oldAppRunner), is(1));

        // a runner can be deleted and added back and the number reported is accurate
        JSONObject savedInfo = new JSONObject(client.getRunner(latestAppRunner.id()).body());
        client.deleteRunner(latestAppRunner.id());
        client.registerRunner(latestAppRunner.id(), URI.create(savedInfo.getString("url")), savedInfo.getInt("maxApps"));
        assertThat(numberOfApps(latestAppRunner), is(1));


        // querying for all the apps returns a combined list
        var appsResponse = client.get("/api/v1/apps");
        JSONAssert.assertEquals("{ " +
            "'appCount': 2," +
            "'apps': [ " +
            "{ 'name': 'app1', 'url': '" + client.targetURI().resolve("/app1/") + "' }," +
            "{ 'name': 'app2', 'url': '" + client.targetURI().resolve("/app2/") + "' }" +
            "] }", appsResponse.body(), JSONCompareMode.STRICT_ORDER);

        assertThat(appsResponse.statusCode(), is(200));

        assertThat(client.get("/api/v1/swagger.json"), equalTo(200, containsString("/apps/{name}")));

        JSONAssert.assertEquals("{ runners: [" +
                "{ id: " + latestAppRunner.id() + ", 'appCount': 1 }," +
                "{ id: " + oldAppRunner.id() + ", 'appCount': 1 }" +
                "] }",
            client.getAppRunners().body(), JSONCompareMode.LENIENT);

        client.stop("app1");
        client.stop("app2");
    }

    private static int numberOfApps(AppRunnerInstance appRunner) throws Exception {
        RestClient c = RestClient.create(appRunner.httpUrl().toString());
        JSONObject apps = new JSONObject(c.get("/api/v1/apps").body());
        return apps.getJSONArray("apps").length();
    }

    @Test
    public void appsAreNotAddedToRunnersThatAreAtMaxCapacity() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);

        AppRepo app1 = AppRepo.create("maven");
        AppRepo app2 = AppRepo.create("maven");

        assertThat(httpClient.createApp(app1.gitUrl(), "app1"), equalTo(201, containsString("app1")));
        assertThat(httpClient.createApp(app2.gitUrl(), "app2"), equalTo(503, containsString("There are no App Runner instances with free capacity")));

        httpClient.deleteApp("app1");

        assertThat(httpClient.createApp(app2.gitUrl(), "app2"), equalTo(201, containsString("app2")));
    }

    @Test
    public void ifNotSupportedByAllRunnersThenThatIsReturned() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 10);
        AppRepo nodeApp = AppRepo.create("nodejs");
        assertThat(httpClient.createApp(nodeApp.gitUrl(), "not-supported"),
            equalTo(501, containsString("No suitable runner found for this app")));

        AppRepo mavenVersion = AppRepo.create("maven");
        assertThat(httpClient.createApp(mavenVersion.gitUrl(), "not-supported"),
            equalTo(201, containsString("not-supported")));
    }

    @Test
    public void canAddAppsEvenIfSomeRunnersNotRunning() throws Exception {
        AppRunnerInstance stoppedOne = AppRunnerInstance.latest("will-stop").start();
        httpClient.registerRunner(stoppedOne.id(), stoppedOne.httpUrl(), 20);

        AppRunnerInstance instanceWithoutMaven = AppRunnerInstance.latest("mavenless");
        instanceWithoutMaven.env.put("M2_HOME", "target/invalid-path");
        instanceWithoutMaven = instanceWithoutMaven.start();
        httpClient.registerRunner(instanceWithoutMaven.id(), instanceWithoutMaven.httpUrl(), 20);
        httpClient.registerRunner(stoppedOne.id() + "_2", stoppedOne.httpUrl(), 20);
        httpClient.registerRunner(stoppedOne.id() + "_3", stoppedOne.httpUrl(), 20);
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 20);
        stoppedOne.shutDown();

        AppRepo app1 = AppRepo.create("maven");

        assertThat(httpClient.createApp(app1.gitUrl(), "app1"), equalTo(201, containsString("app1")));

        instanceWithoutMaven.shutDown();
    }

    @Test
    public void failedCreationDoesNotPermanentlyIncrementUsage() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        httpClient.createApp("", "");
        AppRepo app1 = AppRepo.create("maven");
        assertThat(httpClient.createApp(app1.gitUrl(), "app1"), equalTo(201, containsString("app1")));
    }

    @Test
    public void runnerDetailsCanBeUpdated() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);

        // Can't POST to an existing runner
        assertThat(httpClient.registerRunner(latestAppRunnerWithoutNode.id(), oldAppRunner.httpUrl(), 100),
            equalTo(409, Matchers.containsString("A runner with the ID " + latestAppRunnerWithoutNode.id() + " already exists")));

        // Can't PUT to a non-existent runner
        assertThat(httpClient.updateRunner("non-existent-runner-id", latestAppRunnerWithoutNode.httpUrl(), 50),
            equalTo(404, Matchers.equalTo("No runner with the ID non-existent-runner-id exists")));

        // Can update a runner
        assertThat(httpClient.updateRunner(latestAppRunnerWithoutNode.id(), oldAppRunner.httpUrl(), 100),
            equalTo(200, Matchers.containsString("100")));

        JSONArray runners = new JSONObject(httpClient.getAppRunners().body()).getJSONArray("runners");
        assertThat(runners.length(), is(1));
        JSONAssert.assertEquals("{ 'id': '" + latestAppRunnerWithoutNode.id() + "', 'url': '" + oldAppRunner.httpUrl() + "', maxApps: 100 }", runners.get(0).toString(), JSONCompareMode.LENIENT);
    }

    @Test
    public void appsAddedToAnInstanceBeforeItJoinsTheClusterAreAvailable() throws Exception {
        AppRepo app1 = AppRepo.create("maven");
        RestClient direct = RestClient.create(latestAppRunnerWithoutNode.httpUrl().toString());
        direct.createApp(app1.gitUrl(), "app1");
        direct.deploy(app1.name);
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        var contentResponse = httpClient.get("/api/v1/apps/app1");
        assertThat(contentResponse.statusCode(), is(200));
        JSONObject json = new JSONObject(contentResponse.body());
        JSONAssert.assertEquals("{ 'name': 'app1', 'url': '" + httpClient.routerUrl + "/app1/' }", json, JSONCompareMode.LENIENT);

        httpClient.stop(app1.name);
    }

    @Test
    public void theRunnerAppsAPIGivesTheAppsOfARunner() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        AppRepo app1 = AppRepo.create("maven");
        httpClient.createApp(app1.gitUrl(), "my-app");

        var resp = httpClient.getRunnerApps(latestAppRunnerWithoutNode.id());
        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().allValues("Content-Type"), contains("application/json"));
        JSONObject appJson = new JSONObject(resp.body());
        assertThat(appJson.getJSONArray("apps").length(), is(1));
    }

    @Test
    public void theRunnerAppsAPIGivesTheSystemOfARunner() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        var resp = httpClient.getRunnerSystem(latestAppRunnerWithoutNode.id());
        assertThat(resp.statusCode(), is(200));
        assertThat(resp.headers().allValues("Content-Type"), contains("application/json"));
        JSONObject json = new JSONObject(resp.body());
        assertThat(json.getBoolean("appRunnerStarted"), is(true));
    }


    @Test
    public void onStartupTheAppsOfRunnersAreRemembered() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        AppRepo app1 = AppRepo.create("maven");
        httpClient.createApp(app1.gitUrl(), "my-app");
        httpClient.deploy("my-app");
        router.shutdown();
        router = createStartedRouter();

        Waiter waiter = Waiter.waitForApp(httpClient.targetURI(), "my-app");
        waiter.blockUntilReady();

        assertThat(httpClient.get("/my-app/"), equalTo(200, containsString("My Maven App")));
        assertThat(numberOfApps(latestAppRunnerWithoutNode), Matchers.equalTo(1));

        httpClient.stop("my-app");
    }

    @Test
    public void theSystemApiReturnsTheSetOfAllSampleAppsAndRunnerInfoAcrossAllTheInstances() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        httpClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 2);

        JSONObject system = new JSONObject(httpClient.getSystem().body());

        JSONArray runners = system.getJSONArray("runners");
        assertThat(runners.length(), is(2));
        JSONAssert.assertEquals("{ id: '" + latestAppRunnerWithoutNode.id() + "', url: '" + latestAppRunnerWithoutNode.httpUrl() + "', maxApps: 1, system: { appRunnerStarted: true } }", runners.getJSONObject(0), JSONCompareMode.LENIENT);
        JSONAssert.assertEquals("{ id: '" + oldAppRunner.id() + "', url: '" + oldAppRunner.httpUrl() + "', maxApps: 2, system: { appRunnerStarted: true } }", runners.getJSONObject(1), JSONCompareMode.LENIENT);

        assertThat(system.has("publicKeys"), is(true));

        JSONArray samples = system.getJSONArray("samples");
        Set<String> ids = new HashSet<>(); // a set to remove any duplicates
        for (Object sampleObj : samples) {
            JSONObject sample = (JSONObject) sampleObj;
            ids.add(SystemResource.getSampleID(sample));
            String zipUrl = sample.getString("url");
            assertThat(zipUrl, containsString(httpClient.routerUrl));
            var zip = httpClient.getAbsolute(zipUrl);
            MatcherAssert.assertThat(zipUrl, zip.statusCode(), CoreMatchers.is(200));
            MatcherAssert.assertThat(zipUrl, zip.headers().allValues("Content-Type"), contains("application/zip"));
        }

        assertThat(ids, hasItem(CoreMatchers.equalTo("maven")));
        assertThat(ids, hasItem(CoreMatchers.equalTo("nodejs")));
        assertThat("Number of sample apps", samples.length(), CoreMatchers.equalTo(ids.size()));
    }

    @Test
    public void runnerProxyCanTalkDirectlyToInstances() throws Exception {
        httpClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 10);
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 10);

        AppRepo app1 = AppRepo.create("maven");

        String appName = "runner-proxied-app";
        assertThat(proxyToLatestWithoutNodeClient.createApp(app1.gitUrl(), appName), equalTo(201, containsString(appName)));
        assertThat(proxyToLatestWithoutNodeClient.createApp(app1.gitUrl(), appName), equalTo(409, containsString("already an app")));
        assertThat(httpClient.createApp(app1.gitUrl(), appName), equalTo(409, containsString("already an app")));
        assertThat(proxyToOldClient.createApp(app1.gitUrl(), appName), equalTo(201, containsString(appName)));

        proxyToLatestWithoutNodeClient.deploy(appName);
        Waiter.waitForApp(httpClient.targetURI(), appName);

        assertThat(proxyToLatestWithoutNodeClient.get("/" + appName + "/headers?some-query=" + Mutils.urlEncode("hi: : / & \\ < >")),
            equalTo(200, startsWith("some-query: hi: : / & \\ < >")));

        assertThat(proxyToLatestWithoutNodeClient.deleteApp(appName), equalTo(200, containsString(appName)));
        assertThat(proxyToOldClient.deleteApp(appName), equalTo(200, containsString(appName)));
    }

}
