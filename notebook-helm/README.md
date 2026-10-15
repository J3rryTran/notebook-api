# Notebook Helm Chart

Chart Helm cho Lakehouse Notebook bản all-in-one (contract 2). Hệ thống deploy của nền tảng render chart này
(`helm template`) rồi apply. Chart **không dùng Helm hook**, nên render ra là apply được ngay.

**Mọi giá trị deploy nằm trong `values.yaml`** (đã điền cho môi trường lakehouse thử nghiệm, mode `oidc`,
4 replica). Template không viết cứng giá trị môi trường; chỉ cổng container `8080` cố định vì là hợp đồng
với image. Đổi môi trường = sửa `values.yaml`.

Thiết kế chi tiết: `plan/chart-design.md`. Hợp đồng chart ↔ image: `plan/chart-image-contract.md`.

## 1. Kiến trúc

```
Trình duyệt ──▶ <IP node>:32380 (NodePort) ──▶ Service :8080 ──▶ Pod: nginx :8080
                                                                  ├─ /            web UI
                                                                  ├─ /api/v1/...  notebook-service 127.0.0.1:8084
                                                                  │               execution-service 127.0.0.1:8082
                                                                  └─ /internal/   403
```

- Một container gồm nginx, `nb-notebook-service` và `nb-execution-service`. Hai JVM và actuator chỉ nghe
  `127.0.0.1`; ra ngoài chỉ có cổng 8080.
- Chạy non-root (uid 10001), root filesystem chỉ đọc, `/tmp` là `emptyDir`.
- Token nội bộ giữa hai service do container tự sinh mỗi lần khởi động, không cần Secret.
- Cấu hình không bí mật nằm trong ConfigMap `application.yaml`; đổi values thì pod tự rollout.
- Flyway migrate database lúc notebook-service khởi động (nhiều replica khởi động cùng lúc vẫn an toàn).

## 2. Tham số (`values.yaml`)

**Bắt buộc**

| Tham số | Ví dụ | Ghi chú |
|---|---|---|
| `publicUrl` | `http://10.29.19.56:32380` | Địa chỉ người dùng gõ trên trình duyệt. Dùng làm redirect OIDC và quyết định cookie `Secure` (`https://` → bật). Với NodePort phải kết thúc bằng `:<service.nodePort>` |
| `existingSecret` | `notebook-secrets` | Secret tạo trước, xem mục 3 |
| `mariadb.host` | `10.29.19.52` | |
| `kyuubi.restUrl` | `http://kyuubi-rest.kyuubi.svc.cluster.local:10099` | Kyuubi REST |
| `auth.mode` | `oidc` | `oidc` \| `no-oidc` \| `ldap` |
| `auth.oidc.issuer` | `https://10.29.19.56:32004/realms/lakehouse` | Khi `oidc`; phải khớp issuer bên Kyuubi |
| `auth.ldap.url` | `ldaps://ldap.example:636` | Khi `ldap` |

**Truy cập**

| Tham số | Mặc định | Ghi chú |
|---|---|---|
| `service.type` | `NodePort` | `NodePort` \| `ClusterIP` |
| `service.nodePort` | `32380` | 30000–32767, không trùng cổng đã dùng trên cluster |
| `service.externalTrafficPolicy` | `Cluster` | `Cluster`: vào được qua IP mọi node |
| `ingress.enabled`, `ingress.host`, `ingress.tls` | tắt | Khi bật TLS thì `publicUrl` phải `https://` |

**Lưu trữ**

| Tham số | Mặc định |
|---|---|
| `mariadb.port` / `database` / `username` | `3306` / `test` / `notebook_api` |
| `mariadb.maxPoolSize` | `5` (mỗi JVM; MariaDB dùng chung với hệ thống khác) |

**Xác thực**

