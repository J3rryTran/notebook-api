# Plan sửa backend `notebook-api`: lưu vào MariaDB (database `test`, schema theo `self_service`), ba chế độ bảo mật, OIDC → Kyuubi → Ranger

Trạng thái: chưa thực hiện. Đối tượng thực thi: AI agent.
Ngày viết: 2026-10-14. Kế thừa `plan/fix-plan.md` (vòng 1) và `plan/fix-plan-round2.md` (vòng 2).
Plan này **thay thế** mục 4 "Lưu trữ" của vòng 1, mục P1-3 của vòng 2, và **mở rộng** mục 1
"Bảo mật" của vòng 2 từ hai chế độ thành ba. Những mục khác của hai plan cũ vẫn giữ nguyên.

Mỗi việc có: **Ở đâu** (file), **Làm gì**, **Xong khi** (điều kiện kiểm được). Không làm việc ngoài
danh sách; thấy thiếu thì ghi vào `plan/mariadb-notes.md`, không tự mở rộng.

---

## 0. Quyết định đã chốt (không tự đổi)

| # | Quyết định | Lý do |
|---|---|---|
| Q1 | **Chỉ dùng MariaDB**. Bỏ hẳn PostgreSQL và H2 khỏi code, pom, chart, tài liệu. | Chủ dự án quyết. |
| Q2 | Dữ liệu notebook ghi vào **database `test`** trên MariaDB `10.29.19.52`, **dùng lại** 5 bảng có sẵn `notebooks`, `notebook_revisions`, `notebook_runs`, `notebook_cell_runs`, `app_users` (schema sao chép từ `self_service` của backend web). Không tạo bộ bảng `nb_document.*` / `nb_execution.*` song song. `self_service` chỉ là mẫu tham khảo, B **không kết nối** tới nó. | Giữ cùng schema với backend web để sau này đồng bộ/di chuyển notebook hai chiều không phải chuyển đổi. |
| Q3 | Nội dung notebook lưu dạng **text thuần** trong cột `document_json LONGTEXT` của `notebooks`, mỗi notebook một tài liệu JSON. **Bỏ** bảng `notebook_cells` và cách lưu từng cell một dòng của code hiện tại. | Khớp cách bảng `self_service.notebooks` đang lưu (`notebook_format = SQLLAB_JSON_V1`). |
| Q4 | Xác thực có **ba** chế độ, chọn bằng `notebook.auth.mode = oidc \| no-oidc \| ldap`. Thiếu hoặc sai → khởi động thất bại. | Yêu cầu chủ dự án. |
| Q5 | `oidc` và `no-oidc`: **B không tự xác thực**, Kyuubi quyết. `ldap`: B tự bind LDAP. | `ldap` cho trường hợp Kyuubi chạy `authentication=NONE` nhưng vẫn cần đăng nhập thật. |
| Q6 | B nối Kyuubi bằng **REST API** (`/api/v1/sessions`, `/api/v1/operations`), không dùng Thrift binary/HTTP. Lý do ở §3. | Bearer token đi thẳng trong header; không phải nhét token vào ô password của SASL. |
| Q7 | **Kyuubi giữ nguyên cấu hình**: không đặt `kyuubi.engine.spark.output.mode=NOTEBOOK`, để mặc định `AUTO` để cùng một Kyuubi phục vụ cả SQL editor lẫn notebook. Kyuubi chỉ làm việc **phân luồng tài nguyên** theo cấu hình Spark (share level, pool, engine config). B phải tự xử lý output dạng `AUTO`. | Yêu cầu chủ dự án. |
| Q8 | Phân quyền dữ liệu là việc của **Ranger** thông qua Spark engine chạy dưới tên người dùng. B không có bảng quyền dữ liệu nào; B chỉ có quyền **chia sẻ notebook** (OWNER/EDITOR/RUNNER/VIEWER). | Một nguồn quyền dữ liệu duy nhất. |

---

## 1. Lưu trữ: MariaDB database `test`, schema theo `self_service`

### 1.1 DDL thật của các bảng (đã có, từ ảnh DBeaver 2026-10-14, database `test`)

> Đích là database **`test`** (chủ dự án xác nhận 2026-10-14). `test` chỉ có 5 bảng dưới đây,
> **không có** `flyway_schema_history` và không có bảng của hệ thống khác → database này thuộc về
> notebook-api, B được quyền migrate. `self_service` chỉ là mẫu.

