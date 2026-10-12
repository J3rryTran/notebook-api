# Notebook rời kết nối Kyuubi: bảng tổng hợp khả năng (Zeppelin + Jupyter), chỉ dùng `.ipynb`

Đặc tả cho **ứng dụng notebook rời B**, dựng và triển khai độc lập. B **không phải** một phần của
Kyuubi và không sửa gì trong Kyuubi. Kyuubi chỉ đóng vai cổng vào Spark: B gọi Kyuubi qua REST, Kyuubi
khởi tạo hoặc tái dùng Spark engine và chuyển statement tới engine (xem mục *Giao tiếp B → Kyuubi →
Spark engine*). Tài liệu mô tả hợp đồng dự kiến, chưa phải hành vi đã có. Bảng dưới là **nguồn duy
nhất** để lên plan: mỗi dòng là một hạng mục, có mã `F..` để tham chiếu.

**Cột**

- **Nguồn**: ý tưởng lấy từ đâu. `Zeppelin` = Jupyter không có; `Jupyter` = Zeppelin không có;
  `Cả hai` = cả hai đều có, đã chọn một cách; `Kyuubi` = ràng buộc khi chạy qua Kyuubi tới Spark engine.
- **Class**: `SUPPORTED_DIRECTLY` tương đương trực tiếp · `MAPPED` có nhưng khác tên/hình dạng ·
  `INTENTIONALLY_DIFFERENT` cố ý khác · `OUT_OF_SCOPE` không làm ở bản 1.
- **Pha**: `MVP` bản đầu · `Sau` làm sau · `-` không làm.

## Bảng khả năng

