#!/usr/bin/env bash
# One-time setup script for the persistent Grafana + InfluxDB VM.
# Run on a fresh CX22 Ubuntu 24.04 server.
#
# Usage:
#   scp -r perf/grafana/ root@<IP>:/root/grafana/
#   ssh root@<IP> 'bash /root/grafana/setup-grafana-vm.sh'
#
# After running, note the InfluxDB token and server IP for GitHub secrets:
#   INFLUXDB_URL=http://<IP>:8086
#   INFLUXDB_TOKEN=<token from .env file>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Installing Docker ==="
if ! command -v docker &>/dev/null; then
  apt-get update -qq
  apt-get install -y -qq ca-certificates curl gnupg
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
    | gpg --dearmor -o /etc/apt/keyrings/docker.gpg
  chmod a+r /etc/apt/keyrings/docker.gpg
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
    https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    | tee /etc/apt/sources.list.d/docker.list > /dev/null
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
echo "Docker version: $(docker --version)"

echo "=== Configuring UFW firewall ==="
apt-get install -y -qq ufw
ufw --force reset
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp    # SSH
ufw allow 80/tcp    # HTTP (Caddy → redirect to HTTPS)
ufw allow 443/tcp   # HTTPS (Caddy → Grafana)
ufw allow 8086/tcp  # InfluxDB (token-protected)
ufw --force enable
ufw status verbose

echo "=== Generating secrets ==="
ENV_FILE="$SCRIPT_DIR/.env"
if [ ! -f "$ENV_FILE" ]; then
  INFLUXDB_ADMIN_PASSWORD=$(openssl rand -base64 24)
  INFLUXDB_ADMIN_TOKEN=$(openssl rand -base64 32)
  GRAFANA_ADMIN_PASSWORD=$(openssl rand -base64 16)

  cat > "$ENV_FILE" <<EOF
INFLUXDB_ADMIN_PASSWORD=$INFLUXDB_ADMIN_PASSWORD
INFLUXDB_ADMIN_TOKEN=$INFLUXDB_ADMIN_TOKEN
GRAFANA_ADMIN_PASSWORD=$GRAFANA_ADMIN_PASSWORD
EOF
  chmod 600 "$ENV_FILE"
  echo "Generated .env file with secrets"
else
  echo ".env file already exists, skipping secret generation"
fi

echo "=== Starting services ==="
docker compose up -d

echo "=== Waiting for services to be healthy ==="
for i in $(seq 1 30); do
  if curl -sf http://localhost:8086/health >/dev/null 2>&1 && \
     curl -sf http://localhost:3000/api/health >/dev/null 2>&1; then
    echo "All services healthy"
    break
  fi
  sleep 2
done

echo ""
echo "========================================="
echo "  Setup complete!"
echo "========================================="
echo ""
echo "Grafana:  http://$(hostname -I | awk '{print $1}')"
echo "InfluxDB: http://$(hostname -I | awk '{print $1}'):8086"
echo ""
echo "Add these GitHub secrets:"
echo "  INFLUXDB_URL=http://$(hostname -I | awk '{print $1}'):8086"
# shellcheck disable=SC1090
source "$ENV_FILE"
echo "  INFLUXDB_TOKEN=$INFLUXDB_ADMIN_TOKEN"
echo ""
echo "Grafana admin password: $GRAFANA_ADMIN_PASSWORD"
echo "========================================="
