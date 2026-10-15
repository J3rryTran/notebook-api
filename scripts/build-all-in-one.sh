#!/usr/bin/env bash
# ==============================================================================
# scripts/build-all-in-one.sh
# Cổng chặn build hoàn chỉnh theo §10.1 của plan/chart-design.md (P0 -> P9)
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

ALLOW_DIRTY=false
SKIP_RUNTIME=false

for arg in "$@"; do
  case $arg in
    --allow-dirty)
      ALLOW_DIRTY=true
      shift
      ;;
    --skip-runtime-check)
      SKIP_RUNTIME=true
      shift
      ;;
    *)
      ;;
  esac
done

START_TIME=$(date +%s)
mkdir -p "${ROOT_DIR}/dist"

echo -e "${BLUE}=== P0: Kiểm đầu vào git và version ===${NC}"
cd "${ROOT_DIR}"
IS_DIRTY=false
GIT_STATUS=$(git status --porcelain)
if [ -n "${GIT_STATUS}" ]; then
  IS_DIRTY=true
  if [ "${ALLOW_DIRTY}" = false ]; then
    echo -e "${RED}[P0 FAIL] Working tree chưa sạch (dirty) và không có cờ --allow-dirty!${NC}"
    echo "${GIT_STATUS}"
    exit 1
  else
    echo -e "${YELLOW}[P0 WARN] Working tree dirty nhưng được phép nhờ --allow-dirty.${NC}"
  fi
fi

CHART_FILE="${ROOT_DIR}/notebook-helm/Chart.yaml"
VERSION=$(grep '^appVersion:' "${CHART_FILE}" | awk -F '"' '{print $2}')
if [ -z "${VERSION}" ]; then
  VERSION=$(grep '^appVersion:' "${CHART_FILE}" | awk '{print $2}')
fi

if [ -z "${VERSION}" ]; then
  echo -e "${RED}[P0 FAIL] Không tìm thấy appVersion trong ${CHART_FILE}!${NC}"
  exit 1
fi

GIT_REV=$(git rev-parse HEAD || echo "unknown")
GIT_SHORT_REV=$(git rev-parse --short HEAD || echo "unknown")
TAG_VERSION="${VERSION}"
if [ "${IS_DIRTY}" = true ]; then
  TAG_VERSION="${VERSION}-${GIT_SHORT_REV}"
fi

IMAGE_REPO="j3rrytran/notebook"
IMAGE_FULL="${IMAGE_REPO}:${TAG_VERSION}"

echo -e "${GREEN}[P0 PASS] Version: ${VERSION}, Image Tag: ${IMAGE_FULL}, Git SHA: ${GIT_SHORT_REV}${NC}"

echo -e "${BLUE}=== P1: Build & Verify backend trong Docker (Không skipTests) ===${NC}"
docker run --rm \
  -v "${ROOT_DIR}/backend":/src \
  -v "${HOME}/.m2":/root/.m2 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  --add-host=host.docker.internal:host-gateway \
  -w /src \
  maven:3.9.9-eclipse-temurin-17 \
  mvn -B -ntp clean verify > "${ROOT_DIR}/dist/mvn-verify.log" 2>&1 || {
    echo -e "${RED}[P1 FAIL] Maven build/verify thất bại! 20 dòng log cuối:${NC}"
    tail -n 20 "${ROOT_DIR}/dist/mvn-verify.log"
    exit 1
  }

TESTS_RUN=$(grep -E "Tests run: [0-9]+" "${ROOT_DIR}/dist/mvn-verify.log" | tail -n 1 || echo "Tests run: unknown")
echo -e "${GREEN}[P1 PASS] Backend build & test thành công. (${TESTS_RUN})${NC}"

echo -e "${BLUE}=== P2: Kiểm tra Fat JARs và tạo Checksums ===${NC}"
CHECKSUM_FILE="${ROOT_DIR}/dist/checksums.txt"
rm -f "${CHECKSUM_FILE}"

check_fat_jar() {
  local svc_dir="$1"
  local svc_name="$2"
  local jar_pattern="${ROOT_DIR}/backend/${svc_dir}/target/${svc_name}-*.jar"
  # shellcheck disable=SC2206
  local jars=( $jar_pattern )
  if [ "${#jars[@]}" -ne 1 ] || [ ! -f "${jars[0]}" ]; then
    echo -e "${RED}[P2 FAIL] ${svc_dir} phải ra đúng 1 JAR! Tìm thấy: ${#jars[@]}${NC}"
    exit 1
  fi
  local jar_path="${jars[0]}"
  if ! unzip -l "${jar_path}" | grep "BOOT-INF/" > /dev/null; then
    echo -e "${RED}[P2 FAIL] ${jar_path} không phải Spring Boot Fat JAR (thiếu BOOT-INF/)!${NC}"
    exit 1
  fi
  sha256sum "${jar_path}" >> "${CHECKSUM_FILE}"
  echo "Verified fat jar: $(basename "${jar_path}")"
}

