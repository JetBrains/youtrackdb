# Track 3: SHA-aware drift check

## Purpose / Big Picture
After this track lands, the `/execute-tracks` startup drift gate reads workflow-SHA stamps from the active plan's `_workflow/**` artifacts, compares against HEAD (not `origin/develop`), and routes the user to an in-branch migration. On no-drift with non-uniform stamps, it normalizes every stamp in the active plan to the fold result and commits the change in a separate commit.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Rewrite the Detection section of `workflow-drift-check.md` to walk every `_workflow/**` artifact in the active plan's `_workflow/` directory (D13), classify each as stamped or unstamped, and apply the two-phase rule: any unstamped artifact short-circuits to "drift detected" with no fold; when every artifact in the active plan is stamped, fold the SHA set pairwise through `git merge-base` to derive `BASE_SHA` and run `git log $BASE_SHA..HEAD` against workflow paths. Drop the `git fetch origin develop` step — comparison is purely against HEAD (D10). Update the "Migrate now" resolution text to instruct an in-branch re-invocation of `/migrate-workflow`. On no-drift with non-uniform stamps, normalize every artifact's stamp in the active plan to the fold result and create a separate commit (D11). Skip conditions tighten scope to the active plan (Track 3 Plan of Work); the Defer/Suppress flows stay structurally the same.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Empty at Phase 1. -->

## Context and Orientation

`.claude/workflow/workflow-drift-check.md` (~220 lines) is the turn-1 gate `/execute-tracks` runs after the Branch Divergence Check and before the handoff scan. Today's gate is branch-wide; the rewrite scopes it to the active plan's `_workflow/` directory (D13). Today's Detection section computes:

```bash
git fetch origin develop 2>/dev/null || true
git rev-parse --verify origin/develop >/dev/null 2>&1 || exit
FORK="$(git merge-base origin/develop HEAD)"
test -n "$FORK" || exit
git log --reverse --oneline "$FORK..origin/develop" -- .claude/workflow/ .claude/skills/ | head -10
```

The new derivation matches `design.md` §"Stamp range computation" verbatim. Phase 1 (the walk) is shared with Track 4a: enumerate every ephemeral artifact under the active plan's `_workflow/**` (the plan dir the caller resolved at startup), classify each as stamped (line-1 stamp parses) or unstamped (no stamp on line 1), and collect `STAMPED_SHAS` and `UNSTAMPED_FILES`. Phase 2 (the decision) is caller-specific: the drift check signals drift unconditionally when `UNSTAMPED_FILES` is non-empty (no fold, no `git log`); when everything is stamped, it folds `STAMPED_SHAS` pairwise through `git merge-base` to derive `BASE_SHA` and runs `git log $BASE_SHA..HEAD` against workflow paths (no `git fetch` — the branch is a self-contained capsule per D10). The Phase 1 bash block is shared byte-for-byte with Track 4a — keeping them text-identical is what makes the drift check and the migration agree on what "drift" means.

A new behavior fires when Phase 2 determines no drift but `STAMPED_SHAS` contains more than one distinct SHA: normalize every artifact's line-1 stamp to `BASE_SHA` (the fold result) and create a separate commit titled along the lines of `Normalize workflow-sha stamps to <short-BASE_SHA>` (D11). The next gate's fold input is then a single-element set, and the gate becomes O(1). Branches whose stamps are already uniform skip the normalization silently.

The Resolutions section needs a wording-only change in the "Migrate now" branch: today's "Switch to a `develop` worktree (e.g., `cd ../develop`) and run `/migrate-workflow <branch>` there" becomes "Run `/migrate-workflow` from this worktree."

The Skip conditions section is structurally unchanged, but scopes tighten to the active plan (D13): the "Active plan's `_workflow/` doesn't exist" skip (was branch-wide "No `_workflow/` subtree") checks just the resolved plan dir; the "Plan complete plus Phase 4 active" skip reads only the active plan's `implementation-plan.md` (was every plan on the branch); the "Empty diff" skip reads from the new range and stays the same in spirit.

## Plan of Work

Replace the Detection bash block with the stamp-walking variant defined in `design.md` §"Stamp range computation". The new block has no `git fetch` and no `git rev-parse --verify origin/develop` — both go away because comparison is purely against HEAD. Keep the surrounding prose explaining what the detection produces (the commit list, the short fork SHA in resolution prompts) but update any reference to `$FORK` so the prompt now reports `BASE_SHA` (the merge-base fold result) — the user wants to see which SHA the gate decided to compare against.

