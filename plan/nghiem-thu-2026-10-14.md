# Nghiệm thu commit `bf8cec5` ("update src") theo `plan/mariadb-auth-plan.md`

Ngày: 2026-10-14. Phạm vi: 92 file, +3772 / −902. Cách làm: build + test bằng Maven 3.9.9 / JDK 17
(Docker), đọc code đối chiếu từng mục plan, kiểm lại bằng tay các lỗi nặng.

**Kết luận: CHƯA ĐẠT.** Khung đúng hướng (MariaDB, Flyway V1–V3, ba chế độ auth, bảng run/cell-run),
build xanh, nhưng có 7 lỗi chặn (mục 1), trong đó 3 lỗi làm sai bảo mật và 2 lỗi làm chạy cell
không ra kết quả trên Kyuubi thật.

| Mảng | Đạt | Một phần | Không đạt / thiếu |
|---|---|---|---|
| Build + test | `mvn verify` xanh, 25 test | | Không có test tích hợp DB, auth, Kyuubi |
| Lưu trữ §1 | Migration V1–V3, entity, Flyway, bỏ Postgres/H2 | Định dạng tài liệu | Xóa notebook, chuyển đổi `.ipynb`, T1–T3 |
| Bảo mật §2 | Chọn filter theo mode, `/ping`, mã hóa credential | no-oidc, ldap | Validator không chạy, CSRF, proxy user, recheck |
| Execution §3 | Mở session bằng Bearer, kiểm owner, cancel | Endpoint | Đọc rowset sai, long-poll, trạng thái |
| Đóng gói | Image all-in-one chạy được về cấu trúc | Chart | Đăng nhập OIDC phía frontend, DOCKER.md |

---

## 1. Lỗi chặn (phải sửa trước khi deploy)

| # | Lỗi | Ở đâu | Hậu quả |
|---|---|---|---|
| B1 | `AuthPropertiesValidator` **không được đăng ký**. `DatasourceGuard` đăng ký trong file `META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports`, nhưng Spring Boot 3.3 chỉ nạp `EnvironmentPostProcessor` từ `META-INF/spring.factories`. Cả hai **không bao giờ chạy**. Test unit gọi trực tiếp nên vẫn xanh. | `nb-common/src/main/resources/META-INF/spring/…imports` | Toàn bộ 9 kiểm tra cấu hình (§2.5) và chặn URL không phải MariaDB không có tác dụng. Mode sai/thiếu → **lặng lẽ chạy `oidc`** (`SecurityConfig` nhánh `else`), `internalToken` rỗng vẫn khởi động. |
| B2 | CSRF tắt ở cả hai service; cookie `NB_SESSION` thiếu `Secure` và `SameSite=Strict`. | `*/config/SecurityConfig.java:47-48`; `AuthController.java:106-110` | Chế độ `no-oidc`/`ldap` bị CSRF: trang ngoài có thể chạy cell, xóa notebook dưới tên người dùng. Cookie đi qua HTTP thường. |
| B3 | LDAP: `{0}` thay thẳng vào DN và filter, không escape; bỏ qua DN tìm được và trả lại chuỗi người dùng gõ thay vì `usernameAttribute`. | `LdapAuthenticator.java:45, 56, 69, 89` | LDAP injection. `Alice` và `alice` thành hai người khác nhau (sai owner, sai Ranger). |
| B4 | Proxy user (§2.2 bước 4) **chưa làm**: không tra `ranger_username`, không token tài khoản dịch vụ, không `hive.server2.proxy.user`. | execution-service | Người có `ranger_username ≠ username` bị Ranger áp sai quyền. |
| B5 | Đọc rowset Kyuubi sai định dạng: code coi rowset là mảng JSON, Kyuubi REST trả `{"rows":[{"fields":[…]}]}`. Không gọi `resultsetmetadata` nên bảng SQL không có tên cột. Test giả lập dữ liệu sai định dạng nên không bắt được. | `ExecutionService.java:244-287` | Trên Kyuubi thật, **mọi cell SQL ra 0 dòng, mọi cell Python ra rỗng**. |
| B6 | Trạng thái Kyuubi `CLOSED`/`UNKNOWN` không xử lý. | `ExecutionService.java:161` | Run kẹt `RUNNING` mãi, bị poll mãi. |
| B7 | Frontend chế độ `oidc`: chuyển hướng sang Keycloak không PKCE, không có trang `/auth/callback` đổi code lấy token, không bao giờ gửi `Authorization: Bearer`. | `frontend/public/index.html:213-219` | Chế độ mặc định **không đăng nhập được**: Keycloak trả về `/auth/callback`, SPA gọi `/me` → 401 → lại chuyển hướng, lặp vô hạn. |

## 2. Lỗi cần sửa (không chặn deploy thử nghiệm)

**Bảo mật**
- Không thăm dò Kyuubi `NONE` lúc khởi động; `allowUnverified` khai báo nhưng không ai đọc.
- Không có `recheck` (no-oidc: gọi lại `/ping`; ldap: bind lại) dù có cột `last_recheck_at`.
- LDAP: `adminGroup` cứng `false`; không có StartTLS/trustStore thật; không kiểm Kyuubi-OIDC lúc khởi động.
- `X-Internal-Token` so sánh bằng `String.equals` (không constant-time) (`InternalAuthController.java:33`).
- Thông báo lỗi nội bộ và body lỗi Kyuubi trả nguyên cho client.
- `startsWith("/internal")` khớp cả `/internalxyz`; nên `"/internal/"`.
- Token không có `exp` được gán hạn giả `now + cacheTtl`.

