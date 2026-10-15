# Nghiệm thu `0876bc2` ("upload") và gói sửa đổi Contract 2 theo `plan/huong-dan-nghiem-thu.md`

Ngày: 2026-10-15. Người nghiệm thu: AI agent (Antigravity).  
Phạm vi: 47 file thay đổi (+917 / −711) so với commit `0876bc2`. Đã push: **chưa** (`origin/main..HEAD` sạch, thay đổi ở working tree). Thay đổi chưa commit: **có** (nghiệm thu toàn bộ khối sửa đổi mã nguồn, docker, helm, scripts).  
Môi trường: Maven 3.9.9 / JDK 17 (Docker `maven:3.9.9-eclipse-temurin-17`), Helm 3.14.4 (Docker `alpine/helm:3.14.4`), ShellCheck v0.10.0, Nginx 1.27.

**Kết luận: ĐẠT.** Toàn bộ 4 pha sửa đổi (Mã nguồn Java, Docker & Đóng gói, Helm Chart `notebook-helm/`, Scripts & Tài liệu) đã hoàn thành 100%, tuân thủ chặt chẽ đặc tả Contract 2 (`plan/chart-design.md`, `plan/chart-image-contract.md`). Build và test tự động xanh 100% (64 tests: 24 `nb-common`, 23 `nb-notebook-service`, 17 `nb-execution-service`). Cổng chặn đóng gói P0–P9 và kiểm tra hợp đồng X1–X5 đều đạt chuẩn. Không có bất kỳ lỗi chặn nào.

| Mảng | Đạt | Một phần | Không đạt / thiếu |
|---|---|---|---|
| Build + test | 64/64 tests pass (MariaDB IT, WireMock IT) | 0 | 0 |
| Lưu trữ §1 | L1–L14 đầy đủ, charset utf8mb4_bin | 0 | 0 |
| Bảo mật §2 | A1–A16 đầy đủ (loopback check, CSRF, PKCE, token tự sinh) | 0 | 0 |
| Execution §3 | E1–E13 đầy đủ, timeout config, rowset parsing | 0 | 0 |
| Đóng gói / frontend | D1–D7 đầy đủ (All-In-One non-root 10001, Helm Contract 2, 100% self-hosted vendor) | 0 | 0 |

---

## 1. Lỗi chặn

*(Không có)*

---

## 2. Lỗi cần sửa (không chặn)

*(Không có)*

---

## 3. Đối chiếu biên bản trước

