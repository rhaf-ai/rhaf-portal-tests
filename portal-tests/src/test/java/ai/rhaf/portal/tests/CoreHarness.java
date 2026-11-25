package ai.rhaf.portal.tests;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts a real Core and controlled MCP targets for one integration scenario. */
final class CoreHarness implements AutoCloseable {

    // Use IPv4 loopback for every child process and client address.
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    private static final String LOOPBACK = "127.0.0.1";

    private static final String MCP_VERSION = "2025-03-26";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(1);
    private static final Path FIXTURE_JAR = Path.of("../fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar")
            .toAbsolutePath();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path binary;
    private final Path evidence;
    private final Path config;
    private final HttpClient client;
    private final List<RunningProcess> fixtures = new ArrayList<>();
    private final AtomicInteger requestIds = new AtomicInteger();

    private int managementPort;
    private int mcpPort;
    private int coreStarts;
    private RunningProcess core;

    CoreHarness(String scenario) throws IOException {
        String configuredBinary = System.getProperty("core.binary");
        if (configuredBinary == null || configuredBinary.isBlank()) {
            throw new IllegalArgumentException("Set -Dcore.binary to the Core executable");
        }
        binary = Path.of(configuredBinary).toAbsolutePath();
        if (!Files.isRegularFile(binary) || !Files.isExecutable(binary)) {
            throw new IOException("Core executable is missing or cannot run: " + binary);
        }
        Path evidenceBase = Path.of("target/core-integration").toAbsolutePath();
        Files.createDirectories(evidenceBase);
        evidence = Files.createTempDirectory(evidenceBase, scenario + "-");
        config = Files.writeString(evidence.resolve("wanaku.yaml"), "{}\n");
        client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .connectTimeout(PROBE_TIMEOUT)
                .build();
    }

