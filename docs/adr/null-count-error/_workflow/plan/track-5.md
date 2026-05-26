<!-- workflow-sha: 7412c16fabd0b69f733c9378ceda54ec3056d01d -->
# Track 5: clear() mixed-mode encoding (retrofit)

## Purpose / Big Picture

After this track, `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` share the same encoding shape as `buildInitialHistogram` (Track 4): persisted-side `setApproximateEntriesCount(op, 0)` writes land inline per tree (WAL-tracked through the AOM lifecycle, drift-healing on every successful op, revertable on rollback), and in-memory `AtomicLong` writes route through `IndexCountDelta.accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)` consumed by Hook B post-commit. The drift-amplification accepted regression Track 3 documented in its Decision Log Q2 (pre-existing in-mem > persisted drift makes pure-delta's `addToApproximateEntriesCount(-currentInMem)` land persisted below zero under `-ea` off) closes symmetrically. The WAL invariant target is the in-mem `AtomicLong` write specifically (which Hook B serializes post-commit on both seams), not any persisted-side write inside the atomic op (which is WAL-tracked and revertable).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Retrofit `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` from Track 3's pure-delta encoding to mixed-mode encoding, mirroring Track 4's `buildInitialHistogram` design.

## Progress

- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries

<!-- Continuous-log; empty at Phase 1. Phase A populates if Pre-Flight surfaces cross-cutting facts; Phase B/C promote cross-step findings from episodes. -->

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

<!-- Continuous-log; empty at Phase 1. Phase A populates if decomposition surfaces decisions; Phase B/C promote inline-replan / gate-override / decision-worthy items. -->

## Outcomes & Retrospective

<!-- Continuous-log; empty at Phase 1. Phase A/B/C reviews append timestamped entries; Phase C track completion appends final summary. -->

## Context and Orientation

The two `clear()` bodies (PSI-verified post-Track-4 at inline-replan):

- `BTreeMultiValueIndexEngine.clear(Storage, AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java:283-346`. Today calls `clearSVTree(atomicOperation)` at line 302 (which transitively acquires the per-tree exclusive lock via `tree.remove → executeInsideComponentOperation → acquireExclusiveLockTillOperationComplete` on the populated-tree case), snapshots the in-mem counters at lines 311–312, asserts the snapshot invariant at lines 313–315, calls `indexesSnapshot.clear()` + `nullIndexesSnapshot.clear()` at lines 316–317, records the collapse via `IndexCountDelta.accumulateClearOrRecalibrate(atomicOperation, id, -currentTotal, -currentNull)` at lines 331–332, then resets the histogram cache via `mgr.resetOnClear(atomicOperation)` at line 338 inside a local IOException-to-IndexException wrap try/catch (lines 333–345). The lock-window comment block at lines 284–301 documents the bifurcated-lock-posture concurrency contract; the pure-delta comment block at lines 318–330 explains the encoding choice that Track 5 changes. The MV `clear()` method does NOT declare `throws IOException`; the existing local wrap exists because `mgr.resetOnClear` declares it.

- `BTreeSingleValueIndexEngine.clear(Storage, AtomicOperation)` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java:242-297`. Same shape with one tree. The lock-window comment block sits at lines 244–254, `doClearTree(atomicOperation)` at line 255, the postcondition assert at lines 261–263, the snapshot reads at lines 265–266, the snapshot-invariant assert at lines 267–269, `indexesSnapshot.clear()` at line 270, the pure-delta comment block at lines 271–285, and `IndexCountDelta.accumulateClearOrRecalibrate(atomicOperation, id, -currentTotal, -currentNull)` at lines 286–287, with `mgr.resetOnClear(atomicOperation)` at line 290. The method-level try at line 243 wraps the entire body; the catch at lines 292–296 converts `IOException` to `IndexException`. SV `persistCountDelta` ignores `nullDelta` because the single tree stores nulls and non-nulls together; the persisted side moves by `totalDelta` alone in the pure-delta encoding.

Callers of `clear()` flow through `AbstractStorage.clearIndex` (the API path) and the main-commit path's tombstone resolution. Both paths run under `executeInsideAtomicOperation`. Hook B's `applyIndexCountDeltas` (`core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java:2496`, engine-mutator calls at lines 2538–2541) sums `getTotalDelta() + getInMemAdjustTotal()` and `getNullDelta() + getInMemAdjustNull()` before calling `addToApproximate{Entries,Null}Count`. Track 5 routes `clear()`'s in-mem-side write through the second accumulator pair (Track 4's `inMemAdjustTotal` / `inMemAdjustNull` fields at `IndexCountDelta.java:53-60`, method at `IndexCountDelta.java:191-196`) instead of the first.

The recalibration arithmetic for the mixed-mode conversion:

- **Persisted side (both engines)**: inline `setApproximateEntriesCount(op, 0)` writes per tree. MV calls `svTree.setApproximateEntriesCount(op, 0)` and `nullTree.setApproximateEntriesCount(op, 0)`; SV calls `sbTree.setApproximateEntriesCount(op, 0)`. Each write is WAL-tracked through the AOM lifecycle, reverts on rollback, and lands at zero regardless of the pre-`clear()` persisted value. The drift-amplification accepted regression closes because the persisted side is no longer fed by a delta from a possibly-drifted in-mem snapshot.

- **In-mem side (both engines)**: route the writes through `IndexCountDelta.accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)`. The deltas advance `inMemAdjustTotal` / `inMemAdjustNull`, which Hook B sums with `getTotalDelta()` / `getNullDelta()` when calling the engine mutators. On rollback the holder is dropped; the in-mem `AtomicLong`s stay at their pre-`clear()` values. On successful commit Hook B advances the in-mem counters by `-currentTotal` / `-currentNull`, matching the persisted-side zero.

After Track 5 lands, `IndexCountDelta.accumulateClearOrRecalibrate` has zero production callers (current callers: MV `clear()` at MV:331-332, SV `clear()` at SV:286-287; both go away). The method body stays at `IndexCountDelta.java:140-154` (post-Step-1 of Track 4) and the Javadoc at lines 98-139 gets a one-line update in Track 5 Step 3 to acknowledge the awaiting-cleanup state. Deprecation, removal, and the `IndexCountDeltaHolderTest` fixture migration (15+ test sites at `core/src/test/.../index/engine/IndexCountDeltaHolderTest.java`) are deferred to a follow-up YouTrack issue under `YTDB` with the `dev-workflow` tag — Track 5 keeps test churn minimal.

`IndexCountDelta.accumulateInMemRecalibration` (Track 4) accepts arbitrarily-signed deltas; Track 5's deltas are `(-currentTotal, -currentNull)` with `|nullDelta| <= |totalDelta|` and same-sign (both negative, given the engine snapshot invariant). No precondition rewrites are needed on the accumulator side. Hook A's `persistCountDelta` reads only `getTotalDelta()` / `getNullDelta()`; the in-mem-only fields are not consumed on the persist side. Track 5's `clear()` writes nothing to `getTotalDelta()` / `getNullDelta()` (the persisted side is fed by the inline `setApproximateEntriesCount` calls), so Hook A is a no-op for the `clear()` seam post-Track-5.

The deliverable: MV `clear()` rewrite, SV `clear()` rewrite, and an update of the two existing rollback test suites (`BTreeMultiValueIndexEngineClearRollbackTest`, `BTreeSingleValueIndexEngineClearRollbackTest`) to expect the new mixed-mode shape on the holder row plus inline `setApproximateEntriesCount(op, 0)` persisted-side verifies. The MV body gains a new local IOException-to-IndexException wrap try/catch around the new `setApproximateEntriesCount` calls (mirroring the existing histogram-reset wrap at MV:333-345) because MV `clear()` does not declare `throws IOException`. The SV body extends the existing method-level try/catch (lines 243-296) over the new calls; no new try/catch is added.

## Plan of Work

Three logical edits:

1. **Rewrite `BTreeMultiValueIndexEngine.clear` lines 318–332 (pre-Track-5)** to mixed-mode encoding. Keep `clearSVTree(atomicOperation)` at line 302, the postcondition asserts at lines 304–309, the in-mem snapshot reads at lines 311–312, the snapshot-invariant assert at lines 313–315, the `indexesSnapshot.clear()` / `nullIndexesSnapshot.clear()` calls at lines 316–317, and the histogram-reset block at lines 333–345 (which keeps the pre-existing `mgr.resetOnClear` wrap). Replace the `accumulateClearOrRecalibrate` call at lines 331–332 and the pure-delta comment block at lines 318–330 with a new local try/catch around the per-tree absolute writes plus the in-mem-only accumulator call. Replacement body shape:
   ```java
   // Persisted-side absolute writes per tree: WAL-tracked through the AOM
   // lifecycle, revert on rollback, land at zero regardless of any
   // pre-existing in-mem-vs-persisted drift. Heal the drift Track 3's
   // pure-delta encoding accepted as an open window.
   //
   // Local try-catch needed: unlike BTreeSingleValueIndexEngine.clear(),
   // this method's outer scope does not catch IOException.
   try {
     svTree.setApproximateEntriesCount(atomicOperation, 0);
     nullTree.setApproximateEntriesCount(atomicOperation, 0);
   } catch (IOException e) {
     throw BaseException.wrapException(
         new IndexException(storage.getName(),
             "Error during persisted-count reset on clear of index " + name),
         e, storage.getName());
   }
   // In-mem-side delta: advances only post-commit via Hook B's apply on the
   // same engine. Same accumulator Track 4 uses for buildInitialHistogram.
   // Additive on the in-mem side under the bifurcated lock posture: the
   // engine-level AtomicLong's addAndGet semantics make concurrent
   // recalibrations on the same engine converge to truth post-commit.
   IndexCountDelta.accumulateInMemRecalibration(
       atomicOperation, id, -currentTotal, -currentNull);
   ```
   Update the lock-window comment block at lines 284–301 if any wording calls out the pure-delta encoding choice (sweep on review; the comment focuses on the snapshot-read race and is largely unaffected). The histogram-reset try/catch at lines 333–345 is untouched. The MV `clear()` method signature does NOT gain `throws IOException`; the new wrap absorbs the new exposure locally.

2. **Rewrite `BTreeSingleValueIndexEngine.clear` lines 271–287 (pre-Track-5)** with the structurally identical change for the single-tree case. Keep the outer `try` at line 243, the lock-window comment block at lines 244–254, `doClearTree(atomicOperation)` at line 255, the postcondition assert at lines 261–263, the snapshot reads at lines 265–266, the snapshot-invariant assert at lines 267–269, `indexesSnapshot.clear()` at line 270, the `mgr.resetOnClear` call at line 290, and the method-level catch at lines 292–296 (which already converts `IOException` to `IndexException`). Replace the `accumulateClearOrRecalibrate` call at lines 286–287 and the pure-delta comment block at lines 271–285 with the SV-shaped inline write plus accumulator call:
   ```java
   // Persisted-side absolute write: WAL-tracked through the AOM lifecycle,
   // reverts on rollback, lands at zero regardless of any pre-existing
   // in-mem-vs-persisted drift. The single-tree engine's persistCountDelta
   // ignored nullDelta in the prior pure-delta encoding; the absolute write
   // replaces that path entirely on the persisted side. Inside the existing
   // method-level try, so IOException flows to the catch at lines 292–296.
   sbTree.setApproximateEntriesCount(atomicOperation, 0);
   // In-mem-side delta: advances only post-commit via Hook B's apply on the
   // same engine. The new accumulator is the sole carrier of the null-counter
   // delta on this engine (Track 4's mixed-mode plumbing covers the
   // null counter on the in-mem side; Hook A's persistCountDelta still
   // ignores nullDelta for SV, but Hook A is a no-op for this seam now
   // because clear() writes nothing to getTotalDelta()/getNullDelta()).
   IndexCountDelta.accumulateInMemRecalibration(
       atomicOperation, id, -currentTotal, -currentNull);
   ```
   The catch block at lines 292–296 stays — `setApproximateEntriesCount` may throw `IOException`, and the existing wrap handles it without changes. The lock-window posture comment at lines 244–254 is unchanged.

3. **Update the existing rollback test suites and the `accumulateClearOrRecalibrate` Javadoc.** The two suites are `BTreeMultiValueIndexEngineClearRollbackTest` (`core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearRollbackTest.java:62`, ~9 cases per Track 3 Phase C) and `BTreeSingleValueIndexEngineClearRollbackTest` (`:67`, ~7 cases). Both carry assertions matching Track 3's pure-delta posture. Migration:

   - Persisted-side `verify(svTree, never()).setApproximateEntriesCount(...)` / `verify(sbTree, never()).setApproximateEntriesCount(...)` pins flip to `verify(svTree, times(1)).setApproximateEntriesCount(eq(op), eq(0L))` (MV: two trees × `times(1)`; SV: one tree × `times(1)`). The `times(1)` precision matches Track 4's iter-1 finding F9 pattern, not the looser default `verify(...).setApproximateEntriesCount(eq(op), eq(0L))`.
   - Holder-row assertions migrate from `delta.getTotalDelta() == -currentTotal && delta.getNullDelta() == -currentNull` (Track 3 pure-delta) to `delta.getInMemAdjustTotal() == -currentTotal && delta.getInMemAdjustNull() == -currentNull` (Track 5 mixed-mode in-mem-side) plus `delta.getTotalDelta() == 0 && delta.getNullDelta() == 0` zero-pins on the per-put fields. The four-field shape mirrors Track 4's iter-1 holder-inspection pattern.
   - Mirror Track 4's negative-pin convention: assert the in-mem `AtomicLong`s stay at pre-state during the atomic op (Hook B advances them post-commit, not inline). Mockito stubs of `Storage.applyIndexCountDeltas` (or a real-storage variant if the existing tests already use one) verify that `addToApproximateEntriesCount(-currentTotal)` and `addToApproximateNullCount(-currentNull)` only fire on the success path, not on rollback.
   - The rollback-discard tests' Mockito `verifyNoMoreInteractions` blocks (Track 4 iter-1 finding F1 precedent) stay; the assertion list updates to the four-field holder shape plus the per-tree persisted-side verify.

   Update the `accumulateClearOrRecalibrate` Javadoc at `IndexCountDelta.java:98–139` (post-Step-1 of Track 4): the "two production callers" list at lines 109–114 becomes historical — Track 5 retrofitted both callers to `accumulateInMemRecalibration`. Add a one-line note pointing at the follow-up YouTrack issue (post-merge; the issue id is not yet allocated, so the note names the work in prose). Do not annotate with `@Deprecated`, do not remove the method body, do not touch `IndexCountDeltaHolderTest` fixture references (15+ sites at lines 327, 345, 363, 382, 405, 407, 425, 426, 449, 468, 474, 545). The cleanup is a separate follow-up; this track keeps test churn minimal.

Ordering constraint: Steps 1 and 2 are independent at the engine level (no inter-engine dependency); Step 3 depends on both engines landing because the test suites pin per-engine behaviour. Recommended order: Step 1 (MV) → Step 2 (SV) → Step 3 (tests + Javadoc) so the test rewrites happen once and pin the final shape across both engines.

Invariants to preserve: the lock-window posture comments at MV:284–301 and SV:244–254 stay as-is. The bifurcated-lock-posture concurrency contract that Track 3 documented applies symmetrically to Track 5's persisted-side `setApproximateEntriesCount` writes (which land per tree under `executeInsideComponentOperation`'s per-tree exclusive lock). The in-mem-side delta is additively composable, so concurrent recalibrations on the same engine converge to truth post-commit. The Q5 multi-thread real-tree contention test gap inherited from Tracks 3 and 4 stays open under the single combined follow-up YouTrack issue Track 3 named (one ticket covers both engines and both `clear()` + `buildInitialHistogram()` seams).

## Concrete Steps

<!-- Phase A populates this section with a thin numbered roster: one entry per step with description, `risk:` tag, and a status checkbox. -->

## Episodes

<!-- Continuous-log, workflow-specific sibling. Phase B sub-step 7 appends one block per completed step, identified by step number + commit SHA. -->

## Validation and Acceptance

Track-level acceptance criteria:

- After a successful `clear()` invocation on a populated MV engine, persisted side reads `approximateEntriesCount == 0` on both `svTree` and `nullTree` EP pages; in-mem `approximateIndexEntriesCount == 0` and `approximateNullCount == 0`.
- After a successful `clear()` invocation on a populated SV engine, persisted side reads `approximateEntriesCount == 0` on `sbTree`; in-mem `approximateIndexEntriesCount == 0` and `approximateNullCount == 0`.
- On rollback of the atomic op containing `clear()` (both engines), in-mem `AtomicLong`s retain their pre-`clear()` values; persisted side reverts via WAL to the pre-`clear()` value. The bug pattern observed in `Pre_Tests_Test_REST_2026.2.51599.log` (in-mem advance without WAL commit) is structurally impossible on the `clear()` seam post-Track-5.
- Pre-existing in-mem-vs-persisted drift (Track-1 underflow-clamp event, residual Track-3 drift-amplification window) heals to zero on the persisted side on every successful `clear()` invocation. The drift-amplification accepted regression Track 3 documented closes symmetrically.
- `IndexCountDelta.accumulateClearOrRecalibrate` retains its current body and tests; the production caller count drops to zero. `IndexCountDeltaHolderTest` exercises the method's precondition contract directly and stays green without changes.

Per-step EARS/Gherkin acceptance lines are Phase A placeholders.

<!-- Reserved for Move 3 — per-step EARS/Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery

<!-- Phase A populates: per-step recovery paths. Each step's recovery posture (revert + retry / commit-as-is / split / escalate) lands here once decomposition runs. -->

## Artifacts and Notes

<!-- Continuous-log (rare), cross-step content only per D11. Per-step content lives in `## Episodes` above. -->

