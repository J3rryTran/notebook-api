# Plan: Helm chart `notebook-helm` triển khai notebook rời lên Kubernetes

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent hoặc người vận hành.
Đặc tả tính năng: [`../API-detail.md`](../API-detail.md). Mô tả cho người dùng:
[`README.md`](README.md).

## 0. Phạm vi

- Chart **riêng**, release riêng, không là subchart của `kyuubi-helm`, **không sửa gì ở Kyuubi**.
- Chart chỉ dựng ứng dụng notebook B và cấu hình để B **gọi Kyuubi REST** (Kyuubi lo Spark engine).
- Không chứa Spark, không tạo pod Spark, không cần quyền Kubernetes nào.

### Nền tảng backend B

| Thành phần | Phiên bản | Nguồn version | Hệ quả cho chart |
|---|---|---|---|
| Java (target) | 21 | `<java.version>21</java.version>` | Image nền `eclipse-temurin:21-jre`; bật virtual threads (`spring.threads.virtual.enabled=true`) để long-poll `/events` (F10) không giữ thread nền tảng |
| Spring Boot | 3.5.14 | `spring-boot-starter-parent` | Probe qua Actuator (mục 7); cấu hình app là `application.yaml` render từ ConfigMap |
| Spring Framework | 6.2.18 | Boot 3.5.x quản lý | Gọi Kyuubi REST bằng `RestClient` |
| Spring Security | 6.5.10 | Boot 3.5.x quản lý | `oauth2Login` (client confidential `kyuubi-notebook`); chuyển access token người dùng sang Kyuubi (token relay, tự refresh). Phiên dựa cookie nên **bật CSRF** (`CookieCsrfTokenRepository` cho SPA) |
| Spring Vault | 3.1.2 | pin trong `pom.xml` | Lấy secret từ Vault bằng Kubernetes auth (mục 4). Đây là thư viện Spring Vault, **không phải** Spring Cloud Vault: cấu hình bằng `AbstractVaultConfiguration` + `KubernetesAuthentication`, không có `spring.cloud.vault.*` |
| Tomcat (nhúng) | 10.1.54 | Boot quản lý | `server.tomcat.connection-timeout` và async timeout phải lớn hơn `notebook.eventMaxWait` |

Phiên phía server lưu bằng **Spring Session JDBC** trong PostgreSQL, nên mọi replica dùng chung phiên
mà không cần sticky session hay khóa ký cookie dùng chung.

```
Trình duyệt ──HTTPS──▶ notebook (Deployment, N replica) ──REST + Bearer JWT──▶ Kyuubi rest ──▶ Spark engine
                           │                                                      (kyuubi-helm, có sẵn)
                           ├──▶ PostgreSQL (metadata + nội dung .ipynb + execution)
                           └──▶ Keycloak realm lakehouse (OIDC)
```

## 1. Điều kiện có sẵn trước khi cài (ngoài chart)

| Thứ | Ghi chú |
|---|---|
| Kyuubi đang chạy | Service REST `<kyuubi-release>-rest` trong namespace của Kyuubi. Kiểm tra `server.rest.port` của `kyuubi-helm` (mặc định `10099`) |
| Keycloak client mới `kyuubi-notebook` | Realm `lakehouse`, kiểu **confidential**, redirect URI theo địa chỉ notebook (mục 5). Thêm **audience mapper** để access token có `aud` chứa `kyuubi`, vì Kyuubi đang kiểm `aud=kyuubi` |
| PostgreSQL | Một database + user riêng cho notebook (dùng chung server Postgres có sẵn hoặc cài riêng). Chứa cả bảng của Spring Session JDBC |
| Vault | Role Kubernetes auth gắn với ServiceAccount của notebook, đọc được path secret của notebook (mục 4) |
| Secret `notebook-secrets` | Chỉ dùng khi **tắt** Vault (`vault.enabled=false`), tạo tay như `kyuubi-secrets-config` |

**Cảnh báo `iss`**: Kyuubi kiểm `iss` của token đúng bằng issuer cấu hình bên Kyuubi (hiện là địa chỉ
trình duyệt `https://10.29.19.56:32004/...`). B phải lấy token qua **cùng hostname Keycloak đó** để
`iss` khớp. Gọi JWKS/token endpoint nội bộ (`keycloak-service.keycloak-prod:8080`) được, nhưng
`frontendUrl` của realm phải trả về issuer giống bên Kyuubi. Sai chỗ này thì mọi lệnh gọi Kyuubi trả
401 dù đăng nhập notebook vẫn thành công.

## 2. Cấu trúc chart

```
notebook-helm/
├── Chart.yaml                 name: notebook, version: 0.1.0, appVersion: <tag image>
├── values.yaml                mặc định
├── values-dev.yaml            môi trường thử nghiệm
└── templates/
    ├── _helpers.tpl
    ├── configmap.yaml         application.yaml của B (không chứa secret)
    ├── deployment.yaml
    ├── service.yaml           ClusterIP hoặc NodePort
    ├── ingress.yaml           tùy chọn, mặc định tắt
    ├── serviceaccount.yaml    token chỉ mount khi vault.enabled (để đăng nhập Vault)
    ├── networkpolicy.yaml     tùy chọn, mặc định bật
    ├── pdb.yaml               khi replicaCount > 1
    ├── hpa.yaml               tùy chọn, mặc định tắt
    ├── NOTES.txt
    └── tests/test-ping.yaml   helm test: gọi /actuator/health/readiness của notebook
```

