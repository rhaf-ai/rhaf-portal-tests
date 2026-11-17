package ai.rhaf.portal.fixtures.mcp;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;

/** Trusted test controls, independent of the MCP protocol under test. */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ControlResource {

    @Inject
    FixtureState state;

    /** Reports listener readiness without disclosing fixture state. */
    @GET
    @Path("health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    /** Returns invocation observations and synthetic effects. */
    @GET
    @Path("control/state")
    public JsonNode snapshot() {
        return state.snapshot();
    }

    /** Returns the active tool catalog and fault controls. */
    @GET
    @Path("control/config")
    public JsonNode configuration() {
        return state.configuration();
    }

    /** Replaces the tool catalog and fault controls after validation. */
    @POST
    @Path("control/config")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode configure(JsonNode configuration) {
        return state.configure(configuration);
    }

    /** Clears effects, observations and fault controls while preserving authentication. */
    @POST
    @Path("control/reset")
    public JsonNode reset() {
        return state.reset();
    }
}
