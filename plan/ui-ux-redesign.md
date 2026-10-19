# Plan: làm lại UI/UX notebook và nối với backend

Ngày: 2026-10-19. Trạng thái: chờ duyệt.

## 0. Hiện trạng

- `frontend/public/index.html` chỉ gọi 4 API: `auth/config`, `me`, `auth/login`, `auth/logout`.
- Thanh bên trái (`revenue-analysis.ipynb`, `user-churn-model.ipynb`, `production-jobs`), 2 cell mẫu, bảng kết quả,
  nhãn `Spark Engine Ready` đều viết cứng trong HTML.
- `runCell()` chỉ `setTimeout` 0,7 s rồi in "Query executed successfully". Không gọi backend.
- Vì vậy log pod không có request `/api/v1/notebooks/*/sessions` hay `/executions`, và Kyuubi không tạo session
  Spark nào. Đây là nguyên nhân chính.
- Riêng log `KyuubiAuthProbe ... could not connect ... : null`: `null` là message của exception
  (Java HttpClient thường để `ConnectException` không có message), nên chưa biết là lỗi DNS, bị từ chối hay
  timeout. Cần làm rõ ở bước 1.

## 1. Kết nối Kyuubi (làm trước UI)

| # | Việc | Ở đâu |
|---|---|---|
| 1.1 | Log thêm tên lớp exception và nguyên nhân gốc trong `KyuubiAuthProbe` | `nb-common/.../auth/KyuubiAuthProbe.java` |
| 1.2 | Người dùng kiểm tra trên Dashboard: namespace `kyuubi-operator-experiment` → Services có đúng tên `kyuubi-operator-experiment-rest`, cổng `10099`; Kyuubi bật REST frontend (`kyuubi.frontend.protocols` có `REST`) | Cluster |
| 1.3 | Sửa `kyuubi.restUrl` trong `values.yaml` theo kết quả 1.2 nếu lệch | Chart |

Nghiệm thu: log khởi động không còn dòng `could not connect`.

## 2. Bố cục mới

Bỏ thanh bên trái. Một trang, một notebook mở tại một thời điểm.

```
┌──────────────────────────────────────────────────────────────────────────┐
│ [Notebook ▾ tên-notebook]  [+ Mới] [Nhập] [Xuất]   ● Kyuubi  👤 kyuubi ⏻ │  thanh trên, cố định
├──────────────────────────────────────────────────────────────────────────┤
│  [▶ Chạy tất cả] [■ Dừng] [↻ Khởi động lại session]      Đã lưu 18:32    │  thanh công cụ
├──────────────────────────────────────────────────────────────────────────┤
│  ┌ [SQL ▾]  [1]                                   ▶  ↑  ↓  🗑 ┐           │
│  │ SELECT ...                                                │           │
│  ├──────────────────────────────────────────────────────────┤           │
│  │ bảng kết quả / lỗi / trạng thái · 0,42 s · 10 dòng        │           │
│  └──────────────────────────────────────────────────────────┘           │
│                     [+ SQL]  [+ PySpark]                                 │
└──────────────────────────────────────────────────────────────────────────┘
```

- **Chọn notebook**: dropdown ở thanh trên, có ô tìm kiếm, lấy từ `GET /api/v1/notebooks`. Đổi tên trực tiếp
  trên tiêu đề.
- **Notebook trống**: mở ra có sẵn 1 cell SQL rỗng, không có dữ liệu mẫu.
- **Cell**: chọn ngôn ngữ SQL/PySpark trên từng cell; nút chạy, lên, xuống, xoá chỉ hiện khi rê chuột hoặc
  khi cell đang chọn.
- **Kết quả**: SQL hiện bảng HTML (header cố định, cuộn ngang, báo khi bị cắt ở `resultsMaxRows`); PySpark
  hiện text. Lỗi hiện khung đỏ, có nút mở log.
