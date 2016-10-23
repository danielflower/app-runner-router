package com.danielflower.apprunner.router.mgmt;

import com.danielflower.apprunner.router.web.ProxyMap;
import org.jmock.Expectations;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.Assert.assertThat;

public class ClusterTest {

    @Rule public final JUnitRuleMockery context = new JUnitRuleMockery();
    private final MapManager mapManager = context.mock(MapManager.class);
    private File configFile = new File("target/clusters/" + System.currentTimeMillis() + "/cluster.json");
    private Cluster cluster = Cluster.load(configFile, mapManager);
    private Runner instanceOne = new Runner("one", URI.create("http://localhost:8080"), 2);
    private Runner instanceTwo = new Runner("two", URI.create("http://localhost:9999"), 10);
    private HttpServletRequest clientRequest = context.mock(HttpServletRequest.class);

    public ClusterTest() throws IOException, InterruptedException {
    }

    @Before
    public void allowStuff() throws Exception {
        context.checking(new Expectations() {{
            allowing(mapManager).loadRunner(with(any(HttpServletRequest.class)), with(any(Runner.class)));
            allowing(mapManager).removeRunner(with(instanceOne));
        }});
    }

    @Test
    public void appsCanBeSavedAndRetrievedAndDeleted() throws Exception {
        assertThat(cluster.getRunners(), is(empty()));
        cluster.addRunner(clientRequest, instanceOne);
        cluster.addRunner(clientRequest, instanceTwo);
        assertThat(cluster.getRunners(), contains(instanceOne, instanceTwo));
        cluster.deleteRunner(instanceOne);

        Cluster another = Cluster.load(configFile, mapManager);
        assertThat(another.getRunners(), contains(instanceTwo));
    }

    @Test
    public void canLookupRunnersByID() throws Exception {
        cluster.addRunner(clientRequest, instanceOne);
        cluster.addRunner(clientRequest, instanceTwo);
        assertThat(cluster.runner("nonexistant").isPresent(), is(false));
        assertThat(cluster.runner(instanceOne.id).orElse(null), is(instanceOne));
    }

    @Test
    public void allocatesRunnersBasedOnWhatIsAlreadyLoaded() throws Exception {
        ProxyMap proxyMap = new ProxyMap();
        assertThat(cluster.allocateRunner(proxyMap.getAll()), equalTo(Optional.empty()));

        cluster.addRunner(clientRequest, instanceOne);
        cluster.addRunner(clientRequest, instanceTwo);
        proxyMap.add("blah", instanceOne.url.resolve("/blah/"));
        assertThat(cluster.allocateRunner(proxyMap.getAll()).get(), is(instanceTwo));
    }

    @Test
    public void doesNotAllocateToOversubscribedRunners() throws Exception {
        ProxyMap proxyMap = new ProxyMap();

        cluster.addRunner(clientRequest, new Runner("one", URI.create("http://localhost:8081"), 1));
        cluster.addRunner(clientRequest, new Runner("two", URI.create("http://localhost:8082"), 2));

        proxyMap.add("blah", cluster.allocateRunner(proxyMap.getAll()).get().url.resolve("/blah"));
        proxyMap.add("blah2", cluster.allocateRunner(proxyMap.getAll()).get().url.resolve("/blah2"));
        proxyMap.add("blah3", cluster.allocateRunner(proxyMap.getAll()).get().url.resolve("/blah3"));
        assertThat(cluster.allocateRunner(proxyMap.getAll()), equalTo(Optional.empty()));
    }

}