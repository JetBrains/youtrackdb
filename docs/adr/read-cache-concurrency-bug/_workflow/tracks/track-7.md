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
>   and adds a no-op guard `if (size >= this.size.get()) return;` placed
>   **after** `lock.exclusiveLock(); checkForClose();` so the read of
>   `this.size` happens inside the exclusive-lock window (avoids a
>   TOCTOU race against a concurrent `shrink` / `allocateSpace` writer;
>   locked at iter-1). The existing `AsyncFileTest.testShrink`
>   (zero-target path, `AsyncFileTest.java:376`) stays unchanged; a new
>   `testShrinkPartial` allocates K pages, calls `shrink(M*pageSize)`
>   with `0 < M < K`, and asserts `getFileSize() == M*pageSize` plus
>   `fileChannel.size() == M*pageSize + HEADER_SIZE`.
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
>   true no-op. **Range-scoped purge (locked at iter-1)**: the
>   `writeCachePages` purge is range-scoped, not bulk-by-fileId. Step 2
>   adds `WOWCache.removeCachedPagesAtLeast(intId, minPageIndex)` which
>   iterates `writeCachePages.entrySet()` and removes entries matching
>   `pageKey.fileId == intId && pageKey.pageIndex >= minPageIndex`
>   (mirrors `doRemoveCachePages` at `WOWCache.java:3537` with a range
>   filter). The bulk variant `removeCachedPages(intId)` is
>   inappropriate here because the orchestrator runs after
>   `openCollections` / `openIndexes` / `wireHistogramManagerOnLoad`
>   atomic ops have completed and may have left dirty entries in
>   `writeCachePages` for in-scope fileIds; a bulk purge would silently
>   discard them (no `writePages` before removal — see
>   `doRemoveCachePages:3548-3567`). The range variant preserves dirty
>   entries at `pageIndex < minPageIndex` and only discards the
>   physical-orphan region (`pageIndex >= targetBytes / pageSize`),
>   which is exactly the file region the truncate is about to drop.
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
>   map call. **Range-purge backing (locked at iter-1 to Option A)**:
>   Step 2 adds `ConcurrentLongIntHashMap.removeByFileIdAtLeast(long
>   fileId, int minPageIndex) : List<V>` next to the existing
>   `removeByFileId(long)` at the segment map
>   (`internal/common/collection/ConcurrentLongIntHashMap.java`) with a
>   per-segment range filter under the existing write-lock. PSI at
>   iter-1 confirms `removeByFileId` has exactly 1 production caller
>   (`LockFreeReadCache.clearFile:813`); the sibling addition has
>   bounded blast radius. The rejected alternative (fetch via
>   `removeByFileId` then re-insert entries below `minPageIndex`)
>   disturbs LFRC entries that didn't need to move and breaks WTinyLFU
>   sketch idempotence on policy state.
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
>   3. Computes `targetBytes = max(pageSize, (epLogicalCounter + 1) *
>      pageSize)`. **Uniform `offset = +1` (locked at iter-1,
>      PSI-audited against each component's `create()` path)**: in all
>      four components the EP-stored counter records the highest
>      occupied pageIndex (EP page itself sits at pageIndex 0
>      implicitly), so total physical pages = `counter + 1`.
>
>      | Component | EP-stored counter | Init in `create()` | Extend pattern | Test assertion |
>      |---|---|---|---|---|
>      | BTree (`v3.BTree`) | `pagesSize` (`CellBTreeSingleValueEntryPointV3.pagesSize`) | `init()` sets `pagesSize=1` (root + EP; `BTree:170-226` allocates `ENTRY_POINT_INDEX=0`, `ROOT_INDEX=1`) | `BTree:2185-2188`: `newIdx = getPagesSize()+1; allocate; setPagesSize(newIdx)` | physical pages == `pagesSize + 1` |
>      | SLBB (`SharedLinkBagBTree`) | `pagesSize` (`ridbagbtree.EntryPoint.pagesSize`) | `init()` sets `pagesSize=1` (`SharedLinkBagBTree:51-77`) | `SharedLinkBagBTree:935-937` same pattern | physical pages == `pagesSize + 1` |
>      | CPMV2 (`CollectionPositionMapV2`) | `fileSize` (`MapEntryPoint.fileSize`) | `create():133-153` allocates EP page 0 then `setFileSize(0)` (Javadoc: "does NOT include EP page") | `CollectionPositionMapV2:224-225`: `allocate(lastPage+1); setFileSize(lastPage+1)` | physical pages == `fileSize + 1` |
>      | PCV2 (`PaginatedCollectionV2`) | `fileSize` (`PaginatedCollectionStateV2.fileSize`) | `initCollectionState()` allocates `STATE_ENTRY_INDEX=0` then `setFileSize(0)` | `PaginatedCollectionV2:2237-2256`: `allocate(fileSize+1); setFileSize(fileSize+1)` | physical pages == `fileSize + 1` |
>
>      The `max(pageSize, …)` floor guarantees the EP page itself is
>      preserved even on an uninitialized / freshly-created shape where
>      `epLogicalCounter == 0`.
>   4. **Corruption guard**: if `epLogicalCounter == 0 &&
>      AsyncFile.getFileSize() > pageSize`, skip with a WARN-level log
>      (`storage corruption signal: empty entry-point with non-empty
>      file`) — the recovery pass intentionally does not silently mask
>      a `logical < physical`-inverse-shape that WAL replay is designed
>      to prevent. Per-component `epLogicalCounter` field binding
>      (locked at iter-1): `pagesSize` for BTree / SLBB; `fileSize` for
>      CPMV2 / PCV2 (see uniform-offset table above). Asymmetry note:
>      BTree/SLBB EPs `init()` `pagesSize = 1`, so `pagesSize == 0` is
>      itself anomalous; CPMV2/PCV2 EPs `init()` `fileSize = 0`, which
>      is the legitimate post-create state — the
>      `fileSize_AsyncFile > pageSize` second clause is what
>      discriminates a healthy fresh CPMV2/PCV2 (physical == `pageSize`,
>      one EP page only) from a real `logical < physical` shape.
>   5. **EP-read failure**: if `op.loadPageForRead(fileId,
>      entryPointIndex)` throws (e.g., corrupted EP page under
>      `checksumMode=StoreAndThrow`), the exception propagates through
>      `verifyAndTruncateOrphans` to `truncateOrphansAfterRecovery` and
>      aborts `open()` / `postProcessIncrementalRestore`. This is
>      intentional: without the EP we cannot compute `targetBytes`, and
>      silently skipping the component would re-introduce the
>      partial-flush-orphan path. Operators handle this case via the
>      existing storage-corruption runbook.
>   6. Calls `readCache.shrinkFile(this.fileId, targetBytes,
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
>   - `collections : List<StorageCollection>` (FQN:
>     `com.jetbrains.youtrackdb.internal.core.storage.StorageCollection`
>     — one level up from the `…storage.collection.` sub-package).
>     PSI-confirmed at iter-1: inheritor count = 2 (`PaginatedCollection`
>     abstract + `PaginatedCollectionV2` concrete); PCV2 is the sole
>     concrete/instantiable production inheritor. Call
>     `verifyAndTruncateOrphans` on the PCV2 instance AND on its
>     embedded `collectionPositionMap` field (`CollectionPositionMapV2`).
>     The downcast to `PaginatedCollectionV2` is required to access
>     `collectionPositionMap`; `getFileId()` already lives on the
>     abstract `PaginatedCollection` base.
>   - `indexEngines : List<BaseIndexEngine>` — filter by
>     `instanceof BTreeSingleValueIndexEngine` (FQN
>     `com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine`)
>     || `instanceof BTreeMultiValueIndexEngine` (FQN
>     `com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine`),
>     then call `engine.verifyAndTruncateOrphans(op, readCache,
>     writeCache)` on the engine itself. **Engine-side iteration
>     (locked at iter-1)**: the `sbTree` / `svTree` / `nullTree` fields
>     are `private final` on the engines, so iteration lives on the
>     engine. Step 3 adds `verifyAndTruncateOrphans` as a method on
>     `BTreeSingleValueIndexEngine` (delegates to `sbTree`) and on
>     `BTreeMultiValueIndexEngine` (calls
>     `svTree.verifyAndTruncateOrphans` and, when `nullTree != null`,
>     also `nullTree.verifyAndTruncateOrphans`). This parallels the
>     manager-side
>     `LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans`
>     recipe and keeps `sbTree` / `svTree` / `nullTree` encapsulated.
>     PSI at iter-1: `nullTree` IS a full multi-page-growing BTree
>     (`BTreeMultiValueIndexEngine.java:39-41`; `put(null, value)`
>     routes through `doPut(nullTree, …)` at `:382-385` with multi-page
>     growth under multi-null-key load) — no downgrade. The `v3.BTree`
>     field type lives at
>     `com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`
>     (sole production inheritor of `CellBTreeSingleValue<CompositeKey>`).
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
>     pass). `recoverIfNeeded()` at `AbstractStorage.java:764` ran
>     `flushAllData()` internally (`AbstractStorage.java:4497`) when
>     `isDirty()` returned true, so the flush executor is drained
>     before the pass fires on the dirty-reopen path; on the clean-
>     reopen path `recoverIfNeeded()`'s body is skipped entirely
>     because no work was buffered, which is still safe.
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
>   - Per-component helper unit tests (one suite per component:
>     BTree / SLBB / CPMV2 / PCV2): orphan-present → engine-side
>     `verifyAndTruncateOrphans` invokes `LockFreeReadCache.shrinkFile`
>     with `targetBytes = (epLogicalCounter + 1) * pageSize`; clean
>     (`physicalPages == epLogicalCounter + 1`) → no-op; **boundary
>     case** (`physicalFileSize == targetBytes` exactly) → pre-flight
>     no-op asserted via `shrinkFile` not invoked (catches off-by-one
>     in per-component arithmetic — see uniform-offset table above);
>     `epLogicalCounter == 0 && fileSize > pageSize` → skip-with-WARN
>     (assert log). Each test docstring cites the per-component
>     `create()` source line so the arithmetic stays anchored.
>   - `LockFreeReadCache.shrinkFile` orchestration test: install a
>     `CachePointer` at index past the post-shrink target, call
>     `shrinkFile`, assert the entry is no longer in the cache.
>   - `WOWCache.shrinkFile` range-purge symmetry test pair:
>     (a) above-target dirty entry — install a dirty `writeCachePages`
>     entry at `pageIndex >= targetBytes / pageSize`, call `shrinkFile`,
>     run synthetic `flushAllData`, assert the file size matches
>     `targetBytes` and the dirty entry did NOT re-extend the file;
>     (b) below-target dirty entry — install a dirty `writeCachePages`
>     entry at `pageIndex < targetBytes / pageSize`, call `shrinkFile`,
>     run synthetic `flushAllData`, assert the dirty page WAS persisted
>     and the file size at least covers that index. The pair captures
>     the silent-data-loss class of bug the range-scoped purge prevents.
>   - `ConcurrentLongIntHashMapTest.removeByFileIdAtLeast*` mirroring
>     the existing `removeByFileId` surface: empty range; full range
>     (`minPageIndex = 0`) equivalence to `removeByFileId`; range
>     crossing a segment boundary; concurrent allocations into the
>     same fileId while range-purge runs (recovery excludes
>     concurrency via `stateLock` + `filesLock`, but the cache
>     primitive must still be thread-safe).
>   - `truncateOrphansAfterRecovery` orchestrator unit test:
>     mock-WriteCache + mock-LockFreeReadCache + mock components,
>     assert iteration order + correct dispatch + `nullTree` handling.
>   - Integration tests against a real `WOWCache`:
>     - **Positive (primary) — deterministic orphan fabrication**:
>       open a storage normally, use a test helper to write N extra
>       pages past `epLogicalCounter` via `AsyncFile.allocateSpace(...)`
>       + `AsyncFile.write(...)` (bypassing the
>       `StorageComponent.allocatePageForWrite` cache-allocator path,
>       which is what bumps `epLogicalCounter` — raw `AsyncFile.write`
>       alone rejects offsets past current `size` via `checkPosition`,
>       so the helper must call `allocateSpace` first to extend
>       physical size, then `write` the orphan content). **Orphan-page
>       content must mirror the production crash path**: each orphan
>       page carries LSN `(-1, -1)` + the WOWCache magic stamp (the
>       byte layout `EnsurePageIsValidInFileTask.writeValidPageInFile`
>       produces at `WOWCache.java:3905-3924`), so the next read under
>       `checksumMode=StoreAndThrow` sees a clean empty page and not a
>       checksum-corruption error. Close, reopen, assert the pass
>       truncates and the next TX completes without
>       `IllegalStateException`. Fast, deterministic, exercises the
>       AsyncFile + orchestrator pre-flight path. The unit tests on
>       `WOWCache.shrinkFile` cover the cache-purge layering
>       separately (above-target discard + below-target preservation
>       pair).
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
>        (`filesLock.writeLock` + range-scoped
>        `removeCachedPagesAtLeast(intId, minPageIndex)` purge +
>        `AsyncFile.shrink`); DirectMemoryOnlyDiskCache no-op; 5
>        test-mock implementers throw UOE.
>      - `LockFreeReadCache.shrinkFile(fileId, targetBytes, writeCache)`
>        orchestrator that delegates to `writeCache.shrinkFile` then
>        calls private `clearFileRange(fileId, minPageIndex, writeCache)`.
>      - Range-purge backing: `ConcurrentLongIntHashMap.removeByFileIdAtLeast`
>        (Option A, locked at iter-1).
>      - Unit tests for both production impls plus the range-purge
>        symmetry pair (above-target discard + below-target preservation)
>        and the `ConcurrentLongIntHashMap` test surface mirroring
>        `removeByFileId`.
>      **Risk: high** — interface addition touches 7 implementers; LFRC
>      orchestration ordering is load-bearing; a new segment-map
>      primitive sits in the cache hot-path supporting class.
>   3. **Step 3**: Add `verifyAndTruncateOrphans(AtomicOperation,
>      LockFreeReadCache, WriteCache)` to each of the four EP-equipped
>      components (BTree, SLBB, CPMV2, PCV2) plus the trivial
>      `BTree.getFileId()` getter; add `verifyAndTruncateOrphans` on
>      the two index-engine classes (`BTreeSingleValueIndexEngine`
>      delegates to `sbTree`; `BTreeMultiValueIndexEngine` calls both
>      `svTree` and `nullTree` when non-null) so the orchestrator can
>      stay polymorphic against the engines without violating field
>      encapsulation. Per-component unit tests covering orphan-present,
>      clean, boundary-exact-target (catches off-by-one in uniform
>      `offset = +1` arithmetic), and corruption-skip-with-WARN
>      branches.
>      **Risk: medium** — touches four storage components plus two
>      index-engine classes; uniform `offset = +1` (see table above);
>      floor + corruption guard are uniform across them.
>   4. **Step 4**: Add
>      `LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans`
>      (iteration delegate over `fileIdBTreeMap.values()`). Manager
>      unit test pins the iteration.
>      **Risk: low** — pure delegate-and-iterate.
>   5. **Step 5**: Add `AbstractStorage.truncateOrphansAfterRecovery()`;
>      wire into `open()` (after `:800` — `recoverIfNeeded()` already
>      drained the flush executor at `:4497` when `isDirty()`; clean
>      reopens skip the drain entirely, which is still safe because no
>      work was buffered) and `postProcessIncrementalRestore` (AFTER
>      `:1673` `flushAllData()`).
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
>   - **EP-page floor**: `targetBytes = max(pageSize, (epLogicalCounter
>     + 1) * pageSize)` in helper. Uniform `offset = +1` (locked at
>     iter-1; PSI table above).
>   - **Corruption guard**: skip-with-WARN on
>     `epLogicalCounter == 0 && fileSize > pageSize`
>     (`epLogicalCounter` binds to `pagesSize` for BTree/SLBB and
>     `fileSize` for CPMV2/PCV2).
>   - **Iteration over SLBB instances**: via new manager method, not
>     a public `getAllManaged()` accessor.
>   - **Iteration over BTree index engines**: engine-side
>     `verifyAndTruncateOrphans` methods on
>     `BTreeSingleValueIndexEngine` / `BTreeMultiValueIndexEngine`
>     (locked at iter-1 — keeps `sbTree` / `svTree` / `nullTree`
>     `private final` fields encapsulated).
>   - **`BTree.getFileId()`**: added as part of Step 3.
>   - **`BTreeMultiValueIndexEngine` coverage**: iterate both
>     `svTree` AND `nullTree` when `nullTree != null`
>     (PSI-confirmed multi-page-growing at iter-1).
>   - **WriteCache implementer count**: PSI-confirmed at iter-1 —
>     exactly 7 inheritors (2 production + 5 test-mock). No sixth
>     implementer.
>   - **Range-purge backing**: `ConcurrentLongIntHashMap.removeByFileIdAtLeast`
>     (Option A, locked at iter-1).
>   - **Truncate log**: WARN level with pre/post page counts +
>     delta.
>   - **Truncate ordering safety (corrected at iter-1)**:
>     `recoverIfNeeded()` calls `flushAllData()` at `:4497` before
>     `open()` proceeds. `stateLock.writeLock()` (acquired at
>     `AbstractStorage.java:712`) excludes client TXs but does NOT
>     block the periodic flush executor
>     (`WOWCache.wowCacheFlushExecutor` runs on a separate thread and
>     takes `filesLock.readLock`). The actual exclusion of concurrent
>     flush writers during the truncate window comes from
>     `WOWCache.shrinkFile` acquiring `filesLock.writeLock` (mirrors
>     `WOWCache.truncateFile:1905-1927`); the periodic-flush path's
>     `filesLock.readLock` blocks until the truncate completes. With
>     the range-scoped purge locked above, the design does NOT depend
>     on `stateLock`-blocks-flushers — dirty pages at
>     `pageIndex < minPageIndex` survive, dirty pages at
>     `pageIndex >= minPageIndex` are dropped (which is the file
>     region about to be truncated).
>
> **Constraints**:
> - **In-scope files**:
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/fs/AsyncFile.java` (semantics fix)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java` (new `shrinkFile` method)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` (impl)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCache.java` (no-op impl)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCache.java` (new `shrinkFile` orchestrator + private `clearFileRange`)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/common/collection/ConcurrentLongIntHashMap.java` (new `removeByFileIdAtLeast` — Option A locked at iter-1)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (new `verifyAndTruncateOrphans` delegating to `sbTree`)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (new `verifyAndTruncateOrphans` calling both `svTree` and `nullTree` when non-null)
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
> - **Why FSM/CDPB/IHM are safely out of scope (locked at iter-1)**:
>   the Non-Goals carve-out is invariant-safe by construction.
>   - **`FreeSpaceMap` and `CollectionDirtyPageBitSet`**: both have no
>     logical/physical split. Their growth loops compute
>     `filledUpTo = physicalSize(...)` and grow from there
>     (`FreeSpaceMap:227-235`, `CollectionDirtyPageBitSet:202-211`).
>     Any physical-orphan past `filledUpTo` is structurally invisible
>     to the allocator-only contract: the next growth allocation
>     re-uses the next physical pageIndex (the orphan) via
>     `op.loadOrAddPageForWrite(fileId, filledUpTo)`, which adopts the
>     existing physical page on `loadOrAdd`'s extend branch. CS1
>     cannot fire here because these components do not allocate
>     through the allocator-only narrowed `op.allocatePageForWrite`.
>   - **`IndexHistogramManager`**: pre-existing handlers absorb the
>     partial-flush hazard. The page-1 discriminator
>     `op.filledUpTo > 1 ? load : allocate` at
>     `IndexHistogramManager.java:1928, 1997` structurally avoids the
>     allocator-only contract on the spill path, and
>     `IndexHistogramManager.java:1856-1866` has a warn-and-fall-back
>     path for the page-1-missing case.
>   - Track 6's CS1 integration test asserts this construction-safety
>     by exercising FSM/CDPB/IHM partial-flush workloads under both
>     `checksumMode=Off` and `checksumMode=StoreAndThrow`; the test is
>     a regression net, not a fix target.
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
> - **Uses Track 5's `StorageComponent.physicalSize` on the recovery path**
>   — Track 5's gated-helper work is parallel and non-conflicting; the
>   per-component `verifyAndTruncateOrphans` helpers (BTree, SLBB,
>   CPMV2, PCV2) all read physical size via
>   `physicalSize(atomicOperation, fileId, PhysicalReadIntent.RECOVERY_REBUILD)`,
>   which routes through `atomicOperation.filledUpTo(fileId)`. The new
>   `RECOVERY_REBUILD` enum constant in `PhysicalReadIntent` documents
>   the recovery lifecycle (the per-TX read patterns the other intents
>   serve are distinct). The new `shrinkFile` primitive itself
>   internally reads `AsyncFile.getFileSize() / pageSize` for the
>   pre-flight no-op check, but the orchestrator-side decision of
>   "is there an orphan tail?" routes through Track 5's helper, not
>   `WriteCache.getFilledUpTo` directly.
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
>   state and, on the dirty-reopen path where `isDirty()` returned
>   true, `flushAllData()` has drained the flush executor inside
>   `recoverIfNeeded()` at `:4497`; on the clean-reopen path
>   `recoverIfNeeded()`'s body is skipped entirely because no work
>   was buffered, which is still safe) and **after** `flushAllData()`
>   on the `postProcessIncrementalRestore` path. Both entry-point
>   sequences end up post-flush (or no-flush-needed) before the
>   orchestrator fires, eliminating the flush-after-truncate orphan
>   re-creation hazard. The new orchestrator does not interact with
>   the WAL directly.

## Progress
- [x] Review + decomposition
- [x] Step implementation (5/5 complete)
- [x] Track-level code review (1/3 iterations, PASS)

**PAUSED 2026-05-19 at Phase C track-completion approval pending user Approve / Review-mode Apply / ESCALATE**
- Handoff: `../handoff-track-7-phaseC.md`

## Reviews completed
- [x] Technical: PASS at iteration 2 (8 findings — 1 blocker + 4 should-fix accepted and applied; 3 suggestions deferred)
- [x] Risk: PASS at iteration 2 (6 findings — 2 should-fix accepted and applied; 3 suggestions deferred; 1 skip on Step 1 separability)
- [x] Adversarial: PASS at iteration 2 (8 findings — 2 should-fix accepted and applied; 1 suggestion (orphan-content LSN/magic) accepted; 3 suggestions deferred; 2 skip on SLBB iteration + Step 1 separability)

## Steps

- [x] Step 1: Fix `AsyncFile.shrink(long size)` in-place semantics; add `AsyncFileTest.testShrinkPartial`
  - [x] Context: safe
  > **Risk:** low — isolated bug fix (single primitive body fix at `AsyncFile.java:307-318`; all current `WOWCache` callers pass `0` which still resolves to `size.set(0)`; new partial-target path covered by `testShrinkPartial`)
  >
  > **What**: replace `this.size.set(0)` with `this.size.set(size)` and add a no-op guard `if (size >= this.size.get()) return;` placed AFTER `lock.exclusiveLock(); checkForClose();` (inside the exclusive-lock window so the read of `this.size` is race-free against concurrent `shrink` / `allocateSpace`). The existing `AsyncFileTest.testShrink` (zero-target path, `AsyncFileTest.java:376`) stays unchanged; add `testShrinkPartial` allocating K pages, calling `shrink(M*pageSize)` with `0 < M < K`, asserting `getFileSize() == M*pageSize` and `fileChannel.size() == M*pageSize + HEADER_SIZE`.
  > **Tests**: `AsyncFileTest.testShrink` (unchanged) + new `testShrinkPartial`.
  > **Separability**: this step is intentionally separable from the rest of the track — a future `git revert` of Track 7 internals preserves the AsyncFile fix.
  >
  > **What was done:** Replaced `AsyncFile.shrink`'s unconditional
  > `this.size.set(0)` with `this.size.set(size)` so the in-memory
  > logical-size counter tracks the truncate target; added a pre-flight
  > no-op guard `if (size >= this.size.get()) return;` placed inside the
  > exclusive-lock window (after `lock.exclusiveLock()` / `checkForClose()`)
  > so the read of `this.size` is race-free against a concurrent
  > `shrink` / `allocateSpace` writer. Added two new `AsyncFileTest`
  > cases: `testShrinkPartial` (allocates K=4 pages, writes them, calls
  > `shrink(M*pageSize)` with M=2, asserts both `getFileSize()` and
  > `getUnderlyingFileSize()` report `M*pageSize`) and
  > `testShrinkNoOpWhenTargetGreaterOrEqual` (covers the equal-target and
  > greater-target branches of the new guard). Existing `testShrink`
  > (target=0) unchanged. Full `core` test suite: 18058/18058 passing
  > on the clean re-run (98 skipped); Spotless applied. Commit
  > `743572c5fb`.
  >
  > **What was discovered:**
  > Test-fixture detail not surfaced in the step spec:
  > `AsyncFile.allocateSpace()` only bumps the in-memory size counter —
  > the underlying file channel extends only on `write()`. Initial
  > `testShrinkPartial` assertion `getUnderlyingFileSize() == M*pageSize`
  > failed (returned 0) until the test was changed to actually write K
  > pages of data. `testShrinkNoOpWhenTargetGreaterOrEqual` was hardened
  > the same way so its assertions are non-trivial. The existing
  > `testShrink` (zero-target case) did not surface this gap only
  > because both pre- and post-values are 0.
  >
  > **Cross-track impact (informational):** The implementer surfaced a
  > pre-existing coverage-gate failure on the cumulative branch diff
  > against `origin/develop`. 43 files from Tracks 1–5 (WOWCache,
  > FreeSpaceMap, IndexHistogramManager, AbstractStorage,
  > AtomicOperationBinaryTracking, StorageComponent, BTree, DiskStorage,
  > DirectMemoryOnlyDiskCache, MemoryFile, SharedLinkBagBTree,
  > CollectionDirtyPageBitSet, CollectionPositionMapV2, FreeSpaceMap,
  > PaginatedCollectionV2, LockFreeReadCache) report 0/249 line and
  > 0/86 branch coverage under the `core` unit-test suite alone — these
  > lines need either Track 7 Step 5's integration tests or Track 6's
  > CS1 / poison-cascade end-to-end tests, neither of which has landed
  > yet. Confirmed pre-existing by stashing Step 1 and re-running the
  > gate (identical failure). Recommendation absorbed into orchestrator
  > stance: accept the gate as "pre-existing, unchanged by step"
  > through Steps 2–4 and revisit at Step 5 / Track 6.
  >
  > **What changed from the plan:** none.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/fs/AsyncFile.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/fs/AsyncFileTest.java` (modified)
  >
  > **Critical context:** none.

- [x] Step 2: Layered shrink primitive — `WriteCache.shrinkFile` SPI + `LockFreeReadCache.shrinkFile` orchestrator + `ConcurrentLongIntHashMap.removeByFileIdAtLeast`
  - [x] Context: info
  > **Risk:** high — architecture (new SPI surface touching 7 implementers: `WOWCache`, `DirectMemoryOnlyDiskCache`, plus 5 test-mocks) + concurrency (LFRC orchestration ordering load-bearing; periodic-flush exclusion via `filesLock.writeLock`; new segment-map primitive sits in the cache hot-path supporting class)
  >
  > **What**:
  > 1. Add `WriteCache.shrinkFile(long fileId, long targetBytes)` to the `WriteCache` interface.
  > 2. `WOWCache.shrinkFile` impl: acquires `filesLock.writeLock`; pre-flight `if (AsyncFile.getFileSize() <= targetBytes) return;` (true no-op on clean shutdown); calls new `removeCachedPagesAtLeast(intId, minPageIndex)` (range-scoped purge mirroring `doRemoveCachePages` at `WOWCache.java:3537` with a `pageKey.fileId == intId && pageKey.pageIndex >= minPageIndex` filter); then `AsyncFile.shrink(targetBytes)`. Where `minPageIndex = (int)(targetBytes / pageSize)`.
  > 3. `DirectMemoryOnlyDiskCache.shrinkFile` impl: no-op (in-memory engine cannot produce on-disk orphans).
  > 4. 5 test-mock implementers override `shrinkFile` to throw `UnsupportedOperationException` (`LockFreeReadCacheOptimisticTest.PageFrameWriteCache`; `MockedWriteCache` inner classes in `AsyncReadCacheTestIT`, `LockFreeReadCacheBatchingTest`, `LockFreeReadCacheConcurrentTestIT`; `TrackingWriteCache` in `LockFreeReadCacheFileOpsTest`). PSI re-check at implementation time confirms no sixth implementer.
  > 5. Add `LockFreeReadCache.shrinkFile(long fileId, long targetBytes, WriteCache writeCache)` orchestrator: delegates to `writeCache.shrinkFile(fileId, targetBytes)` first (settles write-back + AsyncFile), then calls private `clearFileRange(fileId, minPageIndex, writeCache)`. `clearFileRange` mirrors `LockFreeReadCache.clearFile` at `:796-839` (acquires `evictionLock`, flushes the current-thread read batch, calls range-scoped removal on the segment map, freezes/notifies the policy).
  > 6. Add `ConcurrentLongIntHashMap.removeByFileIdAtLeast(long fileId, int minPageIndex) : List<V>` next to `removeByFileId(long)`, with per-segment range filter under the existing write-lock.
  >
  > **Tests**:
  > - `WOWCache.shrinkFile` range-purge symmetry pair (above-target dirty entry discarded; below-target dirty entry preserved and persisted on subsequent `flushAllData`).
  > - `LockFreeReadCache.shrinkFile` orchestration test (install `CachePointer` past target; assert entry purged after `shrinkFile`).
  > - `ConcurrentLongIntHashMapTest.removeByFileIdAtLeast*` mirroring the existing `removeByFileId` surface: empty range; full-range equivalence to `removeByFileId`; range crossing a segment boundary; concurrent allocations into the same fileId while range-purge runs.
  > - `DirectMemoryOnlyDiskCache.shrinkFile` no-op test (assert it does not throw).
  > - 5 test-mock implementers: assert UOE.
  >
  > **What was done:** Landed the layered shrink primitive in three
  > commits. Base commit `baa0c5a069` added `WriteCache.shrinkFile(fileId,
  > targetBytes)` to the SPI (WOWCache impl + DirectMemoryOnlyDiskCache
  > no-op + 5 test-mock UOE overrides), `LockFreeReadCache.shrinkFile(...)`
  > orchestrator routing through a new private `clearFileRange`, the new
  > `ConcurrentLongIntHashMap.removeByFileIdAtLeast` segment-map primitive,
  > and `RemoveFilePagesAtLeastTask` as the sibling commit-executor task.
  > Tests: 7 `removeByFileIdAtLeast` cases, 5 `WOWCacheShrinkFileTest`
  > cases (incl. above/below-target symmetry pair), 3 LFRC orchestration
  > tests, 2 in-memory no-op tests, 4 UOE-mock pins. Iter-1 review fix
  > `60cf566b16` applied 10 findings: rewrote `shrinkFilePreservesDirty­
  > EntriesBelowTarget` to exercise a real shrink (had been a pre-flight
  > no-op decorated as a real-shrink test); added flush-after-shrink
  > ordering test; barrier-synchronised the `removeByFileIdAtLeast` race
  > test with UEH-captured exceptions and size-parity assertion;
  > extended `TrackingWriteCache` with an attachable order counter so
  > LFRC ordering is observable from the dispatch test; added zero-target
  > end-to-end test, idempotence test; null-check on
  > `files.acquire(fileId)` in `WOWCache.shrinkFile`; documentation
  > corrections (stale line numbers, logical-vs-physical terminology,
  > FQN cleanup). Iter-2 fix `70274af6e2` promoted three iter-1 asserts
  > to production `IllegalArgumentException` guards (non-negative,
  > page-aligned, no int-overflow) at both `WOWCache.shrinkFile` and
  > `LockFreeReadCache.shrinkFile`, lifting them above each method's
  > pre-flight no-op so the contract is enforced regardless of current
  > file size; added 6 guard-pinning tests (3 per call site). The LFRC
  > guards run before the `writeCache.shrinkFile` delegate, closing the
  > `DirectMemoryOnlyDiskCache` slip case where the in-memory engine's
  > no-op would otherwise let a negative target reach `clearFileRange`.
  > Dim-review fan-out at iter-2: 7/7 PASS (CQ/BC/TB/TC/CS/TY/TX; PF
  > had no findings). Targeted tests: 282/282 at iter-1, 24/24 at
  > iter-2's guard rerun.
  >
  > **What was discovered:**
  > - **Stale coverage baseline, not a real cascade.** The orchestrator
  >   carried over an "unexplained pre-existing coverage gate failure on
  >   43 files from Tracks 1-5" hypothesis from Step 1. The implementer
  >   traced it to a stale `.coverage/reports/youtrackdb-core/jacoco.xml`
  >   (dated May 6, populated only by gremlin-annotations). Regenerating
  >   the report from a fresh `./mvnw -pl core test -P coverage` +
  >   `jacoco:report` dropped the cumulative-diff failure to PASS at
  >   93.3%/81.8% with no new tests beyond Step 2's own. Tracks 1–5
  >   production code is well-covered by the existing core unit-test
  >   suite; the "cascade" was a baseline artifact. Steps 3-5 and Track
  >   6 should regenerate the jacoco report at gate time rather than
  >   trust the on-disk baseline (one `mvnw jacoco:report` + copy into
  >   `.coverage/reports/youtrackdb-core/`).
  > - **`TrackingWriteCache.pageSize() == 0` divisor trap.** The in-tree
  >   test mock returns 0 from `pageSize()`. Original `LockFreeReadCache.shrinkFile`
  >   draft computed `minPageIndex = (int)(targetBytes / writeCache.pageSize())`
  >   and would have div-by-zero'd through it. Switched to `this.pageSize`
  >   (LFRC-local field; semantically identical since LFRC and its
  >   WriteCache are constructed with the same page size). Documented at
  >   the call site.
  > - **`AsyncFile.HEADER_SIZE` on-disk byte accounting.** The
  >   shrink-to-zero test must compare on-disk bytes against
  >   `HEADER_SIZE` (1024), not 0 — AsyncFile prefixes every file with
  >   the header and never truncates it. Reusable pattern for Step 5
  >   integration tests inspecting raw file sizes after a recovery-time
  >   shrink.
  > - **Iter-1 placement bug on the alignment + overflow asserts.** The
  >   iter-1 implementer placed the new asserts inside the `filesLock`
  >   window AFTER the pre-flight no-op. The pre-flight short-circuited
  >   on an overflow target against a small file — the assert never
  >   fired. Iter-2 lifted the production guards above the lock
  >   acquisition + pre-flight, cleanly separating "argument-validity"
  >   (always enforced) from "shrink work needed" (the pre-flight
  >   optimisation).
  >
  > **Cross-track impact (informational):**
  > - **Track 6 (StorageBackupMTStateTest resurrection):** the new
  >   `RemoveFilePagesAtLeastTask` lives next to the existing
  >   `RemoveFilePagesTask`; Track 6 readers can reuse its dispatch
  >   shape if any integration test wants to exercise the range-scoped
  >   write-back purge under contention.
  > - **Step 3 (this track):** the per-component
  >   `verifyAndTruncateOrphans` helpers should call
  >   `readCache.shrinkFile(fileId, targetBytes, writeCache)` (preferred,
  >   handles both layers via the new `ReadCache.shrinkFile` interface
  >   method added in this step). LFRC re-derives `minPageIndex` from
  >   its own `pageSize` — Step 3 components do NOT need to compute or
  >   pass `minPageIndex`. The defensive null-check inside
  >   `WOWCache.shrinkFile` lets Step 3 skip pre-checking file
  >   existence at the component layer.
  > - **Step 5 (this track):** the orchestrator wiring at
  >   `AbstractStorage.truncateOrphansAfterRecovery()` should dispatch
  >   through the `ReadCache.shrinkFile` SPI (polymorphic over disk /
  >   in-memory engines). The HEADER_SIZE-aware on-disk size pattern is
  >   reusable for integration tests that inspect raw file sizes
  >   post-recovery.
  > - **Track 2 MT-hardening backlog absorption.** Deferred items from
  >   this step's dim-review fan-out (not addressed in iter-2, to be
  >   considered by future cache-layer hardening):
  >   BC2 clearFileRange leak-on-pinned-entry (inherited from
  >   clearFile); BC4 InterruptedException flag not restored
  >   (inherited convention); CS3 `AsyncFile.shrink` durability via
  >   subsequent flush rather than fsync (matches `truncateFile`
  >   precedent); TC4 `Integer.MAX_VALUE` boundary on
  >   `removeByFileIdAtLeast`; TC5 multi-file isolation pin at the WOW
  >   layer; TY5 back-port `assert size/usedBuckets >= 0` to the
  >   sibling `removeByFileId` / `removeByStorageId`; TY6
  >   `DirectMemoryOnlyDiskCache.shrinkFile` negative-target silent
  >   acceptance asymmetry; TX3 clearFileRange pinned-entry escalation
  >   path; TX4 LFRC concurrent below-cutoff reader during shrinkFile;
  >   TX5 WOWCache `filesLock.writeLock` exclusion against concurrent
  >   addFile/truncateFile during in-flight shrinkFile; TB4 in-memory
  >   no-op tests don't verify page content survival; TB5 LFRC purge
  >   selectivity verified only by aggregate used memory not per-page
  >   identity; TB6 `shrinkFileRejectsNegativeTarget` doesn't pin the
  >   `targetBytes == 0` boundary; TB7 UOE-mock tests don't assert the
  >   thrown exception message substring.
  >
  > **What changed from the plan:**
  > - The plan didn't explicitly call out adding `shrinkFile` to the
  >   `ReadCache` interface — only `LockFreeReadCache.shrinkFile`. The
  >   interface method is required for polymorphic dispatch from the
  >   per-component helpers Step 3 will add and is the dispatch surface
  >   Step 5's orchestrator wiring will use.
  > - The plan suggested `LockFreeReadCache.shrinkFile` would compute
  >   `minPageIndex` via `writeCache.pageSize()`. Switched to
  >   `this.pageSize` because `TrackingWriteCache.pageSize() == 0`
  >   would div-by-zero through the mock. Semantically identical.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/common/collection/ConcurrentLongIntHashMap.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/ReadCache.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCache.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/RemoveFilePagesAtLeastTask.java` (new)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCache.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/common/collection/ConcurrentLongIntHashMapTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/AsyncReadCacheTestIT.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCacheBatchingTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCacheConcurrentTestIT.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCacheFileOpsTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/chm/LockFreeReadCacheOptimisticTest.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCacheShrinkFileTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCacheTest.java` (modified)
  >
  > **Critical context:**
  > - The `WOWCache.shrinkFile` + `LockFreeReadCache.shrinkFile`
  >   production guards (negative / non-aligned / overflow) run BEFORE
  >   the pre-flight no-op and BEFORE the WriteCache delegate
  >   respectively. The LFRC-side guard is what fires when the in-memory
  >   engine routes through `DirectMemoryOnlyDiskCache` (which itself
  >   no-ops without validation).
  > - The `RemoveFilePagesAtLeastTask` sibling dispatches through the
  >   single-threaded `commitExecutor()` FIFO — same serialisation
  >   primitive that orders the periodic flush task. A regression
  >   re-routing this off the commitExecutor would break the
  >   purge-before-truncate ordering invariant.

