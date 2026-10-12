# Plan: giao diện notebook theo bố cục kiểu Databricks

Trạng thái: chưa thực hiện. Mã `F..` tham chiếu bảng khả năng trong
[`../notebook-api-parity.md`](../notebook-api-parity.md); mọi thao tác UI phải đi qua đúng API ở đó.

## 0. Nguyên tắc

1. **Học bố cục và cách thao tác** của notebook Databricks (thanh trái, chọn compute, cell có thanh
   công cụ, kết quả dạng bảng/biểu đồ, phím tắt). **Không** dùng tên, logo, màu hay icon thương hiệu
   Databricks; dùng bộ nhận diện VTNexus đang có.
2. **Trung thực với giới hạn của Kyuubi.** Thứ gì Kyuubi không cung cấp (output chạy dần, completion,
   tiến độ Spark job qua REST) thì UI không giả lập; hiển thị rõ trạng thái thật.
3. **Không tin output.** Mọi HTML trong output render trong `iframe sandbox` (không có
   `allow-same-origin`), không bao giờ chèn thẳng vào DOM của ứng dụng (F19).

## 1. Bố cục tổng (F30)

```
┌───────┬──────────────────────────────────────────────────────────────────────────┬──────────────┐
│ Rail  │ [Tên notebook ✎]  [Ngôn ngữ mặc định ▾]          [● Compute ▾] [▶ Run all] │ Panel phải   │
│       │ File  Edit  View  Run  Help                              [Lịch] [Chia sẻ]  │ (thu gọn được)│
│ 📁 WS │ ─────────────────────────────────────────────────────────────────────────  │              │
│ 🗂 Cat │  ┌ Cell 1 ─────────────────────────────── [SQL ▾] [⋯] ┐                  │ ▸ Mục lục    │
│ 🕘 Rec │  │ ▶  SELECT * FROM iceberg.sales.orders LIMIT 100     │                  │ ▸ Lịch sử    │
│ 🔍 Tìm │  ├─────────────────────────────────────────────────────┤                  │ ▸ Biến Python │
│       │  │ [Bảng] [+ Biểu đồ]        100 dòng · 1,23 s        │                  │ ▸ Thông tin   │
│       │  │  (bảng kết quả)                                     │                  │   execution  │
│       │  │ Đã chạy 1,23 s · bởi trungtm · 14:05 · pyspark-3.5  │                  │              │
│       │  └─────────────────────────────────────────────────────┘                  │              │
│       │              [+ Code]   [+ Text]                                           │              │
│       │  ┌ Cell 2 (Markdown, đã render — nhấp đúp để sửa) ┐                       │              │
└───────┴──────────────────────────────────────────────────────────────────────────┴──────────────┘
```

| Vùng | Nội dung | Nguồn dữ liệu |
|---|---|---|
| Rail trái | Workspace (cây thư mục), Catalog, Gần đây, Tìm kiếm | F01, F02, F04, F37 |
| Thanh trên | Tên (sửa tại chỗ), ngôn ngữ mặc định, chọn compute + trạng thái, Run all, Lịch, Chia sẻ, menu | F01, F07, F33, F14, F25, F42 |
| Vùng cell | Danh sách cell, nút thêm Code/Text giữa các cell | F05–F08 |
| Panel phải | Mục lục, Lịch sử phiên bản, Biến Python, Thông tin execution | F38, F39, F40, F35 |

## 2. Cell (F31)

- **Hover** hiện thanh công cụ: nút ▶ chạy (trái), badge ngôn ngữ `SQL`/`Python`/`Markdown` (phải,
  đổi được), menu `⋯`: thêm tiêu đề cell, di chuyển lên/xuống, nhân bản, thu gọn code, ẩn kết quả,
  xóa kết quả, xóa cell.
- **Kéo thả** bằng tay nắm bên trái; thả xong gọi một lần `PUT .../cells:reorder` (F06).
- **Đổi ngôn ngữ kiểu Databricks**: gõ `%sql`, `%python`, `%md` ở dòng đầu thì UI đổi badge và **bỏ
  dòng magic khỏi source** (ngôn ngữ lưu ở `metadata.kyuubi.language`, để file `.ipynb` vẫn sạch).
