package ai.rhaf.portal.fixtures.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolResponse;
import io.smallrye.mutiny.Uni;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Isolated synthetic state and deterministic invocation controls. */
@ApplicationScoped
public class FixtureState {
    static final Set<String> TOOL_NAMES = Set.of("read_state", "set_state");
    private static final Set<String> CONFIGURATION_FIELDS = Set.of("enabledTools", "latencyMillis", "failureMode");
    private static final Set<String> WRITE_FIELDS = Set.of("target", "value", "enabled", "metadata");
    private static final Set<String> FAILURE_MODES = Set.of("none", "before", "after");
    private static final int MAX_OBSERVATIONS = 1000;

    @Inject
    ObjectMapper mapper;

    @Inject
    FixtureAuthentication authentication;

    private Set<String> enabledTools = TOOL_NAMES;
    private int latencyMillis;
    private String failureMode = "none";
    private long epoch;
    private long callCount;
    private final List<ObjectNode> observations = new ArrayList<>();
    private final Map<String, ObjectNode> effects = new LinkedHashMap<>();

    /** Returns whether the named tool is enabled in the current catalog. */
    public synchronized boolean enabled(String tool) {
        return enabledTools.contains(tool);
    }

    /** Returns a detached view of the current tool and failure controls. */
    public synchronized JsonNode configuration() {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode tools = result.putArray("enabledTools");
        enabledTools.stream().sorted().forEach(tools::add);
        return result.put("latencyMillis", latencyMillis).put("failureMode", failureMode);
    }

    /** Validates and replaces all controls; malformed input leaves them unchanged. */
    public synchronized JsonNode configure(JsonNode configuration) {
        if (configuration == null
                || !configuration.isObject()
                || !fields(configuration).equals(CONFIGURATION_FIELDS)) {
            throw new BadRequestException("Expected enabledTools, latencyMillis and failureMode");
        }
        JsonNode tools = configuration.get("enabledTools");
        JsonNode latency = configuration.get("latencyMillis");
        JsonNode failure = configuration.get("failureMode");
        if (!tools.isArray()
                || !latency.isIntegralNumber()
                || !latency.canConvertToInt()
                || latency.intValue() < 0
                || latency.intValue() > 30000
                || !failure.isTextual()
                || !FAILURE_MODES.contains(failure.textValue())) {
            throw new BadRequestException("Invalid catalog, latency (0-30000 ms), or failure mode");
        }
        Set<String> selected = new HashSet<>();
        for (JsonNode tool : tools) {
            if (!tool.isTextual() || !TOOL_NAMES.contains(tool.textValue()) || !selected.add(tool.textValue())) {
                throw new BadRequestException("Expected unique known tool names");
            }
        }
        enabledTools = Set.copyOf(selected);
        latencyMillis = latency.intValue();
        failureMode = failure.textValue();
        return configuration();
    }

    /** Returns a detached snapshot with no request headers or credentials. */
    public synchronized JsonNode snapshot() {
        ObjectNode result = mapper.createObjectNode().put("callCount", callCount);
        result.set("observations", mapper.valueToTree(observations));
        result.set("effects", mapper.valueToTree(effects));
        return result;
    }

    /** Clears all state and controls and invalidates pending work from the previous epoch. */
    public synchronized JsonNode reset() {
        epoch++;
        callCount = 0;
        observations.clear();
        effects.clear();
        enabledTools = TOOL_NAMES;
        latencyMillis = 0;
        failureMode = "none";
        return snapshot();
    }

    /** Records raw JSON input and executes once after the configured asynchronous delay. */
    public Uni<ToolResponse> invoke(String tool, JsonNode arguments) {
        Pending pending;
        synchronized (this) {
            if (authentication.containsToken(arguments)) {
                return Uni.createFrom()
                        .item(ToolResponse.error("Configured credentials are not valid synthetic input"));
            }
            if (observations.size() >= MAX_OBSERVATIONS) {
                return Uni.createFrom().item(ToolResponse.error("Observation limit reached; reset the fixture"));
            }
            ObjectNode observation =
                    mapper.createObjectNode().put("sequence", ++callCount).put("tool", tool);
            observation.set("target", arguments.path("target").deepCopy());
            observation.set("arguments", arguments.deepCopy());
            observation.put("outcome", "pending");
            observations.add(observation);
            if (!validArguments(tool, arguments)) {
                observation.put("outcome", "invalid_arguments");
                return Uni.createFrom().item(ToolResponse.error("Arguments do not match the tool input schema"));
            }
            pending = new Pending(epoch, tool, arguments.deepCopy(), observation, failureMode, latencyMillis);
        }
        Uni<Pending> invocation = Uni.createFrom().item(pending);
        if (pending.latencyMillis() > 0) {
            invocation = invocation.onItem().delayIt().by(Duration.ofMillis(pending.latencyMillis()));
        }
        return invocation.map(this::complete);
    }

    private synchronized ToolResponse complete(Pending pending) {
        if (pending.epoch() != epoch) {
            return ToolResponse.error("Fixture reset while the invocation was pending");
        }
        if (pending.failureMode().equals("before")) {
            pending.observation().put("outcome", "failure_before");
            return ToolResponse.error("Controlled failure before effect");
        }
        String target = pending.arguments().get("target").textValue();
        if (pending.tool().equals("set_state")) {
            ObjectNode effect = mapper.createObjectNode();
            effect.set("value", pending.arguments().get("value"));
            effect.set("enabled", pending.arguments().get("enabled"));
            effect.set(
                    "metadata",
                    pending.arguments().has("metadata")
                            ? pending.arguments().get("metadata")
                            : mapper.createObjectNode());
            effects.put(target, effect);
        }
        if (pending.failureMode().equals("after")) {
            pending.observation().put("outcome", "failure_after");
            return ToolResponse.error("Controlled failure after effect");
        }
        pending.observation().put("outcome", "success");
        ObjectNode result = mapper.createObjectNode().put("target", target);
        result.set("state", effects.get(target));
        return new ToolResponse(false, List.of(new TextContent(result.toString())), result, Map.of());
    }

    private static boolean validArguments(String tool, JsonNode arguments) {
        if (!arguments.isObject()
                || !arguments.path("target").isTextual()
                || arguments.get("target").textValue().isBlank()
                || arguments.get("target").textValue().length() > 128) {
            return false;
        }
        if (tool.equals("read_state")) {
            return fields(arguments).equals(Set.of("target"));
        }
        return WRITE_FIELDS.containsAll(fields(arguments))
                && arguments.path("value").isIntegralNumber()
                && arguments.get("value").canConvertToInt()
                && arguments.path("enabled").isBoolean()
                && (!arguments.has("metadata") || arguments.get("metadata").isObject());
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private record Pending(
            long epoch,
            String tool,
            JsonNode arguments,
            ObjectNode observation,
            String failureMode,
            int latencyMillis) {}
}
