#!/usr/bin/env bash
# Runs the tests that need a real database.
#
# They are skipped by default, and silently: `@Testcontainers(disabledWithoutDocker = true)`
# turns "no Docker" into a pass. That is why the JDBC outbox went a long time with two bugs a
# real PostgreSQL found within seconds of first being pointed at it — a missing space in
# concatenated SQL, and a claim batch returned in arbitrary order.
#
# Two things have to be told to Docker Desktop on macOS, neither of which Testcontainers works
# out for itself:
#
#   api.version           docker-java negotiates API 1.32 by default; Engine 25+ answers 400 to
#                         anything below 1.40. It reads this as a system property — the
#                         DOCKER_API_VERSION environment variable does not reach it.
#   DOCKER_HOST           ~/.docker/run/docker.sock is a redirector, not the engine. It answers
#                         /info with a 400 whose body names the real address, which reads as a
#                         broken daemon rather than a redirect.
#
# On Linux, or anywhere DOCKER_HOST already points at a real engine socket, only api.version
# matters and the rest is harmless.
set -euo pipefail

# Check the daemon through the CLI's own configuration, BEFORE overriding DOCKER_HOST below.
# The raw socket serves docker-java but hangs the CLI, so a pre-flight check pointed at it never
# returns — which looks exactly like a slow test suite.
if ! docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop, or these tests will pass by not running." >&2
  exit 1
fi

RAW_SOCKET="$HOME/Library/Containers/com.docker.docker/Data/docker.raw.sock"
if [ -S "$RAW_SOCKET" ]; then
  export DOCKER_HOST="unix://$RAW_SOCKET"
  # Ryuk is the resource reaper that removes containers if the JVM dies without cleaning up.
  # It wants to bind-mount the Docker socket into itself, which the raw socket cannot do, and
  # its startup also consults the credential helper — thirty seconds on a good day and an
  # indefinite block when the keychain is locked. Testcontainers still stops its own containers
  # on a normal exit; what is lost is the cleanup after a kill -9.
  export TESTCONTAINERS_RYUK_DISABLED=true
fi

exec mvn "$@" test -Dapi.version=1.44
