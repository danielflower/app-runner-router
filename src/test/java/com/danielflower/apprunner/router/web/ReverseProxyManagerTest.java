package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.MapManager;
import com.danielflower.apprunner.router.monitoring.RequestInfo;
import io.muserver.*;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.*;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class ReverseProxyManagerTest {

    @Rule
    public final JUnitRuleMockery context = new JUnitRuleMockery();
    private final MapManager mapManager = context.mock(MapManager.class);

    private ProxyMap proxyMap = new ProxyMap();
    private File configFile = new File("target/clusters/" + System.currentTimeMillis() + "/cluster.json");
    private Cluster cluster = Cluster.load(configFile, mapManager);
    private ReverseProxyManager reverseProxyManager = new ReverseProxyManager(cluster, proxyMap, null);

    public ReverseProxyManagerTest() throws IOException {
    }

    @Test
    public void returnsNullIfNoValueInProxyMap() {
        assertThat(reverseProxyManager.mapFrom(request("/blah")), is(nullValue()));
    }

    @Test
    public void returnsAProxiedAddressIfInMap() {
        proxyMap.add("my-app", URI.create("http://localhost:12345/my-app"));
        assertThat(reverseProxyManager.mapFrom(request("/my-app")), is(URI.create("http://localhost:12345/my-app")));
        assertThat(reverseProxyManager.mapFrom(request("/my-app/")), is(URI.create("http://localhost:12345/my-app/")));
        assertThat(reverseProxyManager.mapFrom(request("/my-app/some/thing/and%20this+%26+that")), is(URI.create("http://localhost:12345/my-app/some/thing/and%20this+%26+that")));
    }

    @Test
    public void queryStringsSurviveProxying() {
        proxyMap.add("my-app", URI.create("http://localhost:12345/my-app"));
        assertThat(reverseProxyManager.mapFrom(request("/my-app?blah=ha")), is(URI.create("http://localhost:12345/my-app?blah=ha")));
        assertThat(reverseProxyManager.mapFrom(request("/my-app/?blah=ha")), is(URI.create("http://localhost:12345/my-app/?blah=ha")));
        assertThat(reverseProxyManager.mapFrom(request("/my-app/some/thing?blah=ha+ha%20ha")), is(URI.create("http://localhost:12345/my-app/some/thing?blah=ha+ha%20ha")));
    }

    @Test
    public void requestInfoIsAppendedToTheRequest() {
        proxyMap.add("my-app", URI.create("http://localhost:12345/my-app"));
        MuRequest request = request("/my-app/some/thing?blah=ha");
        reverseProxyManager.mapFrom(request);
        RequestInfo info = ReverseProxyManager.getInfo(request);
        assertThat(info.appName, equalTo("my-app"));
        assertThat(info.method, equalTo("GET"));
        assertThat(info.targetHost, equalTo("localhost:12345"));
        assertThat(info.url, equalTo("http://localhost/my-app/some/thing?blah=ha"));
    }

    private MuRequest request(String path) {
        URI url = URI.create("http://localhost" + path);
        return new MuRequest() {

            private final Map<String, Object> attrs = new LinkedHashMap<>();

            @Override
            public String contentType() {
                return null;
            }

            @Override
            public long startTime() {
                return 0;
            }

            @Override
            public Method method() {
                return Method.GET;
            }

            @Override
            public URI uri() {
                return url;
            }

            @Override
            public URI serverURI() {
                return url;
            }

            @Override
            public Headers headers() {
                return null;
            }

            @Override
            public Optional<InputStream> inputStream() {
                return Optional.empty();
            }

            @Override
            public String readBodyAsString() throws IOException {
                return null;
            }

            @Override
            public List<UploadedFile> uploadedFiles(String name) throws IOException {
                return null;
            }

            @Override
            public UploadedFile uploadedFile(String name) throws IOException {
                return null;
            }

            @Override
            public RequestParameters query() {
                return null;
            }

            @Override
            public RequestParameters form() throws IOException {
                return null;
            }

            @Override
            public String parameter(String name) {
                return null;
            }

            @Override
            public List<String> parameters(String name) {
                return null;
            }

            @Override
            public String formValue(String name) throws IOException {
                return null;
            }

            @Override
            public List<String> formValues(String name) throws IOException {
                return null;
            }

            @Override
            public Set<Cookie> cookies() {
                return null;
            }

            @Override
            public Optional<String> cookie(String name) {
                return Optional.empty();
            }

            @Override
            public String contextPath() {
                return null;
            }

            @Override
            public String relativePath() {
                return path;
            }

            @Override
            public Object state() {
                return null;
            }

            @Override
            public void state(Object value) {

            }

            @Override
            public Object attribute(String key) {
                return attrs.get(key);
            }

            @Override
            public void attribute(String key, Object value) {
                attrs.put(key, value);
            }

            @Override
            public AsyncHandle handleAsync() {
                return null;
            }

            @Override
            public String remoteAddress() {
                return "127.0.0.2";
            }

            @Override
            public MuServer server() {
                return null;
            }

            @Override
            public boolean isAsync() {
                return false;
            }

            @Override
            public String protocol() {
                return null;
            }
        };
    }
}