| Tham số | Mặc định | Ghi chú |
|---|---|---|
| `auth.admins` | `[]` | Username được coi là admin |
| `auth.allowUnverified` | `false` | Chỉ bật cho playground khi Kyuubi chạy `authentication=NONE` (khi đó Kyuubi chấp nhận mọi credential) |
| `auth.oidc.clientId` | `kyuubi-notebook` | Client **public** cho SPA (PKCE) |
| `auth.oidc.scopes` / `usernameClaim` / `cacheTtl` | `[openid, profile]` / `preferred_username` / `60s` | |
| `auth.oidc.serviceAccount.enabled` | `false` | Proxy user khi `app_users.ranger_username` ≠ username. Bật sau khi kiểm P5.7 |
| `auth.oidc.serviceAccount.clientId` | `notebook-api` | Client **confidential**, secret ở key `oidc-client-secret` |
| `auth.session.ttl` / `recheck` | `8h` / `10m` | `no-oidc`, `ldap` |
| `auth.ldap.startTls` | `false` | Bắt buộc `true` với `ldap://`; phải `false` với `ldaps://` |
| `auth.ldap.baseDn` | `""` | |
| `auth.ldap.userDnPattern` | `""` | Ví dụ `uid={0},ou=people`. Chọn **đúng một** trong hai cách tìm DN |
| `auth.ldap.userSearchBase` / `userSearchFilter` | `ou=people` / `""` | Ví dụ filter `(uid={0})`; AD `(sAMAccountName={0})`. Cần `managerDn` |
| `auth.ldap.managerDn` | `""` | Password ở key `ldap-manager-password` |
| `auth.ldap.usernameAttribute` | `uid` | Tên đăng nhập chuẩn (dùng cho owner, Kyuubi, Ranger). AD: `sAMAccountName` |
| `auth.ldap.groupSearchBase` / `groupMemberAttribute` / `adminGroup` | `ou=groups` / `member` / `""` | `adminGroup` là DN nhóm admin; `memberUid` cho OpenLDAP posixGroup |
| `auth.ldap.trustStore.enabled` / `type` | `false` / `PKCS12` | CA của LDAP server (`PKCS12` \| `JKS` \| `PEM`), file ở key `ldap-truststore` |

**Kyuubi**

| Tham số | Mặc định | Ghi chú |
|---|---|---|
| `kyuubi.connectTimeout` / `requestTimeout` | `10s` / `60s` | |
| `kyuubi.idleSessionTimeout` | `30m` | Session rảnh quá thời gian này thì đóng; `0s` = tắt |
| `kyuubi.idleReaperIntervalMs` | `300000` | Chu kỳ quét session rảnh |
| `kyuubi.pinToInstance` | `true` | Gọi đúng instance Kyuubi đã mở session |
| `kyuubi.allowedSessionConfigKeys` | `spark.executor.memory`, `spark.executor.cores`, `spark.sql.shuffle.partitions` | Cấu hình Spark người dùng được phép đặt |
| `kyuubi.extraSessionConfigs` | `{}` | Luôn gửi khi mở session. **Không** đặt `kyuubi.engine.spark.output.mode` (render lỗi) |

**HA** (xem mục 5)

| Tham số | Mặc định | Ghi chú |
|---|---|---|
| `replicaCount` | `4` | ≥ 2 mới có HA |
| `ha.antiAffinity` | `soft` | `soft` = cố tách pod theo node; `hard` = bắt buộc tách; `none` = không đặt |
| `ha.topologyKey` | `kubernetes.io/hostname` | `topology.kubernetes.io/zone` để tách theo zone |
| `updateStrategy.maxUnavailable` / `maxSurge` | `1` / `1` | Không được cùng bằng 0. Với 4 replica: luôn còn ≥ 3 pod khi update |
| `podDisruptionBudget.enabled` / `minAvailable` | `true` / `2` | `minAvailable` phải < `replicaCount` |
| `affinity` | `{}` | Khác rỗng thì ghi đè hoàn toàn `ha.antiAffinity` |

**Secret**: `existingSecret` và `secretKeys.*` (tên từng key trong Secret, mặc định như bảng mục 3).

**Giới hạn và runtime**: `notebook.*` (số cell, trang danh sách, kích thước cell/tài liệu/import, số revision,
số dòng kết quả, long-poll), `jvm.*`, `resources`, `probes.*`, `terminationGracePeriodSeconds`,
`preStopSleepSeconds`, `ingress.annotations`, `networkPolicy` (mặc định tắt), `nodeSelector`, `tolerations`,
`topologySpreadConstraints`. Xem chú thích trong `values.yaml`.

Chart kiểm 21 điều kiện lúc render (`templates/_helpers.tpl`); sai thì render báo lỗi, không ra manifest.

## 3. Secret `existingSecret` (tạo trước, trên Kubernetes Dashboard)

Dashboard → chọn namespace → nút **+** → **Create from input**. Giá trị chữ đặt trong `stringData`; riêng
file truststore phải mã hóa base64 rồi đặt trong `data`.

| Key | Khi nào cần |
|---|---|
| `db-password` | Luôn |
| `auth-encryption-key` | `no-oidc`, `ldap`. 32 byte base64 (`openssl rand -base64 32`), tạo **một lần**, giữ cố định (đổi = mọi người bị đăng xuất) |
| `oidc-client-secret` | `oidc` + `serviceAccount.enabled` |
| `ldap-manager-password` | `ldap` + `userSearchFilter` |
| `ldap-truststore` | `ldap` + `trustStore.enabled` (base64 của file, trong `data`) |
| `ldap-truststore-password` | `ldap` + `trustStore.enabled` + type khác `PEM` |

