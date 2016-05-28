package e2e;

import com.danielflower.apprunner.router.App;
import com.danielflower.apprunner.router.Config;
import com.danielflower.apprunner.router.web.WebServer;
import org.eclipse.jetty.client.api.ContentResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(RoutingTest.class);

    private AppRunnerInstance appRunner1;
    private AppRunnerInstance appRunner2;
    private App router;
    private RestClient client;

    @Before
    public void create() throws Exception {
        appRunner1 = new AppRunnerInstance("app-runner-1").start();
        appRunner2 = new AppRunnerInstance("app-runner-2").start();

        int routerPort = WebServer.getAFreePort();
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
        ContentResponse resp = client.get("/");
        assertThat(resp, equalTo(404, containsString("You can set a default app by setting the appserver.default.app.name property")));

        assertThat(client.registerAppRunner(appRunner1.id(), appRunner1.url(), 1), equalTo(201, containsString(appRunner1.id())));
        assertThat(client.registerAppRunner(appRunner2.id(), appRunner2.url(), 2), equalTo(201, containsString(appRunner2.id())));


        ContentResponse appRunners = client.getAppRunners();
        assertThat(appRunners.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'runners': [" +
            "  { 'id': 'app-runner-1', 'url': '" + appRunner1.url().toString() + "', 'maxApps': 1 }," +
            "  { 'id': 'app-runner-2', 'url': '" + appRunner2.url().toString() + "', 'maxApps': 2 }" +
            "]}", appRunners.getContentAsString(), JSONCompareMode.LENIENT);

        ContentResponse appRunner = client.getAppRunner("app-runner-2");
        assertThat(appRunner.getStatus(), is(200));
        JSONAssert.assertEquals("{ 'id': 'app-runner-2', 'url': '" + appRunner2.url().toString() + "', 'maxApps': 2 }"
            , appRunner.getContentAsString(), JSONCompareMode.LENIENT);

        assertThat(client.deleteRunner(appRunner2.id()), equalTo(200, containsString(appRunner2.id())));
        assertThat(client.getAppRunner(appRunner2.id()), equalTo(404, containsString(appRunner2.id())));
    }


}
