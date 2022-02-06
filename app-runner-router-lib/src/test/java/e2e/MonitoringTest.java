package e2e;

import com.danielflower.apprunner.router.lib.App;
import com.danielflower.apprunner.router.lib.Config;
import com.danielflower.apprunner.router.lib.mgmt.SystemInfo;
import io.muserver.MuServerBuilder;
import org.json.JSONObject;
import org.junit.*;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import scaffolding.AppRepo;
import scaffolding.AppRunnerInstance;
import scaffolding.RestClient;
import scaffolding.Waiter;

import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.danielflower.apprunner.router.lib.Config.dirPath;
import static scaffolding.Photocopier.projectRoot;

public class MonitoringTest {
    private static AppRunnerInstance instance;
    private App router;
    private RestClient client;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private int monitorPort;

    @BeforeClass
    public static void createRunners() {
        Assume.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS"))); // TODO: make this work on Github Actions (at time of writing, the test times out on github)
        instance = AppRunnerInstance.latest("app-runner-1").start();
    }

    @Before
    public void create() throws Exception {
        int routerHttpPort = AppRunnerInstance.getAFreePort();
        monitorPort = AppRunnerInstance.getAFreePort();
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("appserver.data.dir", dirPath(new File(projectRoot(), "target/e2e/router/" + System.currentTimeMillis())));
        env.put("apprunner.udp.listener.host", "localhost");
        env.put("apprunner.udp.listener.port", String.valueOf(monitorPort));
        router = new App(new Config(env));
        router.start(MuServerBuilder.muServer().withHttpPort(routerHttpPort));
        String host = SystemInfo.create().hostName;
        client = RestClient.create("http://" + host + ":" + routerHttpPort);
    }

    @After
    public void destroy() throws Exception {
        instance.clearApps();
        router.shutdown();
    }

    @AfterClass
    public static void deleteRunners() {
        if (instance != null) {
            instance.shutDown();
        }
    }


    @Test
    public void requestsArePublishedOverUDP() throws Exception {
        client.registerRunner(instance.id(), instance.httpUrl(), 1);
        AppRepo app1 = AppRepo.create("maven");

        client.createApp(app1.gitUrl(), "This_is-myApp");
        client.deploy("This_is-myApp");


        Waiter.waitForApp(client.targetURI(), "This_is-myApp");

        DatagramSocket serverSocket = new DatagramSocket(new InetSocketAddress("localhost", monitorPort));
        Future<String> result = executorService.submit(() -> {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            serverSocket.receive(packet);
            return new String(packet.getData(), 0, packet.getLength(), "UTF-8");
        });

        client.get("/This_is-myApp/?blah=aws&what=wat");

        JSONObject message = new JSONObject(result.get(30, TimeUnit.SECONDS));
        JSONAssert.assertEquals("{" +
                "app: 'This_is-myApp', " +
                "method: 'GET', " +
                "url: '" + client.routerUrl + "/This_is-myApp/?blah=aws&what=wat', " +
                "targetHost: '" + instance.httpUrl().getAuthority() + "'," +
                "status: 200" +
                "}"
            , message, JSONCompareMode.LENIENT);
        System.out.println("message = " + message.toString(4));
    }


}
