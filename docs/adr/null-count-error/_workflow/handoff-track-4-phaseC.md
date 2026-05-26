# Handoff: Phase C — Track 4 review complete, user chose ESCALATE to add Track 5

**Paused:** 2026-05-26
**Phase:** C (Track 4 Track Completion, mid Review mode)
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** 1de0b4860f "YTDB-958: Mark Track 4 review complete in Progress"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/null-count-error/_workflow/plan/track-4.md` — all four step episodes (Steps 1–4) on disk; Progress section now reads `Step implementation [x]`, `Track-level code review iteration 1 complete (1/3 iterations) [x]`, `Track-level code review [x]`, `Track complete [x]`. The Track 4 entry in `implementation-plan.md` itself is still `[ ]` (un-collapsed, awaiting user Approve at Phase C step 4).
- Commit `c5d7747c58` "YTDB-958: Review fix: tighten Track 4 test contracts and refresh plan citations" — Phase C iter-1 fix applying all nine in-scope findings (rollback-discard test rewrite, Hook B sum verification, IOException-catch escape pin, forced-close-reopen Javadoc rewrite + rename, in-mem AtomicLong negative pins on 14 migrated tests, crash-recovery test lifecycle robustness, ~25 track-4.md citation refresh, wrapped-cause chain pin, `setApproximateEntriesCount` `times(1)` precision pins). 107/107 tests pass; coverage 86.8% line / 81.5% branch on changed code; Spotless clean.
- New test file `core/src/test/.../storage/impl/local/AbstractStorageApplyDeltaTest.java` — added by the iter-1 fix; carries the focused Hook B per-axis sum test (`applyIndexCountDeltasSumsPerPutAndInMemAdjustOnSameEngine`).
- Workflow update commits `56b97371f0` (Phase C iter-1 Progress) and `1de0b4860f` (Track complete Progress + ticked placeholders).
- Phase C iter-1 gate-check fan-out (7 dimensions: test-behavior, test-completeness, crash-safety, test-crash-safety, bugs-concurrency, test-structure, workflow-consistency) all returned PASS with 0 STILL OPEN, 0 REGRESSION, 0 new findings.

## Pending decision

Track 4 Phase C is mid Track Completion approval. The dimensional fan-out and gate-checks all PASSed at iteration 1; the user picked **Review mode** on the initial approval panel, asked whether the mixed-mode encoding Track 4 just landed for `buildInitialHistogram` should be applied symmetrically to `clear()` to close the drift-amplification accepted regression Track 3 documented, and chose to **ESCALATE** to add a Track 5 for the `clear()` retrofit rather than file a follow-up YouTrack issue post-merge.

ESCALATE routes to `.claude/workflow/inline-replanning.md`. Track 4's own Track Completion approval panel (Approve / Review mode / ESCALATE) remains pending — the user expects to close it after Track 5 is drafted and Track 4's track episode is updated to cross-reference Track 5.

## Verbatim re-present text

Present this to the user at resume so the reasoning chain is not re-derived.

---

> **Track 4 — buildInitialHistogram mixed-mode encoding — review complete.**
>
> All 12 dimensional review agents returned at iter 1. Nine in-scope findings (F1–F9) applied in a single review-fix commit `c5d7747c58` covering: rollback-discard test rewrite (Mockito `verifyNoMoreInteractions` instead of duplicate default-state check), Hook B per-axis sum verification (new test in `AbstractStorageApplyDeltaTest`), `-ea` snapshot-invariant assert escape pinned at `IndexAbstract.buildHistogramAfterFill:401-408` (catch widening explicitly out-of-scope per the plan, deferred to follow-up), the two crash-recovery tests' Javadoc rewritten + renamed to `forcedCloseReopen_*` (the `db.close` vs `forceDatabaseClose → doShutdown → flushAllData` discovery means the tests never exercised WAL replay), in-mem `AtomicLong`-not-mutated-inline pins on all 14 migrated tests, crash-recovery test lifecycle robustness (`create` inside try/finally, `forceDatabaseClose` invalidation contract documented), ~25 track-4.md line-citation refreshes (post-Step landings), wrapped-cause chain pin in the per-engine rollback tests, and `times(1)` precision pins on representative `setApproximateEntriesCount` verifies.
>
> Iter-1 gate-check fan-out (7 dimensions) all PASS; 107/107 tests pass; coverage 86.8% line / 81.5% branch on changed code.
>
> **Deferred follow-up** (cross-track impact):
> - Snapshot-read race + `IOException`-catch widening at `IndexAbstract.buildHistogramAfterFill:401-408` → standalone follow-up YouTrack issue.
> - Q5 multi-thread real-tree contention test + SV-side concurrency-test parity (covering both engines and both `clear()` + `buildInitialHistogram()` seams) → single follow-up issue inherited from Track 3's Q5 carry-forward.
> - True WAL-replay test via `WalTestUtils.withWalProtection` → deferred follow-up, named in test Javadoc.
> - Track-4.md duplication trim (Episodes vs Surprises / Decision Log) → Phase 4 `design-final.md`.
>
> **User asked:** "Should we apply the same approach for clear to avoid drift?" The conversation surfaced that Track 3 chose pure-delta for `clear()` with an accepted drift-amplification regression on the theory that "eagerly normalising the persisted side inside `clear()` would re-introduce the in-atomic-op write this track removes." Track 4 demonstrates that framing was over-tight: the WAL invariant target is the in-mem `AtomicLong` write, not any persisted-side write inside the atomic op (which is WAL-tracked and revertable). Mixed-mode on `clear()` would land persisted at absolute zero on every successful op (drift heals symmetrically with `buildInitialHistogram`'s mixed-mode design), and the in-mem `AtomicLong` continues to advance only post-WAL-commit via Hook B.
>
> **User chose ESCALATE to add Track 5 for the `clear()` retrofit.** Track 4's Track Completion panel remains open until Track 5 is drafted and Track 4's episode is updated to cross-reference it.

---

## Resume notes

### Do NOT redo

- The 12-agent dimensional fan-out for Track 4 (commits `c5d7747c58`, `56b97371f0`, `1de0b4860f` carry the durable record; the on-disk track file Progress section reflects all of Phase C iter-1 + completion).
- The iter-1 implementer spawn (commit `c5d7747c58` is the durable artifact; FIX_NOTES are summarised above and in the implementer's commit body).
- The 7-agent gate-check fan-out (all PASS; verdicts captured in the orchestrator-side conversation and folded into the Track 4 episode below).
- Track 4 episode compilation — re-read `git diff 1ebde1e462765720b99c1d6ec445b7a59132e972..HEAD` against current HEAD before re-rendering per the single re-compile entry point rule (`track-code-review.md` § Track Completion § 1). The episode itself is captured below in §Track 4 episode.

### Next action on resume

Load `.claude/workflow/inline-replanning.md` and follow its protocol to add Track 5 for the `clear()` mixed-mode retrofit, using the design described in §Track 5 seed below. After Track 5 lands as a `[ ]` entry in `implementation-plan.md` plus `plan/track-5.md` with its four Phase 1 sections, return to Track 4's Track Completion three-option panel. The user expects to Approve Track 4 there once Track 4's episode cross-references Track 5 (one line: e.g., "Track 5 retrofits `clear()` to mixed-mode encoding, closing the drift-amplification regression Track 3 accepted.").

### On user approval (Track 4 episode + Track 5 design)

- inline-replanning writes Track 5 to `implementation-plan.md` (new `[ ]` entry below Track 4) plus `plan/track-5.md`.
- Track 4 Phase C Track Completion completes: write the Track 4 episode (with Track 5 cross-reference added), collapse Track 4's description in `implementation-plan.md` to intro + episode + track-file pointer, mark `[x]`, commit + push as "Mark Track 4 complete", then end the session. Track 5 starts in a subsequent `/execute-tracks` session (State A Pre-Flight gate on Track 5).

### On fixes requested

If during inline-replanning the user wants to adjust the Track 5 design, the standard inline-replanning workflow applies. If the user retracts the ESCALATE entirely (decides to file a follow-up YouTrack issue instead), return to Track 4's Track Completion approval panel with the buffer empty, present the three options again, and let the user pick Approve / Review mode / ESCALATE fresh.

### On redirect

Standard inline-replanning redirect handling.

---

## Track 5 seed (for inline-replanning to consume)

**Title:** `clear()` mixed-mode encoding (retrofit)

**Big picture.** Convert `BTreeMultiValueIndexEngine.clear()` and `BTreeSingleValueIndexEngine.clear()` from Track 3's pure-delta encoding to mixed-mode encoding, mirroring Track 4's `buildInitialHistogram` design. Persisted side gets inline `setApproximateEntriesCount(op, 0)` calls per tree (WAL-tracked, drift-healing, revertable on rollback). In-mem side routes through `IndexCountDelta.accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)` consumed by Hook B post-commit. Eliminates the drift-amplification accepted regression documented in Track 3 Decision Log Q2 (`docs/adr/null-count-error/_workflow/plan/track-3.md`, the "(3) The drift-amplification accepted regression" paragraph in the Track 3 episode at `implementation-plan.md:222` of the slim view).

**Mechanism.**

MV `clear()` (current location verified post-Track-4 via PSI; the existing track file's line citations may need a refresh in inline-replanning's design pass):
- Keep `clearSVTree` / `doClearTree` calls and the in-mem counter snapshot under the per-tree component lock (Track 3 behavior preserved).
- Replace the `IndexCountDelta.accumulateClearOrRecalibrate(op, id, -currentTotal, -currentNull)` call with:
  - Inline `svTree.setApproximateEntriesCount(op, 0)` and `nullTree.setApproximateEntriesCount(op, 0)` — WAL-tracked absolute writes.
  - `IndexCountDelta.accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)` — in-mem-only delta accumulated for Hook B.

SV `clear()`: mirror on the single-tree shape. `sbTree.setApproximateEntriesCount(op, 0)` inline + `accumulateInMemRecalibration(op, id, -currentTotal, -currentNull)`.

`IndexCountDelta.accumulateClearOrRecalibrate` becomes unreferenced from production code (callers are the two `clear()` sites only after retrofit). Decide in inline-replanning whether to deprecate / remove in the same track or defer the cleanup. Tests under `IndexCountDeltaHolderTest` that exercise `accumulateClearOrRecalibrate` need a parallel decision.

**Constraints.**
- WAL invariant preserved: in-mem `AtomicLong` advances only post-WAL-commit via Hook B (same property Track 4 just established for `buildInitialHistogram`).
- The Track 1 underflow-clamp, Track 2 single-lifecycle-gate, and Track 4 mixed-mode plumbing (`accumulateInMemRecalibration` + `inMemAdjustTotal` / `inMemAdjustNull` fields + Hook B sum) all stay in place. Track 5 only changes the encoding choice on the `clear()` seam.
- The `accumulateInMemRecalibration` method's contract (no precondition on sign / magnitude, accumulator is additively composable) already accepts the `(-currentTotal, -currentNull)` shape Track 5 will pass — no API change to the accumulator.

**Scope: ~3 steps.**
1. MV `clear()` mixed-mode rewrite. Risk: **high** (changes the persisted-vs-in-mem semantics on the `clear()` path; the structural-impossibility claim for `clear()` rollback now depends on the retrofit landing correctly; same bifurcated lock posture as Track 4 — Hook B on the `clearIndex` API path runs without per-index lock).
2. SV `clear()` mixed-mode rewrite. Risk: **high** (mirror of Step 1; same caveats; SV `persistCountDelta` ignores `nullDelta`, but the new `accumulateInMemRecalibration` is the sole `nullDelta` carrier on the in-mem side, so this concern is already covered by Track 4's plumbing).
3. Test updates and accumulator-method cleanup. Risk: **medium**. Update `BTreeMultiValueIndexEngineClearRollbackTest` and `BTreeSingleValueIndexEngineClearRollbackTest` assertions to expect `inMemAdjustTotal` / `inMemAdjustNull` on the holder row (post-retrofit) instead of `totalDelta` / `nullDelta`, plus persisted-side `verify(...).setApproximateEntriesCount(op, 0)` pins. Mirror the negative-pin pattern Track 4 added (in-mem `AtomicLong` stays at pre-state during the atomic op). Decide on `accumulateClearOrRecalibrate` deprecation / removal as a sub-step.

**Depends on:** Tracks 2, 3, and 4. Specifically:
- Track 2's Hook B (`AbstractStorage.applyIndexCountDeltas` sums `getTotalDelta() + getInMemAdjustTotal()` and the null mirror) — Track 5's in-mem-side write flows through Hook B's sum exactly as Track 4's does.
- Track 3's per-tree-lock snapshot read at the top of `clear()` — kept verbatim.
- Track 4's `accumulateInMemRecalibration` accumulator + `inMemAdjustTotal` / `inMemAdjustNull` fields — Track 5's in-mem-side write reuses them.

**Decision Record (for inline-replanning to capture as D1 of Track 5, or as a new D-numbered record in the plan's `### Decision Records`).** Track 3 chose pure-delta with an accepted drift-amplification regression on the theory that "eagerly normalising the persisted side inside `clear()` would re-introduce the in-atomic-op write this track removes." Track 4 demonstrates the WAL invariant target is the in-mem `AtomicLong` write specifically, not any persisted-side write inside the atomic op (which is WAL-tracked through `executeInsideComponentOperation` and revertable on rollback via WAL). Track 5 generalises Track 4's mixed-mode pattern to the `clear()` seam to close the drift window symmetrically. The persisted-side `setApproximateEntriesCount(op, 0)` writes land per-tree under the existing component-operation gate; the in-mem-side delta carries the same no-precondition contract Track 4 established.

