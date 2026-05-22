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

## Plan of Work

Three groups of edits, landed in any order within the track:

1. **Broaden the catch at AbstractStorage:2319.** Add `AssertionError` to the exception union: `catch (final IOException | RuntimeException | AssertionError e)`. Log messages stay as-is. The post-`endTxCommit` catches at lines 2334 and 2346 are left alone — Track 2 deletes them along with the manual calls they wrap.

2. **Rewrite the four engine mutators** to clamp+error. Each method:
   - Replaces `assert updated >= 0` with an `if (updated < 0)` branch.
   - On underflow, latches via `firstUnderflowDumped.compareAndSet(false, true)`: first occurrence per engine instance emits an error with engine `name`+`id` plus a `new Exception("…underflow stack")` argument so the log carries a stack trace. Subsequent occurrences emit a compact error without the stack.
   - Clamps via `compareAndSet(updated, 0)` so a concurrent delta applied between `addAndGet` and the clamp is not silently overwritten.
   - Both `addToApproximateEntriesCount` and `addToApproximateNullCount` on the same engine share one `firstUnderflowDumped` field.

3. **Add a regression test** in `core/src/test/.../engine/v1/` that forces an underflow on a fresh engine (e.g., by directly invoking `addToApproximateNullCount(-1)` with no prior puts) and asserts: one error line with engine `name`+`id`; the counter clamps to 0; no `AssertionError` thrown. A second invocation on the same engine asserts the compact-error variant (no stack).

Ordering constraint: the catch-broadening edit at 2319 and the four mutator rewrites are independent and can land in any order. The test goes last so it covers the final shape.

Invariants to preserve: the cache-only contract on `applyIndexCountDeltas` and `applyHistogramDeltas` ("must never mask a successful commit") — under Track 1 the contract still holds via the catches at 2334 and 2346; under Track 2 the contract migrates to Hook B's swallow-catch inside `endAtomicOperation`. The broadened catch at 2319 (pre-`endTxCommit`) routes `AssertionError` to rollback — that is the intended behavior; persisted-side underflow indicates a structural inconsistency and rollback is the correct response.

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

## Interfaces and Dependencies

**In-scope files**:
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (three catch sites)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (two mutators + new field)
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (two mutators + new field)
- New regression test under `core/src/test/.../engine/v1/`

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
