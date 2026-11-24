package ai.rhaf.portal.tests;

import java.net.URI;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

import static ai.rhaf.portal.tests.CoreHarness.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Timeout(90)
class CoreNamespaceRoutingIT {

    @Test
    void qualifiedNamesRouteIdenticalToolsToDifferentTargets() throws Exception {
        try (var harness = new CoreHarness("namespace-routing")) {
            URI eastFixture = harness.startFixture();
            URI westFixture = harness.startFixture();
            harness.startCore();

            registerForward(harness, eastFixture, "east");
            registerForward(harness, westFixture, "west");
            harness.request(
                    "POST",
                    harness.management("/api/v1/namespaces"),
                    json(
                            """
                        {"name":"combined","members":["east","west"]}
                        """));

            URI virtualMcp = harness.mcp("/virtual-ns/combined/mcp");
            harness.initialize(virtualMcp);
            assertEquals(
                    Set.of("east/read_state", "east/set_state", "west/read_state", "west/set_state"),
                    harness.tools(virtualMcp));

            JsonNode eastArguments =
                    json("""
                {"target":"shared","value":17,"enabled":true}
                """);
            JsonNode westArguments =
                    json("""
                {"target":"shared","value":29,"enabled":false}
                """);

            JsonNode eastResult = harness.call(virtualMcp, "east/set_state", eastArguments);
            JsonNode eastAfterFirstCall = observe(harness, eastFixture, "east-after-first-call.json");
            JsonNode westAfterFirstCall = observe(harness, westFixture, "west-after-first-call.json");
            assertFalse(eastResult.path("isError").asBoolean(), eastResult::toString);
            assertFixtureWrite(eastAfterFirstCall, eastArguments);
            assertEquals(0, westAfterFirstCall.path("callCount").asInt(-1), westAfterFirstCall::toString);
            assertEquals(json("{}"), westAfterFirstCall.path("effects"));

            JsonNode westResult = harness.call(virtualMcp, "west/set_state", westArguments);
            JsonNode eastAfterSecondCall = observe(harness, eastFixture, "east-after-second-call.json");
            JsonNode westAfterSecondCall = observe(harness, westFixture, "west-after-second-call.json");
            assertFalse(westResult.path("isError").asBoolean(), westResult::toString);
            assertEquals(eastAfterFirstCall, eastAfterSecondCall, "The west call reached the east fixture");
            assertFixtureWrite(westAfterSecondCall, westArguments);
        }
    }

    private static void registerForward(CoreHarness harness, URI fixture, String namespace) throws Exception {
        JsonNode forward = harness.request(
                "POST",
                harness.management("/api/v1/forwards"),
                json(
                        """
                    {"name":"%s-fixture","address":"%s","namespace":"%s"}
                    """
                                .formatted(namespace, fixture.resolve("/mcp"), namespace)));
        assertEquals(2, forward.at("/data/tools_discovered").asInt(-1), forward::toString);
    }

    private static JsonNode observe(CoreHarness harness, URI fixture, String filename) throws Exception {
        JsonNode snapshot = harness.request("GET", fixture.resolve("/control/state"), null);
        harness.save(filename, snapshot);
        return snapshot;
    }

    private static void assertFixtureWrite(JsonNode snapshot, JsonNode arguments) {
        assertEquals(1, snapshot.path("callCount").asInt(-1), snapshot::toString);
        assertEquals(1, snapshot.path("observations").size(), snapshot::toString);
        assertEquals("set_state", snapshot.at("/observations/0/tool").asText(), snapshot::toString);
        assertEquals(arguments, snapshot.at("/observations/0/arguments"));
        assertEquals("success", snapshot.at("/observations/0/outcome").asText(), snapshot::toString);
        assertEquals(arguments.path("value"), snapshot.at("/effects/shared/value"));
        assertEquals(arguments.path("enabled"), snapshot.at("/effects/shared/enabled"));
    }
}