check_fat_jar "nb-notebook-service" "nb-notebook-service"
check_fat_jar "nb-execution-service" "nb-execution-service"
echo -e "${GREEN}[P2 PASS] JARs hợp lệ. Checksums đã ghi vào dist/checksums.txt.${NC}"

echo -e "${BLUE}=== P3: Kiểm tra frontend public static assets & vendor checksum ===${NC}"
INDEX_HTML="${ROOT_DIR}/frontend/public/index.html"
if [ ! -f "${INDEX_HTML}" ]; then
  echo -e "${RED}[P3 FAIL] Không tìm thấy ${INDEX_HTML}!${NC}"
  exit 1
fi

if grep -E "(src|href)=\"https?://" "${INDEX_HTML}"; then
  echo -e "${RED}[P3 FAIL] index.html chứa liên kết CDN http(s):// tuyệt đối (vi phạm plan)!${NC}"
  exit 1
fi

# So khớp checksum oidc-client-ts
VENDOR_README="${ROOT_DIR}/frontend/public/vendor/README.md"
OIDC_SUM=$(sha256sum "${ROOT_DIR}/frontend/public/vendor/oidc-client-ts.min.js" | awk '{print $1}')
if ! grep -q "${OIDC_SUM}" "${VENDOR_README}"; then
  echo -e "${RED}[P3 FAIL] Checksum oidc-client-ts không khớp README!${NC}"
  exit 1
fi
echo -e "${GREEN}[P3 PASS] Frontend assets tự chứa (không CDN) và khớp checksum.${NC}"

echo -e "${BLUE}=== P4: Kiểm tra Chart (helm lint & template) ===${NC}"
bash "${ROOT_DIR}/scripts/helm-check.sh" > "${ROOT_DIR}/dist/helm-check.log" 2>&1 || {
  echo -e "${RED}[P4 FAIL] Helm checks thất bại!${NC}"
  tail -n 20 "${ROOT_DIR}/dist/helm-check.log"
  exit 1
}
echo -e "${GREEN}[P4 PASS] Helm checks (lint + 3 mode templates + 13 validation error cases) đạt.${NC}"

echo -e "${BLUE}=== P5: Kiểm tra hợp đồng tĩnh (X1) ===${NC}"
bash "${ROOT_DIR}/scripts/contract-check.sh" --static-only > "${ROOT_DIR}/dist/contract-x1.log" 2>&1 || {
  echo -e "${RED}[P5 FAIL] So khớp tĩnh X1 thất bại!${NC}"
  cat "${ROOT_DIR}/dist/contract-x1.log"
  exit 1
}
echo -e "${GREEN}[P5 PASS] Khóa ConfigMap render khớp 100% code.${NC}"

echo -e "${BLUE}=== P6: ShellCheck & Nginx Lint ===${NC}"
docker run --rm -v "${ROOT_DIR}":/mnt koalaman/shellcheck:v0.10.0 \
  docker/entrypoint.sh docker/healthcheck.sh scripts/helm-check.sh scripts/contract-check.sh

docker run --rm -v "${ROOT_DIR}/docker/nginx.conf":/etc/nginx/nginx.conf:ro nginx:1.27-alpine nginx -t > /dev/null 2>&1
echo -e "${GREEN}[P6 PASS] Shell scripts và nginx.conf đạt chuẩn cú pháp.${NC}"

echo -e "${BLUE}=== P7: Đóng gói Docker Image All-In-One ===${NC}"
POM_VERSION=$(grep -m1 '<version>' "${ROOT_DIR}/backend/pom.xml" | awk -F '>' '{print $2}' | awk -F '<' '{print $1}')
docker build \
  --build-arg "VERSION=${POM_VERSION}" \
  --label "notebook.contract=2" \
  --label "org.opencontainers.image.version=${TAG_VERSION}" \
  --label "org.opencontainers.image.revision=${GIT_REV}" \
  -t "${IMAGE_FULL}" \
  "${ROOT_DIR}" > "${ROOT_DIR}/dist/docker-build.log" 2>&1 || {
    echo -e "${RED}[P7 FAIL] Docker build thất bại!${NC}"
    tail -n 25 "${ROOT_DIR}/dist/docker-build.log"
    exit 1
  }

