package com.danielflower.apprunner.router.web.v1;

import com.danielflower.apprunner.router.mgmt.Cluster;
import com.danielflower.apprunner.router.mgmt.Runner;
import io.swagger.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.isBlank;

@Api(value = "Runner")
@Path("/runners")
public class RunnerResource {
    public static final Logger log = LoggerFactory.getLogger(RunnerResource.class);

    private final Cluster cluster;

    public RunnerResource(Cluster cluster) {
        this.cluster = cluster;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets all registered runners")
    public String all(@Context UriInfo uriInfo) {
        return cluster.toJSON().toString(4);
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Gets a single app")
    public Response getRunner(@Context UriInfo uriInfo, @ApiParam(required = true, example = "app-runner-instance") @PathParam("id") String id) {
        Optional<Runner> app = cluster.runner(id);
        if (app.isPresent()) {
            return Response.ok(app.get().toJSON().toString(4)).build();
        } else {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @ApiOperation(value = "Registers an app or updates an existing app runner instance")
    @ApiResponses(value = {
        @ApiResponse(code = 201, message = "The new runner was successfully registered"),
        @ApiResponse(code = 200, message = "The existing runner was updated"),
        @ApiResponse(code = 400, message = "ID is missing, or URL is missing, or maxApps is less than 1")
    })
    public Response create(@Context UriInfo uriInfo,
                           @ApiParam(required = true, example = "app-runner-instance", value = "The ID of an app runner instance")
                           @FormParam("id") String id,
                           @ApiParam(required = true, example = "http://machine:1234", value = "The URL of the instance")
                               @FormParam("url") String url,
                           @ApiParam(required = true, example = "20", value = "The maximum number of apps to allow on the instance")
                           @FormParam("maxApps") int maxApps) {

        if (isBlank(id)) {
            return Response.status(400).entity("No runner ID was specified").build();
        }
        if (isBlank(url)) {
            return Response.status(400).entity("No runner URL was specified").build();
        }
        if (maxApps < 1) {
            return Response.status(400).entity("The max apps value must be at least 1").build();
        }

        Runner runner = new Runner(id, URI.create(url), maxApps);
        log.info("Creating " + runner.toJSON().toString());

        try {
            int status;
            Optional<Runner> existing = cluster.runner(id);
            if (existing.isPresent()) {
                cluster.deleteRunner(runner);
                status = 200;
            } else {
                status = 201;
            }
            cluster.addRunner(runner);
            return Response.status(status)
                .header("Location", uriInfo.getRequestUri() + "/" + URLEncoder.encode(id, "UTF-8"))
                .entity(runner.toJSON().toString(4))
                .build();
        } catch (Exception e) {
            log.error("Error while adding app runner instance", e);
            return Response.serverError().entity("Error while adding app runner instance: " + e.getMessage()).build();
        }
    }

    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    @Path("/{id}")
    @ApiOperation(value = "De-registers an instance")
    public Response delete(@Context UriInfo uriInfo, @ApiParam(required=true) @PathParam("id") String id) throws IOException {
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
