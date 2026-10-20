# Plan: sửa 403 khi theo dõi execution, cell kẹt RUNNING, và giảm gọi `notebook-status`

Ngày: 2026-10-20. Dành cho AI agent thực hiện. Mode đang chạy: `auth.mode: ldap`, 4 replica, NodePort.

## 0. Triệu chứng

1. Chạy cell `SHOW DATABASES FROM iceberg;` → `POST /notebooks/{id}/sessions` 200, `POST /notebook-sessions/{sid}/executions` 200,
   rồi `GET /api/v1/executions/{execId}/events?waitMillis=3000` trả **403 Forbidden, body rỗng** (`Content-Length: 0`).
2. Cell hiện `RUNNING` mãi, output ghi `Trạng thái thực thi: RUNNING`.
3. Trên Kyuubi không thấy Spark engine được tạo.
4. Tab Network có rất nhiều request `GET /api/v1/notebook-status`.

## 1. Nguyên nhân đã xác định trong code

### 1.1. 403 trên `/executions/{id}/events`: filter xác thực không chạy ở ASYNC dispatch

- Endpoint này trả `DeferredResult` ([NotebookExecutionController.java:109-170](../backend/nb-execution-service/src/main/java/com/vtnexus/notebook/execution/controller/NotebookExecutionController.java)).
  Khi `DeferredResult` hoàn tất, servlet container **dispatch lại request lần hai** với `DispatcherType.ASYNC`, và toàn bộ filter chain của Spring Security chạy lại.
- `SessionCookieFilter` và `BearerFilter` kế thừa `OncePerRequestFilter`. Mặc định `shouldNotFilterAsyncDispatch()` trả `true`, nên **hai filter này không chạy ở lần dispatch ASYNC**. Thêm nữa, ở lần dispatch đầu chúng đã `SecurityContextHolder.clearContext()` trong `finally` ([SessionCookieFilter.java:85-90](../backend/nb-common/src/main/java/com/vtnexus/notebook/common/auth/SessionCookieFilter.java)).
- `AuthorizationFilter` của Spring Security 6 **có** chạy ở ASYNC dispatch. Gặp `anyRequest().authenticated()` mà SecurityContext trống → `ExceptionTranslationFilter` → entry point mặc định `Http403ForbiddenEntryPoint` → **403, body rỗng**. Khớp đúng với ảnh (các lỗi do code tự viết đều có body JSON `ErrorEnvelope`).
- `ExecutionCsrfTest` không bắt được vì MockMvc không tự chạy async dispatch (cần `asyncDispatch(mvcResult)`).

Hệ quả: nội dung `DeferredResult` (status 200/204 đã set) bị thay bằng 403. Endpoint `/executions/{id}` (đồng bộ) vẫn hoạt động, nên dữ liệu trong DB vẫn đúng.

### 1.2. Cell kẹt `RUNNING`: frontend thoát vòng poll khi gặp mã khác 200/204

[execution.js:143-168](../frontend/public/app/execution.js): nhận 403 → `break` nhưng `finalStatus` vẫn là `RUNNING` → gọi `handleExecutionCompleted(..., 'RUNNING')` → hiển thị "Trạng thái thực thi: RUNNING" rồi dừng hẳn, không poll nữa, không báo lỗi.

### 1.3. Không thấy Spark engine: chưa đủ dữ liệu, cần chẩn đoán (mục 3)

Session được mở với `kyuubi.session.engine.launch.async=true` ([KyuubiRestClient.java:51](../backend/nb-execution-service/src/main/java/com/vtnexus/notebook/execution/client/KyuubiRestClient.java)),
nên Kyuubi trả session ngay và khởi động engine ở nền. Sau 1.1, phía notebook không còn theo dõi được, nên không biết engine lỗi hay chưa kịp lên. Hai nghi vấn phải kiểm trên log:

| Nghi vấn | Dấu hiệu |
|---|---|
| Proxy user bị từ chối (`hadoop.proxyuser.kyuubi.*` chưa có hiệu lực) | Log execution-service: `Kyuubi open session error` / `PROXY_NOT_ALLOWED`; log Kyuubi: `is not allowed to impersonate` |
| `pinToInstance: true` trỏ tới `kyuubiInstance` không resolve được từ pod notebook | Kyuubi trả `kyuubiInstance` dạng `<hostname-pod>:10099` (không có FQDN headless service). `getBaseUrl(instance)` ([KyuubiRestClient.java:72-77](../backend/nb-execution-service/src/main/java/com/vtnexus/notebook/execution/client/KyuubiRestClient.java)) dùng thẳng giá trị này cho `submitStatement` / `getOperationEvent` → `ResourceAccessException` → `KYUUBI_UNAVAILABLE`. Log: `Kyuubi unavailable on submit statement` hoặc `Kyuubi connection error on get operation event` kèm `UnknownHostException` |

### 1.4. `notebook-status` bị gọi nhiều

- [notebook.js:350-354](../frontend/public/app/notebook.js): `setInterval(pollKyuubiStatus, 15000)`, chạy từ lúc mở trang, kể cả khi tab ẩn. 116 request trong ~27 phút khớp chu kỳ 15 s, không phải lỗi trùng timer.
- Mỗi request phía server ([NotebookExecutionController.java:308-344](../backend/nb-execution-service/src/main/java/com/vtnexus/notebook/execution/controller/NotebookExecutionController.java)): gọi `/internal/auth/session` để xác thực, ping Kyuubi (cache 10 s nhưng **per pod**, 4 pod → gần như không cache), `SELECT 1` và 3 câu `count`.

## 2. Việc cần làm

### A. Backend: cho phép ASYNC dispatch đi qua xác thực

Chọn **A1** (cách Spring khuyến nghị); A2 là phương án thay thế nếu A1 không đủ.

- **A1.** Trong `SecurityConfig` của **cả hai** service (`nb-execution-service` và `nb-notebook-service`), thêm trước `anyRequest()`:
  ```java
  .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
  ```
  Lý do an toàn: dispatch ASYNC chỉ xảy ra sau khi dispatch REQUEST đã được xác thực; controller đã chụp `principal` và `auth` vào closure từ lần đầu.
- **A2.** (thay thế) override `shouldNotFilterAsyncDispatch()` trả `false` trong `SessionCookieFilter`/`BearerFilter` và không `clearContext()` khi `request.getDispatcherType() == ASYNC`. Phức tạp hơn và tốn thêm 1 lần gọi `/internal/auth/session` mỗi long-poll, nên chỉ dùng nếu A1 không qua test.
- **A3.** Test: thêm vào `ExecutionCsrfTest` (hoặc test mới) ca `GET /api/v1/executions/{id}/events` với MockMvc:
  `MvcResult r = mockMvc.perform(get(...)).andExpect(request().asyncStarted()).andReturn(); mockMvc.perform(asyncDispatch(r)).andExpect(status().isOk())` (và ca 204 khi timeout). Revert A1 → test phải fail với 403.
- **A4.** Rà các endpoint khác dùng `DeferredResult`/`Callable`/SSE trong cả hai service (grep `DeferredResult|Callable<|SseEmitter`) và áp cùng cách.

### B. Frontend: vòng poll không được im lặng khi lỗi

[execution.js](../frontend/public/app/execution.js) `runCell`:
- **B1.** Mã khác 200/204: đọc body (JSON `ErrorEnvelope` nếu có), hiển thị lỗi trên cell (`renderErrorOutput` với `ename = 'PollError'`, `evalue = "<status> <code>: <message>"`), đặt `finalStatus = 'ERROR'`, và **không** gọi nhánh "Trạng thái thực thi: RUNNING".
- **B2.** 401 → thông báo phiên hết hạn và mở lại form đăng nhập (dùng hàm có sẵn trong `main.js`). 503/`KYUUBI_UNAVAILABLE` → thử lại tối đa N lần với backoff 1s→2s→4s (tối đa 30s), hiển thị "Kyuubi tạm không phản hồi, đang thử lại (k/N)".
- **B3.** Khi `finalStatus` là `LOST` hoặc status lạ: hiển thị `errorMessage` từ `GET /executions/{id}` thay vì chỉ in tên trạng thái.
- **B4.** Giới hạn thời gian chờ tổng (ví dụ `notebook.executionMaxWait`, mặc định 30 phút) để không poll vô hạn; hết giờ thì hiện nút "Kiểm tra lại".

### C. Giảm gọi `notebook-status`

