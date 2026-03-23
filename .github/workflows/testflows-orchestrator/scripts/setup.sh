#!/bin/bash
set -euxo pipefail

trap 'echo "Setup failed at line $LINENO"' ERR

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

    echo "Add Docker's official GPG key"
    install -m 0755 -d /etc/apt/keyrings
    DOCKER_GPG_PATH="/etc/apt/keyrings/docker.asc"
    curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o "$DOCKER_GPG_PATH"
    chmod a+r "$DOCKER_GPG_PATH"

    echo "Set up Docker's repository"
    ARCH=$(dpkg --print-architecture)
    # shellcheck source=/dev/null
    . /etc/os-release
    CODENAME="${UBUNTU_CODENAME:-$VERSION_CODENAME}"
    echo "deb [arch=$ARCH signed-by=$DOCKER_GPG_PATH] https://download.docker.com/linux/ubuntu $CODENAME stable" | \
    tee /etc/apt/sources.list.d/docker.list > /dev/null

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

    echo "Add NodeSource GPG key"
    install -m 0755 -d /etc/apt/keyrings
    NODE_GPG_PATH="/etc/apt/keyrings/nodesource.gpg"
    curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o "$NODE_GPG_PATH"
    chmod a+r "$NODE_GPG_PATH"

    echo "Set up NodeSource repository"
    NODE_MAJOR=22
    echo "deb [signed-by=$NODE_GPG_PATH] https://deb.nodesource.com/node_$NODE_MAJOR.x nodistro main" | \
    tee /etc/apt/sources.list.d/nodesource.list > /dev/null

    echo "Install Node.js"
    apt-get -y update
    apt-get -y install nodejs

    echo "Node.js version: $(node --version)"
    echo "npm version: $(npm --version)"
}
