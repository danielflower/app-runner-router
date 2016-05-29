package com.danielflower.apprunner.router.web;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

public class RouterHandlerList extends HandlerCollection {
    private static final Logger log = LoggerFactory.getLogger(RouterHandlerList.class);

    private Handler restService;
    private Handler reverseProxy;

    public void addReverseProxyHandler(Handler handler) {
        reverseProxy = handler;
        addHandler(handler);
    }
    public void addRestServiceHandler(Handler handler) {
        restService = handler;
        addHandler(handler);
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException
    {
        Handler[] handlers = getHandlers();

        if (handlers!=null && isStarted())
        {

            if (target.startsWith("/api/") && !AppsCallAggregator.canHandle(target, request)) {
                boolean isLocalRestRequest = target.startsWith("/api/v1/runners");
                Handler h = isLocalRestRequest ? restService : reverseProxy;
                log.debug("Going with " + (isLocalRestRequest ? "REST" : "PROXY") + " for " + target);
                h.handle(target, baseRequest, request, response);
                if (baseRequest.isHandled()) {
                    return;
                }
            }

            for (Handler handler : handlers) {
                handler.handle(target, baseRequest, request, response);
                if (baseRequest.isHandled()) {
                    return;
                }
            }

        }
    }

}
