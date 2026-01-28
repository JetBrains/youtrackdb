set -x

{
    echo "Create and configure ubuntu user"
    adduser ubuntu --disabled-password --gecos ""

    echo "%wheel   ALL=(ALL:ALL) NOPASSWD:ALL" >> /etc/sudoers
    addgroup wheel
    usermod -aG wheel ubuntu
    usermod -aG sudo ubuntu
}

{
    echo "Install fail2ban"
    apt-get update
    apt-get install --yes --no-install-recommends \
        fail2ban

    echo "Launch fail2ban"
    systemctl start fail2ban
}

{
    echo "Install Docker Engine"
    apt-get -y update
    apt-get -y install ca-certificates curl gnupg

    CACHE_DIR="${CACHE_DIR:-/mnt/cache}"

    # Setup cache directory if /mnt/cache exists
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

    if [ -n "$CACHE_DIR_DOCKER" ] && [ -f "$CACHE_DIR_DOCKER/docker.gpg" ]; then
        echo "Using cached Docker GPG key"
        cp "$CACHE_DIR_DOCKER/docker.gpg" "$DOCKER_GPG_PATH"
    else
        echo "Downloading Docker GPG key"
        curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o "$DOCKER_GPG_PATH"
        if [ -n "$CACHE_DIR_DOCKER" ]; then
            cp "$DOCKER_GPG_PATH" "$CACHE_DIR_DOCKER/docker.gpg"
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
        echo \
        "deb [arch=$(dpkg --print-architecture) signed-by=$DOCKER_GPG_PATH] https://download.docker.com/linux/ubuntu \
        $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" | \
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
