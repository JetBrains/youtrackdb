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
# Workflow-SHA drift detection — Phase 1 (artifact walk + classification) and
# Phase 2 (pairwise merge-base fold + `git log`).
#
# Byte-source: conventions.md § 1.6(h) "Phase 1 walk bash block" (the
# enumerate-and-classify walk) and the anchored value-extraction regex in
# § 1.6(a1) for Phase 1; workflow-drift-check.md § Detection for the Phase 2
# fold (the `break`-on-first-failure shape) and the `git log` range. The drift
# check (workflow-drift-check.md § Detection) and the /migrate-workflow skill
# both byte-copy the same § 1.6(h) Phase-1 block, so this script's walk must
# stay byte-faithful to it; a source-extraction conformance test (see tests/)
# asserts the glob set and regex match the canonical block.
#
# Phase 1 classifies each active-plan _workflow/** artifact as stamped (a
# line-1 `<!-- workflow-sha: <40-hex> -->` comment present) or unstamped, and
# records the two sets in plain shell variables. Phase 2 consumes the all-
# stamped set, folds it pairwise through `git merge-base` to derive BASE_SHA
# (conventions.md § 1.6(c)), then runs `git log $BASE_SHA..HEAD` against the
# workflow pathspecs to populate base_sha / commit_count / first_commits.
#
# § 1.6(h) resolves the active plan dir per § 1.6(g): the dir is
# `docs/adr/<dir-name>` where `<dir-name>` is the current git branch name. The
# byte-source block carries the literal `PLAN_DIR="docs/adr/<dir-name>"` line
# with `<dir-name>` as a placeholder downstream writers replace at invocation
# time; this script resolves it from the branch. The branch resolution is the
# one line that legitimately differs from the byte-source placeholder (the
# § 1.6(h) prose states the placeholder is substituted at invocation), so the
# conformance test compares the walk's glob set and regex, not the PLAN_DIR
# assignment.
#
# Three Phase-1 outcomes (matching workflow-drift-check.md § Detection):
#   * both sets empty   -> no stampable artifact on disk; silent no-drift
#                          (detected=false, kind=null, scalars null).
#   * any unstamped     -> drift detected unconditionally; kind="unstamped",
#                          fold scalars null (no fold runs for an unstamped set;
#                          the bootstrap prompt covers it agent-side per
#                          § 1.6(d)).
#   * all stamped       -> Phase 2: fold the stamp set pairwise through
#                          `git merge-base` to derive BASE_SHA, then run
#                          `git log $BASE_SHA..HEAD` on the workflow pathspecs.
#                          A non-empty `git log` is drift (detected=true,
#                          kind="stamped", base_sha / commit_count /
#                          first_commits filled); an empty `git log` is no drift
#                          (detected=false, kind="stamped", base_sha filled,
#                          commit_count=0, first_commits=[]). A merge-base
#                          failure during the fold short-circuits to
#                          kind="merge-base-failed" with null fold scalars (the
#                          § 1.6(c) recovery prompt stays agent-side).
#
# detect_drift writes plain shell variables only; the single emit point below
# assembles the `drift` object from them via drift_json.
# ---------------------------------------------------------------------------

# Outputs of detect_drift, consumed by emit_json via drift_json. Defaults cover
# the empty-input no-drift path so emit_json never reads an unset variable.
DRIFT_DETECTED="false"
DRIFT_KIND=""
DRIFT_BASE_SHA=""
DRIFT_COMMIT_COUNT=""
DRIFT_FIRST_COMMITS_JSON="[]"
# normalization_landed is hard-false in this branch — the no-drift
# normalization commit is wired in a later track; nothing here mutates the tree.
DRIFT_NORMALIZATION_LANDED="false"

# Classification arrays, also script-scoped so the Phase 2 fold and
# migrate-range (a later step) reuse the same walk output rather than
# re-walking. Leading-space-delimited word lists, matching the byte-source.
STAMPED_SHAS=""
UNSTAMPED_FILES=""

