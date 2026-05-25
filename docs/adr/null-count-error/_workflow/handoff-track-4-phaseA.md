# Handoff: Phase A — Track 4 iteration-2 amendments queued

**Paused:** 2026-05-25
**Phase:** A
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** c6733cf43e "YTDB-958: Apply pre-flight amendments before Track 4"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/null-count-error/_workflow/plan/track-4.md` — track file with pre-flight amendments + `### Clarifications` subsection landed at commit `c6733cf43e`.
- `docs/adr/null-count-error/_workflow/implementation-plan.md` — strategy-refresh line under Track 3's block at commit `c6733cf43e`.
- `docs/adr/null-count-error/_workflow/plan/track-3.md` — Track 3 completed; cross-track signals consumed.

## Pending decision

User already picked **(β) Mixed-mode encoding** for A1 at this session. No further user decision is pending — the next session has approval to apply the full iteration-2 amendment set and run iteration-2 gate-check.

## What ran this session

- Branch Divergence Check: clean (0 ahead, 0 behind).
- Track Pre-Flight gate Panel 1 + Panel 2 → user picked Approve under ADJUST. Amendments + `### Clarifications` + strategy-refresh line committed at `c6733cf43e`.
- Phase A iteration-1 reviews (Technical + Adversarial) spawned in parallel and both returned with verdict RE-RUN. Findings catalogued below.
- User picked **(β) Mixed-mode encoding** for A1.

## Verbatim re-present text — iteration-1 findings to resolve at iteration 2

### Technical Review (verdict: RE-RUN)

- **T1 [blocker]** — Q3 (runtime assert relaxation). Recommendation: option (b) — introduce separate `accumulateRecalibration(AtomicOperation, int, long, long)` overload without preconditions; keep `accumulateClearOrRecalibrate` strict for clear callers. **NOTE:** Q3's specific resolution is superseded by user's (β) Mixed-mode decision below — see §Iteration-2 amendment set for the consolidated approach.
- **T2 [should-fix]** — 13 existing positive `verify(...).setApproximateEntriesCount(...)` assertions in `BTreeEngineHistogramBuildTest.java` (lines 107, 132, 166, 206-207, 240, 279-280, 540-542, 566-568, 600) need rewriting to holder-inspection pattern (`f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId())`). Not currently called out in Plan of Work / Interfaces and Dependencies. **NOTE:** Under (β) the persisted-side `verify(...setApproximateEntriesCount...)` assertions REMAIN VALID (mixed-mode keeps inline absolute writes); only the in-mem-side assertions (`assertEquals(target, f.engine.getTotalCount(f.op))`) need rewriting because Hook B no longer fires in the mocked fixture path. Smaller scope than the original T2 finding under pure-delta.
- **T3 [should-fix]** — Mockito option (1) (test-side `executeInsideAtomicOperation` lambda with re-throw) is correct. Production-side prerequisite (the IOException catch in `IndexAbstract.buildHistogramAfterFill:401-408`) does NOT apply because the test invokes AOM directly, bypassing `buildHistogramAfterFill`. Document the choice in the track file.
- **T4 [should-fix]** — Lock posture option (a) (accept Q5-style deferred follow-up; rely on narrow call-graph of `buildHistogramAfterFill`) is correct. PSI confirms `IndexAbstract.buildHistogramAfterFill:400` is the only production caller; runs post-`fillIndex` post-rebuild. Document the choice; inherit the Q5 follow-up YouTrack issue (single ticket covering both engines + `clear()` + `buildInitialHistogram()`).
- **T5 [suggestion]** — Add snapshot-invariant assert matching Track 3's `clear()` precedent: `assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal` with engine `name` + `id` in the message.
- **T6 [suggestion]** — Update parallel SV comment block at `BTreeSingleValueIndexEngine.java:639-645` (Plan of Work currently only mentions the MV equivalent at 643-649).

### Adversarial Review (verdict: RE-RUN)

