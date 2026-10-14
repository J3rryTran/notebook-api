#!/usr/bin/env bash
set -e

echo "[STARTUP] Khởi động Notebook All-In-One Application..."

# Kiểm tra NOTEBOOK_INTERNAL_TOKEN bắt buộc
if [ -z "${NOTEBOOK_INTERNAL_TOKEN}" ]; then
  echo "[ERROR] NOTEBOOK_INTERNAL_TOKEN là biến môi trường bắt buộc. Vui lòng cung cấp token nội bộ!" >&2
  exit 1
fi

# 1. Khởi động nb-notebook-service (port 8084, actuator 8085)
echo "[STARTUP] Khởi động nb-notebook-service trên port 8084 (actuator 8085)..."
java -jar /app/nb-notebook-service.jar \
  --server.port=8084 \
  --management.server.port=8085 &
NB_PID=$!

# 2. Khởi động nb-execution-service (port 8082, actuator 8083, trỏ sang notebook-service:8084)
echo "[STARTUP] Khởi động nb-execution-service trên port 8082 (actuator 8083)..."
java -jar /app/nb-execution-service.jar \
  --server.port=8082 \
  --management.server.port=8083 \
  --notebook.notebook-service-url=http://127.0.0.1:8084 &
EXEC_PID=$!

# 3. Khởi động Nginx (lắng nghe port 8080)
echo "[STARTUP] Khởi động Nginx reverse proxy trên port 8080..."
nginx

echo "[STARTUP] Cả 3 thành phần (Frontend, Notebook Service, Execution Service) đã sẵn sàng phục vụ trên cổng 8080!"

# Trap tín hiệu để shutdown nhẹ nhàng
cleanup() {
  echo "[SHUTDOWN] Đang dừng các dịch vụ..."
  kill -TERM "$NB_PID" 2>/dev/null || true
  kill -TERM "$EXEC_PID" 2>/dev/null || true
  nginx -s quit 2>/dev/null || true
  wait "$NB_PID" 2>/dev/null || true
  wait "$EXEC_PID" 2>/dev/null || true
  exit 0
}

trap cleanup SIGINT SIGTERM

# Giám sát các tiến trình chạy ngầm
while true; do
  if ! kill -0 "$NB_PID" 2>/dev/null; then
    echo "[ERROR] nb-notebook-service bị dừng đột ngột!"
    exit 1
  fi
  if ! kill -0 "$EXEC_PID" 2>/dev/null; then
    echo "[ERROR] nb-execution-service bị dừng đột ngột!"
    exit 1
  fi
  if ! pgrep nginx > /dev/null; then
    echo "[ERROR] Nginx bị dừng đột ngột!"
    exit 1
  fi
  sleep 2
done