**Không có** Role/RoleBinding: B không gọi Kubernetes API. Token ServiceAccount (khi bật Vault) chỉ dùng để đăng nhập Vault.

## 3. `values.yaml` đề xuất

```yaml
replicaCount: 2

image:
  repository: docker.io/j3rrytran/notebook
  tag: ""                  # mặc định .Chart.AppVersion
  pullPolicy: IfNotPresent

service:
  type: NodePort           # đồng bộ cách đang expose Kyuubi; đổi ClusterIP khi có Ingress
  port: 8080
  nodePort: 32380          # chọn cổng chưa dùng

ingress:
  enabled: false
  className: nginx
  host: notebook.example.local
  tls: []

kyuubi:
  # B gọi Kyuubi REST trong cluster. Trỏ vào Service, B tự pin theo kyuubiInstance.
  restUrl: http://<kyuubi-release>-rest.<kyuubi-namespace>.svc.cluster.local:10099
  connectTimeout: 10s
  requestTimeout: 60s
  sessionOpenTimeout: 10m  # lần đầu mở session có thể phải chờ Kyuubi khởi tạo Spark engine
  # Luôn gửi khi POST /api/v1/sessions (bắt buộc cho output Python dạng mime bundle)
  sessionConfigs:
    kyuubi.engine.spark.output.mode: NOTEBOOK
  # Người dùng chỉ được ghi đè đúng các key này từ notebook
  allowedSessionConfigKeys:
    - spark.executor.memory
    - spark.executor.cores
    - spark.sql.shuffle.partitions
  idleSessionTimeout: 30m  # B tự đóng Kyuubi session rảnh

oidc:
  issuer: https://10.29.19.56:32004/realms/lakehouse   # phải khớp issuer bên Kyuubi
  clientId: kyuubi-notebook
  redirectPath: /auth/callback
  scopes: [openid, profile]
  usernameClaim: preferred_username
  kyuubiAudience: kyuubi

database:
  url: jdbc:postgresql://<postgres-host>:5432/notebook
  username: notebook
  maxPoolSize: 10
  migrateOnStart: true

notebook:
  importMaxSize: 16Mi
  exportIncludeOutputsDefault: false   # xem notebook-api-parity.md, mục Ánh xạ
  maxCellsPerNotebook: 500
  eventMaxWait: 10s                    # trần long-poll /events

vault:
  enabled: true
  uri: https://<vault-host>:8200
  authMount: kubernetes                # mount Kubernetes auth trong Vault
  role: notebook
  secretPath: secret/data/notebook     # chứa oidc-client-secret, db-password

secrets:
  existingSecret: notebook-secrets     # chỉ dùng khi vault.enabled=false; key: oidc-client-secret, db-password

jvm:
  opts: -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError

resources:
  requests: { cpu: 500m, memory: 1Gi }
  limits:   { cpu: "2",  memory: 2Gi }

networkPolicy:
  enabled: true
  kyuubiNamespace: <kyuubi-namespace>
  kyuubiPodLabels: { app.kubernetes.io/name: kyuubi }
  keycloakNamespace: keycloak-prod
  postgres: { namespace: <pg-namespace>, port: 5432 }

podDisruptionBudget: { enabled: true, minAvailable: 1 }
autoscaling: { enabled: false, minReplicas: 2, maxReplicas: 4, targetCPU: 70 }
```

## 4. Secret

**Mặc định: Vault** (Spring Vault 3.1.2, Kubernetes auth).

1. Vault bật Kubernetes auth, tạo role `notebook` gắn với ServiceAccount và namespace của chart.
2. Ghi `oidc-client-secret`, `db-password` vào `secretPath`.
3. Lúc khởi động, B dùng token ServiceAccount (`/var/run/secrets/kubernetes.io/serviceaccount/token`)
   đăng nhập Vault rồi đọc secret. Vault không tới được thì B **không khởi động** (fail fast), không
   chạy với cấu hình thiếu.

**Dự phòng khi tắt Vault**: Secret Kubernetes tạo tay.

```bash
kubectl -n <notebook-namespace> create secret generic notebook-secrets --from-literal=oidc-client-secret='<từ Keycloak>' --from-literal=db-password='<mật khẩu DB>'
```

Không đưa giá trị nào vào `values*.yaml` hay git. Không cần khóa ký cookie: phiên nằm trong
PostgreSQL (Spring Session JDBC).

## 5. Expose và redirect URI

| Cách | Địa chỉ notebook | Redirect URI đăng ký ở Keycloak |
|---|---|---|
| NodePort (mặc định, giống Kyuubi hiện tại) | `http://10.29.19.56:32380` | `http://10.29.19.56:32380/auth/callback` |
| Ingress (khi có DNS + TLS) | `https://notebook.<domain>` | `https://notebook.<domain>/auth/callback` |

