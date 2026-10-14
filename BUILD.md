# Hướng dẫn Build và Đóng gói phân phối (dist/) bằng Maven

Tài liệu hướng dẫn cách gán tạm thời `JAVA_HOME` trong một session terminal hiện tại và sử dụng công cụ Maven đi kèm dự án (`apache-maven-3.9.11`) để build, đóng gói ứng dụng Lakehouse Notebook API.

---

## 1. Gán tạm thời `JAVA_HOME` trong 1 Session Terminal

Để không làm ảnh hưởng đến cấu hình hệ thống hoặc các dự án khác, bạn có thể gán `JAVA_HOME` tạm thời trong terminal hiện tại trước khi thực thi build:

```bash
# Ví dụ chọn Java có sẵn trên máy:
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
# Hoặc nếu dùng JDK 21:
# export JAVA_HOME="/home/j3rry/.jdks/temurin-21.0.12.1"

# Cập nhật tạm thời PATH cho session này
export PATH="$JAVA_HOME/bin:$PATH"

# Kiểm tra lại phiên bản Java đang active trong session
java -version
```

> **Lưu ý:** Biến môi trường này chỉ có hiệu lực trong cửa sổ terminal bạn đang mở, khi tắt terminal đi thì hệ thống sẽ quay về cấu hình ban đầu.

---

## 2. Đóng gói tự động nhanh nhất bằng Script (Khuyến nghị)

Script [`scripts/build-dist.sh`](scripts/build-dist.sh) được cấu hình sử dụng Maven Wrapper `backend/mvnw`:

```bash
# Chạy script tự động (kế thừa JAVA_HOME bạn vừa gán ở bước 1)
./scripts/build-dist.sh
```

**Quy trình script tự động xử lý:**
1. Nhận diện `JAVA_HOME` hiện tại của terminal và chạy qua `backend/mvnw`.
2. Tự động đồng bộ giao diện Web Frontend vào thư mục tài nguyên của backend (`backend/src/main/resources/static`).
3. Thực thi `mvn clean package -DskipTests` để tạo runnable Fat JAR.
4. Đóng gói đầy đủ vào thư mục `dist/` và tạo sẵn file nén `dist.tar.gz`.

---

## 3. Quy trình thực hiện thủ công bằng lệnh (Manual)

Nếu bạn muốn tự gõ từng lệnh trong session terminal đó:

### Bước 1: Build Spring Boot JAR bằng Maven Wrapper
```bash
cd backend

# Gọi Maven Wrapper của dự án
./mvnw clean package -DskipTests

cd ..
```


### Bước 2: Tạo cấu trúc thư mục `dist/`
```bash
mkdir -p dist/bin dist/config dist/logs dist/helm

# 1. Copy file thực thi JAR
cp backend/target/notebook-api-*.jar dist/bin/notebook-api.jar

# 2. Copy file cấu hình mẫu
cp backend/src/main/resources/application.yml dist/config/application.yml

# 3. Copy Helm chart đi kèm để deploy K8s
cp -r notebook-helm/* dist/helm/

# 4. (Tùy chọn) Nén file để phân phối lên máy chủ
tar -czf dist.tar.gz dist/
```

---

## 4. Cấu trúc thư mục `dist/` hoàn chỉnh

Sau khi hoàn tất, thư mục `dist/` sẽ có bố cục chuẩn:

```text
dist/
├── bin/
│   └── notebook-api.jar        # Ứng dụng Spring Boot đóng gói hoàn chỉnh (đã kèm UI tĩnh)
├── config/
│   └── application.yml         # Cấu hình môi trường (Kyuubi REST URL, MariaDB database `test`, Auth Mode)
├── helm/                       # Helm chart để deploy lên Kubernetes
│   ├── Chart.yaml
│   ├── values.yaml
│   └── templates/
├── start.sh                    # Script chạy nhanh (hỗ trợ chạy nền --daemon)
└── stop.sh                     # Script dừng ứng dụng an toàn
```

---

## 5. Khởi động ứng dụng từ `dist/`

Sau khi đã có thư mục `dist/`, bạn có thể khởi động ngay:

```bash
# Cách 1: Chạy trực tiếp (foreground)
./dist/start.sh

# Cách 2: Chạy nền (background/daemon)
./dist/start.sh --daemon

# Dừng ứng dụng
./dist/stop.sh
```

Hoặc chạy trực tiếp bằng lệnh `java`:
```bash
java -jar dist/bin/notebook-api.jar --spring.config.additional-location=file:dist/config/application.yml
```

### Đường dẫn truy cập:
- **Giao diện Web Notebook**: [http://localhost:8080](http://localhost:8080)
- **Kiểm tra trạng thái Service**: [http://localhost:8080/api/v1/notebook-status](http://localhost:8080/api/v1/notebook-status)
- **Actuator Health Check**: [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)
