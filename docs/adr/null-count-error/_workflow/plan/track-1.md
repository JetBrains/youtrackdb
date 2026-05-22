# Track 1: Containment fixes

## Purpose / Big Picture
After this track, an `AssertionError` from any of the four engine-level counter mutators is logged at error level with engine identity and clamped to 0 inside the mutator; it never propagates as an exception. A persisted-side underflow from `BTree.addToApproximateEntriesCount` (still asserted) is caught and routed through rollback by the broadened catch at `AbstractStorage.commit:2319`. The cascade observed in `Pre_Tests_Test_REST_2026.2.51599.log` cannot recur even if Tracks 2–4 land later or are reverted.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Broaden the pre-`endTxCommit` catch at `AbstractStorage.commit:2319` to include `AssertionError`, and replace the `assert updated >= 0` underflow trap in the four engine-level mutators (`addToApproximate{Entries,Null}Count` × MV + SV) with clamp+error carrying engine identity, plus a one-shot stack-trace dump per engine. The post-`endTxCommit` catches at lines 2334 and 2346 are deleted by Track 2 along with the manual calls they surround, so this track leaves them alone. This is the smallest blast-radius change and lands first as defense-in-depth — after it, the cascade observed in the Hub log is contained even if the rest of the branch were to be reverted.

## Progress
- [x] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-05-22T14:09Z [ctx=info] Review + decomposition complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

### 2026-05-22 — Wrapper-callsite audit (Phase A iteration-1 R4 capture)

Group 2's catch-broadening blast radius rests on the claim that 0 of the 1074 test usages of the four AtomicOperationsManager wrappers (`executeInsideAtomicOperation`, `calculateInsideAtomicOperation`, `executeInsideComponentOperation`, `calculateInsideComponentOperation`) depend on `AssertionError` escaping the wrapper as bare `Error` rather than being wrapped as `StorageException`. PSI-verified during Pre-Flight via find-usages on each wrapper method scoped to `core/src/test/**`, then a grep across the call expression's surrounding `try { ... } catch (...)` for `AssertionError` or `Throwable` in the catch parameter union. Result: zero callers depend on the unchanged catch semantics. Phase B implementation may re-run the audit if the wrapper signatures evolve before landing.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (4 findings, 4 accepted) — T1 (SV mutator citations 630/640 → 638/648), T2 (same-package storage cascade test), T3 (group role split paragraph), T4 (CAS race rationale).
- [x] Risk: PASS at iteration 1 (5 findings, 5 accepted) — R1 (single-PR landing + group 3 before group 5 test, `mgr.applyDelta` residual hole at AbstractStorage:2520 closed by group 3 guard), R2 (no CAS loop + shared latch, accepted trade-off), R3 (synthetic `Long.MIN_VALUE + 1` fixture), R4 (wrapper callsite audit recorded in Decision Log), R5 (three-reason `setInError` rationale).
- [x] Adversarial: PASS at iteration 2 (8 findings, 8 accepted) — iter 1: A1 (commitChanges gap accepted, A6 test added), A2 (component-op wrappers in group 2), A3 (lambda-body assert audit), A4 (statement-line citation normalization), A5 (downstream behavior changes documented), A6 (fourth test entry, group 5), A7 (latch lifecycle note). Iter 2 gate-check surfaced A8 (BTree.java method-decl citation disambiguated as `:1008` annotation + `:1009` signature); mechanical fix applied.

## Context and Orientation

