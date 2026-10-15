#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="$ROOT_DIR/notebook-helm"

echo "=== 1. Helm Lint ==="
docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4 lint /c

# Kiểm chart với chính notebook-helm/values.yaml (cấu hình deploy thật). Các mode/tùy chọn khác chỉ được
# bật tạm bằng --set để chắc template của chúng vẫn render đúng; không cần file values mẫu nào khác.
HELM=(docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4)
# Bộ --set tối thiểu để thử mode ldap (giá trị minh họa, không dùng để deploy)
LDAP_SETS=(--set auth.mode=ldap --set auth.ldap.url=ldaps://ldap.example.invalid:636
           --set 'auth.ldap.userSearchFilter=(uid={0})' --set 'auth.ldap.managerDn=cn=svc\,dc=example\,dc=invalid')

echo "=== 2. Helm Template ==="
echo "Rendering values.yaml (auth.mode=$(grep -E '^  mode:' "$CHART_DIR/values.yaml" | awk '{print $2}'))..."
"${HELM[@]}" template t /c >/dev/null

echo "Rendering other auth modes (template sanity only)..."
"${HELM[@]}" template t /c --set auth.mode=no-oidc >/dev/null
"${HELM[@]}" template t /c "${LDAP_SETS[@]}" >/dev/null
"${HELM[@]}" template t /c "${LDAP_SETS[@]}" --set auth.ldap.trustStore.enabled=true >/dev/null

echo "Rendering HA antiAffinity hard / none..."
out=$("${HELM[@]}" template t /c --set ha.antiAffinity=hard)
echo "$out" | grep -q "requiredDuringSchedulingIgnoredDuringExecution" || { echo "FAILED: hard anti-affinity not rendered"; exit 1; }
out=$("${HELM[@]}" template t /c --set ha.antiAffinity=none)
if echo "$out" | grep -q "podAntiAffinity"; then echo "FAILED: none still renders anti-affinity"; exit 1; fi

echo "=== 3. Helm Template 21 Validation Error Cases ==="

test_error() {
  local desc="$1"
  local expected="$2"
  shift 2
  echo -n "Test case: $desc ... "
  local out
  if out=$("${HELM[@]}" template t /c "$@" 2>&1); then
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

# 1. topology != allInOne
test_error "topology != allInOne" "topology=split is not implemented yet" --set topology=split

# 2. auth.mode invalid (tested via schema or validator)
test_error "auth.mode invalid" "auth.mode must be" --set auth.mode=abc

# 3a. existingSecret empty
test_error "existingSecret empty" "existingSecret is required" --set existingSecret=""

# 3b. mariadb.host empty
test_error "mariadb.host empty" "mariadb.host is required" --set mariadb.host=""

# 3c. kyuubi.restUrl empty
test_error "kyuubi.restUrl empty" "kyuubi.restUrl is required" --set kyuubi.restUrl=""

# 3d. publicUrl empty
test_error "publicUrl empty" "publicUrl is required" --set publicUrl=""

# 4. mode=oidc and auth.oidc.issuer empty
test_error "mode=oidc and issuer empty" "auth.oidc.issuer is required for mode=oidc" --set auth.oidc.issuer=""

# 5. mode=ldap and auth.ldap.url empty
test_error "mode=ldap and url empty" "auth.ldap.url is required for mode=ldap" "${LDAP_SETS[@]}" --set auth.ldap.url=""

# 6. mode=ldap and not exactly one of userDnPattern / userSearchFilter
test_error "mode=ldap both pattern and filter" "set exactly one of auth.ldap.userDnPattern or userSearchFilter" "${LDAP_SETS[@]}" --set auth.ldap.userDnPattern="uid={0}" --set auth.ldap.userSearchFilter="(uid={0})"

# 7. userSearchFilter without managerDn
test_error "userSearchFilter without managerDn" "auth.ldap.managerDn is required with userSearchFilter" "${LDAP_SETS[@]}" --set auth.ldap.managerDn=""

# 8. ldap:// and startTls=false
test_error "plain ldap without startTls" "plain ldap:// requires auth.ldap.startTls=true" "${LDAP_SETS[@]}" --set auth.ldap.url="ldap://10.29.19.56:389" --set auth.ldap.startTls=false

# 9. kyuubi.engine.spark.output.mode present in extraSessionConfigs
test_error "ldaps with startTls" "auth.ldap.startTls must be false with ldaps://" "${LDAP_SETS[@]}" --set auth.ldap.startTls=true

test_error "ldap without usernameAttribute" "auth.ldap.usernameAttribute is required for mode=ldap" "${LDAP_SETS[@]}" --set auth.ldap.usernameAttribute=""

test_error "output.mode set in extraSessionConfigs" "output.mode must stay at Kyuubi default (AUTO)" --set 'kyuubi.extraSessionConfigs.kyuubi\.engine\.spark\.output\.mode=NOTEBOOK'

# 10. ingress.enabled and ingress.host empty
test_error "ha.antiAffinity invalid (schema)" "ha.antiAffinity must be one of the following" --set ha.antiAffinity=strict

test_error "PDB minAvailable >= replicaCount" "must be < replicaCount" --set podDisruptionBudget.minAvailable=4

test_error "updateStrategy both zero" "updateStrategy.maxUnavailable and maxSurge cannot both be 0" --set updateStrategy.maxUnavailable=0 --set updateStrategy.maxSurge=0

test_error "ingress.enabled without host" "ingress.host is required" --set ingress.enabled=true --set ingress.host=""

# 11. service.nodePort out of 30000-32767
test_error "nodePort out of range" "service.nodePort must be in 30000-32767" --set service.nodePort=29999

# 12. publicUrl http:// with ingress.tls
test_error "http publicUrl with ingress.tls" "publicUrl must be https:// when ingress.tls is set" --set ingress.enabled=true --set ingress.host="notebook.example.com" --set 'ingress.tls[0].secretName=tls-secret' --set 'ingress.tls[0].hosts[0]=notebook.example.com'

# 13. NodePort publicUrl port mismatch
test_error "NodePort publicUrl mismatch" "publicUrl must use service.nodePort (32380) when exposing via NodePort" --set publicUrl="http://10.29.19.56:8080"

echo "=== ALL HELM CHECKS PASSED ==="
