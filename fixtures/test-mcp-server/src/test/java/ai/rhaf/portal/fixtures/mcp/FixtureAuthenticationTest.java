package ai.rhaf.portal.fixtures.mcp;

import java.net.URI;
import java.util.Map;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(FixtureAuthenticationTest.AuthenticationProfile.class)
@Timeout(30)
class FixtureAuthenticationTest {

    private static final String TOKEN_MARKER = "synthetic-fixture-secret";
    private static final String TOKEN = TOKEN_MARKER + "\"\\credential-token";

    @TestHTTPResource
    URI baseUri;

    @Test
    void rejectsMissingAndIncorrectCredentialsBeforeMcpOrControlAccess() throws Exception {
        try (McpTestClient anonymous = new McpTestClient(baseUri, null);
                McpTestClient incorrect = new McpTestClient(baseUri, "incorrect-synthetic-token")) {
            assertEquals(401, anonymous.request("POST", "/mcp", Map.of()).statusCode());
            assertEquals(401, incorrect.request("POST", "/mcp", Map.of()).statusCode());
            assertEquals(401, anonymous.request("GET", "/control/state", null).statusCode());
            assertEquals(
                    401, incorrect.request("POST", "/control/reset", Map.of()).statusCode());
        }
    }

    @Test
    void acceptsConfiguredCredentialsWithoutIncludingThemInObservations() throws Exception {
        try (McpTestClient client = new McpTestClient(baseUri, TOKEN)) {
            assertEquals(200, client.request("POST", "/control/reset", Map.of()).statusCode());
            client.initialize();
            assertFalse(client.rpc("tools/list", Map.of()).has("error"));
            client.call("set_state", Map.of("target", "auth-check", "value", 1, "enabled", true));
            var response = client.request("GET", "/control/state", null);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, client.json(response).path("observations").size());
            assertFalse(response.body().contains(TOKEN_MARKER), "Observations must exclude the bearer credential");
        }
    }

    @Test
    void resetPreservesAuthenticationAndLeavesReadinessPublic() throws Exception {
        try (McpTestClient client = new McpTestClient(baseUri, TOKEN);
                McpTestClient anonymous = new McpTestClient(baseUri, null)) {
            assertEquals(200, client.request("POST", "/control/reset", Map.of()).statusCode());
            assertEquals(401, anonymous.request("GET", "/control/state", null).statusCode());
            assertEquals(200, anonymous.request("GET", "/health", null).statusCode());
        }
    }

    @Test
    void rejectsConfiguredCredentialInSyntheticValuesOrKeysWithoutRecordingIt() throws Exception {
        try (McpTestClient client = new McpTestClient(baseUri, TOKEN)) {
            assertEquals(200, client.request("POST", "/control/reset", Map.of()).statusCode());
            client.initialize();
            var result = client.call(
                    "set_state",
                    Map.of(
                            "target",
                            "auth-check",
                            "value",
                            1,
                            "enabled",
                            true,
                            "metadata",
                            Map.of("nested", Map.of("credential", TOKEN))));
            assertTrue(result.path("isError").asBoolean(), result.toString());
            assertFalse(result.toString().contains(TOKEN_MARKER));
            var keyResult = client.call(
                    "set_state",
                    Map.of(
                            "target",
                            "auth-check",
                            "value",
                            1,
                            "enabled",
                            true,
                            "metadata",
                            Map.of("nested", Map.of(TOKEN, "synthetic-value"))));
            assertTrue(keyResult.path("isError").asBoolean(), keyResult.toString());
            assertFalse(keyResult.toString().contains(TOKEN_MARKER));
            var response = client.request("GET", "/control/state", null);
            assertEquals(200, response.statusCode(), response.body());
            assertFalse(response.body().contains(TOKEN_MARKER));
            var state = client.json(response);
            assertEquals(0, state.path("callCount").asInt());
            assertTrue(state.path("observations").isEmpty());
            assertTrue(state.path("effects").isEmpty());
        }
    }

    public static class AuthenticationProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("fixture.auth-token", TOKEN);
        }
    }
}