- **A1 [blocker] — RESOLVED via user's (β) Mixed-mode encoding decision.** Pure-delta encoding transfers in-mem-vs-persisted drift to the persisted side instead of healing it. Worked arithmetic: `Δ = target − currentInMem`. With pre-rebuild drift `currentInMem = persisted + d`, Hook A applies `Δ = −d` to persisted (was at `target`), lands at `target − d`. Today's `setApproximateEntriesCount(op, target)` writes absolute via WAL-tracked path — persisted lands at `target`, drift heals. Concrete scenario: a database hit the Hub-log bug pre-fix → drift on disk. User upgrades, runs `REBUILD INDEX`. Track 4 pure-delta makes persisted MORE drifted, not less. Track 3 Q2 had deferred persisted-side healing to Track 4; pure-delta does not deliver. **User picked (β) Mixed-mode encoding** at the iteration-1 design-call panel.
- **A2 [should-fix]** — Q3 option (b) recommended (consistent with T1). Under (β) the in-mem-only accumulator method is structurally different from the clear accumulator — it has no precondition by design (in-mem-only deltas are arbitrary-sign by recalibration nature).
- **A3 [should-fix]** — Histogram CHM cache divergence: Validation/Acceptance claim "structurally impossible" is true for count counters only. `IndexHistogramManager.cache.put` at `IndexHistogramManager.java:763, 806, 831` mutates eagerly inside the atomic op and is NOT reverted on rollback. After Track 4, a rolled-back `buildInitialHistogram` leaves CHM cache at recalibrated snapshot while persisted `.ixs` page reverts. Scope-bound the claim; add Non-Goal entry for histogram CHM cache divergence (deferred follow-up).
- **A4 [should-fix]** — Concurrent surface: `ProductionAllocatorConcurrencyMTTest.java:660-661` already exercises concurrent `buildInitialHistogram` on the same MV engine across worker threads. Adversarial analysis: pure-delta encoding is additively composable (`addAndGet` semantics) so concurrent recalibrations converge to truth — but Phase A should explicitly review this test against the new arithmetic before committing to option (a). **Under (β):** the persisted side uses inline `setApproximateEntriesCount(target)` which is WAL-tracked + per-tree-locked via `executeInsideComponentOperation`; concurrent recalibrations serialize on the per-tree lock for the persisted-side writes. In-mem-side delta accumulation is additive. Convergence holds.
- **A5 [should-fix]** — Mockito option (1) edge cases: (a) the lambda's exceptions get rewrapped as `StorageException` (RuntimeException via BaseException) by `AtomicOperationsManager.java:147` / `:174`, so the test catches `StorageException`, NOT `IOException`. (b) Under Q3 option (a) (relax in place), the precondition assert on `accumulateClearOrRecalibrate` would fire on sign-opposed deltas BEFORE the test's post-accumulate throw — making the test impossible under `-ea`. Under (β) and the new in-mem-only accumulator (no precondition), this concern evaporates.
- **A6 [suggestion]** — Reword Invariant 3 in `implementation-plan.md` to acknowledge bifurcated lock posture: "On the main-commit path, `applyIndexCountDeltas`/`applyHistogramDeltas` run with the per-index lock acquired at `lockIndexes` held. On the `clearIndex` API and `buildHistogramAfterFill` paths, no per-index lock is held during apply; the in-mem `AtomicLong`'s `addAndGet` additive semantics make ordering harmless."
- **A7 [suggestion]** — Post-Track-3 line citations PSI-verified accurate; no fix needed.
- **A8 [suggestion]** — Regression test Javadoc: explicitly scope the rollback assertion to count counters only; call out CHM cache as out-of-scope.

## Iteration-2 amendment set (queued — apply on resume)

User picked **(β) Mixed-mode encoding**. The amendment set below consolidates A1 (resolved via β) with T1/T2/T3/T4/T5/T6/A2/A3/A4/A5/A6/A8.

### Design summary of (β)

`buildInitialHistogram` keeps today's `svTree.setApproximateEntriesCount(op, scannedNonNull)` + `nullTree.setApproximateEntriesCount(op, exactNullCount)` **inline absolute writes** (WAL-tracked; rollback reverts via WAL — preserves persisted-side drift healing). Routes only the in-mem `AtomicLong` writes through a NEW in-mem-only accumulator + Hook B.

### `IndexCountDelta` machinery change

Add a new accumulator method:

```java
/**
 * Accumulates an in-memory-only recalibration delta. The persisted-side
 * write happens inline via {@code setApproximateEntriesCount(op, target)}
 * (WAL-tracked), so Hook A's {@code persistCountDelta} must NOT consume
 * this delta. Hook B's {@code applyIndexCountDeltas} consumes it
 * additively alongside any per-put/per-remove deltas in the same atomic op.
 *
 * <p>Production callers: {@code BTreeMultiValueIndexEngine.buildInitialHistogram},
 * {@code BTreeSingleValueIndexEngine.buildInitialHistogram}. No
 * sign-alignment or magnitude precondition — recalibration deltas can be
 * arbitrarily signed under organic drift.
 */
public static void accumulateInMemRecalibration(
    AtomicOperation atomicOperation, int engineId, long totalDelta, long nullDelta) {
  var delta = atomicOperation.getOrCreateIndexCountDeltas().getOrCreate(engineId);
  delta.inMemAdjustTotal += totalDelta;
  delta.inMemAdjustNull += nullDelta;
}
```

`IndexCountDelta` gains two new fields:
- `long inMemAdjustTotal` (default 0)
- `long inMemAdjustNull` (default 0)

`AbstractStorage.applyIndexCountDeltas` changes from:
```java
btreeEngine.addToApproximateEntriesCount(delta.getTotalDelta());
btreeEngine.addToApproximateNullCount(delta.getNullDelta());
```
to:
```java
btreeEngine.addToApproximateEntriesCount(delta.getTotalDelta() + delta.getInMemAdjustTotal());
btreeEngine.addToApproximateNullCount(delta.getNullDelta() + delta.getInMemAdjustNull());
```

Hook A's `persistCountDelta` reads `getTotalDelta()` / `getNullDelta()` only — unchanged.

`accumulateClearOrRecalibrate` stays as it is (Track 3 contract untouched; the name is technically misleading post-Track-4 because recalibration uses the new method, but renaming touches a settled track and is deferred as low-priority follow-up).

### Track-4.md Plan of Work — new step shape (4 steps)

1. **`IndexCountDelta` mixed-mode plumbing.** Add `accumulateInMemRecalibration(AtomicOperation, int, long, long)` method; add `inMemAdjustTotal` + `inMemAdjustNull` fields with getters; update `AbstractStorage.applyIndexCountDeltas` to sum both accumulators when calling `addToApproximate*Count`. Hook A's `persistCountDelta` path unchanged. Unit tests cover the additive composition with per-put deltas + recalibration delta in the same atomic op, the rollback discard contract, and the in-mem-only contract (verify Hook A's `persistCountDelta` is NOT called for in-mem-only deltas). Risk tag: **medium** (multi-file logic in core; no HIGH triggers — the holder structure change is local and the Hook B touch is a small two-field sum).
2. **Rewrite `BTreeMultiValueIndexEngine.buildInitialHistogram` lines 650-653 (current bodies):**
   ```java
   // Persisted-side absolute writes — WAL-tracked, revert on rollback,
   // heal pre-existing in-mem-vs-persisted drift.
   svTree.setApproximateEntriesCount(atomicOperation, scannedNonNull);
   nullTree.setApproximateEntriesCount(atomicOperation, exactNullCount);
   // In-mem-side delta — advances only post-commit via Hook B.
   long currentTotal = approximateIndexEntriesCount.get();
   long currentNull  = approximateNullCount.get();
   long targetTotal  = scannedNonNull + exactNullCount;
   assert currentTotal >= 0 && currentNull >= 0 && currentNull <= currentTotal
       : "<engine name + id>: snapshot invariant violated...";  // T5
   IndexCountDelta.accumulateInMemRecalibration(
       atomicOperation, id, targetTotal - currentTotal, exactNullCount - currentNull);
   ```
   Remove the two direct `AtomicLong.set` calls. Update the comment block at lines 643-649 to point at the mixed-mode design (persisted absolute + in-mem delta). Risk tag: **high** (crash-safety: WAL-relevant `buildInitialHistogram` rewrite — same risk shape as Track 3 Step 2/3 even with the persisted-side write preserved).
