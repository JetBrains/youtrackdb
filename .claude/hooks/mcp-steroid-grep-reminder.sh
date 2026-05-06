#!/bin/bash
# PreToolUse hook (matcher: Grep): rate-limited reminder that mcp-steroid PSI
# is the right tool for reference-accuracy Java symbol questions.
#
# Fires at most once per 5 minutes per session. Silent when mcp-steroid is
# unreachable (no point nagging when PSI isn't an option) or when the
# rate-limit window hasn't elapsed.
#
# State file: /tmp/mcp-steroid-grep-reminder-<claude_pid>.txt
# Keyed by the root `claude` process PID (walking up the process tree, same
# pattern as ~/.claude/statusline-command.sh) so concurrent sessions don't
# share a window.
#
# Probed URL is read from ~/.claude.json (mcpServers["localhost-6315"].url),
# matching mcp-steroid-probe.sh, so this works in containerized installs that
# reach the IDE via host.docker.internal. The MCP server registry is
# user-global; the rules + this hook + settings are project-scoped.

set -u

CONFIG_FILE="$HOME/.claude.json"
DEFAULT_URL="http://localhost:6315/"
RATE_LIMIT_SECONDS=300

# --- Liveness probe ---------------------------------------------------------

url="$DEFAULT_URL"
if command -v jq >/dev/null 2>&1 && [ -f "$CONFIG_FILE" ]; then
  configured_url=$(jq -r '.mcpServers["localhost-6315"].url // empty' "$CONFIG_FILE" 2>/dev/null)
  if [ -n "$configured_url" ]; then
    url="$configured_url"
  fi
fi

curl -s --connect-timeout 1 -o /dev/null "$url" 2>/dev/null
exit_code=$?

# Same exit-code interpretation as mcp-steroid-probe.sh: 7 (no listener) and
# 28 (timeout) mean NOT reachable; everything else proves a listener exists.
if [ "$exit_code" -eq 7 ] || [ "$exit_code" -eq 28 ]; then
  exit 0
fi

# --- Per-session state file -------------------------------------------------

claude_pid=""
pid=$$
while [ "$pid" -gt 1 ] 2>/dev/null; do
  comm=$(cat /proc/$pid/comm 2>/dev/null || echo "")
  if [ "$comm" = "claude" ]; then claude_pid=$pid; break; fi
  pid=$(awk '/^PPid:/{print $2}' /proc/$pid/status 2>/dev/null || echo "1")
done
if [ -z "$claude_pid" ]; then claude_pid="$PPID"; fi

state_file="/tmp/mcp-steroid-grep-reminder-${claude_pid}.txt"

# --- Rate limit -------------------------------------------------------------

now=$(date +%s)
if [ -f "$state_file" ]; then
  last=$(stat -c %Y "$state_file" 2>/dev/null || echo 0)
  if [ $((now - last)) -lt "$RATE_LIMIT_SECONDS" ]; then
    exit 0
  fi
fi

# --- Emit reminder ----------------------------------------------------------

touch "$state_file"

msg='mcp-steroid is reachable — for reference-accuracy Java symbol questions (callers/overrides/usages, type hierarchies), prefer PSI find-usages via steroid_execute_code over grep. Grep is still correct for filename globs, unique string literals, and orientation reads. See project CLAUDE.md § MCP Steroid (sub-sections "Grep vs PSI — when to switch" and "Loading discipline") for the full routing rules.'

if command -v jq >/dev/null 2>&1; then
  jq -n --arg msg "$msg" \
    '{hookSpecificOutput: {hookEventName: "PreToolUse", additionalContext: $msg}}'
else
  # Fallback: msg contains a double-quote inside the parenthetical, escape it.
  escaped=${msg//\"/\\\"}
  printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","additionalContext":"%s"}}\n' "$escaped"
fi