| # | Nhóm | Khả năng | Nguồn | Cách làm ở B | Endpoint | Class | Pha |
|---|---|---|---|---|---|---|---|
| F01 | Tài liệu | Tạo, đọc, đổi tên, xóa notebook | Cả hai | Địa chỉ bằng id, không bằng path. Xóa mềm: path được giải phóng, row giữ lại | `GET/POST /notebooks`, `GET/PATCH/DELETE /notebooks/{id}` | `MAPPED` | MVP |
| F02 | Tài liệu | Thư mục, move, clone | Zeppelin | Cây thư mục theo id; đổi tên thư mục ghi lại path con trong một transaction | `/notebook-folders`, `POST /notebooks/{id}:clone`, `:move` | `MAPPED` | MVP |
| F03 | Tài liệu | **Import/export `.ipynb` (định dạng duy nhất)** | Jupyter | nbformat 4.5, không có format native. Export mặc định **không kèm output** (mục Ánh xạ) | `POST /notebooks:import`, `GET /notebooks/{id}:export?includeOutputs=` | `SUPPORTED_DIRECTLY` | MVP |
| F04 | Tài liệu | Tìm kiếm notebook | Cả hai | Lọc theo quyền của người gọi | `GET /notebooks:search` | `MAPPED` | Sau |
| F05 | Cell | Tạo, sửa, xóa cell; kiểu `code`/`markdown`/`raw`; `cell.id` ổn định | Jupyter | `cell.id` của nbformat chính là id API (uuid, 1-64 ký tự `[a-zA-Z0-9-_]`) | `POST /notebooks/{id}/cells`, `PATCH/DELETE .../cells/{cid}` | `SUPPORTED_DIRECTLY` | MVP |
| F06 | Cell | Sắp xếp cell | Cả hai | `PUT` **toàn bộ thứ tự** (idempotent, không có trạng thái trung gian khi nhiều người sửa) thay cho move theo chỉ số của Zeppelin | `PUT /notebooks/{id}/cells:reorder` | `INTENTIONALLY_DIFFERENT` | MVP |
| F07 | Cell | Nhiều ngôn ngữ trong một notebook | Zeppelin | `metadata.kyuubi.language` ∈ `sql`, `python`. SQL và PYTHON chạy trong **cùng một Kyuubi session**, ngôn ngữ chọn theo từng statement qua `confOverlay` `kyuubi.operation.language` | (thuộc tính của cell) | `MAPPED` | MVP |
| F08 | Chạy | Chạy một cell, execution là resource có id bền vững | Cả hai | Gửi statement không đồng bộ lên Kyuubi, lưu `executionId`; refresh, restart server, mở tab khác đều nối lại được | `POST /notebook-sessions/{sid}/executions`, `GET /executions/{eid}` | `MAPPED` | MVP |
| F09 | Chạy | Chạy đồng bộ | Cả hai | **Không làm**: buộc vòng đời query vào một kết nối HTTP và không phục hồi được khi ngắt | - | `INTENTIONALLY_DIFFERENT` | - |
| F10 | Chạy | Theo dõi tiến độ, log, output | Cả hai | Long-poll thay cho WebSocket; dãy `sequence` phát lại được sau khi mất kết nối | `GET /executions/{eid}/events?afterSequence=&waitMillis=`, `/logs`, `/outputs` | `INTENTIONALLY_DIFFERENT` | MVP |
| F11 | Chạy | Kết quả nằm ở server, ngắt kết nối không mất | Zeppelin | Output lưu theo execution; lấy lại bằng `afterSequence` | như F10 | `SUPPORTED_DIRECTLY` | MVP |
| F12 | Chạy | Hủy execution | Cả hai | Gọi Kyuubi `PUT /api/v1/operations/{h}` với `{"action":"cancel"}` | `POST /executions/{eid}:cancel` | `MAPPED` | MVP |
| F13 | Chạy | `execution_count` (`In[n]`) | Jupyter | B tự gán bộ đếm theo notebook session; engine không cung cấp | (trường của execution/cell) | `MAPPED` | MVP |
| F14 | Chạy | Run-all, stop-all | Zeppelin | Chạy tuần tự, tham số `stopOnError`; stop-all hủy mọi execution đang chạy | `POST /notebooks/{id}:run-all`, `:stop-all` | `MAPPED` | Sau |
| F15 | Runtime | Session và runtime theo notebook, cô lập theo user | Cả hai | Một Kyuubi session cho mỗi notebook session. Kyuubi quyết định tạo hay tái dùng Spark engine (theo `kyuubi.engine.share.level` phía Kyuubi); B chỉ truyền `configs` | `POST /notebooks/{id}/sessions`, `DELETE /notebook-sessions/{sid}`, `GET /notebook-sessions/{sid}/runtimes` | `MAPPED` | MVP |
| F16 | Runtime | Trạng thái `idle`/`busy`/`starting`, interrupt, restart | Jupyter | Suy ra từ operation state. Interrupt = cancel operation. Restart = đóng session rồi mở lại (**biến Python mất**, UI phải nói rõ) | `POST /notebook-runtimes/{id}:interrupt`, `:restart` | `MAPPED` | MVP |
| F17 | Runtime | Danh mục runtime (kernel specs) | Jupyter | Không lộ command hay argv | `GET /runtime-specs` | `MAPPED` | Sau |
| F18 | Output | Mô hình output chuẩn nbformat + MIME bundle | Jupyter | Dựng `stream`, `display_data`, `execute_result`, `error` từ mime bundle của Kyuubi. Cần `kyuubi.engine.spark.output.mode=NOTEBOOK` **ở session conf** của `POST /sessions` | `GET /executions/{eid}/outputs` | `MAPPED` | MVP |
| F19 | Output | Lọc output không tin cậy | Jupyter | Notebook import là input không tin cậy: giữ whitelist, loại HTML/SVG/JS (mục Ánh xạ). Output do B sinh ra khi hiển thị vẫn qua bộ lọc HTML | (import và render) | `SUPPORTED_DIRECTLY` | MVP |
| F20 | Output | Bảng/biểu đồ kết quả SQL | Zeppelin | Phân trang từ Kyuubi rowset; biểu đồ do UI dựng | `GET /executions/{eid}/results?offset=&limit=` | `MAPPED` | Sau |
| F21 | Output | Output hiển thị theo thời gian thực | Kyuubi | Không làm được: worker Python trả **một hàng, một cột** khi chạy xong. `stream` chỉ là kết quả cuối; log theo dòng đi qua operation log | - | `INTENTIONALLY_DIFFERENT` | - |
| F22 | Output | Tab-completion, inspect, history | Kyuubi | Không có giao thức kernel | - | `OUT_OF_SCOPE` | - |
| F23 | Quyền | Phân quyền theo notebook, có vai trò chạy-không-sửa | Zeppelin | `OWNER`, `EDITOR`, `RUNNER`, `VIEWER` | `GET/PUT /notebooks/{id}/permissions` | `MAPPED` | MVP |
| F24 | Quyền | Danh tính chạy query | Kyuubi | Là **người gọi** (bearer JWT chuyển tiếp), không phải owner notebook; Ranger chấm theo user thật | (header `Authorization`) | `INTENTIONALLY_DIFFERENT` | MVP |
| F25 | Tự động | Lịch chạy cron | Zeppelin | Timezone bắt buộc, `overlapPolicy` tường minh. **Chưa chốt danh tính chạy** (câu hỏi mở 1) | `PUT /notebooks/{id}/schedule` | `MAPPED` | Sau |
| F26 | Tự động | Tham số, form động `${name=default}` | Zeppelin | `metadata.kyuubi.parameters`, mỗi tham số **có kiểu** (`string`, `number`, `date`, `enum`) và escape theo kiểu. Không nối chuỗi thô vào SQL | (trong `metadata.kyuubi`) | `MAPPED` | Sau |
| F27 | Lịch sử | Revision tự động và khôi phục | Zeppelin | Tự sinh khi sửa. `:restore` **nối thêm** chứ không tua ngược. Revision đã bảo vệ không xóa được | `/notebooks/{id}/revisions`, `.../revisions/{n}:restore` | `MAPPED` | Sau |
| F28 | Meta | Danh tính hiện tại, trạng thái dịch vụ | Cả hai | Lấy từ token, không từ body | `GET /me`, `GET /notebook-status` | `MAPPED` | MVP |
| F29 | Không làm | Kernel connection file, terminal, extension, cộng tác thời gian thực, interpreter management, credential API, Helium, publish/report | Cả hai | Ngoài phạm vi bản 1 (terminal làm hỏng chính sách cài package; connection file là đường không xác thực tới kernel) | - | `OUT_OF_SCOPE` | - |

