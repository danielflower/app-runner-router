package e2e;

import com.danielflower.apprunner.router.App;
import com.danielflower.apprunner.router.Config;
import com.danielflower.apprunner.router.mgmt.SystemInfo;
import com.danielflower.apprunner.router.web.v1.SystemResource;
import org.eclipse.jetty.client.api.ContentResponse;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.danielflower.apprunner.router.Config.dirPath;
import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static scaffolding.ContentResponseMatcher.equalTo;

public class RoutingTest {
    private static AppRunnerInstance latestAppRunnerWithoutNode;
    private static AppRunnerInstance oldAppRunner;
    private App router;
    private RestClient httpClient;
    private Map<String, String> env;
    private RestClient httpsClient;

    @BeforeClass
    public static void createRunners() {
        AppRunnerInstance instanceWithoutNode = AppRunnerInstance.latest("app-runner-1");
        instanceWithoutNode.env.put("NODE_EXEC", "target/invalid-path");
        latestAppRunnerWithoutNode = instanceWithoutNode.start();
        oldAppRunner = AppRunnerInstance.versionOne("app-runner-2").start();
    }

    @Before
    public void create() throws Exception {
        int routerHttpPort = AppRunnerInstance.getAFreePort();
        int routerHttpsPort = AppRunnerInstance.getAFreePort();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("appserver.port", String.valueOf(routerHttpPort));
        env.put("appserver.https.port", String.valueOf(routerHttpsPort));
        env.put("appserver.data.dir", dirPath(new File("target/e2e/router/" + System.currentTimeMillis())));
        env.put("apprunner.keystore.path", dirPath(new File("local/test.keystore")));
        env.put("apprunner.keystore.password", "password");
        env.put("apprunner.keymanager.password", "password");
        env.put("allow.untrusted.instances", "true");
        this.env = env;
        router = new App(new Config(env));
        router.start();
        String host = SystemInfo.create().hostName;
        httpClient = RestClient.create("http://" + host + ":" + routerHttpPort);
        httpsClient = RestClient.create("https://" + host + ":" + routerHttpsPort);
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
        assertThat(httpClient.get("/"), equalTo(404, containsString("You can set a default app by setting the appserver.default.app.name property")));

        URI urlOfLatest = latestAppRunnerWithoutNode.httpsUrl();
        assertThat(httpClient.registerRunner(latestAppRunnerWithoutNode.id(), urlOfLatest, 1), equalTo(201, containsString(latestAppRunnerWithoutNode.id())));
        assertThat(httpClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 2), equalTo(201, containsString(oldAppRunner.id())));

        ContentResponse appRunners = httpClient.getAppRunners();
        assertThat(appRunners.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'runners': [" +
            "  { 'id': 'app-runner-1', 'url': '" + urlOfLatest.toString() + "', 'appCount': 0, 'maxApps': 1, " + "'systemUrl': '" + urlOfLatest.resolve("/api/v1/system").toString() + "', 'appsUrl': '" + urlOfLatest.resolve("/api/v1/apps").toString() + "' }," +
            "  { 'id': 'app-runner-2', 'url': '" + oldAppRunner.httpUrl().toString() + "', 'appCount': 0, 'maxApps': 2 }" +
            "]}", appRunners.getContentAsString(), JSONCompareMode.LENIENT);

        ContentResponse appRunner = httpClient.getRunner("app-runner-2");
        assertThat(appRunner.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'id': 'app-runner-2', 'url': '" + oldAppRunner.httpUrl().toString() + "', 'maxApps': 2 }"
            , appRunner.getContentAsString(), JSONCompareMode.LENIENT);

        assertThat(httpClient.deleteRunner(oldAppRunner.id()), equalTo(200, containsString(oldAppRunner.id())));
        assertThat(httpClient.getRunner(oldAppRunner.id()), equalTo(404, containsString(oldAppRunner.id())));
        assertThat(httpClient.deleteRunner(latestAppRunnerWithoutNode.id()), equalTo(200, containsString(latestAppRunnerWithoutNode.id())));
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

        String headersFromOne = httpsClient.get("/app1/headers").getContentAsString();

        httpsClient.createApp(app2.gitUrl(), "app2");
        httpsClient.deploy("app2");
        Waiter.waitForApp(httpsClient.targetURI(), "app2");

        String headersFromTwo = httpsClient.get("/app2/headers").getContentAsString();

        assertThat(headersFromOne, containsString(";proto=https\r\n"));
        assertThat(headersFromOne, containsString(";proto=http\r\n"));
        assertThat(headersFromOne.indexOf(";proto=https\r\n"), Matchers.lessThan(headersFromOne.lastIndexOf(";proto=http\r\n")));

