#!/usr/bin/env bash
set -euo pipefail

echo "[STARTUP] notebook contract=2"

# Tự sinh token nếu chưa có (không in ra log)
: "${NOTEBOOK_INTERNAL_TOKEN:=$(head -c 32 /dev/urandom | base64 | tr -d '\n')}"
export NOTEBOOK_INTERNAL_TOKEN

CFG=()
if [ -n "${NOTEBOOK_CONFIG:-}" ]; then
  CFG=(--spring.config.additional-location="file:${NOTEBOOK_CONFIG}")
fi

# 1. Khởi động nb-notebook-service
# shellcheck disable=SC2086
java ${JAVA_OPTS_NOTEBOOK:-} -jar /app/nb-notebook-service.jar "${CFG[@]}" \
  --server.address=127.0.0.1 --server.port=8084 --management.server.port=8085 --management.server.address=127.0.0.1 &
NB_PID=$!

# 2. Khởi động nb-execution-service
# shellcheck disable=SC2086
java ${JAVA_OPTS_EXECUTION:-} -jar /app/nb-execution-service.jar "${CFG[@]}" \
  --server.address=127.0.0.1 --server.port=8082 --management.server.port=8083 --management.server.address=127.0.0.1 \
  --notebook.notebook-service-url=http://127.0.0.1:8084 &
EXEC_PID=$!

# 3. Khởi động Nginx
nginx -g 'daemon off;' &
NGINX_PID=$!

# Trap SIGTERM/SIGINT: nginx quit -> TERM 2 JVM -> wait
cleanup() {
  echo "[SHUTDOWN] Đang dừng Nginx và các JVM..."
  nginx -s quit 2>/dev/null || true
  kill -TERM "$NB_PID" 2>/dev/null || true
  kill -TERM "$EXEC_PID" 2>/dev/null || true
  wait "$NB_PID" 2>/dev/null || true
  wait "$EXEC_PID" 2>/dev/null || true
  wait "$NGINX_PID" 2>/dev/null || true
  exit 0
}

trap cleanup SIGINT SIGTERM

# Giám sát các tiến trình; dừng ngay nếu bất kỳ tiến trình nào thoát
wait -n "$NB_PID" "$EXEC_PID" "$NGINX_PID" || true
cleanup
