#!/bin/bash
set -e

# Runner Pool Manager for Hetzner Cloud
# Manages a pool of self-hosted GitHub runners with auto-scaling
# Uses ephemeral runners with SSH-based drain signaling for safe deletion

COMMAND=$1
HCLOUD_TOKEN=$2
GITHUB_TOKEN=$3
REPO=$4
SSH_KEY_NAME=${5:-"github-runner-key"}

# Pool configuration
MIN_SERVERS_PER_ARCH=1      # Always keep this many servers per architecture
MAX_SERVERS_PER_ARCH=2      # Maximum servers per architecture
IDLE_TIMEOUT_MINUTES=30     # Delete extra servers after this idle time
RUNNERS_PER_SERVER=2        # Number of runner instances per server

# Server types (must match Packer image disk sizes)
X86_SERVER_TYPE="cx53"
ARM_SERVER_TYPE="cax41"

# Export for hcloud CLI
export HCLOUD_TOKEN

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

get_image_id() {
    local arch=$1
    hcloud image list --selector "role=github-runner,arch=$arch" --sort created:desc -o json | jq -r '.[0].id'
}

get_pool_servers() {
    local arch=$1
    hcloud server list --selector "pool=github-runner,arch=$arch" -o json
}

get_server_count() {
    local arch=$1
    get_pool_servers "$arch" | jq 'length'
}

get_server_ip() {
    local server_name=$1
    hcloud server describe "$server_name" -o json | jq -r '.public_net.ipv4.ip'
}

get_idle_servers() {
    local arch=$1
    hcloud server list --selector "pool=github-runner,arch=$arch,pool-status=idle" -o json
}

create_pool_server() {
    local arch=$1
    local server_num=$2

    local server_type=$X86_SERVER_TYPE
    if [ "$arch" == "arm" ]; then
        server_type=$ARM_SERVER_TYPE
    fi

    local image_id=$(get_image_id "$arch")
    if [ "$image_id" == "null" ] || [ -z "$image_id" ]; then
        log "ERROR: No image found for arch=$arch"
        return 1
    fi

    local server_name="pool-runner-${arch}-${server_num}"

    # Prepare user data - pass GitHub PAT for ephemeral runner registration
    local user_data_file=$(mktemp)
    sed -e "s|\${GITHUB_REPOSITORY}|$REPO|g" \
        -e "s|\${GITHUB_PAT}|$GITHUB_TOKEN|g" \
        -e "s|\${RUNNER_NAME}|$server_name|g" \
        "$(dirname "$0")/user-data-pool.yaml" > "$user_data_file"

    log "Creating server $server_name (arch=$arch, type=$server_type, image=$image_id)"

    hcloud server create \
        --name "$server_name" \
        --image "$image_id" \
        --type "$server_type" \
        --location "nbg1" \
        --user-data-from-file "$user_data_file" \
        --ssh-key "$SSH_KEY_NAME" \
        --label "pool=github-runner" \
        --label "arch=$arch" \
        --label "pool-status=idle" \
        --label "created-at=$(date +%s)"

    rm -f "$user_data_file"
    log "Server $server_name created"
}

set_server_status() {
    local server_name=$1
    local status=$2

    hcloud server add-label "$server_name" "pool-status=$status" --overwrite
    log "Set $server_name status to $status"
}