The cascade root sits in one catch site (the one Track 1 broadens) and four engine-level methods, in two files:

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java`
  - Line 2319: `catch (final IOException | RuntimeException e)` covering `commitIndexes` and (until Track 2 deletes it) `persistIndexCountDeltas`. The catch lives at the end of the inner try at lines 2227–2326 and routes errors through `rollback(error, atomicOperation)` at line 2329. Track 1 broadens it to also catch `AssertionError`, so a persisted-side underflow from `BTree.addToApproximateEntriesCount` is routed through rollback.
  - Lines 2334 and 2346: `catch (final RuntimeException e)` blocks covering the manual `applyIndexCountDeltas` and `applyHistogramDeltas`. Track 1 does not touch these — Track 2 deletes them along with the manual calls they surround, replacing their swallow-contract with Hook B's `catch (RuntimeException | AssertionError applyFailure)` inside `endAtomicOperation`.
  - Line 2357: `catch (final RuntimeException e)` covering `cleanupSnapshotIndex`. Untouched — no current code path in that method asserts.

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - Line 636: `addToApproximateEntriesCount(long delta)` with `assert updated >= 0` at line 638.
  - Line 644: `addToApproximateNullCount(long delta)` with `assert updated >= 0` at line 646. This is the assertion observed firing in the Hub log.

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java`
  - Line 638: `addToApproximateEntriesCount(long delta)` with `assert updated >= 0` at line 641.
  - Line 648: `addToApproximateNullCount(long delta)` with `assert updated >= 0` at line 650.

The four mutators are called exclusively from `AbstractStorage.applyIndexCountDeltas` at lines 2489–2490 (PSI-verified via the BTreeIndexEngine interface). No other production caller exists, so the clamp+error surface is narrow.

The pre-fix cascade chain from the assertion handoff: assertion fires at MV:646 → bubbles out of `applyIndexCountDeltas` → hits the `catch (RuntimeException)` at 2334 (no match because `AssertionError extends Error`) → falls through the try-finally → outer `catch (Error)` at line 2388 → `logAndPrepareForRethrow(error)` at line 5784 → `setInError(error)` at line 5791 → from that point every commit on this storage rethrows `StorageException`. Track 1's clamp+error inside the mutator stops the assertion from ever throwing, so the cascade is broken at its source — independent of where the post-fix mutator call lives (today at `AbstractStorage.commit:2333`, after Track 2 inside `endAtomicOperation` Hook B).

The deliverable: one broadened catch line at AbstractStorage:2319, four rewritten mutator bodies, two new `firstUnderflowDumped` fields (one per engine class), and a regression test forcing an underflow and verifying clamp + error + no thrown exception.

### Clarifications

Track 1 adopts option (a) from the deferred PR #1088 finding plus a complementary `setInError` AssertionError guard, with the AtomicOperationsManager broadening extended to the component-operation wrapper pair as well. (1) Broaden the catches at four AtomicOperationsManager wrappers: `executeInsideAtomicOperation:155`, `calculateInsideAtomicOperation:136`, `executeInsideComponentOperation:179`, and `calculateInsideComponentOperation:208` (catch statement lines; method decls at :148, :129, :167, :196) from `catch (Exception e)` to `catch (Exception | AssertionError e)`. (2) Add an entry-point guard to `AbstractStorage.setInError(Throwable)` at line 1756: return without setting `error` when the throwable is an `AssertionError`. Both changes are scoped to `AssertionError` only; catching the wider `Error` superclass would also swallow `OutOfMemoryError`, `StackOverflowError`, and `LinkageError`, which is dangerous. Production behavior is unchanged (JVM default `-ea` OFF; `core/pom.xml:36` enables `-ea` only for the test JVM). Tests are unaffected: 0 callsites across 1074 test usages of the four wrappers expect `AssertionError` to escape, PSI-verified during Pre-Flight (see Decision Log entry for the audit method). Together with the existing broadened catch at `AbstractStorage.commit:2319`, the three layers close every known cascade path: the main-commit inner try (catch at 2319), the API path (four wrappers), and any other top-level storage method's outer `catch (Error)` clause (`setInError` guard, ~30+ sites including `synch` / `count` / `freeze` / `release`). Decided 2026-05-22.

**A1 gap acknowledged (commitChanges-internal asserts).** The wrapper broadening covers AssertionErrors thrown from inside the wrapper's `try { ... }` lambda body. AssertionErrors thrown from `AtomicOperationBinaryTracking.commitChanges` (five asserts at lines 953, 980, 1045, 1059, 1170) escape the wrappers because `endAtomicOperation` invokes `commitChanges` from the wrapper's `finally` block at `AtomicOperationsManager:244`, outside the broadened `try`. On a `-ea` JVM such an error surfaces to the API caller as a bare `Error`, not as the `StorageException` wrap the wrapper-level narrative implies. Layer 2 (the `setInError` guard) still keeps `isInError()` false so the storage instance stays usable; group 5 includes a regression test pinning this contract.

