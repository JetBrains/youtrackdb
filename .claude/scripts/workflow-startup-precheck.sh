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
  #
  # Bound the startup fetch so a stalled-but-resolving remote cannot hang
  # session startup. This deliberately diverges from the byte-source
  # (branch-divergence-check.md § Detection uses a bare `git fetch`): that
  # prose runs interactively where a hang is visible, but `full` mode is the
  # session-startup precheck, so an unbounded fetch would block startup for
  # the OS TCP timeout. `timeout 10` falls through to the same fetch-failed
  # skip as an offline remote, so detection parity with the byte-source holds
  # for every outcome except a slow-but-reachable remote >10s, which the
  # downstream per-commit push re-check still catches. `--no-tags` trims the
  # fetch to the branch refs the divergence count actually needs.
  if ! timeout 10 git fetch --no-tags >/dev/null 2>&1; then
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
# migrate-range detection — the artifact walk and stamp-fold the /migrate-workflow
# skill's Step 2 needs, emitted as data the skill reads instead of re-deriving
# the walk in prose.
#
# Byte-source: migrate-workflow/SKILL.md Step 2. The walk is byte-copied from
# conventions.md § 1.6(h) but extended with the one sanctioned § 1.6(h)
# extension — a paired `(file, sha)` array (`STAMPED_PAIRS`, one `$f=$SHA` entry
# per stamped artifact) so a merge-base failure can name the failing artifact
# PATH, not a bare SHA, in the skill's recovery re-prompt. The drift walk above
# never re-prompts on a failing pair, so it omits the pairing; this is why the
# § 1.6(h) source-conformance test scopes its "no STAMPED_PAIRS" assertion to
# the drift walk and treats the pairing here as the whitelisted extension.
#
# The fold reuses the shared fold_stamps_to_base function (the same one the
# drift Phase 2 fold uses) with the "continue" failure mode — distinct from the
# drift gate's "break" mode. The migration's bootstrap re-prompt is user-
# interactive, so the fold continues past every merge-base failure to collect
# the FULL failing set into one batch (FOLD_FAILED_PAIRS), letting the skill
# cover the combined unstamped + merge-base-failed set in one re-prompt. The
# break-shape would re-prompt once per failing pair encountered serially. The
# single shared fold parameterized by this one axis keeps the in-script fold
# single-sourced the way conventions.md § 1.6(h) single-sources the prose walk.
#
# The fold input set is $STAMPED_SHAS plus the optional --bootstrap-sha when the
# skill supplies one (after the user provides a base SHA for the unstamped set);
# absent, the fold runs over $STAMPED_SHAS alone (migrate-workflow/SKILL.md
# Step 2's FOLD_INPUT shape). The script never prompts: an unstamped set with no
# --bootstrap-sha is reported in unstamped_files and the skill drives the prompt
# agent-side, then re-invokes with the validated SHA (the no-prompt invariant:
# no mode reads stdin or asks the user).
#
# detect_migrate_range writes plain shell variables only; the single emit point
# below assembles the migrate-range object from them.
#
# Outputs (script-scoped, consumed by emit_json's migrate-range branch):
#   * MR_STAMPED_PAIRS_JSON  — `[{file, sha}, ...]` for each stamped artifact.
#   * MR_UNSTAMPED_JSON       — `[<path>, ...]` for each unstamped artifact.
#   * MR_BASE_SHA             — the folded BASE_SHA, or "" (-> JSON null) when
#                               the fold did not produce a clean base (no stamps,
#                               or one or more merge-base failures).
#   * MR_LOG_RANGE_JSON       — `[{sha, subject}, ...]` for the BASE_SHA..HEAD
#                               workflow-path commits (oldest first), or the
#                               empty array when the fold produced no base / the
#                               range is empty.
#   * MR_FAILED_PAIRS_JSON    — `[{base, sha, files}, ...]` one entry per failing
#                               merge-base pair, `files` naming the artifact
#                               paths that emitted the failing SHAs (resolved via
#                               STAMPED_PAIRS); the empty array on a clean fold.
# ---------------------------------------------------------------------------

MR_STAMPED_PAIRS_JSON="[]"
MR_UNSTAMPED_JSON="[]"
MR_BASE_SHA=""
MR_LOG_RANGE_JSON="[]"
MR_FAILED_PAIRS_JSON="[]"

