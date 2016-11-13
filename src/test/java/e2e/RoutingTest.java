package e2e;

import com.danielflower.apprunner.router.App;
import com.danielflower.apprunner.router.Config;
import com.danielflower.apprunner.router.web.v1.SystemResource;
import org.eclipse.jetty.client.api.ContentResponse;
import org.hamcrest.CoreMatchers;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
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
    private AppRunnerInstance appRunner1;
    private AppRunnerInstance appRunner2;
    private App router;
    private RestClient client;
    private int routerPort;
    private Map<String, String> env;

    @Before
    public void create() throws Exception {
        AppRunnerInstance instanceWithoutNode = AppRunnerInstance.latest("app-runner-1");
        instanceWithoutNode.env.put("NODE_EXEC", "target/invalid-path");
        appRunner1 = instanceWithoutNode.start();
        appRunner2 = AppRunnerInstance.versionOne("app-runner-2").start();

        routerPort = AppRunnerInstance.getAFreePort();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("appserver.port", String.valueOf(routerPort));
        env.put("appserver.data.dir", dirPath(new File("target/e2e/router/" + System.currentTimeMillis())));
        env.put("apprunner.keystore.path", dirPath(new File("local/test.keystore")));
        env.put("apprunner.keystore.password", "password");
        env.put("apprunner.keymanager.password", "password");
        this.env = env;
        router = new App(new Config(env));
        router.start();
        client = RestClient.create("http://localhost:" + routerPort);
    }

    @After
    public void destroy() throws Exception {
        try {
            router.shutdown();
        } finally {
            appRunner1.shutDown();
            appRunner2.shutDown();
            client.close();
        }
    }

    @Test
    public void appRunnersCanBeRegisteredAndDeregisteredWithTheRestAPI() throws Exception {
        assertThat(client.get("/"), equalTo(404, containsString("You can set a default app by setting the appserver.default.app.name property")));

        assertThat(client.registerRunner(appRunner1.id(), appRunner1.url(), 1), equalTo(201, containsString(appRunner1.id())));
        assertThat(client.registerRunner(appRunner2.id(), appRunner2.url(), 2), equalTo(201, containsString(appRunner2.id())));

        ContentResponse appRunners = client.getAppRunners();
        assertThat(appRunners.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'runners': [" +
            "  { 'id': 'app-runner-1', 'url': '" + appRunner1.url().toString() + "', 'maxApps': 1, 'systemUrl': '" + appRunner1.url().resolve("/api/v1/system").toString() + "' }," +
            "  { 'id': 'app-runner-2', 'url': '" + appRunner2.url().toString() + "', 'maxApps': 2 }" +
            "]}", appRunners.getContentAsString(), JSONCompareMode.LENIENT);

        ContentResponse appRunner = client.getRunner("app-runner-2");
        assertThat(appRunner.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'id': 'app-runner-2', 'url': '" + appRunner2.url().toString() + "', 'maxApps': 2 }"
            , appRunner.getContentAsString(), JSONCompareMode.LENIENT);

        assertThat(client.deleteRunner(appRunner2.id()), equalTo(200, containsString(appRunner2.id())));
        assertThat(client.getRunner(appRunner2.id()), equalTo(404, containsString(appRunner2.id())));
        assertThat(client.deleteRunner(appRunner1.id()), equalTo(200, containsString(appRunner1.id())));
    }

    @Test
    public void appsCanBeAddedToTheRouterAndItWillDistributeThoseToTheRunners() throws Exception {
        client.registerRunner(appRunner1.id(), appRunner1.url(), 1);
        client.registerRunner(appRunner2.id(), appRunner2.url(), 1);

        AppRepo app1 = AppRepo.create("maven");
        AppRepo app2 = AppRepo.create("maven");

        ContentResponse app1Creation = client.createApp(app1.gitUrl(), "app1");
        assertThat(app1Creation.getStatus(), is(201));
        JSONObject app1Json = new JSONObject(app1Creation.getContentAsString());
        assertThat(app1Json.getString("url"), startsWith(client.routerUrl));

        client.deploy("app1");
        Waiter.waitForApp("app1", routerPort);
        assertThat(client.get("/app1/"), equalTo(200, containsString("My Maven App")));

        client.createApp(app2.gitUrl(), "app2");
        client.deploy("app2");

        assertThat(client.get("/app2/"), equalTo(200, containsString("My Maven App")));

        // the apps should be evenly distributed
        assertThat(numberOfApps(appRunner1), is(1));
        assertThat(numberOfApps(appRunner2), is(1));

        // querying for all the apps returns a combined list
        ContentResponse appsResponse = client.get("/api/v1/apps");
        JSONAssert.assertEquals("{ 'apps': [ " +
            "{ 'name': 'app1', 'url': 'http://localhost:" + routerPort + "/app1/' }," +
            "{ 'name': 'app2', 'url': 'http://localhost:" + routerPort + "/app2/' }" +
            "] }", appsResponse.getContentAsString(), JSONCompareMode.STRICT_ORDER);

        assertThat(appsResponse.getStatus(), is(200));

        assertThat(client.get("/api/v1/swagger.json"), equalTo(200, containsString("/apps/{name}")));

        client.stop("app1");
        client.stop("app2");
    }

    private static int numberOfApps(AppRunnerInstance appRunner) throws Exception {
        int numberOfApps;
        try (RestClient c = RestClient.create(appRunner.url().toString())) {
            JSONObject apps = new JSONObject(c.get("/api/v1/apps").getContentAsString());
            numberOfApps = apps.getJSONArray("apps").length();
        }
        return numberOfApps;
    }

    @Test
    public void appsAreNotAddedToRunnersThatAreAtMaxCapacity() throws Exception {
        client.registerRunner(appRunner1.id(), appRunner1.url(), 1);

        AppRepo app1 = AppRepo.create("maven");
        AppRepo app2 = AppRepo.create("maven");

        client.createApp(app1.gitUrl(), "app1");

        assertThat(client.createApp(app2.gitUrl(), "app2"), equalTo(503, containsString("There are no App Runner instances with free capacity")));
        client.deleteApp("app1");

        assertThat(client.createApp(app2.gitUrl(), "app2"), equalTo(201, containsString("app2")));
    }

    @Test
    public void failedCreationDoesNotPermanentlyIncrementUsage() throws Exception {
        client.registerRunner(appRunner1.id(), appRunner1.url(), 1);
        client.createApp("", "");
        AppRepo app1 = AppRepo.create("maven");
        assertThat(client.createApp(app1.gitUrl(), "app1"), equalTo(201, containsString("app1")));
    }

    @Test
    public void runnerDetailsCanBeUpdated() throws Exception {
        client.registerRunner(appRunner1.id(), appRunner1.url(), 1);
        ContentResponse response = client.registerRunner(appRunner1.id(), appRunner2.url(), 100);
        assertThat(response, equalTo(200, Matchers.containsString("100")));

        JSONArray runners = new JSONObject(client.getAppRunners().getContentAsString()).getJSONArray("runners");
        assertThat(runners.length(), is(1));
        JSONAssert.assertEquals("{ 'id': '" + appRunner1.id() + "', 'url': '" + appRunner2.url() + "', maxApps: 100 }", runners.get(0).toString(), JSONCompareMode.LENIENT);
    }

    @Test
    public void appsAddedToAnInstanceBeforeItJoinsTheClusterAreAvailable() throws Exception {
        AppRepo app1 = AppRepo.create("maven");
        try (RestClient direct = RestClient.create(appRunner1.url().toString())) {
            direct.createApp(app1.gitUrl(), "app1");
            direct.deploy(app1.name);
        }
        client.registerRunner(appRunner1.id(), appRunner1.url(), 1);
        ContentResponse contentResponse = client.get("/api/v1/apps/app1");
        assertThat(contentResponse.getStatus(), is(200));
        JSONObject json = new JSONObject(contentResponse.getContentAsString());
        JSONAssert.assertEquals("{ 'name': 'app1', 'url': '" + client.routerUrl + "/app1/' }", json, JSONCompareMode.LENIENT);

        client.stop(app1.name);
    }

    @Test
    public void onStartupTheAppsOfRunnersAreRemembered() throws Exception {
        client.registerRunner(appRunner1.id(), appRunner1.url(), 1);
        AppRepo app1 = AppRepo.create("maven");
        client.createApp(app1.gitUrl(), "my-app");
        client.deploy("my-app");
        router.shutdown();
        router = new App(new Config(env));
        router.start();

        Waiter waiter = Waiter.waitForApp("my-app", routerPort);
        waiter.blockUntilReady();
        assertThat(client.get("/my-app/"), equalTo(200, containsString("My Maven App")));

        client.stop("my-app");
    }

    @Test
    public void theSystemApiReturnsTheSetOfAllSampleAppsAndRunnerInfoAcrossAllTheInstances() throws Exception {
        client.registerRunner(appRunner1.id(), appRunner1.url(), 1);
        client.registerRunner(appRunner2.id(), appRunner2.url(), 2);

        JSONObject system = new JSONObject(client.getSystem().getContentAsString());
//        System.out.println(system.toString(4));

        JSONArray runners = system.getJSONArray("runners");
        assertThat(runners.length(), is(2));
        JSONAssert.assertEquals("{ id: '" + appRunner1.id() + "', url: '" + appRunner1.url() + "', maxApps: 1, system: { appRunnerStarted: true } }", runners.getJSONObject(0), JSONCompareMode.LENIENT);
        JSONAssert.assertEquals("{ id: '" + appRunner2.id() + "', url: '" + appRunner2.url() + "', maxApps: 2, system: { appRunnerStarted: true } }", runners.getJSONObject(1), JSONCompareMode.LENIENT);

        assertThat(system.has("publicKeys"), is(true));

        JSONArray samples = system.getJSONArray("samples");
        Set<String> ids = new HashSet<>(); // a set to remove any duplicates
        for (Object sampleObj : samples) {
            JSONObject sample = (JSONObject) sampleObj;
            ids.add(SystemResource.getSampleID(sample));
            String zipUrl = sample.getString("url");
            assertThat(zipUrl, containsString(":" + routerPort + "/"));
            ContentResponse zip = client.getAbsolute(zipUrl);
            MatcherAssert.assertThat(zipUrl, zip.getStatus(), CoreMatchers.is(200));
            MatcherAssert.assertThat(zipUrl, zip.getHeaders().get("Content-Type"), CoreMatchers.is("application/zip"));
        }

        assertThat(ids, hasItem(CoreMatchers.equalTo("maven")));
        assertThat(ids, hasItem(CoreMatchers.equalTo("nodejs")));
        assertThat("Number of sample apps", samples.length(), CoreMatchers.equalTo(ids.size()));
    }

}
