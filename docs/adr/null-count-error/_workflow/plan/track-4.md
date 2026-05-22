# Track 4: buildInitialHistogram pure-delta encoding

## Purpose / Big Picture
After this track, the recalibration-rollback divergence is structurally impossible on the `buildHistogramAfterFill` path, which is the only production caller of `buildInitialHistogram`. The recalibration target `(scannedNonNull, exactNullCount)` advances through the `IndexCountDelta` accumulator instead of direct writes to the persisted EP pages and in-memory `AtomicLong`s; on rollback both sides stay at their prior approximate values.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Convert `BTreeMultiValueIndexEngine.buildInitialHistogram()` and `BTreeSingleValueIndexEngine.buildInitialHistogram()` to pure-delta encoding: compute the recalibration as `Δ = target - current` and accumulate via the long-form `IndexCountDelta.accumulate` overload. After this track, the recalibration-rollback divergence is structurally impossible on the `buildHistogramAfterFill` path, which is the only production caller.

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

The two buildInitialHistogram bodies:

- `BTreeMultiValueIndexEngine.buildInitialHistogram(AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java:574–623`. Computes `approxTotal` and `approxNull` from current in-memory counters at lines 582–583 (used for histogram bucket sizing in `mgr.buildHistogram`). Scans for exact `scannedNonNull` and `exactNullCount`. Final recalibration lines 619–622:
  ```java
  svTree.setApproximateEntriesCount(atomicOperation, scannedNonNull);
  nullTree.setApproximateEntriesCount(atomicOperation, exactNullCount);
  approximateNullCount.set(exactNullCount);
  approximateIndexEntriesCount.set(scannedNonNull + exactNullCount);
  ```

- `BTreeSingleValueIndexEngine.buildInitialHistogram(AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java:581–617`. Same shape with one tree; recalibration lines 613–616:
  ```java
  long exactTotal = scannedNonNull + exactNullCount;
  sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
  approximateIndexEntriesCount.set(exactTotal);
  approximateNullCount.set(exactNullCount);
  ```

