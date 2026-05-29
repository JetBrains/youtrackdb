# Workflow Drift Check

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Detection | orchestrator,planner | 2,3A | The two-phase detection bash: classify artifacts as stamped/unstamped, then fold or short-circuit to a drift signal. |
| §No-drift normalization | orchestrator,planner | 2,3A | Collapse multiple stamps that fold to one BASE_SHA into a single normalization commit, guarded by a strict diff shape. |
| §Skip conditions | orchestrator,planner | 2,3A | Three silent-skip conditions (no _workflow dir, plan complete + Phase 4 active, empty diff) checked cheapest-first. |
| §Resolutions | orchestrator,planner | 2,3A | On drift, print the commit count and stamp base, then force a Migrate / Defer / Suppress pick with no default. |
| §Migrate now | orchestrator,planner | 2,3A | End the session and ask the user to re-invoke /migrate-workflow; the gate never runs the skill inline. |
| §Defer | orchestrator,planner | 2,3A | Continue this session and record a deferred-drift todo recited at session end. |
| §Suppress | orchestrator,planner | 2,3A | Continue this session and silence the drift residue with no session-end reminder. |
| §After the choice | orchestrator,planner | 2,3A | The chosen resolution holds for the session; Migrate ends it, Defer/Suppress continue startup. |

<!--Document index end-->

Runs early in the startup sequence for two callers: `/execute-tracks`
(turn 1, immediately after the Branch Divergence Check at `workflow.md`
§ Startup Protocol step 3 and before the handoff scan at step 4) and
`/create-plan` (between Step 1's workflow-docs read and Step 1a's
handoff scan). Undetected drift surfaces later as confused reviewers
in Phase C, missing required sections during track completion, or
auto-resume tripping on a schema field the branch never gained —
exactly the failure mode this gate prevents. The branch carries
per-branch `_workflow/**` artifacts whose required shape is dictated
by current `develop`: section names, mandatory artifacts, step-file
schema. Workflow-format commits land on `develop` while the branch
runs, and the branch's artifacts silently drift.

Detection is one `git log` over the active plan's stamp-derived range
against HEAD. The branch is a self-contained capsule (workflow-format
commits enter the branch's view only when the user explicitly rebases
or merges `develop`), so the gate ranges against the branch's own
HEAD and never fetches `develop` independently. The plan dir the
caller resolved at startup (see `conventions.md` `§1.2` and `§1.6(g)`)
scopes the walk; cross-plan folding is out of scope (D13). Migration
itself stays in the `/migrate-workflow` skill — the gate detects and
gates, the skill replays. The skill assumes a fresh session, so the
Migrate now resolution ends this session and asks the user to
re-invoke `/migrate-workflow` from this worktree.

---

## Detection
<!-- roles=orchestrator,planner phases=2,3A summary="The two-phase detection bash: classify artifacts as stamped/unstamped, then fold or short-circuit to a drift signal." -->

The Detection bash has two phases. **Phase 1** walks the active plan's
`_workflow/**` artifacts and classifies each as stamped or unstamped
(byte-copied from `conventions.md` `§1.6(h)` so the drift check and the
migration agree on what "drift" means). **Phase 2** is caller-specific:
the drift check signals drift unconditionally when any artifact is
unstamped (no fold, no `git log`); when every artifact is stamped, the
gate folds the stamp set pairwise through `git merge-base` to derive
`BASE_SHA` and runs `git log $BASE_SHA..HEAD` against workflow paths.

The Phase 1 walk block, byte-for-byte from `conventions.md` `§1.6(h)`:

```bash
PLAN_DIR="docs/adr/<resolved-dir-name>"
STAMPED_SHAS=""
UNSTAMPED_FILES=""
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
```

The `<resolved-dir-name>` placeholder is the active plan dir the caller
resolved at startup per `conventions.md` `§1.6(g)`. The walk silently
skips artifacts not yet on disk (`design-mechanics.md` before the
length trigger, any `track-*.md` not yet created), so absent optional
artifacts contribute neither a stamp nor an unstamped flag.

