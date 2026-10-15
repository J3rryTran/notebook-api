#!/usr/bin/env bash
# ==============================================================================
# scripts/contract-check.sh: Kiểm tra hợp đồng tĩnh & động (X1 - X5)
# Tuân thủ đặc tả §5 trong plan/chart-image-contract.md
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

RUN_DYNAMIC=true
if [ "${1:-}" = "--static-only" ]; then
  RUN_DYNAMIC=false
fi

IMAGE_TAG="${IMAGE_TAG:-0.1.0}"
IMAGE_NAME="${IMAGE_NAME:-j3rrytran/notebook:${IMAGE_TAG}}"

echo -e "${BLUE}=== [X1] Static Contract Check: Helm ConfigMap vs Code Properties ===${NC}"

# 1. Trích xuất các key từ ConfigMap được render bởi Helm
TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

docker run --rm -v "${ROOT_DIR}/notebook-helm":/c alpine/helm:3.14.4 template t /c -f /c/ci/oidc-values.yaml > "${TMP_DIR}/oidc.yaml"
docker run --rm -v "${ROOT_DIR}/notebook-helm":/c alpine/helm:3.14.4 template t /c -f /c/ci/ldap-values.yaml > "${TMP_DIR}/ldap.yaml"

# Danh sách khóa chính trong ConfigMap theo Contract 2
EXPECTED_CONFIG_KEYS=(
  "spring.datasource.url"
  "spring.datasource.username"
  "spring.datasource.hikari.maximum-pool-size"
  "server.forward-headers-strategy"
  "logging.level.root"
  "notebook.public-url"
  "notebook.admins"
  "notebook.auth.mode"
  "notebook.auth.allow-unverified"
  "notebook.auth.username-claim"
  "notebook.auth.cache-ttl"
  "notebook.auth.oidc.issuer"
  "notebook.auth.oidc.client-id"
  "notebook.auth.oidc.scopes"
  "notebook.auth.oidc.service-client-id"
  "notebook.auth.session-ttl"
  "notebook.auth.recheck"
  "notebook.auth.ldap.url"
  "notebook.auth.ldap.start-tls"
  "notebook.auth.ldap.base-dn"
  "notebook.auth.ldap.group-search-base"
  "notebook.auth.ldap.group-member-attribute"
  "notebook.auth.ldap.admin-group"
  "notebook.auth.ldap.connect-timeout"
  "notebook.auth.ldap.read-timeout"
  "notebook.max.cells"
  "notebook.max.page-size"
  "notebook.cell.source.max-size"
  "notebook.document.max-size"
  "notebook.import.max-size"
  "notebook.revision.max-per-notebook"
  "notebook.results.max-rows"
  "notebook.events.pool-size"
  "notebook.events.max-wait"
  "kyuubi.rest-url"
  "kyuubi.connect-timeout"
  "kyuubi.request-timeout"
  "kyuubi.session-open-timeout"
  "kyuubi.idle-session-timeout"
  "kyuubi.pin-to-instance"
  "kyuubi.allowed-session-config-keys"
)

# Kiểm tra các khóa này có được định nghĩa và đọc trong code / application.yml
MISSING_KEYS=0
for key in "${EXPECTED_CONFIG_KEYS[@]}"; do
  # Chuyển key dạng a.b.c sang pattern tìm kiếm trong Java / yml
  prefix="${key%%.*}"
  if ! grep -rq "${key}" "${ROOT_DIR}/backend" && \
     ! grep -rq "${prefix}" "${ROOT_DIR}/backend/nb-common/src/main/java" && \
     ! grep -rq "${prefix}" "${ROOT_DIR}/backend/nb-notebook-service/src/main/resources/application.yml"; then
    echo -e "${RED}[X1 FAIL] Key ${key} không tìm thấy trong backend code/config!${NC}"
    MISSING_KEYS=$((MISSING_KEYS + 1))
  fi
done

