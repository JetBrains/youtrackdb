# Track 4b: Migration replay + final batch

## Purpose / Big Picture
After this track lands, `/migrate-workflow`'s per-commit replay loop advances every stamp in the active plan's `_workflow/` to the just-replayed commit's SHA in lockstep, and a final post-loop batch re-stamps every artifact to `git rev-parse HEAD`. Workflow-modifying branches (this very plan's branch) accept dogfood semantics — the branch's own workflow commits register as drift and trigger migration of in-progress workflow changes; one migration session per commit cluster touching `.claude/workflow/` or `.claude/skills/`.

The dogfood claim is transient. Once Track 7 (staging architecture, D14) lands and a workflow-modifying branch adopts the staged-workflow convention, that branch routes its workflow document edits to `<plan-dir>/_workflow/staged-workflow/.claude/{workflow,skills}/...` and the drift gate's path-scoped `git log -- .claude/workflow .claude/skills` returns zero in-branch commits. After Track 7, dogfood applies only to plans that pre-date the staging convention or that hand-edit live paths.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-24T10:55Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Empty at Phase 1. -->

## Decision Log
<!-- Continuous-log. Empty at Phase 1. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
- [x] Technical: PASS at iteration 2 (8 findings, 7 accepted + 1 dropped — T7 WP1 line 506 example `/migrate-workflow <branch>` already-handled by Track 4a's optional-`$ARGUMENTS` preflight)
- [x] Adversarial: PASS at iteration 2 (9 findings inc. iter-2 A9 Plan-of-Work prose residue, 7 accepted + 2 skipped — A3 / A8 D2 lockstep-advance relitigation settled at Phase 2 plan review)

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

1. **Step 4 sub-step inserts (was Step 5; sub-step 4.5 add + 5.5/5.6 renumber).** Inside the per-commit loop (renamed to Step 4 by Track 4a): keep today's sub-steps 5.1 (context check), 5.2 (read commit), 5.3 (classify), 5.4 (apply edits) under their new identifiers 4.1-4.4. Insert a new sub-step 4.5 *Advance stamps in lockstep* immediately after 4.4 (apply edits) and before today's 5.5 (progress sentinel). New 4.5 rewrites line 1 of every stamped-or-just-stamped artifact in the active `_workflow/` to `<!-- workflow-sha: <current-commit-sha> -->` via `Edit` against line 1 with the prior stamp as `old_string` (per the External Interfaces clause; portable across platforms, idempotent on equal SHA). Document the ordering invariant in the sub-step prose: edits → advance → progress sentinel, never another order; this matches design.md's sequence diagram and §"Per-commit replay and lockstep advance" prose (updated under CR1 resolution to land after 4.4 and before 4.6). Today's 5.5 (progress sentinel) becomes 4.6; today's 5.6 (task flip) becomes 4.7. Previously unstamped artifacts get their first stamp written in 4.5 as a side effect. The 4.5 sub-step is the crash-resume marker — without it, a mid-loop crash leaves stamps behind.

2. **Sub-step 4.8 add (final stamp-to-HEAD batch).** After the per-commit loop (Step 4) exits, re-stamp every artifact's line-1 SHA to `git rev-parse HEAD` in one batch — even artifacts the loop already advanced to the same value. This is the invariant-landing step (I2). The reason both 4.5 and 4.8 exist: 4.5 preserves resume on mid-loop crash; 4.8 closes the gap when the last replayed commit's SHA != HEAD's SHA (e.g., HEAD has non-workflow commits past the last workflow commit in the range). 4.8 is numbered as a sub-step of Step 4 (not a separate top-level Step 5) because it pairs semantically with 4.5; the top-level Step 5 slot is taken by the final summary. Update Step 0's umbrella task list to add a task for the final stamp-to-HEAD batch.

3. **Step 5 final summary (was Step 6).** Add a line reporting "Workflow stamps now at `<HEAD-sha>`" before the existing per-classification counts. When Step 2.0 ran (Track 4a), also report "Bootstrapped N previously unstamped artifacts using SHA `<user-sha>`." Replace the closing instruction to point at "Review the diff in the current worktree, then commit + push when satisfied" — no more "<migration-worktree>" reference. Note that Track 5 will append a Step 6 (self-improvement reflection) AFTER this final summary.

## Concrete Steps

1. Step 4 body rewrite in `migrate-workflow/SKILL.md` — insert sub-step 4.5 *Advance stamps in lockstep* between today's 5.4 (apply edits) and today's 5.5 (which slides to 4.6); rename sub-step H3 headers `### 5.1`–`### 5.6` to `### 4.1`–`### 4.7` (with new 4.5 taking the lockstep-advance slot and old 5.5 / 5.6 sliding to 4.6 / 4.7); sweep every in-prose `5.x` cross-reference at SKILL.md lines 463, 476, 477, 492, 542, 546, 547, 553, 580, 584-585, 595, 601 to the new numbering; drop the remaining `<migration-worktree>` placeholders at SKILL.md lines 511, 514, 557, 580, 601 (rewrite each to in-branch wording); update the Step 4.1 context-check halt message at line 506 to warn about the new 4.4→4.5 crash window ("If you `/clear` between 4.4 and 4.5, the next session re-replays this commit's edits over already-edited content; run `git diff` before re-invoking"). New 4.5 prose pins the ordering invariant (edits → advance → progress sentinel, never another order), names the `Edit`-against-line-1 writer with the prior stamp as `old_string`, documents the previously-unstamped-artifact side effect, and cross-references design.md's sequence diagram in §"Per-commit replay and lockstep advance" — risk: medium (new observable behavior added to the migrate-workflow skill's per-commit loop + resume-contract crash-window update; multi-site coverage across ~13 cross-reference sites and 5 placeholder sites within one file)  [ ]

