# Plan sửa `notebook-api` sau nghiệm thu: tách microservice, ủy quyền xác thực cho Kyuubi

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent.
Đầu vào: biên bản nghiệm thu ngày 2026-10-13 (mã lỗi `B1`–`B6`, `A1`–`A5`, `S1`–`S10`, `D1`–`D3` ở
mục 9). Đặc tả gốc: `plan/API-detail.md` (sẽ được thay ở pha 6), `plan/UI-plan.md`, `plan/Chart.md`.

---

## 0. Quyết định đã chốt (không tự đổi)

| # | Quyết định | Nguồn |
|---|---|---|
| Q1 | **Giữ nền tảng hiện tại**: Java 17, Spring Boot 3.3.4. Không nâng lên Java 21 / Boot 3.5. Không dùng Vault; secret là Kubernetes Secret. | Chủ dự án |
| Q2 | **Xác thực thuộc về Kyuubi** (OIDC hoặc LDAP đang cấu hình bên Kyuubi). B không tự kiểm JWT, không tự gọi LDAP, không có user/mật khẩu riêng. | Chủ dự án |
| Q3 | **Lưu trữ có tùy chọn dùng chung DB với Kyuubi** (DB của Kyuubi metadata store), ngoài tùy chọn PostgreSQL riêng. H2 chỉ còn cho profile `dev`. | Chủ dự án |
| Q4 | **Backend tách microservice** để sửa độc lập từng phần. | Chủ dự án |
| Q5 | **Output của một execution chỉ người chạy (`submittedBy`) đọc được.** Kết quả lấy bằng quyền Ranger của người chạy; cho người khác xem là vượt Ranger. Thành viên khác thấy code, không thấy output. | Hệ quả của F24 |

Hệ quả của Q1: không có virtual threads, nên long-poll (`/events`) phải dùng xử lý bất đồng bộ của
Servlet (`DeferredResult`), không giữ thread Tomcat trong lúc chờ.

---

## 1. Kiến trúc đích

```
Trình duyệt
   │  (Bearer token Keycloak  hoặc  cookie NB_SESSION ở chế độ LDAP)
   ▼
nb-frontend  (nginx: phục vụ SPA + reverse proxy /api/v1/*)
   ├── /api/v1/notebooks*, /api/v1/notebook-folders*, /api/v1/auth/*, /api/v1/me ──▶ nb-document-service
   └── /api/v1/notebook-sessions*, /api/v1/executions*, /api/v1/notebook-runtimes*,
       /api/v1/runtime-specs, /api/v1/notebook-status                          ──▶ nb-execution-service
                                                                                      │
nb-execution-service ──/internal/access──▶ nb-document-service (quyền + nội dung cell)  │
nb-execution-service ──────────── REST + credential của người dùng ──────────▶ Kyuubi ──▶ Spark engine
nb-document-service, nb-execution-service ──▶ DB (schema riêng mỗi service, mục 4)
Mọi service ──/api/v1/ping (xác thực)──▶ Kyuubi
```

### Module Maven (`backend/` thành multi-module)

| Module | Loại | Trách nhiệm | Sở hữu dữ liệu |
|---|---|---|---|
| `nb-common` | thư viện | Lọc xác thực ủy quyền Kyuubi (mục 3), envelope lỗi + `requestId`, client Kyuubi `/ping`, header nội bộ | schema `nb_auth` (chỉ chế độ LDAP) |
| `nb-document-service` | Boot app | Notebook, thư mục, cell, revision, quyền, import/export `.ipynb`, `/me` | schema `nb_document` |
| `nb-execution-service` | Boot app | Notebook session, Kyuubi client, execution, log, output, kết quả SQL, runtime, trạng thái | schema `nb_execution` |

**Quy tắc ranh giới**

- Service không đọc/ghi bảng của service khác. Execution không ghi `outputs` vào cell; UI ghép
  output từ execution-service theo `cellId`.