- [x] Step 3: Per-component `verifyAndTruncateOrphans` (4 EP-equipped components) + `v3.BTree.getFileId()` accessor + engine-side `verifyAndTruncateOrphans` (2 BTree index engines)
  - [x] Context: info
  > **Risk:** medium — multi-file logic in core (4 storage components + 2 index engines, each with its own EP shape; uniform `offset = +1` and corruption-guard arithmetic shared via helper template)
  >
  > **What**:
  > 1. Add `verifyAndTruncateOrphans(AtomicOperation op, LockFreeReadCache readCache, WriteCache writeCache)` to each of the four EP-equipped components: `v3.BTree`, `SharedLinkBagBTree`, `CollectionPositionMapV2`, `PaginatedCollectionV2`. Each helper: (a) loads its EP page via `op.loadPageForRead(fileId, entryPointIndex)`; (b) reads `epLogicalCounter` from the EP (`pagesSize` for BTree/SLBB; `fileSize` for CPMV2/PCV2) and releases the page; (c) computes `targetBytes = max(pageSize, (epLogicalCounter + 1) * pageSize)`; (d) applies corruption guard `if (epLogicalCounter == 0 && AsyncFile.getFileSize() > pageSize) WARN-and-skip`; (e) calls `readCache.shrinkFile(this.fileId, targetBytes, writeCache)`. EP-read failure propagates (aborts open() / restore as designed).
  > 2. Add `getFileId() -> long` accessor to `v3.BTree` (trivial; field exists at `:117`, no getter today). The other three components already expose `getFileId()`.
  > 3. Add `verifyAndTruncateOrphans(AtomicOperation, LockFreeReadCache, WriteCache)` to `BTreeSingleValueIndexEngine` (FQN `com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine`) delegating to its `sbTree.verifyAndTruncateOrphans(...)`.
  > 4. Add `verifyAndTruncateOrphans(AtomicOperation, LockFreeReadCache, WriteCache)` to `BTreeMultiValueIndexEngine` (FQN `com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine`) calling `svTree.verifyAndTruncateOrphans(...)` and, when `nullTree != null`, also `nullTree.verifyAndTruncateOrphans(...)`. (PSI at iter-1 confirms `nullTree` is a full multi-page-growing BTree — no downgrade.)
  >
  > **Tests** (per-component, one suite per of: BTree / SLBB / CPMV2 / PCV2):
  > - Orphan-present branch: physical file is `(epLogicalCounter + 1 + N) * pageSize` for N ≥ 1; assert engine-side or component-side `verifyAndTruncateOrphans` invokes `LockFreeReadCache.shrinkFile` with `targetBytes = (epLogicalCounter + 1) * pageSize`.
  > - Clean branch (`physicalPages == epLogicalCounter + 1`): no `shrinkFile` invocation.
  > - **Boundary case** (`physicalFileSize == targetBytes` exactly): pre-flight no-op asserted via `shrinkFile` not invoked — catches off-by-one in per-component arithmetic.
  > - Corruption-guard branch (`epLogicalCounter == 0 && fileSize > pageSize`): skip-with-WARN, no `shrinkFile` invocation, WARN log asserted.
  > - Each test docstring cites the per-component `create()` source line (BTree:170-226; SLBB:51-77; CPMV2:133-153; PCV2 `initCollectionState`) so the arithmetic stays anchored.
  > - Engine-side wrapper tests (`BTreeSingleValueIndexEngine.verifyAndTruncateOrphans`, `BTreeMultiValueIndexEngine.verifyAndTruncateOrphans` for both `svTree`-only and `svTree`+`nullTree` cases) using mocked inner trees to assert delegate dispatch.
  >
  > **What was done:** Implemented `verifyAndTruncateOrphans(AtomicOperation,
  > ReadCache, WriteCache)` on the four EP-equipped storage components
  > (`v3.BTree`, `SharedLinkBagBTree`, `CollectionPositionMapV2`,
  > `PaginatedCollectionV2`) and engine-side wrappers on
  > `BTreeSingleValueIndexEngine` (delegates to `sbTree`) and
  > `BTreeMultiValueIndexEngine` (delegates to `svTree` plus `nullTree`
  > when non-null). Each component helper (a) loads its EP page via
  > `op.loadPageForRead`, (b) reads `epLogicalCounter` (`pagesSize` for
  > BTree/SLBB; `fileSize` for CPMV2/PCV2), (c) computes `targetBytes =
  > max(pageSize, (epLogicalCounter + 1) * pageSize)`, (d) applies the
  > corruption-guard skip-with-WARN when `epLogicalCounter == 0 &&
  > AsyncFile.getFileSize() > pageSize`, (e) dispatches to
  > `readCache.shrinkFile(this.fileId, targetBytes, writeCache)`. Added
  > trivial `getFileId()` accessor on `v3.BTree`. Promoted
  > `verifyAndTruncateOrphans` onto the `CellBTreeSingleValue` interface
  > with a default-throw stub so the engine-side delegates dispatch
  > polymorphically. Four new per-component test suites + one
  > engine-wrapper test suite pin all four branches (orphan-present,
  > clean, boundary-exact, corruption-guard) plus delegate dispatch.
  > 18212/18212 unit tests pass; Spotless applied; coverage gate PASS at
  > 89.9% line / 85.4% branch on the cumulative branch diff after
  > regenerating the jacoco baseline.
  >
  > **What was discovered:**
  > - **LogManager.warn three-arg dbName overload trap.** The
  >   `LogManager.warn(this, "format-string-with-%s-or-%d", arg1, arg2)`
  >   shape silently picks the three-arg `warn(Object, String dbName,
  >   String message, Object... args)` overload when `arg1` is a String,
  >   consuming the first arg as `dbName` and dropping it from format
  >   substitution. The first test run flagged "Format specifier '%d'"
  >   runtime errors because `String.format` saw only one positional
  >   arg. Fix: pre-format the message via `String.format` eagerly then
  >   pass it as the two-arg `(this, message)` call. All four
  >   corruption-guard WARN sites now use the pre-format pattern. Future
  >   storage-component helpers logging mixed-type WARN should follow
  >   the same shape.
  > - **Mockito `executeInsideComponentOperation` consumer trap.** The
  >   BTree / SLBB / PCV2 tests cannot mock the storage stack with bare
  >   `mock(AtomicOperationsManager.class)` because the mocked default
  >   for `executeInsideComponentOperation(...)` returns without
  >   invoking the consumer lambda — so the component's `fileId` field
  >   stays at 0 and the helper reads page 0 of a zero-fileId file.
  >   Required pattern: install
  >   `doAnswer(inv -> { consumer.accept(op); return null; }).when(...)`
  >   so the consumer actually runs. CPMV2's create() doesn't route
  >   through this idiom and is exempt. Reusable across future
  >   per-component recovery hooks (Step 5 orchestrator).
  > - **BTreeMultiValueIndexEngine accepts only version 4.** The MV
  >   engine rejects versions 1, 2, 3 (legacy `CellBTreeMultiValue`
  >   shapes). Phase A iter-1 noted v3 SV engine accepts version 3; the
  >   MV engine's version range diverges. The engine-wrapper test
  >   constructs the MV engine with version 4.
  >
  > **Cross-track impact (informational):**
  > - **Step 4 (this track):** dispatch into
  >   `SharedLinkBagBTree.verifyAndTruncateOrphans(AtomicOperation,
  >   ReadCache, WriteCache)` — the public method is now in place on
  >   the concrete SLBB class. Step 4's iteration delegate signature
  >   matches the per-component shape.
  > - **Step 5 (this track):** the orchestrator dispatches through
  >   `ReadCache` / `WriteCache` (not `LockFreeReadCache`); the
  >   per-component helpers are polymorphic over disk + in-memory
  >   engines because `DirectMemoryOnlyDiskCache.shrinkFile` is a no-op.
  >   `storage.getReadCache()` + `storage.getWriteCache()` produce the
  >   cache pair. The CPMV2 / PCV2 iteration must call
  >   `verifyAndTruncateOrphans` on BOTH the `PaginatedCollectionV2`
  >   AND its embedded `collectionPositionMap` field (the PCV2 helper
  >   only truncates the `.pcl` file; the CPMV2 helper only truncates
  >   the `.cpm` file). The plan's Step 5 spec already calls this out;
  >   the implementation here makes the asymmetry concrete via separate
  >   per-instance helpers.
  >
  > **What changed from the plan:**
  > The plan's What section names the helper's third parameter as
  > `LockFreeReadCache readCache`. Step 2's `What was discovered`
  > cross-track note already escalated this to `ReadCache readCache`
  > (the new SPI surface Step 2 added to the `ReadCache` interface).
  > Step 3 implemented with `ReadCache` throughout — polymorphic over
  > disk and in-memory engines. Step 4 (Step file What still cites
  > `LockFreeReadCache`) and Step 5 (Step file What still cites
  > `LockFreeReadCache` in the `executeInsideAtomicOperation` lambda)
  > should use `ReadCache` instead. The orchestrator's existing
  > `storage.getReadCache()` accessor returns the polymorphic
  > `ReadCache` type, so this is a strictly-additive simplification
  > and does not require plan-correction commits.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/CollectionPositionMapV2.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/PaginatedCollectionV2.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/CellBTreeSingleValue.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/ridbagbtree/SharedLinkBagBTree.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeIndexEngineVerifyAndTruncateOrphansTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/CollectionPositionMapV2Test.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/PaginatedCollectionV2VerifyAndTruncateOrphansTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTreeVerifyAndTruncateOrphansTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/ridbagbtree/SharedLinkBagBTreeVerifyAndTruncateOrphansTest.java` (new)
  >
  > **Critical context:**
  > - The `nullTree` defensive null-check in
  >   `BTreeMultiValueIndexEngine.verifyAndTruncateOrphans` is a
  >   defensive guard against future refactors — production today
  >   always assigns `nullTree` in the ctor (PSI-verified at Phase A).
  >   The corresponding test pins the null-tree-absent branch via
  >   reflection.
  > - Coverage gate baseline: cumulative-diff PASS at 89.9% line /
  >   85.4% branch on 49 changed files. Step 4-5 should regenerate the
  >   jacoco baseline before gate-checking (`./mvnw -pl core test -P
  >   coverage` + `jacoco:report` + copy to
  >   `.coverage/reports/youtrackdb-core/`) — trusting the on-disk
  >   baseline is the Step 1 trap.

- [x] Step 4: `LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans` iteration delegate
  - [x] Context: info
  > **Risk:** low — default (pure delegate-and-iterate over `fileIdBTreeMap.values()`; no new locking or invariant)
  >
  > **What**: add `verifyAndTruncateAllOrphans(AtomicOperation op, LockFreeReadCache readCache, WriteCache writeCache)` to `LinkCollectionsBTreeManagerShared` that iterates `fileIdBTreeMap.values()` and calls each `SharedLinkBagBTree.verifyAndTruncateOrphans(op, readCache, writeCache)`. The manager holds N ≥ 0 SLBB instances in `fileIdBTreeMap : ConcurrentHashMap<Integer, SharedLinkBagBTree>` and exposes no public iteration API — the new method is the iteration delegate (NOT a public `getAllManaged()` accessor).
  >
  > **Tests**: manager-level test installs 3 mock SLBBs into `fileIdBTreeMap`, calls `verifyAndTruncateAllOrphans`, asserts each receives the call exactly once with the supplied `(op, readCache, writeCache)` triple. Empty-map case asserts no NPE / no iteration side-effect.
  >
  > **What was done:** Added
  > `verifyAndTruncateAllOrphans(AtomicOperation, ReadCache, WriteCache)`
  > on `LinkCollectionsBTreeManagerShared` as the iteration delegate
  > over the private `fileIdBTreeMap : ConcurrentHashMap<Integer,
  > SharedLinkBagBTree>`. The method iterates `fileIdBTreeMap.values()`
  > and forwards the supplied triple to each
  > `SharedLinkBagBTree.verifyAndTruncateOrphans` call. Iteration order
  > is undefined (independent per-file calls, no shared state across
  > SLBBs); `IOException` from any per-SLBB helper propagates and
  > aborts further iteration, matching the per-component contract.
  > New manager-level unit-test class pins both branches: 3 mock
  > SLBBs installed via reflection (the manager exposes no public
  > mutator outside its own load/create paths) with `verify(...)` on
  > each forwarded triple; empty-map case asserts a clean no-op with
  > `verifyNoInteractions` on both caches. 2/2 tests pass in 2.8 s;
  > Spotless applied.
  >
  > **What was discovered:** none.
  >
  > **Cross-track impact (informational):** Step 5's orchestrator
  > can dispatch
  > `linkCollectionsBTreeManager.verifyAndTruncateAllOrphans(op,
  > readCache, writeCache)` as a single call alongside the
  > per-component helpers and engine-side wrappers — no further
  > accessor on the manager is required, the iteration is fully
  > encapsulated. The empty-map case (no ridbags loaded yet on a
  > fresh storage) is a clean no-op rather than an error path.
  >
  > **What changed from the plan:** Step file's What cited
  > `LockFreeReadCache readCache` as the third parameter. Implemented
  > with `ReadCache` per Step 3's cross-track note (strictly-additive
  > simplification matching the polymorphic SPI surface Step 2 added).
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/LinkCollectionsBTreeManagerShared.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/LinkCollectionsBTreeManagerSharedVerifyAndTruncateAllOrphansTest.java` (new)
  >
  > **Critical context:** none.

