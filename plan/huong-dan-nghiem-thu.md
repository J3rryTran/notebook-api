# Hướng dẫn nghiệm thu `notebook-api` (dành cho AI agent)

Đối tượng: AI agent được giao nghiệm thu một commit/branch của repo này.
Đầu ra: **một file markdown** `plan/nghiem-thu-<YYYY-MM-DD>.md` theo mẫu ở mục 7. Không trả kết quả
chỉ trong chat.

Tài liệu chuẩn để đối chiếu (đọc trước, theo thứ tự ưu tiên khi mâu thuẫn):

1. `plan/mariadb-auth-plan.md` (plan hiện hành: MariaDB, 3 chế độ auth, Kyuubi REST, Ranger)
2. `plan/fix-plan-round2.md` (phần không bị plan 1 thay thế; bảng đối chiếu ở §6 plan 1)
3. `plan/fix-plan.md` (vòng 1, chỉ phần còn hiệu lực)
4. Biên bản nghiệm thu trước gần nhất `plan/nghiem-thu-*.md` (để kiểm lỗi cũ đã sửa chưa)

---

## 0. Quy tắc bắt buộc

| # | Quy tắc |
|---|---|
| R1 | **Chỉ đọc.** Không sửa code, không commit, không push, không chạy migration lên DB thật (`10.29.19.52`). File duy nhất được tạo là biên bản ở mục 7 (và file tạm trong scratchpad). |
| R2 | **Mọi kết luận phải có bằng chứng**: `đường/dẫn/File.java:dòng`, output lệnh, hoặc trích đoạn code ngắn. Không có bằng chứng → ghi `CHƯA KIỂM`, không ghi `ĐẠT`. |
| R3 | **Test xanh không có nghĩa là đúng.** Với mỗi test, kiểm dữ liệu giả có khớp định dạng thật không (JSON Kyuubi, DDL MariaDB). Test gọi trực tiếp một class không chứng minh class đó được Spring nạp. |
| R4 | **Kiểm lại lỗi nặng bằng tay.** Nếu dùng sub-agent, mọi lỗi mức CHẶN mà sub-agent báo phải được tự đọc lại code trước khi ghi vào biên bản. Sub-agent từng báo sai (ví dụ: `Duration.parse("PT8h")` *không* lỗi vì Java parse không phân biệt hoa thường). |
| R5 | Không bịa số liệu: số test, số file, thời gian build lấy từ output thật. |
| R6 | Không đưa secret vào biên bản (mật khẩu DB, token, `auth-encryption-key`). |
| R7 | Viết tiếng Việt, ngắn, dạng bảng. Tên file, class, cấu hình giữ nguyên tiếng Anh. |

---

## 1. Chuẩn bị

### 1.1 Xác định phạm vi

```bash
git -C /home/j3rrytran/Documents/trungtm_VT/notebook-api log --oneline -5
```

```bash
git -C /home/j3rrytran/Documents/trungtm_VT/notebook-api status -sb
```

```bash
git -C /home/j3rrytran/Documents/trungtm_VT/notebook-api diff --stat <commit-nghiệm-thu-trước>..HEAD
```

Ghi vào biên bản: commit được nghiệm thu (hash + message + tác giả + ngày), đã push hay chưa
(`git log origin/main..HEAD`), số file và số dòng thay đổi. Nếu working tree có thay đổi chưa commit,
ghi rõ và nghiệm thu **cả** phần đó.

### 1.2 Môi trường build

Máy hiện tại **không có `mvn`** và chỉ có **Java 8**, dự án cần **Java 17**. Thư mục
`apache-maven-3.9.11/` trong repo **không dùng được** (thư mục `lib/` chỉ có license, jar bị
`.gitignore` chặn). Dùng Maven trong Docker:

```bash
docker run --rm -v /home/j3rrytran/Documents/trungtm_VT/notebook-api/backend:/src -v "$HOME/.m2":/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal --add-host=host.docker.internal:host-gateway -w /src maven:3.9.9-eclipse-temurin-17 mvn -B verify
```

Mount `docker.sock` để test Testcontainers (MariaDB) chạy được. Timeout lệnh tối thiểu 10 phút.
Lọc output: `| grep -E "Tests run|ERROR|FAIL|BUILD|Reactor|SUCCESS"`.

Nếu build lỗi: ghi nguyên văn 20 dòng lỗi đầu tiên vào biên bản, kết luận tổng `KHÔNG ĐẠT`, vẫn
tiếp tục đọc code ở mục 3–6.

---

## 2. Kiểm tra tự động (chạy được, không cần cluster)

