package com.danielflower.apprunner.router.web;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URL;

class FavIconHandler extends AbstractHandler {
    private static final Logger log = LoggerFactory.getLogger(FavIconHandler.class);

    private final byte[] favicon;

    public FavIconHandler() {
        byte[] bytes;
        try {
            URL fav = this.getClass().getClassLoader().getResource("favicon.ico");
            Resource r = Resource.newResource(fav);
            bytes = IO.readBytes(r.getInputStream());
        } catch (IOException e) {
            log.warn("Could not load favicon", e);
            bytes = null;
        }
        favicon = bytes;
    }

    @Override
    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        if (target.equalsIgnoreCase("/favicon.ico") && favicon != null) {
            baseRequest.setHandled(true);
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("image/x-icon");
            response.setContentLength(favicon.length);
            response.setHeader(HttpHeader.CACHE_CONTROL.toString(),"max-age=360000,public");
            try (ServletOutputStream outputStream = response.getOutputStream()) {
                outputStream.write(favicon);
            }
        }
    }
}
