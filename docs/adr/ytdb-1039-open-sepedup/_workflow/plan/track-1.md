<!-- workflow-sha: 9a34db786e015e1a0c6d7c4d80932afbddda6a0b -->
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
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review
- [ ] Track completion

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

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

## Plan of Work

The change is a narrow contract refinement on the `shrinkFile` SPI plus a
guarded call in the read-cache orchestration.

1. Change `WriteCache.shrinkFile(long, long)` to return `boolean` — `true` iff
   the file was physically shrunk, `false` on the pre-flight no-op. Update
   `WOWCache.shrinkFile` to return `false` at the `fileSize <= targetBytes`
   early-return and `true` after a real truncate.
2. Update the other `WriteCache` implementors: `DirectMemoryOnlyDiskCache.shrinkFile`
   returns `false` (no physical store), and each of the 6 in-tree test
   `WriteCache` mocks returns `false` (PSI-confirmed list in
   `## Interfaces and Dependencies`).
3. In `LockFreeReadCache.shrinkFile`, capture the boolean from
   `writeCache.shrinkFile(...)` and call `clearFile(fileId, minPageIndex, writeCache)`
   only when it is `true`. Preserve all argument-validity guards (they run before
   the delegate). `truncateFile`/`closeFile`/`deleteFile` keep their unconditional
   `clearFile` — they always drop pages — so the 3-arg `clearFile` change is
   localized to the `shrinkFile` path.
4. Tests: assert the no-op shrink performs no purge (spy/counter on
   `removeByFileId` or assert below-target cache entries survive with no sweep),
   and that a genuine shrink still evicts entries at `pageIndex >= minPageIndex`
   while preserving entries below it. Extend `WOWCacheShrinkFileTest` for the
   boolean contract and `LockFreeReadCacheFileOpsTest` for the conditional purge.

Ordering: step 1 must precede steps 2-3 (the SPI return type drives both impls
and the caller). Invariant to preserve: S3 — the purge runs iff the write-cache
layer truncated.

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered roster here. -->

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step. Empty at Phase 1. -->

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
<!-- Phase A placeholder — names per-step idempotence and recovery paths once steps are decomposed. -->

## Artifacts and Notes
<!-- Continuous-log (rare). Often empty. -->

## Interfaces and Dependencies

**In scope (production):**
- `core/.../storage/cache/WriteCache.java` — `shrinkFile` return type.
- `core/.../storage/cache/local/WOWCache.java` — `shrinkFile` impl.
- `core/.../storage/memory/DirectMemoryOnlyDiskCache.java` — `shrinkFile` impl.
- `core/.../storage/cache/chm/LockFreeReadCache.java` — `shrinkFile` conditional purge.

**In scope (tests):**
- `WOWCacheShrinkFileTest`, `LockFreeReadCacheFileOpsTest`, and the 6 test
  `WriteCache` mocks that override `shrinkFile` (PSI-confirmed): the inner mocks
  in `LockFreeReadCacheOptimisticTest` (`PageFrameWriteCache`),
  `LockFreeReadCacheFileOpsTest` (`TrackingWriteCache`),
  `LockFreeReadCacheConcurrentTestIT`, `AsyncReadCacheTestIT`,
  `LockFreeReadCacheBatchingTest` (`MockedWriteCache`).

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