- Execution-service lấy quyền và nội dung cell qua
  `GET /internal/notebooks/{id}/access?user=` và `GET /internal/notebooks/{id}/cells/{cid}` của
  document-service. Endpoint `/internal/**` **không** đi qua nginx, chỉ nhận header
  `X-Internal-Token` khớp secret chung; NetworkPolicy chỉ cho execution-service gọi vào.
- Không thêm Spring Cloud, message broker hay service discovery. Gọi nhau bằng DNS Service.

---

## 2. Pha 0: kiểm tra trước khi code (bắt buộc, ghi kết quả vào `plan/phase0-findings.md`)

| # | Kiểm tra | Cách | Ảnh hưởng |
|---|---|---|---|
| P0.1 | Kyuubi đang dùng `kyuubi.authentication` = `OIDC`, `LDAP` hay cả hai | Đọc `kyuubi-defaults.conf` đang chạy | Bật chế độ nào ở mục 3 |
| P0.2 | `GET /api/v1/ping` của Kyuubi **trả 401 khi không có credential** | `curl -i <kyuubi>/api/v1/ping` | Nếu trả 200 (Kyuubi `NONE`) thì cơ chế mục 3 không xác thực được gì: **dừng**, báo lại |
| P0.3 | Loại DB của Kyuubi metadata store: `kyuubi.metadata.store.jdbc.database.type` | Đọc config | `SQLITE` (mặc định của Kyuubi, file riêng mỗi pod) thì **không dùng chung được**, phải chọn chế độ `postgres`. `POSTGRESQL` hoặc `MYSQL` thì dùng được |
| P0.4 | Có tạo được DB user riêng cho B trên server đó không | Hỏi DBA | Khuyến nghị user riêng, chỉ có quyền trên schema `nb_*` |

---

## 3. Xác thực ủy quyền cho Kyuubi (sửa `B1`, Q2)

B không tự quyết ai là ai. Kyuubi chấp nhận credential nào thì B chấp nhận credential đó.

### 3.1 Chế độ OIDC (khuyến nghị)

1. SPA đăng nhập Keycloak bằng **Authorization Code + PKCE**, client **public** `kyuubi-notebook`
   (realm `lakehouse`), có audience mapper `kyuubi`. Token lấy qua **đúng hostname issuer mà Kyuubi
   cấu hình**, nếu không `iss` lệch và Kyuubi trả 401.
2. SPA gửi `Authorization: Bearer <access_token>` tới B.
3. `nb-common` gọi `GET <kyuubi>/api/v1/ping` kèm nguyên header đó.
   - `200` → hợp lệ. Tên người dùng = claim trong payload JWT, **cùng claim Kyuubi dùng** (mặc định
     `preferred_username`, đặt ở `notebook.auth.usernameClaim`). Chỉ đọc payload **sau khi** Kyuubi
     đã chấp nhận token.
   - `401`/`403` → trả `401 UNAUTHENTICATED`.
   - Kyuubi không trả lời → `503 AUTH_BACKEND_UNAVAILABLE`, **không** cho qua.
4. Cache kết quả theo SHA-256 của header, TTL = min(`notebook.auth.cacheTtl` mặc định 60 s, thời
   điểm `exp` của token). Không cache kết quả thất bại quá 5 s.
5. Header `Authorization` được chuyển nguyên sang mọi lời gọi Kyuubi của request đó.

### 3.2 Chế độ LDAP (tùy chọn, bật khi Kyuubi dùng LDAP)

1. `POST /api/v1/auth/login` (document-service) nhận `{username, password}`, dựng header
   `Basic`, gọi Kyuubi `/api/v1/ping`. `200` → tạo phiên.
2. Phiên lưu ở bảng `nb_auth.session`: id ngẫu nhiên, username, **credential mã hóa AES-GCM** bằng
   khóa từ Secret `notebook-secrets/auth-encryption-key`, `expires_at` (mặc định 8 h, trượt theo hoạt
   động). Cookie `NB_SESSION`: `HttpOnly`, `Secure`, `SameSite=Strict`.
