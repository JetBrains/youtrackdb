# Track 3: clear() pure-delta encoding

## Purpose / Big Picture
After this track, the clear-rollback divergence is structurally impossible for the in-memory `AtomicLong` index-entry counters and the persisted EP-page entry counts on both the commit path and the `clearIndex` API path. `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` no longer mutate either the persisted EP page or the in-memory `AtomicLong` directly; both sides advance through the `IndexCountDelta` accumulator that Track 2 wired into the atomic-op lifecycle. Out of scope: the in-memory `IndexHistogramManager.cache` and the `indexesSnapshot`/`nullIndexesSnapshot` maps still mutate eagerly inside the atomic op; on rollback those caches stay stale until the next recalibration (deferred to histogram-delta refactor / snapshot-semantics follow-ups).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Convert `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` to pure-delta encoding: call `clearSVTree(atomicOperation)` first to take the per-tree component lock transitively, read current counters under that lock, accumulate `Δ = -current` on the atomic op via the new long-form `IndexCountDelta.accumulateClearOrRecalibrate` overload, and stop writing directly to the persisted EP pages and in-memory `AtomicLong`s. After this track, the clear-rollback divergence is structurally impossible for the in-memory `AtomicLong` index-entry counters and the persisted EP-page entry counts on both the commit path and the `clearIndex` API path (the latter requires Track 2's hook wiring to be effective). Out of scope (see Purpose / Big Picture for the full statement): the in-memory `IndexHistogramManager.cache` and the `indexesSnapshot`/`nullIndexesSnapshot` maps.

## Progress
- [>] 2026-05-25T02:46Z [ctx=warning] Phase A paused before decomposition for context refresh — see `handoff-phase-a-track-3.md`. Reviews PASS at iteration 2 (Technical / Risk / Adversarial). Iter-1 fixes applied; gate-check verified.
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

- [x] 2026-05-25T02:46Z [ctx=warning] Q1 lock-window: Read after clearSVTree (user-picked). The new clear() body calls `clearSVTree(atomicOperation)` first — the per-tree component lock is acquired transitively via `tree.remove → executeInsideComponentOperation → acquireExclusiveLockTillOperationComplete` — then reads `currentTotal`/`currentNull` under the lock. Closes the snapshot-read vs concurrent Hook B apply race on the `clearIndex` API path without adding an explicit engine-level lock acquisition.
- [x] 2026-05-25T02:46Z [ctx=warning] Q2 persisted-side normalization: Accept + document (user-picked). Pure-delta encoding propagates pre-existing in-mem-vs-persisted drift; today's `setApproximateEntriesCount(0)` hard-reset is no longer present. Long-term self-heal is `buildInitialHistogram` recalibration on next touch (Track 4). Accepted regression vs today because eagerly normalizing the persisted side inside `clear()` would re-introduce the in-atomic-op write this track removes (D2's one-place-only goal).
- [x] 2026-05-25T02:46Z [ctx=warning] Q3 overload hardening: Rename + Javadoc + assert (user-picked). New overload named `accumulateClearOrRecalibrate`; additive semantics spelled out (mirrors existing `±1` overload's `+= sign` pattern); runtime assert checks `|nullDelta| <= |totalDelta|` AND sign-aligned (`Long.signum(totalDelta) == Long.signum(nullDelta)`). The assert holds structurally for clear (since `currentNull <= currentTotal`); Track 4 recalibration may hit edge cases where opposed-direction drift between total and null counts violates the magnitude check — flag as cross-track signal for Track 4 Phase A.
- [x] 2026-05-25T02:46Z [ctx=warning] Q4 rollback-injection seam: Stub `histogramManager.resetOnClear` (user-picked). `ClearIndexApiRollbackTest` activation injects a stub `IndexHistogramManager` whose `resetOnClear` throws `IOException`. The throw routes through `engine.clear()`'s catch wrap (MV inline at lines 317–324; SV method-level at lines 277–280), wraps as `IndexException`, escapes the `executeInsideAtomicOperation` lambda, triggers rollback. Hook B's `currentError == null` gate skips apply, so both counter sides retain pre-clear values.

## Outcomes & Retrospective

- [x] 2026-05-25T02:46Z [ctx=warning] Technical: PASS at iteration 2 (5 findings; 5 accepted and verified — T1 lock-window, T2 persisted-normalization regression, T3 Step 4 four-edit enumeration + seam choice, T4 Track 4 preflight, T5 FQN polish).
- [x] 2026-05-25T02:46Z [ctx=warning] Risk: PASS at iteration 2 (6 findings; 4 accepted and verified — R1 lock-window, R2 Step 4 seam, R3 overload runtime assert, R4 staleness deferred-Phase-4 extension; 1 moot — R5 alternative test subsumed by R2 seam; 1 rejected — R6 worked-example polish, suggestion-tier no fix required).
- [x] 2026-05-25T02:46Z [ctx=warning] Adversarial: PASS at iteration 2 (7 findings; 7 accepted and verified — A1 lock-window, A2 in-mem-derived persisted-underflow accepted via Q2 path, A3 BLUF narrowing, A4 additive semantics, A5 rename to `accumulateClearOrRecalibrate`, A6 snapshot-maps staleness, A7 Step 4 four-edit enumeration).
- [x] 2026-05-25T02:46Z [ctx=warning] Design decisions resolved in chat (Q1–Q4). See Decision Log.

## Context and Orientation

The clear() bodies span:

- `BTreeMultiValueIndexEngine.clear(Storage, AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java:282–326`. The body today: `clearSVTree(atomicOperation)`; two assertions confirming both trees emptied; `svTree.setApproximateEntriesCount(atomicOperation, 0)`; `nullTree.setApproximateEntriesCount(atomicOperation, 0)`; `indexesSnapshot.clear()`; `nullIndexesSnapshot.clear()`; `approximateIndexEntriesCount.set(0)`; `approximateNullCount.set(0)`; optional `histogramManager.resetOnClear(atomicOperation)` wrapped in try/catch.

- `BTreeSingleValueIndexEngine.clear(Storage, AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java:241–282`. Same shape with one tree and a method-level `IOException` wrap.

`IndexCountDelta` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` has one `accumulate` overload today (the `±1` sign + isNullKey form). The new long-form overload — named `accumulateClearOrRecalibrate` so the call-site is self-documenting — accepts arbitrary-magnitude signed deltas with additive semantics (`delta.totalDelta += totalDelta; delta.nullDelta += nullDelta;` mirrors the existing `±1` overload's `+= sign` pattern). It must be added before the clear() conversions.

`persistCountDelta` on MV engine (lines 707–719): splits the total delta as `nonNullDelta = totalDelta - nullDelta` and writes `svTree.addToApproximateEntriesCount(op, nonNullDelta)` + `nullTree.addToApproximateEntriesCount(op, nullDelta)`. For the proposed clear delta `(-currentTotal, -currentNull)`, this produces `nonNullDelta = -(currentTotal - currentNull) = -currentSv` and `nullDelta = -currentNull`. Writes apply correctly to both trees.

`persistCountDelta` on SV engine (lines 709–717): writes only `sbTree.addToApproximateEntriesCount(op, totalDelta)`; deliberately ignores `nullDelta` because SV stores all entries (including nulls) in one tree. The clear delta `(-currentTotal, -currentNull)` produces total = `-currentTotal`, applied to the single tree. Correct.

Locking. Commit path: `lockIndexes` at `AbstractStorage.java:2233` acquires the per-engine exclusive lock before `engine.clear()` is invoked. `clearIndex` API path: `executeInsideAtomicOperation` (`AtomicOperationsManager.java:157-182`) takes only the atomic-op-lifecycle freeze gate plus `stateLock.readLock()` from `clearIndex` itself (`AbstractStorage.java:3075-3097`) — no per-engine exclusive lock at entry. The per-BTree component lock is taken lazily, inside `clearSVTree → tree.remove → executeInsideComponentOperation → acquireExclusiveLockTillOperationComplete`. The new clear() body therefore reads `approximateIndexEntriesCount.get()` and `approximateNullCount.get()` **after** `clearSVTree(atomicOperation)` returns; the per-tree exclusive lock acquired by the first `tree.remove` is held when the snapshot is taken, so the read is race-free against any concurrent commit's Hook B apply on the same engine.

Persisted-side normalization regression vs today. Today's `setApproximateEntriesCount(atomicOperation, 0)` writes hard-reset the persisted EP pages regardless of any drift between the in-memory `AtomicLong` and the persisted EP value. The pure-delta encoding's `addToApproximateEntriesCount(-currentInMem)` only zeros the persisted side when in-memory equals persisted at clear time. After a Track-1 `reportAndClampUnderflow` event (in-memory clamped to 0, persisted left intact) or any other divergence source, the post-clear persisted EP page lands at `(drifted_pre_clear − currentInMem)` rather than `0`. Accepted regression: the long-term posture is one-place-only delta-encoded mutation (D2), so eagerly normalizing the persisted side inside `clear()` would re-introduce the in-atomic-op write this track removes. Drift sources are already-fixed (Track 1+2) or out-of-scope (YTDB-953 Bug C); `buildInitialHistogram` recalibration covers the rare residual case on next touch.

The deliverable: one new `IndexCountDelta.accumulateClearOrRecalibrate` overload (with additive semantics + sign-aligned runtime assert); two rewritten clear() bodies (clearSVTree first, then snapshot read, then accumulate, then drop direct writes); clear-rollback regression tests for both engines on the commit path; activation of `ClearIndexApiRollbackTest` from Track 2 Step 4 (four edits, rollback injection via reflective `histogramManager.resetOnClear` IOException stub).

## Plan of Work

Four logical edits:

1. **Add `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation, int, long totalDelta, long nullDelta)`** at `IndexCountDelta.java`. The self-documenting name signals the intended use; the existing `±1` `accumulate(... int sign, boolean isNullKey)` overload stays for the per-put/per-remove hot path.
   - **Additive semantics**: `delta.totalDelta += totalDelta; delta.nullDelta += nullDelta;` — mirrors the existing overload's `+= sign` pattern so puts before and after a clear in the same atomic op compose algebraically.
   - **Runtime precondition assert**: `assert Math.abs(nullDelta) <= Math.abs(totalDelta) && (totalDelta == 0 || nullDelta == 0 || Long.signum(totalDelta) == Long.signum(nullDelta)) : "accumulateClearOrRecalibrate requires sign-aligned deltas with |nullDelta| <= |totalDelta|; got totalDelta=" + totalDelta + " nullDelta=" + nullDelta;`. The four production call sites (MV/SV `clear()` plus MV/SV `buildInitialHistogram()`) all pass sign-aligned deltas.
   - **Javadoc** names the four production callers as the only intended callsites and forbids per-put/per-remove use (those keep the `±1` overload).
   - **Track 4 preflight**: if `accumulateClearOrRecalibrate(AtomicOperation, int, long, long)` already exists from Track 4 having landed first, skip the addition and confirm the signature + Javadoc match this track's intent.

2. **Rewrite `BTreeMultiValueIndexEngine.clear()`** (lines 282–326) per the design doc's mechanism block, with the new ordering:
   - Call `clearSVTree(atomicOperation)` first — this acquires the per-BTree component lock transitively via `tree.remove → executeInsideComponentOperation → acquireExclusiveLockTillOperationComplete`. The lock is now held by the current atomic op.
   - Keep the two `svTree.firstKey` / `nullTree.firstKey` assertions confirming both trees emptied.
   - **After the assertions**, read `currentTotal = approximateIndexEntriesCount.get()` and `currentNull = approximateNullCount.get()` — under the per-tree exclusive lock acquired above, race-free against any concurrent commit's Hook B apply on the same engine.
   - Call `indexesSnapshot.clear()` and `nullIndexesSnapshot.clear()`.
   - Call `IndexCountDelta.accumulateClearOrRecalibrate(atomicOperation, id, -currentTotal, -currentNull)`.
   - Remove the four direct writes (`svTree.setApproximateEntriesCount(0)`, `nullTree.setApproximateEntriesCount(0)`, `approximateIndexEntriesCount.set(0)`, `approximateNullCount.set(0)`).
   - Keep the `histogramManager.resetOnClear` block with its IOException-to-IndexException wrap unchanged. (Histogram reset is orthogonal to the index-count delta and stays as-is until histogram-delta refactor lands separately.)
   - Replace the obsolete comment at lines 298–310 (the one documenting the rollback hazard) with a two-line comment: (a) the persisted EP page is transiently out of sync with the empty tree until `persistCountDelta` runs at Hook A; (b) any pre-existing drift between in-memory and persisted is intentionally not normalized here — see Context and Orientation § Persisted-side normalization regression.

3. **Rewrite `BTreeSingleValueIndexEngine.clear()`** (lines 241–282) with the structurally identical change for the single-tree case: `doClearTree(atomicOperation)` first (acquires the per-tree component lock transitively); the post-condition assert; **then** the `currentTotal`/`currentNull` snapshot reads under the lock; `indexesSnapshot.clear()`; `IndexCountDelta.accumulateClearOrRecalibrate(atomicOperation, id, -currentTotal, -currentNull)`; drop the three direct writes (`sbTree.setApproximateEntriesCount(0)`, `approximateIndexEntriesCount.set(0)`, `approximateNullCount.set(0)`). Keep the method-level `try/catch (IOException)` wrap because `doClearTree` propagates `IOException`. SV's `persistCountDelta` ignores `nullDelta` (single tree stores nulls and non-nulls together), so the `nullDelta` half of the accumulated delta drives the in-memory `approximateNullCount` apply only — the persisted side moves by `totalDelta` alone, which is the correct full-tree collapse.

4. **Regression tests** under `core/src/test/.../engine/v1/` (commit-path coverage) and `core/src/test/.../storage/impl/local/` (API-path activation):
   - `BTreeMultiValueIndexEngineClearRollbackTest` — clear inside a TX, force the commit to fail via the `RecordSerializationOperation` push pattern that Track 2's `MainCommitCounterSyncTest:186-214` established (inject a wrapped IOException into the active TX's `RecordSerializationContext` so the throw lands inside `recordSerializationContext.executeOperations(atomicOperation, this)` at `AbstractStorage.java:2331` — inside the pre-`endTxCommit` catch broadened by Track 1). Assert both in-memory and persisted counters retain pre-clear values; the worked example from Context and Orientation pins the expected post-rollback `(svTree, nullTree, in-mem total, in-mem null)` quad. Cover the commit-path clear via `commitIndexes` with `changes.cleared = true`.
   - `BTreeSingleValueIndexEngineClearRollbackTest` — same scenario for SV engine.
   - Activate `ClearIndexApiRollbackTest` (staged at `core/src/test/.../storage/impl/local/` from Track 2 Step 4 commit `21fe1a5c45`). **Four edits required**:
     1. Remove the `@Ignore` annotation at lines 50–53.
     2. Remove the `fail("Scaffold body incomplete: ...")` tripwire at lines 131–135.
     3. Wire the `clearIndex(indexId)` invocation between the preparatory commit and the post-clear assertions.
     4. Wire the forced-rollback injection by reflectively replacing the target engine's `histogramManager` field with a stub whose `resetOnClear(AtomicOperation)` throws `IOException`. The throw routes through `engine.clear()`'s inner `try/catch (IOException)` wrap (SV at lines 277–280) or the MV histogram block's inline `try/catch (IOException)` (MV at lines 317–324), which wraps as `IndexException` and escapes the `executeInsideAtomicOperation` lambda, triggering rollback. Hook B's `currentError == null` gate then skips apply, so both counter sides retain pre-clear values.

Ordering constraint: Step 1 (the overload) before Steps 2 and 3 (the clear() rewrites). Step 4 last.

Invariants to preserve: the postcondition that `clearSVTree` empties both trees (assertions at MV:285, :288 today; preserved). The lock contract on the in-memory reads — commit path holds the per-engine lock via `lockIndexes` before `engine.clear()` is invoked; API path takes the per-tree component lock lazily inside `clearSVTree → tree.remove`, so the snapshot read at the top of the new `clear()` body must happen **after** `clearSVTree` returns. The `IOException`-to-`IndexException` wrap on the histogram reset.

## Concrete Steps

## Episodes

## Validation and Acceptance

After Track 3 lands:

- `clear()` on both engines does not mutate either the persisted EP page or the in-memory `AtomicLong` directly. Both sides advance via `persistCountDelta` (inside the atomic op) and `addToApproximate{Entries,Null}Count` (after `commitChanges`).
- The clear-rollback regression test passes on both engines, on both the commit path and the `clearIndex` API path.
- The pre-clear counter values `(persisted_sv, persisted_null, in-mem total, in-mem null)` are bit-identical before and after a rolled-back clear-and-puts transaction (the worked example in the design doc).
- After a successful clear-and-puts commit, the counters land on the post-clear values that the worked example predicts.
- Histogram reset still throws `IndexException` (wrapping IOException) on failure exactly as today.
- Accepted persisted-side normalization regression: after a clear of an engine whose persisted-side and in-memory previously diverged by `D`, the post-clear persisted EP page lands at `(drifted_pre_clear − currentInMem)` rather than `0`. Today's `setApproximateEntriesCount(0)` hard-reset is no longer present; the long-term self-heal is `buildInitialHistogram` recalibration on next touch (Track 4).

## Idempotence and Recovery

## Artifacts and Notes

### Deferred from PR #1088 Gemini review (Phase 2, 2026-05-22)

Pure-delta encoding for `clear()` (this track) and `buildInitialHistogram()` (Track 4) creates an in-memory staleness window symmetrical to the persisted-side window already documented in `design.md` § Pure-delta encoding § Edge cases (1): between the `IndexCountDelta.accumulate(...)` call and Hook B's `applyIndexCountDeltas` (post-`commitChanges`, pre-`releaseLocks`), `approximateIndexEntriesCount.get()` returns the pre-clear / pre-recalibration value. PSI-verified during Phase 2 that no production caller of `engine.size()` reads within the same atomic op as a `clear` or `buildInitialHistogram`: the five production callers via `Index.size` are `IndexRebuildOutputListener.onCompletition` (post-rebuild), `SQLCreateIndexStatement.execute` (DDL), `DatabaseCompare.compareIndexes` (×2; utility, no tx), and `CountFromIndexStep.produce` (SQL query plan; does not co-execute with `clear` in normal flows).

Phase 4 action: `design-final.md` § Pure-delta encoding § Edge cases (currently item 4) should add a one-line note covering the in-memory staleness window and the call-graph reason it does not bite production. Captured here so Track 3's Phase 4 inputs survive merge — `design.md` is frozen during Phase 3.

**Extension surfaced by Track 3 Phase A reviews.** The same staleness window applies to the in-memory `IndexHistogramManager.cache` (mutated by `resetOnClear` at `IndexHistogramManager.java:909`) and the `indexesSnapshot`/`nullIndexesSnapshot` maps (cleared inside `clear()` at MV:296 / SV:257) — both mutate eagerly inside the atomic op and stay stale on rollback. Deliberately out of Track 3's scope (histogram-delta refactor / snapshot-semantics belong to follow-up tracks); `buildInitialHistogram` recalibration covers the histogram-cache residual case on next touch. Phase 4 action: extend the `design-final.md` staleness-window note to enumerate all three state machines (counter, histogram cache, snapshot maps).

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` (long-form `accumulate` overload)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (clear() body)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (clear() body)
- New regression tests under `core/src/test/.../engine/v1/`
- Existing `ClearIndexApiRollbackTest` staged in Track 2 (move from `@Ignore` to active)

