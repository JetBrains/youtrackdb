# Track 3: clear() pure-delta encoding

## Purpose / Big Picture
After this track, the clear-rollback divergence is structurally impossible on both the commit path and the `clearIndex` API path. `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` no longer mutate either the persisted EP page or the in-memory `AtomicLong` directly; both sides advance through the `IndexCountDelta` accumulator that Track 2 wired into the atomic-op lifecycle.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Convert `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` to pure-delta encoding: read current counters under the engine's exclusive lock, accumulate `Δ = -current` on the atomic op via the new long-form `IndexCountDelta.accumulate` overload, and stop writing directly to the persisted EP pages and in-memory `AtomicLong`s. After this track, the clear-rollback divergence is structurally impossible on both the commit path and the `clearIndex` API path (the latter requires Track 2's hook wiring to be effective).

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

## Context and Orientation

The clear() bodies span:

- `BTreeMultiValueIndexEngine.clear(Storage, AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java:270–315`. The body today: `clearSVTree(atomicOperation)`; two assertions confirming both trees emptied; `svTree.setApproximateEntriesCount(atomicOperation, 0)`; `nullTree.setApproximateEntriesCount(atomicOperation, 0)`; `indexesSnapshot.clear()`; `nullIndexesSnapshot.clear()`; `approximateIndexEntriesCount.set(0)`; `approximateNullCount.set(0)`; optional `histogramManager.resetOnClear(atomicOperation)` wrapped in try/catch.

- `BTreeSingleValueIndexEngine.clear(Storage, AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java:221–263`. Same shape with one tree and a method-level `IOException` wrap.

