#!/bin/bash
set -euo pipefail

IMAGE_NAME="ytdb-devcontainer"
MAX_AGE_SECONDS=86400  # 1 day
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Derive a unique container name from the worktree directory name so that
# multiple instances on different worktrees can run simultaneously.
WORKTREE_SUFFIX="$(basename "${SCRIPT_DIR}")"
CONTAINER_NAME="ytdb-claude-sandbox-${WORKTREE_SUFFIX}"

# Build args (single source of truth — no external config file)
TZ="${TZ:-Europe/Berlin}"
CLAUDE_CODE_VERSION="latest"
GIT_DELTA_VERSION="0.18.2"
ZSH_IN_DOCKER_VERSION="1.2.0"

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
    # Safety check: refuse to rebuild if the image is in use by running containers
    running_containers=$(docker ps -q --filter "ancestor=${IMAGE_NAME}")
    if [ -n "${running_containers}" ]; then
      echo "WARNING: Image '${IMAGE_NAME}' is stale ($(( age / 3600 ))h old) but cannot be rebuilt"
      echo "         because it is currently used by running container(s):"
      docker ps --filter "ancestor=${IMAGE_NAME}" --format "         - {{.Names}} ({{.ID}}, up {{.RunningFor}})"
      echo "         Reusing the existing image. Stop the above container(s) and re-run to rebuild."
    else
      echo "Image '${IMAGE_NAME}' is older than 1 day ($(( age / 3600 ))h). Rebuilding..."
      docker rmi "${IMAGE_NAME}" || true
      build_image
    fi
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

# Resolve git worktree main repo for mounting.
# Git worktrees store a .git *file* (not directory) whose gitdir points to
# <main-repo>/.git/worktrees/<name>.  Inside the container only /workspace
# is mounted, so the main repo is unreachable.  We mount the entire main repo
# at its host absolute path so that (a) the gitdir reference works and (b) main
# repo working-tree files are accessible.
MAIN_REPO_MOUNT=()
MAIN_REPO_PATH=""
if [ -f "${SCRIPT_DIR}/.git" ]; then
  # Git worktree — .git is a file pointing to the main repo
  WORKTREE_GITDIR=$(sed 's/^gitdir: //' "${SCRIPT_DIR}/.git" | tr -d '[:space:]')
  if [ ! -d "${WORKTREE_GITDIR}" ]; then
    echo "ERROR: Git worktree gitdir '${WORKTREE_GITDIR}' not found."
    exit 1
  fi
  GIT_COMMON_DIR=$(cd "${WORKTREE_GITDIR}" && cd "$(cat commondir)" && pwd)
  MAIN_REPO_PATH="$(dirname "${GIT_COMMON_DIR}")"
  echo "Git worktree detected. Mounting main repo: ${MAIN_REPO_PATH}"
  MAIN_REPO_MOUNT=(-v "${MAIN_REPO_PATH}:${MAIN_REPO_PATH}:delegated")
elif [ ! -d "${SCRIPT_DIR}/.git" ]; then
  echo "ERROR: '${SCRIPT_DIR}' is not a git repository."
  exit 1
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

  # Main repo mount (resolved above, empty for regular clones)
  "${MAIN_REPO_MOUNT[@]}"

  # Persistent volumes
  -v "ytdb-claude-bashhistory:/commandhistory"
  -v "ytdb-claude-config:/home/node/.claude"

  # GitHub CLI config (persistent volume, user runs 'gh auth login' once inside container)
  -v "ytdb-claude-ghconfig:/home/node/.config/gh"

  # Environment variables
  -e "NODE_OPTIONS=--max-old-space-size=4096"
  -e "CLAUDE_CONFIG_DIR=/home/node/.claude"
  -e "POWERLEVEL9K_DISABLE_GITSTATUS=true"
  -e "JAVA_HOME=/usr/lib/jvm/temurin-21-jdk"
  -e "JETBRAINS_MCP_PORT=${JETBRAINS_MCP_PORT:-64342}"
  -e "HOST_UID=$(id -u)"
  -e "HOST_GID=$(id -g)"
  -e "MAIN_REPO_PATH=${MAIN_REPO_PATH}"
  -e "YTDB_DEV_CONTAINER=1"
)

run_cmd+=(
  "${IMAGE_NAME}"
  /workspace/.devcontainer/entrypoint.sh
)

# Clean up container-specific settings on the host side.  The entrypoint has its
# own trap, but it won't fire on SIGKILL / docker rm -f / host crash.  By
# removing exec we keep this shell alive so the EXIT trap always runs.
cleanup() {
  rm -f "${SCRIPT_DIR}/.claude/settings.local.json" "${SCRIPT_DIR}/.mcp.json"
}
trap cleanup EXIT

# Wrap with systemd-inhibit if available to prevent sleep
if command -v systemd-inhibit >/dev/null 2>&1; then
  echo "Using systemd-inhibit to prevent sleep."
  systemd-inhibit --what=sleep --who="ytdb-devcontainer" --why="Dev container session active" --mode=block "${run_cmd[@]}"
else
  "${run_cmd[@]}"
fi