- **Trạng thái cell**: chờ / đang chạy (có đồng hồ) / xong / lỗi / đã huỷ.
- **Phím tắt**: `Shift+Enter` chạy và sang cell sau, `Ctrl+Enter` chạy tại chỗ, `Ctrl+S` lưu.
- **Lưu**: tự lưu sau 1,5 s ngừng gõ; thanh công cụ hiện "Đang lưu… / Đã lưu HH:mm / Lỗi lưu".
- **Trạng thái Kyuubi**: chấm ở thanh trên lấy từ `GET /api/v1/notebook-status`, không viết cứng.

## 3. Nối API

| Thao tác | API |
|---|---|
| Danh sách notebook | `GET /api/v1/notebooks` |
| Tạo / mở / đổi tên / xoá | `POST /notebooks`, `GET /notebooks/{id}`, `PATCH /notebooks/{id}`, `DELETE /notebooks/{id}` |
| Nhập / xuất `.ipynb` | `POST /notebooks:import`, `GET /notebooks/{id}:export` |
| Thêm / sửa / xoá / đổi thứ tự cell | `POST/PATCH/DELETE /notebooks/{id}/cells[/{cellId}]`, `PUT /notebooks/{id}/cells:reorder` |
| Mở session Kyuubi | `POST /notebooks/{id}/sessions` (gọi khi chạy cell đầu tiên) |
| Chạy cell | `POST /notebook-sessions/{sessionId}/executions` với `{cellId}` |
| Theo dõi | `GET /executions/{id}/events?waitMillis=3000` (long-poll) |
| Kết quả / output / log | `GET /executions/{id}/results`, `/outputs`, `/logs` |
| Dừng / khởi động lại | `POST /executions/{id}:cancel`, `POST /notebook-runtimes/{id}:interrupt`, `:restart` |
| Đóng session | `DELETE /notebook-sessions/{sessionId}` |
| Trạng thái | `GET /api/v1/notebook-status` |

Hàm `api()` hiện có (CSRF, cookie, Bearer cho OIDC) giữ nguyên, dùng cho mọi lời gọi.

## 4. Kỹ thuật

- Giữ một trang tĩnh `index.html`, JS thuần, không build step, không tải gì từ CDN (bước B3 của `build.sh`).
- Tách JS ra `frontend/public/app/*.js` theo phần: `api`, `notebook`, `cells`, `execution`, `render`.
- Ô soạn thảo: giữ `textarea` tự giãn chiều cao, font monospace, Tab chèn 4 dấu cách. Tô màu cú pháp để giai
  đoạn sau (cần vendor thêm thư viện, sẽ hỏi trước).
- Xoá toàn bộ dữ liệu mẫu, `runCell` giả và nhãn `Spark Engine Ready` viết cứng.

## 5. Các bước và nghiệm thu

| Bước | Nội dung | Nghiệm thu |
|---|---|---|
| U1 | Mục 1 (kết nối Kyuubi) | Log không còn `could not connect` |
| U2 | Bố cục mới, bỏ sidebar, dropdown notebook, tạo/mở/đổi tên/xoá | Tạo notebook, tải lại trang vẫn còn |
| U3 | Cell: thêm/sửa/xoá/đổi thứ tự, tự lưu | Sửa cell, tải lại trang thấy nội dung mới |
| U4 | Chạy cell qua Kyuubi, long-poll, bảng kết quả, lỗi, huỷ | `SHOW DATABASES` trả bảng thật; Kyuubi có session Spark mang proxy user đúng tên người đăng nhập |
| U5 | Nhập/xuất `.ipynb`, phím tắt, trạng thái Kyuubi | Xuất rồi nhập lại ra cùng nội dung |
| U6 | `build.sh` xanh, build image, deploy | Chạy được trên `10.29.19.56:32380` |

## 6. Ngoài phạm vi

- Tô màu cú pháp, gợi ý code.
- Chia sẻ notebook / phân quyền trên UI (API `permissions` có sẵn, chưa làm giao diện).
- Thư mục, cây notebook.
