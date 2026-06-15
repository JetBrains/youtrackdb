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
#       [--bootstrap-sha <40-char-sha>] [--exclude-sha <sha> ...]
#   workflow-startup-precheck.sh --append-ledger \
#       [--ctx <level>] [--phase <token>] [--track <n>] [--tier <token>] \
#       [--categories <text>] [--s17 <token>] [--paused <event>]
#
# Modes:
#   full              Full startup detection: {divergence, drift, handoffs,
#                     state, actions_taken}.
#   divergence-only   A cheap mid-session re-check: {divergence, actions_taken}.
#   migrate-range     The migration stamp-fold range and per-artifact pairs.
#   append-ledger     Append one event line to the phase ledger
#                     (`<plan_dir>/_workflow/phase-ledger.md`) and exit. This is
#                     the only mode that mutates a workflow artifact and the only
#                     mode that emits no JSON. It is selected by the standalone
#                     `--append-ledger` flag, not `--mode append-ledger`.
#
# --bootstrap-sha applies only to migrate-range (folded into the range when
# supplied). --exclude-sha also applies only to migrate-range and is
# repeatable: each value names a stamp SHA to drop from the merge-base fold
# input, so a stamp on a pruned or unreachable commit cannot re-produce a
# merge-base failure on a --bootstrap-sha re-invocation (the /migrate-workflow
# skill's agent-side recovery drives this). The script emits JSON to stdout and
# never prompts, never force-pushes, and never resets — those mutations stay
# agent-side and user-gated (the no-prompt invariant: no mode reads stdin or
# asks the user).
#
# --- The phase ledger (the Track-2 consumer contract) -----------------------
#
# `--append-ledger` appends ONE event line per phase boundary to
# `<plan_dir>/_workflow/phase-ledger.md`, an append-only, unstamped event log
# (conventions.md § 1.6(f) exclusion). The orchestrator calls it at the same
# boundaries it flips plan checkboxes today. The grammar is fixed and is the
# contract `determine_state` greps and Track 2's runtime consumers read:
#
#   [<ISO>] [ctx=<level>] phase=<v> track=<v> tier=<v> categories="<v>" s17=<v> paused=<v>
#
#   * One entry per line; the leading `[<ISO>]` timestamp and `[ctx=<level>]`
#     marker are always present, then the `key=value` fields that the append
#     was given (an unsupplied key is simply omitted from that line).
#   * The key set is exactly { phase, track, tier, categories, s17, paused }.
#     `categories` is the one quoted value (it may carry spaces and commas, e.g.
#     `categories="Workflow machinery,Architecture"`); every other value is a
#     bare metacharacter-free token.
#   * Read semantics are LAST-VALUE-WINS PER KEY across the whole file: a reader
#     scans every line and keeps the most recent value seen for each key, so a
#     mid-flight tier or phase change is recorded by APPENDING a new line rather
#     than rewriting an old one. `phase` and `track` feed determine_state's
#     two-level resume (the ledger owns the top-level phase and the active
#     track; the track file's `## Progress` owns the within-track sub-state).
#   * The append is atomic via temp-file-plus-rename: the new line is written to
#     a sibling temp file holding `(old contents)+(new line)`, then `mv`'d over
#     the ledger. A crash mid-write leaves either the prior ledger or the
#     temp file, never a torn ledger, so a torn append leaves the prior tail
#     intact and determine_state resolves the prior state.
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
# Space-delimited accumulator of --exclude-sha values (40-hex SHAs, no
# metacharacters, so unquoted word-splitting downstream is safe). Each value is
# dropped from the migrate-range fold input by detect_migrate_range.
EXCLUDE_SHAS=""

# --append-ledger selector + its per-key field accumulators. The flag is
# standalone (not a --mode value) so a caller cannot accidentally mix a JSON
# reporting mode with the ledger mutation. LEDGER_* default to the empty string;
# an empty field is omitted from the appended line (an append never emits an
# empty `key=` token). LEDGER_CTX defaults to "safe" so an entry always carries a
# `[ctx=…]` marker even when the caller forgets to pass one.
APPEND_LEDGER="0"
LEDGER_CTX=""
LEDGER_PHASE=""
LEDGER_TRACK=""
LEDGER_TIER=""
LEDGER_CATEGORIES=""
LEDGER_S17=""
LEDGER_PAUSED=""

usage() {
  # Usage text goes to stderr so it never contaminates the JSON on stdout.
  cat >&2 <<'USAGE'
Usage: workflow-startup-precheck.sh --mode {full,divergence-only,migrate-range} [--bootstrap-sha <40-char-sha>] [--exclude-sha <sha> ...]
       workflow-startup-precheck.sh --append-ledger [--ctx <level>] [--phase <token>] [--track <n>] [--tier <token>] [--categories <text>] [--s17 <token>] [--paused <event>]
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
    --exclude-sha)
      # Repeatable: accumulate each value into the space-delimited list.
      EXCLUDE_SHAS="$EXCLUDE_SHAS $2"
      shift 2
      ;;
    --append-ledger)
      # Standalone selector — no value follows. Selects the ledger-append
      # mutation mode; the --phase/--track/... flags below carry the event.
      APPEND_LEDGER="1"
      shift 1
      ;;
    --ctx)
      LEDGER_CTX="$2"
      shift 2
      ;;
    --phase)
      LEDGER_PHASE="$2"
      shift 2
      ;;
    --track)
      LEDGER_TRACK="$2"
      shift 2
      ;;
    --tier)
      LEDGER_TIER="$2"
      shift 2
      ;;
    --categories)
      LEDGER_CATEGORIES="$2"
      shift 2
      ;;
    --s17)
      LEDGER_S17="$2"
      shift 2
      ;;
    --paused)
      LEDGER_PAUSED="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

# Mode selection. --append-ledger is mutually exclusive with --mode: the ledger
# append is a mutation that emits no JSON, the --mode values are JSON reporters.
# Mixing them is a caller error.
if [ "$APPEND_LEDGER" = "1" ]; then
  if [ -n "$MODE" ]; then
    echo "--append-ledger and --mode are mutually exclusive" >&2
    usage
    exit 2
  fi
else
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
fi

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
# normalization_landed defaults false and flips true only when the no-drift
# normalization path below lands its single stamp-folding commit. Every other
# path (drift detected, unstamped, merge-base-failed, empty-input, already-
# uniform stamps) leaves it false: those paths mutate nothing.
DRIFT_NORMALIZATION_LANDED="false"

# Classification arrays, also script-scoped so the Phase 2 fold and
# migrate-range (a later step) reuse the same walk output rather than
# re-walking. Leading-space-delimited word lists, matching the byte-source.
STAMPED_SHAS=""
UNSTAMPED_FILES=""

# actions_taken array literal. The no-drift normalization path below is the
# script's only autonomous mutation, so it is the only writer of this variable;
# on a landed commit it replaces the empty `[]` with a one-element array naming
# the normalization commit (action label, short SHA, subject). Every other path
# leaves the empty array. emit_json splices it via ${ACTIONS_TAKEN_JSON:-[]}.
ACTIONS_TAKEN_JSON="[]"

# The workflow pathspecs the drift `git log` ranges against. Trailing slashes
# make the directory intent explicit and exclude the staged subtree at
# `docs/adr/*/_workflow/staged-workflow/.claude/{workflow,skills,agents}/` by
# prefix difference (workflow-drift-check.md § Detection is the canonical source
# for this exclusion). A leading-/internal-space-delimited word list so the `git
# log` invocation can splice it after `--` unquoted (the three paths contain no
# shell metacharacters).
WORKFLOW_PATHSPECS=".claude/workflow/ .claude/skills/ .claude/agents/"

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

