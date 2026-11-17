# Project Standards

Build and code settings for OSS Helper.

- Build tool: Maven, JDK 21
- Build command: mvn verify
- Test command: mvn verify
- Test with coverage command: none configured
- Format command: mvn spotless:apply
- Static analysis command: mvn verify -Pstatic-analysis
- Module-specific build: yes; run the full reactor before commit
- Parallelized Maven: no
- Code style: Palantir Java Format; no wildcard imports
- Test classes: *Test.java
- Technical text: ASD-STE100 Simplified Technical English
- Keep tests independent of Saci and model providers. Use synthetic fixture state.

## Version
0d234a11fc0a0d1478aa11edbf68d1a880c29cda
