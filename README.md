# RHAF Portal Tests

Tests and test infrastructure for RHAF Portal. The repository contains a Java
test scaffold, a controlled HTTP MCP fixture, code quality checks, CI workflows,
a Saci worker image, and black-box tests for Portal Core.

## Build

Install JDK 21 and Maven 3.9 or later, then run:

```bash
mvn verify
```

Portal test sources are in `portal-tests/src/test/java/ai/rhaf/portal/tests`.
Name test classes `*Test.java`. Results are written to each module's
`target/surefire-reports/`. Java tests run
without a Saci backend or model provider.

## Core integration tests

Build Portal Core at the revision in the
[manual integration workflow](.github/workflows/full-integration-test.yml).
Run the integration profile from the root of this repository:

```bash
mvn verify -Pcore-integration -Dcore.binary=/absolute/path/to/rhaf-portal-core
```

The profile runs the `*IT.java` tests and requires an executable Core binary.
Each scenario starts Core and the fixture on loopback with fresh data. Maven
writes test results to `portal-tests/target/failsafe-reports/` and retains
process logs and fixture observations in `portal-tests/target/core-integration/`.

## Controlled HTTP MCP fixture

The [test MCP server](fixtures/test-mcp-server/README.md) provides typed tools,
synthetic state, optional authentication, catalog changes, fault controls, and
request observations. Use its `/mcp` URL as a real HTTP target for gateway,
operator, and client tests. Build and test it with the root `mvn verify` command.
The fixture guide includes local startup, container startup, reset, and test
lifecycle examples.

## Code quality

The build uses Spotless with Palantir Java Format, forbids wildcard imports, and
enforces import order. Spotless applies formatting during `compile`.

```bash
mvn spotless:check
mvn verify -Pstatic-analysis
```

The `static-analysis` profile enables PMD and SpotBugs. Their reports are written
to `target/`. The SpotBugs exclusion filter is `spotbugs-exclude.xml`.

## CI and containers

Pull requests and pushes to `main` run the Java suite. Static analysis publishes
reports and a separate workflow adds the results to pull requests. The manual
`full-integration-test` workflow builds a pinned Core revision and runs the
Core integration profile.

The Saci container workflow builds on native AMD64 and ARM64 runners. Pull
requests build and smoke-test the worker. Main builds publish architecture
images and combined `sha-<commit>` and `latest` manifests. Publishing uses the
organization variables `CONTAINER_REGISTRY` and `CONTAINER_GROUP` and the
repository secrets `QUAY_USERNAME` and `QUAY_PASSWORD`.

See [Saci worker instructions](saci/README.md) for local builds and execution.
Dependabot checks Maven dependencies, GitHub Actions, and the worker base image
weekly.

## License

[Apache License 2.0](.LICENSE).
