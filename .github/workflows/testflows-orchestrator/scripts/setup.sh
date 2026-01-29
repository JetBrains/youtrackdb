#!/bin/bash
set -euxo pipefail

trap 'echo "Setup failed at line $LINENO"' ERR

# Set default cache directory
if [ -z "${CACHE_DIR:-}" ]; then
    CACHE_DIR="/mnt/cache"
fi

# Setup APT package cache if volume is available (must be done before any apt-get commands)
{
    if [ -d "$CACHE_DIR" ]; then
        echo "Setting up APT package cache"
        APT_CACHE_DIR="$CACHE_DIR/apt/archives"
        mkdir -p "$APT_CACHE_DIR"

        # Configure APT to use the cache directory
        cat > /etc/apt/apt.conf.d/00-cache-config <<EOF
Dir::Cache::Archives "$APT_CACHE_DIR";
EOF

        # Also cache the package lists
        APT_LISTS_DIR="$CACHE_DIR/apt/lists"
        mkdir -p "$APT_LISTS_DIR"

        # Bind mount the lists directory for faster apt-get update
        if ! mountpoint -q /var/lib/apt/lists; then
            mount --bind "$APT_LISTS_DIR" /var/lib/apt/lists
        fi

        echo "APT cache configured:"
        echo "  - Package archives: $APT_CACHE_DIR"
        echo "  - Package lists: $APT_LISTS_DIR"
    else
        echo "No cache volume available, using default APT cache"
    fi
}

# Initial package list update
apt-get update

{
    echo "Create and configure ubuntu user"
    id -u ubuntu &>/dev/null || adduser ubuntu --disabled-password --gecos ""
    getent group wheel &>/dev/null || addgroup wheel
    getent group docker &>/dev/null || addgroup docker
    SUDOERS_FILE="/etc/sudoers.d/wheel-nopasswd"
    SUDOERS_TMP="${SUDOERS_FILE}.tmp"
    echo "%wheel ALL=(ALL:ALL) NOPASSWD:ALL" > "$SUDOERS_TMP"
    chmod 440 "$SUDOERS_TMP"
    if visudo -c -f "$SUDOERS_TMP"; then
        mv "$SUDOERS_TMP" "$SUDOERS_FILE"
    else
        rm -f "$SUDOERS_TMP"
        echo "ERROR: Invalid sudoers syntax, file not installed"
        exit 1
    fi
    usermod -aG wheel ubuntu
    usermod -aG sudo ubuntu
    usermod -aG docker ubuntu
}
{
    echo "Install fail2ban"
    apt-get install -y --no-install-recommends \
        fail2ban

    echo "Launch fail2ban"
    systemctl start fail2ban
}