# Check if runners on a server are busy (via GitHub API)
are_runners_busy() {
    local server_name=$1

    local busy_count=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runners" | \
        jq "[.runners[] | select(.name | startswith(\"$server_name\")) | select(.busy==true)] | length")

    [ "$busy_count" -gt 0 ]
}

# Check if server has any runners registered in GitHub
has_registered_runners() {
    local server_name=$1

    local runner_count=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runners" | \
        jq "[.runners[] | select(.name | startswith(\"$server_name\"))] | length")

    [ "$runner_count" -gt 0 ]
}

# Signal server to drain via SSH (creates drain file)
# This prevents the launcher from starting new runners
drain_server() {
    local server_name=$1

    log "Signaling $server_name to drain via SSH"

    local server_ip=$(get_server_ip "$server_name")
    if [ -z "$server_ip" ] || [ "$server_ip" == "null" ]; then
        log "ERROR: Could not get IP for $server_name"
        return 1
    fi

    # Create drain file via SSH (disable strict host key checking for automation)
    ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null \
        -o ConnectTimeout=10 \
        root@"$server_ip" "touch /var/run/drain-runner" 2>/dev/null || {
        log "WARNING: SSH drain signal failed for $server_name, will retry"
        return 1
    }

    set_server_status "$server_name" "draining"
    log "Server $server_name marked for draining"
}

# Safely delete a server (only call when runners are not busy)
delete_server() {
    local server_name=$1

    log "Deleting server $server_name"
    hcloud server delete "$server_name" || log "Server $server_name already deleted"
}

# Get count of queued/in-progress workflow runs
get_pending_jobs() {
    local queued=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runs?status=queued" | jq '.total_count')

    local in_progress=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runs?status=in_progress" | jq '.total_count')

    echo $((queued + in_progress))
}

# Main scaling logic
scale_pool() {
    log "=== Starting pool scaling check ==="

    for arch in x86 arm; do
        log "--- Processing $arch architecture ---"

        local current_count=$(get_server_count "$arch")
        local pending_jobs=$(get_pending_jobs)

        log "Current servers: $current_count, Pending jobs: $pending_jobs"

        # Ensure minimum servers
        if [ "$current_count" -lt "$MIN_SERVERS_PER_ARCH" ]; then
            local needed=$((MIN_SERVERS_PER_ARCH - current_count))
            log "Below minimum, creating $needed server(s)"
            for i in $(seq 1 $needed); do
                local next_num=$((current_count + i))
                create_pool_server "$arch" "$next_num"
            done
            current_count=$MIN_SERVERS_PER_ARCH
        fi

        # Scale up if needed (and under max)
        if [ "$pending_jobs" -gt 0 ] && [ "$current_count" -lt "$MAX_SERVERS_PER_ARCH" ]; then
            local idle_count=$(get_idle_servers "$arch" | jq 'length')
            local available_runners=$((idle_count * RUNNERS_PER_SERVER))

            if [ "$pending_jobs" -gt "$available_runners" ]; then
                log "Need more capacity, scaling up"
                local next_num=$((current_count + 1))
                create_pool_server "$arch" "$next_num"
            fi
        fi

        # Scale down: safe two-phase deletion with ephemeral runners
        # Phase 1: Signal drain via SSH (prevents new runner registration)
        # Phase 2: Delete server once no runners are busy
        local servers=$(get_pool_servers "$arch")
        local server_count=$(echo "$servers" | jq 'length')

        if [ "$server_count" -gt "$MIN_SERVERS_PER_ARCH" ]; then
            echo "$servers" | jq -r '.[] | @base64' | while read server_b64; do
                local server=$(echo "$server_b64" | base64 -d)
                local server_name=$(echo "$server" | jq -r '.name')
                local created_at=$(echo "$server" | jq -r '.labels["created-at"] // "0"')
                local pool_status=$(echo "$server" | jq -r '.labels["pool-status"] // "unknown"')

                # Phase 2: If draining, check if safe to delete
                if [ "$pool_status" == "draining" ]; then
                    if are_runners_busy "$server_name"; then
                        log "Server $server_name has busy runners, waiting..."
                    else
                        # No busy runners - safe to delete
                        # (drain file prevents new runners from starting)
                        log "Server $server_name runners finished, deleting"
                        delete_server "$server_name"
                    fi
                    continue
                fi

                # Update status based on runner activity
                if are_runners_busy "$server_name"; then
                    set_server_status "$server_name" "busy"
                    continue
                else
                    set_server_status "$server_name" "idle"
                fi

                # Check idle timeout for extra servers
                local current_time=$(date +%s)
                local idle_time=$(( (current_time - created_at) / 60 ))

                # Phase 1: If idle, over minimum, and past timeout -> start draining
                local current_count=$(get_server_count "$arch")
                if [ "$pool_status" == "idle" ] && \
                   [ "$current_count" -gt "$MIN_SERVERS_PER_ARCH" ] && \
                   [ "$idle_time" -gt "$IDLE_TIMEOUT_MINUTES" ]; then
                    log "Server $server_name idle for ${idle_time}m, starting drain"
                    drain_server "$server_name"
                fi
            done
        fi
    done

    log "=== Pool scaling check complete ==="
}

# Update server status based on runner activity
update_status() {
    log "=== Updating server status ==="

    for arch in x86 arm; do
        local servers=$(get_pool_servers "$arch")

        echo "$servers" | jq -r '.[] | @base64' | while read server_b64; do
            local server=$(echo "$server_b64" | base64 -d)
            local server_name=$(echo "$server" | jq -r '.name')
            local pool_status=$(echo "$server" | jq -r '.labels["pool-status"] // "unknown"')

            # Don't change status of draining servers
            if [ "$pool_status" == "draining" ]; then
                log "Server $server_name is draining, skipping status update"
                continue
            fi

            if are_runners_busy "$server_name"; then
                set_server_status "$server_name" "busy"
            else
                set_server_status "$server_name" "idle"
            fi
        done
    done
}

# Initialize pool (create minimum servers if none exist)
init_pool() {
    log "=== Initializing runner pool ==="

    for arch in x86 arm; do
        local current_count=$(get_server_count "$arch")

        if [ "$current_count" -lt "$MIN_SERVERS_PER_ARCH" ]; then
            local needed=$((MIN_SERVERS_PER_ARCH - current_count))
            log "Creating $needed initial $arch server(s)"

            for i in $(seq 1 $needed); do
                create_pool_server "$arch" "$i"
            done
        else
            log "$arch: Already have $current_count server(s)"
        fi
    done

    log "=== Pool initialization complete ==="
}

# Cleanup entire pool (force delete all servers)
cleanup_pool() {
    log "=== Cleaning up entire pool ==="

    for arch in x86 arm; do
        local servers=$(get_pool_servers "$arch")

        echo "$servers" | jq -r '.[].name' | while read server_name; do
            log "Force deleting $server_name"
            hcloud server delete "$server_name" || log "Server $server_name already deleted"
        done
    done

    log "=== Pool cleanup complete ==="
}

# Show pool status
status() {
    log "=== Pool Status ==="

    for arch in x86 arm; do
        echo ""
        echo "--- $arch servers ---"
        local servers=$(get_pool_servers "$arch")

        if [ "$(echo "$servers" | jq 'length')" -eq 0 ]; then
            echo "  No servers"
        else
            echo "$servers" | jq -r '.[] | "  \(.name): status=\(.labels["pool-status"] // "unknown"), ip=\(.public_net.ipv4.ip), created=\(.labels["created-at"] // "unknown")"'
        fi
    done

    echo ""
    echo "--- GitHub Runners ---"
    curl -s -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runners" | \
        jq -r '.runners[] | select(.name | startswith("pool-runner")) | "  \(.name): status=\(.status), busy=\(.busy)"'
}

# Command dispatch
case "$COMMAND" in
    init)
        init_pool
        ;;
    scale)
        scale_pool
        ;;
    status)
        status
        ;;
    update-status)
        update_status
        ;;
    cleanup)
        cleanup_pool
        ;;
    *)
        echo "Usage: $0 {init|scale|status|update-status|cleanup} HCLOUD_TOKEN GITHUB_TOKEN REPO [SSH_KEY_NAME]"
        exit 1
        ;;
esac
