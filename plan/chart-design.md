# Thiết kế lại chart `notebook-helm`

> Cập nhật 2026-10-15: bỏ `publicUrl`. Redirect OIDC = `window.location.origin` (địa chỉ người dùng thực sự mở, qua NodePort/Ingress) + `auth.oidc.redirectPath` (mặc định `/auth/callback`, điền khớp Keycloak). Cờ `Secure` của cookie theo request thật (`X-Forwarded-Proto` / `request.isSecure()`). Các chỗ nhắc `publicUrl` bên dưới là lịch sử. Còn 19 ca lỗi trong `scripts/helm-check.sh`.

> Cập nhật 2026-10-15: bỏ tham số `topology` (chỉ có all-in-one, không template nào dùng); mục `topology: split` bên dưới là lịch sử, không còn trong chart. Còn 20 ca lỗi trong `scripts/helm-check.sh`.

> Cập nhật 2026-10-15: build tách 2 lệnh — `scripts/build.sh` (test + kiểm, gom `dist/image-context/` không mã nguồn) và `scripts/package-image.sh <image>:<tag>` (đóng gói + chạy thử). Bỏ `scripts/build-all-in-one.sh`, `.dockerignore`; tag không còn lấy từ `Chart.yaml`/git. Mục 10 bên dưới (P0–P9) là lịch sử; quy trình hiện hành ở `BUILD.md`.

> Cập nhật 2026-10-15 (sau 0.2.1): bỏ thư mục `notebook-helm/ci/`; `values.yaml` chứa giá trị deploy thật (mode `oidc`, `replicaCount: 4`, PDB `minAvailable: 2`, `updateStrategy` 1/1, `mariadb.maxPoolSize: 5`); thêm `ha.*`, `updateStrategy`, `secretKeys.*`, `probes.*`, `terminationGracePeriodSeconds`, `preStopSleepSeconds`, `notebook.pageSize`, `ingress.annotations` (không còn viết cứng trong template); `scripts/helm-check.sh` và `contract-check.sh` kiểm trên `values.yaml` thật, mode khác thử bằng `--set`; 21 ca lỗi. Các chỗ nhắc `ci/` bên dưới là lịch sử.
> Cập nhật 2026-10-15 (chart 0.2.1): bỏ `kyuubi.sessionOpenTimeout` (session mở `launch.async`, không có code dùng); thêm `kyuubi.idleReaperIntervalMs`; `username-attribute` luôn render ở mode `ldap`; thêm 2 điều kiện validate LDAP (tổng 18 ca lỗi trong `scripts/helm-check.sh`); NOTES ghi đúng tên user proxy `service-account-<clientId>`.

Trạng thái: thiết kế, chưa thực hiện. Ngày: 2026-10-14. Đối tượng thực thi: AI agent.
Thay thế `plan/Chart.md` (bản cũ có Vault, PostgreSQL, `output.mode: NOTEBOOK`, đều đã bỏ) và mục
C4, C5, C6 của `plan/huong-dan-sua-vong-3.md`. Plan gốc vẫn là `plan/mariadb-auth-plan.md`.

---

## 0. Quyết định đã chốt

| # | Quyết định | Lý do |
|---|---|---|
| H1 | Chart chỉ là **Helm chart thuần**. Việc deploy do **hệ thống khác của nền tảng** đảm nhận. Repo này không chứa manifest của công cụ deploy (không ArgoCD Application, không Kustomize overlay). | Chủ dự án quyết. |
| H2 | **Không dùng Helm hook** (`pre-install`, `post-upgrade`, `helm test`…). Mọi thứ là tài nguyên thường, render ra là apply được. | Hệ thống deploy bên ngoài có thể không chạy hook, hoặc chạy khác `helm install`. Chart phải đúng khi chỉ được `helm template` rồi apply. |
| H3 | Giữ **image all-in-one** hiện có (nginx + notebook-service + execution-service trong một container, `Dockerfile` ở gốc repo), **một Deployment**. Chart vẫn có công tắc `topology: split` để sau này tách 3 Deployment mà không đổi tên value (mục 7), nhưng vòng này **chỉ làm `allInOne`**. | Đúng với cách đóng gói đã chọn ở `bf8cec5`; ít thay đổi nhất. |
| H4 | Secret do **vận hành tạo trước** (Dashboard), chart chỉ tham chiếu `existingSecret`. Chart **không bao giờ** render Secret chứa giá trị. Bỏ Vault. | Không để secret trong values/git. Chính sách hiện tại là tạo tay trên Dashboard. |
| H5 | Cấu hình ứng dụng (không bí mật) render vào **một ConfigMap** dạng `application.yaml`, mount vào container, cả hai JVM đọc qua `--spring.config.additional-location`. Không truyền từng cấu hình bằng env var nữa. Env chỉ dùng cho secret. | Một nguồn cấu hình, dễ đọc, khỏi lỗi tên env (C4). Đổi ConfigMap tự rollout nhờ annotation checksum. |
| H6 | Chart **validate values lúc render** (`fail` trong `_helpers.tpl`). Sai mode, thiếu giá trị bắt buộc → `helm template` lỗi, không ra manifest. | Bắt lỗi trước khi tới cluster, hệ thống deploy hiện lỗi ngay. |
| H7 | Flyway vẫn chạy trong notebook-service khi pod khởi động (không tách Job). Nhiều replica cùng khởi động an toàn nhờ khóa của Flyway trên MariaDB. | Không cần hook (H2). |

---

## 1. Cấu trúc

