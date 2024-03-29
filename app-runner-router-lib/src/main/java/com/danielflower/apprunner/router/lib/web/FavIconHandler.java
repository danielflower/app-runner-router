package com.danielflower.apprunner.router.lib.web;

import io.muserver.*;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.Map;

public class FavIconHandler implements RouteHandler {
    private static final Logger log = LoggerFactory.getLogger(FavIconHandler.class);

    private final byte[] favicon;

    public FavIconHandler() {
        byte[] bytes;
        try {
            URL fav = this.getClass().getClassLoader().getResource("favicon.ico");
            Resource r = Resource.newResource(fav);
            if (r != null) {
                try (InputStream inputStream = r.getInputStream()) {
                    bytes = IO.readBytes(inputStream);
                }
            } else {
                bytes = null;
                log.warn("Could not find favicon on classpath");
            }
        } catch (Exception e) {
            log.warn("Could not load favicon", e);
            bytes = null;
        }
        favicon = bytes;
    }

    @Override
    public void handle(MuRequest request, MuResponse response, Map<String, String> pathParams) throws Exception {
        if (favicon == null) {
            throw new NotFoundException();
        }
        response.contentType(ContentTypes.IMAGE_X_ICON);
        response.headers().set(HeaderNames.CONTENT_LENGTH, favicon.length);
        response.headers().set(HeaderNames.CACHE_CONTROL, "max-age=360000,public");
        try (OutputStream os = response.outputStream()) {
            os.write(favicon);
        }
    }
}
