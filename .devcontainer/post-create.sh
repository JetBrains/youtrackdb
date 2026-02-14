#!/bin/bash
set -euo pipefail

# Copy container-specific Claude Code settings as local override
mkdir -p /workspace/.claude
cp /usr/local/share/claude-settings.json /workspace/.claude/settings.local.json

# Resolve git directory (supports both regular repos and worktrees)
GIT_DIR="$(git -C /workspace rev-parse --git-dir)"
# Make absolute if relative
[[ "$GIT_DIR" = /* ]] || GIT_DIR="/workspace/$GIT_DIR"

EXCLUDE_FILE="$GIT_DIR/info/exclude"
mkdir -p "$(dirname "$EXCLUDE_FILE")"

# Add generated files to git exclude
for entry in '.claude/settings.local.json' '.mcp.json'; do
    grep -qxF "$entry" "$EXCLUDE_FILE" 2>/dev/null || echo "$entry" >> "$EXCLUDE_FILE"
done

# Generate .mcp.json with JetBrains MCP pointing to the Docker host
# The host IP is the default gateway from inside the container
HOST_IP=$(ip route | awk '/default/ {print $3; exit}')
JETBRAINS_MCP_PORT="${JETBRAINS_MCP_PORT:-64342}"

if [ -n "$HOST_IP" ]; then
    cat > /workspace/.mcp.json << MCPEOF
{
  "mcpServers": {
    "jetbrains": {
      "type": "sse",
      "url": "http://${HOST_IP}:${JETBRAINS_MCP_PORT}/sse"
    }
  }
}
MCPEOF
    echo "JetBrains MCP configured at http://${HOST_IP}:${JETBRAINS_MCP_PORT}/sse"
else
    echo "WARNING: Could not detect host IP. JetBrains MCP not configured."
fi