**Out of scope**:
- `buildInitialHistogram` conversion (Track 4).
- Histogram delta refactor or `resetOnClear` changes — the histogram reset path stays as-is.
- `persistCountDelta` signature changes — the existing splitting logic in MV and the `nullDelta`-ignored logic in SV both work with the new delta encoding.
- In-memory `IndexHistogramManager.cache` and `indexesSnapshot`/`nullIndexesSnapshot` eager-mutation under rollback — same staleness shape as the index-count fix; deferred to histogram-delta-refactor and snapshot-semantics follow-up tracks.
- Persisted-side normalization of pre-existing drift — accepted regression vs today's `setApproximateEntriesCount(0)` hard-reset; long-term self-heal is `buildInitialHistogram` recalibration on next touch.

**Inter-track dependencies**:
- **Depends on Track 2**: the `clearIndex` API path requires the lifecycle hooks to consume the accumulated delta. Without Track 2, the API-path clear would leave the tree empty but both counters at pre-clear values — worse than today.
- Track 4 (buildInitialHistogram) reuses the long-form `accumulateClearOrRecalibrate` overload added in this track; Track 4 has a soft dependency on Track 3 landing first. If Track 4 lands first, this track's Step 1 preflight (skip the addition and confirm the signature + Javadoc match) absorbs the order swap.

**Library/function signatures relevant to this track**:
- `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta)` — new overload (package `com.jetbrains.youtrackdb.internal.core.index.engine`). Additive semantics + sign-aligned/`|nullDelta| <= |totalDelta|` runtime assert (see Plan of Work Step 1).
- `BTreeMultiValueIndexEngine.clear(Storage, AtomicOperation)` — rewritten body with new ordering (clearSVTree first, then snapshot read, then accumulate).
- `BTreeSingleValueIndexEngine.clear(Storage, AtomicOperation)` — rewritten body with same new ordering.
- `IndexHistogramManager` (package `com.jetbrains.youtrackdb.internal.core.index.engine`) — `resetOnClear(AtomicOperation)` unchanged; still throws IOException on failure (and is the chosen seam for `ClearIndexApiRollbackTest`'s rollback-injection in Step 4).
