# Notebook Helm Chart

Chart Helm triển khai ứng dụng Lakehouse Notebook All-In-One (Contract 2) lên Kubernetes.

## 1. Yêu cầu & Kiến trúc

- **Topology**: All-In-One (1 container duy nhất chạy Nginx reverse proxy + 2 Spring Boot services: `nb-notebook-service` và `nb-execution-service`).
- **Bảo mật**: Chạy non-root (`runAsUser: 10001`), `readOnlyRootFilesystem: true`, tmpfs tại `/tmp`. Token nội bộ `NOTEBOOK_INTERNAL_TOKEN` tự sinh ngẫu nhiên lúc khởi động, chỉ truy cập qua loopback.
- **Không nhúng Secret**: Khai báo Secret rời qua `existingSecret: notebook-secrets`.

---

## 2. Bảng tham số cấu hình (`values.yaml`)

| Tham số | Kiểu | Mặc định | Mô tả |
|---|---|---|---|
| `replicaCount` | int | `2` | Số lượng Pod replica |
| `image.repository` | string | `docker.io/j3rrytran/notebook` | Repository chứa Docker image |
| `image.tag` | string | `""` | Tag image (để trống sẽ dùng `appVersion` từ `Chart.yaml`) |
| `image.pullPolicy` | string | `IfNotPresent` | Chính sách kéo image |
| `publicUrl` | string | **Bắt buộc** | URL công khai của Web Notebook (VD: `http://10.29.19.56:32380`) |
| `existingSecret` | string | `notebook-secrets` | Tên Kubernetes Secret chứa mật khẩu/token |
| `mariadb.host` | string | **Bắt buộc** | Địa chỉ MariaDB server |
| `mariadb.port` | int | `3306` | Cổng kết nối MariaDB |
| `mariadb.database` | string | `test` | Tên database MariaDB |
| `mariadb.username` | string | `notebook_api` | Username MariaDB |
| `auth.mode` | string | `oidc` | Chế độ xác thực (`oidc`, `no-oidc`, `ldap`) |
| `auth.allowUnverified` | bool | `false` | Cho phép đăng nhập không xác thực khi dev |
| `auth.oidc.issuer` | string | **Bắt buộc khi oidc** | Issuer URL của Keycloak / OpenID Provider |
| `auth.oidc.clientId` | string | `kyuubi-notebook` | Client ID công khai (PKCE) |
| `auth.oidc.scopes` | list | `["openid", "profile"]` | Scopes yêu cầu khi xác thực |
| `auth.oidc.serviceAccount.enabled` | bool | `false` | Bật Service Account Token cho Ranger Proxy |
| `auth.ldap.url` | string | **Bắt buộc khi ldap** | URL máy chủ LDAP (`ldap://` hoặc `ldaps://`) |
| `kyuubi.restUrl` | string | **Bắt buộc** | URL Kyuubi REST API |
| `service.type` | string | `NodePort` | Loại Service (`NodePort` hoặc `ClusterIP`) |
| `service.nodePort` | int | `32380` | Cổng NodePort khi chọn `type: NodePort` (30000-32767) |
| `ingress.enabled` | bool | `false` | Bật Ingress Controller |

---

## 3. Mẫu Secret (`notebook-secrets`) theo từng chế độ xác thực

Tạo Secret trước khi cài đặt Helm chart:

### Chế độ `oidc`
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: notebook-secrets
type: Opaque
stringData:
  db-password: "YOUR_MARIADB_PASSWORD"
  # Chỉ cần nếu auth.oidc.serviceAccount.enabled=true:
  oidc-client-secret: "YOUR_OIDC_CLIENT_SECRET"
```

### Chế độ `no-oidc`
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: notebook-secrets
type: Opaque
stringData:
  db-password: "YOUR_MARIADB_PASSWORD"
  auth-encryption-key: "YOUR_AES_256_BASE64_KEY"
```

### Chế độ `ldap`
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: notebook-secrets
type: Opaque
stringData:
  db-password: "YOUR_MARIADB_PASSWORD"
  auth-encryption-key: "YOUR_AES_256_BASE64_KEY"
  # Chỉ cần nếu dùng auth.ldap.userSearchFilter:
  ldap-manager-password: "YOUR_LDAP_ADMIN_PASSWORD"
  # Chỉ cần nếu bật auth.ldap.trustStore.enabled:
  ldap-truststore-password: "YOUR_TRUSTSTORE_PASSWORD"
```

---

## 4. Kiểm tra sau khi triển khai (Checklist)

1. **Kiểm tra trạng thái Pod & Service**:
   ```bash
   kubectl get pods -l app.kubernetes.io/name=notebook
   kubectl get svc -l app.kubernetes.io/name=notebook
   ```
2. **Kiểm tra endpoint cấu hình**:
   ```bash
   curl -s http://<NODE_IP>:32380/api/v1/auth/config
   ```
   Kỳ vọng JSON trả về: `{"mode":"oidc", ...}`.
3. **Bảo mật**:
   - `curl -I http://<NODE_IP>:32380/internal/access/token` -> Trả về `403 Forbidden`.
   - `curl -I http://<NODE_IP>:32380/actuator/health` -> Trả về `404 Not Found`.
