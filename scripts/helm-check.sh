#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="$ROOT_DIR/notebook-helm"

echo "=== 1. Helm Lint ==="
docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4 lint /c

echo "=== 2. Helm Template Valid Modes ==="
echo "Testing OIDC mode..."
docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4 template t /c -f /c/ci/oidc-values.yaml >/dev/null

echo "Testing NO-OIDC mode..."
docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4 template t /c -f /c/ci/no-oidc-values.yaml >/dev/null

echo "Testing LDAP mode..."
docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4 template t /c -f /c/ci/ldap-values.yaml >/dev/null

echo "=== 3. Helm Template 13 Validation Error Cases ==="

test_error() {
  local desc="$1"
  local expected="$2"
  shift 2
  echo -n "Test case: $desc ... "
  local out
  if out=$(docker run --rm -v "$CHART_DIR":/c alpine/helm:3.14.4 template t /c -f /c/ci/oidc-values.yaml "$@" 2>&1); then
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
test_error "mode=ldap and url empty" "auth.ldap.url is required for mode=ldap" -f /c/ci/ldap-values.yaml --set auth.ldap.url=""

# 6. mode=ldap and not exactly one of userDnPattern / userSearchFilter
test_error "mode=ldap both pattern and filter" "set exactly one of auth.ldap.userDnPattern or userSearchFilter" -f /c/ci/ldap-values.yaml --set auth.ldap.userDnPattern="uid={0}" --set auth.ldap.userSearchFilter="(uid={0})"

# 7. userSearchFilter without managerDn
test_error "userSearchFilter without managerDn" "auth.ldap.managerDn is required with userSearchFilter" -f /c/ci/ldap-values.yaml --set auth.ldap.managerDn=""

# 8. ldap:// and startTls=false
test_error "plain ldap without startTls" "plain ldap:// requires auth.ldap.startTls=true" -f /c/ci/ldap-values.yaml --set auth.ldap.url="ldap://10.29.19.56:389" --set auth.ldap.startTls=false

# 9. kyuubi.engine.spark.output.mode present in extraSessionConfigs
test_error "output.mode set in extraSessionConfigs" "output.mode must stay at Kyuubi default (AUTO)" --set 'kyuubi.extraSessionConfigs.kyuubi\.engine\.spark\.output\.mode=NOTEBOOK'

# 10. ingress.enabled and ingress.host empty
test_error "ingress.enabled without host" "ingress.host is required" --set ingress.enabled=true --set ingress.host=""

# 11. service.nodePort out of 30000-32767
test_error "nodePort out of range" "service.nodePort must be in 30000-32767" --set service.nodePort=29999

# 12. publicUrl http:// with ingress.tls
test_error "http publicUrl with ingress.tls" "publicUrl must be https:// when ingress.tls is set" --set ingress.enabled=true --set ingress.host="notebook.example.com" --set 'ingress.tls[0].secretName=tls-secret' --set 'ingress.tls[0].hosts[0]=notebook.example.com'

# 13. NodePort publicUrl port mismatch
test_error "NodePort publicUrl mismatch" "publicUrl must use service.nodePort (32380) when exposing via NodePort" --set publicUrl="http://10.29.19.56:8080"

echo "=== ALL HELM CHECKS PASSED ==="