**A3 lambda-body assert audit** (PSI, 2026-05-22). The wrappers' broadening (group 2) covers AssertionErrors thrown anywhere inside the lambda body. Asserts mapped to their containing catch site:
- MV `BTreeMultiValueIndexEngine.clear:274` and `:277` (`svTree.firstKey` / `nullTree.firstKey` invariants) — inside the lambda passed via `executeInsideAtomicOperation`; caught by the widened `:155` after group 2 lands.
- SV `BTreeSingleValueIndexEngine.clear:238` (`sbTree.firstKey` invariant) — same path.
- MV `doClearTree:169` and `:181`, SV `doClearTree:147` — called from `clear()`; same lambda path.
- MV `load:230` and `:232`, SV `load:190` — runs at storage-open recovery time outside any wrapper. These persisted-count asserts are out of scope for Track 1; recovery-time underflow indicates a structural inconsistency that recovery itself should fail loudly.

**R5 three-reason rationale for the `setInError` AssertionError guard** (group 3 inline-comment content). The guard skips `AssertionError` for three reasons: (i) JVM default `-ea` is OFF, so production paths never throw asserts; (ii) the rollback path already routes group 1's broadened catch for the main-commit inner try and group 2's broadening for the API wrappers, so the throw is caught at its source; (iii) the four mutators (group 4) prevent the in-memory underflow from throwing at all. The guard is defense-in-depth for any future top-level method whose `catch (Error)` would otherwise put the storage in permanent error state on a stray assert.

**A5 setInError guard downstream behavior changes**. Pre-guard, an `AssertionError` flipped `isInError()` true and three sites skipped subsequent work. Post-guard, those sites run on any `AssertionError` survivor: `doShutdown` at `AbstractStorage.java:5008` no longer skips `flushAllData()` at :5013; `synch` at `AbstractStorage.java:3614` no longer skips `flushDirtyHistograms()` at :3615; `DiskStorage.clearStorageDirty` at `DiskStorage.java:612` runs on storage open. Accepted: under `-ea`-only `AssertionError`, the storage's on-disk state is no different from a normal exit, so flushing is safe. The behavior on a `RuntimeException` survivor is unchanged (also flushes today).

**A7 `firstUnderflowDumped` latch lifecycle.** The field is per-engine-instance and resets on storage close + reopen. Across many shutdown-restart cycles in tests, the same underflow may re-emit its stack trace once per cycle. Intentional: each fresh engine instance reports its first underflow loudly so the next regression is pin-pointable.

## Plan of Work

Five groups of edits, landed in any order within the track:

1. **Broaden the catch at AbstractStorage:2319.** Add `AssertionError` to the exception union: `catch (final IOException | RuntimeException | AssertionError e)`. Log messages stay as-is. The post-`endTxCommit` catches at lines 2334 and 2346 are left alone — Track 2 deletes them along with the manual calls they wrap. Covers the main-commit path's inner try.

2. **Broaden the catches in `AtomicOperationsManager`.** Change `catch (Exception e)` to `catch (Exception | AssertionError e)` at four catch statement lines: `executeInsideAtomicOperation:155`, `calculateInsideAtomicOperation:136`, `executeInsideComponentOperation:179`, and `calculateInsideComponentOperation:208` (method decls at :148, :129, :167, :196). The `error` variable is already typed `Throwable`, so it accepts the broader type without further change; `BaseException.wrapException` already accepts `Throwable` as its second argument. Covers the API path (`clearIndex`, `buildHistogramAfterFill`, and the 44 other src/main wrapper callsites for the atomic-operation pair, plus the production callers of the component-operation pair). The broadening is scoped to `AssertionError` only; catching the wider `Error` superclass would also swallow `OutOfMemoryError`, `StackOverflowError`, and `LinkageError`, which the rollback path should not silently absorb. Closes the API-path cascade vector identified in PR #1088 Gemini review: under the current `catch (Exception)`, an `AssertionError` thrown inside the lambda bypasses the catch, leaves `error = null`, runs `endAtomicOperation(op, null)` as if successful (committing the op), and propagates to the API caller as the API path's analogue of the main-commit cascade. Gap acknowledged: AssertionErrors thrown from `AtomicOperationBinaryTracking.commitChanges` (invoked in `endAtomicOperation`'s `finally` at `AtomicOperationsManager:244`, with five asserts at lines 953, 980, 1045, 1059, 1170) still escape the wrappers because the broadened `try { ... }` body does not cover the `finally` (see Clarifications A1). Layer 2 (group 3) keeps the storage usable; group 5 includes a regression test pinning the contract.

