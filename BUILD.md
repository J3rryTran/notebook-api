# Build và đóng gói

Hai bước, hai lệnh riêng. Máy chỉ cần **Docker**: Maven/Java 17, ShellCheck đều chạy trong container.
Build và đóng gói không đụng tới chart `notebook-helm`; kiểm chart là lệnh riêng (mục 4).

```
./scripts/build.sh                          → dist/image-context/   (jar + web + cấu hình, KHÔNG mã nguồn)
./scripts/package-image.sh <image>:<tag>    → Docker image           (chỉ đọc dist/image-context/)
```

## 1. Build

```bash
./scripts/build.sh
```

| Bước | Làm gì | Dừng khi |
|---|---|---|
| B1 | `mvn clean verify` trong `maven:3.9.9-eclipse-temurin-17`: unit test + test MariaDB (Testcontainers). Tự dò phiên bản Docker API của máy (`docker version`) và truyền cho Testcontainers, nên chạy được với Docker cũ (20.10, API 1.41) lẫn mới (29+) | Bất kỳ test nào đỏ. Không có cờ bỏ qua test |
| B2 | Mỗi service ra đúng 1 Spring Boot fat jar | Thiếu/thừa jar |
| B3 | Frontend tự chứa (không tải từ CDN), checksum `vendor/oidc-client-ts.min.js` khớp | Có `http(s)://` trong `src`/`href`; checksum lệch |
| B4 | ShellCheck `docker/*.sh`, `scripts/build.sh`, `scripts/package-image.sh`; `nginx -t` | Lỗi cú pháp |
| B5 | Gom `dist/image-context/`: `app/*.jar`, `web/`, `conf/nginx.conf`, `bin/`, `Dockerfile`, `BUILD-INFO` (git sha, thời điểm, số test), `SHA256SUMS` | Phát hiện mã nguồn trong context |

Log: `dist/mvn-verify.log`. Chi tiết test lỗi:
`backend/*/target/surefire-reports` (unit), `backend/*/target/failsafe-reports` (MariaDB IT).

Không yêu cầu commit trước; `BUILD-INFO` ghi lại nếu build từ code chưa commit.

## 2. Đóng gói image

```bash
./scripts/package-image.sh docker.io/j3rrytran/notebook:0.1.1
```

- Tên và tag image truyền thẳng vào lệnh, bắt buộc có tag, không nhận `latest`.
- Build context chỉ là `dist/image-context/`. Script kiểm `SHA256SUMS` trước: thư mục bị sửa sau bước 1 thì
  dừng, yêu cầu chạy lại `./scripts/build.sh`.
- Sau khi build, chạy thử image bằng `scripts/image-smoke-test.sh` (không dùng chart): MariaDB và Kyuubi giả
  lập, cấu hình `application.yaml` tối thiểu có sẵn trong script, chạy như trong pod (uid 10001, root
  filesystem chỉ đọc). Kiểm: probe Ready, `/internal` 403, actuator không lộ, dừng sạch. Bỏ bước này bằng
  `--skip-runtime-check` (không dùng cho bản phát hành).
- Không tự push. Báo cáo: `dist/package-report.md`.

Push và trỏ chart vào image vừa đóng gói:

```bash
docker push docker.io/j3rrytran/notebook:0.1.1
```

```yaml
# notebook-helm/values.yaml
image:
  repository: docker.io/j3rrytran/notebook
  tag: "0.1.1"
```

## 3. Chạy Maven bằng tay (không qua script)

Test `*IT` cần Docker. Truyền phiên bản API của Docker daemon đang chạy, nếu không Testcontainers gửi API
1.32 và Docker 29+ sẽ từ chối:

```bash
API=$(docker version --format '{{.Server.APIVersion}}'); docker run --rm -v "$PWD/backend":/src -v "$HOME/.m2":/root/.m2 -v /var/run/docker.sock:/var/run/docker.sock -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e DOCKER_API_VERSION=$API --add-host=host.docker.internal:host-gateway -w /src maven:3.9.9-eclipse-temurin-17 mvn -B -ntp clean verify -Dapi.version=$API
```

## 4. Kiểm chart (riêng, không thuộc build)

```bash
./scripts/helm-check.sh
```

Kiểm bản thân chart (template, schema, các điều kiện chặn); giá trị riêng của môi trường được thay bằng giá trị
giả nên `values.yaml` đang điền dở không làm lỗi. `./scripts/contract-check.sh`: khóa ConfigMap chart render ra
đều có code đọc. Trước khi deploy, kiểm đúng `values.yaml`:

```bash
./scripts/helm-check.sh --deploy
```