Phase 2 (drift-check-specific decision):

```bash
# Both arrays empty: no stampable artifacts on disk under the active
# plan's _workflow/ (a freshly-created plan dir holding only a transient
# handoff-*.md, or a plan dir cleaned ahead of Phase 4). Silent no-op
# skip per conventions.md §1.6(h).
if [ -z "$STAMPED_SHAS" ] && [ -z "$UNSTAMPED_FILES" ]; then
    DRIFT_DETECTED=0
    exit 0
fi

if [ -n "$UNSTAMPED_FILES" ]; then
    # Any unstamped artifact short-circuits to drift detected: no fold,
    # no git log. The /migrate-workflow skill's bootstrap prompt (per
    # conventions.md §1.6(d)) will gather a base SHA for the unstamped set.
    DRIFT_DETECTED=1
else
    # Every artifact in the active plan is stamped. Fold the stamp set
    # pairwise through git merge-base to derive BASE_SHA, the oldest
    # stamp reachable from HEAD (conventions.md §1.6(c)).
    BASE_SHA=""
    MERGE_BASE_FAILED=""
    for SHA in $STAMPED_SHAS; do
        if [ -z "$BASE_SHA" ]; then
            BASE_SHA="$SHA"; continue
        fi
        NEW_BASE="$(git merge-base "$BASE_SHA" "$SHA" 2>/dev/null)" || NEW_BASE=""
        if [ -z "$NEW_BASE" ]; then
            # merge-base failure (a stamp pointing at a git-gc-pruned
            # commit, or two stamps with no reachable common ancestor in
            # the local repo). Per conventions.md §1.6(c), route the
            # failing pair to the unstamped path so the bootstrap prompt
            # in §1.6(d) covers them in the same user prompt as any
            # other unstamped artifacts.
            UNSTAMPED_FILES="$UNSTAMPED_FILES merge-base-failed:$BASE_SHA,$SHA"
            MERGE_BASE_FAILED=1
            BASE_SHA=""
            break
        fi
        BASE_SHA="$NEW_BASE"
    done
    if [ -n "$MERGE_BASE_FAILED" ]; then
        # Short-circuit to drift detected; the bootstrap prompt will
        # cover the failing pair alongside any pre-existing unstamped
        # artifacts in one batched user prompt.
        DRIFT_DETECTED=1
    else
        # Range is BASE_SHA..HEAD; comparison is purely against HEAD
        # (D10: the branch is a self-contained capsule). Pathspecs use
        # trailing slashes to make the directory intent explicit and
        # prevent accidental matches against a same-named file in a
        # sibling location.
        # The pathspecs `.claude/workflow/` and `.claude/skills/`
        # deliberately exclude the staged subtree at
        # `docs/adr/*/_workflow/staged-workflow/.claude/workflow/` and
        # `.../staged-workflow/.claude/skills/`. The exclusion holds by
        # prefix difference: staged paths sit under
        # `docs/adr/*/_workflow/staged-workflow/`, which neither
        # `.claude/workflow/` nor `.claude/skills/` matches. This
        # drift-check file is the single canonical source for the
        # exclusion; `migrate-workflow/SKILL.md`'s range computation
        # uses the same pathspecs for symmetry. A future change that
        # broadens these pathspecs must re-check the staged-subtree
        # exclusion at both sites.
        git log --reverse --oneline "$BASE_SHA..HEAD" -- .claude/workflow/ .claude/skills/ | head -10
    fi
fi
```

The drift gate sees one of five outcomes:

- **Any unstamped artifact**: `DRIFT_DETECTED=1`. The Resolutions
  prompt runs regardless of whether `git log` would have returned
  commits; the unstamped state is itself the signal that the artifact
  set predates the stamp scheme and must be migrated.
- **All stamped, non-empty `git log`**: drift detected. The Resolutions
  prompt runs with `BASE_SHA` reported in place of the legacy
  fork-point SHA.