```sql
CREATE TABLE notebooks (
  id CHAR(36) NOT NULL, node_id CHAR(36) NOT NULL,
  notebook_format VARCHAR(30) NOT NULL, default_language VARCHAR(30) NOT NULL,
  document_json LONGTEXT NOT NULL,
  current_revision BIGINT NOT NULL DEFAULT 1, lock_version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  zeppelin_note_id VARCHAR(64) NULL, kyuubi_notebook_id VARCHAR(64) NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_notebook_node (node_id), KEY idx_notebook_kyuubi_notebook (kyuubi_notebook_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;

CREATE TABLE notebook_revisions (
  id CHAR(36) NOT NULL, notebook_id CHAR(36) NOT NULL, revision_number BIGINT NOT NULL,
  document_json LONGTEXT NOT NULL, revision_type VARCHAR(30) NOT NULL,
  created_by VARCHAR(255) NOT NULL, created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_notebook_revision (notebook_id, revision_number),
  KEY idx_notebook_revisions_created_at (created_at, notebook_id, revision_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE notebook_runs (
  id CHAR(36) NOT NULL, notebook_id CHAR(36) NOT NULL, run_type VARCHAR(30) NOT NULL,
  status VARCHAR(30) NOT NULL, triggered_by VARCHAR(255) NOT NULL,
  started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
  error_code VARCHAR(100) NULL, error_message TEXT NULL, created_at DATETIME(6) NOT NULL,
  kyuubi_notebook_id VARCHAR(64) NULL, cancel_requested TINYINT(1) NOT NULL DEFAULT 0,
  cells_total INT NOT NULL DEFAULT 0, cells_done INT NOT NULL DEFAULT 0,
  current_cell_id VARCHAR(128) NULL, current_execution_id VARCHAR(128) NULL,
  notebook_revision BIGINT NULL, heartbeat_at DATETIME(6) NULL, notebook_saved_at DATETIME(6) NULL,
  PRIMARY KEY (id), KEY idx_notebook_runs_notebook_status (notebook_id, status),
  KEY idx_notebook_runs_status_heartbeat (status, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE notebook_cell_runs (
  id CHAR(36) NOT NULL, notebook_run_id CHAR(36) NOT NULL, cell_id VARCHAR(100) NOT NULL,
  execution_order INT NOT NULL, language VARCHAR(30) NOT NULL, status VARCHAR(30) NOT NULL,
  started_at DATETIME(6) NULL, finished_at DATETIME(6) NULL,
  row_count BIGINT NULL, execution_time_ms BIGINT NULL,
  output_type VARCHAR(30) NULL, output_json LONGTEXT NULL, output_storage_uri VARCHAR(2048) NULL,
  error_message TEXT NULL, execution_id VARCHAR(128) NULL, source TEXT NULL,
  PRIMARY KEY (id), KEY fk_cell_run_notebook_run (notebook_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3;

CREATE TABLE app_users (
  id CHAR(36) NOT NULL, username VARCHAR(255) NOT NULL, display_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NULL, status VARCHAR(32) NOT NULL, ranger_username VARCHAR(255) NOT NULL,
  created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_app_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

Kết luận rút ra từ DDL:

| # | Phát hiện | Hệ quả |
|---|---|---|
| D1 | `notebooks` **không có** tên, owner, thư mục, mô tả, cờ xóa mềm. `node_id` là map S3 của backend web, B không có map đó. | V2 thêm cột (1.2) và **`MODIFY node_id CHAR(36) NULL`**: notebook do B tạo để `node_id = NULL` (MariaDB cho phép nhiều NULL trong UNIQUE). Không sinh UUID giả. Dòng có sẵn giữ nguyên `node_id`. B không bao giờ ghi `node_id`. |
| D2 | `app_users.ranger_username` tách khỏi `username`. | User mà Kyuubi/Ranger thấy phải là `ranger_username`. Xem 2.2 bước 4, mục "Proxy user". |
| D3 | `notebook_runs` + `notebook_cell_runs` là mô hình "run": một run gồm nhiều cell run, có `execution_id`, `output_json`, `cancel_requested`, `heartbeat_at`. | Dùng lại cho execution-service (1.2). Không tạo `notebook_executions`. |
| D4 | Không có bảng session Kyuubi, không có chỗ lưu log operation. | Script 001 tạo `notebook_kyuubi_sessions`, `notebook_execution_logs`. |
| D5 | `notebook_revisions.id` là `CHAR(36)`, có `revision_type`. | Entity dùng UUID string; B ghi `revision_type` ∈ `AUTO`, `MANUAL`, `IMPORT`. |
| D6 | `error_message`, `source` là `TEXT` (64 KB). | `source` của cell tới 1 MB sẽ bị cắt; script 001 `MODIFY ... LONGTEXT` hai cột này (an toàn, chỉ nới rộng). |
| D7 | 4 bảng notebook `utf8mb3`, `app_users` đã `utf8mb4`. | Convert theo 1.5. |
| D8 | `app_users.username` là `VARCHAR(255)`, `created_by`/`triggered_by` cũng 255. | Mọi cột username của B dùng `VARCHAR(255)`, không phải 128 như plan cũ. |

**Quyết định ai migrate: B tự chạy Flyway** (đổi so với bản trước, vì `test` là database riêng của
B). `spring.flyway.baseline-on-migrate=true`, `baseline-version=1`, `V1__baseline.sql` chứa đúng DDL
5 bảng trên (để dựng từ đầu trên DB trống, Testcontainers, dev); trên `test` đã có bảng thì Flyway
baseline bỏ qua V1 và chạy từ V2. Chỉ **một** service chạy Flyway: notebook-service sở hữu toàn bộ
migration (kể cả bảng của execution-service); execution-service `flyway.enabled=false`,
`ddl-auto=validate`, và Deployment của nó có `initContainer` chờ notebook-service `/actuator/health`
UP. Lý do: hai Flyway trên một database cùng `flyway_schema_history` là xung đột.

### 1.2 Ánh xạ mô hình B → bảng trong database `test`

| Khái niệm B | Bảng/cột | Ghi chú |
|---|---|---|
| Notebook | `notebooks` | `id` UUID; `node_id` UUID mới (D1); `notebook_format = SQLLAB_JSON_V1` luôn; `default_language` ∈ `SQL`/`PYTHON`; `document_json` theo 1.3; `current_revision` tăng mỗi lần lưu; `lock_version` = `@Version`; `kyuubi_notebook_id`, `zeppelin_note_id` để NULL. |
| Tên, owner, thư mục, mô tả, xóa mềm | **Cột mới** trên `notebooks` (V2): `name VARCHAR(255) NOT NULL DEFAULT ''`, `owner VARCHAR(255) NOT NULL DEFAULT ''`, `folder_id CHAR(36) NULL`, `description LONGTEXT NULL`, `deleted_at DATETIME(6) NULL`; index `idx_notebooks_owner (owner, deleted_at)`, `idx_notebooks_folder (folder_id)`. | `DEFAULT ''` để `ALTER` không fail trên dòng có sẵn. Dòng có sẵn (`owner=''`) chỉ admin thấy, trong mục "Chưa có chủ"; admin gán owner bằng `PUT /notebooks/{id}/owner`. `name` của dòng có sẵn lấy từ `document_json.metadata.name` nếu có, không thì `id`. |
| Thư mục | Bảng mới `notebook_folders` (`id CHAR(36)`, `name`, `parent_id`, `owner VARCHAR(255)`, `created_at`, `updated_at`, `deleted_at`) | Như vòng 1. |
| Cell | Phần tử `document_json.cells[]` | Không có bảng cell. |
| Revision | `notebook_revisions` | Dùng nguyên; `created_by` = username; `revision_type`. Dọn revision cũ vượt `maxRevisions`, giữ `MANUAL`. Cột `is_protected`/`reason` của plan cũ bỏ. |
| Quyền chia sẻ | Bảng mới `notebook_permissions` (`id BIGINT AUTO_INCREMENT`, `notebook_id CHAR(36)` FK cascade, `principal_type VARCHAR(32)`, `principal_id VARCHAR(255)`, `role VARCHAR(32)`, `created_at`, `UNIQUE(notebook_id, principal_id)`) | |
| Session Kyuubi | Bảng mới `notebook_kyuubi_sessions` (`id CHAR(36)`, `notebook_id CHAR(36)`, `user_id VARCHAR(255)`, `kyuubi_session_id VARCHAR(64)`, `kyuubi_instance VARCHAR(256)`, `status VARCHAR(32)`, `execution_count_seq INT`, `created_at`, `updated_at`, `last_used_at`; index `(notebook_id, user_id, status)`) | D4 |
| Một lần "chạy" (run cell / run all) | `notebook_runs` | `run_type` ∈ `CELL`, `ALL`; `status` ∈ `PENDING, RUNNING, SUCCEEDED, FAILED, CANCELED, LOST`; `triggered_by` = username; `cells_total/cells_done` cho run all; `cancel_requested` do `POST :cancel` đặt; `heartbeat_at` cập nhật mỗi lần sync; `notebook_revision` = `current_revision` lúc chạy. Thêm cột mới `kyuubi_session_ref CHAR(36) NULL` (FK mềm → `notebook_kyuubi_sessions.id`) vì `kyuubi_notebook_id` có nghĩa khác (để NULL). |
| Execution của một cell | `notebook_cell_runs` | `execution_id` = Kyuubi operation id; `execution_order` = thứ tự trong run; `source` = câu lệnh đã gửi (D6: `LONGTEXT`); `output_type` ∈ `TABLE, STREAM, ERROR, NONE`; `output_json` = mảng `outputs[]` nbformat (§3); `row_count`, `execution_time_ms` điền từ Kyuubi; `output_storage_uri` để NULL (B không ghi S3). Thêm cột mới `execution_count INT NULL` (số `[n]` của cell). |
| Log operation | Bảng mới `notebook_execution_logs` (`id BIGINT AUTO_INCREMENT`, `cell_run_id CHAR(36)` FK cascade, `sequence BIGINT`, `line LONGTEXT`, `created_at`; `UNIQUE(cell_run_id, sequence)`) | D4 |
| Người dùng | `app_users` (chỉ `SELECT`) | B tra `username` → `ranger_username` (D2). Không có dòng, hoặc hai giá trị bằng nhau → không proxy. Khác nhau → proxy user (2.2 bước 4). B **không** tạo user. Cache 5 phút. |
| Phiên đăng nhập | Bảng mới `notebook_auth_sessions` (§2.6) | |

Migration (`nb-notebook-service/src/main/resources/db/migration/`):

| File | Nội dung |
|---|---|
| `V1__baseline.sql` | DDL 5 bảng ở 1.1, `CREATE TABLE IF NOT EXISTS` |
| `V2__notebook_api.sql` | `ALTER TABLE notebooks MODIFY node_id CHAR(36) NULL`; `ADD COLUMN IF NOT EXISTS` 5 cột + 2 index; `notebook_runs ADD kyuubi_session_ref`; `notebook_cell_runs ADD execution_count`, `MODIFY source LONGTEXT`, `MODIFY error_message LONGTEXT`; `CREATE TABLE IF NOT EXISTS` 5 bảng mới (`utf8mb4_bin`) |
| `V3__utf8mb4.sql` | `ALTER TABLE … CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin` cho `notebooks`, `notebook_revisions`, `notebook_runs`, `notebook_cell_runs` (1.5) |

Mọi lệnh idempotent để chạy lại trên bản sao không lỗi.

### 1.3 Định dạng `document_json`: dùng `SQLLAB_JSON_V1` của backend web, mở rộng

Mẫu thật từ `self_service.notebooks` (2026-10-14):

```json
{"schemaVersion":1,"defaultLanguage":"SQL","metadata":{},"parameters":[],
 "cells":[{"id":"cell-315387a0-…","type":"CODE","language":"SQL","source":"hhhhh",
           "metadata":{"kyuubiCellId":"b523460e-…"}}]}