3. Mọi request có cookie: `nb-common` giải mã credential và dùng làm header `Basic` khi gọi Kyuubi.
4. Bật **CSRF** cho chế độ này (`CookieCsrfTokenRepository`), vì xác thực bằng cookie.
5. `POST /api/v1/auth/logout` xóa phiên. Log không bao giờ ghi credential, kể cả ở DEBUG.

Ghi rõ trong README: chế độ LDAP buộc B giữ mật khẩu (đã mã hóa) trong thời gian phiên, vì REST của
Kyuubi cần credential ở mỗi lời gọi. OIDC không có rủi ro này.

### 3.3 Gỡ bỏ

- Xóa `DevHeaderAuthFilter`, mọi giá trị mặc định `alice`, mọi đọc header `X-User`.
- `/me` lấy `admin` từ cấu hình `notebook.admins` (danh sách username), không suy từ tên.
- `/actuator/**` chuyển sang cổng quản trị riêng (`management.server.port: 8081`), không `permitAll`
  trên cổng chính.
- Profile `dev` được phép có chế độ `notebook.auth.mode=dev-header` để chạy local, nhưng **khởi động
  thất bại** nếu chế độ này được bật cùng profile khác `dev`.

---

## 4. Lưu trữ (sửa `B5` phần DB, `B6`, Q3)

Cấu hình `notebook.storage.mode`:

| Chế độ | Kết nối | Dùng khi |
|---|---|---|
| `kyuubi-db` | Server DB của Kyuubi metadata store (PostgreSQL hoặc MySQL). URL/user/password lấy từ values `storage.kyuubiDb.*`, đặt **cùng giá trị** với `kyuubi.metadata.store.jdbc.*` của Kyuubi, hoặc user riêng trên cùng server (khuyến nghị, P0.4) | Muốn phụ thuộc lưu trữ bên Kyuubi |
| `postgres` | PostgreSQL riêng | Kyuubi dùng SQLite, hoặc muốn tách hẳn |
| `h2` | H2 trong bộ nhớ | **Chỉ** profile `dev`; khởi động thất bại ở profile khác |

Quy tắc:

1. B chỉ dùng schema của mình: `nb_document`, `nb_execution`, `nb_auth`. **Không đọc, không ghi,
   không migrate** bảng nào của Kyuubi.
2. Migration bằng **Flyway**, mỗi service một thư mục migration và một bảng lịch sử riêng
   (`flyway_schema_history` trong schema của service). Bỏ `ddl-auto: update`, đặt `validate`.
   Boot 3.3.4 cần thêm `flyway-database-postgresql` (và `flyway-mysql` nếu P0.3 là MySQL).
3. Bỏ `driverClassName: org.h2.Driver` và `H2Dialect` khỏi `application.yml` chung; mỗi chế độ là
   một profile (`application-kyuubi-db.yml`, `application-postgres.yml`, `application-dev.yml`).
   Để Spring Boot tự suy driver từ URL.
4. `kyuubi-db` với URL `jdbc:sqlite:` → khởi động thất bại với thông báo rõ (P0.3).

---

## 5. `nb-document-service`

