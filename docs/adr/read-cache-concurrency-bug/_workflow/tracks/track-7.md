# Track 7: Recovery-time orphan-truncation pass

## Description

Add a new private `AbstractStorage.truncateOrphansAfterRecovery()` pass —
called from `open()` AFTER `openCollections` / `openIndexes` /
`linkCollectionsBTreeManager::load` populate the iteration targets, and
from `DiskStorage.postProcessIncrementalRestore` AFTER `flushAllData()`
runs so WAL-replay-buffered dirty pages settle before truncation — that
walks each EP-equipped storage component, reads its EP page logical-page
count, and truncates physical orphans through a layered shrink:
`LockFreeReadCache.shrinkFile(fileId, targetBytes, writeCache)`
orchestrates a new `WriteCache.shrinkFile(fileId, targetBytes)` then a
range-scoped LFRC purge — mirroring the existing
`LockFreeReadCache.truncateFile` two-phase pattern. The pass restores
Invariant I6 (`entryPoint.logicalPages <= AsyncFile.getFileSize() /
pageSize`, with equality after a successful truncate) before any TX
runs. Scope is intentionally limited to the four EP-equipped components
subject to CS1 (`BTree`, `SharedLinkBagBTree`, `CollectionPositionMapV2`,
`PaginatedCollectionV2`). EP-less components (`FreeSpaceMap`,
`CollectionDirtyPageBitSet`) and `IndexHistogramManager` are deliberately
excluded — see Non-Goals in `implementation-plan.md`, sharpened at Phase
A iter-1 to acknowledge the `checksumMode=StoreAndThrow` exposure
pattern.