# ---------------------------------------------------------------------------
# No-drift normalization — the script's only autonomous mutation.
#
# Byte-source: workflow-drift-check.md § No-drift normalization. Fires only on
# the no-drift path (empty `git log` range) AND when the stamp set carries more
# than one distinct SHA — the active plan's stamps fold to the same BASE_SHA yet
# sit on different commits on disk. Rewriting every artifact's line-1 stamp to
# BASE_SHA collapses the next gate's fold input to a single-element set, keeping
# the gate O(1) on subsequent runs. The fold result the byte-source names
# $BASE_SHA is the script's $DRIFT_BASE_SHA, passed in as $1; the byte-source's
# bare $BASE_SHA would expand empty under this script's no-`set -u` posture and
# rewrite every stamp blank, so binding it to the resolved fold result is the
# one variable adaptation the move into a function forces.
#
# All-or-nothing contract: either every stamp moves to BASE_SHA in one commit,
# or the on-disk state is unchanged. Two diff-shape guards enforce it. The
# rewrite is in place (printf + tail, never staged) until both guards pass;
# `git checkout --` alone restores the tree on mismatch (nothing is staged yet),
# so the "stamps rewritten without a commit" in-between state is unreachable
# under correct invocation.
#
# Args: $1 = the folded BASE_SHA (the script's DRIFT_BASE_SHA); $2 = the active
# plan dir (PLAN_DIR, the same value detect_drift resolved from the branch).
#
# Control-flow adaptation vs the byte-source: the byte-source ends the success
# path with a terminal `exit 0`. This function instead `return`s on success so
# the caller flows into emit_json and the `full`-mode JSON still emits with
# drift.normalization_landed and actions_taken reflecting the commit. The abort
# path keeps the byte-source's hard `exit 1` (a non-zero exit that halts the
# calling session) — that asymmetry, success-`return` vs abort-`exit 1`, is the
# second sanctioned adaptation. On success the function sets
# DRIFT_NORMALIZATION_LANDED=true and replaces ACTIONS_TAKEN_JSON's empty `[]`
# with a one-element array naming the commit (action label, short SHA, subject),
# so emit_json reports the mutation in the `full`-mode JSON.
#
# Path-quoting assumption (byte-source): all `_workflow/**` artifact names are
# fixed-template (implementation-plan.md, design.md, design-mechanics.md,
# track-<digits>.md) with no shell metacharacters, so the unquoted expansion of
# $stamped_files below is safe. A future artifact name carrying spaces or
# metacharacters would require a NUL-delimited path list first.
no_drift_normalization() {
  local base_sha="$1" plan_dir="$2"

  # Recompute the stamped-artifact PATH list. The Phase 1 walk exports
  # STAMPED_SHAS / UNSTAMPED_FILES but no companion stamped-path list, so recompute
  # it here under the same enumeration the § 1.6(h) walk uses — keeping the
  # byte-copy contract with § 1.6(h) intact. The grep guard matches the full
  # `<!-- workflow-sha: <40-hex> -->` line-1 comment, so an artifact whose first
  # line merely contains a 40-hex run is not mistaken for a stamped file.
  local stamped_files="" f
  for f in $(ls "$plan_dir/_workflow/implementation-plan.md" \
                "$plan_dir/_workflow/design.md" \
                "$plan_dir/_workflow/design-mechanics.md" \
                "$plan_dir/_workflow/plan/"track-*.md 2>/dev/null); do
    if head -1 "$f" | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'; then
      stamped_files="$stamped_files $f"
    fi
  done

  # Rewrite line 1 of every stamped artifact in place. The portable printf +
  # `tail -n +2` pattern (not `sed -i`, whose `-i` flag differs between BSD and
  # GNU) writes the new stamp then re-appends lines 2.. via a `.tmp` + `mv`. The
  # `&&` runs under the no-`set -e` posture: a failed redirect leaves the
  # original file intact and at worst an orphan `.tmp`, which guard 2 catches as
  # an untracked path inside the plan's `_workflow/`.
  for f in $stamped_files; do
    { printf '<!-- workflow-sha: %s -->\n' "$base_sha"; tail -n +2 "$f"; } > "$f.tmp" \
      && mv "$f.tmp" "$f"
  done

  # Diff-shape guard 1: every hunk header in the unstaged diff of the stamped
  # artifacts must start `@@ -1` (line-1 only). A header naming a different
  # start line means the rewriter touched more than the stamp — abort.
  local diff_bad
  diff_bad="$(git diff -U0 -- $stamped_files | grep -E '^@@' | grep -vE '^@@ -1[, ]' || true)"

  # Diff-shape guard 2: porcelain status scoped to the active plan's `_workflow/`
  # must list only the stamped artifacts. The narrow scope matches the byte-
  # source's narrow dirty-check philosophy (workflow-drift-check.md
  # § No-drift normalization): a whole-repo clean check is too strict (unrelated
  # edits outside the plan's `_workflow/` have no bearing), so a dirty file
  # outside `_workflow/` does not abort. Any path inside the plan's `_workflow/`
  # that the rewrite did not touch (an orphan `.tmp`, a missed artifact type, a
  # pre-existing dirty file) is a path the gate refuses to swallow — abort.
  local porcelain_bad
  porcelain_bad="$(git status --porcelain -- "$plan_dir/_workflow/" | awk '{print $2}' | LC_ALL=C sort -u \
    | comm -23 - <(printf '%s\n' $stamped_files | LC_ALL=C sort -u))"

  if [ -n "$diff_bad" ] || [ -n "$porcelain_bad" ]; then
    # Restore the pre-rewrite state for the stamped artifacts and surface a
    # clear error on stderr (never stdout — the JSON channel stays clean). Nothing
    # is staged at this point, so `git checkout --` alone is sufficient. The hard
    # `exit 1` halts the calling session, byte-faithful to the byte-source: the
    # user inspects the named hunks or paths manually and re-invokes after
    # resolving them. There is no automatic fallthrough.
    git checkout -- $stamped_files
    echo "workflow-sha normalization aborted: diff shape mismatch" >&2
    [ -n "$diff_bad" ]      && echo "  off-line-1 hunks: $diff_bad" >&2
    [ -n "$porcelain_bad" ] && echo "  unexpected paths: $porcelain_bad" >&2
    exit 1
  fi

  # Diff shape verified. Stage the stamped artifacts and land one commit. The
  # subject is byte-identical to the byte-source: `Normalize workflow-sha stamps
  # to <short-BASE_SHA>`, the seven-character abbreviation of BASE_SHA.
  git add -- $stamped_files
  local short_base_sha subject
  short_base_sha="$(printf '%s' "$base_sha" | cut -c1-7)"
  subject="Normalize workflow-sha stamps to $short_base_sha"
  git commit -q -m "$subject"

  # Record that the mutation landed, and report it in actions_taken. This is the
  # script's only autonomous mutation, so it is the only writer of
  # ACTIONS_TAKEN_JSON: replace the empty `[]` with a one-element array naming
  # the commit (the short SHA + the byte-source subject). The commit short SHA is
  # read back with `git rev-parse --short HEAD` (the commit just landed, so HEAD
  # is it); `short_base_sha` above is the BASE_SHA abbreviation in the subject,
  # not the new commit's own hash, so the two are distinct fields. jq assembles
  # the object so quoting/escaping is correct by construction — the subject is a
  # fixed template with no metacharacters, but routing it through `--arg` keeps
  # the one-contract-home idiom the rest of the emit surface uses. `action` is a
  # stable machine-readable label so a downstream reader can branch on the
  # mutation kind without parsing the human-facing subject.
  local commit_sha
  commit_sha="$(git rev-parse --short HEAD 2>/dev/null || true)"
  ACTIONS_TAKEN_JSON="$(jq -nc \
    --arg action "normalize-workflow-sha-stamps" \
    --arg commit "$commit_sha" \
    --arg subject "$subject" \
    '[{action: $action, commit: $commit, subject: $subject}]')"
  DRIFT_NORMALIZATION_LANDED="true"
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
    # is the empty array.
    DRIFT_DETECTED="false"
    DRIFT_KIND="stamped"
    DRIFT_COMMIT_COUNT="0"
    DRIFT_FIRST_COMMITS_JSON="[]"

    # Fire gate — the distinct-SHA precondition (workflow-drift-check.md
    # § No-drift normalization: "Fires only when … STAMPED_SHAS carries more than
    # one distinct SHA"). The byte-source's bash block assumes the caller already
    # established this precondition; here it gains an explicit in-script home and
    # is NEW selecting logic, not a body byte-copy. Deduplicate the stamp set and
    # count the distinct SHAs: a single distinct SHA means the stamps are already
    # uniform, so skip silently (no mutation, normalization_landed stays false).
    # More than one means the stamps fold to one BASE_SHA while sitting on
    # distinct commits — run the normalization. The `grep -c .` counts non-empty
    # lines so the leading-space word list does not inflate the count.
    local distinct_count
    distinct_count="$(printf '%s\n' $STAMPED_SHAS | LC_ALL=C sort -u | grep -c . || true)"
    if [ "$distinct_count" -gt 1 ] 2>/dev/null; then
      # Multi-SHA fold on distinct commits: normalize. On clean guards the helper
      # lands one commit, flips DRIFT_NORMALIZATION_LANDED, and returns here so
      # the no-drift scalars set above still emit. On a guard mismatch the helper
      # restores the tree and `exit 1`s, halting the session before emit_json.
      no_drift_normalization "$DRIFT_BASE_SHA" "$PLAN_DIR"
    fi
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

  # Fold input: $STAMPED_SHAS (minus any --exclude-sha values) plus the optional
  # --bootstrap-sha (FOLD_INPUT in migrate-workflow/SKILL.md Step 2). The
  # continue-and-collect fold collects EVERY failing pair into FOLD_FAILED_PAIRS
  # for one batched recovery prompt.
  #
  # --exclude-sha drops a pruned- or unreachable-commit stamp from the FOLD
  # input only: the skill's agent-side recovery passes one --exclude-sha per
  # merge_base_failed[].sha alongside a fresh --bootstrap-sha, so the restarted
  # fold no longer runs `git merge-base` over the failing pair and the bootstrap
  # SHA anchors the excluded artifacts' range. The exclusion does NOT touch the
  # reported stamped_artifacts / unstamped_files (those stay the raw on-disk walk
  # result above) — the migration re-stamps every artifact during replay
  # regardless, so the filter is a fold-input concern only.
  local fold_input="" sha ex excluded
  for sha in $STAMPED_SHAS; do
    excluded=""
    for ex in $EXCLUDE_SHAS; do
      if [ "$sha" = "$ex" ]; then
        excluded="1"
        break
      fi
    done
    if [ -z "$excluded" ]; then
      fold_input="$fold_input $sha"
    fi
  done
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
# Resume-state determination — the markdown walk over the plan file and the
# active track file that reproduces the precedence in workflow.md § Startup
# Protocol step 5.
#
# Byte-source: workflow.md § Startup Protocol step 5 (the prose precedence
# table). The top-level precedence is:
#
#   * State 0 first — an absent plan file, an absent `## Plan Review` section,
#     or a `## Plan Review` first top-level checkbox still `[ ]` means plan
#     review has not passed. Checked before any track walk.
#   * Otherwise walk `## Checklist` for the first `[ ]` track. A track file
#     present at `plan/track-N.md` is State C (mid-track resume); absent is
#     State A (pre-Phase-A — the rare path, since /create-plan writes every
#     track file at Phase 1 and the only track-file-deleting action leaves the
#     track `[~]`, not `[ ]`).
#   * Every track `[x]`/`[~]` — read `## Final Artifacts`' first top-level
#     checkbox: `[ ]`/`[>]` is State D (Phase 4), `[x]` is Done.
#
# The top-level walk resolves State 0/A/C/D/Done. For State C the walk computes
# the sub-state from the active track file's `## Progress` continuous log and
# `## Concrete Steps` roster (the joint read below); the section-discrepancy
# edge — a roster step flipped `[x]` with no matching Progress entry — lands in a
# later step of this track, so the `section-discrepancy` literal is not yet
# emitted here. STATE_JSON is the `{phase, substate}` object the emit point
# splices into `full`-mode output via --argjson (the track's Interfaces
# contract); divergence/drift/handoffs are unaffected.
#
# The five State C sub-states (the strings emitted in state.substate) and their
# sources, mirroring workflow.md § Startup Protocol step 5's sub-state table:
#
#   * "decomposition-pending"        — the `## Progress` "Review + decomposition"
#                                       entry is `[ ]` (Phase A not yet done).
#                                       Short-circuits BEFORE the roster is
#                                       quantified, so the still-empty/placeholder
#                                       `## Concrete Steps` roster is never coerced
#                                       to an all-steps-done shape.
#   * "failed-step"                  — the `## Concrete Steps` roster carries a
#                                       `[!]` step (a failed step). Checked before
#                                       steps-partial so a roster that is both
#                                       failed and partial routes to the failed
#                                       resume, matching the workflow.md `[!]` row.
#   * "steps-partial"                — the roster has at least one `[ ]` step (and
#                                       no `[!]`): the next `[ ]` step is the
#                                       resume target. Covers both the "mix of
#                                       `[x]` and `[ ]`" shape and the all-`[ ]`
#                                       just-decomposed shape (resume from step 1).
#   * "steps-done-review-pending"    — every roster step is `[x]`/`[~]` and the
#                                       `## Progress` code-review entry is not yet
#                                       `[x]` (review pending / partial).
#   * "review-done-track-open"       — every roster step is `[x]`/`[~]`, the
#                                       `## Progress` code-review entry is `[x]`,
#                                       and the plan-file track checkbox is still
#                                       `[ ]` (track completion pending).
#
# Sub-states 2-4 read the `## Concrete Steps` roster; sub-state 5 additionally
# reads the plan-file track checkbox (already in hand from the Checklist walk);
# only sub-state 1 is a pure `## Progress` read. The slug strings above are the
# track's Interfaces contract; design.md's frozen example glosses them with the
# longer workflow.md row text (a Phase-4 design-final reconciliation).
#
# The markers read are the conventions.md § 1.2 § Status markers set
# (`[ ]` / `[x]` / `[~]` / `[>]`) plus the roster/Progress `[!]` failed-step
# marker (step-implementation-recovery.md) that § 1.2's table omits but a later
# step's State C failed-step sub-state depends on. `[!]` is recognized, not an
# error. The enum stays closed: an unrecognized glyph (`[X]`, `[ x]`, `[]`,
# etc.) on a checkbox line the parser reads is an explicit parse error on
# stderr with a non-zero exit, before any `state` is emitted — never silently
# coerced into a state. Because determine_state runs in the main shell (no
# subshell), the parse_error `exit` terminates the script before emit_json, so
# the malformed-marker path emits no JSON on stdout.
#
# determine_state sets STATE_JSON (a compact JSON object) directly rather than
# writing scalars for a *_json assembler, because the object is small and the
# phase/substate shape is self-contained; the single emit point still owns
# splicing it into the mode output.
# ---------------------------------------------------------------------------

# Output of determine_state, consumed by emit_json's full branch. Defaults to
# null so a mode that skips state determination (divergence-only, migrate-range)
# emits `state: null` via the ${STATE_JSON:-null} guard at the emit point.
STATE_JSON=""

# Print a parse-error diagnostic naming the section and offending line, then
# exit non-zero. Called for any unrecognized checkbox glyph on a line the state
# parser reads. The exit terminates the whole script (determine_state runs in
# the main shell), so no `state` — and no JSON at all — reaches stdout. The
# diagnostic goes to stderr so it never contaminates the JSON channel.
parse_error() {
  local section="$1" line="$2"
  printf 'workflow-startup-precheck: malformed checkbox marker in %s: %s\n' \
    "$section" "$line" >&2
  exit 3
}

# Test whether a line is the `## <heading>` line for a named section, tolerating
# trailing whitespace. Section entry must match the heading exactly except for a
# trailing whitespace run (a `## Plan Review ` heading with a stray trailing space
# must still enter the section), but must NOT match a longer heading that merely
# shares the prefix (`## Plan ReviewXYZ` is a different heading). The trailing-run
# strip `${line%"${line##*[![:space:]]}"}` removes only the suffix of whitespace
# (a no-op when the line has none), so the equality test stays exact on the
# visible text. Returns 0 (match) / 1 (no match).
#
# Args: $1 = the raw line; $2 = the section heading text (e.g. "Plan Review").
heading_matches() {
  local line="$1" heading="$2" trimmed
  trimmed="${line%"${line##*[![:space:]]}"}"
  [ "$trimmed" = "## $heading" ]
}

# Classify a single bracketed checkbox glyph, echoing a normalized token the
# caller branches on. The recognized set is the § 1.2 markers plus the `[!]`
# failed-step marker:
#   " " -> "todo"   "x" -> "done"   "~" -> "skip"   ">" -> "wip"   "!" -> "fail"
# Any other glyph echoes "BAD" so the caller routes it to parse_error with the
# section/line context (classify_marker itself has no context to name). The
# glyph is matched exactly — a multi-character or empty bracket body (`[ x]`,
# `[]`) falls through to "BAD", so `[]`/`[ x]`/`[X]` are all rejected.
classify_marker() {
  case "$1" in
    " ") printf 'todo' ;;
    "x") printf 'done' ;;
    "~") printf 'skip' ;;
    ">") printf 'wip' ;;
    "!") printf 'fail' ;;
    *) printf 'BAD' ;;
  esac
}

