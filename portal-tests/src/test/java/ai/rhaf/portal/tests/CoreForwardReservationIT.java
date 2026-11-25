package ai.rhaf.portal.tests;

import java.net.URI;
import java.util.Set;
import com.fasterxml.jackson.databind.JsonNode;

import static ai.rhaf.portal.tests.CoreHarness.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(150)
class CoreForwardReservationIT {

    private static final String FORWARDS = "/api/rhaf/v1/forwards";
    private static final String NAMESPACES = "/api/v1/namespaces";

    @ParameterizedTest
    @CsvSource({"reserved-all,reserved-progressive", "reserved-progressive,reserved-all"})
    void existingEmptyNamespaceBlocksReservationWithoutCreatingOtherNamespace(String occupied, String unused)
            throws Exception {
        try (var harness = new CoreHarness("reservation-conflict-" + occupied)) {
            harness.startCore();
            harness.request(
                    "POST",
                    harness.management(NAMESPACES),
                    json(
                            """
                         {"name":"%s","labels":{"owner":"external"}}
                         """
                                    .formatted(occupied)));
            URI occupiedNamespace = harness.management(NAMESPACES + "/" + occupied);
            JsonNode before = harness.request("GET", occupiedNamespace, null);

            harness.request(
                    "POST",
                    harness.management(FORWARDS),
                    registration("candidate", harness.management("/api/rhaf/v1/unknown")),
                    409);

            assertEquals(before, harness.request("GET", occupiedNamespace, null));
            harness.request("GET", harness.management(NAMESPACES + "/" + unused), null, 404);
            assertEquals(
                    json("[]"),
                    harness.request("GET", harness.management(FORWARDS), null).path("data"));
        }
    }

    @Test
    void reservationProtectsBothNamespacesAndSurvivesRestart() throws Exception {
        try (var harness = new CoreHarness("durable-reservation")) {
            URI fixture = harness.startFixture().resolve("/mcp");
            harness.startCore();
            JsonNode owner = harness.request("POST", harness.management(FORWARDS), registration("owner", fixture), 201)
                    .path("data");
            assertEquals("reserved-all", owner.path("conventionalNamespace").asText());
            assertEquals(
                    "reserved-progressive", owner.path("progressiveNamespace").asText());
            JsonNode registrationId = owner.path("registrationId");
            assertTrue(registrationId.isTextual() && !registrationId.asText().isBlank(), owner::toString);
            URI conventional = harness.management(NAMESPACES + "/reserved-all");
            URI progressive = harness.management(NAMESPACES + "/reserved-progressive");
            JsonNode conventionalBefore = harness.request("GET", conventional, null);
            JsonNode progressiveBefore = harness.request("GET", progressive, null);

            harness.restartCore();
            harness.request("POST", harness.management(FORWARDS), registration("contender", fixture), 409);
            harness.request("DELETE", conventional, null, 409);
            harness.request("PUT", progressive, json("{\"name\":\"reserved-progressive\"}"), 409);
            harness.request(
                    "POST",
                    harness.management("/api/v1/forwards"),
                    json(
                            """
                         {"name":"intruder","address":"%s","namespace":"reserved-all"}
                         """
                                    .formatted(fixture)),
                    409);
            harness.request("POST", harness.management("/api/v1/forwards/owner/refreshes"), null, 409);
            harness.request("DELETE", harness.management("/api/v1/forwards/owner"), null, 409);

            assertEquals(conventionalBefore, harness.request("GET", conventional, null));
            assertEquals(progressiveBefore, harness.request("GET", progressive, null));
            JsonNode forwards =
                    harness.request("GET", harness.management(FORWARDS), null).path("data");
            assertEquals(1, forwards.size(), forwards::toString);
            assertEquals("owner", forwards.path(0).path("name").asText(), forwards::toString);
            assertEquals(registrationId, forwards.path(0).path("registrationId"));

            URI mcp = harness.mcp("/reserved-all/mcp");
            harness.initialize(mcp);
            assertEquals(Set.of("read_state", "set_state"), harness.tools(mcp));
        }
    }

    private static JsonNode registration(String name, URI address) throws Exception {
        return json(
                """
                    {"name":"%s","address":"%s","namespaceBase":"reserved"}
                    """
                        .formatted(name, address));
    }
}