```
notebook-helm/
├── Chart.yaml
├── values.yaml                 mặc định an toàn, chạy được khi điền đủ phần bắt buộc
├── values.schema.json          kiểm kiểu/enum values (Helm tự áp khi render)
├── README.md                   bảng values, cách tạo Secret, cách kiểm sau deploy
└── templates/
    ├── _helpers.tpl            tên, label, validate (mục 5)
    ├── configmap.yaml          application.yaml chung cho 2 JVM (mục 3)
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml            tùy chọn, mặc định tắt
    ├── serviceaccount.yaml     automountServiceAccountToken: false
    ├── pdb.yaml                khi replicaCount > 1
    ├── networkpolicy.yaml      tùy chọn, mặc định tắt
    └── NOTES.txt               in checklist sau deploy (mục 8)
```

Xóa: `plan/Chart.md` (đánh dấu lỗi thời ở đầu file, không xóa lịch sử), `notebook-helm.zip` ở gốc repo
(bản cũ, đang bị `.gitignore` bỏ qua nhưng nằm trong thư mục làm việc).

`Chart.yaml`:

```yaml
apiVersion: v2
name: notebook
description: Notebook SQL/PySpark trên Kyuubi REST, lưu MariaDB
type: application
version: 0.2.0          # tăng mỗi lần đổi chart
appVersion: "0.1.0"     # tag image mặc định
kubeVersion: ">=1.23.0-0"
```

---

## 2. `values.yaml`

```yaml
# ---- Bắt buộc phải điền (đánh dấu REQUIRED); thiếu thì render lỗi ----

topology: allInOne            # allInOne (vòng này) | split (chưa hỗ trợ, render lỗi)

image:
  repository: docker.io/j3rrytran/notebook
  tag: ""                     # rỗng = .Chart.AppVersion
  pullPolicy: IfNotPresent
  pullSecrets: []             # [{name: regcred}]

replicaCount: 2

existingSecret: notebook-secrets   # REQUIRED. Key xem mục 4

# ---- Truy cập ----
service:
  type: NodePort              # NodePort | ClusterIP
  port: 8080
  nodePort: 32380             # chỉ dùng khi NodePort; rỗng = để K8s tự chọn

ingress:
  enabled: false
  className: nginx
  host: ""                    # REQUIRED khi enabled
  tls: []                     # [{secretName: notebook-tls, hosts: [...]}]
  annotations: {}

publicUrl: ""                 # REQUIRED. URL người dùng mở, vd http://10.29.19.56:32380
                              # dùng cho redirect OIDC và cờ cookie Secure (https:// → Secure)

# ---- Lưu trữ ----
mariadb:
  host: ""                    # REQUIRED, vd 10.29.19.52
  port: 3306
  database: test
  username: notebook_api
  params: "useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC"
  maxPoolSize: 10             # mỗi JVM

# ---- Kyuubi ----
kyuubi:
  restUrl: ""                 # REQUIRED, vd http://kyuubi-rest.kyuubi.svc.cluster.local:10099
  connectTimeout: 10s
  requestTimeout: 60s
  idleSessionTimeout: 30m     # 0s = tắt; IdleSessionReaper đóng session rảnh
  idleReaperIntervalMs: 300000
  pinToInstance: true
  allowedSessionConfigKeys:
    - spark.executor.memory
    - spark.executor.cores
    - spark.sql.shuffle.partitions
  # KHÔNG có sessionConfigs.kyuubi.engine.spark.output.mode: giữ AUTO (plan Q7). Render lỗi nếu có.
  extraSessionConfigs: {}

# ---- Xác thực ----
auth:
  mode: oidc                  # REQUIRED: oidc | no-oidc | ldap
  admins: []                  # username được coi là admin
  allowUnverified: false      # chỉ playground khi Kyuubi authentication=NONE

  oidc:                       # dùng khi mode=oidc
    issuer: ""                # REQUIRED khi oidc, phải khớp issuer bên Kyuubi
    clientId: kyuubi-notebook # client public cho SPA (PKCE)
    scopes: [openid, profile]
    usernameClaim: preferred_username
    cacheTtl: 60s
    serviceAccount:           # proxy user khi username ≠ ranger_username (plan §2.2 bước 4b)
      enabled: false          # bật sau khi P5.7 đạt
      clientId: notebook-api  # client confidential; secret ở key oidc-client-secret

  session:                    # dùng khi mode=no-oidc | ldap
    ttl: 8h
    recheck: 10m

  ldap:                       # dùng khi mode=ldap
    url: ""                   # REQUIRED khi ldap
    startTls: false
    baseDn: ""
    userDnPattern: ""         # đúng MỘT trong userDnPattern / userSearchFilter
    userSearchBase: ou=people
    userSearchFilter: ""
    usernameAttribute: uid
    managerDn: ""             # bắt buộc khi dùng userSearchFilter; password ở key ldap-manager-password
    groupSearchBase: ou=groups
    groupMemberAttribute: member
    adminGroup: ""
    connectTimeout: 5s
    readTimeout: 10s
    trustStore:
      enabled: false          # true → mount key ldap-truststore của existingSecret
      type: PKCS12            # PKCS12 | JKS | PEM

# ---- Giới hạn ứng dụng ----
notebook:
  maxCells: 500
  cellSourceMaxSize: 1Mi
  documentMaxSize: 16Mi
  importMaxSize: 16Mi
  maxRevisions: 100
  resultsMaxRows: 1000
  eventsPoolSize: 16
  eventsMaxWait: 10s

# ---- Runtime ----
jvm:
  notebookService: "-XX:MaxRAMPercentage=35 -XX:+ExitOnOutOfMemoryError"
  executionService: "-XX:MaxRAMPercentage=35 -XX:+ExitOnOutOfMemoryError"
  # 2 JVM chung 1 container: tổng ≈ 70% limit, phần còn lại cho nginx + metaspace

resources:
  requests: { cpu: 500m, memory: 1Gi }
  limits:   { cpu: "2",  memory: 2Gi }

logLevel: INFO                # root logger cả 2 JVM
extraEnv: []                  # [{name: X, value: Y}] cho trường hợp đặc biệt

podSecurityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: [ALL] }

nodeSelector: {}
tolerations: []
affinity: {}                  # rỗng → chart tự thêm podAntiAffinity "preferred" theo hostname
topologySpreadConstraints: []
podAnnotations: {}
podLabels: {}

podDisruptionBudget:
  enabled: true               # chỉ render khi replicaCount > 1
  minAvailable: 1

networkPolicy:
  enabled: false
  ingressFrom: []             # [{namespaceSelector: …}] nguồn được vào cổng 8080; rỗng = mọi nơi
  egress:                     # chỉ áp khi enabled
    mariadb: { cidr: 10.29.19.52/32, port: 3306 }
    kyuubi:  { namespace: kyuubi, podLabels: { app.kubernetes.io/name: kyuubi }, port: 10099 }
    keycloak: { cidr: 10.29.19.56/32, port: 32004 }
    ldap: { cidr: "", port: 636 }
    dns: true
```