# The workflow pathspecs the drift `git log` ranges against. Trailing slashes
# make the directory intent explicit and exclude the staged subtree at
# `docs/adr/*/_workflow/staged-workflow/.claude/{workflow,skills}/` by prefix
# difference (workflow-drift-check.md § Detection is the canonical source for
# this exclusion). A leading-/internal-space-delimited word list so the `git
# log` invocation can splice it after `--` unquoted (the two paths contain no
# shell metacharacters).
WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/"

# ---------------------------------------------------------------------------
# Shared pairwise merge-base fold (conventions.md § 1.6(c)).
#
# Folds a stamp set pairwise through `git merge-base` to derive BASE_SHA, the
# oldest stamp reachable from HEAD. The two callers differ only in how they
# handle a merge-base failure (a stamp on a git-gc-pruned commit, or two stamps
# with no reachable common ancestor in the local repo), so the loop is
# single-sourced here and parameterized by that one axis:
#
#   * "break"    (full / drift-check, workflow-drift-check.md § Detection):
#                the gate has no recovery prompt to amortise, so it records the
#                first failing pair and stops folding.
#   * "continue" (migrate-range, migrate-workflow/SKILL.md Step 2): the
#                migration's bootstrap re-prompt is user-interactive, so the
#                fold collects EVERY failing pair into one batch before exiting.
#
# Inputs (positional): $1 = failure mode ("break" | "continue"); $2.. = the
# stamp set to fold (each a 40-hex SHA).
# Outputs (script-scoped, the caller reads them after the call):
#   * FOLD_BASE_SHA           — the folded BASE_SHA, or "" when a failure
#                               reset it (break mode leaves "" on failure;
#                               continue mode leaves the last successful fold
#                               value, which the caller discards when
#                               FOLD_FAILED_PAIRS is non-empty).
#   * FOLD_FAILED_PAIRS       — space-delimited `BASE,SHA` failing pairs (one
#                               in break mode, all of them in continue mode);
#                               empty when the fold succeeded end to end.
# The function performs no git mutation and prints nothing to stdout.
# ---------------------------------------------------------------------------
fold_stamps_to_base() {
  local fail_mode="$1"
  shift

  FOLD_BASE_SHA=""
  FOLD_FAILED_PAIRS=""

  local sha new_base
  for sha in "$@"; do
    if [ -z "$FOLD_BASE_SHA" ]; then
      # Seed the fold with the first stamp.
      FOLD_BASE_SHA="$sha"
      continue
    fi
    # `git merge-base A B` prints the best common ancestor; the `|| new_base=""`
    # keeps the no-errexit script from carrying a stale value when the two
    # SHAs share no reachable ancestor (or one is pruned).
    new_base="$(git merge-base "$FOLD_BASE_SHA" "$sha" 2>/dev/null)" || new_base=""
    if [ -z "$new_base" ]; then
      # Record the failing pair (`BASE,SHA`). Reset FOLD_BASE_SHA so a failed
      # value never propagates into a subsequent merge-base call.
      FOLD_FAILED_PAIRS="$FOLD_FAILED_PAIRS $FOLD_BASE_SHA,$sha"
      FOLD_BASE_SHA=""
      if [ "$fail_mode" = "break" ]; then
        # The drift gate stops at the first failure: it has no recovery prompt
        # to amortise, so it routes this one pair to the unstamped path and
        # signals drift without continuing the fold.
        break
      fi
      # continue mode (migrate-range): keep folding to collect every failing
      # pair into FOLD_FAILED_PAIRS for one batched recovery prompt.
      continue
    fi
    FOLD_BASE_SHA="$new_base"
  done
}