- **Markdown**: hiển thị đã render; nhấp đúp hoặc `Enter` để sửa; `Esc` để render lại.
- **Trạng thái cell**: chờ (xếp hàng), đang chạy (đồng hồ đếm giây), xong, lỗi, đã hủy, mất kết nối
  runtime. Đang chạy thì nút ▶ thành ■ để hủy (F12).
- **Footer thực thi** (F35): `Đã chạy {thời gian} · bởi {user} · {giờ} · {runtime}` lấy từ execution.
- **Editor**: Monaco, tô màu SQL/Python, gập code, tìm/thay trong cell. **Không** có tự hoàn thành
  (F22).

## 3. Kết quả

| Loại | Cách hiển thị | Mã |
|---|---|---|
| Bảng SQL | Phân trang từ `/results`; sắp xếp/lọc **trên các dòng đã tải** (ghi rõ trên UI); dòng tóm tắt `N dòng · thời gian`; báo "đã cắt bớt" khi vượt giới hạn | F20 |
| Biểu đồ | Tab `+ Biểu đồ` cạnh tab Bảng: cột, đường, tròn, phân tán; cấu hình trục từ cột kết quả; lưu cấu hình trong `metadata.kyuubi` của cell | F34 |
| Output Python | `text/plain` dạng console; ảnh `image/png`; HTML (ví dụ bảng pandas) trong `iframe sandbox`; JSON dạng cây | F18, F19 |
| Lỗi | Dòng đầu nổi bật `ZeroDivisionError: division by zero`, traceback thu gọn bên dưới, có nút sao chép kèm request id | F36 |
| Log | Tab `Log` tải từ `/logs`, cập nhật theo long-poll khi cell đang chạy | F10 |
| Tải CSV | Chỉ hiện khi quản trị bật `ui.allowResultDownload` (mặc định tắt, cùng lý do với export không kèm output) | F45 |

## 4. Chọn compute (F33)

| Trạng thái UI | Ý nghĩa | Từ đâu |
|---|---|---|
| ○ Chưa kết nối | Chưa có session | không có session |
| ◌ Đang khởi tạo… | `POST /sessions` đang chờ; ghi chú **"Lần đầu có thể mất vài phút: đang khởi tạo Spark engine"** | F15 |
| ● Sẵn sàng / ● Đang chạy | Runtime `idle` / `busy` | F16 |
| ⚠ Mất kết nối | Session không còn trên Kyuubi; nút "Kết nối lại" | F16 |

- Dropdown: chọn runtime (`GET /runtime-specs`, F17), **Kết nối / Ngắt**, **Khởi động lại** (hộp
  thoại cảnh báo: "Mọi biến Python sẽ mất"), **Cấu hình Spark** chỉ cho sửa các key trong
  `allowedSessionConfigKeys` (helm values).
- Banner khi runtime sắp bị đóng vì rảnh (`idleSessionTimeout`).

## 5. Panel và hộp thoại

| Thành phần | Hành vi | Mã |
|---|---|---|
| Catalog (rail trái) | Cây catalog → schema → bảng → cột; dùng metadata ops của Kyuubi `POST /api/v1/sessions/{sid}/operations/catalogs`, `schemas`, `tables`, `columns`; nhấp để chèn tên vào cell. Cần có session | F37 |
| Mục lục | Dựng từ heading trong cell Markdown, chỉ phía trình duyệt | F38 |
| Lịch sử phiên bản | Danh sách revision, xem khác biệt, khôi phục (nối thêm) | F39 |
| Biến Python | Tên, kiểu, kích thước biến trong runtime. Cần chạy một statement Python ẩn trên cùng session; **spike trước** để chắc không gây tác dụng phụ | F40 |
| Thanh tham số | Hàng ô nhập trên đầu notebook cho tham số `${...}`; đổi giá trị thì đánh dấu các cell dùng nó | F41 |
| Chia sẻ | Ô tìm người dùng + vai trò `Có thể quản lý` (OWNER), `Có thể sửa` (EDITOR), `Có thể chạy` (RUNNER), `Có thể xem` (VIEWER) | F42 |
| Lịch chạy | Nút hiện nhưng **khóa** cho tới khi chốt danh tính chạy cron | F25 |
| Spark UI | Liên kết tới Spark UI của engine qua proxy `/engine-ui/{host}:{port}/` của Kyuubi; người dùng phải đăng nhập được Kyuubi Web; **spike** cách lấy `host:port` của engine | F43 |
| Tiến độ Spark job | Event REST của Kyuubi **không có** số job/stage. Bản đầu chỉ hiện đồng hồ chạy và log; thanh tiến độ chờ spike | F44 |

