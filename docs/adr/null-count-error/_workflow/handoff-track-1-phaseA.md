# Handoff: Phase A — Track 1 reviews complete, design-decisions + mechanical fixes pending

**Paused:** 2026-05-22
**Phase:** A (Track 1, Containment fixes)
**Context level at pause:** warning
**Branch:** YTDB-958-null-count-error
**HEAD:** 3e6e4f2cc9 "YTDB-958: Apply pre-flight amendments before Track 1"
**Unpushed:** 0 commits

## Durable artifacts on disk

- `docs/adr/null-count-error/_workflow/implementation-plan.md` — Track 1 entry expanded to three-layer cascade containment, scope bumped to ~6-7 steps; Track 1 is the next `[ ]`.
- `docs/adr/null-count-error/_workflow/plan/track-1.md` — Phase 1 sections amended at Pre-Flight: Plan of Work now lists 5 groups; Interfaces and Dependencies in-scope files list expanded; `### Clarifications` subsection at the end of `## Context and Orientation` records the option (a) + `setInError` guard decision.
- Pre-Flight commit `3e6e4f2cc9` (pushed). Working tree clean before pause.
- `## Outcomes & Retrospective` and `## Concrete Steps` in `track-1.md` are **empty**; no `Technical:` / `Risk:` / `Adversarial:` entries recorded yet (the reviews ran in conversation; findings live below in the verbatim re-present text, not on disk).

## Pending decision

Three Phase A reviews ran iteration 1 in parallel and returned a cumulative finding set: 1 blocker (Adversarial), 8 should-fix (across all three), 5 suggestions. The user must resolve the design-decision items (5 of them) before iteration 2 gate-check runs; mechanical items (8 of them) auto-fix during the next session's apply.

The blocker (A1) is the headline: the wrapper broadening from option (a) doesn't actually cover `AssertionError` thrown from `AtomicOperationBinaryTracking.commitChanges` inside `endAtomicOperation`'s `finally`. The cascade-containment property still holds via Layer 2 (setInError guard keeps storage usable), but the track's narrative promise that "the caller receives a StorageException wrapping the original AssertionError" is false for one path. Three resolution shapes:

- **(i)** Restructure the wrapper try to include `endAtomicOperation` — broader rewrite of `executeInsideAtomicOperation` / `calculateInsideAtomicOperation`, larger blast radius.
- **(ii)** Accept the gap, update Track 1's narrative to acknowledge `commitChanges` asserts escape as bare `Error` to the caller (Layer 2 still prevents permanent error state).
- **(iii)** Add a new layer at `AtomicOperationsManager.endAtomicOperation` that wraps `AssertionError` from `commitChanges` symmetrically with Layer 1.

The other design-decision items (T2/R3 test entry; T4+R2 CAS race + latch sharing; A2 component-operation wrappers; A5 setInError guard side effects) need pick-from-options answers.

## Verbatim re-present text

### Findings catalog (iteration 1, cumulative)

**Blocker (1)**

- **[A1][blocker]** (`AtomicOperationsManager.endAtomicOperation` + `AtomicOperationBinaryTracking.commitChanges`): AssertionError from `commitChanges` escapes the wrapper catches. The wrapper catches at `executeInsideAtomicOperation:155` and `calculateInsideAtomicOperation:136` only cover the lambda body inside `try { … }`; `endAtomicOperation` runs in `finally` and calls `commitChanges` at `AtomicOperationsManager.java:244`. PSI shows 5 asserts in `AtomicOperationBinaryTracking.commitChanges` (lines 953, 980, 1045, 1059, 1170). On `-ea` JVM, any of those throws AssertionError from the finally, bypassing the broadened wrapper catch entirely, and propagates to the storage method's outer `catch (Error)` clause. Layer 2 (`setInError` guard) blocks the read-only-mode flip, but the AssertionError still reaches the API caller as a bare `Error`, not the `StorageException` wrap Track 1 promises ("the caller receives a StorageException wrapping the original AssertionError"). The wrapper-level regression test (group 5) will PASS even with this gap because it forces the assert from inside the lambda body, not from `commitChanges`.

