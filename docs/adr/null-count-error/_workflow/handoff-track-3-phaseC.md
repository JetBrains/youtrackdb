# Handoff: Phase C — Track 3 between iter-1 success and iter-2 gate-check

**Paused:** 2026-05-25
**Phase:** C
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** fcd2e58d7c "YTDB-958: Review fix: tighten Track 3 rollback tests and prose"
**Unpushed:** 0 commits (HEAD is on origin; this handoff commit will follow)

## Durable artifacts on disk

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` — F2 (pure-delta comment tightened), F11 (one-tree-empty subcase added to lock-window comment) applied at HEAD `fcd2e58d7c`.
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeEngineHistogramBuildTest.java` — F5 (MV snapshot-invariant negative-path test), F6 (MV addToApproximateEntriesCount(never()) verifications on 5 tests), F7 (MV persistCountDelta split test), F8 (`SV_CLEAR_CONCURRENCY_CONTRACT_NOTE` → `CLEAR_CONCURRENCY_CONTRACT_NOTE`, propagated to MV tests via IDE rename of 14 sites) applied.
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearRollbackTest.java` — F1 (Javadoc reframed to actual weaker contract) applied.
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngineClearRollbackTest.java` — F1 applied.
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/ClearIndexApiRollbackTest.java` — F3 (stale line citations → structural descriptions), F12 (volatile-field reliance Javadoc on swapHistogramManager) applied.
- `docs/adr/null-count-error/_workflow/plan/track-3.md` — F4 (8 line citations refreshed), F9 (multi-thread concurrency gap duplication trimmed), F10 (em-dash discipline) applied.
- Coverage gate on cumulative branch diff: 93.6% line / 87.5% branch (above 85%/70% thresholds). All 83 targeted tests pass (`BTreeEngineHistogramBuildTest`, `IndexCountDeltaHolderTest`, `BTreeMultiValueIndexEngineClearRollbackTest`, `BTreeSingleValueIndexEngineClearRollbackTest`, `ClearIndexApiRollbackTest`).

## Pending decision

None for the user. The next session resumes the autonomous Phase C iteration loop at iteration 2 (gate-check fan-out). The user clears the session and re-runs `/execute-tracks`; the orchestrator picks up from this handoff.

## Verbatim re-present text

The iteration 1 implementer applied all 12 in-scope findings (F1–F12) in a single `Review fix:` commit. The dimensions that produced findings in iteration 1 are:

- **Code quality (CQ)** — F1, F2, F3, F5, F6, F7, F8 (CQ3 cluster), F11 implicit (BC1 + CQ comments overlap)
- **Bugs & concurrency (BC)** — F11 (BC1), F8 (BC2)
- **Test behavior (TB)** — F1 (TB1), F5 (TB3), F7 (TB4)
- **Test completeness (TC)** — F5 (TC1), F6 (TC2), F8 (TC4)
- **Test crash safety (TY)** — F1 (TY1)
- **Test concurrency (TX)** — F1 (TX3), F12 (TX2)
- **Test structure (TS)** — only minor findings, all deferred
- **Performance (PF)** — no findings raised
- **Crash safety (CS)** — only Q2-accepted regression noted (CS1/CS2 cluster, deferred), F11 inspired by CS3 informational
- **Workflow consistency (WC)** — F4 (WC1–WC8)
- **Workflow context budget (WB)** — F9 (WB1, WB2)
- **Workflow writing style (WS)** — F10 (WS1, WS2, WS3)

Iteration 2's gate-check fan-out re-runs **only the dimensions with open findings carried into the gate-check**. Since iteration 1 closed all 12 findings (no STILL OPEN carry-overs by intent), the gate-check fires on every dimension that produced findings in iter-1 to verify the fixes landed and check for regressions:

**Gate-check fan-out for iter-2 (target dimensions, 9 sub-agents):**
- `review-code-quality` (CQ — verify F1, F2, F3, F5, F6, F7, F8 landed cleanly)
- `review-bugs-concurrency` (BC — verify F11, F8)
- `review-test-behavior` (TB — verify F1, F5, F7)
- `review-test-completeness` (TC — verify F5, F6, F8)
- `review-test-crash-safety` (TY — verify F1)
- `review-test-concurrency` (TX — verify F1, F12)
- `review-workflow-consistency` (WC — verify F4)
- `review-workflow-context-budget` (WB — verify F9)
- `review-workflow-writing-style` (WS — verify F10)

**Skip in gate-check** (no findings from iter-1 to verify):
- `review-performance` (PF — no findings raised; skip)
- `review-test-structure` (TS — only minor findings, all deferred; skip)
- `review-crash-safety` (CS — only Q2-accepted regression noted, deferred via follow-up; F11 inspired by informational CS3 but no CS finding was applied — skip)

Per `review-iteration.md` § Gate-check budget, each gate-check sub-agent gets the compact prompt template at `.claude/workflow/prompts/dimensional-review-gate-check.md` (≤60-line budget, verdict-only output), not the full dimensional-review prompt.

**Iteration 2 implementer spawn (only if STILL OPEN / REGRESSION verdicts):** spawn a fresh per-iteration implementer with `level=track`, `mode=FIX_REVIEW_FINDINGS`, `base_commit=c2e99ebd3a90bbbe0a906ec940db7e5416eb231c`, and the carried-forward findings list per `track-code-review.md` § Implementer Spawns.

**Iteration 2 fan-out fully VERIFIED:** proceed to track completion (§Track Completion in `track-code-review.md`) — compile track episode, present to user via three-option AskUserQuestion (Approve / Review mode / ESCALATE), on approve collapse description and mark `[x]`.

## Resume notes

- **Do NOT redo (per Phase C row of `mid-phase-handoff.md` §"Phase-specific do NOT redo defaults"):**
  - Iteration count already on disk (Progress section's "Track-level code review iteration 1 complete (1/3 iterations)" line).
  - The 12 iter-1 findings already applied in commit `fcd2e58d7c`. Do NOT re-spawn the full dimensional review fan-out; only the compact gate-check fan-out on the dimensions listed above.
  - Plan corrections not yet committed (deferred-finding follow-up issues for TY3/CS1/CS2 cluster, TB2/TY2, TC3, TC5/BC4, TS3/CQ1, and TX1/TY3-Q5 multi-thread contention). These are filed at Track Completion via `track-code-review.md` § Plan Corrections from Deferred Findings, not in the iteration loop. Resume processes them after the iteration loop closes.

- **Pre-spawn before iter-2 fan-out:**
  - Re-stage the cumulative diff and changed-files list because `fcd2e58d7c` is on top of the iter-1 base:
    ```bash
    git diff c2e99ebd3a90bbbe0a906ec940db7e5416eb231c..HEAD \
        > /tmp/claude-code-track-3-diff-$PPID.patch
    git diff c2e99ebd3a90bbbe0a906ec940db7e5416eb231c..HEAD --name-only \
        > /tmp/claude-code-track-3-files-$PPID.txt
    ```
  - Regenerate slim plan snapshot at `/tmp/claude-code-plan-slim-$PPID.md` since `$PPID` changes with the new session.
  - Build each gate-check sub-agent prompt from `.claude/workflow/prompts/dimensional-review-gate-check.md` substituting the open finding IDs and titles **verbatim from this handoff's `## Verbatim re-present text` section**.

