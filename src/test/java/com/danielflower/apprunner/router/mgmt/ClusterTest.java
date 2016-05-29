package com.danielflower.apprunner.router.mgmt;

import com.danielflower.apprunner.router.web.ProxyMap;
import org.junit.Test;

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

    private File configFile = new File("target/clusters/" + System.currentTimeMillis() + "/cluster.json");
    private Cluster cluster = Cluster.load(configFile);
    private Runner instanceOne = new Runner("one", URI.create("http://localhost:8080"), 2);
    private Runner instanceTwo = new Runner("two", URI.create("http://localhost:9999"), 10);

    public ClusterTest() throws IOException {
    }

    @Test
    public void appsCanBeSavedAndRetrievedAndDeleted() throws IOException {
        assertThat(cluster.getRunners(), is(empty()));
        cluster.addRunner(instanceOne);
        cluster.addRunner(instanceTwo);
        assertThat(cluster.getRunners(), contains(instanceOne, instanceTwo));
        cluster.deleteRunner(instanceOne);

        Cluster another = Cluster.load(configFile);
        assertThat(another.getRunners(), contains(instanceTwo));
    }

    @Test
    public void canLookupRunnersByID() throws IOException {
        cluster.addRunner(instanceOne);
        cluster.addRunner(instanceTwo);
        assertThat(cluster.runner("nonexistant").isPresent(), is(false));
        assertThat(cluster.runner(instanceOne.id).orElse(null), is(instanceOne));
    }

    @Test
    public void allocatesRunnersBasedOnWhatIsAlreadyLoaded() throws IOException {
        ProxyMap proxyMap = new ProxyMap();
        assertThat(cluster.allocateRunner(proxyMap.getAll()), equalTo(Optional.empty()));

        cluster.addRunner(instanceOne);
        cluster.addRunner(instanceTwo);
        proxyMap.add("blah", instanceOne.url.resolve("/blah/"));
        assertThat(cluster.allocateRunner(proxyMap.getAll()).get(), is(instanceTwo));
    }

    @Test
    public void doesNotAllocateToOversubscribedRunners() throws IOException {
        ProxyMap proxyMap = new ProxyMap();

        cluster.addRunner(new Runner("one", URI.create("http://localhost:8081"), 1));
        cluster.addRunner(new Runner("two", URI.create("http://localhost:8082"), 2));

        proxyMap.add("blah", cluster.allocateRunner(proxyMap.getAll()).get().url.resolve("/blah"));
        proxyMap.add("blah2", cluster.allocateRunner(proxyMap.getAll()).get().url.resolve("/blah2"));
        proxyMap.add("blah3", cluster.allocateRunner(proxyMap.getAll()).get().url.resolve("/blah3"));
        assertThat(cluster.allocateRunner(proxyMap.getAll()), equalTo(Optional.empty()));
    }

}