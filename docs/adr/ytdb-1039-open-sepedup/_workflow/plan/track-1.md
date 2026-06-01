<!-- workflow-sha: 795f7e1902017877bd158df977a01e3ddb436a42 -->
# Track 1: Axis B — skip the read-cache purge on a no-op shrink

## Purpose / Big Picture
The recovery-time orphan pass becomes O(1) per component when nothing needs truncating, so crash recovery on a large-collection database no longer pays the O(N²) `removeByFileId` penalty.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Make the recovery-time orphan pass O(1) per component when nothing is truncated,
so crash recovery on a large-collection database no longer pays the O(N²)
`removeByFileId` penalty. Change `WriteCache.shrinkFile` to report whether it
physically truncated, and have `LockFreeReadCache.shrinkFile` purge the read
cache only when it did.

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [x] Track completion
- [x] 2026-06-01T11:36Z [ctx=info] Review + decomposition complete
- [x] 2026-06-01T12:05Z [ctx=safe] Step 1 complete (commit 909ee97829ee695aabc13ee4fa8a9923f3f82ca0)
- [x] 2026-06-01T12:47Z [ctx=info] Step 2 complete (commit 6828bfce30a60ce25529969ab1b3ed0b2963dc28, dim-review PASS iter 2)
- [x] 2026-06-01T13:40Z [ctx=info] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-01T13:50Z [ctx=info] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- JaCoCo's report goal is bound to the `prepare-package` phase, not `test`, so a
  `mvnw -pl core test -P coverage` run regenerates `jacoco.exec` but leaves
  `.coverage/reports/.../jacoco.xml` stale and the changed-line coverage gate reads
  pre-edit numbers. Run through `package -P coverage` (scoped via `-Dtest=…` to stay
  under the Bash 600s cap) to regenerate the XML before invoking `coverage-gate.py`.
  Relevant to Track 2's coverage gate and the Phase C verification. See Episodes §Step 2.
- `AsyncReadCacheTestIT` and `LockFreeReadCacheConcurrentTestIT` carry the
  `MockedWriteCache` boolean-signature change but are failsafe ITs not run by the
  surefire `test` goal; exercise them via `verify -P ci-integration-tests` in Phase C
  and Track 2 validation. See Episodes §Step 1.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 2 (4 findings T1-T4, 4 accepted) — T1 blocker corrected the "5 mocks return false" instruction (4 mocks keep their UOE throw, `TrackingWriteCache` returns a configurable boolean default `true`); T2/T3/T4 (DirectMemoryOnlyDiskCache dual overload, guard-mock trip-wire note, WOWCache boolean-contract assertions) folded into Plan of Work + Interfaces.
- [x] Risk: PASS at iteration 2 (4 findings R1-R4, 4 accepted) — R1 blocker is the same mock-instruction error as T1; R2 noted the Mockito `mock(WriteCache.class)` needs no edit; R3 specified the non-vacuous no-op test; R4 corrected the `LockFreeReadCache.shrinkFile` line ref to `:673`.
- [x] Adversarial: PASS at iteration 2 (4 findings A1-A4, 4 accepted) — S3 verified INFEASIBLE to violate (every path installing an above-target read-cache entry also bumps `getFileSize()` past `targetBytes`); A3 pinned the call-site gate + same-snapshot boolean constraints; A1/A2 enriched the D2 rationale (orchestrator-gate TOCTOU, `removeByStorageId` precedent) in Context without touching the immutable plan-file D2.
- [x] Gate verification: PASS at iteration 2 (all 9 accepted findings VERIFIED, no regressions; cosmetic N1 wording fixed).
- [x] Phase C (track-level code review): PASS at iteration 1. 11-agent fan-out
  (4 baseline + crash-safety/test-crash-safety + performance/test-concurrency
  + 3 workflow-review on this track file). 4 actionable findings, all fixed and
  gate-check VERIFIED: TC1 (pin the new `DirectMemoryOnlyDiskCache.shrinkFile`
  false-return contract with a test), WC1 (recompute the orphaned post-rebase
  step-commit SHAs cited in this track file), TY2 (zero-cost production `assert
  file.getFileSize() == targetBytes` in `WOWCache.shrinkFile`), TX1
  (single-threaded-production-contract Javadoc on `TrackingWriteCache.shrinkFile`).
  TC1/TY2/TX1 landed in review-fix commit cc339bde1d; WC1 was an orchestrator
  track-file fix (commits 0a0caf42ca + 298c85a9c5). Dropped as low-value: TB1
  (boundary test asserts evict-count not page-identity), TC2 (true-branch
  empty-purge-range), CQ1 (fully-qualified java.util imports match the file
  convention), CQ2 (duplicated mock comment, no shared base). No findings
  deferred to other tracks; none unfixed.

