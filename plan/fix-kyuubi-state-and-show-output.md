# Plan: cell báo LOST dù Kyuubi đã chạy xong, và hiển thị kết quả cell trên UI

Ngày: 2026-10-20. Dành cho AI agent thực hiện. Làm sau `plan/fix-execution-403-and-status-polling.md` (phần A, B đã lên môi trường).

## 0. Triệu chứng

- UI: cell `SHOW DATABASES FROM iceberg;` hiện `LOST`, output ghi
  `Trạng thái thực thi: LOST: Kyuubi engine operation state: FINISHED_STATE`.
- Log Spark engine (driver pod) cho thấy **engine đã lên và query đã chạy xong**:
  ```
  SparkSQLEngine: ... State: STARTED  (User: kyuubi, shared mode: USER)
  ExecuteStatement: Processing kyuubi's query[3eefbff3-...]: PENDING_STATE -> RUNNING_STATE, statement: SHOW DATABASES FROM iceberg;
  ExecuteStatement: ... RUNNING_STATE -> FINISHED_STATE, time taken: 0.075 seconds
  ```
  Ranger plugin StarRocks cũng nạp xong (`Switched policy engine to [276]`, 48 policy).

Kết luận: Kyuubi, Spark, proxy user và Ranger đều hoạt động. Lỗi nằm ở notebook-api khi đọc trạng thái operation.

## 1. Nguyên nhân (đã xác định trong code)

### 1.1. Tên trạng thái Kyuubi trả về có hậu tố `_STATE`

`GET /api/v1/operations/{id}/event` của Kyuubi trả `state` theo tên thrift (`OperationState.toTOperationState`,
`kyuubi-common/.../operation/OperationState.scala:36-45`):

| Kyuubi REST trả | Code notebook đang so sánh ([ExecutionService.java:364-371, 423-508](../backend/nb-execution-service/src/main/java/com/vtnexus/notebook/execution/service/ExecutionService.java)) |
|---|---|
| `INITIALIZED_STATE`, `PENDING_STATE`, `RUNNING_STATE` | `INITIALIZED`, `PENDING`, `RUNNING` |
| `FINISHED_STATE` | `FINISHED` |
| `CANCELED_STATE` | `CANCELED` |
| `ERROR_STATE` | `ERROR` |
| `TIMEDOUT_STATE` | `TIMEOUT` |
| `CLOSED_STATE`, `UKNOWN_STATE` (Kyuubi viết thiếu chữ N) | không có |

Không khớp nhánh nào → rơi vào nhánh cuối `else` → `LOST` với `errorCode = KYUUBI_FINISHED_STATE`.
Hệ quả thêm: với query chạy lâu, lần poll đầu gặp `RUNNING_STATE` cũng bị đánh `LOST` ngay (vì `RUNNING_STATE` ≠ `RUNNING`),
và operation trên engine không bao giờ được `closeOperation`.

Test hiện tại ([ExecutionServiceAutoOutputTest.java:272](../backend/nb-execution-service/src/test/java/com/vtnexus/notebook/execution/service/ExecutionServiceAutoOutputTest.java), `ProxyUserWireMockTest`) stub `"state": "ERROR"` / `"FINISHED"`,
tức là test mã hoá đúng giả định sai, nên không bắt được.

### 1.2. Hiển thị kết quả: backend đã có, frontend đã có, chỉ bị chặn bởi 1.1

- Khi `FINISHED`, backend gọi `resultsetmetadata` + `rowset` (phân trang 500, tối đa `notebook.resultsMaxRows`), ghi
  `outputJson` dạng `execute_result` với `data["application/vnd.notebook.table+json"] = {columns:[{name,type}], rows:[[...]], truncated}`
  và `data["text/plain"]` (bảng ASCII) ([ExecutionService.java:616-668](../backend/nb-execution-service/src/main/java/com/vtnexus/notebook/execution/service/ExecutionService.java)).
  PySpark → `stream` (stdout).
- Frontend `runCell` → `handleExecutionCompleted` → `GET /executions/{id}/results` → `renderParsedOutput` → `renderTableOutput`
  ([cells.js:90-104](../frontend/public/app/cells.js), [render.js:30](../frontend/public/app/render.js)). Đường đi đã đầy đủ.