# Resolve a failing SHA to the artifact paths that emitted it, via the
# STAMPED_PAIRS `$f=$SHA` table. Mirrors migrate-workflow/SKILL.md Step 2's
# recovery resolver (the `case "$PAIR" in *"=$FAILED_SHA")` match). Prints the
# space-joined matching paths (empty when the SHA is the --bootstrap-sha, which
# has no owning artifact). The pairs and paths carry no shell metacharacters
# (artifact paths under _workflow/, 40-hex SHAs), so word-splitting is safe.
mr_files_for_sha() {
  local target="$1" pair out=""
  for pair in $STAMPED_PAIRS; do
    case "$pair" in
      *"=$target")
        out="$out ${pair%=*}"
        ;;
    esac
  done
  printf '%s' "$out"
}

detect_migrate_range() {
  # Resolve the active plan dir from the current branch per § 1.6(g), the same
  # resolution detect_drift uses.
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  local PLAN_DIR="docs/adr/${branch}"

  # --- Phase 1 walk, byte-copied from conventions.md § 1.6(h), extended with
  # the STAMPED_PAIRS `$f=$SHA` pairing (the whitelisted § 1.6(h) extension
  # migrate-workflow/SKILL.md Step 2 declares) ------------------------------
  # design-mechanics.md is optional; absent until the length trigger fires.
  # The ls 2>/dev/null swallows the stderr for any artifact kind that is not
  # yet present on disk, so missing files do not abort the walk.
  STAMPED_SHAS=""
  UNSTAMPED_FILES=""
  STAMPED_PAIRS=""
  for f in $(ls "$PLAN_DIR/_workflow/implementation-plan.md" \
                "$PLAN_DIR/_workflow/design.md" \
                "$PLAN_DIR/_workflow/design-mechanics.md" \
                "$PLAN_DIR/_workflow/plan/"track-*.md 2>/dev/null); do
      SHA="$(head -1 "$f" | grep -oE 'workflow-sha: [0-9a-f]{40}' | grep -oE '[0-9a-f]{40}$')"
      if [ -n "$SHA" ]; then
          STAMPED_SHAS="$STAMPED_SHAS $SHA"
          STAMPED_PAIRS="$STAMPED_PAIRS $f=$SHA"
      else
          UNSTAMPED_FILES="$UNSTAMPED_FILES $f"
      fi
  done
  # --- end byte-copied walk -------------------------------------------------

  # The `(file, sha)` pairs and unstamped list are emitted regardless of the
  # fold outcome so the skill can name failing artifacts and surface the
  # unstamped set. Build the JSON from the space-delimited word lists with jq.
  MR_STAMPED_PAIRS_JSON="$(printf '%s\n' $STAMPED_PAIRS \
    | jq -Rnc '[inputs | select(length > 0) | {file: sub("=[0-9a-f]+$"; ""), sha: (capture("=(?<s>[0-9a-f]+)$").s)}]')"
  MR_UNSTAMPED_JSON="$(printf '%s\n' $UNSTAMPED_FILES \
    | jq -Rnc '[inputs | select(length > 0)]')"

  # Fold input: $STAMPED_SHAS plus the optional --bootstrap-sha (FOLD_INPUT in
  # migrate-workflow/SKILL.md Step 2). The continue-and-collect fold collects
  # EVERY failing pair into FOLD_FAILED_PAIRS for one batched recovery prompt.
  local fold_input="$STAMPED_SHAS"
  if [ -n "$BOOTSTRAP_SHA" ]; then
    fold_input="$fold_input $BOOTSTRAP_SHA"
  fi
  fold_stamps_to_base continue $fold_input

  if [ -n "$FOLD_FAILED_PAIRS" ]; then
    # One or more merge-base failures. Resolve each failing SHA to its owning
    # artifact path(s) via STAMPED_PAIRS so the skill's re-prompt names the
    # files, not bare SHAs (migrate-workflow/SKILL.md Step 2's recovery
    # resolver). FOLD_FAILED_PAIRS holds `BASE,SHA` entries; split each into the
    # two SHAs, resolve both to paths, and emit one `{base, sha, files}` object
    # per failing pair. The fold's BASE_SHA is discarded when any pair failed
    # (the skill re-prompts and restarts the fold), so MR_BASE_SHA stays empty
    # and MR_LOG_RANGE_JSON stays the empty array — there is no clean range to
    # report yet.
    local pair base_sha failed_sha base_files failed_files
    MR_FAILED_PAIRS_JSON="$(
      for pair in $FOLD_FAILED_PAIRS; do
        base_sha="${pair%,*}"
        failed_sha="${pair#*,}"
        base_files="$(mr_files_for_sha "$base_sha")"
        failed_files="$(mr_files_for_sha "$failed_sha")"
        # Emit one object per pair; jq builds the files array from the
        # space-joined paths (an empty string -> the empty array, e.g. when the
        # failing SHA is the --bootstrap-sha, which owns no artifact).
        jq -nc \
          --arg base "$base_sha" \
          --arg sha "$failed_sha" \
          --arg files "$base_files $failed_files" \
          '{base: $base, sha: $sha, files: ($files | split(" ") | map(select(length > 0)))}'
      done | jq -nc '[inputs]'
    )"
    return
  fi

  # Clean fold: FOLD_BASE_SHA is the oldest stamp reachable from HEAD. Report it
  # and range `git log --reverse $BASE_SHA..HEAD` over the workflow pathspecs
  # (the same trailing-slash pathspecs the drift range uses, excluding the
  # staged subtree by prefix). The byte-source uses the FULL `%H` SHA here (the
  # progress file records range_start/range_end as full SHAs), distinct from the
  # drift range's short `%h`. An empty fold base (no stamps and no
  # --bootstrap-sha) leaves MR_BASE_SHA empty -> JSON null and the range empty.
  MR_BASE_SHA="$FOLD_BASE_SHA"
  if [ -n "$FOLD_BASE_SHA" ]; then
    local log_lines
    log_lines="$(git log --reverse --format='%H %s' "$FOLD_BASE_SHA..HEAD" \
                   -- $WORKFLOW_PATHSPECS 2>/dev/null || true)"
    if [ -n "$log_lines" ]; then
      # Build `[{sha, subject}, ...]` oldest-first. Unlike the drift range, the
      # migration replays every workflow commit, so the full list is emitted
      # (no head -10 cap). sub() splits the first space so a subject with spaces
      # stays whole in the one field.
      MR_LOG_RANGE_JSON="$(printf '%s\n' "$log_lines" \
        | jq -Rnc '[inputs | {sha: (split(" ")[0]), subject: sub("^[^ ]+ *"; "")}]')"
    fi
  fi
}

