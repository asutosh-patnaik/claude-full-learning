#!/usr/bin/env bash
# Stop hook: runs the test suite plus the JaCoCo coverage gate once per turn
# (./mvnw verify) and, on failure, feeds the failure back to Claude instead of
# letting the turn end silently broken or with coverage quietly regressed.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

INPUT="$(cat)"
STOP_HOOK_ACTIVE="$(printf '%s' "$INPUT" | jq -r '.stop_hook_active // false' 2>/dev/null)"

# Already looped once because a prior run of this same hook blocked the stop.
# Don't loop forever if the suite is broken in a way this turn can't fix.
if [ "$STOP_HOOK_ACTIVE" = "true" ]; then
  exit 0
fi

cd "$PROJECT_ROOT" || exit 0

TEST_OUTPUT="$(./mvnw verify 2>&1)"
STATUS=$?

if [ "$STATUS" -eq 0 ]; then
  exit 0
fi

RELEVANT="$(printf '%s\n' "$TEST_OUTPUT" | grep -E 'Tests run|ERROR|FAIL|BUILD FAILURE|Rule violated' | tail -n 80)"
if [ -z "$RELEVANT" ]; then
  RELEVANT="$(printf '%s\n' "$TEST_OUTPUT" | tail -n 80)"
fi

REASON="./mvnw verify failed after this turn's changes (test failure or code-coverage regression). Fix it before finishing:

${RELEVANT}"

jq -n --arg reason "$REASON" '{decision: "block", reason: $reason}'
exit 0
