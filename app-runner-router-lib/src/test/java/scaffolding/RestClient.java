package scaffolding;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.net.URI;
import java.net.URLEncoder;

import static io.muserver.Mutils.urlEncode;

public class RestClient {

    public static final HttpClient client;

    static {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm("HTTPS");
        HttpClient c = new HttpClient(sslContextFactory);
        c.setConnectTimeout(10000);
        try {
            c.start();
        } catch (Exception e) {
            throw new RuntimeException("Unable to make client", e);
        }
        client = c;
    }

    public static RestClient create(String appRunnerUrl) {
        if (appRunnerUrl.endsWith("/")) {
            appRunnerUrl = appRunnerUrl.substring(0, appRunnerUrl.length() - 1);
        }
        return new RestClient(appRunnerUrl.toLowerCase());
    }

    public final String routerUrl;

    private RestClient(String routerUrl) {
        this.routerUrl = routerUrl;
    }

    public URI targetURI() {
        return URI.create(routerUrl);
    }

    public ContentResponse createApp(String gitUrl, String appName) throws Exception {
        Fields fields = new Fields();
        fields.add("gitUrl", gitUrl);
        if (appName != null) {
            fields.add("appName", appName);
        }
        return client.POST(routerUrl + "/api/v1/apps")
            .content(new FormContentProvider(fields)).send();
    }

    public ContentResponse updateApp(String gitUrl, String appName) throws Exception {
        Fields fields = new Fields();
        fields.add("gitUrl", gitUrl);
        return client.newRequest(routerUrl + "/api/v1/apps/" + appName)
            .method("PUT")
            .content(new FormContentProvider(fields)).send();
    }

    public ContentResponse deploy(String app) throws Exception {
        return client.POST(routerUrl + "/api/v1/apps/" + app + "/deploy")
            .header("Accept", "application/json") // to simulate products like the Stash commit hook
            .send();
    }

    public ContentResponse stop(String app) throws Exception {
        return client.newRequest(routerUrl + "/api/v1/apps/" + app + "/stop").method("PUT").send();
    }

    public ContentResponse deleteApp(String appName) throws Exception {
        return client.newRequest(routerUrl + "/api/v1/apps/" + appName).method("DELETE").send();
    }

    public ContentResponse homepage(String appName) throws Exception {
        return client.GET(routerUrl + "/" + appName + "/");
    }

    public ContentResponse get(String url) throws Exception {
        return getAbsolute(routerUrl + url);
    }

    public ContentResponse getAbsolute(String absoluteUrl) throws Exception {
        return client.GET(absoluteUrl);
    }

    public ContentResponse registerRunner(String id, URI url, int maxInstances) throws Exception {
        Fields fields = new Fields();
        fields.add("id", id);
        fields.add("url", url.toString());
        fields.add("maxApps", String.valueOf(maxInstances));
        return client.POST(routerUrl + "/api/v1/runners")
            .content(new FormContentProvider(fields)).send();
    }


    public ContentResponse updateRunner(String id, URI url, int maxInstances) throws Exception {
        Fields fields = new Fields();
        fields.add("url", url.toString());
        fields.add("maxApps", String.valueOf(maxInstances));
        return client.newRequest(routerUrl + "/api/v1/runners/" + id)
            .method("PUT")
            .content(new FormContentProvider(fields)).send();
    }


    public ContentResponse getAppRunners() throws Exception {
        return get("/api/v1/runners");
    }

    public ContentResponse getSystem() throws Exception {
        return get("/api/v1/system");
    }

    public ContentResponse getRunner(String id) throws Exception {
        return get("/api/v1/runners/" + urlEncode(id));
    }

    public ContentResponse getRunnerApps(String id) throws Exception {
        return get("/api/v1/runners/" + urlEncode(id) + "/apps");
    }

    public ContentResponse getRunnerSystem(String id) throws Exception {
        return get("/api/v1/runners/" + urlEncode(id) + "/system");
    }

    public ContentResponse deleteRunner(String id) throws Exception {
        return client.newRequest(routerUrl + "/api/v1/runners/" + URLEncoder.encode(id, "UTF-8")).method("DELETE").send();
    }
}
