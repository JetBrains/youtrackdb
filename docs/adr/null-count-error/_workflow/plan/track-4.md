# Track 4: buildInitialHistogram mixed-mode encoding

## Purpose / Big Picture
After this track, the in-memory recalibration-rollback divergence is structurally impossible on the `buildHistogramAfterFill` path, which is the only production caller of `buildInitialHistogram`. The in-memory `AtomicLong`s advance through the new in-mem-only `IndexCountDelta.accumulateInMemRecalibration` accumulator instead of direct writes; on rollback they stay at their prior approximate values. The persisted EP-page side keeps today's inline `setApproximateEntriesCount(op, target)` writes, which are WAL-tracked, revert on rollback, and heal pre-existing in-mem-vs-persisted drift on every successful recalibration.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Convert `BTreeMultiValueIndexEngine.buildInitialHistogram()` and `BTreeSingleValueIndexEngine.buildInitialHistogram()` to mixed-mode encoding. Keep today's inline `setApproximateEntriesCount(op, target)` writes on the persisted EP pages, and route the in-memory `AtomicLong` writes through a new `IndexCountDelta.accumulateInMemRecalibration(op, id, totalDelta, nullDelta)` accumulator consumed by Hook B. After this track, the in-memory recalibration-rollback divergence is structurally impossible on the `buildHistogramAfterFill` path; the persisted-side drift-healing behavior already in production is preserved.

## Progress
- [x] 2026-05-25T13:37Z [ctx=safe] Review + decomposition complete
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective

- [x] Technical: PASS at iteration 2 (6 findings, all VERIFIED)
- [x] Adversarial: PASS at iteration 2 (8 findings, all VERIFIED)

## Context and Orientation

The two buildInitialHistogram bodies:

- `BTreeMultiValueIndexEngine.buildInitialHistogram(AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java:604–654`. Computes `approxTotal` and `approxNull` from current in-memory counters at lines 613–614 (used for histogram bucket sizing in `mgr.buildHistogram`). Scans for exact `scannedNonNull` and `exactNullCount`. Final recalibration lines 650–653:
  ```java
  svTree.setApproximateEntriesCount(atomicOperation, scannedNonNull);
  nullTree.setApproximateEntriesCount(atomicOperation, exactNullCount);
  approximateNullCount.set(exactNullCount);
  approximateIndexEntriesCount.set(scannedNonNull + exactNullCount);
  ```

- `BTreeSingleValueIndexEngine.buildInitialHistogram(AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java:615–651`. Same shape with one tree; recalibration lines 647–650:
  ```java
  long exactTotal = scannedNonNull + exactNullCount;
  sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
  approximateIndexEntriesCount.set(exactTotal);
  approximateNullCount.set(exactNullCount);
  ```