## Context and Orientation

The recovery-time orphan-truncation pass dispatches, per EP-equipped component,
to `StorageComponent.verifyAndTruncateOrphans` (`StorageComponent.java:389-422`),
which computes a `targetBytes` and calls
`readCache.shrinkFile(fileId, targetBytes, writeCache)` **unconditionally**
(`:417`). The cache-layer orchestration today is:

- `LockFreeReadCache.shrinkFile` (`LockFreeReadCache.java:674-721`): validates
  the target, calls `writeCache.shrinkFile(fileId, targetBytes)` (`:712`), then
  **always** computes `minPageIndex` and calls
  `clearFile(fileId, minPageIndex, writeCache)` (`:720`).
- `clearFile(3-arg)` (`:889-...`) calls `data.removeByFileId(fileId, minPageIndex)`
  (`:901`), which sweeps every section's full capacity under its write lock and
  rehashes any section that removed anything
  (`ConcurrentLongIntHashMap.removeByFileId` → `Section.removeByFileId` `:712-741`).
  The sweep is O(capacity) even when it removes nothing.
- `WOWCache.shrinkFile` (`WOWCache.java:1999-...`) already has a pre-flight no-op:
  it returns early when `file.getFileSize() <= targetBytes` (`:2065-2067`) — but
  it returns `void`, so `LockFreeReadCache` cannot tell the no-op from a real
  truncate and runs the purge regardless.
- `DirectMemoryOnlyDiskCache.shrinkFile` is a no-op on the in-memory engine.

Because the open-time pass runs after `openCollections`/`openIndexes` have loaded
~one entry-point page per component into the shared cache, the cache holds ~O(N)
entries while the pass runs; N components × O(N)-capacity sweeps gives the O(N²)
curve the issue measured (97.2% of reopen in `removeByFileId`).

**Deliverables.** `WriteCache.shrinkFile` reports whether it physically
truncated; `LockFreeReadCache.shrinkFile` runs the read-cache purge only on a
real truncate; tests assert the no-op path performs no purge and the genuine
truncate path still drops the correct cache entries.

**Terminology.** "purge" = the `clearFile`/`removeByFileId` eviction of cache
entries at `pageIndex >= minPageIndex`. "no-op shrink" = a `shrinkFile` call
whose `targetBytes >= currentFileSize`, dropping nothing.

**Why the boolean lives in the cache layer (Phase A review).** Gating one layer
up — comparing `physicalBytes <= targetBytes` at
`StorageComponent.verifyAndTruncateOrphans` on the already-computed sizes — was
considered and rejected: that path reads size through the gated
`StorageComponent.physicalSize` helper, a second read not synchronized with
`WOWCache`'s authoritative `getFileSize()` snapshot taken under
`filesLock.writeLock`, so it would open a TOCTOU window the in-cache no-op avoids.
Deriving the boolean from the same locked snapshot that drives the early-return
keeps the return exact. The rejected secondary-`fileId`-index alternative to
`removeByFileId` (plan D2) is likewise unnecessary: the project's own precedent —
`ConcurrentLongIntHashMap.removeByStorageId`, added when a per-file sweep pegged a
close thread at 100% CPU — favors targeted bulk skips over structural per-key
indices, and the no-op skip is the cheapest member of that family.

## Plan of Work

The change is a narrow contract refinement on the `shrinkFile` SPI plus a
guarded call in the read-cache orchestration.