| # | Kiểm gì | Cách | Đạt khi |
|---|---|---|---|
| K1 | Build + test | Lệnh 1.2 | `BUILD SUCCESS`; ghi số test từng module |
| K2 | `EnvironmentPostProcessor` được nạp thật | Tìm `META-INF/spring.factories` trong `nb-common/src/main/resources` có key `org.springframework.boot.env.EnvironmentPostProcessor=` liệt kê **cả** `AuthPropertiesValidator` và `DatasourceGuard` | Có. **Lưu ý:** file `META-INF/spring/…EnvironmentPostProcessor.imports` **không** được Spring Boot 3.3 đọc, không tính |
| K3 | Khởi động thất bại khi cấu hình sai | Nếu có test `@SpringBootTest` với `notebook.auth.mode=abc` / URL `jdbc:postgresql:` → kỳ vọng context fail. Không có test → ghi `THIẾU TEST`. | Có test và pass |
| K4 | Không còn Postgres/H2 | `grep -rniE "postgres|h2database|jdbc:h2" backend --include=*.xml --include=*.yml --include=*.java` (bỏ `target/`) và `README.md`, `BUILD.md` | Rỗng |
| K5 | Không còn ArgoCD và đường dẫn máy cá nhân | `grep -rniE "argocd|argo-cd|argoproj|file:///mnt" . --exclude-dir=.git --exclude-dir=target` | Rỗng (ArgoCD thuộc hệ thống khác, repo này không chứa) |
| K6 | Không có secret cứng | `grep -rniE "password\s*[:=]\s*\S+|secret\s*[:=]|token\s*[:=]\s*\"[^$]" backend/*/src/main docker notebook-helm` | Chỉ có trong `application-dev.yml` hoặc placeholder `${…}` |
| K7 | Không đặt output mode NOTEBOOK | `grep -rn "output.mode" backend notebook-helm` | Chỉ xuất hiện trong validator (để cấm) |
| K8 | Testcontainers MariaDB được dùng | `grep -rn "MariaDBContainer" backend/*/src/test` | Có ít nhất một test cho mỗi service |
| K9 | Chart render được | `docker run --rm -v $PWD/notebook-helm:/c alpine/helm:3.14.4 template t /c` với lần lượt `--set auth.mode=oidc`, `no-oidc`, `ldap` | Render không lỗi; env đúng theo mode (mục 6) |
| K10 | Script build chạy được trên máy sạch | Đọc `scripts/*.sh`, `DOCKER.md`, `BUILD.md`: không phụ thuộc `apache-maven-3.9.11/` | Dùng `mvn` hệ thống, Maven Wrapper, hoặc build Maven trong Docker |

---

## 3. Lưu trữ (đối chiếu plan §1)

Nguồn sự thật DDL: plan §1.1 (database `test`). Đọc migration `nb-notebook-service/src/main/resources/db/migration/` và mọi `@Entity`.

| # | Kiểm gì | Đạt khi |
|---|---|---|
| L1 | `V1__baseline.sql` khớp từng cột DDL §1.1 (kiểu, NULL, default, key, charset) | Khớp |
| L2 | `V2` đủ: `node_id` NULL; 5 cột + 2 index trên `notebooks`; `kyuubi_session_ref`; `execution_count`; `source`/`error_message` LONGTEXT; 5 bảng mới `utf8mb4_bin`; mọi lệnh idempotent | Đủ, cú pháp MariaDB hợp lệ |
| L3 | `V3` convert 4 bảng sang `utf8mb4_bin` | Có |
| L4 | Mỗi entity khớp migration (tên cột, độ dài, `LONGTEXT`, `VARBINARY`, `CHAR(36)`, `DATETIME(6)`) | Khớp; **chỉ ghi ĐẠT nếu có test T1 chạy `validate` trên MariaDB thật**, không thì ghi `CHƯA KIỂM (không có T1)` |
| L5 | Flyway chỉ chạy ở notebook-service; execution-service `enabled: false`; `baseline-on-migrate`, `baseline-version: 1` | Đúng |
| L6 | Datasource không có giá trị mặc định ở `application.yml` gốc | Đúng |
| L7 | `notebook_format` luôn `SQLLAB_JSON_V1`; `node_id` không bao giờ được ghi | Đúng |
| L8 | `NotebookDocument` giữ trường lạ ở root, `metadata`, cell, cell `metadata` (`@JsonAnySetter/Getter` hoặc `Map`) | Có test round-trip với mẫu ở plan §1.3 giữ nguyên `parameters`, `kyuubiCellId` |
| L9 | `schemaVersion ≠ 1` → 409; tài liệu > 16 MB → 413; cell > 500 → lỗi; source > 1 MB → lỗi; đo bằng `StandardCharsets.UTF_8` | Đủ cả 4 |
| L10 | `@Version` trên `lock_version`; `ObjectOptimisticLockingFailureException` map thành 409 | Có handler |
| L11 | Xóa notebook: permission cascade; revision, run, cell-run, log bị xóa trong cùng transaction (hoặc xóa mềm có chủ đích và ghi trong plan) | Đúng plan |
| L12 | `.ipynb` theo bảng ánh xạ plan §1.3: `metadata.language`, magic `%%sql/%%python`, fallback `defaultLanguage`, `RAW`, `execution_count`, `outputs` khi `includeOutputs`, `kernelspec` lưu và xuất lại, lỗi 409/413 không bị đổi thành 400 | Từng dòng bảng ánh xạ |
| L13 | Dọn revision giữ `MANUAL`, xóa `AUTO` cũ nhất vượt giới hạn | Đúng |
| L14 | Lỗi parse JSON khi cập nhật → trả lỗi, không tăng revision | Đúng |