detect_drift() {
  # Resolve the active plan dir from the current branch per § 1.6(g). The
  # branch name is the <dir-name> placeholder in the § 1.6(h) literal
  # PLAN_DIR line; an absent branch (detached HEAD) yields an empty name and
  # therefore a PLAN_DIR that matches no artifact, collapsing cleanly to the
  # empty-input no-drift path rather than aborting the no-errexit script.
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  local PLAN_DIR="docs/adr/${branch}"

  # --- Phase 1 walk, byte-copied from conventions.md § 1.6(h) --------------
  # design-mechanics.md is optional; absent until the length trigger fires.
  # The ls 2>/dev/null swallows the stderr for any artifact kind that is not
  # yet present on disk, so missing files do not abort the walk.
  for f in $(ls "$PLAN_DIR/_workflow/implementation-plan.md" \
                "$PLAN_DIR/_workflow/design.md" \
                "$PLAN_DIR/_workflow/design-mechanics.md" \
                "$PLAN_DIR/_workflow/plan/"track-*.md 2>/dev/null); do
      SHA="$(head -1 "$f" | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$')"
      if [ -n "$SHA" ]; then
          STAMPED_SHAS="$STAMPED_SHAS $SHA"
      else
          UNSTAMPED_FILES="$UNSTAMPED_FILES $f"
      fi
  done
  # --- end byte-copied walk -------------------------------------------------

  # Phase-1 classification decision (workflow-drift-check.md § Detection).
  if [ -z "$STAMPED_SHAS" ] && [ -z "$UNSTAMPED_FILES" ]; then
    # No stampable artifact on disk (a fresh _workflow/ holding only a
    # transient handoff-*.md). Silent no-drift; kind stays null.
    DRIFT_DETECTED="false"
    DRIFT_KIND=""
    return
  fi

  if [ -n "$UNSTAMPED_FILES" ]; then
    # Any unstamped artifact short-circuits to drift detected: no fold, no
    # `git log`. The fold scalars stay null; the bootstrap prompt that gathers
    # a base SHA for the unstamped set stays agent-side (§ 1.6(d)).
    DRIFT_DETECTED="true"
    DRIFT_KIND="unstamped"
    return
  fi

  # Every artifact is stamped: Phase 2. Fold the stamp set pairwise through
  # `git merge-base` to derive BASE_SHA, then range `git log` against the
  # workflow pathspecs (workflow-drift-check.md § Detection). The drift gate
  # uses the "break" failure mode: it stops at the first merge-base failure
  # because it has no recovery prompt to amortise.
  fold_stamps_to_base break $STAMPED_SHAS

  if [ -n "$FOLD_FAILED_PAIRS" ]; then
    # A merge-base failure routed a stamp pair to the unstamped path
    # (§ 1.6(c)). The drift gate signals drift; the fold scalars stay null and
    # the § 1.6(d) bootstrap prompt that recovers the failing set stays
    # agent-side. The byte-source appends `merge-base-failed:$BASE,$SHA` to
    # UNSTAMPED_FILES so the agent-side resolver can surface it alongside any
    # genuinely-unstamped artifacts; the pair is already in FOLD_FAILED_PAIRS
    # as `$BASE,$SHA`, so prefix it and append.
    local pair
    for pair in $FOLD_FAILED_PAIRS; do
      UNSTAMPED_FILES="$UNSTAMPED_FILES merge-base-failed:$pair"
    done
    DRIFT_DETECTED="true"
    DRIFT_KIND="merge-base-failed"
    return
  fi

  # Fold succeeded: BASE_SHA is the oldest stamp reachable from HEAD. Range
  # `git log --reverse $BASE_SHA..HEAD` against the workflow pathspecs, oldest
  # first. The pathspecs' trailing slashes exclude the staged subtree by prefix
  # difference (WORKFLOW_PATHSPECS comment above). `--format=%h %s` yields one
  # `<short-sha> <subject>` line per commit; the byte-source caps the displayed
  # list at the first ten (`head -10`).
  DRIFT_BASE_SHA="$FOLD_BASE_SHA"
  local log_lines
  log_lines="$(git log --reverse --format='%h %s' "$FOLD_BASE_SHA..HEAD" \
                 -- $WORKFLOW_PATHSPECS 2>/dev/null || true)"

  if [ -z "$log_lines" ]; then
    # Empty range: every stamp is already at or past every workflow commit
    # reachable from HEAD on the watched paths. No drift in the strict sense.
    # base_sha is reported (the fold ran); commit_count is 0 and first_commits
    # is the empty array. (The no-drift normalization sub-step that collapses
    # multiple distinct stamps to one BASE_SHA is a later track; this branch
    # only reports the no-drift read.)
    DRIFT_DETECTED="false"
    DRIFT_KIND="stamped"
    DRIFT_COMMIT_COUNT="0"
    DRIFT_FIRST_COMMITS_JSON="[]"
    return
  fi

  # Non-empty range: drift detected. commit_count is the full range total;
  # first_commits is the first ten subject lines (oldest first), each a
  # `{sha, subject}` object. Build the JSON array with jq from the capped
  # `<short-sha> <subject>` lines (jq -R reads each raw line; sub() splits the
  # first space into sha + subject so a subject containing spaces stays whole).
  DRIFT_DETECTED="true"
  DRIFT_KIND="stamped"
  DRIFT_COMMIT_COUNT="$(printf '%s\n' "$log_lines" | grep -c '')"
  DRIFT_FIRST_COMMITS_JSON="$(printf '%s\n' "$log_lines" | head -10 \
    | jq -Rnc '[inputs | {sha: (split(" ")[0]), subject: sub("^[^ ]+ *"; "")}]')"
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

# Assemble the `drift` JSON object from the plain shell variables detect_drift
# wrote. `detected`/`normalization_landed` are real JSON booleans via the
# `== "true"` test. `kind` is a nullable string (null on the empty-input
# no-drift path, "unstamped"/"stamped"/"merge-base-failed" otherwise) through
# the empty->null idiom. `base_sha` is a nullable string; `commit_count` is a
# nullable count routed through `... else tonumber end` so a present value is a
# JSON number, not a quoted string. `first_commits` is already a JSON array
# literal (DRIFT_FIRST_COMMITS_JSON), spliced via --argjson; it defaults to the
# empty array and the Phase 2 fold (a later step) replaces it.
drift_json() {
  jq -nc \
    --arg detected "$DRIFT_DETECTED" \
    --arg kind "$DRIFT_KIND" \
    --arg base_sha "$DRIFT_BASE_SHA" \
    --arg commit_count "$DRIFT_COMMIT_COUNT" \
    --argjson first_commits "$DRIFT_FIRST_COMMITS_JSON" \
    --arg normalization_landed "$DRIFT_NORMALIZATION_LANDED" \
    '{
      detected: ($detected == "true"),
      kind: ($kind | if . == "" then null else . end),
      base_sha: ($base_sha | if . == "" then null else . end),
      commit_count: ($commit_count | if . == "" then null else tonumber end),
      first_commits: $first_commits,
      normalization_landed: ($normalization_landed == "true")
    }'
}

