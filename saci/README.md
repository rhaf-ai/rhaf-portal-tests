# Saci worker image

This image extends `quay.io/orpiske/saci-worker:latest` with Java 21 or later,
Maven, and the RHAF Portal test scaffold at `/opt/rhaf-portal-tests`. It keeps the
Saci worker entry point and supports an arbitrary UID in group 0.

Build from the repository root:

```sh
podman build -f saci/Containerfile -t localhost/rhaf-portal-saci-worker:latest .
```

Use `--build-arg SACI_IMAGE=<image>@sha256:<digest>` to select an exact Saci
base image. Record the resulting worker image digest with test evidence.

Run the bundled deterministic tests without a Saci backend or model provider:

```sh
podman run --rm --user 123456:0 --entrypoint mvn \
  localhost/rhaf-portal-saci-worker:latest \
  --batch-mode --no-transfer-progress -f /opt/rhaf-portal-tests/pom.xml clean verify
```

Set Saci's `worker.image` to the published image. CI publishes
`${CONTAINER_REGISTRY}/${CONTAINER_GROUP}/rhaf-portal-saci-worker` using the
organization variables `CONTAINER_REGISTRY` and `CONTAINER_GROUP`. Pushes to
`main` that change the image inputs and manual runs on `main` build on native
Linux AMD64 and ARM64 runners. Both architectures must build and pass the
container smoke check before the workflow publishes `sha-<commit>` and
`latest` manifests. Registry credentials use the `QUAY_USERNAME` and
`QUAY_PASSWORD` GitHub secrets. Pull requests build and test without publishing.

The bundled test currently validates only the project scaffold. Product tests
and Saci plans can be added as RHAF Portal contracts become available.
