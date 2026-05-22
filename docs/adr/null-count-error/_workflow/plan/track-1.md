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
- [x] 2026-05-22T16:02Z [ctx=safe] Step 1 complete (commit 45e22026a8)
- [x] 2026-05-22T17:22Z [ctx=safe] Step 2 complete (commit 55db0a0aad)
- [x] 2026-05-22T18:21Z [ctx=info] Step 3 complete (commit 27c11e95b3, Review fix dc833ba36d)
- [x] 2026-05-22T19:15Z [ctx=warning] Step 4 complete (commit d55a110a40, Review fix 41d80eebd6)
- [x] 2026-05-22T20:19Z [ctx=info] Step 5 complete (commit d5962f4061, Review fix 3845809907)

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

### 2026-05-22 — engineId bit-encoding for `IndexCountDeltaHolder` lookups

The `IndexAbstract.getIndexId()` external id packs `engineAPIVersion` in the high 5 bits and the internal engine id in the low 27 bits (see `AbstractStorage.generateIndexId` / `extractInternalId`). `IndexCountDeltaHolder` keys on the internal id, so any test that wants to inject or read a delta for a named index must mask the external id with `0x7FFFFFF`. The four engine-mutator regression tests later in this track and the storage cascade-containment test will need the same mask. See Episodes §Step 1.

### 2026-05-22 — `AtomicLong` arithmetic primitives are final — use visibility relaxation for engine-CAS tests

`AtomicLong.addAndGet`, `compareAndSet`, `set`, and the rest of its arithmetic surface are declared `final` on the JDK class, so a wrapping subclass cannot intercept `addAndGet` to swap the live value between observation and the trailing CAS. Step 4 worked around this by relaxing `BTreeMultiValueIndexEngine.reportAndClampUnderflow` from `private` to package-private with an inline comment naming the test hook. Step 5's SV engine mirror, and any future engine-level test that needs to drive a controlled failed-CAS interleaving against the live counter, should apply the same visibility relaxation pattern. See Episodes §Step 4.

### 2026-05-22 — `logAndPrepareForRethrow` overload asymmetry for seeding in-error state

Only two of the three `logAndPrepareForRethrow` overloads on `AbstractStorage` call `setInError`: the `Error`-overload at line 5816 and the `Throwable`-overload at line 5838. The `RuntimeException`-overload at line 5789 does NOT touch the `error` field. A test that needs to seed in-error state via `logAndPrepareForRethrow` must use an `Error` subtype (e.g., `OutOfMemoryError`) or a bare `Throwable`. Affects Step 6 (storage cascade-containment) and any Track 2-4 test that needs prior in-error state. See Episodes §Step 3.

### 2026-05-22 — `RecordSerializationContext.executeOperations` as a wrapper-bypassing injection point

After Step 2 broadened the four `AtomicOperationsManager` wrapper catches, every production path inside `AbstractStorage.commit`'s inner try wraps an `AssertionError` as `RuntimeException` before it reaches the pre-`endTxCommit` catch at line 2319. The catch's fallback wrap line (the throw on the non-`RuntimeException` branch) is now defense-in-depth only — covered exclusively by `CommitNonRuntimeExceptionFallbackTest`, which pushes a throwable through a custom `RecordSerializationOperation` via `RecordSerializationContext.executeOperations`. The same injection point is reusable for Step 6's storage cascade-containment test and for any Track 2 test that needs to verify `endAtomicOperation` Hook A's persist-failure-to-rollback conversion survives an `AssertionError` that escapes the wrappers. See Episodes §Step 2.

### 2026-05-22 — SV `persistCountDelta` ignores `nullDelta`; null counter recalibrates at `load()` via `countNulls`

`BTreeSingleValueIndexEngine.persistCountDelta` writes only `totalDelta`; the `nullDelta` argument is silently dropped because non-null and null keys share one B-tree and the persisted null count is derived from `countNulls(atomicOperation)` during `load()`. The MV engine writes both deltas to separate EP pages. After Step 5 the in-memory clamp+error path is symmetric across the two engines, but the upstream divergence story is not: an SV in-memory `approximateNullCount` underflow has no persisted counterpart to reconcile against, so a load-time recalibration is the only correction path. Step 6's storage cascade-containment test should pick its synthetic `Long.MIN_VALUE + 1` injection on the SV side knowing the null counter cannot be restored by re-reading an EP page. Track 2's `applyIndexCountDeltas` consolidation does not need to special-case SV null-only deltas (they were already a no-op on persist), but the asymmetry is worth a comment when Track 2 lands. See Episodes §Step 5.

