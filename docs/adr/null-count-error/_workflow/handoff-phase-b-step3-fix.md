# Handoff — Track 2 Step 3 dim-review iter-1 (RESULT_MISSING recovery)

**Phase**: Phase B (`step-implementation.md`) — dim-review iteration 1 for Track 2 Step 3
**Reason**: implementer spawn returned without a parsable `RESULT` block (silent exit during a bg-task.sh Monitor wait); context refresh required at the orchestrator's 32% warning gate before continuing recovery.
**Created**: 2026-05-24 (orchestrator session pre-`/clear`)
**Working tree**: clean. The implementer's in-progress edits are saved in `git stash@{0}` (SHA `d74a6d4734c9852311258a3812ec50793ffb3460`, message `YTDB-958 Step 3 fix-review-findings WIP (RESULT_MISSING recovery)`).

## What happened

1. Phase B resume entered with Step 3's implementer commit `72e7e5a107` already on disk (orphan from the prior session's RESULT_MISSING commit-as-is). No `Review fix:` siblings. Recovery path: re-enter `on_success` at sub-step 4 (dimensional review).
2. Spawned 9 dim-review agents in parallel (baseline 4 + crash-safety pair + performance + test-concurrency + test-structure). All returned. Synthesised 12 must-fix items (M1–M12) + 1 suggestion (S1) — see §Findings below.
3. Spawned the `FIX_REVIEW_FINDINGS` implementer (model=opus, level=step) with the full finding list.
4. The implementer ran for ~46 min (228k tokens, 121 tool uses), applied the edits across 5 files (511 insertions / 58 deletions; matches the M-set), launched `./mvnw -pl core clean package -P coverage -Dtest.skip.cucumber=true` via `.claude/scripts/bg-task.sh launch`, then exited with the final line `Will wait for the Monitor notification. No further polling.` — message budget exhausted before the bg-task completion notification arrived.
5. The Maven build was passing cleanly (9750 tests, 0 failures, 0 errors, 18 skipped — including the touched test classes `HistogramDeltaHolderTest 10/10`, `IndexCountDeltaHolderTest 18/18`, `IndexHistogramIntegrationTest 56/56`) but had not yet reached the `jacoco:report` goal. The orchestrator killed the bg-task wrapper + Maven JVM cleanly; the `[ERROR] Process Exit Code: 143` and `forked VM terminated without properly saying goodbye` lines in the log are the orchestrator's SIGTERM, not a real test failure.
6. Orchestrator stashed the dirty tree (`stash@{0}`) and wrote this handoff at context level `info` → `warning` (32%).

## Where work stands

