# RHAF Portal Tests

Java tests and Saci worker infrastructure for RHAF Portal. The optional
Core integration profile tests a real Core process with controlled MCP targets.

## Build & Run

```bash
# Build without tests
mvn -DskipTests package

# Run the tests
mvn verify

# Run static analysis
mvn verify -Pstatic-analysis

# Check formatting
mvn spotless:check
```

## Project Structure

```text
rhaf-portal-tests/
  pom.xml                 # Maven build, Java 21, quality configuration
  portal-tests/           # Portal JUnit and Core integration tests
  fixtures/test-mcp-server/ # Controlled Quarkus HTTP MCP target and tests
  spotbugs-exclude.xml    # SpotBugs exclusion filter
  saci/                   # Saci worker image
  .github/workflows/      # Builds, analysis, and container publishing
```

## Code Style & Conventions

- Java 21, formatted by Spotless with Palantir Java Format (auto-applied at compile).
- Wildcard imports are forbidden; import order is enforced by Spotless.
- Surefire runs `*Test.java`. Failsafe runs `*IT.java` in the Core integration profile.
- Keep the native Java test suite runnable without Saci or a model provider.

## Guidelines for Code Generation

Prioritize clarity, stability, and ease of maintenance:

- Keep test infrastructure simple and predictable. Avoid clever abstractions; prefer explicit setup over hidden magic in base classes.
- Each test should be readable on its own. A reader should understand what is being tested without tracing through multiple layers of inheritance.
- Use descriptive method and variable names. Favor longer, self-documenting names over terse ones.
- Do not add retry/polling logic unless testing an inherently async operation. Tests that need retries to pass are hiding flaky behavior.
- Process lifecycle management must be deterministic: clear start, health-check, use, shutdown sequence. Always clean up in `@AfterAll`/`@AfterEach`.
- Do not introduce new dependencies without justification.
- Do not catch and swallow exceptions in test setup/teardown. Let failures propagate so they surface clearly.
- Missing required infrastructure must remain visible as missing evidence; skipped tests do not establish success.

## Verifying Changes on CI

The manual `full-integration-test` workflow builds a pinned Core revision and
runs the Core integration profile. Pull request and main builds run
`mvn verify`. Static analysis runs separately.

## Documentation

- Keep build and contributor entry points in `README.md`.
- Keep worker image instructions in `saci/README.md`.
- Use ASD-STE100 Simplified Technical English for technical text.
