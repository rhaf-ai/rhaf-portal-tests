package ai.rhaf.portal.tests;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Uses the packaged fixture as an external HTTP target, as gateway tests will. */
@Timeout(60)
class FixtureProcessTest {

    private static final Path SERVER_JAR = Path.of("../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar")
            .toAbsolutePath();
    private static final Path SERVER_LOG = Path.of("target/fixture-process.log").toAbsolutePath();
    private static Process server;
    private static URI baseUri;

    @BeforeAll
    static void startServer() throws Exception {
        assertTrue(Files.isRegularFile(SERVER_JAR), "Build the fixture first with root mvn verify: " + SERVER_JAR);
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        baseUri = URI.create("http://127.0.0.1:" + port);
        Files.createDirectories(SERVER_LOG.getParent());
        server = new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dquarkus.http.host=127.0.0.1",
                        "-Dquarkus.http.port=" + port,
                        "-Dfixture.auth-token=",
                        "-jar",
                        SERVER_JAR.toString())
                .redirectErrorStream(true)
                .redirectOutput(SERVER_LOG.toFile())
                .start();
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            IOException lastFailure = null;
            while (server.isAlive() && System.nanoTime() < deadline) {
                try {
                    HttpResponse<String> response = client.send(
                            HttpRequest.newBuilder(baseUri.resolve("/health"))
                                    .timeout(Duration.ofSeconds(1))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        return;
                    }
                } catch (IOException failure) {
                    lastFailure = failure;
                }
                Thread.sleep(100);
            }
            throw new IllegalStateException(
                    "Fixture did not become ready. Log: " + Files.readString(SERVER_LOG), lastFailure);
        }
    }

    @AfterAll
    static void stopServer() throws InterruptedException {
        if (server != null) {
            server.destroy();
            if (!server.waitFor(10, TimeUnit.SECONDS)) {
                server.destroyForcibly();
                assertTrue(server.waitFor(10, TimeUnit.SECONDS), "Fixture process did not stop");
            }
        }
    }

    @Test
    void packagedServerAcceptsAnMcpClient() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(baseUri.resolve("/mcp"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .header("Accept", "application/json, text/event-stream")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            """
                                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                                      "protocolVersion":"2025-03-26","capabilities":{},
                                      "clientInfo":{"name":"packaged-fixture-test","version":"1.0"}}}
                                    """))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"serverInfo\""), response.body());
            assertFalse(response.body().contains("\"error\""), response.body());
        }
    }
}
