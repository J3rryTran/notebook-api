# Hợp đồng chart ↔ image ↔ code (contract 2)

Ngày: 2026-10-15. Đi kèm `plan/chart-design.md`. Đối tượng: AI agent sửa code và người nghiệm thu.

**Nguyên tắc: chart, image và code là một khối.** Chart mới truyền cấu hình theo cách khác chart
hiện tại (file ConfigMap thay cho env, probe bằng script, chạy non-root). Ghép chart mới với image cũ
hoặc ngược lại thì **pod không chạy**. Vì vậy:

1. Sửa cả ba trong **cùng một PR/commit nhóm**, không deploy riêng lẻ.
2. Mỗi lần đổi hợp đồng (bảng ở mục 1) thì tăng **cả** `Chart.yaml: version` lẫn `appVersion`, và tag
   image đúng bằng `appVersion`. Chart chỉ dùng image cùng `appVersion` (`image.tag` để rỗng).
3. Ghi phiên bản hợp đồng vào image: `LABEL notebook.contract="2"` trong `Dockerfile`, và
   `entrypoint.sh` in `contract=2` khi khởi động. Chart mới tương ứng `contract: 2` trong
   `Chart.yaml` (annotation). Chart cũ/image cũ hiện tại coi là contract 1.

---

## 1. Hợp đồng (contract 2)

Chart **cung cấp** → image/code **phải đọc đúng** như sau:

| # | Chart cung cấp | Image/code nhận | Hiện tại (contract 1) | Phải sửa |
|---|---|---|---|---|
| K1 | File `/etc/notebook/application.yaml` (ConfigMap) | env `NOTEBOOK_CONFIG=/etc/notebook/application.yaml`; `entrypoint.sh` thêm `--spring.config.additional-location=file:$NOTEBOOK_CONFIG` cho **cả hai** JVM | Không có; cấu hình qua env rời | `entrypoint.sh` |
| K2 | Khóa trong `application.yaml` (bảng mục 2) | Code đọc **đúng tên khóa** đó | Tên khóa lẫn lộn camelCase/kebab-case, vài khóa chưa có trong code | Java + `application.yml` (mục 2) |
| K3 | env `JAVA_OPTS_NOTEBOOK`, `JAVA_OPTS_EXECUTION` | `entrypoint.sh` chèn vào `java $JAVA_OPTS_… -jar …` | `java -jar` không tham số JVM | `entrypoint.sh` |
| K4 | env secret: `SPRING_DATASOURCE_PASSWORD`, `NOTEBOOK_AUTH_ENCRYPTION_KEY`, `NOTEBOOK_OIDC_CLIENT_SECRET`, `NOTEBOOK_LDAP_MANAGER_PASSWORD`, `NOTEBOOK_LDAP_TRUSTSTORE_PASSWORD` | `application.yml` trong jar ánh xạ từng env vào khóa ở mục 2 bằng placeholder `${ENV:}` | Chỉ có 2 biến đầu; 3 biến LDAP/OIDC chưa map | `application.yml` cả hai service |
| K5 | **Không** truyền `NOTEBOOK_INTERNAL_TOKEN` | `entrypoint.sh` tự sinh khi thiếu (32 byte `/dev/urandom`, không in ra log), export cho cả hai JVM | Tự sinh bằng `head /dev/urandom \| tr`, in câu thông báo | `entrypoint.sh` |
| K6 | `exec` probe gọi `/app/healthcheck.sh startup\|readiness\|liveness` | Image có file này, kiểm `:8085` (notebook), `:8083` (execution), `:8080/nginx-health` | Không có file; probe HTTP qua nginx chỉ tới notebook-service | `Dockerfile`, thêm `docker/healthcheck.sh`, `nginx.conf` |
| K7 | Endpoint `/actuator/health/readiness`, `/liveness` trên 8085 và 8083 | Cả hai service bật `management.endpoint.health.probes.enabled: true`, nhóm `readiness` gồm `readinessState,db`; `liveness` gồm `livenessState` | Chỉ tự bật khi Spring phát hiện chạy trên K8s; không có nhóm `db` | `application.yml` cả hai service |
| K8 | `securityContext`: `runAsUser 10001`, `readOnlyRootFilesystem: true`; `emptyDir` tại `/tmp` | Image chạy được dưới uid 10001 và chỉ ghi vào `/tmp` | Chạy root; nginx ghi `/var/lib/nginx`, `/var/log/nginx`, `/run/nginx.pid` | `Dockerfile`, `nginx.conf` (mục 3) |
| K9 | Container port `http: 8080`; `preStop: sleep 5`; `terminationGracePeriodSeconds: 40` | nginx lắng nghe 8080; `entrypoint.sh` nhận `SIGTERM` → `nginx -s quit` → `kill -TERM` 2 JVM → `wait` | Đã có trap nhưng vòng giám sát `sleep 2` chạy foreground nên `SIGTERM` chỉ được xử lý sau tối đa 2 s; chấp nhận được, chỉ cần `wait` đúng | `entrypoint.sh` (nhỏ) |
| K10 | nginx nhận `X-Forwarded-Proto` từ Ingress | nginx chuyển tiếp `X-Forwarded-Proto $http_x_forwarded_proto` (nếu có) hoặc `$scheme`; Spring `server.forward-headers-strategy: native` | Không có | `nginx.conf`, `application.yml` |
| K11 | Không mount ServiceAccount token | Code không gọi K8s API | Đúng | — |
| K12 | Volume truststore `/etc/notebook/ldap/truststore` khi bật | Code đọc đường dẫn từ khóa `notebook.auth.ldap.trustStore` | Chưa hỗ trợ (B3 vòng 3) | Java (B3) |