`values.schema.json` khai báo kiểu và `enum` cho: `topology`, `auth.mode`, `service.type`,
`ldap.trustStore.type`, `image.pullPolicy`; kiểu số cho `replicaCount`, `port`; `pattern` cho các giá
trị thời gian (`^[0-9]+(ms|s|m|h|d)$`) và kích thước (`^[0-9]+(Ki|Mi|Gi)?$`).

---

## 3. ConfigMap `application.yaml`

Render từ values thành **một** file `application.yaml`, mount tại `/etc/notebook/application.yaml`.
Cả hai JVM chạy với `--spring.config.additional-location=file:/etc/notebook/application.yaml`.
Cổng/URL riêng từng JVM vẫn truyền bằng đối số dòng lệnh trong `entrypoint.sh` như hiện tại. Đối số
dòng lệnh thắng file cấu hình, nên không xung đột.

```yaml
spring:
  datasource:
    url: jdbc:mariadb://{{ host }}:{{ port }}/{{ database }}?{{ params }}
    username: {{ username }}
    hikari: { maximum-pool-size: {{ maxPoolSize }} }
server:
  forward-headers-strategy: native
logging:
  level: { root: {{ logLevel }} }
notebook:
  publicUrl: {{ publicUrl }}
  admins: {{ toJson admins }}
  auth:
    mode: {{ mode }}
    allowUnverified: {{ allowUnverified }}
    # chỉ render khối của mode đang chọn
    oidc: { issuer, clientId, scopes, usernameClaim, cacheTtl, serviceClientId? }
    sessionTtl / recheck            # khi no-oidc | ldap
    ldap: { … }                     # khi ldap; trustStore path = /etc/notebook/ldap/truststore
  max: { cells, page-size }
  cell.source.max-size, document.max-size, import.max-size, revision.max-per-notebook
  results.max-rows, events.pool-size, events.max-wait
kyuubi:
  rest-url, connect-timeout, request-timeout, idle-session-timeout, idle-reaper-interval,
  pin-to-instance, allowed-session-config-keys, session-configs (= extraSessionConfigs)
```

Quy tắc:
- Kích thước kiểu `1Mi` đổi sang byte bằng helper (`notebook.bytes`) trước khi ghi.
- Tên key trong file phải **khớp đúng** `@ConfigurationProperties`/`@Value` hiện có của hai service.
  Agent đối chiếu từng key với code. Key nào chưa có trong code thì ghi vào `plan/round3-notes.md`,
  **không** tự đặt tên mới.
- ConfigMap **không** chứa giá trị bí mật nào.

Deployment có annotation `checksum/config: {{ include (print $.Template.BasePath "/configmap.yaml") . | sha256sum }}`
để đổi cấu hình là pod rollout.

---

## 4. Secret (vận hành tạo trước)

Một Secret `existingSecret` (mặc định `notebook-secrets`), cùng namespace với release:

| Key | Bắt buộc khi | Vào container dưới dạng |
|---|---|---|
| `db-password` | luôn | env `SPRING_DATASOURCE_PASSWORD` |
| ~~`internal-token`~~ | **không cần** với `allInOne` | `entrypoint.sh` tự sinh mỗi lần container khởi động (mục 4.1). Chỉ cần khi `topology: split` |
| `auth-encryption-key` | `no-oidc`, `ldap` | env `NOTEBOOK_AUTH_ENCRYPTION_KEY` (32 byte base64) |
| `oidc-client-secret` | `oidc` + `serviceAccount.enabled` | env `NOTEBOOK_OIDC_CLIENT_SECRET` |
| `ldap-manager-password` | `ldap` + `userSearchFilter` | env `NOTEBOOK_LDAP_MANAGER_PASSWORD` |
| `ldap-truststore` | `ldap` + `trustStore.enabled` | file `/etc/notebook/ldap/truststore` (volume `secret`, `items` chỉ key này, `defaultMode: 0440`) |
| `ldap-truststore-password` | `ldap` + `trustStore.enabled` + type ≠ PEM | env `NOTEBOOK_LDAP_TRUSTSTORE_PASSWORD` |

