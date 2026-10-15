#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="$ROOT_DIR/notebook-helm"

HELM=(docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4)

if [ "${1:-}" = "--deploy" ]; then
  echo "=== Kiểm values.yaml để deploy ==="
  "${HELM[@]}" lint /c
  "${HELM[@]}" template t /c > /dev/null
  echo "=== values.yaml render được, sẵn sàng deploy ==="
  exit 0
fi

ENV_SETS=(--set mariadb.host=mariadb.build-check.invalid
          --set kyuubi.restUrl=http://kyuubi.build-check.invalid:10099
          --set auth.oidc.issuer=https://keycloak.build-check.invalid/realms/check
          --set auth.ldap.url=ldaps://ldap.build-check.invalid:636
          --set service.nodePort=null)
HELM_T=("${HELM[@]}" template t /c "${ENV_SETS[@]}")

echo "=== 1. Helm Lint ==="
if ! lint_out=$("${HELM[@]}" lint /c "${ENV_SETS[@]}" 2>&1); then
  if echo "$lint_out" | grep '^\[ERROR\]' | grep -v "^\[ERROR\] values.yaml:" | grep -vq "values don't meet the specifications"; then
    echo "$lint_out"
    exit 1
  fi
  { echo "$lint_out" | grep -E '^- ' | sort -u | sed 's/^/WARN values.yaml /'; } || true
  echo "WARN values.yaml chưa hợp lệ để deploy (không chặn build). Kiểm trước khi deploy: ./scripts/helm-check.sh --deploy"
else
  echo "$lint_out"
fi
LDAP_SETS=(--set auth.mode=ldap --set auth.ldap.url=ldaps://ldap.example.invalid:636 --set auth.ldap.startTls=false
           --set auth.ldap.baseDn=dc=example --set auth.ldap.userSearchBase=ou=people
           --set auth.ldap.userDnPattern= --set 'auth.ldap.userSearchFilter=(uid={0})' --set auth.ldap.usernameAttribute=uid
           --set 'auth.ldap.managerDn=cn=svc\,dc=example\,dc=invalid' --set auth.ldap.groupSearchBase=ou=groups
           --set auth.ldap.groupMemberAttribute=member --set auth.ldap.adminGroup=
           --set auth.ldap.connectTimeout=5s --set auth.ldap.readTimeout=10s
           --set auth.ldap.trustStore.enabled=false --set auth.ldap.trustStore.type=PKCS12)

echo "=== 2. Helm Template ==="
echo "Rendering chart (values.yaml + giá trị môi trường giả, auth.mode=$(grep -E '^  mode:' "$CHART_DIR/values.yaml" | awk '{print $2}'))..."
"${HELM_T[@]}" >/dev/null

echo "Rendering all auth modes (template sanity only)..."
"${HELM_T[@]}" --set auth.mode=oidc >/dev/null
"${HELM_T[@]}" --set auth.mode=no-oidc >/dev/null
"${HELM_T[@]}" "${LDAP_SETS[@]}" >/dev/null
"${HELM_T[@]}" "${LDAP_SETS[@]}" --set auth.ldap.trustStore.enabled=true >/dev/null

echo "Rendering HA antiAffinity hard / none..."
out=$("${HELM_T[@]}" --set ha.antiAffinity=hard)
echo "$out" | grep -q "requiredDuringSchedulingIgnoredDuringExecution" || { echo "FAILED: hard anti-affinity not rendered"; exit 1; }
out=$("${HELM_T[@]}" --set ha.antiAffinity=none)
if echo "$out" | grep -q "podAntiAffinity"; then echo "FAILED: none still renders anti-affinity"; exit 1; fi

echo "=== 3. Helm Template 19 Validation Error Cases ==="

test_error() {
  local desc="$1"
  local expected="$2"
  shift 2
  echo -n "Test case: $desc ... "
  local out
  if out=$("${HELM_T[@]}" "$@" 2>&1); then
    echo "FAILED (expected error '$expected', but command succeeded)"
    exit 1
  fi
  if echo "$out" | grep -Fq "$expected"; then
    echo "PASSED (got expected error: $expected)"
  else
    echo "FAILED (error did not match '$expected'):"
    echo "$out"
    exit 1
  fi
}

test_error "auth.mode invalid" "auth.mode must be" --set auth.mode=abc

test_error "existingSecret empty" "existingSecret is required" --set existingSecret=""

test_error "mariadb.host empty" "mariadb.host is required" --set mariadb.host=""

test_error "kyuubi.restUrl empty" "kyuubi.restUrl is required" --set kyuubi.restUrl=""

test_error "mode=oidc and issuer empty" "auth.oidc.issuer is required for mode=oidc" --set auth.mode=oidc --set auth.oidc.issuer=""

test_error "mode=ldap without ldap block" "auth.ldap block is required for mode=ldap" --set auth.mode=ldap --set auth.ldap=null

test_error "mode=ldap and url empty" "auth.ldap.url is required for mode=ldap" "${LDAP_SETS[@]}" --set auth.ldap.url=""

test_error "mode=ldap both pattern and filter" "set exactly one of auth.ldap.userDnPattern or userSearchFilter" "${LDAP_SETS[@]}" --set auth.ldap.userDnPattern="uid={0}" --set auth.ldap.userSearchFilter="(uid={0})"

test_error "userSearchFilter without managerDn" "auth.ldap.managerDn is required with userSearchFilter" "${LDAP_SETS[@]}" --set auth.ldap.managerDn=""

test_error "plain ldap without startTls" "plain ldap:// requires auth.ldap.startTls=true" "${LDAP_SETS[@]}" --set auth.ldap.url="ldap://10.29.19.56:389" --set auth.ldap.startTls=false

test_error "ldaps with startTls" "auth.ldap.startTls must be false with ldaps://" "${LDAP_SETS[@]}" --set auth.ldap.startTls=true

test_error "ldap without usernameAttribute" "auth.ldap.usernameAttribute is required for mode=ldap" "${LDAP_SETS[@]}" --set auth.ldap.usernameAttribute=""

test_error "output.mode set in extraSessionConfigs" "output.mode must stay at Kyuubi default (AUTO)" --set 'kyuubi.extraSessionConfigs.kyuubi\.engine\.spark\.output\.mode=NOTEBOOK'

test_error "ha.antiAffinity invalid (schema)" "ha.antiAffinity must be one of the following" --set ha.antiAffinity=strict

test_error "PDB minAvailable >= replicaCount" "must be < replicaCount" --set podDisruptionBudget.minAvailable=4

test_error "updateStrategy both zero" "updateStrategy.maxUnavailable and maxSurge cannot both be 0" --set updateStrategy.maxUnavailable=0 --set updateStrategy.maxSurge=0

test_error "oidc redirectPath under /api/" "auth.oidc.redirectPath must start with /" --set auth.mode=oidc --set auth.oidc.redirectPath=/api/v1/cb

test_error "ingress.enabled without host" "ingress.host is required" --set ingress.enabled=true --set ingress.host=""

test_error "nodePort out of range" "service.nodePort must be in 30000-32767" --set service.nodePort=29999

echo "=== ALL HELM CHECKS PASSED ==="
