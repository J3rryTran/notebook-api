#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
RED='\033[0;31m'
NC='\033[0m'

ENV_SETS=(--set mariadb.host=mariadb.build-check.invalid
          --set kyuubi.restUrl=http://kyuubi.build-check.invalid:10099
          --set auth.oidc.issuer=https://keycloak.build-check.invalid/realms/check
          --set auth.ldap.url=ldaps://ldap.build-check.invalid:636
          --set service.nodePort=null)

echo -e "${BLUE}=== [X1] Static Contract Check: Helm ConfigMap vs Code Properties ===${NC}"

TMP_DIR=$(mktemp -d)
trap 'rm -rf "${TMP_DIR}"' EXIT

docker run --rm -v "${ROOT_DIR}/notebook-helm":/c alpine/helm:3.14.4 template t /c "${ENV_SETS[@]}" --set auth.mode=oidc --set auth.oidc.serviceAccount.enabled=true > "${TMP_DIR}/oidc.yaml"
LDAP_SETS=(--set auth.mode=ldap --set auth.ldap.url=ldaps://ldap.example.invalid:636 --set auth.ldap.startTls=false
           --set auth.ldap.baseDN=dc=example --set 'auth.ldap.binddn=uid=svc\,dc=example'
           --set 'auth.ldap.userDNPattern=uid=%s\,ou=people\,dc=example' --set auth.ldap.guidKey=uid
           --set auth.ldap.groupClassKey=groupOfNames --set auth.ldap.groupMembershipKey=member
           --set auth.ldap.connectTimeout=5s --set auth.ldap.readTimeout=10s
           --set auth.ldap.trustStore.enabled=false --set auth.ldap.trustStore.type=PKCS12 --set auth.ldap.serviceAccount.username=notebook-svc)
docker run --rm -v "${ROOT_DIR}/notebook-helm":/c alpine/helm:3.14.4 template t /c "${ENV_SETS[@]}" \
  "${LDAP_SETS[@]}" --set auth.ldap.trustStore.enabled=true > "${TMP_DIR}/ldap.yaml"

EXPECTED_CONFIG_KEYS=(
  "spring.datasource.url"
  "spring.datasource.username"
  "spring.datasource.hikari.maximum-pool-size"
  "server.forward-headers-strategy"
  "logging.level.root"
  "notebook.admins"
  "notebook.auth.mode"
  "notebook.auth.allow-unverified"
  "notebook.auth.username-claim"
  "notebook.auth.cache-ttl"
  "notebook.auth.oidc.issuer"
  "notebook.auth.oidc.client-id"
  "notebook.auth.oidc.redirect-path"
  "notebook.auth.oidc.scopes"
  "notebook.auth.oidc.service-client-id"
  "notebook.auth.session-ttl"
  "notebook.auth.recheck"
  "notebook.auth.ldap.url"
  "notebook.auth.ldap.start-tls"
  "notebook.auth.ldap.base-dn"
  "notebook.auth.ldap.bind-dn"
  "notebook.auth.ldap.guid-key"
  "notebook.auth.ldap.user-dn-pattern"
  "notebook.auth.ldap.group-dn-pattern"
  "notebook.auth.ldap.group-class-key"
  "notebook.auth.ldap.group-membership-key"
  "notebook.auth.ldap.user-membership-key"
  "notebook.auth.ldap.group-filter"
  "notebook.auth.ldap.user-filter"
  "notebook.auth.ldap.custom-ldap-query"
  "notebook.auth.ldap.service-account.username"
  "notebook.auth.ldap.connect-timeout"
  "notebook.auth.ldap.read-timeout"
  "notebook.max.cells"
  "notebook.max.page-size"
  "notebook.cell.source.max-size"
  "notebook.document.max-size"
  "notebook.import.max-size"
  "notebook.revision.max-per-notebook"
  "notebook.results.max-rows"
  "notebook.events.pool-size"
  "notebook.events.max-wait"
  "kyuubi.rest-url"
  "kyuubi.connect-timeout"
  "kyuubi.request-timeout"
  "kyuubi.idle-session-timeout"
  "kyuubi.idle-reaper-interval"
  "kyuubi.pin-to-instance"
  "kyuubi.allowed-session-config-keys"
)

PROPS_SRC="${ROOT_DIR}/backend/nb-common/src/main/java/com/vtnexus/notebook/common"
MISSING_KEYS=0
for key in "${EXPECTED_CONFIG_KEYS[@]}"; do
  leaf="${key##*.}"
  if ! grep -qE "^[[:space:]]+${leaf}:" "${TMP_DIR}/oidc.yaml" "${TMP_DIR}/ldap.yaml"; then
    echo -e "${RED}[X1 FAIL] Key ${key} không có trong ConfigMap render từ chart${NC}"
    MISSING_KEYS=$((MISSING_KEYS + 1))
    continue
  fi
  case "${key}" in
    spring.*|server.*|logging.*) continue ;;
  esac
  camel=$(echo "${leaf}" | sed -E 's/-([a-z])/\U\1/g')
  if ! grep -rqE "private [A-Za-z<>,. ]+ ${camel}( |;|=)" "${PROPS_SRC}/config" "${PROPS_SRC}/auth/AuthProperties.java"; then
    echo -e "${RED}[X1 FAIL] Key ${key}: không có field '${camel}' trong @ConfigurationProperties${NC}"
    MISSING_KEYS=$((MISSING_KEYS + 1))
  fi
done

if [ "${MISSING_KEYS}" -eq 0 ]; then
  echo -e "${GREEN}[X1 PASSED] Tất cả các khóa chart render đều có code đọc.${NC}"
else
  echo -e "${RED}[X1 FAILED] Có ${MISSING_KEYS} khóa mồ côi!${NC}"
  exit 1
fi