Chart **chỉ** tham chiếu key của mode đang chọn. `secretKeyRef` **không** dùng `optional: true` cho key
bắt buộc: thiếu key thì pod `CreateContainerConfigError`, lỗi lộ ngay.

Tạo trên Kubernetes Dashboard (không dùng CLI):
1. Chọn namespace → nút **+** → **Create from input**, dán YAML có `stringData` (giá trị thường).
2. `ldap-truststore` là file nhị phân: mã hóa base64 rồi dán vào khối `data:` (không phải `stringData`).
3. Sinh `auth-encryption-key` (chỉ `no-oidc`/`ldap`) **một lần** trên máy cá nhân bằng `openssl rand -base64 32`, rồi giữ cố định. Kubernetes/Dashboard không tự sinh giá trị Secret; Helm `randAlphaNum` + `lookup` không dùng được vì hệ thống deploy render chart (không `helm upgrade`), `lookup` trả rỗng → khóa đổi mỗi lần đồng bộ, đăng xuất mọi người.

Mẫu tối thiểu cho `mode=oidc`:

```yaml
apiVersion: v1
kind: Secret
metadata: { name: notebook-secrets, namespace: <ns> }
type: Opaque
stringData:
  db-password: "<mật khẩu user notebook_api>"
```

Với mode `oidc` (mặc định), đây là **toàn bộ** Secret.

### 4.1 Token nội bộ giữa hai service (không cấu hình)

Giống Jupyter server ↔ kernel: hai service chạy **cùng container**, gọi nhau qua `127.0.0.1`, nên
token nội bộ do `entrypoint.sh` tự sinh lúc khởi động (`head -c 32 /dev/urandom | base64`), export
`NOTEBOOK_INTERNAL_TOKEN` cho cả hai JVM. Token không rời pod, mỗi pod một token, restart sinh lại,
không cần đồng bộ giữa replica vì execution-service chỉ gọi notebook-service **trong cùng pod**
(`notebook.notebook-service-url=http://127.0.0.1:8084`, đã có trong `entrypoint.sh`).

Điều kiện để cách này an toàn (đưa vào H-5):
- nginx chặn `/internal/` từ ngoài (đã có `return 403`).
- notebook-service chỉ nhận `/internal/**` từ `127.0.0.1`: thêm kiểm `request.getRemoteAddr()` là
  loopback trong `InternalTokenFilter` (C18), ngoài kiểm token.
- Nếu biến `NOTEBOOK_INTERNAL_TOKEN` được truyền từ ngoài thì dùng giá trị đó (để `topology: split`
  sau này truyền qua Secret), không thì tự sinh.

### 4.2 Vì sao `auth-encryption-key` không tự sinh như token nội bộ

Khóa này mã hóa mật khẩu người dùng trong bảng `notebook_auth_sessions` ở MariaDB **dùng chung**.
Đăng nhập ở pod A, request sau có thể vào pod B → mọi pod phải cùng khóa, và khóa phải sống qua
restart. Tự sinh theo pod sẽ làm phiên chỉ dùng được ở đúng pod tạo ra nó và mất khi restart. Vì vậy
khóa tạo **một lần** trong Secret. Mode `oidc` không dùng khóa này.

---

## 5. Validate lúc render (`_helpers.tpl` → `notebook.validate`, gọi đầu `deployment.yaml`)

| Điều kiện | Thông báo `fail` |
|---|---|
| `topology` ≠ `allInOne` | `topology=split is not implemented yet` |
| `auth.mode` ∉ {oidc, no-oidc, ldap} | `auth.mode must be oidc, no-oidc or ldap` |
| `existingSecret`, `mariadb.host`, `kyuubi.restUrl`, `publicUrl` rỗng | `<key> is required` |
| `mode=oidc` và `auth.oidc.issuer` rỗng | `auth.oidc.issuer is required for mode=oidc` |
| `mode=ldap` và `auth.ldap.url` rỗng | `auth.ldap.url is required for mode=ldap` |
| `mode=ldap` và không đúng một trong `userDnPattern`/`userSearchFilter` | `set exactly one of auth.ldap.userDnPattern or userSearchFilter` |
| `userSearchFilter` có mà `managerDn` rỗng | `auth.ldap.managerDn is required with userSearchFilter` |
| `ldap.url` bắt đầu `ldap://` và `startTls=false` | `plain ldap:// requires auth.ldap.startTls=true` |
| `ldap.url` bắt đầu `ldaps://` và `startTls=true` | `auth.ldap.startTls must be false with ldaps:// (already TLS)` |
| `mode=ldap` và `usernameAttribute` rỗng | `auth.ldap.usernameAttribute is required for mode=ldap` |
| `kyuubi.extraSessionConfigs` có key `kyuubi.engine.spark.output.mode` | `output.mode must stay at Kyuubi default (AUTO)` |
| `ingress.enabled` và `ingress.host` rỗng | `ingress.host is required` |
| `service.type=NodePort` và `nodePort` ngoài 30000–32767 | `service.nodePort must be in 30000-32767` |
| `publicUrl` bắt đầu `http://` và `ingress.tls` không rỗng | `publicUrl must be https:// when ingress.tls is set` |
| `service.type=NodePort`, `ingress.enabled=false`, `publicUrl` không kết thúc `:<nodePort>` | `publicUrl must use service.nodePort (<n>) when exposing via NodePort` (mục 11.3) |