---

## 4. Bảo mật (đối chiếu plan §2, round2 §1.3–1.4)

Đọc: `nb-common/.../auth/*`, `*/config/SecurityConfig.java`, `AuthController`, `AuthConfigController`, `InternalAuthController`, `AuthSessionService`, `LdapAuthenticator`, `InternalSessionClient`, `KyuubiRestClient`.

| # | Kiểm gì | Đạt khi |
|---|---|---|
| A1 | Mode thiếu/sai → khởi động thất bại; **không** có nhánh `else` rơi về `oidc` | Đúng (kết hợp K2) |
| A2 | Validator có đủ 9 điều kiện plan §2.5 | Đủ |
| A3 | `oidc`: chỉ nhận `Bearer`; từ chối `Basic`/cookie; `/ping` 200 → qua, 401/403 → 401, không trả lời → 503; thiếu claim → 401; cache Caffeine TTL = min(`cacheTtl`, `exp`−now); token không có `exp` → không cache hoặc TTL = `cacheTtl` có ghi chú | Đủ |
| A4 | Thăm dò Kyuubi `NONE` lúc khởi động; dùng `allowUnverified` | Có |
| A5 | Cookie `NB_SESSION`: `HttpOnly`, `Secure` (trừ dev), `SameSite=Strict`, `Path=/` | Đủ 4 |
| A6 | CSRF bật cho `no-oidc`/`ldap` (`CookieCsrfTokenRepository.withHttpOnlyFalse()`), tắt cho `oidc` | Đúng |
| A7 | `credential_enc` AES-256-GCM, nonce ngẫu nhiên; đổi key → xóa phiên, 401, không crash | Đúng |
| A8 | `recheck`: no-oidc gọi lại `/ping`, ldap bind lại, theo `last_recheck_at` | Có |
| A9 | Phiên trượt theo `last_seen_at` (ghi tối đa 1 lần/phút), job dọn phiên hết hạn | Có |
| A10 | LDAP: escape `{0}` (`LdapEncoder.nameEncode` cho DN, `filterEncode` cho filter); dùng DN tìm được; trả `usernameAttribute` làm tên chuẩn | Đủ 3 |
| A11 | LDAP: `adminGroup`; StartTLS thật khi `ldap://`; `trustStore`; `trustAll` chỉ dev; kiểm Kyuubi-OIDC không tương thích lúc khởi động | Đủ |
| A12 | `/internal/**`: so token bằng `MessageDigest.isEqual`; khớp đường dẫn `"/internal/"` có dấu `/`; nginx chặn `/internal/` từ ngoài | Đủ |
| A13 | Proxy user (plan §2.2 bước 4): tra `app_users.ranger_username`; bằng nhau → token người dùng; khác → token client credentials `notebook-api` + `hive.server2.proxy.user`; thiếu secret → 503 `PROXY_UNAVAILABLE` | Có |
| A14 | `KyuubiRestClient` dùng header của **request hiện tại**; không rơi về credential khác khi thiếu header | Đúng |
| A15 | Không trả `exception.getMessage()` / body lỗi Kyuubi thô cho client; không log credential | Đúng |
| A16 | `/api/v1/notebook-status` yêu cầu đăng nhập | Đúng |

---

## 5. Execution (đối chiếu plan §3, §1.2, round2 P1-4, P1-5, P2-*)

**Định dạng thật của Kyuubi REST** (dùng để kiểm code và dữ liệu giả trong test):