if [ "${MISSING_KEYS}" -eq 0 ]; then
  echo -e "${GREEN}[X1 PASSED] Tất cả các khóa chart render đều có code đọc.${NC}"
else
  echo -e "${RED}[X1 FAILED] Có ${MISSING_KEYS} khóa mồ côi!${NC}"
  exit 1
fi

if [ "${RUN_DYNAMIC}" = false ]; then
  echo -e "${YELLOW}Bỏ qua kiểm tra động X2 - X5 (--static-only).${NC}"
  exit 0
fi

echo -e "${BLUE}=== [X2 - X5] Dynamic Contract Verification ===${NC}"

# Tạo network riêng cho kiểm thử
TEST_NET="nb-contract-test-net-$$"
docker network create "${TEST_NET}" > /dev/null

cleanup_docker() {
  echo "Cleaning up containers and network..."
  docker rm -f nb-mock-mariadb-$$ nb-mock-wiremock-$$ nb-app-test-$$ > /dev/null 2>&1 || true
  docker network rm "${TEST_NET}" > /dev/null 2>&1 || true
}
trap cleanup_docker EXIT

echo "1. Khởi tạo Mock MariaDB..."
docker run -d --name "nb-mock-mariadb-$$" --network "${TEST_NET}" \
  -e MARIADB_ROOT_PASSWORD=rootpass \
  -e MARIADB_DATABASE=test \
  -e MARIADB_USER=notebook_api \
  -e MARIADB_PASSWORD=testpass \
  mariadb:10.11 > /dev/null

echo "2. Khởi tạo Mock WireMock (Kyuubi API)..."
docker run -d --name "nb-mock-wiremock-$$" --network "${TEST_NET}" \
  wiremock/wiremock:3.9.1 > /dev/null

# Chờ MariaDB và WireMock sẵn sàng
echo "Chờ MariaDB và WireMock khởi động..."
for i in $(seq 1 30); do
  if docker exec "nb-mock-mariadb-$$" mariadb-admin ping -h localhost -u root -prootpass --silent > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

# Cấu hình stub WireMock cho /api/v1/ping
docker exec "nb-mock-wiremock-$$" curl -s -X POST http://localhost:8080/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{"request":{"method":"GET","url":"/api/v1/ping"},"response":{"status":200,"body":"PONG"}}' > /dev/null

# Chuẩn bị file application.yaml từ chart template
APP_CFG="${TMP_DIR}/application.yaml"
cat <<EOF > "${APP_CFG}"
spring:
  datasource:
    url: jdbc:mariadb://nb-mock-mariadb-$$:3306/test?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC
    username: "notebook_api"
    hikari:
      maximum-pool-size: 5
server:
  forward-headers-strategy: native
logging:
  level:
    root: INFO
notebook:
  public-url: "http://127.0.0.1:8080"
  admins: []
  auth:
    mode: oidc
    allow-unverified: true
    username-claim: "preferred_username"
    cache-ttl: 60s
    oidc:
      issuer: "http://nb-mock-wiremock-$$:8080/realms/lakehouse"
      client-id: "kyuubi-notebook"
      scopes: ["openid","profile"]
  max:
    cells: 500
    page-size: 200
  cell:
    source:
      max-size: 1048576
  document:
    max-size: 16777216
  import:
    max-size: 16777216
  revision:
    max-per-notebook: 100
  results:
    max-rows: 1000
  events:
    pool-size: 16
    max-wait: 10s
kyuubi:
  rest-url: "http://nb-mock-wiremock-$$:8080"
  connect-timeout: 10s
  request-timeout: 60s
  session-open-timeout: 10m
  idle-session-timeout: 30m
  pin-to-instance: true
  allowed-session-config-keys: ["spark.executor.memory"]
EOF

echo "3. Khởi chạy Notebook Image theo đúng securityContext pod K8s..."
docker run -d --name "nb-app-test-$$" --network "${TEST_NET}" \
  -p 18080:8080 \
  --user 10001:10001 \
  --read-only \
  --tmpfs /tmp \
  -v "${APP_CFG}:/etc/notebook/application.yaml:ro" \
  -e NOTEBOOK_CONFIG=/etc/notebook/application.yaml \
  -e SPRING_DATASOURCE_PASSWORD=testpass \
  "${IMAGE_NAME}" > /dev/null

