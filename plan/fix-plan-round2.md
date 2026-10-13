# Plan sửa vòng 2 `notebook-api`: phần còn thiếu sau nghiệm thu lần 2

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent.
Kế thừa `plan/fix-plan.md` (vòng 1). Chỉ liệt kê việc **chưa đạt** ở nghiệm thu lần 2 (2026-10-13), xếp
theo ưu tiên `P1` (chặn, phải xong trước) → `P2` → `P3` (tùy chọn). Mục 1 thay thế hoàn toàn mục 3 của
vòng 1 (xác thực), vì thiết kế đổi thành hai chế độ.

Mỗi việc có: **Ở đâu** (file), **Làm gì**, **Xong khi** (điều kiện kiểm được). Không làm việc ngoài
danh sách; thấy thiếu thì ghi vào `plan/round2-notes.md`, không tự mở rộng.

---

## 0. Đọc trước khi làm

### 0.1 `nb-document-service` là gì

Tên "document" gây hiểu nhầm. Nó **không** phải tính năng tài liệu. Nó là service **lưu notebook**:
notebook, cell, quyền chia sẻ, revision, import/export `.ipynb`. Không có nó thì không có chỗ lưu
notebook. Để khỏi nhầm, **đổi tên thành `nb-notebook-service`** (module Maven, package
`com.vtnexus.notebook.store`, tên Service K8s `nb-notebook-service`, `nginx.conf`, Dockerfile). Chưa
có gì deploy nên đổi tên lúc này rẻ. Từ đây trở đi tài liệu gọi là **notebook-service**.

### 0.2 Hai service, ai làm gì

| Service | Giữ gì | Không được làm gì |
|---|---|---|
| `nb-notebook-service` | notebook, cell, quyền, revision, `.ipynb`, `/me`, `/auth/*` | gọi Kyuubi để chạy lệnh |
| `nb-execution-service` | session Kyuubi, execution, output, log, kết quả SQL, runtime | đọc/ghi bảng của notebook-service |

Execution-service hỏi notebook-service qua `/internal/**` (đã có). Giữ nguyên ranh giới này.

### 0.3 Thư mục, clone, move (F02)

Chưa làm ở vòng 1 và **không chặn**. Xem mục 4 (P3). Nếu chủ dự án không cần, bỏ hẳn và bỏ mục
"Workspace tree" ở thanh trái trong `UI-plan.md`.

---

## 1. Bảo mật: hai chế độ `oidc` và `no-oidc`  (P1)

### 1.1 Ý tưởng chung, đọc kỹ

B **không tự xác thực ai**. B chỉ chuyển credential cho Kyuubi và hỏi "Kyuubi có chấp nhận không?"
bằng `GET <kyuubi>/api/v1/ping`. Kyuubi trả `200` nghĩa là credential hợp lệ **theo đúng cấu hình xác
thực đang bật bên Kyuubi** (OIDC, LDAP, hay NONE). Kyuubi trả `401`/`403` nghĩa là từ chối.

Hai chế độ chỉ khác nhau ở **credential là gì và ai giữ nó**:

| | `oidc` | `no-oidc` |
|---|---|---|
| Kyuubi đang bật | `kyuubi.authentication=OIDC` (hoặc có OIDC trong danh sách) | `LDAP`, `CUSTOM`, `JDBC`, hoặc `NONE` |
| Credential | `Authorization: Bearer <access token Keycloak>` | `Authorization: Basic base64(user:password)` |
| Ai giữ credential | **Trình duyệt** (SPA giữ token, gửi ở mỗi request) | **B** (giữ mã hóa trong phiên server, trình duyệt chỉ có cookie) |
| B có thấy mật khẩu | Không bao giờ | Có, lúc login và khi gọi Kyuubi; phải mã hóa khi lưu |
| CSRF | Không cần (không dùng cookie) | **Bắt buộc** (dùng cookie) |

Cấu hình: `notebook.auth.mode = oidc | no-oidc`. Thiếu hoặc sai giá trị → **khởi động thất bại**.

### 1.2 Chế độ `oidc`

**Luồng**

1. SPA mở `GET /api/v1/auth/config` (không cần đăng nhập) → `{ "mode": "oidc", "issuer", "clientId",
   "scopes", "redirectUri" }`.