```json
GET /api/v1/operations/{op}/rowset?maxrows=..&fetchorientation=FETCH_NEXT
{"rows":[{"fields":[{"dataType":"STRING","value":"a"},{"dataType":"INT","value":1}]}],"rowCount":1}

GET /api/v1/operations/{op}/resultsetmetadata
{"columns":[{"columnName":"c1","dataType":"STRING_TYPE","precision":0,"scale":0}]}

GET /api/v1/operations/{op}/log?maxrows=..
{"logRowSet":["line1","line2"],"rowCount":2}
```

Trạng thái operation Kyuubi: `INITIALIZED, PENDING, RUNNING, FINISHED, CANCELED, CLOSED, ERROR, TIMEOUT, UNKNOWN`.

| # | Kiểm gì | Đạt khi |
|---|---|---|
| E1 | Đọc rowset theo `rows[].fields[].value`; lấy tên cột từ `resultsetmetadata`; `truncated` đúng khi vượt `results.max-rows` | Đúng; **dữ liệu giả trong test phải có dạng trên**, test dùng mảng JSON trần → ghi `TEST SAI ĐỊNH DẠNG` |
| E2 | SQL → `execute_result` table; Python stdout → `stream`; lỗi → `error` với `ename`/`evalue`/`traceback` tách từ message | Đủ |
| E3 | Ánh xạ trạng thái: `FINISHED→SUCCEEDED`, `ERROR/TIMEOUT→FAILED`, `CANCELED→CANCELED`, `CLOSED/UNKNOWN→LOST`, 409 `RUNTIME_LOST → LOST`; không trạng thái nào kẹt `RUNNING` | Đủ |
| E4 | `notebook_runs`: `PENDING` trước submit, `RUNNING`, `heartbeat_at`, `cancel_requested`; `notebook_cell_runs`: `execution_id`, `row_count`, `execution_time_ms`, `output_type`, `output_json`; log vào `notebook_execution_logs` | Đủ cột |
| E5 | Submit lỗi → còn bản ghi `FAILED` | Có |
| E6 | Endpoint: `outputs`, `logs?afterSequence=`, `results?offset=&limit=`, `DELETE` session (đóng cả session Kyuubi), `runtimes`, `:interrupt` (hủy operation đang chạy của runtime) | Tham số được dùng thật, `:interrupt` không 404 |
| E7 | `/events` long-poll chạy trên `TaskExecutor` riêng, giữ security context, tôn trọng `waitMillis` | Đúng |
| E8 | Không gọi HTTP Kyuubi bên trong transaction giữ khóa `FOR UPDATE` | Đúng |
| E9 | Submit đồng thời không trùng `execution_count`; hủy không ghi đè trạng thái kết thúc | Có khóa/kiểm |
| E10 | Session `LOST`/`CLOSED` không được dùng lại | Đúng |
| E11 | Kiểm owner/quyền ở mọi endpoint nhận `sessionId`/`executionId`/`runtimeId`; `RUNNER` chạy được, `VIEWER` không | Đủ |
| E12 | Config Spark người dùng lọc theo `allowedSessionConfigKeys` và **thực sự được truyền** (không luôn `null`) | Đúng |
| E13 | `/notebook-status`: Kyuubi = kết quả `/ping` thật; persistence = `SELECT 1` | Đúng |

---

## 6. Đóng gói, chart, frontend

| # | Kiểm gì | Đạt khi |
|---|---|---|
| D1 | `Dockerfile`/`entrypoint.sh`: với `topology: allInOne`, tự sinh `NOTEBOOK_INTERNAL_TOKEN` từ `/dev/urandom` khi không được truyền (không in ra log); `/internal/**` chỉ nhận từ loopback và bị nginx chặn từ ngoài; chạy non-root; dừng sạch khi SIGTERM | Đủ |
| D2 | Probe kiểm cả notebook-service lẫn execution-service | Có |
| D3 | nginx: route execution trước notebook; chặn `/internal/`; đặt `X-Forwarded-Proto`; `server.forward-headers-strategy` bật phía Spring | Đủ |
| D4 | Chart truyền đủ: `auth.mode`, `oidc.issuer/clientId/scopes/usernameClaim`, `auth.ldap.*`, `kyuubi.*`, Secret theo plan §2.7 chỉ cho mode đang chọn | Mọi key trong `values.yaml` được template dùng |
| D5 | Frontend `oidc`: Authorization Code + **PKCE**, trang `/auth/callback` đổi code lấy token, gửi `Authorization: Bearer` mọi request, silent renew | Đủ; thiếu PKCE/callback → **CHẶN** |
| D6 | Frontend `no-oidc`/`ldap`: form, gửi `X-XSRF-TOKEN`, logout | Đủ |
| D7 | Tài liệu `README.md`, `BUILD.md`, `DOCKER.md`: không ArgoCD, không đường dẫn máy cá nhân, không Postgres/H2, có mục Đăng nhập / Kyuubi / Ranger | Đủ |