## Interfaces and Dependencies

**In-scope files:**

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (modified — `clear()` body at lines 283–346; the recalibration call at lines 331–332 and the pure-delta comment block at lines 318–330 become a mixed-mode block; new local IOException wrap around the persisted-side writes)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (modified — `clear()` body at lines 242–297; the recalibration call at lines 286–287 and the pure-delta comment block at lines 271–285 become a mixed-mode block; existing method-level try/catch absorbs the new persisted-side write exposure)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` (Javadoc-only modification — `accumulateClearOrRecalibrate` Javadoc at lines 98–139 gets the awaiting-cleanup note; method body at lines 140–154 stays unchanged)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearRollbackTest.java` (modified — ~9 cases; holder-row migration plus per-tree persisted-side verify pins)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngineClearRollbackTest.java` (modified — ~7 cases; holder-row migration plus persisted-side verify pin)

**Out-of-scope:**

- `IndexCountDelta.accumulateClearOrRecalibrate` removal / `@Deprecated` annotation / `IndexCountDeltaHolderTest` fixture migration — deferred to a follow-up YouTrack issue under `YTDB` with the `dev-workflow` tag.
- Multi-thread real-tree contention test against `ReentrantReadWriteLock` (Q5 gap, inherited from Tracks 3 and 4) — deferred to the same single combined follow-up YouTrack issue Track 3 named.
- `IndexHistogramManager` cache divergence on rollback (CHM mutations not reverted) — out-of-scope per Track 4's existing deferral.
- `IndexAbstract.buildHistogramAfterFill:401-408` `IOException` catch widening — out-of-scope per Track 4's existing deferral.
- Lock-window posture comment rewording at MV:284–301 and SV:244–254 — the snapshot-read race is inherited unchanged from Track 3 (the snapshot reads still happen at the same site under the same transitive per-tree lock); the comments stay verbatim.
- `design.md` sync to reflect mixed-mode-on-both-seams — deferred to Phase 4 `design-final.md`, which is the canonical "as-built" artifact.

**Inter-track dependencies:**

- Track 2's Hook B sum (`getTotalDelta() + getInMemAdjustTotal()` and the null mirror at `AbstractStorage.java:2538-2541`) carries Track 5's in-mem-side delta. No new wiring; Track 4's accumulator pair is the seam.
- Track 3's per-tree-lock snapshot read at the top of `clear()`, the bifurcated-lock-posture comments at MV:284–301 and SV:244–254, and the snapshot-invariant assert at MV:313-315 and SV:267-269 are kept verbatim.
- Track 4's `accumulateInMemRecalibration` accumulator at `IndexCountDelta.java:191-196` and the two package-private fields `inMemAdjustTotal` / `inMemAdjustNull` at `IndexCountDelta.java:53-60` are reused. No API change to the accumulator.
- Downstream: none. Track 5 is the last work track. Phase 4 (`design-final.md`, `adr.md`) consumes Track 5's episodes alongside Tracks 1–4 and will absorb the historical reframing of D1's "pure-delta for clear()" decision into the mixed-mode-on-both-seams narrative.

**Library signatures relevant to this track:**

- `IndexCountDelta.accumulateInMemRecalibration(AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta)` at `IndexCountDelta.java:191-196` (Track 4) — accepts arbitrarily-signed deltas; advances `inMemAdjustTotal` / `inMemAdjustNull`. No precondition.
- `AbstractStorage.applyIndexCountDeltas(AtomicOperation atomicOperation)` at `AbstractStorage.java:2496` (Track 4) — sums `getTotalDelta() + getInMemAdjustTotal()` and `getNullDelta() + getInMemAdjustNull()` at lines 2538-2541 before calling the engine mutators.
- `CellBTreeSingleValue.setApproximateEntriesCount(AtomicOperation atomicOperation, long value)` — WAL-tracked through `executeInsideComponentOperation`, reverts on rollback. Used by Track 4's `buildInitialHistogram` mixed-mode encoding (MV at `BTreeMultiValueIndexEngine.java:670-671`; SV at `BTreeSingleValueIndexEngine.java:673`) and by Track 5 on the `clear()` seam.
- `IndexCountDelta.accumulateClearOrRecalibrate(AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta)` at `IndexCountDelta.java:140-154` (Track 3) — production-callerless after Track 5; method body stays in place pending follow-up cleanup. Track 5 Step 3 updates only the Javadoc at lines 98-139.
- `BaseException.wrapException(IndexException, IOException, String)` — pre-existing exception wrap pattern used by MV's histogram-reset wrap at MV:339-344 and SV's method-level catch at SV:292-296; reused on the new MV wrap for the persisted-side writes.

## Base commit

<!-- Phase B writes the SHA of HEAD at session start. -->
