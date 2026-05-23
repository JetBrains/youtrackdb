# Workflow Drift Check

Runs in turn 1 of every `/execute-tracks` session, immediately after
the Branch Divergence Check (workflow.md § Startup Protocol step 3)
and before the handoff scan (step 4). Undetected drift surfaces later
as confused reviewers in Phase C, missing required sections during
track completion, or auto-resume tripping on a schema field the
branch never gained — exactly the failure mode this gate prevents.
The branch carries per-branch `_workflow/**` artifacts whose required
shape is dictated by current `develop`: section names, mandatory
artifacts, step-file schema. Workflow-format commits land on
`develop` while the branch runs, and the branch's artifacts silently
drift.

Detection is one `git log` over the active plan's stamp-derived range
against HEAD. The branch is a self-contained capsule — workflow-format
commits enter the branch's view only when the user explicitly rebases
or merges `develop` — so the gate ranges against the branch's own
HEAD and never fetches `develop` independently. The plan dir the
caller resolved at startup (see `conventions.md` §1.2 and §1.6(g))
scopes the walk; cross-plan folding is out of scope (D13). Migration
itself stays in the `/migrate-workflow` skill — the gate detects and
gates, the skill replays. The skill assumes a fresh session, so the
Migrate now resolution ends this session and asks the user to
re-invoke `/migrate-workflow` from this worktree.

---

## Detection

The Detection bash has two phases. **Phase 1** walks the active plan's
`_workflow/**` artifacts and classifies each as stamped or unstamped
(byte-copied from `conventions.md` §1.6(h) so the drift check and the
migration agree on what "drift" means). **Phase 2** is caller-specific:
the drift check signals drift unconditionally when any artifact is
unstamped (no fold, no `git log`); when every artifact is stamped, the
gate folds the stamp set pairwise through `git merge-base` to derive
`BASE_SHA` and runs `git log $BASE_SHA..HEAD` against workflow paths.

The Phase 1 walk block, byte-for-byte from `conventions.md` §1.6(h):

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
resolved at startup per `conventions.md` §1.6(g). The walk silently
skips artifacts not yet on disk (`design-mechanics.md` before the
length trigger, any `track-*.md` not yet created), so absent optional
artifacts contribute neither a stamp nor an unstamped flag.

Phase 2 (drift-check-specific decision):

```bash
if [ -n "$UNSTAMPED_FILES" ]; then
    # Any unstamped artifact short-circuits to drift detected — no fold,
    # no git log. The /migrate-workflow skill's bootstrap prompt (per
    # conventions.md §1.6(d)) will gather a base SHA for the unstamped set.
    DRIFT_DETECTED=1
else
    # Every artifact in the active plan is stamped. Fold the stamp set
    # pairwise through git merge-base to derive BASE_SHA — the oldest
    # stamp reachable from HEAD (conventions.md §1.6(c)).
    BASE_SHA=""
    for SHA in $STAMPED_SHAS; do
        if [ -z "$BASE_SHA" ]; then
            BASE_SHA="$SHA"
        else
            BASE_SHA="$(git merge-base "$BASE_SHA" "$SHA")"
        fi
    done
    # Range is BASE_SHA..HEAD; comparison is purely against HEAD
    # (D10 — the branch is a self-contained capsule). Pathspecs use
    # trailing slashes to make the directory intent explicit and
    # prevent accidental matches against a same-named file in a
    # sibling location.
    git log --reverse --oneline "$BASE_SHA..HEAD" -- .claude/workflow/ .claude/skills/ | head -10
fi
```

The drift gate sees one of three outcomes:

- **Any unstamped artifact** — `DRIFT_DETECTED=1`. The Resolutions
  prompt runs regardless of whether `git log` would have returned
  commits; the unstamped state is itself the signal that the artifact
  set predates the stamp scheme and must be migrated.
- **All stamped, non-empty `git log`** — drift detected. The Resolutions
  prompt runs with `BASE_SHA` reported in place of the legacy
  fork-point SHA.
- **All stamped, empty `git log`** — no drift in the strict sense. When
  `STAMPED_SHAS` carries more than one distinct SHA, the gate runs the
  no-drift normalization sub-step (§ No-drift normalization below)
  before exiting; when stamps are already uniform, the gate skips
  silently and startup continues to step 4. Any matched condition in
  § Skip conditions takes the same silent path before Phase 1 runs.

**Cross-reference.** The Phase 1 walk above is the byte-source
shared with `/migrate-workflow`'s Step 2 range computation; both
files must remain text-identical. The range definition
(`BASE_SHA..HEAD`, pairwise fold via `git merge-base`, merge-base-
failure recovery), the canonical parser regex (`workflow-sha:`
anchor plus `[0-9a-f]{40}` extraction), and the active-plan scope
rule live in `conventions.md` §1.6(c), §1.6(h), and §1.6(a1)
respectively. The `design.md` §"Stamp range computation" narrative
is a soft reference for context but is not the byte-source for the
bash block above — its walk uses an unanchored `[0-9a-f]{40}` regex
that `conventions.md` §1.6(a1) explicitly rejects (false-positives
on H1 lines containing a 40-hex run).

---

## No-drift normalization

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

# Rewrite line 1 of every stamped artifact in place.
for f in $STAMPED_FILES; do
    sed -i "1s/.*/<!-- workflow-sha: $BASE_SHA -->/" "$f"
done

# Diff-shape guard #1: every hunk in the unstaged diff against the
# stamped artifacts must start with `@@ -1` (line-1 only). Any hunk
# header that names a different starting line means sed rewrote more
# than the stamp; abort.
DIFF_BAD="$(git diff -U0 -- $STAMPED_FILES | grep -E '^@@' | grep -vE '^@@ -1[, ]' || true)"

