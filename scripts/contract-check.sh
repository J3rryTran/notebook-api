#!/usr/bin/env bash
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

IMAGE_NAME="${IMAGE_NAME:-}"
if [ "${RUN_DYNAMIC}" = true ] && [ -z "${IMAGE_NAME}" ]; then
  echo "IMAGE_NAME chưa đặt (vd IMAGE_NAME=docker.io/j3rrytran/notebook:0.1.1)" >&2
  exit 2
fi

echo -e "${BLUE}=== [X1] Static Contract Check: Helm ConfigMap vs Code Properties ===${NC}"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

docker run --rm -v "${ROOT_DIR}/notebook-helm":/c alpine/helm:3.14.4 template t /c --set auth.mode=oidc --set auth.oidc.serviceAccount.enabled=true > "${TMP_DIR}/oidc.yaml"
LDAP_SETS=(--set auth.mode=ldap --set auth.ldap.url=ldaps://ldap.example.invalid:636 --set auth.ldap.startTls=false
           --set auth.ldap.baseDn=dc=example --set auth.ldap.userSearchBase=ou=people
           --set auth.ldap.userDnPattern= --set 'auth.ldap.userSearchFilter=(uid={0})' --set auth.ldap.usernameAttribute=uid
           --set 'auth.ldap.managerDn=cn=svc\,dc=example\,dc=invalid' --set auth.ldap.groupSearchBase=ou=groups
           --set auth.ldap.groupMemberAttribute=member --set auth.ldap.adminGroup=
           --set auth.ldap.connectTimeout=5s --set auth.ldap.readTimeout=10s
           --set auth.ldap.trustStore.enabled=false --set auth.ldap.trustStore.type=PKCS12)
docker run --rm -v "${ROOT_DIR}/notebook-helm":/c alpine/helm:3.14.4 template t /c \
  "${LDAP_SETS[@]}" --set auth.ldap.trustStore.enabled=true > "${TMP_DIR}/ldap.yaml"

EXPECTED_CONFIG_KEYS=(
  "spring.datasource.url"
  "spring.datasource.username"
  "spring.datasource.hikari.maximum-pool-size"
  "server.forward-headers-strategy"
  "logging.level.root"
  "notebook.admins"
  "notebook.auth.mode"
  "notebook.auth.allow-unverified"
  "notebook.auth.username-claim"
  "notebook.auth.cache-ttl"
  "notebook.auth.oidc.issuer"
  "notebook.auth.oidc.client-id"
  "notebook.auth.oidc.redirect-path"
  "notebook.auth.oidc.scopes"
  "notebook.auth.oidc.service-client-id"
  "notebook.auth.session-ttl"
  "notebook.auth.recheck"
  "notebook.auth.ldap.url"
  "notebook.auth.ldap.start-tls"
  "notebook.auth.ldap.base-dn"
  "notebook.auth.ldap.username-attribute"
  "notebook.auth.ldap.group-search-base"
  "notebook.auth.ldap.group-member-attribute"
  "notebook.auth.ldap.group-object-class"
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
  "kyuubi.idle-session-timeout"
  "kyuubi.idle-reaper-interval"
  "kyuubi.pin-to-instance"
  "kyuubi.allowed-session-config-keys"
)

PROPS_SRC="${ROOT_DIR}/backend/nb-common/src/main/java/com/vtnexus/notebook/common"
MISSING_KEYS=0
for key in "${EXPECTED_CONFIG_KEYS[@]}"; do
  leaf="${key##*.}"
  if ! grep -qE "^[[:space:]]+${leaf}:" "${TMP_DIR}/oidc.yaml" "${TMP_DIR}/ldap.yaml"; then
    echo -e "${RED}[X1 FAIL] Key ${key} không có trong ConfigMap render từ chart${NC}"
    MISSING_KEYS=$((MISSING_KEYS + 1))
    continue
  fi
  case "${key}" in
    spring.*|server.*|logging.*) continue ;;
  esac
  camel=$(echo "${leaf}" | sed -E 's/-([a-z])/\U\1/g')
  if ! grep -rqE "private [A-Za-z<>,. ]+ ${camel}( |;|=)" "${PROPS_SRC}/config" "${PROPS_SRC}/auth/AuthProperties.java"; then
    echo -e "${RED}[X1 FAIL] Key ${key}: không có field '${camel}' trong @ConfigurationProperties${NC}"
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

