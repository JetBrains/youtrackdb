#!/bin/bash
# PreToolUse hook (matcher: Write|Edit|mcp__.+__steroid_apply_patch):
# rate-limited reminder that the project's house style applies to the
# prose/code surface about to be written.
#
# Fires at most once per Claude session per tier (Tier A: Markdown;
# Tier B: Java/Kotlin) regardless of how many tool invocations match,
# so a writing burst sees the rules once early and then stays quiet.
#
# Why bash, not Python: the two existing PreToolUse hooks
# (mcp-steroid-grep-reminder.sh, mcp-steroid-probe.sh) are bash, the
# parsing surface here is small (one JSON read, path classification,
# one rate-limit decision), and staying on bash keeps the hook
# directory consistent for future readers. The script does call out to
# `python3 -c '…'` twice — once for JSON parsing when `jq` is missing,
# once for `realpath` when GNU `realpath -m` is unavailable on BSD
# coreutils — because reimplementing JSON parsing or path normalization
# in pure bash would be more fragile than borrowing the stdlib for two
# lines.
#
# State file: ${TMPDIR:-/tmp}/house-style-reminder-${session_id}.txt
# Lock file: ${TMPDIR:-/tmp}/house-style-reminder-${session_id}.lock
#
# Keyed by `session_id` from the hook input JSON (a top-level field on
# every PreToolUse hook input) rather than by Claude pid. `session_id`
# changes on /clear and on every fresh conversation, which is the
# logical-session boundary this once-per-session reminder cares about.
# Keying by Claude pid (the mcp-steroid-grep-reminder.sh precedent)
# would silently inherit state across /clear and suppress reminders the
# user expects after a fresh start. The pid keying is correct there
# because that hook uses a 5-minute time window — different semantics.
#
# The `${session_id}` suffix also satisfies the user-global Concurrent
# Agent File Isolation rule in ~/.claude/CLAUDE.md (every /tmp file
# carries a unique-per-process suffix so concurrent agents on the same
# host never collide on state).
#
# Matcher regex `Write|Edit|mcp__.+__steroid_apply_patch` is unanchored
# in settings.json on purpose: Claude Code's tool-name matcher is
# regex-based, and the `.+` middle segment covers every MCP server
# registry-key choice (~/.claude.json may key the server as
# localhost-6315, mcp-steroid, intellij, …). The hook's internal
# tool-name check uses an anchored variant (`^mcp__.+__steroid_apply_patch$`)
# so a hypothetical tool name like `Write_to_mcp__foo__steroid_apply_patch_log`
# would not be misclassified. Over-matching on the settings.json side is
# an acceptable risk for an informational reminder that no-ops on
# unrecognized tool inputs.
#
# State and lock files persist across the session by design: the
# per-tier rate-limit lives inside the state file, and a trap-cleanup
# on exit would erase the rate-limit memory and let every Write/Edit
# in the session re-fire the reminder. Orphan accumulation is bounded
# by the host's /tmp aging policy; the per-`session_id` keying also
# ensures one zero-byte/few-bytes pair per logical session, not per
# tool invocation.
#
# Fail-silent guards already cover: missing/empty `session_id` (falls
# back to `$$`), missing `tool_input.file_path`, empty `hunks: []`,
# non-JSON stdin, jq absent (Python fallback), GNU `realpath -m`
# absent (Python fallback), state-file read failure, `flock`-busy
# (lock holder will emit), and `flock` binary absent (race accepted
# at most one duplicate reminder). A non-writable `${TMPDIR:-/tmp}`
# would normally turn the fd-open at the lock-acquisition step into a
# fatal bash redirection error that also prints to stderr before any
# `2>/dev/null` on `exec` itself can suppress it; the hook therefore
# probes `test -w "$state_dir"` before the `exec 9>"$lock_file"`,
# exits 0 on the miss, and that branch joins the silent-no-op list
# with no stderr leak.

set -uo pipefail

# ---------- Stage 1: read stdin and parse the input JSON --------------

input_json=$(cat)

# Helper: extract a JSON field via jq first, Python fallback on jq miss
# or non-zero jq exit. Both helpers accept the JSON on stdin and emit
# the value (or empty) on stdout.
jq_or_python_get() {
  local filter="$1"
  local py_expr="$2"
  if command -v jq >/dev/null 2>&1; then
    local result
    result=$(printf '%s' "$input_json" | jq -r "$filter" 2>/dev/null)
    if [ "$result" != "null" ] && [ -n "$result" ]; then
      printf '%s' "$result"
      return 0
    elif [ "$result" = "null" ]; then
      # jq confirmed the field is absent / null. Skip the Python
      # fallback — its ~10–15 ms startup would add latency for no
      # gain. Empty output (`-n "$result"` false above) is still
      # ambiguous between miss and absent, so it falls through.
      return 0
    fi
  fi
  printf '%s' "$input_json" | python3 -c "$py_expr" 2>/dev/null
}