- **All stamped, empty `git log`**: no drift in the strict sense. When
  `STAMPED_SHAS` carries more than one distinct SHA, the gate runs the
  no-drift normalization sub-step (§ No-drift normalization below)
  before exiting; when stamps are already uniform, the gate skips
  silently and startup continues to the calling session's next
  startup step. Any matched condition in § Skip conditions takes the
  same silent path before Phase 1 runs.
- **No artifacts under `$PLAN_DIR/_workflow/`**: the walk matched
  nothing (both `STAMPED_SHAS` and `UNSTAMPED_FILES` are empty). Silent
  skip per `conventions.md` `§1.6(h)`; the gate exits with no drift to
  report and startup continues to the calling session's next startup
  step.
- **`git merge-base` failure during the fold**: any failing pair routes
  to the unstamped short-circuit per `conventions.md` `§1.6(c)`. The gate
  signals drift and the bootstrap prompt in `§1.6(d)` covers the failing
  set alongside any other unstamped artifacts in one batched user
  prompt.

**Cross-reference.** The Phase 1 walk above is the byte-source
shared with `/migrate-workflow`'s Step 2 range computation. The
migration side extends the walk with a paired `STAMPED_PAIRS` array
(one init line plus one assignment inside the stamped branch) so its
merge-base-failure recovery can resolve failing SHAs to artifact
paths; the loop body otherwise stays text-identical between the two
files. The migration side names the same contract explicitly in its
Step 2 prose, so a future edit to the `§1.6(h)` block applies to both
files in lockstep with the pairing rows as the only legitimate
divergence. The range definition (`BASE_SHA..HEAD`, pairwise fold via
`git merge-base`, merge-base-failure recovery), the canonical parser
regex (`workflow-sha:` anchor plus `[0-9a-f]{40}` extraction), and the
active-plan scope rule live in `conventions.md` `§1.6(c)`, `§1.6(h)`, and
`§1.6(a1)` respectively. The `design.md` §"Stamp range computation"
narrative is a soft reference for context but is not the byte-source
for the bash block above — its walk uses an unanchored `[0-9a-f]{40}`
regex that `conventions.md` `§1.6(a1)` explicitly rejects
(false-positives on H1 lines containing a 40-hex run).

---

## No-drift normalization
<!-- roles=orchestrator,planner phases=2,3A summary="Collapse multiple stamps that fold to one BASE_SHA into a single normalization commit, guarded by a strict diff shape." -->

Fires only when Phase 2 reports the empty `git log` (no drift) but
`STAMPED_SHAS` carries more than one distinct SHA — the active plan's
stamps fold to the same `BASE_SHA` yet sit on different commits on
disk. Rewriting every artifact's line-1 stamp to `BASE_SHA` collapses
the next gate's fold input to a single-element set and keeps the gate
O(1) on subsequent runs. Branches whose stamps are already uniform
(i.e., `STAMPED_SHAS` contains a single distinct SHA after
deduplication) skip this sub-step and exit Detection silently.

The sub-step preserves an all-or-nothing contract: either every stamp
in the active plan moves to `BASE_SHA` in one commit, or the on-disk
state is unchanged. The "stamps rewritten without a normalization
commit" in-between state is unreachable under correct invocation. The
diff-shape guard below is the mechanism: the rewrite is staged, the
diff is verified against a strict shape, and on any mismatch the
working tree is restored from `HEAD` before any commit is created.

**Path-quoting assumption.** All paths under `_workflow/**` are
constrained by convention to fixed-template names
(`implementation-plan.md`, `design.md`, `design-mechanics.md`,
`track-<digits>.md`); no path contains shell metacharacters, so
unquoted expansion of `$STAMPED_FILES` in the bash block below is
safe. A future change that introduced spaces or shell metacharacters
in artifact names would require switching to a NUL-delimited path
list before the unquoted expansions become hazardous.

