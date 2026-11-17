package ai.rhaf.portal.fixtures.mcp;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@Timeout(30)
class FixtureProtocolTest {

    @TestHTTPResource
    URI baseUri;

    private McpTestClient client;

    @BeforeEach
    void resetAndInitializeHttpSession() throws Exception {
        client = new McpTestClient(baseUri, null);
        assertEquals(200, client.request("POST", "/control/reset", Map.of()).statusCode());
        client.initialize();
    }

    @AfterEach
    void closeHttpSession() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void advertisesTypedReadAndStateChangingTools() throws Exception {
        JsonNode tools = client.rpc("tools/list", Map.of()).path("result").path("tools");
        assertEquals(Set.of("read_state", "set_state"), toolNames(tools));
        JsonNode readSchema = tool(tools, "read_state").path("inputSchema").path("properties");
        assertEquals("string", readSchema.path("target").path("type").asText());
        JsonNode writeSchema = tool(tools, "set_state").path("inputSchema").path("properties");
        assertEquals("integer", writeSchema.path("value").path("type").asText());
        assertEquals("boolean", writeSchema.path("enabled").path("type").asText());
        assertEquals("object", writeSchema.path("metadata").path("type").asText());
    }

    @Test
    void recordsExactTargetsArgumentTypesCallCountsAndEffects() throws Exception {
        Map<String, Object> arguments = Map.of(
                "target",
                "service-a",
                "value",
                7,
                "enabled",
                true,
                "metadata",
                Map.of("labels", List.of("blue", 3, true), "nested", Map.of("ratio", 2.5)));

        JsonNode written = client.toolValue(client.call("set_state", arguments));
        assertEquals("service-a", written.path("target").asText());
        assertEquals(7, written.path("state").path("value").asInt());
        client.call("set_state", Map.of("target", "service-b", "value", 11, "enabled", false));
        JsonNode read = client.toolValue(client.call("read_state", Map.of("target", "service-a")));
        assertEquals(written, read);

        JsonNode state = state();
        assertEquals(3, state.path("callCount").asInt());
        assertEquals(3, state.path("observations").size());
        JsonNode first = state.path("observations").get(0);
        assertEquals(1, first.path("sequence").asInt());
        assertEquals("set_state", first.path("tool").asText());
        assertEquals("service-a", first.path("target").asText());
        assertEquals("success", first.path("outcome").asText());
        assertEquals(new ObjectMapper().valueToTree(arguments), first.path("arguments"));
        assertTrue(first.path("arguments").path("value").isIntegralNumber());
        assertTrue(first.path("arguments").path("enabled").isBoolean());
        assertTrue(first.path("arguments").path("metadata").isObject());
        assertEquals(7, state.path("effects").path("service-a").path("value").asInt());
        assertEquals(11, state.path("effects").path("service-b").path("value").asInt());
        assertFalse(state.path("effects").path("service-b").path("enabled").asBoolean());
    }

    @Test
    void changesCatalogAndInvocationAvailabilityWithoutRestarting() throws Exception {
        configure(List.of("read_state"), 0, "none");
        JsonNode tools = client.rpc("tools/list", Map.of()).path("result").path("tools");
        assertEquals(Set.of("read_state"), toolNames(tools));
        JsonNode disabled = client.rpc(
                "tools/call",
                Map.of("name", "set_state", "arguments", Map.of("target", "service-a", "value", 1, "enabled", true)));
        assertTrue(isFailedCall(disabled), disabled.toString());
        assertTrue(state().path("effects").isEmpty());

        configure(List.of("read_state", "set_state"), 0, "none");
        assertEquals(
                Set.of("read_state", "set_state"),
                toolNames(client.rpc("tools/list", Map.of()).path("result").path("tools")));
        assertEquals(
                2,
                client.toolValue(client.call("set_state", Map.of("target", "service-a", "value", 2, "enabled", true)))
                        .path("state")
                        .path("value")
                        .asInt());
    }

    @Test
    void rejectsIncorrectJsonTypesWithoutChangingSyntheticState() throws Exception {
        JsonNode response = client.rpc(
                "tools/call",
                Map.of("name", "set_state", "arguments", Map.of("target", "service-a", "value", "7", "enabled", true)));
        assertTrue(isFailedCall(response), response.toString());
        JsonNode state = state();
        assertTrue(state.path("effects").isEmpty());
        assertEquals(1, state.path("callCount").asInt());
        assertEquals(
                "invalid_arguments",
                state.path("observations").get(0).path("outcome").asText());
        assertTrue(state.path("observations")
                .get(0)
                .path("arguments")
                .path("value")
                .isTextual());
    }

    @Test
    void failsBeforeMutationAndRecordsOneAttemptWithoutReplay() throws Exception {
        configure(List.of("read_state", "set_state"), 0, "before");
        JsonNode response = client.call("set_state", Map.of("target", "service-a", "value", 3, "enabled", true));
        assertTrue(response.path("isError").asBoolean(), response.toString());
        JsonNode state = state();
        assertEquals(1, state.path("callCount").asInt());
        assertEquals(1, state.path("observations").size());
        assertEquals(
                "failure_before",
                state.path("observations").get(0).path("outcome").asText());
        assertTrue(state.path("effects").isEmpty());
    }

