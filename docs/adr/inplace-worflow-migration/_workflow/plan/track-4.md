# Track 4: In-branch migrate-workflow

## Purpose / Big Picture
After this track lands, `/migrate-workflow` runs inside the branch's own worktree, scoped to the active plan's `_workflow/` directory (D13; one plan at a time, matching today's skill contract), ranges over `BASE_SHA..HEAD` (not `origin/develop`), and ends with every artifact in the active plan stamped to `HEAD`'s SHA in one batch.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Rewrite the migration skill to run inside the branch's own worktree, scoped to the active plan's `_workflow/` directory (D13; one plan at a time, matching today's skill contract): drop the develop-worktree preflight (Step 1); collapse Step 2 to "active branch + clean tree + pick the active plan dir via today's zero/one/many ladder over `docs/adr/*/_workflow/`"; tighten the preflight to refuse on tracked-uncommitted or untracked files under the active plan's `_workflow/**` with the progress-sentinel carve-out (D12); add a Step 3.0 that prompts the user once for an unstamped-artifact base SHA when any unstamped artifacts exist in the active plan; replace the Step 3 commit-range derivation with the same stamp-walking logic the drift check uses (range `BASE_SHA..HEAD`, no fetch); update Step 5's per-commit replay so every stamp in the active plan advances to the just-replayed commit's SHA in lockstep (preserves crash-resume); add a final post-loop step that re-stamps every artifact in the active plan to `HEAD`'s SHA in one batch (D2 + D10). Renames tracker stays. Final summary names the post-run state ("stamps now at `<HEAD-SHA>`") and reminds the user to commit + push. Workflow-modifying branches (this very plan's branch) accept dogfood semantics: own workflow commits register as drift and trigger migration of in-progress changes.

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

`.claude/skills/migrate-workflow/SKILL.md` (~290 lines, 7 step sections) is the canonical migration skill. The current step layout:

| Step | Today's purpose | Track-4 fate |
|---|---|---|
| 0 | Create progress tracker via `TaskCreate` | Keep (relabel umbrella titles; add tasks for 3.0 bootstrap and final stamp-to-HEAD batch) |
| 1 | Preflight — must be on `develop`, clean tree, validate `$ARGUMENTS` | Drop the develop check; relax the argument contract since `$ARGUMENTS` becomes optional; add a refusal when any tracked file under `_workflow/**` has uncommitted changes (working tree or index) or any untracked file lives under `_workflow/**` (progress-sentinel carve-out) — D12 |
| 2 | Resolve migration worktree path via `git worktree list --porcelain` | Delete entirely — migration runs in cwd |
| 3.0 | (new) Unstamped-artifact bootstrap prompt | Add: enumerate `_workflow/**` artifacts, classify stamped vs unstamped; if any unstamped, prompt user once for a base SHA covering the unstamped set, validate via `git rev-parse --verify` + `git merge-base --is-ancestor "$SHA" HEAD`, re-prompt up to 3 times on failure, abort the session on 3 rejections |
| 3 | Compute commit range from `merge-base(develop, branch)..develop` | Replace with stamp-walking range derivation from `design.md` §"Stamp range computation" — fold `STAMPED_SHAS` plus the validated user SHA (when 3.0 ran) through `git merge-base` to derive `BASE_SHA`; range is `BASE_SHA..HEAD` (no `git fetch`) — D10 |
| 4 | Load/initialize `.migration-progress`; identify target `_workflow/` | Keep, but the progress file is now a transient crash-recovery sentinel only; stamps are the durable marker |
| 5 | Per-commit replay loop (context check / classify / apply / record) | Keep the loop shape; add a sub-step 5.6 *Advance stamps in lockstep* between 5.4 and 5.5 |
| 5.7 | (new) Final stamp-to-HEAD batch | Add after the loop exits: re-stamp every artifact's line-1 SHA to `git rev-parse HEAD` in one batch, including artifacts the loop's per-commit advance already updated — invariant I2 lands here (D2) |
| 6 | Final summary | Update to report final stamp SHA (= HEAD) + "commit your changes" reminder |

`$ARGUMENTS` becomes optional — when called from the branch's own worktree, the argument is redundant (current branch IS the migration branch). When provided, it must match the current branch; otherwise halt with a clear error.

The renames tracker (`# renames:` header block in `.migration-progress`) stays. Renames are derived per-commit via `git show --diff-filter=R` and the tracker is rebuilt fresh each invocation — same shape as today.

The stamp-walking bash block must be byte-identical to Track 3's copy in `workflow-drift-check.md`. Both files own their own copy; the canonical text lives in `design.md` §"Stamp range computation".

## Plan of Work

The rewrite proceeds in roughly eight edits, in order:

1. **Step 0 / Step 1 relax + dirty-`_workflow/**` refusal.** Drop the `git branch --show-current = develop` check and the `$ARGUMENTS = branch-name` validation. Replace with "active branch + clean tree + resolve active plan dir" preflight (the active plan dir is picked via today's skill ladder — enumerate `docs/adr/*/_workflow/`, apply zero/one/many, with `$ARGUMENTS` optionally pre-selecting). Add a refusal: enumerate `git status --porcelain "$PLAN_DIR/_workflow/"` against the resolved plan dir; if any tracked file shows a non-`?` status (modified, added, deleted, renamed, etc.) or any untracked file shows `??`, halt with the offending paths printed and instructions to commit, stash, or remove them. The `.migration-progress` sentinel is the only exception — it is allowed to be untracked or modified mid-session. Update Step 0's umbrella task labels to drop the "migration worktree" terminology and add tasks for Step 3.0 (bootstrap) and Step 5.7 (final stamp-to-HEAD batch).

2. **Step 2 deletion.** Remove the entire "Resolve migration worktree path" section. Renumber subsequent steps in the prose and any cross-references.

3. **Step 3.0 add (unstamped-artifact bootstrap).** Insert a new step before Step 3. Walk the active plan's `_workflow/**` ephemeral artifacts (the plan dir resolved in Step 1), classify each as stamped (line-1 stamp parses) or unstamped (no stamp); collect `STAMPED_SHAS` and `UNSTAMPED_FILES`. If `UNSTAMPED_FILES` is empty, no-op and continue to Step 3. Otherwise prompt the user once with the unstamped-file list and a one-paragraph explanation of why no auto-computed reference is safe (D8). Read a 40-character SHA; validate via `git rev-parse --verify "$SHA^{commit}"` and `git merge-base --is-ancestor "$SHA" HEAD` (the supplied SHA must be reachable from HEAD because HEAD is the comparison reference — D10). Re-prompt up to 3 times on validation failure; abort the session on 3 rejections (no edits applied). The validated SHA is stored as `$USER_BOOTSTRAP_SHA` for Step 3.

4. **Step 3 rewrite.** Replace the old commit-range bash with the stamp-walking variant from `design.md` §"Stamp range computation". The fold input set is `STAMPED_SHAS` plus `$USER_BOOTSTRAP_SHA` when Step 3.0 ran. Output is `BASE_SHA` and the `git log` of commits in `$BASE_SHA..HEAD` against workflow paths (no `git fetch`). Cross-link to `conventions.md` §1.6 and `design.md` §"Stamp range computation".

5. **Step 4 simplification.** The `.migration-progress` file stays in shape but its semantic role shifts to "in-flight session crash-recovery sentinel" — the durable progress marker is now the per-artifact stamps. The progress file's header `head=<sha>` field becomes `range_end=<sha>` (= `git rev-parse HEAD` at session start); the `fork=<sha>` field becomes `range_start=<sha>` (= `BASE_SHA`). The renames block is unchanged. Update the file-existence handling: if `range_start` in the file doesn't match the freshly-computed `BASE_SHA`, halt and ask the user to delete the file (same as today's stale-fork check, just under the new field names).

6. **Step 5.6 add (per-commit lockstep advance).** Insert a new sub-step between 5.4 (apply edits) and 5.5 (update progress file) that rewrites line 1 of every stamped-or-just-stamped artifact in the active `_workflow/` to `<!-- workflow-sha: <current-commit-sha> -->` via `sed -i` (or `Edit`). Document the ordering invariant in the sub-step prose: edits → advance, never the reverse. Previously unstamped artifacts get their first stamp written here as a side effect. This sub-step is the crash-resume marker — without it, a mid-loop crash leaves stamps behind.

7. **Step 5.7 add (final stamp-to-HEAD batch).** After the per-commit loop exits, re-stamp every artifact's line-1 SHA to `git rev-parse HEAD` in one batch — even artifacts the loop already advanced to the same value. This is the invariant-landing step (I2). The reason both 5.6 and 5.7 exist: 5.6 preserves resume on mid-loop crash; 5.7 closes the gap when the last replayed commit's SHA != HEAD's SHA (e.g., HEAD has non-workflow commits past the last workflow commit in the range).

8. **Step 6 final summary.** Add a line reporting "Workflow stamps now at `<HEAD-sha>`" before the existing per-classification counts. When Step 3.0 ran, also report "Bootstrapped N previously unstamped artifacts using SHA `<user-sha>`." Replace the closing instruction to point at "Review the diff in the current worktree, then commit + push when satisfied" — no more "<migration-worktree>" reference. Note that Track 5 will append a Step 7 (self-improvement reflection) AFTER this final summary.

Cross-cutting: every `<migration-worktree>` placeholder in the file becomes either "the current worktree" or just disappears. Every cross-reference to running "from develop" is removed. The "two worktrees" mental model is gone — readers should never wonder which worktree owns which file.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 4 lands:

- Running `/migrate-workflow` from a feature branch's worktree no longer halts on the develop-worktree check.
- The `$ARGUMENTS` parameter is optional; supplying it produces a no-op match check against `git branch --show-current`.
- The preflight refuses to start if any tracked file under `_workflow/**` has uncommitted changes (working tree or index), or if any untracked file under `_workflow/**` exists, except for the `.migration-progress` sentinel. The refusal prints the offending paths and exits with no edits applied.
- On a branch with unstamped artifacts, Step 3.0 prompts the user once, validates the input via `git merge-base --is-ancestor "$SHA" HEAD`, and stores it as `$USER_BOOTSTRAP_SHA`. Three rejected attempts abort the session with no edits applied.
- On a branch with all-stamped artifacts, Step 3.0 is a no-op (no prompt fires).
- Step 3 produces a commit list whose lower bound is `BASE_SHA` (the `git merge-base` fold over `STAMPED_SHAS` plus `$USER_BOOTSTRAP_SHA` when present) and upper bound is `HEAD` — no `git fetch origin develop` runs. On a fully-stamped branch where `git log $BASE_SHA..HEAD -- workflow paths` is empty, Step 3 halts with "Nothing to migrate" without running the per-commit loop.
- After a successful per-commit replay of commit X, every artifact in the active `_workflow/` directory has `head -1 <artifact>` equal to `<!-- workflow-sha: X -->`, including previously unstamped artifacts that gain their first stamp during the lockstep advance.
- After the loop exits successfully, Step 5.7 re-stamps every artifact's line-1 SHA to `git rev-parse HEAD`. Invariant I2 holds: every stamp equals HEAD's SHA at session end.
- A killed-mid-loop session resumed by re-invoking `/migrate-workflow` reads the latest stamp from any artifact, re-runs Step 3.0 (which is a no-op now that every artifact is stamped), recomputes the range, and picks up at the next commit.
- The renames tracker still drives path mapping for commits that follow rename-classified commits within the same migration.
- The final summary names HEAD's SHA and instructs the user to commit + push; when Step 3.0 ran, it also reports the bootstrap SHA and count of previously unstamped artifacts.
- On a workflow-modifying branch, the migration replays the branch's own workflow commits onto its own `_workflow/**` artifacts. One migration session per commit cluster touching `.claude/workflow/` or `.claude/skills/` (dogfood).
- No reference to `<migration-worktree>` survives in the file.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/skills/migrate-workflow/SKILL.md` (Steps 0, 1, 2, 3, 4, 5.6 add, 6 — every step except the per-commit classification logic in Step 5.3 and the in-loop context check in Step 5.1)

**Out-of-scope files:**
- `.claude/workflow/conventions.md` (Track 1)
- `.claude/skills/create-plan/SKILL.md`, `.claude/skills/edit-design/SKILL.md` (Track 2)
- `.claude/workflow/workflow-drift-check.md` (Track 3 — shares the bash block by byte-identical copy)
- `.claude/workflow/self-improvement-reflection.md` and the new Step 7 wiring (Track 5)

**Inter-track dependencies:**
- **Depends on:** Track 1 (stamp format + SHA computation in `conventions.md` §1.6).
- **Coordinates with:** Track 3 (shared stamp-walking bash block — both files own a copy, must be byte-identical).
- **Unblocks:** Track 5 (Track 5 appends Step 7 to this file after Track 4 lands).

**External interfaces:**
- `git rev-parse HEAD` — the final-batch stamp value (Step 5.7).
- `git status --porcelain "$PLAN_DIR/_workflow/"` against the resolved active plan dir — the dirty-`_workflow/**` preflight check (Step 1).
- `git rev-parse --verify "$SHA^{commit}"` and `git merge-base --is-ancestor "$SHA" HEAD` — used by Step 3.0 to validate the user-supplied bootstrap SHA before it enters the fold. HEAD is the reachability anchor because the range upper bound is HEAD (D10).
- `git merge-base <a> <b>` — pairwise fold across stamps to find the oldest reachable ancestor (`BASE_SHA`). Handles linear and divergent histories uniformly.
- `git log $BASE_SHA..HEAD -- .claude/workflow .claude/skills` — the commit range for replay.
- `sed -i "1s/.*/<!-- workflow-sha: <new-sha> -->/" <file>` — the lockstep advance writer (Step 5.6) and the final-batch writer (Step 5.7). Idempotent: re-running on an already-stamped file with the same SHA is a no-op.
