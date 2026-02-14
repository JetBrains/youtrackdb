#!/bin/bash
set -euo pipefail

HOST_UID="${HOST_UID:-1000}"
HOST_GID="${HOST_GID:-1000}"

# Adjust node user UID/GID to match host user if they differ.
# This ensures bind-mounted files (SSH keys, agent socket, workspace) are accessible.
if [ "$HOST_UID" != "1000" ] || [ "$HOST_GID" != "1000" ]; then
    echo "Adjusting node user UID:GID to ${HOST_UID}:${HOST_GID}..."
    groupmod -o -g "$HOST_GID" node
    usermod -o -u "$HOST_UID" -g "$HOST_GID" node
    chown -R "$HOST_UID:$HOST_GID" /home/node /commandhistory
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

# Drop privileges and start interactive shell
exec runuser -u node -- zsh
