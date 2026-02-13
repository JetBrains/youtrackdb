#!/bin/bash
set -e

MIRROR_DOMAIN="${1:?Usage: setup.sh <domain>}"
echo "Configuring maven mirror for domain: ${MIRROR_DOMAIN}"

# --- 1. System Updates & Install Tools ---
echo "Updating system..."
export DEBIAN_FRONTEND=noninteractive
apt-get update && apt-get upgrade -y
apt-get install -y openjdk-21-jre-headless curl jq ufw fail2ban caddy

# --- 2. Create User & Directory ---
echo "Setting up reposilite user..."
useradd -r -m -d /opt/reposilite -s /bin/bash reposilite

# --- 3. Download Reposilite (pinned version with integrity check) ---
REPOSILITE_VERSION="3.5.26"
REPOSILITE_SHA256="252be9e0a6bff7c024784c6b480d9608da2a310026a429652113721f3864cea3"
REPOSILITE_URL="https://maven.reposilite.com/releases/com/reposilite/reposilite/${REPOSILITE_VERSION}/reposilite-${REPOSILITE_VERSION}-all.jar"

echo "Downloading Reposilite ${REPOSILITE_VERSION}..."
curl -fL "$REPOSILITE_URL" -o /opt/reposilite/reposilite.jar

echo "Verifying checksum..."
echo "${REPOSILITE_SHA256}  /opt/reposilite/reposilite.jar" | sha256sum -c -

# --- 4. Configure Systemd Service ---
echo "Creating systemd service..."
cat <<EOF > /etc/systemd/system/reposilite.service
[Unit]
Description=Reposilite Maven Repository
After=network.target

[Service]
User=reposilite
Group=reposilite
WorkingDirectory=/opt/reposilite
ExecStart=/usr/bin/java -Xmx512M -jar reposilite.jar --port 8080

SuccessExitStatus=143
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# --- 5. Configure Caddy as TLS reverse proxy ---
echo "Configuring Caddy reverse proxy for ${MIRROR_DOMAIN}..."
cat <<EOF > /etc/caddy/Caddyfile
${MIRROR_DOMAIN} {
  reverse_proxy localhost:8080
}
EOF

systemctl enable caddy

# --- 6. Fix Permissions & Start Service ---
chown -R reposilite:reposilite /opt/reposilite
systemctl daemon-reload
systemctl enable reposilite

# --- 7. Security Configuration ---
echo "Configuring Firewall (UFW) and Fail2Ban..."

# A. Configure Fail2Ban
cat <<EOF > /etc/fail2ban/jail.local
[sshd]
enabled = true
port = ssh
filter = sshd
logpath = /var/log/auth.log
maxretry = 3
bantime = 3600
EOF
systemctl enable fail2ban
systemctl restart fail2ban

# B. Configure UFW (Firewall)
ufw --force reset
ufw default deny incoming
ufw default allow outgoing

# Allow SSH
ufw allow 22/tcp

# Allow HTTPS (port 443) - Caddy handles TLS termination
ufw allow 443/tcp

# Allow HTTP (port 80) for ACME challenge / redirect
ufw allow 80/tcp

# Enable firewall
echo "y" | ufw enable

echo "Setup Complete. Reposilite on :8080, Caddy reverse proxy on :443."