| # | Việc | Sửa lỗi | Xong khi |
|---|---|---|---|
| D-1 | `checkAccess(notebook, user, need)` theo thứ bậc `VIEWER < RUNNER < EDITOR < OWNER` | `A1`, `A2` | RUNNER nhận 403 khi sửa cell; VIEWER nhận 403 khi `/internal/access` hỏi quyền chạy |
| D-2 | `GET /notebooks`: bỏ tham số `owner`; trả notebook mình sở hữu **và** được chia sẻ | `A4` | `?owner=bob` không còn tác dụng |
| D-3 | API quyền: `GET/PUT /notebooks/{id}/permissions` (chỉ OWNER) | `A5`, F23 | Chia sẻ RUNNER rồi người đó chạy được, không sửa được |
| D-4 | Xóa mềm: cột `deleted_at`, ẩn khỏi mọi truy vấn, tên/path được giải phóng | `S5` | Xóa rồi tạo lại cùng tên được; row cũ còn trong DB |
| D-5 | Sửa URL: khai báo **nguyên đường dẫn** `/api/v1/notebooks:import`, `/api/v1/notebooks/{id}/cells:reorder` (không để Spring ghép `/`) | `S3` | Test gọi đúng URL spec trả 2xx; URL có `/:import` trả 404 |
| D-6 | Import đọc `metadata.kyuubi.language` **trước**, magic dòng đầu là dự phòng | `.ipynb` khứ hồi | Export rồi import lại: cell SQL vẫn là SQL |
| D-7 | `includeOutputs=true` chỉ cho OWNER; export lấy output của **chính người export** từ execution-service | `S6`, Q5 | EDITOR bật `includeOutputs` nhận 403 |
| D-8 | Áp giới hạn từ cấu hình: kích thước cell, số cell, kích thước import, số revision (cắt revision cũ không bảo vệ) | `S7` | Mỗi giới hạn có test vượt ngưỡng trả 413/422 |
| D-9 | `snapshotRevision` lỗi thì **ném lỗi**, không nuốt; revision không serialize entity JPA trực tiếp mà serialize DTO | `S9` | Không còn `catch` rỗng |
| D-10 | Endpoint nội bộ `/internal/notebooks/{id}/access?user=`, `/internal/notebooks/{id}/cells/{cid}` | ranh giới | Gọi không có `X-Internal-Token` hợp lệ trả 401 |

---

## 6. `nb-execution-service` (sửa `B2`, `B3`)

### 6.1 Kyuubi client

- **Bỏ toàn bộ fallback giả** (`mock-session-…`, `kyuubi-pod-0`, `FINISHED` khi lỗi, `"OK"`). Lỗi Kyuubi
  đổi thành lỗi kiểu rõ:

| Tình huống | Mã | HTTP |
|---|---|---|
| Kết nối/timeout | `KYUUBI_UNAVAILABLE` (retryable) | 503 |
| Kyuubi trả 401/403 | `KYUUBI_AUTH_FAILED` | 401 |
| Session/operation không còn trên Kyuubi | `RUNTIME_LOST` | 409 |
| Lỗi khác | `KYUUBI_ERROR`, kèm message gốc | 502 |

- **Gọi đúng pod**: sau `POST /api/v1/sessions`, mọi lời gọi của session đó đi tới
  `http://{kyuubiInstance}`. Cờ `notebook.kyuubi.pinToInstance` (mặc định `true`; `false` chỉ dùng
  khi dev qua port-forward). (`S4`)
- `configs` khi mở session = `notebook.kyuubi.sessionConfigs` (có `output.mode=NOTEBOOK`) + các key
  người dùng gửi **nằm trong** `allowedSessionConfigKeys`; key ngoài danh sách trả 422.

### 6.2 Vòng đời execution

```
QUEUED ──submit──▶ RUNNING ──event FINISHED──▶ SUCCEEDED
                      ├──event ERROR/TIMEOUT──▶ FAILED
                      ├──cancel─────────────▶ CANCELED
                      └──session mất─────────▶ LOST
```

1. `POST /notebook-sessions/{sid}/executions {cellId}`: hỏi document-service quyền **RUNNER** trở
   lên và nội dung cell; ghi execution `QUEUED`; gửi statement (`runAsync=true`); lưu
   `kyuubiOperationId`; chuyển `RUNNING`. **Không** gán kết quả.
2. **Đồng bộ trạng thái theo yêu cầu của người dùng**, không chạy poller nền: khi client gọi
   `GET /executions/{eid}` hoặc `/events`, service gọi Kyuubi `GET /operations/{op}/event` **bằng
   credential của người gọi**. Vì vậy B không phải lưu token để chạy nền.
3. Khi lần đầu thấy kết thúc: khóa dòng execution (`SELECT … FOR UPDATE`), lấy kết quả **một lần**,
   lưu vào DB, rồi `PUT /operations/{op} {"action":"close"}`. Lần đọc sau lấy từ DB. Lý do:
   `rowset` dùng con trỏ `FETCH_NEXT`, hai request đọc song song sẽ chia nhau mất dòng.