### 2026-05-22 — `BTreeEngineTestFixtures.captureSevereOn` is the shared JUL-capture surface for engine-mutator tests

The Step 5 Review fix at commit `3845809907` promoted three helpers (`readAtomicLong`, `readAtomicLongRef`, `installCapturingHandler`) onto `BTreeEngineTestFixtures` and added `captureSevereOn(Class engineClass, Runnable body)` to consume the install/snapshot/restore ritual. Twelve of the 16 fitting tests across `BTreeMultiValueIndexEngineUnderflowTest` and `BTreeSingleValueIndexEngineUnderflowTest` now route through `captureSevereOn`; four tests (the zero-delta and 16-thread contention tests in each file) keep manual handler control because they interleave assertions or a `CyclicBarrier` release with the captured window. The helper's Javadoc names the carve-out by test. Step 6 should reuse `captureSevereOn` rather than re-import the JUL handler ritual. See Episodes §Step 5.

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

1. Broaden the catch at `AbstractStorage.commit:2319` to include `AssertionError` and add a regression test forcing a persisted-side `BTree.addToApproximateEntriesCount` underflow that routes through the broadened catch to `rollback`. — risk: medium (error-handling code change at a single high-traffic main-commit catch; the catch lives on a storage-component method body so the change is observable by every commit)  [x]  commit: 45e22026a8
2. Broaden the four `AtomicOperationsManager` wrapper catches (`executeInsideAtomicOperation:155`, `calculateInsideAtomicOperation:136`, `executeInsideComponentOperation:179`, `calculateInsideComponentOperation:208`) from `catch (Exception e)` to `catch (Exception | AssertionError e)`, and add a wrapper-level cascade-containment test that forces `AssertionError` from the lambda body of each pair and asserts rollback + `StorageException` wrap. — risk: medium (error-handling code change; blast-radius audit recorded in Decision Log shows 0 of 1074 callers depend on the unchanged catch)  [x]  commit: 55db0a0aad
3. Add the `setInError(Throwable)` `AssertionError` entry-point guard at `AbstractStorage.java:1756` with the R5 three-reason inline comment (JVM `-ea` OFF, groups 1+2 catch at source, group 4 prevents in-memory underflow). — risk: high (storage-component lifecycle change; A5 documents downstream behavior changes at `doShutdown`, `synch`, `DiskStorage.clearStorageDirty`)  [x]  commit: 27c11e95b3
4. Rewrite `BTreeMultiValueIndexEngine.addToApproximate{Entries,Null}Count` to clamp+error: replace `assert updated >= 0` with `if (updated < 0)` branch, add `firstUnderflowDumped` `AtomicBoolean` field shared by both mutators, log first underflow with stack trace and engine `name`+`id` then clamp via `compareAndSet(updated, 0)`. Add engine-level regression test forcing an underflow on a fresh engine and asserting clamp + log line + no throw. — risk: high (concurrency: new shared mutable state with documented CAS race trade-off)  [x]  commit: d55a110a40
5. Rewrite `BTreeSingleValueIndexEngine.addToApproximate{Entries,Null}Count` with the same clamp+error pattern and matching engine-level regression test. *(parallel with Step 4 — different file)* — risk: high (concurrency: same as Step 4)  [x]  commit: d5962f4061
6. Add storage cascade-containment test in package `com.jetbrains.youtrackdb.internal.core.storage.impl.local` using the synthetic `addToApproximateNullCount(Long.MIN_VALUE + 1)` fixture; assert the surrounding `commit()` succeeds, `isInError()` returns `false`, and a subsequent commit on the same storage instance succeeds. — risk: low (tests-only, no production code change; depends on Steps 3, 4, 5)  [ ]
7. Add `commitChanges`-internal `AssertionError` regression test pinning the A1=accept-the-gap contract: force one of the five `AtomicOperationBinaryTracking.commitChanges` asserts (953/980/1045/1059/1170) to fire on a `-ea` JVM and assert the `AssertionError` surfaces as bare `Error`, `isInError()` stays `false`, and the storage stays usable. *(parallel with Step 6 — different test file)* — risk: low (tests-only, no production code change)  [ ]

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step. Empty at Phase 1. -->