---

## 2. Bảng khóa cấu hình (K2, K4)

Quy ước **duy nhất**: khóa trong YAML viết **kebab-case**; Java đọc bằng `@ConfigurationProperties`
(Spring tự khớp camelCase ↔ kebab-case). **Không dùng `@Value` cho khóa có chữ hoa**: `@Value` không
khớp lỏng giữa `internalToken` và `internal-token`. Hiện code đang có cả `notebook.internalToken`
lẫn `notebook.internal-token`; gom về `notebook.internal-token`.

| Khóa (ConfigMap / `application.yml`) | Nguồn chart | Code hiện đọc | Việc |
|---|---|---|---|
| `spring.datasource.url/username` | `mariadb.*` | Spring | — |
| `spring.datasource.password` | env `SPRING_DATASOURCE_PASSWORD` | Spring | — |
| `spring.datasource.hikari.maximum-pool-size` | `mariadb.maxPoolSize` | Spring | — |
| `server.forward-headers-strategy` | cố định `native` | Spring | thêm (K10) |
| `logging.level.root` | `logLevel` | Spring | — |
| `notebook.public-url` | `publicUrl` | **chưa có** | thêm: dùng cho `redirectUri` trong `/auth/config` và quyết định cookie `Secure` (B2) |
| `notebook.admins` | `auth.admins` | `@Value("${notebook.admins}")` | giữ |
| `notebook.internal-token` | env tự sinh (K5) | lẫn `internalToken`/`internal-token` | gom tên |
| `notebook.encryption-key` | env `NOTEBOOK_AUTH_ENCRYPTION_KEY` | `@Value("${notebook.encryptionKey}")` | đổi tên khóa, `@ConfigurationProperties` |
| `notebook.auth.mode` | `auth.mode` | `AuthProperties` | — |
| `notebook.auth.allow-unverified` | `auth.allowUnverified` | `AuthProperties` (chưa dùng, C16) | — |
| `notebook.auth.username-claim` | `auth.oidc.usernameClaim` | `@Value("${notebook.auth.usernameClaim}")` | chuyển vào `AuthProperties` |
| `notebook.auth.cache-ttl` | `auth.oidc.cacheTtl` | `@Value("${notebook.auth.cacheTtl}")` | chuyển, kiểu `Duration` |
| `notebook.auth.session-ttl`, `recheck` | `auth.session.*` | `AuthProperties` (kiểu `String`) | kiểu `Duration` (B2) |
| `notebook.auth.oidc.issuer`, `client-id`, `scopes` | `auth.oidc.*` | `issuer` có; `clientId`/`scopes` đọc trong `AuthConfigController` bằng `@Value` với mặc định cứng | `AuthProperties.Oidc` |
| `notebook.auth.oidc.service-client-id` | `auth.oidc.serviceAccount.clientId` (chỉ khi `enabled`) | `@Value("${notebook.auth.oidc.serviceClientId}")` | đổi tên khóa |
| `notebook.auth.oidc.service-client-secret` | env `NOTEBOOK_OIDC_CLIENT_SECRET` | `@Value(…serviceClientSecret)` | đổi tên khóa, map env (K4) |
| `notebook.auth.ldap.*` (url, start-tls, base-dn, user-dn-pattern, user-search-base, user-search-filter, username-attribute, manager-dn, group-search-base, group-member-attribute, admin-group, connect-timeout, read-timeout, trust-store, trust-store-type) | `auth.ldap.*` | `AuthProperties.LdapProperties` (một phần) | bổ sung khóa thiếu |
| `notebook.auth.ldap.manager-password` | env `NOTEBOOK_LDAP_MANAGER_PASSWORD` | có | map env (K4) |
| `notebook.auth.ldap.trust-store-password` | env `NOTEBOOK_LDAP_TRUSTSTORE_PASSWORD` | **chưa có** | thêm (B3) |
| `notebook.max.cells`, `notebook.max.page-size` | `notebook.maxCells`, cố định 200 | có | — |
| `notebook.cell.source.max-size` | `notebook.cellSourceMaxSize` (byte) | có | — |
| `notebook.document.max-size` | `notebook.documentMaxSize` (byte) | **chưa có** | thêm (C20) |
| `notebook.import.max-size` | `notebook.importMaxSize` (byte) | có | — |
| `notebook.revision.max-per-notebook` | `notebook.maxRevisions` | có | — |
| `notebook.results.max-rows` | `notebook.resultsMaxRows` | có | — |
| `notebook.events.pool-size` | `notebook.eventsPoolSize` | `@Value("${notebook.events.poolSize}")` | đổi tên khóa (C12) |
| `notebook.events.max-wait` | `notebook.eventsMaxWait` | **chưa có** | thêm (C12) |
| `notebook.notebook-service-url` | **không** (entrypoint đặt `http://127.0.0.1:8084`) | có | — |
| `kyuubi.rest-url`, `pin-to-instance`, `allowed-session-config-keys` | `kyuubi.*` | có | — |
| `kyuubi.connect-timeout`, `request-timeout`, `session-open-timeout`, `idle-session-timeout` | `kyuubi.*` | **không thấy code đọc** | thêm vào `KyuubiRestClient` (timeout) và job đóng session rảnh; nếu chưa làm vòng này thì chart **không render** các khóa này, ghi `round3-notes.md` |
| `kyuubi.session-configs` | `kyuubi.extraSessionConfigs` | có | — |