    URI startFixture() throws IOException, InterruptedException {
        if (!Files.isRegularFile(FIXTURE_JAR)) {
            throw new IOException("Fixture JAR is missing. Run Maven from the reactor root: " + FIXTURE_JAR);
        }
        int port;
        try (ServerSocket socket = reservePort()) {
            port = socket.getLocalPort();
        }
        URI base = localUri(port, "");
        ProcessBuilder command = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dquarkus.http.host=" + LOOPBACK,
                "-Dquarkus.http.port=" + port,
                "-Dfixture.auth-token=",
                "-jar",
                FIXTURE_JAR.toString());
        RunningProcess fixture = launch(command, "fixture-" + (fixtures.size() + 1) + ".log", true);
        fixtures.add(fixture);
        waitForReadiness(fixture, base.resolve("/health"));
        return base;
    }

    void startCore() throws IOException, InterruptedException {
        if (core != null) {
            throw new IllegalStateException("Core is already started");
        }
        if (coreStarts == 0) {
            try (ServerSocket management = reservePort();
                    ServerSocket mcp = reservePort()) {
                managementPort = management.getLocalPort();
                mcpPort = mcp.getLocalPort();
            }
        }
        ProcessBuilder command = new ProcessBuilder(
                binary.toString(),
                "--mcp-listen",
                LOOPBACK + ":" + mcpPort,
                "--management-listen",
                LOOPBACK + ":" + managementPort,
                "--data-dir",
                evidence.resolve("data").toString(),
                "--wanaku-config",
                config.toString(),
                "--shutdown-timeout-secs",
                "1");
        core = launch(command, "core-" + ++coreStarts + ".log", false);
        waitForReadiness(core, management("/api/v1/tools"));
    }

    void restartCore() throws IOException, InterruptedException {
        core.stop();
        core = null;
        startCore();
    }

    URI management(String path) {
        return localUri(managementPort, path);
    }

    URI mcp(String path) {
        return localUri(mcpPort, path);
    }

    static JsonNode json(String body) throws IOException {
        return JSON.readTree(body);
    }

    void save(String fileName, JsonNode body) throws IOException {
        Files.writeString(evidence.resolve(fileName), body.toPrettyString() + "\n");
    }

    JsonNode registrySnapshot() throws IOException {
        return json(Files.readString(evidence.resolve("data/registry.json")));
    }

    JsonNode request(String method, URI uri, JsonNode body) throws IOException, InterruptedException {
        return request(method, uri, body, 200);
    }

    JsonNode request(String method, URI uri, JsonNode body, int expectedStatus)
            throws IOException, InterruptedException {
        return checkedBody(send(method, uri, body, false), expectedStatus);
    }

    void initialize(URI uri) throws IOException, InterruptedException {
        JsonNode result = rpc(
                uri,
                "initialize",
                json(
                        """
                {"protocolVersion":"2025-03-26","capabilities":{},
                 "clientInfo":{"name":"rhaf-portal-tests","version":"1.0"}}
                """));
        assertEquals(MCP_VERSION, result.path("protocolVersion").asText(), result::toString);
        ObjectNode notification =
                JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized");
        checkedBody(send("POST", uri, notification, true), 202);
    }

    Set<String> tools(URI uri) throws IOException, InterruptedException {
        JsonNode tools = rpc(uri, "tools/list", null).path("tools");
        assertTrue(tools.isArray(), tools::toString);
        Set<String> names = new HashSet<>();
        for (JsonNode tool : tools) {
            JsonNode name = tool.path("name");
            assertTrue(name.isTextual() && names.add(name.asText()), tool::toString);
        }
        return names;
    }

    JsonNode call(URI uri, String name, JsonNode arguments) throws IOException, InterruptedException {
        ObjectNode params = JSON.createObjectNode().put("name", name);
        params.set("arguments", arguments);
        return rpc(uri, "tools/call", params);
    }

    private JsonNode rpc(URI uri, String method, JsonNode params) throws IOException, InterruptedException {
        int id = requestIds.incrementAndGet();
        ObjectNode body =
                JSON.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        if (params != null) {
            body.set("params", params);
        }
        HttpResponse<String> response = send("POST", uri, body, true);
        assertFalse(response.headers().firstValue("Mcp-Session-Id").isPresent(), "Core must serve stateless MCP");
        JsonNode envelope = checkedBody(response, 200);
        assertEquals(JSON.getNodeFactory().numberNode(id), envelope.get("id"), envelope::toString);
        assertFalse(envelope.has("error"), envelope::toString);
        JsonNode result = envelope.path("result");
        assertTrue(result.isObject(), envelope::toString);
        return result;
    }

    private HttpResponse<String> send(String method, URI uri, JsonNode body, boolean mcp)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
        if (mcp) {
            request.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", MCP_VERSION);
        } else {
            request.header("Accept", "application/json");
        }
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode checkedBody(HttpResponse<String> response, int expectedStatus) throws IOException {
        String body = response.body();
        assertEquals(expectedStatus, response.statusCode(), () -> response.uri() + ": " + body);
        return body.isBlank() ? NullNode.getInstance() : json(body);
    }

    private RunningProcess launch(ProcessBuilder command, String fileName, boolean fixture) throws IOException {
        command.environment()
                .keySet()
                .removeIf(name -> name.startsWith("WANAKU_")
                        || name.startsWith("PRAXIS_")
                        || name.startsWith("QUARKUS_")
                        || name.startsWith("FIXTURE_")
                        || name.startsWith("RHAF_"));
        Path log = evidence.resolve(fileName);
        Process process = command.directory(evidence.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        return new RunningProcess(process, log, fixture);
    }

    private void waitForReadiness(RunningProcess child, URI uri) throws IOException, InterruptedException {
        HttpRequest probe = HttpRequest.newBuilder(uri).timeout(PROBE_TIMEOUT).build();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        IOException lastFailure = null;
        while (child.process.isAlive() && System.nanoTime() < deadline) {
            try {
                if (client.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode() == 200
                        && child.process.isAlive()) {
                    return;
                }
            } catch (IOException failure) {
                lastFailure = failure;
            }
            Thread.sleep(100);
        }
        throw new IOException("Process did not become ready at " + uri + ". See " + child.log, lastFailure);
    }

    private static ServerSocket reservePort() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK));
    }

    private static URI localUri(int port, String path) {
        return URI.create("http://" + LOOPBACK + ":" + port + path);
    }

    @Override
    public void close() throws IOException, InterruptedException {
        List<RunningProcess> children = new ArrayList<>();
        if (core != null) {
            children.add(core);
        }
        children.addAll(fixtures.reversed());
        IOException cleanupFailure = null;
        InterruptedException interruption = null;
        try (client) {
            for (RunningProcess child : children) {
                try {
                    child.stop();
                } catch (IOException failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = new IOException("Core integration cleanup failed. See " + evidence);
                    }
                    cleanupFailure.addSuppressed(failure);
                } catch (InterruptedException failure) {
                    interruption = failure;
                }
            }
        }
        if (interruption != null) {
            Thread.currentThread().interrupt();
            if (cleanupFailure != null) {
                interruption.addSuppressed(cleanupFailure);
            }
            throw interruption;
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private static final class RunningProcess {

        private final Process process;
        private final Path log;
        private final boolean fixture;

        private RunningProcess(Process process, Path log, boolean fixture) {
            this.process = process;
            this.log = log;
            this.fixture = fixture;
        }

        private void stop() throws IOException, InterruptedException {
            if (!process.isAlive()) {
                throw new IOException(
                        "Process exited before shutdown with status " + process.exitValue() + ". See " + log);
            }
            process.destroy();
            try {
                if (!process.waitFor(25, TimeUnit.SECONDS)) {
                    throw new IOException("Process did not stop after SIGTERM. See " + log);
                }
                int status = process.exitValue();
                if (status != 0 && !(fixture && status == 143)) {
                    throw new IOException("Process shutdown exited with status " + status + ". See " + log);
                }
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        throw new IOException("Process did not stop after forced shutdown. See " + log);
                    }
                }
            }
        }
    }
}
