package com.danielflower.apprunner.router.lib;

import jakarta.ws.rs.WebApplicationException;

public interface RunnerUrlVerifier {
    default void verify(String url) throws WebApplicationException {}
}