2. SPA tự đăng nhập Keycloak bằng Authorization Code + PKCE (`oidc-client-ts`), client **public**
   `kyuubi-notebook`, realm `lakehouse`. Token phải có `aud` chứa `kyuubi` (audience mapper bên
   Keycloak) và `iss` **đúng hostname Kyuubi cấu hình**, nếu không Kyuubi trả 401.
3. SPA gửi `Authorization: Bearer <access_token>` ở mọi request tới B. Token hết hạn thì SPA tự
   refresh (silent renew), B không tham gia.
4. `KyuubiAuthDelegationFilter` (nb-common):
   - Không có header → `401 UNAUTHENTICATED`.
   - Header không bắt đầu bằng `Bearer ` → `401 UNAUTHENTICATED` (ở chế độ này **từ chối `Basic`**).
   - Gọi Kyuubi `/ping` kèm header. `200` → đọc payload JWT, lấy claim `notebook.auth.usernameClaim`
     (mặc định `preferred_username`). **Không có claim → 401**, không dùng `sub`, không dùng tên mặc
     định. (Sửa lỗi "authenticated-user" ở `KyuubiAuthDelegationFilter.java:162`.)
   - `401`/`403` từ Kyuubi → `401`. Kyuubi không trả lời → `503 AUTH_BACKEND_UNAVAILABLE`.
5. Header `Authorization` được chuyển **nguyên vẹn** sang mọi lời gọi Kyuubi của request đó (đã có).

**Cache** (sửa lỗi cache vô hạn): key = SHA-256(header); TTL = min(`notebook.auth.cacheTtl` 60 s,
`exp` của token − now). Dùng Caffeine (`maximumSize`, `expireAfterWrite`) thay `ConcurrentHashMap`
để phần tử hết hạn tự bị dọn.

**Không có** `/auth/login` và `/auth/logout` phía B ở chế độ này; logout là việc của SPA với Keycloak.

### 1.3 Chế độ `no-oidc`

**Luồng**

1. `GET /api/v1/auth/config` → `{ "mode": "no-oidc", "verified": true|false }` (`verified` xem 1.4).
2. SPA hiện form user/password, gọi `POST /api/v1/auth/login` (notebook-service, không cần đăng nhập):
   - B dựng `Authorization: Basic base64(user:password)`, gọi Kyuubi `/ping`.
   - `200` → tạo phiên: bảng `nb_auth.sessions` (`id` ngẫu nhiên 32 byte, `username`,
     `credential_enc`, `created_at`, `expires_at`, `last_seen_at`). `credential_enc` = AES-256-GCM của
     chuỗi `user:password`, khóa từ Secret `notebook-secrets/auth-encryption-key` (32 byte base64),
     nonce 12 byte ngẫu nhiên lưu kèm. Thiếu khóa → **khởi động thất bại** ở chế độ này.
   - Trả cookie `NB_SESSION=<id>`: `HttpOnly`, `Secure` (trừ profile `dev`), `SameSite=Strict`,
     `Path=/`. Body `{ "user": "<username>" }`.
   - `401` từ Kyuubi → `401 INVALID_CREDENTIALS`. Không trả lời → `503`.
3. Mọi request sau: filter đọc cookie → tìm phiên → còn hạn → giải mã credential → đặt
   `Authorization: Basic …` vào context để chuyển sang Kyuubi. **Không gọi lại `/ping` mỗi request**
   ở chế độ này (phiên đã được Kyuubi xác nhận lúc login); gọi lại mỗi `notebook.auth.recheck`
   (mặc định 10 phút) để phát hiện mật khẩu bị đổi/khóa.
4. Phiên hết hạn (`notebook.auth.sessionTtl` mặc định 8 h, trượt theo `last_seen_at`) → `401`, SPA
   quay về form login.
5. `POST /api/v1/auth/logout` xóa dòng phiên và cookie.
6. **CSRF**: bật `CookieCsrfTokenRepository.withHttpOnlyFalse()`; SPA đọc cookie `XSRF-TOKEN` và gửi
   header `X-XSRF-TOKEN` ở mọi request không phải GET. Thiếu → `403`.
