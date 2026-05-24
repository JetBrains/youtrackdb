# Track 4a: Migration preflight + range computation

## Purpose / Big Picture
After this track lands, `/migrate-workflow`'s preflight and range computation run inside the branch's own worktree, scoped to the active plan's `_workflow/` directory (D13). The develop-worktree resolution is gone. The unstamped-artifact bootstrap prompt (D8) and the stamp-walking range derivation (D10) are wired in. The per-commit replay loop and final stamp-to-HEAD batch land in Track 4b.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

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

`.claude/skills/migrate-workflow/SKILL.md` (~290 lines, 7 step sections) is the canonical migration skill. After Track 4a, today's Step 2 is deleted (no separate migration worktree) and the leading steps renumber down by one; today's Step 3.0 lands as new Step 2.0. The per-commit replay loop and final batch are out of scope here — they land in Track 4b. The table covers the steps Track 4a touches (Steps 0, 1, 2.0, 2, 3 in post-renumber numbering):

| Old → New | Today's purpose | Track-4a fate |
|---|---|---|
| 0 → 0 | Create progress tracker via `TaskCreate` | Keep (relabel umbrella titles; add tasks for new Step 2.0 bootstrap; Track 4b adds the task for new sub-step 4.8 final stamp-to-HEAD batch) |
| 1 → 1 | Preflight — must be on `develop`, clean tree, validate `$ARGUMENTS` | Drop the develop check; relax the argument contract since `$ARGUMENTS` becomes optional; add a refusal when any tracked file under `_workflow/**` has uncommitted changes (working tree or index) or any untracked file lives under `_workflow/**` (progress-sentinel carve-out) — D12 |
| 2 → (deleted) | Resolve migration worktree path via `git worktree list --porcelain` | Delete entirely — migration runs in cwd. Subsequent top-level steps renumber down. |
| 3.0 → 2.0 | (new) Unstamped-artifact bootstrap prompt | Add: enumerate `_workflow/**` artifacts, classify stamped vs unstamped; if any unstamped, prompt user once for a base SHA covering the unstamped set, validate via `git rev-parse --verify` + `git merge-base --is-ancestor "$SHA" HEAD`, re-prompt up to 3 times on failure, abort the session on 3 rejections |
| 3 → 2 | Compute commit range from `merge-base(develop, branch)..develop` | Replace with stamp-walking range derivation from `conventions.md` §1.6(h) — fold `STAMPED_SHAS` plus the validated user SHA (when 2.0 ran) through `git merge-base` to derive `BASE_SHA`; range is `BASE_SHA..HEAD` (no `git fetch`) — D10 |
| 4 → 3 | Load/initialize `.migration-progress`; identify target `_workflow/` | Keep, but the progress file is now a transient crash-recovery sentinel only; stamps are the durable marker |

`$ARGUMENTS` becomes optional — when called from the branch's own worktree, the argument is redundant (current branch IS the migration branch). When provided, it must match the current branch; otherwise halt with a clear error.

The stamp-walking bash block must be byte-identical to Track 3's copy in `workflow-drift-check.md`. Both files own their own copy; the canonical text lives in `conventions.md` §1.6(h).

§1.6(h) is the Phase 1 walk only (enumerate-and-classify). Phase 2 (fold + range derivation + failure recovery) is caller-specific, governed by `conventions.md` §1.6(c) and the final paragraph of §1.6(h). Track 3 implemented the drift-check-side variant of those branches; Track 4a implements the migration-side variant. The two render the same canonical contracts differently — see §Plan of Work Step 4 for the migration's specific obligations.

## Plan of Work

