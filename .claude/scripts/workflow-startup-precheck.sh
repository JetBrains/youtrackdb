#!/usr/bin/env bash
# Workflow startup precheck: one JSON blob describing branch divergence,
# workflow-SHA drift, pending handoffs, and the resume state, so the agent
# parses facts instead of re-deriving them from the startup gate prose.
#
# The artifact walk this script implements is specified by
# conventions.md § 1.6 "Workflow-SHA stamps on _workflow/** artifacts" —
# specifically § 1.6(h) "Phase 1 walk bash block" (the enumerate-and-classify
# walk) and the canonical parser idioms in § 1.6(a1) (the anchored
# 'workflow-sha: <40-hex>' value-extraction regex). conventions.md § 1.6 is
# the single source of truth; this script is one implementation of that spec.
# A source-conformance test asserts the script's walk stays byte-faithful to
# the § 1.6(h) block (the walk lands in a later step of this track).
#
# Usage:
#   workflow-startup-precheck.sh --mode {full,divergence-only,migrate-range} \
#       [--bootstrap-sha <40-char-sha>]
#
# Modes:
#   full              Full startup detection: {divergence, drift, handoffs,
#                     state, actions_taken}.
#   divergence-only   A cheap mid-session re-check: {divergence, actions_taken}.
#   migrate-range     The migration stamp-fold range and per-artifact pairs.
#
# --bootstrap-sha applies only to migrate-range (folded into the range when
# supplied). The script emits JSON to stdout and never prompts, never
# force-pushes, and never resets — those mutations stay agent-side and
# user-gated (the no-prompt invariant: no mode reads stdin or asks the user).
#
# No global `set -e`: matching statusline-command.sh and the byte-source
# detection blocks, the detection paths rely on defensive `|| true` rather
# than errexit, so the empty-handoff and no-divergence paths cannot abort the
# script mid-walk. `jq` (v1.8.1) is present and required; there is no jq-free
# JSON emitter.

# ---------------------------------------------------------------------------
# Argument parsing.
# ---------------------------------------------------------------------------

MODE=""
BOOTSTRAP_SHA=""

usage() {
  # Usage text goes to stderr so it never contaminates the JSON on stdout.
  cat >&2 <<'USAGE'
Usage: workflow-startup-precheck.sh --mode {full,divergence-only,migrate-range} [--bootstrap-sha <40-char-sha>]
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode)
      MODE="$2"
      shift 2
      ;;
    --bootstrap-sha)
      BOOTSTRAP_SHA="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

# Unknown / missing mode: exit non-zero with usage and emit NO JSON. A caller
# that mistypes the mode must see a hard failure on stderr, not a silently
# valid-but-empty JSON blob on stdout.
case "$MODE" in
  full | divergence-only | migrate-range) ;;
  *)
    echo "Unknown mode: '${MODE}'" >&2
    usage
    exit 2
    ;;
esac

# ---------------------------------------------------------------------------
# The single JSON emit point — the one site that knows the JSON shape, so the
# contract has exactly one authoring home (the one-contract-home invariant).
#
# jq makes quoting and escaping correct by construction, but emitting JSON
# null for an absent scalar is NOT automatic: the naive `--arg x "$VAR"` form
# binds an empty shell variable to the JSON empty string "", never null. Each
# nullable scalar therefore uses the explicit
#   ($x | if . == "" then null else . end)
# idiom, and counts add `... else tonumber end` so a present count emits a JSON
# number rather than a quoted string. This is the load-bearing emit surface for
# the behavior-parity contract: downstream `jq -e '.field == null'` assertions
# distinguish a genuinely-absent scalar from an empty string, and parity with
# the prose this script replaces depends on null, not "".
# ---------------------------------------------------------------------------

emit_json() {
  # Detection functions in later steps of this track write the plain shell
  # variables this function reads (divergence_*, drift_*, handoffs, state).
  # Until they land, the per-mode assembly below emits the pinned scaffold
  # shape: actions_taken is an empty array (a later change wires the no-drift
  # normalization commit into it) and `state` is JSON null (a later change
  # replaces the null with the populated {phase, track, substate} object).
  #
  # ACTIONS_TAKEN_JSON is a JSON array literal so callers that record an
  # autonomous mutation can replace the empty `[]` without re-threading the
  # null idiom. The scaffold leaves it empty.
  local actions_taken_json="${ACTIONS_TAKEN_JSON:-[]}"

  case "$MODE" in
    full)
      # `state` is JSON null in this scaffold; the state parser fills it in a
      # later change. The null literal is injected via --argjson so jq treats
      # it as the JSON value null, not the string "null".
      jq -n \
        --argjson actions_taken "$actions_taken_json" \
        --argjson state "${STATE_JSON:-null}" \
        '{
          divergence: null,
          drift: null,
          handoffs: [],
          state: $state,
          actions_taken: $actions_taken
        }'
      ;;
    divergence-only)
      jq -n \
        --argjson actions_taken "$actions_taken_json" \
        '{
          divergence: null,
          actions_taken: $actions_taken
        }'
      ;;
    migrate-range)
      # migrate-range emits no state/handoffs/divergence — only the migration
      # range and per-artifact pairs. The detection fields are filled by a
      # later step of this track; the scaffold pins the key set.
      #
      # `base_sha` is the first live nullable scalar wired through the
      # empty->null idiom: an absent --bootstrap-sha (BOOTSTRAP_SHA empty)
      # must emit JSON null, never the empty string "". A later step replaces
      # this with the folded BASE_SHA derived from the merge-base walk; until
      # then the scaffold sources it from --bootstrap-sha so the idiom is
      # genuinely exercised (and pinned by the null-vs-empty test) rather than
      # a hard-coded null literal.
      jq -n \
        --arg bootstrap_sha "$BOOTSTRAP_SHA" \
        '{
          stamped_artifacts: [],
          unstamped_files: [],
          base_sha: ($bootstrap_sha | if . == "" then null else . end),
          log_range: null,
          merge_base_failed: []
        }'
      ;;
  esac
}

# In this scaffold step the script runs no detection; it emits the pinned
# per-mode shape directly. Later steps of this track insert the divergence,
# drift, and handoff detection ahead of this call.
emit_json