emit_json() {
  # Detection functions write the plain shell variables this function reads.
  # Divergence (DIVERGENCE_*) and drift (DRIFT_* + the STAMPED_SHAS /
  # UNSTAMPED_FILES classification) are populated; emit assembles them via
  # divergence_json and drift_json. The handoff scan and the state parser land
  # in later steps, so `handoffs` is still a pinned empty array and `state` is
  # still JSON null. actions_taken stays an empty array (a later track wires the
  # no-drift normalization commit into it).
  #
  # ACTIONS_TAKEN_JSON is a JSON array literal so callers that record an
  # autonomous mutation can replace the empty `[]` without re-threading the
  # null idiom. The scaffold leaves it empty.
  local actions_taken_json="${ACTIONS_TAKEN_JSON:-[]}"
  local divergence_obj drift_obj
  divergence_obj="$(divergence_json)"
  drift_obj="$(drift_json)"

  case "$MODE" in
    full)
      # `state` is JSON null in this scaffold; the state parser fills it in a
      # later change. The null literal is injected via --argjson so jq treats
      # it as the JSON value null, not the string "null".
      jq -n \
        --argjson actions_taken "$actions_taken_json" \
        --argjson divergence "$divergence_obj" \
        --argjson drift "$drift_obj" \
        --argjson state "${STATE_JSON:-null}" \
        '{
          divergence: $divergence,
          drift: $drift,
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
# both report `divergence`, so they run detect_divergence; `full` additionally
# runs the drift Phase 1 walk via detect_drift. `migrate-range` emits no
# `divergence` and skips it (the byte-source migrate-range walk does not compute
# ahead/behind); its own walk + fold land in a later step. A later step also
# inserts the handoff scan for the `full` mode.
case "$MODE" in
  full)
    detect_divergence
    detect_drift
    ;;
  divergence-only)
    detect_divergence
    ;;
esac

emit_json
