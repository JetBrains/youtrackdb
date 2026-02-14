#!/bin/bash
set -euo pipefail

IMAGE_NAME="ytdb-devcontainer"
MAX_AGE_SECONDS=86400  # 1 day
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Derive a unique container name from the worktree directory name so that
# multiple instances on different worktrees can run simultaneously.
WORKTREE_SUFFIX="$(basename "${SCRIPT_DIR}")"
CONTAINER_NAME="ytdb-claude-sandbox-${WORKTREE_SUFFIX}"

# Build args — read from devcontainer.json (single source of truth)
TZ="${TZ:-Europe/Berlin}"
DEVCONTAINER_JSON="${SCRIPT_DIR}/.devcontainer/devcontainer.json"
CLAUDE_CODE_VERSION=$(jq -r '.build.args.CLAUDE_CODE_VERSION' "$DEVCONTAINER_JSON")
GIT_DELTA_VERSION=$(jq -r '.build.args.GIT_DELTA_VERSION' "$DEVCONTAINER_JSON")
ZSH_IN_DOCKER_VERSION=$(jq -r '.build.args.ZSH_IN_DOCKER_VERSION' "$DEVCONTAINER_JSON")

# Cross-platform date-to-epoch (GNU and BSD/macOS)
date_to_epoch() {
  local timestamp="$1"
  if date -d "2000-01-01" +%s >/dev/null 2>&1; then
    # GNU date
    date -d "${timestamp}" +%s
  else
    # BSD/macOS date — strip fractional seconds and timezone suffixes
    # Handles: 2024-02-14T10:30:00.123Z, +00:00, -05:00
    local cleaned
    cleaned=$(echo "$timestamp" | sed 's/T/ /' | sed 's/[.][0-9]*//' | sed 's/[Zz]$//' | sed 's/[+-][0-9][0-9]:[0-9][0-9]$//')
    date -j -f "%Y-%m-%d %H:%M:%S" "${cleaned}" +%s
  fi
}

build_image() {
  echo "Building dev container image..."
  docker build \
    --build-arg "TZ=${TZ}" \
    --build-arg "CLAUDE_CODE_VERSION=${CLAUDE_CODE_VERSION}" \
    --build-arg "GIT_DELTA_VERSION=${GIT_DELTA_VERSION}" \
    --build-arg "ZSH_IN_DOCKER_VERSION=${ZSH_IN_DOCKER_VERSION}" \
    -t "${IMAGE_NAME}" \
    -f "${SCRIPT_DIR}/.devcontainer/Dockerfile" \
    "${SCRIPT_DIR}/.devcontainer"
}

# Check if image exists
if ! docker image inspect "${IMAGE_NAME}" >/dev/null 2>&1; then
  echo "Image '${IMAGE_NAME}' not found."
  build_image
else
  # Check image age
  created=$(docker image inspect --format '{{.Created}}' "${IMAGE_NAME}")
  created_epoch=$(date_to_epoch "${created}")
  now_epoch=$(date +%s)
  age=$(( now_epoch - created_epoch ))

  if [ "${age}" -ge "${MAX_AGE_SECONDS}" ]; then
    echo "Image '${IMAGE_NAME}' is older than 1 day ($(( age / 3600 ))h). Rebuilding..."
    docker rmi "${IMAGE_NAME}" || true
    build_image
  else
    echo "Image '${IMAGE_NAME}' is fresh ($(( age / 3600 ))h old). Reusing."
  fi
fi

# Stop and remove any existing container with the same name
if docker container inspect "${CONTAINER_NAME}" >/dev/null 2>&1; then
  echo "Removing existing container '${CONTAINER_NAME}'..."
  docker rm -f "${CONTAINER_NAME}"
fi

# Limit container to 85% of host CPU capacity
CPU_LIMIT=$(nproc | awk '{printf "%.2f", $1 * 0.85}')
echo "CPU limit: ${CPU_LIMIT} cores (85% of $(nproc))"

