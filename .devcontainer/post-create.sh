#!/bin/bash
set -euo pipefail

# Copy container-specific Claude Code settings as local override
mkdir -p /workspace/.claude
cp /usr/local/share/claude-settings.json /workspace/.claude/settings.local.json

# Resolve git directory (supports both regular repos and worktrees)
GIT_DIR="$(git -C /workspace rev-parse --git-dir)"
# Make absolute if relative
[[ "$GIT_DIR" = /* ]] || GIT_DIR="/workspace/$GIT_DIR"

# Add settings.local.json to git exclude
EXCLUDE_FILE="$GIT_DIR/info/exclude"
mkdir -p "$(dirname "$EXCLUDE_FILE")"
grep -qxF '.claude/settings.local.json' "$EXCLUDE_FILE" 2>/dev/null || echo '.claude/settings.local.json' >> "$EXCLUDE_FILE"

# Install pre-push hook to block pushes inside the container
HOOKS_DIR="$GIT_DIR/hooks"
mkdir -p "$HOOKS_DIR"
cat > "$HOOKS_DIR/pre-push" << 'HOOK'
#!/bin/bash
if [ "${DEVCONTAINER:-}" = "true" ]; then
    echo "ERROR: git push is blocked inside the dev container."
    echo "Review changes on the host and push from there."
    exit 1
fi
HOOK
chmod +x "$HOOKS_DIR/pre-push"