### Step 1 — commit 45e22026a8, 2026-05-22T16:02Z [ctx=safe]
**What was done:** Widened the pre-`endTxCommit` catch at `AbstractStorage.commit:2319` from `IOException | RuntimeException` to `IOException | RuntimeException | AssertionError`, and re-shaped the body so both `IOException` and `AssertionError` wrap uniformly as `StorageException` with the original throwable preserved as the cause. A persisted-side underflow from `BTree.addToApproximateEntriesCount` raised inside `persistIndexCountDeltas` or `commitIndexes` now routes through the inner finally's `rollback` branch instead of bypassing it and committing the WAL atomic op with half-baked counter state. Added regression test `PersistedSideAssertionRoutedToRollbackTest` in the same package; the test injects an oversized negative delta into the per-transaction `IndexCountDeltaHolder` via reflection, forces the assert at `BTree.java:1020`, and verifies the captured `AtomicOperation` is marked rollback-in-progress with `AssertionError` as the root cause of the surfaced `StorageException`.

**What was discovered:** `IndexAbstract.getIndexId()` returns an external id that packs `engineAPIVersion` in the high 5 bits and the internal engine id in the low 27 bits (see `AbstractStorage.generateIndexId` / `extractInternalId`). `IndexCountDeltaHolder` keys on the internal id, so the test masks the external id with `0x7FFFFFF` before lookup. The four mutator-rewrite regression tests later in this track and the storage cascade-containment test will need the same mask when they inject deltas by index name.

**What changed from the plan:** none.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/PersistedSideAssertionRoutedToRollbackTest.java` (new)

**Critical context:** After this step the storage still flips to permanent error state via `setInError` when the `AssertionError` surfaces at the outer `catch (Error)`. Restoring usability for a subsequent commit on the same storage is the `setInError` entry-point guard's job, which lands in a later step of this track.

### Step 2 — commit 55db0a0aad, 2026-05-22T17:22Z [ctx=safe]
**What was done:** Broadened the four `AtomicOperationsManager` wrapper catches at `calculateInsideAtomicOperation:136`, `executeInsideAtomicOperation:155`, `executeInsideComponentOperation:179`, and `calculateInsideComponentOperation:208` from `catch (Exception e)` to `catch (Exception | AssertionError e)`, with an inline comment at each site naming the deliberate scope (other `Error` subclasses — `OutOfMemoryError`, `StackOverflowError`, `LinkageError` — still escape). The local `error` variable was already typed `Throwable` and `BaseException.wrapException` already accepts a `Throwable` cause, so no signature change was needed. Added `AtomicOperationsManagerWrapperAssertionErrorTest` with six tests: four per-wrapper `AssertionError` catch-and-rollback contracts (using a Mockito spy on the manager to capture the error argument passed to `endAtomicOperation`, pinning the rollback path rather than only the wrapped throw), a `RuntimeException` regression sanity test, and an `OutOfMemoryError` escape sanity test that pins the deliberate scope choice.

**What was discovered:** The wrapper broadening re-routed the prior step's `PersistedSideAssertionRoutedToRollbackTest`. The `BTree.addToApproximateEntriesCount` underflow `AssertionError` is now wrapped as `CommonStorageComponentException` (a `RuntimeException`) at the `executeInsideComponentOperation` boundary instead of escaping unwrapped, so the pre-`endTxCommit` catch at `AbstractStorage.commit:2319` takes the `if (e instanceof RuntimeException)` branch and the `IOException`/`AssertionError` fallback wrap line goes uncovered (1/2 changed lines, the coverage gate's only failure). Added `CommitNonRuntimeExceptionFallbackTest`, which pushes an `AssertionError` through a custom `RecordSerializationOperation` via `RecordSerializationContext.executeOperations` — a call site that is not inside any `executeInsideComponentOperation` wrapper, so the throw stays bare, fails the `RuntimeException` check, and runs the fallback wrap. Coverage then passed 100% on the two changed lines.

**What changed from the plan:** A second test file (`CommitNonRuntimeExceptionFallbackTest`) was added in `core/src/test/.../storage/impl/local/` outside the four test locations the plan listed. The wrapper broadening turned the prior step's fallback-wrap coverage into a dependency on a wrapper-independent injection path, and `RecordSerializationContext.executeOperations` was the cheapest such path. The plan's storage cascade-containment test (Step 6) and `commitChanges`-internal `AssertionError` test (Step 7) keep their original scope.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManagerWrapperAssertionErrorTest.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/CommitNonRuntimeExceptionFallbackTest.java` (new)

