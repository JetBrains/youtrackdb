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
# Branch-divergence detection.
#
# Byte-source: branch-divergence-check.md § Detection. The three commands run
# in order:
#
#   git rev-parse --abbrev-ref --symbolic-full-name '@{u}'   # upstream guard
#   git fetch                                                 # fetch guard
#   git rev-list --left-right --count HEAD...'@{u}'           # ahead<TAB>behind
#
# The upstream check runs first so a branch with no upstream skips cleanly
# without attempting the fetch. `git fetch` (no argument) targets the
# upstream's remote, which is not always `origin`. `git rev-list
# --left-right --count HEAD...'@{u}'` prints `<ahead>\t<behind>`
# (tab-separated). The branch has diverged iff BOTH counts are non-zero —
# `git status -sb` does not emit the literal word `diverged`, so the prose
# does not grep for it and neither does this script.
#
# The two guards map to the two skip reasons the prose calls out:
#   * upstream absent  -> skipped=true, skip_reason="no-upstream", detected=false
#   * fetch fails      -> skipped=true, skip_reason="fetch-failed", detected=false
# Both are normal, expected states (no upstream configured; offline). The
# script reports them as data; the conversational resolution stays agent-side.
#
# The detection writes plain shell variables only (no JSON here) — the single
# emit_json site below assembles the `divergence` object from them.
# ---------------------------------------------------------------------------

# Outputs of detect_divergence, consumed by emit_json. Defaults cover the
# skip paths so emit_json never reads an unset variable.
DIVERGENCE_DETECTED="false"
DIVERGENCE_AHEAD=""
DIVERGENCE_BEHIND=""
DIVERGENCE_SKIPPED="false"
DIVERGENCE_SKIP_REASON=""

detect_divergence() {
  # Upstream guard. `@{u}` resolves to the configured upstream tracking ref;
  # absent (no upstream set) means the divergence check cannot run, so skip
  # cleanly with skip_reason="no-upstream". 2>/dev/null suppresses git's
  # "no upstream configured" diagnostic so it never reaches the JSON-bearing
  # stdout.
  if ! git rev-parse --abbrev-ref --symbolic-full-name '@{u}' >/dev/null 2>&1; then
    DIVERGENCE_SKIPPED="true"
    DIVERGENCE_SKIP_REASON="no-upstream"
    DIVERGENCE_DETECTED="false"
    return
  fi

  # Fetch guard. `git fetch` (no argument) targets the upstream's remote.
  # A failure (offline, removed remote, auth) means the local view of the
  # upstream may be stale, so skip with skip_reason="fetch-failed" rather
  # than compute a divergence count against a possibly-stale ref. Output is
  # routed to stderr so progress lines never contaminate the JSON on stdout.
  if ! git fetch >/dev/null 2>&1; then
    DIVERGENCE_SKIPPED="true"
    DIVERGENCE_SKIP_REASON="fetch-failed"
    DIVERGENCE_DETECTED="false"
    return
  fi

  # Both guards passed: compute ahead/behind. `git rev-list --left-right
  # --count HEAD...'@{u}'` prints `<ahead>\t<behind>`. The triple-dot
  # symmetric-difference form is the byte-source idiom; `cut` splits the two
  # tab-separated counts. The `|| echo` fallback keeps the no-errexit script
  # from carrying a stale value if rev-list itself fails for an unexpected
  # reason (treated as a clean no-divergence read, which the first per-commit
  # push would surface anyway).
  local counts
  counts="$(git rev-list --left-right --count HEAD...'@{u}' 2>/dev/null || echo '0	0')"
  DIVERGENCE_AHEAD="$(printf '%s' "$counts" | cut -f1)"
  DIVERGENCE_BEHIND="$(printf '%s' "$counts" | cut -f2)"

  # Diverged iff BOTH counts are non-zero. A purely-ahead or purely-behind
  # branch fast-forwards in one direction and is not a divergence.
  if [ "$DIVERGENCE_AHEAD" -gt 0 ] 2>/dev/null && [ "$DIVERGENCE_BEHIND" -gt 0 ] 2>/dev/null; then
    DIVERGENCE_DETECTED="true"
  else
    DIVERGENCE_DETECTED="false"
  fi
}

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

# Assemble the `divergence` JSON object from the plain shell variables
# detect_divergence wrote. Emitted as a compact JSON value on stdout so the
# per-mode jq below can splice it in via --argjson.
#
# `ahead`/`behind` are counts: a number when both guards passed, JSON null on
# either skip path (no count was computed). The empty->null-then-tonumber
# idiom collapses the empty skip-path value to null and converts a present
# value to a JSON number rather than a quoted string. `skip_reason` is a
# nullable string (null off the skip paths). `detected`/`skipped` are real
# JSON booleans via the `== "true"` test.
divergence_json() {
  jq -nc \
    --arg detected "$DIVERGENCE_DETECTED" \
    --arg ahead "$DIVERGENCE_AHEAD" \
    --arg behind "$DIVERGENCE_BEHIND" \
    --arg skipped "$DIVERGENCE_SKIPPED" \
    --arg skip_reason "$DIVERGENCE_SKIP_REASON" \
    '{
      detected: ($detected == "true"),
      ahead: ($ahead | if . == "" then null else tonumber end),
      behind: ($behind | if . == "" then null else tonumber end),
      skipped: ($skipped == "true"),
      skip_reason: ($skip_reason | if . == "" then null else . end)
    }'
}

emit_json() {
  # Detection functions in later steps of this track write the plain shell
  # variables this function reads (drift_*, handoffs, state). The divergence
  # detection above already populates DIVERGENCE_*; emit assembles them via
  # divergence_json. Until the remaining detection lands, the per-mode
  # assembly below emits the pinned scaffold shape for the not-yet-built
  # fields: `drift` is null, `handoffs` is an empty array, actions_taken is an
  # empty array (a later change wires the no-drift normalization commit into
  # it), and `state` is JSON null (a later change replaces the null with the
  # populated {phase, track, substate} object).
  #
  # ACTIONS_TAKEN_JSON is a JSON array literal so callers that record an
  # autonomous mutation can replace the empty `[]` without re-threading the
  # null idiom. The scaffold leaves it empty.
  local actions_taken_json="${ACTIONS_TAKEN_JSON:-[]}"
  local divergence_obj
  divergence_obj="$(divergence_json)"

  case "$MODE" in
    full)
      # `state` is JSON null in this scaffold; the state parser fills it in a
      # later change. The null literal is injected via --argjson so jq treats
      # it as the JSON value null, not the string "null".
      jq -n \
        --argjson actions_taken "$actions_taken_json" \
        --argjson divergence "$divergence_obj" \
        --argjson state "${STATE_JSON:-null}" \
        '{
          divergence: $divergence,
          drift: null,
          handoffs: [],
          state: $state,
          actions_taken: $actions_taken
        }'
      ;;
    divergence-only)
      jq -n \
        --argjson actions_taken "$actions_taken_json" \
        --argjson divergence "$divergence_obj" \
        '{
          divergence: $divergence,
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

# Run the detection each mode needs, then emit. `full` and `divergence-only`
# both report `divergence`, so they run detect_divergence; `migrate-range`
# emits no `divergence` and skips it (the byte-source migrate-range walk does
# not compute ahead/behind). Later steps of this track insert the drift and
# handoff detection alongside divergence for the `full` mode.
case "$MODE" in
  full | divergence-only)
    detect_divergence
    ;;
esac

emit_json
