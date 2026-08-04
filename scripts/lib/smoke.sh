#!/usr/bin/env bash
# Smoke tests reuse the existing, maintained postman/ collection via Newman rather than a second,
# parallel set of curl assertions that would drift from it over time. Requires Node/npx - see
# docs/setup.md. Each run registers a fresh user (the collection's username is Date.now()-based,
# see CLAUDE.md's Postman gotcha) - fine for a smoke test, just not idempotent against a fixed user.
set -euo pipefail

# run_smoke_tests <base_url>
run_smoke_tests() {
  local base_url="$1"
  local collection="${REPO_ROOT}/postman/claude-full-learning.postman_collection.json"

  if ! command -v npx >/dev/null 2>&1; then
    log_error "npx not found - Newman smoke tests require Node.js (see docs/setup.md)."
    return 1
  fi
  if [[ ! -f "$collection" ]]; then
    log_error "Postman collection not found at ${collection}"
    return 1
  fi

  log_info "running smoke tests against ${base_url}"
  npx --yes newman run "$collection" --env-var "baseUrl=${base_url}"
}