**Critical context:** After this step, no production path inside `AbstractStorage.commit`'s inner try produces a bare `AssertionError` or `IOException` — every code site either runs inside an `executeInsideComponentOperation` wrapper (now catches both) or declares no `IOException` and only allows `RuntimeException` to escape. The fallback wrap line at `AbstractStorage:2335` is therefore defense-in-depth only after this step, kept covered by `CommitNonRuntimeExceptionFallbackTest`. Track 2 will revisit this catch when the manual `persistIndexCountDeltas`/`applyIndexCountDeltas` calls are deleted.

### Step 3 — commit 27c11e95b3, 2026-05-22T18:21Z [ctx=info]
**What was done:** Added a one-line `if (e instanceof AssertionError) return;` guard at the entry of the private `setInError(Throwable)` setter in `AbstractStorage` (line 1756), with an inline comment naming the three reasons the skip is safe (JVM `-ea` OFF in production; the broadened pre-`endTxCommit` catch at `commit()` plus the four broadened `AtomicOperationsManager` wrapper catches catch the throw at its source; the upcoming engine-mutator clamp+error rewrites prevent the in-memory underflow from throwing). The guard is scoped to `AssertionError`; `OutOfMemoryError`, `StackOverflowError`, `LinkageError`, and other `Throwable` subtypes still flip the read-only-mode flag. PSI find-usages confirmed `setInError` is `private` with five internal callers, so the chokepoint guard closes the entire surface without per-caller edits. Added `SetInErrorAssertionErrorGuardTest` in the same package; after a dim-review fix iteration the test class carries six tests: the `Error`-overload AssertionError-skip contract, the `Throwable`-overload variant, an `AssertionError` subclass skip, idempotence against prior in-error state, a non-AssertionError `Throwable` still flipping the flag, and an `OutOfMemoryError` still flipping the flag.

**What was discovered:** Three overloads of `logAndPrepareForRethrow` exist on `AbstractStorage`, but only two of them actually call `setInError`: the `Error`-overload at line 5816 and the `Throwable`-overload at line 5838. The `RuntimeException`-overload at line 5789 does NOT touch the `error` field. The idempotence test (`assertionErrorAfterPriorErrorLeavesPriorErrorIntact`) first tried to seed in-error state with a `RuntimeException` and stayed in clean state because that overload bypasses the setter; the seed was switched to `OutOfMemoryError` (Error overload) and the test passes. Step 6's storage cascade-containment test and any Track 2-4 test that needs to seed in-error state inherits this constraint.

The dim review also surfaced a documentation completeness gap: the plan's A5 clarification enumerates `doShutdown`, `synch`, and `DiskStorage.clearStorageDirty` as the downstream behavior changes from the `setInError` guard, but `delete()` at `AbstractStorage.java:5119-5193` (which reads `isInError()` at three gate sites for `preCloseSteps`, engine deletion, and WAL deletion) was omitted. Pre-guard, an `AssertionError` survivor routed `delete()` into the log-only branch at line 5187; post-guard, `delete()` now runs the full deletion sequence. The behavior is correct for an actual user-initiated drop (the storage's on-disk state is no different from a normal exit), so the A5 expansion is documentation-only and does not require a code change.