Lớp kiểm thứ hai vẫn là `AuthPropertiesValidator` trong ứng dụng (B1). Chart kiểm sớm, ứng dụng kiểm chắc.

---

## 6. Deployment (`topology: allInOne`)

| Phần | Thiết kế |
|---|---|
| Strategy | `RollingUpdate`, `maxUnavailable: 0`, `maxSurge: 1` |
| `serviceAccountName` | SA của chart, `automountServiceAccountToken: false` (ứng dụng không gọi K8s API) |
| Container | 1 container `notebook`, cổng `http: 8080` (nginx). Không expose 8081–8085 ra Service |
| `args`/env | Env chỉ có secret (mục 4) + `JAVA_OPTS_NOTEBOOK`, `JAVA_OPTS_EXECUTION` từ `jvm.*` + `NOTEBOOK_CONFIG=/etc/notebook/application.yaml` + `extraEnv`. `entrypoint.sh` đọc 3 biến này |
| Volume | `config` (ConfigMap → `/etc/notebook/application.yaml`, `subPath`); `tmp` (`emptyDir` → `/tmp`, dùng cho nginx temp/pid và JVM vì `readOnlyRootFilesystem`); `ldap-truststore` (khi bật) |
| `startupProbe` | `exec`: `/app/healthcheck.sh startup`, `periodSeconds: 5`, `failureThreshold: 60` (5 phút cho Flyway + 2 JVM) |
| `readinessProbe` | `exec`: `/app/healthcheck.sh readiness`, mỗi 5 s, `failureThreshold: 3` |
| `livenessProbe` | `exec`: `/app/healthcheck.sh liveness`, mỗi 10 s, `failureThreshold: 3` |
| `terminationGracePeriodSeconds` | 40 (long-poll `/events` tối đa 10 s + đóng session Kyuubi) |
| `lifecycle.preStop` | `exec: sleep 5` để Service rút endpoint trước khi nginx dừng |
| Anti-affinity | `affinity` rỗng → `preferredDuringScheduling` tách pod theo `kubernetes.io/hostname` |
| Annotation | `checksum/config` (mục 3) |

`/app/healthcheck.sh` (thêm vào image, thay probe HTTP đi qua nginx — sửa C5):

```bash
#!/usr/bin/env sh
# $1 = startup | readiness | liveness
p=$1; [ "$p" = startup ] && p=readiness
curl -fsS -m 2 "http://127.0.0.1:8085/actuator/health/$p" >/dev/null &&
curl -fsS -m 2 "http://127.0.0.1:8083/actuator/health/$p" >/dev/null &&
curl -fsS -m 2 "http://127.0.0.1:8080/nginx-health" >/dev/null
```

`nginx.conf` thêm `location = /nginx-health { access_log off; return 200; }`. Cả hai service bật
`management.endpoint.health.probes.enabled: true` và nhóm `readiness` gồm `db`. Kyuubi **không**
nằm trong readiness: Kyuubi chết thì notebook vẫn mở/sửa được, chỉ chạy cell lỗi. Nếu để Kyuubi trong
readiness, mọi pod sẽ NotReady cùng lúc.

### Thay đổi image đi kèm (đã có ở C1, C6 vòng 3; nhắc lại cho khớp chart)

- `entrypoint.sh`: giữ việc tự sinh `NOTEBOOK_INTERNAL_TOKEN` khi không được truyền vào (mục 4.1), nhưng sinh từ `/dev/urandom` 32 byte và **không in token ra log**. Đọc `NOTEBOOK_CONFIG`, `JAVA_OPTS_*`.
  Dùng `exec` cho tiến trình giám sát; nhận `SIGTERM` thì dừng nginx (`quit`) trước, chờ 2 JVM thoát.
- `Dockerfile`: `USER 10001`. nginx `pid /tmp/nginx.pid`, `client_body_temp_path`, `proxy_temp_path`…
  đều trỏ vào `/tmp`. Thư mục log ghi ra stdout/stderr (`access_log /dev/stdout`, `error_log /dev/stderr`).
- Thêm `/app/healthcheck.sh`.

---

## 7. Tài nguyên khác

| File | Thiết kế |
|---|---|
| `service.yaml` | Một Service, cổng `http` 8080 → `targetPort: http`. `NodePort` thì gắn `nodePort` khi có giá trị. Không `sessionAffinity`: ứng dụng stateless (phiên ở MariaDB, token ở trình duyệt) |
| `ingress.yaml` | Khi `enabled`: một rule `host`, path `/` `Prefix` → Service. Annotation mặc định cho nginx-ingress: `proxy-read-timeout: "120"`, `proxy-body-size: "20m"` (import 16 MB). Ghép thêm `ingress.annotations` |
| `pdb.yaml` | Khi `podDisruptionBudget.enabled` **và** `replicaCount > 1`: `minAvailable` |
| `networkpolicy.yaml` | Khi `enabled`: ingress cổng 8080 từ `ingressFrom` (rỗng = mọi nơi); egress tới MariaDB, Kyuubi, Keycloak (khi oidc), LDAP (khi ldap, `cidr` có giá trị), DNS (UDP/TCP 53 tới `kube-system`). Mặc định tắt vì không biết CNI của cluster có hỗ trợ không |
| `serviceaccount.yaml` | Một SA, `automountServiceAccountToken: false`. Không Role/RoleBinding |
| `NOTES.txt` | Mục 8 |

