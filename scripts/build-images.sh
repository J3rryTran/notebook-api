#!/usr/bin/env bash
# ==============================================================================
# Script tự động build cả 3 Docker Images cho hệ thống Notebook
# ==============================================================================

set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

REGISTRY="${DOCKER_REGISTRY:-docker.io/j3rrytran}"
TAG="${IMAGE_TAG:-0.1.0}"

echo "=============================================================================="
echo "          BUILD DOCKER IMAGES CHO NOTEBOOK API (TAG: ${TAG})                  "
echo "=============================================================================="

echo "[1/3] Building image: ${REGISTRY}/nb-notebook-service:${TAG}..."
docker build \
  -t "${REGISTRY}/nb-notebook-service:${TAG}" \
  -t "${REGISTRY}/nb-notebook-service:latest" \
  -f "${ROOT_DIR}/backend/nb-notebook-service/Dockerfile" \
  "${ROOT_DIR}/backend"

echo "[2/3] Building image: ${REGISTRY}/nb-execution-service:${TAG}..."
docker build \
  -t "${REGISTRY}/nb-execution-service:${TAG}" \
  -t "${REGISTRY}/nb-execution-service:latest" \
  -f "${ROOT_DIR}/backend/nb-execution-service/Dockerfile" \
  "${ROOT_DIR}/backend"

echo "[3/3] Building image: ${REGISTRY}/nb-frontend:${TAG}..."
docker build \
  -t "${REGISTRY}/nb-frontend:${TAG}" \
  -t "${REGISTRY}/nb-frontend:latest" \
  -f "${ROOT_DIR}/frontend/Dockerfile" \
  "${ROOT_DIR}/frontend"

echo "=============================================================================="
echo " BUILD THÀNH CÔNG CẢ 3 DOCKER IMAGES:"
echo " 1. ${REGISTRY}/nb-notebook-service:${TAG}"
echo " 2. ${REGISTRY}/nb-execution-service:${TAG}"
echo " 3. ${REGISTRY}/nb-frontend:${TAG}"
echo "=============================================================================="
