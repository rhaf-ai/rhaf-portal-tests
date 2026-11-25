package ai.rhaf.portal.tests;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static ai.rhaf.portal.tests.CoreHarness.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(150)
class CoreForwardLifecycleIT {

    private static final String COLLECTION = "/api/rhaf/v1/forwards";
    private static final String ITEM = COLLECTION + "/managed-fixture";

    @Test
    void failedReplacementPreservesRoutingAndSuccessfulReplacementSurvivesRestart() throws Exception {
        try (var harness = new CoreHarness("forward-replacement")) {
            URI originalFixture = harness.startFixture();
            URI replacementFixture = harness.startFixture();
            harness.request(
                    "POST",
                    replacementFixture.resolve("/control/config"),
                    json(
                            """
                         {"enabledTools":["read_state"],"latencyMillis":0,"failureMode":"none"}
                         """));
            harness.startCore();

            ObjectNode original = forward(originalFixture.resolve("/mcp"), "initial");
            JsonNode created = harness.request("POST", harness.management(COLLECTION), original, 201)
                    .path("data");
            assertPersisted(harness, original);
            assertRegistration(created, original, true);
            URI mcp = harness.mcp("/managed-all/mcp");
            harness.initialize(mcp);
            Set<String> originalTools = Set.of("read_state", "set_state");
            assertEquals(originalTools, harness.tools(mcp));

            ObjectNode replacement = forward(replacementFixture.resolve("/mcp"), "replacement");
            replacement.put("namespaceBase", "different");
            harness.request("POST", harness.management(COLLECTION), replacement, 409);
            harness.request("PUT", harness.management(ITEM), replacement, 409);

            ObjectNode unavailable = forward(originalFixture.resolve("/missing"), "failed");
            JsonNode failure = harness.request("PUT", harness.management(ITEM), unavailable, 502);
            assertTrue(failure.path("data").isNull(), failure::toString);
            assertTrue(failure.path("error").isTextual(), failure::toString);
            assertPersisted(harness, original);
            assertLifecycleReads(harness, created);
            assertEquals(originalTools, harness.tools(mcp));

            JsonNode replaced = harness.request(
                            "PUT", harness.management(ITEM), replacement.put("namespaceBase", "managed"))
                    .path("data");
            assertPersisted(harness, replacement);
            assertRegistration(replaced, replacement, true);
            assertEquals(created.path("registrationId"), replaced.path("registrationId"));
            assertEquals(Set.of("read_state"), harness.tools(mcp));

            harness.restartCore();
            assertLifecycleReads(harness, replaced);
            harness.initialize(mcp);
            assertEquals(Set.of("read_state"), harness.tools(mcp));
            JsonNode result = harness.call(mcp, "read_state", json("{\"target\":\"sample\"}"));
            assertFalse(result.path("isError").asBoolean(), result::toString);
            assertEquals(
                    0,
                    harness.request("GET", originalFixture.resolve("/control/state"), null)
                            .path("callCount")
                            .asInt(-1));
            assertEquals(
                    1,
                    harness.request("GET", replacementFixture.resolve("/control/state"), null)
                            .path("callCount")
                            .asInt(-1));
        }
    }

    @Test
    void unavailableCreateRetainsRegistrationAndRejectsClientRuntimeState() throws Exception {
        try (var harness = new CoreHarness("forward-unavailable")) {
            harness.startCore();
            ObjectNode request = forward(harness.management("/missing"), "unavailable");
            harness.request(
                    "POST", harness.management(COLLECTION), request.deepCopy().put("available", true), 400);
            assertEquals(
                    json("[]"),
                    harness.request("GET", harness.management(COLLECTION), null).path("data"));

            JsonNode created = harness.request("POST", harness.management(COLLECTION), request, 201)
                    .path("data");
            assertPersisted(harness, request);
            assertRegistration(created, request, false);
            assertLifecycleReads(harness, created);

            harness.restartCore();
            assertLifecycleReads(harness, created);
        }
    }

    private static ObjectNode forward(URI address, String version) throws Exception {
        return (ObjectNode) json(
                """
                {"name":"managed-fixture","address":"%s","namespaceBase":"managed","labels":{"version":"%s"}}
                """
                        .formatted(address, version));
    }

    private static void assertRegistration(JsonNode registration, JsonNode request, boolean available) {
        assertEquals(request.path("name"), registration.path("name"), registration::toString);
        assertEquals(request.path("address"), registration.path("address"));
        assertEquals(request.path("labels"), registration.path("labels"));
        assertEquals("managed", registration.path("namespaceBase").asText());
        assertEquals("managed-all", registration.path("conventionalNamespace").asText());
        assertEquals(
                "managed-progressive", registration.path("progressiveNamespace").asText());
        assertEquals("active", registration.path("state").asText());
        assertTrue(
                registration.path("registrationId").isTextual()
                        && !registration.path("registrationId").asText().isBlank(),
                registration::toString);
        assertTrue(registration.path("available").isBoolean(), registration::toString);
        assertEquals(available, registration.path("available").asBoolean());
    }

    private static void assertPersisted(CoreHarness harness, JsonNode expected) throws Exception {
        JsonNode snapshot = harness.registrySnapshot();
        JsonNode forwards = snapshot.path("forwards");
        assertEquals(1, forwards.size(), forwards::toString);
        JsonNode forward = forwards.path(0);
        assertEquals(expected.path("name"), forward.path("name"));
        assertEquals(expected.path("address"), forward.path("address"));
        assertEquals(expected.at("/labels/version"), forward.at("/labels/version"));
        Set<String> namespaceNames = new HashSet<>();
        snapshot.path("namespaces")
                .forEach(namespace -> namespaceNames.add(namespace.path("name").asText()));
        assertTrue(namespaceNames.containsAll(Set.of("managed-all", "managed-progressive")), namespaceNames::toString);
    }

    private static void assertLifecycleReads(CoreHarness harness, JsonNode expected) throws Exception {
        assertEquals(
                expected, harness.request("GET", harness.management(ITEM), null).path("data"));
        assertEquals(
                json("[" + expected + "]"),
                harness.request("GET", harness.management(COLLECTION), null).path("data"));
    }
}
