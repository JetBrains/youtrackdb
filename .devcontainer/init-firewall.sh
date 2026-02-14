#!/bin/bash
set -euo pipefail  # Exit on error, undefined vars, and pipeline failures
IFS=$'\n\t'       # Stricter word splitting

# Verify required tools
for cmd in iptables ip6tables ipset curl dig jq aggregate iptables-restore; do
    command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: '$cmd' tool not found"; exit 1; }
done

# 1. Detect DNS servers from resolv.conf (works on both default bridge and user-defined networks)
DNS_SERVERS=$(awk '/^nameserver/ {print $2}' /etc/resolv.conf)
if [ -z "$DNS_SERVERS" ]; then
    echo "ERROR: No DNS servers found in /etc/resolv.conf"
    exit 1
fi
DNS_SERVER=$(echo "$DNS_SERVERS" | head -1)
echo "DNS servers detected: $(echo $DNS_SERVERS | tr '\n' ' ')"

# 2. Extract Docker DNS NAT rules BEFORE any flushing (only relevant for 127.0.0.11)
DOCKER_NAT_SAVE=$(iptables-save -t nat)
DOCKER_DNS_RULES=$(echo "$DOCKER_NAT_SAVE" | grep "127\.0\.0\.11" || true)
DOCKER_DNS_CHAINS=$(echo "$DOCKER_NAT_SAVE" | grep "^:" | grep -E "DOCKER_OUTPUT|DOCKER_POSTROUTING" || true)

# Flush existing IPv4 rules and delete existing ipsets
iptables -F
iptables -X
iptables -t nat -F
iptables -t nat -X
iptables -t mangle -F
iptables -t mangle -X
ipset destroy allowed-domains 2>/dev/null || true

# Set DROP policies immediately to close the transient open window
iptables -P INPUT DROP
iptables -P FORWARD DROP
iptables -P OUTPUT DROP

# Flush and block all IPv6 traffic to prevent firewall bypass
ip6tables -F
ip6tables -X
ip6tables -P INPUT DROP
ip6tables -P FORWARD DROP
ip6tables -P OUTPUT DROP
ip6tables -A INPUT -i lo -j ACCEPT
ip6tables -A OUTPUT -o lo -j ACCEPT

# Allow localhost
iptables -A INPUT -i lo -j ACCEPT
iptables -A OUTPUT -o lo -j ACCEPT

# Allow established/related connections
iptables -A INPUT -m state --state ESTABLISHED,RELATED -j ACCEPT
iptables -A OUTPUT -m state --state ESTABLISHED,RELATED -j ACCEPT

# Allow DNS to each configured nameserver (UDP and TCP)
while read -r dns; do
    iptables -A OUTPUT -p udp --dport 53 -d "$dns" -j ACCEPT
    iptables -A INPUT -p udp --sport 53 -s "$dns" -j ACCEPT
    iptables -A OUTPUT -p tcp --dport 53 -d "$dns" -j ACCEPT
    iptables -A INPUT -p tcp --sport 53 -s "$dns" -j ACCEPT
done <<< "$DNS_SERVERS"

# Restore Docker DNS NAT rules using iptables-restore (preserves correct argument parsing)
if [ -n "$DOCKER_DNS_RULES" ]; then
    echo "Restoring Docker DNS rules..."
    {
        echo "*nat"
        if [ -n "$DOCKER_DNS_CHAINS" ]; then
            echo "$DOCKER_DNS_CHAINS"
        else
            echo ":DOCKER_OUTPUT - [0:0]"
            echo ":DOCKER_POSTROUTING - [0:0]"
        fi
        echo "$DOCKER_DNS_RULES"
        echo "COMMIT"
    } | iptables-restore --noflush
else
    echo "No Docker DNS rules to restore"
fi

# Temporary: allow all outbound HTTPS/HTTP during setup for API fetches and DNS resolution
# (removed after ipset is populated at the end of the setup phase)
iptables -A OUTPUT -p tcp --dport 443 -j ACCEPT
iptables -A OUTPUT -p tcp --dport 80 -j ACCEPT

# Ensure temporary rules are cleaned up on error
cleanup_temp_rules() {
    iptables -D OUTPUT -p tcp --dport 443 -j ACCEPT 2>/dev/null || true
    iptables -D OUTPUT -p tcp --dport 80 -j ACCEPT 2>/dev/null || true
}
trap cleanup_temp_rules EXIT