4. Dựng output:
   - **Python**: `rowset` một hàng một cột là mime bundle JSON → `text/plain` thành `stream` stdout,
     các MIME khác thành `display_data`.
   - **SQL**: `resultsetmetadata` + trang đầu `rowset` (giới hạn `notebook.results.maxRows`) → lưu
     schema + dòng; trang sau lấy qua `/results?offset=&limit=` (đọc tiếp từ Kyuubi trước khi close,
     hoặc giới hạn ở số dòng đã lưu, ghi rõ "đã cắt bớt").
   - **Lỗi**: tách `ename`, `evalue`, `traceback` từ JSON trong message lỗi; không tách được thì đưa
     nguyên message vào `evalue`.
5. Log: `GET /operations/{op}/log?fetchorientation=FETCH_NEXT` mỗi lần đồng bộ, nối vào bảng log có
   `sequence`.
6. `execution_count` tăng theo notebook session (giữ logic hiện có).
7. Giới hạn đã biết, ghi vào README: nếu người dùng không quay lại trước khi Kyuubi tự đóng operation
   hoặc session rảnh, kết quả chưa lấy về sẽ mất (`LOST`).

### 6.3 Endpoint (sửa `S2`)

| Endpoint | Quyền | Ghi chú |
|---|---|---|
| `POST /notebooks/{id}/sessions` | RUNNER+ | Một session cho mỗi (notebook, user) |
| `DELETE /notebook-sessions/{sid}` | chủ session | Đóng Kyuubi session |
| `GET /notebook-sessions/{sid}/runtimes` | chủ session | Trạng thái `idle`/`busy`/`starting`/`lost` |
| `POST /notebook-runtimes/{id}:interrupt` | chủ session | Cancel operation đang chạy |
| `POST /notebook-runtimes/{id}:restart` | chủ session | Đóng + mở session mới, reset `execution_count`; thay cho `/notebook-sessions/{id}:restart` |
| `POST /notebook-sessions/{sid}/executions` | RUNNER+ và chủ session | |
| `GET /executions/{eid}` | **chỉ `submittedBy`** (Q5) | Đồng bộ trạng thái |
| `GET /executions/{eid}/events?afterSequence=&waitMillis=` | chỉ `submittedBy` | `DeferredResult`, trần `notebook.events.maxWait` (10 s) |
| `GET /executions/{eid}/logs`, `/outputs`, `/results?offset=&limit=` | chỉ `submittedBy` | |
| `POST /executions/{eid}:cancel` | chỉ `submittedBy` | |
| `GET /executions?notebookId=&cellId=` | chính mình | Lấy execution mới nhất của mình cho từng cell |
| `GET /runtime-specs` | đã đăng nhập | Không lộ command |
| `GET /notebook-status` | đã đăng nhập | Số liệu **thật**: đếm session/execution trong DB, `kyuubi` = kết quả `/ping` thật (`S10`) |

Mọi kiểm "chủ session"/`submittedBy` sai thì trả **404**, không 403, để không lộ id tồn tại (`A3`).

---

## 7. `nb-frontend` (sửa `B4`)

- Viết lại bằng **Vue 3 + Vite + Element Plus + Monaco + Pinia** theo `plan/UI-plan.md`, phạm vi
  **MVP** (F30–F33, F35, F36, F42 và hiển thị F10, F18–F20).
- **Không** còn dữ liệu mẫu viết cứng, `setTimeout` giả, hay `alert` giả. Mọi nút gọi API thật.
- Đăng nhập: OIDC PKCE (thư viện `oidc-client-ts`) hoặc form LDAP, theo `/api/v1/auth/config` trả về.
- HTML trong output render trong `iframe sandbox` (không `allow-same-origin`).
- Không tải tài nguyên từ CDN (mạng nội bộ có thể chặn): đóng gói icon/font vào bundle.
- Image: build Node, chạy nginx; `nginx.conf` proxy `/api/v1/*` như mục 1 và **chặn** `/internal/**`.

---

## 8. Đóng gói và chart (sửa `B5`)

### 8.1 Image

