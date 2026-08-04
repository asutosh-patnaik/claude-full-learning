#!/usr/bin/env bash
# Installs the platform-level observability stack into its own `monitoring` namespace - separate
# from scripts/start.sh since this is a heavier, optional step, and separate from the app's own
# Helm release since Prometheus/Grafana/Loki are cluster-wide, not per-app.
#
# Chart choices (see CLAUDE.md's Observability section for the "why", including one dead end this
# ran into): prometheus-community/kube-prometheus-stack bundles Prometheus + Grafana + Alertmanager
# + node-exporter + kube-state-metrics in one release. For logs: grafana-community/helm-charts'
# `loki` chart (Monolithic deployment mode, filesystem storage - no S3/MinIO needed for a local
# demo) + grafana/promtail to ship container logs to it. The originally-planned loki-stack chart
# turned out to be deprecated - grafana/loki itself moved to a new grafana-community org in March
# 2026, and grafana/promtail is the one piece still served from the old grafana repo.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0")

Installs kube-prometheus-stack (Prometheus + Grafana + Alertmanager) and Loki + Promtail into the
'monitoring' namespace. Idempotent (helm upgrade --install). Run once per cluster, after
scripts/start.sh. Turn on scraping/dashboard for this app afterwards with:
  helm upgrade --install claude-full-learning helm/claude-full-learning \\
    -f helm/claude-full-learning/values-<env>.yaml -f helm/claude-full-learning/values-<env>.secrets.yaml \\
    --set serviceMonitor.enabled=true --set grafanaDashboard.enabled=true \\
    -n claude-full-learning-<env>
EOF
}

[[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && { usage; exit 0; }

log_info "adding/updating Helm repos..."
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null
helm repo add grafana-community https://grafana-community.github.io/helm-charts >/dev/null
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null
helm repo update >/dev/null

log_info "installing kube-prometheus-stack (Prometheus + Grafana + Alertmanager)..."
# grafana.additionalDataSources pre-wires a Loki datasource pointing at the Loki release installed
# below - without this, kube-prometheus-stack's bundled Grafana only auto-provisions Prometheus and
# Alertmanager (confirmed via its own /api/datasources), so Explore has nothing to query logs with
# until someone adds Loki by hand. The URL is in-cluster DNS for the "loki" release's gateway
# Service, resolvable regardless of install order since it's only evaluated at query time, not here.
helm upgrade --install monitoring prometheus-community/kube-prometheus-stack \
  -n monitoring --create-namespace \
  --set-json 'grafana.additionalDataSources=[{"name":"Loki","type":"loki","access":"proxy","url":"http://loki-gateway.monitoring.svc.cluster.local","isDefault":false,"jsonData":{"maxLines":1000}}]' \
  --wait --timeout 5m

log_info "installing Loki (Monolithic mode, filesystem storage - no S3/MinIO needed locally)..."
# read/write/backend.replicas default to 3 each regardless of deploymentMode - the chart doesn't
# zero them out for you, and refuses to install if both the Monolithic and SimpleScalable targets
# have non-zero replicas at once ("ambiguous which mode was intended"). loki.useTestSchema is the
# chart's own suggested flag for a schema_config-free local/no-persistence setup. chunksCache and
# resultsCache (memcached sidecars) are disabled - optional read-path caching, not required for
# ingestion/query to work, and this minikube node doesn't have spare memory to schedule them.
# loki.auth_enabled defaults to true (multi-tenancy) - every push/query needs an X-Scope-OrgID
# header or Loki rejects it with 404 "no org id". Promtail's default config doesn't send one, so
# without this override logs would silently fail to ingest. Disabled here since a single-tenant
# local demo has no use for multi-tenancy. All five found by actually running this, not the docs.
helm upgrade --install loki grafana-community/loki \
  -n monitoring \
  --set loki.storage.type=filesystem \
  --set loki.useTestSchema=true \
  --set loki.auth_enabled=false \
  --set singleBinary.replicas=1 \
  --set read.replicas=0 \
  --set write.replicas=0 \
  --set backend.replicas=0 \
  --set loki.commonConfig.replication_factor=1 \
  --set chunksCache.enabled=false \
  --set resultsCache.enabled=false \
  --wait --timeout 5m

log_info "installing Promtail (ships container stdout/stderr to Loki, no app changes needed)..."
helm upgrade --install promtail grafana/promtail \
  -n monitoring \
  --wait --timeout 5m

log_info "done. Grafana: kubectl port-forward -n monitoring svc/monitoring-grafana 3000:80"
log_info "Grafana admin password: kubectl get secret -n monitoring monitoring-grafana -o jsonpath='{.data.admin-password}' | base64 -d"
log_info "Loki is pre-wired as a Grafana datasource - use Explore, pick 'Loki', query e.g. {namespace=\"claude-full-learning-dev\"}"
