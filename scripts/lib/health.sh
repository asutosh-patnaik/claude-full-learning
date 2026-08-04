#!/usr/bin/env bash
# Retry-loop health check, shared by deploy.sh and rollback.sh - a passed `kubectl rollout status`
# doesn't guarantee the app is reachable through the Service/Ingress path specifically yet.
set -euo pipefail

# wait_for_health <base_url> [max_attempts] [delay_seconds]
wait_for_health() {
  local base_url="$1"
  local max_attempts="${2:-10}"
  local delay="${3:-3}"

  for attempt in $(seq 1 "$max_attempts"); do
    if curl -sf -o /dev/null "${base_url}/actuator/health"; then
      log_info "health check passed on attempt ${attempt}/${max_attempts}"
      return 0
    fi
    log_info "health check attempt ${attempt}/${max_attempts} failed, retrying in ${delay}s..."
    sleep "$delay"
  done

  log_error "health check failed after ${max_attempts} attempts against ${base_url}/actuator/health"
  return 1
}
