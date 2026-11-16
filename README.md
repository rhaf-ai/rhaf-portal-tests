# RHAF Portal Tests

Tests for RHAF Portal. This repository currently provides a Java scaffold with
one passing JUnit test, code quality checks, CI workflows, and a Saci worker image.
The bootstrap test verifies the test runner only; product tests will be added as
the portal is implemented.

## Build

Install JDK 21 and Maven 3.9 or later, then run:

```bash
mvn verify
```

Test sources are in `src/test/java/ai/rhaf/portal/tests`. Name test classes
`*Test.java`. Results are written to `target/surefire-reports/`. Java tests run
without a Saci backend or model provider.

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
`full-integration-test` workflow runs the current suite; initially this checks
only the scaffold.

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
