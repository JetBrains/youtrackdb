#!/bin/bash
set -euo pipefail

HOST_UID="${HOST_UID:-1000}"
HOST_GID="${HOST_GID:-1000}"

# Adjust node user UID/GID to match host user if they differ.
# This ensures bind-mounted files and volumes are accessible.
if [ "$HOST_UID" != "1000" ] || [ "$HOST_GID" != "1000" ]; then
    echo "Adjusting node user UID:GID to ${HOST_UID}:${HOST_GID}..."
    groupmod -o -g "$HOST_GID" node
    usermod -o -u "$HOST_UID" -g "$HOST_GID" node
    chown -R "$HOST_UID:$HOST_GID" /home/node /commandhistory /home/node/.config/gh
fi

# Create convenience symlink to main repo when running from a worktree
if [ -n "${MAIN_REPO_PATH:-}" ]; then
    ln -sfn "${MAIN_REPO_PATH}" /main-repo
    chown -h node:node /main-repo
fi

# Run lifecycle scripts
runuser -u node -- /usr/local/bin/post-create.sh

if ! /usr/local/bin/init-firewall.sh; then
    echo ""
    echo "========================================"
    echo "  ERROR: FIREWALL SETUP FAILED"
    echo "  Container is NOT network-restricted!"
    echo "  Aborting for safety."
    echo "========================================"
    echo ""
    exit 1
fi

# Drop privileges and start interactive shell.
# Set HOME explicitly — the container starts as root so HOME=/root,
# but all user files (Claude config, gh config) are under /home/node.
export HOME=/home/node

# Configure git to use gh as credential helper (HTTPS-only, no SSH keys needed)
runuser -u node -- git config --global credential.helper '!gh auth git-credential'

# Propagate host user's git identity into the container
if [ -n "${GIT_USER_NAME:-}" ]; then
    runuser -u node -- git config --global user.name "$GIT_USER_NAME"
fi
if [ -n "${GIT_USER_EMAIL:-}" ]; then
    runuser -u node -- git config --global user.email "$GIT_USER_EMAIL"
fi

# Hint if GitHub CLI is not authenticated
if ! runuser -u node -- gh auth status >/dev/null 2>&1; then
    echo ""
    echo "========================================"
    echo "  GitHub is not configured."
    echo ""
    echo "  1. Create a fine-grained PAT at:"
    echo "     github.com/settings/personal-access-tokens"
    echo ""
    echo "  2. Authenticate the GitHub CLI:"
    echo "     gh auth login"
    echo ""
    echo "  3. Add GitHub MCP for Claude Code:"
    echo "     claude mcp add-json github \\"
    echo "       --scope user \\"
    echo "       '{\"type\":\"http\",\"url\":\"https://api.githubcopilot.com/mcp\",\"headers\":{\"Authorization\":\"Bearer <PAT>\"}}'"
    echo "========================================"
    echo ""
fi

# Clean up container-specific settings on exit so they don't leak to the host.
trap 'rm -f /workspace/.claude/settings.local.json /workspace/.mcp.json' EXIT
runuser -u node -- zsh
