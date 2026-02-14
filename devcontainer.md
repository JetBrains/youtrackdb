# Dev Container Manual

A sandboxed Docker environment for running Claude Code against the YouTrackDB codebase. The container is network-restricted by an iptables firewall that only allows traffic to approved services.

## Prerequisites

- Docker Engine installed and running
- `jq` installed on the host (used by the launcher script)
- A GitHub fine-grained personal access token (PAT) for git and PR operations (see [GitHub Authentication](#github-authentication))

## Quick Start

```bash
./run-devcontainer.sh
```

On first launch the script builds the Docker image (takes a few minutes). Subsequent launches reuse the cached image; it rebuilds automatically after 24 hours.

Once the container starts you are dropped into a zsh shell at `/workspace`.

## First-Time Authentication

### Claude Code

Claude Code authentication is stored in a persistent Docker volume. On the very first run you need to authenticate once:

```bash
claude
```

Select **option 1** (Claude account with subscription), open the URL in your browser, authenticate, and paste the code back. This persists across container restarts — you won't need to do it again unless the volume is deleted.

### GitHub Authentication

The container uses the GitHub CLI (`gh`) for both git operations (push, pull) and GitHub API access (PR link detection, issue viewing). No SSH keys are used — all GitHub access goes over HTTPS.

On first launch you'll see a prompt to authenticate. Run:

```bash
gh auth login
```

When prompted:
1. Select **GitHub.com**
2. Select **HTTPS** as the protocol
3. Authenticate with a **fine-grained personal access token** (PAT)

Create the PAT at https://github.com/settings/personal-access-tokens with these minimal permissions:
- **Repository access**: Only the repositories you need (e.g., `JetBrains/youtrackdb`)
- **Permissions**: Contents (read/write), Pull requests (read/write), Issues (read)

The token is stored in a persistent Docker volume and survives container restarts.

## What the Launcher Does

| Step | Description |
|---|---|
| Image build | Builds (or reuses) the `ytdb-devcontainer` Docker image |
| UID mapping | Adjusts the container `node` user UID/GID to match the host user |
| Git worktree | Detects git worktrees and mounts the parent `.git` directory |
| Workspace | Bind-mounts the repository to `/workspace` |
| Post-create | Copies container-specific Claude settings, generates JetBrains MCP config |
| Firewall | Locks down the network to an allowlist of domains |
| Cleanup | Removes `settings.local.json` and `.mcp.json` from the workspace on exit |

## Network Firewall

The container runs behind an iptables firewall. Only these destinations are reachable:

| Service | Domains |
|---|---|
| GitHub | API, web, git CIDR ranges from `api.github.com/meta` |
| Anthropic | `api.anthropic.com`, `console.anthropic.com` |
| Claude telemetry | `statsig.anthropic.com`, `statsig.com`, `sentry.io` |
| Maven | `repo.maven.apache.org`, `repo1.maven.org`, `central.sonatype.com`, `maven.youtrackdb.io` |
| Other | `registry.npmjs.org`, `packages.adoptium.net`, `api.githubcopilot.com` |
| Docker host | The host machine IP (for JetBrains MCP) |

All other outbound traffic is rejected. SSH (port 22) is blocked — git uses HTTPS via the `gh` credential helper.

## Persistent Volumes

These Docker volumes survive container restarts:

| Volume | Container path | Contents |
|---|---|---|
| `ytdb-claude-config` | `/home/node/.claude` | Claude Code auth, history, settings |
| `ytdb-claude-bashhistory` | `/commandhistory` | Shell history |
| `ytdb-claude-ghconfig` | `/home/node/.config/gh` | GitHub CLI auth token |

To reset Claude auth or start fresh:

```bash
docker volume rm ytdb-claude-config
```

To reset GitHub CLI auth:

```bash
docker volume rm ytdb-claude-ghconfig
```

## Git

Full git operations work inside the container over HTTPS, including for git worktrees. The launcher detects worktree layouts and mounts the parent `.git` directory automatically. Git authentication is handled by the `gh` credential helper — no SSH keys are needed.

```bash
git status
git commit -m "YTDB-123: Fix something"
git push
```

## Multiple Worktrees

The launcher derives a unique container name from the directory name, so you can run multiple instances on different worktrees simultaneously:

```bash
# These run in parallel without conflicts
cd ~/Projects/ytdb/feature-a && ./run-devcontainer.sh
cd ~/Projects/ytdb/feature-b && ./run-devcontainer.sh
```

## JetBrains MCP

If you have the JetBrains MCP server running in your IDE, the container auto-configures access to it. The default port is 64342; override with:

```bash
JETBRAINS_MCP_PORT=12345 ./run-devcontainer.sh
```

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `TZ` | `Europe/Berlin` | Container timezone |
| `JETBRAINS_MCP_PORT` | `64342` | JetBrains MCP server port on the host |

## Forcing a Rebuild

The image auto-rebuilds after 24 hours. To force an immediate rebuild:

```bash
docker rmi ytdb-devcontainer
./run-devcontainer.sh
```

If you hit stale Docker layer cache issues after changing scripts in `.devcontainer/`:

```bash
docker rmi ytdb-devcontainer
docker builder prune -f
./run-devcontainer.sh
```

Note: changes to `entrypoint.sh` take effect immediately (it runs from the workspace bind mount, not from the image).

## Troubleshooting

### Firewall setup fails

Check the output for the specific error. Common causes:

- **DNS resolution failure** — a domain in the allowlist doesn't resolve. The firewall script detects DNS servers from `/etc/resolv.conf` automatically.
- **Stale Docker image** — scripts changed but the image has the old copy. Force a rebuild (see above).

### GitHub CLI not authenticated

```
GitHub CLI is not authenticated.
Run: gh auth login
```

This is expected on first launch. Run `gh auth login` inside the container and authenticate with a fine-grained PAT (see [GitHub Authentication](#github-authentication)). The token persists in a Docker volume.

### Claude shows login prompt

This is expected on first launch. Authenticate once via option 1 (subscription) — it persists in the Docker volume.

If it appears after a previous successful login, the volume may have been deleted:

```bash
docker volume ls | grep claude-config
```

### Permission errors on workspace files

The launcher maps your host UID/GID into the container. If you see permission issues, check:

```bash
id -u   # should match the UID inside the container
```
