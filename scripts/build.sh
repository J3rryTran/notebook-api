#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
CONTEXT_DIR="${DIST_DIR}/image-context"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

fail() { echo -e "${RED}[$1 FAIL] $2${NC}"; exit 1; }
pass() { echo -e "${GREEN}[$1 PASS] $2${NC}"; }
step() { echo -e "${BLUE}=== $1 ===${NC}"; }

START_TIME=$(date +%s)
rm -rf "${CONTEXT_DIR}"
mkdir -p "${DIST_DIR}"
cd "${ROOT_DIR}"

step "B1: Maven build & test (unit + IT MariaDB)"
DOCKER_API_VERSION="$(docker version --format '{{.Server.APIVersion}}' 2>/dev/null || true)"
[ -n "${DOCKER_API_VERSION}" ] || fail B1 "Không đọc được phiên bản API của Docker daemon (docker version). Docker có đang chạy?"
echo "Docker API version: ${DOCKER_API_VERSION}"

docker run --rm \
  -v "${ROOT_DIR}/backend":/src \
  -v "${HOME}/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e DOCKER_API_VERSION="${DOCKER_API_VERSION}" \
  --add-host=host.docker.internal:host-gateway \
  -w /src \
  maven:3.9.9-eclipse-temurin-17 \
  mvn -B -ntp clean verify -Dapi.version="${DOCKER_API_VERSION}" > "${DIST_DIR}/mvn-verify.log" 2>&1 || {
    tail -n 25 "${DIST_DIR}/mvn-verify.log"
    fail B1 "Maven build/test thất bại (log đầy đủ: dist/mvn-verify.log; chi tiết test: backend/*/target/{surefire,failsafe}-reports)"
  }
TESTS_RUN=$(grep -E "Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$" "${DIST_DIR}/mvn-verify.log" \
  | sed -E 's/.*Tests run: ([0-9]+).*/\1/' | awk '{s+=$1} END {print s+0}')
pass B1 "${TESTS_RUN} test (unit + IT) xanh"

step "B2: Kiểm jar"
find_jar() {
  local svc="$1" jars
  mapfile -t jars < <(find "${ROOT_DIR}/backend/${svc}/target" -maxdepth 1 -name "${svc}-*.jar" ! -name "*-plain.jar" | sort)
  [ "${#jars[@]}" -eq 1 ] || fail B2 "${svc}: cần đúng 1 jar trong target/, tìm thấy ${#jars[@]}"
  unzip -Z1 "${jars[0]}" "BOOT-INF/lib/*" > /dev/null 2>&1 || fail B2 "${jars[0]} không phải Spring Boot fat jar"
  FOUND_JAR="${jars[0]}"
}
find_jar nb-notebook-service;  NB_JAR="${FOUND_JAR}"
find_jar nb-execution-service; EXEC_JAR="${FOUND_JAR}"
pass B2 "$(basename "${NB_JAR}"), $(basename "${EXEC_JAR}")"

step "B3: Kiểm frontend"
INDEX_HTML="${ROOT_DIR}/frontend/public/index.html"
[ -f "${INDEX_HTML}" ] || fail B3 "Không có ${INDEX_HTML}"
if grep -En "(src|href)=\"https?://" "${INDEX_HTML}"; then
  fail B3 "index.html tải tài nguyên từ http(s):// bên ngoài (phải tự host trong vendor/)"
fi
OIDC_SUM=$(sha256sum "${ROOT_DIR}/frontend/public/vendor/oidc-client-ts.min.js" | awk '{print $1}')
grep -q "${OIDC_SUM}" "${ROOT_DIR}/frontend/public/vendor/README.md" || fail B3 "Checksum vendor/oidc-client-ts.min.js không khớp vendor/README.md"
pass B3 "Frontend tự chứa, checksum vendor khớp"

step "B4: Kiểm chart"
bash "${ROOT_DIR}/scripts/helm-check.sh" > "${DIST_DIR}/helm-check.log" 2>&1 || {
  tail -n 20 "${DIST_DIR}/helm-check.log"
  fail B4 "Helm check thất bại (dist/helm-check.log)"
}
bash "${ROOT_DIR}/scripts/contract-check.sh" --static-only > "${DIST_DIR}/contract-static.log" 2>&1 || {
  cat "${DIST_DIR}/contract-static.log"
  fail B4 "Khóa ConfigMap của chart không khớp code (dist/contract-static.log)"
}
pass B4 "helm lint + template values.yaml + ca lỗi; khóa ConfigMap khớp code"

step "B5: Kiểm script & nginx.conf"
docker run --rm -v "${ROOT_DIR}":/mnt -w /mnt koalaman/shellcheck:v0.10.0 \
  docker/entrypoint.sh docker/healthcheck.sh scripts/build.sh scripts/package-image.sh \
  scripts/helm-check.sh scripts/contract-check.sh || fail B5 "ShellCheck báo lỗi"
docker run --rm -v "${ROOT_DIR}/docker/nginx.conf":/etc/nginx/nginx.conf:ro nginx:1.27-alpine nginx -t > /dev/null 2>&1 \
  || fail B5 "nginx -t báo lỗi docker/nginx.conf"
pass B5 "ShellCheck, nginx -t sạch"

step "B6: Gom build context cho image (dist/image-context)"
mkdir -p "${CONTEXT_DIR}/app" "${CONTEXT_DIR}/web" "${CONTEXT_DIR}/conf" "${CONTEXT_DIR}/bin"
cp "${NB_JAR}"   "${CONTEXT_DIR}/app/nb-notebook-service.jar"
cp "${EXEC_JAR}" "${CONTEXT_DIR}/app/nb-execution-service.jar"
cp -r "${ROOT_DIR}/frontend/public/." "${CONTEXT_DIR}/web/"
cp "${ROOT_DIR}/docker/nginx.conf" "${CONTEXT_DIR}/conf/nginx.conf"
cp "${ROOT_DIR}/docker/entrypoint.sh" "${ROOT_DIR}/docker/healthcheck.sh" "${CONTEXT_DIR}/bin/"
cp "${ROOT_DIR}/Dockerfile" "${CONTEXT_DIR}/Dockerfile"

GIT_REV=$(git rev-parse HEAD 2>/dev/null || echo unknown)
GIT_DIRTY=$([ -n "$(git status --porcelain 2>/dev/null)" ] && echo true || echo false)
{
  echo "git_revision=${GIT_REV}"
  echo "git_dirty=${GIT_DIRTY}"
  echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "tests=${TESTS_RUN}"
} > "${CONTEXT_DIR}/BUILD-INFO"
(cd "${CONTEXT_DIR}" && find . -type f -print0 | sort -z | xargs -0 sha256sum > "${DIST_DIR}/SHA256SUMS.tmp")
mv "${DIST_DIR}/SHA256SUMS.tmp" "${CONTEXT_DIR}/SHA256SUMS"

if find "${CONTEXT_DIR}" -name "*.java" -o -name "pom.xml" -o -name "*.sql" | grep -q .; then
  fail B6 "dist/image-context chứa mã nguồn"
fi
pass B6 "$(find "${CONTEXT_DIR}" -type f | wc -l) file, $(du -sh "${CONTEXT_DIR}" | cut -f1)"

echo "----------------------------------------------------------------------"
echo -e "${GREEN}BUILD XONG sau $(( $(date +%s) - START_TIME ))s.${NC} Bước tiếp: đóng gói image, ví dụ"
echo "  ./scripts/package-image.sh docker.io/j3rrytran/notebook:0.1.1"
echo "----------------------------------------------------------------------"