1. Change `WriteCache.shrinkFile(long, long)` to return `boolean` — `true` iff
   the file was physically shrunk, `false` on the pre-flight no-op. In
   `WOWCache.shrinkFile`, return `false` at the `fileSize <= targetBytes`
   early-return (`:2065`) and `true` after the real truncate (`file.shrink(...)`,
   `:2076`). Derive the boolean from these two control-flow branches — the no-op
   early-return and the post-`file.shrink()` fall-through — **not** from a
   recomputed `getFileSize()` comparison: both branches read the same
   `getFileSize()` snapshot held under `filesLock.writeLock`, which is what makes
   the return exact (a recomputed compare after the lock drops reopens a TOCTOU
   race; see S3).
2. Update the other `WriteCache.shrinkFile` overriders (7 total, PSI-confirmed in
   `## Interfaces and Dependencies`):
   - `DirectMemoryOnlyDiskCache` — the **2-arg** `WriteCache.shrinkFile(long, long)`
     returns `false` (the in-memory engine has no physical store). Its separate
     **3-arg** `ReadCache.shrinkFile(long, long, WriteCache)` forwarder discards the
     new boolean and needs **no** change.
   - The four test mocks that today `throw UnsupportedOperationException`
     (`MockedWriteCache` in `AsyncReadCacheTestIT` / `LockFreeReadCacheConcurrentTestIT`
     / `LockFreeReadCacheBatchingTest`, and `PageFrameWriteCache` in
     `LockFreeReadCacheOptimisticTest`) **keep the throw** — change only the return
     type to `boolean` (a `throw` satisfies any return type). Each is pinned by a
     `testShrinkFileMockThrowsUnsupportedOperation` guard test; rewriting them to
     `return false` would delete that deliberate trip-wire and fail those tests.
   - `TrackingWriteCache` (the only mock exercised by the `LockFreeReadCacheFileOpsTest`
     orchestration tests) returns a **test-controlled boolean defaulting to `true`**
     (genuine-truncate semantics). The existing eviction tests
     (`testShrinkFileDropsAboveTargetEntriesAndDelegates`,
     `testShrinkFileToZeroDropsEverything`) assert the purge runs, which under the
     new conditional contract requires `true`; add a setter so the new no-op test
     (step 4) can flip it to `false`.
3. In `LockFreeReadCache.shrinkFile` (`:673`), capture the boolean from
   `writeCache.shrinkFile(...)` (`:712`) and call
   `clearFile(fileId, minPageIndex, writeCache)` (`:720`) only when it is `true`.
   Gate at this **call site** — do **not** push the conditional into the `clearFile`
   body: `truncateFile`/`closeFile`/`deleteFile` reach the purge through the
   separate **2-arg** `clearFile(fileId, writeCache)` → `clearFile(fileId, 0, writeCache)`
   and must keep dropping pages unconditionally; only `shrinkFile:720` uses the
   3-arg `clearFile` directly, so the change is localized to the `shrinkFile` path.
   Preserve all argument-validity guards (`:694-711`) — they run before the delegate.
4. Tests:
   - **No-op skip (non-vacuous):** seed N cached pages for a file, drive
     `TrackingWriteCache.shrinkFile` to return `false`, call
     `readCache.shrinkFile(fileId, targetBytes >= currentSize, …)`, then assert
     (i) `getUsedMemory()` is unchanged, (ii) the call-order tracker recorded
     `writeCache.shrinkFile` but **no** `clearFile.checkCacheOverflow` event,
     (iii) `shrinkFileCount == 1`. Seeding live entries (not an empty cache) is
     what makes the skipped-purge branch non-vacuous.
   - **Genuine truncate (unchanged):** a real shrink still evicts entries at
     `pageIndex >= minPageIndex` and preserves entries below it
     (`testShrinkFileDropsAboveTargetEntriesAndDelegates` /
     `testShrinkFileToZeroDropsEverything` keep passing with `TrackingWriteCache`
     returning `true`).
   - **WOWCache boolean contract:** extend `WOWCacheShrinkFileTest` so the
     `targetBytes >= currentSize` no-op asserts the return is `false` and the
     real-truncate cases assert `true`.
   Target both branches of the new `if` (100% branch coverage) via the two
   `LockFreeReadCacheFileOpsTest` methods.

Ordering: step 1 must precede steps 2-3 (the SPI return type drives both impls
and the caller). Invariant to preserve: S3 — the purge runs iff the write-cache
layer physically truncated.

