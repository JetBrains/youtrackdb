<!-- workflow-sha: 7412c16fabd0b69f733c9378ceda54ec3056d01d -->
# Track 5: clear() mixed-mode encoding (retrofit)

## Purpose / Big Picture

After this track, `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` share the same encoding shape as `buildInitialHistogram` (Track 4): persisted-side `setApproximateEntriesCount(op, 0)` writes land inline per tree (WAL-tracked through the AOM lifecycle, drift-healing on every successful op, revertable on rollback), and in-memory `AtomicLong` writes route through `IndexCountDelta.accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)` consumed by Hook B post-commit. The drift-amplification accepted regression Track 3 documented in its Decision Log Q2 (pre-existing in-mem > persisted drift makes pure-delta's `addToApproximateEntriesCount(-currentInMem)` land persisted below zero under `-ea` off) closes symmetrically. The WAL invariant target is the in-mem `AtomicLong` write specifically (which Hook B serializes post-commit on both seams), not any persisted-side write inside the atomic op (which is WAL-tracked and revertable).

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Retrofit `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` from Track 3's pure-delta encoding to mixed-mode encoding, mirroring Track 4's `buildInitialHistogram` design.

## Progress

- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion
- [x] 2026-05-26T08:25Z [ctx=safe] Review + decomposition complete

## Surprises & Discoveries

<!-- Continuous-log; empty at Phase 1. Phase A populates if Pre-Flight surfaces cross-cutting facts; Phase B/C promote cross-step findings from episodes. -->

## Decision Log

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

<!-- Continuous-log; empty at Phase 1. Phase A populates if decomposition surfaces decisions; Phase B/C promote inline-replan / gate-override / decision-worthy items. -->

## Outcomes & Retrospective

<!-- Continuous-log; empty at Phase 1. Phase A/B/C reviews append timestamped entries; Phase C track completion appends final summary. -->

- [x] Technical: PASS at iteration 2 (6 actionable findings, 5 accepted into plan, 1 rejected as spurious). Iter-1 surfaced T1 (blocker — unreachable IOException wrap on MV `clear()`; `setApproximateEntriesCount` declares no checked exception), T2 (blocker — Step 3's Mockito-driven test migration described files that don't carry that shape; user picked Option A — add per-engine pin tests), T3 (should-fix — citation drifts; PSI re-verification showed reviewer hallucinated line numbers, all plan citations correct), T4 (suggestion — SV try/catch widening was vacuously true), T5 (should-fix — confirmed `persistCountDelta` short-circuits on `(0, 0)` so Hook A no-op claim holds without plan change), T6 (suggestion — added race-blast-radius commentary to Step 1). Iter-2 gate-check VERIFIED T1/T2/T4/T5/T6, REJECTED T3 (orchestrator's SPURIOUS classification confirmed), 0 STILL OPEN / 0 REGRESSION / 0 new findings.

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

The deliverable: MV `clear()` rewrite, SV `clear()` rewrite, two new Mockito-driven pin tests (`BTreeMultiValueIndexEngineClearMixedModeTest`, `BTreeSingleValueIndexEngineClearMixedModeTest`) mirroring Track 4 iter-1's holder-row + per-tree-verify + negative-pin patterns, Javadoc-only reframings on the three existing tests (`BTreeMultiValueIndexEngineClearRollbackTest`, `BTreeSingleValueIndexEngineClearRollbackTest`, `ClearIndexApiRollbackTest`) that today reference the pure-delta encoding in prose, and a one-line awaiting-cleanup note on the `accumulateClearOrRecalibrate` Javadoc. No new try/catch on either engine: `BTree.setApproximateEntriesCount` declares no checked exception (the body wraps the EP-page write in `executeInsideComponentOperation`, which absorbs IOException internally), so the MV body inlines the two writes directly and the SV body's existing method-level try/catch (lines 243–296) continues to cover `mgr.resetOnClear` unchanged.

## Plan of Work

Three logical edits:

1. **Rewrite `BTreeMultiValueIndexEngine.clear` lines 318–332 (pre-Track-5)** to mixed-mode encoding. Keep `clearSVTree(atomicOperation)` at line 302, the postcondition asserts at lines 304–309, the in-mem snapshot reads at lines 311–312, the snapshot-invariant assert at lines 313–315, the `indexesSnapshot.clear()` / `nullIndexesSnapshot.clear()` calls at lines 316–317, and the histogram-reset block at lines 333–345 (which keeps the pre-existing `mgr.resetOnClear` wrap). Replace the `accumulateClearOrRecalibrate` call at lines 331–332 and the pure-delta comment block at lines 318–330 with two inline per-tree absolute writes plus the in-mem-only accumulator call. Replacement body shape:
   ```java
   // Persisted-side absolute writes per tree: WAL-tracked through the AOM
   // lifecycle, revert on rollback, land at zero regardless of any
   // pre-existing in-mem-vs-persisted drift. Heal the drift Track 3's
   // pure-delta encoding accepted as an open window.
   //
   // No new try/catch: setApproximateEntriesCount declares no checked
   // exception (BTree.setApproximateEntriesCount and the CellBTreeSingleValue
   // interface both have empty throws clauses; the body wraps the EP-page
   // write in executeInsideComponentOperation, which absorbs IOException
   // internally). The histogram-reset try/catch at lines 333–345 still
   // covers mgr.resetOnClear, the only IOException source on this body.
   svTree.setApproximateEntriesCount(atomicOperation, 0);
   nullTree.setApproximateEntriesCount(atomicOperation, 0);
   // In-mem-side delta: advances only post-commit via Hook B's apply on the
   // same engine. Same accumulator Track 4 uses for buildInitialHistogram.
   // Additive on the in-mem side under the bifurcated lock posture: the
   // engine-level AtomicLong's addAndGet semantics make concurrent
   // recalibrations on the same engine converge to truth post-commit.
   //
   // Race posture vs Track 3 pure-delta. Mixed-mode narrows the snapshot-
   // read race the bifurcated-lock-posture comment block documents: the
   // persisted side is now an absolute zero write (immune to a wrong
   // (currentTotal, currentNull) snapshot); only the in-mem side keeps the
   // race window, and the consequence is bounded the same way Track 3
   // documented (next buildInitialHistogram self-heals).
   IndexCountDelta.accumulateInMemRecalibration(
       atomicOperation, id, -currentTotal, -currentNull);
   ```
   Update the lock-window comment block at lines 284–301 if any wording calls out the pure-delta encoding choice (sweep on review; the comment focuses on the snapshot-read race and is largely unaffected — the narrowed-race observation above is a Step 1 comment, not a comment-block edit). The histogram-reset try/catch at lines 333–345 is untouched. The MV `clear()` method signature does NOT gain `throws IOException`; no new exposure to absorb.

2. **Rewrite `BTreeSingleValueIndexEngine.clear` lines 271–287 (pre-Track-5)** with the structurally identical change for the single-tree case. Keep the outer `try` at line 243, the lock-window comment block at lines 244–254, `doClearTree(atomicOperation)` at line 255, the postcondition assert at lines 261–263, the snapshot reads at lines 265–266, the snapshot-invariant assert at lines 267–269, `indexesSnapshot.clear()` at line 270, the `mgr.resetOnClear` call at line 290, and the method-level catch at lines 292–296 (which converts `IOException` from `mgr.resetOnClear` to `IndexException`). Replace the `accumulateClearOrRecalibrate` call at lines 286–287 and the pure-delta comment block at lines 271–285 with the SV-shaped inline write plus accumulator call:
   ```java
   // Persisted-side absolute write: WAL-tracked through the AOM lifecycle,
   // reverts on rollback, lands at zero regardless of any pre-existing
   // in-mem-vs-persisted drift. The single-tree engine's persistCountDelta
   // ignored nullDelta in the prior pure-delta encoding; the absolute write
   // replaces that path entirely on the persisted side. setApproximateEntriesCount
   // declares no checked exception (executeInsideComponentOperation absorbs
   // any IOException internally), so the surrounding method-level try/catch
   // is not load-bearing for this call — it stays purely to cover
   // mgr.resetOnClear below.
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
   The catch block at lines 292–296 stays as-is — `mgr.resetOnClear` declares `throws IOException`, and the existing wrap continues to handle it. The new `setApproximateEntriesCount` call sits inside the same try purely for code locality, not because it adds new checked-exception exposure. The lock-window posture comment at lines 244–254 is unchanged.

3. **Add per-engine mixed-mode pin tests, update the existing rollback tests' Javadocs to mixed-mode framing, and update the `accumulateClearOrRecalibrate` Javadoc.** The current test landscape (PSI-verified at Phase A iter-1):

   - `BTreeMultiValueIndexEngineClearRollbackTest` (`core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearRollbackTest.java`, 1 `@Test` method, 252 lines) is a DISK-mode integration test where the failing transaction pushes a `RecordSerializationOperation` that throws inside `executeOperations` — `engine.clear()` never runs. Zero `verify(...)` calls; the contract pinned is "cleared-flag rollback survival" (post-rollback in-mem and persisted reads stay at preparatory values).
   - `BTreeSingleValueIndexEngineClearRollbackTest` (`:BTreeSingleValueIndexEngineClearRollbackTest.java`, 1 `@Test` method, 257 lines) is the SV mirror of the same shape.
   - `ClearIndexApiRollbackTest` (`core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/ClearIndexApiRollbackTest.java`, 1 `@Test` method) is a MEMORY-mode standalone-atomic-op test where `engine.clear()` DOES run via the `clearIndex` API path; a throwing `IndexHistogramManager.resetOnClear` stub triggers rollback from inside `clear()`. The post-rollback in-mem counter contract is pinned.

   Step 3's scope is three sub-edits:

   - **3a. Add per-engine mixed-mode pin tests.** Create two new Mockito-driven test classes mirroring Track 4 iter-1's holder-inspection + per-tree-verify + negative-pin patterns:

     - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearMixedModeTest.java`. Asserts:
       - Per-tree persisted-side verify: `verify(svTree, times(1)).setApproximateEntriesCount(eq(op), eq(0L))` and `verify(nullTree, times(1)).setApproximateEntriesCount(eq(op), eq(0L))`. The `times(1)` precision matches Track 4 iter-1 finding F9.
       - Holder-row inspection on the post-`clear()`-pre-commit holder state: `delta.getInMemAdjustTotal() == -currentTotal && delta.getInMemAdjustNull() == -currentNull` plus `delta.getTotalDelta() == 0 && delta.getNullDelta() == 0`. Four-field shape mirrors Track 4 iter-1 holder-inspection.
       - Negative pin: in-mem `AtomicLong`s (`getTotalCount` / `getNullCount` engine accessors) stay at pre-`clear()` values DURING the atomic op (read between `clear()` returning and Hook B's apply firing). Hook B advances them post-commit, not inline. Mirrors Track 4 iter-1's "not mutated inline by `buildInitialHistogram`" pattern.
       - `verifyNoMoreInteractions` on the per-tree Mockito stubs after the four pinned calls (mirrors Track 4 iter-1 finding F1).

     - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngineClearMixedModeTest.java`. SV mirror with single-tree shape: `verify(sbTree, times(1)).setApproximateEntriesCount(eq(op), eq(0L))`; same holder-row + negative-pin + `verifyNoMoreInteractions` set.

     Both classes use Mockito stubs for the per-tree BTree fields (reflective field injection mirroring `ClearIndexApiRollbackTest`'s `swapHistogramManager` pattern at lines 203–210; the field names are `svTree`/`nullTree` for MV and `sbTree` for SV). Setup mirrors `ClearIndexApiRollbackTest` for the engine + storage wiring (MEMORY DB, preparatory commit of `(3 non-null + 1 null)` for MV / `(4 entries)` for SV, snapshot of pre-`clear()` counters via engine accessors). Cumulative new test surface across both classes: ~250-400 lines.

   - **3b. Update the three existing tests' Javadocs to mixed-mode framing.** No code changes — only Javadoc rewording so future readers don't see "pure-delta-encoded" prose alongside Track 5's mixed-mode encoding:

     - `BTreeMultiValueIndexEngineClearRollbackTest.java` lines 23 ("Regression for the pure-delta-encoded `clear()`...") and 197–200 ("A regression where the persist hook landed the -currentTotal / -currentNull delta on the rollback path..."). Reframe to "mixed-mode-encoded `clear()`" and reframe the persisted-side regression hook to "where the persist hook landed the absolute zero write on the rollback path".
     - `BTreeSingleValueIndexEngineClearRollbackTest.java` — equivalent edits at the matching SV positions.
     - `ClearIndexApiRollbackTest.java` lines 26–56 (the class-level Javadoc). Reword "Under the pure-delta encoding on both ... the `clear()` body records a negative delta on the atomic op rather than writing the in-memory `AtomicLong` counters directly" to "Under the mixed-mode encoding on both ... the `clear()` body lands the persisted-side absolute zero write inline via `setApproximateEntriesCount(op, 0)` and routes the in-memory `AtomicLong` write through `IndexCountDelta.accumulateInMemRecalibration` consumed by Hook B post-commit."

   - **3c. Update the `accumulateClearOrRecalibrate` Javadoc** at `IndexCountDelta.java:98–139` (the Javadoc block immediately preceding the method declaration at line 140). The "two production callers" list becomes historical — Track 5 retrofitted both callers to `accumulateInMemRecalibration`. Add a one-line note pointing at the follow-up YouTrack issue (post-merge; the issue id is not yet allocated, so the note names the work in prose). Do not annotate with `@Deprecated`, do not remove the method body, do not touch `IndexCountDeltaHolderTest` fixture references (12+ sites — actual count from PSI find-usages, not 15+ as the earlier draft stated). The cleanup is a separate follow-up; this track keeps existing-test churn minimal while adding the new per-engine mixed-mode pin coverage.

Ordering constraint: Steps 1 and 2 are independent at the engine level (no inter-engine dependency); Step 3 depends on both engines landing because the new pin tests verify per-engine post-`clear()` behavior. Recommended order: Step 1 (MV) → Step 2 (SV) → Step 3 (new pin tests + existing-test Javadoc updates + `IndexCountDelta` Javadoc) so the pin tests are authored once against the final production shape and the existing-test Javadoc rewording happens after the encoding change is in place.

Invariants to preserve: the lock-window posture comments at MV:284–301 and SV:244–254 stay as-is. The bifurcated-lock-posture concurrency contract that Track 3 documented applies symmetrically to Track 5's persisted-side `setApproximateEntriesCount` writes (which land per tree under `executeInsideComponentOperation`'s per-tree exclusive lock). The in-mem-side delta is additively composable, so concurrent recalibrations on the same engine converge to truth post-commit. The Q5 multi-thread real-tree contention test gap inherited from Tracks 3 and 4 stays open under the single combined follow-up YouTrack issue Track 3 named (one ticket covers both engines and both `clear()` + `buildInitialHistogram()` seams).

## Concrete Steps

1. Rewrite `BTreeMultiValueIndexEngine.clear()` body (lines 283-346 pre-Track-5) to mixed-mode encoding. Replace the `accumulateClearOrRecalibrate(atomicOperation, id, -currentTotal, -currentNull)` call at lines 331-332 and the pure-delta comment block at lines 318-330 with two inline per-tree absolute writes (`svTree.setApproximateEntriesCount(atomicOperation, 0)`, `nullTree.setApproximateEntriesCount(atomicOperation, 0)`) followed by `IndexCountDelta.accumulateInMemRecalibration(atomicOperation, id, -currentTotal, -currentNull)`. Keep `clearSVTree(atomicOperation)` at line 302, postcondition asserts at lines 304-309, the `currentTotal`/`currentNull` snapshot reads at lines 311-312, the snapshot-invariant assert at lines 313-315, the two `indexesSnapshot.clear()` / `nullIndexesSnapshot.clear()` calls at lines 316-317, the lock-window comment block at lines 284-301, and the histogram-reset try/catch at lines 333-345 (covers `mgr.resetOnClear` unchanged). No new try/catch: `BTree.setApproximateEntriesCount` declares no checked exception (the body wraps the EP-page write in `executeInsideComponentOperation`, which absorbs IOException internally), and the MV `clear()` signature does not gain `throws IOException`. The new comment block names the mixed-mode design and the narrowed snapshot-read race posture against Track 3 pure-delta — risk: high (crash-safety: WAL-tracked encoding change on the `clear()` seam, with the structurally-impossible-divergence design claim depending on this rewrite; concurrency: publication ordering of the persisted-side write shifts from post-commit via Hook B to inline within the atomic op, while the in-mem `AtomicLong` advance still rides Hook B's post-commit apply via the new accumulator)  [ ]
2. Rewrite `BTreeSingleValueIndexEngine.clear()` body (lines 242-297 pre-Track-5) with the structurally identical mixed-mode change for the single-tree case. Replace the `accumulateClearOrRecalibrate(atomicOperation, id, -currentTotal, -currentNull)` call at lines 286-287 and the pure-delta comment block at lines 271-285 with one inline `sbTree.setApproximateEntriesCount(atomicOperation, 0)` write followed by `IndexCountDelta.accumulateInMemRecalibration(atomicOperation, id, -currentTotal, -currentNull)`. Keep the outer `try` at line 243, the lock-window comment block at lines 244-254, `doClearTree(atomicOperation)` at line 255, the postcondition assert at lines 261-263, the snapshot reads at lines 265-266, the snapshot-invariant assert at lines 267-269, `indexesSnapshot.clear()` at line 270, the `mgr.resetOnClear(atomicOperation)` call at line 290, and the method-level catch at lines 292-296 (converts IOException from `mgr.resetOnClear` to IndexException — load-bearing for `mgr.resetOnClear` alone post-Track-5). The new `setApproximateEntriesCount` write sits inside the same try purely for code locality; it adds no new checked-exception exposure. SV's `persistCountDelta` ignored `nullDelta` in the prior pure-delta encoding, and the absolute write replaces that path entirely on the persisted side, while the in-mem null-counter delta now rides the accumulator — risk: high (crash-safety: same shape as Step 1 on SV; concurrency: same lock contract and post-commit publication ordering hold) *(parallel with Step 1)*  [ ]
3. Add per-engine mixed-mode pin tests and Javadoc-only updates. Sub-edit 3a creates `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearMixedModeTest.java` and `BTreeSingleValueIndexEngineClearMixedModeTest.java` as Mockito-driven pin tests mirroring Track 4 iter-1's holder-row + per-tree-verify + negative-pin + `verifyNoMoreInteractions` patterns. MV asserts `verify(svTree, times(1)).setApproximateEntriesCount(eq(op), eq(0L))` and the same on `nullTree`; SV asserts the single-tree shape on `sbTree`. Both classes pin the post-`clear()`-pre-commit holder shape `inMemAdjustTotal == -currentTotal && inMemAdjustNull == -currentNull && totalDelta == 0 && nullDelta == 0` and the negative invariant that in-mem `AtomicLong` accessors return pre-`clear()` values during the atomic op (Hook B advances them post-commit, not inline). Setup uses a MEMORY DB with reflective field injection for the per-tree BTree stubs, mirroring `ClearIndexApiRollbackTest`'s `swapHistogramManager` pattern at lines 203-210; field names are `svTree`/`nullTree` for MV and `sbTree` for SV. Sub-edit 3b reframes Javadocs from "pure-delta-encoded" to "mixed-mode-encoded" on `BTreeMultiValueIndexEngineClearRollbackTest.java` (class-level at line 23, in-method paragraph at lines 197-200), `BTreeSingleValueIndexEngineClearRollbackTest.java` (SV mirror positions), and `ClearIndexApiRollbackTest.java` (class-level at lines 26-56). Sub-edit 3c appends a one-line awaiting-cleanup note to the `IndexCountDelta.accumulateClearOrRecalibrate` Javadoc at lines 98-139, naming the follow-up YouTrack work in prose (no `@Deprecated` annotation, no method-body change, no `IndexCountDeltaHolderTest` fixture migration — deferred to the follow-up issue) — risk: low (default: tests-only step plus Javadoc-only edits; no shared test infrastructure modified; no production-code behavior change)  [ ]

## Episodes

<!-- Continuous-log, workflow-specific sibling. Phase B sub-step 7 appends one block per completed step, identified by step number + commit SHA. -->

## Validation and Acceptance

Track-level acceptance criteria:

- After a successful `clear()` invocation on a populated MV engine, persisted side reads `approximateEntriesCount == 0` on both `svTree` and `nullTree` EP pages; in-mem `approximateIndexEntriesCount == 0` and `approximateNullCount == 0`.
- After a successful `clear()` invocation on a populated SV engine, persisted side reads `approximateEntriesCount == 0` on `sbTree`; in-mem `approximateIndexEntriesCount == 0` and `approximateNullCount == 0`.
- On rollback of the atomic op containing `clear()` (both engines), in-mem `AtomicLong`s retain their pre-`clear()` values; persisted side reverts via WAL to the pre-`clear()` value. The bug pattern observed in `Pre_Tests_Test_REST_2026.2.51599.log` (in-mem advance without WAL commit) is structurally impossible on the `clear()` seam post-Track-5.
- Pre-existing in-mem-vs-persisted drift (Track-1 underflow-clamp event, residual Track-3 drift-amplification window) heals to zero on the persisted side on every successful `clear()` invocation. The drift-amplification accepted regression Track 3 documented closes symmetrically.
- New per-engine pin tests verify the mechanical contract per-call: each tree's `setApproximateEntriesCount(op, 0)` fires exactly once during `clear()`; the holder records `(inMemAdjustTotal == -currentTotal, inMemAdjustNull == -currentNull, totalDelta == 0, nullDelta == 0)` post-`clear()`-pre-commit; the in-mem `AtomicLong`s read pre-`clear()` values during the atomic op (Hook B advances them post-commit, not inline).
- `IndexCountDelta.accumulateClearOrRecalibrate` retains its current body and tests; the production caller count drops to zero. `IndexCountDeltaHolderTest` exercises the method's precondition contract directly and stays green without changes.

Per-step EARS/Gherkin acceptance lines are Phase A placeholders.

<!-- Reserved for Move 3 — per-step EARS/Gherkin acceptance lines. Empty until Move 3 lands. -->

## Idempotence and Recovery

All three steps follow the standard revert-and-retry posture. Pre-commit failures route through the implementer's `git reset --hard HEAD`; post-commit dim-review findings on Step 1's `risk: high` and Step 2's `risk: high` route through `Review fix:` commits or, on a risk-upgrade or rollback request, through `git revert` plus a fresh `mode=INITIAL` respawn per `step-implementation-recovery.md`.

Per-step replay safety:

- **Step 1 (MV `clear()` rewrite).** The two `setApproximateEntriesCount(atomicOperation, 0)` writes are WAL-tracked through the AOM lifecycle; WAL replay re-applies them on recovery. The in-mem side rides Track 4's `inMemAdjustTotal` / `inMemAdjustNull` accumulator pair, which Hook B re-applies post-commit on the same engine. Re-running the rewritten `clear()` on the same atomic op writes zero twice with identical end state; the accumulator advances additively but in practice fires once per `clear()` invocation.
- **Step 2 (SV `clear()` rewrite).** Same recovery surface as Step 1 with one tree. The single `sbTree.setApproximateEntriesCount(atomicOperation, 0)` write inherits the same AOM lifecycle and WAL replay coverage; the in-mem null-counter delta lands on the same accumulator pair Hook B reads.
- **Step 3 (pin tests plus Javadoc edits).** No production-code behavior change to recover from. The new Mockito-driven pin tests are deterministic unit tests (pass-or-fail per JVM invocation, no shared mutable state across runs). Javadoc edits roll back cleanly via `git revert` with no functional impact.

## Artifacts and Notes

<!-- Continuous-log (rare), cross-step content only per D11. Per-step content lives in `## Episodes` above. -->

## Interfaces and Dependencies

**In-scope files:**

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (modified — `clear()` body at lines 283–346; the recalibration call at lines 331–332 and the pure-delta comment block at lines 318–330 become a mixed-mode block with two inline `setApproximateEntriesCount(op, 0)` calls plus the accumulator call; no new try/catch — `setApproximateEntriesCount` declares no checked exception)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (modified — `clear()` body at lines 242–297; the recalibration call at lines 286–287 and the pure-delta comment block at lines 271–285 become a mixed-mode block with one inline `setApproximateEntriesCount(op, 0)` call plus the accumulator call; existing method-level try/catch covers `mgr.resetOnClear` unchanged)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexCountDelta.java` (Javadoc-only modification — `accumulateClearOrRecalibrate` Javadoc at lines 98–139 gets the awaiting-cleanup note; method body at lines 140–154 stays unchanged)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearMixedModeTest.java` (**new** — Mockito-driven pin test for the MV mixed-mode `clear()` contract: per-tree `verify(...times(1)).setApproximateEntriesCount(eq(op), eq(0L))`, holder-row inspection, negative pin "in-mem `AtomicLong`s not mutated inline", `verifyNoMoreInteractions`. Mirrors Track 4 iter-1 patterns)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngineClearMixedModeTest.java` (**new** — Mockito-driven pin test for the SV mixed-mode `clear()` contract: single-tree shape of the same assertion set)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineClearRollbackTest.java` (Javadoc-only modification — class-level Javadoc at line 23 and the in-method Javadoc paragraph at lines 197–200 reframe "pure-delta-encoded" / "-currentTotal / -currentNull delta" to mixed-mode framing)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngineClearRollbackTest.java` (Javadoc-only modification — SV mirror of the MV rollback-test Javadoc edits)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/ClearIndexApiRollbackTest.java` (Javadoc-only modification — class-level Javadoc at lines 26–56 reframes "pure-delta encoding" / "records a negative delta on the atomic op" to mixed-mode framing)

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

928825a13cd912b4bfe653ae6c169e9917ac498a