# ---------- Stage 2: extract session_id with $$ fallback --------------

session_id=$(jq_or_python_get \
  '.session_id // empty' \
  'import json,sys
try:
    d = json.load(sys.stdin)
    v = d.get("session_id") or ""
    sys.stdout.write(v)
except Exception:
    pass')

if [ -z "$session_id" ]; then
  # Fallback to hook PID. Sacrifices the rate-limit guarantee for this
  # one invocation (the state file becomes unique per call) but keeps
  # state-file paths from collapsing to `…-reminder-.txt`, which would
  # let two concurrent hooks read each other's state.
  session_id="$$"
fi

# ---------- Stage 3: extract target paths -----------------------------

tool_name=$(jq_or_python_get \
  '.tool_name // empty' \
  'import json,sys
try:
    d = json.load(sys.stdin)
    sys.stdout.write(d.get("tool_name") or "")
except Exception:
    pass')

paths_raw=""
if [[ "$tool_name" =~ ^mcp__.+__steroid_apply_patch$ ]]; then
  # apply-patch: enumerate hunks[].file_path (array of objects).
  paths_raw=$(jq_or_python_get \
    '[.tool_input.hunks[]?.file_path // empty] | unique | .[]' \
    'import json,sys
try:
    d = json.load(sys.stdin)
    hunks = (d.get("tool_input") or {}).get("hunks") or []
    seen = []
    for h in hunks:
        if not isinstance(h, dict):
            continue
        fp = h.get("file_path")
        if isinstance(fp, str) and fp and fp not in seen:
            seen.append(fp)
    sys.stdout.write("\n".join(seen))
except Exception:
    pass')
else
  # Write / Edit: single file_path on tool_input.
  paths_raw=$(jq_or_python_get \
    '.tool_input.file_path // empty' \
    'import json,sys
try:
    d = json.load(sys.stdin)
    fp = (d.get("tool_input") or {}).get("file_path")
    if isinstance(fp, str):
        sys.stdout.write(fp)
except Exception:
    pass')
fi

# No usable paths → nothing to remind about.
if [ -z "$paths_raw" ]; then
  exit 0
fi

# ---------- Stage 4: realpath-normalize each path ---------------------

normalize_path() {
  local p="$1"
  # Prefer GNU realpath -m (resolves without requiring the path to
  # exist). Fall back to a portable Python one-liner for BSD coreutils.
  if command -v realpath >/dev/null 2>&1; then
    local out
    out=$(realpath -m -- "$p" 2>/dev/null)
    if [ -n "$out" ]; then
      printf '%s' "$out"
      return 0
    fi
  fi
  python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$p" 2>/dev/null
}

normalized_paths=""
while IFS= read -r raw_path; do
  [ -z "$raw_path" ] && continue
  norm=$(normalize_path "$raw_path")
  [ -z "$norm" ] && continue
  normalized_paths+="$norm"$'\n'
done <<< "$paths_raw"

if [ -z "$normalized_paths" ]; then
  exit 0
fi

# ---------- Stage 5: blacklist check ----------------------------------

# Blacklist runs AFTER normalization so suffix matches work regardless
# of whether the tool passed an absolute path, a basename, or a
# repo-relative path. Each entry is a repo-relative suffix.
blacklist=(
  ".claude/output-styles/house-style.md"
  ".claude/skills/ai-tells/SKILL.md"
  ".claude/scripts/design-mechanical-checks.py"
  ".claude/scripts/tests/test_dsc_ai_tell.py"
)

is_blacklisted() {
  local path="$1"
  local entry
  for entry in "${blacklist[@]}"; do
    case "$path" in
      *"/$entry"|"$entry") return 0 ;;
    esac
  done
  return 1
}

# ---------- Stage 6: tier classification ------------------------------

# Walk every normalized non-blacklisted path; accumulate a tier set.
want_tier_a=0
want_tier_b=0
while IFS= read -r p; do
  [ -z "$p" ] && continue
  if is_blacklisted "$p"; then
    continue
  fi
  case "$p" in
    *.md)
      want_tier_a=1
      ;;
    *.java|*.kt)
      want_tier_b=1
      ;;
    *)
      # Silent extensions contribute nothing.
      :
      ;;
  esac
done <<< "$normalized_paths"

if [ "$want_tier_a" -eq 0 ] && [ "$want_tier_b" -eq 0 ]; then
  exit 0
fi

# ---------- Stage 7: flock-wrapped rate-limit check + emit ------------

