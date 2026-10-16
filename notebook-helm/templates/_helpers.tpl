{{- define "notebook.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "notebook.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "notebook.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "notebook.labels" -}}
helm.sh/chart: {{ include "notebook.chart" . }}
{{ include "notebook.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "notebook.selectorLabels" -}}
app.kubernetes.io/name: {{ include "notebook.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "notebook.bytes" -}}
{{- $v := toString . -}}
{{- if hasSuffix "Gi" $v -}}
{{- mul (trimSuffix "Gi" $v | int64) 1073741824 -}}
{{- else if hasSuffix "Mi" $v -}}
{{- mul (trimSuffix "Mi" $v | int64) 1048576 -}}
{{- else if hasSuffix "Ki" $v -}}
{{- mul (trimSuffix "Ki" $v | int64) 1024 -}}
{{- else -}}
{{- $v | int64 -}}
{{- end -}}
{{- end }}

{{- define "notebook.validate" -}}
{{- if and (ne .Values.auth.mode "oidc") (ne .Values.auth.mode "no-oidc") (ne .Values.auth.mode "ldap") }}
  {{- fail "auth.mode must be oidc, no-oidc or ldap" }}
{{- end }}

{{- if not .Values.existingSecret }}
  {{- fail "existingSecret is required" }}
{{- end }}

{{- if not .Values.mariadb.host }}
  {{- fail "mariadb.host is required" }}
{{- end }}

{{- if not .Values.kyuubi.restUrl }}
  {{- fail "kyuubi.restUrl is required" }}
{{- end }}

{{- if eq .Values.auth.mode "oidc" }}
  {{- $rp := .Values.auth.oidc.redirectPath | default "/auth/callback" }}
  {{- if or (not (hasPrefix "/" $rp)) (hasPrefix "//" $rp) (contains "://" $rp) (hasPrefix "/api/" $rp) (hasPrefix "/internal/" $rp) (hasPrefix "/actuator/" $rp) }}
    {{- fail "auth.oidc.redirectPath must start with / and not be under /api/, /internal/ or /actuator/" }}
  {{- end }}
{{- end }}

{{- if and (eq .Values.auth.mode "oidc") (not .Values.auth.oidc.issuer) }}
  {{- fail "auth.oidc.issuer is required for mode=oidc" }}
{{- end }}

{{- if eq .Values.auth.mode "ldap" }}
  {{- if not .Values.auth.ldap }}
    {{- fail "auth.ldap block is required for mode=ldap" }}
  {{- end }}
  {{- if not .Values.auth.ldap.trustStore }}
    {{- fail "auth.ldap.trustStore is required for mode=ldap (enabled: false nếu không dùng)" }}
  {{- end }}
  {{- if not (and .Values.auth.ldap.serviceAccount .Values.auth.ldap.serviceAccount.username) }}
    {{- fail "auth.ldap.serviceAccount.username is required for mode=ldap" }}
  {{- end }}
  {{- if not .Values.auth.ldap.url }}
    {{- fail "auth.ldap.url is required for mode=ldap" }}
  {{- end }}
  {{- if and (hasPrefix "ldap://" .Values.auth.ldap.url) (not .Values.auth.ldap.startTls) }}
    {{- fail "plain ldap:// requires auth.ldap.startTls=true" }}
  {{- end }}
  {{- if and (hasPrefix "ldaps://" .Values.auth.ldap.url) .Values.auth.ldap.startTls }}
    {{- fail "auth.ldap.startTls must be false with ldaps:// (already TLS)" }}
  {{- end }}
{{- end }}

{{- if .Values.kyuubi.extraSessionConfigs }}
  {{- if hasKey .Values.kyuubi.extraSessionConfigs "kyuubi.engine.spark.output.mode" }}
    {{- fail "output.mode must stay at Kyuubi default (AUTO)" }}
  {{- end }}
{{- end }}

{{- if not (has .Values.ha.antiAffinity (list "soft" "hard" "none")) }}
  {{- fail "ha.antiAffinity must be soft, hard or none" }}
{{- end }}

{{- if and .Values.podDisruptionBudget.enabled (gt (int .Values.replicaCount) 1) (ge (int .Values.podDisruptionBudget.minAvailable) (int .Values.replicaCount)) }}
  {{- fail (printf "podDisruptionBudget.minAvailable (%v) must be < replicaCount (%v), otherwise node drain is blocked" .Values.podDisruptionBudget.minAvailable .Values.replicaCount) }}
{{- end }}

{{- if and (eq (toString .Values.updateStrategy.maxUnavailable) "0") (eq (toString .Values.updateStrategy.maxSurge) "0") }}
  {{- fail "updateStrategy.maxUnavailable and maxSurge cannot both be 0" }}
{{- end }}

{{- if and .Values.ingress.enabled (not .Values.ingress.host) }}
  {{- fail "ingress.host is required" }}
{{- end }}

{{- if eq .Values.service.type "NodePort" }}
  {{- if .Values.service.nodePort }}
    {{- $np := int .Values.service.nodePort }}
    {{- if or (lt $np 30000) (gt $np 32767) }}
      {{- fail "service.nodePort must be in 30000-32767" }}
    {{- end }}
  {{- end }}
{{- end }}

{{- end }}