---

## 7. Mẫu biên bản (bắt buộc dùng)

Tạo `plan/nghiem-thu-<YYYY-MM-DD>.md`. Nếu đã có file cùng ngày, thêm hậu tố `-2`, `-3`. Không ghi đè.

```markdown
# Nghiệm thu `<hash>` ("<commit message>") theo `plan/mariadb-auth-plan.md`

Ngày: <YYYY-MM-DD>. Người nghiệm thu: AI agent (<model>).
Phạm vi: <n> file, +<a> / −<d>, so với `<commit trước>`. Đã push: <có/không>. Thay đổi chưa commit: <có/không>.
Môi trường: Maven <ver> / JDK <ver> (Docker).

**Kết luận: <ĐẠT | ĐẠT CÓ ĐIỀU KIỆN | CHƯA ĐẠT>.** <1–2 câu lý do.>

| Mảng | Đạt | Một phần | Không đạt / thiếu |
|---|---|---|---|
| Build + test | | | |
| Lưu trữ §1 | | | |
| Bảo mật §2 | | | |
| Execution §3 | | | |
| Đóng gói / frontend | | | |

## 1. Lỗi chặn

| # | Lỗi | Ở đâu (file:dòng) | Hậu quả | Mã kiểm |
|---|---|---|---|---|

## 2. Lỗi cần sửa (không chặn)

Nhóm theo: Bảo mật / Lưu trữ / Execution / Đóng gói. Mỗi dòng: mô tả + `file:dòng` + mã kiểm.

## 3. Đối chiếu biên bản trước

| Lỗi cũ (biên bản <ngày>) | Trạng thái | Bằng chứng |
|---|---|---|
| B1 … | ĐÃ SỬA / CHƯA SỬA / SỬA MỘT PHẦN | |

## 4. Bảng kiểm chi tiết

| Mã | Kết quả | Bằng chứng |
|---|---|---|
| K1 | ĐẠT / KHÔNG ĐẠT / MỘT PHẦN / CHƯA KIỂM / THIẾU TEST | |
| … (đủ K1–K10, L1–L14, A1–A16, E1–E13, D1–D7) | | |

## 5. Test

| Plan | Có | Ghi chú (dữ liệu giả có đúng định dạng thật không) |
|---|---|---|
| T1 … T15 | | |

## 6. Việc cần kiểm trên cluster (không làm được tại chỗ)

P5.1–P5.7 (plan §2.2): trạng thái đã có kết quả hay chưa.

## 7. Thứ tự sửa đề xuất

1. …
```

**Quy tắc kết luận:**

- `ĐẠT`: build xanh, không có lỗi chặn, mọi mục kiểm ở trạng thái `ĐẠT` hoặc `CHƯA KIỂM` có lý do
  chính đáng (chỉ kiểm được trên cluster).
- `ĐẠT CÓ ĐIỀU KIỆN`: không có lỗi chặn, còn lỗi cần sửa hoặc thiếu test.
- `CHƯA ĐẠT`: build lỗi, **hoặc** có ít nhất một lỗi chặn.

**Mức CHẶN** (luôn xếp vào mục 1): build/test đỏ; cấu hình bảo mật không được nạp; bỏ qua xác
thực/phân quyền; CSRF hoặc injection; mất/sai dữ liệu; cell chạy không ra kết quả trên Kyuubi thật;
chế độ đăng nhập mặc định không dùng được; Flyway/`validate` lỗi trên MariaDB.

---

## 8. Cách chia việc nếu dùng sub-agent

Tối đa 3 sub-agent chạy song song, mỗi agent **chỉ đọc**, trả báo cáo ≤ 700 từ có `file:dòng`:

| Agent | Phạm vi | Mã kiểm |
|---|---|---|
| storage | mục 3 | L1–L14 |
| auth | mục 4 | A1–A16 |
| execution | mục 5 | E1–E13 |

Agent chính tự làm mục 1, 2, 6, rồi **kiểm lại bằng tay mọi lỗi CHẶN** sub-agent báo (R4) trước khi
viết biên bản. Lỗi sub-agent báo mà kiểm lại thấy sai → bỏ, không ghi.

Sau khi viết xong biên bản, trả lời trong chat **tối đa 10 dòng**: kết luận, số lỗi chặn, đường dẫn
file biên bản.