## 6. Phím tắt (F32)

Hai chế độ như Databricks/Jupyter: **sửa** (con trỏ trong editor) và **lệnh** (`Esc`).

| Phím | Chế độ | Việc |
|---|---|---|
| `Shift+Enter` | cả hai | Chạy cell, chuyển xuống cell dưới |
| `Ctrl/Cmd+Enter` | cả hai | Chạy cell, giữ nguyên vị trí |
| `Alt+Enter` | cả hai | Chạy cell, chèn cell mới bên dưới |
| `Esc` / `Enter` | — | Sang chế độ lệnh / sửa |
| `A` / `B` | lệnh | Chèn cell trên / dưới |
| `D` `D` | lệnh | Xóa cell (có hoàn tác) |
| `M` / `Y` | lệnh | Đổi cell sang Markdown / Code |
| `↑` `↓` | lệnh | Chọn cell trên / dưới |
| `Ctrl/Cmd+Shift+Enter` | cả hai | Run all (F14) |
| `Ctrl/Cmd+S` | cả hai | Lưu ngay (bình thường tự lưu) |
| `Ctrl/Cmd+F` | cả hai | Tìm trong notebook |

## 7. Không làm

AI assistant, bình luận trên cell, dashboard, ngôn ngữ Scala/R, Git folders, hiển thị người khác đang
cùng sửa (F47, F29).

## 8. Công nghệ

- **Vue 3 + Vite + Element Plus + Monaco + Pinia**: cùng stack với Web UI Kyuubi hiện tại, đội đã quen.
- Giao diện cũ trong `kyuubi-custom` (`web-ui/src/views/notebook`) chỉ dùng làm **tham khảo** thành
  phần (cell, render kết quả); nó gắn với API cũ nên không bê nguyên.
- Một client long-poll dùng chung cho `/events` (F10), tự nối lại theo `afterSequence`.
- Chủ đề sáng/tối theo hệ thống, có nút đổi (F46).
- Tự lưu có debounce; xung đột sửa đồng thời báo rõ ("Notebook vừa được người khác sửa, tải lại?")
  dựa trên `version` của notebook.

## 9. Phân pha

| Pha | Mã |
|---|---|
| MVP | F30 F31 F32 F33 F35 F36 F42, cùng hiển thị F18 F19 F20 F10 |
| Sau | F34 F37 F38 F39 F40 F41 F43 F44 F45 F46 |
| Chờ quyết định | F25 (lịch chạy) |

## 10. Nghiệm thu MVP

1. Bố cục 3 vùng hiển thị đúng ở màn hình 1366 px và 1920 px; panel phải thu gọn được.
2. Tạo notebook, thêm cell SQL và Python bằng nút `+ Code` và phím `B`; đổi ngôn ngữ bằng `%sql`.
3. Chạy bằng `Shift+Enter`: trạng thái compute đi `Chưa kết nối → Đang khởi tạo → Sẵn sàng → Đang chạy`.
4. Bảng SQL phân trang được; bảng pandas hiện trong iframe sandbox; `1/0` hiện dòng lỗi + traceback.
5. Hủy cell đang chạy bằng nút ■; khởi động lại runtime có hộp thoại cảnh báo mất biến.
6. Chia sẻ cho người khác với vai trò "Có thể chạy": người đó chạy được, không sửa được.
7. Đóng tab khi cell đang chạy, mở lại thấy kết quả.