# Set SECTION_TOKEN to the normalized token of the FIRST top-level checkbox line
# within a named section of the plan file. "Top-level" means a `- [<glyph>]`
# list item at column 0 (no leading whitespace, no leading `>` blockquote), and
# outside a fenced code block. The section runs from its `## <heading>` line to
# the next `## ` heading (or EOF). Used for `## Plan Review` and `## Final
# Artifacts`, each of which carries exactly one decision checkbox as its first
# list item.
#
# Sets SECTION_TOKEN to the classify_marker token for that checkbox, or the
# empty string when the section is absent or carries no top-level checkbox. On a
# malformed glyph it calls parse_error, naming the section.
#
# This sets a script-scoped variable rather than echoing to stdout *on purpose*:
# parse_error must `exit` the whole script before any JSON reaches stdout, and a
# command substitution `$(...)` would run the body in a subshell whose `exit`
# only kills the subshell, leaving the main script to continue and emit a state.
# Setting a global keeps parse_error's exit in the main shell. (The detection
# functions above follow the same write-plain-shell-variables idiom.)
#
# Args: $1 = plan file path; $2 = section heading text (e.g. "Plan Review").
SECTION_TOKEN=""
section_first_checkbox_token() {
  local file="$1" section="$2"
  local in_section="0" in_fence="0" line body
  SECTION_TOKEN=""
  # IFS= and -r keep leading whitespace and backslashes intact so the column-0
  # anchor test (no leading space) is accurate.
  while IFS= read -r line || [ -n "$line" ]; do
    # Toggle fenced-code state on a ``` fence (``` or ```lang). A checkbox-shaped
    # line inside a fence is template text, not a real marker, so it is skipped.
    case "$line" in
      '```'*)
        if [ "$in_fence" = "1" ]; then in_fence="0"; else in_fence="1"; fi
        continue
        ;;
    esac
    if [ "$in_fence" = "1" ]; then
      continue
    fi
    if [ "$in_section" = "0" ]; then
      # Enter the section on its `## <heading>` line (trailing whitespace tolerated).
      if heading_matches "$line" "$section"; then
        in_section="1"
      fi
      continue
    fi
    # In-section: a new `## ` heading ends the section without a checkbox found.
    case "$line" in
      "## "*)
        return
        ;;
    esac
    # Match a column-0 top-level checkbox attempt `- [<body>] ...` where the
    # closing `]` is followed by a space or end-of-line. The trailing `] `/EOL
    # guard distinguishes a checkbox from a markdown link `- [text](url)` (whose
    # `]` is followed by `(`), so a prose link as the first list item is not
    # misread as a checkbox. A blockquoted (`> - [x]`) or indented checkbox is
    # not column-0 and does not match, so an episode's quoted checkbox is never
    # miscounted. The body is then classified: a single recognized glyph yields
    # a token; an empty (`[]`), multi-char (`[ x]`), or single-unrecognized
    # (`[X]`) body is BAD and routes to parse_error — the enum stays closed.
    case "$line" in
      "- ["*"] "* | "- ["*"]")
        # Body is everything between `- [` and the FIRST `]`.
        body="${line#- [}"
        body="${body%%]*}"
        SECTION_TOKEN="$(classify_marker "$body")"
        if [ "$SECTION_TOKEN" = "BAD" ]; then
          parse_error "## $section" "$line"
        fi
        return
        ;;
    esac
  done < "$file"
}