TEST_NET="nb-contract-test-net-$$"
docker network create "${TEST_NET}" > /dev/null

cleanup_docker() {
  echo "Cleaning up containers and network..."
  docker rm -f nb-mock-mariadb-$$ nb-mock-wiremock-$$ nb-app-test-$$ > /dev/null 2>&1 || true
  docker network rm "${TEST_NET}" > /dev/null 2>&1 || true
  rm -rf "${TMP_DIR}"
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

echo "Chờ MariaDB và WireMock khởi động..."
for i in $(seq 1 30); do
  if docker exec "nb-mock-mariadb-$$" mariadb-admin ping -h localhost -u root -prootpass --silent > /dev/null 2>&1; then
    break
  fi
  sleep 1
done

WIREMOCK_READY=false
for i in $(seq 1 60); do
  if docker exec "nb-mock-wiremock-$$" curl -sf http://localhost:8080/__admin/mappings > /dev/null 2>&1; then
    WIREMOCK_READY=true
    break
  fi
  sleep 1
done
if [ "${WIREMOCK_READY}" != true ]; then
  echo -e "${RED}[X2 FAIL] WireMock không sẵn sàng sau 60s${NC}"
  exit 1
fi

docker exec "nb-mock-wiremock-$$" curl -s -X POST http://localhost:8080/__admin/mappings \
  -H "Content-Type: application/json" \
  -d '{"request":{"method":"GET","url":"/api/v1/ping"},"response":{"status":200,"body":"PONG"}}' > /dev/null

APP_CFG="${TMP_DIR}/application.yaml"
docker run --rm -v "${ROOT_DIR}/notebook-helm":/c alpine/helm:3.14.4 template t /c \
  --set auth.mode=oidc \
  --set mariadb.host="nb-mock-mariadb-$$" \
  --set mariadb.username=notebook_api \
  --set kyuubi.restUrl="http://nb-mock-wiremock-$$:8080" \
  --set auth.oidc.issuer="http://nb-mock-wiremock-$$:8080/realms/lakehouse" \
  --set auth.allowUnverified=true \
  --show-only templates/configmap.yaml > "${TMP_DIR}/configmap.yaml"
awk '/^  application.yaml: \|/{f=1; next} f && /^    /{sub(/^    /, ""); print; next} f && /^[[:space:]]*$/{print; next} f{f=0}' \
  "${TMP_DIR}/configmap.yaml" > "${APP_CFG}"
if ! grep -q "nb-mock-mariadb-$$" "${APP_CFG}"; then
  echo -e "${RED}[X2 FAIL] Không trích được application.yaml từ ConfigMap render${NC}"
  cat "${TMP_DIR}/configmap.yaml"
  exit 1
fi
chmod 0644 "${APP_CFG}"

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

AUTH_CONFIG=$(curl -s http://127.0.0.1:18080/api/v1/auth/config)
if ! echo "${AUTH_CONFIG}" | grep -q '"mode":"oidc"'; then
  echo -e "${RED}[X2 FAIL] /api/v1/auth/config không trả mode=oidc: ${AUTH_CONFIG}${NC}"
  exit 1
fi
echo -e "${GREEN}[X2 PASSED] Image chạy thành công với Chart configuration & probes.${NC}"

echo "6. Kiểm tra cổng nội bộ không lộ (X3)..."
INTERNAL_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:18080/internal/access/token || true)
if [ "${INTERNAL_CODE}" != "403" ]; then
  echo -e "${RED}[X3 FAIL] /internal/access/token trả ${INTERNAL_CODE} (kỳ vọng 403)${NC}"
  exit 1
fi

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
