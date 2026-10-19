#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
NETWORK_NAME="nb-local-test"
DEFAULT_IMAGE="docker.io/j3rrytran/notebook:0.1.1"

CMD="${1:-start}"
IMAGE="${2:-$DEFAULT_IMAGE}"

# Nếu tham số đầu tiên là tên image (có chứa : hoặc /)
if [[ "$CMD" == *:* || "$CMD" == */* ]]; then
  IMAGE="$CMD"
  CMD="${2:-start}"
fi

case "$CMD" in
  start)
    echo "=== KHỞI CHẠY MÔI TRƯỜNG TEST LOCALHOST (BỎ QUA BẢO MẬT) ==="
    echo "Image: ${IMAGE}"

    # 1. Tạo network nếu chưa có
    if ! docker network inspect "${NETWORK_NAME}" >/dev/null 2>&1; then
      echo "[1/4] Đang tạo Docker network: ${NETWORK_NAME}..."
      docker network create "${NETWORK_NAME}"
    else
      echo "[1/4] Docker network ${NETWORK_NAME} đã sẵn sàng."
    fi

    # 2. Khởi chạy MariaDB
    if ! docker ps --format '{{.Names}}' | grep -q "^test-mariadb$"; then
      docker rm -f test-mariadb >/dev/null 2>&1 || true
      echo "[2/4] Đang khởi chạy MariaDB 10.11..."
      docker run -d --name test-mariadb \
        --network "${NETWORK_NAME}" \
        -p 3306:3306 \
        -e MYSQL_ROOT_PASSWORD=rootpassword \
        -e MYSQL_DATABASE=test \
        -e MYSQL_USER=notebook_api \
        -e MYSQL_PASSWORD=devpassword \
        mariadb:10.11 \
        --character-set-server=utf8mb4 \
        --collation-server=utf8mb4_unicode_ci \
        --max-allowed-packet=64M >/dev/null
      echo "      Đợi MariaDB sẵn sàng..."
      until docker exec test-mariadb mariadb -u notebook_api -pdevpassword -e "SELECT 1" test >/dev/null 2>&1; do
        sleep 1
      done
      echo "      MariaDB đã sẵn sàng!"
    else
      echo "[2/4] MariaDB (test-mariadb) đang chạy."
    fi

    # 3. Khởi chạy Mock Kyuubi (WireMock)
    if ! docker ps --format '{{.Names}}' | grep -q "^test-kyuubi-mock$"; then
      docker rm -f test-kyuubi-mock >/dev/null 2>&1 || true
      echo "[3/4] Đang khởi chạy Mock Kyuubi (WireMock)..."
      docker run -d --name test-kyuubi-mock \
        --network "${NETWORK_NAME}" \
        -v "${ROOT_DIR}/docker/local-test/wiremock-mappings":/home/wiremock/mappings:ro \
        wiremock/wiremock:3.9.1 >/dev/null
    else
      echo "[3/4] Mock Kyuubi (test-kyuubi-mock) đang chạy."
    fi

    # 4. Khởi chạy Notebook App Container
    echo "[4/4] Đang khởi chạy Notebook container (${IMAGE})..."
    docker rm -f test-notebook-app >/dev/null 2>&1 || true
    docker run -d --name test-notebook-app \
      --network "${NETWORK_NAME}" \
      -p 8080:8080 \
      --user 10001:10001 \
      --read-only \
      --tmpfs /tmp \
      -v "${ROOT_DIR}/docker/local-test/application.yaml":/etc/notebook/application.yaml:ro \
      -e NOTEBOOK_CONFIG=/etc/notebook/application.yaml \
      -e SPRING_DATASOURCE_PASSWORD=devpassword \
      "${IMAGE}" >/dev/null

    echo ""
    echo "================================================================="
    echo " MÔI TRƯỜNG ĐÃ SẴN SÀNG!"
    echo "================================================================="
    echo " Trình duyệt: http://localhost:8080"
    echo " Đăng nhập:   Nhập bất kỳ tài khoản/mật khẩu nào (vd: admin / 123456)"
    echo " Chế độ:      Bỏ qua bảo mật (no-oidc + mock Kyuubi engine)"
    echo " Xem logs:    $0 logs"
    echo " Dừng test:   $0 stop"
    echo "================================================================="
    ;;

  stop)
    echo "Đang dừng và dọn dẹp các container test..."
    docker rm -f test-notebook-app test-kyuubi-mock test-mariadb >/dev/null 2>&1 || true
    echo "Đã dừng và xoá: test-notebook-app, test-kyuubi-mock, test-mariadb."
    ;;

  status)
    docker ps --filter "name=test-notebook-app" --filter "name=test-kyuubi-mock" --filter "name=test-mariadb"
    ;;

  logs)
    docker logs -f test-notebook-app
    ;;

  *)
    echo "Cách dùng: $0 [start|stop|status|logs] [image:tag]"
    echo "  vd khởi chạy mặc định: $0 start"
    echo "  vd khởi chạy image khác: $0 start j3rrytran/notebook:0.1.1"
    echo "  vd dừng môi trường:    $0 stop"
    exit 1
    ;;
esac
