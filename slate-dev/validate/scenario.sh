#!/usr/bin/env bash
# Slate acceptance scenario (ExecPlan M4).
#
# Drives a full thread-weaving pass headlessly against a toy repo:
#   /slate on -> two parallel recon threads -> a third synthesis thread fed
#   ONLY the two episodes via context -> combined answer.
#
# Asserts:
#   1. pi exits 0 and the final answer contains the combined planted facts
#   2. >= 3 episode files exist, each with all 6 contract sections
#   3. at least one worker session file proves episode injection
#      ("Context from prior episodes" in its first user message)
#
# Idempotent: each run uses a fresh temp workspace.

set -u
EXT="$(cd "$(dirname "$0")/../.." && pwd)/.pi/extensions/slate/index.ts"
WS="$(mktemp -d /tmp/slate-scenario.XXXXXX)"
trap 'rm -rf "$WS"' EXIT

fail() { echo "FAIL: $1"; exit 1; }

mkdir -p "$WS/src"
echo "project codename: BLUEFIN" > "$WS/a.txt"
echo "the demo port number is 9944" > "$WS/src/b.txt"

cd "$WS"
OUT=$(pi -p -e "$EXT" "/slate on" \
  "Step 1: dispatch TWO NEW threads in parallel in one message: thread 'codename' finds the project codename in this repo; thread 'port' finds the demo port number. Step 2: after both episodes return, dispatch a THIRD new thread named 'synth', passing BOTH episode ids via the context parameter, with task: 'Using ONLY the injected context and no tools, output the single line CODENAME:PORT built from the codename and port you were given.' Step 3: report the synth thread's answer line verbatim." 2>&1)
RC=$?

[ $RC -eq 0 ] || fail "pi exited $RC: $(echo "$OUT" | tail -3)"
echo "$OUT" | grep -q "BLUEFIN:9944" || fail "combined answer BLUEFIN:9944 not found in output: $(echo "$OUT" | tail -5)"

EP_DIR="$WS/.pi/slate/episodes"
EP_COUNT=$(ls "$EP_DIR"/*.md 2>/dev/null | wc -l)
[ "$EP_COUNT" -ge 3 ] || fail "expected >= 3 episodes, found $EP_COUNT"

for f in "$EP_DIR"/*.md; do
  for section in "## Intent" "## Actions Taken" "## Key Findings" "## Artifacts Changed" "## Open Issues" "## Handoff Notes"; do
    grep -q "^$section" "$f" || fail "$(basename "$f") missing section '$section'"
  done
done

grep -rq "Context from prior episodes" "$WS/.pi/slate/threads/" || fail "no worker session shows injected episode context"

echo "PASS: $EP_COUNT episodes, combined answer BLUEFIN:9944, composition proven"