# Resolve git worktree parent directory for mounting.
# Git worktrees store a .git *file* (not directory) whose gitdir points to
# <parent-repo>/.git/worktrees/<name>.  Inside the container only /workspace
# is mounted, so the parent .git is unreachable.  We mount it at the same
# absolute host path so the gitdir reference works inside the container.
GIT_WORKTREE_MOUNT=()
if [ -f "${SCRIPT_DIR}/.git" ]; then
  # Git worktree — .git is a file pointing to the parent repo
  WORKTREE_GITDIR=$(sed 's/^gitdir: //' "${SCRIPT_DIR}/.git" | tr -d '[:space:]')
  if [ ! -d "${WORKTREE_GITDIR}" ]; then
    echo "ERROR: Git worktree gitdir '${WORKTREE_GITDIR}' not found."
    exit 1
  fi
  GIT_COMMON_DIR=$(cd "${WORKTREE_GITDIR}" && cd "$(cat commondir)" && pwd)
  echo "Git worktree detected. Mounting parent .git: ${GIT_COMMON_DIR}"
  GIT_WORKTREE_MOUNT=(-v "${GIT_COMMON_DIR}:${GIT_COMMON_DIR}:delegated")
elif [ ! -d "${SCRIPT_DIR}/.git" ]; then
  echo "ERROR: '${SCRIPT_DIR}' is not a git repository."
  exit 1
fi

# Forward SSH agent socket if available (needed for passphrase-protected keys)
SSH_AGENT_MOUNT=()
if [ -n "${SSH_AUTH_SOCK:-}" ] && [ -S "$SSH_AUTH_SOCK" ]; then
  echo "SSH agent detected, forwarding into container"
  SSH_AGENT_MOUNT=(
    -v "${SSH_AUTH_SOCK}:/tmp/ssh-agent.sock"
    -e "SSH_AUTH_SOCK=/tmp/ssh-agent.sock"
  )
else
  echo "WARNING: No SSH agent found. Passphrase-protected keys won't work inside the container."
fi

# Assemble docker run command
run_cmd=(
  docker run
  --rm
  -it
  --name "${CONTAINER_NAME}"
  --cap-add=NET_ADMIN
  --cap-add=NET_RAW
  --cpus="${CPU_LIMIT}"
  -u root
  -w /workspace

  # Workspace bind mount
  -v "${SCRIPT_DIR}:/workspace:delegated"

  # Git worktree parent .git directory (resolved above, empty for regular clones)
  "${GIT_WORKTREE_MOUNT[@]}"

  # Persistent volumes
  -v "ytdb-claude-bashhistory:/commandhistory"
  -v "ytdb-claude-config:/home/node/.claude"

  # SSH keys (read-only, for git push over SSH)
  -v "${HOME}/.ssh:/home/node/.ssh:ro"

  # SSH agent forwarding (for passphrase-protected keys)
  ${SSH_AGENT_MOUNT[@]+"${SSH_AGENT_MOUNT[@]}"}

  # Environment variables
  -e "NODE_OPTIONS=--max-old-space-size=4096"
  -e "CLAUDE_CONFIG_DIR=/home/node/.claude"
  -e "POWERLEVEL9K_DISABLE_GITSTATUS=true"
  -e "JAVA_HOME=/usr/lib/jvm/temurin-21-jdk"
  -e "JETBRAINS_MCP_PORT=${JETBRAINS_MCP_PORT:-64342}"
  -e "HOST_UID=$(id -u)"
  -e "HOST_GID=$(id -g)"
)

# Claude user config (needed for MCP servers, settings; auth stored in persistent volume)
if [ -f "${HOME}/.claude.json" ]; then
  run_cmd+=(-v "${HOME}/.claude.json:/home/node/.claude.json:ro")
fi

run_cmd+=(
  "${IMAGE_NAME}"
  /workspace/.devcontainer/entrypoint.sh
)

# Wrap with systemd-inhibit if available to prevent sleep
if command -v systemd-inhibit >/dev/null 2>&1; then
  echo "Using systemd-inhibit to prevent sleep."
  exec systemd-inhibit --what=sleep --who="ytdb-devcontainer" --why="Dev container session active" --mode=block "${run_cmd[@]}"
else
  exec "${run_cmd[@]}"
fi
