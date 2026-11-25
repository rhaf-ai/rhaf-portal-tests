package ai.rhaf.portal.tests;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import static ai.rhaf.portal.tests.CoreHarness.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(90)
class CoreStaleDiscoveryIT {

    @Test
    void sameAddressReplacementRejectsEarlierDiscovery() throws Exception {
        var staleListingStarted = new CountDownLatch(1);
        var releaseStaleListing = new CountDownLatch(1);
        var listings = new AtomicInteger();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        try (var harness = new CoreHarness("stale-discovery");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var target = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
            target.setExecutor(executor);
            target.createContext(
                    "/mcp", exchange -> listTools(exchange, listings, staleListingStarted, releaseStaleListing));
            target.start();
            try {
                harness.startCore();
                URI address = new URI(
                        "http",
                        null,
                        loopback.getHostAddress(),
                        target.getAddress().getPort(),
                        "/mcp",
                        null,
                        null);
                URI forwards = harness.management("/api/rhaf/v1/forwards");
                URI forward = harness.management("/api/rhaf/v1/forwards/race");
                URI publicMcp = harness.mcp("/discovery-race-all/mcp");
                harness.request("POST", forwards, registration(address, "initial"), 201);
                harness.initialize(publicMcp);
                assertEquals(Set.of("initial_tool"), harness.tools(publicMcp));

                var earlier =
                        executor.submit(() -> harness.request("PUT", forward, registration(address, "earlier"), 409));
                assertTrue(
                        staleListingStarted.await(8, TimeUnit.SECONDS),
                        "Earlier replacement did not request tools/list");

                JsonNode committed = harness.request("PUT", forward, registration(address, "later"));
                assertEquals("later", committed.at("/data/labels/revision").asText());
                assertEquals(Set.of("current_tool"), harness.tools(publicMcp));

                releaseStaleListing.countDown();
                earlier.get(10, TimeUnit.SECONDS);

                assertEquals(committed, harness.request("GET", forward, null));
                assertEquals(Set.of("current_tool"), harness.tools(publicMcp));
            } finally {
                releaseStaleListing.countDown();
                target.stop(0);
            }
        }
    }

    private static JsonNode registration(URI address, String revision) throws IOException {
        return json(
                """
                {"name":"race","address":"%s","namespaceBase":"discovery-race","labels":{"revision":"%s"}}
                """
                        .formatted(address, revision));
    }

    private static void listTools(
            HttpExchange exchange,
            AtomicInteger listings,
            CountDownLatch staleListingStarted,
            CountDownLatch releaseStaleListing)
            throws IOException {
        try (exchange) {
            JsonNode request = json(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (!request.has("id")) {
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            String result;
            switch (request.path("method").asText()) {
                case "initialize" ->
                    result =
                            """
                        {"protocolVersion":"2025-03-26","capabilities":{"tools":{}},
                         "serverInfo":{"name":"discovery-target","version":"1"}}
                        """;
                case "resources/list" -> result = "{\"resources\":[]}";
                case "resources/templates/list" -> result = "{\"resourceTemplates\":[]}";
                case "prompts/list" -> result = "{\"prompts\":[]}";
                case "tools/list" -> {
                    int listing = listings.incrementAndGet();
                    if (listing == 2) {
                        staleListingStarted.countDown();
                        try {
                            if (!releaseStaleListing.await(12, TimeUnit.SECONDS)) {
                                exchange.sendResponseHeaders(504, -1);
                                return;
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted while holding stale discovery", failure);
                        }
                    }
                    String tool =
                            switch (listing) {
                                case 1 -> "initial_tool";
                                case 2 -> "stale_tool";
                                default -> "current_tool";
                            };
                    result =
                            """
                            {"tools":[{"name":"%s","inputSchema":{"type":"object"}}]}
                            """
                                    .formatted(tool);
                }
                default -> {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
            }
            byte[] body = """
                    {"jsonrpc":"2.0","id":%s,"result":%s}
                    """
                    .formatted(request.get("id"), result)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
