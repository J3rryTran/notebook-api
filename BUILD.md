# Hướng dẫn Build và Đóng gói

Tài liệu hướng dẫn quy trình build, kiểm thử và đóng gói ứng dụng Lakehouse Notebook API theo Contract 2.

## 1. Yêu cầu hệ thống

- **Docker Engine**: Môi trường chỉ yêu cầu Docker có sẵn. Toàn bộ quá trình biên dịch Maven (Java 17), kiểm thử (Testcontainers MariaDB), linting (ShellCheck, Helm) đều được thực thi khép kín trong container Docker. Không cần cài JDK hay Maven trên máy host.

---

## 2. Lệnh đóng gói tự động (All-In-One Gate P0 - P9)

Thực thi cổng chặn đóng gói hoàn chỉnh bằng script:

```bash
./scripts/build-all-in-one.sh
```

Nếu đang trong quá trình phát triển và working tree chưa commit:
```bash
./scripts/build-all-in-one.sh --allow-dirty
```

---

## 3. Các cổng chặn tự động (P0 - P9)

| Cổng | Tên | Chi tiết | Dừng khi |
|---|---|---|---|
| **P0** | Kiểm tra đầu vào Git | Đọc `appVersion` từ `notebook-helm/Chart.yaml`, xác thực trạng thái git clean / `--allow-dirty`. | Git dirty không cờ; version rỗng |
| **P1** | Maven Build & Test | Chạy `mvn -B -ntp clean verify` qua Docker `maven:3.9.9-eclipse-temurin-17`, mount Docker socket cho MariaDB Testcontainers. Script tự dò phiên bản Docker API của máy (`docker version`) và truyền cho Testcontainers (`DOCKER_API_VERSION`, `-Dapi.version`), nên chạy được trên cả Docker cũ (20.10, API 1.41) lẫn mới (29+, yêu cầu API ≥ 1.40). | Bất kỳ unit/IT test nào thất bại (Tuyệt đối không `-DskipTests`) |
| **P2** | Kiểm tra Spring Boot Fat JARs | Xác thực `nb-notebook-service` và `nb-execution-service` mỗi dịch vụ ra đúng 1 Fat JAR có `BOOT-INF/`. Ghi SHA-256 vào `dist/checksums.txt`. | JAR không hợp lệ hoặc thiếu |
| **P3** | Kiểm tra Frontend Static | Đảm bảo `frontend/public/index.html` tự chứa hoàn toàn tài nguyên (không gọi CDN bên ngoài) và khớp mã băm SHA-256 trong `vendor/README.md`. | Phát hiện `http(s)://` trong `src`/`href`; sai SHA-256 |
| **P4** | Kiểm tra Helm Chart | Chạy `scripts/helm-check.sh` qua Docker `alpine/helm:3.14.4`: `helm lint` + `template` 3 chế độ (`oidc`, `no-oidc`, `ldap`) + 13 kịch bản lỗi xác thực. | Lint lỗi hoặc sai kịch bản |
| **P5** | Kiểm tra hợp đồng tĩnh (X1) | Chạy `scripts/contract-check.sh --static-only`: so khớp 100% key trong ConfigMap render với code properties. | Xuất hiện key mồ côi |
| **P6** | Kiểm tra cấu hình & Script | Chạy ShellCheck (`koalaman/shellcheck:v0.10.0`) cho toàn bộ shell scripts và `nginx -t` (`nginx:1.27-alpine`). | Lỗi cú pháp shell hoặc nginx |
| **P7** | Đóng gói Docker Image | Build Docker image all-in-one với nhãn `LABEL notebook.contract="2"`, chạy non-root (UID 10001). | Build thất bại |
| **P8** | Kiểm tra runtime thực tế (X2-X5) | Mô phỏng Pod K8s: `--user 10001 --read-only --tmpfs /tmp`, MariaDB container, WireMock Kyuubi mock. Kiểm tra readiness, chặn cổng nội bộ, dừng sạch SIGTERM < 40s. | Không đạt readiness sau 2 phút hoặc cổng nội bộ bị lộ |
| **P9** | Xuất báo cáo | Ghi báo cáo chi tiết vào `dist/build-report.md`. In lệnh `docker push` để người vận hành chủ động đẩy image. | - |

---

## 4. Đọc báo cáo kết quả

Sau khi hoàn tất, kết quả đóng gói và SHA-256 của các artifact được ghi đầy đủ tại:
- `dist/build-report.md`: Báo cáo tổng thể quá trình build.
- `dist/checksums.txt`: Bảng mã băm SHA-256 của các Fat JARs.


### Chạy Maven bằng tay (không qua script)

Test `*IT` cần Docker. Truyền phiên bản API của Docker daemon đang chạy, nếu không Testcontainers gửi API
1.32 và Docker 29+ sẽ từ chối:

```bash
API=$(docker version --format '{{.Server.APIVersion}}'); docker run --rm -v "$PWD/backend":/src -v "$HOME/.m2":/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e DOCKER_API_VERSION=$API --add-host=host.docker.internal:host-gateway -w /src maven:3.9.9-eclipse-temurin-17 mvn -B -ntp clean verify -Dapi.version=$API
```