state_dir="${TMPDIR:-/tmp}"
state_file="${state_dir}/house-style-reminder-${session_id}.txt"
lock_file="${state_dir}/house-style-reminder-${session_id}.lock"

# Reminder bodies. Each ≤500 chars; concatenated ≤1500 chars including
# the JSON envelope (validated by the test runner). The text cites
# conventions.md §1.5 by repo-relative path and names the six Tier-B
# section headings of house-style.md verbatim so a future rename in
# that file fails the §1.5 anchor-drift guard test before the pointer
# text silently rots.
tier_a_body='House style applies to this Markdown surface. See .claude/workflow/conventions.md §1.5 *Writing style for Markdown and prose artifacts* (canonical anchor) and the rule source at .claude/output-styles/house-style.md — BLUF lead, banned vocabulary, banned sentence patterns, banned analysis patterns, punctuation and typography, structural rules, document-shape rules.'

tier_b_body='House style AI-tell subset applies to code comments and Javadoc on this Java/Kotlin surface. See .claude/workflow/conventions.md §1.5 and the six sections in .claude/output-styles/house-style.md: § Orientation, § Plain language, § Banned vocabulary, § Banned sentence patterns, § Banned analysis patterns, § Em-dash discipline. At comment scale § Orientation bans out-of-file assumptions, not in-file terseness; § Plain language is word-choice only (common word, expand acronyms, no idioms).'

emit_reminder() {
  local fired_a="$1"
  local fired_b="$2"
  local msg=""
  if [ "$fired_a" = "1" ] && [ "$fired_b" = "1" ]; then
    msg="${tier_a_body}

${tier_b_body}"
  elif [ "$fired_a" = "1" ]; then
    msg="$tier_a_body"
  elif [ "$fired_b" = "1" ]; then
    msg="$tier_b_body"
  else
    return 0
  fi
  if command -v jq >/dev/null 2>&1; then
    jq -n --arg msg "$msg" \
      '{hookSpecificOutput: {hookEventName: "PreToolUse", additionalContext: $msg}}'
  else
    # Python fallback: emit valid JSON with proper escaping.
    MSG="$msg" python3 -c '
import json, os, sys
msg = os.environ.get("MSG", "")
out = {"hookSpecificOutput": {"hookEventName": "PreToolUse",
                              "additionalContext": msg}}
sys.stdout.write(json.dumps(out))
'
  fi
}

# Critical section: read state, decide which tiers to fire, update
# state, emit reminder — all under flock so two concurrent same-session
# invocations cannot both observe an empty state and both emit.
run_critical_section() {
  local already_a=0
  local already_b=0
  if [ -f "$state_file" ]; then
    grep -q '^A$' "$state_file" 2>/dev/null && already_a=1
    grep -q '^B$' "$state_file" 2>/dev/null && already_b=1
  fi

  local fire_a=0
  local fire_b=0
  [ "$want_tier_a" -eq 1 ] && [ "$already_a" -eq 0 ] && fire_a=1
  [ "$want_tier_b" -eq 1 ] && [ "$already_b" -eq 0 ] && fire_b=1

  if [ "$fire_a" -eq 0 ] && [ "$fire_b" -eq 0 ]; then
    return 0
  fi

  # Persist state — append the newly-fired tier letters.
  [ "$fire_a" -eq 1 ] && printf 'A\n' >> "$state_file"
  [ "$fire_b" -eq 1 ] && printf 'B\n' >> "$state_file"

  emit_reminder "$fire_a" "$fire_b"
}

# Use flock when available so the read-decide-write block is atomic
# across concurrent same-session hooks. On lock-acquisition failure
# (-n returns non-zero), exit 0 silently — the holder will fire the
# reminder, and racing to emit a second concurrent body would be
# duplicative noise. Systems without flock (rare) fall through and
# accept the race; the failure mode is at most one duplicate reminder,
# never blocking the underlying tool call.
if command -v flock >/dev/null 2>&1; then
  # Guard the fd-open against non-writable ${TMPDIR}: bash prints the
  # redirection error before `2>/dev/null` on `exec` itself can
  # suppress it, so probe the lock-dir writability first via
  # `test -w` (silent on failure) and skip the lock-and-emit path on
  # any miss. Exit 0 keeps the never-block-tool-execution contract
  # intact in restricted sandboxes (read-only TMPDIR, custom TMPDIR
  # override pointing at a missing directory, etc.) and the
  # test -w probe leaves no stderr noise behind.
  if [ ! -w "$state_dir" ]; then
    exit 0
  fi
  exec 9>"$lock_file"
  if flock -n 9; then
    run_critical_section
    flock -u 9
  fi
  exec 9>&-
else
  run_critical_section
fi

exit 0
