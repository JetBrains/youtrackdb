#!/bin/bash
set -euxo pipefail

# Create hostedtoolcache directory for GitHub Actions (required by setup-ruby, setup-python, etc.)
sudo mkdir -p /opt/hostedtoolcache
sudo chown ubuntu:ubuntu /opt/hostedtoolcache

echo "Install runner"
cd /home/ubuntu

# GitHub Actions Runner - update version and checksum when upgrading
# https://github.com/actions/runner/releases
ACTIONS_RUNNER_VERSION="2.331.0"
ACTIONS_RUNNER_SHA256="5fcc01bd546ba5c3f1291c2803658ebd3cedb3836489eda3be357d41bfcf28a7"

ACTIONS_RUNNER_ARCH="x64"
ACTIONS_RUNNER_FILE="actions-runner-linux-${ACTIONS_RUNNER_ARCH}-${ACTIONS_RUNNER_VERSION}.tar.gz"
ACTIONS_RUNNER_URL="https://github.com/actions/runner/releases/download/v${ACTIONS_RUNNER_VERSION}/${ACTIONS_RUNNER_FILE}"

echo "Downloading actions runner package from GitHub..."
curl -o "${ACTIONS_RUNNER_FILE}" -L "${ACTIONS_RUNNER_URL}"
echo "${ACTIONS_RUNNER_SHA256}  ${ACTIONS_RUNNER_FILE}" | shasum -a 256 -c

tar xzf "./${ACTIONS_RUNNER_FILE}"

echo "Configure runner"
./config.sh --unattended --replace --url "https://github.com/${GITHUB_REPOSITORY}" --token "${GITHUB_RUNNER_TOKEN}" --name "$(hostname)-${SERVER_TYPE_NAME}-${SERVER_LOCATION_NAME}" --runnergroup "${GITHUB_RUNNER_GROUP}" --labels "${GITHUB_RUNNER_LABELS}" --work _work --ephemeral

echo "Start runner"
bash -c "screen -d -m bash -c './run.sh; sudo poweroff'"