Redirect URI ở values và ở Keycloak phải khớp từng ký tự.

## 6. Kết nối tới Kyuubi: những điểm chart phải đảm bảo

1. **Đi trong cluster**: `kyuubi.restUrl` dùng DNS Service nội bộ, không đi qua NodePort `32379`.
2. **Pin pod**: response `POST /api/v1/sessions` trả `kyuubiInstance` (IP pod:port). Mọi lệnh sau
   của session đó B gọi thẳng địa chỉ này. Vì vậy NetworkPolicy phải cho B ra **pod** Kyuubi (theo
   label), không chỉ ra Service.
3. **Hiện trạng Kyuubi**: Service `rest` đang pin về pod-0 do ZooKeeper nhúng chưa quorum. Notebook vẫn
   chạy đúng; khi Kyuubi bỏ pin, B không cần sửa gì nhờ cơ chế `kyuubiInstance`.
4. **Không lưu trạng thái trong replica**: execution, handle Kyuubi, `kyuubiInstance` nằm ở
   PostgreSQL, nên mọi replica phục vụ được mọi request; Service **không cần** sessionAffinity.

## 7. Deployment: các chi tiết bắt buộc

- Actuator chạy trên **cổng quản trị riêng** (`management.server.port: 8081`), không đưa vào Service hay
  Ingress.
- `readinessProbe`: `GET :8081/actuator/health/readiness`, nhóm readiness gồm `readinessState`, `db` và
  một `HealthIndicator` gọi `GET <kyuubi.restUrl>/api/v1/ping` (Kyuubi trả `pong`). Kyuubi lỗi thì
  notebook vẫn sống nhưng rút khỏi Service.
- `livenessProbe`: `GET :8081/actuator/health/liveness`, chỉ `livenessState`, **không** kiểm Kyuubi hay
  DB (tránh restart dây chuyền).
- `startupProbe`: cùng endpoint liveness, cho JVM và đăng nhập Vault đủ thời gian khởi động.
- `checksum/config` annotation để đổi ConfigMap là rolling restart.
- `securityContext`: `runAsNonRoot: true`, `readOnlyRootFilesystem: true`, drop mọi capability.
- `terminationGracePeriodSeconds` lớn hơn `notebook.eventMaxWait` để long-poll kết thúc gọn.
- Khi tắt Vault, env lấy secret bằng `secretKeyRef` từ `secrets.existingSecret`.
- `JAVA_TOOL_OPTIONS` lấy từ `jvm.opts`.

## 8. NetworkPolicy (mặc định bật)

Egress chỉ cho phép:

| Đích | Cổng |
|---|---|
| Pod Kyuubi (label `kyuubiPodLabels`, namespace `kyuubiNamespace`) | 10099 |
| Keycloak (`keycloakNamespace`) | 8080 |
| PostgreSQL | 5432 |
| Vault (khi `vault.enabled`) | 8200 |
| kube-dns | 53/UDP, 53/TCP |

Ingress chỉ vào cổng `service.port`.

## 9. Thứ tự thực hiện

| Bước | Việc | Xong khi |
|---|---|---|
| 1 | Tạo client Keycloak `kyuubi-notebook` + audience mapper `kyuubi` | Token decode ra có `aud` chứa `kyuubi`, `iss` khớp bên Kyuubi |
| 2 | Tạo database, user Postgres; cấu hình Vault role + secret (hoặc Secret `notebook-secrets` nếu tắt Vault) | `psql` kết nối được bằng user notebook; `vault read` bằng role `notebook` đọc được secret |
| 3 | Viết chart theo mục 2–8 | `helm lint` và `helm template` sạch |
| 4 | `helm install` với `values-dev.yaml` | Pod `Ready`, `helm test` đạt |
| 5 | Smoke test (mục 10) | Đạt cả 5 mục |

```bash
helm upgrade --install notebook ./notebook-helm -n <notebook-namespace> -f notebook-helm/values-dev.yaml
```

## 10. Smoke test sau khi cài

1. Đăng nhập qua Keycloak, trang notebook hiện tên người dùng (`GET /me`).
2. Tạo notebook, cell SQL `SELECT 1` → ra bảng 1 dòng.
3. Cell Python `print(1+1)` → output `2`. Cell `import pandas as pd; pd.DataFrame({"a":[1]})` → ra
   bảng HTML (xác nhận `output.mode=NOTEBOOK` đã tới engine).
4. Cell Python `1/0` → hiện traceback đủ `ZeroDivisionError` (xác nhận lỗi đi nguyên qua REST).
5. Xóa một pod notebook giữa lúc cell đang chạy → mở lại trang vẫn thấy execution và kết quả.

## 11. Ngoài phạm vi chart này

- Build image notebook (repo/CI riêng của ứng dụng).
- Sửa `kyuubi-helm` hay cấu hình Kyuubi.
- Lịch chạy cron (F25) chờ chốt danh tính chạy.