Only one production caller: `IndexAbstract.buildHistogramAfterFill` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstract.java:390–409`. The call site wraps `buildInitialHistogram` in `executeInsideAtomicOperation` at line 397–400, then swallows `IOException` with a warn at lines 401–408 ("Histogram build failure must not fail the index rebuild. The histogram will be built lazily on the next rebalance.").

The recalibration arithmetic for the pure-delta conversion (per the design doc's mechanism block):
- MV: `totalDelta = (scannedNonNull + exactNullCount) - currentTotal`, `nullDelta = exactNullCount - currentNull`.
- SV: `totalDelta = exactTotal - currentTotal`, `nullDelta = exactNullCount - currentNull`.

For MV's `persistCountDelta` split: `nonNullDelta = totalDelta - nullDelta = (scannedNonNull + exactNullCount - currentTotal) - (exactNullCount - currentNull) = scannedNonNull - (currentTotal - currentNull) = scannedNonNull - currentSv`. That advances `svTree` from `currentSv` to `scannedNonNull`. Correct. `nullDelta = exactNullCount - currentNull` advances `nullTree` from `currentNull` to `exactNullCount`. Correct.

For SV's `persistCountDelta` (`nullDelta` ignored): `totalDelta = exactTotal - currentTotal` advances `sbTree` from `currentTotal` to `exactTotal`. Correct.

`applyIndexCountDeltas` then advances in-memory: total from `currentTotal` to `target`, null from `currentNull` to `exactNullCount`. Correct on both engines.

The `approxTotal` / `approxNull` reads at MV:582–583 and the `approxTotal` read at SV:590 (paired with the `exactNullCount = countNulls(atomicOperation)` scan at SV:591) used for histogram bucket sizing are unaffected; they consume the in-memory counter *as-of* the start of buildInitialHistogram, before the recalibration delta is recorded.

The deliverable: two rewritten buildInitialHistogram bodies and a recalibration-rollback regression test.

## Plan of Work

Three logical edits:

1. **Rewrite `BTreeMultiValueIndexEngine.buildInitialHistogram` lines 619–622**:
   ```java
   long currentTotal = approximateIndexEntriesCount.get();
   long currentNull  = approximateNullCount.get();
   long targetTotal  = scannedNonNull + exactNullCount;
   IndexCountDelta.accumulate(
       atomicOperation, id, targetTotal - currentTotal, exactNullCount - currentNull);
   ```
   The four direct writes (two `setApproximateEntriesCount` on the trees, two `AtomicLong.set` on the in-memory counters) are removed. The histogram-build code at lines 590–595 and the null-tree scan at lines 600–610 are unchanged. Update the comment block at lines 612–617 to point at the design doc's pure-delta invariant.

2. **Rewrite `BTreeSingleValueIndexEngine.buildInitialHistogram` lines 613–616** with the structurally identical change for the single-tree case:
   ```java
   long exactTotal   = scannedNonNull + exactNullCount;
   long currentTotal = approximateIndexEntriesCount.get();
   long currentNull  = approximateNullCount.get();
   IndexCountDelta.accumulate(
       atomicOperation, id, exactTotal - currentTotal, exactNullCount - currentNull);
   ```

3. **Regression test** at `core/src/test/.../index/IndexAbstractBuildHistogramRollbackTest.java`:
   - Set up an index with known counters (e.g., a few thousand entries).
   - Inject a failure in the `executeInsideAtomicOperation` lambda that wraps `buildInitialHistogram` (failure between the recalibration delta accumulate and the WAL commit).
   - Assert: in-memory counters and persisted EP page counters retain pre-recalibration values.
   - On a separate successful run, assert the post-recalibration counters match the exact values from the scan.

Ordering constraint: this track depends on Track 3's `IndexCountDelta.accumulate(long, long)` overload. If Track 3 hasn't landed yet, this track adds the overload too (with an `if not exists` discipline during step decomposition). The two clear/recalibration sites are the only callers of the long-form overload, so there's no risk of a future caller losing the sign precondition guarantee.

Invariants to preserve: the `approxTotal` read at MV:582 / SV:590, the `approxNull` read at MV:583, and the `exactNullCount = countNulls(atomicOperation)` scan at SV:591 still feed the histogram-bucket sizing. The null-tree scan logic (MV lines 600–610) is unchanged.

## Concrete Steps

## Episodes

## Validation and Acceptance

After Track 4 lands:

- `buildInitialHistogram` on both engines does not mutate either the persisted EP page or the in-memory `AtomicLong` directly. Both sides advance through the same persist → commitChanges → apply pipeline as ordinary put/remove deltas.
- The recalibration-rollback regression test passes: on a forced rollback after the delta accumulate, counters remain at pre-recalibration values; on a successful commit, counters land on the post-recalibration values.
- The histogram-build itself (bucket creation, key scanning) is unchanged.
- `IndexAbstract.buildHistogramAfterFill`'s outer `IOException` handler still swallows builder failures at lines 401–408.

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (buildInitialHistogram body)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (buildInitialHistogram body)
- New regression test under `core/src/test/.../index/`

**Out of scope**:
- `IndexHistogramManager.buildHistogram` itself (the histogram-bucket-creation logic).
- The `IOException`-swallow at `IndexAbstract.buildHistogramAfterFill:401–408`.
- `clear()` conversion (Track 3).
- Adding the long-form `IndexCountDelta.accumulate` overload — Track 3 owns that, or this track adds it idempotently if Track 3 hasn't landed first.

**Inter-track dependencies**:
- **Depends on Track 2**: `buildHistogramAfterFill` invokes `buildInitialHistogram` through `executeInsideAtomicOperation`. Without Track 2's hook wiring, the accumulated delta is dropped at the end of the atomic op and neither the persisted nor the in-memory side advances — strictly worse than today.
- **Soft dependency on Track 3**: both tracks add the long-form `accumulate` overload. Whichever lands first owns the addition; the other reuses it.

**Library/function signatures relevant to this track**:
- `IndexCountDelta.accumulate(AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta)` — the overload (from Track 3 or added here).
- `BTreeMultiValueIndexEngine.buildInitialHistogram(AtomicOperation)` — rewritten lines 619–622.
- `BTreeSingleValueIndexEngine.buildInitialHistogram(AtomicOperation)` — rewritten lines 613–616.
- `IndexAbstract.buildHistogramAfterFill` — unchanged caller.
