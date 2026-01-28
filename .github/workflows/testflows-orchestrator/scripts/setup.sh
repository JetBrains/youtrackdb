#!/bin/bash
set -euxo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

{
    echo "Create and configure ubuntu user"
    id -u ubuntu &>/dev/null || adduser ubuntu --disabled-password --gecos ""
    getent group wheel &>/dev/null || addgroup wheel
    getent group docker &>/dev/null || addgroup docker
    echo "%wheel ALL=(ALL:ALL) NOPASSWD:ALL" > /etc/sudoers.d/wheel-nopasswd
    chmod 440 /etc/sudoers.d/wheel-nopasswd
    usermod -aG wheel ubuntu
    usermod -aG sudo ubuntu
    usermod -aG docker ubuntu
}
{
    echo "Install fail2ban"
    apt-get update
    apt-get install --yes --no-install-recommends \
        fail2ban

    echo "Launch fail2ban"
    systemctl start fail2ban
}

source "$SCRIPT_DIR/firewall.sh"
source "$SCRIPT_DIR/docker.sh"
source "$SCRIPT_DIR/git.sh"
source "$SCRIPT_DIR/mcache.sh"