- **Dirty edits** preserved in `git stash@{0}` covering five files, 511 / 58 lines:
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (+39 / 0 — likely M1 latch placement + comment update)
  - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (+55 / -58 — likely M11 DRY refactor + M1 helper + S1 assert)
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/HistogramDeltaHolderTest.java` (+90 — M9 idempotency)
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDeltaHolderTest.java` (+100 — M9 idempotency, plus likely M7 storage-side test)
  - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/EndAtomicOperationHookOrderingTest.java` (+285 / 0 — M2 deletions + M3/M4/M5 strengthen + M6 VM-error escape + M8 mixed-null + M10 nested-latch + M12 Javadoc)
- **Test status from the partial bg-task log**:
  - `HistogramDeltaHolderTest`: 10 tests pass
  - `IndexCountDeltaHolderTest`: 18 tests pass
  - `IndexHistogramIntegrationTest`: 56 tests pass
  - All visited test classes pass; no failures recorded in the log
  - Surefire summary line: `Tests run: 9750, Failures: 0, Errors: 0, Skipped: 18` (whole-module aggregate)
  - Build killed mid-flight before `jacoco:report`, so coverage gate output is unavailable
- **HEAD**: `b72224ded4` (unchanged from before the spawn — no commit landed). Base commit `1617505fe7` reachable.
- **Plan / track-file state**: Track 2 Step 3 roster line still `[ ]`; no Step 3 episode written. Steps 1 and 2 remain `[x]`.

## Resume protocol

Before doing anything else next session:

1. Confirm `git status --short` is clean and `git stash list | head` shows the `YTDB-958 Step 3 fix-review-findings WIP` entry on top (stash SHA `d74a6d4734c9852311258a3812ec50793ffb3460`). If absent — surface the discrepancy to the user; do not invent a recovery.
2. Re-read this handoff in full, then ask the user to choose one of the three options below. Do not pick a default — the per-`handle_result_missing` protocol in `step-implementation-recovery.md` § Non-`SUCCESS` orchestrator handlers requires explicit consent.
3. The Phase B resume order (rulebook §`handle_result_missing`):
   - **Re-spawn finalizer** — drop `stash@{0}`, re-spawn the `FIX_REVIEW_FINDINGS` implementer from `step_base_commit = HEAD` (`b72224ded4`) with the full M1–M12 + S1 input. Counter-arg: the stash already carries 46 min of implementer work; re-deriving it is expensive.
   - **Commit-as-is** — `git stash pop`, orchestrator runs Spotless + targeted tests + the coverage gate (use the surgical-coverage path documented in `implementation-plan.md` § Implementer pacing because the prod-source diff is moderate), then commits with subject `Review fix: harden apply-hook tests and clean dual-apply window` (or similar — describe by fix shape). The implementer's spawn is consumed as one iteration counter regardless (RESULT_MISSING rule). The orchestrator notes the RESULT_MISSING in the eventual Step 3 episode.
   - **Discard** — drop `stash@{0}`, treat the iteration as `FAILED` with `recommended_action: retry`, write the failed-iteration entry, and re-enter the loop. Equivalent to losing the implementer's work.
4. Whatever the user picks, the dim-review iteration counter advances by one (per the RESULT_MISSING accounting rule). Iteration 2 still has 2 of 3 attempts available.

## Findings (verbatim — input to the failed implementer spawn)

The following 12 must-fix items (M1–M12) plus 1 suggestion (S1) were the synthesised input to the spawn. On `Re-spawn finalizer`, reuse this list verbatim (per the rule about respawning with the same fix iteration but optionally halving when ≥15 items / ≥10 files; 12 / 5 is comfortably under both). On `Commit-as-is`, the orchestrator verifies the stash addresses each M-item before committing.

### Finding M1 [should-fix] — Move `setApplied()` latch to top of apply methods

`core/src/main/java/.../AbstractStorage.java` (`applyIndexCountDeltas` and `applyHistogramDeltas`). Dimensions: CS1 (crash-safety, concerning), TY1 (test-crash-safety, should-fix), BC1 (bugs-concurrency, suggestion). The latch must move from the bottom (post-loop) to the top (after null + already-applied guards) so a partial-loop throw caught by Hook B's `RuntimeException | AssertionError` swallow still latches the holder — otherwise the legacy inline call at `AbstractStorage.commit:2365 / 2381` re-iterates and double-applies engines processed before the throw. Add a regression test driving partial-apply.

### Finding M2 [should-fix] — Delete `lockedObjectsEmptyByDefault` and `storageMockExposesStableName`

`core/src/test/.../EndAtomicOperationHookOrderingTest.java:825-848`. Dimensions: CQ3, TB-2, TB-5, TS-1. Both tests drift from the class charter; neither pins anything meaningful that other tests don't already cover. Delete.

### Finding M3 [should-fix] — Pin persist-failure re-raise with `assertThrows` + `assertSame`

`core/src/test/.../EndAtomicOperationHookOrderingTest.java:444-475` (`persistFailureSkipsCommitAndBothApplyHooks`). Dimensions: TB-1, TS-4. The bare `try/catch (RuntimeException) {}` swallows the throw without asserting it happened. Rewrite to `assertThrows(RuntimeException.class, …)` + `assertSame(persistFailure, thrown)` to pin both the throw and the typed re-raise contract.

### Finding M4 [should-fix] — Add `isExclusiveOwner()` snapshot + `InOrder(apply, unlock)` to lock-window tests

`applyIndexCountHookSeesLockedComponentsHeld` (`:489-527`) and `applyHistogramHookSeesLockedComponentsHeld` (`:534-559`). Dimensions: TB-3, TX-1. The size-snapshot alone passes even for buggy placements; the histogram sibling omits the ownership snapshot. Add ownership snapshot to histogram, add explicit `InOrder` block (apply before `unlockExclusive`) to both.

### Finding M5 [should-fix] — Add `lockedList.isEmpty()` + apply-ordering `InOrder` to `applyHistogramFailureIsSwallowed`

`core/src/test/.../EndAtomicOperationHookOrderingTest.java:601-623`. Dimensions: TB-4, TY2. Mirror the index-count sibling's assertions; add `InOrder` confirming `applyIndexCountDeltas` runs before `applyHistogramDeltas`.

### Finding M6 [should-fix] — Add VM-error escape tests for Hook B

`EndAtomicOperationHookOrderingTest.java` (new tests). Dimension: TC1. Mirror Hook A's 4 VM-error escape tests (`OutOfMemoryError`, `LinkageError`, `StackOverflowError`, `InternalError`) via the shared `EndAtomicOperationHookTestSupport.assertVmErrorEscapesUnconverted` helper introduced in Step 2. Pins that the `RuntimeException | AssertionError` bounded catch does NOT extend to genuine VM errors.

### Finding M7 [should-fix] — Add storage-side `isApplied()` short-circuit coverage

`IndexCountDeltaHolderTest.java` and `HistogramDeltaHolderTest.java` (or a new `AbstractStorageApplyDeltaTest`). Dimension: TC2. The `if (holder.isApplied()) return;` gate inside `AbstractStorage.applyIndexCountDeltas:2529-2531` and `applyHistogramDeltas:2570-2572` is never exercised by the mock-storage hook test. Add focused tests that call `storage.apply*Deltas(op)` twice and assert the engine counter advances exactly once.

### Finding M8 [should-fix] — Add tests for mixed-null holder cases

`EndAtomicOperationHookOrderingTest.java` (new tests). Dimension: TC3. Hook B's two gates are independent `if`-statements; `applyHooksSkipWhenHoldersAreNull` covers both-null only. Add `applyHistogramRunsWhenIndexHolderIsNull` and `applyIndexCountRunsWhenHistogramHolderIsNull`.

### Finding M9 [should-fix] — Pin `applied` latch idempotency + default value

`IndexCountDeltaHolderTest.java` and `HistogramDeltaHolderTest.java`. Dimension: TC4. Add `appliedLatchIsIdempotent` tests mirroring Step 2's `persistedLatchIsIdempotent` pattern: assert default `false`, then two `setApplied()` calls leave `isApplied() == true` without throwing.

### Finding M10 [should-fix] — Pin "inner endAtomicOperation does not flip outer's `applied` latch"

`EndAtomicOperationHookOrderingTest.java` (extend the nested-op test or add a sibling). Dimension: TC5. Wire the `doAnswer` stubs to call `setApplied()` on the inbound holder mirroring real `AbstractStorage.apply*Deltas`, then assert outer-vs-inner latch isolation across the nested call.

### Finding M11 [should-fix] — DRY apply-hook block + gate symmetry between Hook A and Hook B

`core/src/main/java/.../AtomicOperationsManager.java:363-388`. Dimensions: CQ1, CQ2. Extract a small `tryApply(holder, applyCall, label)` helper (or factor the warn-message template into a constant) so the two structurally-identical apply blocks don't drift. Tighten Hook B's gate to `currentError == null && !operation.isRollbackInProgress()` matching Hook A for visual symmetry (zero behavioural change since the only failure source between the two gates is `commitChanges`, whose throw bypasses Hook B via the inner-finally).

### Finding M12 [should-fix] — Document `mockOperationWithLockedComponents` as a delta of `mockOperation`

`core/src/test/.../EndAtomicOperationHookOrderingTest.java:347`. Dimension: TS-2. Add a one-line Javadoc clause naming the four deltas vs the shared `EndAtomicOperationHookTestSupport.mockOperation` helper.

### Finding S1 [suggestion] — `assert` Hook B latch post-condition (only after M1 lands)

`core/src/main/java/.../AtomicOperationsManager.java` (Hook B body, after each apply call). Dimension: TY4. Once M1 moves the latch to the top of each apply method, the post-condition is unconditional. Add `assert indexHolder.isApplied() : "..."` and `assert histogramHolder.isApplied() : "..."` after each apply call. Cheap and catches future regressions that forget the latch.

## Synthesis audit trail (deferred / dropped)

`DEFERRED BC2` — pre-existing commit-side gap (between `commitChanges` and Hook B); follow-up.
`DEFERRED BC3` — persist-failure re-raise re-test belongs in Step 4 when inline persist is deleted.
`DEFERRED BC4, TX-3` — non-volatile latch fields; future-proofing only.
`DEFERRED CQ4` — dual idempotency-latch gates reconciled at Step 4 when inline calls are deleted.
`DEFERRED CQ5, CQ6, CQ7` — minor style; polish-pass.
`DEFERRED PF-1` — lock-hold window widens for `flushSnapshotToPage`; Track 3/4 perf measurement.
`DEFERRED TX-2, TY3` — real-thread cross-transaction lock-window test belongs in Step 4's `MainCommitCounterSyncTest`.
`DEFERRED TS-3, TS-5, TS-6, TS-7, TY5` — minor polish.
`DEFERRED TC6, TB-6` — accept-known-cost-to-value gap (apply-failure warn-log JUL Handler test infrastructure).
`DROP CQ8, CS2, CS3, CS4, PF-2, PF-3` — confirmations / negligible / informational; no action needed.

## Plan-file and track-file invariants on resume

- `implementation-plan.md` § Checklist Track 2 still `[ ]` (untouched). Plan is correct.
- `track-2.md` Progress section: Step 3 still `[ ]`; need to append a PAUSED marker on the same line under `Step implementation` (this commit will).
- `track-2.md` Concrete Steps roster: Step 3 line still `[ ]`. Will become `[x]` on Phase B exit after this iteration lands.
- `## Episodes` section in `track-2.md`: no Step 3 episode yet. Episode will be written by the orchestrator after the implementer iteration succeeds.

## Context status at handoff

`ctx: 32% level=warning` — the orchestrator's pause is the workflow-required action at warning. Resume in a fresh session.

## MEMORY pointer

A line will be appended to `MEMORY.md` under the `## Branch: YTDB-958-null-count-error` section pointing at this file. The MEMORY entry is informational so the user can find the handoff fast if the session boundary erases the orchestrator's memory of the path.