7. Execution-service **không có bảng phiên**. Nó nhận cookie, gọi notebook-service
   `GET /internal/auth/session?id=` (kèm `X-Internal-Token`) để lấy `{username, authorizationHeader}`.
   Cache trong execution-service 60 s. Không bao giờ ghi credential ra log.

**Vì sao phải giữ credential**: REST của Kyuubi cần `Authorization` ở **mỗi** lời gọi; không có
token nào khác thay thế. Ghi câu này vào README để người vận hành biết chọn `oidc` khi có thể.

### 1.4 Khi Kyuubi chạy `authentication=NONE` (playground)

Lúc này `/ping` trả `200` với **mọi** header, và Kyuubi lấy tên người dùng từ phần `user` của header
`Basic` **không kiểm mật khẩu** (`BasicAuthenticationHandler`: `allowAnonymous`). Tức là ai cũng tự
xưng được là bất kỳ ai. B **không thể** tự sửa chuyện này.

Xử lý:

1. Lúc khởi động, B thăm dò: gọi `/ping` với `Basic` của user ngẫu nhiên + mật khẩu ngẫu nhiên.
   - `401` → Kyuubi có kiểm. `verified=true`.
   - `200` → Kyuubi **không kiểm**. `verified=false`.
2. `verified=false` chỉ được chạy khi `notebook.auth.allowUnverified=true`; nếu không →
   **khởi động thất bại** với thông báo: "Kyuubi accepts any credential (authentication=NONE);
   set notebook.auth.allowUnverified=true only for playground".
3. Khi chạy với `allowUnverified=true`: log `WARN` mỗi 5 phút, `/auth/config` trả `verified=false`,
   SPA hiện banner "Môi trường không xác thực, chỉ dùng thử nghiệm", form login ẩn ô mật khẩu.
4. Chế độ `oidc` + Kyuubi NONE: cũng áp dụng thăm dò; `verified=false` thì xử lý như trên (token
   không được kiểm chữ ký).

### 1.5 Cấu hình đầy đủ

```yaml
notebook:
  auth:
    mode: oidc            # oidc | no-oidc ; bắt buộc
    usernameClaim: preferred_username   # oidc
    cacheTtl: 60s         # oidc
    sessionTtl: 8h        # no-oidc
    recheck: 10m          # no-oidc
    allowUnverified: false
    oidc:                 # chỉ để trả cho SPA qua /auth/config
      issuer: https://10.29.19.56:32004/realms/lakehouse
      clientId: kyuubi-notebook
      scopes: [openid, profile]
  internalToken: ${NOTEBOOK_INTERNAL_TOKEN}   # bắt buộc, không có giá trị mặc định
  admins: []
```

Gỡ bỏ: mọi giá trị mặc định `dev-internal-secret-token` trong `application.yml` và trong `@Value`.
Thiếu `NOTEBOOK_INTERNAL_TOKEN` → khởi động thất bại. Profile `dev` được phép đặt giá trị cố định
trong `application-dev.yml`.

### 1.6 Endpoint

| Endpoint | Service | Cần đăng nhập | Ghi chú |
|---|---|---|---|
| `GET /api/v1/auth/config` | notebook | Không | mode, verified, thông tin OIDC |
| `POST /api/v1/auth/login` | notebook | Không | chỉ `no-oidc`; `oidc` trả `405` |
| `POST /api/v1/auth/logout` | notebook | Có | chỉ `no-oidc` |
| `GET /api/v1/me` | notebook | Có | `{ user, admin }`, `admin` từ `notebook.admins` (chưa có, phải thêm) |
| `GET /internal/auth/session` | notebook | token nội bộ | chỉ `no-oidc` |
| `GET /api/v1/notebook-status` | execution | **Có** (sửa: hiện đang `permitAll`) | |

`nginx.conf`: route `/api/v1/auth` và `/api/v1/me` đã có; thêm `proxy_cookie_path` không cần vì cookie
`Path=/`.

---

## 2. Việc chặn (P1)