**What changed from the plan:** Dim review surfaced four should-fix items at iteration 1, all VERIFIED at iteration 2 via a `Review fix:` commit `dc833ba36d` adding three new tests (Throwable-overload path, AssertionError subclass, idempotence against prior state) and a Javadoc rewrite. The original Javadoc said the test invoked the "package-protected method" but `setInError` is `private` and the test calls the `protected` `logAndPrepareForRethrow` overloads; `count` was also miscited as an example top-level entry point since it uses the two-arg `logAndPrepareForRethrow(ee, false)` overload that bypasses `setInError`.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/SetInErrorAssertionErrorGuardTest.java` (new)

**Critical context:** After this step the storage's permanent error-state cascade is closed: any `AssertionError` that still escapes the broadened catches in Step 1 and Step 2 is logged by `logAndPrepareForRethrow` and rethrown to the API caller, but `isInError()` stays `false` so the storage instance remains usable for subsequent commits. Step 6's storage cascade-containment test will exercise this end-to-end through the engine-mutator clamp+error path after Steps 4 and 5 land.

### Step 4 — commit d55a110a40, 2026-05-22T19:15Z [ctx=warning]
**What was done:** Rewrote `BTreeMultiValueIndexEngine.addToApproximateEntriesCount` and `addToApproximateNullCount` to clamp+error: the legacy `assert updated >= 0` was replaced with an `if (updated < 0)` branch delegating to a new helper `reportAndClampUnderflow`. The helper logs at error level through `LogManager.instance().error` with the engine's `name` and `id`, emits a stack trace on the first underflow per engine instance via a shared `AtomicBoolean firstUnderflowDumped` latch, and clamps the counter to 0 via `compareAndSet(observedNegative, 0)`. The CAS is not looped — a concurrent applier that moved the counter past the observed value is presumed to hold the correct new value. The companion test class `BTreeMultiValueIndexEngineUnderflowTest` ships nine tests after the dim-review iteration: first-underflow stack trace, compact second-underflow on same engine, the reverse cross-mutator order, fresh engine resets the latch (with both engines' records captured), `delta=0` no-op preserves the latch, the CAS-no-op through the engine path (live counter at 7), the negative-residue trade-off (live counter at -3), a 16-thread cross-mutator stress that asserts exactly one stack-trace record, and an isolated AtomicLong-CAS sanity anchor.

**What was discovered:** `java.util.concurrent.atomic.AtomicLong`'s arithmetic primitives (`addAndGet`, `compareAndSet`, `set`, ...) are declared `final` on the JDK class, so a `RacingAtomicLong` subclass that swaps the live value inside `addAndGet` does not compile. The workaround applied here — relax `reportAndClampUnderflow` visibility from `private` to package-private with an inline comment naming the test that exercises the failed-CAS branch — is the cleanest path for any future engine-level test that needs to drive `compareAndSet(observedNegative, 0)` against a mismatched live value. Step 5's SV engine mirror should plan to apply the same visibility relaxation if it wants the symmetric failed-CAS contract test (the symmetric test in this class demonstrates the exact pattern).

The test fixture uses `slf4j-jdk14` (pulled in transitively via `youtrackdb-test-commons`), so `SLF4JLogManager.log` routes through `java.util.logging`. The new tests capture log output by attaching a JUL `Handler` to the logger named after `BTreeMultiValueIndexEngine.class.getName()` — the same logger name the production `LogManager` uses when the requester is an engine instance. SLF4J `ERROR` maps to JUL `SEVERE` in this binding. The pattern is reusable by Steps 5–7 and by any Track 2–4 test that needs to verify engine-side or storage-side log emission without modifying the production logging surface.

**What changed from the plan:** Dim review surfaced six should-fix items at iteration 1, all VERIFIED at iteration 2 via a `Review fix:` commit `41d80eebd6`: the CAS-race test was rewritten to drive `reportAndClampUnderflow` directly through the now-package-private surface (five reviewer consensus); a 16-thread stress test was added to pin the latch CAS against a check-then-act regression; the reverse cross-mutator order was added; the first-underflow message assertion now pins the "see stack trace" variant marker; the latch-reset test was rebuilt to capture both engines' records; a `delta=0` no-op test was added to pin the steady-state quiescent-commit contract. The plan's roster `risk:` tag stays `high`; production code now also carries a one-line visibility relaxation on `reportAndClampUnderflow` (private → package-private) with the rationale captured in the inline comment.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineUnderflowTest.java` (new)

**Critical context:** `reportAndClampUnderflow` is now package-private. The test hook is documented in the method's inline comment so a future reader knows why the visibility was relaxed. The package is `com.jetbrains.youtrackdb.internal.core.index.engine.v1`; the engine class is `final` and lives entirely under `internal`, so the visibility change is scoped to this package and does not widen any external API surface. Step 5 should expect to apply the same pattern on its SV engine mirror if it wants the same dimensional coverage.