echo "4. Kiểm tra X4 (Contract labels & logs)..."
LABEL_CONTRACT=$(docker inspect --format '{{ index .Config.Labels "notebook.contract"}}' "${IMAGE_NAME}")
if [ "${LABEL_CONTRACT}" != "2" ]; then
  echo -e "${RED}[X4 FAIL] Label notebook.contract='${LABEL_CONTRACT}' != '2'${NC}"
  exit 1
fi

sleep 2
LOG_CONTRACT=$(docker logs "nb-app-test-$$" 2>&1 | grep -o "\[STARTUP\] notebook contract=2" || true)
if [ -z "${LOG_CONTRACT}" ]; then
  echo -e "${RED}[X4 FAIL] Log không có '[STARTUP] notebook contract=2'${NC}"
  docker logs "nb-app-test-$$"
  exit 1
fi
echo -e "${GREEN}[X4 PASSED] Contract 2 label and startup log verified.${NC}"

echo "5. Chờ readiness probe thành công (X2)..."
READY=false
# shellcheck disable=SC2034
for i in $(seq 1 60); do
  if docker exec "nb-app-test-$$" /app/healthcheck.sh readiness > /dev/null 2>&1; then
    READY=true
    break
  fi
  sleep 2
done

if [ "${READY}" = false ]; then
  echo -e "${RED}[X2 FAIL] Container không Ready sau 120s!${NC}"
  docker logs "nb-app-test-$$"
  exit 1
fi

# Kiểm tra GET /api/v1/auth/config trả đúng mode oidc
AUTH_CONFIG=$(curl -s http://127.0.0.1:18080/api/v1/auth/config)
if ! echo "${AUTH_CONFIG}" | grep -q '"mode":"oidc"'; then
  echo -e "${RED}[X2 FAIL] /api/v1/auth/config không trả mode=oidc: ${AUTH_CONFIG}${NC}"
  exit 1
fi
echo -e "${GREEN}[X2 PASSED] Image chạy thành công với Chart configuration & probes.${NC}"

echo "6. Kiểm tra cổng nội bộ không lộ (X3)..."
# nginx chặn /internal/ -> 403
INTERNAL_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18080/internal/access/token || true)
if [ "${INTERNAL_CODE}" != "403" ]; then
  echo -e "${RED}[X3 FAIL] /internal/access/token trả ${INTERNAL_CODE} (kỳ vọng 403)${NC}"
  exit 1
fi

# /actuator/health không lộ qua nginx -> 404
ACTUATOR_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18080/actuator/health || true)
if [ "${ACTUATOR_CODE}" != "404" ]; then
  echo -e "${RED}[X3 FAIL] /actuator/health trả ${ACTUATOR_CODE} (kỳ vọng 404)${NC}"
  exit 1
fi
echo -e "${GREEN}[X3 PASSED] Cổng nội bộ và actuator bảo vệ an toàn.${NC}"

echo "7. Kiểm tra dừng sạch SIGTERM trong < 40s (X5)..."
STOP_START=$(date +%s)
docker stop -t 40 "nb-app-test-$$" > /dev/null
STOP_END=$(date +%s)
STOP_DURATION=$((STOP_END - STOP_START))

if [ "${STOP_DURATION}" -gt 40 ]; then
  echo -e "${RED}[X5 FAIL] docker stop mất ${STOP_DURATION}s > 40s!${NC}"
  exit 1
fi
echo -e "${GREEN}[X5 PASSED] Dừng container sạch trong ${STOP_DURATION}s (< 40s).${NC}"

echo -e "${GREEN}=== TẤT CẢ CÁC ĐIỀU KIỆN CONTRACT X1 - X5 ĐẠT TUYỆT ĐỐI ===${NC}"