| # | Việc | Ở đâu | Làm gì | Xong khi |
|---|---|---|---|---|
| P1-1 | Dockerfile build được | `backend/*/Dockerfile` | Context build là `backend/`; copy `pom.xml`, `nb-common`, **cả hai** service (hoặc `mvn -pl <svc> -am` với `-f` và tắt module kia bằng profile). Cách đơn giản nhất: copy toàn bộ `backend/` rồi `mvn -pl nb-notebook-service -am package` | `docker build -f backend/nb-notebook-service/Dockerfile backend/` và bản execution đều ra image |
| P1-2 | nginx route đúng | `frontend/nginx.conf` | Đặt location của execution-service **trước**, dùng regex đầy đủ: `^/api/v1/notebooks/[^/]+/sessions$` → execution; `^/api/v1/(notebook-sessions\|executions\|notebook-runtimes\|runtime-specs\|notebook-status)` → execution; còn lại `/api/v1/` → notebook-service | `POST /api/v1/notebooks/x/sessions` tới execution-service |
| P1-3 | Không còn H2 ngầm ở production | `application.yml`, `pom.xml` | H2 dependency đổi `scope=test` + profile `dev` chỉ. Thêm `notebook.storage.mode = kyuubi-db \| postgres \| h2`; `h2` chỉ hợp lệ khi profile `dev`. Thiếu `spring.datasource.url` ở mode khác `h2` → khởi động thất bại. URL bắt đầu `jdbc:sqlite:` hoặc `jdbc:h2:` ở mode `kyuubi-db`/`postgres` → khởi động thất bại | Chạy không có DB URL → tiến trình thoát mã ≠ 0 với thông báo rõ |
| P1-4 | Dựng output thật từ Kyuubi | `ExecutionService.syncAndGetExecution`, `KyuubiRestClient` | (a) `getOperationRowset` **ném lỗi**, không trả `null`; lấy rowset lỗi → execution `FAILED` với `errorMessage`, **không** `SUCCEEDED`, không close operation. (b) Python: rowset 1 hàng 1 cột → parse mime bundle → lưu `outputs` JSON dạng nbformat (`stream`/`display_data`/`error`). (c) SQL: gọi `resultsetmetadata` lấy cột, rowset → lưu `{columns, rows, truncated}`. (d) Lỗi: `event.exception` là object → lấy `message`/`stackTrace`; tìm JSON `ename/evalue/traceback` trong message. (e) Xử lý đủ trạng thái Kyuubi: `CANCELED`→`CANCELED`, `CLOSED`/`UNKNOWN`→`LOST`, `TIMEOUT`→`FAILED`; `RUNTIME_LOST` (409 khi hỏi) → ghi `LOST`, không ném lỗi lặp lại | Test 7–9 ở mục 7 |
| P1-5 | Endpoint còn thiếu | execution-service | `GET /executions/{eid}/outputs`, `/logs?afterSequence=`, `/results?offset=&limit=`; `DELETE /notebook-sessions/{sid}`; `GET /notebook-sessions/{sid}/runtimes`; `POST /notebook-runtimes/{id}:interrupt`. Log lấy từ Kyuubi `/operations/{op}/log?fetchorientation=FETCH_NEXT` mỗi lần sync, nối vào bảng `nb_execution.execution_logs(execution_id, sequence, line)` | Mọi endpoint ở bảng 6.3 vòng 1 tồn tại |
| P1-6 | Chart ba Deployment | `notebook-helm/` | Deployment + Service cho `nb-notebook-service`, `nb-execution-service`, `nb-frontend`; chỉ frontend có NodePort/Ingress; ConfigMap render `application.yaml` mỗi service; Secret `notebook-secrets` (`db-password`, `internal-token`, `auth-encryption-key`); probe cổng 8081/8083; NetworkPolicy; PDB. Mọi value trong `values.yaml` phải được template dùng | `helm template` ra 3 Deployment; `helm install` lên cluster, 3 pod Ready |
| P1-7 | Frontend thật | `frontend/` | Vue 3 + Vite + Element Plus + Monaco + Pinia theo `plan/UI-plan.md` phạm vi MVP. Xóa `public/index.html` mockup. Đăng nhập theo `/auth/config` (1.2 hoặc 1.3). Không CDN. HTML output trong `iframe sandbox` | Nghiệm thu mục 10 của `UI-plan.md` |
| P1-8 | Test | cả hai service | Toàn bộ mục 7 dưới đây. WireMock giả Kyuubi, Testcontainers PostgreSQL | `mvn verify` xanh, có báo cáo Surefire |

---

## 3. Việc còn lại (P2)