**`topology: split` (để sau, không làm vòng này).** Giữ chỗ cho 3 Deployment `nb-frontend`,
`nb-notebook-service`, `nb-execution-service` dùng 3 image ở `scripts/build-images.sh`. Khi làm cần:
Service nội bộ cho hai backend, `notebook.notebook-service-url` trỏ Service, frontend nginx route theo
`docker/nginx.conf`, readiness execution chờ notebook-service. Các value ở mục 2 giữ nguyên tên, chỉ
thêm `split.*` cho replica/resources riêng.

---

## 8. `NOTES.txt` (checklist sau deploy)

In ra theo mode:

1. Secret `{{ existingSecret }}` phải có các key: *(liệt kê đúng theo mode)*.
2. User MariaDB `{{ mariadb.username }}` có quyền trên `{{ mariadb.database }}.*` (liệt kê quyền ngay trong NOTES;
   câu `GRANT` mẫu ở `notebook-helm/README.md`).
3. `mode=oidc`: client Keycloak `{{ clientId }}` là **public**, *Valid redirect URIs* có
   `{{ publicUrl }}/auth/callback`, *Web origins* có `{{ publicUrl }}`, có audience mapper `kyuubi`.
4. `serviceAccount.enabled`: Kyuubi có `hadoop.proxyuser.<user của service account>.users/hosts`.
5. Kiểm nhanh:
   - mở `{{ publicUrl }}/api/v1/auth/config` phải trả JSON có `"mode":"{{ mode }}"`;
   - Dashboard → Pod → Logs có dòng Flyway `Successfully applied` hoặc `Schema … is up to date`.

---

## 9. Việc phải làm

| # | Ở đâu | Làm gì | Xong khi |
|---|---|---|---|
| H-1 | `notebook-helm/` | Viết lại theo mục 1–8. Xóa khối `oidc:` cũ, `secrets.*` cũ. | `helm lint` sạch |
| H-2 | `values.schema.json` | Theo mục 2 | `helm template --set auth.mode=abc` lỗi tại schema |
| H-3 | `_helpers.tpl` | `notebook.validate` đủ 13 điều kiện mục 5; `notebook.bytes`; `notebook.labels`, `notebook.selectorLabels` theo `app.kubernetes.io/*` | Mỗi điều kiện có một ca render lỗi trong test (H-7) |
| H-4 | `configmap.yaml` | Theo mục 3; key khớp code | Đối chiếu từng key với `@ConfigurationProperties`/`@Value`, ghi bảng đối chiếu vào PR |
| H-5 | Image | `entrypoint.sh`, `Dockerfile`, `nginx.conf`, `healthcheck.sh` theo mục 6 | `docker run` không có `NOTEBOOK_INTERNAL_TOKEN` → vẫn chạy, hai JVM dùng cùng token tự sinh; gọi `/internal/**` từ ngoài container → 403; 3 tiến trình chạy dưới uid 10001, `healthcheck.sh readiness` = 0 |
| H-6 | Tài liệu | `notebook-helm/README.md` (bảng values, mẫu Secret cho 3 mode, NodePort/Ingress); `plan/Chart.md` thêm dòng đầu "Lỗi thời, xem `plan/chart-design.md`"; `DOCKER.md` bỏ phần deploy | Không còn nhắc ArgoCD/Vault/PostgreSQL trong `notebook-helm/` và `DOCKER.md` |
| H-7 | `notebook-helm/ci/` + script | Ba file `ci/oidc-values.yaml`, `ci/no-oidc-values.yaml`, `ci/ldap-values.yaml` (dùng bởi `helm lint`/`template`); `scripts/helm-check.sh` chạy bằng Docker `alpine/helm:3.14.4`: `lint` + `template` 3 file hợp lệ, và 13 ca sai (mục 5) phải lỗi đúng thông báo | Script thoát 0 |

Kiểm render 3 mode, không cần cluster:

```bash
docker run --rm -v "$PWD/notebook-helm":/c alpine/helm:3.14.4 template t /c -f /c/ci/oidc-values.yaml
```

Kết quả mong đợi theo mode:

| Mode | Env secret trong Deployment | Khối trong ConfigMap |
|---|---|---|
| `oidc` | `SPRING_DATASOURCE_PASSWORD` (+ `NOTEBOOK_OIDC_CLIENT_SECRET` khi `serviceAccount.enabled`) | `auth.oidc` |
| `no-oidc` | như trên + `NOTEBOOK_AUTH_ENCRYPTION_KEY` | `auth.sessionTtl/recheck` |
| `ldap` | như `no-oidc` + `NOTEBOOK_LDAP_MANAGER_PASSWORD` (khi `userSearchFilter`) + volume truststore (khi bật) | `auth.ldap`, `auth.sessionTtl/recheck` |

Thứ tự trong vòng 3: thay bước 7 của `huong-dan-sua-vong-3.md` (C1–C6) bằng H-1 → H-9 (H-8, H-9 ở
mục 10, 11). H-5 làm cùng C1, C6.

---

## 10. Trước khi đóng gói image (cổng chặn build)

Image chỉ được build khi **mọi bước dưới đây xanh**. Gom vào một script
`scripts/build-all-in-one.sh` (viết lại), dừng ngay ở bước đầu tiên lỗi (`set -euo pipefail`).
Script chạy được trên máy sạch chỉ cần **Docker** (không cần JDK, Maven, Helm cài sẵn), vì máy
hiện tại chỉ có Java 8 và không có Maven.

### 10.1 Các bước

