#!/usr/bin/env bash
# The actual Phase-4 sequence: resolve an image, helm upgrade --install, wait for rollout, health
# check, smoke test, and roll back automatically on any failure from that point on. Run by hand
# against local minikube - see docs/architecture.md for why this isn't triggered by cd.yml itself
# (GitHub-hosted runners can't reach a local minikube).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
source "${SCRIPT_DIR}/lib/health.sh"
source "${SCRIPT_DIR}/lib/smoke.sh"

IMAGE_REPOSITORY_DEFAULT="ghcr.io/asutosh-patnaik/claude-full-learning"
SERVICE_PORT=8080

usage() {
  cat <<EOF
Usage: $(basename "$0") <dev|test|production> [options]

Options:
  --tag <tag>          Image tag to deploy (default: latest). For --source ghcr, this is the
                        GHCR tag (e.g. a commit SHA or semver tag from cd.yml).
  --source <ghcr|local> Where the image comes from (default: ghcr):
                          ghcr  - cluster pulls ${IMAGE_REPOSITORY_DEFAULT}:<tag> directly.
                          local - builds from this checkout and 'minikube image load's it,
                                  mirroring k8s/app.yaml's flow, for an unpushed change.
  --local-port <port>   Local port for the port-forward used to health/smoke check (default: 18080).
  --help                Show this help.

Environment variables (--source ghcr only, for a private GHCR package):
  GHCR_PULL_PAT         A GitHub PAT with read:packages, used to create an imagePullSecret.
  GHCR_PULL_USERNAME    The GitHub username that PAT belongs to. Both required together.

Requires helm/claude-full-learning/values-<env>.secrets.yaml to already exist (never committed -
see values-secrets.yaml.example and docs/deployment.md).
EOF
}

ENVIRONMENT=""
TAG="latest"
SOURCE="ghcr"
LOCAL_PORT="18080"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --source) SOURCE="$2"; shift 2 ;;
    --local-port) LOCAL_PORT="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    dev|test|production) ENVIRONMENT="$1"; shift ;;
    *) log_error "unknown argument: $1"; usage; exit 1 ;;
  esac
done

require_environment_arg "$ENVIRONMENT"
NAMESPACE="$(namespace_for_env "$ENVIRONMENT")"
VALUES_FILE="${CHART_DIR}/values-${ENVIRONMENT}.yaml"
SECRETS_FILE="${CHART_DIR}/values-${ENVIRONMENT}.secrets.yaml"

if [[ ! -f "$SECRETS_FILE" ]]; then
  log_error "missing ${SECRETS_FILE}"
  log_error "copy helm/claude-full-learning/values-secrets.yaml.example to that path and fill in real values (never commit it) - see docs/deployment.md"
  exit 1
fi

IMAGE_ARGS=()
case "$SOURCE" in
  ghcr)
    log_info "using GHCR image ${IMAGE_REPOSITORY_DEFAULT}:${TAG} (cluster pulls directly)"
    IMAGE_ARGS+=(--set "image.tag=${TAG}")
    # Private GHCR package fallback. Credential is read from the shell environment only - never
    # written to a file or passed as a literal --set value (which would land in shell history).
    if [[ -n "${GHCR_PULL_PAT:-}" ]]; then
      if [[ -z "${GHCR_PULL_USERNAME:-}" ]]; then
        log_error "GHCR_PULL_PAT is set but GHCR_PULL_USERNAME is not - both are required"
        exit 1
      fi
      log_info "GHCR_PULL_PAT set - creating/updating imagePullSecret 'ghcr-pull-secret' in ${NAMESPACE}"
      kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"
      kubectl create secret docker-registry ghcr-pull-secret \
        --docker-server=ghcr.io \
        --docker-username="${GHCR_PULL_USERNAME}" \
        --docker-password="${GHCR_PULL_PAT}" \
        -n "$NAMESPACE" \
        --dry-run=client -o yaml | kubectl apply -f -
      IMAGE_ARGS+=(--set "image.pullSecretName=ghcr-pull-secret")
    fi
    ;;
  local)
    log_info "building local image claude-full-learning:${TAG} and loading into minikube..."
    docker build -t "claude-full-learning:${TAG}" "$REPO_ROOT"
    minikube image load "claude-full-learning:${TAG}"
    IMAGE_ARGS+=(--set "image.repository=claude-full-learning" --set "image.tag=${TAG}" --set "image.pullPolicy=Never")
    ;;
  *)
    log_error "unknown --source '${SOURCE}' - must be 'ghcr' or 'local'"
    exit 1
    ;;
