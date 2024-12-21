package scaffolding;

import io.muserver.Mutils;
import io.muserver.murp.ReverseProxyBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static io.muserver.Mutils.urlEncode;
import static java.net.http.HttpRequest.BodyPublishers.noBody;

public class RestClient {

    public static final HttpClient client = ReverseProxyBuilder.createHttpClientBuilder(true).build();

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

    public HttpResponse<String> createApp(String gitUrl, String appName) throws Exception {

        var sb = new StringBuilder();
        sb.append("gitUrl=").append(Mutils.urlEncode(gitUrl));
        if (appName != null) {
            sb.append("&appName=").append(Mutils.urlEncode(appName));
        }
        return client.send(req(routerUrl + "/api/v1/apps")
            .method("POST", HttpRequest.BodyPublishers.ofString(sb.toString()))
            .header("content-type", "application/x-www-form-urlencoded")
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest.Builder req(String uri) {
        return req(URI.create(uri));
    }
    private static HttpRequest.Builder req(URI uri) {
        return HttpRequest.newBuilder(uri);
    }

    public HttpResponse<String> updateApp(String gitUrl, String appName) throws Exception {
        return client.send(req(routerUrl + "/api/v1/apps/" + appName)
            .method("PUT", HttpRequest.BodyPublishers.ofString("gitUrl=" + Mutils.urlEncode(gitUrl)))
            .header("content-type", "application/x-www-form-urlencoded")
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> deploy(String app) throws Exception {
        return client.send(req(routerUrl + "/api/v1/apps/" + app + "/deploy")
            .method("POST", noBody())
            .header("Accept", "application/json") // to simulate products like the Stash commit hook
            .header("content-type", "application/json")
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> stop(String app) throws Exception {
        return client.send(req(routerUrl + "/api/v1/apps/" + app + "/stop")
            .method("PUT", noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> deleteApp(String appName) throws Exception {
        return client.send(req(routerUrl + "/api/v1/apps/" + appName).method("DELETE", noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> homepage(String appName) throws Exception {
        return client.send(req(routerUrl + "/" + appName + "/").build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<String> get(String url) throws Exception {
        return getAbsolute(routerUrl + url);
    }

    public HttpResponse<String> getAbsolute(String absoluteUrl) throws Exception {
        return client.send(req(absoluteUrl).build(), HttpResponse.BodyHandlers.ofString());
    }

    public HttpResponse<byte[]> getAbsoluteByteArray(String absoluteUrl) throws Exception {
        return client.send(req(absoluteUrl).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public HttpResponse<String> registerRunner(String id, URI url, int maxInstances) throws Exception {
        String body = "id=" + urlEncode(id) + "&url=" + urlEncode(url.toString()) + "&maxApps=" + maxInstances;
        return client.send(req(routerUrl + "/api/v1/runners")
            .method("POST", HttpRequest.BodyPublishers.ofString(body))
            .header("content-type", "application/x-www-form-urlencoded")
            .build(), HttpResponse.BodyHandlers.ofString());
    }


    public HttpResponse<String> updateRunner(String id, URI url, int maxInstances) throws Exception {
        String body = "url=" + urlEncode(url.toString()) + "&maxApps=" + maxInstances;

        return client.send(req(routerUrl + "/api/v1/runners/" + id)
                .method("PUT", HttpRequest.BodyPublishers.ofString(body))
                .header("content-type", "application/x-www-form-urlencoded")
                .build(),
                HttpResponse.BodyHandlers.ofString());
    }


    public HttpResponse<String> getAppRunners() throws Exception {
        return get("/api/v1/runners");
    }

    public HttpResponse<String> getSystem() throws Exception {
        return get("/api/v1/system");
    }

    public HttpResponse<String> getRunner(String id) throws Exception {
        return get("/api/v1/runners/" + urlEncode(id));
    }

    public HttpResponse<String> getRunnerApps(String id) throws Exception {
        return get("/api/v1/runners/" + urlEncode(id) + "/apps");
    }

    public HttpResponse<String> getRunnerSystem(String id) throws Exception {
        return get("/api/v1/runners/" + urlEncode(id) + "/system");
    }

    public HttpResponse<String> deleteRunner(String id) throws Exception {
        return client.send(req(routerUrl + "/api/v1/runners/" + URLEncoder.encode(id, StandardCharsets.UTF_8))
            .method("DELETE", noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }
}