3. **Exclude `AssertionError` from the storage error-state cascade.** Add a one-line entry-point guard to `AbstractStorage.setInError(Throwable)` at line 1756: return without calling `error.set(e)` when the throwable is an `AssertionError`. Defense-in-depth third layer that closes the residual hole left by groups 1 and 2. Today every top-level storage method (`synch`, `count`, `freeze`, `release`, and ~30+ others) has the pattern `catch (Error) → logAndPrepareForRethrow(ee)` at its outer scope; an `AssertionError` fired anywhere inside one of those methods hits that catch, descends through `logAndPrepareForRethrow(Error)` at line 5784, calls `setInError(error)` at line 5791, puts the storage in a permanent error state, and produces the cascade observed in `Pre_Tests_Test_REST_2026.2.51599.log`. After this change, `AssertionError` still bubbles up to the caller (the `catch (Error)` clause still rethrows it via `logAndPrepareForRethrow(Error)`), still gets logged with the storage URL and version, but the storage instance stays usable for subsequent commits. The guard sits at the single low-level setter (`setInError` is private; `error.set(e)` is only reachable through it), so the `moveToErrorStateIfNeeded(Throwable)` path at line 3598 funnels through the guard automatically; no separate edit is needed there. Production behavior is unchanged (JVM default `-ea` OFF; asserts are no-ops). The rationale matches groups 1 and 2: `AssertionError` signals a dev/test invariant violation, and the rollback path is the correct response. Implementation includes an inline comment naming the three reasons the guard is safe (see Clarifications R5): JVM `-ea` is off in production, groups 1 and 2 catch the throw at its source, and group 4 prevents the in-memory underflow throw entirely. Downstream behavior changes at `doShutdown`, `synch`, and `DiskStorage.clearStorageDirty` are accepted (see Clarifications A5).

4. **Rewrite the four engine mutators** to clamp+error. Each method:
   - Replaces `assert updated >= 0` with an `if (updated < 0)` branch.
   - On underflow, latches via `firstUnderflowDumped.compareAndSet(false, true)`: first occurrence per engine instance emits an error with engine `name`+`id` plus a `new Exception("…underflow stack")` argument so the log carries a stack trace. Subsequent occurrences emit a compact error without the stack. The latch is per-engine-instance and resets on storage close + reopen (see Clarifications A7).
   - Clamps via `compareAndSet(updated, 0)`. If the CAS fails (a concurrent applier already moved the counter away from `updated`), leave the counter alone: a clamp-loop would mask a legitimate concurrent decrement and force the counter to 0 even when the new value is correct. Under heavy concurrent contention the counter may stay negative until the next sufficiently-positive delta. Accepted trade-off.
   - Both `addToApproximateEntriesCount` and `addToApproximateNullCount` on the same engine share one `firstUnderflowDumped` field. The shared latch fires for whichever mutator method wins the CAS race; the other method's first underflow logs a compact error instead of a stack trace. Accepted trade-off.