| Bước | Làm gì | Công cụ (chạy trong Docker) | Dừng khi |
|---|---|---|---|
| P0 | **Kiểm đầu vào**: working tree sạch (`git status --porcelain` rỗng) hoặc có cờ `--allow-dirty`; đọc `VERSION` = `appVersion` trong `notebook-helm/Chart.yaml`; tag image = `VERSION` (+ `-<git short sha>` khi dirty) | `git` | Tree bẩn mà không có cờ; `VERSION` rỗng |
| P1 | **Build + test backend**: `mvn -B -ntp clean verify` (gồm unit + `*IT` Testcontainers MariaDB) | `maven:3.9.9-eclipse-temurin-17`, mount `docker.sock` | Bất kỳ test đỏ. **Không có** `-DskipTests` |
| P2 | **Kiểm jar**: mỗi service ra **đúng một** `target/<svc>-<ver>.jar` (fat jar Spring Boot, có `BOOT-INF/`); ghi SHA-256 vào `dist/checksums.txt` | `unzip -l`, `sha256sum` | 0 hoặc > 1 jar; jar không phải fat jar |
| P3 | **Kiểm frontend**: `frontend/public/index.html` tồn tại; mọi `<script src>`/`<link href>` là đường dẫn **tương đối** tới `vendor/` (không CDN, plan cấm); file vendor khớp checksum trong `frontend/public/vendor/README.md` | `grep`, `sha256sum` | Có `http(s)://` trong `src`/`href`; checksum lệch |
| P4 | **Kiểm chart**: `helm lint` + `helm template` 3 file `ci/*-values.yaml` + 13 ca lỗi (H-7) | `alpine/helm:3.14.4` | Lint lỗi; ca hợp lệ không render; ca lỗi lại render được |
| P5 | **Kiểm hợp đồng tĩnh** (X1 trong `chart-image-contract.md`): khóa ConfigMap render ↔ khóa code đọc | script so danh sách | Có khóa mồ côi |
| P6 | **Kiểm cấu hình image**: `docker/entrypoint.sh`, `docker/healthcheck.sh` qua `shellcheck`; `docker/nginx.conf` qua `nginx -t` | `koalaman/shellcheck:v0.10.0`, `nginx:1.27` | Lỗi cú pháp |
| P7 | **Build image**: `docker build --label notebook.contract=2 --label org.opencontainers.image.version=$VERSION --label org.opencontainers.image.revision=<git sha> -t <repo>:$VERSION .` | `docker` | Build lỗi |
| P8 | **Kiểm image chạy với chart** (X2–X5): MariaDB container + image vừa build, chạy như pod (`--user 10001 --read-only --tmpfs /tmp`), ConfigMap render từ `ci/oidc-values.yaml`; Kyuubi giả bằng WireMock trả `/api/v1/ping` 200 | `mariadb:10.11`, `wiremock/wiremock:3.9.1` | `healthcheck.sh readiness` ≠ 0 sau 3 phút; `/api/v1/auth/config` sai; cổng nội bộ lộ; `docker stop` > 40 s |
| P9 | **Xuất kết quả**: `dist/build-report.md` (phiên bản, git sha, số test, checksum jar, digest image, kết quả P1–P8). **Không push** image; in lệnh `docker push` để người chạy tự quyết | — | — |

Cờ của script:
- `--allow-dirty`: build khi tree bẩn (tag có hậu tố sha, report ghi rõ).
- `--skip-runtime-check`: bỏ P8 khi máy không chạy được container lồng nhau. Report ghi
  `P8: SKIPPED`, và **không** được dùng cho bản phát hành.

Không có cờ bỏ qua P1 (test). Đây là bài học từ vòng trước: tài liệu cũ build bằng `-DskipTests`.

### 10.2 Dọn đi kèm

- `.dockerignore`: chỉ cho vào context những gì `Dockerfile` cần:
  `backend/*/target/*.jar`, `frontend/public/**`, `docker/**`. Mặc định loại hết (`*`) rồi `!` từng
  mục, tránh đưa `plan/`, `.git`, mã nguồn vào context.
- `Dockerfile`: `COPY` jar theo **tên chính xác** có `VERSION` (truyền bằng `--build-arg VERSION`),
  không dùng glob `*-*.jar` (glob khớp nhiều file thì `COPY` lỗi khó hiểu).
- Xóa `scripts/build-dist.sh` (đang mô tả `nb-document-service`, tên cũ) và `scripts/build-images.sh`
  (3 image cho `topology: split`, chưa dùng). Khi làm `split` thì viết lại.
- `BUILD.md`, `DOCKER.md` chỉ còn: yêu cầu (Docker), một lệnh `./scripts/build-all-in-one.sh`,
  bảng P0–P9, cách đọc `dist/build-report.md`, cách push.

---

## 11. Mở NodePort để vào web UI

### 11.1 Luồng truy cập

```
Trình duyệt ──▶ http://<IP node>:32380 ──▶ Service notebook (NodePort 32380 → port 8080)
            ──▶ Pod: nginx :8080 ──┬─ /            → web UI (frontend/public)
                                   ├─ /api/v1/...  → notebook-service 127.0.0.1:8084 / execution-service 127.0.0.1:8082
                                   └─ /internal/   → 403
```

Chỉ **một** cổng mở ra ngoài: 8080 của nginx, qua NodePort. Cổng 8082–8085 (hai JVM, actuator) chỉ
nghe `127.0.0.1` trong pod, **không** có trong Service.

### 11.2 Values