```

**Không phải nbformat.** Quyết định: B lưu **cùng format này** cho mọi notebook, `notebook_format`
luôn `SQLLAB_JSON_V1`. Không có `IPYNB_JSON_V1`. `.ipynb` chỉ xuất hiện ở import/export
(`IpynbConversionService`). Lý do: notebook của backend web và của B mở lẫn nhau được, một parser.

Đặc tả `SQLLAB_JSON_V1` như B hiểu và ghi (phần **in đậm** là B mở rộng, backend web chưa dùng):

| Trường | Kiểu | Ghi chú |
|---|---|---|
| `schemaVersion` | int | Luôn `1`. Khác 1 → `409 UNSUPPORTED_FORMAT`. |
| `defaultLanguage` | `SQL` \| `PYTHON` | Trùng cột `notebooks.default_language`; B ghi cả hai. |
| `metadata` | object | B thêm **`name`**, **`description`**, **`defaultCatalog`**, **`defaultSchema`**, **`kernelspec`** (khi import `.ipynb`). Key lạ giữ nguyên. |
| `parameters` | array | B không dùng, **giữ nguyên** khi ghi lại. |
| `cells[].id` | string | Giữ dạng `cell-<uuid>` khi tạo mới để giống backend web. |
| `cells[].type` | `CODE` \| **`MARKDOWN`** \| **`RAW`** | Backend web chỉ có `CODE`. |
| `cells[].language` | `SQL` \| `PYTHON` \| **`MARKDOWN`** | Với `MARKDOWN`/`RAW` ghi `MARKDOWN`/`RAW` cho đủ trường. |
| `cells[].source` | string | Text thuần, không mảng dòng như nbformat. |
| `cells[].metadata` | object | `kyuubiCellId` của backend web **giữ nguyên**, B không đọc. B thêm **`executionCount`** (int, null nếu chưa chạy), **`collapsed`**. |
| **`cells[].outputs`** | array nbformat | Chỉ có khi lưu kèm output (export `includeOutputs` hoặc người dùng bấm "lưu kèm output"). Mặc định không có. |

Parser: POJO Jackson với `@JsonAnySetter/@JsonAnyGetter` ở mọi cấp để **không làm mất** trường lạ.
Validate khi ghi: `cells.length ≤ notebook.max.cells`, mỗi `source ≤ 1 MB`, tổng ≤ 16 MB.

Ánh xạ `.ipynb` ↔ `SQLLAB_JSON_V1` (`IpynbConversionService`):

| nbformat | SQLLAB_JSON_V1 |
|---|---|
| `cell_type: code` + `metadata.language` (hoặc magic `%%sql`/`%%python` dòng đầu, hoặc `defaultLanguage`) | `type: CODE`, `language` |
| `cell_type: markdown` / `raw` | `type: MARKDOWN` / `RAW` |
| `source` mảng dòng | `source` nối chuỗi; chiều ngược tách theo `\n` giữ `\n` cuối dòng |
| `execution_count` | `metadata.executionCount` |
| `outputs` | `outputs` (chỉ khi `includeOutputs`) |
| `metadata.kernelspec`, `language_info` | `metadata.kernelspec`, `metadata.language_info` |
| `nbformat`, `nbformat_minor` | bỏ; khi export ghi `4`, `5` |

Output khi chạy nằm ở `notebook_cell_runs.output_json` (1.2), **không** ghi vào `document_json` mỗi
lần chạy.

### 1.4 Việc phải làm

| # | Ở đâu | Làm gì | Xong khi |
|---|---|---|---|
| S0 | `plan/self-service-ddl.md` | Lấy một mẫu `document_json` từ `self_service.notebooks` để đối chiếu nbformat; ghi `SHOW CREATE TABLE` của `test` (đã có ở 1.1). | File có mẫu JSON |
| S1 | `backend/pom.xml` | `dependencyManagement`: bỏ `postgresql`, `flyway-database-postgresql`, `h2`. Thêm `org.mariadb.jdbc:mariadb-java-client` (Boot 3.3 quản lý version), `org.flywaydb:flyway-mysql` (cùng `${flyway.version}`), `org.testcontainers:mariadb` + `junit-jupiter` (test). | `mvn dependency:tree` không còn `postgresql`, `h2` |
| S2 | `nb-notebook-service/pom.xml`, `nb-execution-service/pom.xml` | Thay dependency theo S1, `mariadb-java-client` scope `runtime`. | Build xanh |
| S3 | `nb-notebook-service/.../db/migration/V1..V3` | Viết 3 migration theo bảng ở 1.2. Xóa `db/migration` của execution-service. | Flyway chạy sạch trên (a) DB trống, (b) bản sao `test` đã có 5 bảng (baseline) |
| S4 | `V2__notebook_api.sql` | Bảng mới theo 1.2: `notebook_folders`, `notebook_permissions`, `notebook_kyuubi_sessions`, `notebook_execution_logs`, `notebook_auth_sessions`. Cột mới trên `notebooks`, `notebook_runs`, `notebook_cell_runs`. | Như S3 |
| S5 | `application.yml` | notebook-service: `flyway.enabled: true`, `baseline-on-migrate: true`, `baseline-version: 1`, `table: flyway_schema_history`. execution-service: `flyway.enabled: false`. Cả hai `ddl-auto: validate`. | execution-service khởi động trước notebook-service trên DB trống → fail validate (đúng), sau khi notebook-service lên → OK |
| S6 | Entity notebook-service | Viết lại `Notebook` theo cột `notebooks` thật (1.2), `@Version lock_version`, `@Column(columnDefinition="LONGTEXT") documentJson`. **Xóa** `NotebookCell` entity + `NotebookCellRepository`; `NotebookCellService` thao tác trên `NotebookDocument` (POJO parse từ JSON, Jackson) rồi `save` notebook. `NotebookRevision`, `NotebookPermission` theo DDL thật. Bỏ mọi `@Table(schema=...)`. Thời gian `Instant`. | `ddl-auto: validate` không lỗi trên bản sao `test` |
| S7 | Entity execution-service | `NotebookKyuubiSession` → `notebook_kyuubi_sessions`; `NotebookRun` → `notebook_runs`; `NotebookCellRun` → `notebook_cell_runs` (thay `NotebookExecution`); `NotebookExecutionLog`. API `/executions/{id}` trỏ tới `notebook_cell_runs.id`. Bỏ `@Table(schema=...)`. | Như S6 |
| S8 | `application.yml` (cả hai) | Bỏ `flyway.schemas`, `default-schema`, `hibernate.default_schema`. Thêm `hibernate.jdbc.time_zone: UTC`. `spring.datasource.url/username/password` **không có mặc định**. Bỏ `kyuubi.session-configs.kyuubi.engine.spark.output.mode` (Q7). | Chạy không có `SPRING_DATASOURCE_URL` → thoát mã ≠ 0 |
| S9 | `application-dev.yml`, `backend/dev/docker-compose.yml` | MariaDB 10.11 local, database `test` trống; Flyway tự dựng V1→V3. | Profile `dev` lên được, `validate` xanh |
| S10 | `nb-common` | `DatasourceGuard`: URL phải bắt đầu `jdbc:mariadb:`; khác → khởi động thất bại với "notebook-api only supports MariaDB". | Test 5 tiền tố URL |
| S11 | Test | Testcontainers `MariaDBContainer("mariadb:10.11")`: một test trên DB trống (V1→V3), một test nạp sẵn 5 bảng `utf8mb3` + vài dòng mẫu rồi chạy baseline + V2, V3 (giả lập `test` thật). Cell source 5 MB round-trip; emoji sau V3. | `mvn verify` xanh |
| S12 | `BUILD.md`, `README.md` | Thay PostgreSQL/H2 bằng MariaDB database `test`; ghi yêu cầu `max_allowed_packet ≥ 64M`. | `grep -ri "postgres\|h2database" README.md BUILD.md` rỗng |

### 1.5 Charset `utf8mb3` của bảng có sẵn

`notebooks` đang `utf8mb3_general_ci`: **không lưu được emoji và ký tự 4 byte** trong
`document_json` (MariaDB báo `Incorrect string value`, cả `UPDATE` thất bại). Vì `test` là database
riêng của B, **chọn (i)** và làm trong `V3__utf8mb4.sql`. Bảng nhỏ (≤ 1 MB), chạy vài giây.

- (i) Đội vận hành đổi `ALTER TABLE notebooks CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin`
  (và `notebook_revisions`). Đưa vào script `002_utf8mb4.sql`, chạy ngoài giờ, cần kiểm index
  `VARCHAR` không vượt 3072 byte.
- (ii) Không đổi: B lọc và thay ký tự ngoài BMP bằng `�` trước khi lưu, trả `warning` trong
  response. Ghi rõ hạn chế trong README.

Bảng mới (S4) luôn `utf8mb4`. Kết nối JDBC luôn `characterEncoding=utf8` (= utf8mb4 với driver
MariaDB 3.x).

### 1.6 Kết nối và quyền DB

- URL: `jdbc:mariadb://10.29.19.52:3306/test?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC`.
  Tên database là giá trị cấu hình (`mariadb.database`), không viết cứng `test`.
