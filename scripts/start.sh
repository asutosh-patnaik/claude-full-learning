#!/usr/bin/env bash
# One-time-per-cluster local setup: starts minikube and enables the addons the Helm chart depends
# on (ingress for Ingress resources, metrics-server for HPA - without it HPA shows <unknown>
# forever, see helm/java-spring-auth-service-claude/templates/hpa.yaml). Fully idempotent, safe to re-run.
# --stop pauses minikube (not destroys it - see scripts/destroy.sh --wipe-cluster for that).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--stop]

No args: starts minikube (no-op if already running) and enables the ingress and metrics-server
addons. Run this once before scripts/deploy.sh; safe to re-run any time.

--stop: stops minikube (minikube stop) - pauses the cluster and preserves all its data/state
(everything deployed on it, addon config) for a fast resume later via a plain re-run of this
script. This is NOT the same as scripts/destroy.sh --wipe-cluster, which permanently destroys the
cluster and all its data (minikube delete) - stop here is the safe, reversible, no-confirmation-
needed pause; that one needs typing the cluster name back to confirm for exactly that reason. Also
stops whatever's running on it (the app, the observability stack) along with the cluster itself.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if [[ "${1:-}" == "--stop" ]]; then
  log_info "stopping minikube (preserves all data - resume with a plain re-run of this script)..."
  minikube stop
  exit 0
fi

log_info "starting minikube (no-op if already running)..."
minikube start

log_info "enabling ingress addon (required for Ingress resources, see docs/setup.md)..."
minikube addons enable ingress

log_info "enabling metrics-server addon (required for HPA, see docs/setup.md)..."
minikube addons enable metrics-server

log_info "done. Next: scripts/deploy.sh <dev|test|production>"
