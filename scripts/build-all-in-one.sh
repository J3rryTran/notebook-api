#!/usr/bin/env bash
# ==============================================================================
# Script đóng gói All-In-One Docker Image (1 Stage) cho Notebook Application
# Bước 1: Build file Jar ở host bằng Maven có sẵn
# Bước 2: Build Docker image 1-stage bằng debian:12-slim
# ==============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

IMAGE_NAME="${1:-docker.io/j3rrytran/notebook:0.1.0}"
MAVEN_BIN="${ROOT_DIR}/backend/mvnw"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

echo -e "${BLUE}[1/2] Biên dịch và đóng gói JAR ở host...${NC}"
cd "${ROOT_DIR}/backend"
chmod +x "${MAVEN_BIN}"
"${MAVEN_BIN}" clean package -DskipTests


echo -e "${BLUE}[2/2] Build Docker image (1 stage): ${IMAGE_NAME}...${NC}"
cd "${ROOT_DIR}"
docker build -t "${IMAGE_NAME}" -f Dockerfile .

echo -e "${GREEN}[SUCCESS] Đóng gói thành công image: ${IMAGE_NAME}${NC}"
echo "Để push image lên registry: docker push ${IMAGE_NAME}"
