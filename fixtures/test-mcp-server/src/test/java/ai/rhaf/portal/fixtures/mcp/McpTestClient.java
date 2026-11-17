package ai.rhaf.portal.fixtures.mcp;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A real HTTP MCP session. The test owns and closes the client after each use. */
final class McpTestClient implements AutoCloseable {

    private final URI baseUri;
    private final String bearerToken;
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private String sessionId;
    private int nextId;

    McpTestClient(URI baseUri, String bearerToken) {
        this.baseUri = baseUri;
        this.bearerToken = bearerToken;
    }

    void initialize() throws IOException, InterruptedException {
        HttpResponse<String> response = request(
                "POST",
                "/mcp",
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        ++nextId,
                        "method",
                        "initialize",
                        "params",
                        Map.of(
                                "protocolVersion", "2025-03-26",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "fixture-test", "version", "1.0"))));
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(json(response).has("error"), response.body());
        sessionId = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
        assertEquals(
                202,
                request("POST", "/mcp", Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                        .statusCode());
    }

    JsonNode rpc(String method, Map<String, ?> params) throws IOException, InterruptedException {
        int requestId = ++nextId;
        HttpResponse<String> response =
                request("POST", "/mcp", Map.of("jsonrpc", "2.0", "id", requestId, "method", method, "params", params));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode message = json(response);
        assertEquals("2.0", message.path("jsonrpc").asText(), response.body());
        assertEquals(requestId, message.path("id").asInt(), response.body());
        return message;
    }

    JsonNode call(String name, Map<String, ?> arguments) throws IOException, InterruptedException {
        JsonNode message = rpc("tools/call", Map.of("name", name, "arguments", arguments));
        assertFalse(message.has("error"), message.toString());
        assertNotNull(message.get("result"), message.toString());
        return message.get("result");
    }

    HttpResponse<String> request(String method, String path, Object body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("Mcp-Protocol-Version", "2025-03-26");
        if (bearerToken != null) {
            request.header("Authorization", "Bearer " + bearerToken);
        }
        if (sessionId != null && path.equals("/mcp")) {
            request.header("Mcp-Session-Id", sessionId);
        }
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode json(HttpResponse<String> response) throws IOException {
        return mapper.readTree(response.body());
    }

    JsonNode toolValue(JsonNode result) throws IOException {
        assertFalse(result.path("isError").asBoolean(), result.toString());
        return mapper.readTree(result.path("content").get(0).path("text").asText());
    }

    @Override
    public void close() {
        client.close();
    }
}