echo "Fetching GitHub IP ranges..."
gh_ranges=$(curl -sf https://api.github.com/meta)
if [ -z "$gh_ranges" ]; then
    echo "ERROR: Failed to fetch GitHub IP ranges"
    exit 1
fi

if ! echo "$gh_ranges" | jq -e '.web and .api and .git' >/dev/null; then
    echo "ERROR: GitHub API response missing required fields"
    exit 1
fi

# Create ipset with CIDR support
ipset create allowed-domains hash:net

# Add GitHub IPv4 ranges (filter out IPv6, -exist ignores duplicates)
# aggregate -q coalesces overlapping CIDR ranges to minimize ipset entries
echo "Processing GitHub IPs..."
GH_CIDR_COUNT=0
while read -r cidr; do
    if [[ ! "$cidr" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}/[0-9]{1,2}$ ]]; then
        echo "ERROR: Invalid CIDR range from GitHub meta: $cidr"
        exit 1
    fi
    ipset add allowed-domains "$cidr" -exist
    GH_CIDR_COUNT=$((GH_CIDR_COUNT + 1))
done < <(echo "$gh_ranges" | jq -r '(.web + .api + .git)[] | select(contains(":") | not)' | aggregate -q)
echo "Added $GH_CIDR_COUNT GitHub CIDR ranges"

# Resolve and add other allowed domains
for domain in \
    "registry.npmjs.org" \
    "api.anthropic.com" \
    "console.anthropic.com" \
    "sentry.io" \
    "statsig.anthropic.com" \
    "statsig.com" \
    "repo.maven.apache.org" \
    "repo1.maven.org" \
    "central.sonatype.com" \
    "maven.youtrackdb.io" \
    "packages.adoptium.net" \
    "api.githubcopilot.com"; do
    ips=$(dig +noall +answer A "$domain" @"$DNS_SERVER" | awk '$4 == "A" {print $5}')
    if [ -z "$ips" ]; then
        echo "ERROR: Failed to resolve $domain"
        exit 1
    fi

    while read -r ip; do
        if [[ ! "$ip" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
            echo "ERROR: Invalid IP from DNS for $domain: $ip"
            exit 1
        fi
        ipset add allowed-domains "$ip" -exist
    done < <(echo "$ips")
    echo "  $domain -> $(echo "$ips" | tr '\n' ' ')"
done

# Remove temporary broad outbound rules now that ipset is populated
cleanup_temp_rules
trap - EXIT

# Get host IP from default route (take first match only)
HOST_IP=$(ip route | awk '/default/ {print $3; exit}')
if [ -z "$HOST_IP" ]; then
    echo "ERROR: Failed to detect host IP"
    exit 1
fi

echo "Host IP detected as: $HOST_IP"

# Allow only the specific host IP (needed for Docker host-container interaction and JetBrains MCP)
iptables -A INPUT -s "$HOST_IP" -j ACCEPT
iptables -A OUTPUT -d "$HOST_IP" -j ACCEPT

# Allow only HTTP/HTTPS/SSH outbound to allowed domains
iptables -A OUTPUT -p tcp --dport 443 -m set --match-set allowed-domains dst -j ACCEPT
iptables -A OUTPUT -p tcp --dport 80 -m set --match-set allowed-domains dst -j ACCEPT
iptables -A OUTPUT -p tcp --dport 22 -m set --match-set allowed-domains dst -j ACCEPT

# Explicitly REJECT all other outbound traffic for immediate feedback
iptables -A OUTPUT -j REJECT --reject-with icmp-admin-prohibited

echo "Firewall configuration complete"
echo "Verifying firewall rules..."
if curl --connect-timeout 5 https://example.com >/dev/null 2>&1; then
    echo "ERROR: Firewall verification failed - was able to reach https://example.com"
    exit 1
else
    echo "Firewall verification passed - unable to reach https://example.com as expected"
fi

# Verify GitHub API access
if ! curl --connect-timeout 5 https://api.github.com/zen >/dev/null 2>&1; then
    echo "ERROR: Firewall verification failed - unable to reach https://api.github.com"
    exit 1
else
    echo "Firewall verification passed - able to reach https://api.github.com as expected"
fi

# Verify GitHub SSH access (run as node user since SSH keys are mounted under /home/node/.ssh)
# SSH_AUTH_SOCK is set to the fixed path where run-devcontainer.sh mounts the host agent socket.
# sudo strips env vars, so we can't rely on the container environment here.
# Note: ssh -T always returns exit code 1 with GitHub (no shell access), so capture output
# to avoid pipefail propagating the non-zero code.
ssh_output=$(SSH_AUTH_SOCK=/tmp/ssh-agent.sock runuser -u node -- ssh -T -o ConnectTimeout=5 -o StrictHostKeyChecking=accept-new git@github.com 2>&1) || true
if echo "$ssh_output" | grep -q "successfully authenticated"; then
    echo "Firewall verification passed - GitHub SSH authentication successful"
else
    echo "WARNING: GitHub SSH authentication not confirmed (missing SSH keys or not configured)"
    echo "  ssh output: $ssh_output"
fi
