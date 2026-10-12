# Notebook (thử nghiệm)

> **Trạng thái: thử nghiệm.** Chưa dùng cho công việc chính thức. Tính năng, giao diện và API có thể
> đổi, dữ liệu notebook có thể phải xóa giữa các lần cập nhật. Hãy export `.ipynb` những gì muốn giữ.

## Đây là gì

Một ứng dụng notebook chạy trên trình duyệt để viết và chạy **SQL** và **PySpark** trên lakehouse.
Ứng dụng đứng riêng, không phải giao diện của Kyuubi. Khi chạy cell, nó gửi lệnh tới **Kyuubi**, và
Kyuubi chuyển tới **Spark engine** của chính bạn.

```
Bạn (trình duyệt) ──▶ Notebook ──▶ Kyuubi ──▶ Spark engine của bạn ──▶ Iceberg / HDFS / SeaweedFS
                         │
                         └── Keycloak (đăng nhập lakehouse)
```

## Bắt đầu

1. Mở địa chỉ notebook do nhóm vận hành cung cấp (môi trường thử nghiệm: `http://10.29.19.56:32380`).
2. Đăng nhập bằng tài khoản **Keycloak realm `lakehouse`**, cùng tài khoản đang dùng cho Kyuubi.
3. Tạo notebook mới hoặc **import file `.ipynb`**.
4. Chạy cell đầu tiên. **Lần đầu có thể mất vài phút** vì Spark engine của bạn đang được khởi tạo.

## Giao diện

Bố cục quen thuộc nếu bạn đã dùng notebook Databricks: thanh trái (Workspace, Catalog, Gần đây, Tìm
kiếm), thanh trên (tên notebook, chọn compute và trạng thái, **Run all**, **Chia sẻ**), các cell ở
giữa, panel phải thu gọn được. Phím tắt chính:

- `Shift+Enter` chạy cell và chuyển xuống, `Ctrl/Cmd+Enter` chạy tại chỗ, `Alt+Enter` chạy rồi chèn cell mới.
- `Esc` sang chế độ lệnh, rồi `A`/`B` chèn cell trên/dưới, `D D` xóa cell, `M`/`Y` đổi Markdown/Code.
- Gõ `%sql`, `%python`, `%md` ở dòng đầu cell để đổi ngôn ngữ.

## Tính năng có trong bản thử nghiệm

- Notebook, thư mục, đổi tên, di chuyển, nhân bản, xóa.
- Cell `code`, `markdown`, `raw`; sắp xếp lại cell.
- **SQL và Python trong cùng một notebook**, dùng chung một Spark session: bảng tạm tạo bằng SQL dùng
  được trong Python và ngược lại. Chọn ngôn ngữ trên từng cell, hoặc viết dòng đầu `%%sql`.
- Output chuẩn Jupyter: text, bảng, hình (ví dụ bảng pandas, biểu đồ matplotlib).
- **Đóng trình duyệt không mất kết quả**: cell vẫn chạy trên server, mở lại là thấy.
- Dừng cell đang chạy, khởi động lại runtime.
- Chia sẻ notebook với 4 vai trò: `OWNER`, `EDITOR`, `RUNNER` (chạy được, không sửa được), `VIEWER`.
- Import và export **`.ipynb`** (định dạng duy nhất, mở được bằng Jupyter).

Danh sách đầy đủ và kế hoạch: [`../notebook-api-parity.md`](../notebook-api-parity.md).

## Cần biết trước khi dùng

| Điều | Giải thích |
|---|---|
| Truy vấn chạy **bằng danh tính của bạn** | Ranger áp đúng quyền của bạn. Notebook người khác chia sẻ cũng chạy bằng quyền của bạn, nên có thể ra kết quả khác hoặc bị từ chối |
| Export mặc định **không kèm kết quả** | Để dữ liệu không rời khỏi phạm vi phân quyền. Chủ notebook có thể bật kèm kết quả khi export |
| Import **loại bỏ** output HTML, SVG, JavaScript | File `.ipynb` từ ngoài được coi là không tin cậy |
| Output Python hiện **một lần khi cell chạy xong** | Không hiện dần trong lúc chạy. Theo dõi tiến độ ở tab log của cell |
| **Restart runtime xóa toàn bộ biến Python** | Chạy lại các cell cần thiết sau khi restart |
| Runtime rảnh lâu sẽ tự đóng | Để giải phóng tài nguyên Spark. Chạy lại cell là tự mở lại |
| Chưa có tự hoàn thành code | Chưa hỗ trợ ở bản này |
| Chưa có lịch chạy tự động | Đang chờ chốt cách xác thực khi chạy không có người dùng |

## Cài thêm thư viện Python

Thư viện có sẵn đến từ image Spark do nhóm vận hành quản lý. Cần thêm thư viện thì liên hệ nhóm vận
hành, chưa hỗ trợ tự cài trong notebook ở bản thử nghiệm.

## Báo lỗi và góp ý

Khi báo lỗi, gửi kèm: tên notebook, thời điểm chạy, nội dung cell, và **mã lỗi / request id** hiển
thị trên giao diện. Kênh tiếp nhận: `<điền kênh của nhóm>`.

## Cho người phát triển và vận hành

| Tài liệu | Nội dung |
|---|---|
| [`../notebook-api-parity.md`](../notebook-api-parity.md) | Bảng khả năng F01–F47, ánh xạ `.ipynb`, cách gọi Kyuubi → Spark engine |
| [`helm-chart-plan.md`](helm-chart-plan.md) | Plan Helm chart triển khai lên Kubernetes |
| [`ui-plan.md`](ui-plan.md) | Plan giao diện theo bố cục kiểu Databricks |