## Concrete Steps

1. Migrate `WriteCache.shrinkFile` `void` → `boolean` across all 7 overriders, behavior-preserving: `WOWCache` returns `false` at the `fileSize <= targetBytes` no-op (`:2065`) and `true` after `file.shrink()` (`:2076`); `DirectMemoryOnlyDiskCache` 2-arg returns `false` (its 3-arg `ReadCache` forwarder is unchanged, discards the boolean); the four throwing mocks (`MockedWriteCache` ×3, `PageFrameWriteCache`) keep their `UnsupportedOperationException` (return type only); `TrackingWriteCache` returns a test-controlled boolean (default `true`, with a setter). `LockFreeReadCache.shrinkFile` still calls `clearFile` unconditionally (the boolean is consumed in Step 2). Extend `WOWCacheShrinkFileTest` to assert the return (`false` on `targetBytes >= currentSize`, `true` on a real truncate). — risk: medium (multi-file SPI signature change across core storage-cache classes; behavior-preserving, no eviction/recovery behavior change)  [x] commit: 909ee97829ee695aabc13ee4fa8a9923f3f82ca0
2. Gate the read-cache purge in `LockFreeReadCache.shrinkFile` (`:673`) on the boolean: capture `truncated` from `writeCache.shrinkFile(...)` (`:712`) and call `clearFile(fileId, minPageIndex, writeCache)` (`:720`) only when `true`. Keep the argument-validity guards (`:694-711`) and the unconditional 2-arg `clearFile` in `truncateFile`/`closeFile`/`deleteFile` untouched (gate at the call site, never inside `clearFile`). Add the non-vacuous no-op-skip test to `LockFreeReadCacheFileOpsTest` (seed N live pages, drive `TrackingWriteCache` to return `false`, assert `getUsedMemory()` unchanged + no `clearFile.checkCacheOverflow` event + `shrinkFileCount == 1`) and confirm the genuine-truncate path still evicts at `pageIndex >= minPageIndex`. — risk: high (crash-safety + cache-eviction: gates the recovery-time read-cache purge in LockFreeReadCache; preserves invariant S3)  [x] commit: 6828bfce30a60ce25529969ab1b3ed0b2963dc28

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. Empty at Phase 1. -->

### Step 1 — commit 909ee97829ee695aabc13ee4fa8a9923f3f82ca0, 2026-06-01T12:05Z [ctx=safe]
**What was done:** Changed `WriteCache.shrinkFile(long, long)` from `void` to
`boolean` across all 7 PSI-confirmed overriders, behavior-preserving. `WOWCache`
returns `false` at the `fileSize <= targetBytes` no-op early-return and `true`
after `file.shrink()`, both read off the single `getFileSize()` snapshot held
under `filesLock.writeLock` (no recomputed compare, preserving S3).
`DirectMemoryOnlyDiskCache`'s 2-arg returns `false`; its 3-arg `ReadCache`
forwarder is untouched. The four throwing mocks (`MockedWriteCache` ×3,
`PageFrameWriteCache`) kept their `UnsupportedOperationException` trip-wire with
only the return type changed. `TrackingWriteCache` returns a test-controlled
boolean (default `true`) with a `setShrinkFileReturnValue` setter for the Step 2
no-op test. `LockFreeReadCache.shrinkFile:712` still calls `clearFile`
unconditionally and discards the boolean. `WOWCacheShrinkFileTest` asserts `false`
on the no-op branches and `true` on real truncates. 163/163 core tests pass,
100% changed-line coverage.

**What was discovered:** `AsyncReadCacheTestIT` and
`LockFreeReadCacheConcurrentTestIT` carry the same `MockedWriteCache` signature
change but are failsafe ITs (`*IT` suffix), so the surefire `test` goal compiled
them without running them; Phase C and Track 2 validation should exercise them
via `verify -P ci-integration-tests`. The `WOWCache` `true`/`false` returns sit
inside nested try blocks (inner `files.release` finally, outer `filesLock`
release plus a `catch(InterruptedException)` that always throws), so the method
needs no fall-through return and compiles cleanly.

