#!/usr/bin/env bash
# Download every nightly LDBC input without consulting persistent AWS state.
set -euo pipefail

fail() {
  echo "::error::$*" >&2
  exit 1
}

[ "$#" -eq 3 ] \
  || fail 'Usage: download-ldbc-inputs.sh CURATED_PARAMS FACTOR_TABLES DATASET_ARCHIVE'
for variable in AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY HETZNER_S3_ENDPOINT AWS_CLI; do
  [ -n "${!variable:-}" ] || fail "Required storage setting is empty: $variable"
done
[ -x "$AWS_CLI" ] || fail "AWS CLI is not executable: $AWS_CLI"
[ -n "${RUNNER_TEMP:-}" ] && [ -d "$RUNNER_TEMP" ] \
  || fail 'RUNNER_TEMP must name an existing directory.'

endpoint=${HETZNER_S3_ENDPOINT%/}
case "$endpoint" in
  https://fsn1.your-objectstorage.com) region=fsn1 ;;
  https://nbg1.your-objectstorage.com) region=nbg1 ;;
  https://hel1.your-objectstorage.com) region=hel1 ;;
  *) fail 'HETZNER_S3_ENDPOINT must be an official HTTPS Hetzner location endpoint.' ;;
esac

config_dir=$(mktemp -d "$RUNNER_TEMP/aws-config.XXXXXX") \
  || fail 'Could not create the isolated AWS configuration directory.'
cleanup() {
  rm -rf -- "$config_dir"
}
trap cleanup EXIT
chmod 700 "$config_dir" || fail 'Could not secure the isolated AWS configuration directory.'
mkdir -m 700 "$config_dir/home" \
  || fail 'Could not create the isolated AWS home directory.'

aws() {
  # A clean environment blocks inherited profiles, credential processes,
  # and endpoint overrides.
  env -i \
    HOME="$config_dir/home" \
    PATH='/usr/bin:/bin' \
    AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
    AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
    AWS_CONFIG_FILE="$config_dir/config" \
    AWS_SHARED_CREDENTIALS_FILE="$config_dir/credentials" \
    AWS_CLI_HISTORY_FILE="$config_dir/history" \
    AWS_DEFAULT_REGION="$region" \
    AWS_REGION="$region" \
    AWS_EC2_METADATA_DISABLED=true \
    AWS_PAGER='' \
    "$AWS_CLI" --no-cli-pager --region "$region" --endpoint-url "$endpoint" "$@"
}

download() {
  local source=$1
  local destination=$2
  rm -f -- "$destination"
  aws s3 cp "$source" "$destination" --only-show-errors \
    || fail "Required object download failed: $source"
  [ -s "$destination" ] \
    || fail "Required object is missing or empty after download: $source"
}

if ! aws s3 ls 's3://bench-cache/ldbc/'; then
  echo '::warning::Could not list the LDBC object storage prefix.'
fi

download 's3://bench-cache/ldbc/curated-params-v3.json' "$1"
download 's3://bench-cache/ldbc/factor-tables.json' "$2"
download 's3://bench-cache/ldbc/ldbc-sf1-composite-merged-fk.tar.zst' "$3"
echo 'Downloaded all required nightly LDBC inputs.'