3. **Rewrite `BTreeSingleValueIndexEngine.buildInitialHistogram` lines 647-650** with the structurally identical change for the single-tree case (keep `sbTree.setApproximateEntriesCount(op, exactTotal)`; add snapshot-invariant assert; remove the two `AtomicLong.set` calls; add `accumulateInMemRecalibration` call). Update the parallel SV comment block at lines 639-645 (T6). Risk tag: **high** *(parallel with Step 2)*.
4. **Regression test + existing-test rewrite.** New `IndexAbstractBuildHistogramRollbackTest` under `core/src/test/.../index/` (Mockito option 1 — test wraps `executeInsideAtomicOperation` lambda; lambda runs `engine.buildInitialHistogram(op)` then re-throws IOException; test catches `StorageException` per A5 rewrap note; assertions pin (a) in-mem counters retain pre-recalibration values on rollback path, (b) persisted EP page reverts via WAL on rollback path, (c) both sides land on post-recalibration target on success path, (d) Hook B's `setApplied()` latch is true on success / false on rollback). Rewrite the `BTreeEngineHistogramBuildTest.java` assertions that use `assertEquals(target, f.engine.getTotalCount(f.op))` to the holder-inspection pattern matching Track 3's clear-tests. Javadoc scopes the assertions to count counters only; CHM cache out-of-scope (A8). Risk tag: **medium** (test infrastructure: novel rollback-injection seam local to test; validation gate for Steps 2/3).

### Track-4.md other section amendments

- **`## Purpose / Big Picture`** — reframe from "pure-delta encoding" to "mixed-mode encoding (persisted absolute writes + in-mem delta accumulator)" for `buildInitialHistogram`. Preserve the structural-impossibility-of-divergence claim, scope-bounded to in-mem counters.
- **`## Context and Orientation`** — add discussion of the mixed-mode rationale (drift healing preservation per A1). Update arithmetic walkthrough: persisted side lands at absolute target via `setApproximateEntriesCount` regardless of in-mem state; in-mem side advances by `Δ = target − currentInMem`. Update Q3 / Mockito / lock-posture / A1 entries in `### Clarifications` to record the chosen resolutions.
- **`## Validation and Acceptance`** — scope the "structurally impossible" claim to in-mem counters; add a one-line acknowledgement that persisted-side drift heals on every successful recalibration (preserved from today's behavior).
- **`## Interfaces and Dependencies`** — list the new method signature, new fields, updated `AbstractStorage.applyIndexCountDeltas` body.

### `implementation-plan.md` amendments

- **Decision Record D1** — append an "Update after Track 4 Phase A:" paragraph noting that `buildInitialHistogram` uses mixed-mode encoding rather than pure-delta on both sides, because the recalibration target needs to heal pre-existing drift on the persisted side. Track 3 (`clear()`) stays pure-delta because clear's `Δ = −current` converges both sides to zero regardless of drift.
- **Invariant 3** (A6) — reword to acknowledge the bifurcated lock posture.

## Resume notes

- **Do NOT redo:**
  - Pre-Flight gate (Panel 1 + Panel 2): already committed at `c6733cf43e`.
  - Branch Divergence Check: ran this session, clean.
  - Iteration-1 Technical and Adversarial reviews: findings already catalogued above.
  - A1 design decision: user picked **(β) Mixed-mode encoding**.
  - Q3/T1/A2 decision: superseded by (β) — new `accumulateInMemRecalibration` method has no precondition.
  - Mockito/T3/A5 decision: option (1) accepted; rewrap note captured.
  - Lock posture/T4/A4 decision: option (a) accepted.
  - Scope clarifications T2/T5/T6/A3/A8: captured in the iteration-2 amendment set.
- **Next actions on resume** (in order):
  1. Apply the iteration-2 amendment set to `track-4.md` and `implementation-plan.md` (atomic `steroid_apply_patch`).
  2. Update `## Outcomes & Retrospective` in `track-4.md` with iteration-1 entries marked `[x]` plus the iteration-2 outcomes after the gate-check runs.
  3. Spawn iteration-2 gate-check sub-agent per `prompts/review-gate-verification.md` with `previous_findings` set to the catalogued T-findings + A-findings above and `findings` set to the iteration-2 fix (no new code — the fixes are in the track file).
  4. On gate-check PASS: decompose into 4 steps per the "Track-4.md Plan of Work — new step shape" section above; write `## Concrete Steps` roster lines with the risk tags noted (Step 1 medium, Step 2 high, Step 3 high, Step 4 medium). Append D12 Progress entry `- [x] <ISO> [ctx=<level>] Review + decomposition complete` to `## Progress`.
  5. Commit "Phase A review and decomposition for Track 4" with `track-4.md` + `implementation-plan.md` (the D1 + Invariant 3 amendments) staged. Push.
  6. Run self-improvement reflection per `self-improvement-reflection.md`.
  7. End the session per Phase A Completion protocol.
- **If gate-check fails:** iterate per `review-iteration.md`. Max 3 iterations; this is iteration 2 of the budget.
- **Open question for the gate-check prompt:** none load-bearing — the (β) decision is final.