**Should-fix (8)**

- **[T1][should-fix]** (`track-1.md` lines 43-45): SV mutator line citations off by 8. Track says `BTreeSingleValueIndexEngine.addToApproximateEntriesCount` at L630 / `addToApproximateNullCount` at L640; PSI says L638 / L648. MV citations (636/644) match. Update to L638 (assert L640) and L648 (assert L650). Mechanical fix.

- **[T2][should-fix]** (`track-1.md` line 73, Group 5 storage-level regression test): `isInError()` is `protected`, not public. A test in a different package can't call it. Options: (a) write the storage-level test in the same package (`com.jetbrains.youtrackdb.internal.core.storage.impl.local`) — conventional in this codebase; (b) widen the accessor in a separate step; (c) assert against an observable side-effect. Design decision.

- **[R1][should-fix]** (groups 1–4 sequencing within the track): the surviving sibling catches at AbstractStorage:2334/2346 still catch `RuntimeException` exclusively while Track 1 is in flight. After group 4 the four mutators no longer throw `AssertionError`, so the gap is theoretical for the named call sites. However, `applyHistogramDeltas` at line 2345 calls `mgr.applyDelta(delta)` which is unguarded; if any current or future histogram-cache assert fires there, the cascade chain re-opens until group 3 lands. Mechanical fix: clarify in Plan of Work that the 5 groups land as one commit set (no intermediate-commit gap), and that the storage-level test in group 5 must exercise the path with group 3 applied.

- **[R2][should-fix]** (group 4, MV+SV `addToApproximate{Entries,Null}Count`): the planned `compareAndSet(updated, 0)` clamp races with concurrent positive deltas in a way the track does not address. Thread A computes `updated = addAndGet(delta)` and sees `updated = -3`. Before A executes the CAS, thread B runs `addAndGet(+5)`; counter is now `+2`. A's `CAS(-3, 0)` fails; counter stays at `+2` (correct outcome). But if B's delta is also negative (`-1`), counter is `-4`; A's CAS fails on its own `-4`, B's CAS fails too; neither clamps. Track's validation bullet "the counter clamps to 0" is then false. Additionally, the shared `firstUnderflowDumped` latch fires for whichever method wins the CAS; the other method's underflow loses its stack trace even though it represents a separate logical underflow on a different counter. Design decision: (a) loop the CAS until either it succeeds or `updated >= 0`; (b) accept residual (no loop) and document that the counter may remain negative under heavy concurrent contention until the next sufficiently-positive delta; (c) per-mutator latches instead of one shared latch. **Note: T4 (suggestion) recommends (b) explicitly — "no loop, leave the counter alone".** Resolve T4+R2 together.

- **[R3][should-fix]** (group 5, storage-level cascade-containment test): the test as described needs a top-level storage method whose internal path can be driven to throw `AssertionError`. PSI shows that `synch`, `count`, `freeze`, `release` (the methods Track 1 names) do not contain asserts that fire under realistic test conditions. Design decision: (a) wrap the four mutators' compact-error path inside a synthetic test fixture that pushes `delta = Long.MIN_VALUE + 1` to force a clamp+error AND assert the surrounding `commit()` returns successfully (covers the property end-to-end without needing a top-level catch-and-throw fixture); (b) add a single test-only `@VisibleForTesting throwAssertForCascadeTest()` method on `AbstractStorage` and exercise it through `count()`; (c) drop the storage-level test and rely on the engine-level + wrapper-level tests.

- **[A2][should-fix]** (`AtomicOperationsManager.executeInsideComponentOperation:179` and `calculateInsideComponentOperation:208`): two un-broadened component-operation wrappers carry the same cascade vector as the four wrappers Track 1 covers. Both catch only `Exception`. PSI shows 1 src/main callsite for each (executeInsideComponentOperation: 1+10, calculateInsideComponentOperation: 1+6); production blast radius is narrow but non-zero. PR #1088 Gemini finding identified the four-name wrappers; the component-operation pair has the same shape and the same hazard. Track 1's "Clarifications block: 'the three layers close every known cascade path'" is overstated. Design decision: (a) extend Track 1's group 2 to broaden both component-operation wrapper catches; (b) accept the gap and update the Clarifications block to acknowledge it; (c) defer to a follow-up track.

