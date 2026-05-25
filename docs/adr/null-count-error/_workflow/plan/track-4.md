# Track 4: buildInitialHistogram pure-delta encoding

## Purpose / Big Picture
After this track, the recalibration-rollback divergence is structurally impossible on the `buildHistogramAfterFill` path, which is the only production caller of `buildInitialHistogram`. The recalibration target `(scannedNonNull, exactNullCount)` advances through the `IndexCountDelta` accumulator instead of direct writes to the persisted EP pages and in-memory `AtomicLong`s; on rollback both sides stay at their prior approximate values.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Convert `BTreeMultiValueIndexEngine.buildInitialHistogram()` and `BTreeSingleValueIndexEngine.buildInitialHistogram()` to pure-delta encoding: compute the recalibration as `Δ = target - current` and accumulate via the long-form `IndexCountDelta.accumulateClearOrRecalibrate` overload. After this track, the recalibration-rollback divergence is structurally impossible on the `buildHistogramAfterFill` path, which is the only production caller.

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

The recalibration arithmetic for the pure-delta conversion (per the design doc's mechanism block):
- MV: `totalDelta = (scannedNonNull + exactNullCount) - currentTotal`, `nullDelta = exactNullCount - currentNull`.
- SV: `totalDelta = exactTotal - currentTotal`, `nullDelta = exactNullCount - currentNull`.

For MV's `persistCountDelta` split: `nonNullDelta = totalDelta - nullDelta = (scannedNonNull + exactNullCount - currentTotal) - (exactNullCount - currentNull) = scannedNonNull - (currentTotal - currentNull) = scannedNonNull - currentSv`. That advances `svTree` from `currentSv` to `scannedNonNull`. Correct. `nullDelta = exactNullCount - currentNull` advances `nullTree` from `currentNull` to `exactNullCount`. Correct.

For SV's `persistCountDelta` (`nullDelta` ignored): `totalDelta = exactTotal - currentTotal` advances `sbTree` from `currentTotal` to `exactTotal`. Correct.

`applyIndexCountDeltas` then advances in-memory: total from `currentTotal` to `target`, null from `currentNull` to `exactNullCount`. Correct on both engines.

The `approxTotal` / `approxNull` reads at MV:613–614 and the `approxTotal` read at SV:624 (paired with the `exactNullCount = countNulls(atomicOperation)` scan at SV:625) used for histogram bucket sizing are unaffected; they consume the in-memory counter *as-of* the start of buildInitialHistogram, before the recalibration delta is recorded.

The deliverable: two rewritten buildInitialHistogram bodies and a recalibration-rollback regression test.

### Clarifications

Carried forward from the Track Pre-Flight gate (post-Track-3, 2026-05-25). Captured here so Phase A reviews see them as current-state context.

- **Overload already exists.** `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation, int, long, long)` landed in Track 3 commit `5f7f882830` (PSI-verified at `IndexCountDelta.java:70–125`). Track 4 reuses it; the original Plan of Work's preflight-add branch is removed. Step decomposition produces three steps: MV rewrite, SV rewrite, recalibration-rollback regression test.
- **Q3 (runtime assert relaxation) is a Phase A design call.** Track 3's overload carries `assert |nullDelta| <= |totalDelta| && (totalDelta == 0 || nullDelta == 0 || Long.signum(totalDelta) == Long.signum(nullDelta))`. The precondition holds structurally for `clear()` (`currentNull <= currentTotal` ⇒ both deltas equal and negative). For Track 4 recalibration deltas the precondition can fail under organic drift between approximate (in-memory `AtomicLong`) and exact (scanned) counts. Track-1 `reportAndClampUnderflow` events are one source, ordinary drift another. Worked counter-example: `currentTotal=100, currentNull=10, scannedNonNull=100, exactNullCount=5 → totalDelta=+5, nullDelta=−5` (sign-opposed under `-ea`). A second pattern: `currentTotal=100, currentNull=10, scannedNonNull=80, exactNullCount=15 → totalDelta=−5, nullDelta=+5` (magnitude-and-sign violation). Phase A picks one of: (a) relax `accumulateClearOrRecalibrate` precondition (document the new contract: recalibration is a *correction* operation, sign-alignment is no longer load-bearing); (b) introduce a separate `accumulateRecalibration(AtomicOperation, int, long, long)` overload without those preconditions; (c) drop the assert entirely. Carries forward from Track 3 Decision Log Q3 + Surprises row 2 + Step 4 Cross-track signal (2).
- **Mockito recipe needs adaptation.** Track 3's `mgr.resetOnClear` IOException seam (Track 3 Episodes §Step 4) fires while `engine.clear()` is still inside the `executeInsideAtomicOperation` lambda but *before* the accumulate, so the clear-rollback assertions pin pre-clear values retained. For Track 4 the recalibration accumulate sits *after* `mgr.buildHistogram` and the null-tree scan; throwing from `mgr.buildHistogram(...)` short-circuits the accumulate and the delta is never recorded, so the test would then assert nothing more than today's rollback already guarantees. Two adaptation options for the regression test: (1) wrap the `executeInsideAtomicOperation` lambda test-side so `engine.buildInitialHistogram(op)` runs first (records the delta), then the lambda re-throws to trigger rollback; (2) inject failure into Hook A `persistCountDelta` via a test-only seam so commit-side rollback fires after the delta is in the holder. Option (1) is the cleaner choice: no production-code change, only test-side lambda composition. Carries forward from Track 3 Step 4 Cross-track signal (1).
- **Lock posture differs from `clear()`.** `buildInitialHistogram` does NOT call `clearSVTree` / `doClearTree` at the top, so the per-tree exclusive lock is NOT transitively held when `approximateIndexEntriesCount.get()` and `approximateNullCount.get()` are read for the recalibration `currentTotal` / `currentNull`. The MV bucket-sizing read at `BTreeMultiValueIndexEngine.java:613–614` and the SV bucket-sizing read at `BTreeSingleValueIndexEngine.java:624` already happen unlocked today; the pure-delta rewrite reuses them for the recalibration snapshot (or adds matching reads) and inherits the same lock posture. The `CLEAR_CONCURRENCY_CONTRACT_NOTE` constant Track 3 introduced does not extend cleanly. There is no transitive per-tree lock to anchor the snapshot. Phase A picks: (a) accept the same Q5-style deferred follow-up under `buildHistogramAfterFill`'s narrower call-graph (invoked only from `fillIndex` post-rebuild, no concurrent `clearIndex` API path active in normal flows); (b) anchor the snapshot read inside an explicit per-engine lock acquisition (changes the lock contract; high blast radius). Option (a) is the load-bearing precedent.
- **Q5 multi-thread real-tree contention test gap inherited.** Track 3 Decision Log Q5 deferred to a single follow-up YouTrack issue under YTDB with `dev-workflow` tag, covering both engines and both `clear()` + `buildInitialHistogram()` seams together. Track 4 picks up the second half of the same deferral; no separate ticket should be filed.
- **Line citations refreshed.** All MV/SV `buildInitialHistogram` and `IndexAbstract.buildHistogramAfterFill` line numbers in this track file are now post-Track-3 PSI-verified: MV body 604–654, MV recalibration 650–653, MV bucket-sizing reads 613–614, MV null-tree scan 631–641, MV comment block 643–649; SV body 615–651, SV recalibration 647–650, SV bucket-sizing reads 624 + `countNulls` at 625; `IndexAbstract.buildHistogramAfterFill` 390–415 (`executeInsideAtomicOperation` call at 397–400, `IOException` catch at 401–408).

## Plan of Work

Three logical edits:

1. **Rewrite `BTreeMultiValueIndexEngine.buildInitialHistogram` lines 650–653**:
   ```java
   long currentTotal = approximateIndexEntriesCount.get();
   long currentNull  = approximateNullCount.get();
   long targetTotal  = scannedNonNull + exactNullCount;
   IndexCountDelta.accumulateClearOrRecalibrate(
       atomicOperation, id, targetTotal - currentTotal, exactNullCount - currentNull);
   ```
   The four direct writes (two `setApproximateEntriesCount` on the trees, two `AtomicLong.set` on the in-memory counters) are removed. The histogram-build code at lines 612–625 and the null-tree scan at lines 631–641 are unchanged. Update the comment block at lines 643–649 to point at the design doc's pure-delta invariant.

2. **Rewrite `BTreeSingleValueIndexEngine.buildInitialHistogram` lines 647–650** with the structurally identical change for the single-tree case:
   ```java
   long exactTotal   = scannedNonNull + exactNullCount;
   long currentTotal = approximateIndexEntriesCount.get();
   long currentNull  = approximateNullCount.get();
   IndexCountDelta.accumulateClearOrRecalibrate(
       atomicOperation, id, exactTotal - currentTotal, exactNullCount - currentNull);
   ```

3. **Regression test** at `core/src/test/.../index/IndexAbstractBuildHistogramRollbackTest.java`:
   - Set up an index with known counters (e.g., a few thousand entries).
   - Inject a failure in the `executeInsideAtomicOperation` lambda that wraps `buildInitialHistogram` (failure between the recalibration delta accumulate and the WAL commit).
   - Assert: in-memory counters and persisted EP page counters retain pre-recalibration values.
   - On a separate successful run, assert the post-recalibration counters match the exact values from the scan.

Ordering constraint: this track depends on Track 3's `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation, int, long, long)` overload, landed at commit `5f7f882830`. The preflight-add branch is dropped — Phase A does not re-add the method. The two clear/recalibration sites are the only callers of the long-form overload, so there's no risk of a future caller losing the sign precondition guarantee.

Invariants to preserve: the `approxTotal` read at MV:613 / SV:624, the `approxNull` read at MV:614, and the `exactNullCount = countNulls(atomicOperation)` scan at SV:625 still feed the histogram-bucket sizing. The null-tree scan logic (MV lines 631–641) is unchanged.

## Concrete Steps

## Episodes

## Validation and Acceptance

After Track 4 lands:

- `buildInitialHistogram` on both engines does not mutate either the persisted EP page or the in-memory `AtomicLong` directly. Both sides advance through the same persist → commitChanges → apply pipeline as ordinary put/remove deltas.
- The recalibration-rollback regression test passes: on a forced rollback after the delta accumulate, counters remain at pre-recalibration values; on a successful commit, counters land on the post-recalibration values.
- The histogram-build itself (bucket creation, key scanning) is unchanged.
- `IndexAbstract.buildHistogramAfterFill`'s outer `IOException` handler still swallows builder failures at lines 401–408 (PSI-verified at the Pre-Flight gate, post-Track-3).

## Idempotence and Recovery

## Artifacts and Notes

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (buildInitialHistogram body)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (buildInitialHistogram body)
- New regression test under `core/src/test/.../index/`

**Out of scope**:
- `IndexHistogramManager.buildHistogram` itself (the histogram-bucket-creation logic).
- The `IOException`-swallow at `IndexAbstract.buildHistogramAfterFill:401–408` (PSI-verified at the Pre-Flight gate, post-Track-3).
- `clear()` conversion (Track 3).
- Adding the long-form `IndexCountDelta.accumulateClearOrRecalibrate` overload — owned by Track 3 (landed at commit `5f7f882830`).

**Inter-track dependencies**:
- **Depends on Track 2**: `buildHistogramAfterFill` invokes `buildInitialHistogram` through `executeInsideAtomicOperation`. Without Track 2's hook wiring, the accumulated delta is dropped at the end of the atomic op and neither the persisted nor the in-memory side advances — strictly worse than today.
- **Soft dependency on Track 3** (resolved): Track 3 added the long-form `accumulateClearOrRecalibrate` overload at commit `5f7f882830`. Track 4 reuses it; the previously-anticipated order swap is no longer possible.

**Library/function signatures relevant to this track**:
- `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta)` — the overload (from Track 3, commit `5f7f882830`).
- `BTreeMultiValueIndexEngine.buildInitialHistogram(AtomicOperation)` — rewritten lines 650–653.
- `BTreeSingleValueIndexEngine.buildInitialHistogram(AtomicOperation)` — rewritten lines 647–650.
- `IndexAbstract.buildHistogramAfterFill` — unchanged caller.
