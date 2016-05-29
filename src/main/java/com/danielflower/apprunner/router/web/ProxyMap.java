package com.danielflower.apprunner.router.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;

public class ProxyMap {
    private static final Logger log = LoggerFactory.getLogger(ProxyMap.class);
    private final ConcurrentHashMap<String, URI> mapping = new ConcurrentHashMap<>();

    public void add(String prefix, URI url) {
        URI old = mapping.put(prefix, url);
        if (old == null) {
            log.info(prefix + " maps to " + url);
        } else {
            log.info(prefix + " maps to " + url + " (previously " + old + ")");
        }
    }

    public void remove(String prefix) {
        URI remove = mapping.remove(prefix);
        if (remove != null) {
            log.info("Removed " + prefix + " mapping to " + remove);
        }
    }

    public URI get(String prefix) {
        return mapping.getOrDefault(prefix, null);
    }

    public ConcurrentHashMap<String, URI> getAll() {
        return mapping;
    }
}