| Image | Dockerfile | Nền |
|---|---|---|
| `notebook-document` | `backend/nb-document-service/Dockerfile` | build `maven:3.9-eclipse-temurin-17`, chạy `eclipse-temurin:17-jre`, user không phải root |
| `notebook-execution` | `backend/nb-execution-service/Dockerfile` | như trên |
| `notebook-frontend` | `frontend/Dockerfile` | build `node:20`, chạy `nginx` không root |

`scripts/build-dist.sh` đổi thành build ba image; bỏ bước copy frontend vào `static/` của backend.
Gỡ `apache-maven-3.9.11/` khỏi git (dùng `./mvnw` hoặc image Maven).

### 8.2 Chart `notebook-helm`

- Ba Deployment + Service: `document`, `execution`, `frontend`. Chỉ `frontend` expose ra ngoài
  (NodePort 32380 hoặc Ingress).
- `values.yaml` thêm: `auth.mode` (`oidc`/`ldap`), `auth.usernameClaim`, `storage.mode`
  (`kyuubi-db`/`postgres`), `storage.kyuubiDb.*` / `storage.postgres.*`, `kyuubi.pinToInstance`,
  `notebook.admins`. Mọi value khai báo phải được template dùng (hiện `oidc`, `sessionConfigs`,
  `allowedSessionConfigKeys`, `ingress` đang không dùng).
- ConfigMap mỗi service (render `application.yaml`), annotation `checksum/config`.
- Secret tạo tay `notebook-secrets`: `db-password`, `internal-token`, `auth-encryption-key` (LDAP).
  Không Vault (Q1).
- Probe Actuator trên cổng 8081: readiness gồm `db` + Kyuubi `/ping` (execution-service), liveness
  chỉ `livenessState`; thêm `startupProbe`.
- NetworkPolicy: frontend → document/execution; execution → document (`/internal`), pod Kyuubi
  :10099; document/execution → DB; mọi service → Kyuubi :10099 (xác thực) và DNS.
- ServiceAccount không mount token; không Role.
- `securityContext`: `runAsNonRoot`, `readOnlyRootFilesystem`, drop capabilities.
- PDB khi `replicaCount > 1`; `helm test` gọi readiness của cả hai service.

---

## 9. Bảng đối chiếu lỗi nghiệm thu → việc sửa

| Mã | Lỗi | Sửa ở |
|---|---|---|
| B1 | Giả danh qua `X-User`, mọi JWT thành `alice`, `X-User: admin` | 3 |
| B2 | Chạy cell giả, luôn `FINISHED`/`OK` | 6.2 |
| B3 | Fallback giả khi Kyuubi lỗi | 6.1 |
| B4 | Frontend mockup | 7 |
| B5 | Không Dockerfile; driver H2 cứng làm crash với PostgreSQL | 4, 8 |
| B6 | Dữ liệu mất khi restart (H2 in-memory) | 4 |
| A1 | RUNNER sửa được notebook | D-1 |
| A2 | VIEWER mở được session | D-1, 6.3 |
| A3 | Chạy/xem/hủy/restart không kiểm quyền | 6.3 |
| A4 | `?owner=` liệt kê notebook người khác | D-2 |
| A5 | Không có API quyền | D-3 |
| S2 | Thiếu endpoint MVP | 6.3 |
| S3 | URL `/:import`, `/:reorder` | D-5 |
| S4 | Không gọi đúng pod `kyuubiInstance` | 6.1 |
| S5 | Xóa cứng | D-4 |
| S6 | `includeOutputs` không giới hạn OWNER | D-7 |
| S7 | Giới hạn kích thước không áp dụng | D-8 |
| S9 | Revision lỗi bị nuốt | D-9 |
| S10 | `/notebook-status` viết cứng | 6.3 |
| — | Export→import làm cell SQL thành Python | D-6 |
| D1 | `plan/API.md` là API notebook bên trong Kyuubi | 10 |
| D2 | `plan/API-detail.md` là bản bảng khả năng cũ | 10 |
| D3 | 3 link README hỏng | 10 |

`S1` (nền tảng Java 21 / Boot 3.5) **không sửa** theo Q1.