- **Path to Track Completion (after iter-2 fan-out PASSes):**
  - Track Completion is part of Phase C — runs in the same session as the iteration loop (per `track-code-review.md` § Track Completion).
  - Process deferred plan corrections per `track-code-review.md` § Plan Corrections from Deferred Findings — file follow-up YouTrack issues for the deferred clusters (named in the iter-1 synthesis above), as Workflow update commits.
  - Compile track episode from step episodes + iter-1 FIX_NOTES + CROSS_TRACK_HINTS already in this handoff's `## Durable artifacts on disk` section (the implementer's return text captured the CROSS_TRACK_HINTS but they are not yet folded into the track file's episode).
  - Present to user via `AskUserQuestion` with three options per `review-mode.md` § Approval-panel contract.
  - On Approve: write track episode, collapse description, mark `[x]` in `implementation-plan.md`, commit as `Mark Track 3 complete`, push.

- **Cross-track impact captured by iter-1 implementer (forward to Track 4):**
  - The renamed engine-agnostic `CLEAR_CONCURRENCY_CONTRACT_NOTE` constant is reusable verbatim for any Mockito-fixture short-circuit acknowledgement in Track 4's recalibration tests.
  - The `(-currentNonNull to svTree, -currentNull to nullTree)` two-tree split is the MV-side persisted-write shape for any negative delta on the multi-value engine, including recalibration deltas.
  - The MV one-tree-empty subcase (F11) names a contract Track 4 must preserve on its own recalibration path: snapshot of engine-level AtomicLongs can capture a concurrent put against the empty side; self-healed by `buildInitialHistogram` recalibration on next touch.

## Iteration counter accounting

| Iteration | Status | Counter consumed |
|---|---|---|
| 1 | SUCCESS — all 12 findings applied in commit `fcd2e58d7c` | 1 |
| 2 | NOT STARTED — gate-check fan-out + (if any STILL OPEN / REGRESSION) implementer respawn | not yet |
| 3 | available if iter-2 surfaces blockers | not yet |

Remaining iteration budget: 2 (iter-2, iter-3). The pre-spawn budget rule (~15 findings / ~10 files) applied at iter-1 — 12 findings × 7 files landed within budget with no split.