Sau khi sửa: `application.yml` trong jar chỉ giữ **mặc định an toàn** và placeholder env secret.
Mọi giá trị môi trường đến từ ConfigMap. `application-dev.yml` giữ cho chạy local.

---

## 3. Thay đổi image cụ thể

**`Dockerfile`**
```dockerfile
LABEL notebook.contract="2"
RUN groupadd -g 10001 notebook && useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin notebook
COPY docker/healthcheck.sh /app/healthcheck.sh
RUN chmod 0755 /app/entrypoint.sh /app/healthcheck.sh
USER 10001
```

**`docker/nginx.conf`** (chạy được dưới uid 10001, root filesystem chỉ đọc):
- `pid /tmp/nginx.pid;`, `error_log /dev/stderr warn;`, `access_log /dev/stdout;`
- `client_body_temp_path /tmp/nginx-client;` và tương tự cho `proxy_temp_path`,
  `fastcgi_temp_path`, `uwsgi_temp_path`, `scgi_temp_path`.
- `location = /nginx-health { access_log off; return 200; }`
- Bỏ `location /actuator/` công khai (probe giờ gọi thẳng cổng nội bộ; actuator không nên lộ qua
  Service). Giữ `location /internal/ { return 403; }`.
- Mọi `proxy_pass` thêm `proxy_set_header X-Forwarded-Proto $fwd_proto;` với
  `map $http_x_forwarded_proto $fwd_proto { default $http_x_forwarded_proto; "" $scheme; }`.
- `client_max_body_size 20m;` (import 16 MB).

**`docker/entrypoint.sh`**
```bash
#!/usr/bin/env bash
set -euo pipefail
echo "[STARTUP] notebook contract=2"
: "${NOTEBOOK_INTERNAL_TOKEN:=$(head -c 32 /dev/urandom | base64 | tr -d '\n')}"
export NOTEBOOK_INTERNAL_TOKEN
CFG=()
[ -n "${NOTEBOOK_CONFIG:-}" ] && CFG=(--spring.config.additional-location="file:${NOTEBOOK_CONFIG}")
java ${JAVA_OPTS_NOTEBOOK:-} -jar /app/nb-notebook-service.jar "${CFG[@]}" \
  --server.address=127.0.0.1 --server.port=8084 --management.server.port=8085 --management.server.address=127.0.0.1 &
NB_PID=$!
java ${JAVA_OPTS_EXECUTION:-} -jar /app/nb-execution-service.jar "${CFG[@]}" \
  --server.address=127.0.0.1 --server.port=8082 --management.server.port=8083 --management.server.address=127.0.0.1 \
  --notebook.notebook-service-url=http://127.0.0.1:8084 &
EXEC_PID=$!
nginx -g 'daemon off;' &
NGINX_PID=$!
# trap SIGTERM: nginx quit → TERM 2 JVM → wait; vòng giám sát dùng `wait -n` thay vì sleep
```
Ghi chú:
- `--management.server.address=127.0.0.1`: actuator chỉ nghe trong pod.
- `--server.address=127.0.0.1`: hai JVM chỉ nghe trong pod, chỉ nginx mở 8080 ra ngoài. Đây là lớp
  bảo vệ chính cho `/internal/**` (K5): ngoài pod không ai tới được 8084.