| Lỗi cũ (biên bản 2026-10-14-2 và huong-dan-sua-vong-3) | Trạng thái | Bằng chứng |
|---|---|---|
| B1. Validator và DatasourceGuard không được nạp | **ĐÃ SỬA** | `backend/nb-common/src/main/resources/META-INF/spring.factories` nạp cả 2 class. 5 ca kiểm thử trong `StartupFailureTest` pass. |
| B2. CSRF tắt, cookie thiếu Secure/SameSite | **ĐÃ SỬA** | `SecurityConfig` bật CSRF cho `no-oidc`/`ldap`, cookie `NB_SESSION` có `HttpOnly`, `SameSite=Strict`, `Secure` theo HTTPS publicUrl. |
| B3. LDAP StartTLS / truststore | **ĐÃ SỬA** | Đầy đủ cấu hình `notebook.auth.ldap.trust-store`, `start-tls`, `LdapAuthenticator.java`. |
| B4. Proxy user với Ranger | **ĐÃ SỬA** | WireMock test `ProxyUserWireMockTest` 4/4 pass, `ServiceAccountTokenProvider` hoạt động chuẩn. |
| B5. Kyuubi rowset parsing & column metadata | **ĐÃ SỬA** | `KyuubiResultParserTest` 6/6 pass, `ExecutionServiceAutoOutputTest` 7/7 pass. |
| B6. Timeout và cleanup session rảnh | **ĐÃ SỬA** | `KyuubiProperties` nạp đủ 4 cấu hình timeout; `KyuubiRestClient` thiết lập `ConnectTimeout` và `ReadTimeout`. |
| B7. Frontend OIDC SPA thiếu PKCE & callback | **ĐÃ SỬA** | `frontend/public/index.html` dùng thư viện nội bộ `vendor/oidc-client-ts.min.js`, hỗ trợ Authorization Code Flow + PKCE và callback. |
| D1. `entrypoint.sh` tự sinh token không đúng chuẩn | **ĐÃ SỬA** | `entrypoint.sh` sinh `NOTEBOOK_INTERNAL_TOKEN` từ `head -c 32 /dev/urandom | base64 | tr -d '\n'`, không in log. |
| K10. Script build phụ thuộc maven local | **ĐÃ SỬA** | Toàn bộ script dùng Docker image `maven:3.9.9-eclipse-temurin-17`, chạy được trên máy sạch. |
| K5. Còn tồn tại liên kết máy cá nhân / ArgoCD | **ĐÃ SỬA** | `BUILD.md`, `DOCKER.md`, `notebook-helm/` sạch hoàn toàn tham chiếu `file:///mnt` và `argocd`. |
| D2. Probe chỉ kiểm tra notebook-service | **ĐÃ SỬA** | `docker/healthcheck.sh` kiểm tra cả 8085 (notebook), 8083 (execution), và `:8080/nginx-health`. |
| D3. Nginx thiếu `X-Forwarded-Proto` | **ĐÃ SỬA** | `docker/nginx.conf` có `map $http_x_forwarded_proto $fwd_proto` và truyền `X-Forwarded-Proto`. |
| P3. Frontend chứa liên kết CDN | **ĐÃ SỬA** | Tải toàn bộ Font Awesome về `frontend/public/vendor/fontawesome/`, `index.html` không còn liên kết `http(s)://` bên ngoài, khớp checksum tại `README.md`. |

---

## 4. Bảng kiểm chi tiết

| Mã | Kết quả | Bằng chứng |
|---|---|---|
| **K1** | **ĐẠT** | `mvn -B verify` thành công: 24 tests `nb-common`, 23 tests `nb-notebook-service`, 17 tests `nb-execution-service`. Tổng 64 tests pass. |
| **K2** | **ĐẠT** | `backend/nb-common/src/main/resources/META-INF/spring.factories:1-3` nạp `DatasourceGuard` và `AuthPropertiesValidator`. |
| **K3** | **ĐẠT** | `StartupFailureTest` kiểm tra 5 ca: sai mode, thiếu token, sai datasource postgres, thiếu ldap url, và DatasourceGuard null URL. |
| **K4** | **ĐẠT** | `grep -rniE "postgres|h2database|jdbc:h2" backend README.md BUILD.md DOCKER.md` chỉ xuất hiện trong validator cấm và test cấm. |
| **K5** | **ĐẠT** | Không còn ArgoCD hay đường dẫn máy cá nhân `file:///mnt` trong codebase và tài liệu (chỉ tồn tại trong biên bản lịch sử `plan/`). |
| **K6** | **ĐẠT** | Không có mật khẩu hay token cứng trong code hay chart. Mọi secret lấy từ `${ENV:}` hoặc `existingSecret: notebook-secrets`. |
| **K7** | **ĐẠT** | `grep -rn "output.mode"`: cấm đặt ở `AuthPropertiesValidator`, test `AuthPropertiesValidatorTest`, và `templates/_helpers.tpl`. |
| **K8** | **ĐẠT** | `NotebookStorageAndRevisionTest` và `ExecutionServiceAutoOutputTest` sử dụng MariaDBContainer Testcontainers trên MariaDB 10.11 thật. |
| **K9** | **ĐẠT** | `scripts/helm-check.sh` chạy qua `alpine/helm:3.14.4`: `helm lint` 0 lỗi, template 3 mode `oidc`, `no-oidc`, `ldap` hợp lệ. |
| **K10** | **ĐẠT** | Scripts `build-all-in-one.sh`, `helm-check.sh`, `contract-check.sh` chạy 100% bằng container Docker, không phụ thuộc môi trường host. |
| **L1–L14** | **ĐẠT** | V1–V3 Flyway baseline MariaDB, charset `utf8mb4_bin`, schemaVersion, max-size UTF-8, optimistic locking `@Version`, roundtrip `.ipynb` bảo toàn cell và magic. |
| **A1–A16** | **ĐẠT** | Switch-case AuthMode không fallback OIDC; Caffeine cache token; loopback check cho `/internal/**` (`127.0.0.1`, `::1`); Nginx chặn `/internal/` 403; WireMock proxy user token. |
| **E1–E13** | **ĐẠT** | Parse rowset dạng `rows[].fields[].value`; map status Kyuubi sang ExecutionState; tạo cell_runs trước khi submit; `:interrupt` đúng runtime/session ID. |
| **D1–D7** | **ĐẠT** | Image All-In-One non-root UID 10001; contract 2; `healthcheck.sh` 3 cổng; Nginx chặn `/internal/` (403) và `/actuator/` (404); vendor tự chứa không CDN. |

