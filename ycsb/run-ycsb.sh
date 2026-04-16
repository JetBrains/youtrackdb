#!/usr/bin/env bash
#
# run-ycsb.sh — YCSB benchmark runner for YouTrackDB
#
# Automates the full benchmark lifecycle: build, load data, snapshot,
# run each selected workload in two passes (max throughput + fixed
# throughput for latency distribution), and collect results.
#
# Quick validation (small dataset, two workloads):
#   ./ycsb/run-ycsb.sh --record-count 1000 --operation-count 500 --workloads B,C
#
# Full benchmark run (defaults: 3.5M records, 1M ops, all workloads):
#   ./ycsb/run-ycsb.sh
#
set -euo pipefail

# ============================================================================
# Constants
# ============================================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# JVM --add-opens flags required by YouTrackDB.
# Keep in sync with ycsb/pom.xml <argLine>.
JVM_ADD_OPENS=(
  "--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED"
  "--add-opens=java.base/sun.security.x509=ALL-UNNAMED"
  "--add-opens=java.base/java.io=ALL-UNNAMED"
  "--add-opens=java.base/java.nio=ALL-UNNAMED"
  "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED"
  "--add-opens=java.base/java.lang=ALL-UNNAMED"
  "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED"
  "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
  "--add-opens=java.base/java.util=ALL-UNNAMED"
  "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED"
  "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED"
  "--add-opens=java.base/java.net=ALL-UNNAMED"
)

ALL_WORKLOADS="B,C,E,W,I"

# ============================================================================
# Defaults
# ============================================================================

WORKLOADS="$ALL_WORKLOADS"
RECORD_COUNT=""
OPERATION_COUNT=""
THREADS=""
THROUGHPUT_RATIO="0.8"
DB_PATH=""
RESULTS_DIR=""
SKIP_BUILD=false
SKIP_LOAD=false

# ============================================================================
# Functions
# ============================================================================

usage() {
  cat <<USAGE
Usage: $(basename "$0") [OPTIONS]

Options:
  --workloads W1,W2,...   Workloads to run (default: $ALL_WORKLOADS)
  --record-count N        Number of records to load (overrides property file)
  --operation-count N     Operations per workload run (overrides property file)
  --threads N             Thread count (default: number of CPUs)
  --throughput-ratio R    Pass 2 target = max_throughput * R (default: 0.8)
  --db-path PATH          Database directory (default: \$RESULTS_DIR/ytdb-data)
  --results-dir PATH      Results output directory (default: ./ycsb-results)
  --skip-build            Skip Maven build
  --skip-load             Skip data loading (assumes snapshot exists)
  --help                  Show this help message

Workload names: B (read-mostly), C (read-only), E (scan),
                W (write-heavy), I (insert-burst)

Examples:
  # Quick validation
  $(basename "$0") --record-count 1000 --operation-count 500 --workloads B,C

  # Full run with defaults
  $(basename "$0")

  # Custom thread count and results directory
  $(basename "$0") --threads 8 --results-dir /data/ycsb-results
USAGE
}

# Detect CPU count (works on Linux and macOS)
detect_cpu_count() {
  if command -v nproc &>/dev/null; then
    nproc
  elif [ -f /proc/cpuinfo ]; then
    grep -c '^processor' /proc/cpuinfo
  elif command -v sysctl &>/dev/null; then
    sysctl -n hw.ncpu
  else
    echo 4
  fi
}

log() {
  echo "[$(date '+%H:%M:%S')] $*"
}

log_error() {
  echo "[$(date '+%H:%M:%S')] ERROR: $*" >&2
}

# Resolve the uber-jar path from the Maven build output.
resolve_uber_jar() {
  local jar
  jar=$(find "$SCRIPT_DIR/target" -maxdepth 1 -name 'youtrackdb-ycsb-*.jar' \
    ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name 'original-*' \
    -print -quit 2>/dev/null)
  if [ -z "$jar" ]; then
    log_error "Uber-jar not found in $SCRIPT_DIR/target/"
    log_error "Run without --skip-build or build manually first."
    exit 1
  fi
  echo "$jar"
}