```yaml
service:
  type: NodePort
  port: 8080
  nodePort: 32380             # cố định để URL không đổi giữa các lần deploy
  externalTrafficPolicy: Cluster   # Cluster: vào được qua IP của MỌI node; Local: chỉ node có pod, giữ IP client
  annotations: {}

publicUrl: http://10.29.19.56:32380   # đúng địa chỉ người dùng gõ trên trình duyệt
```

Quy tắc chọn `nodePort`:
- Trong dải `30000–32767` (validate ở mục 5).
- **Không trùng** cổng đang dùng. Trên cluster này đã thấy: `30332`, `30335`, `30399`, `32004`
  (Keycloak), `32369`, `32374`, `32376`. Kiểm trên Dashboard: **Service** → lọc mọi namespace → cột
  *Internal endpoints* / *External endpoints*. Trùng thì Kubernetes từ chối tạo Service với lỗi
  `provided port is already allocated`.
- `32380` (từ README) chưa thấy trùng, giữ làm mặc định.

`templates/service.yaml`:

```yaml
spec:
  type: {{ .Values.service.type }}
  {{- if eq .Values.service.type "NodePort" }}
  externalTrafficPolicy: {{ .Values.service.externalTrafficPolicy }}
  {{- end }}
  ports:
    - name: http
      port: {{ .Values.service.port }}
      targetPort: http
      protocol: TCP
      {{- if and (eq .Values.service.type "NodePort") .Values.service.nodePort }}
      nodePort: {{ .Values.service.nodePort }}
      {{- end }}
```

### 11.3 `publicUrl` phải khớp NodePort

`publicUrl` không chỉ để hiển thị. Ứng dụng dùng nó cho:

| Dùng cho | Sai thì |
|---|---|
| `redirectUri` OIDC = `publicUrl + /auth/callback` | Keycloak báo `Invalid parameter: redirect_uri` |
| Cờ cookie `Secure` (`https://` → bật) | `http://` mà bật `Secure` → cookie không được lưu, đăng nhập `no-oidc`/`ldap` lặp |
| Link trong `NOTES.txt` | Người vận hành mở sai địa chỉ |

Validate thêm ở mục 5: `service.type=NodePort` và `ingress.enabled=false` thì `publicUrl` phải kết
thúc bằng `:<service.nodePort>`; sai → `fail "publicUrl must use service.nodePort (<n>) when exposing via NodePort"`.

### 11.4 Keycloak cho địa chỉ NodePort (mode `oidc`)

Realm `lakehouse` → Clients → `kyuubi-notebook`:
- **Valid redirect URIs**: `http://10.29.19.56:32380/auth/callback`
- **Valid post logout redirect URIs**: `http://10.29.19.56:32380/*`
- **Web origins**: `http://10.29.19.56:32380`

Lưu ý: SPA lấy token qua **`http://`** là chấp nhận được cho môi trường thử nghiệm. Keycloak đang chạy
`https://10.29.19.56:32004` với chứng chỉ tự ký, nên người dùng phải mở và chấp nhận chứng chỉ Keycloak
một lần trên trình duyệt, nếu không trang đăng nhập không tải. Ghi điều này vào `NOTES.txt` và README.
Khi có Ingress + TLS thì chuyển sang `ingress.enabled=true`, `service.type=ClusterIP`, `publicUrl`
`https://…`.

### 11.5 Chuyển sang Ingress sau này

| Values | NodePort (hiện tại) | Ingress |
|---|---|---|
| `service.type` | `NodePort` | `ClusterIP` |
| `ingress.enabled` | `false` | `true` |
| `publicUrl` | `http://10.29.19.56:32380` | `https://notebook.<domain>` |
| Keycloak redirect | theo NodePort | theo domain |

Chart không cấm bật cả hai (NodePort + Ingress) để chuyển dần, nhưng `publicUrl` chỉ được là **một**
địa chỉ. Đăng nhập OIDC chỉ chạy ở địa chỉ đó.

### 11.6 Kiểm sau deploy (thêm vào `NOTES.txt`)

1. Dashboard → **Service** `<release>` → *External endpoints* có `<IP node>:32380`.
2. Mở `{{ publicUrl }}`: thấy giao diện notebook (không phải trang lỗi nginx).
3. Mở `{{ publicUrl }}/api/v1/auth/config`: JSON có `"mode"` đúng values.
4. Mở `{{ publicUrl }}/internal/x`: phải **403**. Mở `{{ publicUrl }}/actuator/health`: phải **404**
   (actuator không lộ ra ngoài).
5. Không vào được bước 1–2: kiểm firewall giữa máy người dùng và node (cổng 32380 TCP), và Pod Ready
   (Dashboard → Pod → Conditions).

### 11.7 Việc phải làm (bổ sung mục 9)

| # | Ở đâu | Làm gì | Xong khi |
|---|---|---|---|
| H-8 | `scripts/build-all-in-one.sh`, `.dockerignore`, `Dockerfile`, `BUILD.md`, `DOCKER.md` | Mục 10 | Máy sạch chỉ có Docker: script chạy hết P0–P9, ra `dist/build-report.md`; cố tình làm đỏ một test → dừng ở P1, không có image |
| H-9 | `values.yaml`, `service.yaml`, `_helpers.tpl`, `NOTES.txt`, README chart | Mục 11 (`externalTrafficPolicy`, validate `publicUrl` ↔ `nodePort`, checklist) | `helm template` với `publicUrl` sai cổng → lỗi đúng thông báo; render đúng thì Service có `nodePort: 32380` |