`IndexCountDelta` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` has one `accumulate` overload today (the `±1` sign + isNullKey form). The new `(long, long)` overload accepts arbitrary-magnitude negative deltas. It must be added before the clear() conversions.

`persistCountDelta` on MV engine (lines 651–664): splits the total delta as `nonNullDelta = totalDelta - nullDelta` and writes `svTree.addToApproximateEntriesCount(op, nonNullDelta)` + `nullTree.addToApproximateEntriesCount(op, nullDelta)`. For the proposed clear delta `(-currentTotal, -currentNull)`, this produces `nonNullDelta = -(currentTotal - currentNull) = -currentSv` and `nullDelta = -currentNull`. Writes apply correctly to both trees.

`persistCountDelta` on SV engine (lines 647–656): writes only `sbTree.addToApproximateEntriesCount(op, totalDelta)`; deliberately ignores `nullDelta` because SV stores all entries (including nulls) in one tree. The clear delta `(-currentTotal, -currentNull)` produces total = `-currentTotal`, applied to the single tree. Correct.

Locking: the commit-path clear runs under the engine's exclusive lock via `lockIndexes` at `AbstractStorage.java:2233`; the `clearIndex` API path acquires the lock inside `executeInsideAtomicOperation`. The `approximateIndexEntriesCount.get()` and `approximateNullCount.get()` reads at the start of the new clear() body must happen under one of those locks. Both paths satisfy this.

The deliverable: one new `IndexCountDelta.accumulate` overload; two rewritten clear() bodies; clear-rollback regression tests for both engines on both the commit path and the `clearIndex` API path.

## Plan of Work

Four logical edits:

1. **Add `IndexCountDelta.accumulate(AtomicOperation, int, long, long)`** at `IndexCountDelta.java`. Mirrors the existing overload but takes raw long deltas with no sign precondition. Javadoc names the intended callers (clear, recalibration). The existing `±1` overload stays for the per-put/per-remove hot path.

2. **Rewrite `BTreeMultiValueIndexEngine.clear()`** (lines 270–315) per the design doc's mechanism block:
   - Read `currentTotal = approximateIndexEntriesCount.get()` and `currentNull = approximateNullCount.get()` first.
   - Call `clearSVTree(atomicOperation)`.
   - Keep the two `svTree.firstKey` / `nullTree.firstKey` assertions confirming both trees emptied.
   - Call `indexesSnapshot.clear()` and `nullIndexesSnapshot.clear()`.
   - Call `IndexCountDelta.accumulate(atomicOperation, id, -currentTotal, -currentNull)`.
   - Remove the four direct writes (`svTree.setApproximateEntriesCount(0)`, `nullTree.setApproximateEntriesCount(0)`, `approximateIndexEntriesCount.set(0)`, `approximateNullCount.set(0)`).
   - Keep the `histogramManager.resetOnClear` block with its IOException-to-IndexException wrap unchanged. (Histogram reset is orthogonal to the index-count delta and stays as-is until histogram-delta refactor lands separately.)
   - Replace the obsolete comment at lines 287–301 (the one documenting the rollback hazard) with a one-line comment noting that the persisted EP page is transiently out of sync with the empty tree until `persistCountDelta` runs.

3. **Rewrite `BTreeSingleValueIndexEngine.clear()`** (lines 221–263) with the structurally identical change for the single-tree case. Keep the method-level `try/catch (IOException)` wrap because `doClearTree` propagates `IOException`.

4. **Regression tests** under `core/src/test/.../engine/v1/`:
   - `BTreeMultiValueIndexEngineClearRollbackTest` — clear inside a TX, force the commit to fail (IOException injection at WAL flush), verify both in-memory and persisted counters retain pre-clear values. Cover both the commit-path clear (via `commitIndexes` with `changes.cleared = true`) and the `clearIndex` API path.
   - `BTreeSingleValueIndexEngineClearRollbackTest` — same scenario for SV engine.
   - Update the `ClearIndexApiRollbackTest` staged in Track 2 from `@Ignore` to active, since the standalone-atomic-op path now works.

Ordering constraint: Step 1 (the overload) before Steps 2 and 3 (the clear() rewrites). Step 4 last.

Invariants to preserve: the postcondition that `clearSVTree` empties both trees (assertions at MV:274, :277 today; preserved). The lock contract on the in-memory reads (commit path: `lockIndexes`; API path: `executeInsideAtomicOperation`). The `IOException`-to-`IndexException` wrap on the histogram reset.

## Concrete Steps

## Episodes

## Validation and Acceptance

After Track 3 lands:

- `clear()` on both engines does not mutate either the persisted EP page or the in-memory `AtomicLong` directly. Both sides advance via `persistCountDelta` (inside the atomic op) and `addToApproximate{Entries,Null}Count` (after `commitChanges`).
- The clear-rollback regression test passes on both engines, on both the commit path and the `clearIndex` API path.
- The pre-clear counter values `(persisted_sv, persisted_null, in-mem total, in-mem null)` are bit-identical before and after a rolled-back clear-and-puts transaction (the worked example in the design doc).
- After a successful clear-and-puts commit, the counters land on the post-clear values that the worked example predicts.
- Histogram reset still throws `IndexException` (wrapping IOException) on failure exactly as today.

## Idempotence and Recovery

## Artifacts and Notes

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

**Inter-track dependencies**:
- **Depends on Track 2**: the `clearIndex` API path requires the lifecycle hooks to consume the accumulated delta. Without Track 2, the API-path clear would leave the tree empty but both counters at pre-clear values — worse than today.
- Track 4 (buildInitialHistogram) reuses the long-form `accumulate` overload added in this track; Track 4 has a soft dependency on Track 3 landing first, or both tracks can add the overload guarded by an `if not exists` check.

**Library/function signatures relevant to this track**:
- `IndexCountDelta.accumulate(AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta)` — new overload.
- `BTreeMultiValueIndexEngine.clear(Storage, AtomicOperation)` — rewritten body.
- `BTreeSingleValueIndexEngine.clear(Storage, AtomicOperation)` — rewritten body.
- `IndexHistogramManager.resetOnClear(AtomicOperation)` — unchanged; still throws IOException on failure.