| # | Việc | Ở đâu | Làm gì |
|---|---|---|---|
| P2-1 | `/events` long-poll thật | `NotebookExecutionController` | Giữ `DeferredResult`; thêm `afterSequence`; vòng chờ: sync → nếu có sự kiện mới (đổi trạng thái hoặc log mới) trả ngay, không thì ngủ 500 ms rồi sync lại cho tới `waitMillis`; chạy trên `TaskExecutor` riêng (`notebook.events.poolSize`), **không** `ForkJoinPool.commonPool()` |
| P2-2 | Cancel không nuốt lỗi | `KyuubiRestClient.cancelOperation` | Ném lỗi như các hàm khác; chỉ ghi `CANCELED` khi Kyuubi trả 2xx |
| P2-3 | URL reorder | `NotebookCellController` | Class mapping `/api/v1/notebooks/{notebookId}` và method `PUT /cells:reorder`, kết quả `/api/v1/notebooks/{id}/cells:reorder` |
| P2-4 | `includeOutputs` export | notebook-service ↔ execution-service | Thêm `GET /internal/executions/latest?notebookId=&user=` ở execution-service; notebook-service gọi khi `includeOutputs=true` để nhét output của **chính người export** |
| P2-5 | Dọn revision | `NotebookService.snapshotRevision` | Sau khi ghi, xóa revision cũ nhất không `is_protected` vượt `maxRevisions` |
| P2-6 | Cấu hình Spark của người dùng | execution-service | `POST /notebooks/{id}/sessions` nhận body `{ "configs": {...} }`, lọc theo `allowedSessionConfigKeys`, truyền vào `openSession` (hiện luôn `null`) |
| P2-7 | `updatePermissions` validate | `NotebookService.updatePermissions` | `principalId` không rỗng, `role` ∈ {EDITOR, RUNNER, VIEWER}; sai → 400, không NPE |
| P2-8 | `/notebook-status` | execution-service | Yêu cầu đăng nhập; `kyuubi` = kết quả `/ping` thật (200→`HEALTHY`, khác→`UNAVAILABLE`); `persistence` = `SELECT 1`; bỏ các trường viết cứng |
| P2-9 | Maven đóng gói | repo | Xóa `apache-maven-3.9.11/` khỏi git (jar bị `.gitignore` chặn nên bản trong git không chạy được). Thêm `mvnw` (Maven Wrapper) ở `backend/` |
| P2-10 | Phase 0 có bằng chứng | `plan/phase0-findings.md` | Dán output `curl -i` của `/ping` không credential và với credential sai; trích đúng dòng `kyuubi.authentication` và `kyuubi.metadata.store.jdbc.database.type` từ config đang chạy |

---

## 4. Tùy chọn (P3), chờ chủ dự án quyết

| # | Việc | Ghi chú |
|---|---|---|
| P3-1 | Thư mục, `:clone`, `:move` (F02) | Nếu bỏ: xóa `NotebookFolder*`, cột `folder_id`, và mục Workspace tree ở UI-plan |
| P3-2 | `GET /notebooks:search` (F04) | Repo đã có query, chỉ thiếu endpoint |

---

## 5. Tài liệu (P2)

| File | Việc |
|---|---|
| `plan/API-detail.md` | Thay bằng bản 47 dòng F01–F47 (bản hiện tại là 29 dòng cũ) |
| `plan/Chart.md` | Bỏ Java 21, Vault, `oauth2Login`, Spring Session JDBC; ghi Java 17 / Boot 3.3.4; ba Deployment; `auth.mode`, `storage.mode` |
| `README.md` | Sửa link `../notebook-api-parity.md` → `plan/API-detail.md`; thêm mục "Đăng nhập" (hai chế độ, câu cảnh báo ở 1.3 và 1.4); thêm giới hạn "kết quả chưa lấy về có thể `LOST` nếu Kyuubi đóng operation trước khi bạn quay lại" |
| `BUILD.md` | Bỏ hướng dẫn `apache-maven-3.9.11`; thêm build ba image và chạy local với profile `dev` |

---

## 6. Thứ tự thực hiện