Mẫu mode `oidc` (mặc định):

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: notebook-secrets
  namespace: <namespace-của-notebook>
type: Opaque
stringData:
  db-password: "<mật khẩu user notebook_api>"
```

Mẫu mode `ldap` có truststore:

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: notebook-secrets
  namespace: <namespace-của-notebook>
type: Opaque
stringData:
  db-password: "<mật khẩu user notebook_api>"
  auth-encryption-key: "<openssl rand -base64 32>"
  ldap-manager-password: "<mật khẩu managerDn>"
  ldap-truststore-password: "<mật khẩu truststore>"
data:
  ldap-truststore: "<base64 của file truststore>"
```

Thiếu key bắt buộc thì pod báo `CreateContainerConfigError` (Dashboard → Pod → Events).

### User MariaDB

Người có quyền admin chạy một lần trên DBeaver (Flyway cần quyền DDL trên database này, không cần quyền
database khác):

```sql
CREATE USER IF NOT EXISTS 'notebook_api'@'%' IDENTIFIED BY '<mật khẩu = db-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON test.* TO 'notebook_api'@'%';
FLUSH PRIVILEGES;
```

## 4. Kiểm tra sau khi deploy

1. Dashboard → **Service** `<release>-notebook` → *External endpoints* có `<IP node>:32380`.
2. Dashboard → **Pod** → *Conditions* Ready; *Logs* có dòng Flyway `Successfully applied` hoặc `is up to date`.
3. Trình duyệt:
   - `{publicUrl}`: giao diện notebook.
   - `{publicUrl}/api/v1/auth/config`: JSON có `"mode"` đúng values.
   - `{publicUrl}/internal/x`: **403**. `{publicUrl}/actuator/health`: **404**.
4. Không vào được: kiểm firewall cổng NodePort (TCP) giữa máy người dùng và node.

## 5. HA

**Chịu được mất 1 pod/node:** mọi trạng thái (notebook, phiên đăng nhập, session Kyuubi, kết quả chạy)
nằm trong MariaDB dùng chung, nên request vào pod nào cũng được, không cần sticky session. Cell đang chạy
vẫn lấy được kết quả từ pod còn lại vì operation nằm bên Kyuubi. Flyway và các job định kỳ an toàn khi
nhiều pod chạy cùng lúc.

**Chọn cấu hình theo số node** (N = số node còn đủ tài nguyên cho 1 pod notebook):

| Tình huống | Values |
|---|---|
| N ≥ `replicaCount + maxSurge` (vd 5 node cho 4 replica) | `ha.antiAffinity: hard`, giữ `updateStrategy` (1 / 1) |
| N = `replicaCount` (vd 4 node) | `ha.antiAffinity: hard`, `updateStrategy.maxUnavailable: 1`, `maxSurge: 0` |
| N < `replicaCount` hoặc không chắc | `ha.antiAffinity: soft` (mặc định): ưu tiên tách node, thiếu node vẫn chạy |

Với `hard`, nếu thiếu node thì pod ở trạng thái **Pending** (Dashboard → Pod → Events: `didn't match pod
anti-affinity rules`).

**Cấu hình hiện tại (4 replica):** `ha.antiAffinity: soft`, `updateStrategy` 1 / 1, PDB `minAvailable: 2`.
Khi đã biết cluster có ≥ 5 node đủ tài nguyên thì đổi `ha.antiAffinity: hard`.

**Giới hạn (ngoài phạm vi chart):**
- MariaDB `mariadb.host`, Kyuubi và Keycloak/LDAP là điểm lỗi chung. Readiness kiểm DB, nên MariaDB chết thì
  mọi pod NotReady.
- Tài nguyên: lúc rolling update có thể có `replicaCount + maxSurge` pod; kết nối DB tối đa
  `(replicaCount + maxSurge) × 2 JVM × mariadb.maxPoolSize` (hiện 5 × 2 × 5 = 50; MariaDB mặc định
  `max_connections=151` và đang dùng chung với các hệ thống khác). CPU/RAM: lúc update có tối đa 5 pod ×
  `resources.limits`.
- Migration Flyway chạy khi pod mới khởi động trong lúc pod cũ còn phục vụ: migration mới phải tương thích
  ngược (thêm cột/bảng trước, xóa ở phiên bản sau).

## 6. Kiểm chart tại chỗ

`scripts/helm-check.sh` (chạy bằng Docker, không cần cluster): lint và render **`values.yaml` thật**; render thêm mode `no-oidc`/`ldap` và `ha.antiAffinity` hard/none bằng
`--set` (chỉ để chắc template còn đúng); và 21 ca
cấu hình sai phải bị chặn đúng thông báo.
