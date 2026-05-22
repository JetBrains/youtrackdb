# Track 1: Containment fixes

## Purpose / Big Picture
After this track, an `AssertionError` from any of the four engine-level counter mutators is logged at error level with engine identity and clamped to 0 inside the mutator; it never propagates as an exception. A persisted-side underflow from `BTree.addToApproximateEntriesCount` (still asserted) is caught and routed through rollback by the broadened catch at `AbstractStorage.commit:2319`. The cascade observed in `Pre_Tests_Test_REST_2026.2.51599.log` cannot recur even if Tracks 2–4 land later or are reverted.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Broaden the pre-`endTxCommit` catch at `AbstractStorage.commit:2319` to include `AssertionError`, and replace the `assert updated >= 0` underflow trap in the four engine-level mutators (`addToApproximate{Entries,Null}Count` × MV + SV) with clamp+error carrying engine identity, plus a one-shot stack-trace dump per engine. The post-`endTxCommit` catches at lines 2334 and 2346 are deleted by Track 2 along with the manual calls they surround, so this track leaves them alone. This is the smallest blast-radius change and lands first as defense-in-depth — after it, the cascade observed in the Hub log is contained even if the rest of the branch were to be reverted.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

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
  - Line 630: `addToApproximateEntriesCount(long delta)` with `assert updated >= 0` at line 633.
  - Line 640: `addToApproximateNullCount(long delta)` with `assert updated >= 0` at line 642.

The four mutators are called exclusively from `AbstractStorage.applyIndexCountDeltas` at lines 2489–2490 (PSI-verified via the BTreeIndexEngine interface). No other production caller exists, so the clamp+error surface is narrow.

The pre-fix cascade chain from the assertion handoff: assertion fires at MV:646 → bubbles out of `applyIndexCountDeltas` → hits the `catch (RuntimeException)` at 2334 (no match because `AssertionError extends Error`) → falls through the try-finally → outer `catch (Error)` at line 2388 → `logAndPrepareForRethrow(error)` at line 5784 → `setInError(error)` at line 5791 → from that point every commit on this storage rethrows `StorageException`. Track 1's clamp+error inside the mutator stops the assertion from ever throwing, so the cascade is broken at its source — independent of where the post-fix mutator call lives (today at `AbstractStorage.commit:2333`, after Track 2 inside `endAtomicOperation` Hook B).

The deliverable: one broadened catch line at AbstractStorage:2319, four rewritten mutator bodies, two new `firstUnderflowDumped` fields (one per engine class), and a regression test forcing an underflow and verifying clamp + error + no thrown exception.

### Clarifications

Track 1 adopts option (a) from the deferred PR #1088 finding plus a complementary `setInError` AssertionError guard. (1) Broaden the catches at `AtomicOperationsManager.executeInsideAtomicOperation:148` and `calculateInsideAtomicOperation:129` from `catch (Exception e)` to `catch (Exception | AssertionError e)`. (2) Add an entry-point guard to `AbstractStorage.setInError(Throwable)` at line 1756: return without setting `error` when the throwable is an `AssertionError`. Both changes are scoped to `AssertionError` only; catching the wider `Error` superclass would also swallow `OutOfMemoryError`, `StackOverflowError`, and `LinkageError`, which is dangerous. Production behavior is unchanged (JVM default `-ea` OFF; `core/pom.xml:36` enables `-ea` only for the test JVM). Tests are unaffected: 0 callsites across 1074 test usages of the wrappers expect `AssertionError` to escape, PSI-verified during Pre-Flight. Together with the existing broadened catch at `AbstractStorage.commit:2319`, the three layers close every known cascade path: the main-commit inner try (catch at 2319), the API path (wrappers), and any other top-level storage method's outer `catch (Error)` clause (`setInError` guard, ~30+ sites including `synch` / `count` / `freeze` / `release`). Decided 2026-05-22.

## Plan of Work

Five groups of edits, landed in any order within the track:

1. **Broaden the catch at AbstractStorage:2319.** Add `AssertionError` to the exception union: `catch (final IOException | RuntimeException | AssertionError e)`. Log messages stay as-is. The post-`endTxCommit` catches at lines 2334 and 2346 are left alone — Track 2 deletes them along with the manual calls they wrap. Covers the main-commit path's inner try.

2. **Broaden the catches in `AtomicOperationsManager`.** At `executeInsideAtomicOperation:148` and `calculateInsideAtomicOperation:129`, change `catch (Exception e)` to `catch (Exception | AssertionError e)`. The `error` variable is already typed `Throwable`, so it accepts the broader type without further change; `BaseException.wrapException` already accepts `Throwable` as its second argument. Covers the API path (`clearIndex`, `buildHistogramAfterFill`, and the 44 other src/main wrapper callsites). The broadening is scoped to `AssertionError` only; catching the wider `Error` superclass would also swallow `OutOfMemoryError`, `StackOverflowError`, and `LinkageError`, which the rollback path should not silently absorb. Closes the API-path cascade vector identified in PR #1088 Gemini review: under the current `catch (Exception)`, an `AssertionError` thrown inside the lambda bypasses the catch, leaves `error = null`, runs `endAtomicOperation(op, null)` as if successful (committing the op), and propagates to the API caller as the API path's analogue of the main-commit cascade.

