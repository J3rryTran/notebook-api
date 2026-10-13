#!/usr/bin/env bash
# ==============================================================================
# Script đóng gói tự động cho hệ thống Microservices Notebook API
# Đóng gói: nb-document-service, nb-execution-service, nb-frontend
# ==============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
BACKEND_DIR="${ROOT_DIR}/backend"
FRONTEND_DIR="${ROOT_DIR}/frontend"
HELM_DIR="${ROOT_DIR}/notebook-helm"
LOCAL_MAVEN="${ROOT_DIR}/apache-maven-3.9.11/bin/mvn"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo "=============================================================================="
echo "          TỰ ĐỘNG ĐÓNG GÓI MICROSERVICES NOTEBOOK API VÀO dist/              "
echo "=============================================================================="

# 1. Xác định Maven
if [ -x "${LOCAL_MAVEN}" ]; then
    MVN_CMD="${LOCAL_MAVEN}"
elif command -v mvn >/dev/null 2>&1; then
    MVN_CMD="mvn"
else
    log_error "Không tìm thấy Maven!"
    exit 1
fi
log_success "Sử dụng Maven: ${MVN_CMD}"

# 2. Build Backend Microservices
log_info "Biên dịch và đóng gói các module Spring Boot..."
cd "${BACKEND_DIR}"
"${MVN_CMD}" clean package -DskipTests

# 3. Tạo thư mục dist/
log_info "Xây dựng thư mục phân phối dist/..."
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}/bin" "${DIST_DIR}/config" "${DIST_DIR}/frontend" "${DIST_DIR}/helm"

# Copy Artifacts
cp "${BACKEND_DIR}/nb-notebook-service/target/nb-notebook-service-"*.jar "${DIST_DIR}/bin/nb-notebook-service.jar"
cp "${BACKEND_DIR}/nb-execution-service/target/nb-execution-service-"*.jar "${DIST_DIR}/bin/nb-execution-service.jar"

cp "${BACKEND_DIR}/nb-notebook-service/src/main/resources/application.yml" "${DIST_DIR}/config/notebook-application.yml"
cp "${BACKEND_DIR}/nb-execution-service/src/main/resources/application.yml" "${DIST_DIR}/config/execution-application.yml"

cp -r "${FRONTEND_DIR}/public/"* "${DIST_DIR}/frontend/"
cp "${FRONTEND_DIR}/nginx.conf" "${DIST_DIR}/frontend/nginx.conf"

if [ -d "${HELM_DIR}" ]; then
    cp -r "${HELM_DIR}/"* "${DIST_DIR}/helm/"
fi

# Tạo script start-all.sh cho local/dev
cat << 'EOF' > "${DIST_DIR}/start-all.sh"
#!/usr/bin/env bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Khởi động nb-notebook-service trên port 8080 (dev profile)..."
java -jar "${DIR}/bin/nb-notebook-service.jar" --spring.profiles.active=dev &
DOC_PID=$!

echo "Khởi động nb-execution-service trên port 8082 (dev profile)..."
java -jar "${DIR}/bin/nb-execution-service.jar" --spring.profiles.active=dev &
EXEC_PID=$!

echo "Document PID: $DOC_PID, Execution PID: $EXEC_PID"
echo "$DOC_PID $EXEC_PID" > "${DIR}/services.pid"

trap "kill $DOC_PID $EXEC_PID 2>/dev/null" EXIT
wait
EOF
chmod +x "${DIST_DIR}/start-all.sh"

# Nén tarball
cd "${ROOT_DIR}"
tar -czf dist.tar.gz -C "${ROOT_DIR}" dist

echo "=============================================================================="
log_success "HOÀN TẤT ĐÓNG GÓI MICROSERVICES VÀO dist/ VÀ dist.tar.gz!"
echo " - nb-document-service : dist/bin/nb-document-service.jar"
echo " - nb-execution-service: dist/bin/nb-execution-service.jar"
echo " - Frontend Static/Conf: dist/frontend/"
echo " - Helm K8s manifests  : dist/helm/"
echo "=============================================================================="
