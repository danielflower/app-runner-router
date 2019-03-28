package com.danielflower.apprunner.router.mgmt;

import com.danielflower.apprunner.router.web.ProxyMap;
import io.muserver.MuRequest;
import org.jmock.Expectations;
import org.jmock.integration.junit4.JUnitRuleMockery;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.junit.Assert.assertThat;

public class ClusterTest {

    @Rule public final JUnitRuleMockery context = new JUnitRuleMockery();
    private final MapManager mapManager = context.mock(MapManager.class);
    private File configFile = new File("target/clusters/" + UUID.randomUUID() + "/cluster.json");
    private Cluster cluster = Cluster.load(configFile, mapManager);
    private Runner instanceOne = new Runner("one", URI.create("http://localhost:8080"), 2);
    private Runner instanceTwo = new Runner("two", URI.create("http://localhost:9999"), 10);
    private MuRequest clientRequest = context.mock(MuRequest.class);
    private Collection<String> excludeNone = emptyList();

    public ClusterTest() throws IOException {
    }

    @Before
    public void allowStuff() throws Exception {
        context.checking(new Expectations() {{
            allowing(mapManager).loadRunner(with(any(MuRequest.class)), with(any(Runner.class)));
            allowing(mapManager).removeRunner(with(instanceOne));
            allowing(mapManager).getCurrentMapping();will(returnValue(new ConcurrentHashMap<String, URI>()));
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

        // no runners, so no allocation
        assertThat(cluster.allocateRunner(proxyMap.getAll(), excludeNone), equalTo(Optional.empty()));

        // Add two runners to the cluster
        cluster.addRunner(clientRequest, instanceOne);
        cluster.addRunner(clientRequest, instanceTwo);

        // Add an app to runner 1
        proxyMap.add("blah", instanceOne.url.resolve("/blah/"));

        // If we exclude runner 2, then it won't be picked even though it has greater capacity
        assertThat(cluster.allocateRunner(proxyMap.getAll(), singletonList(instanceTwo.id)).get(), is(instanceOne));

        // When allocating without exclusion, runner 2 should be allocated
        assertThat(cluster.allocateRunner(proxyMap.getAll(), excludeNone).get(), is(instanceTwo));
    }

    @Test
    public void doesNotAllocateToOversubscribedRunners() throws Exception {
        ProxyMap proxyMap = new ProxyMap();

        cluster.addRunner(clientRequest, new Runner("one", URI.create("http://localhost:8081"), 1));
        cluster.addRunner(clientRequest, new Runner("two", URI.create("http://localhost:8082"), 2));

        proxyMap.add("blah", cluster.allocateRunner(proxyMap.getAll(), excludeNone).get().url.resolve("/blah"));
        proxyMap.add("blah2", cluster.allocateRunner(proxyMap.getAll(), excludeNone).get().url.resolve("/blah2"));
        proxyMap.add("blah3", cluster.allocateRunner(proxyMap.getAll(), excludeNone).get().url.resolve("/blah3"));
        assertThat(cluster.allocateRunner(proxyMap.getAll(), excludeNone), equalTo(Optional.empty()));
    }

}