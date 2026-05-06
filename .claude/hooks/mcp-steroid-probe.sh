#!/bin/bash
# SessionStart hook: probe mcp-steroid liveness.
#
# Emits JSON with hookSpecificOutput.additionalContext so Claude Code injects
# the IDE-availability status into the model's context at turn 1.
#
# The probed URL is read from ~/.claude.json (mcpServers["localhost-6315"].url)
# so this works in containerized installs where the IDE is reached via
# host.docker.internal rather than localhost. The MCP server registry is
# user-global; only the rules + this hook + settings are project-scoped.
# Falls back to localhost:6315 if the config or jq is unavailable.
#
# We only check whether anything answers HTTP at the configured URL. The model
# itself runs steroid_list_projects to verify cwd-vs-open-project alignment
# when reachable.

CONFIG_FILE="$HOME/.claude.json"
DEFAULT_URL="http://localhost:6315/"
url="$DEFAULT_URL"

if command -v jq >/dev/null 2>&1 && [ -f "$CONFIG_FILE" ]; then
  configured_url=$(jq -r '.mcpServers["localhost-6315"].url // empty' "$CONFIG_FILE" 2>/dev/null)
  if [ -n "$configured_url" ]; then
    url="$configured_url"
  fi
fi

curl -s --connect-timeout 2 -o /dev/null "$url" 2>/dev/null
exit_code=$?

# curl exit codes we care about:
#   0   connected, got an HTTP response (any status)
#   7   couldn't connect to host (nothing listening)
#   22  HTTP error (still proves a listener exists)
#   28  operation timeout (firewall DROP, slow listener, ambiguous)
#   52  empty reply (still proves a listener exists)
# Treat 7 and 28 as "NOT reachable"; everything else means a listener exists.

if [ "$exit_code" -ne 7 ] && [ "$exit_code" -ne 28 ]; then
  msg="mcp-steroid: reachable at $url — run steroid_list_projects before any symbol audit (PSI required for reference-accuracy audits per project CLAUDE.md § MCP Steroid)"
else
  msg="mcp-steroid: NOT reachable at $url — IDE control unavailable; symbol audits via grep with reference-accuracy caveats per project CLAUDE.md § MCP Steroid"
fi

# Emit hookSpecificOutput.additionalContext. Prefer jq for proper escaping;
# fall back to printf only if jq is unavailable (the message is fixed-text
# so direct interpolation is safe).
if command -v jq >/dev/null 2>&1; then
  jq -n --arg msg "$msg" \
    '{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: $msg}}'
else
  printf '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"%s"}}\n' "$msg"
fi
