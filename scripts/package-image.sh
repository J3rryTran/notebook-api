#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
CONTEXT_DIR="${DIST_DIR}/image-context"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

fail() { echo -e "${RED}[$1 FAIL] $2${NC}"; exit 1; }
pass() { echo -e "${GREEN}[$1 PASS] $2${NC}"; }
step() { echo -e "${BLUE}=== $1 ===${NC}"; }

usage() {
  echo "Dùng: $0 <image>:<tag>"
  echo "  vd: $0 docker.io/j3rrytran/notebook:0.1.1"
  exit 2
}

IMAGE=""
for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage ;;
    -*) echo "Tham số không hợp lệ: ${arg}"; usage ;;
    *) [ -z "${IMAGE}" ] || usage; IMAGE="${arg}" ;;
  esac
done
[ -n "${IMAGE}" ] || usage

NAME_PART="${IMAGE##*/}"
case "${NAME_PART}" in
  *:*) ;;
  *) echo -e "${RED}Thiếu tag: '${IMAGE}'. Ghi rõ <image>:<tag>, vd docker.io/j3rrytran/notebook:0.1.1${NC}"; exit 2 ;;
esac
TAG="${NAME_PART##*:}"
REPO="${IMAGE%:"${TAG}"}"
[[ "${TAG}" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]] || fail P0 "Tag '${TAG}' không hợp lệ (chữ, số, '_', '.', '-'; tối đa 128 ký tự)"
[ "${TAG}" != "latest" ] || fail P0 "Không dùng tag 'latest'; đặt phiên bản cụ thể để chart trỏ đúng image"

START_TIME=$(date +%s)

step "P1: Kiểm build context"
[ -f "${CONTEXT_DIR}/SHA256SUMS" ] || fail P1 "Chưa có ${CONTEXT_DIR}. Chạy ./scripts/build.sh trước"
(cd "${CONTEXT_DIR}" && sha256sum --quiet -c SHA256SUMS) || fail P1 "dist/image-context bị sửa sau khi build. Chạy lại ./scripts/build.sh"
GIT_REV=$(grep '^git_revision=' "${CONTEXT_DIR}/BUILD-INFO" | cut -d= -f2)
GIT_DIRTY=$(grep '^git_dirty=' "${CONTEXT_DIR}/BUILD-INFO" | cut -d= -f2)
BUILT_AT=$(grep '^built_at=' "${CONTEXT_DIR}/BUILD-INFO" | cut -d= -f2)
pass P1 "build lúc ${BUILT_AT}, git ${GIT_REV:0:8}$([ "${GIT_DIRTY}" = true ] && echo ' (có thay đổi chưa commit)')"

step "P2: docker build ${IMAGE}"
docker build \
  --label "notebook.contract=2" \
  --label "org.opencontainers.image.title=notebook" \
  --label "org.opencontainers.image.version=${TAG}" \
  --label "org.opencontainers.image.revision=${GIT_REV}" \
  --label "org.opencontainers.image.created=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  -t "${IMAGE}" \
  "${CONTEXT_DIR}" > "${DIST_DIR}/docker-build.log" 2>&1 || {
    tail -n 25 "${DIST_DIR}/docker-build.log"
    fail P2 "docker build thất bại (dist/docker-build.log)"
  }
IMAGE_ID=$(docker inspect --format '{{.Id}}' "${IMAGE}")
pass P2 "${IMAGE} (${IMAGE_ID})"

REPORT="${DIST_DIR}/package-report.md"
cat > "${REPORT}" <<EOF
# Đóng gói image notebook

| | |
|---|---|
| Image | \`${IMAGE}\` |
| Image ID | \`${IMAGE_ID}\` |
| Git | \`${GIT_REV}\`$([ "${GIT_DIRTY}" = true ] && echo ' (có thay đổi chưa commit)') |
| Build (bước 1) | ${BUILT_AT}, $(grep '^tests=' "${CONTEXT_DIR}/BUILD-INFO" | cut -d= -f2) test xanh |
| Thời gian đóng gói | $(( $(date +%s) - START_TIME ))s |

Push: \`docker push ${IMAGE}\`

Chart (\`notebook-helm/values.yaml\`):

\`\`\`yaml
image:
  repository: ${REPO}
  tag: "${TAG}"
\`\`\`
EOF

echo "----------------------------------------------------------------------"
echo -e "${GREEN}ĐÓNG GÓI XONG: ${IMAGE}${NC} (báo cáo: dist/package-report.md)"
[ "${GIT_DIRTY}" = true ] && echo -e "${YELLOW}Lưu ý: build từ code có thay đổi chưa commit.${NC}"
echo "Push:   docker push ${IMAGE}"
echo "Chart:  image.repository=${REPO}  image.tag=\"${TAG}\"  (notebook-helm/values.yaml)"
echo "----------------------------------------------------------------------"