---

## Track 4 episode (for the Phase C collapse — final form, regenerate from diff on resume)

This is the strategic summary compiled at the initial approval panel. Regenerate it from `git diff 1ebde1e462765720b99c1d6ec445b7a59132e972..HEAD` on resume per the single re-compile entry point rule; the version below is the resume seed, not the canonical text.

> Track 4 lands the mixed-mode `buildInitialHistogram` rewrite across both BTree engines. The persisted side keeps inline `setApproximateEntriesCount(op, target)` writes (WAL-tracked, revertable on rollback, drift-healing on every successful recalibration). The in-memory `AtomicLong` writes route through a new in-mem-only accumulator (`IndexCountDelta.accumulateInMemRecalibration` plus two new package-private fields `inMemAdjustTotal` / `inMemAdjustNull`) consumed by Hook B (`AbstractStorage.applyIndexCountDeltas`), which sums `getTotalDelta() + getInMemAdjustTotal()` and `getNullDelta() + getInMemAdjustNull()` before calling `addToApproximate{Entries,Null}Count`. After this track, the in-memory recalibration-rollback divergence is structurally impossible on the `buildHistogramAfterFill` path; on rollback the in-mem counters stay at pre-recalibration values and the persisted EP page reverts via WAL.
>
> **Key discoveries.** (1) Hook B's per-axis sum is the single load-bearing production line that gives the two new accumulator pairs their meaning; Phase C iter-1 added a focused regression test (`AbstractStorageApplyDeltaTest.applyIndexCountDeltasSumsPerPutAndInMemAdjustOnSameEngine`) that pins the same-engine non-zero-both-pairs sum with `verifyNoMoreInteractions` for axis pairing. (2) `forceDatabaseClose` routes through `storage.shutdown() → doShutdown() → flushAllData()`; the two tests originally framed as "crash recovery" did not exercise WAL replay and were renamed in iter-1 to `forcedCloseReopen_*` with Javadocs that defer a true crash variant (via `WalTestUtils.withWalProtection`) to a follow-up. (3) The new `-ea` snapshot-invariant assert produces a `StorageException` that escapes the `IOException`-only catch at `IndexAbstract.buildHistogramAfterFill:401-408`; the catch widening is explicitly out-of-scope per the plan, and iter-1 pinned the current escape behavior with a regression test (`buildInitialHistogram_assertViolation_escapesIOExceptionCatch`). (4) Track 3's `clear()` precedent of pinning "in-mem `AtomicLong` not mutated inline" by `buildInitialHistogram` was absent from the migrated tests; iter-1 added the negative pin to all 14 migrated tests.
>
> **Plan deviations.** Step 2's review-fix iteration absorbed Step 4's MV holder-migration scope; Step 4's scope absorbed six review-deferred test-coverage items.
>
> **Cross-track follow-up: Track 5 (`clear()` mixed-mode retrofit).** Phase C surfaced that the mixed-mode pattern Track 4 establishes can be applied symmetrically to `clear()` to close the drift-amplification regression Track 3 accepted on purpose. The user chose to add Track 5 for the retrofit via ESCALATE; inline-replanning fires in the next session to draft Track 5's design and decompose it, then Track 4's Phase C closes.
>
> **Deferred follow-up (post-merge YouTrack issues).** Catch widening at `IndexAbstract.buildHistogramAfterFill:401-408`; the Q5 single-issue scope from Track 3 (multi-thread real-tree contention test + SV concurrency-test parity for both engines × both `clear()` and `buildInitialHistogram()` seams; non-atomic snapshot-read race fix); true WAL-replay test via `WalTestUtils.withWalProtection`; track-4.md duplication trim (defer to Phase 4 `design-final.md` per the existing "additively composable" deferral).
>
> **Track file:** `plan/track-4.md` (4 steps, 0 failed; 1 review-fix iteration).