**Lưu trữ**
- `schemaVersion ≠ 1` không bị chặn; giới hạn 16 MB toàn tài liệu không kiểm; đo kích thước bằng `getBytes()` theo charset máy, phải `UTF_8`.
- Xóa notebook chỉ xóa mềm, không xóa permission/revision/run; `deleteByNotebookId` khai báo nhưng không gọi (T3).
- `.ipynb`: import không đọc `metadata.language`, magic lạ rơi về `PYTHON` thay vì `defaultLanguage`, `RAW` sai language, bỏ `execution_count`/`outputs`/`kernelspec`, bỏ `id` và metadata cell; export bỏ qua `includeOutputs`, ghi `kernelspec` cứng; lỗi 409/413 bị nuốt thành 400.
- `updateNotebook` nuốt lỗi parse JSON nhưng vẫn tăng revision.
- Dọn revision đếm sai khi có revision `MANUAL`.
- README vẫn nhắc PostgreSQL/H2 (S12).

**Execution**
- `/events` vẫn chạy trên `ForkJoinPool.commonPool()`, mất security context, timeout không dừng được việc đang chạy (P2-1).
- `:interrupt` truyền id runtime vào hàm hủy cell-run → luôn 404. `DELETE` session không đóng session Kyuubi. `/results` bỏ `offset/limit`, `/logs` bỏ `afterSequence`.
- `execution_time_ms`, `heartbeat_at`, `cancel_requested` không ghi; không dùng `PENDING`.
- Traceback Python chỉ là một dòng message, `ename` cứng `"ExecutionError"`.
- Cả `ExecutionService` là `@Transactional`: gọi HTTP Kyuubi trong transaction, giữ khóa `FOR UPDATE` suốt lúc lấy rowset/log.
- Không khóa khi submit (trùng `execution_count`); hủy có thể ghi đè `SUCCEEDED` thành `CANCELED`.
- Submit Kyuubi lỗi → không còn bản ghi `FAILED` nào.
- Session `LOST` vẫn được dùng lại.
- `/notebook-status` báo Kyuubi `HEALTHY` không kiểm, đếm "active" sai.
- `KyuubiRestClient` rơi về `KyuubiCredentialHolder` khi request không có header: rủi ro gọi Kyuubi dưới danh tính khác.

**Đóng gói / chart**
- [DOCKER.md](../DOCKER.md) còn ArgoCD (dòng 1, 3, 39–46), trỏ `argocd/application.yaml` không tồn tại, link `file:///mnt/d/...` của máy Windows.
- `scripts/build-all-in-one.sh` và DOCKER.md dùng `apache-maven-3.9.11/bin/mvn` trong repo, nhưng thư mục `lib/` chỉ có file license (jar bị `.gitignore` chặn) → **script không chạy được** trên máy clone mới.
- Chart không truyền `oidc.issuer`, `oidc.clientId`, `auth.ldap.*`, `oidc-client-secret` vào container: khối `oidc:` trong `values.yaml` không có tác dụng; chế độ `ldap` không cấu hình được qua chart.
- `replicaCount: 2` với image all-in-one: mỗi pod chạy cả notebook-service lẫn Flyway; chấp nhận được (Flyway có khóa), nhưng probe chỉ kiểm notebook-service, execution-service chết không bị phát hiện cho tới khi `entrypoint.sh` thoát.
- Nginx không đặt `X-Forwarded-Proto` → khi sửa B2, Spring không biết đang HTTPS để bật `Secure`.
- Container chạy bằng root.

## 3. Test

| Plan | Có | Ghi chú |
|---|---|---|
| T1 Flyway + validate trên MariaDB | Không | Có dependency Testcontainers nhưng không dùng. Rủi ro chưa kiểm: `CHAR(36)` vs `String`, `DATETIME(6)` vs `Instant` khi `validate`. |
| T2 5 MB, emoji, xung đột `lock_version` | Không | |
| T3 Xóa notebook | Không | Và chức năng sai (mục 2) |
| T4 DatasourceGuard | Có | Nhưng guard không chạy thật (B1) |
| T5 Validator | Có | Nhưng validator không chạy thật (B1) |
| T6 CredentialCrypto | Có | |
| T7–T12 oidc / no-oidc / ldap / internal | Không | |
| T13 Output AUTO | Có | Dữ liệu giả sai định dạng Kyuubi (B5) |
| T14 SQLLAB round-trip | Một phần | Unit test, không DB |

## 4. Thứ tự sửa đề xuất

1. B1 (thêm `META-INF/spring.factories`, bỏ nhánh `else` mặc định oidc) + test khởi động thất bại.
2. B5, B6 + sửa dữ liệu giả trong `ExecutionServiceAutoOutputTest` theo đúng JSON Kyuubi.
3. B2, B3 + T9, T10.
4. B7 (PKCE + `/auth/callback` bằng `oidc-client-ts`).
5. T1 Testcontainers MariaDB để chốt `validate`.
6. B4 sau khi có kết quả P5.1–P5.7 trên Kyuubi.
7. Mục 2 theo thứ tự bảo mật → lưu trữ → execution → đóng gói.