- **User riêng `notebook_api`**: `SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP,
  REFERENCES` trên `test.*` (Flyway cần DDL). Không cấp quyền database khác, không `GRANT OPTION`.
  Câu lệnh `GRANT` nằm trong `backend/sql/000_user.sql` và `NOTES.txt` của chart.
- `app_users`: B chỉ `SELECT`; ai nạp dữ liệu vào `app_users` của `test` là việc vận hành (đồng bộ
  từ `self_service` hoặc nhập tay). Không có dòng → `ranger_username = username` (D2).
- Hikari `maximum-pool-size: 10` mỗi service.

### 1.7 Helm chart

| Ở đâu | Làm gì |
|---|---|
| `values.yaml` | Bỏ `postgres`. Thêm `mariadb: { host: 10.29.19.52, port: 3306, database: test, username: notebook_api, existingSecret: notebook-secrets, passwordKey: db-password }`. |
| `templates/deployment-*.yaml` | `SPRING_DATASOURCE_URL` render theo 1.6; password từ `secretKeyRef`; không đặt driver class. |
| `templates/NOTES.txt` | Nhắc tạo user DB (`000_user.sql`) trước khi cài; Flyway tự migrate; hướng dẫn Secret §2.7. |
| `templates/deployment-execution.yaml` | `initContainers` chờ `http://nb-notebook-service:8081/actuator/health` trả 200 (busybox `wget`), tối đa 5 phút. |

---

## 2. Bảo mật: ba chế độ `oidc` / `no-oidc` / `ldap`

### 2.1 Tổng quan

