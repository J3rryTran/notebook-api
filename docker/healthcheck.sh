#!/usr/bin/env sh
# $1 = startup | readiness | liveness
p=$1; [ "$p" = startup ] && p=readiness
curl -fsS -m 2 "http://127.0.0.1:8085/actuator/health/$p" >/dev/null && \
curl -fsS -m 2 "http://127.0.0.1:8083/actuator/health/$p" >/dev/null && \
curl -fsS -m 2 "http://127.0.0.1:8080/nginx-health" >/dev/null
