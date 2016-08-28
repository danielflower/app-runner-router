package com.danielflower.apprunner.router.web;

import com.danielflower.apprunner.router.Config;
import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.MapManager;
import com.danielflower.apprunner.router.web.v1.RunnerResource;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.servlet.ServletContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import java.io.IOException;
import java.util.HashMap;

public class WebServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WebServer.class);
    private final ProxyMap proxyMap;
    private Server jettyServer;
    private final String defaultAppName;
    private final RunnerResource runnerResource;
    private final Cluster cluster;
    private final MapManager mapManager;
    private final String accessLogFilename;
    private final boolean allowUntrustedInstances;

    public WebServer(Server jettyServer, Cluster cluster, MapManager mapManager, ProxyMap proxyMap, String defaultAppName, RunnerResource runnerResource, String accessLogFilename, boolean allowUntrustedInstances) {
        this.jettyServer = jettyServer;
        this.cluster = cluster;
        this.mapManager = mapManager;
        this.proxyMap = proxyMap;
        this.defaultAppName = defaultAppName;
        this.runnerResource = runnerResource;
        this.accessLogFilename = accessLogFilename;
        this.allowUntrustedInstances = allowUntrustedInstances;
    }

    public void start() throws Exception {
        RouterHandlerList handlers = new RouterHandlerList();
        handlers.addHandler(createHomeRedirect());
        handlers.addHandler(new AppsCallAggregator(mapManager, cluster));
        handlers.addRestServiceHandler(createRestService());
        handlers.addReverseProxyHandler(createReverseProxy(cluster, proxyMap, allowUntrustedInstances));
        jettyServer.setHandler(handlers);
        addAccessLog();
        jettyServer.start();

        for (Connector connector : jettyServer.getConnectors()) {
            log.info("Endpoint: " + StringUtils.join(connector.toString().split("[{}]+"), " ", 1, 3));
        }
        log.info("Started web server");
    }

    private void addAccessLog() {
        if (StringUtils.isNotBlank(accessLogFilename)) {
            NCSARequestLog log = new NCSARequestLog();
            log.setAppend(true);
            log.setFilename(accessLogFilename);
            log.setFilenameDateFormat("yyyy_MM_dd");
            log.setRetainDays(30);
            log.setLogTimeZone("GMT");
            log.setExtended(false);
            log.setLogCookies(false);
            jettyServer.setRequestLog(log);
        }
    }

    private Handler createRestService() {
        ResourceConfig rc = new ResourceConfig();
        rc.register(runnerResource);
        rc.register(JacksonFeature.class);
        rc.register(CORSFilter.class);
        rc.addProperties(new HashMap<String,Object>() {{
            // Turn off buffering so results can be streamed
            put(ServerProperties.OUTBOUND_CONTENT_LENGTH_BUFFER, 0);
        }});

        ServletHolder holder = new ServletHolder(new ServletContainer(rc));

        ServletContextHandler sch = new ServletContextHandler();
        sch.setContextPath("/api/v1");
        sch.addServlet(holder, "/*");

        return sch;
    }

    private static class CORSFilter implements ContainerResponseFilter {
        public void filter(ContainerRequestContext request,
                           ContainerResponseContext response) throws IOException {
            response.getHeaders().add("Access-Control-Allow-Origin", "*");
            response.getHeaders().add("Access-Control-Allow-Headers",
                "origin, content-type, accept, authorization");
            response.getHeaders().add("Access-Control-Allow-Credentials", "true");
            response.getHeaders().add("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS, HEAD");
        }
    }

    private Handler createHomeRedirect() {
        return new AbstractHandler() {
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
                if ("/".equals(target)) {
                    if (StringUtils.isNotEmpty(defaultAppName)) {
                        response.sendRedirect("/" + defaultAppName);
                    } else {
                        response.sendError(404, "You can set a default app by setting the " + Config.DEFAULT_APP_NAME + " property.");
                    }
                    baseRequest.setHandled(true);
                }
            }
        };
    }

    private ServletHandler createReverseProxy(Cluster cluster, ProxyMap proxyMap, boolean allowUntrustedInstances) {
        AsyncProxyServlet servlet = new ReverseProxy(cluster, proxyMap, allowUntrustedInstances);
        ServletHolder proxyServletHolder = new ServletHolder(servlet);
        proxyServletHolder.setAsyncSupported(true);
        proxyServletHolder.setInitParameter("maxThreads", "100");
        ServletHandler proxyHandler = new ServletHandler();
        proxyHandler.addServletWithMapping(proxyServletHolder, "/*");
        return proxyHandler;
    }

    public void close() throws Exception {
        jettyServer.stop();
        jettyServer.join();
        jettyServer.destroy();
    }

}
