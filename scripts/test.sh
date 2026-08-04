#!/usr/bin/env bash
# Default: the same full test+coverage-gate run documented in CLAUDE.md and used by the Stop hook.
# --smoke <url>: just the Newman portion (shared with deploy.sh via lib/smoke.sh), for re-checking
# an already-running deployment without a full redeploy.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
source "${SCRIPT_DIR}/lib/smoke.sh"

usage() {
  cat <<EOF
Usage: $(basename "$0") [--smoke <base_url>]

No args:      runs ./mvnw verify (full unit+integration test suite plus the JaCoCo coverage gate).
--smoke <url>: runs just the Newman smoke tests against an already-deployed URL, e.g.
               $(basename "$0") --smoke http://localhost:18080
EOF
}

if [[ "${1:-}" == "--smoke" ]]; then
  [[ -n "${2:-}" ]] || { log_error "--smoke requires a base URL"; usage; exit 1; }
  run_smoke_tests "$2"
  exit 0
fi

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

log_info "running ./mvnw verify..."
cd "$REPO_ROOT"
./mvnw verify
