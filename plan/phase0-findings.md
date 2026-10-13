# Kết quả kiểm tra trước khi thực hiện (Phase 0 Findings)

Ngày thực hiện: 2026-10-13  
Dựa trên đặc tả và yêu cầu kiểm tra tại `plan/fix-plan.md` (Mục 2: P0.1 – P0.4).

---

## 1. P0.1: Kiểm tra cấu hình xác thực Kyuubi (`kyuubi.authentication`)
- **Kết quả điều tra**:
  - Tại file cấu hình playground `vtnet-kyuubi/.../kyuubi-defaults.conf`: mặc định là `NONE`.
  - Tại cấu hình production / helm chart: Hệ thống sử dụng chế độ `OIDC` kết nối Keycloak realm `lakehouse` (issuer: `https://10.29.19.56:32004/realms/lakehouse`), client `kyuubi-notebook` với audience mapper `kyuubi`. Hỗ trợ chế độ tùy chọn dự phòng `LDAP`.
- **Ảnh hưởng**:
  - Triển khai cơ chế xác thực ủy quyền cho Kyuubi theo **Chế độ OIDC** làm mặc định (Mục 3.1 trong fix plan).
  - Hỗ trợ chế độ `LDAP` (Mục 3.2 trong fix plan) qua cờ cấu hình `notebook.auth.mode=oidc|ldap`.

---

## 2. P0.2: `GET /api/v1/ping` của Kyuubi khi không có credential
- **Kết quả điều tra**:
  - Khi Kyuubi bật `kyuubi.authentication=OIDC` hoặc `LDAP`, endpoint `/api/v1/ping` trả về mã `401 Unauthorized` nếu request thiếu header `Authorization` hoặc token không hợp lệ.
  - Phía ứng dụng Notebook (microservices), `nb-common` ủy quyền toàn bộ việc kiểm tra token qua lệnh gọi `/api/v1/ping` của Kyuubi. Nếu Kyuubi từ chối (401/403) -> Notebook từ chối (401). Nếu Kyuubi không phản hồi -> trả về `503 AUTH_BACKEND_UNAVAILABLE`.
  - Loại bỏ hoàn toàn cơ chế bypass qua `X-User` và user mặc định `alice`.

---

## 3. P0.3: Loại Database của Kyuubi Metadata Store
- **Kết quả điều tra**:
  - Kyuubi mặc định có thể cấu hình SQLite nội bộ trên từng pod, nhưng trong môi trường chạy cụm hoặc production lakehouse, metadata store sử dụng **PostgreSQL** (`kyuubi.metadata.store.jdbc.database.type=POSTGRESQL`).
- **Ảnh hưởng đến chế độ lưu trữ**:
  - Chế độ `notebook.storage.mode=kyuubi-db`: Áp dụng khi Kyuubi dùng PostgreSQL/MySQL chung server. Notebook sử dụng schema riêng (`nb_document`, `nb_execution`, `nb_auth`), tuyệt đối không đụng chạm đến bảng của Kyuubi.
  - Chế độ `notebook.storage.mode=postgres`: Sử dụng PostgreSQL riêng biệt cho Notebook.
  - Chế độ `h2`: Chỉ cho phép chạy với profile `dev`. Nếu chạy profile khác thì báo lỗi và dừng khởi động (fail-fast).
  - Nếu `storage.mode=kyuubi-db` nhưng URL là `jdbc:sqlite:` -> Khởi động thất bại với thông báo rõ ràng để bảo vệ dữ liệu.

---

## 4. P0.4: Database User riêng cho Notebook
- **Kết quả điều tra**:
  - Cần khởi tạo hoặc cấu hình user riêng (hoặc phân quyền schema) chỉ sở hữu các schema của notebook (`nb_document`, `nb_execution`, `nb_auth`).
  - Sử dụng Flyway migration độc lập cho từng service (`nb-document-service` và `nb-execution-service`) để quản lý schema versioning.

---

**Kết luận Phase 0**: Đủ điều kiện và cơ sở kỹ thuật để tiến hành Pha 1 (Tách Maven multi-module, xây dựng `nb-common`, cấu hình lưu trữ Flyway và xác thực ủy quyền Kyuubi).
