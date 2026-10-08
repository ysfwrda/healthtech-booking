#!/bin/bash
# Prepares a Claude Code cloud session so every test layer can run:
# Docker for Testcontainers, a JWT key pair in keys/, Maven and npm deps.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

# Testcontainers (all *IntegrationTest and most *ApplicationTests) needs a daemon.
if ! docker info >/dev/null 2>&1 && command -v dockerd >/dev/null 2>&1; then
  nohup dockerd >/tmp/dockerd.log 2>&1 &
  for _ in $(seq 1 30); do
    docker info >/dev/null 2>&1 && break
    sleep 1
  done
fi
if docker info >/dev/null 2>&1; then
  for image in postgres:16-alpine confluentinc/cp-kafka:7.7.0; do
    docker pull -q "$image" >/dev/null || echo "warning: could not pull $image" >&2
  done
else
  echo "warning: Docker is not available; Testcontainers tests will fail" >&2
fi

# Same key generation as CI. The new public.pem replaces the committed one
# locally, so hide that change from git to keep it out of commits.
if [ ! -f keys/private.pem ]; then
  openssl genrsa -out keys/private.pem 2048 2>/dev/null
  openssl rsa -in keys/private.pem -pubout -out keys/public.pem 2>/dev/null
  git update-index --skip-worktree keys/public.pem
fi

# Download dependencies and compile each service without running tests. The
# JUnit provider is only fetched by the first real test run.
# Best effort: one failing service (e.g. a network hiccup) shouldn't skip the rest.
for service in api-gateway appointment-service doctor-service notification-service patient-service; do
  (cd "$service" && mvn -B -q test -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false) \
    || echo "warning: could not prepare $service" >&2
done

(cd frontend && npm install --no-audit --no-fund --loglevel=error --no-update-notifier) \
  || echo "warning: npm install failed in frontend" >&2