# Set PROGRESS_TOKEN to the classify_marker token of the MOST-RECENT `## Progress`
# continuous-log entry whose text contains a target phrase. The `## Progress`
# section is an append-only continuous log (conventions-execution.md § 2.1): the
# four pre-seeded phase-checkpoint entries (`Review + decomposition`, `Step
# implementation`, `Track-level code review`, `Track completion`) accrue per-step
# and per-iteration entries below them, and a resume reader takes the most-recent
# matching entry as the current phase signal. So this scans every matching entry
# and keeps the LAST one's token, never the first.
#
# A `## Progress` entry is a column-0 `- [<glyph>] <timestamp/ctx> <text>` list
# item; the checkbox glyph is between `- [` and the first `]`, classified by
# classify_marker. The phrase match is a substring test on the whole line (the
# pre-seeded entries carry their phrase verbatim, e.g. "Review + decomposition"
# / "code review"). The section runs from `## Progress` to the next `## ` heading,
# and fenced blocks are skipped so a checkbox-shaped template line in a fence is
# not read as an entry.
#
# Sets PROGRESS_TOKEN to the matching entry's token, or the empty string when no
# entry matches the phrase (caller treats absent as not-yet-done). A malformed
# glyph on a MATCHING entry routes to parse_error; a malformed glyph on a
# non-matching Progress entry is ignored (the parser only reads the entries it
# needs, keeping an unrelated typo elsewhere from aborting the resume read).
#
# Runs inline (sets a script-scoped variable, no command substitution) for the
# same reason section_first_checkbox_token does: parse_error must `exit` the
# whole script, and a `$(...)` subshell would swallow that exit and let the
# script emit a state anyway.
#
# Args: $1 = track file path; $2 = phrase to match (e.g. "Review + decomposition").
PROGRESS_TOKEN=""
progress_entry_token() {
  local file="$1" phrase="$2"
  local in_section="0" in_fence="0" line body tok
  PROGRESS_TOKEN=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      '```'*)
        if [ "$in_fence" = "1" ]; then in_fence="0"; else in_fence="1"; fi
        continue
        ;;
    esac
    if [ "$in_fence" = "1" ]; then
      continue
    fi
    if [ "$in_section" = "0" ]; then
      if heading_matches "$line" "Progress"; then
        in_section="1"
      fi
      continue
    fi
    case "$line" in
      "## "*)
        return
        ;;
    esac
    # A column-0 `- [<glyph>] ...` Progress entry that contains the target phrase.
    # The `- [`*`] ` / `- [`*`]` guard matches the checkbox shape; the inner phrase
    # test selects only the entries this read cares about. The MOST-RECENT (last)
    # matching entry wins, so PROGRESS_TOKEN is overwritten on every match.
    case "$line" in
      "- ["*"] "*"$phrase"* | "- ["*"]"*"$phrase"*)
        body="${line#- [}"
        body="${body%%]*}"
        tok="$(classify_marker "$body")"
        if [ "$tok" = "BAD" ]; then
          parse_error "## Progress" "$line"
        fi
        PROGRESS_TOKEN="$tok"
        ;;
    esac
  done < "$file"
}