Only one production caller: `IndexAbstract.buildHistogramAfterFill` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexAbstract.java:390–415` (PSI-verified at the Pre-Flight gate, post-Track-3). The call site wraps `buildInitialHistogram` in `executeInsideAtomicOperation` at lines 397–400, then swallows `IOException` with a warn at lines 401–408 ("Histogram build failure must not fail the index rebuild. The histogram will be built lazily on the next rebalance.").

The recalibration arithmetic for the mixed-mode conversion (per the design doc's mechanism block):
- **Persisted side (both engines)**: keep today's inline writes. MV calls `svTree.setApproximateEntriesCount(op, scannedNonNull)` and `nullTree.setApproximateEntriesCount(op, exactNullCount)`; SV calls `sbTree.setApproximateEntriesCount(op, exactTotal)`. Each write is WAL-tracked through the AOM lifecycle, reverts on rollback, and lands at the absolute target regardless of the pre-rebuild persisted value. The drift-healing property already present in today's code is preserved.
- **In-mem side (both engines)**: route the writes through the new in-mem-only accumulator with `totalDelta = target - currentInMemTotal` and `nullDelta = exactNullCount - currentInMemNull`. MV: `target = scannedNonNull + exactNullCount`. SV: `target = exactTotal = scannedNonNull + exactNullCount`. The deltas are signed and may oppose each other; the new accumulator has no sign-alignment precondition, unlike `accumulateClearOrRecalibrate`.

Hook A's `persistCountDelta` reads `delta.getTotalDelta()` and `delta.getNullDelta()` only. The new fields `inMemAdjustTotal` / `inMemAdjustNull` are not consumed by Hook A — the persisted side is fed by the inline `setApproximateEntriesCount` calls above, not by Hook A on the recalibration path. Hook B's `applyIndexCountDeltas` (at `AbstractStorage.java:2496`, engine-mutator calls at lines 2529–2530) sums `getTotalDelta() + getInMemAdjustTotal()` and `getNullDelta() + getInMemAdjustNull()` and calls `addToApproximate{Entries,Null}Count`. The in-mem `AtomicLong`s therefore advance only post-commit, exactly when the persisted side has landed at the absolute target.

The `approxTotal` / `approxNull` reads at MV:613–614 and the `approxTotal` read at SV:624 (paired with the `exactNullCount = countNulls(atomicOperation)` scan at SV:625) used for histogram bucket sizing are unaffected; they consume the in-memory counter *as-of* the start of `buildInitialHistogram`, before the recalibration delta is recorded.

The deliverable: a new `IndexCountDelta.accumulateInMemRecalibration` static method with two new package-private fields and a sum update in `AbstractStorage.applyIndexCountDeltas`; mixed-mode rewrites of `buildInitialHistogram` on both engines (persisted-side inline writes preserved, in-mem-side writes routed through the new accumulator); a recalibration-rollback regression test; and an existing-test rewrite migrating the in-mem-side assertions in `BTreeEngineHistogramBuildTest` to the holder-inspection pattern.

### Clarifications

Carried forward from the Track Pre-Flight gate (post-Track-3, 2026-05-25) plus the Phase A iteration-1 design call. Captured here so reviewers see them as current-state context.

- **A1 design call resolved: (β) Mixed-mode encoding chosen.** Pure-delta encoding on both sides would transfer pre-existing in-mem-vs-persisted drift to the persisted side instead of healing it. Worked arithmetic: pre-rebuild `currentInMem = persisted + d` makes Hook A's `Δ = -d` land persisted at `target - d` instead of `target`. Today's `setApproximateEntriesCount(op, target)` writes absolute via WAL — persisted lands at `target` and drift heals on every successful recalibration. Track 4 keeps the inline absolute writes on the persisted side and routes only the in-mem `AtomicLong` writes through a new accumulator. Track 3 (`clear()`) stays pure-delta because `Δ = -current` converges both sides to zero regardless of drift.
- **New in-mem-only accumulator added; existing overload untouched.** Track 4 Step 1 adds `IndexCountDelta.accumulateInMemRecalibration(AtomicOperation, int, long, long)` plus two new package-private long fields `inMemAdjustTotal` / `inMemAdjustNull` on the per-engine `IndexCountDelta` record (PSI-verified absent at session start). `accumulateClearOrRecalibrate` (Track 3, commit `5f7f882830`, `IndexCountDelta.java:111–125`) stays unchanged with its `|nullDelta| <= |totalDelta|` + sign-alignment precondition; only `clear()` callers consume it. The original "preflight-add" branch is removed: Track 4 adds a *different* method, not a re-add of the Track 3 overload.
- **Q3 supersession.** The Q3 question (runtime assert relaxation on `accumulateClearOrRecalibrate`) is moot under (β). The new `accumulateInMemRecalibration` method has no sign-alignment or magnitude precondition by design — in-mem-only recalibration deltas are arbitrarily signed because organic drift between `approximateIndexEntriesCount` and `approximateNullCount` (from Track-1 `reportAndClampUnderflow` events, or any pre-existing in-mem skew) can produce sign-opposed deltas. Worked counter-example that would have tripped option (a)'s in-place relaxation: `currentTotal=100, currentNull=10, scannedNonNull=80, exactNullCount=15 → totalDelta=−5, nullDelta=+5` (magnitude-and-sign violation). The new method accepts it without an assert.
- **Mockito recipe option (1) accepted.** The regression test wraps `executeInsideAtomicOperation` test-side so `engine.buildInitialHistogram(op)` runs first (records the in-mem delta and lands the persisted-side `setApproximateEntriesCount` write), then the lambda re-throws `IOException` to trigger rollback. The thrown `IOException` is rewrapped as a `StorageException` (RuntimeException via BaseException) by `AtomicOperationsManager.java:147` / `:174`, so the test catches `StorageException`, not `IOException`. No production-side change is required; the IOException catch at `IndexAbstract.buildHistogramAfterFill:401–408` does not apply because the test invokes AOM directly and bypasses `buildHistogramAfterFill`.
- **Lock posture option (a) accepted.** `buildInitialHistogram` does not transitively hold a per-tree exclusive lock when reading `approximateIndexEntriesCount.get()` / `approximateNullCount.get()` for the recalibration snapshot. PSI confirms `IndexAbstract.buildHistogramAfterFill:400` is the only production caller, invoked post-`fillIndex` post-rebuild, so no concurrent `clearIndex` API path is active under the narrow call graph. Track 4 inherits the Q5-style multi-thread contention test deferral; one combined follow-up YouTrack issue under `YTDB` with the `dev-workflow` tag covers both engines and both `clear()` + `buildInitialHistogram()` seams (no separate ticket).
- **Histogram CHM cache divergence (A3) is out of scope.** `IndexHistogramManager.cache.put` at lines 763, 806, 831 mutates eagerly inside the atomic op and is NOT reverted on rollback. A rolled-back `buildInitialHistogram` therefore leaves the CHM cache at the recalibrated snapshot while the persisted `.ixs` page reverts via WAL. The structural-impossibility claim is scope-bounded to in-mem count counters. Follow-up deferred — separate work, not Track 4 scope.
- **Concurrent-surface review (A4).** The new in-mem-only accumulator is additively composable (`+=` semantics on the per-engine `IndexCountDelta` record) so concurrent recalibrations on the same engine converge to truth on the in-mem side. The persisted side uses inline `setApproximateEntriesCount(target)` which is WAL-tracked and serialized through `executeInsideComponentOperation`'s per-tree lock. `ProductionAllocatorConcurrencyMTTest.java:660–661` already exercises concurrent `buildInitialHistogram` on the same MV engine across worker threads; Track 4 Step 4 reviews this test against the new arithmetic before committing.
- **Snapshot-invariant assert (T5).** Both `buildInitialHistogram` rewrites add `assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal` (with the engine `name` + `id` in the message) before computing deltas, matching Track 3's `clear()` precedent. This catches structural in-mem skew at the read-snapshot site before it propagates into the delta arithmetic.
- **Bifurcated lock posture acknowledged.** Track 2's Invariant 3 holds on the main-commit path (Hook B runs under the per-index lock acquired at `lockIndexes` (AbstractStorage:2255)). On the `clearIndex` API and `buildHistogramAfterFill` paths, no per-index lock is held during Hook B's apply; the in-mem `AtomicLong`'s `addAndGet` additive semantics make ordering harmless. The `implementation-plan.md` Invariant 3 wording is updated in this Phase A round to acknowledge both paths.
- **Q5 multi-thread real-tree contention test gap inherited.** Track 3 Decision Log Q5 deferred to a single follow-up YouTrack issue under `YTDB` with the `dev-workflow` tag, covering both engines and both `clear()` + `buildInitialHistogram()` seams together. Track 4 picks up the second half of the same deferral; no separate ticket should be filed.
- **Line citations refreshed.** All MV/SV `buildInitialHistogram` and `IndexAbstract.buildHistogramAfterFill` line numbers in this track file are post-Track-3 PSI-verified: MV body 604–654, MV persisted-side recalibration calls 650–651, MV in-mem-side writes 652–653 (removed by Step 2), MV bucket-sizing reads 613–614, MV null-tree scan 631–641, MV comment block 643–649; SV body 615–651, SV persisted-side recalibration calls 647–648 (local var + `sbTree.setApproximateEntriesCount`), SV in-mem-side writes 649–650 (removed by Step 3), SV bucket-sizing read 624 + `countNulls` at 625, SV comment block 639–646; `IndexAbstract.buildHistogramAfterFill` 390–415 (`executeInsideAtomicOperation` call at 397–400, `IOException` catch at 401–408). `IndexCountDelta.java` post-Track-3 reference: `accumulateClearOrRecalibrate` at lines 111–125, per-put `accumulate` at lines 60–68.

## Plan of Work

Four logical edits:

1. **Add mixed-mode plumbing to `IndexCountDelta`.** Land a new `accumulateInMemRecalibration(AtomicOperation, int, long, long)` static method alongside the existing `accumulate` (per-put/remove, `IndexCountDelta.java:60–68`) and `accumulateClearOrRecalibrate` (clear long-form, lines 111–125). Add two new package-private long fields on the per-engine `IndexCountDelta` record: `inMemAdjustTotal` and `inMemAdjustNull` (default 0), plus two public getters `getInMemAdjustTotal()` / `getInMemAdjustNull()`. The new method has no sign-alignment or magnitude precondition (recalibration deltas are arbitrarily signed by drift nature). Update `AbstractStorage.applyIndexCountDeltas` (method at line 2496; engine-mutator calls at lines 2529–2530) to sum both accumulators when calling the engine mutators:
   ```java
   btreeEngine.addToApproximateEntriesCount(
       delta.getTotalDelta() + delta.getInMemAdjustTotal());
   btreeEngine.addToApproximateNullCount(
       delta.getNullDelta() + delta.getInMemAdjustNull());
   ```
   Hook A's `persistCountDelta` reads `delta.getTotalDelta()` and `delta.getNullDelta()` only and is unchanged — the in-mem-only adjustments do not feed the persisted side. Trim the existing `accumulateClearOrRecalibrate` Javadoc at `IndexCountDelta.java:78–91` to drop the two `buildInitialHistogram` caller bullets and tighten the precondition justification at lines 99–103 to mention only `clear()` callers; both `buildInitialHistogram` paths route through the new `accumulateInMemRecalibration` method post-Track-4. Unit tests cover the additive composition of per-put deltas with a recalibration delta in the same atomic operation, the rollback discard contract, and the in-mem-only contract (Hook A's `persistCountDelta` is NOT called for in-mem-only deltas).

2. **Rewrite `BTreeMultiValueIndexEngine.buildInitialHistogram` lines 650–653** to mixed-mode encoding. Keep lines 650 and 651 (the two `setApproximateEntriesCount` calls on `svTree` and `nullTree` — WAL-tracked, drift-healing). Replace lines 652 and 653 (the two `AtomicLong.set` calls) with a snapshot-invariant assert and the new in-mem-only accumulator call:
   ```java
   // Persisted-side absolute writes — WAL-tracked, revert on rollback,
   // heal pre-existing in-mem-vs-persisted drift on every successful
   // recalibration.
   svTree.setApproximateEntriesCount(atomicOperation, scannedNonNull);
   nullTree.setApproximateEntriesCount(atomicOperation, exactNullCount);
   // In-mem-side delta — advances only post-commit via Hook B.
   long currentTotal = approximateIndexEntriesCount.get();
   long currentNull  = approximateNullCount.get();
   long targetTotal  = scannedNonNull + exactNullCount;
   assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal
       : name + "[" + id + "]: snapshot invariant violated:"
           + " currentTotal=" + currentTotal + " currentNull=" + currentNull;
   IndexCountDelta.accumulateInMemRecalibration(
       atomicOperation, id, targetTotal - currentTotal, exactNullCount - currentNull);
   ```
   Update the comment block at lines 643–649 to describe the mixed-mode design (persisted-side absolute writes plus in-mem-side delta). The histogram-build code at lines 612–625 and the null-tree scan at lines 631–641 are unchanged.

3. **Rewrite `BTreeSingleValueIndexEngine.buildInitialHistogram` lines 647–650** with the structurally identical change for the single-tree case. Keep lines 647 (local `exactTotal`) and 648 (the `sbTree.setApproximateEntriesCount` call). Replace lines 649 and 650 (the two `AtomicLong.set` calls) with the same snapshot-invariant assert and `accumulateInMemRecalibration` call shape:
   ```java
   long exactTotal = scannedNonNull + exactNullCount;
   sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
   long currentTotal = approximateIndexEntriesCount.get();
   long currentNull  = approximateNullCount.get();
   assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal
       : name + "[" + id + "]: snapshot invariant violated:"
           + " currentTotal=" + currentTotal + " currentNull=" + currentNull;
   IndexCountDelta.accumulateInMemRecalibration(
       atomicOperation, id, exactTotal - currentTotal, exactNullCount - currentNull);
   ```
   Update the parallel comment block at lines 639–646 to point at the mixed-mode design (mirroring Step 2's MV comment update).

4. **Add a recalibration-rollback regression test and rewrite the existing positive assertions.** New test `IndexAbstractBuildHistogramRollbackTest` under `core/src/test/.../index/`:
   - Build an index with known counters (a few thousand entries with a mix of null and non-null keys).
   - Wrap `executeInsideAtomicOperation` test-side so the lambda runs `engine.buildInitialHistogram(op)` (records the in-mem delta and lands the persisted-side `setApproximateEntriesCount` write), then re-throws `IOException`. Catch the rewrapped `StorageException` per `AtomicOperationsManager.java:147` / `:174`.
   - Assertions pin: (a) the in-mem `AtomicLong`s retain pre-recalibration values after the throw, (b) the persisted EP page reverts via WAL on rollback, (c) on a separate successful run both sides land on the post-recalibration target, (d) Hook B's `setApplied()` latch is true on success / false on rollback.
   - Javadoc scopes the assertions to count counters only; CHM cache divergence is named as out-of-scope.

   Rewrite the existing positive assertions in `BTreeEngineHistogramBuildTest.java` that read `assertEquals(target, f.engine.getTotalCount(f.op))` to the holder-inspection pattern matching Track 3's clear-tests (`f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId())`). Under (β), the in-mem getters return the *pre-recalibration* value during the atomic op because Hook B has not yet applied; the persisted-side `verify(...setApproximateEntriesCount(target))` Mockito assertions stay valid because the persisted write still happens inline. Only the in-mem-side `assertEquals(target, f.engine.getTotalCount(f.op))` assertions migrate.

Ordering constraint: this track depends on Track 2's Hook B, which consumes the new `inMemAdjustTotal` / `inMemAdjustNull` fields via `applyIndexCountDeltas`. Track 3's `accumulateClearOrRecalibrate` overload is independently consumed by `clear()` and is unaffected; Track 4 adds a separate method (`accumulateInMemRecalibration`) rather than reusing the Track 3 overload, because the two methods carry different invariants (clear has sign-alignment + magnitude bound; recalibration has neither).

Invariants to preserve: the `approxTotal` read at MV:613 / SV:624, the `approxNull` read at MV:614, and the `exactNullCount = countNulls(atomicOperation)` scan at SV:625 still feed the histogram-bucket sizing in `IndexHistogramManager.buildHistogram`. The null-tree scan logic (MV lines 631–641) is unchanged. Hook A's `persistCountDelta` is not invoked for the recalibration deltas — the persisted side is fed by the inline `setApproximateEntriesCount` writes, not by Hook A on this path.

## Concrete Steps

1. Add mixed-mode plumbing to `IndexCountDelta`: new `accumulateInMemRecalibration(AtomicOperation, int, long, long)` static method (no precondition), two new package-private fields `inMemAdjustTotal` / `inMemAdjustNull` with public getters, and Hook B sum update at `AbstractStorage.applyIndexCountDeltas` (method line 2496; engine-mutator calls lines 2529–2530). Trim the `accumulateClearOrRecalibrate` Javadoc at `IndexCountDelta.java:78–103` to drop the two `buildInitialHistogram` caller bullets and tighten the precondition justification to mention only `clear()` callers. Unit tests cover additive composition, rollback discard, and in-mem-only contract.  — risk: medium (multi-file logic spanning new SPI method, holder field additions, hook wiring; touches Track 2's `applyIndexCountDeltas` consumer)  [ ]
2. Rewrite `BTreeMultiValueIndexEngine.buildInitialHistogram` lines 650–653 to mixed-mode encoding: keep the two `setApproximateEntriesCount` calls on `svTree` and `nullTree` (lines 650–651, WAL-tracked drift-healing), replace the two `AtomicLong.set` calls (lines 652–653) with a snapshot-invariant assert plus `IndexCountDelta.accumulateInMemRecalibration(op, id, targetTotal - currentTotal, exactNullCount - currentNull)`. Update the comment block at lines 643–649 to describe the mixed-mode design.  — risk: high (concurrency: changes the persisted-vs-in-mem semantics on the recalibration path; structurally-impossible-divergence claim depends on this rewrite landing correctly; under the bifurcated lock posture the in-mem advance happens post-commit under Track 2's Hook B)  [ ]
3. Rewrite `BTreeSingleValueIndexEngine.buildInitialHistogram` lines 647–650 with the structurally identical change for the single-tree case: keep lines 647 (`exactTotal` local) and 648 (`sbTree.setApproximateEntriesCount` call), replace lines 649–650 (the two `AtomicLong.set` calls) with the same snapshot-invariant assert plus `accumulateInMemRecalibration` call. Update the parallel comment block at lines 639–646.  — risk: high (concurrency: same surface as Step 2 for the single-value engine; SV `persistCountDelta` already ignores `nullDelta` per Track 1's cross-track signal, so the in-mem-only accumulator is the sole `nullDelta` carrier on this path)  [ ]
4. Add `IndexAbstractBuildHistogramRollbackTest` under `core/src/test/.../index/` and rewrite the existing positive in-mem assertions in `BTreeEngineHistogramBuildTest.java`. New test builds an index with known counters, wraps `executeInsideAtomicOperation` test-side to run `engine.buildInitialHistogram(op)` then re-throw `IOException` (caught as the rewrapped `StorageException` per `AtomicOperationsManager.java:147` / `:174`), and pins: (a) in-mem `AtomicLong`s retain pre-recalibration values after the throw, (b) the persisted EP page reverts via WAL on rollback, (c) on a separate successful run both sides land on the post-recalibration target, (d) Hook B's `setApplied()` latch is true on success / false on rollback. Javadoc scopes the assertions to count counters only; CHM cache divergence is out-of-scope. Migrate the in-mem-side `assertEquals(target, f.engine.getTotalCount(f.op))` assertions in `BTreeEngineHistogramBuildTest` to the holder-inspection pattern (`f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId())`); persisted-side `verify(...setApproximateEntriesCount(target))` Mockito assertions stay.  — risk: medium (test-infrastructure changes plus a new rollback regression test; touches 10+ assertion sites in BTreeEngineHistogramBuildTest; rollback recipe inherited from Track 3 with a fresh Mockito stub seam)  [ ]

## Episodes

## Validation and Acceptance

After Track 4 lands:

- `buildInitialHistogram` on both engines does not mutate the in-memory `AtomicLong` directly. The in-mem side advances through Hook B's `applyIndexCountDeltas` after `commitChanges` returns, which sums `getTotalDelta() + getInMemAdjustTotal()` and `getNullDelta() + getInMemAdjustNull()` and calls `addToApproximate{Entries,Null}Count`.
- The persisted EP-page side keeps today's inline `setApproximateEntriesCount(op, target)` writes; on every successful recalibration the persisted side lands at the absolute target, healing any pre-existing in-mem-vs-persisted drift. On rollback the persisted side reverts via WAL.
- The recalibration-rollback regression test passes: on a forced rollback after `buildInitialHistogram` returns (in-mem delta recorded but not yet applied), the in-mem counters retain pre-recalibration values and the persisted EP page reverts via WAL. On a separate successful run, both sides land on the post-recalibration target.
- The structural-impossibility claim is scope-bounded to the count counters. `IndexHistogramManager.cache.put` at lines 763, 806, 831 mutates eagerly inside the atomic op and is NOT reverted on rollback; histogram CHM cache divergence is named as out-of-scope (deferred follow-up).
- The histogram-build itself (bucket creation, key scanning) is unchanged.
- `IndexAbstract.buildHistogramAfterFill`'s outer `IOException` handler still swallows builder failures at lines 401–408 (PSI-verified at the Pre-Flight gate, post-Track-3).

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` — new `accumulateInMemRecalibration` static method, two new package-private fields `inMemAdjustTotal` / `inMemAdjustNull`, two new public getters.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` — `applyIndexCountDeltas` (method at line 2496) reads both accumulator pairs when calling the engine mutators at lines 2529–2530.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` — `buildInitialHistogram` body, mixed-mode rewrite of lines 650–653 with comment block at 643–649 updated.
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` — `buildInitialHistogram` body, mixed-mode rewrite of lines 647–650 with comment block at 639–646 updated.
- New regression test `IndexAbstractBuildHistogramRollbackTest` under `core/src/test/.../index/`.
- Existing-test rewrite in `core/src/test/.../index/BTreeEngineHistogramBuildTest.java` (in-mem-side assertions migrate to the holder-inspection pattern; persisted-side `verify(...setApproximateEntriesCount...)` assertions stay).

**Out of scope**:
- `IndexHistogramManager.buildHistogram` itself (the histogram-bucket-creation logic).
- The `IOException`-swallow at `IndexAbstract.buildHistogramAfterFill:401–408` (PSI-verified at the Pre-Flight gate, post-Track-3).
- `clear()` conversion (Track 3).
- `IndexCountDelta.accumulateClearOrRecalibrate` is untouched (Track 3 contract preserved); Track 4 adds a separate method rather than re-tuning the existing one.
- Histogram CHM cache divergence at `IndexHistogramManager.java:763, 806, 831` (eager mutation inside the atomic op, not reverted on rollback). Deferred follow-up; named in Validation and Acceptance as out-of-scope.
- Hook A's `persistCountDelta` — unchanged. The in-mem-only adjustments do not feed the persisted side.

**Inter-track dependencies**:
- **Depends on Track 2**: Hook B's `applyIndexCountDeltas` consumes the new `inMemAdjustTotal` / `inMemAdjustNull` fields. Without Track 2's hook wiring, the in-mem delta is dropped at the end of the atomic op and the in-mem side never advances — strictly worse than today.
- **Independent of Track 3**: Track 3 added `accumulateClearOrRecalibrate` (commit `5f7f882830`) for the `clear()` long-form path; Track 4 adds `accumulateInMemRecalibration` as a separate method. The previously-anticipated overload reuse is dropped under (β) because the two methods carry different invariants (clear has sign-alignment + magnitude bound; recalibration has neither).

**Library/function signatures relevant to this track**:
- New: `IndexCountDelta.accumulateInMemRecalibration(AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta)` — in-mem-only recalibration accumulator, no precondition.
- New fields: `IndexCountDelta.inMemAdjustTotal` / `inMemAdjustNull` (package-private long, default 0) with public getters `getInMemAdjustTotal()` / `getInMemAdjustNull()`.
- Modified: `AbstractStorage.applyIndexCountDeltas` body (method at line 2496; engine-mutator calls at lines 2529–2530) — sums both accumulator pairs when calling `addToApproximate{Entries,Null}Count`.
- `BTreeMultiValueIndexEngine.buildInitialHistogram(AtomicOperation)` — rewritten lines 650–653 (mixed-mode).
- `BTreeSingleValueIndexEngine.buildInitialHistogram(AtomicOperation)` — rewritten lines 647–650 (mixed-mode).
- `IndexAbstract.buildHistogramAfterFill` — unchanged caller.
- Existing untouched: `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation, int, long, long)` (Track 3, commit `5f7f882830`, `IndexCountDelta.java:111–125`).