# Run a YCSB command via the uber-jar.
#
# Usage: run_ycsb <mode> [extra_args...]
#   mode: "load" or "run"
#   extra_args: additional -P, -p, -target, etc. arguments
#
# Output is written to stdout and captured in $YCSB_OUTPUT_FILE if set.
run_ycsb() {
  local mode="$1"
  shift

  local jar
  jar=$(resolve_uber_jar)

  local cmd=(
    java
    "${JVM_ADD_OPENS[@]}"
    -jar "$jar"
    "-$mode"
    -threads "$THREADS"
  )

  # Add common properties
  cmd+=(-P "$SCRIPT_DIR/workloads/workload-common.properties")

  # Append any extra arguments
  cmd+=("$@")

  # Override record/operation counts if specified on command line
  if [ -n "$RECORD_COUNT" ]; then
    cmd+=(-p "recordcount=$RECORD_COUNT")
  fi
  if [ -n "$OPERATION_COUNT" ]; then
    cmd+=(-p "operationcount=$OPERATION_COUNT")
  fi

  log "Running: ${cmd[*]}"

  if [ -n "${YCSB_OUTPUT_FILE:-}" ]; then
    if ! "${cmd[@]}" 2>&1 | tee "$YCSB_OUTPUT_FILE"; then
      log_error "YCSB $mode failed (exit code: ${PIPESTATUS[0]})"
      return 1
    fi
  else
    if ! "${cmd[@]}" 2>&1; then
      log_error "YCSB $mode failed"
      return 1
    fi
  fi
}

# Signal handler — print recovery instructions on interrupt.
cleanup_on_signal() {
  echo ""
  log_error "Interrupted. To resume:"
  log_error "  - If loading was interrupted: re-run without --skip-load"
  log_error "  - If a workload was interrupted: re-run with --skip-load --skip-build"
  log_error "  - Snapshot location: \${DB_PATH}-snapshot"
  exit 130
}

trap cleanup_on_signal INT TERM

# ============================================================================
# Argument parsing
# ============================================================================

while [ $# -gt 0 ]; do
  case "$1" in
    --workloads)
      WORKLOADS="$2"
      shift 2
      ;;
    --record-count)
      RECORD_COUNT="$2"
      shift 2
      ;;
    --operation-count)
      OPERATION_COUNT="$2"
      shift 2
      ;;
    --threads)
      THREADS="$2"
      shift 2
      ;;
    --throughput-ratio)
      THROUGHPUT_RATIO="$2"
      shift 2
      ;;
    --db-path)
      DB_PATH="$2"
      shift 2
      ;;
    --results-dir)
      RESULTS_DIR="$2"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=true
      shift
      ;;
    --skip-load)
      SKIP_LOAD=true
      shift
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      log_error "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
done

# Apply defaults after argument parsing
THREADS="${THREADS:-$(detect_cpu_count)}"
RESULTS_DIR="${RESULTS_DIR:-./ycsb-results}"
DB_PATH="${DB_PATH:-$RESULTS_DIR/ytdb-data}"

# ============================================================================
# Build
# ============================================================================

if [ "$SKIP_BUILD" = false ]; then
  log "Building ycsb module..."
  (cd "$REPO_ROOT" && ./mvnw -pl ycsb -am package -DskipTests -q)
  log "Build complete."
else
  log "Skipping build (--skip-build)."
fi

# Verify uber-jar exists
resolve_uber_jar >/dev/null

log "Configuration:"
log "  Workloads:        $WORKLOADS"
log "  Record count:     ${RECORD_COUNT:-<from property file>}"
log "  Operation count:  ${OPERATION_COUNT:-<from property file>}"
log "  Threads:          $THREADS"
log "  Throughput ratio: $THROUGHPUT_RATIO"
log "  DB path:          $DB_PATH"
log "  Results dir:      $RESULTS_DIR"

# Create results directory
mkdir -p "$RESULTS_DIR"