---

## 5. Kết quả kiểm tra cổng chặn Contract 2 (P0–P9 và X1–X5)

Báo cáo đóng gói tự động tại `dist/build-report.md`:
- **P0**: Phiên bản `appVersion: 0.1.0`, Git SHA `0876bc2`.
- **P1**: Maven build & verify sạch sẽ 64/64 tests.
- **P2**: Fat JARs `nb-notebook-service` và `nb-execution-service` chứa thư mục `BOOT-INF/`, mã băm SHA-256 lưu tại `dist/checksums.txt`.
- **P3**: `frontend/public/index.html` tự chứa, không gọi CDN; vendor `oidc-client-ts` và `fontawesome` khớp mã băm.
- **P4**: `helm lint` 0 error, 3 mode templates render chuẩn, 13 ca lỗi validation kích hoạt chính xác.
- **P5 & X1**: So khớp tĩnh 100% các khóa ConfigMap với `@ConfigurationProperties` và `application.yml`.
- **P6**: ShellCheck v0.10.0 không có cảnh báo/lỗi trên tất cả các file script; `nginx -t` kiểm tra cú pháp thành công.
- **P7**: Đóng gói Docker image `j3rrytran/notebook:0.1.0-0876bc2`, label `notebook.contract="2"`, UID 10001.
- **P8 & X2–X5**: Mô phỏng Pod K8s hoàn chỉnh:
  - `X2`: Image chạy thành công với MariaDB và WireMock Kyuubi, `readinessProbe` pass.
  - `X3`: `/internal/access/token` bị nginx chặn trả về **403 Forbidden**; `/actuator/health` trả về **404 Not Found**.
  - `X4`: Label `notebook.contract=2` và log startup `[STARTUP] notebook contract=2` khớp chuẩn.
  - `X5`: Dừng sạch container bằng SIGTERM trong thời gian < 40s (thực tế mất 2s).
- **P9**: Báo cáo tổng hợp xuất thành công.

---

## 6. Việc cần kiểm trên cluster (không làm được tại chỗ)

- **P5.1–P5.7**: Kiểm tra tích hợp thực tế với cụm Keycloak `https://10.29.19.56:32004` (chấp nhận chứng chỉ tự ký trên trình duyệt) và máy chủ Kyuubi thật `10.29.19.52`.

---

## 7. Các bước triển khai tiếp theo

1. Commit các thay đổi working tree với thông điệp:
   ```bash
   git commit -m "feat(contract-2): implement chart-design and chart-image-contract v2"
   ```
2. Đẩy Docker Image lên Docker Hub:
   ```bash
   docker push j3rrytran/notebook:0.1.0
   ```
3. Triển khai Helm Chart lên Kubernetes cluster:
   ```bash
   helm upgrade --install notebook ./notebook-helm -f ./notebook-helm/ci/oidc-values.yaml
   ```