# ---------------------------------------------------------------------------
# Pending mid-phase handoff scan.
#
# Byte-source: workflow.md § Startup Protocol step 4 and
# mid-phase-handoff.md § "Both /create-plan and /execute-tracks MUST run this
# check". The canonical idiom is:
#
#   ls -t docs/adr/<dir-name>/_workflow/handoff-*.md 2>/dev/null
#
# `-t` sorts most-recent-first by mtime so the resume protocol processes
# handoffs in the correct order; on an mtime tie `ls -t` falls back to
# descending filename order, which matches the byte-source's tie rule
# (mid-phase-handoff.md § Resume protocol step 2). The active plan dir is
# `docs/adr/<branch>` per § 1.6(g), the same resolution detect_drift uses.
#
# `handoffs` carries the file BASENAMES (e.g. "handoff-track-4-phaseC.md"), not
# the full paths — the agent resolves them under the known plan dir
# (design.md § "The JSON contract"). The `2>/dev/null` glob means a plan dir
# with no handoff yields no lines, so the empty-safe path produces the empty
# JSON array `[]` (no handoff pending — the common case at session start).
#
# scan_handoffs writes the JSON array literal directly into HANDOFFS_JSON
# (consumed by emit_json via --argjson) because the order must survive into
# the array and a plain space-delimited word list cannot preserve a basename
# containing a space — handoff basenames never do, but building the array with
# jq keeps the emit contract uniform with the other detection objects.
# ---------------------------------------------------------------------------

# Output of scan_handoffs, consumed by emit_json. Defaults to the empty array
# so emit_json never reads an unset variable on a mode that skips the scan.
HANDOFFS_JSON="[]"