# Scan the active track file's `## Concrete Steps` roster, setting four script-
# scoped outputs the State C sub-state map branches on:
#
#   * ROSTER_HAS_FAIL  — "1" if any roster step is `[!]` (failed-step).
#   * ROSTER_HAS_TODO  — "1" if any roster step is `[ ]` (an unfinished step).
#   * ROSTER_STEP_COUNT — the count of roster step lines seen (0 = empty /
#                         placeholder roster, the decomposition-pending shape).
#   * ROSTER_PAIRS     — a space-delimited list of `<N>:<token>` per roster step
#                        (the leading `N.` step number joined to its classified
#                        status token), so a join keyed on the step number can run
#                        without re-walking the roster. The flags above are
#                        derivable from these pairs, but they are kept as direct
#                        outputs so the existing sub-state branches need no change.
#
# The roster line is the canonical thin-roster shape (conventions-execution.md
# § 2.1): a column-0 `N. <description> — \`risk: <tag>\`  [<glyph>]` with an
# optional ` commit: <SHA>` tail. The description can itself contain bracketed
# tokens (e.g. an inline `\`[ ]\`` in backticks), so the STATUS checkbox is NOT
# the first `[...]` on the line — it is the checkbox AFTER the `risk:` token. The
# scan therefore splits each roster line at the last `risk:` and reads the
# checkbox from that tail, matching the immutable-after-Phase-A roster grammar
# (the only roster mutation is the status flip `[ ]`→`[x]`/`[!]` plus the
# optional `commit:` annotation). A line beginning `N. ` with no `risk:` tail is
# not a well-formed roster step (Phase A guarantees the tag) and is skipped, so a
# stray numbered prose line never miscounts.
#
# Runs inline (sets script-scoped variables, no command substitution) so a
# parse_error on a malformed status glyph terminates the whole script rather than
# a subshell.
ROSTER_HAS_FAIL="0"
ROSTER_HAS_TODO="0"
ROSTER_STEP_COUNT="0"
ROSTER_PAIRS=""
roster_scan() {
  local file="$1"
  local in_section="0" in_fence="0" line tail body tok step_num
  ROSTER_HAS_FAIL="0"
  ROSTER_HAS_TODO="0"
  ROSTER_STEP_COUNT="0"
  ROSTER_PAIRS=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      '```'*)
        if [ "$in_fence" = "1" ]; then in_fence="0"; else in_fence="1"; fi
        continue
        ;;
    esac
    if [ "$in_fence" = "1" ]; then
      continue
    fi
    if [ "$in_section" = "0" ]; then
      if heading_matches "$line" "Concrete Steps"; then
        in_section="1"
      fi
      continue
    fi
    case "$line" in
      "## "*)
        return
        ;;
    esac
    # A column-0 numbered roster line: `<digit(s)>. ...`. A leading-space or
    # blockquoted line is not a column-0 roster step and is skipped (the
    # description below a step may wrap onto indented continuation lines).
    case "$line" in
      [0-9]*". "*) ;;
      *) continue ;;
    esac
    # The status checkbox is the checkbox AFTER the last `risk:` token. Split the
    # line at the last `risk:`; if there is no `risk:`, the line is not a
    # well-formed roster step (Phase A guarantees the tag), so skip it without
    # counting it.
    case "$line" in
      *"risk:"*)
        tail="${line##*risk:}"
        ;;
      *)
        continue
        ;;
    esac
    # Find the status checkbox `[<glyph>]` in the post-`risk:` tail. The tail is
    # `<space><tag>\`  [<glyph>]( commit: <SHA>)?` (the tag may carry a closing
    # backtick / paren); the checkbox is the first `[...]` in that tail.
    #
    # Assumption: the status box is the FIRST `[...]` after `risk:`. This is safe
    # because the canonical roster grammar (`conventions-execution.md` § 2.1) writes
    # risk-note annotations parenthesized `(...)`, never bracketed `[...]`; a
    # bracketed tail annotation BEFORE the status box (e.g. `risk: high [note]  [ ]`)
    # would be mis-read as the status box, so risk-note annotations must stay
    # parenthesized. Switching to the last `[...]` is not the fix — it introduces
    # its own edge cases with trailing `commit:`-adjacent tokens.
    case "$tail" in
      *"["*"]"*)
        body="${tail#*[}"
        body="${body%%]*}"
        ;;
      *)
        # A roster step line with a `risk:` tag but no status checkbox is
        # malformed; name it so the closed-enum contract holds for the roster too.
        parse_error "## Concrete Steps" "$line"
        ;;
    esac
    tok="$(classify_marker "$body")"
    if [ "$tok" = "BAD" ]; then
      parse_error "## Concrete Steps" "$line"
    fi
    ROSTER_STEP_COUNT=$((ROSTER_STEP_COUNT + 1))
    case "$tok" in
      fail) ROSTER_HAS_FAIL="1" ;;
      todo) ROSTER_HAS_TODO="1" ;;
    esac
    # Record the `<N>:<token>` pair. The step number N is the leading digits
    # before the first `.` (the `[0-9]*". "` guard above already confirmed the
    # line opens with one or more digits then `. `). The step-number/status join
    # the section-discrepancy edge runs reads these pairs without re-walking.
    step_num="${line%%.*}"
    ROSTER_PAIRS="$ROSTER_PAIRS $step_num:$tok"
  done < "$file"
}

