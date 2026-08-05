{{/*
Standard helm-create-style name helpers. Every other template uses these so labels/selectors stay
consistent - this matters beyond style: the ServiceMonitor (added when observability is enabled)
matches Prometheus scrape targets by the exact selector labels defined here.
*/}}

{{- define "claude-full-learning.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "claude-full-learning.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "claude-full-learning.namespace" -}}
{{- .Values.namespace.name | default .Release.Namespace -}}
{{- end -}}

{{- define "claude-full-learning.labels" -}}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{ include "claude-full-learning.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "claude-full-learning.selectorLabels" -}}
app.kubernetes.io/name: {{ include "claude-full-learning.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "claude-full-learning.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- .Values.serviceAccount.name | default (include "claude-full-learning.fullname" .) -}}
{{- else -}}
{{- .Values.serviceAccount.name | default "default" -}}
{{- end -}}
{{- end -}}

{{/*
Mongo URI: use the explicit override if set, otherwise (when the in-chart Mongo is enabled) point
at this chart's own Mongo Service DNS name.
*/}}
{{- define "claude-full-learning.mongoUri" -}}
{{- if .Values.config.mongoUri -}}
{{- .Values.config.mongoUri -}}
{{- else if .Values.mongodb.enabled -}}
{{- printf "mongodb://%s-mongo:27017/logindb" (include "claude-full-learning.fullname" .) -}}
{{- else -}}
{{- fail "config.mongoUri must be set when mongodb.enabled is false" -}}
{{- end -}}
{{- end -}}