# Diff-shape guard #2: porcelain status must list only the stamped
# artifacts. Any other modified path means the rewrite touched
# something outside the planned set; abort.
PORCELAIN_BAD="$(git status --porcelain -- . | awk '{print $2}' | LC_ALL=C sort -u \
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
```

**Diff-shape guard rationale.** The two checks are complementary.
Guard #1 catches a sed expression that rewrote line 1 plus stray
trailing lines on the same file (e.g., a CRLF-stripped artifact where
`1s/.*/.../` ate a trailing carriage return on every line). Guard #2
catches a `STAMPED_FILES` set that silently expanded to include an
artifact outside the active plan (e.g., a pathspec glob picking up a
stray file). Either failure mode would land a malformed normalization
commit; the abort+restore path keeps the working tree at HEAD on
mismatch and surfaces the failure to the user instead of papering over
it.

**Restore-on-mismatch.** `git checkout -- $STAMPED_FILES` rewinds only
the stamped artifacts because nothing else was touched; the working
tree returns to the exact state Detection observed. If the rewrite
left any path outside `$STAMPED_FILES` modified, guard #2 already
fired and the abort message names the path so the user can clean it
up manually — the gate refuses to guess at the recovery shape.

**Commit shape.** One commit, subject `Normalize workflow-sha stamps
to <short-BASE_SHA>`, body optional. The commit is independent of any
phase work — Detection runs in turn 1 before the handoff scan, so no
episode commit can interleave with the normalization commit. The next
gate run reads the now-uniform stamps and Phase 1 exits with a
single-element `STAMPED_SHAS`.

After the commit lands, Detection exits silently and startup
continues to step 4. The user sees no prompt; the normalization is a
silent housekeeping step, not a drift signal.

---

## Skip conditions

The gate skips silently in three cases, all derivable from cheap
on-disk reads before the detection command runs. All three scope to
the active plan dir (the `$PLAN_DIR` resolved at startup per
`conventions.md` §1.6(g) and §1.2); cross-plan folding is out of scope
(D13). Order matters for fail-fast — check the cheapest first:

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
continues to step 4. None matching falls through to the
three-resolution prompt below.

---

## Resolutions

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
trigger a re-prompt using the same shape — same contract as the
Branch Divergence Check.

### Migrate now

End the current session and instruct the user to re-invoke the
migration skill from this worktree:

> Run `/migrate-workflow` from this worktree.

The Migrate now branch deliberately does not run the skill inline.
The skill assumes a fresh session and runs its own context-check
loop with per-commit handoff semantics; mixing two long-running
protocols in one session risks a mid-migration context warning that
triggers the wrong handoff path. Ending the session and asking the
user to re-invoke is the cleaner boundary.

End the session before reaching `workflow.md § What to do before
ending a session` — this is the only Startup-Protocol-side early
exit. No phase work has run, so there are no episodes to commit, no
unpushed-commit residue beyond what the Branch Divergence Check may
have produced, and self-improvement reflection has nothing to record.
The session-end output is the single instruction line above.

### Defer

Continue this session. Record the deferred-drift count so the
end-of-turn protocol can recite it at session end. Per-phase work
proceeds normally.

State shape: a TaskCreate todo titled `Deferred workflow drift:
<count> commits since <short-stamp-base-SHA>`, where `<count>` is the
full commit range total and `<short-stamp-base-SHA>` is the seven-
character abbreviation of `$BASE_SHA`. Subject lines are omitted —
the user re-runs the detection bash for full context. The session-end
summary reads the todo title verbatim. If TaskCreate is unavailable,
hold the same two fields in in-context memory and recite the same
line shape; the todo is preferred because in-context memory is
unreliable across long sessions.

The session-end summary appends the title line plus the same
in-branch `/migrate-workflow` instruction shown in the prompt — see
`workflow.md` § What to do before ending a session for the residue
contract. An on-disk sentinel would survive across
`/execute-tracks` invocations and double-report against the next
session's gate re-prompt, so the marker stays in-conversation only.

### Suppress

Continue this session. Do **not** record the deferred-drift count.
The session-end summary does not mention drift at all. Use this
when the user has already evaluated the commit range and wants to
silence the residue for the rest of the session without ending it.

The functional difference from Defer is one line of session-end
prose; the semantic difference is "I have evaluated and chosen to
ignore for now" versus "remind me at session end".

---

## After the choice

The user's choice applies for the remainder of the session; no
re-check is required in the normal startup flow. After Migrate now,
the session ends. After Defer or Suppress, startup continues to
step 4 (handoff scan) and the auto-resume decision proceeds against
the unchanged on-disk shape of `_workflow/**`.

**Remote-authoritative re-entry — forward-looking note.** A
`git reset --hard origin/<branch>` from the divergence gate's
Remote-authoritative resolution shifts the fork point and therefore
the drift detection range. The divergence gate currently routes
post-reset only to workflow.md § Startup Protocol step 3, not back
into this gate (step 3a); the re-entry contract is one-sided. Until
that gap closes, an orchestrator resolving Remote-authoritative
within a session should treat the post-reset drift state as
unverified and re-invoke `/execute-tracks` in a fresh session. Once
symmetric, this gate will re-fire on the post-reset HEAD with any
prior Defer state discarded first. Local-authoritative and Defer in
the divergence gate do not move HEAD and are unaffected.

An in-session non-fast-forward push that re-routes to the Branch
Divergence Check (per `commit-conventions.md` § Push failure handling)
does not re-fire this gate. The drift gate is startup-only;
mid-session re-entry only happens via the Remote-authoritative reset
path above.
