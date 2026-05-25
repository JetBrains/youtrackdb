# Handoff: Phase C — Track 3 between post-iter-3 gate-check PASS and Track Completion approval

**Paused:** 2026-05-25
**Phase:** C
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** 40ad98c240 "Record Track 3 Phase C iter-3 completion in Progress"
**Unpushed:** 0 commits (this handoff commit will follow)

## Durable artifacts on disk

- Phase B commits: `5f7f882830` (Step 1) / `656df1d6a8` (Step 2) / `e2373b771b` (Step 3) / `de908ccfa6` (Step 4).
- Phase C iter-1 Review fix: `fcd2e58d7c` (all 12 in-scope F-findings applied).
- Handoff resolution commit: `ac3d2dda14` (handoff-track-3-phaseC.md deleted; PAUSED marker removed).
- Phase C iter-2 Review fix: `ced57f481d` (CQ1 + WC9 + WC10 applied — structural citations in ClearRollback Javadoc + PSI-verified line ranges on track-3.md).
- Phase C iter-2 Progress entry: `d8d43b91d9`.
- Phase C iter-3 Review fix: `cbe2219fb8` (CQ2 + WC11 applied — residual `:2335` cite at MV ClearRollbackTest:142 + SV `persistCountDelta` 724–733 → 723–732).
- Phase C iter-3 Progress entry: `40ad98c240` (current HEAD).
- Track file `docs/adr/null-count-error/_workflow/plan/track-3.md` carries: 4 step episodes (Steps 1–4 in `## Episodes`); Progress entries for the 3 review iterations + the iter-3 Track-level code review PASS marker flip; Outcomes & Retrospective entries summarising Phase A reviews (Technical/Risk/Adversarial), iter-1 dimensional fan-out, iter-2 + iter-3 gate-check fan-outs, and the final PASS verdict.
- Iteration budget consumed: 3/3. Post-iter-3 gate-check on CQ + WC dimensions returned PASS with no remaining open findings and no new findings.
- Coverage gate on the cumulative branch diff at HEAD: 93.6% line / 87.5% branch (above the 85/70 thresholds; recorded during iter-1 fix application and unchanged by iter-2/iter-3 which were Markdown + Javadoc-only).

## Pending decision

User approval of Track 3 completion. The next session compiles the Track 3 episode from the step episodes + per-iteration FIX_NOTES + the deferred-finding clusters and presents three options via `AskUserQuestion`:

1. **Approve** — write the track episode to `implementation-plan.md`, collapse the description, mark `[x]` in the Checklist, commit as `Mark Track 3 complete`, push, run self-improvement reflection, end session.
2. **Review mode** — accumulate observations in a conversational loop; on Apply, FIX_FINDING items spawn a fresh implementer with `level=track`, `mode=FIX_REVIEW_FINDINGS` per `track-code-review.md` § Track Completion step 3.
3. **ESCALATE** — route to inline replanning per `inline-replanning.md`.

## Verbatim re-present text

### Track 3 episode (compiled draft — present to user verbatim)