```bash
# Recompute the stamped-artifact path list. The Phase 1 walk in
# conventions.md §1.6(h) exports STAMPED_SHAS and UNSTAMPED_FILES but
# not a companion path list for the stamped set; recomputing the
# stamped set here (under the same enumeration `$PLAN_DIR/_workflow/`
# uses) keeps the byte-copy contract with §1.6(h) intact.
STAMPED_FILES=""
for f in $(ls "$PLAN_DIR/_workflow/implementation-plan.md" \
              "$PLAN_DIR/_workflow/design.md" \
              "$PLAN_DIR/_workflow/design-mechanics.md" \
              "$PLAN_DIR/_workflow/plan/"track-*.md 2>/dev/null); do
    if head -1 "$f" | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'; then
        STAMPED_FILES="$STAMPED_FILES $f"
    fi
done

# Rewrite line 1 of every stamped artifact in place. Use a portable
# printf + tail pattern rather than `sed -i`, whose `-i` flag differs
# between BSD (macOS) and GNU (Linux) — matching the no-`sed -i`
# stance documented in `migrate-workflow/SKILL.md` Step 4.5's stamp
# rewriter.
for f in $STAMPED_FILES; do
    { printf '<!-- workflow-sha: %s -->\n' "$BASE_SHA"; tail -n +2 "$f"; } > "$f.tmp" \
        && mv "$f.tmp" "$f"
done

# Diff-shape guard #1: every hunk in the unstaged diff against the
# stamped artifacts must start with `@@ -1` (line-1 only). Any hunk
# header that names a different starting line means the rewriter
# touched more than the stamp; abort.
DIFF_BAD="$(git diff -U0 -- $STAMPED_FILES | grep -E '^@@' | grep -vE '^@@ -1[, ]' || true)"

# Diff-shape guard #2: porcelain status, scoped to the active plan's
# _workflow/ subtree, must list only the stamped artifacts. The narrow
# scope matches the D12 dirty-check philosophy (`adr.md` D12: "the
# whole-repo clean check is too strict — unrelated edits under `core/`
# or `server/` have no bearing on the migration"); unrelated user
# edits outside `$PLAN_DIR/_workflow/` must not abort the
# normalization. Any path inside the active plan's `_workflow/` that
# the rewrite did not touch (i.e., not in `$STAMPED_FILES`) signals a
# walk that missed an artifact or a pre-existing dirty file the gate
# refuses to swallow; abort.
PORCELAIN_BAD="$(git status --porcelain -- "$PLAN_DIR/_workflow/" | awk '{print $2}' | LC_ALL=C sort -u \
    | comm -23 - <(printf '%s\n' $STAMPED_FILES | LC_ALL=C sort -u))"

if [ -n "$DIFF_BAD" ] || [ -n "$PORCELAIN_BAD" ]; then
    # Restore the pre-rewrite state for the stamped artifacts and
    # surface a clear error. Nothing is staged at this point, so
    # `git checkout --` is sufficient; no `git reset` is needed.
    git checkout -- $STAMPED_FILES
    echo "workflow-sha normalization aborted: diff shape mismatch" >&2
    [ -n "$DIFF_BAD" ]      && echo "  off-line-1 hunks: $DIFF_BAD" >&2
    [ -n "$PORCELAIN_BAD" ] && echo "  unexpected paths: $PORCELAIN_BAD" >&2
    exit 1
fi

# Diff shape verified. Stage the stamped artifacts and commit.
git add -- $STAMPED_FILES
SHORT_BASE_SHA="$(printf '%s' "$BASE_SHA" | cut -c1-7)"
git commit -m "Normalize workflow-sha stamps to $SHORT_BASE_SHA"
exit 0
```

**Diff-shape guard rationale.** The two checks are complementary.
Guard #1 catches a rewriter that touched more than line 1 (e.g., a
printf format that emitted no newline, leaving the new stamp glued
to the artifact's original first line). Guard #2 catches a dirty
path inside the active plan's `_workflow/` that the walk did not
record as stamped (e.g., a new artifact type the walk does not yet
enumerate, or an in-tree edit inside the plan subtree outside the
stamped set). Either failure mode would land a malformed normalization
commit; the abort+restore path keeps the working tree at HEAD on
mismatch and surfaces the failure to the user instead of papering over
it. On `exit 1` (diff-shape mismatch), the gate halts the entire
calling session with a non-zero exit code; the user inspects the
unexpected paths or hunks manually and re-invokes after resolving
them. There is no automatic fallthrough to Resolutions. On `exit 0`
(success path), Detection exits silently and startup continues to
the calling session's next startup step.