| | `oidc` | `no-oidc` | `ldap` |
|---|---|---|---|
| Ai xác thực người dùng | Kyuubi (qua `GET /api/v1/ping`) | Kyuubi (qua `/api/v1/ping`) | **B**, bind thẳng LDAP |
| Kyuubi phải bật | `OIDC` | `LDAP`, `CUSTOM`, `JDBC`, hoặc `NONE` (với `allowUnverified`) | `LDAP` (cùng LDAP với B) hoặc `NONE` |
| Credential tới B | `Authorization: Bearer <JWT Keycloak>` | form user/password → cookie `NB_SESSION` | form user/password → cookie `NB_SESSION` |
| Credential tới Kyuubi | Bearer nguyên vẹn | `Basic base64(user:password)` | `Basic base64(user:password)` |
| B giữ mật khẩu | Không | Có, mã hóa trong `notebook_auth_sessions` | Có, mã hóa |
| CSRF | Không cần | Bắt buộc | Bắt buộc |
| Thăm dò Kyuubi NONE (1.4 vòng 2) | Có | Có | Không cần |
| Ranger nhận user nào | `preferred_username` trong token | user của `Basic` | `usernameAttribute` từ LDAP |
| Admin (`/me.admin`) | `notebook.admins` | `notebook.admins` | `notebook.admins` hoặc group `adminGroup` |

Vì sao có cả `no-oidc` lẫn `ldap`: `no-oidc` tin Kyuubi, Kyuubi `NONE` thì ai cũng vào được.
`ldap` tự hỏi LDAP, dùng được cả khi Kyuubi `NONE`.

### 2.2 Chế độ `oidc`: đăng nhập một lần, tự vào Kyuubi, tự nhận quyền Ranger

Đây là chế độ chính. Luồng đầy đủ, từ trình duyệt tới Ranger:

```
SPA ──(1) PKCE──▶ Keycloak realm lakehouse ──▶ access_token (aud: kyuubi, preferred_username: alice)
SPA ──(2) Authorization: Bearer ──▶ notebook-api
notebook-api ──(3) GET /api/v1/ping, Bearer ──▶ Kyuubi REST  (200 → alice hợp lệ)
notebook-api ──(4) POST /api/v1/sessions, Bearer ──▶ Kyuubi: session user = alice
Kyuubi ──(5) launch / attach Spark engine chạy dưới user alice (share level USER)
Spark engine ──(6) Ranger Spark plugin: policy cho user alice (+ group từ usersync) ──▶ Iceberg/HDFS
```

| Bước | Ở đâu | Chi tiết |
|---|---|---|
| 1 | `frontend/` | `oidc-client-ts`, Authorization Code + PKCE, client public `kyuubi-notebook`, scope `openid profile`. Keycloak client cần **audience mapper** thêm `kyuubi` vào `aud` và `iss` đúng hostname mà Kyuubi cấu hình (`https://10.29.19.56:32004/realms/lakehouse`). Silent renew trước khi `exp`; B không giữ refresh token. |
| 2 | SPA | Mọi request tới B kèm `Authorization: Bearer`. Không cookie. |
| 3 | `nb-common` `BearerFilter` | Kiểm như vòng 2 §1.2: `/ping` 200 → đọc payload, `preferred_username` → principal. Cache Caffeine, TTL = min(`cacheTtl`, `exp`−now). Không có claim → 401. **Không** tự verify chữ ký JWT (Kyuubi đã verify qua JWKS), B chỉ đọc claim để lấy tên. |
| 4 | execution-service `KyuubiRestClient.openSession` | `POST /api/v1/sessions` kèm **cùng header Bearer của request đang xử lý**. Body `configs`: `kyuubi.session.engine.launch.async=true`, cộng config Spark người dùng chọn đã lọc theo `allowedSessionConfigKeys`. Lưu `kyuubi_session_id` + `kyuubi_instance`. **Proxy user (D2), if/else**: B tra `app_users.ranger_username` của `preferred_username` (cache 5 phút). (a) Bằng nhau hoặc không có dòng → mở session bằng **token người dùng**, không gửi gì thêm. (b) Khác nhau → mở session và mọi lời gọi sau bằng **token tài khoản dịch vụ** `notebook-api` (Keycloak client confidential, grant `client_credentials`, `aud` chứa `kyuubi`; secret từ `notebook-secrets/oidc-client-secret`; B cache token, làm mới trước `exp`) và `configs.hive.server2.proxy.user=<ranger_username>`. Kyuubi kiểm `hadoop.proxyuser.notebook-api.users=*` (hoặc danh sách), `.hosts=*`; thiếu → Kyuubi trả `… is not allowed to impersonate …` → B trả `403 PROXY_NOT_ALLOWED`. Lý do dùng tài khoản dịch vụ: Kyuubi tra `hadoop.proxyuser.<real user>`, với real user là người gọi thật; nếu dùng token của `alice` thì phải khai `hadoop.proxyuser.alice` cho từng người. Ở nhánh (b) người dùng vẫn phải qua `/ping` bằng token của chính họ ở bước 3, nên không ai mượn được tài khoản dịch vụ mà không có token hợp lệ. |
| 4' | Mọi lời gọi sau (`/operations`, `/rowset`, `/log`, `DELETE /sessions`) | Luôn gắn **Bearer hiện tại** của request (token có thể đã được SPA renew; Kyuubi chỉ cần token hợp lệ và cùng user với session). Request của B tới Kyuubi không bao giờ dùng token đã cache. |
| 5 | Kyuubi (không sửa) | `kyuubi.engine.share.level=USER` (hoặc `CONNECTION`) để engine thuộc về đúng user; **không** dùng `GROUP`/`SERVER` vì khi đó engine chạy dưới user khác và Ranger sẽ áp policy sai. Kyuubi `kyuubi.engine.doAs.enabled=true` (mặc định). Đây là "phân luồng tài nguyên" mà Kyuubi phụ trách; B không can thiệp. |
| 6 | Spark engine (do Kyuubi dựng, không sửa) | `spark.sql.extensions=org.apache.kyuubi.plugin.spark.authz.ranger.RangerSparkExtension`, `ranger-spark-security.xml` trỏ service Ranger. Plugin lấy user từ `SparkContext.sparkUser` = session user Kyuubi = `alice`. Policy theo **user** chạy ngay. Policy theo **group** cần một trong hai: Ranger usersync đã đồng bộ group của `alice` từ cùng LDAP/Keycloak **và** engine phân giải group được (`hadoop.security.group.mapping=org.apache.hadoop.security.LdapGroupsMapping` trong `spark-defaults` của engine), hoặc Ranger plugin bật `ranger.plugin.spark.use.rangerGroups=true` (nếu phiên bản plugin hỗ trợ). Ghi vào README mục "Ranger" như **điều kiện phía vận hành**, B không làm gì thêm. |

**Điều kiện phải đúng, kiểm ở Pha 0 (ghi vào `plan/phase0-findings.md` mục 5):**

