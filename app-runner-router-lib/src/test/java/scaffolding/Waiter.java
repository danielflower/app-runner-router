package scaffolding;

import io.muserver.murp.ReverseProxyBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

public class Waiter implements AutoCloseable {
    public static final Logger log = LoggerFactory.getLogger(Waiter.class);

    private static final long POLL_INTERVAL = 500;
    private Predicate<HttpClient> predicate;
    private final String name;
    private final long timeout;
    private final TimeUnit unit;
    private final HttpClient client = ReverseProxyBuilder.createHttpClientBuilder(true).build();

    public Waiter(String name, Predicate<HttpClient> predicate, long timeout, TimeUnit unit) {
        this.predicate = predicate;
        this.name = name;
        this.timeout = timeout;
        this.unit = unit;
    }

    public void or(Predicate<HttpClient> other) {
        predicate = predicate.or(other);
    }

    public void blockUntilReady() throws Exception {
        long start = System.currentTimeMillis();
        while ((System.currentTimeMillis() - start) < unit.toMillis(timeout)) {
            Thread.sleep(POLL_INTERVAL);
            if (predicate.test(client)) {
                return;
            }
            log.info("Waiting for start up of " + name);
        }

        throw new TimeoutException();
    }

    public static Waiter waitForApp(URI appServer, String appName) {
        URI url = appServer.resolve(appName + "/");
        return waitFor(appName, url, 30, TimeUnit.SECONDS);
    }

    public static Waiter waitFor(String name, URI url, long timeout, TimeUnit unit) {
        return new Waiter(name, client -> {
            try {
                client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding());
                return true;
            } catch (InterruptedException e) {
                return true; // erg... really want to bubble this but can't
            } catch (Exception ex) {
                return false;
            }
        }, timeout, unit);
    }

    @Override
    public void close() {
    }
}
