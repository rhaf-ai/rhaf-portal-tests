package ai.rhaf.portal.tests;

import java.net.URI;
import com.fasterxml.jackson.databind.JsonNode;

import static ai.rhaf.portal.tests.CoreHarness.json;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(150)
class CoreFailedWriteIT {

    @ParameterizedTest
    @ValueSource(strings = {"before", "after"})
    void failedWriteIsNotReplayed(String failureMode) throws Exception {
        try (var harness = new CoreHarness("failed-write-" + failureMode)) {
            URI fixture = harness.startFixture();
            harness.startCore();
            JsonNode registration = harness.request(
                    "POST",
                    harness.management("/api/v1/forwards"),
                    json(
                            """
                            {"name":"write-target","address":"%s"}
                            """
                                    .formatted(fixture.resolve("/mcp"))));
            assertEquals(2, registration.path("data").path("tools_discovered").asInt());

            URI mcp = harness.mcp("/default/mcp");
            harness.initialize(mcp);
            JsonNode initial =
                    json("""
                    {"target":"sample","value":1,"enabled":false}
                    """);
            JsonNode update =
                    json("""
                    {"target":"sample","value":37,"enabled":true}
                    """);
            JsonNode successfulWrite = harness.call(mcp, "set_state", initial);
            assertTrue(successfulWrite.path("content").isArray(), successfulWrite::toString);
            assertFalse(successfulWrite.path("isError").asBoolean(), successfulWrite::toString);

            harness.request(
                    "POST",
                    fixture.resolve("/control/config"),
                    json(
                            """
                            {"enabledTools":["read_state","set_state"],"latencyMillis":0,"failureMode":"%s"}
                            """
                                    .formatted(failureMode)));
            JsonNode failedWrite = harness.call(mcp, "set_state", update);
            harness.save("failed-write.json", failedWrite);
            assertTrue(failedWrite.path("isError").asBoolean(), failedWrite::toString);

            JsonNode observed = harness.request("GET", fixture.resolve("/control/state"), null);
            harness.save("before-restart.json", observed);
            assertEquals(2, observed.path("callCount").asInt());
            JsonNode failedAttempt = observed.path("observations").path(1);
            assertEquals("set_state", failedAttempt.path("tool").asText());
            assertEquals("failure_" + failureMode, failedAttempt.path("outcome").asText());
            assertEquals(update, failedAttempt.path("arguments"));
            JsonNode expectedEffect = failureMode.equals("before")
                    ? json("{\"value\":1,\"enabled\":false,\"metadata\":{}}")
                    : json("{\"value\":37,\"enabled\":true,\"metadata\":{}}");
            assertEquals(expectedEffect, observed.path("effects").path("sample"));

            harness.restartCore();
            harness.initialize(mcp);
            assertTrue(harness.tools(mcp).contains("set_state"));
            JsonNode recovered = harness.request("GET", fixture.resolve("/control/state"), null);
            harness.save("after-restart.json", recovered);
            assertEquals(observed, recovered, "Core replayed a write during restart");
        }
    }
}
