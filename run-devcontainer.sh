#!/bin/bash
set -euo pipefail

IMAGE_NAME="ytdb-devcontainer"
CONTAINER_NAME="ytdb-claude-sandbox"
MAX_AGE_SECONDS=86400  # 1 day
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Build args matching devcontainer.json
TZ="${TZ:-America/Los_Angeles}"
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
    # BSD/macOS date — strip fractional seconds, timezone suffixes
    local cleaned
    cleaned=$(echo "$timestamp" | sed 's/[.+].*//' | sed 's/Z$//' | sed 's/T/ /')
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

# Assemble docker run command
run_cmd=(
  docker run
  --rm
  -it
  --name "${CONTAINER_NAME}"
  --cap-add=NET_ADMIN
  --cap-add=NET_RAW
  -u node
  -w /workspace

  # Workspace bind mount
  -v "${SCRIPT_DIR}:/workspace:delegated"

  # Persistent volumes
  -v "ytdb-claude-bashhistory:/commandhistory"
  -v "ytdb-claude-config:/home/node/.claude"

  # Environment variables
  -e "NODE_OPTIONS=--max-old-space-size=4096"
  -e "CLAUDE_CONFIG_DIR=/home/node/.claude"
  -e "POWERLEVEL9K_DISABLE_GITSTATUS=true"
  -e "JAVA_HOME=/usr/lib/jvm/temurin-21-jdk"
  -e "ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY:-}"
  -e "JETBRAINS_MCP_PORT=${JETBRAINS_MCP_PORT:-64342}"
)

# GitHub CLI config (read-only, only if present on host)
if [ -d "${HOME}/.config/gh" ]; then
  run_cmd+=(-v "${HOME}/.config/gh:/home/node/.config/gh:ro")
else
  echo "WARNING: ~/.config/gh not found. GitHub CLI will not be authenticated."
fi

run_cmd+=(
  "${IMAGE_NAME}"

  # Entry: run lifecycle scripts, then drop into zsh
  zsh -c '
    /usr/local/bin/post-create.sh
    if ! sudo /usr/local/bin/init-firewall.sh; then
      echo ""
      echo "========================================"
      echo "  ERROR: FIREWALL SETUP FAILED"
      echo "  Container is NOT network-restricted!"
      echo "  Aborting for safety."
      echo "========================================"
      echo ""
      exit 1
    fi
    exec zsh
  '
)

# Wrap with systemd-inhibit if available to prevent sleep
if command -v systemd-inhibit >/dev/null 2>&1; then
  echo "Using systemd-inhibit to prevent sleep."
  exec systemd-inhibit --what=sleep --who="ytdb-devcontainer" --why="Dev container session active" --mode=block "${run_cmd[@]}"
else
  exec "${run_cmd[@]}"
fi