- Không in giá trị token.

**`docker/healthcheck.sh`**: như `chart-design.md` §6.

---

## 4. Thay đổi code Java (ngoài các lỗi B*/C* đã có ở vòng 3)

| # | Ở đâu | Làm gì |
|---|---|---|
| J1 | `nb-common/.../AuthProperties.java` | Bổ sung mọi khóa `notebook.auth.*` ở mục 2 (oidc client-id/scopes/service-client-*, ldap đầy đủ, `Duration` cho ttl). Thêm `@Validated` nếu muốn, nhưng validator chính vẫn là `AuthPropertiesValidator` (B1). |
| J2 | Mọi `@Value` liệt kê ở mục 2 | Chuyển sang `AuthProperties` hoặc một `NotebookProperties` mới (`@ConfigurationProperties("notebook")`) cho `public-url`, `admins`, `internal-token`, `encryption-key`, `max.*`, `cell.*`, `document.*`, `import.*`, `revision.*`, `results.*`, `events.*`. Và `KyuubiProperties` (`"kyuubi"`). Sau J2: `grep -rn '@Value("\${notebook\|@Value("\${kyuubi' backend` rỗng. |
| J3 | `AuthConfigController` | `redirectUri` = `publicUrl + "/auth/callback"`; bỏ mặc định cứng `kyuubi-notebook`/`openid,profile` trong `@Value`. |
| J4 | `application.yml` cả hai service | Đổi tên khóa theo mục 2; map env secret (K4); bật probes + nhóm (K7); `forward-headers-strategy` (K10); bỏ giá trị môi trường (issuer, rest-url `localhost`) — để trống, ConfigMap điền. |
| J5 | `InternalTokenFilter` (C18) | Ngoài so token: từ chối nếu `request.getRemoteAddr()` không phải loopback. |
| J6 | `KyuubiRestClient` | Đọc `kyuubi.connect-timeout`, `request-timeout` cho `RestTemplate`; `session-open-timeout` cho chờ engine. Nếu không làm vòng này: ghi `round3-notes.md` và chart không render các khóa này. |

---

## 5. Kiểm khớp (thêm vào nghiệm thu)

| # | Kiểm | Cách | Đạt khi |
|---|---|---|---|
| X1 | Mọi khóa chart render đều có code đọc | Lấy danh sách khóa từ `helm template` (ConfigMap) và so với `@ConfigurationProperties` + `application.yml` | Không có khóa "mồ côi" ở cả hai phía |
| X2 | Image chạy với chart | Build image, rồi `docker run` mô phỏng pod: `--user 10001 --read-only --tmpfs /tmp`, mount `application.yaml` render từ `helm template -f ci/oidc-values.yaml`, chỉ truyền `SPRING_DATASOURCE_PASSWORD` + `NOTEBOOK_CONFIG` + MariaDB container | `healthcheck.sh readiness` = 0 trong 3 phút; `GET :8080/api/v1/auth/config` trả `mode=oidc`, `issuer` đúng giá trị values |
| X3 | Cổng nội bộ không lộ | Từ ngoài container: `curl :8084`, `:8085`, `:8080/internal/x`, `:8080/actuator/health` | Lần lượt: không kết nối, không kết nối, 403, 404 |
| X4 | Contract khớp | Log khởi động có `contract=2`; `docker inspect` label `notebook.contract=2`; `Chart.yaml` annotation `notebook/contract: "2"` | Cả ba bằng nhau |
| X5 | Dừng sạch | `docker stop` | Thoát trong < 40 s, log có hai JVM dừng |

Script `scripts/contract-check.sh` chạy X1–X5 bằng Docker (Helm, MariaDB, image vừa build).