- Hai điểm sẽ lộ ra ngay khi 1.1 được sửa:
  1. `GET /executions/{id}/results` mặc định `limit=100` ([NotebookExecutionController.java](../backend/nb-execution-service/src/main/java/com/vtnexus/notebook/execution/controller/NotebookExecutionController.java) `getExecutionResults`), frontend gọi không truyền `limit` → chỉ thấy 100 dòng dù backend đã lưu tới 1000; cờ `truncated` của backend chỉ phản ánh giới hạn 1000, không phản ánh việc cắt ở 100.
  2. Khi mở lại notebook, `cells.js:80` render output đã lưu của cell; cần xác nhận document-service có lưu output cuối vào cell (hoặc frontend gọi `GET /executions?notebookId&cellId` để lấy lần chạy gần nhất). Nếu không, F5 là mất kết quả.

### 1.3. Các dòng khác trong log: không chặn, ghi nhận để xử lý ở repo `kyuubi-custom`

| Log | Ý nghĩa | Xử lý |
|---|---|---|
| `ERROR RangerConfiguration: addResourceIfReadable(ranger-starrocks-starrocks-*.xml): couldn't find` | Ranger tìm thêm file theo mẫu `ranger-<serviceType>-<appId>-*.xml`; vì `appId == serviceType == starrocks` nên tên bị lặp. Vô hại | Tuỳ chọn: đổi `appId` của `StarRocksRangerServiceProfile` thành `sparkSql` để log sạch; ghi vào `plan-authz-impl.md` mục R6 |
| `ERROR ... ranger-starrocks-policymgr-ssl.xml: couldn't find` | Chưa dùng SSL tới Ranger Admin (`http://`), file không cần | Bỏ qua, hoặc render file rỗng từ chart khi `ranger.policymgrSsl` trống |
| `WARN RangerScriptConditionEvaluator ... NoClassDefFoundError: jdk/nashorn` rồi `ScriptEngine for engineName=[JavaScript] is successfully created` | Java 17 không có Nashorn, Ranger chuyển sang GraalJS có sẵn trong `/opt/spark/jars` | Bỏ qua |
| `WARN ShellBasedUnixGroupsMapping: unable to return groups for user kyuubi ... no such user` | Spark lấy nhóm của user từ `/etc/passwd` trong driver pod → không có → **policy Ranger theo group sẽ không áp dụng**, chỉ policy theo user | Bật `ranger.plugin.starrocks.use.usergroups.from.userstore.enabled=true` + `ranger.plugin.starrocks.enable.implicit.userstore.enricher=true` trong `ranger-starrocks-security.xml` (Ranger UserStore đồng bộ từ FreeIPA). Thuộc `plan-authz-impl.md` |
| `WARN HiveConf: HiveConf of name hive.server2.proxy.user does not exist` | Cảnh báo chuẩn của Kyuubi khi dùng proxy user | Bỏ qua |

## 2. Việc cần làm

### A. Chuẩn hoá trạng thái Kyuubi (bắt buộc)

- **A1.** Thêm hàm `normalizeKyuubiState(String raw)` trong `ExecutionService` (hoặc `KyuubiResultParser`):
  upper-case, bỏ hậu tố `_STATE`, rồi ánh xạ `TIMEDOUT→TIMEOUT`, `UKNOWN→UNKNOWN`, `COMPILED→RUNNING`. Nhận cả tên trần (`FINISHED`) lẫn tên thrift (`FINISHED_STATE`) để không phụ thuộc phiên bản Kyuubi.
- **A2.** Dùng hàm này tại `syncAndGetExecution` (dòng 364) trước mọi so sánh; `handleOperationTerminalOrLost` nhận state đã chuẩn hoá. Thêm nhánh `CLOSED` → coi như `LOST` với thông báo rõ ("operation đã bị đóng trên engine"), `UNKNOWN` → giữ RUNNING thêm tối đa N lần poll rồi mới LOST.
- **A3.** Sửa `syncLogsAndHeartbeat` và mọi chỗ khác đọc `event.state` (grep `"state"` trong `nb-execution-service`).
- **A4.** Log ở mức WARN khi gặp state không nhận ra, kèm giá trị thô, thay vì âm thầm đánh LOST.
- **A5.** Test:
  - Unit test cho `normalizeKyuubiState` với đủ 9 giá trị thrift + 9 giá trị trần.
  - Sửa stub trong `ExecutionServiceAutoOutputTest` và `ProxyUserWireMockTest` sang giá trị thật của Kyuubi (`FINISHED_STATE`, `RUNNING_STATE`, `ERROR_STATE`, `TIMEDOUT_STATE`). Giữ thêm một ca với tên trần để chứng minh tương thích.
  - Ca WireMock: event `RUNNING_STATE` → DTO `RUNNING` (không LOST); sau đó `FINISHED_STATE` → `SUCCEEDED`, có gọi `resultsetmetadata`, `rowset`, `closeOperation` (dùng `verify` của WireMock).
  - Revert A1 → các ca trên phải fail.

