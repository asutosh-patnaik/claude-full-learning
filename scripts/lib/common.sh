#!/usr/bin/env bash
# Shared by every scripts/*.sh - namespace naming, logging, and the one env-name validation rule,
# so they can't silently drift between scripts (e.g. one script deriving "claude-full-learning-dev"
# and another "dev-claude-full-learning").
set -euo pipefail

RELEASE_NAME="claude-full-learning"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHART_DIR="${REPO_ROOT}/helm/claude-full-learning"

log_info()  { echo "[INFO]  $*"; }
log_warn()  { echo "[WARN]  $*" >&2; }
log_error() { echo "[ERROR] $*" >&2; }

# Every environment this chart supports - see helm/claude-full-learning/values-<env>.yaml.
valid_environment() {
  case "$1" in
    dev|test|production) return 0 ;;
    *) return 1 ;;
  esac
}

require_environment_arg() {
  local env="${1:-}"
  if [[ -z "$env" ]]; then
    log_error "environment argument is required (dev|test|production)"
    exit 1
  fi
  if ! valid_environment "$env"; then
    log_error "unknown environment '$env' - must be one of: dev, test, production"
    exit 1
  fi
}

# Matches values-<env>.yaml's own namespace.name - keep both in sync if either changes.
namespace_for_env() {
  echo "${RELEASE_NAME}-$1"
}
