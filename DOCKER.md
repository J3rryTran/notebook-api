# Đóng gói và Phân phối Docker Image

Tài liệu hướng dẫn quy trình đóng gói All-In-One Docker Image cho ứng dụng Lakehouse Notebook API (Contract 2).

---

## 1. Cơ chế đóng gói All-In-One (Contract 2)

Hệ thống được đóng gói trong một Docker image duy nhất (`Dockerfile`):
- **Base image**: `debian:12-slim`.
- **Java Runtime**: JDK 17 lấy từ `trungtmba11093/java_env:java17-v0`.
- **Nginx**: Phục vụ giao diện tĩnh (`/usr/share/nginx/html`), reverse proxy API tới 2 Spring Boot services và chặn các truy cập tới `/internal/**` từ bên ngoài.
- **Bảo mật**:
  - Image chạy dưới user không có đặc quyền `notebook` (UID 10001, GID 10001).
  - Khả năng chạy với file system chỉ đọc (`readOnlyRootFilesystem: true`) kết hợp tmpfs tại `/tmp`.
  - Token nội bộ `NOTEBOOK_INTERNAL_TOKEN` tự sinh ngẫu nhiên từ `/dev/urandom` lúc khởi động (không ghi log) và các endpoint `/internal/**` chỉ tiếp nhận yêu cầu từ loopback (`127.0.0.1`, `::1`).

---

## 2. Quy trình đóng gói tự động

Khuyến nghị sử dụng script đóng gói tích hợp sẵn cổng chặn chất lượng P0 - P9:

```bash
# Đóng gói với kiểm tra đầy đủ P0 - P9
./scripts/build-all-in-one.sh

# Hoặc nếu working tree đang có thay đổi chưa commit:
./scripts/build-all-in-one.sh --allow-dirty
```

Sau khi script hoàn thành và vượt qua toàn bộ các cổng chặn P0 - P8, image sẽ được tạo với tag tương ứng (ví dụ: `docker.io/j3rrytran/notebook:0.1.0`).

---

## 3. Đẩy Image lên Docker Registry

Để đẩy image đã build lên Docker Hub:

```bash
docker push docker.io/j3rrytran/notebook:0.1.0
```

Sau đó sử dụng Helm Chart tại [notebook-helm](notebook-helm) để triển khai lên Kubernetes cluster.
