#!/usr/bin/env bash
# Parse JMH JSON results and push to InfluxDB 2.x via line protocol.
# Bash/jq/curl replacement for jmh-to-influxdb.py (no Python dependency).
set -euo pipefail

usage() {
  echo "Usage: $0 --input FILE --influxdb-url URL --influxdb-token TOKEN"
  echo "       [--influxdb-org ORG] [--influxdb-bucket BUCKET]"
  echo "       --branch BRANCH --commit-sha SHA --timestamp EPOCH_S"
  echo "       [--dry-run]"
  exit 1
}

# Defaults
INFLUXDB_ORG="youtrackdb"
INFLUXDB_BUCKET="jmh-benchmarks"
DRY_RUN=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)          INPUT="$2";          shift 2 ;;
    --influxdb-url)   INFLUXDB_URL="$2";   shift 2 ;;
    --influxdb-token) INFLUXDB_TOKEN="$2"; shift 2 ;;
    --influxdb-org)   INFLUXDB_ORG="$2";   shift 2 ;;
    --influxdb-bucket) INFLUXDB_BUCKET="$2"; shift 2 ;;
    --branch)         BRANCH="$2";         shift 2 ;;
    --commit-sha)     COMMIT_SHA="$2";     shift 2 ;;
    --timestamp)      TIMESTAMP="$2";      shift 2 ;;
    --dry-run)        DRY_RUN=true;        shift ;;
    *) echo "Unknown option: $1"; usage ;;
  esac
done

: "${INPUT:?--input is required}"
: "${INFLUXDB_URL:?--influxdb-url is required}"
: "${INFLUXDB_TOKEN:?--influxdb-token is required}"
: "${BRANCH:?--branch is required}"
: "${COMMIT_SHA:?--commit-sha is required}"
: "${TIMESTAMP:?--timestamp is required}"

if ! command -v jq &>/dev/null; then
  echo "Error: jq is required but not found" >&2
  exit 1
fi

TIMESTAMP_NS=$(( TIMESTAMP * 1000000000 ))

# Escape InfluxDB tag value: backslash-escape spaces, commas, equals
escape_tag() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/ /\\ /g; s/,/\\,/g; s/=/\\=/g'
}

BRANCH_ESC=$(escape_tag "$BRANCH")
SHA_ESC=$(escape_tag "$COMMIT_SHA")

# Parse JMH JSON and emit InfluxDB line protocol
LINE_DATA=$(jq -r --arg branch "$BRANCH_ESC" \
                   --arg sha "$SHA_ESC" \
                   --arg ts "$TIMESTAMP_NS" '
  # Build benchmark lines
  def esc: gsub("\\\\"; "\\\\") | gsub(" "; "\\ ") | gsub(","; "\\,") | gsub("="; "\\=");
  [ .[] |
    .benchmark as $bench |
    ($bench | split(".")[-2]) as $class |
    ($bench | split(".")[-1]) as $method |
    (if ($class | test("SingleThread")) then "SingleThread"
     elif ($class | test("MultiThread")) then "MultiThread"
     else $class end) as $suite |
    .primaryMetric as $pm |
    ($pm.score // 0) as $score |
    ($pm.scoreError // 0) as $err |
    ($pm.scorePercentiles // {}) as $pct |
    "jmh_benchmark,query=\($method | esc),suite=\($suite | esc),branch=\($branch),commit_sha=\($sha) score=\($score),score_error=\($err),score_p0_00=\($pct["0.0"] // 0),score_p50_00=\($pct["50.0"] // 0),score_p90_00=\($pct["90.0"] // 0),score_p95_00=\($pct["95.0"] // 0),score_p99_00=\($pct["99.0"] // 0),score_p99_99=\($pct["99.99"] // 0),score_p100_00=\($pct["100.0"] // 0) \($ts)"
  ] +
  # Build scalability lines (MT/ST ratio per query)
  (
    [ .[] |
      .benchmark as $bench |
      ($bench | split(".")[-2]) as $class |
      ($bench | split(".")[-1]) as $method |
      (if ($class | test("SingleThread")) then "ST"
       elif ($class | test("MultiThread")) then "MT"
       else null end) as $type |
      select($type != null) |
      { query: $method, type: $type, score: (.primaryMetric.score // 0) }
    ] |
    group_by(.query) |
    map(
      (map(select(.type == "ST")) | first // null) as $st |
      (map(select(.type == "MT")) | first // null) as $mt |
      select($st != null and $mt != null and $st.score > 0) |
      "scalability,query=\($st.query | esc),branch=\($branch),commit_sha=\($sha) ratio=\($mt.score / $st.score),st_score=\($st.score),mt_score=\($mt.score) \($ts)"
    )
  ) |
  .[]
' "$INPUT")

NUM_BENCHMARKS=$(printf "%s\n" "$LINE_DATA" | grep -c '^jmh_benchmark,' || true)
NUM_SCALABILITY=$(printf "%s\n" "$LINE_DATA" | grep -c '^scalability,' || true)
echo "Parsed $NUM_BENCHMARKS benchmark results, $NUM_SCALABILITY scalability metrics"

if [ "$DRY_RUN" = true ]; then
  echo "$LINE_DATA"
  exit 0
fi

# Push to InfluxDB
WRITE_URL="${INFLUXDB_URL%/}/api/v2/write?org=${INFLUXDB_ORG}&bucket=${INFLUXDB_BUCKET}&precision=ns"

HTTP_CODE=$(printf "%s" "$LINE_DATA" | curl -s -o /tmp/influxdb-response-$$.txt -w '%{http_code}' \
  -X POST "$WRITE_URL" \
  -H "Authorization: Token ${INFLUXDB_TOKEN}" \
  -H "Content-Type: text/plain; charset=utf-8" \
  --data-binary @-)

if [[ "$HTTP_CODE" -ge 200 && "$HTTP_CODE" -lt 300 ]]; then
  echo "InfluxDB write successful: $HTTP_CODE"
else
  echo "InfluxDB write failed: $HTTP_CODE - $(cat /tmp/influxdb-response-$$.txt)" >&2
  rm -f /tmp/influxdb-response-$$.txt
  exit 1
fi
rm -f /tmp/influxdb-response-$$.txt
