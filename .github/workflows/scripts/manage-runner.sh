#!/bin/bash
set -e

COMMAND=$1
ARCH=$2
HCLOUD_TOKEN=$3
GITHUB_TOKEN=$4
REPO=$5
RUNNER_NAME="runner-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-${ARCH}"

if [ "$COMMAND" == "start" ]; then
    echo ">>> Generating GitHub Runner Token..."
    # Call GitHub API to get a registration token
    REG_TOKEN=$(curl -s -X POST -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github.v3+json" \
        "https://api.github.com/repos/$REPO/actions/runners/registration-token" | jq -r .token)

    if [ "$REG_TOKEN" == "null" ]; then echo "Failed to get token"; exit 1; fi

    # Find the snapshot ID we created with Packer
    IMAGE_ID=$(hcloud image list --selector "role=github-runner,arch=$ARCH" --sort created:desc -o json | jq -r '.[0].id')
    echo ">>> Found Image ID: $IMAGE_ID"

    # Prepare User Data: Inject the real token into our YAML template
    # We use sed to replace the placeholders in the yaml file
    USER_DATA=$(sed -e "s|\${GITHUB_REPOSITORY}|$REPO|g" \
                    -e "s|\${RUNNER_TOKEN}|$REG_TOKEN|g" \
                    -e "s|\${RUNNER_NAME}|$RUNNER_NAME|g" \
                    .github/scripts/user-data.yaml)

    echo ">>> Creating Hetzner Server..."
    SERVER_TYPE="cx22" # default x86
    if [ "$ARCH" == "arm" ]; then SERVER_TYPE="cax11"; fi

    hcloud server create \
        --name "$RUNNER_NAME" \
        --image "$IMAGE_ID" \
        --type "$SERVER_TYPE" \
        --location "nbg1" \
        --user-data "$USER_DATA" \
        --label "run-id=${GITHUB_RUN_ID}"

    echo ">>> Server created. Waiting for runner to register..."
    # Optional: Wait loop to ensure runner is online before returning
    # Usually 30-60 seconds

elif [ "$COMMAND" == "stop" ]; then
    echo ">>> Deleting Hetzner Server..."
    # We find the server by the name we gave it
    hcloud server delete "$RUNNER_NAME" || echo "Server already deleted or not found"
fi