### B. Hiển thị kết quả trên UI

- **B1.** Frontend gọi `GET /executions/{id}/results?offset=0&limit=<notebook.pageSize hoặc 200>`; nếu response có `truncated` hoặc số dòng trả về bằng `limit`, hiện nút "Tải thêm" gọi tiếp với `offset += limit`, nối vào bảng. Backend `getExecutionResults` trả thêm `totalRows` (tổng trước khi cắt) trong JSON để UI hiện "Hiển thị 200/1000 dòng".
- **B2.** Bảng kết quả: header cố định khi cuộn, cuộn ngang, giá trị `null` hiển thị `NULL` mờ, số căn phải, chuỗi dài cắt với tooltip. Kiểm tra `renderTableOutput` với `columns` dạng `{name,type}` và `rows` dạng mảng (đúng với output backend).
- **B3.** Dưới bảng: dòng thống kê "N dòng · X giây · [Xem log] · [Tải CSV]". Tải CSV tạo từ dữ liệu đã tải về trình duyệt (không thêm endpoint).
- **B4.** Giữ kết quả sau khi tải lại trang: xác nhận document-service lưu output cuối vào cell (`NotebookCellDto`/`outputs`). Nếu chưa lưu: sau `SUCCEEDED`/`FAILED`, frontend `PATCH /notebooks/{id}/cells/{cellId}` với `outputs` (định dạng ipynb) và `executionCount`; lúc mở notebook, `cells.js:80` render từ đó. Nếu document-service không nhận `outputs`, bổ sung trường này (DTO + model + migration nếu cột chưa có) và ghi rõ trong plan.
- **B5.** PySpark: `stream` → khối text giữ nguyên xuống dòng; `error` → khung đỏ, `traceback` thu gọn. Đã có `renderStreamOutput`/`renderErrorOutput`, chỉ cần kiểm tra với dữ liệu thật.

### C. Nghiệm thu

| Bước | Tiêu chí |
|---|---|
| A | `mvn verify` xanh; chạy `SHOW DATABASES FROM iceberg;` → cell `Xong`, bảng có cột `namespace`; log execution-service không có `KYUUBI_FINISHED_STATE`; trên engine, operation được đóng (log `closeOperation` hoặc Kyuubi UI không còn operation treo) |
| A | Query chạy > 10 giây (ví dụ `SELECT count(*)` bảng lớn, hoặc `SELECT java_method('java.lang.Thread','sleep',15000L)`) → cell hiện `Đang chạy (xx s)` rồi `Xong`, không LOST |
| A | Query sai cú pháp → `Lỗi`, khung đỏ có message của Spark |
| B | Query trả > 200 dòng → hiện 200 dòng + "Tải thêm" + "Hiển thị 200/N"; > 1000 dòng → có badge "Kết quả đã bị cắt bớt" |
| B | F5 sau khi chạy → kết quả cell còn nguyên |
| B | Cell PySpark `print("hi")` → output `hi` |
| Chung | `./scripts/build.sh` xanh; không thêm comment trong code; không sửa `values.yaml` |

## 3. Đầu vào từ người dùng

| # | Đầu vào | Dùng cho |
|---|---|---|
| I1 | Xác nhận `notebook.pageSize` (hiện 200 trong `values.yaml`) là kích thước trang kết quả mong muốn | B1 |
| I2 | Có cần nút "Tải CSV" ở bước đầu không | B3 |

## 4. Ngoài phạm vi

- Các mục Ranger ở 1.3 (làm trong `kyuubi-custom/docs/plans/plan-authz-impl.md`).
- Biểu đồ, hiển thị kiểu dữ liệu phức tạp (struct/map) ngoài dạng chuỗi.
