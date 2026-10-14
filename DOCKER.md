# Hướng dẫn Build Docker & Triển khai trên ArgoCD

Hệ thống được đóng gói theo mô hình **All-In-One (1 Stage)** để triển khai dưới dạng **1 Application trên ArgoCD**.

---

## 1. Cơ chế đóng gói 1-Stage

File [Dockerfile](file:///mnt/d/DOC/VT/notebook-api/Dockerfile) chỉ gồm **1 stage duy nhất**:
- Base image: `debian:12-slim`.
- Lấy Java 17 từ image: `trungtmba11093/java_env:java17-v0`.
- Cài đặt Nginx làm reverse proxy và phục vụ frontend static.
- Copy trực tiếp 2 file jar (`nb-notebook-service.jar` và `nb-execution-service.jar`) đã được biên dịch sẵn từ host.

---

## 2. Các bước tự thực hiện build

### Bước 1: Biên dịch và đóng gói JAR ở host
Sử dụng Maven có sẵn trong thư mục `apache-maven-3.9.11`:
```bash
./apache-maven-3.9.11/bin/mvn clean package -f backend/pom.xml -DskipTests
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

---

## 3. Triển khai 1 Application trên ArgoCD

Chỉ cần apply manifest [argocd/application.yaml](file:///mnt/d/DOC/VT/notebook-api/argocd/application.yaml) vào cluster Kubernetes:
```bash
kubectl apply -f argocd/application.yaml
```

ArgoCD sẽ tự động đồng bộ Helm chart từ [notebook-helm](file:///mnt/d/DOC/VT/notebook-api/notebook-helm) và chạy 1 Deployment duy nhất quản lý container all-in-one.
