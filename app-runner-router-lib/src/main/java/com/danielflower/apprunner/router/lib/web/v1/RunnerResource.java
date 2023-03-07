package com.danielflower.apprunner.router.lib.web.v1;

import com.danielflower.apprunner.router.lib.RunnerUrlVerifier;
import com.danielflower.apprunner.router.lib.mgmt.Cluster;
import com.danielflower.apprunner.router.lib.mgmt.MapManager;
import com.danielflower.apprunner.router.lib.mgmt.Runner;
import io.muserver.MuRequest;
import io.muserver.rest.Description;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
@Description(value = "Runners", details = "This resource allows you to add, remove, and get all the AppRunner instances " +
    "that are registered with this router. It also provides a couple of endpoints that let you query data on a specific" +
    " runner directly. Note that in the normal case, requests against the router to `/api/v1/apps` are made without needing " +
    "to worry about which runner will service the request. However if you want to target endpoints on a specific runner " +
    "you can prefix any URL available on the runner with `/api/v1/runner-proxy/{runnerId}`. For example, to add an app " +
    "to a runner with the ID `myspecialrunner` you can `POST` to `/api/v1/runner-proxy/myspecialrunner/api/v1/apps` and " +
    "it will bypass the usual router processing.")
public class RunnerResource {
    public static final Logger log = LoggerFactory.getLogger(RunnerResource.class);

    private final Cluster cluster;
    private final MapManager mapManager;
    private final RunnerUrlVerifier runnerUrlVerifier;

    public RunnerResource(Cluster cluster, MapManager mapManager, RunnerUrlVerifier runnerUrlVerifier) {
        this.cluster = cluster;
        this.mapManager = mapManager;
        this.runnerUrlVerifier = runnerUrlVerifier;
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
            throw new NotFoundException("No runner with ID " + id + " found");
        }
    }

    @GET
    @Path("/{id}/apps")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRunnerApps(@Context MuRequest clientRequest, @PathParam("id") String id) {
        Runner runner = getRunner(id);
        try {
            JSONObject appsJSON = mapManager.loadRunner(clientRequest, runner);
            return Response.ok(appsJSON.toString(4)).build();
        } catch (Exception e) {
            log.error("Error while getting apps for " + id, e);
            return Response.serverError().entity(e.getMessage()).build();
        }
    }

    @GET
    @Path("/{id}/system")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRunnerSystem(@Context MuRequest clientRequest, @PathParam("id") String id) {
        Runner runner = getRunner(id);
        try {
            JSONObject appsJSON = mapManager.loadRunnerSystemInfo(clientRequest, runner);
            return Response.ok(appsJSON.toString(4)).build();
        } catch (Exception e) {
            log.error("Error while getting system info for " + id, e);
            return Response.serverError().entity(e.getMessage()).build();
        }
    }


    private Runner getRunner(@PathParam("id") String id) {
        Optional<Runner> app = cluster.runner(id);
        if (!app.isPresent()) {
            throw new NotFoundException("No runner with that ID");
        }
        return app.get();
    }


    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response create(@Context MuRequest clientRequest,
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
        runnerUrlVerifier.verify(url);

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
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response update(@Context MuRequest clientRequest,
                           @Context UriInfo uriInfo,
                           @PathParam("id") String id,
                           @FormParam("url") String url,
                           @FormParam("maxApps") int maxApps) {

        if (isBlank(url)) {
            return Response.status(400).entity("No runner URL was specified").build();
        }
        if (maxApps < 0) {
            return Response.status(400).entity("The max apps value must be at least 0").build();
        }
        runnerUrlVerifier.verify(url);

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