        assertThat(headersFromOne, containsString("X-Forwarded-Proto:https\r\n"));
        assertThat(headersFromTwo, containsString("X-Forwarded-Proto:https\r\nX-Forwarded-Proto:https\r\n"));
        assertThat(headersFromOne, containsString("\r\nHost:" + httpsClient.targetURI().getAuthority() + "\r\n"));
        assertThat(headersFromTwo, containsString("\r\nHost:" + httpsClient.targetURI().getAuthority() + "\r\n"));

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
            assertThat(httpsClient.get("/" + appName + "/slow?millis=" + waitTime).getStatus(), is(504));
            httpsClient.stop(appName);
        }
    }

    private static void appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunners(RestClient client, AppRunnerInstance latestAppRunner, AppRunnerInstance oldAppRunner) throws Exception {
        AppRepo app1 = AppRepo.create("maven");
        AppRepo app2 = AppRepo.create("maven");

        ContentResponse app1Creation = client.createApp(app1.gitUrl(), "app1");
        assertThat("Error returned: " + app1Creation.getContentAsString(), app1Creation.getStatus(), is(201));
        JSONObject app1Json = new JSONObject(app1Creation.getContentAsString());
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
        JSONObject savedInfo = new JSONObject(client.getRunner(latestAppRunner.id()).getContentAsString());
        client.deleteRunner(latestAppRunner.id());
        client.registerRunner(latestAppRunner.id(), URI.create(savedInfo.getString("url")), savedInfo.getInt("maxApps"));
        assertThat(numberOfApps(latestAppRunner), is(1));


        // querying for all the apps returns a combined list
        ContentResponse appsResponse = client.get("/api/v1/apps");
        JSONAssert.assertEquals("{ " +
            "'appCount': 2," +
            "'apps': [ " +
            "{ 'name': 'app1', 'url': '" + client.targetURI().resolve("/app1/") + "' }," +
            "{ 'name': 'app2', 'url': '" + client.targetURI().resolve("/app2/") + "' }" +
            "] }", appsResponse.getContentAsString(), JSONCompareMode.STRICT_ORDER);

        assertThat(appsResponse.getStatus(), is(200));

        assertThat(client.get("/api/v1/swagger.json"), equalTo(200, containsString("/apps/{name}")));

        JSONAssert.assertEquals("{ runners: [" +
                "{ id: " + latestAppRunner.id() + ", 'appCount': 1 }," +
                "{ id: " + oldAppRunner.id() + ", 'appCount': 1 }" +
                "] }",
            client.getAppRunners().getContentAsString(), JSONCompareMode.LENIENT);

        client.stop("app1");
        client.stop("app2");
    }

    private static int numberOfApps(AppRunnerInstance appRunner) throws Exception {
        RestClient c = RestClient.create(appRunner.httpUrl().toString());
        JSONObject apps = new JSONObject(c.get("/api/v1/apps").getContentAsString());
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

        JSONArray runners = new JSONObject(httpClient.getAppRunners().getContentAsString()).getJSONArray("runners");
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
        ContentResponse contentResponse = httpClient.get("/api/v1/apps/app1");
        assertThat(contentResponse.getStatus(), is(200));
        JSONObject json = new JSONObject(contentResponse.getContentAsString());
        JSONAssert.assertEquals("{ 'name': 'app1', 'url': '" + httpClient.routerUrl + "/app1/' }", json, JSONCompareMode.LENIENT);

        httpClient.stop(app1.name);
    }

    @Test
    public void theRunnerAppsAPIGivesTheAppsOfARunner() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        AppRepo app1 = AppRepo.create("maven");
        httpClient.createApp(app1.gitUrl(), "my-app");

        ContentResponse resp = httpClient.getRunnerApps(latestAppRunnerWithoutNode.id());
        assertThat(resp.getStatus(), is(200));
        assertThat(resp.getHeaders().get("Content-Type"), Matchers.equalTo("application/json"));
        JSONObject appJson = new JSONObject(resp.getContentAsString());
        assertThat(appJson.getJSONArray("apps").length(), is(1));
    }

    @Test
    public void onStartupTheAppsOfRunnersAreRemembered() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        AppRepo app1 = AppRepo.create("maven");
        httpClient.createApp(app1.gitUrl(), "my-app");
        httpClient.deploy("my-app");
        router.shutdown();
        router = new App(new Config(env));
        router.start();

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

        JSONObject system = new JSONObject(httpClient.getSystem().getContentAsString());

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
            ContentResponse zip = httpClient.getAbsolute(zipUrl);
            MatcherAssert.assertThat(zipUrl, zip.getStatus(), CoreMatchers.is(200));
            MatcherAssert.assertThat(zipUrl, zip.getHeaders().get("Content-Type"), CoreMatchers.is("application/zip"));
        }

        assertThat(ids, hasItem(CoreMatchers.equalTo("maven")));
        assertThat(ids, hasItem(CoreMatchers.equalTo("nodejs")));
        assertThat("Number of sample apps", samples.length(), CoreMatchers.equalTo(ids.size()));
    }

}