The rewrite proceeds in roughly five edits, in order. Step numbers below are the post-Track-4a numbering (today's Step 2 is deleted, today's Steps 3 → 4 renumber down by one, today's Step 3.0 becomes 2.0).

1. **Step 0 / Step 1 relax + dirty-`_workflow/**` refusal.** Drop the `git branch --show-current = develop` check and the `$ARGUMENTS = branch-name` validation. Replace with "active branch + clean tree + resolve active plan dir" preflight (the active plan dir is picked via today's skill ladder — enumerate `docs/adr/*/_workflow/`, apply zero/one/many, with `$ARGUMENTS` optionally pre-selecting). Add a refusal: enumerate `git status --porcelain "$PLAN_DIR/_workflow/"` against the resolved plan dir; if any tracked file shows a non-`?` status (modified, added, deleted, renamed, etc.) or any untracked file shows `??`, halt with the offending paths printed and instructions to commit, stash, or remove them. The `.migration-progress` sentinel is the only exception — it is allowed to be untracked or modified mid-session. Update Step 0's umbrella task labels to drop the "migration worktree" terminology and add a task for new Step 2.0 (bootstrap).

2. **Step 2 deletion + downstream renumber.** Remove the entire "Resolve migration worktree path" section. Renumber every later top-level step down by one: today's Step 3.0 → Step 2.0, today's Step 3 → Step 2, today's Step 4 → Step 3, today's Step 5 → Step 4, today's Step 6 → Step 5. Update every cross-reference in the prose to the shifted numbers. Sub-steps inside the per-commit loop (today's 5.1-5.6) will renumber to 4.1-4.7 under Track 4b's edits — leave them alone in this track.

3. **Step 2.0 add (unstamped-artifact bootstrap).** Insert a new step before the renumbered Step 2. Walk the active plan's `_workflow/**` ephemeral artifacts (the plan dir resolved in Step 1), classify each as stamped (line-1 stamp parses) or unstamped (no stamp); collect `STAMPED_SHAS` and `UNSTAMPED_FILES`. If `UNSTAMPED_FILES` is empty, no-op and continue to Step 2. Otherwise prompt the user once with the unstamped-file list and a one-paragraph explanation of why no auto-computed reference is safe (D8). Read a 40-character SHA; validate via `git rev-parse --verify "$SHA^{commit}"` and `git merge-base --is-ancestor "$SHA" HEAD` (the supplied SHA must be reachable from HEAD because HEAD is the comparison reference — D10). Re-prompt up to 3 times on validation failure; abort the session on 3 rejections (no edits applied). The validated SHA is stored as `$USER_BOOTSTRAP_SHA` for Step 2.

4. **Step 2 rewrite (was Step 3).** Replace the old commit-range bash with the stamp-walking variant from `conventions.md` §1.6(h). The fold input set is `STAMPED_SHAS` plus `$USER_BOOTSTRAP_SHA` when Step 2.0 ran. Output is `BASE_SHA` and the `git log` of commits in `$BASE_SHA..HEAD` against workflow paths (no `git fetch`). Cross-link to `conventions.md` §1.6(h) for the Phase 1 walk byte-source and §1.6 for the surrounding stamp-format prose. The Phase 2 fold and its failure-recovery semantics are governed by:
   - **`conventions.md` §1.6(c) — merge-base failure recovery.** Each pairwise `git merge-base` call may fail (a stamp pointing at a `git gc`-pruned commit, or two stamps with no reachable common ancestor in the local repo). The migration treats every stamp in the failing call's affected set as unstamped for the rest of the session and routes the full failed set back into Step 2.0's bootstrap prompt — one user prompt covers the combined "genuinely unstamped + merge-base-failed" set per §1.6(c)'s batch semantics. Step 2 records the merge-base-failed set, then either jumps back to Step 2.0 (when no prompt has fired yet) or extends the prompt's input set (when 2.0 already ran but more stamps failed mid-fold). Same 3-attempt validation cap as Step 2.0's initial prompt.
   - **`conventions.md` §1.6(h) final paragraph — both-arrays-empty.** When the Phase 1 walk produces both `STAMPED_SHAS` and `UNSTAMPED_FILES` empty (a freshly-created `_workflow/` directory holding only a transient `handoff-*.md`, for example), the migration halts with `no artifacts to migrate` and exits with no edits applied. This is distinct from the no-drift case for the drift check; the migration has nothing to replay against.

5. **Step 3 simplification (was Step 4).** The `.migration-progress` file stays in shape but its semantic role shifts to "in-flight session crash-recovery sentinel" — the durable progress marker becomes the per-artifact stamps once Track 4b lands. The progress file's header `head=<sha>` field becomes `range_end=<sha>` (= `git rev-parse HEAD` at session start); the `fork=<sha>` field becomes `range_start=<sha>` (= `BASE_SHA`). The renames block is unchanged. Update the file-existence handling: if `range_start` in the file doesn't match the freshly-computed `BASE_SHA`, halt and ask the user to delete the file (same as today's stale-fork check, just under the new field names).

Cross-cutting: every `<migration-worktree>` placeholder in the preflight / range / progress-file sections becomes either "the current worktree" or just disappears. Every cross-reference to running "from develop" is removed. The "two worktrees" mental model is gone — readers should never wonder which worktree owns which file. Per-commit-loop and final-summary scrubs land in Track 4b.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 4a lands:

- Running `/migrate-workflow` from a feature branch's worktree no longer halts on the develop-worktree check.
- The `$ARGUMENTS` parameter is optional; supplying it produces a no-op match check against `git branch --show-current`.
- The preflight refuses to start if any tracked file under `_workflow/**` has uncommitted changes (working tree or index), or if any untracked file under `_workflow/**` exists, except for the `.migration-progress` sentinel. The refusal prints the offending paths and exits with no edits applied.
- On a branch with unstamped artifacts, Step 2.0 prompts the user once, validates the input via `git merge-base --is-ancestor "$SHA" HEAD`, and stores it as `$USER_BOOTSTRAP_SHA`. Three rejected attempts abort the session with no edits applied.
- On a branch with all-stamped artifacts, Step 2.0 is a no-op (no prompt fires).
- Step 2 produces a commit list whose lower bound is `BASE_SHA` (the `git merge-base` fold over `STAMPED_SHAS` plus `$USER_BOOTSTRAP_SHA` when present) and upper bound is `HEAD` — no `git fetch origin develop` runs. On a fully-stamped branch where `git log $BASE_SHA..HEAD -- workflow paths` is empty, Step 2 halts with "Nothing to migrate" without entering the per-commit loop.
- When `git merge-base` fails mid-fold (a stamp pointing at a pruned commit, or two stamps with no reachable common ancestor), Step 2 collects the failing stamps' artifacts into the unstamped set and routes back through Step 2.0's bootstrap prompt covering the combined set. Three rejected attempts abort the session with no edits applied, matching the Step 2.0 initial-prompt contract.
- When the Phase 1 walk produces both `STAMPED_SHAS` and `UNSTAMPED_FILES` empty (no stampable artifacts on disk), Step 2 halts with `no artifacts to migrate` and exits with no edits applied — no prompt fires.
- The progress file uses the new field names (`range_start`/`range_end`); stale-file detection still fires when the recorded range start doesn't match the freshly-computed `BASE_SHA`.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Empty at Phase 1. -->

## Interfaces and Dependencies

**In-scope files:**
- `.claude/skills/migrate-workflow/SKILL.md` (Steps 0, 1, 2.0 add, 2, 3 in the post-renumber numbering — the preflight, the bootstrap prompt, the range derivation, and the progress-file rename)

**Out-of-scope files:**
- `.claude/workflow/conventions.md` (Track 1)
- `.claude/skills/create-plan/SKILL.md`, `.claude/skills/edit-design/SKILL.md` (Track 2)
- `.claude/workflow/workflow-drift-check.md` (Track 3 — shares the bash block by byte-identical copy)
- `.claude/skills/migrate-workflow/SKILL.md` Steps 4 (per-commit loop with sub-steps 4.1-4.8) and 5 (final summary) — Track 4b
- `.claude/workflow/self-improvement-reflection.md` and the new Step 6 wiring (Track 5)

**Inter-track dependencies:**
- **Depends on:** Track 1 (stamp format + SHA computation in `conventions.md` §1.6).
- **Coordinates with:** Track 3 (shared stamp-walking bash block — both files own a copy, must be byte-identical).
- **Unblocks:** Track 4b (the per-commit replay loop relies on the range Track 4a derives and the progress file Track 4a renames).

**External interfaces:**
- `git status --porcelain "$PLAN_DIR/_workflow/"` against the resolved active plan dir — the dirty-`_workflow/**` preflight check (Step 1).
- `git rev-parse --verify "$SHA^{commit}"` and `git merge-base --is-ancestor "$SHA" HEAD` — used by Step 2.0 to validate the user-supplied bootstrap SHA before it enters the fold. HEAD is the reachability anchor because the range upper bound is HEAD (D10).
- `git merge-base <a> <b>` — pairwise fold across stamps to find the oldest reachable ancestor (`BASE_SHA`). Handles linear and divergent histories uniformly.
- `git log $BASE_SHA..HEAD -- .claude/workflow .claude/skills` — the commit range for replay.
