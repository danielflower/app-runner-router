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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static com.danielflower.apprunner.router.Config.dirPath;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
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

        clearApps(latestAppRunnerWithoutNode);
        clearApps(oldAppRunner);
    }

    private void clearApps(AppRunnerInstance instance) throws Exception {
        RestClient client = RestClient.create(instance.httpUrl().toString());
        JSONObject apps = new JSONObject(client.get("/api/v1/apps").getContentAsString());
        for (Object o : apps.getJSONArray("apps")) {
            JSONObject app = (JSONObject) o;
            client.deleteApp(app.getString("name"));
        }
    }

    @After
    public void destroy() throws Exception {
        try {
            router.shutdown();
        } finally {
            httpClient.close();
        }
    }

    @AfterClass
    public static void deleteRunners() {
        oldAppRunner.shutDown();
        latestAppRunnerWithoutNode.shutDown();
    }

    @Test
    public void appRunnersCanBeRegisteredAndDeregisteredWithTheRestAPIWithAnHTTPRouter() throws Exception {
        assertThat(httpClient.get("/"), equalTo(404, containsString("You can set a default app by setting the appserver.default.app.name property")));

        assertThat(httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpsUrl(), 1), equalTo(201, containsString(latestAppRunnerWithoutNode.id())));
        assertThat(httpClient.registerRunner(oldAppRunner.id(), oldAppRunner.httpUrl(), 2), equalTo(201, containsString(oldAppRunner.id())));

        ContentResponse appRunners = httpClient.getAppRunners();
        assertThat(appRunners.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'runners': [" +
            "  { 'id': 'app-runner-1', 'url': '" + latestAppRunnerWithoutNode.httpsUrl().toString() + "', 'maxApps': 1, 'systemUrl': '" + latestAppRunnerWithoutNode.httpsUrl().resolve("/api/v1/system").toString() + "' }," +
            "  { 'id': 'app-runner-2', 'url': '" + oldAppRunner.httpUrl().toString() + "', 'maxApps': 2 }" +
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

        assertThat(client.get("/app2/"), equalTo(200, containsString("My Maven App")));

        // the apps should be evenly distributed
        assertThat(numberOfApps(latestAppRunner), is(1));
        assertThat(numberOfApps(oldAppRunner), is(1));

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

        client.stop("app1");
        client.stop("app2");
    }

    private static int numberOfApps(AppRunnerInstance appRunner) throws Exception {
        int numberOfApps;
        try (RestClient c = RestClient.create(appRunner.httpUrl().toString())) {
            JSONObject apps = new JSONObject(c.get("/api/v1/apps").getContentAsString());
            numberOfApps = apps.getJSONArray("apps").length();
        }
        return numberOfApps;
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
    public void failedCreationDoesNotPermanentlyIncrementUsage() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        httpClient.createApp("", "");
        AppRepo app1 = AppRepo.create("maven");
        assertThat(httpClient.createApp(app1.gitUrl(), "app1"), equalTo(201, containsString("app1")));
    }

    @Test
    public void runnerDetailsCanBeUpdated() throws Exception {
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        ContentResponse response = httpClient.registerRunner(latestAppRunnerWithoutNode.id(), oldAppRunner.httpUrl(), 100);
        assertThat(response, equalTo(200, Matchers.containsString("100")));

        JSONArray runners = new JSONObject(httpClient.getAppRunners().getContentAsString()).getJSONArray("runners");
        assertThat(runners.length(), is(1));
        JSONAssert.assertEquals("{ 'id': '" + latestAppRunnerWithoutNode.id() + "', 'url': '" + oldAppRunner.httpUrl() + "', maxApps: 100 }", runners.get(0).toString(), JSONCompareMode.LENIENT);
    }

    @Test
    public void appsAddedToAnInstanceBeforeItJoinsTheClusterAreAvailable() throws Exception {
        AppRepo app1 = AppRepo.create("maven");
        try (RestClient direct = RestClient.create(latestAppRunnerWithoutNode.httpUrl().toString())) {
            direct.createApp(app1.gitUrl(), "app1");
            direct.deploy(app1.name);
        }
        httpClient.registerRunner(latestAppRunnerWithoutNode.id(), latestAppRunnerWithoutNode.httpUrl(), 1);
        ContentResponse contentResponse = httpClient.get("/api/v1/apps/app1");
        assertThat(contentResponse.getStatus(), is(200));
        JSONObject json = new JSONObject(contentResponse.getContentAsString());
        JSONAssert.assertEquals("{ 'name': 'app1', 'url': '" + httpClient.routerUrl + "/app1/' }", json, JSONCompareMode.LENIENT);

        httpClient.stop(app1.name);
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
