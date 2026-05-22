# Track 4b: Migration replay + final batch

## Purpose / Big Picture
After this track lands, `/migrate-workflow`'s per-commit replay loop advances every stamp in the active plan's `_workflow/` to the just-replayed commit's SHA in lockstep, and a final post-loop batch re-stamps every artifact to `git rev-parse HEAD`. Workflow-modifying branches (this very plan's branch) accept dogfood semantics — the branch's own workflow commits register as drift and trigger migration of in-progress workflow changes; one migration session per commit cluster touching `.claude/workflow/` or `.claude/skills/`.

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

After Track 4a lands, `.claude/skills/migrate-workflow/SKILL.md` opens with the new preflight, the unstamped-artifact bootstrap prompt, the stamp-walking range derivation, and the renamed progress-file fields. Track 4b rewrites the per-commit replay loop (today's Step 5, renumbered to Step 4 by Track 4a's renumber-down) and the final summary (today's Step 6, renumbered to Step 5). The loop gains a new sub-step 4.5 *Advance stamps in lockstep* and a new sub-step 4.8 *Final stamp-to-HEAD batch*. The table covers the steps Track 4b touches:

| Old → New | Today's purpose | Track-4b fate |
|---|---|---|
| 5 → 4 | Per-commit replay loop (context check / classify / apply / record) | Keep the loop shape; insert a new sub-step 4.5 *Advance stamps in lockstep* between today's 5.4 (apply edits) and today's 5.5 (progress sentinel). Old 5.5 → 4.6, old 5.6 (task flip) → 4.7. The new 4.5 fires after edits and before the progress-sentinel update; the design.md sequence diagram is the authoritative ordering, and §"Per-commit replay and lockstep advance" prose matches (CR1 resolution). |
| (new) 4.8 | (new) Final stamp-to-HEAD batch | Add after the per-commit loop (Step 4) exits: re-stamp every artifact's line-1 SHA to `git rev-parse HEAD` in one batch, including artifacts the per-commit advance already updated — invariant I2 lands here (D2). Numbered 4.8 to pair with sub-steps 4.5/4.6/4.7 inside Step 4. |
| 6 → 5 | Final summary | Update to report final stamp SHA (= HEAD) + "commit your changes" reminder |

The renames tracker (`# renames:` header block in `.migration-progress`) stays. Renames are derived per-commit via `git show --diff-filter=R` and the tracker is rebuilt fresh each invocation — same shape as today.

## Plan of Work

The rewrite proceeds in roughly three edits, in order. Step numbers below are the post-Track-4a numbering.

1. **Step 4 sub-step inserts (was Step 5; sub-step 4.5 add + 5.5/5.6 renumber).** Inside the per-commit loop (renamed to Step 4 by Track 4a): keep today's sub-steps 5.1 (context check), 5.2 (read commit), 5.3 (classify), 5.4 (apply edits) under their new identifiers 4.1-4.4. Insert a new sub-step 4.5 *Advance stamps in lockstep* immediately after 4.4 (apply edits) and before today's 5.5 (progress sentinel). New 4.5 rewrites line 1 of every stamped-or-just-stamped artifact in the active `_workflow/` to `<!-- workflow-sha: <current-commit-sha> -->` via `sed -i` (or `Edit`). Document the ordering invariant in the sub-step prose: edits → advance → progress sentinel, never another order; this matches design.md's sequence diagram and §"Per-commit replay and lockstep advance" prose (updated under CR1 resolution to land after 4.4 and before 4.6). Today's 5.5 (progress sentinel) becomes 4.6; today's 5.6 (task flip) becomes 4.7. Previously unstamped artifacts get their first stamp written in 4.5 as a side effect. The 4.5 sub-step is the crash-resume marker — without it, a mid-loop crash leaves stamps behind.

2. **Sub-step 4.8 add (final stamp-to-HEAD batch).** After the per-commit loop (Step 4) exits, re-stamp every artifact's line-1 SHA to `git rev-parse HEAD` in one batch — even artifacts the loop already advanced to the same value. This is the invariant-landing step (I2). The reason both 4.5 and 4.8 exist: 4.5 preserves resume on mid-loop crash; 4.8 closes the gap when the last replayed commit's SHA != HEAD's SHA (e.g., HEAD has non-workflow commits past the last workflow commit in the range). 4.8 is numbered as a sub-step of Step 4 (not a separate top-level Step 5) because it pairs semantically with 4.5; the top-level Step 5 slot is taken by the final summary. Update Step 0's umbrella task list to add a task for the final stamp-to-HEAD batch.

3. **Step 5 final summary (was Step 6).** Add a line reporting "Workflow stamps now at `<HEAD-sha>`" before the existing per-classification counts. When Step 2.0 ran (Track 4a), also report "Bootstrapped N previously unstamped artifacts using SHA `<user-sha>`." Replace the closing instruction to point at "Review the diff in the current worktree, then commit + push when satisfied" — no more "<migration-worktree>" reference. Note that Track 5 will append a Step 6 (self-improvement reflection) AFTER this final summary.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes the step roster here. -->

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 4b lands:

- Inside Step 4's per-commit loop, sub-step 4.5 fires after sub-step 4.4 (apply edits) and before sub-step 4.6 (progress sentinel). After a successful per-commit replay of commit X, every artifact in the active `_workflow/` directory has `head -1 <artifact>` equal to `<!-- workflow-sha: X -->`, including previously unstamped artifacts that gain their first stamp during the 4.5 lockstep advance.
- After Step 4's per-commit loop exits successfully, sub-step 4.8 re-stamps every artifact's line-1 SHA to `git rev-parse HEAD`. Invariant I2 holds: every stamp equals HEAD's SHA at session end.
- A killed-mid-loop session resumed by re-invoking `/migrate-workflow` reads the latest stamp from any artifact, re-runs Step 2.0 (which is a no-op now that every artifact is stamped), recomputes the range, and picks up at the next commit.
- The renames tracker still drives path mapping for commits that follow rename-classified commits within the same migration.
- The final summary (Step 5) names HEAD's SHA and instructs the user to commit + push; when Step 2.0 ran, it also reports the bootstrap SHA and count of previously unstamped artifacts.
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
- `.claude/skills/migrate-workflow/SKILL.md` (Step 4 with sub-step 4.5 + sub-step 4.8 adds, Step 5 final summary — every step except the per-commit classification logic in Step 4.3 and the in-loop context check in Step 4.1)

**Out-of-scope files:**
- `.claude/skills/migrate-workflow/SKILL.md` Steps 0, 1, 2.0, 2, 3 — Track 4a
- `.claude/workflow/self-improvement-reflection.md` and the new Step 6 wiring (Track 5; the reflection step lands as Step 6 after the final summary)

**Inter-track dependencies:**
- **Depends on:** Track 4a (range derivation, progress-file rename, and renumber-down land first); transitively depends on Track 1.
- **Coordinates with:** Track 3 (shared stamp-walking bash block; Track 4a owns the migration-side copy, but the per-commit replay relies on the same range Track 3 reads).
- **Unblocks:** Track 5 (Track 5 appends Step 6 — the self-improvement reflection step — to this file after Track 4b lands, since the final summary is Step 5 post-renumber).

**External interfaces:**
- `git rev-parse HEAD` — the final-batch stamp value (sub-step 4.8).
- `sed -i "1s/.*/<!-- workflow-sha: <new-sha> -->/" <file>` — the lockstep advance writer (sub-step 4.5) and the final-batch writer (sub-step 4.8). Idempotent: re-running on an already-stamped file with the same SHA is a no-op.
