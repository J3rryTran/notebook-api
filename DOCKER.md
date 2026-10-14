# Build Docker image

Hệ thống được đóng gói theo mô hình **All-In-One (1 Stage)**. Sau khi build, image được hệ thống deploy của nền tảng kéo về để triển khai trên Kubernetes.

---

## 1. Cơ chế đóng gói 1-Stage

File [Dockerfile](Dockerfile) chỉ gồm **1 stage duy nhất**:
- Base image: `debian:12-slim`.
- Lấy Java 17 từ image: `trungtmba11093/java_env:java17-v0`.
- Cài đặt Nginx làm reverse proxy và phục vụ frontend static.
- Copy trực tiếp 2 file jar (`nb-notebook-service.jar` và `nb-execution-service.jar`) đã được biên dịch sẵn từ host.

---

## 2. Các bước tự thực hiện build

### Bước 1: Biên dịch và đóng gói JAR ở host
Sử dụng Maven Wrapper tại thư mục `backend/`:
```bash
./backend/mvnw clean package -f backend/pom.xml -DskipTests
```

### Bước 2: Build Docker Image (1 Stage)
```bash
docker build -t docker.io/j3rrytran/notebook:0.1.0 -f Dockerfile .
```

*(Hoặc bạn có thể chạy 1 lệnh tự động gồm cả 2 bước trên thông qua script: `./scripts/build-all-in-one.sh`)*

### Bước 3: Push image lên Registry
```bash
docker push docker.io/j3rrytran/notebook:0.1.0
```

Image sẵn sàng để hệ thống deploy của nền tảng kéo về và triển khai cùng Helm chart tại [notebook-helm](notebook-helm).