- **[A3][should-fix]** (other asserts in MV/SV engines reachable from lambdas): six asserts outside the four mutators are unaddressed by an explicit audit. PSI enumeration of `BTreeMultiValueIndexEngine`: asserts at `doClearTree:169`, `doClearTree:181`, `load:230`, `load:232`, `clear:274`, `clear:277`; SV mirrors at `doClearTree:147`, `load:190`, `clear:238`. Lambda-body asserts at `clear:274/277` (and SV `clear:238`) are exactly the Gemini-flagged surface option (a) ostensibly catches via the wrapper broadening. `doClearTree` and `load` asserts run on different control paths (recovery-time load, in-atomic-op clear). Mechanical fix: add an audit table to the Clarifications subsection mapping each lambda-body assert to its containing catch site.

- **[A4][should-fix]** (line-citation accuracy — wrapper catch lines): Track 1 cites `executeInsideAtomicOperation:148` and `calculateInsideAtomicOperation:129` as the **catch** lines. Those are **method-declaration** lines; the actual `catch (Exception e)` clauses are at lines 155 and 136. Same pattern: `BTree.addToApproximateEntriesCount` cited as `BTree.java:1020` but PSI shows method declaration at `BTree.java:1009` (the v3 `singlevalue/v3/BTree.java`, not the `paginated/btree/BTree.java` the path implies). Track 1 line 35's `AbstractStorage.java:2319` citation is correctly statement-line. Mechanical fix: normalize all citations to either statement lines (preferred — actionable for Phase B implementers) or explicitly mark `(method decl)` when the declaration is what matters.