# Scan the active track file's `## Progress` continuous log, setting
# PROGRESS_STEP_NUMS to a space-delimited list of the step numbers N that have a
# word-boundary `Step N` entry. A roster step `[x]` flip and its `Step N complete
# (commit <SHA>)` Progress entry are written in adjacent sub-steps
# (`step-implementation.md` sub-step 7.1 flips the roster, 7.2 appends the Progress
# entry), so a `[x]` roster step whose number is absent from this list is the
# interrupted-write state the agent reconciles from the `## Episodes` block.
#
# The match is a word-boundary `Step <N>` where <N> is a run of digits: the
# entry text `Step 1 complete (commit abc123)` contributes 1, but the pre-seeded
# phase-checkpoint line `Step implementation` (no digit after `Step `) does NOT,
# so a healthy track whose Progress interleaves phase-checkpoint lines between
# per-step lines never registers a spurious step number. The digit run is read
# whole so `Step 12` contributes 12, not 1 — a `Step 1` lookup is an exact
# numeric-equality test against the collected list, never a substring test.
#
# A `[!]` failed-step Progress entry counts as present-for-N: the recovery
# protocol writes `Step N failed` / `Step N retry` entries, and a failed step
# that is later flipped `[x]` is reconciled work, not a missing-section edge. So
# this scan records the step number from ANY Progress entry naming `Step N`
# regardless of its checkbox glyph.
#
# Runs inline (sets a script-scoped variable, no command substitution) for the
# same reason the other Progress reads do, though this scan calls no parse_error
# (it reads only the entry text, not a status enum). The section runs from
# `## Progress` to the next `## ` heading, and fenced blocks are skipped.
#
# Args: $1 = track file path.
PROGRESS_STEP_NUMS=""
progress_step_numbers() {
  local file="$1"
  local in_section="0" in_fence="0" line rest digits
  PROGRESS_STEP_NUMS=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      '```'*)
        if [ "$in_fence" = "1" ]; then in_fence="0"; else in_fence="1"; fi
        continue
        ;;
    esac
    if [ "$in_fence" = "1" ]; then
      continue
    fi
    if [ "$in_section" = "0" ]; then
      if heading_matches "$line" "Progress"; then
        in_section="1"
      fi
      continue
    fi
    case "$line" in
      "## "*)
        return
        ;;
    esac
    # Find a `Step <digit>` token anywhere in the entry. The `*"Step "[0-9]*`
    # glob requires a digit immediately after `Step ` (a single space), so
    # `Step implementation` (a non-digit follows) does not match. Strip up to and
    # including the first `Step ` prefix, then take the leading digit run as the
    # whole step number.
    case "$line" in
      *"Step "[0-9]*)
        rest="${line#*Step }"
        # Take the leading digit run as the whole step number. `${rest%%[!0-9]*}`
        # strips the first non-digit and everything after it (so `1 complete`
        # yields `1`, `12 failed` yields `12`); when `rest` is all digits there is
        # no non-digit to strip and the run is returned whole.
        digits="${rest%%[!0-9]*}"
        if [ -n "$digits" ]; then
          PROGRESS_STEP_NUMS="$PROGRESS_STEP_NUMS $digits"
        fi
        ;;
    esac
  done < "$file"
}

# Test whether a step number N is present in the space-delimited
# PROGRESS_STEP_NUMS list, by exact numeric token equality (never a substring
# test, so 1 does not match 12). Returns 0 (present) / 1 (absent).
step_num_in_progress() {
  local target="$1" n
  for n in $PROGRESS_STEP_NUMS; do
    if [ "$n" = "$target" ]; then
      return 0
    fi
  done
  return 1
}

# Compute the State C sub-state, setting C_SUBSTATE to one of the five slug
# strings — or the literal `section-discrepancy` when the roster and the
# `## Progress` log disagree. The joint read over the active track file's
# `## Progress` continuous log and `## Concrete Steps` roster plus the
# already-in-hand plan-file track checkbox, mirroring workflow.md § Startup
# Protocol step 5's sub-state table.
#
# Precedence (the order the branches are evaluated):
#   1. decomposition-pending — the `## Progress` "Review + decomposition" entry is
#      NOT `[x]` (still `[ ]`, or — defensively — absent). Short-circuits BEFORE
#      the roster is read, so the still-empty/placeholder roster is never coerced.
#   2. section-discrepancy — a roster step flipped `[x]` (done) whose step number
#      N has NO word-boundary `Step N` entry in `## Progress`. The roster `[x]`
#      flip and the `Step N` Progress entry are written in adjacent sub-steps
#      (`step-implementation.md` sub-step 7.1 flips the roster, 7.2 appends the
#      Progress entry), so a done step flipped `[x]` whose `Step N` Progress entry
#      is missing is the interrupted-write state; the agent reconciles from the
#      `## Episodes` block. Checked before the failed/partial/done split because
#      it is an inconsistency signal that overrides the normal resume routing. A
#      `[!]` failed Progress entry counts as present-for-N (the recovery protocol
#      writes `Step N failed`/`retry`), so a reconciled retry is not a discrepancy.
#   3. failed-step — the roster carries a `[!]`. Checked before steps-partial so a
#      both-failed-and-partial roster routes to the failed resume.
#   4. steps-partial — the roster has at least one `[ ]` step (and no `[!]`).
#   5./6. all roster steps done (`[x]`/`[~]`, none `[ ]`, none `[!]`): the
#      `## Progress` code-review entry decides — not-`[x]` ⇒ steps-done-review-
#      pending; `[x]` ⇒ review-done-track-open (the plan-file track checkbox is
#      `[ ]` by construction — the Checklist walk reached State C on the first
#      `[ ]` track, which is this track).
#
# ---------------------------------------------------------------------------
# The phase ledger — the append primitive and the last-value-wins tail reader.
#
# The ledger is `<plan_dir>/_workflow/phase-ledger.md`, an append-only event log
# whose grammar is fixed in the file header. These two functions are the only
# code that writes or reads it: append_ledger composes and atomically appends
# one event line; ledger_tail_value resolves the latest value of one key for
# determine_state. Both resolve the plan dir from the current branch the same
# way detect_drift / determine_state do (§ 1.6(g)).
# ---------------------------------------------------------------------------

# Resolve the phase-ledger path for the current branch. Echoes the path; the
# caller decides whether the file must already exist.
ledger_path() {
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  printf '%s' "docs/adr/${branch}/_workflow/phase-ledger.md"
}

# Append one event line to the phase ledger, atomically. Reads the LEDGER_*
# accumulators the arg parser filled. The line always carries the `[<ISO>]`
# timestamp and `[ctx=<level>]` marker; each non-empty LEDGER_* field is appended
# as a `key=value` token (an empty field is omitted, never written as `key=`).
# `categories` is the one quoted value because it may carry spaces and commas.
#
# Atomicity: the new contents (existing ledger, if any, plus the new line) are
# written to a sibling temp file, then `mv`'d over the ledger. `mv` within one
# directory is a rename, which is atomic on POSIX filesystems, so a crash leaves
# either the prior ledger or the temp file — never a torn ledger. A torn append
# therefore leaves the prior tail intact and determine_state resolves the prior
# state (D3 / D6).
append_ledger() {
  local ledger dir tmp ts line ctx
  ledger="$(ledger_path)"
  dir="$(dirname "$ledger")"
  # The plan `_workflow/` dir is created by /create-plan before the first
  # append; mkdir -p makes the append self-sufficient and idempotent.
  mkdir -p "$dir"

  # ISO 8601 UTC, minute precision, matching the `## Progress` entry timestamps
  # (e.g. 2026-06-15T16:42Z). `-u` forces UTC regardless of the host TZ.
  ts="$(date -u +%Y-%m-%dT%H:%MZ)"
  ctx="${LEDGER_CTX:-safe}"

  line="[$ts] [ctx=$ctx]"
  [ -n "$LEDGER_PHASE" ]      && line="$line phase=$LEDGER_PHASE"
  [ -n "$LEDGER_TRACK" ]      && line="$line track=$LEDGER_TRACK"
  [ -n "$LEDGER_TIER" ]       && line="$line tier=$LEDGER_TIER"
  [ -n "$LEDGER_CATEGORIES" ] && line="$line categories=\"$LEDGER_CATEGORIES\""
  [ -n "$LEDGER_S17" ]        && line="$line s17=$LEDGER_S17"
  [ -n "$LEDGER_PAUSED" ]     && line="$line paused=$LEDGER_PAUSED"

  # Build the new contents in a temp file in the SAME directory (so the final mv
  # is a same-filesystem rename, not a cross-device copy that would not be
  # atomic). The temp name carries the PID so concurrent appends — which the
  # orchestrator never issues, but a stray re-invocation might — cannot collide.
  tmp="$dir/.phase-ledger.$$.tmp"
  if [ -f "$ledger" ]; then
    cat "$ledger" > "$tmp"
  else
    : > "$tmp"
  fi
  printf '%s\n' "$line" >> "$tmp"
  mv -f "$tmp" "$ledger"
}