---

## 10. Tài liệu

- Xóa `plan/API.md` (mô tả API notebook nằm trong Kyuubi, trái với kiến trúc này).
- Thay `plan/API-detail.md` bằng bản bảng khả năng mới nhất (47 dòng, F01–F47).
- `plan/Chart.md`: cập nhật bảng nền tảng về Java 17 / Boot 3.3.4, bỏ Vault, bỏ `oauth2Login` và
  Spring Session JDBC, thêm `auth.mode` / `storage.mode` / ba Deployment.
- README: sửa 3 link về `plan/…`; thêm mục "Đăng nhập" (OIDC/LDAP qua Kyuubi) và giới hạn ở 6.2 bước 7.
- `BUILD.md`: hướng dẫn build ba image, chạy local bằng profile `dev`.

---

## 11. Thứ tự thực hiện

| Pha | Việc | Xong khi |
|---|---|---|
| 0 | Mục 2 | `plan/phase0-findings.md` có đủ P0.1–P0.4 |
| 1 | Tách multi-module, `nb-common` với xác thực mục 3, lưu trữ mục 4 | Hai service khởi động ở profile `postgres`; request không credential trả 401; `X-User` không còn tác dụng |
| 2 | Document-service mục 5 | Test D-1…D-10 xanh |
| 3 | Execution-service mục 6 | Test mục 12 nhóm "Chạy" xanh với Kyuubi thật hoặc Kyuubi giả lập bằng WireMock |
| 4 | Frontend mục 7 | Nghiệm thu UI MVP ở `plan/UI-plan.md` mục 10 |
| 5 | Image + chart mục 8 | `helm install` lên cluster, ba pod `Ready`, `helm test` đạt |
| 6 | Tài liệu mục 10 | Không còn link hỏng; không còn nhắc Java 21/Vault |

Mỗi pha một hoặc nhiều commit riêng; không gộp pha.

---

## 12. Test bắt buộc (thay cho `contextLoads` duy nhất hiện nay)

**Xác thực**
1. Không credential → 401. Gửi `X-User: bob` → 401.
2. Bearer bị Kyuubi từ chối (WireMock `/ping` trả 401) → 401; Kyuubi không trả lời → 503.
3. LDAP: login đúng → cookie `HttpOnly`; DB không chứa mật khẩu dạng rõ; POST không CSRF token → 403.

**Quyền**
4. RUNNER sửa cell → 403; RUNNER chạy cell → 2xx; VIEWER mở session → 403.
5. User B đọc `/executions/{eid}` của user A → 404.
6. `GET /notebooks?owner=bob` không trả notebook của bob.

**Chạy** (WireMock giả Kyuubi, có thêm một bản chạy tay với Kyuubi thật)
7. Kyuubi trả operation `RUNNING` rồi `FINISHED` → execution đi `RUNNING → SUCCEEDED`, output đúng bundle.
8. Kyuubi `ERROR` với JSON traceback → output `error` có `ename`/`evalue`/`traceback`.
9. Kyuubi tắt → 503 `KYUUBI_UNAVAILABLE`, **không** có execution `SUCCEEDED`.
10. Mọi lời gọi sau mở session đi tới host `kyuubiInstance`.
11. Hai request đọc kết quả song song → cùng một tập dòng, không mất dòng.

**Tài liệu và lưu trữ**
12. Export rồi import lại notebook có cell SQL → cell vẫn SQL.
13. Restart service (DB PostgreSQL thật trong Testcontainers) → notebook và execution còn nguyên.
14. `storage.mode=kyuubi-db` với URL `jdbc:sqlite:` → khởi động thất bại với thông báo rõ.
15. Gọi đúng `/api/v1/notebooks:import` và `/cells:reorder` → 2xx.

**Smoke trên cluster** (sau pha 5): đăng nhập, `SELECT 1` ra bảng, `print(1+1)` ra `2`, bảng pandas
hiện trong iframe, `1/0` ra traceback, xóa pod execution giữa lúc chạy rồi mở lại vẫn thấy kết quả.
