#!/usr/bin/env bash
set -eo pipefail

TARGET="${1:-readiness}"

if [ "$TARGET" = "liveness" ]; then
  curl -fs -m 3 http://127.0.0.1:8085/actuator/health/liveness >/dev/null && \
  curl -fs -m 3 http://127.0.0.1:8083/actuator/health/liveness >/dev/null
else
  curl -fs -m 3 http://127.0.0.1:8085/actuator/health/readiness >/dev/null && \
  curl -fs -m 3 http://127.0.0.1:8083/actuator/health/readiness >/dev/null
fi
