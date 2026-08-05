#!/usr/bin/env bash
# Two independent, never-bundled flags, because "remove this app's release" and "wipe the entire
# local cluster" are very different blast radii. The observability stack (a third, unrelated
# blast radius - platform-level, not per-app) is deliberately NOT handled here - it's
# scripts/observability.sh --stop, since that script already owns installing it.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"

usage() {
  cat <<EOF
Usage:
  $(basename "$0") --uninstall-release <dev|test|production> [--yes]
      Removes just this app's Helm release (and its namespace's resources) for that environment.
      Leaves minikube and everything else on it running. Prompts for confirmation unless --yes.

  $(basename "$0") --wipe-cluster
      Destroys the ENTIRE local minikube cluster (minikube delete) - every namespace, every
      release, anything else you have running on it. Requires typing the cluster context name
      back to confirm; there is no --yes shortcut for this one.

To stop just the observability stack (Prometheus/Grafana/Loki/Promtail) without touching the app or
the cluster, use scripts/observability.sh --stop instead - not handled here.

No flags: prints this usage and exits non-zero - never guesses which destructive action you meant.
EOF
}

case "${1:-}" in
  --uninstall-release)
    ENVIRONMENT="${2:-}"
    require_environment_arg "$ENVIRONMENT"
    NAMESPACE="$(namespace_for_env "$ENVIRONMENT")"
    AUTO_YES=false
    [[ "${3:-}" == "--yes" ]] && AUTO_YES=true

    if [[ "$AUTO_YES" != true ]]; then
      read -r -p "Uninstall release '${RELEASE_NAME}' in namespace '${NAMESPACE}'? [y/N] " reply
      [[ "$reply" =~ ^[Yy]$ ]] || { log_info "aborted, nothing changed."; exit 0; }
    fi

    log_info "uninstalling '${RELEASE_NAME}' from '${NAMESPACE}'..."
    helm uninstall "$RELEASE_NAME" -n "$NAMESPACE"
    log_info "done. minikube itself and any other namespaces are untouched."
    ;;

  --wipe-cluster)
    CONTEXT="$(kubectl config current-context 2>/dev/null || echo minikube)"
    log_warn "this destroys the ENTIRE local minikube cluster (context: '${CONTEXT}') -"
    log_warn "every namespace, every release, anything else you have running on it."
    read -r -p "Type the context name ('${CONTEXT}') to confirm: " reply
    if [[ "$reply" != "$CONTEXT" ]]; then
      log_info "confirmation did not match, aborted. Nothing changed."
      exit 0
    fi

    log_info "deleting minikube cluster..."
    minikube delete
    log_info "done."
    ;;

  --help|-h|"")
    usage
    [[ "${1:-}" == "" ]] && exit 1
    exit 0
    ;;

  *)
    log_error "unknown argument: $1"
    usage
    exit 1
    ;;
esac