| # | Kiểm gì | Cách kiểm |
|---|---|---|
| P5.1 | Kyuubi REST chấp nhận Bearer của realm `lakehouse` | `curl -H "Authorization: Bearer $T" http://<kyuubi-rest>:10099/api/v1/ping` → 200 |
| P5.2 | Session mở bằng Bearer có `user` = `preferred_username` | `POST /api/v1/sessions` rồi `GET /api/v1/sessions/{id}` → trường `user` |
| P5.3 | Engine chạy dưới user đó | Spark UI của engine: `User: alice`; hoặc `SELECT current_user()` qua session |
| P5.4 | Ranger áp policy | Tạo policy deny bảng X cho `alice`, chạy `SELECT * FROM X` qua session → lỗi `Permission denied` có chữ Ranger |
| P5.5 | Token renew không làm mất session | Đợi token cũ hết hạn, lấy token mới, `GET /api/v1/sessions/{id}` với token mới → 200 |
| P5.6 | Tên user nhất quán | `preferred_username` (Keycloak) = `app_users.username`; `app_users.ranger_username` = user trong Ranger (usersync) |
| P5.7 | Proxy user (chỉ khi `username ≠ ranger_username`) | Lấy token client credentials của `notebook-api`; `POST /api/v1/sessions` với `configs.hive.server2.proxy.user=<ranger_username>` → `GET /sessions/{id}` trả `user` = ranger_username; Ranger policy theo tên đó có hiệu lực. Kiểm `hadoop.proxyuser.notebook-api.*` có trong `kyuubi-defaults.conf` hoặc `core-site.xml` của Kyuubi server (Kyuubi nạp mọi key của `kyuubi-defaults.conf` vào Hadoop `Configuration`, nên để ở đó là đủ). |

Nếu P5.5 thất bại (Kyuubi gắn token vào session), B phải mở session mới khi gặp 401 và đánh dấu
session cũ `LOST`; ghi vào `mariadb-notes.md`.

**Những thứ không có ở chế độ này**: `/auth/login`, `/auth/logout`, bảng phiên, mật khẩu, cookie.
Logout là việc của SPA với Keycloak. Kyuubi session rảnh quá `idleSessionTimeout` thì B tự đóng.

### 2.3 Chế độ `no-oidc`

Giữ nguyên mục 1.3 và 1.4 của `fix-plan-round2.md`. Bảng phiên là `notebook_auth_sessions` (§2.6).
Header gửi Kyuubi là `Basic`; Kyuubi `LDAP` kiểm lại; Ranger nhận user của `Basic`.

### 2.4 Chế độ `ldap`

**Luồng**

1. `GET /api/v1/auth/config` → `{ "mode": "ldap", "verified": true }`.
2. `POST /api/v1/auth/login` `{ "username", "password" }`.
3. B bind LDAP (Spring Security LDAP: `spring-security-ldap`, `spring-ldap-core`):
   - Chọn một: `userDnPattern` (`uid={0},ou=people`, bind trực tiếp) **hoặc** `userSearch`
     (`managerDn` + `managerPassword` tìm DN theo `userSearchFilter`, rồi bind bằng mật khẩu user).
   - Thành công → đọc `usernameAttribute` (mặc định `uid`; AD: `sAMAccountName`) làm **username
     chuẩn**, dùng cho owner/quyền/phiên và phần user của `Basic` gửi Kyuubi. Không dùng chuỗi
     người dùng gõ.
   - `adminGroup` cấu hình → tìm `groupSearchBase` với `(member={0})`/`memberUid` → `admin`.
   - Sai → `401 INVALID_CREDENTIALS` (không phân biệt user không tồn tại/sai mật khẩu). LDAP không
     trả lời → `503 AUTH_BACKEND_UNAVAILABLE`.
4. Tạo phiên `notebook_auth_sessions`, cookie `NB_SESSION` (`HttpOnly`, `Secure` trừ dev,
   `SameSite=Strict`), lưu `is_admin`.
5. Request sau: cookie → phiên → giải mã → `Authorization: Basic …` chuyển Kyuubi. Mỗi `recheck`
   (10 phút) bind lại LDAP; thất bại → xóa phiên → 401.
6. Logout xóa phiên + cookie. CSRF như `no-oidc`.
7. Execution-service lấy `{username, authorizationHeader}` qua `/internal/auth/session`; không nối LDAP.

**Kyuubi ở chế độ `ldap`**: Kyuubi `LDAP` → kiểm lại cùng LDAP (ghi README: `kyuubi.authentication.ldap.*`
phải cùng server/base DN). Kyuubi `NONE` → lấy user từ `Basic`, chấp nhận vì B đã kiểm. Kyuubi
`OIDC` → **không tương thích**: lúc khởi động B gọi `/ping` với `Basic` giả, nếu 401 kèm
`WWW-Authenticate: Bearer` → khởi động thất bại "Kyuubi requires Bearer; use mode=oidc".

**Kết nối LDAP**: `ldap://` bắt buộc `startTls: true` ngoài dev; `ldaps://` tự ký → `trustStore`
từ Secret hoặc `trustAll` chỉ dev. `connectTimeout 5s`, `readTimeout 10s`, pool `maxTotal 8`. LDAP
chưa lên lúc khởi động → vẫn khởi động, `/actuator/health` DOWN tới khi nối được.

### 2.5 Cấu hình đầy đủ

```yaml
notebook:
  auth:
    mode: oidc                 # oidc | no-oidc | ldap ; bắt buộc
    usernameClaim: preferred_username   # oidc
    cacheTtl: 60s              # oidc
    sessionTtl: 8h             # no-oidc, ldap
    recheck: 10m               # no-oidc, ldap
    allowUnverified: false     # oidc, no-oidc
    oidc:                      # trả cho SPA qua /auth/config
      issuer: https://10.29.19.56:32004/realms/lakehouse
      clientId: kyuubi-notebook
      scopes: [openid, profile]
      serviceClientId: notebook-api            # execution-service, nhánh proxy (2.2 bước 4b)
      serviceClientSecret: ${NOTEBOOK_OIDC_CLIENT_SECRET:}   # rỗng → nhánh proxy trả 503 PROXY_UNAVAILABLE, nhánh (a) vẫn chạy
    ldap:                      # chỉ đọc khi mode=ldap
      url: ldaps://ldap.lakehouse.local:636
      startTls: false
      trustStore: ""
      trustAll: false
      baseDn: dc=lakehouse,dc=local
      userDnPattern: ""        # uid={0},ou=people ; HOẶC userSearch
      userSearchBase: ou=people
      userSearchFilter: (uid={0})
      usernameAttribute: uid
      managerDn: ""
      managerPassword: ${NOTEBOOK_LDAP_MANAGER_PASSWORD:}
      groupSearchBase: ou=groups
      groupMemberAttribute: member
      adminGroup: ""
      connectTimeout: 5s
      readTimeout: 10s
  encryptionKey: ${NOTEBOOK_AUTH_ENCRYPTION_KEY:}   # no-oidc, ldap ; 32 byte base64
  internalToken: ${NOTEBOOK_INTERNAL_TOKEN}         # bắt buộc
  admins: []

kyuubi:
  rest-url: http://<kyuubi-release>-rest.<ns>.svc.cluster.local:10099
  pin-to-instance: true
  session-configs:
    kyuubi.session.engine.launch.async: "true"
    # KHÔNG đặt kyuubi.engine.spark.output.mode (Q7)
  allowed-session-config-keys: [spark.executor.memory, spark.executor.cores, spark.sql.shuffle.partitions]
```

