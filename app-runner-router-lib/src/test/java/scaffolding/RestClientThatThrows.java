package scaffolding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpResponse;

public class RestClientThatThrows {
    private static final Logger log = LoggerFactory.getLogger(RestClientThatThrows.class);

    private final RestClient underlying;

    public RestClientThatThrows(RestClient underlying) {
        this.underlying = underlying;
    }

    private HttpResponse<String> verify(HttpResponse<String> resp) {
        int status = resp.statusCode();
        if (status <= 99 || status >= 400) {
            throw new RuntimeException("Error returned: " + status + " with response: " + resp.body());
        }
        return resp;
    }

    public HttpResponse<String> createApp(String gitUrl, String appName) throws Exception {
        log.info("Create app " + appName + " at " + gitUrl);
        return verify(underlying.createApp(gitUrl, appName));
    }

    public HttpResponse<String> deploy(String app) throws Exception {
        log.info("Deploy app " + app);
        return verify(underlying.deploy(app));
    }

    public HttpResponse<String> stop(String app) throws Exception {
        return verify(underlying.stop(app));
    }

    public HttpResponse<String> deleteApp(String appName) throws Exception {
        return verify(underlying.deleteApp(appName));
    }

    public HttpResponse<String> homepage(String appName) throws Exception {
        return verify(underlying.homepage(appName));
    }

    public HttpResponse<String> get(String url) throws Exception {
        return verify(underlying.get(url));
    }

    public HttpResponse<String> getAbsolute(String absoluteUrl) throws Exception {
        log.info("Downloading " + absoluteUrl);
        return verify(underlying.getAbsolute(absoluteUrl));
    }

    public HttpResponse<byte[]> getAbsoluteByteArray(String absoluteUrl) throws Exception {
        log.info("Downloading " + absoluteUrl);
        HttpResponse<byte[]> resp = underlying.getAbsoluteByteArray(absoluteUrl);
        int status = resp.statusCode();
        if (status <= 99 || status >= 400) {
            throw new RuntimeException("Error returned: " + status + " with response: " + resp.body());
        }
        return resp;
    }

    public HttpResponse<String> registerRunner(String id, URI url, int maxInstances) throws Exception {
        log.info("Registering runner " + id + " at " + url);
        return verify(underlying.registerRunner(id, url, maxInstances));
    }

    public HttpResponse<String> getAppRunners() throws Exception {
        return verify(underlying.getAppRunners());
    }

    public HttpResponse<String> getSystem() throws Exception {
        return verify(underlying.getSystem());
    }

    public HttpResponse<String> getRunner(String id) throws Exception {
        return verify(underlying.getRunner(id));
    }

    public HttpResponse<String> deleteRunner(String id) throws Exception {
        return verify(underlying.deleteRunner(id));
    }

}