**Restore-on-mismatch.** `git checkout -- $STAMPED_FILES` rewinds only
the stamped artifacts because nothing else was touched; the working
tree returns to the exact state Detection observed. If any path
inside the active plan's `_workflow/` but outside `$STAMPED_FILES`
shows up dirty, guard #2 already fired and the abort message names
the path so the user can clean it up manually; the gate refuses to
guess at the recovery shape.

**Commit shape.** One commit, subject `Normalize workflow-sha stamps
to <short-BASE_SHA>`, body optional. The commit is independent of any
phase work — Detection runs at session start before any phase work,
so no episode commit can interleave with the normalization commit.
The next gate run reads the now-uniform stamps and Phase 1 exits with
a single-element `STAMPED_SHAS`.

After the commit lands, Detection exits silently and startup
continues to the calling session's next startup step. The user sees
no prompt; the normalization is a silent housekeeping step, not a
drift signal.

---

## Skip conditions
<!-- roles=orchestrator,planner phases=2,3A summary="Three silent-skip conditions (no _workflow dir, plan complete + Phase 4 active, empty diff) checked cheapest-first." -->

Three silent-skip conditions short-circuit before the prompt fires.
Skip #1 and Skip #2 evaluate pre-Detection (cheap on-disk reads, no
`git log` needed); Skip #3 evaluates post-Phase-2 (after the fold
derives `$BASE_SHA` and Phase 2's `git log` returns its result). All
three scope to the active plan dir (the `$PLAN_DIR` resolved at
startup per `conventions.md` `§1.6(g)` and `§1.2`); cross-plan folding is
out of scope (D13). Order matters for fail-fast: check the cheapest
first:

1. **Active plan's `_workflow/` directory doesn't exist.** Nothing to
   migrate for this plan. Check: `[ -d "$PLAN_DIR/_workflow" ]`.
   Matches the `/migrate-workflow` skill's zero-match halt path when
   the caller's resolved plan dir carries no workflow subtree.

2. **Plan complete plus Phase 4 active.** The active plan's
   `_workflow/` subtree is about to be removed by the Phase 4 cleanup
   commit. Read only `$PLAN_DIR/_workflow/implementation-plan.md`; the
   skip fires when that plan has all track entries `[x]` or `[~]`
   **and** the `## Final Artifacts` entry `[>]` or `[x]`. A missing,
   unreadable, or still-open plan (or Pre-`[>]` Final Artifacts) falls
   through to #3. Pre-`[>]` is still a window for a user migration
   before final artifacts land. The cross-plan AND-fold the
   branch-wide gate used to apply is dropped — each plan migrates
   independently (D13).

3. **Empty diff.** The Phase 2 `git log $BASE_SHA..HEAD` returns
   nothing **and** no artifact in the active plan is unstamped. Every
   stamped artifact's `BASE_SHA` is already at or past every workflow
   commit reachable from HEAD on the paths the gate watches. The
   no-drift normalization sub-step above handles the non-uniform-stamp
   variant of this case; this skip fires when stamps are already
   uniform (or the no-drift normalization just landed on a prior gate
   run).

First match returns silent skip; the gate emits no prose and startup
continues to the calling session's next startup step. None matching
falls through to the three-resolution prompt below.

---

## Resolutions
<!-- roles=orchestrator,planner phases=2,3A summary="On drift, print the commit count and stamp base, then force a Migrate / Defer / Suppress pick with no default." -->

When drift surfaces (non-empty detection output and no skip
condition matched), print the commit count, the short stamp-base SHA,
and the first ten subject lines (oldest first), then force an
explicit pick. Approximate prompt shape:

