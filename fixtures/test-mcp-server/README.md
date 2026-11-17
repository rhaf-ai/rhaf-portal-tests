# Controlled HTTP MCP server

This Quarkus fixture is a real HTTP MCP target for gateway, operator, and client
tests. It uses synthetic state. It needs no RHAF services, cluster, Saci backend,
or model provider. A passing fixture test does not prove RHAF product behavior.

The layout and use follow the Wanaku
[test MCP fixture](https://github.com/wanaku-ai/wanaku-tests/tree/main/fixtures/test-mcp-server)
and its
[forwarding test lifecycle](https://github.com/wanaku-ai/wanaku-tests/blob/main/mcp-forwarding-tests/src/test/java/ai/wanaku/test/forward/McpForwardingTestBase.java):
build a separate server JAR, start it, check readiness, register its `/mcp` URL
with the system under test, make assertions, remove test registrations, then
stop the server. The observation API supplies independent evidence of calls
that reach the target. It does not replace tests through the real gateway.

## Build and start

From the repository root, with Java 21 and Maven 3.9 or later:

```sh
mvn verify
java -jar fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar
```

The default address is `http://127.0.0.1:8181`. Readiness is `GET /health`.
The Streamable HTTP MCP endpoint is `/mcp`. Configure the port and interface
with `QUARKUS_HTTP_PORT` and `QUARKUS_HTTP_HOST`, or JVM properties:

```sh
java -Dquarkus.http.port=8282 -jar fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar
```

Keep the complete `target/quarkus-app/` directory when you copy the fixture.
The runner JAR needs its adjacent libraries.

## Tools and observations

| Tool | Arguments | Effect |
|---|---|---|
| `read_state` | `target`: string | Read the target's synthetic state. |
| `set_state` | `target`: string, `value`: integer, `enabled`: boolean, optional `metadata`: object | Set the target's synthetic state. |

Arguments retain JSON types. Send integers and booleans as JSON numbers and
booleans. Each tool has an explicit input schema. Do not send real secrets in
tool arguments or metadata; observations retain synthetic argument values.
Authentication headers are not part of observations or results.
Targets contain 1 to 128 characters. Values are signed 32-bit integers.
Reading a target with no state returns `state: null`.

`GET /control/state` returns the call count, ordered observations, and state
effects keyed by target. Each observation identifies the sequence, tool,
target, original JSON arguments, and outcome. Use it to check target identity,
argument types, attempts, and effects independently of the MCP response.
Requests are limited to 64 KiB. The fixture keeps at most 1,000 observations;
further calls return a tool error until reset. Calls rejected by authentication
or the catalog do not reach the tool and do not increment its count.

```sh
curl --fail-with-body http://127.0.0.1:8181/control/state
```

## Runtime controls and reset

Read settings with `GET /control/config`. Replace them with
`POST /control/config`. No restart is needed:

```sh
curl --fail-with-body http://127.0.0.1:8181/control/config \
  -H 'Content-Type: application/json' \
  -d '{"enabledTools":["read_state"],"latencyMillis":0,"failureMode":"none"}'
```

| Setting | Meaning |
|---|---|
| `enabledTools` | Tools available to discover and call. An empty list removes all tools. |
| `latencyMillis` | Delay for each valid tool call, from 0 to 30,000 ms. |
| `failureMode: "none"` | Normal operation. |
| `failureMode: "before"` | Return a tool error before the synthetic effect. |
| `failureMode: "after"` | Apply the synthetic effect, then return a tool error. |

The `after` mode proves why a failed call must not cause automatic replay.
Use the observation count and effects to distinguish an attempt from a
successful response. Re-list tools or refresh the gateway's catalog after a
catalog change. Catalog controls do not send a `notifications/tools/list_changed`
event. Fault settings apply to each call until replaced or reset; a pending call
keeps the settings that it received at entry.

Reset the state, observations, count, and runtime settings between scenarios:

```sh
curl --fail-with-body -X POST http://127.0.0.1:8181/control/reset
```

Reset restores both tools, zero delay, and no injected failure. Authentication
is a startup setting and survives reset. Use a separate fixture process per
concurrent test run. Complete outstanding calls before reset.
If reset occurs during a delayed call, that call returns an error and cannot
restore cleared state.

## Optional authentication

By default, no authentication is required. Set `FIXTURE_AUTH_TOKEN` before
startup to require `Authorization: Bearer <token>` on `/mcp` and `/control`.
`/health` remains public. Missing or incorrect credentials return HTTP 401.

```sh
export FIXTURE_AUTH_TOKEN=synthetic-test-token
java -jar fixtures/test-mcp-server/target/quarkus-app/quarkus-run.jar
```

For control requests, supply the same synthetic token:

```sh
curl --fail-with-body -H "Authorization: Bearer ${FIXTURE_AUTH_TOKEN}" \
  http://127.0.0.1:8181/control/state
```

Bind to a trusted test interface. The control API can change state and inject
failures; it uses the same optional authentication as MCP.

For a client that accepts an `mcpServers` configuration:

```json
{
  "mcpServers": {
    "rhaf-fixture": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8181/mcp"
    }
  }
}
```

When authentication is enabled, configure that client's bearer header with the
synthetic token. Test discovery and calls through the gateway when the gateway
is the component under test.

## Container

Build the JAR first. From the repository root:

```sh
mvn verify
podman build -f fixtures/test-mcp-server/Containerfile \
  -t localhost/rhaf-test-mcp-server:local fixtures/test-mcp-server
podman run --rm --name rhaf-test-mcp-server -p 127.0.0.1:8181:8181 \
  localhost/rhaf-test-mcp-server:local
```

Pass `-e FIXTURE_AUTH_TOKEN` to the container when authentication is required.
The container listens on port 8181. Its state is held in memory. Stop it with
`podman stop rhaf-test-mcp-server`; a new container starts with clean state.

## Test ownership

The fixture's HTTP tests initialize real MCP sessions and exercise discovery,
typed calls, authentication, faults, controls, and reset. The portal module's
`FixtureProcessTest` starts the packaged JAR, waits for readiness with a bounded
deadline, connects as an MCP client, and stops the child in `@AfterAll`.
Missing build artifacts fail the test. They do not cause a skip.

Use the same order in new integration tests. Register the exact fixture URL
with the gateway, invoke through the real client path, inspect `/control/state`,
and clean up registrations before stopping the fixture. Retain the fixture
revision, settings, assertions, and logs with the test evidence.
