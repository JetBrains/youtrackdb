#!/usr/bin/env bash
# Install one authenticated AWS CLI v2 release into runner-owned temporary directories.
set -euo pipefail

readonly AWS_CLI_VERSION='2.36.48'
readonly AWS_CLI_SIGNING_FINGERPRINT='FB5DB77FD5C118B80511ADA8A6310ACC4672475C'
readonly SCRIPT_DIR=$(cd -- "${BASH_SOURCE[0]%/*}" && pwd)
readonly SIGNING_KEY="$SCRIPT_DIR/aws-cli-signing-key.asc"

fail() {
  echo "::error::$*" >&2
  exit 1
}

for tool in awk chmod curl gpg grep mkdir mktemp realpath rm uname unzip; do
  command -v "$tool" >/dev/null 2>&1 || fail "Required installer tool is unavailable: $tool"
done
[ "$(uname -s)" = 'Linux' ] || fail 'AWS CLI installation requires Linux.'
[ "$(uname -m)" = 'x86_64' ] || fail 'AWS CLI installation requires Linux x86_64.'
[ -r "$SIGNING_KEY" ] || fail "AWS CLI signing key is unavailable: $SIGNING_KEY"
[ -n "${RUNNER_TEMP:-}" ] && [ -d "$RUNNER_TEMP" ] \
  || fail 'RUNNER_TEMP must name an existing directory.'

runner_temp=$(realpath -e -- "$RUNNER_TEMP") \
  || fail 'RUNNER_TEMP could not be resolved.'
install_root=$(realpath -m -- "${AWS_CLI_INSTALL_ROOT:-$runner_temp/aws-cli}") \
  || fail 'The AWS CLI installation root could not be resolved.'
bin_dir=$(realpath -m -- "${AWS_CLI_BIN_DIR:-$runner_temp/aws-cli-bin}") \
  || fail 'The AWS CLI binary directory could not be resolved.'
runner_prefix=${runner_temp%/}/
for installation_path in "$install_root" "$bin_dir"; do
  case "$installation_path" in
    "$runner_prefix"*) ;;
    *) fail 'AWS CLI installation paths must remain below RUNNER_TEMP.' ;;
  esac
done
# Canonicalize before mkdir so traversal and existing symlink descendants cannot escape.
mkdir -p -- "$install_root" "$bin_dir"
[ -w "$install_root" ] && [ -w "$bin_dir" ] \
  || fail 'AWS CLI installation directories are not writable.'

work_dir=''
gnupg_home=''
cleanup() {
  [ -z "$work_dir" ] || rm -rf -- "$work_dir"
  [ -z "$gnupg_home" ] || rm -rf -- "$gnupg_home"
}
trap cleanup EXIT
work_dir=$(mktemp -d "$runner_temp/aws-cli-installer.XXXXXX") \
  || fail 'Could not create the AWS CLI installer directory.'
gnupg_home=$(mktemp -d "$runner_temp/aws-cli-gnupg.XXXXXX") \
  || fail 'Could not create the AWS CLI verification directory.'
chmod 700 "$gnupg_home"

readonly base_url="https://awscli.amazonaws.com/awscli-exe-linux-x86_64-${AWS_CLI_VERSION}.zip"
curl --fail --silent --show-error --location --retry 3 \
  "$base_url" --output "$work_dir/awscliv2.zip" \
  || fail "Could not download AWS CLI v${AWS_CLI_VERSION}."
curl --fail --silent --show-error --location --retry 3 \
  "${base_url}.sig" --output "$work_dir/awscliv2.zip.sig" \
  || fail "Could not download the AWS CLI v${AWS_CLI_VERSION} signature."

GNUPGHOME=$gnupg_home gpg --batch --quiet --import "$SIGNING_KEY" \
  || fail 'Could not import the pinned AWS CLI signing key.'
actual_fingerprint=$(GNUPGHOME=$gnupg_home gpg --batch --with-colons --fingerprint \
  | awk -F: '$1 == "fpr" { print $10; exit }')
[ "$actual_fingerprint" = "$AWS_CLI_SIGNING_FINGERPRINT" ] \
  || fail 'The AWS CLI signing key fingerprint does not match the pinned fingerprint.'
verification_status=$(GNUPGHOME=$gnupg_home gpg --batch --status-fd 1 \
  --verify "$work_dir/awscliv2.zip.sig" "$work_dir/awscliv2.zip" 2>/dev/null) \
  || fail 'AWS CLI installer signature verification failed.'
echo "$verification_status" \
  | grep -F "[GNUPG:] VALIDSIG $AWS_CLI_SIGNING_FINGERPRINT " >/dev/null \
  || fail 'AWS CLI installer signature came from an unexpected key.'

# The archive and its installer are not processed until publisher authentication succeeds.
unzip -q "$work_dir/awscliv2.zip" -d "$work_dir" \
  || fail 'Could not extract the verified AWS CLI installer.'
[ -x "$work_dir/aws/install" ] || fail 'The verified archive lacks an executable installer.'
"$work_dir/aws/install" --install-dir "$install_root" --bin-dir "$bin_dir" \
  || fail 'The verified AWS CLI installer failed.'
readonly aws="$bin_dir/aws"
[ -x "$aws" ] || fail 'The AWS CLI installer did not create the expected executable.'
version_output=$($aws --version 2>&1) || fail 'The installed AWS CLI could not start.'
case "$version_output" in
  "aws-cli/$AWS_CLI_VERSION "*) ;;
  *) fail "Installed AWS CLI version is unexpected: $version_output" ;;
esac
printf 'AWS CLI v%s installed and verified at %s\n' "$AWS_CLI_VERSION" "$aws"
