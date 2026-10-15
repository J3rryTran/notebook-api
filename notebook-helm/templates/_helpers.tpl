{{/*
Expand the name of the chart.
*/}}
{{- define "notebook.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
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

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "notebook.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "notebook.labels" -}}
helm.sh/chart: {{ include "notebook.chart" . }}
{{ include "notebook.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "notebook.selectorLabels" -}}
app.kubernetes.io/name: {{ include "notebook.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Convert size string (e.g. 1Mi, 16Mi, 1Gi, 500Ki or raw bytes) to integer bytes.
*/}}
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

{{/*
Validate values during rendering (13 rules from chart-design.md §5)
*/}}
{{- define "notebook.validate" -}}
{{- if ne .Values.topology "allInOne" }}
  {{- fail "topology=split is not implemented yet" }}
{{- end }}

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

{{- if not .Values.publicUrl }}
  {{- fail "publicUrl is required" }}
{{- end }}

{{- if and (eq .Values.auth.mode "oidc") (not .Values.auth.oidc.issuer) }}
  {{- fail "auth.oidc.issuer is required for mode=oidc" }}
{{- end }}

{{- if eq .Values.auth.mode "ldap" }}
  {{- if not .Values.auth.ldap.url }}
    {{- fail "auth.ldap.url is required for mode=ldap" }}
  {{- end }}
  {{- $hasPattern := and .Values.auth.ldap.userDnPattern (ne .Values.auth.ldap.userDnPattern "") }}
  {{- $hasFilter := and .Values.auth.ldap.userSearchFilter (ne .Values.auth.ldap.userSearchFilter "") }}
  {{- if or (and $hasPattern $hasFilter) (and (not $hasPattern) (not $hasFilter)) }}
    {{- fail "set exactly one of auth.ldap.userDnPattern or userSearchFilter" }}
  {{- end }}
  {{- if and $hasFilter (not .Values.auth.ldap.managerDn) }}
    {{- fail "auth.ldap.managerDn is required with userSearchFilter" }}
  {{- end }}
  {{- if and (hasPrefix "ldap://" .Values.auth.ldap.url) (not .Values.auth.ldap.startTls) }}
    {{- fail "plain ldap:// requires auth.ldap.startTls=true" }}
  {{- end }}
{{- end }}

{{- if .Values.kyuubi.extraSessionConfigs }}
  {{- if hasKey .Values.kyuubi.extraSessionConfigs "kyuubi.engine.spark.output.mode" }}
    {{- fail "output.mode must stay at Kyuubi default (AUTO)" }}
  {{- end }}
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

{{- if and (hasPrefix "http://" .Values.publicUrl) .Values.ingress.tls }}
  {{- fail "publicUrl must be https:// when ingress.tls is set" }}
{{- end }}

{{- if and (eq .Values.service.type "NodePort") (not .Values.ingress.enabled) }}
  {{- if .Values.service.nodePort }}
    {{- $expectedSuffix := printf ":%v" .Values.service.nodePort }}
    {{- if not (hasSuffix $expectedSuffix .Values.publicUrl) }}
      {{- fail (printf "publicUrl must use service.nodePort (%v) when exposing via NodePort" .Values.service.nodePort) }}
    {{- end }}
  {{- end }}
{{- end }}

{{- end }}