# Set LEDGER_VALUE to the LAST (most-recent) value of $key across every line of
# the phase ledger, or the empty string when the key never appears (or the
# ledger is absent). This is the last-value-wins read the header contract
# defines: a mid-flight change appends a new value, and the reader keeps the
# final one. The match is a per-line ` key=` / `^key=` token scan; the value is
# everything after the first `=` up to the next space, with surrounding double
# quotes stripped so a quoted `categories="a,b"` reads back as `a,b`.
#
# Sets a script-scoped variable (no command substitution) to stay consistent
# with the other inline parse helpers above. Args: $1 = the key name.
LEDGER_VALUE=""
ledger_tail_value() {
  local key="$1" ledger line rest val
  LEDGER_VALUE=""
  ledger="$(ledger_path)"
  [ -f "$ledger" ] || return
  while IFS= read -r line || [ -n "$line" ]; do
    # Anchor the key at a token boundary: either at line start (`key=`) or
    # after a space (` key=`), so `track=` never matches inside `xtrack=` and
    # the `phase=` key never matches a `categories="…phase=…"` substring (a
    # quoted value is consumed whole below before the next key is scanned).
    case " $line" in
      *" $key="*)
        # Strip everything up to and including the first ` $key=`.
        rest="${line#*" $key="}"
        if [ "${rest#\"}" != "$rest" ]; then
          # Quoted value: take everything between this `"` and the next `"`.
          rest="${rest#\"}"
          val="${rest%%\"*}"
        else
          # Bare token: everything up to the next space.
          val="${rest%% *}"
        fi
        LEDGER_VALUE="$val"
        ;;
    esac
  done < "$ledger"
}

# Args: $1 = track file path; $2 = the plan-file track checkbox token for this
# track (the classify_marker token the Checklist walk read; "todo" here by
# construction, passed for explicitness and future use).
C_SUBSTATE=""
determine_c_substate() {
  local track_file="$1"
  # 1. decomposition-pending: pure `## Progress` read, short-circuit.
  progress_entry_token "$track_file" "Review + decomposition"
  if [ "$PROGRESS_TOKEN" != "done" ]; then
    C_SUBSTATE="decomposition-pending"
    return
  fi

  # Decomposition is done — quantify the roster, then collect the step numbers
  # the `## Progress` log records so the discrepancy join below can run.
  roster_scan "$track_file"
  progress_step_numbers "$track_file"

  # 2. section-discrepancy: any `[x]` (done) roster step whose number has no
  # matching `Step N` Progress entry. `[~]` (skip) is not a completed-step flip
  # the orchestrator logs a `Step N` entry for, so only `[x]` (done) is joined.
  local pair pnum ptok
  for pair in $ROSTER_PAIRS; do
    pnum="${pair%%:*}"
    ptok="${pair#*:}"
    if [ "$ptok" = "done" ] && ! step_num_in_progress "$pnum"; then
      C_SUBSTATE="section-discrepancy"
      return
    fi
  done

  # 3. failed-step before steps-partial.
  if [ "$ROSTER_HAS_FAIL" = "1" ]; then
    C_SUBSTATE="failed-step"
    return
  fi
  # 4. steps-partial: any `[ ]` step remains.
  if [ "$ROSTER_HAS_TODO" = "1" ]; then
    C_SUBSTATE="steps-partial"
    return
  fi
  # An all-done roster with zero steps would be a vacuous "all done" — but the
  # decomposition-pending short-circuit above already absorbs the empty-roster
  # case (a roster is empty only before decomposition completes), so reaching
  # here with ROSTER_STEP_COUNT=0 is not expected. Treat it as steps-partial (no
  # step done yet) rather than coercing to a review phase, the conservative
  # resume that re-enters Phase B rather than skipping ahead.
  if [ "$ROSTER_STEP_COUNT" = "0" ]; then
    C_SUBSTATE="steps-partial"
    return
  fi

  # 5./6. all steps done: the code-review Progress entry decides.
  progress_entry_token "$track_file" "code review"
  if [ "$PROGRESS_TOKEN" = "done" ]; then
    C_SUBSTATE="review-done-track-open"
  else
    C_SUBSTATE="steps-done-review-pending"
  fi
}

# Two-level resume from the phase ledger: the ledger owns the top-level phase
# and the active track; the track file's `## Progress` owns the within-track
# sub-state. Sets STATE_JSON and returns 0 when the ledger resolves the state;
# returns 1 (without touching STATE_JSON) when there is no ledger, so the caller
# falls back to the legacy plan-checkbox walk. This is the D3/D10 path: a
# plan-less `minimal` branch resumes from its ledger instead of restarting, and
# the active track is `track-1` by construction (single-track tier, D10's
# `plan/track-1.md` secondary signal) when the ledger names no track.
determine_state_from_ledger() {
  local branch plan_dir ledger phase track track_file
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  plan_dir="docs/adr/${branch}"
  ledger="$plan_dir/_workflow/phase-ledger.md"
  [ -f "$ledger" ] || return 1

  # Top-level phase (last value wins). An absent / empty phase means the ledger
  # exists but recorded no phase boundary yet — treat it as State 0 (nothing
  # durable has crossed the plan-review gate), the same conservative default the
  # legacy walk gives an unreviewed plan.
  ledger_tail_value "phase"
  phase="$LEDGER_VALUE"
  if [ -z "$phase" ]; then
    STATE_JSON='{"phase":"0","substate":null}'
    return 0
  fi

  case "$phase" in
    0 | A | D | Done)
      # These phases carry no within-track sub-state, so emit directly with a
      # null substate (jq escapes the phase token by construction). A is the
      # pre-Phase-A no-track-file state; D is Phase-4-pending; Done is complete.
      STATE_JSON="$(jq -nc --arg p "$phase" '{phase:$p, substate:null}')"
      return 0
      ;;
    C)
      # Execution resume: the ledger owns the active track, the track file owns
      # the within-track sub-state. Default the active track to 1 for the
      # single-track `minimal` tier whose ledger names no track (D10).
      ledger_tail_value "track"
      track="$LEDGER_VALUE"
      [ -n "$track" ] || track="1"
      track_file="$plan_dir/_workflow/plan/track-${track}.md"
      if [ -f "$track_file" ]; then
        determine_c_substate "$track_file" "todo"
        STATE_JSON="$(jq -nc --arg s "$C_SUBSTATE" '{phase:"C", substate:$s}')"
      else
        # Phase recorded as C but no track file on disk yet — pre-decomposition
        # State A, mirroring the legacy walk's first-`[ ]`-track-without-a-file
        # branch.
        STATE_JSON='{"phase":"A","substate":null}'
      fi
      return 0
      ;;
    *)
      # An unrecognized phase token is a malformed ledger. Reuse parse_error so a
      # corrupt ledger fails loudly (exit 3 before any JSON) rather than silently
      # routing to a wrong state.
      parse_error "phase-ledger.md" "phase=$phase"
      ;;
  esac
}

