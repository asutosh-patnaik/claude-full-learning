#!/usr/bin/env bash
# Manual "undo" for a Helm release. deploy.sh already auto-rolls-back on its own failures - this
# is for rolling back a deploy that *succeeded* but turned out to be bad, or for inspecting history.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
source "${SCRIPT_DIR}/lib/health.sh"

SERVICE_PORT=8080
LOCAL_PORT="18080"

usage() {
  cat <<EOF
Usage:
  $(basename "$0") <dev|test|production>                 List revision history for that environment.
  $(basename "$0") <dev|test|production> <revision>       Roll back to that revision and verify health.

What a rollback actually reverts: the Deployment's pod template (image, env, resources) - NOT
Mongo's data, which has no PersistentVolumeClaim regardless (see helm/claude-full-learning/
templates/mongo-deployment.yaml) and is unaffected by any Helm operation either way.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" || $# -eq 0 ]]; then
  usage
  exit 0
fi

ENVIRONMENT="$1"
require_environment_arg "$ENVIRONMENT"
NAMESPACE="$(namespace_for_env "$ENVIRONMENT")"
REVISION="${2:-}"

if [[ -z "$REVISION" ]]; then
  log_info "revision history for '${RELEASE_NAME}' in '${NAMESPACE}':"
  helm history "$RELEASE_NAME" -n "$NAMESPACE"
  log_info "re-run with a revision number to roll back: $(basename "$0") ${ENVIRONMENT} <revision>"
  exit 0
fi

log_info "rolling back '${RELEASE_NAME}' in '${NAMESPACE}' to revision ${REVISION}..."
helm rollback "$RELEASE_NAME" "$REVISION" -n "$NAMESPACE" --wait --timeout 3m

log_info "verifying health after rollback..."
PORT_FORWARD_PID=""
cleanup() { [[ -n "$PORT_FORWARD_PID" ]] && kill "$PORT_FORWARD_PID" 2>/dev/null || true; }
trap cleanup EXIT

kubectl port-forward -n "$NAMESPACE" "svc/${RELEASE_NAME}" "${LOCAL_PORT}:${SERVICE_PORT}" \
  > /tmp/claude-full-learning-rollback-portforward.log 2>&1 &
PORT_FORWARD_PID=$!
sleep 3

wait_for_health "http://localhost:${LOCAL_PORT}"
log_info "rollback to revision ${REVISION} verified healthy."