scan_handoffs() {
  # Resolve the active plan dir from the current branch per § 1.6(g), matching
  # detect_drift's resolution. A detached HEAD yields an empty branch name and
  # therefore a plan dir that matches no handoff, collapsing cleanly to the
  # empty-array path rather than aborting the no-errexit script.
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  local plan_dir="docs/adr/${branch}"

  # `ls -t` lists most-recent-first by mtime (filename-descending on a tie).
  # The trailing `|| true` keeps the no-errexit script from carrying a failure
  # status when the glob matches nothing (ls exits non-zero on no match even
  # with 2>/dev/null swallowing the diagnostic). `basename` strips the plan-dir
  # prefix so the array carries just the file names; the order is preserved
  # because the loop reads ls's already-sorted lines top to bottom.
  local listing
  listing="$(ls -t "$plan_dir/_workflow/"handoff-*.md 2>/dev/null || true)"

  if [ -z "$listing" ]; then
    # Empty-safe: no handoff pending. The pinned empty array stands.
    HANDOFFS_JSON="[]"
    return
  fi

  # Reduce each full path to its basename, preserving the ls -t order, and
  # build the JSON array with jq (jq -R reads each raw line; -s slurps the
  # lines into one array so the emitted order matches the input order).
  HANDOFFS_JSON="$(printf '%s\n' "$listing" \
    | while IFS= read -r path; do
        [ -n "$path" ] && basename "$path"
      done \
    | jq -Rnc '[inputs]')"
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
  # Divergence (DIVERGENCE_*), drift (DRIFT_* + the STAMPED_SHAS /
  # UNSTAMPED_FILES classification), and the handoff scan (HANDOFFS_JSON) are
  # populated; emit assembles them via divergence_json, drift_json, and the
  # HANDOFFS_JSON array literal. The state parser lands in a later track, so
  # `state` is still JSON null. actions_taken stays an empty array (a later
  # track wires the no-drift normalization commit into it).
  #
  # ACTIONS_TAKEN_JSON is a JSON array literal so callers that record an
  # autonomous mutation can replace the empty `[]` without re-threading the
  # null idiom. The scaffold leaves it empty.
  local actions_taken_json="${ACTIONS_TAKEN_JSON:-[]}"
  local handoffs_json="${HANDOFFS_JSON:-[]}"
  local divergence_obj drift_obj
  divergence_obj="$(divergence_json)"
  drift_obj="$(drift_json)"

  case "$MODE" in
    full)
      # `state` is JSON null in this scaffold; the state parser fills it in a
      # later track. The null literal is injected via --argjson so jq treats
      # it as the JSON value null, not the string "null". `handoffs` is the
      # scan_handoffs array literal (the empty array when no handoff is
      # pending), spliced via --argjson so the ls -t mtime order survives.
      jq -n \
        --argjson actions_taken "$actions_taken_json" \
        --argjson divergence "$divergence_obj" \
        --argjson drift "$drift_obj" \
        --argjson handoffs "$handoffs_json" \
        --argjson state "${STATE_JSON:-null}" \
        '{
          divergence: $divergence,
          drift: $drift,
          handoffs: $handoffs,
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
      # range and per-artifact pairs (migrate-workflow/SKILL.md Step 2). The
      # detection function detect_migrate_range filled the MR_* variables:
      #   * stamped_artifacts — `[{file, sha}, ...]` per stamped artifact.
      #   * unstamped_files    — `[<path>, ...]` per unstamped artifact.
      #   * base_sha           — the folded BASE_SHA (full %H SHA), or JSON null
      #                          when the fold produced no clean base.
      #   * log_range          — `[{sha, subject}, ...]` for BASE_SHA..HEAD
      #                          (oldest first, no head cap — the migration
      #                          replays every workflow commit), or the empty
      #                          array when the fold base is absent / the range
      #                          is empty.
      #   * merge_base_failed  — `[{base, sha, files}, ...]` per failing pair,
      #                          `files` naming the owning artifact paths.
      #
      # `base_sha` runs through the empty->null idiom (an absent fold base must
      # emit JSON null, never ""); the three arrays are JSON literals spliced
      # via --argjson. The key set is unchanged from the scaffold.
      jq -n \
        --arg base_sha "$MR_BASE_SHA" \
        --argjson stamped_artifacts "$MR_STAMPED_PAIRS_JSON" \
        --argjson unstamped_files "$MR_UNSTAMPED_JSON" \
        --argjson log_range "$MR_LOG_RANGE_JSON" \
        --argjson merge_base_failed "$MR_FAILED_PAIRS_JSON" \
        '{
          stamped_artifacts: $stamped_artifacts,
          unstamped_files: $unstamped_files,
          base_sha: ($base_sha | if . == "" then null else . end),
          log_range: $log_range,
          merge_base_failed: $merge_base_failed
        }'
      ;;
  esac
}

# Run the detection each mode needs, then emit. `full` and `divergence-only`
# both report `divergence`, so they run detect_divergence; `full` additionally
# runs the drift Phase 1+2 walk via detect_drift and the pending-handoff scan
# via scan_handoffs (only `full` carries `handoffs`). `migrate-range` emits no
# `divergence` and skips it (the byte-source migrate-range walk does not compute
# ahead/behind); it runs detect_migrate_range, which walks the artifacts (with
# the STAMPED_PAIRS pairing), folds the stamp set continue-and-collect, and
# ranges `git log`.
case "$MODE" in
  full)
    detect_divergence
    detect_drift
    scan_handoffs
    ;;
  divergence-only)
    detect_divergence
    ;;
  migrate-range)
    detect_migrate_range
    ;;
esac

emit_json