2. Sub-step 4.8 add in `migrate-workflow/SKILL.md` — insert `### 4.8 Final stamp-to-HEAD batch` after the per-commit loop (Step 4) exits but before Step 5's final summary: re-stamp every artifact present on disk under §1.6(h)'s walk to `git rev-parse HEAD` via `Edit` against line 1, including artifacts the per-commit advance already updated (idempotent on equal SHA); document that this lands invariant I2; note that optional artifacts (e.g., `design-mechanics.md`) are silently skipped by §1.6(h)'s `ls 2>/dev/null` shape. Update Step 0's umbrella task list to insert "Final stamp-to-HEAD batch" between today's "Per-commit migration loop" task and today's "Final summary" task (shifting the final-summary task one slot down) — risk: low (additive: one new sub-step appended to Step 4 + one inserted line in Step 0's task list; no change to existing sub-step semantics)  [ ]

3. Step 5 final summary rewrite in `migrate-workflow/SKILL.md` — drop the two `<migration-worktree>` references at lines 613 and 614 in favour of in-branch worktree wording ("Review the diff in this worktree, then commit + push when satisfied. Delete `.migration-progress` after the commit."); add a new "Workflow stamps now at `<HEAD-sha>`" line above the existing per-classification counts (sourced from the sub-step 4.8 batch); add a conditional one-line bootstrap-count report ("Bootstrapped N previously unstamped artifacts using SHA `<user-bootstrap-sha>`") that fires only when Step 2.0's `$USER_BOOTSTRAP_SHA` is set — risk: low (single-section prose rewrite; placeholder removal + two reporting lines; no semantic shift beyond what Steps 1-2 already deliver)  [ ]

4. design.md crash-window prose hygiene via `/edit-design` content-edit mutation — correct §"Per-commit replay and lockstep advance" around line 289 which currently mis-routes the 4.5→4.6 resume contract through "the no-drift normalization path advances every stamp to HEAD's SHA". The drift-check-side normalization (Track 3, in `workflow-drift-check.md`) and the migration-side resume (Track 4b's per-commit loop) are distinct paths; the migration's re-invocation does not enter no-drift normalization. Replacement prose names the migration-side resume contract: re-fold from non-uniform stamps gives `BASE_SHA` at the lowest, range computation re-queues the not-yet-stamped commit, Step 4 re-applies its edits idempotently for file-shape edits, the user's pre-resume `git diff` catches non-idempotent diffs. One mutation through the established edit-design discipline; `design-mutations.md` records the change — risk: low (single-paragraph documentation hygiene via established edit-design discipline; no semantic shift to the design — just clarifying which resume path the contract follows)  [ ]

## Episodes
<!-- Continuous-log. Empty at Phase 1. -->

## Validation and Acceptance

After Track 4b lands:

- Inside Step 4's per-commit loop, sub-step 4.5 fires after sub-step 4.4 (apply edits) and before sub-step 4.6 (progress sentinel). After a successful per-commit replay of commit X, every artifact in the active `_workflow/` directory has `head -1 <artifact>` equal to `<!-- workflow-sha: X -->`, including previously unstamped artifacts that gain their first stamp during the 4.5 lockstep advance.
- After Step 4's per-commit loop exits successfully, sub-step 4.8 re-stamps every artifact present on disk under §1.6(h)'s walk to `git rev-parse HEAD` via `Edit` against line 1. Optional artifacts (e.g., `design-mechanics.md` on branches that never crossed the length-trigger) are silently skipped by §1.6(h)'s `ls 2>/dev/null` shape. Invariant I2 holds for the set of artifacts on disk at sub-step 4.8 completion: every such artifact's line-1 SHA equals HEAD's SHA at session end.
- Sub-step 4.5's `Edit` writer assumes the stamp format does not change inside the replay range (A4 from Phase A adversarial review). A hypothetical future workflow-format commit that itself changes the stamp format (HTML-comment to YAML frontmatter, etc.) would require an out-of-band migration; the in-place migration cannot self-bootstrap a stamp-format change because the writer's `old_string`-match contract assumes the prior format's text shape and would clobber the new format with the old one. Risk accepted; no codebase precedent for the stamp format changing post-D7.
- A killed-mid-loop session resumed by re-invoking `/migrate-workflow` reads the latest stamp from any artifact, re-runs Step 2.0 (which is a no-op now that every artifact is stamped), recomputes the range, and picks up at the next commit.
- The renames tracker still drives path mapping for commits that follow rename-classified commits within the same migration.
- The final summary (Step 5) names HEAD's SHA and instructs the user to commit + push; when Step 2.0 ran, it also reports the bootstrap SHA and count of previously unstamped artifacts.
- On a workflow-modifying branch, the migration replays the branch's own workflow commits onto its own `_workflow/**` artifacts. One migration session per commit cluster touching `.claude/workflow/` or `.claude/skills/` (dogfood).
- No reference to `<migration-worktree>` survives in the file.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

- **Step 1 (Step 4 body rewrite + 4.5 add).** Each in-prose edit is idempotent under `Edit`'s exact-string match — re-running on already-edited content fails the `old_string` precondition. A partial-failure mid-step leaves the file partially rewritten; the re-spawn surface for the failed edit is the same site (the `old_string` either still matches or already drifted). The lockstep-advance writer (new 4.5) uses `Edit` against line 1 with the prior stamp as `old_string`, so re-running on an already-advanced file is a no-op (the prior stamp is gone). The 4.1 halt message update is a single-site `Edit` with no recovery surface beyond the standard retry.
- **Step 2 (sub-step 4.8 add).** 4.8's final-batch writer is idempotent — re-running on stamps already at `git rev-parse HEAD` is a no-op (the `old_string` match for the new SHA equals the new SHA). A killed-mid-4.8 session resumes by re-invoking `/migrate-workflow`: Step 2.0's stamp walk shows mixed stamps (some at HEAD, some at the last per-commit-advance SHA), the fold collapses to the lowest, and Step 4's per-commit loop re-runs from the next-after-stamp commit — or finds an empty range if all commits have been replayed, in which case 4.8 fires fresh and lands I2.
- **Step 3 (Step 5 final summary rewrite).** Text-only; `Edit` against the existing summary block. Idempotent on `old_string`-match failure (re-run on already-rewritten content fails fast). No persistent state.
- **Step 4 (design.md prose hygiene via edit-design).** The `/edit-design` mutation is logged in `design-mutations.md` as a content-edit. Re-running on an already-corrected paragraph triggers the idempotency-guard in `edit-design`'s mutation logic — the target text is absent from the document, so the mutation no-ops (and the mutations log records the skip).

**Per-commit-loop partial-failure recovery (T4 from Phase A technical review).** Sub-step 4.5's `Edit`-against-line-1 loop iterates artifacts sequentially. If `Edit` on artifact N of M fails mid-loop (orchestrator-side error, file lock, permission), artifacts 1..N-1 carry the just-replayed commit's SHA and artifacts N..M still carry the prior commit's SHA. The next migration session's Step 2.0 walk produces non-uniform stamps; the fold collapses to the lowest (the prior commit's SHA) and Step 4's per-commit replay re-includes the just-replayed commit. Sub-step 4.4's `Edit` operations re-run over already-edited file content — idempotent for file-shape edits, caught by the existing `format`-classification halt-on-ambiguity for non-idempotent format-change patterns, and surfaced by the user's pre-resume `git diff` for everything else.

**HEAD race between Step 2 and sub-step 4.8 (A5 from Phase A adversarial review).** Step 2 captures `range_end = git rev-parse HEAD` at skill start; sub-step 4.8 re-reads `git rev-parse HEAD` fresh after the loop exits. If the user (or another concurrent agent on this branch per the user-global concurrent-agent isolation rule) commits between those reads, sub-step 4.8 stamps to the new HEAD while the progress file's `range_end` still names the old one. The mismatch is benign for I2 (which snapshots at 4.8 completion against the fresh HEAD), but the progress file's `range_end` row becomes stale. The migration's pending working-tree changes commit on top of the user's intervening commit; the next session's drift gate uses the new stamp set and computes a fresh range. The user's recovery if they prefer a clean slate: delete `.migration-progress` before re-invoking `/migrate-workflow`.

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
- `Edit` against line 1 of each artifact — the lockstep advance writer (sub-step 4.5) and the final-batch writer (sub-step 4.8). Replaces the existing `<!-- workflow-sha: <old-sha> -->` text with `<!-- workflow-sha: <new-sha> -->` via exact-string match on the prior stamp. Matches the rest of the migrate-workflow skill's writer pattern (today's 5.4 / future 4.4 also uses `Edit`). Portable across platforms — no `sed -i` BSD/GNU dialect difference. Idempotent: re-running on an already-stamped file with the same SHA is a no-op.

## Base commit
9fe505ef29c327aee02d03f32414156b7550f6e0
