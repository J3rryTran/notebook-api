# Docker image

Một image all-in-one (contract 2), deploy bằng chart `notebook-helm`. Cách build: xem [BUILD.md](BUILD.md)
(`./scripts/build.sh` rồi `./scripts/package-image.sh <image>:<tag>`).

## Nội dung image

| Thành phần | Nguồn trong build context (`dist/image-context/`) | Trong image |
|---|---|---|
| notebook-service | `app/nb-notebook-service.jar` | `/app/nb-notebook-service.jar` (127.0.0.1:8084, actuator :8085) |
| execution-service | `app/nb-execution-service.jar` | `/app/nb-execution-service.jar` (127.0.0.1:8082, actuator :8083) |
| Web UI | `web/` | `/usr/share/nginx/html/` |
| nginx | `conf/nginx.conf` | `/etc/nginx/nginx.conf`, cổng **8080** (duy nhất mở ra ngoài) |
| Khởi động | `bin/entrypoint.sh` | `/app/` |

- Base `debian:12-slim`, JDK 17 lấy từ `trungtmba11093/java_env:java17-v0`.
- Chạy user `notebook` (uid/gid 10001), chạy được với root filesystem chỉ đọc, chỉ ghi `/tmp`.
- Token nội bộ giữa hai service tự sinh từ `/dev/urandom` mỗi lần khởi động (không ghi log); `/internal/**`
  chỉ nhận từ loopback và bị nginx chặn từ ngoài.
- Image **không chứa mã nguồn**: build context chỉ gồm các file trên.

Label: `notebook.contract=2`, `org.opencontainers.image.version` (= tag), `org.opencontainers.image.revision`
(git sha lúc build).

Không build trực tiếp `docker build .` từ gốc repo: `Dockerfile` đọc layout của `dist/image-context/`.