{
  # 1. Install UFW if it's missing (standard Hetzner images have it, but safety first)
  apt-get install -y ufw

  # 2. Reset UFW to default state (deny incoming, allow outgoing)
  ufw --force reset
  ufw default deny incoming
  ufw default allow outgoing

  # 3. Allow SSH
  ufw allow 22/tcp

  # 4. Enable the firewall immediately
  ufw --force enable

  # 5. Verify status (Logs to syslog, helpful for debugging)
  ufw status verbose
}
{
    echo "Install Docker Engine"
    apt-get -y install ca-certificates curl gnupg

    # Setup cache directory if it exists
    if [ -d "$CACHE_DIR" ]; then
        CACHE_DIR_DOCKER="$CACHE_DIR/docker"
        mkdir -p "$CACHE_DIR_DOCKER"
        echo "Using cache directory: $CACHE_DIR_DOCKER"
    else
        CACHE_DIR_DOCKER=""
        echo "No cache directory available, proceeding without caching"
    fi

    echo "Add Docker's official GPG key"
    install -m 0755 -d /etc/apt/keyrings
    DOCKER_GPG_PATH="/etc/apt/keyrings/docker.asc"

    DOCKER_GPG_CACHE="$CACHE_DIR_DOCKER/docker.asc"
    if [ -n "$CACHE_DIR_DOCKER" ] && [ -f "$DOCKER_GPG_CACHE" ]; then
        echo "Using cached Docker GPG key"
        cp "$DOCKER_GPG_CACHE" "$DOCKER_GPG_PATH"
    else
        echo "Downloading Docker GPG key"
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o "$DOCKER_GPG_PATH"
        if [ -n "$CACHE_DIR_DOCKER" ]; then
            cp "$DOCKER_GPG_PATH" "$DOCKER_GPG_CACHE"
        fi
    fi
    chmod a+r "$DOCKER_GPG_PATH"

    echo "Set up Docker's repository"
    DOCKER_LIST_PATH="/etc/apt/sources.list.d/docker.list"
    if [ -n "$CACHE_DIR_DOCKER" ] && [ -f "$CACHE_DIR_DOCKER/docker.list" ]; then
        echo "Using cached Docker repository list"
        cp "$CACHE_DIR_DOCKER/docker.list" "$DOCKER_LIST_PATH"
    else
        echo "Creating Docker repository list"
        ARCH=$(dpkg --print-architecture)
        # shellcheck source=/dev/null
        . /etc/os-release
        CODENAME="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
        echo "deb [arch=$ARCH signed-by=$DOCKER_GPG_PATH] https://download.docker.com/linux/ubuntu $CODENAME stable" | \
        tee "$DOCKER_LIST_PATH" > /dev/null
        if [ -n "$CACHE_DIR_DOCKER" ]; then
            cp "$DOCKER_LIST_PATH" "$CACHE_DIR_DOCKER/docker.list"
        fi
    fi

    echo "Install Docker Engine and containerd"
    apt-get -y update
    apt-get -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

    echo "Add ubuntu user to docker group"
    usermod -aG docker ubuntu
}
{
   echo "Setup build tools: git"
   apt-get -y install git
}
{
    echo "Install Node.js LTS"

    # Setup cache directory if it exists
    if [ -d "$CACHE_DIR" ]; then
        CACHE_DIR_NODE="$CACHE_DIR/node"
        mkdir -p "$CACHE_DIR_NODE"
        echo "Using cache directory: $CACHE_DIR_NODE"
    else
        CACHE_DIR_NODE=""
        echo "No cache directory available, proceeding without caching"
    fi

    echo "Add NodeSource GPG key"
    NODE_GPG_PATH="/etc/apt/keyrings/nodesource.asc"

    NODE_GPG_CACHE="${CACHE_DIR_NODE}/nodesource.asc"
    if [ -n "$CACHE_DIR_NODE" ] && [ -f "$NODE_GPG_CACHE" ]; then
        echo "Using cached NodeSource GPG key"
        cp "$NODE_GPG_CACHE" "$NODE_GPG_PATH"
    else
        echo "Downloading NodeSource GPG key"
        curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o "$NODE_GPG_PATH"
        if [ -n "$CACHE_DIR_NODE" ]; then
            cp "$NODE_GPG_PATH" "$NODE_GPG_CACHE"
        fi
    fi
    chmod a+r "$NODE_GPG_PATH"

    echo "Set up NodeSource repository"
    NODE_MAJOR=22
    NODE_LIST_PATH="/etc/apt/sources.list.d/nodesource.list"
    if [ -n "$CACHE_DIR_NODE" ] && [ -f "$CACHE_DIR_NODE/nodesource.list" ]; then
        echo "Using cached NodeSource repository list"
        cp "$CACHE_DIR_NODE/nodesource.list" "$NODE_LIST_PATH"
    else
        echo "Creating NodeSource repository list"
        echo "deb [signed-by=$NODE_GPG_PATH] https://deb.nodesource.com/node_$NODE_MAJOR.x nodistro main" | \
        tee "$NODE_LIST_PATH" > /dev/null
        if [ -n "$CACHE_DIR_NODE" ]; then
            cp "$NODE_LIST_PATH" "$CACHE_DIR_NODE/nodesource.list"
        fi
    fi

    echo "Install Node.js"
    apt-get -y update
    apt-get -y install nodejs

    echo "Node.js version: $(node --version)"
    echo "npm version: $(npm --version)"
}
{
    echo "Setup build tool caches"

    if [ -d "$CACHE_DIR" ]; then
        # Verify ubuntu user exists before operating on home directory
        if ! id ubuntu &>/dev/null; then
            echo "ERROR: ubuntu user does not exist. Run the user setup block first."
            exit 1
        fi

        UBUNTU_HOME="/home/ubuntu"

        # Maven cache setup
        MAVEN_CACHE="$CACHE_DIR/maven"
        mkdir -p "$MAVEN_CACHE" "${UBUNTU_HOME}/.m2/repository"

        # Bind mount Maven cache
        if ! grep -qF "$MAVEN_CACHE ${UBUNTU_HOME}/.m2/repository" /etc/fstab; then
            echo "$MAVEN_CACHE ${UBUNTU_HOME}/.m2/repository none bind 0 0" >> /etc/fstab
        fi
        if ! mountpoint -q "${UBUNTU_HOME}/.m2/repository"; then
            mount --bind "$MAVEN_CACHE" "${UBUNTU_HOME}/.m2/repository"
        fi

        # Set Maven cache ownership for ubuntu user (non-recursive to avoid slow operation on large caches)
        chown ubuntu:ubuntu "${UBUNTU_HOME}/.m2"
        chown ubuntu:ubuntu "${UBUNTU_HOME}/.m2/repository"
        chown ubuntu:ubuntu "$MAVEN_CACHE"

        # npm cache setup
        NPM_CACHE="$CACHE_DIR/npm"
        mkdir -p "$NPM_CACHE" "${UBUNTU_HOME}/.npm"

        # Bind mount npm cache
        if ! grep -qF "$NPM_CACHE ${UBUNTU_HOME}/.npm" /etc/fstab; then
            echo "$NPM_CACHE ${UBUNTU_HOME}/.npm none bind 0 0" >> /etc/fstab
        fi
        if ! mountpoint -q "${UBUNTU_HOME}/.npm"; then
            mount --bind "$NPM_CACHE" "${UBUNTU_HOME}/.npm"
        fi

        # Set npm cache ownership for ubuntu user
        chown ubuntu:ubuntu "${UBUNTU_HOME}/.npm"
        chown ubuntu:ubuntu "$NPM_CACHE"

        echo "Build tool caches mounted from volume:"
        echo "  - $MAVEN_CACHE → ${UBUNTU_HOME}/.m2/repository"
        echo "  - $NPM_CACHE → ${UBUNTU_HOME}/.npm"
    else
        echo "No cache volume available, proceeding without build tool caching"
    fi
}