## Ánh xạ `.ipynb` ↔ B

- **Phiên bản**: import nhận nbformat 4.0 đến 4.5, thiếu `cell.id` thì B sinh. Export luôn ghi 4.5.
- **`metadata.kernelspec`, `language_info`**: import bỏ qua, không dùng để chọn runtime. Export ghi
  `python3` để Jupyter mở được.
- **`cells[].source`**: chuỗi hoặc danh sách dòng khi import; lưu thành chuỗi; export thành danh sách dòng.
- **Ngôn ngữ cell**: lưu ở `cells[].metadata.kyuubi.language`. Import thấy dòng đầu `%%sql`, `%sql`,
  `%%pyspark` thì tách ra và gán metadata.
- **`outputs`, `execution_count`**: export mặc định **không kèm** (`includeOutputs=false`, chỉ
  `OWNER` được bật). Import chỉ giữ whitelist `text/plain`, `text/markdown`, `image/png`,
  `image/jpeg`, `application/json`; **loại** `text/html`, `image/svg+xml`, `application/javascript`.
- **markdown `attachments`**: bỏ.
- **Kích thước import**: giới hạn cấu hình được phía B (gợi ý 16 MiB).
- **Vì sao export không kèm output**: Ranger chỉ kiểm soát lúc truy vấn. File `.ipynb` chứa kết quả là
  đường đưa dữ liệu ra ngoài chính sách.

## Giao tiếp B → Kyuubi → Spark engine

B chỉ nói chuyện với Kyuubi REST (`/api/v1`). Mọi lệnh gọi mang `Authorization: Bearer <token của
người dùng>` (F24). Kyuubi lo phần còn lại: chọn hoặc khởi tạo Spark engine, chuyển statement, trả
kết quả.

1. **Mở session** (F15): `POST /api/v1/sessions`, body `{"configs": {...}}`. Bắt buộc có
   `kyuubi.engine.spark.output.mode=NOTEBOOK`, đặt ở đây chứ **không** ở `confOverlay` của statement,
   sai chỗ thì rich output mất im lặng. Thêm các `spark.*` của notebook nếu cần. Response trả
   `identifier` (session) và `kyuubiInstance` (pod Kyuubi giữ session). Lần đầu có thể chậm vì Kyuubi
   đang khởi tạo Spark engine.
2. **Chạy cell** (F08): `POST /api/v1/sessions/{sid}/operations/statement`, body
   `{"statement": "...", "runAsync": true, "queryTimeout": 0, "confOverlay":
   {"kyuubi.operation.language": "PYTHON"}}`. Cell SQL bỏ `confOverlay` (mặc định SQL). Response trả
   `identifier` của operation, B lưu vào execution.
3. **Theo dõi** (F10): `GET /api/v1/operations/{op}/event` lấy trạng thái;
   `GET /api/v1/operations/{op}/log?maxrows=&fetchorientation=` lấy log.
4. **Lấy kết quả** (F18, F20): `GET /api/v1/operations/{op}/rowset?maxrows=&fetchorientation=`.
   Cell Python trả một hàng, một cột là mime bundle JSON. Cell SQL thêm
   `GET /api/v1/operations/{op}/resultsetmetadata` cho schema.
5. **Hủy / dọn** (F12): `PUT /api/v1/operations/{op}` với `{"action":"cancel"}` hoặc
   `{"action":"close"}`.
6. **Đóng session** (F15, restart ở F16): `DELETE /api/v1/sessions/{sid}`.

Session sống trong một pod Kyuubi: mọi lệnh 2-6 phải tới đúng pod `kyuubiInstance` trả về ở bước 1.