Validate lúc khởi động (`AuthPropertiesValidator`, nb-common):

| Điều kiện | Lỗi |
|---|---|
| `mode` ∉ {oidc, no-oidc, ldap} | `notebook.auth.mode must be one of oidc, no-oidc, ldap` |
| `mode` ∈ {no-oidc, ldap} và `encryptionKey` không phải 32 byte base64 | `notebook.encryptionKey must be 32 bytes base64` |
| `mode=ldap` và `url` rỗng | `notebook.auth.ldap.url is required` |
| `mode=ldap` và không đúng một trong `userDnPattern`/`userSearchFilter` | `set exactly one of userDnPattern or userSearchFilter` |
| `mode=ldap`, `userSearch` nhưng `managerDn` rỗng | `managerDn is required when using userSearch` |
| `mode=ldap`, `ldap://`, `startTls=false`, profile ≠ dev | `plain ldap:// requires startTls=true outside dev` |
| `mode=ldap`, `trustAll=true`, profile ≠ dev | `trustAll is only allowed in dev` |
| `internalToken` rỗng | `NOTEBOOK_INTERNAL_TOKEN is required` |
| `kyuubi.session-configs` chứa `kyuubi.engine.spark.output.mode` | `output.mode must stay at Kyuubi default (Q7)` |

### 2.6 Bảng `notebook_auth_sessions` (V2)

```sql
CREATE TABLE IF NOT EXISTS notebook_auth_sessions (
  id              VARCHAR(64)     NOT NULL,
  username        VARCHAR(255)    NOT NULL,
  auth_mode       VARCHAR(16)     NOT NULL,          -- 'no-oidc' | 'ldap'
  credential_enc  VARBINARY(1024) NOT NULL,          -- AES-256-GCM(user:password), nonce 12 byte đứng đầu
  is_admin        BOOLEAN         NOT NULL DEFAULT FALSE,
  created_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_seen_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_recheck_at DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  expires_at      DATETIME(6)     NOT NULL,
  PRIMARY KEY (id),
  KEY idx_nas_user (username),
  KEY idx_nas_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
```

Job dọn `@Scheduled(fixedDelay=10m)` xóa hết hạn; `last_seen_at` ghi tối đa 1 lần/phút; đổi
`encryptionKey` → phiên cũ giải mã lỗi → xóa, 401, không crash.

### 2.7 Secret Kubernetes `notebook-secrets`

| Key | Dùng ở | Bắt buộc |
|---|---|---|
| `db-password` | cả hai service | Luôn |
| `internal-token` | cả hai service | Luôn |
| `auth-encryption-key` | notebook-service | `no-oidc`, `ldap` |
| `oidc-client-secret` | execution-service | `oidc`, chỉ khi có user `ranger_username ≠ username` (proxy, 2.2 bước 4b) |
| `ldap-manager-password` | notebook-service | `ldap` + `userSearch` |
| `ldap-truststore` | notebook-service | `ldap` + `ldaps://` tự ký; mount `/etc/notebook/ldap-truststore` |

Template chỉ render env/volume của chế độ đang chọn. Tạo trên Dashboard: dùng `stringData`, riêng
truststore dán base64 vào `data`; `auth-encryption-key` sinh bằng `openssl rand -base64 32`.

### 2.8 Endpoint

| Endpoint | Service | Đăng nhập | `oidc` | `no-oidc` | `ldap` |
|---|---|---|---|---|---|
| `GET /api/v1/auth/config` | notebook | Không | mode, issuer, clientId, scopes, redirectUri, verified | mode, verified | mode, verified=true |
| `POST /api/v1/auth/login` | notebook | Không | `405` | Kyuubi `/ping` Basic | LDAP bind |
| `POST /api/v1/auth/logout` | notebook | Có | `405` | xóa phiên | xóa phiên |
| `GET /api/v1/me` | notebook | Có | `{user, admin}` | `{user, admin}` | `{user, admin}` |
| `GET /internal/auth/session?id=` | notebook | `X-Internal-Token` | `404` | có | có |

### 2.9 Mã nguồn

| # | Ở đâu | Làm gì |
|---|---|---|
| A1 | `nb-common/.../auth/` | Tách `KyuubiAuthDelegationFilter` thành `AuthMode`, `AuthProperties`, `AuthPropertiesValidator`, `BearerDelegationAuthenticator`, `BasicDelegationAuthenticator`, `LdapAuthenticator`, `SessionCookieFilter`, `BearerFilter`. `SecurityConfig` chọn filter bằng `@ConditionalOnProperty(notebook.auth.mode)`. Principal luôn mang theo `authorizationHeader` để client Kyuubi dùng (một `KyuubiCredentialHolder` request-scoped). |
| A2 | `nb-common/.../auth/CredentialCrypto` | AES-256-GCM, nonce 12 byte. |
| A3 | `nb-notebook-service` | `AuthSessionRepository/Service`, `AuthController`, `InternalAuthController`; `AuthConfigController` theo 2.8; `MeController` đọc admin từ phiên hoặc `admins`. |
| A4 | `nb-notebook-service/pom.xml` | `spring-security-ldap`, `caffeine`; test `unboundid-ldapsdk`. |
| A5 | `nb-execution-service` | `SessionCookieFilter` gọi `/internal/auth/session`, cache 60 s. `/api/v1/notebook-status` yêu cầu đăng nhập. `KyuubiRestClient` nhận header từ `KyuubiCredentialHolder`, không bao giờ từ cache. |
| A6 | `frontend/` | Login view theo `/auth/config`. `X-XSRF-TOKEN` khi mode ≠ oidc. |

---

## 3. Kết nối B → Kyuubi: chọn giao thức

Kyuubi có ba cửa: Thrift binary (JDBC, cổng 10009), Thrift HTTP (cổng 10010), REST (cổng 10099).
Đối chiếu với ba chế độ bảo mật:

