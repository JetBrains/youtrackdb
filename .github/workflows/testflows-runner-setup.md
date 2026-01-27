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
- Uses custom Packer-built images with pre-installed tools
- Simple systemd-based deployment

## Prerequisites

Before starting, ensure you have:

1. **Hetzner Cloud account** with API access
2. **GitHub repository** or organization admin access
3. **Runner images** already built (see `build-hetzner-images.yml` workflow)
4. **Firewall** configured (see `network.tf`)

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

This creates a snapshot named `testflows-orchestrator-YYYY-MM-DD-HHMM` with:
- Python 3 and pip
- testflows.github.hetzner.runners package
- systemd service template
- Configuration directory at `/etc/github-hetzner-runners/`

## Step 2: Create Server from Snapshot

Using Hetzner Cloud Console or CLI:

```bash
# Find the snapshot ID
hcloud image list --type snapshot | grep testflows-orchestrator

# Create server (CX23 is sufficient - 2 vCPU, 4GB RAM)
hcloud server create \
  --name testflows-orchestrator \
  --type cx23 \
  --image <snapshot-id> \
  --location nbg1 \
  --ssh-key <your-ssh-key> \
  --firewall testflows-orchestrator-protection
```

**Note:** The firewall `testflows-orchestrator-protection` allows SSH (port 22) and ICMP inbound,
and all outbound traffic. Create it first using `terraform apply` on `network.tf` if not already done.

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

# Snapshot names (created by build-hetzner-images.yml)
IMAGE_X64=github-runner-x86
IMAGE_ARM64=github-runner-arm

# Hetzner datacenter location
LOCATION=nbg1
```

### Server Type Configuration

Server types are specified per-job using **workflow labels**, not orchestrator config:

```yaml
# In workflow file - x64 jobs use cx53 (Intel/AMD)
runs-on: [self-hosted, Linux, x64, type-cx53]

# arm64 jobs use cax41 (ARM Ampere)
runs-on: [self-hosted, Linux, arm64, type-cax41]
```

Jobs without a `type-*` label will fail, ensuring explicit server type selection.

### Firewall Configuration (Security)

Runner servers are automatically protected by the `github-runner-protection` firewall:

1. TestFlows creates runners with label `role=github-runner` (configured in wrapper script)
2. The firewall has `apply_to { label_selector = "role=github-runner" }` in `network.tf`
3. Hetzner automatically attaches the firewall to any server with this label

**Important:** Run `terraform apply` on `network.tf` to update the firewall with the label selector
if it was created before this change.

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
2. Server registers as a self-hosted runner with labels: `self-hosted`, `Linux`, `x64` or `arm64`
3. Job runs on the runner
4. After job completes, server is deleted immediately

## Scaling Behavior

| Scenario | Behavior |
|----------|----------|
| No jobs queued | No servers running (zero cost) |
| 1 x64 job queued | 1 cx53 server created |
| 2 x64 + 2 arm64 jobs | 2 cx53 + 2 cax41 servers (max 4) |
| Jobs > max runners | Jobs queue until runners available |

## Troubleshooting

### Runners not appearing

1. Check GitHub token has correct permissions
2. Verify repository name is correct
3. Check Hetzner token is valid

```bash
journalctl -u github-hetzner-runners -n 100
```

### Servers created but jobs not running

1. Verify runner images exist and have correct names
2. Check firewall allows outbound connections
3. Ensure images have GitHub runner pre-configured

### Service won't start

1. Check environment file syntax: `cat /etc/github-hetzner-runners/env`
2. Verify all required variables are set
3. Check systemd logs: `journalctl -u github-hetzner-runners -e`

## Maintenance

### Runner Image Updates (Automated)

Runner images are rebuilt monthly by the `build-hetzner-images.yml` workflow. The workflow automatically:

1. Builds new x86 and arm64 runner images with Packer
2. SSHs to the orchestrator server
3. Updates `IMAGE_X64` and `IMAGE_ARM64` in `/etc/github-hetzner-runners/env`
4. Restarts the orchestrator service
5. Cleans up old snapshots (keeps last 2)

**Requirements for automated updates:**
- `ORCHESTRATOR_HOST` secret set to orchestrator server IP/hostname
- `ORCHESTRATOR_SSH_KEY` secret set to SSH private key for root access

Running jobs are not affected during the restart (see architecture notes above).

### Updating TestFlows

```bash
/opt/testflows-runners/bin/pip install --upgrade testflows.github.hetzner.runners
systemctl restart github-hetzner-runners
```

### Rotating Tokens

1. Generate new token in GitHub/Hetzner
2. Update `/etc/github-hetzner-runners/env`
3. Restart service: `systemctl restart github-hetzner-runners`

### Rebuilding Orchestrator Image

If you need to rebuild the orchestrator image with updates:

```bash
packer build testflows-orchestrator.pkr.hcl
# Then create new server from new snapshot and migrate config
```

## Required Secrets Summary

### GitHub Repository Secrets

| Secret | Purpose |
|--------|---------|
| `HCLOUD_TOKEN` | Hetzner API (for Packer image builds) |
| `HETZNER_S3_ACCESS_KEY` | Maven cache storage |
| `HETZNER_S3_SECRET_KEY` | Maven cache storage |
| `HETZNER_S3_ENDPOINT` | Maven cache storage |
| `ORCHESTRATOR_HOST` | Orchestrator server IP/hostname (for automated image updates) |
| `ORCHESTRATOR_SSH_KEY` | SSH private key for orchestrator root access (for automated image updates) |

### Orchestrator Server Environment

| Variable | Purpose |
|----------|---------|
| `GITHUB_TOKEN` | Runner registration (PAT with repo/admin:org scope) |
| `GITHUB_REPOSITORY` | Target repository |
| `HCLOUD_TOKEN` | Server creation/deletion |