    @Test
    void exposesMutationWhenResponseFailsAfterItsEffectWithoutReplay() throws Exception {
        configure(List.of("read_state", "set_state"), 0, "after");
        JsonNode response = client.call("set_state", Map.of("target", "service-a", "value", 3, "enabled", true));
        assertTrue(response.path("isError").asBoolean(), response.toString());
        JsonNode state = state();
        assertEquals(1, state.path("callCount").asInt());
        assertEquals(1, state.path("observations").size());
        assertEquals(
                "failure_after",
                state.path("observations").get(0).path("outcome").asText());
        assertEquals(3, state.path("effects").path("service-a").path("value").asInt());
    }

    @Test
    void appliesConfiguredLatencyToTheActualHttpToolCall() throws Exception {
        configure(List.of("read_state", "set_state"), 150, "none");
        long started = System.nanoTime();
        client.toolValue(client.call("set_state", Map.of("target", "service-a", "value", 3, "enabled", true)));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
        assertTrue(elapsed.compareTo(Duration.ofMillis(150)) >= 0, "Configured tool latency was not applied");
        assertEquals(1, state().path("callCount").asInt());
    }

    @Test
    void resetClearsEffectsObservationsAndFaultControls() throws Exception {
        client.call("set_state", Map.of("target", "service-a", "value", 3, "enabled", true));
        configure(List.of("read_state"), 25, "before");

        assertEquals(200, client.request("POST", "/control/reset", Map.of()).statusCode());
        JsonNode state = state();
        assertEquals(0, state.path("callCount").asInt());
        assertTrue(state.path("observations").isEmpty());
        assertTrue(state.path("effects").isEmpty());
        JsonNode config = client.json(client.request("GET", "/control/config", null));
        assertEquals(0, config.path("latencyMillis").asInt());
        assertEquals("none", config.path("failureMode").asText());
        assertEquals(
                Set.of("read_state", "set_state"),
                toolNames(client.rpc("tools/list", Map.of()).path("result").path("tools")));
    }

    @Test
    void rejectsInvalidControlsAndKeepsThePreviousConfiguration() throws Exception {
        configure(List.of("read_state"), 0, "none");
        var response = client.request(
                "POST",
                "/control/config",
                Map.of("enabledTools", List.of("read_state", "set_state"), "latencyMillis", -1, "failureMode", "none"));
        assertEquals(400, response.statusCode(), response.body());
        assertEquals(
                Set.of("read_state"),
                toolNames(client.rpc("tools/list", Map.of()).path("result").path("tools")));
    }

    @Test
    void resetPreventsAnAdmittedDelayedMutationFromRestoringOldState() throws Exception {
        configure(List.of("read_state", "set_state"), 3000, "none");
        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = executor.submit(
                    () -> client.call("set_state", Map.of("target", "stale-target", "value", 9, "enabled", true)));
            // This call is asynchronous. Wait for admission before resetting its state.
            long admissionDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            JsonNode admitted = state();
            while (admitted.path("callCount").asInt() == 0 && System.nanoTime() < admissionDeadline) {
                Thread.sleep(10);
                admitted = state();
            }
            assertEquals(1, admitted.path("callCount").asInt(), "Delayed call was not admitted before the deadline");
            assertEquals(
                    "pending",
                    admitted.path("observations").get(0).path("outcome").asText());

            assertEquals(200, client.request("POST", "/control/reset", Map.of()).statusCode());
            JsonNode completed = pending.get(10, TimeUnit.SECONDS);
            assertTrue(completed.path("isError").asBoolean(), completed.toString());
            JsonNode state = state();
            assertEquals(0, state.path("callCount").asInt());
            assertTrue(state.path("observations").isEmpty());
            assertTrue(state.path("effects").isEmpty());
        }
    }

    private void configure(List<String> enabledTools, int latencyMillis, String failureMode) throws Exception {
        var response = client.request(
                "POST",
                "/control/config",
                Map.of("enabledTools", enabledTools, "latencyMillis", latencyMillis, "failureMode", failureMode));
        assertEquals(200, response.statusCode(), response.body());
    }

    private JsonNode state() throws Exception {
        var response = client.request("GET", "/control/state", null);
        assertEquals(200, response.statusCode(), response.body());
        return client.json(response);
    }

    private static boolean isFailedCall(JsonNode message) {
        return message.has("error") || message.path("result").path("isError").asBoolean();
    }

    private static Set<String> toolNames(JsonNode tools) {
        Set<String> names = new HashSet<>();
        tools.forEach(tool -> names.add(tool.path("name").asText()));
        return names;
    }

    private static JsonNode tool(JsonNode tools, String name) {
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) {
                return tool;
            }
        }
        throw new AssertionError("Missing tool " + name);
    }
}