| Tiêu chí | Thrift binary (JDBC) | Thrift HTTP | REST |
|---|---|---|---|
| Bearer JWT (`oidc`) | Phải nhét token vào ô **password** của SASL PLAIN; Kyuubi cần `CUSTOM` provider nhận token làm password. Token 1–2 KB qua SASL, đổi token = mở lại connection. | Header `Authorization` có đi, nhưng JDBC driver Hive/Kyuubi chỉ hỗ trợ Basic/Kerberos chuẩn; Bearer cần driver vá. | **Header `Authorization: Bearer` nguyên vẹn mỗi request**, đổi token không ảnh hưởng session. Kyuubi hiện đã nhận Bearer ở `/ping` (phase0). |
| `Basic` (`no-oidc`, `ldap`) | SASL PLAIN user/password, được | Basic header, được | Basic header, được |
| Chạy bất đồng bộ, lấy log, hủy, poll trạng thái | Phải giữ connection + `Statement` sống trong JVM của B; B restart là mất operation | Như binary | `operationHandle` là chuỗi, lưu DB được; B restart vẫn poll tiếp; `GET /operations/{id}/log`, `/rowset`, `DELETE` để hủy |
| Nhiều replica B | Connection dính một pod B | Như binary | Stateless, chỉ cần `kyuubi_instance` để pin |
| Đồng bộ với tính năng SQL editor đang dùng cùng Kyuubi | Không liên quan | Không liên quan | Cùng Kyuubi, cùng cấu hình `AUTO` (Q7) |
| Mã đã có trong repo | Không | Không | `KyuubiRestClient` |

**Chọn REST** (Q6). Ghi README: cổng `10099`, Service `<kyuubi-release>-rest`. Thrift chỉ dành cho
JDBC client bên ngoài (DBeaver, dbt), không phải cho B.

Hệ quả với output `AUTO` (Q7): rowset trả về là văn bản. B xử lý ở `ExecutionService.sync`:

| Loại cell | Kyuubi trả | B lưu (`outputs_json`, nbformat) |
|---|---|---|
| SQL | `resultsetmetadata` + rowset cột/hàng | `{ "output_type": "execute_result", "data": { "application/vnd.notebook.table+json": { columns, rows, truncated } } }`, tối đa `results.max-rows` |
| Python, stdout | rowset 1 cột text | `{ "output_type": "stream", "name": "stdout", "text": ... }` |
| Python, lỗi | `event.exception` / message chứa traceback | `{ "output_type": "error", "ename", "evalue", "traceback": [...] }` |
| Python, hình/HTML | Ở `AUTO` **không** có mime bundle; chỉ có text | Ghi rõ trong README là giới hạn của `AUTO`; muốn hình phải người dùng tự `display(HTML(...))` → vẫn là text. Không đổi Kyuubi sang `NOTEBOOK` (Q7). |

---

## 4. Thứ tự thực hiện

1. **S0 + P5.1–P5.6** (Pha 0): mẫu `document_json`, kiểm OIDC → Kyuubi → Ranger bằng `curl`. Không
   code gì trước khi xong. Chủ dự án trả lời 1.1 (a/b) và 1.5 (i/ii).
2. **S1–S10**: chuyển MariaDB, entity theo DDL thật, `validate` xanh trên bản sao `test`.
3. **S11**: Testcontainers MariaDB.
4. **A1, A2, validator 2.5**: khung ba chế độ; `oidc` chạy end-to-end tới Ranger (2.2).
5. **A3 cho `no-oidc`**, A5.
6. **A3 cho `ldap`**.
7. **§3 output AUTO** trong execution-service.
8. **1.7, 2.7**: chart, Secret.
9. **A6** frontend. **S12** README: mục "Đăng nhập", "Kyuubi", "Ranger".

Mỗi bước xong phải `mvn verify` xanh.

---

## 5. Test bắt buộc

| # | Ở đâu | Kiểm gì |
|---|---|---|
| T1 | cả hai service | `validate` xanh trên Testcontainers MariaDB : DB trống (V1→V3) và DB có sẵn 5 bảng (baseline + V2, V3) |
| T2 | notebook-service | `document_json` 5 MB round-trip; emoji theo lựa chọn 1.5; `lock_version` xung đột → `409` |
| T3 | notebook-service | Xóa notebook → permission bị xóa theo (FK mới); revision/run **không** có FK (bảng cũ) → B xóa bằng tay trong cùng transaction; test cả hai |
| T4 | nb-common | `DatasourceGuard` 5 tiền tố URL |
| T5 | nb-common | `AuthPropertiesValidator` 9 điều kiện |
| T6 | nb-common | `CredentialCrypto` round-trip, sai key, nonce khác nhau |
| T7 | notebook-service `oidc` | WireMock Kyuubi: 200 → qua; 401 → 401; không trả lời → 503; `Basic` → 401; thiếu claim → 401; cache hết hạn theo `exp` |
| T8 | execution-service `oidc` | Mở session bằng Bearer A; poll operation bằng Bearer B (cùng user, token mới) → WireMock nhận đúng header B, không phải A |
| T9 | notebook-service `no-oidc` | login/logout/cookie/CSRF/header Basic tới Kyuubi |
| T10 | notebook-service `ldap` | UnboundID: bind đúng → username chuẩn (`Alice` → `alice`); sai → 401; LDAP tắt → 503; `adminGroup` → admin; recheck sau đổi mật khẩu → 401; cả hai cách tìm DN |
| T11 | notebook-service `ldap` | Kyuubi WireMock 401 + `WWW-Authenticate: Bearer` → khởi động thất bại |
| T12 | execution-service | Cookie → `/internal/auth/session` → Basic tới Kyuubi; cache 60 s |
| T13 | execution-service | Output `AUTO`: SQL rowset → table; Python stdout → stream; Python exception → error với traceback |
| T14 | notebook-service | Đọc đúng mẫu JSON ở 1.3 → sửa một cell → ghi lại: `parameters`, `kyuubiCellId` còn nguyên; export `.ipynb` → import lại → `document_json` tương đương (bỏ qua id) |
| T15 | e2e (thủ công, ghi vào `phase0-findings.md`) | P5.1–P5.6 trên cluster thật |

---

## 6. Bảng đối chiếu plan cũ → plan này

| Plan cũ | Trạng thái |
|---|---|
| fix-plan.md §4 Lưu trữ (`kyuubi-db`/`postgres`/`h2`) | Bỏ, thay bằng §1 |
| fix-plan.md §3.2 LDAP → round2 `no-oidc` | Tách lại: `no-oidc` (Kyuubi kiểm) và `ldap` (B kiểm) |
| fix-plan.md §6.1 Kyuubi client REST | Giữ, lý do ở §3 |
| fix-plan-round2.md §1 | Giữ, thêm chế độ thứ ba; bảng phiên §2.6 |
| fix-plan-round2.md P1-3, P1-4(b) mime bundle NOTEBOOK | Bỏ; thay bằng S8/S10 và §3 output `AUTO` |
| fix-plan-round2.md P1-6 chart, P1-8 test | Sửa DB/Secret theo 1.7, 2.7; Testcontainers MariaDB |
| `values.yaml` `kyuubi.sessionConfigs.kyuubi.engine.spark.output.mode: NOTEBOOK` | Xóa (Q7) |
| phase0-findings.md §3, §4 | Lỗi thời; thêm §5 (P5.1–P5.6) |
