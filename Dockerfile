FROM debian:12-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV JAVA_HOME=/usr/bin/jdk-17.0.12
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN apt-get update && \
    apt-get install -y --no-install-recommends nginx curl procps ca-certificates && \
    rm -rf /var/lib/apt/lists/*

COPY --from=trungtmba11093/java_env:java17-v0 /usr/bin/jdk-17.0.12 /usr/bin/jdk-17.0.12

WORKDIR /app

COPY backend/nb-notebook-service/target/nb-notebook-service-*.jar /app/nb-notebook-service.jar
COPY backend/nb-execution-service/target/nb-execution-service-*.jar /app/nb-execution-service.jar

COPY frontend/public/ /usr/share/nginx/html/
COPY docker/nginx.conf /etc/nginx/nginx.conf

COPY docker/entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh
EXPOSE 8080

ENTRYPOINT ["/app/entrypoint.sh"]