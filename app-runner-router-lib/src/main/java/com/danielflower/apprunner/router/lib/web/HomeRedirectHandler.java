package com.danielflower.apprunner.router.lib.web;

import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.Mutils;
import io.muserver.RouteHandler;

import javax.ws.rs.NotFoundException;
import java.util.Map;

public class HomeRedirectHandler implements RouteHandler {
    private final String defaultAppName;

    public HomeRedirectHandler(String defaultAppName) {
        this.defaultAppName = defaultAppName;
    }

    @Override
    public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) {
        if (Mutils.nullOrEmpty(defaultAppName)) {
            throw new NotFoundException("Welcome to AppRunner. No application has been set as the homepage.");
        }
        response.redirect("/" + defaultAppName);
    }
}