### Step 5 — commit d5962f4061, 2026-05-22T20:19Z [ctx=info]
**What was done:** Mirrored Step 4's MV clamp+error pattern onto `BTreeSingleValueIndexEngine`. The two mutators `addToApproximateEntriesCount` and `addToApproximateNullCount` delegate underflow handling to a new package-private helper `reportAndClampUnderflow`: log at ERROR with engine `name`+`id`, emit a stack trace on the first underflow per engine instance via a shared `AtomicBoolean firstUnderflowDumped` latch, then clamp via `compareAndSet(observedNegative, 0)` with no retry loop. Added `BTreeSingleValueIndexEngineUnderflowTest` carrying the same nine-test shape Step 4 settled on after dim-review iter-2: first-underflow stack trace, compact second-underflow on the same engine (both call orders), per-engine latch independence, `delta=0` no-op latch preservation, failed-CAS no-op through the engine path, failed-CAS via the direct helper hook, AtomicLong-CAS sanity anchor, negative-residue trade-off, and a 16-thread cross-mutator contention stress asserting exactly one stack-trace record. The `SingleValueFixture` was already available in `BTreeEngineTestFixtures` (engine name `test-sv`, id 0), so no fixture work was needed.

Dim-review iteration 1 surfaced two should-fix items (TS1+TS2 from `review-test-structure`) flagging helper duplication between the MV and SV underflow test files and a 16-copy repeat of the per-test JUL capture ritual. Commit `3845809907` (`Review fix:`) promoted three helpers (`readAtomicLong`, `readAtomicLongRef`, `installCapturingHandler`) onto `BTreeEngineTestFixtures` and added `captureSevereOn(Class, Runnable)` so the install/snapshot/restore ritual lives in one place. Twelve of the 16 ritual sites (six MV + six SV) now route through `captureSevereOn`; four tests in each pair (the zero-delta and 16-thread contention tests) keep manual handler control because they interleave assertions or barrier release with the captured window. The helper's Javadoc names the carve-out by test. Iter-2 gate-check from `review-test-structure` returned PASS with TS1 and TS2 verified.

**What was discovered:** PSI find-usages confirmed the SV mutators have only test-file callers in this repo. The single production caller of the `BTreeIndexEngine` surface remains `AbstractStorage.applyIndexCountDeltas` at lines 2514-2515; the track-plan citation at lines 2489-2490 has drifted forward by 25 lines since Phase A, but the one-call-site claim still holds. The SV engine carries a structural asymmetry with MV that matters for Step 6 and Track 2: `persistCountDelta` ignores `nullDelta` because non-null and null keys share one B-tree, and `load()` recalibrates `approximateNullCount` from `countNulls(atomicOperation)`. The in-memory clamp+error path is engine-symmetric, but a future divergence between persisted and in-memory state on the null counter has a different upstream root cause on SV than on MV. Step 6's synthetic `Long.MIN_VALUE + 1` injection should factor this in.

**What changed from the plan:** none. Production-code surface and test scope match the Concrete Steps entry. The Review fix added two new helper methods on the shared fixtures class, which is in-scope for "matching engine-level regression test" cleanup.

**Key files:**
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (modified)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngineUnderflowTest.java` (new)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeEngineTestFixtures.java` (modified by Review fix: three helpers promoted, `captureSevereOn` added)
- `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngineUnderflowTest.java` (modified by Review fix: local helpers removed, ritual routed through fixtures)

**Critical context:** `reportAndClampUnderflow` is now package-private on both MV and SV engines, with an inline comment naming the failed-CAS test that exercises the branch through the engine path. Both engine classes are `final` and live under `internal/`; the visibility relaxation does not widen any external API surface. Step 6's storage cascade-containment test should reuse `BTreeEngineTestFixtures.captureSevereOn`. The SV null-counter asymmetry (persisted side ignores `nullDelta`; load-time recalibration via `countNulls`) is the SV-specific input for Step 6's injection-point choice; see Surprises §"SV `persistCountDelta` ignores `nullDelta`".

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

## Base commit

6dd104000bba4134b9c47ee26a90cd1bb80da7d4