esac

DEPLOY_STARTED=false
PREVIOUS_REVISION=""
PORT_FORWARD_PID=""

cleanup() {
  local exit_code=$?
  if [[ -n "$PORT_FORWARD_PID" ]]; then
    kill "$PORT_FORWARD_PID" 2>/dev/null || true
    wait "$PORT_FORWARD_PID" 2>/dev/null || true
  fi
  if [[ $exit_code -ne 0 && "$DEPLOY_STARTED" == true ]]; then
    if [[ -n "$PREVIOUS_REVISION" ]]; then
      log_error "deploy failed - rolling back '${RELEASE_NAME}' in '${NAMESPACE}' to revision ${PREVIOUS_REVISION}"
      helm rollback "$RELEASE_NAME" "$PREVIOUS_REVISION" -n "$NAMESPACE" --wait --timeout 3m \
        || log_error "rollback itself failed - manual intervention needed, see docs/troubleshooting.md"
    else
      log_error "deploy failed on a fresh install - nothing to roll back to."
      log_error "consider: helm uninstall ${RELEASE_NAME} -n ${NAMESPACE} (or scripts/destroy.sh --uninstall-release ${ENVIRONMENT})"
    fi
    log_error "see docs/rollback.md and docs/troubleshooting.md"
  fi
  exit $exit_code
}
trap cleanup EXIT

if helm status "$RELEASE_NAME" -n "$NAMESPACE" >/dev/null 2>&1; then
  PREVIOUS_REVISION="$(helm history "$RELEASE_NAME" -n "$NAMESPACE" | tail -n +2 | tail -1 | awk '{print $1}')"
  log_info "existing release found at revision ${PREVIOUS_REVISION} - will roll back to it on failure"
else
  log_info "no existing release in ${NAMESPACE} - this will be a fresh install"
fi

DEPLOY_STARTED=true
log_info "deploying '${ENVIRONMENT}' (namespace: ${NAMESPACE}, tag: ${TAG}, source: ${SOURCE})..."
helm upgrade --install "$RELEASE_NAME" "$CHART_DIR" \
  -f "$VALUES_FILE" -f "$SECRETS_FILE" \
  "${IMAGE_ARGS[@]}" \
  -n "$NAMESPACE" --create-namespace \
  --wait --timeout 5m

log_info "checking rollout status..."
kubectl rollout status "deployment/${RELEASE_NAME}" -n "$NAMESPACE" --timeout=120s

log_info "starting port-forward on localhost:${LOCAL_PORT} for health/smoke checks..."
kubectl port-forward -n "$NAMESPACE" "svc/${RELEASE_NAME}" "${LOCAL_PORT}:${SERVICE_PORT}" \
  > /tmp/claude-full-learning-deploy-portforward.log 2>&1 &
PORT_FORWARD_PID=$!
sleep 3

BASE_URL="http://localhost:${LOCAL_PORT}"
wait_for_health "$BASE_URL"
run_smoke_tests "$BASE_URL"

kill "$PORT_FORWARD_PID" 2>/dev/null || true
wait "$PORT_FORWARD_PID" 2>/dev/null || true
PORT_FORWARD_PID=""

log_info "deploy succeeded: release=${RELEASE_NAME} namespace=${NAMESPACE} tag=${TAG}"
log_info "reach it later with: kubectl port-forward -n ${NAMESPACE} svc/${RELEASE_NAME} 8080:${SERVICE_PORT}"
log_info "(or the Ingress path from 'helm get notes ${RELEASE_NAME} -n ${NAMESPACE}', if ingress.enabled)"
