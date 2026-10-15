FROM debian:12-slim

LABEL notebook.contract="2"

ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/usr/bin/jdk-17.0.12
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN apt-get update && \
    apt-get install -y --no-install-recommends nginx curl procps ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY --from=trungtmba11093/java_env:java17-v0 /usr/bin/jdk-17.0.12 /usr/bin/jdk-17.0.12

# Tạo user notebook (uid 10001, gid 10001) không login
RUN groupadd -g 10001 notebook && \
    useradd -u 10001 -g 10001 -M -s /usr/sbin/nologin notebook

WORKDIR /app

ARG VERSION=0.1.0-SNAPSHOT
COPY backend/nb-notebook-service/target/nb-notebook-service-${VERSION}.jar /app/nb-notebook-service.jar
COPY backend/nb-execution-service/target/nb-execution-service-${VERSION}.jar /app/nb-execution-service.jar

COPY frontend/public/ /usr/share/nginx/html/
COPY docker/nginx.conf /etc/nginx/nginx.conf
COPY docker/entrypoint.sh /app/entrypoint.sh
COPY docker/healthcheck.sh /app/healthcheck.sh

RUN chmod 0755 /app/entrypoint.sh /app/healthcheck.sh && \
    chown -R 10001:10001 /app /usr/share/nginx/html

USER 10001

EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]