5. **Add regression tests** in four locations:
   - **Engine-level test** under `core/src/test/.../engine/v1/`: force an underflow on a fresh engine (e.g., by directly invoking `addToApproximateNullCount(-1)` with no prior puts) and assert one error line with engine `name`+`id`, the counter clamps to 0, and no `AssertionError` is thrown. A second invocation on the same engine asserts the compact-error variant (no stack).
   - **Wrapper-level test** under `core/src/test/.../atomicoperations/`: force an `AssertionError` from inside a lambda passed to `executeInsideAtomicOperation` and `calculateInsideAtomicOperation` and assert the op is rolled back (no `endAtomicOperation(op, null)` path), the caller receives a `StorageException` wrapping the original `AssertionError`, and the cascade vector is closed.
   - **Storage cascade-containment test** in package `com.jetbrains.youtrackdb.internal.core.storage.impl.local` (same package as `AbstractStorage` so the test can read the `protected isInError()` accessor directly): use the four mutators as a synthetic fixture by invoking `addToApproximateNullCount(Long.MIN_VALUE + 1)` on a live engine inside a transaction, and assert the surrounding `commit()` returns successfully, the storage's `isInError()` returns `false` after the clamp+error path executes, and a subsequent commit on the same storage instance succeeds. Exercises the engine-mutator clamp+error path end-to-end without needing a test-only assert hook on `AbstractStorage`.
   - **`commitChanges`-internal AssertionError test** (pins the A1=accept-the-gap contract): force one of the five asserts in `AtomicOperationBinaryTracking.commitChanges` to fire on a `-ea` JVM (e.g., via a `WriteAheadLog` mismatch on the assert at line 953) and assert the AssertionError surfaces to the API caller as a bare `Error` (Layer 1's wrapper broadening does not cover the `finally` block), Layer 2's `setInError` guard keeps `isInError()` false, and the storage instance remains usable for a subsequent commit.

Ordering constraint: the two catch-broadening edits (groups 1 and 2), the `setInError` AssertionError guard (group 3), and the four mutator rewrites (group 4) are independent and can land in any order. The tests (group 5) go last so they cover the final shape. All five groups land within Track 1's single PR; no partial Track 1 merges to `develop`. Between group landings the residual gap is bounded: group 1's broadened catch at AbstractStorage:2319 routes persisted-side underflow through rollback, and group 3's `setInError` guard closes the residual hole at any other top-level method's outer `catch (Error)` (including the unguarded `mgr.applyDelta(delta)` call inside `applyHistogramDeltas` at line 2345). Group 5's storage cascade-containment test must exercise the path with group 3 already applied so it actually verifies the guard's contract.

Group role split: group 1 closes the persisted-side cascade (`BTree.addToApproximateEntriesCount` underflow inside `commitIndexes`, routed via the broadened catch at AbstractStorage:2319). Group 2 closes the API-path cascade (lambda-body AssertionErrors inside `executeInside*` and `calculateInside*` wrappers). Groups 3 and 4 close the in-memory cascade observed in the Hub log: group 4 prevents the underflow throw at its source in the four mutators; group 3 catches any residual `AssertionError` survivor before it flips the storage to permanent error state.

Invariants to preserve: the cache-only contract on `applyIndexCountDeltas` and `applyHistogramDeltas` ("must never mask a successful commit"). Under Track 1 the contract still holds via the catches at 2334 and 2346; under Track 2 the contract migrates to Hook B's swallow-catch inside `endAtomicOperation`. The broadened catch at 2319 (pre-`endTxCommit`) routes `AssertionError` to rollback, which is the intended behavior: persisted-side underflow indicates a structural inconsistency and rollback is the correct response. The new `AtomicOperationsManager` catch broadening routes lambda-body `AssertionError`s through the same rollback path via `endAtomicOperation(op, error)`. The `setInError(Throwable)` guard preserves the existing read-only-mode contract for every Error type other than `AssertionError`; `OutOfMemoryError`, `StackOverflowError`, `LinkageError`, and unknown Errors still trigger error state.

## Concrete Steps

1. Broaden the catch at `AbstractStorage.commit:2319` to include `AssertionError` and add a regression test forcing a persisted-side `BTree.addToApproximateEntriesCount` underflow that routes through the broadened catch to `rollback`. — risk: medium (error-handling code change at a single high-traffic main-commit catch; the catch lives on a storage-component method body so the change is observable by every commit)  [ ]
2. Broaden the four `AtomicOperationsManager` wrapper catches (`executeInsideAtomicOperation:155`, `calculateInsideAtomicOperation:136`, `executeInsideComponentOperation:179`, `calculateInsideComponentOperation:208`) from `catch (Exception e)` to `catch (Exception | AssertionError e)`, and add a wrapper-level cascade-containment test that forces `AssertionError` from the lambda body of each pair and asserts rollback + `StorageException` wrap. — risk: medium (error-handling code change; blast-radius audit recorded in Decision Log shows 0 of 1074 callers depend on the unchanged catch)  [ ]
3. Add the `setInError(Throwable)` `AssertionError` entry-point guard at `AbstractStorage.java:1756` with the R5 three-reason inline comment (JVM `-ea` OFF, groups 1+2 catch at source, group 4 prevents in-memory underflow). — risk: high (storage-component lifecycle change; A5 documents downstream behavior changes at `doShutdown`, `synch`, `DiskStorage.clearStorageDirty`)  [ ]
4. Rewrite `BTreeMultiValueIndexEngine.addToApproximate{Entries,Null}Count` to clamp+error: replace `assert updated >= 0` with `if (updated < 0)` branch, add `firstUnderflowDumped` `AtomicBoolean` field shared by both mutators, log first underflow with stack trace and engine `name`+`id` then clamp via `compareAndSet(updated, 0)`. Add engine-level regression test forcing an underflow on a fresh engine and asserting clamp + log line + no throw. — risk: high (concurrency: new shared mutable state with documented CAS race trade-off)  [ ]
5. Rewrite `BTreeSingleValueIndexEngine.addToApproximate{Entries,Null}Count` with the same clamp+error pattern and matching engine-level regression test. *(parallel with Step 4 — different file)* — risk: high (concurrency: same as Step 4)  [ ]
6. Add storage cascade-containment test in package `com.jetbrains.youtrackdb.internal.core.storage.impl.local` using the synthetic `addToApproximateNullCount(Long.MIN_VALUE + 1)` fixture; assert the surrounding `commit()` succeeds, `isInError()` returns `false`, and a subsequent commit on the same storage instance succeeds. — risk: low (tests-only, no production code change; depends on Steps 3, 4, 5)  [ ]
7. Add `commitChanges`-internal `AssertionError` regression test pinning the A1=accept-the-gap contract: force one of the five `AtomicOperationBinaryTracking.commitChanges` asserts (953/980/1045/1059/1170) to fire on a `-ea` JVM and assert the `AssertionError` surfaces as bare `Error`, `isInError()` stays `false`, and the storage stays usable. *(parallel with Step 6 — different test file)* — risk: low (tests-only, no production code change)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step. Empty at Phase 1. -->

## Validation and Acceptance

After Track 1 lands:

- An `AssertionError` from any of the four engine mutators is logged at error level and the counter clamps to 0. The mutator does not throw, so no `AssertionError` reaches any catch site at all.
- The first underflow per engine instance carries a stack trace in the log; subsequent underflows on the same engine emit a compact error line.
- The cache-only contract on the two post-`endTxCommit` catches (lines 2334, 2346) holds for the duration of Track 1: a successful WAL commit cannot be masked by a counter-application failure.
- A persisted-side `AssertionError` from `BTree.addToApproximateEntriesCount` (out of scope for Track 1's mutator rewrite) now routes through the broadened catch at line 2319 → `rollback(error, atomicOperation)`, instead of escaping to the outer `catch (Error)`.
- A `RuntimeException` thrown from `persistIndexCountDeltas` still routes through `rollback(error, atomicOperation)` exactly as today.
- The regression test passes and reproduces the original failure (without Track 1) when reverted.
- An `AssertionError` thrown from `AtomicOperationBinaryTracking.commitChanges` on a `-ea` JVM surfaces to the API caller as a bare `Error` (Layer 1's wrapper broadening does not cover the `finally` block where `endAtomicOperation` invokes `commitChanges`). Layer 2's `setInError` guard keeps `isInError()` false; a subsequent commit on the same storage instance succeeds. This is the A1=accept-the-gap contract; the corresponding test in group 5 pins it.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). -->

### Deferred from PR #1088 Gemini review (Phase 2, 2026-05-22)

`AtomicOperationsManager.executeInsideAtomicOperation` (line 148) and `calculateInsideAtomicOperation` (line 129) catch only `Exception`. An `AssertionError` thrown inside the lambda bypasses the catch, leaves `error = null`, runs `endAtomicOperation(op, null)` as if successful, and propagates to the API caller — bypassing the cascade containment story for the API path (`clearIndex`, `buildHistogramAfterFill`). The four-mutator clamp+error pattern this track lands covers `addToApproximate{Entries,Null}Count` on MV / SV, but not lambda-body asserts such as `assert svTree.firstKey() == null` inside `clear()` at `BTreeMultiValueIndexEngine.java:274` / `:277` (and the SV mirror).

User decision needed during this track's Pre-Flight gate (Panel 2). Options:
- **(a)** broaden `executeInsideAtomicOperation` / `calculateInsideAtomicOperation` catches to include `AssertionError`. Changes exception semantics for every wrapper caller across the codebase — wide blast radius.
- **(b)** **(recommended)** extend this track's clamp+error pattern to the lambda-body asserts inside `clear()` / `buildInitialHistogram()`. Narrow scope, structurally consistent with the four-mutator rewrite. Adds one step to Track 1.
- **(c)** accept the gap as out of scope; document as a known limitation in `design-final.md` (Phase 4).

PSI-verified during Phase 2 assessment.

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (catch broadening at line 2319; `setInError` AssertionError guard at line 1756; existing catches at lines 2334, 2346, 2357 untouched)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (four catch sites at statement lines: `executeInsideAtomicOperation:155`, `calculateInsideAtomicOperation:136`, `executeInsideComponentOperation:179`, `calculateInsideComponentOperation:208`; corresponding method decls at lines 148, 129, 167, 196)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (two mutators + new field)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (two mutators + new field)
- New regression tests in four locations: `core/src/test/.../engine/v1/` (engine-level underflow), `core/src/test/.../atomicoperations/` (wrapper-level AssertionError cascade containment), `core/src/test/.../storage/impl/local/` in package `com.jetbrains.youtrackdb.internal.core.storage.impl.local` (storage cascade-containment via synthetic `Long.MIN_VALUE + 1` delta fixture; same-package placement reads the `protected isInError()` accessor directly), and a `commitChanges`-internal AssertionError test pinning the A1=accept-the-gap contract (Layer 2 keeps the storage usable after a `-ea`-only `AtomicOperationBinaryTracking.commitChanges` assert escapes)

**Out of scope**:
- `BTree.addToApproximateEntriesCount` at `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java:1020` (assert statement; method decl at :1008, signature at :1009). The persisted-side mutator. Its assertion fires inside the atomic op and is now routed through the broadened catch at AbstractStorage:2319. Persisted-side underflow indicates a structural inconsistency; the assertion stays so a future occurrence surfaces as a hard failure.
- `cleanupSnapshotIndex` catch at AbstractStorage:2357. No code path in that method asserts.
- `IndexCountDelta` changes (Tracks 2–4 territory).
- Bug C (YTDB-953) SV `load()` null reset. After Track 1, Bug C's underflow downgrades from `AssertionError` to a logged error; the load-path fix is tracked separately.

**Inter-track dependencies**:
- Track 1 is **independent**. Tracks 2, 3, and 4 depend on Track 1 because Track 1's clamp+error in the four mutators is what stops `AssertionError` from being thrown in the first place. Under Track 2's consolidation Hook B's swallow-catch covers `RuntimeException | AssertionError` as defense-in-depth, but the steady-state expectation is that Track 1 prevents the throw. Track 1 must land first so the cascade is broken at the source before any later track touches the surrounding code.

**Library/function signatures relevant to this track**:
- `LogManager.instance().error(this, String, Object...)` — the error helper; takes a final `Throwable` argument as the last vararg when present (used for the one-shot stack-trace dump).
- `AtomicBoolean.compareAndSet(boolean, boolean)` — one-shot dump latch.
- `AtomicLong.compareAndSet(long, long)` — clamp.