> **Track 3 lands `clear()` pure-delta encoding on both engines.** `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` now call `clearSVTree`/`doClearTree` first to take the per-tree component lock transitively, snapshot the in-memory counters under that lock, record `Δ = -current` via the new `IndexCountDelta.accumulateClearOrRecalibrate(op, id, totalDelta, nullDelta)` long-form overload, and drop the four (MV) / three (SV) direct writes to the persisted EP pages and in-memory `AtomicLong`s. The accumulated delta routes through Track 2's Hook A (`persistCountDelta`, before `commitChanges`, with persist-failure-to-rollback conversion) and Hook B (`addToApproximateEntriesCount` apply, after `commitChanges`, under the per-engine lock acquired at `lockIndexes`). After this track, the clear-rollback divergence is structurally impossible for both the commit path and the `clearIndex` API path; the lock-window race that today's manual `applyIndexCountDeltas` at the post-`releaseLocks` call would expose is closed by Track 2's single-lifecycle gate.
>
> **Key discoveries surfaced for Track 4.** (1) `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation, int, long, long)` is added by Step 1 and reusable verbatim; Track 4 Phase A must skip the preflight-add branch (the overload exists post-`5f7f882830`). (2) The runtime assert `|nullDelta| <= |totalDelta|` with sign-alignment holds structurally for clear (since `currentNull <= currentTotal`) but may fire on Track 4's recalibration deltas where opposed-direction drift between `approximateIndexEntriesCount` and `approximateNullCount` (from Track-1 `reportAndClampUnderflow` events) violates the magnitude check under `-ea` — flagged in Decision Log Q3 + Surprises row 2 as the candidate relaxation for Track 4 Phase A. (3) The drift-amplification accepted regression (Q2): the new `clear()` writes `addToApproximate{Entries,Null}Count(-currentInMem)` to the persisted EP page via Hook A without clamping, so if pre-existing in-memory > persisted drift exists from a Track-1 underflow-clamp event the persisted EP can land below zero under `-ea` off. Today's `setApproximateEntriesCount(0)` hard-reset absorbed any drift to zero; the new pure-delta encoding does not. Long-term self-heal is `buildInitialHistogram` recalibration on next touch (Track 4); accepted because eagerly normalising the persisted side inside `clear()` would re-introduce the in-atomic-op write this track removes. (4) Reusable rollback-injection recipe for Track 4: `Mockito.mock(IndexHistogramManager.class)` + `doThrow(IOException.class).when(stub).resetOnClear(...)` + reflective field-swap installs the stub without subclassing the multi-arg constructor; the throw routes through `engine.clear()`'s catch wrap (MV inline; SV method-level), wraps as `IndexException`, escapes the `executeInsideAtomicOperation` lambda, and triggers rollback under Hook B's `currentError == null` skip-apply gate. Track 4's `buildInitialHistogram` rollback test reuses this seam unchanged. (5) The shared `CLEAR_CONCURRENCY_CONTRACT_NOTE` constant naming pattern (Javadoc `{@link}` + `assertNotNull` runtime anchor from each test acknowledging the Mockito short-circuit) carries forward to any Mockito-fixture limitation in Track 4 that needs load-bearing acknowledgement in test prose. (6) The MV `(-currentNonNull to svTree, -currentNull to nullTree)` two-tree split shape applies to recalibration deltas; the one-tree-empty subcase (F11) names a contract Track 4 must preserve on its own recalibration path — the snapshot of engine-level `AtomicLong`s can capture a concurrent `put` against the empty side; self-healed by `buildInitialHistogram` recalibration on the next touch.
>
> **Plan deviations.** Decision Log Q5 promoted from Step 4 (deferred multi-thread real-tree contention test against `ReentrantReadWriteLock`). The three commit-path + API-path regression tests cover the mechanical contract enumerated in Plan-of-Work Step 4; the lock-window race on the `clearIndex` API path stays uncovered by automated tests but is named in production via the `CLEAR_CONCURRENCY_CONTRACT_NOTE` constant from Step 3, propagated to MV tests at iter-1 via finding F8.
>
> **Phase C review used 3 of 3 iterations.** Iter-1 dimensional fan-out applied 12 findings (F1–F12) in commit `fcd2e58d7c` covering rollback-test Javadoc tightening, MV pure-delta + lock-window comment tightening, MV snapshot-invariant + persistCountDelta-split + `verify(never())` test additions, `SV_CLEAR_CONCURRENCY_CONTRACT_NOTE` → `CLEAR_CONCURRENCY_CONTRACT_NOTE` IDE rename across 14 sites, the volatile-field reliance Javadoc on `swapHistogramManager`, plus 8 stale-citation refreshes on track-3.md and em-dash discipline tightening. Iter-2 post-iter-1 gate-check (9 dimensions) cleared 11 F-findings and surfaced CQ1 (residual `:2335` literal cite in the in-method ClearRollback Javadoc — same anti-pattern as F3 at a different location) + WC9 + WC10 (the F4 commit itself shifted MV `persistCountDelta` and snapshot-maps line numbers downstream; the F4 reconciliation pass missed the new offsets) — applied in commit `ced57f481d`. Iter-3 post-iter-2 gate-check (CQ + WC) cleared CQ1 / WC9 / WC10 and surfaced CQ2 (residual `:2335` cite at the MV ClearRollbackTest in-method body comment line 142; SV counterpart at line 149 was already clean — MV/SV asymmetry the iter-2 fix didn't catch) + WC11 (SV `persistCountDelta` cite drift `724–733` → PSI-verified `723–732`) — applied in commit `cbe2219fb8`. Post-iter-3 gate-check on CQ + WC returned PASS on CQ2 + WC11 with no new findings; all gate-check verdicts VERIFIED.
>
> **Deferred follow-up clusters (deferred from iter-1's dimensional fan-out as out-of-scope for Track 3).** These are surfaced for user disposition at Track Completion approval — they do NOT add new tracks to the plan (the four-track plan stays as-is), and they are not blocking Track 3 completion:
>
> - **Multi-thread real-tree contention test (TX1 / TY3 cluster — Decision Log Q5).** No automated test exercises real `ReentrantReadWriteLock` contention against the lock-window claim that `clear()`'s snapshot reads under `clearSVTree`'s transitive per-tree lock are race-free against a concurrent commit's Hook B apply on the same engine. Q5 user-decision: file a follow-up YouTrack issue under YTDB with the `dev-workflow` tag that covers both engines and both `clear()` + `buildInitialHistogram()` seams together (single ticket, not split per engine or per seam) so Track 4's `buildInitialHistogram` rewrite inherits the same automation gap closure path. Candidate Track 4 Phase A work, not Track 3 scope.
> - **Drift-amplification accepted regression (TY3 / CS1 / CS2 cluster — Decision Log Q2).** Accepted by design; documented in production via the comment on `BTreeMultiValueIndexEngine.clear()`. No issue needed unless follow-up work surfaces (clamp at `BTree.addToApproximateEntriesCount` write site; load-time normalisation in `load()`).
> - **Test-behavior / test-crash-safety / test-completeness / bugs-concurrency / test-structure deferred items (TB2 / TY2 / TC3 / TC5 / BC4 / TS3 / CQ1 from iter-1's full synthesis).** The iter-1 synthesised list classified these as out-of-scope for the in-iteration fix (the 12 F-findings exhausted the budget); they did not re-surface in iter-2's gate-check fan-out and remain uncovered. Track 1 and Track 2 episodes carry similar deferred clusters in the same pattern. No concrete YouTrack issue is filed by default; the user may choose to file one per cluster at approval time.
>
> **Track file:** `plan/track-3.md` (4 steps, 0 failed)

### Three-option AskUserQuestion panel (use verbatim at resume)

Question header: `Track 3 result`

Question text: `Track 3 (clear() pure-delta encoding) completed all four steps + 3 Phase C iterations with the cumulative diff verified clean on every dimension that produced findings. How would you like to proceed?`

Options:
1. **Approve — write episode and mark complete.** Write the compiled track episode (above) into `implementation-plan.md`'s Track 3 Checklist entry, collapse the description per `track-code-review.md` § Track Completion step 4, mark `[x]`, commit as `Mark Track 3 complete`, push, run self-improvement reflection, end session.
2. **Review mode — accumulate observations first.** Enter the conversational refinement loop per `review-mode.md` § Flow. Apply may spawn a fresh `level=track`, `mode=FIX_REVIEW_FINDINGS` implementer for any FIX_FINDING items the user adds.
3. **ESCALATE — inline replan.** Route to inline replanning per `inline-replanning.md`.

## Resume notes

- **Do NOT redo (per Phase C row of `mid-phase-handoff.md` §"Phase-specific do NOT redo defaults"):**
  - Iteration count is already on disk (`Track-level code review iteration 1/2/3 complete` plus `Track-level code review` PASS marker in the Progress section). Do NOT re-spawn any dimensional reviewer or gate-check sub-agent — every dimension's verdict is on disk in the track file's Outcomes & Retrospective entries dated 2026-05-25T08:43Z / 09:20Z / 09:29Z / 09:34Z.
  - All 18 in-scope findings (F1–F12 + CQ1 iter-2 + WC9 + WC10 + CQ2 iter-3 + WC11 + the F4 parent close) already applied in commits `fcd2e58d7c` / `ced57f481d` / `cbe2219fb8`. Do NOT re-attempt any of them.
  - Plan corrections from deferred findings: the deferred clusters listed in the compiled episode under "Deferred follow-up clusters" are NOT routed through `track-code-review.md` § Plan Corrections from Deferred Findings (which adds new tracks to the plan); they are routed through the user-facing Track Completion approval panel where the user decides which deserve concrete YouTrack issues. The four-track plan stays as-is — no new tracks for Track 3 deferred items.

- **Pre-approval work for the next session (before re-presenting the panel):**
  - Re-stage cumulative diff + slim plan snapshot if `$PPID` changed:
    ```bash
    git diff c2e99ebd3a90bbbe0a906ec940db7e5416eb231c..HEAD \
        > /tmp/claude-code-track-3-diff-$PPID.patch
    git diff c2e99ebd3a90bbbe0a906ec940db7e5416eb231c..HEAD --name-only \
        > /tmp/claude-code-track-3-files-$PPID.txt
    python3 .claude/scripts/render-slim-plan.py \
        --plan-path docs/adr/null-count-error/_workflow/implementation-plan.md \
        --out /tmp/claude-code-plan-slim-$PPID.md
    ```
  - Re-read `git diff c2e99ebd3a90bbbe0a906ec940db7e5416eb231c..HEAD` against current HEAD to confirm no `Review fix:` commits landed between this pause and resume (the orchestrator never edits source files itself in Phase C, so the only way new commits could land is a different agent's work — unlikely but worth checking per `track-code-review.md` § Track Completion step 1's re-compile entry-point caveat).

- **On user Approve at the panel:**
  1. Read `implementation-plan.md` to locate the Track 3 Checklist entry.
  2. Replace the existing Track 3 description (intro paragraph + `**Scope:**` + `**Depends on:**`) with: intro paragraph (first paragraph of the current description, kept verbatim) → blank line → `**Track episode:**` block containing the compiled summary (verbatim from `## Verbatim re-present text` above) → blank line → `**Track file:** plan/track-3.md (4 steps, 0 failed)`. Drop the `**Scope:**` line. Drop the `**Depends on:**` line. The next session's Track Pre-Flight gate (Track 4) appends the `**Strategy refresh:**` line when Panel 1 clears.
  3. Flip the Track 3 Checklist marker from `[ ]` to `[x]`.
  4. Append `- [x] <ISO> [ctx=<level>] Track completion` to the track file's `## Progress` section (flipping the pre-seeded marker text in place, matching the `- [x] ... Step implementation` and `- [x] ... Track-level code review` flips already on disk).
  5. Append a final `## Outcomes & Retrospective` entry: `- [x] <ISO> [ctx=<level>] Track complete: 4 steps, 0 failed, 3 Phase C iterations (all VERIFIED at post-iter-3 gate-check).`.
  6. Commit + push the plan-file edit and track-file Progress + Outcomes edits together as a single Workflow update commit: `Mark Track 3 complete`. Stage explicit paths (`docs/adr/null-count-error/_workflow/implementation-plan.md` + `docs/adr/null-count-error/_workflow/plan/track-3.md`); do NOT `git add -A`.
  7. Remove the staged temp files for Track 3:
     ```bash
     rm -f /tmp/claude-code-track-3-diff-$PPID.patch /tmp/claude-code-track-3-files-$PPID.txt
     ```
  8. Run self-improvement reflection per `self-improvement-reflection.md`. Friction worth recording: the iter-2 implementer's MV/SV asymmetry miss on CQ2 (SV counterpart cleaned at line 149 but MV in-method comment at line 142 missed); the F4 reconciliation that shifted lines downstream without re-PSI-verifying the new offsets (WC9 + WC10); the line-citation drift pattern across iterations (F4 → WC9/WC10/WC11 → cross-track signal recorded in iter-3's CROSS_TRACK_HINTS recommending structural citations from the outset on Track 4 Phase A); the gate-check verdict ID format mismatch (handoff resolved by passing F-prefixed IDs verbatim per `dimensional-review-gate-check.md` § Inputs).
  9. Session ends; next session's Track Pre-Flight gate runs Panel 1 (strategy assessment) against this track's episode.

- **On Review mode:** enter `review-mode.md` § Flow. The user adds observations; on Apply, FIX_FINDING items collect into a synthesised findings list and a fresh `level=track`, `mode=FIX_REVIEW_FINDINGS` implementer is spawned per `track-code-review.md` § Track Completion step 3. The 3-iteration cap does NOT apply to Completion FIX_FINDING per `review-mode.md` § Completion FIX_FINDING outcome mapping; the user-in-the-loop check at each Apply is the natural rate limit.

- **Cross-track impact captured by iter-1 + iter-2 + iter-3 implementers (forward to Track 4):**
  - The renamed engine-agnostic `CLEAR_CONCURRENCY_CONTRACT_NOTE` constant is reusable verbatim for any Mockito-fixture short-circuit acknowledgement in Track 4's recalibration tests.
  - The `(-currentNonNull to svTree, -currentNull to nullTree)` two-tree split is the MV-side persisted-write shape for any negative delta on the multi-value engine, including recalibration deltas.
  - The MV one-tree-empty subcase (F11) names a contract Track 4 must preserve on its own recalibration path: snapshot of engine-level AtomicLongs can capture a concurrent put against the empty side; self-healed by `buildInitialHistogram` recalibration on next touch.
  - The `accumulateClearOrRecalibrate` runtime assert `|nullDelta| <= |totalDelta|` with sign-alignment may need relaxation for recalibration deltas with opposed-direction drift between in-memory non-null and null counts (carry-forward from Q3).
  - Line-number citations to production source in workflow Markdown remain a recurring source of stale-citation findings (F3 → F4 → WC9 → WC10 → WC11 on this track alone). For Track 4 Phase A, prefer structural citations from the outset (method name + structural locator resolvable via PSI/find-usages) over numeric line ranges (iter-3 implementer CROSS_TRACK_HINTS).

## Iteration counter accounting

| Iteration | Status | Counter consumed |
|---|---|---|
| 1 | SUCCESS — 12 findings applied in commit `fcd2e58d7c` | 1 |
| 2 | SUCCESS — 3 findings (CQ1 + WC9 + WC10) applied in commit `ced57f481d` | 2 |
| 3 | SUCCESS — 2 findings (CQ2 + WC11) applied in commit `cbe2219fb8` | 3 |

Remaining iteration budget: 0. Post-iter-3 gate-check on CQ + WC dimensions returned PASS with no new findings. Track-level code review loop closed.
