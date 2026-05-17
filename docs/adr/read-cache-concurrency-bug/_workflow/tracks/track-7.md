# Track 7: Recovery-time orphan-truncation pass

## Description

Add a new private `AbstractStorage.truncateOrphansAfterRecovery()` pass —
called from `open()` AFTER `openCollections` / `openIndexes` /
`linkCollectionsBTreeManager::load` populate the iteration targets, and
also from `DiskStorage.postProcessIncrementalRestore` after its catalogue
load — that walks each EP-equipped storage component, reads its EP page
logical-page count, and truncates physical orphans via a new
`WriteCache.shrinkFile(fileId, targetBytes)` primitive backed by the
existing `AsyncFile.shrink(size)`. The pass restores Invariant I6
(`entryPoint.logicalPages == AsyncFile.getFileSize() / pageSize`) before
any TX runs. Scope is intentionally limited to the four EP-equipped
components subject to CS1 (`BTree`, `SharedLinkBagBTree`,
`CollectionPositionMapV2`, `PaginatedCollectionV2`). EP-less components
(`FreeSpaceMap`, `CollectionDirtyPageBitSet`) and `IndexHistogramManager`
are deliberately excluded — see Non-Goals in `implementation-plan.md`,
sharpened at Phase A iter-1 to acknowledge the `checksumMode=StoreAndThrow`
exposure pattern.

