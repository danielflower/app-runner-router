package com.danielflower.apprunner.router.web;

import io.muserver.*;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotFoundException;
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
            bytes = IO.readBytes(r.getInputStream());
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