**Key files:**
- `core/.../storage/cache/WriteCache.java` (modified)
- `core/.../storage/cache/local/WOWCache.java` (modified)
- `core/.../storage/memory/DirectMemoryOnlyDiskCache.java` (modified)
- `core/.../storage/cache/chm/AsyncReadCacheTestIT.java` (modified)
- `core/.../storage/cache/chm/LockFreeReadCacheConcurrentTestIT.java` (modified)
- `core/.../storage/cache/chm/LockFreeReadCacheBatchingTest.java` (modified)
- `core/.../storage/cache/chm/LockFreeReadCacheOptimisticTest.java` (modified)
- `core/.../storage/cache/chm/LockFreeReadCacheFileOpsTest.java` (modified)
- `core/.../storage/cache/local/WOWCacheShrinkFileTest.java` (modified)

### Step 2 — commit 6828bfce30a60ce25529969ab1b3ed0b2963dc28, 2026-06-01T12:47Z [ctx=info]
**What was done:** Gated the read-cache purge in `LockFreeReadCache.shrinkFile` on
the `truncated` boolean from `WriteCache.shrinkFile`. The `minPageIndex` computation
and the 3-arg `clearFile(fileId, minPageIndex, writeCache)` call now run inside
`if (truncated)`. The argument-validity guards are unchanged and still run before the
delegate; the unconditional 2-arg `clearFile` reached by
`truncateFile`/`closeFile`/`deleteFile` is untouched (gated at the call site, never
inside `clearFile`). PSI confirmed the 3-arg `clearFile` has exactly two callers (the
gated `shrinkFile` site and the 2-arg overload), so the gate is correctly localized.
Commit 0f22bb7221 added the gate plus the no-op-skip and genuine-truncate tests;
review-fix commit 6828bfce30 strengthened the test set. The 7-agent dimensional
review confirmed S3 holds and that the gate removes the `removeByFileId` O(capacity)
sweep on the no-op path.

**What was discovered:** The first no-op test was vacuous: it seeded pages 0–4 and
shrank to `5*PAGE_SIZE`, so the would-be `minPageIndex=5` matched no seeded page and
every assertion passed even with the `if (truncated)` gate deleted. The rewrite seeds
6 pages (0–5) and shrinks to `3*PAGE_SIZE` (`minPageIndex=3`) so an un-gated purge
would evict pages 3,4,5; falsifiability was confirmed by deleting the gate (test
failed `expected:<24576> but was:<12288>`) and restoring it. A test-only `assert`
pinning S3 on the no-op branch was considered and declined: the `WriteCache` SPI has
no byte-valued per-file size accessor (`fileId`-keyed candidates are page-valued and
`TrackingWriteCache` returns 0, so the assert trips spuriously under `-ea`;
`getFilledUpTo` is deprecated and audit-gated). S3 is already proven
infeasible-to-violate by Phase A adversarial review and is further pinned by Track 2's
S2 work. JaCoCo's report goal is bound to the `prepare-package` phase, not `test`, so
a `mvnw test -P coverage` run leaves `jacoco.xml` stale; the changed-line gate must
run through `package -P coverage` (scoped via `-Dtest=…`) to regenerate the XML.

**Key files:**
- `core/.../storage/cache/chm/LockFreeReadCache.java` (modified — `if (truncated)` gate)
- `core/.../storage/cache/chm/LockFreeReadCacheFileOpsTest.java` (modified — non-vacuous no-op test + `>=`-boundary test)

## Validation and Acceptance

- A `shrinkFile` whose `targetBytes >= currentFileSize` performs no read-cache
  purge: `removeByFileId` is not invoked, and cache entries for the file are
  untouched.
- A `shrinkFile` that physically truncates still evicts cache entries at
  `pageIndex >= minPageIndex` and preserves entries below `minPageIndex`
  (existing behavior unchanged).
- `WriteCache.shrinkFile` returns `true` exactly when `AsyncFile` was shrunk,
  `false` on the no-op; `DirectMemoryOnlyDiskCache` returns `false`.
- The full existing `WOWCacheShrinkFileTest`, `LockFreeReadCacheFileOpsTest`,
  and `*VerifyAndTruncateOrphansTest` suites still pass.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Both steps are deterministic source edits with no on-disk or data-migration