> **What**:
> - **New `WriteCache.shrinkFile(long fileId, long targetBytes)`
>   primitive.** Today `WriteCache` exposes `truncateFile(long fileId)`
>   (truncate to zero, used for `DROP_FILE`-style operations);
>   `shrinkFile` is distinct because it takes a target size and the
>   shrink direction is one-way (no growth). The WOWCache implementation
>   acquires `filesLock.writeLock`, purges `LockFreeReadCache` entries
>   for `pageIndex >= targetBytes / pageSize` via `removeCachedPages`
>   (mirroring `WOWCache.truncateFile`'s ordering at `WOWCache.java:1905-1927`),
>   then delegates to `AsyncFile.shrink(targetBytes)`. The
>   `DirectMemoryOnlyDiskCache` implementation is a no-op (in-memory
>   engine cannot produce on-disk orphans). The five test-mock
>   `WriteCache` implementers (`PageFrameWriteCache`, `MockedWriteCache`
>   × 3, `TrackingWriteCache` — all under `core/src/test/...`) override
>   to throw `UnsupportedOperationException` so a test that exercises
>   the truncate path against a mock surfaces loudly. Pre-flight check:
>   if `AsyncFile.getFileSize() <= targetBytes`, the method returns
>   without invoking `shrink` or `removeCachedPages` so a clean shutdown
>   is a true no-op.
> - **`AsyncFile.shrink(long size)` in-place semantics fix.** The
>   existing primitive at `AsyncFile.java:307-318` unconditionally sets
>   the in-memory `AtomicLong size` to `0` regardless of the `size`
>   argument (all current production callers in `WOWCache` at `:969`,
>   `:1916`, `:2495` pass `0`, so the bug is latent). Track 7 is the
>   first non-zero caller. Step 1 fixes the body to `this.size.set(size)`
>   and adds a top-line `if (size >= this.size.get()) return;` no-op
>   guard. The existing `AsyncFileTest.testShrink` (zero-target path,
>   `AsyncFileTest.java:376`) stays unchanged; a new
>   `testShrinkPartial` allocates K pages, calls `shrink(M*pageSize)`
>   with `0 < M < K`, and asserts `getFileSize() == M*pageSize` plus
>   `fileChannel.size() == M*pageSize + HEADER_SIZE`.
> - **Per-component `verifyAndTruncateOrphans(AtomicOperation,
>   WriteCache)` helper.** Each of the four EP-equipped components
>   implements a helper that:
>   1. Loads its entry-point page read-only via
>      `op.loadPageForRead(fileId, entryPointIndex)`.
>   2. Reads logical page count from the EP — `pagesSize` for
>      `CellBTreeSingleValueEntryPointV3` (BTree) and
>      `ridbagbtree.EntryPoint` (SLBB); `fileSize` for `MapEntryPoint`
>      (CPMV2) and `PaginatedCollectionStateV2` (PCV2). Releases the
>      page.
>   3. Computes `targetBytes = (logicalPages + offset) * pageSize`
>      where `offset` is component-specific (see Phase A audit
>      targets — locked down at Phase A iter-1 for the EP-counted vs
>      data-only convention).
>   4. Calls `writeCache.shrinkFile(this.fileId, targetBytes)`. The
>      `shrinkFile` pre-flight makes this a no-op when the file is
>      already at or below the target.
>   The helper runs under whichever atomic-operation boundary the
>   caller (i.e., `truncateOrphansAfterRecovery`) supplies.
> - **`v3.BTree.getFileId()` accessor.** The other three EP-equipped
>   components expose `getFileId() -> long`; BTree has a private
>   `fileId` field but no getter (PSI-confirmed at Phase A iter-1).
>   Track 7 adds the trivial accessor to unify the iteration recipe.
> - **`LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans
>   (AtomicOperation, WriteCache)`.** The manager holds N≥0 SLBB
>   instances in `fileIdBTreeMap : ConcurrentHashMap<Integer,
>   SharedLinkBagBTree>` and exposes no public iteration API.
>   Track 7 adds a method on the manager that iterates
>   `fileIdBTreeMap.values()` and calls each SLBB's
>   `verifyAndTruncateOrphans(op, writeCache)`. Keeps the
>   iteration-set boundary inside the manager.
> - **`AbstractStorage.truncateOrphansAfterRecovery()` orchestrator.**
>   Iterates the four EP-equipped component groups:
>   - `collections : List<StorageCollection>` — each entry is a
>     `PaginatedCollectionV2` (the sole concrete/instantiable
>     production inheritor of `StorageCollection`); call
>     `verifyAndTruncateOrphans` on the PCV2 instance AND on its
>     embedded `collectionPositionMap` field (`CollectionPositionMapV2`).
>   - `indexEngines : List<BaseIndexEngine>` — filter by
>     `instanceof BTreeSingleValueIndexEngine` (holds an `sbTree` field
>     typed `CellBTreeSingleValue<CompositeKey>`) || `instanceof
>     BTreeMultiValueIndexEngine` (holds `svTree` + `nullTree` fields,
>     both `CellBTreeSingleValue<CompositeKey>`; both are
>     separately-managed BTree instances). The field type is the
>     `CellBTreeSingleValue` interface, but the sole production
>     inheritor is the generic
>     `com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3.BTree`,
>     so each field value is downcastable to `v3.BTree`. After the
>     engine-class `instanceof` check, cast the field value to
>     `v3.BTree` and call `verifyAndTruncateOrphans` on it. With the
>     new `BTree.getFileId()` accessor in place, the cast is the
>     simplest shape and no engine-side getter is required.
>   - `linkCollectionsBTreeManager` — call
>     `verifyAndTruncateAllOrphans(op, writeCache)` (iteration is
>     internal to the manager).
>   The orchestrator is wrapped in
>   `atomicOperationsManager.executeInsideAtomicOperation(...)` —
>   matches the catalogue-load idiom at `AbstractStorage.java:797-802`.
>   The orchestrator is silent on no-op (clean shutdown / no orphans);
>   on truncate it emits a one-line info log naming the component +
>   fileId + delta pages so operators see when CS1 actually fires.
> - **Wiring (two entry points).**
>   - `AbstractStorage.open()`: insert a call to
>     `truncateOrphansAfterRecovery()` AFTER line 800 (`openIndexes`)
>     and BEFORE the first non-recovery TX. Called unconditionally
>     (NOT gated by `wereDataRestoredAfterOpen` — orphan creation can
>     survive a crash → clean-reopen-without-touch → clean reclose, so
>     a subsequent open with `isDirty() == false` still needs the
>     pass).
>   - `DiskStorage.postProcessIncrementalRestore`: insert a call
>     between `:1671` (after `openIndexes`) and `:1673` (before
>     `flushAllData()`) — i.e., after the catalogue load is populated
>     but before the data flush. Same orchestrator method.
> - **Unit + integration tests.** Cumulative test surface:
>   - `AsyncFileTest.testShrinkPartial` — partial-shrink semantics
>     regression for the AsyncFile fix.
>   - Per-component helper unit tests (one suite per component):
>     orphan-present → `shrinkFile` called with correct target;
>     clean (`logical == physical`) → no-op; `logical > physical`
>     (corruption case) → assertion or no-op-with-warning per Phase A
>     decision.
>   - `WOWCache.shrinkFile` cache-purge test: install a `CachePointer`
>     in `LockFreeReadCache` at index past the post-shrink target,
>     call `shrinkFile`, assert the entry is no longer in the cache.
>   - `truncateOrphansAfterRecovery` orchestrator unit test:
>     mock-WriteCache, mock components, assert iteration order +
>     correct dispatch.
>   - Integration tests against a real `WOWCache`:
>     - Positive (orphan present → truncated post-replay): drive
>       `commitChanges` to its WAL-buffered state on the disk engine,
>       kill the JVM mid-flight, reopen, assert `physical == logical`.
>       Test pattern: sub-JVM via `Runtime.exec` (matches
>       `LocalPaginatedStorageRestoreFromWALIT` precedent).
>     - Negative (clean shutdown → no-op): assert the pass runs but
>       emits no truncate log line.
>     - Incremental-restore entry point: drive a backup with concurrent
>       writes (so `physicalSizeForBackupSnapshot` captures a transient
>       orphan-shape file), restore the backup, assert
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
>   2. **Step 2**: Introduce `WriteCache.shrinkFile(fileId,
>      targetBytes)` SPI. WOWCache impl (lock + LFRC purge + delegate
>      to AsyncFile.shrink); DirectMemoryOnlyDiskCache no-op; 5
>      test-mock implementers throw UOE. Add unit tests for both
>      production impls plus the cache-purge ordering pin. **Risk:
>      medium** — interface addition touches 7 implementers; LFRC
>      purge ordering is load-bearing.
>   3. **Step 3**: Add `verifyAndTruncateOrphans(AtomicOperation,
>      WriteCache)` to each of the four EP-equipped components, plus
>      the trivial `BTree.getFileId()` getter. Phase A audits each
>      component's `create()` path to lock the `offset` arithmetic
>      (whether the EP's logical count includes the EP page itself
>      or not — varies per component). Per-component unit tests.
>      **Risk: medium** — touches four storage components; each has
>      its own EP shape.
>   4. **Step 4**: Add
>      `LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans`
>      (iteration delegate). Manager unit test pins the iteration.
>      **Risk: low** — pure delegate-and-iterate.
>   5. **Step 5**: Add `AbstractStorage.truncateOrphansAfterRecovery()`;
>      wire into `open()` (after `:800`) and
>      `postProcessIncrementalRestore` (after its catalogue load). Both
>      call sites wrapped in `executeInsideAtomicOperation`.
>      Integration tests: positive (sub-JVM crash + reopen), negative
>      (clean shutdown), incremental-restore. **Risk: high** —
>      recovery path, crash-test coordination, two entry points,
>      side-effect ordering vs `flushAllData()` (which has already
>      run inside `recoverIfNeeded()` by the time the pass fires).
> - Phase A audit targets locked down at iter-1:
>   - **Placement**: `open()` after `:800` + `postProcessIncrementalRestore`
>     after its catalogue load. Wrapped in `executeInsideAtomicOperation`.
>   - **Gating**: unconditional (NOT `wereDataRestoredAfterOpen`-gated).
>   - **`AsyncFile.shrink` fix**: in-place semantics fix as Step 1.
>   - **LFRC purge in `shrinkFile`**: required; mirrors `truncateFile`.
>   - **Iteration over SLBB instances**: via new manager method, not
>     a public `getAllManaged()` accessor.
>   - **`BTree.getFileId()`**: added as part of Step 3.
> - Phase A audit targets remaining for Phase A iter-2 (after this
>   replan re-enters /execute-tracks):
>   - The exact `offset` arithmetic per component for `targetBytes`
>     — read each `create()` path to determine whether the EP's
>     logical count includes the EP page itself or excludes it.
>   - Phase A audit also needs to verify `BTreeMultiValueIndexEngine`'s
>     `nullTree` BTree can grow (and thus needs a pass), or whether
>     it's single-page-by-construction like `BTree.nullBucketFileId`
>     (no pass needed). Plan correction CR7 already flagged
>     `{svTree, nullTree}` both for the pass; iter-2 confirms.
>   - Per-fileId truncate ordering safety (analysis already done at
>     iter-1: safe under per-`AsyncFile` exclusive lock; iter-2
>     captures the one-sentence rationale).
>
> **Constraints**:
> - **In-scope files**:
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/fs/AsyncFile.java` (semantics fix)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java` (new method)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` (impl)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCache.java` (no-op impl)
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
>   - New unit + integration tests under `core/src/test/...`.
> - **Out of scope**: EP-less components (FSM, CDPB) and
>   `IndexHistogramManager` — see D6 Risks/Caveats and Non-Goals.
>   Public API renames. Any change to
>   `WriteCache.getFilledUpTo` / `truncateFile` semantics.
> - **Performance constraint**: the recovery pass adds
>   ~100-300 single-page EP-load reads per storage open (one per
>   in-scope component instance). Per-component cost: one
>   `loadPageForRead` + one `AsyncFile.getFileSize` + one comparison +
>   (rare) one `shrink`. Cost is paid once per `open()` /
>   `postProcessIncrementalRestore`; expected impact on storage-open
>   latency is negligible. Track 7 does NOT add periodic or per-TX
>   cost.
> - **WAL constraint**: the pass does NOT generate WAL records. The
>   truncate happens post-replay; the entry-point logical state is
>   already consistent (replayed from WAL). Any subsequent TX that
>   needs to grow the file regenerates the physical pages through
>   the normal `loadOrAdd` path with WAL-tracked allocation.
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
>   §Architecture Notes (D6 revised; I6 wording revised; Integration
>   Points revised; Non-Goals sharpened — all at Phase A iter-1).
> - **Crash-recovery coordination**: the pass runs **after**
>   `recoverIfNeeded()` returns (so WAL replay has settled logical
>   state) and **after** the catalogue load (`openCollections` /
>   `openIndexes` / `linkCollectionsBTreeManager::load`) so the
>   iteration targets are populated. The truncate is a pure
>   file-system-level operation protected by the same
>   `stateLock.writeLock()` that wraps `open()`. The new orchestrator
>   does not interact with the WAL directly.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