```
Workflow drift detected: N commits in your branch's range touch .claude/workflow/** or
.claude/skills/** since stamp base <short-BASE_SHA>.

First commits (oldest first):
  <short-sha-1>  <subject-1>
  <short-sha-2>  <subject-2>
  ...

Resolutions:
  [migrate]   end session; user runs /migrate-workflow from this worktree
  [defer]     continue this session; deferred drift will appear in the session-end summary
  [suppress]  continue this session; no session-end reminder

Pick one (no default).
```

Do **not** pick a default. The user must choose one of the three
resolutions before startup proceeds. Malformed answers (`yes`, `ok`)
trigger a re-prompt using the same shape. The retry is bounded at
three attempts per the policy in `conventions.md` `§1.6(d)`; after
three malformed answers the gate halts and the user `/clear`s the
session.

**Unstamped short-circuit rendering.** When `$UNSTAMPED_FILES` is
non-empty (the unstamped path or a `git merge-base` failure routed
there per `conventions.md` `§1.6(c)`), Phase 2 runs no `git log` and
neither `$BASE_SHA` nor the commit count is derived. The Resolutions
prompt substitutes:

- The commit-count line becomes: `One or more workflow artifacts in
  the active plan are unstamped: the /migrate-workflow bootstrap
  prompt will gather a base SHA.`
- The "First commits" block is replaced with `<no commit list:
  artifacts are unstamped>`.
- The Defer todo title becomes `Deferred workflow drift: unstamped
  artifacts in active plan, see /migrate-workflow`.

The three sub-sections below (Migrate now / Defer / Suppress) read
these substitutions verbatim; the Defer state-shape paragraph in
particular points at the same rule.

### Migrate now
<!-- roles=orchestrator,planner phases=2,3A summary="End the session and ask the user to re-invoke /migrate-workflow; the gate never runs the skill inline." -->

End the current session and instruct the user to re-invoke the
migration skill from this worktree:

> Run `/migrate-workflow` from this worktree.

The Migrate now branch deliberately does not run the skill inline.
The skill assumes a fresh session and runs its own context-check
loop with per-commit handoff semantics; mixing two long-running
protocols in one session risks a mid-migration context warning that
triggers the wrong handoff path. Ending the session and asking the
user to re-invoke is the cleaner boundary.

End the session before the calling session reaches its session-end
surface: `/execute-tracks` exits before
`workflow.md § What to do before ending a session` (the only Startup-Protocol-side early exit
for that caller); `/create-plan` exits before Step 5's commit and
push (the Migrate-now branch cuts the session short of the recital
added in Step 5 of `/create-plan`). The calling SKILL appends a
caller-specific re-invocation instruction to the single-line message
above — `/execute-tracks` re-invokes `/execute-tracks`, `/create-plan`
re-invokes `/create-plan`. No phase work has run in either caller,
so there are no episodes to commit, no unpushed-commit residue
beyond what the Branch Divergence Check may have produced
(`/execute-tracks` only — `/create-plan` has no such check), and
self-improvement reflection has nothing to record. The session-end
output is the single instruction line above. For `/create-plan`,
Step 1.5 fires before Step 2's aim prompt and before Step 1b's
`mkdir`, so the "no phase work" claim holds unconditionally: no
research, no plan files, no draft PR.

### Defer
<!-- roles=orchestrator,planner phases=2,3A summary="Continue this session and record a deferred-drift todo recited at session end." -->

Continue this session. Record the deferred-drift count so the
end-of-turn protocol can recite it at session end. Per-phase work
proceeds normally.