3. **Exclude `AssertionError` from the storage error-state cascade.** Add a one-line entry-point guard to `AbstractStorage.setInError(Throwable)` at line 1756: return without calling `error.set(e)` when the throwable is an `AssertionError`. Defense-in-depth third layer that closes the residual hole left by groups 1 and 2. Today every top-level storage method (`synch`, `count`, `freeze`, `release`, and ~30+ others) has the pattern `catch (Error) → logAndPrepareForRethrow(ee)` at its outer scope; an `AssertionError` fired anywhere inside one of those methods hits that catch, descends through `logAndPrepareForRethrow(Error)` at line 5784, calls `setInError(error)` at line 5791, puts the storage in a permanent error state, and produces the cascade observed in `Pre_Tests_Test_REST_2026.2.51599.log`. After this change, `AssertionError` still bubbles up to the caller (the `catch (Error)` clause still rethrows it via `logAndPrepareForRethrow(Error)`), still gets logged with the storage URL and version, but the storage instance stays usable for subsequent commits. The guard sits at the single low-level setter (`setInError` is private; `error.set(e)` is only reachable through it), so the `moveToErrorStateIfNeeded(Throwable)` path at line 3598 funnels through the guard automatically; no separate edit is needed there. Production behavior is unchanged (JVM default `-ea` OFF; asserts are no-ops). The rationale matches groups 1 and 2: `AssertionError` signals a dev/test invariant violation, and the rollback path is the correct response.

4. **Rewrite the four engine mutators** to clamp+error. Each method:
   - Replaces `assert updated >= 0` with an `if (updated < 0)` branch.
   - On underflow, latches via `firstUnderflowDumped.compareAndSet(false, true)`: first occurrence per engine instance emits an error with engine `name`+`id` plus a `new Exception("…underflow stack")` argument so the log carries a stack trace. Subsequent occurrences emit a compact error without the stack.
   - Clamps via `compareAndSet(updated, 0)` so a concurrent delta applied between `addAndGet` and the clamp is not silently overwritten.
   - Both `addToApproximateEntriesCount` and `addToApproximateNullCount` on the same engine share one `firstUnderflowDumped` field.

5. **Add regression tests** in `core/src/test/.../engine/v1/`, `core/src/test/.../atomicoperations/`, and a cascade-containment test at the storage level. The engine-level test forces an underflow on a fresh engine (e.g., by directly invoking `addToApproximateNullCount(-1)` with no prior puts) and asserts: one error line with engine `name`+`id`; the counter clamps to 0; no `AssertionError` thrown. A second invocation on the same engine asserts the compact-error variant (no stack). The wrapper-level test forces an `AssertionError` from inside a lambda passed to `executeInsideAtomicOperation` and `calculateInsideAtomicOperation` and asserts: the op is rolled back (no `endAtomicOperation(op, null)` path); the caller receives a `StorageException` wrapping the original `AssertionError`; the cascade vector is closed. The storage-level test fires an `AssertionError` through a path that reaches a top-level `catch (Error)` clause and asserts: the AssertionError propagates to the caller; the storage's `isInError()` returns `false` after the throw; a subsequent commit on the same storage instance succeeds.

Ordering constraint: the two catch-broadening edits (groups 1 and 2), the `setInError` AssertionError guard (group 3), and the four mutator rewrites (group 4) are independent and can land in any order. The tests (group 5) go last so they cover the final shape.

Invariants to preserve: the cache-only contract on `applyIndexCountDeltas` and `applyHistogramDeltas` ("must never mask a successful commit"). Under Track 1 the contract still holds via the catches at 2334 and 2346; under Track 2 the contract migrates to Hook B's swallow-catch inside `endAtomicOperation`. The broadened catch at 2319 (pre-`endTxCommit`) routes `AssertionError` to rollback, which is the intended behavior: persisted-side underflow indicates a structural inconsistency and rollback is the correct response. The new `AtomicOperationsManager` catch broadening routes lambda-body `AssertionError`s through the same rollback path via `endAtomicOperation(op, error)`. The `setInError(Throwable)` guard preserves the existing read-only-mode contract for every Error type other than `AssertionError`; `OutOfMemoryError`, `StackOverflowError`, `LinkageError`, and unknown Errors still trigger error state.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. -->

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
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationsManager.java` (two catch sites — `executeInsideAtomicOperation:148` and `calculateInsideAtomicOperation:129`)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (two mutators + new field)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (two mutators + new field)
- New regression tests under `core/src/test/.../engine/v1/` (engine-level underflow), `core/src/test/.../atomicoperations/` (wrapper-level AssertionError cascade containment), and a storage-level test (verify `setInError` AssertionError guard keeps the storage usable after a top-level `catch (Error)` clause sees an AssertionError)

**Out of scope**:
- `BTree.addToApproximateEntriesCount` at `BTree.java:1020` (the persisted-side mutator). Its assertion fires inside the atomic op and is now routed through the broadened catch at AbstractStorage:2319. Persisted-side underflow indicates a structural inconsistency; the assertion stays so a future occurrence surfaces as a hard failure.
- `cleanupSnapshotIndex` catch at AbstractStorage:2357. No code path in that method asserts.
- `IndexCountDelta` changes (Tracks 2–4 territory).
- Bug C (YTDB-953) SV `load()` null reset. After Track 1, Bug C's underflow downgrades from `AssertionError` to a logged error; the load-path fix is tracked separately.

**Inter-track dependencies**:
- Track 1 is **independent**. Tracks 2, 3, and 4 depend on Track 1 because Track 1's clamp+error in the four mutators is what stops `AssertionError` from being thrown in the first place. Under Track 2's consolidation Hook B's swallow-catch covers `RuntimeException | AssertionError` as defense-in-depth, but the steady-state expectation is that Track 1 prevents the throw. Track 1 must land first so the cascade is broken at the source before any later track touches the surrounding code.

**Library/function signatures relevant to this track**:
- `LogManager.instance().error(this, String, Object...)` — the error helper; takes a final `Throwable` argument as the last vararg when present (used for the one-shot stack-trace dump).
- `AtomicBoolean.compareAndSet(boolean, boolean)` — one-shot dump latch.
- `AtomicLong.compareAndSet(long, long)` — clamp.