determine_state() {
  # Two-level resume: prefer the phase ledger (D3) when present, which is the
  # only resume signal a plan-less `minimal` branch has. When no ledger exists —
  # an existing in-flight `lite`/`full` plan created before the ledger, or a
  # fresh checkout — fall back to the legacy plan-checkbox walk below so those
  # plans resume without regression (the two-level lookup keeps the track-file
  # sub-state walk unchanged).
  if determine_state_from_ledger; then
    return
  fi

  # Resolve the active plan dir from the current branch per § 1.6(g), the same
  # resolution detect_drift / scan_handoffs use.
  local branch
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
  local plan_dir="docs/adr/${branch}"
  local plan_file="$plan_dir/_workflow/implementation-plan.md"

  # --- State 0: plan review not passed -------------------------------------
  # An absent plan file is State 0 (no plan to review against). An absent
  # `## Plan Review` section or a first-checkbox `[ ]` is also State 0; both
  # mean plan review has not passed (workflow.md step 5 row 1: "section missing
  # entirely" is treated identically to an unchecked entry).
  if [ ! -f "$plan_file" ]; then
    STATE_JSON='{"phase":"0","substate":null}'
    return
  fi
  section_first_checkbox_token "$plan_file" "Plan Review"
  if [ "$SECTION_TOKEN" != "done" ]; then
    # Empty token (absent section / no checkbox) or "todo" (`[ ]`) -> State 0.
    # A "wip"/"skip" glyph on the Plan-Review checkbox is not a defined shape,
    # but it is still not "passed", so it collapses to State 0 here rather than
    # inventing a state; the parse-error guard already rejects unrecognized
    # glyphs upstream.
    STATE_JSON='{"phase":"0","substate":null}'
    return
  fi

  # --- Walk the Checklist for the first [ ] track --------------------------
  # Top-level track lines are `- [<glyph>] Track N:` at column 0 (no leading
  # `>`), bounded between `## Checklist` and the next `## ` heading, and outside
  # a fenced code block. A checkbox token inside a blockquoted episode (the
  # `> **Track episode:**` block carries `- [x]`-shaped lines) sits under a
  # leading `>` so it is not column-0 and is excluded; a fenced template block
  # (e.g. a mermaid or bash fence) is skipped by the fence toggle. The first
  # `[ ]` track is the resume target.
  local in_checklist="0" in_fence="0" line glyph token track_num=""
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in
      '```'*)
        if [ "$in_fence" = "1" ]; then in_fence="0"; else in_fence="1"; fi
        continue
        ;;
    esac
    if [ "$in_fence" = "1" ]; then
      continue
    fi
    if [ "$in_checklist" = "0" ]; then
      if heading_matches "$line" "Checklist"; then
        in_checklist="1"
      fi
      continue
    fi
    case "$line" in
      "## "*)
        break
        ;;
    esac
    # A column-0 `- [<body>] Track <N>:` line. The `] Track ` tail anchors it to
    # a real track entry (not an arbitrary checkbox or a markdown link), and the
    # column-0 `- [` prefix excludes blockquoted episode checkboxes. The body is
    # everything up to the first `]`; classify_marker rejects an empty / multi-
    # char / single-unrecognized body as BAD so a malformed track marker
    # (`- [] Track 1:`, `- [ x] Track 1:`, `- [X] Track 1:`) is a parse error,
    # the same closed-enum rule the section helper applies.
    #
    # The closed-enum parse-error contract is TOTAL over the bounded `## Checklist`
    # region: every track line's glyph is validated, not just those at or before the
    # first `[ ]`. So the first-`[ ]` track only RECORDS the resume target (the walk
    # does not break on it); the loop keeps running to the next `## ` heading so a
    # malformed glyph on a LATER track line still parse-errors rather than being
    # silently skipped. The region is small, so the full validation is cheap.
    case "$line" in
      "- ["*"] Track "*)
        glyph="${line#- [}"
        glyph="${glyph%%]*}"
        token="$(classify_marker "$glyph")"
        if [ "$token" = "BAD" ]; then
          parse_error "## Checklist" "$line"
        fi
        if [ "$token" = "todo" ] && [ -z "$track_num" ]; then
          # First [ ] track: record the resume target's number from `Track <N>:`.
          # Do NOT break — later track lines must still be glyph-validated.
          local tail
          tail="${line#*Track }"
          track_num="${tail%%:*}"
        fi
        ;;
    esac
  done < "$plan_file"

  if [ -n "$track_num" ]; then
    # First [ ] track found: State A (no track file) or State C (track file
    # present). For State C, compute the sub-state from the joint read over the
    # track file's `## Progress` log and `## Concrete Steps` roster (plus this
    # track's plan-file checkbox, which is `[ ]`/"todo" by construction here).
    local track_file="$plan_dir/_workflow/plan/track-${track_num}.md"
    if [ -f "$track_file" ]; then
      determine_c_substate "$track_file" "todo"
      # The slug is plain ASCII (no quoting hazard), but build the object with jq
      # so the substate string is JSON-escaped by construction, keeping the
      # one-contract-home discipline rather than hand-splicing into the literal.
      STATE_JSON="$(jq -nc --arg s "$C_SUBSTATE" '{phase:"C", substate:$s}')"
    else
      STATE_JSON='{"phase":"A","substate":null}'
    fi
    return
  fi

  # --- No [ ] track: every track is [x]/[~] -> Final Artifacts decides -----
  # State D (Phase 4 pending: `[ ]` or `[>]`) vs Done (`[x]`). An absent
  # `## Final Artifacts` section or no checkbox collapses to State D (Phase 4
  # has not completed), matching workflow.md step 5's "Phase 4 is `[ ]`" row as
  # the not-yet-Done default.
  section_first_checkbox_token "$plan_file" "Final Artifacts"
  if [ "$SECTION_TOKEN" = "done" ]; then
    STATE_JSON='{"phase":"Done","substate":null}'
  else
    # "todo"/"wip" (`[ ]`/`[>]`), absent section, or no checkbox -> State D.
    STATE_JSON='{"phase":"D","substate":null}'
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
  # UNSTAMPED_FILES classification), the handoff scan (HANDOFFS_JSON), and the
  # state walk (STATE_JSON) are populated; emit assembles them via
  # divergence_json, drift_json, the HANDOFFS_JSON array literal, and the
  # STATE_JSON object literal. The state walk fills the top-level phase
  # (0/A/C/D/Done) with a null substate; the State C sub-state map lands in a
  # later step of this track. actions_taken carries the no-drift normalization
  # commit when that path landed one, otherwise the empty array.
  #
  # ACTIONS_TAKEN_JSON is a JSON array literal so the no-drift normalization
  # path (the only autonomous mutation) can replace the empty `[]` with its
  # one-element commit record without re-threading the null idiom. Every other
  # path leaves it empty.
  local actions_taken_json="${ACTIONS_TAKEN_JSON:-[]}"
  local handoffs_json="${HANDOFFS_JSON:-[]}"
  local divergence_obj drift_obj
  divergence_obj="$(divergence_json)"
  drift_obj="$(drift_json)"

  case "$MODE" in
    full)
      # `state` is the determine_state STATE_JSON object (phase 0/A/C/D/Done
      # with a null substate at this step). The ${STATE_JSON:-null} guard at the
      # call below keeps `state: null` for a mode/path that skips the state
      # walk, so the object is injected via --argjson and jq treats either a
      # real object or the literal null as a JSON value, never the string
      # "null". `handoffs` is the scan_handoffs array literal (the empty array
      # when no handoff is pending), spliced via --argjson so the ls -t mtime
      # order survives.
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

# --append-ledger is the one mutation mode and emits no JSON: append the event
# line atomically and exit before the JSON dispatch below. Handled first so the
# JSON-reporter dispatch never runs for it.
if [ "$APPEND_LEDGER" = "1" ]; then
  append_ledger
  exit 0
fi

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
    determine_state
    ;;
  divergence-only)
    detect_divergence
    ;;
  migrate-range)
    detect_migrate_range
    ;;
esac

emit_json
