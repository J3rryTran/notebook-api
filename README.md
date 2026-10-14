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

### Cơ sở dữ liệu (MariaDB)
- **Hệ quản trị CSDL**: Chỉ sử dụng **MariaDB** (bỏ hoàn toàn PostgreSQL và H2).
- **Database đích**: Database `test` (schema đồng nhất với `self_service`), Flyway tự động migrate `V1` baseline và `V2`–`V5` (`utf8mb4_bin`).
- **Yêu cầu server MariaDB**: `max_allowed_packet >= 64M` để lưu trữ an toàn các tài liệu JSON notebook lớn.

### 3 Chế độ Xác thực (`notebook.auth.mode`)
1. **`oidc`** (Mặc định trong môi trường production / lakehouse):
   - Đăng nhập qua Keycloak Authorization Code Flow kèm PKCE (`oidc-client-ts` tự host tại `frontend/public/vendor/`).
   - Token người dùng được chuyển tiếp trực tiếp tới Kyuubi REST API (`/api/v1/sessions`).
   - Không lưu cookie phiên, không lưu credential trên backend.
   - Khi có proxy user (`ranger_username ≠ username`), backend tự động lấy client_credentials token của service account `notebook-api` để mở session và truyền `hive.server2.proxy.user`.
2. **`no-oidc`**:
   - Dành cho môi trường nội bộ hoặc dev không có OIDC/LDAP.
   - Đăng nhập Form User/Password, xác thực thông qua Kyuubi `/api/v1/ping` (HTTP Basic).
   - Lưu phiên mã hóa AES-256-GCM trong bảng `notebook_auth_sessions`.
   - Bảo vệ CSRF qua Double Submit Cookie (`XSRF-TOKEN` / `X-XSRF-TOKEN`).
   - Định kỳ recheck kiểm tra tính hợp lệ của session với Kyuubi.
3. **`ldap`**:
   - Backend bind trực tiếp LDAP server (`ldap://` hoặc `ldaps://`), kiểm tra mật khẩu và tìm tên chuẩn (`usernameAttribute`).
   - Kiểm tra group admin qua `adminGroup`.
   - Lưu phiên trong `notebook_auth_sessions` và chuyển tiếp Basic tới Kyuubi.

### Kết nối Kyuubi & Định dạng Output
- **Giao thức**: Kết nối Kyuubi qua cổng **REST API (10099)**.
- **Output mode**: Giữ nguyên mặc định `AUTO` của Kyuubi (`kyuubi.engine.spark.output.mode=AUTO`).
  - **SQL**: Backend đọc `resultsetmetadata` và `rowset`, chuyển đổi thành execute_result dạng bảng JSON chuẩn (`application/vnd.notebook.table+json`) và bảng chữ thuần ASCII (tối đa 50 dòng) để export `.ipynb`.
  - **Python**: Đọc stream stdout; trích xuất `ename`, `evalue`, và `traceback` (tối đa 200 dòng) khi có exception (ví dụ `NameError`, `AnalysisException`).
  - **Giới hạn hiện tại**: Chưa hỗ trợ hiển thị hình ảnh đồ thị sinh động (HTML/SVG/PNG) trực tiếp từ Spark engine qua Kyuubi REST (chỉ hỗ trợ bảng và text).

### Phân quyền Ranger & Proxy User (`ranger_username`)
- **Tích hợp Ranger**: Kyuubi chạy với `share.level=USER`, `doAs=true`. Spark engine áp dụng chính xác quyền phân bổ theo username người dùng trong Ranger.
- **Proxy User**:
  - Khi `app_users` định nghĩa `ranger_username` khác với `username` đăng nhập (ví dụ tài khoản dịch vụ hoặc alias phân quyền), `execution-service` gọi `notebook-service` (`GET /internal/users/{username}/ranger-username`) để tra cứu (kết quả được cache Caffeine 5 phút).
  - Ở mode OIDC: Execution-service dùng `NOTEBOOK_OIDC_CLIENT_SECRET` lấy token của `notebook-api` từ Keycloak, mở session Kyuubi với cấu hình `hive.server2.proxy.user: <ranger_username>` và đánh dấu session `proxied=true`. Mọi thao tác submit/sync sau đó đều dùng token dịch vụ để bảo đảm quyền ủy quyền.
  - Ở mode no-oidc/ldap: Nếu có sự khác biệt, hệ thống ghi log `WARN` và chạy trực tiếp bằng user đăng nhập.

### Cấu hình Keycloak Client
Để vận hành OIDC và Proxy User trên Keycloak realm `lakehouse`:
1. **Client Frontend / User (`kyuubi-notebook`)**:
   - Client Type: **Public** (Access Type: `public`).
   - Standard Flow Enabled: `true` (Authorization Code Flow với PKCE S256).
   - Valid Redirect URIs: `http://<domain-or-ip>:32380/auth/callback`.
   - Web Origins: `+` hoặc `http://<domain-or-ip>:32380`.
2. **Client Backend / Service Account (`notebook-api`)**:
   - Client Type: **Confidential** (Client authentication: `ON`).
   - Service Accounts Enabled: `true` (Direct Access Grants / Service Account Roles).
   - Cung cấp Secret qua biến môi trường `NOTEBOOK_OIDC_CLIENT_SECRET`.

| Tài liệu | Nội dung |
|---|---|
| [`plan/mariadb-auth-plan.md`](plan/mariadb-auth-plan.md) | Chi tiết kiến trúc MariaDB, 3 chế độ Auth và Kyuubi REST |
| [`plan/huong-dan-sua-vong-3.md`](plan/huong-dan-sua-vong-3.md) | Kế hoạch sửa lỗi vòng 3 và hướng dẫn nghiệm thu |
| [`plan/API-detail.md`](plan/API-detail.md) | Bảng khả năng F01–F47, ánh xạ `.ipynb`, cách gọi Kyuubi → Spark engine |
| [`plan/Chart.md`](plan/Chart.md) | Plan Helm chart triển khai lên Kubernetes |
| [`plan/UI-plan.md`](plan/UI-plan.md) | Plan giao diện theo bố cục kiểu Databricks |
| [`BUILD.md`](BUILD.md) | Hướng dẫn build, kiểm thử và đóng gói dist/ |

