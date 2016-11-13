package com.danielflower.apprunner.router.mgmt;

import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

public class Runner {
    public final String id;
    public final URI url;
    public final int maxApps;
    public AtomicInteger numberOfApps = new AtomicInteger(0);

    public Runner(String id, URI url, int maxApps) {
        this.id = id;
        this.url = url;
        this.maxApps = maxApps;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Runner runner = (Runner) o;
        return id.equals(runner.id);

    }

    @Override
    public int hashCode() {
        int result = id.hashCode();
        result = 31 * result + url.hashCode();
        result = 31 * result + maxApps;
        return result;
    }

    @Override
    public String toString() {
        return "Runner{" +
            "id='" + id + '\'' +
            ", url=" + url +
            ", maxApps=" + maxApps +
            '}';
    }

    public JSONObject toJSON() {
        JSONObject vals = new JSONObject();
        vals.put("id", id);
        vals.put("url", url.toString());
        vals.put("systemUrl", url.resolve("/api/v1/system").toString());
        vals.put("maxApps", maxApps);
        return vals;
    }

    public static Runner fromJSON(JSONObject o) {
        return new Runner((String) o.get("id"), URI.create((String) o.get("url")), (int) o.get("maxApps"));
    }

    public boolean hasCapacity() {
        return numberOfApps.get() < maxApps;
    }
}