- **C1.** Frontend: chu kỳ 60 s; dừng khi `document.visibilityState !== 'visible'` (lắng nghe `visibilitychange`, poll ngay khi tab hiện lại); poll ngay sau khi một execution kết thúc và sau `restartSession`. Giữ `clearInterval` trước khi tạo timer mới.
- **C2.** Backend `getNotebookStatus`: bỏ `SELECT 1` riêng (3 câu `count` đã chứng minh DB sống; nếu chúng ném lỗi thì `persistence = UNHEALTHY`); nâng cache ping Kyuubi lên 30 s. Trả thêm `checkedAt` để UI hiển thị.
- **C3.** Không đổi hợp đồng response (`kyuubi`, `persistence`, `active`, `queued`, `activeSessions`).

### D. Chẩn đoán và sửa phần "không thấy Spark engine" (làm sau A, vì cần theo dõi được execution)

- **D1.** Build image mới với A+B, chạy lại cell, lấy:
  - log execution-service: các dòng `Kyuubi open session error`, `Kyuubi submit statement error`, `Kyuubi unavailable`, `Kyuubi connection error`, `PROXY_NOT_ALLOWED`;
  - log Kyuubi server quanh thời điểm đó: `hive.server2.proxy.user`, `impersonate`, `Launching engine`, `Spark` driver;
  - `kubectl get pods -n kyuubi-operator-experiment` xem driver pod có xuất hiện không.
- **D2.** Nếu lỗi là `UnknownHostException` tới `kyuubiInstance`: đặt `kyuubi.pinToInstance: false` trong `values.yaml` (đi qua Service) **hoặc** cấu hình Kyuubi `kyuubi.frontend.rest.bind.host`/`kyuubi.frontend.advertised.host` thành FQDN headless (`<pod>.<headless-svc>.<ns>.svc.cluster.local`). Ghi rõ chọn cách nào; với 4 replica notebook và Kyuubi nhiều instance, đi qua Service chỉ đúng nếu Kyuubi dùng HA/ZK để tìm session, cần xác nhận.
- **D3.** Nếu lỗi là impersonate: xác nhận `core-site.xml`/`kyuubi-defaults.conf` đã có `hadoop.proxyuser.kyuubi.users=*`, `hosts=*` và Kyuubi đã restart.
- **D4.** Nếu engine lỗi khi khởi động (Spark on K8s): lấy log driver pod; nằm ngoài phạm vi notebook-api, chỉ ghi nhận.
- **D5.** Bổ sung log mức INFO trong `KyuubiRestClient.openSession`/`submitStatement`: `sessionId`, `kyuubiInstance`, `proxyUser`, base URL thực dùng, để lần sau chẩn đoán không phải đoán.

### E. Nghiệm thu

| Bước | Tiêu chí |
|---|---|
| A | `mvn verify` xanh; test async dispatch mới pass, revert A1 thì fail 403 |
| B | Tắt execution-service (scale 0) rồi chạy cell → cell hiện lỗi rõ, không kẹt RUNNING; bật lại → chạy bình thường |
| C | Mở tab 10 phút: ≤ 10 request `notebook-status`; ẩn tab 5 phút: 0 request |
| D | `SHOW DATABASES FROM iceberg;` trả bảng trên UI; Kyuubi có session mang `hive.server2.proxy.user=<user đăng nhập>`; driver pod xuất hiện |
| Chung | `./scripts/build.sh` xanh; không thêm comment trong code; không sửa `values.yaml` ngoài các khóa nêu ở D2 |

## 3. Đầu vào cần người dùng cung cấp cho agent

| # | Đầu vào | Dùng cho |
|---|---|---|
| I1 | Log execution-service lúc chạy cell (sau khi deploy A+B) | D1 |
| I2 | Log Kyuubi server cùng thời điểm | D1, D3 |
| I3 | Giá trị `kyuubiInstance` Kyuubi trả về (xem cột `kyuubi_instance` bảng `notebook_kyuubi_sessions` trong MariaDB `test`) | D2 |
| I4 | Kyuubi có bật HA/ZooKeeper không | D2 |

## 4. Ngoài phạm vi

- Thay long-poll bằng SSE/WebSocket.
- Tô màu cú pháp, các mục khác trong `plan/ui-ux-redesign.md`.