> **What**:
> - **New `AsyncFile.shrink(long size)` in-place semantics.** The
>   existing primitive at `AsyncFile.java:307-318` unconditionally sets
>   the in-memory `AtomicLong size` to `0` regardless of the `size`
>   argument (all current production callers in `WOWCache` at `:969`,
>   `:1916`, `:2495` pass `0`, so the bug is latent). Track 7 is the
>   first non-zero caller. Step 1 fixes the body to `this.size.set(size)`
>   and adds a top-line `if (size >= this.size.get()) return;` no-op
>   guard. The existing `AsyncFileTest.testShrink` (zero-target path,
>   `AsyncFileTest.java:376`) stays unchanged; a new `testShrinkPartial`
>   allocates K pages, calls `shrink(M*pageSize)` with `0 < M < K`, and
>   asserts `getFileSize() == M*pageSize` plus `fileChannel.size() ==
>   M*pageSize + HEADER_SIZE`.
> - **New `WriteCache.shrinkFile(long fileId, long targetBytes)`
>   primitive.** Today `WriteCache` exposes `truncateFile(long fileId)`
>   (truncate to zero, used for `DROP_FILE`-style operations);
>   `shrinkFile` is distinct because it takes a target size and the
>   shrink direction is one-way (no growth). The WOWCache implementation
>   acquires `filesLock.writeLock`, calls `removeCachedPages(intId)` to
>   drop `writeCachePages` entries for the file (mirroring
>   `WOWCache.truncateFile`'s ordering at `WOWCache.java:1904-1927` —
>   purge writeCachePages first to avoid a flush extending the file
>   back past `targetBytes`), then delegates to
>   `AsyncFile.shrink(targetBytes)`. The `DirectMemoryOnlyDiskCache`
>   implementation is a no-op (in-memory engine cannot produce on-disk
>   orphans). The five test-mock `WriteCache` implementers
>   (`PageFrameWriteCache` in `LockFreeReadCacheOptimisticTest`;
>   `MockedWriteCache` inner classes in `AsyncReadCacheTestIT`,
>   `LockFreeReadCacheBatchingTest`, `LockFreeReadCacheConcurrentTestIT`;
>   `TrackingWriteCache` in `LockFreeReadCacheFileOpsTest`) override to
>   throw `UnsupportedOperationException`. Pre-flight check: if
>   `AsyncFile.getFileSize() <= targetBytes`, the method returns without
>   invoking `shrink` or `removeCachedPages` so a clean shutdown is a
>   true no-op. **Note**: `removeCachedPages` is bulk-by-fileId today;
>   Phase A iter-2 / Phase B decides whether `WriteCache.shrinkFile`
>   accepts the bulk purge (acceptable on the recovery path because
>   `stateLock.writeLock` excludes concurrent allocators) or grows a
>   range-scoped variant.
> - **New `LockFreeReadCache.shrinkFile(long fileId, long targetBytes,
>   WriteCache writeCache)` orchestrator.** Mirrors the existing
>   `LockFreeReadCache.truncateFile` / `deleteFile` two-phase shape at
>   `LockFreeReadCache.java:666-671`: call
>   `writeCache.shrinkFile(fileId, targetBytes)` to settle the
>   write-back layer + AsyncFile, then a new private
>   `clearFileRange(long fileId, int minPageIndex, WriteCache
>   writeCache)` to purge LFRC entries at `pageIndex >= minPageIndex`.
>   `clearFileRange` mirrors `LockFreeReadCache.clearFile` at
>   `LockFreeReadCache.java:796-839` (acquires `evictionLock`, flushes
>   the current-thread read batch, calls a range-scoped removal,
>   freezes/notifies the policy) — the only difference is the segment-
>   map call. Phase A iter-2 / Phase B picks placement:
>   - **Option A**: add `ConcurrentLongIntHashMap.removeByFileIdAtLeast(long
>     fileId, int minPageIndex) : List<V>` next to the existing
>     `removeByFileId(long)` at the segment map
>     (`internal/common/collection/ConcurrentLongIntHashMap.java`) — true
>     range purge under the per-segment write lock.
>   - **Option B**: fetch via `removeByFileId(fileId)` then re-insert
>     entries with `pageIndex < minPageIndex` — simpler at the segment
>     map but disturbs LFRC entries that didn't need to move.
>   - **Recommendation**: Option A. Step 2 PSI-verifies callers of
>     `removeByFileId` before adding the sibling method.
> - **Per-component `verifyAndTruncateOrphans(AtomicOperation,
>   LockFreeReadCache, WriteCache)` helper.** Each of the four
>   EP-equipped components implements a helper that:
>   1. Loads its entry-point page read-only via
>      `op.loadPageForRead(fileId, entryPointIndex)`.
>   2. Reads logical page count from the EP — `pagesSize` for
>      `CellBTreeSingleValueEntryPointV3` (BTree) and
>      `ridbagbtree.EntryPoint` (SLBB); `fileSize` for `MapEntryPoint`
>      (CPMV2) and `PaginatedCollectionStateV2` (PCV2). Releases the
>      page.
>   3. Computes `targetBytes = max(pageSize, (logicalPages + offset) *
>      pageSize)` where `offset` is component-specific (Phase A iter-2
>      audit target — locked down per the EP-counted vs data-only
>      convention each component uses in its `create()` path). The
>      `max(pageSize, …)` floor guarantees the EP page itself is
>      preserved even on an uninitialized / freshly-created
>      `EP.pagesSize == 0` shape.
>   4. **Guard**: if `EP.pagesSize == 0 && AsyncFile.getFileSize() >
>      pageSize`, skip with a WARN-level log (`storage corruption signal:
>      empty entry-point with non-empty file`) — the recovery pass
>      intentionally does not silently mask a `logical > physical`-like
>      shape that WAL replay is designed to prevent.
>   5. Calls `readCache.shrinkFile(this.fileId, targetBytes,
>      writeCache)`. The `shrinkFile` pre-flight makes this a no-op
>      when the file is already at or below the target.
>   The helper runs under whichever atomic-operation boundary the
>   caller (i.e., `truncateOrphansAfterRecovery`) supplies.
> - **`v3.BTree.getFileId()` accessor.** The other three EP-equipped
>   components expose `getFileId() -> long`; BTree has a private
>   `fileId` field but no getter (PSI-confirmed at Phase A iter-1).
>   Track 7 adds the trivial accessor to unify the iteration recipe.
> - **`LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans
>   (AtomicOperation, LockFreeReadCache, WriteCache)`.** The manager
>   holds N≥0 SLBB instances in `fileIdBTreeMap : ConcurrentHashMap
>   <Integer, SharedLinkBagBTree>` and exposes no public iteration API.
>   Track 7 adds a method on the manager that iterates
>   `fileIdBTreeMap.values()` and calls each SLBB's
>   `verifyAndTruncateOrphans(op, readCache, writeCache)`.
> - **`AbstractStorage.truncateOrphansAfterRecovery()` orchestrator.**
>   Iterates the four EP-equipped component groups:
>   - `collections : List<StorageCollection>` — each entry is a
>     `PaginatedCollectionV2` (the sole concrete/instantiable
>     production inheritor of `StorageCollection`); call
>     `verifyAndTruncateOrphans` on the PCV2 instance AND on its
>     embedded `collectionPositionMap` field (`CollectionPositionMapV2`).
>     Note: the downcast is required to access `collectionPositionMap`;
>     `getFileId()` already lives on the abstract `PaginatedCollection`
>     base.
>   - `indexEngines : List<BaseIndexEngine>` — filter by
>     `instanceof BTreeSingleValueIndexEngine` (holds an `sbTree` field
>     typed `CellBTreeSingleValue<CompositeKey>`) || `instanceof
>     BTreeMultiValueIndexEngine` (holds `svTree` + `nullTree` fields,
>     both `CellBTreeSingleValue<CompositeKey>`). The field type is the
>     `CellBTreeSingleValue` interface, but the sole production
>     inheritor is the generic
>     `com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`,
>     so each field value is downcastable to `v3.BTree`. After the
>     engine-class `instanceof` check, cast the field value to
>     `v3.BTree` and call `verifyAndTruncateOrphans` on it. For
>     `BTreeMultiValueIndexEngine`, iterate BOTH `svTree` AND
>     `nullTree` if `nullTree != null` (Phase A iter-2 PSI-confirms
>     whether `nullTree` can grow under multi-page null-key load; if
>     single-page-by-construction the iter-2 audit downgrades to
>     `svTree`-only).
>   - `linkCollectionsBTreeManager` — call
>     `verifyAndTruncateAllOrphans(op, readCache, writeCache)`
>     (iteration is internal to the manager).
>   The orchestrator is wrapped in
>   `atomicOperationsManager.executeInsideAtomicOperation(...)` —
>   matches the catalogue-load idiom at `AbstractStorage.java:797-802`.
>   The orchestrator is silent on no-op (clean shutdown / no orphans);
>   on truncate it emits a one-line WARN log naming the component +
>   fileId + pre/post-truncate page counts + delta pages so operators
>   see when CS1 actually fires.
> - **Wiring (two entry points).**
>   - `AbstractStorage.open()`: insert a call to
>     `truncateOrphansAfterRecovery()` AFTER line 800 (`openIndexes`)
>     and BEFORE the first non-recovery TX. Called unconditionally
>     (NOT gated by `wereDataRestoredAfterOpen` — orphan creation can
>     survive a crash → clean-reopen-without-touch → clean reclose, so
>     a subsequent open with `isDirty() == false` still needs the
>     pass). `recoverIfNeeded()` at `AbstractStorage.java:764` already
>     ran `flushAllData()` internally (`AbstractStorage.java:4497`),
>     so the flush executor is drained before the pass fires.
>   - `DiskStorage.postProcessIncrementalRestore`: insert a call
>     **AFTER** `:1673` (`flushAllData()`). The earlier-spec'd
>     "between `:1671` and `:1673`" placement is wrong because at that
>     site WAL replay has left dirty pages in `writeCachePages` and a
>     truncate-then-flush ordering would silently re-create the orphan
>     when `flushAllData()` writes pages past `targetBytes`. Placing
>     the pass post-flush keeps both entry points in the same
>     post-flush regime.
> - **Unit + integration tests.** Cumulative test surface:
>   - `AsyncFileTest.testShrinkPartial` — partial-shrink semantics
>     regression for the AsyncFile fix.
>   - Per-component helper unit tests (one suite per component):
>     orphan-present → `LockFreeReadCache.shrinkFile` called with
>     correct target; clean (`logical == physical`) → no-op;
>     `EP.pagesSize == 0 && fileSize > pageSize` → skip-with-WARN
>     (assert log).
>   - `LockFreeReadCache.shrinkFile` orchestration test: install a
>     `CachePointer` at index past the post-shrink target, call
>     `shrinkFile`, assert the entry is no longer in the cache.
>   - `WOWCache.shrinkFile` cache-purge test: install a dirty
>     `writeCachePages` entry at index past the post-shrink target,
>     call `shrinkFile`, run a synthetic `flushAllData`, assert the
>     file size matches `targetBytes` and the dirty entry did NOT
>     re-extend the file.
>   - `truncateOrphansAfterRecovery` orchestrator unit test:
>     mock-WriteCache + mock-LockFreeReadCache + mock components,
>     assert iteration order + correct dispatch + `nullTree` handling.
>   - Integration tests against a real `WOWCache`:
>     - **Positive (primary) — deterministic orphan fabrication**:
>       open a storage normally, use a test helper to write N extra
>       pages past `EP.pagesSize` via `AsyncFile.write` (bypassing
>       the allocator), close, reopen, assert the pass truncates and
>       the next TX completes without `IllegalStateException`. Fast,
>       deterministic, exercises the production path without sub-JVM
>       coordination.
>     - **Positive (confirmation) — sub-JVM crash** (slower, tagged
>       integration): drive `commitChanges` to its WAL-buffered state
>       on the disk engine, kill the JVM mid-flight via
>       `Runtime.exec` (`LocalPaginatedStorageRestoreFromWALIT`
>       precedent), reopen, assert `physical == logical`.
>     - **Negative (clean shutdown → no-op)**: assert the pass runs
>       but emits no truncate log line.
>     - **Incremental-restore entry point**: drive a backup with
>       concurrent writes (so `physicalSizeForBackupSnapshot` captures
>       a transient orphan-shape file), restore the backup, assert
>       `physical == logical` post-restore.
>   Track 6 owns the end-to-end CS1 verification (combined with the
>   FSM/CDPB/IHM symptom-surface coverage); Track 7's tests are scoped
>   to the recovery pass itself.
>
> **How**:
> - Step ordering (provisional, Phase A iter-2 may revise):
>   1. **Step 1**: Fix `AsyncFile.shrink(size)` semantics in place;
>      add `AsyncFileTest.testShrinkPartial`. Separable commit so a
>      future `git revert` of Track 7 internals preserves the fix.
>      **Risk: low** — single primitive body fix; all current callers
>      pass `0` which still resolves to `size.set(0)`.
>   2. **Step 2**: Introduce the layered shrink primitive.
>      - `WriteCache.shrinkFile(fileId, targetBytes)` SPI; WOWCache impl
>        (`filesLock.writeLock` + `removeCachedPages` for writeCachePages
>        purge + `AsyncFile.shrink`); DirectMemoryOnlyDiskCache no-op;
>        5 test-mock implementers throw UOE.
>      - `LockFreeReadCache.shrinkFile(fileId, targetBytes, writeCache)`
>        orchestrator that delegates to `writeCache.shrinkFile` then
>        calls private `clearFileRange(fileId, minPageIndex, writeCache)`.
>      - Range-purge backing on `ConcurrentLongIntHashMap` —
>        `removeByFileIdAtLeast(long fileId, int minPageIndex) :
>        List<V>` (recommended) or LFRC-side filter + re-insert.
>      - Unit tests for both production impls plus the
>        flush-after-truncate ordering pin (no re-extend).
>      **Risk: high** — interface addition touches 7 implementers; LFRC
>      orchestration ordering is load-bearing; a new segment-map
>      primitive sits in the cache hot-path supporting class.
>   3. **Step 3**: Add `verifyAndTruncateOrphans(AtomicOperation,
>      LockFreeReadCache, WriteCache)` to each of the four EP-equipped
>      components, plus the trivial `BTree.getFileId()` getter. Phase A
>      iter-2 audits each component's `create()` path to lock the
>      `offset` arithmetic (whether the EP's logical count includes
>      the EP page itself or not — varies per component). Per-component
>      unit tests covering orphan-present, clean, and corruption-
>      skip-with-WARN branches.
>      **Risk: medium** — touches four storage components; each has
>      its own EP shape; floor + corruption guard are uniform across
>      them.
>   4. **Step 4**: Add
>      `LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans`
>      (iteration delegate over `fileIdBTreeMap.values()`). Manager
>      unit test pins the iteration.
>      **Risk: low** — pure delegate-and-iterate.
>   5. **Step 5**: Add `AbstractStorage.truncateOrphansAfterRecovery()`;
>      wire into `open()` (after `:800` — `recoverIfNeeded()` has
>      already drained the flush executor at `:4497`) and
>      `postProcessIncrementalRestore` (AFTER `:1673` `flushAllData()`).
>      Both call sites wrapped in `executeInsideAtomicOperation`.
>      Integration tests: positive (primary — deterministic orphan
>      fabrication via `AsyncFile.write`), positive (confirmation —
>      sub-JVM crash, tagged integration), negative (clean shutdown),
>      incremental-restore.
>      **Risk: high** — recovery path, two entry points, side-effect
>      ordering vs `flushAllData()` resolved by post-flush placement
>      on both entry points; deterministic orphan-fabrication test
>      pattern is the primary regression source.
> - Phase A audit targets locked down at iter-2 (replan-2):
>   - **Placement**: `open()` after `:800` + `postProcessIncrementalRestore`
>     AFTER `:1673` (`flushAllData`). Wrapped in
>     `executeInsideAtomicOperation`.
>   - **Gating**: unconditional (NOT `wereDataRestoredAfterOpen`-gated).
>   - **`AsyncFile.shrink` fix**: in-place semantics fix as Step 1.
>   - **LFRC purge layering**: `LockFreeReadCache.shrinkFile`
>     orchestrates; `WriteCache.shrinkFile` does write-back +
>     `AsyncFile.shrink`; LFRC range purge via private
>     `clearFileRange` mirrors `truncateFile`'s two-phase pattern.
>   - **EP-page floor**: `targetBytes = max(pageSize, …)` in helper.
>   - **Corruption guard**: skip-with-WARN on
>     `EP.pagesSize == 0 && fileSize > pageSize`.
>   - **Iteration over SLBB instances**: via new manager method, not
>     a public `getAllManaged()` accessor.
>   - **`BTree.getFileId()`**: added as part of Step 3.
>   - **`BTreeMultiValueIndexEngine` coverage**: iterate both
>     `svTree` AND `nullTree` when `nullTree != null`.
>   - **Truncate log**: WARN level with pre/post page counts +
>     delta.
>   - **Truncate ordering safety**: `recoverIfNeeded()` calls
>     `flushAllData()` at `:4497` before `open()` proceeds; the
>     orchestrator runs after the catalogue load, and the only
>     other writer would be a client TX which is excluded by
>     `stateLock.writeLock()`. The orchestrator races only against
>     itself (single-threaded inside the lock).
> - Phase A audit targets remaining for Phase A iter-2 (after this
>   replan re-enters /execute-tracks):
>   - The exact `offset` arithmetic per component for `targetBytes`
>     — read each `create()` path to determine whether the EP's
>     logical count includes the EP page itself or excludes it.
>     Output as a four-row table (one per component: BTree, SLBB,
>     CPMV2, PCV2) documenting (1) where `pagesSize` / `fileSize`
>     is incremented in `create()`, (2) the resulting offset, (3)
>     the WAL op that establishes the increment, (4) a unit-test
>     assertion shape.
>   - PSI audit of `BTreeMultiValueIndexEngine.nullTree` growth —
>     confirm whether it can grow under multi-page null-key load
>     or is single-page-by-construction like `BTree.nullBucketFileId`.
>     If single-page, downgrade the orchestrator to `svTree`-only.
>   - PSI find-implementations on `WriteCache` to confirm the
>     test-mock implementer count (the in-scope file list assumes
>     exactly five; iter-2 verifies no sixth implementer exists in
>     `embedded` / `tests` / `jmh-ldbc` modules).
>   - PSI audit of `v3.BTree.nullBucketFileId` — confirm
>     single-page-by-construction so the carve-out from
>     `verifyAndTruncateOrphans` is invariant-safe.
>   - Range-purge placement decision (segment-map sibling vs
>     LFRC-side filter) — Step 2 lockdown.
>
> **Constraints**:
> - **In-scope files**:
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/fs/AsyncFile.java` (semantics fix)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java` (new `shrinkFile` method)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` (impl)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCache.java` (no-op impl)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCache.java` (new `shrinkFile` orchestrator + private `clearFileRange`)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/common/collection/ConcurrentLongIntHashMap.java` (new `removeByFileIdAtLeast` if range purge is pushed into the segment map; skip if filter is kept inside LFRC — Step 2 lockdown)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java` (new helper + getFileId getter)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/ridbagbtree/SharedLinkBagBTree.java` (new helper)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/LinkCollectionsBTreeManagerShared.java` (new iteration delegate)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/CollectionPositionMapV2.java` (new helper)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/PaginatedCollectionV2.java` (new helper)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (new orchestrator + open() wiring)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/disk/DiskStorage.java` (postProcessIncrementalRestore wiring)
>   - Five test-mock `WriteCache` implementers (`UnsupportedOperationException` overrides):
>     `core/src/test/java/.../cache/chm/LockFreeReadCacheOptimisticTest.java`,
>     `core/src/test/java/.../cache/chm/AsyncReadCacheTestIT.java`,
>     `core/src/test/java/.../cache/chm/LockFreeReadCacheBatchingTest.java`,
>     `core/src/test/java/.../cache/chm/LockFreeReadCacheConcurrentTestIT.java`,
>     `core/src/test/java/.../cache/chm/LockFreeReadCacheFileOpsTest.java`.
>     (PSI find-implementations at iter-2 verifies no sixth implementer
>     exists.)
>   - New unit + integration tests under `core/src/test/...`.
> - **Out of scope**: EP-less components (FSM, CDPB) and
>   `IndexHistogramManager` — see D6 Risks/Caveats and Non-Goals.
>   Public API renames. Any change to
>   `WriteCache.getFilledUpTo` / `truncateFile` semantics.
> - **Performance constraint**: the recovery pass adds
>   ~100-300 single-page EP-load reads per storage open (one per
>   in-scope component instance, in-cache after `openIndexes` warms
>   the LFRC). Per-component cost: one `loadPageForRead` + one
>   `AsyncFile.getFileSize` + one comparison + (rare) one
>   `LockFreeReadCache.shrinkFile`. Cost is paid once per `open()` /
>   `postProcessIncrementalRestore`; expected impact on storage-open
>   latency is negligible. Track 7 does NOT add periodic or per-TX
>   cost.
> - **WAL constraint**: the pass does NOT generate WAL records. The
>   truncate happens post-replay; the entry-point logical state is
>   already consistent (replayed from WAL). Any subsequent TX that
>   needs to grow the file regenerates the physical pages through
>   the normal `loadOrAdd` path with WAL-tracked allocation. Because
>   the truncate is unlogged, an arithmetic bug in `offset` is
>   irrecoverable — the EP-page floor + corruption guard in
>   `verifyAndTruncateOrphans` are the only safety nets.
>
> **Interactions**:
> - **Depends on Track 4** — Track 4's AOBT allocator-only contract is
>   what makes the orphan reachable as an `IllegalStateException` (and
>   thus motivates this track). Track 7 does not modify Track 4 code.
> - **Independent of Track 5** — Track 5's gated-helper work is
>   parallel and non-conflicting. Track 7 reads physical size via
>   `AsyncFile.getFileSize() / pageSize` internally to its new
>   `shrinkFile` primitive; it does NOT call
>   `WriteCache.getFilledUpTo` or Track 5's `StorageComponent.physicalSize`
>   on the recovery path (different lifecycle from steady-state
>   discovery — recovery happens before the per-TX read patterns the
>   Track 5 helpers serve).
> - **Feeds Track 6** — Track 6's CS1 integration test asserts the
>   post-replay invariant Track 7 establishes (I6). The Track 6 CS1
>   coverage also exercises FSM/CDPB/IHM partial-flush scenarios
>   under both `checksumMode=Off` and `checksumMode=StoreAndThrow`
>   (since Track 7 deliberately excludes those components per the
>   Non-Goals sharpening).
> - **Implements D6 and establishes I6.** See `implementation-plan.md`
>   §Architecture Notes (D6 revised at iter-1 v2 for layered LFRC
>   purge + restore-path wiring; I6 tightened from `==` to `<=`;
>   Integration Points revised; Non-Goals sharpened — all at Phase A
>   iter-1).
> - **Crash-recovery coordination**: the pass runs **after**
>   `recoverIfNeeded()` returns (so WAL replay has settled logical
>   state and `flushAllData()` has drained the flush executor on the
>   `open()` path) and **after** `flushAllData()` on the
>   `postProcessIncrementalRestore` path. Both entry-point sequences
>   end up post-flush before the orchestrator fires, eliminating the
>   flush-after-truncate orphan re-creation hazard. The new
>   orchestrator does not interact with the WAL directly.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