State shape: a TaskCreate todo titled `Deferred workflow drift:
<count> commits since <short-stamp-base-SHA>`, where `<count>` is the
full commit range total and `<short-stamp-base-SHA>` is the seven-
character abbreviation of `$BASE_SHA`. Subject lines are omitted; the
user re-runs § Detection's Phase 1 walk plus the Phase 2 `git log` to
recover subject lines (the variables `$PLAN_DIR`, `$STAMPED_SHAS`,
and `$BASE_SHA` come from those phases). The session-end recital
reads the todo title verbatim from the per-caller recital surface:
`/execute-tracks` reads it at
`workflow.md § What to do before ending a session`; `/create-plan` reads it at the recital in
`/create-plan` `SKILL.md` Step 5 (the commit-push-and-PR step; the
recital fires before Step 5 opens the draft PR so the user sees the
residue in the same session). If TaskCreate is unavailable, hold the
same two fields in in-context memory and recite the same line shape
from whichever recital surface the calling session uses; the todo is
preferred because in-context memory is unreliable across long
sessions.

When the deferred drift came from the unstamped short-circuit (the
§ Resolutions § Unstamped short-circuit rendering paragraph above),
the todo title carries the unstamped variant verbatim (`Deferred
workflow drift: unstamped artifacts in active plan, see
/migrate-workflow`) and neither `<count>` nor `<short-stamp-base-SHA>`
appear. Subject-line recovery via § Detection's Phase 1+2 walk does
not apply in this variant — Phase 2 ran no `git log` — so the user
runs `/migrate-workflow` directly when they pick up the deferred
work; the migration prompts for the unstamped-base SHA and
enumerates the affected artifacts from there.

The session-end summary appends the title line plus the same
in-branch `/migrate-workflow` instruction shown in the prompt — see
`workflow.md` § What to do before ending a session for the residue
contract. An on-disk sentinel would survive across calling-session
invocations and double-report against the next session's gate
re-prompt, so the marker stays in-conversation only.

### Suppress
<!-- roles=orchestrator,planner phases=2,3A summary="Continue this session and silence the drift residue with no session-end reminder." -->

Continue this session. Do **not** record the deferred-drift count.
The session-end summary does not mention drift at all. Use this
when the user has already evaluated the commit range and wants to
silence the residue for the rest of the session without ending it.

The functional difference from Defer is one line of session-end
prose; the semantic difference is "I have evaluated and chosen to
ignore for now" versus "remind me at session end".

---

## After the choice
<!-- roles=orchestrator,planner phases=2,3A summary="The chosen resolution holds for the session; Migrate ends it, Defer/Suppress continue startup." -->

The user's choice applies for the remainder of the session; no
re-check is required in the normal startup flow. After Migrate now,
the session ends. After Defer or Suppress, startup continues to the
calling session's next startup step (the handoff scan in both
callers — for `/execute-tracks` this is Startup Protocol step 4; for
`/create-plan` this is Step 1a) and proceeds against the unchanged
on-disk shape of `_workflow/**`.

**Remote-authoritative re-entry — forward-looking note
(`/execute-tracks` only).** The Branch Divergence Check exists only
in `/execute-tracks`'s Startup Protocol (intro lines 3-5), so this
re-entry path applies only when the calling session is
`/execute-tracks`. A `git reset --hard origin/<branch>` from the
divergence gate's Remote-authoritative resolution shifts the fork
point that the divergence gate computes, and the post-reset HEAD
also moves the drift detection range (`$BASE_SHA..HEAD`) since both
endpoints can change. The divergence gate currently routes post-reset
only to `workflow.md` § Startup Protocol step 3 (the
`/execute-tracks`-specific divergence-gate anchor), not back into
this gate (step 3a); the re-entry contract is one-sided. Until that
gap closes, an orchestrator resolving Remote-authoritative within an
`/execute-tracks` session should treat the post-reset drift state as
unverified and re-invoke `/execute-tracks` in a fresh session. Once
symmetric, this gate will re-fire on the post-reset HEAD with any
prior Defer state discarded first. Local-authoritative and Defer in
the divergence gate do not move HEAD and are unaffected.

An in-session non-fast-forward push that re-routes to the Branch
Divergence Check (per `commit-conventions.md` § Push failure handling)
does not re-fire this gate. The drift gate is startup-only;
mid-session re-entry only happens via the Remote-authoritative reset
path above.