| Bước | Việc | Xong khi |
|---|---|---|
| 1 | Đổi tên service (0.1) | Build xanh, `nginx.conf` và Dockerfile dùng tên mới |
| 2 | Mục 1 (hai chế độ xác thực) + P1-3 (storage) | Test 1–6 mục 7 xanh |
| 3 | P1-4, P1-5, P2-1, P2-2 | Test 7–12 xanh |
| 4 | P1-1, P1-2, P2-9 | Ba image build được; `docker compose` local (thêm `compose.yaml` với PostgreSQL + WireMock Kyuubi) lên được |
| 5 | P1-7 frontend | Nghiệm thu UI |
| 6 | P1-6 chart | Smoke test mục 8 |
| 7 | P2 còn lại, mục 5 tài liệu | Không còn link hỏng, không còn nhắc Java 21/Vault |

Mỗi bước một hoặc nhiều commit riêng. Mọi khởi động thất bại cố ý (thiếu `mode`, thiếu token nội
bộ, H2 ngoài `dev`, Kyuubi NONE không `allowUnverified`) phải in **một dòng** nguyên nhân rồi thoát,
không stack trace dài.

---

## 7. Test bắt buộc (`mvn verify` phải chạy hết)

**Xác thực, chế độ `oidc`** (WireMock giả Kyuubi)
1. Không header → 401. Header `Basic` → 401. `X-User` → không ảnh hưởng (401).
2. `/ping` giả trả 200, JWT có `preferred_username=alice` → request thành công với user `alice`.
3. JWT **không** có claim → 401 (không còn `authenticated-user`).
4. `/ping` trả 401 → 401; WireMock tắt → 503.

**Xác thực, chế độ `no-oidc`**
5. Login đúng → cookie `HttpOnly`; bảng `nb_auth.sessions` không chứa `user:password` dạng rõ; request sau dùng cookie tới execution-service thành công (qua `/internal/auth/session`).
6. POST không `X-XSRF-TOKEN` → 403. Logout → cookie bị xóa, request sau 401.
7. Thăm dò NONE: WireMock `/ping` trả 200 cho mọi header; `allowUnverified=false` → khởi động thất bại; `=true` → khởi động, `/auth/config.verified=false`.

**Chạy cell**
8. Kyuubi `RUNNING` rồi `FINISHED`, rowset 1 hàng mime bundle → `SUCCEEDED`, `outputs` có `stream` + `display_data`.
9. Kyuubi `ERROR` với JSON `ename/evalue/traceback` trong `exception.message` → `FAILED`, output `error` có đủ ba trường.
10. `FINISHED` nhưng rowset trả 500 → `FAILED`, operation **không** bị close, không `SUCCEEDED`.
11. Kyuubi tắt khi hỏi trạng thái → 503, execution vẫn `RUNNING` (hỏi lại được). Kyuubi trả 404 → execution `LOST`, hỏi lại trả `LOST` không ném 409.
12. Hai request `GET /executions/{eid}` song song lúc vừa `FINISHED` → cùng kết quả, Kyuubi `/rowset` được gọi đúng **một** lần.
13. `cancel` khi Kyuubi trả 500 → execution vẫn `RUNNING`, lỗi 502 trả về client.

**Quyền và lưu trữ**
14. RUNNER chạy được, EDITOR không mở được `/permissions`, user khác đọc `/executions/{eid}` → 404.
15. Khởi động mode `postgres` không URL → thất bại; mode `kyuubi-db` với `jdbc:sqlite:` → thất bại.
16. Restart service với PostgreSQL (Testcontainers) → notebook, execution, phiên `no-oidc` còn nguyên.

**Tích hợp (chạy tay, ghi kết quả vào `plan/round2-notes.md`)**
17. Với Kyuubi thật: `SELECT 1` ra bảng; `print(1+1)` ra `2`; `pd.DataFrame` ra HTML trong iframe; `1/0` ra traceback.

---

## 8. Smoke test sau khi `helm install`

1. `helm test` đạt; 3 pod Ready; `GET /api/v1/auth/config` qua NodePort trả đúng `mode`.
2. Đăng nhập theo chế độ đã cấu hình; `/me` trả đúng user.
3. Chạy 4 cell ở test 17 qua giao diện.
4. Xóa pod execution giữa lúc cell đang chạy → mở lại trang vẫn thấy execution và sau đó thấy kết quả.
5. Xóa pod notebook-service → notebook vẫn còn sau khi pod lên lại.
