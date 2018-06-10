package com.danielflower.apprunner.router.web.v1;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Path("/runners")
public class RunnerResource {
    public static final Logger log = LoggerFactory.getLogger(RunnerResource.class);

    private final Cluster cluster;

    public RunnerResource(Cluster cluster) {
        this.cluster = cluster;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public String all(@Context UriInfo uriInfo) {
        return cluster.toJSON().toString(4);
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRunner(@Context UriInfo uriInfo, @PathParam("id") String id) {
        Optional<Runner> app = cluster.runner(id);
        if (app.isPresent()) {
            return Response.ok(app.get().toJSON().toString(4)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response create(@Context HttpServletRequest clientRequest,
                           @Context UriInfo uriInfo,
                           @FormParam("id") String id,
                           @FormParam("url") String url,
                           @FormParam("maxApps") int maxApps) {

        if (isBlank(id)) {
            return Response.status(400).entity("No runner ID was specified").build();
        }
        if (isBlank(url)) {
            return Response.status(400).entity("No runner URL was specified").build();
        }
        if (maxApps < 0) {
            return Response.status(400).entity("The max apps value must be at least 0").build();
        }

        try {
            String resourceLocation = uriInfo.getRequestUri() + "/" + urlEncode(id);
            if (cluster.runner(id).isPresent()) {
                return Response
                    .status(409)
                    .header("Location", resourceLocation)
                    .entity("A runner with the ID " + id + " already exists. To update this runner, instead make a PUT request to " + resourceLocation).build();
            }
            Runner runner = new Runner(id, URI.create(url), maxApps);
            log.info("Creating " + runner.toJSON().toString());
            cluster.addRunner(clientRequest, runner);
            return Response.status(201)
                .header("Location", resourceLocation)
                .entity(runner.toJSON().toString(4))
                .build();
        } catch (Exception e) {
            log.error("Error while adding app runner instance", e);
            return Response.serverError().entity("Error while adding app runner instance: " + e.getMessage()).build();
        }
    }

    @PUT
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response update(@Context HttpServletRequest clientRequest,
                           @Context UriInfo uriInfo,
                           @PathParam("id") String id,
                           @FormParam("url") String url,
                           @FormParam("maxApps") int maxApps) {

        if (isBlank(url)) {
            return Response.status(400).entity("No runner URL was specified").build();
        }
        if (maxApps < 1) {
            return Response.status(400).entity("The max apps value must be at least 1").build();
        }

        try {
            String resourceLocation = uriInfo.getRequestUri().toString();
            if (!cluster.runner(id).isPresent()) {
                return Response
                    .status(404)
                    .entity("No runner with the ID " + id + " exists").build();
            }
            Runner runner = new Runner(id, URI.create(url), maxApps);
            log.info("Updating " + runner.toJSON().toString());
            cluster.deleteRunner(runner);
            cluster.addRunner(clientRequest, runner);
            return Response.status(200)
                .header("Location", resourceLocation)
                .entity(runner.toJSON().toString(4))
                .build();
        } catch (Exception e) {
            log.error("Error while updating app runner instance", e);
            return Response.serverError().entity("Error while updating app runner instance: " + e.getMessage()).build();
        }
    }

    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("Unsupported Encoding for " + value, e);
        }
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}")
    public Response delete(@Context UriInfo uriInfo, @PathParam("id") String id) throws IOException {
        Optional<Runner> existing = cluster.runner(id);
        if (existing.isPresent()) {
            Runner runner = existing.get();
            cluster.deleteRunner(runner);
            return Response.ok(runner.toJSON().toString(4)).build();
        } else {
            return Response.status(400).entity("Could not find runner with name " + id).build();
        }
    }

}
