package scaffolding;

import org.eclipse.jetty.client.api.ContentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

public class RestClientThatThrows implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RestClientThatThrows.class);

    private final RestClient underlying;

    public RestClientThatThrows(RestClient underlying) {
        this.underlying = underlying;
    }

    private ContentResponse verify(ContentResponse resp) {
        int status = resp.getStatus();
        if (status <= 99 || status >= 400) {
            throw new RuntimeException("Error returned: " + status + " with response: " + resp.getContentAsString());
        }
        return resp;
    }

    public ContentResponse createApp(String gitUrl, String appName) throws Exception {
        log.info("Create app " + appName + " at " + gitUrl);
        return verify(underlying.createApp(gitUrl, appName));
    }

    public ContentResponse deploy(String app) throws Exception {
        log.info("Deploy app " + app);
        return verify(underlying.deploy(app));
    }

    public ContentResponse stop(String app) throws Exception {
        return verify(underlying.stop(app));
    }

    public ContentResponse deleteApp(String appName) throws Exception {
        return verify(underlying.deleteApp(appName));
    }

    public ContentResponse homepage(String appName) throws Exception {
        return verify(underlying.homepage(appName));
    }

    public ContentResponse get(String url) throws Exception {
        return verify(underlying.get(url));
    }

    public ContentResponse getAbsolute(String absoluteUrl) throws Exception {
        log.info("Downloading " + absoluteUrl);
        return verify(underlying.getAbsolute(absoluteUrl));
    }

    public ContentResponse registerRunner(String id, URI url, int maxInstances) throws Exception {
        log.info("Registering runner " + id + " at " + url);
        return verify(underlying.registerRunner(id, url, maxInstances));
    }

    public ContentResponse getAppRunners() throws Exception {
        return verify(underlying.getAppRunners());
    }

    public ContentResponse getSystem() throws Exception {
        return verify(underlying.getSystem());
    }

    public ContentResponse getRunner(String id) throws Exception {
        return verify(underlying.getRunner(id));
    }

    public ContentResponse deleteRunner(String id) throws Exception {
        return verify(underlying.deleteRunner(id));
    }

    public void close() throws Exception {
        underlying.close();
    }
}
