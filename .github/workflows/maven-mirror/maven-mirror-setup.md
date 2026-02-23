---
source_files:
  - .github/workflows/maven-mirror/maven-mirror.pkr.hcl
  - .github/workflows/maven-mirror/setup.sh
  - .github/workflows/settings.xml
last_synced_commit: 2d5822220f
related_docs:
  - docs/ci-cd-diagram.md
---

# Maven Mirror (Reposilite) Setup Guide

Self-hosted Maven Central mirror running on Hetzner Cloud using Reposilite, fronted by Caddy for automatic TLS.

## Architecture

```
CI (GitHub Actions) --> Caddy (TLS :443) --> Reposilite (:8080) --> Maven Central (proxy)
```

- **Reposilite 3.5.26** - Maven repository manager, proxies Maven Central
- **Caddy** - Reverse proxy with automatic Let's Encrypt TLS
- **Hetzner Cloud** - `cax11` ARM64 instance in `nbg1`, built from Packer snapshot
- **Hetzner Floating IP** - Static IPv4 so DNS stays stable across server rebuilds
- **Domain**: `maven.youtrackdb.io` (A record pointing to the floating IP)

## What the Packer Image Includes

The Packer template (`maven-mirror.pkr.hcl`) and setup script (`setup.sh`) install:

- OpenJDK 21 (headless)
- Reposilite 3.5.26 (checksum-verified) as a systemd service on port 8080
- Caddy as a TLS reverse proxy on ports 80/443
- UFW firewall (allows only SSH, HTTP, HTTPS)
- Fail2Ban (SSH brute-force protection)

## Building the Packer Snapshot

```bash
export HCLOUD_TOKEN="your-hetzner-api-token"
cd .github/workflows/maven-mirror
packer init maven-mirror.pkr.hcl
packer build maven-mirror.pkr.hcl
```

This creates a Hetzner snapshot labeled `application=reposilite, type=maven-mirror`.

## Post-Installation Steps (Manual)

After creating a server from the snapshot, complete these steps.

### 1. Allocate a Hetzner Floating IP (one-time)

A floating IP ensures the DNS record never changes when the server is rebuilt.

```bash
hcloud floating-ip create --type ipv4 --home-location nbg1 --description "maven-mirror"
```

Note the IP address returned. Cost: ~4 EUR/month.

### 2. Create DNS A Record (one-time)

In your DNS provider, add an A record:

| Type | Host | Value |
|------|------|-------|
| A Record | `maven` | `<floating-ip>` |

### 3. Assign Floating IP to the Server

```bash
hcloud floating-ip assign <floating-ip-id> <server-name>
```

### 4. Configure the Server to Accept the Floating IP

SSH into the server and configure netplan:

```bash
ssh root@<server-direct-ip>

cat <<EOF > /etc/netplan/60-floating-ip.yaml
network:
  version: 2
  ethernets:
    eth0:
      addresses:
        - <floating-ip>/32
EOF

chmod 600 /etc/netplan/60-floating-ip.yaml
netplan apply
```

Verify the floating IP appears on the interface:

```bash
ip addr show eth0 | grep inet
```

### 5. Start Services

Services are enabled but may not start automatically on first boot from a snapshot:

```bash
systemctl start reposilite
systemctl start caddy
```

Caddy will automatically obtain a Let's Encrypt TLS certificate once DNS resolves to the floating IP. Check logs with:

```bash
journalctl -u caddy -f
```

### 6. Create Reposilite Admin Token

Stop the service and run interactively to create the initial admin token:

```bash
systemctl stop reposilite
sudo -u reposilite java -Xmx512M -jar /opt/reposilite/reposilite.jar --port 8080
```

In the Reposilite console that appears, run:

```
token-generate admin m
```

Save the generated secret. Then press `Ctrl+C` and restart via systemd:

```bash
systemctl start reposilite
```

### 7. Configure Maven Central Proxy

Log in to the Reposilite dashboard at `https://maven.youtrackdb.io` with the admin credentials.

1. Go to **Settings** > **Maven**
2. Under the **maven-central** repository, find **Mirrored repositories**
3. Add `https://repo1.maven.org/maven2/`
4. Optionally enable storing proxied artifacts for caching
5. Save

### 8. Set Repository Visibility to Private

In the dashboard settings, change the `maven-central` repository type from **public** to **private** so that reads require authentication.

### 9. Create CI Read-Only Token

In the Reposilite console (accessible via the Console tab in the dashboard):

```
token-generate ci-user
route-add ci-user / r
```

Save the generated secret.

### 10. Set GitHub Actions Secrets

```bash
gh secret set MAVEN_MIRROR_USERNAME --body "ci-user"
gh secret set MAVEN_MIRROR_PASSWORD --body "<ci-user-secret>"
```

These are referenced in `.github/workflows/settings.xml` as `${env.MAVEN_MIRROR_USERNAME}` and `${env.MAVEN_MIRROR_PASSWORD}`.

### 11. Update settings.xml Mirror URL

Update the mirror URL in `.github/workflows/settings.xml` to point to the new mirror:

```xml
<mirror>
  <id>ytdb-maven-central</id>
  <mirrorOf>central</mirrorOf>
  <name>YTDB mirror of Maven Central</name>
  <url>https://maven.youtrackdb.io/maven-central</url>
</mirror>
```

### 12. Verify

```bash
# Test artifact resolution through the proxy
curl -u "ci-user:<secret>" https://maven.youtrackdb.io/maven-central/org/apache/maven/maven-core/maven-metadata.xml

# Test that unauthenticated access is denied (should return 401)
curl -v https://maven.youtrackdb.io/maven-central/org/apache/maven/maven-core/maven-metadata.xml
```

## Server Rebuild Procedure

When rebuilding the server from a new Packer snapshot:

1. Create a new server from the latest snapshot
2. Reassign the floating IP: `hcloud floating-ip assign <floating-ip-id> <new-server-name>`
3. SSH in and configure netplan for the floating IP (step 4 above)
4. Repeat steps 5-9 (services, tokens, proxy config)

DNS does not need to change since the floating IP stays the same.

## Cost

| Resource | Cost |
|----------|------|
| `cax11` instance (2 vCPU ARM64, 4 GB RAM) | ~4 EUR/month |
| Floating IPv4 | ~4 EUR/month |
| Snapshot storage | ~0.01 EUR/GB/month |
| **Total** | **~8 EUR/month** |