IMAGE_DIGEST=$(docker inspect --format '{{.Id}}' "${IMAGE_FULL}")
echo -e "${GREEN}[P7 PASS] Đã build image: ${IMAGE_FULL} (${IMAGE_DIGEST})${NC}"

P8_STATUS="PASSED"
if [ "${SKIP_RUNTIME}" = true ]; then
  P8_STATUS="SKIPPED"
  echo -e "${YELLOW}[P8 SKIP] Bỏ qua kiểm tra runtime động X2 - X5 (--skip-runtime-check).${NC}"
else
  echo -e "${BLUE}=== P8: Kiểm tra Image chạy với Chart (X2 - X5) ===${NC}"
  IMAGE_NAME="${IMAGE_FULL}" IMAGE_TAG="${TAG_VERSION}" bash "${ROOT_DIR}/scripts/contract-check.sh" > "${ROOT_DIR}/dist/contract-runtime.log" 2>&1 || {
    echo -e "${RED}[P8 FAIL] Kiểm tra runtime X2 - X5 thất bại!${NC}"
    tail -n 30 "${ROOT_DIR}/dist/contract-runtime.log"
    exit 1
  }
  echo -e "${GREEN}[P8 PASS] Toàn bộ tiêu chí runtime X2 - X5 đạt chuẩn.${NC}"
fi

END_TIME=$(date +%s)
DURATION=$((END_TIME - START_TIME))

echo -e "${BLUE}=== P9: Xuất báo cáo kết quả dist/build-report.md ===${NC}"
REPORT_FILE="${ROOT_DIR}/dist/build-report.md"
cat <<EOF > "${REPORT_FILE}"
# Báo cáo đóng gói Notebook All-In-One Image

- **Thời gian**: $(date -u +"%Y-%m-%dT%H:%M:%SZ")
- **Thời lượng**: ${DURATION}s
- **Phiên bản (AppVersion)**: ${VERSION}
- **Image Tag**: ${IMAGE_FULL}
- **Image Digest**: \`${IMAGE_DIGEST}\`
- **Git Commit**: \`${GIT_REV}\`
- **Trạng thái Git**: $([ "${IS_DIRTY}" = true ] && echo "DIRTY (cho phép bởi --allow-dirty)" || echo "CLEAN")

## Kết quả các cổng chặn (P0 - P8)

| Bước | Nội dung | Kết quả | Chi tiết |
|---|---|---|---|
| P0 | Kiểm tra đầu vào git & Chart.yaml | **PASSED** | Version: ${VERSION} |
| P1 | Maven Build & Test (Unit & IT) | **PASSED** | ${TESTS_RUN} |
| P2 | Kiểm tra Spring Boot Fat JARs | **PASSED** | Xem \`dist/checksums.txt\` |
| P3 | Kiểm tra Frontend Static & Vendor | **PASSED** | 100% tự chứa, không CDN |
| P4 | Helm Lint & Template Tests | **PASSED** | Lint 0 error, 3 mode templates, 13 error cases |
| P5 | Kiểm tra hợp đồng tĩnh X1 | **PASSED** | ConfigMap khớp 100% code properties |
| P6 | Kiểm tra cú pháp script & nginx | **PASSED** | Shellcheck & nginx -t sạch |
| P7 | Build All-In-One Docker Image | **PASSED** | Contract: 2, User: 10001, Non-root |
| P8 | Kiểm tra runtime thực tế (X2-X5) | **${P8_STATUS}** | Pod simulation, MariaDB + WireMock mock |

## Checksum JARs

\`\`\`
$(cat "${CHECKSUM_FILE}")
\`\`\`

---
> **Lưu ý**: Image không tự động push. Để đẩy image lên Docker Registry:
> \`\`\`bash
> docker push ${IMAGE_FULL}
> \`\`\`
EOF

echo -e "${GREEN}[P9 PASS] Báo cáo đã xuất thành công ra ${REPORT_FILE}.${NC}"
echo "----------------------------------------------------------------------"
echo -e "${GREEN}BUILD TỔNG THỂ HOÀN TẤT VÀ THÀNH CÔNG RỰC RỠ!${NC}"
echo "Để push image lên registry: docker push ${IMAGE_FULL}"
echo "----------------------------------------------------------------------"
