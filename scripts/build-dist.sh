#!/usr/bin/env bash
# ==============================================================================
# Script đóng gói tự động cho Notebook API
# Sử dụng trực tiếp Apache Maven tại thư mục apache-maven-3.9.11
# ==============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DIST_DIR="${ROOT_DIR}/dist"
BACKEND_DIR="${ROOT_DIR}/backend"
FRONTEND_DIR="${ROOT_DIR}/frontend"
HELM_DIR="${ROOT_DIR}/notebook-helm"
LOCAL_MAVEN="${ROOT_DIR}/apache-maven-3.9.11/bin/mvn"

# Màu hiển thị console
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo "=============================================================================="
echo "          TỰ ĐỘNG ĐÓNG GÓI NOTEBOOK API BẰNG APACHE MAVEN                    "
echo "=============================================================================="

# ------------------------------------------------------------------------------
# 1. Kiểm tra Maven tại apache-maven-3.9.11
# ------------------------------------------------------------------------------
log_info "1/5. Xác định công cụ Maven..."
if [ -x "${LOCAL_MAVEN}" ]; then
    MVN_CMD="${LOCAL_MAVEN}"
    log_success "Sử dụng Maven tại thư mục dự án: ${MVN_CMD}"
elif command -v mvn >/dev/null 2>&1; then
    MVN_CMD="mvn"
    log_warn "Không tìm thấy apache-maven-3.9.11/bin/mvn, chuyển sang dùng system mvn"
else
    log_error "Không tìm thấy Maven tại ${LOCAL_MAVEN}!"
    exit 1
fi

"${MVN_CMD}" -version

# ------------------------------------------------------------------------------
# 2. Đồng bộ giao diện Web Frontend vào Spring Boot
# ------------------------------------------------------------------------------
log_info "2/5. Đồng bộ tài nguyên Web Frontend..."
STATIC_TARGET="${BACKEND_DIR}/src/main/resources/static"
mkdir -p "${STATIC_TARGET}"

if [ -d "${FRONTEND_DIR}/public" ]; then
    cp -r "${FRONTEND_DIR}/public/"* "${STATIC_TARGET}/"
    log_success "Đã tích hợp giao diện HTML/CSS/JS vào ${STATIC_TARGET}"
fi

# ------------------------------------------------------------------------------
# 3. Biên dịch và Đóng gói Spring Boot Backend bằng Maven
# ------------------------------------------------------------------------------
log_info "3/5. Thực hiện Maven Build & Package..."
cd "${BACKEND_DIR}"
"${MVN_CMD}" clean package -DskipTests

# ------------------------------------------------------------------------------
# 4. Xây dựng thư mục phân phối dist/
# ------------------------------------------------------------------------------
log_info "4/5. Tạo cấu trúc thư mục phân phối dist/..."
rm -rf "${DIST_DIR}"
mkdir -p "${DIST_DIR}/bin" "${DIST_DIR}/config" "${DIST_DIR}/logs" "${DIST_DIR}/helm"

# Copy runnable fat JAR
JAR_FILE=$(ls "${BACKEND_DIR}/target/notebook-api-"*.jar | grep -v '\.original$' | head -n 1)
cp "${JAR_FILE}" "${DIST_DIR}/bin/notebook-api.jar"
log_success "Đã copy file thực thi: dist/bin/notebook-api.jar"

# Copy file cấu hình
cp "${BACKEND_DIR}/src/main/resources/application.yml" "${DIST_DIR}/config/application.yml"
log_success "Đã copy file cấu hình: dist/config/application.yml"

# Copy Helm chart
if [ -d "${HELM_DIR}" ]; then
    cp -r "${HELM_DIR}/"* "${DIST_DIR}/helm/"
    log_success "Đã copy Kubernetes Helm chart: dist/helm/"
fi

# Tạo script chạy start.sh
cat << 'EOF' > "${DIST_DIR}/start.sh"
#!/usr/bin/env bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR_FILE="${DIR}/bin/notebook-api.jar"
CONFIG_FILE="${DIR}/config/application.yml"
PID_FILE="${DIR}/notebook.pid"
LOG_FILE="${DIR}/logs/notebook.log"

if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "Ứng dụng đang chạy với PID: $(cat "$PID_FILE")"
    exit 0
fi

echo "Khởi động Notebook API..."
mkdir -p "${DIR}/logs"

if [ "$1" = "--daemon" ] || [ "$1" = "-d" ]; then
    shift
    nohup java -jar "${JAR_FILE}" --spring.config.additional-location="file:${CONFIG_FILE}" "$@" > "${LOG_FILE}" 2>&1 &
    echo $! > "${PID_FILE}"
    echo "Ứng dụng đã được khởi động ở chế độ nền. PID: $(cat "$PID_FILE")"
    echo "Xem log tại: ${LOG_FILE}"
else
    exec java -jar "${JAR_FILE}" --spring.config.additional-location="file:${CONFIG_FILE}" "$@"
fi
EOF
chmod +x "${DIST_DIR}/start.sh"

# Tạo script dừng stop.sh
cat << 'EOF' > "${DIST_DIR}/stop.sh"
#!/usr/bin/env bash
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PID_FILE="${DIR}/notebook.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "Không tìm thấy file PID. Có thể ứng dụng chưa chạy."
    exit 0
fi

PID="$(cat "$PID_FILE")"
if kill -0 "$PID" 2>/dev/null; then
    echo "Đang dừng Notebook API (PID: $PID)..."
    kill "$PID"
    sleep 2
    if kill -0 "$PID" 2>/dev/null; then
        echo "Ép dừng (SIGKILL)..."
        kill -9 "$PID"
    fi
    echo "Đã dừng thành công."
else
    echo "Tiến trình (PID: $PID) không tồn tại."
fi
rm -f "$PID_FILE"
EOF
chmod +x "${DIST_DIR}/stop.sh"

# ------------------------------------------------------------------------------
# 5. Đóng gói archive dist.tar.gz
# ------------------------------------------------------------------------------
log_info "5/5. Đóng gói archive dist.tar.gz..."
cd "${ROOT_DIR}"
tar -czf dist.tar.gz -C "${ROOT_DIR}" dist

echo "=============================================================================="
log_success "QUÁ TRÌNH ĐÓNG GÓI BẰNG APACHE MAVEN HOÀN TẤT THÀNH CÔNG!"
echo " - Maven đã dùng     : ${MVN_CMD}"
echo " - Thư mục phân phối : ${DIST_DIR}"
echo " - File nén deploy   : ${ROOT_DIR}/dist.tar.gz"
echo " - Khởi động         : ./dist/start.sh (hoặc ./dist/start.sh --daemon)"
echo " - Dừng              : ./dist/stop.sh"
echo "=============================================================================="