- [x] Step 5: `AbstractStorage.truncateOrphansAfterRecovery()` orchestrator + `open()` wiring + `postProcessIncrementalRestore` wiring + integration tests
  - [x] Context: warning
  > **Risk:** high — crash-safety / durability (recovery path, two storage-startup entry points, unlogged truncate — EP-page floor + corruption guard are the only safety nets; integration tests include sub-JVM crash precedent)
  >
  > **What**:
  > 1. Add private `AbstractStorage.truncateOrphansAfterRecovery()` orchestrator that iterates:
  >    - `collections : List<StorageCollection>` (FQN `com.jetbrains.youtrackdb.internal.core.storage.StorageCollection`): downcast each entry to `PaginatedCollectionV2`; call `verifyAndTruncateOrphans` on the PCV2 instance AND on its embedded `collectionPositionMap` field (`CollectionPositionMapV2`).
  >    - `indexEngines : List<BaseIndexEngine>`: filter by `instanceof BTreeSingleValueIndexEngine || BTreeMultiValueIndexEngine` and call `engine.verifyAndTruncateOrphans(op, readCache, writeCache)` polymorphically (engine-side iteration encapsulates `sbTree`/`svTree`/`nullTree`).
  >    - `linkCollectionsBTreeManager.verifyAndTruncateAllOrphans(op, readCache, writeCache)`.
  >    The orchestrator is silent on no-op (clean shutdown / no orphans); on truncate emits one-line WARN per affected file: component name + fileId + pre/post-truncate page counts + delta pages.
  > 2. Wrap the orchestrator body in `atomicOperationsManager.executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)` (matches the catalogue-load idiom at `AbstractStorage.java:797-802`).
  > 3. `AbstractStorage.open()` wiring: insert call to `truncateOrphansAfterRecovery()` AFTER `:800` (`openIndexes`) and BEFORE the first non-recovery TX. Unconditional (NOT gated by `wereDataRestoredAfterOpen`).
  > 4. `DiskStorage.postProcessIncrementalRestore` wiring: insert call AFTER `:1673` (`flushAllData()`) and BEFORE `:1675` (`generateDatabaseInstanceId`). Both call sites sit inside their respective `stateLock.writeLock()` windows.
  >
  > **Tests**:
  > - **Orchestrator unit test** (mock-WriteCache + mock-LockFreeReadCache + mock components/engines/manager): assert iteration order across the three groups; assert correct dispatch through `instanceof` filter on `indexEngines`; assert `BTreeMultiValueIndexEngine` calls both `svTree` and `nullTree` when non-null; assert silent on clean.
  > - **Integration tests against a real `WOWCache`**:
  >   - **Positive (primary) — deterministic orphan fabrication**: open a storage normally; use a test helper to write N orphan pages past `epLogicalCounter` via `AsyncFile.allocateSpace(...)` + `AsyncFile.write(...)`. Orphan-page content must carry LSN `(-1, -1)` + the WOWCache magic stamp (byte layout `EnsurePageIsValidInFileTask.writeValidPageInFile` produces at `WOWCache.java:3905-3924`) so `checksumMode=StoreAndThrow` reads see clean empty pages rather than corruption. Close, reopen, assert the pass truncates and the next TX completes without `IllegalStateException`.
  >   - **Positive (confirmation) — sub-JVM crash** (slower, tagged integration): drive `commitChanges` to WAL-buffered state on the disk engine, kill the JVM mid-flight via `Runtime.exec` (`LocalPaginatedStorageRestoreFromWALIT` precedent), reopen, assert `physical == logical`.
  >   - **Negative (clean shutdown → no-op)**: assert the pass runs but emits no truncate log line.
  >   - **Incremental-restore entry point**: drive a backup with concurrent writes (so `physicalSizeForBackupSnapshot` captures a transient orphan-shape file), restore the backup, assert `physical == logical` post-restore.
  >
  > **What was done:** Implemented
  > `AbstractStorage.truncateOrphansAfterRecovery(AtomicOperation)` as a
  > `protected` orchestrator (lifted from `private` so the disk-engine
  > subclass can dispatch across packages). The orchestrator walks
  > three groups in documented order: (1) `PaginatedCollectionV2` +
  > its embedded `CollectionPositionMapV2` for every non-null entry
  > in `collections`; (2) `BTreeSingleValueIndexEngine` /
  > `BTreeMultiValueIndexEngine` in `indexEngines` (`instanceof` filter,
  > other engine types silently skipped); (3) a single
  > `linkCollectionsBTreeManager.verifyAndTruncateAllOrphans` call.
  > `(atomicOperation, readCache, writeCache)` forward unchanged to
  > each helper. Wired from `AbstractStorage.open()` after
  > `openIndexes` and from `DiskStorage.postProcessIncrementalRestore`
  > after `flushAllData()` and before `generateDatabaseInstanceId`; both
  > sites wrap the dispatch in `executeInsideAtomicOperation`. Added
  > `PaginatedCollectionV2.getCollectionPositionMap()` so the
  > orchestrator can dispatch the `.cpm` truncate independently.
  > Tests: orchestrator unit test pins iteration order (Mockito
  > `InOrder`), null-slot skipping, `instanceof` filtering, and
  > per-group exception propagation across all three group origins.
  > Integration test fabricates orphan pages on `.pcl` / `.cpm` /
  > `.cbt` files via `RandomAccessFile` + magic-stamp pages, reopens,
  > asserts strict-equality file shrinkage to the pre-fabrication
  > size measured BEFORE any post-recovery TX. A separate
  > `DiskStorageRestoreOrchestratorWiringTest` is a source-text
  > regression sentinel pinning `flushAllData()` before
  > `truncateOrphansAfterRecovery` in
  > `postProcessIncrementalRestore`'s body — a Mockito spy was
  > infeasible because the method is `private` on `DiskStorage` and
  > a real spy would re-create the integration scaffolding the test
  > is trying to avoid. Iter-1 fix `f2f35391a0` applied M1–M7 + m1–m4
  > findings across 6 dim reviews: restored the orphaned
  > `verifyAndTruncateOrphans` Javadoc in PCV2, tightened all four
  > IT scenarios to capture file size pre-TX with strict equality,
  > added Javadoc orderiing rationale + inline FSM-rebuild
  > future-work note, pinned cross-group dispatch ordering with
  > `InOrder`, added Group-2 / Group-3 exception-origin tests,
  > added the wiring-sentinel test, added the `.cpm` IT, renamed
  > stale `.sbt` references to `.cbt`, named
  > `WOWCache.MAGIC_NUMBER_WITHOUT_CHECKSUM` at its anchor, and
  > tidied static imports. Iter-1 dim-review gate-check: 5/6
  > dimensions PASS (BC/TB/TC/CS/TY all clear). One CQ STILL OPEN
  > (CQ5, FQN inline-import cleanup for ~10 type references in the
  > IT — style only, no behavior/correctness impact, including a
  > genuine `metadata.schema.schema.PropertyType` package path with
  > a doubled `.schema.` segment that exists on disk). Deferred —
  > recorded in the deferred-items list below for Phase C track
  > review or a future cleanup pass. Targeted tests: 20/20 pass at
  > iter-1; full Step-5-implementer suite at base commit
  > `fe6b728f2e` was 18225/18225 unit tests + coverage gate PASS at
  > 91.5% line / 85.9% branch on the 58-file cumulative diff.
  >
  > **What was discovered:**
  > - **`postProcessIncrementalRestore` is `private` on `DiskStorage`**,
  >   so a Mockito spy of the wiring is impractical (the spy
  >   re-creates the integration scaffolding the test is trying to
  >   avoid). Used a source-text regression sentinel instead — load
  >   `DiskStorage.java`, walk the brace-matched
  >   `postProcessIncrementalRestore` body, assert `flushAllData()`
  >   text precedes `this::truncateOrphansAfterRecovery` text. This
  >   pattern is reusable for any future test where the production
  >   method-level Mockito spy is more invasive than the regression
  >   surface justifies.
  > - **`BTreeSingleValueIndexEngine.DATA_FILE_EXTENSION = ".cbt"`**,
  >   not `.sbt`. The plan's terminology slipped (the `.sbt` /
  >   `.nbt` pair lives on the lower-level `v3.BTree` class; the
  >   engine wraps those under `.cbt` / `.nbt`). Test code uses
  >   `.cbt` correctly; prose and variable names updated to match.
  > - **`YouTrackDBImpl` has no public `getStorage(name)`** accessor.
  >   Integration tests that need the storage reference must go
  >   through `session.getStorage()` inside an open session before
  >   close — a reusable recipe for Track 6's CS1 / poison-cascade
  >   regression tests.
  > - **The full `core` test suite under `-P coverage` exceeds the
  >   10-minute foreground Bash budget** on this host. Targeted
  >   reruns over the changed-files dependency surface plus the
  >   pre-coverage-stage `jacoco.exec` from the timed-out run was
  >   enough to produce a gate-eligible cumulative-diff report.
  >   Track 6 / later tracks that need a full coverage run should
  >   split into `default-test` (parallel) and `sequential-tests`
  >   stages, or restrict via `-Dtest=`.
  > - **Orchestrator runs AFTER `openCollections` / `openIndexes`**.
  >   The `PaginatedCollectionV2.open()` FSM-rebuild branch
  >   (`freeSpaceMap.exists(...) == false`) scans physical pages
  >   that may include orphans not yet truncated. Inline comment
  >   added; full FSM-rebuild-orphan-interaction handling is
  >   future work (potentially Track 6 or a follow-up issue).
  >
  > **Cross-track impact (informational):**
  > - **Track 6 (CS1 partial-flush-orphan regression test).** Step
  >   5's `TruncateOrphansAfterRecoveryIT` is a template for the
  >   orphan-fabrication pattern. Track 6 can extend the same
  >   approach to assert that without the recovery pass, the first
  >   non-recovery TX raises `IllegalStateException` (the CS1
  >   negative case), then re-run with the pass enabled to assert
  >   it disappears. The IT does not yet simulate a real dirty-WAL
  >   recovery crash — Track 6 owns that.
  > - **Track 6 (StorageBackupMTStateTest resurrection).** The
  >   orphan fabrication helper (`RandomAccessFile` + magic-stamp
  >   pages) is reusable; the `findFileName` lookup pattern that
  >   maps cluster names to on-disk file IDs is too.
  > - **Track 6 (HLL-spill recovery).** Outside Step 5's EP-equipped
  >   component set (IHM uses page-1 discrimination, not
  >   EP-fileSize sizing). Step 5's orchestrator deliberately
  >   skips IHM and other EP-less components per the plan's
  >   Non-Goals.
  > - **Track 6 (I4 per-component MT pins).** The orchestrator
  >   runs under `stateLock.writeLock()` at both entry points;
  >   Track 6's MT pins should ensure no allocator-side concurrent
  >   contention can race with the orchestrator's per-component
  >   helpers during the open window.
  >
  > **What changed from the plan:**
  > - **Method visibility** changed from `private` (per the plan)
  >   to `protected` — required for `DiskStorage` to dispatch the
  >   orchestrator across package boundaries.
  > - **Helper signature** uses `ReadCache` / `WriteCache` (per
  >   Step 2's promoted interface), not `LockFreeReadCache` (per
  >   the plan's pre-Step-2 text). Strictly-additive simplification
  >   matching the polymorphic SPI surface.
  > - **`getCollectionPositionMap()` accessor** added to
  >   `PaginatedCollectionV2` — was not explicitly in the plan but
  >   needed so the orchestrator can dispatch `.cpm` truncate
  >   independently of `.pcl` truncate (the embedded field is
  >   `private final`).
  > - **Sub-JVM crash test deferred to Track 6** rather than
  >   landed here. Step 5's IT exercises the post-WAL orchestrator
  >   path with deterministic orphan fabrication; the real
  >   dirty-WAL-replay crash path is owned by Track 6's CS1
  >   integration tests.
  >
  > **Deferred items (style / depth — fold into Phase C track
  > review or Track 6 / future passes):**
  > - **CQ5 (style):** FQN inline usage in `TruncateOrphansAfterRecoveryIT`
  >   for `java.nio.file.Path` (lines 118, 203, 275, 346),
  >   `com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType`
  >   / `SchemaClass.INDEX_TYPE` (lines 352, 354 — note the genuine
  >   `metadata.schema.schema.` package path with a doubled
  >   `.schema.` segment; this is real, not a typo),
  >   `org.apache.commons.configuration2.BaseConfiguration` (lines
  >   419, 420), `ChecksumMode` (line 422), `java.io.File` /
  >   `java.io.IOException` (lines 439, 440, 482). Style cleanup —
  >   add proper imports. No behavior impact.
  > - **TB-MultiValue IT:** No IT exercises
  >   `BTreeMultiValueIndexEngine` end-to-end (only a unit test
  >   covers the dispatch). Track 6's CS1 IT will exercise both
  >   engine types under partial-flush-orphan scenarios.
  > - **TB-SLBB IT:** No IT exercises
  >   `LinkCollectionsBTreeManagerShared` end-to-end (only a unit
  >   test covers the iteration). The IT fixture has no edges /
  >   RidBag-using classes, so the manager's `fileIdBTreeMap` is
  >   empty in the current IT. Track 6's CS1 IT (edge-bearing
  >   schema) covers this naturally.
  > - **TB-DirtyReopen IT:** No IT covers the real dirty-WAL
  >   recovery crash path. Track 6's CS1 partial-flush-orphan
  >   integration test owns this shape end-to-end (sub-JVM crash
  >   precedent at `LocalPaginatedStorageRestoreFromWALIT`).
  > - **CS-MultiMode IT:** IT uses `checksumMode=Off`; coverage
  >   under `StoreAndVerify` / `StoreAndThrow` is implicit in
  >   the orchestrator's design (it reads only EP pages, never
  >   orphan content) but not tested.
  > - **CS-FSMRebuild ordering follow-up:** The
  >   `PaginatedCollectionV2.open()` FSM-rebuild branch can scan
  >   orphan pages because the orchestrator runs after
  >   `openCollections`. Inline comment added in this iteration;
  >   full handling is future work (track in a follow-up issue if
  >   it surfaces in real recovery scenarios).
  > - **Reflection-helper fragility:** Unit test uses reflection
  >   to install lists on a `Mockito.CALLS_REAL_METHODS` storage.
  >   The fields are `private final`; the write is JDK-21-version-
  >   dependent. Hygiene-level concern only.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/PaginatedCollectionV2.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/disk/DiskStorage.java` (modified)
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (modified)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/disk/DiskStorageRestoreOrchestratorWiringTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorageTruncateOrphansAfterRecoveryTest.java` (new)
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/TruncateOrphansAfterRecoveryIT.java` (new)
  >
  > **Critical context:**
  > - The orphan-fabrication IT pattern (close DB, extend
  >   `.pcl` / `.cpm` / `.cbt` file with valid-stamped pages via
  >   `RandomAccessFile`, reopen, assert file shrank back to logical
  >   horizon) is reusable for Track 6's CS1 / partial-flush
  >   regression tests. The pages must carry
  >   `MAGIC_NUMBER_WITHOUT_CHECKSUM = 0xEF30BCAFL` at offset 0
  >   plus the LSN `(-1, -1)` slot. Open the storage with
  >   `ChecksumMode.Off` so the magic stamp is the only validity
  >   check the read path applies (defence-in-depth — the recovery
  >   orchestrator itself never reads the orphan pages).
  > - The orchestrator dispatches `.pcl` truncate BEFORE `.cpm`
  >   truncate for each PCV2 entry. If `.cpm` fails (e.g., bad EP
  >   page), `.pcl` has already been truncated and the per-file
  >   invariant holds on `.pcl` but is unrestored on `.cpm`. This
  >   is a documented design choice — per-component invariants are
  >   the contract, not per-storage-atomic invariants. The next
  >   reopen re-runs the pass and converges.

## Base commit

7d0c88567e43628d8c86d12d14ac0991a8f28aef
