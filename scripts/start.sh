#!/usr/bin/env bash
# One-time-per-cluster local setup: starts minikube and enables the addons the Helm chart depends
# on (ingress for Ingress resources, metrics-server for HPA - without it HPA shows <unknown>
# forever, see helm/claude-full-learning/templates/hpa.yaml). Fully idempotent, safe to re-run.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0")

Starts minikube (no-op if already running) and enables the ingress and metrics-server addons.
Run this once before scripts/deploy.sh; safe to re-run any time.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

log_info "starting minikube (no-op if already running)..."
minikube start

log_info "enabling ingress addon (required for Ingress resources, see docs/setup.md)..."
minikube addons enable ingress

log_info "enabling metrics-server addon (required for HPA, see docs/setup.md)..."
minikube addons enable metrics-server

log_info "done. Next: scripts/deploy.sh <dev|test|production>"