component: re-applying a step's commit produces identical bytes, and a failed
step is recovered by the Phase B revert path (`git reset --hard HEAD`) followed
by a re-attempt.

Runtime recovery semantics (Step 2): the change alters recovery-time behavior
(the orphan-truncation pass) but preserves S3 — the read-cache purge still runs
on every genuine truncate, so a crash during the pass leaves the same
post-recovery state as today (the next dirty reopen re-runs the pass). A no-op
shrink drops no pages, so an interrupted no-op leaves nothing to reconcile.

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In scope (production):**
- `core/.../storage/cache/WriteCache.java` — `shrinkFile` return type (`void` → `boolean`).
- `core/.../storage/cache/local/WOWCache.java` — `shrinkFile` impl (`false` at the no-op early-return, `true` after the real truncate).
- `core/.../storage/memory/DirectMemoryOnlyDiskCache.java` — the **2-arg** `WriteCache.shrinkFile` returns `false`; the separate **3-arg** `ReadCache.shrinkFile` forwarder discards the boolean and is unchanged.
- `core/.../storage/cache/chm/LockFreeReadCache.java` — `shrinkFile` (`:673`) conditional purge, gated at the `clearFile` call site (`:720`).

**In scope (tests):**
- `WOWCacheShrinkFileTest` (extend for the boolean contract) and
  `LockFreeReadCacheFileOpsTest` (conditional-purge tests + `TrackingWriteCache`).
- The 7 `WriteCache.shrinkFile` overriders are PSI-confirmed: 2 production
  (`WOWCache`, `DirectMemoryOnlyDiskCache`) + 5 test mocks. Four of the five mocks
  intentionally `throw UnsupportedOperationException` (`MockedWriteCache` in
  `AsyncReadCacheTestIT` / `LockFreeReadCacheConcurrentTestIT` /
  `LockFreeReadCacheBatchingTest`; `PageFrameWriteCache` in
  `LockFreeReadCacheOptimisticTest`) and are pinned by
  `testShrinkFileMockThrowsUnsupportedOperation` guard tests — the migration
  changes only their return type, not the throwing body. Only `TrackingWriteCache`
  (`LockFreeReadCacheFileOpsTest`) returns a real boolean (test-controlled, default `true`).
- `AbstractStorageTruncateOrphansAfterRecoveryTest` uses a Mockito
  `mock(WriteCache.class)` — it is **not** a source overrider and needs no edit
  (Mockito returns the `boolean` default). It mocks `ReadCache` directly and
  asserts on `verifyAndTruncateOrphans` (no `shrinkFile` assertions), so it is
  unaffected; re-run it after the change.

**Out of scope:**
- `ConcurrentLongIntHashMap.removeByFileId` itself (no signature/behavior change —
  it is simply no longer called on a no-op).
- `truncateFile`/`closeFile`/`deleteFile` purge behavior (unconditional, unchanged).
- `StorageComponent.verifyAndTruncateOrphans` (unchanged; it still calls
  `shrinkFile` unconditionally — the skip happens one layer down).

**Signatures:**
- `boolean WriteCache.shrinkFile(long fileId, long targetBytes)` (was `void`).
- `LockFreeReadCache.shrinkFile(long, long, WriteCache)` unchanged externally;
  only its internal call to `clearFile` becomes conditional.

**Dependencies:** none inbound. Track 2 depends on this track (the crash-recovery
path it tests benefits from the cheap pass, and the two axes are validated together).

## Base commit
6ed334837049ba42972ef34cb80efdd8ade6c95e

Note: recorded base 6ed334837049ba42972ef34cb80efdd8ade6c95e was stale (a
post-Phase-B rebase onto develop pulled in #1110, rewriting every on-branch
commit); using actual on-branch parent
afdb5c2d2fea1afdb35851c3aaf93702ebcef2b2 (the "Phase A review and
decomposition for Track 1" commit, parent of the "Record Phase B base
commit" commit) for this Phase C. The same rebase rewrote every step-commit
SHA, so the citations throughout this file were recomputed to their
post-rebase counterparts: Step 1 `909ee97829`, Step 2 code `0f22bb7221`,
Step 2 review-fix `6828bfce30`.
