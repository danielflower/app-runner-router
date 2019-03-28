package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.Config;
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
        if ( Mutils.nullOrEmpty(defaultAppName)) {
            throw new NotFoundException("You can set a default app by setting the " + Config.DEFAULT_APP_NAME + " property.");
        }
        response.redirect("/" + defaultAppName);
    }
}
