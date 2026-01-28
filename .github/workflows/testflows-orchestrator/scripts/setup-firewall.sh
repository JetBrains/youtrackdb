set -x
{
  # 1. Install UFW if it's missing (standard Hetzner images have it, but safety first)
  apt-get update && apt-get install -y ufw

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
