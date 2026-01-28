# TestFlows Runner Setup Guide

This document describes how to deploy and maintain the TestFlows GitHub Hetzner Runners orchestrator
for managing self-hosted GitHub Actions runners.

## Overview

[TestFlows GitHub Hetzner Runners](https://github.com/testflows/testflows-github-hetzner-runners) is
an external service that automatically creates and destroys Hetzner Cloud servers as GitHub Actions
self-hosted runners based on job demand.

**Key benefits:**
- Zero cost when no jobs are running (no idle servers)
- Automatic scaling based on queued jobs
- Uses base Ubuntu images with label-based setup scripts
- Maven cache persisted via Hetzner volume mounts
- Simple systemd-based deployment

## Prerequisites

Before starting, ensure you have:

1. **Hetzner Cloud account** with API access
2. **GitHub repository** or organization admin access

## Hetzner Cloud Token Setup

Create an API token for Hetzner Cloud:

1. Go to https://console.hetzner.cloud/
2. Select your project
3. Navigate to **Security** → **API Tokens**
4. Click **Generate API Token**
5. Give it **Read & Write** permissions
6. Copy the token (it's only shown once)

Set up the token for CLI usage:

```bash
# Option 1: Create a named context (recommended)
hcloud context create youtrackdb
# Paste your token when prompted

# Verify it works
hcloud server list

# Option 2: Set as environment variable (for scripts/Packer)
export HCLOUD_TOKEN="your-token-here"
```

## Step 1: Build the Orchestrator Image (One-time)

Build the orchestrator server image using Packer:

```bash
cd .github/workflows
packer init testflows-orchestrator.pkr.hcl
packer build testflows-orchestrator.pkr.hcl
```

This creates a snapshot named `testflows-orchestrator` with:
- Python 3 and pip
- testflows.github.hetzner.runners package
- systemd service template
- Configuration directory at `/etc/github-hetzner-runners/`
- Setup scripts for runners at `/etc/github-hetzner-runners/scripts/`
- UFW firewall configured (SSH only)

## Step 2: Create Server from Snapshot

Using Hetzner Cloud Console or CLI:

```bash
# Find the snapshot ID
hcloud image list --type snapshot | grep testflows-orchestrator

# Create server (cpx22 is sufficient - 3 vCPU, 4GB RAM)
hcloud server create \
  --name testflows-orchestrator \
  --type cpx22 \
  --image <snapshot-id> \
  --location nbg1 \
  --ssh-key <your-ssh-key>
```

**Note:** The orchestrator image has UFW firewall pre-configured, allowing only SSH (port 22)
inbound
and all outbound traffic.

**Estimated cost:** ~4-8 EUR/month

## Step 3: Configure the Service

SSH into the server and configure the environment:

```bash
ssh root@<server-ip>

# Copy example config
cp /etc/github-hetzner-runners/env.example /etc/github-hetzner-runners/env

# Edit with your tokens
nano /etc/github-hetzner-runners/env
```

### Required Configuration

```bash
# GitHub Personal Access Token
# Required scopes: repo, admin:org (for org runners) or repo admin (for repo runners)
GITHUB_TOKEN=ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# Repository in format owner/repo
GITHUB_REPOSITORY=JetBrains/youtrackdb

# Hetzner Cloud API Token
HCLOUD_TOKEN=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Optional Configuration

```bash
# Maximum concurrent runners (default: 4)
MAX_RUNNERS=4
```

### Workflow Labels

Runners are configured per-job using workflow labels. The integration tests workflow uses:

```yaml
runs-on: [ self-hosted, in-nbg1, '${{ matrix.arch == ''x86'' && ''type-cpx42'' || ''type-cax31'' }}',
           'image-${{ matrix.arch == ''x86'' && ''x86-system'' || ''arm-system'' }}-ubuntu-24.04',
           docker, firewall, git, mcache, volume-cache ]
```

| Label                       | Purpose                               |
|-----------------------------|---------------------------------------|
| `self-hosted`               | Required for self-hosted runners      |
| `in-nbg1`                   | Hetzner datacenter location           |
| `type-cpx42` / `type-cax31` | Server type (x64 / arm64)             |
| `image-*-ubuntu-24.04`      | Base Ubuntu image to use              |
| `docker`                    | Run Docker installation script        |
| `firewall`                  | Run UFW firewall configuration script |
| `git`                       | Run Git installation script           |
| `mcache`                    | Run Maven cache mount script          |
| `volume-cache`              | Attach persistent volume for caching  |

### Setup Scripts

The orchestrator image includes setup scripts in `/etc/github-hetzner-runners/scripts/`:

| Script             | Purpose                                              |
|--------------------|------------------------------------------------------|
| `docker.sh`        | Installs Docker Engine, creates ubuntu user          |
| `firewall.sh`      | Configures UFW (deny incoming, allow SSH)            |
| `git.sh`           | Installs Git                                         |
| `mcache.sh`        | Mounts Maven cache from volume to `~/.m2/repository` |
| `setup.sh`         | Basic setup (ubuntu user, fail2ban)                  |
| `startup-x64.sh`   | GitHub Actions runner setup for x64                  |
| `startup-arm64.sh` | GitHub Actions runner setup for arm64                |

These scripts run on runner startup based on the workflow labels.

The public key (`id_rsa.pub`) will be automatically added to runner servers when they are created.

## Step 4: Start the Service

```bash
# Enable service to start on boot
systemctl enable github-hetzner-runners

# Start the service
systemctl start github-hetzner-runners

# Check status
systemctl status github-hetzner-runners

# View logs
journalctl -u github-hetzner-runners -f
```

## Verifying the Setup

### Check Service Status

```bash
# On the orchestrator server
systemctl status github-hetzner-runners
journalctl -u github-hetzner-runners --since "10 minutes ago"
```

### Trigger a Test Run

1. Go to GitHub repository > Actions
2. Run `maven-integration-tests-pipeline.yml` manually
3. Watch Hetzner console for new servers being created
4. Check GitHub > Settings > Actions > Runners for new runners appearing

### Expected Behavior

1. When a job is queued, TestFlows creates a server (~1-2 min startup)
2. Setup scripts run based on workflow labels (Docker, firewall, Git, Maven cache)
3. Server registers as a self-hosted runner
4. Job runs on the runner
5. After job completes, server is deleted immediately

## Scaling Behavior

| Scenario             | Behavior                           |
|----------------------|------------------------------------|
| No jobs queued       | No servers running (zero cost)     |
| 1 x64 job queued     | 1 cpx42 server created             |
| 2 x64 + 2 arm64 jobs | 2 cpx42 + 2 cax31 servers (max 4)  |
| Jobs > max runners   | Jobs queue until runners available |

## Troubleshooting

### Runners not appearing

1. Check GitHub token has correct permissions
2. Verify repository name is correct
3. Check Hetzner token is valid

```bash
journalctl -u github-hetzner-runners -n 100
```

### Servers created but jobs not running

1. Check setup scripts completed successfully (review server logs)
2. Verify firewall allows outbound connections
3. Check if runner registration succeeded

### Service won't start

1. Check environment file syntax: `cat /etc/github-hetzner-runners/env`
2. Verify all required variables are set
3. Check systemd logs: `journalctl -u github-hetzner-runners -e`

### Maven cache not working

1. Verify `volume-cache` label is in the workflow
2. Check if `/mnt/cache` directory exists on the runner
3. Verify `mcache` label is included

## Maintenance

### Updating TestFlows

```bash
/opt/testflows-runners/bin/pip install --upgrade testflows.github.hetzner.runners
systemctl restart github-hetzner-runners
```

### Rotating Tokens

1. Generate new token in GitHub/Hetzner
2. Update `/etc/github-hetzner-runners/env`
3. Restart service: `systemctl restart github-hetzner-runners`

### Updating Setup Scripts

To update the setup scripts:

1. Modify scripts in `.github/workflows/testflows-orchestrator/scripts/`
2. Rebuild the orchestrator image: `packer build testflows-orchestrator.pkr.hcl`
3. Create a new server from the new snapshot
4. Migrate the `/etc/github-hetzner-runners/env` configuration

### Rebuilding Orchestrator Image

If you need to rebuild the orchestrator image with updates:

```bash
cd .github/workflows
packer build testflows-orchestrator.pkr.hcl
# Then create new server from new snapshot and migrate config
```

## Required Secrets Summary

### GitHub Repository Secrets

| Secret | Purpose |
|--------|---------|
| `HCLOUD_TOKEN` | Hetzner API (for Packer image builds) |

### Orchestrator Server Environment

| Variable            | Purpose                                             |
|---------------------|-----------------------------------------------------|
| `GITHUB_TOKEN`      | Runner registration (PAT with repo/admin:org scope) |
| `GITHUB_REPOSITORY` | Target repository                                   |
| `HCLOUD_TOKEN`      | Server creation/deletion                            |
| `MAX_RUNNERS`       | Maximum concurrent runners (optional, default: 4)   |