- **[A5][should-fix]** (`setInError` guard's downstream behavioral changes): pre-guard, an AssertionError flipped `isInError()` to true, and:
  - `doShutdown` at `AbstractStorage.java:5008` skipped `flushAllData()` (line 5013)
  - `synch` at `AbstractStorage.java:3614` skipped `flushDirtyHistograms()` (line 3615)
  - `DiskStorage.clearStorageDirty` at `DiskStorage.java:612` is the parallel hazard on startup
  
  Post-guard, all three execute on any AssertionError survivor. If a future AssertionError signals real divergence (e.g., a page-level invariant violation), `flushAllData` would be called against potentially inconsistent state. Track 1's narrative ("storage instance stays usable for subsequent commits") doesn't enumerate which behaviors *change*. Design decision: (a) add a separate `assertionErrorSurvived` flag that gates the existing `!isInError()` checks at doShutdown/synch/clearStorageDirty so AssertionError still suppresses the dirty-flush side-effects; (b) accept the broader behavior change and document it; (c) narrow the `setInError` guard to skip only `setInError` from `logAndPrepareForRethrow` paths, not from the direct calls at lines 1746/3603/3894.

**Suggestion (5)**

- **[T3][suggestion]** (`track-1.md` line 49, cascade-chain narrative): clarify role split between groups. Group 1 (L2319 broadening) covers persisted-side `BTree.addToApproximateEntriesCount` asserts from within `commitIndexes`; Groups 3 + 4 cover the observed in-memory underflow at L2333. Currently a reader skimming L4 + L8 may conflate the two and decompose Groups 1 and 3 as redundant. Mechanical fix.

- **[T4][suggestion]** (`track-1.md` Group 4, clamp+CAS sequence): name the race the CAS clamp tolerates. Recommend "If the CAS fails (concurrent applier already moved the counter from `updated`), leave the counter alone — a clamp-loop would mask a legitimate concurrent decrement." **Pre-resolved interaction with R2**: T4 picks "no loop"; R2 lists "no loop" as option (b). Mechanical fix if user agrees with T4's recommendation.

- **[R4][suggestion]** (group 2 wrapper broadenings, blast radius ~1,102 callers): the "0 callsites across 1074 test usages expect AssertionError to escape" claim is not recorded with a PSI query. Capture the audit at decomposition time as a Decision Log entry so a reviewer can re-run it. Mechanical fix.

- **[R5][suggestion]** (group 3 `setInError` guard inline comment): the guard is also reached from `onException(Throwable)` at line 1746 and the broken-page path at line 3894, where current callers do not pass `AssertionError`. The guard is safe at those sites, but Track 1 names only the cascade case. Add an inline comment when groups land naming the three reasons `AssertionError` is special-cased (dev-only via `-ea`, rollback path already routes the throw, groups 1+2 already prevent the throw at known sources). Mechanical fix.

- **[A6][suggestion]** (regression test for `commitChanges`-assert path): group 5 tests cover the engine mutator, the wrapper-lambda body, and the storage outer-catch path; none covers an AssertionError from `commitChanges`. Even if A1 is closed by broadening `endAtomicOperation`'s try, a regression test pinning the contract is the only durable defense. Add a fourth test entry to group 5. Mechanical fix (conditional on A1 resolution).

- **[A7][suggestion]** (one-shot dump latch lifecycle): the `firstUnderflowDumped` field is per-engine-instance. On storage close and reopen, the engine instance is rebuilt; the latch resets and the next first underflow re-emits the stack trace. Probably intended, but the track does not say so. A log-volume regression (many shutdown-restart cycles in tests) could re-emit the same stack at high frequency. Add a one-line lifecycle note to group 4. Mechanical fix.

### Findings summary

- **Mechanical fixes** (auto-apply at iteration 2): T1, T3, T4, R1, R4, R5, A3, A4, A7. Total: 9. Plus A6 conditional on A1 resolution.
- **Design decisions** (escalate to user before iteration 2): A1 (blocker), T2, R2 (overlaps with T4 — T4's "no loop" already covers one resolution), R3, A2, A5. Total: 5 standalone (T4+R2 resolved together if user accepts T4).

## Resume notes

- **Do NOT redo:**
  - Pre-Flight gate (already cleared via commit `3e6e4f2cc9`).
  - Technical / Risk / Adversarial review sub-agents — findings catalog above is iteration 1 verbatim; do NOT re-spawn them.
  - PSI verification of class names (done during reviews; cited line numbers + class FQNs in the catalog above are PSI-verified).
- **On user resolution of design decisions:**
  1. Apply mechanical fixes to `track-1.md` (Plan of Work + Interfaces and Dependencies + Clarifications + optionally Validation and Acceptance for A6). Group them as a single `steroid_apply_patch` if more than two sites are touched.
  2. Apply user-chosen design-decision resolutions to the same sections.
  3. Spawn the gate-verification sub-agent per `track-review.md` § Review gate verification (prompt at `.claude/workflow/prompts/review-gate-verification.md`) with the iteration 1 findings + applied fixes as input. Three separate spawns (one per review type) OR one combined spawn — convention is one per review type. **Re-check context budget after each spawn** per `track-review.md` § What You Do sub-step 3.
  4. If gate-check PASSes all three: record three `[x]` entries in `## Outcomes & Retrospective` (one per review type with iteration count) and proceed to decomposition (§What You Do sub-step 4).
  5. If gate-check FAILS or surfaces new findings: iterate (max 3 per review type per `review-iteration.md`).
  6. Decompose Track 1 into ~6-7 concrete steps with risk tags per `risk-tagging.md`. Write the numbered roster to `## Concrete Steps`.
  7. Mark Phase A `[x]` in track-1.md Progress with `[ctx=<level>]` timestamp per D12.
  8. Commit + push the Phase A workflow update (single commit per `track-review.md` § What You Do sub-step 6).
- **On fixes requested (user pushes back on the option set):** re-read the relevant finding's text from the catalog above, propose a refined option, ask again.

## House-style notes

The original `## Plan of Work` group 2 + group 3 paragraphs and the Invariants paragraph were touched up in this session for em-dash overuse and negative parallelism (commit `3e6e4f2cc9`). When the next session edits those paragraphs to apply mechanical fixes T1/T3/T4/A4/A7, preserve the em-dash discipline (≤1 per paragraph) and avoid negative parallelism. The `### Clarifications` subsection is house-style compliant.
