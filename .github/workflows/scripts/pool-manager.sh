#!/bin/bash
set -e

# Runner Pool Manager for Hetzner Cloud
# Manages a pool of self-hosted GitHub runners with auto-scaling

COMMAND=$1
HCLOUD_TOKEN=$2
GITHUB_TOKEN=$3
REPO=$4

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

get_registration_token() {
    curl -s -X POST -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runners/registration-token" | jq -r .token
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

get_idle_servers() {
    local arch=$1
    # Servers with pool-status=idle
    hcloud server list --selector "pool=github-runner,arch=$arch,pool-status=idle" -o json
}

get_busy_servers() {
    local arch=$1
    hcloud server list --selector "pool=github-runner,arch=$arch,pool-status=busy" -o json
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
    local reg_token=$(get_registration_token)

    if [ "$reg_token" == "null" ] || [ -z "$reg_token" ]; then
        log "ERROR: Failed to get registration token"
        return 1
    fi

    # Prepare user data
    local user_data_file=$(mktemp)
    sed -e "s|\${GITHUB_REPOSITORY}|$REPO|g" \
        -e "s|\${RUNNER_TOKEN}|$reg_token|g" \
        -e "s|\${RUNNER_NAME}|$server_name|g" \
        "$(dirname "$0")/user-data-pool.yaml" > "$user_data_file"

    log "Creating server $server_name (arch=$arch, type=$server_type, image=$image_id)"

    hcloud server create \
        --name "$server_name" \
        --image "$image_id" \
        --type "$server_type" \
        --location "nbg1" \
        --user-data-from-file "$user_data_file" \
        --label "pool=github-runner" \
        --label "arch=$arch" \
        --label "pool-status=idle" \
        --label "created-at=$(date +%s)"

    rm -f "$user_data_file"
    log "Server $server_name created"
}

delete_pool_server() {
    local server_name=$1

    log "Deleting server $server_name"

    # First, remove runners from GitHub
    local runner_ids=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runners" | \
        jq -r ".runners[] | select(.name | startswith(\"$server_name\")) | .id")

    for runner_id in $runner_ids; do
        log "Removing runner $runner_id from GitHub"
        curl -s -X DELETE -H "Authorization: token $GITHUB_TOKEN" \
            -H "Accept: application/vnd.github.v3+json" \
            "https://api.github.com/repos/$REPO/actions/runners/$runner_id" || true
    done

    # Delete the server
    hcloud server delete "$server_name" || log "Server $server_name already deleted"
}

set_server_status() {
    local server_name=$1
    local status=$2

    hcloud server add-label "$server_name" "pool-status=$status" --overwrite
    log "Set $server_name status to $status"
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

# Check if runners on a server are busy
are_runners_busy() {
    local server_name=$1

    local busy_count=$(curl -s -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runners" | \
        jq "[.runners[] | select(.name | startswith(\"$server_name\")) | select(.busy==true)] | length")

    [ "$busy_count" -gt 0 ]
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

        # Scale down idle servers (keep minimum)
        local servers=$(get_pool_servers "$arch")
        local server_count=$(echo "$servers" | jq 'length')

        if [ "$server_count" -gt "$MIN_SERVERS_PER_ARCH" ]; then
            echo "$servers" | jq -r '.[] | @base64' | while read server_b64; do
                local server=$(echo "$server_b64" | base64 -d)
                local server_name=$(echo "$server" | jq -r '.name')
                local created_at=$(echo "$server" | jq -r '.labels["created-at"] // "0"')
                local pool_status=$(echo "$server" | jq -r '.labels["pool-status"] // "unknown"')

                # Skip if busy
                if are_runners_busy "$server_name"; then
                    set_server_status "$server_name" "busy"
                    continue
                fi

                # Check idle timeout for extra servers
                local current_time=$(date +%s)
                local idle_time=$(( (current_time - created_at) / 60 ))

                # Only delete if: idle, over minimum, and past timeout
                local current_count=$(get_server_count "$arch")
                if [ "$pool_status" == "idle" ] && \
                   [ "$current_count" -gt "$MIN_SERVERS_PER_ARCH" ] && \
                   [ "$idle_time" -gt "$IDLE_TIMEOUT_MINUTES" ]; then
                    log "Server $server_name idle for ${idle_time}m, deleting"
                    delete_pool_server "$server_name"
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

        echo "$servers" | jq -r '.[].name' | while read server_name; do
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

# Cleanup entire pool
cleanup_pool() {
    log "=== Cleaning up entire pool ==="

    for arch in x86 arm; do
        local servers=$(get_pool_servers "$arch")

        echo "$servers" | jq -r '.[].name' | while read server_name; do
            delete_pool_server "$server_name"
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
            echo "$servers" | jq -r '.[] | "  \(.name): status=\(.labels["pool-status"] // "unknown"), created=\(.labels["created-at"] // "unknown")"'
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
        echo "Usage: $0 {init|scale|status|update-status|cleanup} HCLOUD_TOKEN GITHUB_TOKEN REPO"
        exit 1
        ;;
esac