Add the no-drift normalization sub-step after the Phase 2 decision. When `STAMPED_SHAS` contains more than one distinct SHA and Phase 2 reports no drift, rewrite every artifact's line-1 stamp to `BASE_SHA` via `sed -i "1s/.*/<!-- workflow-sha: $BASE_SHA -->/"` and create a separate commit. Verify the diff touches only line-1 of stamped artifacts (no other content changes) before committing — refuse otherwise to avoid swallowing unrelated edits.

Update the Resolutions section's "Migrate now" sub-section. Replace the two-worktree instruction with "Run `/migrate-workflow` from this worktree." Remove the rationale paragraph about why the skill assumes a fresh session (it still does, but no worktree switch is implied). Keep the early-exit contract (end session before reaching `workflow.md § What to do before ending a session`) — that part is unchanged.

Update the "After the choice" section's Remote-authoritative re-entry note. It currently refers to a one-sided contract pending a symmetric edit to `branch-divergence-check.md`. The contract stays one-sided in this track — fixing it is out of scope for the in-place migration work — but the prose may need a small tweak to reflect that the post-reset re-entry now lands the user back in the same worktree (no develop-worktree switch).

Add a one-line note in the §Detection prose pointing readers at `design.md` §"Stamp range computation" and `conventions.md` §1.6 (the Track 1 section) for the canonical definitions of stamp format and SHA computation.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 3 lands:

- The Detection section of `workflow-drift-check.md` enumerates ephemeral artifacts under the active plan's `_workflow/**` (the plan dir the caller resolved at startup), reads line-1 stamps with regex `[0-9a-f]{40}`, classifies each artifact as stamped or unstamped, and applies the two-phase rule.
- The Detection bash contains no `git fetch origin develop` and no `git rev-parse --verify origin/develop` — comparison is purely against HEAD.
- The "Migrate now" resolution wording no longer mentions `develop` worktree, `cd ../develop`, or any worktree switch.
- The "Empty diff" skip condition still triggers when `git log $BASE_SHA..HEAD -- .claude/workflow .claude/skills` is empty AND no artifact is unstamped.
- When the active plan carries any unstamped artifact, the gate signals drift unconditionally and routes to the three-resolution prompt (Migrate now / Defer / Suppress) — independent of whether `git log` would return commits.
- When every artifact in the active plan is stamped, the stamps fold to HEAD's latest workflow-touching commit, and the stamps are already uniform, the gate skips silently (empty range, no unstamped artifacts, no normalization needed).
- When every artifact in the active plan is stamped but the stamps are non-uniform yet the `$BASE_SHA..HEAD` range is empty for workflow paths, the gate normalizes every stamp in the active plan to `BASE_SHA` and creates a separate commit; the next gate run sees uniform stamps and a single-element fold input.
- The normalization step refuses to commit if the diff contains any change outside line-1 of stamped artifacts.
- The three-resolution prompt keeps its no-default contract.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/workflow/workflow-drift-check.md` (Detection section bash + prose, Resolutions section "Migrate now" branch, "After the choice" Remote-authoritative note)

**Out-of-scope files:**
- `.claude/workflow/conventions.md` (Track 1)
- `.claude/skills/create-plan/SKILL.md`, `.claude/skills/edit-design/SKILL.md` (Track 2)
- `.claude/skills/migrate-workflow/SKILL.md` (Track 4a — reads the same bash block but owns its own copy in Step 2 after Track 4a's renumber-down)
- `.claude/workflow/branch-divergence-check.md` (the Remote-authoritative re-entry contract symmetry is explicitly NOT fixed here)

**Inter-track dependencies:**
- **Depends on:** Track 1 (stamp format + SHA computation rule defined in `conventions.md` §1.6).
- Coordinates with Track 4a on the bash block — both files carry a copy. The two copies must be byte-identical (verified by `diff` between the two extracted blocks during Phase C review of whichever track lands second).

**External interfaces:**
- `git merge-base` (pairwise fold), `git log $BASE_SHA..HEAD -- workflow paths`, `git rev-parse HEAD`, `sed -i` (stamp rewrite), `git add` + `git commit` (normalization commit). The `git fetch origin develop` and `git rev-parse --verify origin/develop` invocations are removed.
