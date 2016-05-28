package e2e;

import com.danielflower.apprunner.router.App;
import com.danielflower.apprunner.router.Config;
import com.danielflower.apprunner.router.io.Waiter;
import com.danielflower.apprunner.router.web.WebServer;
import org.eclipse.jetty.client.api.ContentResponse;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import scaffolding.AppRepo;
import scaffolding.AppRunnerInstance;
import scaffolding.RestClient;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import static com.danielflower.apprunner.router.FileSandbox.dirPath;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static scaffolding.ContentResponseMatcher.equalTo;

public class RoutingTest {
    private AppRunnerInstance appRunner1;
    private AppRunnerInstance appRunner2;
    private App router;
    private RestClient client;
    private int routerPort;

    @Before
    public void create() throws Exception {
        appRunner1 = new AppRunnerInstance("app-runner-1").start();
        appRunner2 = new AppRunnerInstance("app-runner-2").start();

        routerPort = WebServer.getAFreePort();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("appserver.port", String.valueOf(routerPort));
        env.put("appserver.data.dir", dirPath(new File("target/e2e/router/" + System.currentTimeMillis())));
        router = new App(new Config(env));
        router.start();
        client = RestClient.create("http://localhost:" + routerPort);
    }

    @After
    public void destroy() {
        try {
            router.shutdown();
        } finally {
            appRunner1.shutDown();
            appRunner2.shutDown();
            client.stop();
        }
    }

    @Test
    public void appsAreRoutedToIndividualAppRunners() throws Exception {
        assertThat(client.get("/"), equalTo(404, containsString("You can set a default app by setting the appserver.default.app.name property")));

        assertThat(client.registerRunner(appRunner1.id(), appRunner1.url(), 1), equalTo(201, containsString(appRunner1.id())));
        assertThat(client.registerRunner(appRunner2.id(), appRunner2.url(), 2), equalTo(201, containsString(appRunner2.id())));


        ContentResponse appRunners = client.getAppRunners();
        assertThat(appRunners.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'runners': [" +
            "  { 'id': 'app-runner-1', 'url': '" + appRunner1.url().toString() + "', 'maxApps': 1 }," +
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
//        AppRepo app2 = AppRepo.create("maven");

        ContentResponse app1Creation = client.createApp(app1.gitUrl(), "app1");
        Thread.sleep(10000000);
        assertThat(app1Creation.getStatus(), is(201));
        JSONObject app1Json = new JSONObject(app1Creation.getContentAsString());
        System.out.println("app1Json = " + app1Json.toString(4));

        client.deploy("app1");
        Waiter.waitForApp("app1", routerPort);
        assertThat(client.get("/app1/"), equalTo(200, containsString("My Maven App")));
    }

}
