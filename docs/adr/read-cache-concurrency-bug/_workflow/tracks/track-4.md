# Track 4: Write-side API collapse + residual read-side migration

## Description

Delete the `addPage` API surface and migrate the 19 production call sites to
`loadOrAddPageForWrite(fileId, knownIndex)` on top of Track 1's primitive.
Collapse the `commitChanges` / `restoreAtomicUnit` /
`restoreFromIncrementalBackup` reconciliation loops, drop the
`internalFilledUpTo` prediction helper, delete the per-component
reuse-or-extend probes, and absorb the surviving read-side work from the
retired Track 3 (one BTree pure-sizing migration plus rationale comments at
the stay-on-physical sites).

> **What**:
> - Add `loadOrAddPageForWrite(long fileId, long pageIndex) → CacheEntry`
>   to the `AtomicOperation` interface and implement in
>   `AtomicOperationBinaryTracking`. Today the interface has
>   `addPage(long)` and `loadPageForWrite(...)` but no
>   `loadOrAddPageForWrite`.
>   - **Disk-engine path** (when `readCache` is `LockFreeReadCache`):
>     call `readCache.loadOrAddForWrite(fileId, pageIndex, writeCache,
>     verifyChecksums, startLSN)`. Track 1 made this total via
>     `data.compute → WriteCache.loadOrAdd`, so no null return.
>   - **In-memory-engine path** (when `readCache` is
>     `DirectMemoryOnlyDiskCache`): Track 1 deliberately left the
>     read-cache wrappers non-total on this engine —
>     `loadOrAddForWrite` returns null on miss because dispatch logic
>     upstream depends on that signal (see
>     `DirectMemoryOnlyDiskCache.java:192-325`). The new
>     `loadOrAddPageForWrite` impl handles the null by falling back
>     to `readCache.loadOrAdd(fileId, pageIndex, verifyChecksums)`
>     (the in-memory total primitive) and wrapping the returned
>     `CachePointer` in a write-locked `CacheEntry` via
>     `LockManager.acquireExclusiveLock(...)` plus
>     `CacheEntryImpl`. After this eager install, subsequent
>     `loadOrAddForWrite` calls find the page (it is in `MemoryFile`),
>     so the existing null-branch in `commitChanges` becomes
>     genuinely unreachable on both engines and is deleted in step 5.
>   - **Bookkeeping**: preserve today's `addPage` side effects at
>     `AtomicOperationBinaryTracking.java:342-373` — bump
>     `changesContainer.maxNewPageIndex` (the in-progress-TX visibility
>     horizon consulted by `loadPageForWrite`/`loadPageForRead`/
>     `hasChangesForPage` at AOBT:233, :282, :428), set
>     `pageChangesMap.put(pageIndex, container)` keyed by the actual
>     returned `pageIndex` (no prediction), mark `isNew=true` if the
>     pageIndex extends beyond `writeCache.getFilledUpTo`. The
>     `maxNewPageIndex` **field** survives; only the
>     `internalFilledUpTo` private helper method goes away in step 7
>     (alongside the `addPage:355` caller, see the "Drop
>     `internalFilledUpTo`" bullet below).
> - Rewire `StorageComponent.loadOrAddPageForWrite(AtomicOperation,
>   fileId, pageIndex)` (which already exists at
>   `StorageComponent.java:149` as a `loadPageForWrite`-then-`addPage`
>   fallback with 2 prod callers in `IndexHistogramManager`) to
>   delegate to `atomicOperation.loadOrAddPageForWrite(fileId,
>   pageIndex)` instead of falling back to `addPage`. The internal
>   call from inside `StorageComponent.loadOrAddPageForWrite`'s body
>   to `addPage` at `StorageComponent.java:155` (one of the 20 total
>   PSI hits on `StorageComponent.addPage`) is removed by this
>   rewire.
> - Migrate the 19 external `addPage` call sites to
>   `loadOrAddPageForWrite(fileId, knownIndex)`. PSI shows 20 total
>   references on `StorageComponent.addPage`; the 20th is the
>   internal call from `StorageComponent.loadOrAddPageForWrite`'s
>   fallback removed by the rewire above. Exact partition (PSI
>   ReferencesSearch on `StorageComponent.addPage`):
>   - **9 fresh-file sites** (pageIndex 0 or 1, known statically):
>     `CDPB.create:60`, `FreeSpaceMap.create:112`,
>     `IndexHistogramManager.createEmptyStatsPage:1800`,
>     `SharedLinkBagBTree.create:59, :64`,
>     `BTree.create:195, :201, :208`,
>     `CollectionPositionMapV2.create:138`,
>     `PaginatedCollectionV2.initCollectionState:2257`.
>   - **8 reuse-or-extend probe sites** (target is logical-size + 1):
>     - `BTree.allocateNewPage:2163` (single site; preserve outer
>       `freeListHead > -1` branch and collapse only the `else` extend
>       branch).
>     - `SharedLinkBagBTree.splitNonRootBucket:929` (single probe).
>     - `SharedLinkBagBTree.splitRootBucket:1057, :1066` (two
>       consecutive probes in one method body, allocating
>       leftBucketEntry + rightBucketEntry — see "Per-component recipe"
>       below for the two-page pattern).
>     - `CollectionPositionMapV2.allocate:216, :243` (two probes;
>       target = `MapEntryPoint.fileSize + 1`, not `pagesSize + 1`).
>     - `PaginatedCollectionV2.allocateNewPage:2237` (single probe;
>       target = `PaginatedCollectionStateV2.fileSize + 1`).
>   - **2 growth-loop sites** (loop body migrates from `addPage` to
>     `loadOrAddPageForWrite(fileId, iteratorIndex)`; loop persists,
>     each iteration allocates a distinct pageIndex):
>     `FreeSpaceMap.updatePageFreeSpace:229`,
>     `CollectionDirtyPageBitSet.ensureCapacity:197`.
> - Delete the reuse-or-extend probe blocks themselves at every site
>   (the cache absorbs orphans uniformly via Track 1's `loadOrAdd`):
>   `BTree.allocateNewPage`, `SharedLinkBagBTree.{splitNonRootBucket,
>   splitRootBucket}`, `CollectionPositionMapV2.allocate`,
>   `PaginatedCollectionV2.allocateNewPage`. Replace each
>   `if (pageSize < filledUpTo - 1) { reuse } else { extend }` block
>   with a single `loadOrAddPageForWrite(fileId, logicalSize + 1)`
>   call. The "Per-component recipe" subsection below pins the exact
>   `logicalSize` source per component.
> - Re-shape the two growth-loops at
>   `FreeSpaceMap.updatePageFreeSpace:227` and
>   `CollectionDirtyPageBitSet.ensureCapacity:194` — both read
>   `getFilledUpTo` and call `addPage` repeatedly to extend the file.
>   The Phase A audit of the retired Track 3 reclassified these as
>   probe-shaped (the `filledUpTo` value is the loop-start index, not
>   a sizing read). Replace the loop body's `addPage(...)` with
>   `loadOrAddPageForWrite(fileId, iteratorIndex)`; the loop itself
>   persists (each iteration allocates a distinct pageIndex). Since
>   both components are EP-less, there is no `entryPoint.setPagesSize`
>   follow-up — the `loadOrAdd` totality is sufficient.
> - Migrate the lone pure-sizing read at `BTree.doAssertFreePages:1389`
>   away from `getFilledUpTo`. The current code reads
>   `getFilledUpTo(atomicOperation, fileId)` directly with no
>   EntryPoint load (verified by reading
>   `BTree.java:1387-1399`). The migration **adds** a
>   `loadPageForRead(ENTRY_POINT_INDEX)` call at method entry, wraps
>   the result in `CellBTreeSingleValueEntryPointV3`, and uses
>   `entryPoint.getPagesSize() + 1` as the upper iteration bound.
>   This is a test-only assertion path (`-ea` only via
>   `BTree.assertFreePages:1373`), so the added page-pin cost is
>   inconsequential in production builds.
> - Add rationale-bearing inline comments at the **6 stay-on-physical
>   sites** (no code change beyond the comment + javadoc): name the
>   contract that justifies physical-size access in each location.
>   These sites read `getFilledUpTo` via the gated helper Track 5
>   introduces.
>   - `CollectionPositionMapV2.create:136` — fresh-file emptiness
>     check; EntryPoint lives on the page being checked
>     (chicken-and-egg).
>   - `PaginatedCollectionV2.initCollectionState:2256` — same
>     fresh-file pattern.
>   - `PaginatedCollectionV2.open:391` — FSM-rebuild recovery scan;
>     logical bookkeeping was lost, physical-by-design.
>   - `IndexHistogramManager.readSnapshotFromPage:1833` — defensive
>     physical-presence probe for the optional HLL page 1 (guards
>     against partial crash between page-0 and page-1 writes in the
>     same atomic op). **Pre-committed Option A** (gated
>     `getFilledUpTo` helper via Track 5, atomic-op-aware
>     `filledUpTo(fileId)`). Rationale: tx-uniformity with the rest
>     of IHM (read/write paths go through AtomicOperation); structural
>     future-proofing for a hypothetical caller added inside a write
>     tx. Option B (`loadIfPresent(fileId, 1)`) was rejected — it
>     bypasses `AtomicOperation.pageChangesMap`, which would silently
>     miss in-tx writes to page 1 from any future caller. Zero extra
>     surface vs Option B: Track 5's gated helper is already required
>     for CDPB:141/168.
>   - `CollectionDirtyPageBitSet.{clear:141, nextSetBit:168}` —
>     EP-less pure-sizing bounds checks under per-component lock.
> - Collapse the do/while reconciliation loops:
>   - `AtomicOperationBinaryTracking.commitChanges` lines 850-872 —
>     remove the `if (cacheEntry == null) { do { allocateNewPage }
>     while (idx != pageIndex) }` branch. `loadOrAddForWrite` is
>     total on disk; on in-memory engine the new
>     `loadOrAddPageForWrite` eagerly installed the page at
>     allocation time, so `loadOrAddForWrite` at commit time finds
>     it. `pageChangesMap` iteration keys on the actual returned
>     `pageIndex` (already the case post-Track-1 since the iteration
>     reads `filePageChangesEntry.getLongKey()`).
>   - `AbstractStorage.restoreAtomicUnit` `UpdatePageRecord` branch
>     at lines 5400-5408 and `PageOperation` branch at lines
>     5479-5486 — each collapses to a single `loadOrAddForWrite`
>     call (gap-fill handled by `loadOrAdd`).
>   - `DiskStorage.restoreFromIncrementalBackup` lines 1826-1833 —
>     same shape.
> - Drop `AtomicOperationBinaryTracking.internalFilledUpTo` private
>   helper method at line 517-528 in **step 7** (the dead-code-cleanup
>   step), not earlier. The helper has two callers today:
>   `filledUpTo:514` (the impl of the `AtomicOperation.filledUpTo`
>   interface method) and `addPage:355`. The latter is dead by
>   step 6 (call-site migrations are done in steps 2-3, test
>   fixtures in step 4, replay loops in step 5 — none of which
>   require `addPage` to be removed). Step 7 deletes
>   `AtomicOperationBinaryTracking.addPage` (removing the :355
>   caller) and simultaneously inlines the helper's three-arm logic
>   into `filledUpTo` (new file → `maxNewPageIndex + 1`, truncated
>   → 0, committed → `writeCache.getFilledUpTo(fileId)`), removing
>   the helper. **Keep the `maxNewPageIndex` field** (line 1274) —
>   it serves as the in-progress-TX visibility horizon for new
>   files (consumed by `loadPageForWrite`, `loadPageForRead`,
>   `hasChangesForPage` at AOBT:233, :282, :428). The new
>   `loadOrAddPageForWrite` bumps this field exactly as today's
>   `addPage` does.
> - Migrate the **15 test fixture sites** that call
>   `op.addPage(fileId)` or `op.addPage(...)` directly. PSI
>   ReferencesSearch found:
>   - `FlushPendingOperationsTest` — 4 sites
>   - `PageOperationAccumulationLifecycleTest` — 4 sites
>   - `RegisterPageOperationTest` — 1 site
>   - `AtomicOperationSnapshotProxyTest` — 3 sites
>   - `AtomicOperationBinaryTrackingWALSkipTest` — 1 site
>     (`verify(readCache).allocateNewPage(...)` Mockito assertion)
>   - `CollectionDirtyPageBitSetTest:375` — 1 Mockito stub
>   - `CollectionPositionMapV2Test:1204` — 1 site
>
>   Each call migrates from `op.addPage(fileId)` to
>   `op.loadOrAddPageForWrite(fileId, expectedNextIndex)` where
>   `expectedNextIndex` is 0 for fresh-file or the value the
>   surrounding test arithmetic dictates.
>   `Mockito.when(op.addPage(FILE_ID))` becomes
>   `Mockito.when(op.loadOrAddPageForWrite(FILE_ID, expectedIndex))`.
>   Also migrate the parallel `allocateNewPage` test callers:
>   `LockFreeReadCacheBatchingTest` (cache-size verification),
>   `WOWCacheTestIT` (~10 calls), `WOWCacheNonDurableFileTrackingTest`
>   — these get rewritten to use `loadOrAdd` or `loadOrAddForWrite`.
>   PSI ReferencesSearch on each deprecated cache method is the
>   audit gate before step 7 deletes them.
> - After all migration steps land, delete
>   `LockFreeReadCache.allocateNewPage`, `WOWCache.allocateNewPage`,
>   `DirectMemoryOnlyDiskCache.allocateNewPage`,
>   `WriteCache.allocateNewPage` (interface),
>   `AtomicOperation.addPage`, `AtomicOperationBinaryTracking.addPage`,
>   and `StorageComponent.addPage`. Use the IDE safe-delete recipe
>   (`mcp-steroid://ide/safe-delete`) to confirm zero remaining
>   callers immediately before deletion.
> - Add a Java `assert false` (with a descriptive message) to the
>   defensive totality fallback in `WOWCache.loadOrAddLoadBranch`
>   where `loadFileContent` returns null. Per the method's existing
>   Javadoc the path is dead code today (dispatch prelude routes any
>   pageIndex past `currentSize` to extend or gap-fill), so the
>   assertion surfaces a regression in test runs (`-ea`) without
>   runtime cost in production. Message: `"loadFileContent returned
>   null on load branch — dispatch prelude invariant violated;
>   pageIndex < currentSize should always find a page on disk"`.
>   Surfaced by Track 2 Phase C track-level code review
>   (test-crash-safety dimension).
>
> **Per-component recipe (logicalSize source per component)**:
>
> | Component | EntryPoint class | Logical-size getter / setter | WAL op | Migration target |
> |---|---|---|---|---|
> | BTree (singlevalue v3) | `CellBTreeSingleValueEntryPointV3` | `getPagesSize` / `setPagesSize` | `SetPagesSizeOp` | `entryPoint.getPagesSize() + 1` |
> | SharedLinkBagBTree | `EntryPoint` (its own) | `getPagesSize` / `setPagesSize` | `SetPagesSizeOp` | `entryPoint.getPagesSize() + 1`; **`splitRootBucket` two-page special case** — see below |
> | CollectionPositionMapV2 | `MapEntryPoint` | `getFileSize` / `setFileSize` | `MapEntryPointSetFileSizeOp` | `mapEntryPoint.getFileSize() + 1` |
> | PaginatedCollectionV2 | `PaginatedCollectionStateV2` | `getFileSize` / `setFileSize` | `PaginatedCollectionStateV2SetFileSizeOp` | `collectionState.getFileSize() + 1` |
> | FreeSpaceMap | (EP-less) | n/a | n/a | growth-loop iterator `i`; no setter follow-up |
> | CollectionDirtyPageBitSet | (EP-less) | n/a | n/a | growth-loop iterator `i`; no setter follow-up |
> | IndexHistogramManager (createEmptyStatsPage:1800) | n/a (fresh-file at pageIndex 0) | n/a | n/a | pageIndex 0 |
>
> Per-site template for an EP-equipped probe:
>
> ```java
> var newPageIndex = entryPoint.<getter>() + 1;  // pagesSize or fileSize per component
> var entry = atomicOperation.loadOrAddPageForWrite(fileId, newPageIndex);
> entryPoint.<setter>(newPageIndex);  // setPagesSize or setFileSize per component
> ```
>
> **`SharedLinkBagBTree.splitRootBucket` two-page special case** —
> the method allocates two pages back-to-back at lines 1057 and 1066
> (leftBucketEntry + rightBucketEntry) inside a single
> `loadPageForWrite(ENTRY_POINT_INDEX)` block. The migration must
> emit two sequential `loadOrAddPageForWrite` calls, each preceded
> by a fresh `entryPoint.getPagesSize() + 1` read, and a single
> `entryPoint.setPagesSize(newPageIndexAfterSecond)` at the end
> (today the EP bump happens at line 1070). Naïvely reusing one
> `pagesSize + 1` value for both calls would target the same
> pageIndex twice — an I4 violation within a single transaction
> that would trip the fail-fast `IllegalStateException` Track 1
> added to `WOWCache.loadOrAdd`.
>
> **For fresh-file sites** (`create()` / `init()`), the target
> pageIndex is known statically (0 or 1). Migration is a direct
> replacement of `addPage(...)` with
> `loadOrAddPageForWrite(fileId, 0)` (or 1) — no probe block.
>
> **Replay-loop collapse**: today's loop runs
> `readCache.allocateNewPage` repeatedly until the returned pageIndex
> matches the WAL record's pageIndex. With `loadOrAdd` doing
> gap-fill internally, the loop becomes a single
> `loadOrAddForWrite(fileId, recordedPageIndex)` call — the cache
> gap-fills if needed, returns the requested entry directly.
>
> **Phase A audit deliverables (gated before step 1 ships)**:
>
> 1. **Per-component lock audit** — per design.md §"Concurrency
>    model" Lock layering bullet 1, document each touched component's
>    serialization mechanism for allocators sharing a fileId:
>    - For BTree singlevalue v3, SharedLinkBagBTree,
>      CollectionPositionMapV2, PaginatedCollectionV2, FreeSpaceMap,
>      IndexHistogramManager, CollectionDirtyPageBitSet: identify
>      whether the lock is `executeInsideComponentOperation`'s
>      acquire-exclusive on the StorageComponent base class, an
>      internal `synchronized(this)`, a dedicated `Lock` field, or
>      something else.
>    - Confirm via PSI that every public allocator entry point
>      enters via the locked path. Note any caller path that
>      bypasses the lock.
> 2. **IHM.flushSnapshotToPage concurrency audit** — PSI
>    call-hierarchy on `flushSnapshotToPage(IHM:1898)` to determine
>    whether concurrent invocation with `writeSnapshotToPage` for
>    the same fileId can happen.
>    - If reachable: the step also wraps `flushSnapshotToPage` in
>      `executeInsideComponentOperation` (acquires the per-component
>      exclusive lock). Without this fix, Track 4's new fail-fast
>      `IllegalStateException` in `WOWCache.loadOrAdd` would surface
>      a hard crash where today's `addPage`-based path silently
>      mis-allocates.
>    - If not reachable (pre-existing latent hazard, no production
>      trigger): record the verification result in the step file and
>      leave the call unwrapped; file a follow-up ticket for the
>      defensive wrapping.
>
> **How** (revised step ordering — see §Steps for the concrete
> decomposition):
> 1. Add `op.loadOrAddPageForWrite` + Phase A audits + IHM
>    flushSnapshot wrap (if reachable).
> 2. Migrate BTree + SharedLinkBagBTree call sites + BTree
>    `doAssertFreePages` pure-sizing migration.
> 3. Migrate Collection v2 + Paginated v2 + FSM + IHM + CDPB call
>    sites.
> 4. Migrate AtomicOperation* and cache test fixture sites.
> 5. Collapse replay-loop reconciliation (commitChanges +
>    restoreAtomicUnit + restoreFromIncrementalBackup) + add
>    WOWCache `assert false`. Defer `internalFilledUpTo` drop to
>    step 7 (it has an `addPage:355` caller that step 7 removes).
> 6. Add rationale-bearing inline comments at the 6 stay-on-physical
>    sites (Option A for IHM:1833 pre-committed).
> 7. Delete dead-code surfaces: `addPage` (interface + impl +
>    StorageComponent), `internalFilledUpTo` private helper
>    (inlined into `filledUpTo`), and `allocateNewPage` from
>    `WriteCache` / `LockFreeReadCache` / `WOWCache` /
>    `DirectMemoryOnlyDiskCache`.
>
> **Constraints**:
> - **In-scope files**:
>   - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java`
>   - `core/.../storage/impl/local/paginated/atomicoperations/AtomicOperation.java` (interface)
>   - `core/.../storage/impl/local/AbstractStorage.java`
>     (`restoreAtomicUnit` and helpers — note: at the `local/` package
>     level, not under `local/paginated/`)
>   - `core/.../storage/disk/DiskStorage.java`
>     (`restoreFromIncrementalBackup` only)
>   - `core/.../storage/impl/local/paginated/base/StorageComponent.java`
>     (under `paginated/base/`, not directly under `paginated/`)
>   - `core/.../storage/cache/chm/LockFreeReadCache.java`,
>     `core/.../storage/cache/local/WOWCache.java`,
>     `core/.../storage/memory/DirectMemoryOnlyDiskCache.java` (final
>     `allocateNewPage` deletions; bodies were already redirected in
>     Track 1)
>   - `core/.../storage/cache/WriteCache.java` (interface
>     `allocateNewPage` deletion)
>   - All concrete component classes named in the call-site table
>     (BTree singlevalue v3, SharedLinkBagBTree, CollectionPositionMapV2,
>     PaginatedCollectionV2, FreeSpaceMap, IndexHistogramManager,
>     CollectionDirtyPageBitSet).
>   - Test files holding the 15 `op.addPage` + `allocateNewPage`
>     callers: `FlushPendingOperationsTest`,
>     `PageOperationAccumulationLifecycleTest`,
>     `RegisterPageOperationTest`, `AtomicOperationSnapshotProxyTest`,
>     `AtomicOperationBinaryTrackingWALSkipTest`,
>     `CollectionDirtyPageBitSetTest`, `CollectionPositionMapV2Test`,
>     `LockFreeReadCacheBatchingTest`, `WOWCacheTestIT`,
>     `WOWCacheNonDurableFileTrackingTest`.
> - **Out of scope**: cache classes' `loadOrAdd` bodies (Track 1
>   already shipped them); `getFilledUpTo` access modifier and gated
>   helpers (Track 5); WAL classes; DoubleWriteLog.
> - WAL format unchanged — page extension stays implicit. The
>   `SetPagesSizeOp` / `SetFileSizeOp` records continue to carry the
>   logical-size bump; no `AddPage*` record is introduced. The order
>   of WAL records emitted by `commitChanges` is preserved (on disk
>   engine `loadOrAdd` returns sequential indices via
>   `AsyncFile.allocateSpace.getAndAdd`; on in-memory engine the
>   in-memory `loadOrAdd` also returns sequential indices).
> - **Crash safety**: each replay-loop collapse must preserve today's
>   semantics for the three scenarios documented in design.md
>   §"Crash safety" (TX in-flight, TX committed task-not-run, TX
>   committed task-ran-fully). The "task partially ran" case is
>   foreclosed by the FIFO + monotonic-submission executor model
>   (`wowCacheFlushExecutor` is single-threaded per
>   `YouTrackDBEnginesManager.java:231` via
>   `newSingleThreadScheduledPool`; per-component lock serializes
>   allocators); the orthogonal torn-write / writeback durability
>   gap is tracked separately as
>   `ISSUE-ensurevalidpagetask-torn-write.md`.
> - The deletion of `allocateNewPage` and `addPage` requires that no
>   test code calls them directly. Phase A audits this via PSI
>   ReferencesSearch on each deprecated method immediately before
>   step 7 deletes them (use `mcp-steroid://ide/safe-delete` recipe).
>
> **Interactions**:
> - Depends on Track 1 (the cache primitive must exist before
>   migration starts).
> - Independent of Track 2 (parallel — Track 2 tests catch regressions
>   in real time).
> - Enables Track 5 (final `getFilledUpTo` lockdown — Track 5's helper
>   set is shaped by Track 4's per-site rationale comments) and Track 6
>   (regression test must run against the post-migration code path).
> - Verifies invariants **I1** + **I4** at the source-code level for
>   the EP-equipped components after the BTree pure-sizing migration
>   and the probe collapses land; the EP-less stay-on-physical sites
>   are pinned by D4's gated-helper surface (Track 5).

## Progress
- [x] Review + decomposition
- [ ] Step implementation (2/7 complete)
- [ ] Track-level code review

## Reviews completed
- [x] Technical: PASS at iteration 3 (9 findings; 4 should-fix accepted as plan amendments — T1 test scope, T2 per-component recipe, T3 doAssertFreePages wording, T4 lock audit; 4 suggestions actioned or noted; 1 SKIP rejected — Track 4 required)
- [x] Risk: PASS at iteration 3 (12 findings; 5 should-fix accepted — R1 maxNewPageIndex preservation, R2 recipe table, R3 doAssertFreePages, R4 IHM flushSnapshot audit deferred to Phase B, R6 test fixture step; 4 suggestions deferred/actioned; 1 SKIP rejected; R11 progress check resolved by populating Steps section)
- [x] Adversarial: PASS at iteration 3 (10 findings + 1 regression A11; 2 blockers fixed — A1 in-memory engine fallback spec, A2 step reorder; A11 regression — `internalFilledUpTo` drop moved from Step 5 to Step 7 alongside `addPage` deletion; 5 should-fix accepted — A3 test scope, A4 SLBB two-page, A5 IHM Option A pre-commit, A6 track stays unsplit per user, A7-A8 wording fixes; 1 SKIP rejected; suggestions A9-A10 deferred)

## Steps

- [x] Step 1: Add `op.loadOrAddPageForWrite` + Phase A audits + IHM flushSnapshot wrap
  - [x] Context: info
  > **Risk:** high — concurrency (new AtomicOperation method must handle
  > both engines' totality contracts; per-component lock interaction);
  > public API (new method on the `AtomicOperation` interface affects
  > every implementer).
  >
  > **What was done:** Added `AtomicOperation#loadOrAddPageForWrite(long, long)`
  > to the SPI and implemented it in `AtomicOperationBinaryTracking` with
  > the dual-engine dispatch the plan specifies — disk engine delegates to
  > `ReadCache#loadOrAddForWrite` (total after Track 1) and immediately
  > releases the exclusive lock so the delegate's lifecycle matches
  > `loadPageForWrite`; in-memory engine falls back to
  > `WriteCache#loadOrAdd` when the read-cache wrapper returns null and
  > balances the bumped `CachePointer` readers-referrer with a single
  > `decrementReadersReferrer()` after the wrap (fixed during review
  > iteration 1 — the original commit leaked one referrer per in-memory
  > allocation). Bookkeeping preserves today's `addPage` side effects:
  > `maxNewPageIndex` bump, `pageChangesMap` insert keyed by the actual
  > returned `pageIndex`, `isNew=true` when `pageIndex >= committedFilledUpTo`,
  > and a new pre-insert `assert` mirroring legacy `addPage`'s
  > `pageChangesMap == null` invariant. `StorageComponent#loadOrAddPageForWrite`
  > rewired to delegate to the new SPI method (drops the legacy
  > `loadPageForWrite`-then-`addPage` fallback). Phase A audit 1
  > (per-component lock): all 19 production `addPage` call sites enter
  > under `executeInsideComponentOperation` /
  > `calculateInsideComponentOperation` (BTree, SLBB, PCV2, CPMV2 directly;
  > FSM and CDPB transitively via PaginatedCollectionV2's lock); IHM's
  > `flushSnapshotToPage` was the lone unlocked caller. Phase A audit 2
  > (IHM concurrency): `flushSnapshotToPage` is reachable from
  > `applyDelta`/`flushIfDirty`/`closeStatsFile`/`doRebalance` while
  > `writeSnapshotToPage` runs from `buildHistogram`/`createStatsFileWithCounters`/
  > `flushIfDirty(AtomicOperation)` — neither side serialized naturally.
  > Resolved by acquiring the per-component exclusive lock on **both**
  > sides: `flushSnapshotToPage` wraps in
  > `executeInsideAtomicOperation -> executeInsideComponentOperation`
  > (creates a standalone atomic op when called without one);
  > `writeSnapshotToPage` calls
  > `AtomicOperationsManager#acquireExclusiveLockTillOperationComplete`
  > directly inside its existing atomic-op context.
  >
  > **What was discovered:** (1) Two production-code surprises landed
  > during the Phase C-equivalent review loop: the in-memory
  > `WriteCache.loadOrAdd` Javadoc explicitly states callers must
  > `decrementReadersReferrer()` to release, but the original implementation
  > wrapped the bumped pointer in a fresh
  > `CacheEntryImpl(insideCache=false)` whose release path never touches
  > readers-referrer — a permanent native-memory leak on the in-memory
  > engine. Caught by three reviewers independently (BC1). (2) The
  > initial IHM lock wrap was one-sided — only `flushSnapshotToPage`
  > took the IHM component lock, while `writeSnapshotToPage` ran
  > lock-free. The audit's stated objective ("close the race") was not
  > met; Step 3's `createEmptyStatsPage` migration would have hard-crashed
  > on Track 1's fail-fast `IllegalStateException`. Caught by two
  > reviewers (BC2). (3) `AtomicOperationsManager.executeInsideComponentOperation`
  > rewraps `IOException` to the unchecked `CommonStorageComponentException`,
  > which would have broken `IHM.flushIfDirty(AtomicOperation)`'s
  > `catch(IOException)` dirty-mutation-counter restoration. The fix
  > calls `acquireExclusiveLockTillOperationComplete` directly to
  > preserve the checked exception transparency. The pattern (direct
  > acquire vs `executeInsideComponentOperation` when the wrapped method
  > already throws `IOException` and callers catch it specifically) is a
  > recipe future Track 4 migrations should follow if they encounter
  > the same shape.
  >
  > **What changed from the plan:** Plan unchanged — Step 1 lands as
  > planned. The in-memory referrer-balancing call and the
  > `writeSnapshotToPage` lock acquisition are corrections inside
  > Step 1's scope, not plan deviations. The audit 2 resolution still
  > matches the plan's intent ("wrap `flushSnapshotToPage` in
  > `executeInsideComponentOperation`") — Step 1 just extended the
  > resolution to both sides of the race.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperation.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/base/StorageComponent.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexHistogramManager.java`
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/LoadOrAddPageForWriteTest.java`
  >
  > **Critical context:** The new method's disk-engine delegate is
  > intentionally NOT write-locked on return (lock released immediately
  > after `loadOrAddForWrite`'s install). Future call-site migrations
  > (Step 2-3) must NOT add extra `acquireExclusiveLock` calls — the
  > overlay model (`CacheEntryChanges.changes` buffer) handles
  > concurrency at the AOBT layer, not the cache layer. Track 5 must
  > keep `WriteCache.getFilledUpTo` callable from
  > `AtomicOperationBinaryTracking#loadOrAddPageForWrite` (used to read
  > the committed cross-TX horizon for the `isNew` classification) —
  > a private downgrade would break Step 1.
  >
  > **Scope:**
  > - Add `CacheEntry loadOrAddPageForWrite(long fileId, long pageIndex)
  >   throws IOException;` to `AtomicOperation` interface.
  > - Implement in `AtomicOperationBinaryTracking` with the two-engine
  >   spec from the Description (disk: `loadOrAddForWrite` is total;
  >   in-memory: fallback to `loadOrAdd` + write-lock wrap). Preserve
  >   `maxNewPageIndex` bookkeeping, `pageChangesMap` insert keyed by
  >   actual `pageIndex`, `isNew=true` if pageIndex extends beyond
  >   committed `getFilledUpTo`.
  > - Rewire `StorageComponent.loadOrAddPageForWrite(AtomicOperation,
  >   long, long)` body to delegate to
  >   `atomicOperation.loadOrAddPageForWrite(fileId, pageIndex)`.
  >   Today's body calls `loadPageForWrite` then falls back to
  >   `addPage`; the new body has no fallback path.
  > - **Phase A audit 1**: per-component lock audit. For each of the
  >   7 touched components, run PSI find-usages on the allocator
  >   entry methods (e.g., `BTree.create`, `BTree.splitBucket`,
  >   `SharedLinkBagBTree.splitRootBucket`) and confirm each enters
  >   via `executeInsideComponentOperation` (or document the actual
  >   mechanism). Record results in the step's commit message.
  > - **Phase A audit 2**: IHM flushSnapshot concurrency audit. PSI
  >   call-hierarchy on `IHM.flushSnapshotToPage` to determine
  >   concurrent-invocation reachability with `writeSnapshotToPage`
  >   for the same fileId. If reachable, wrap `flushSnapshotToPage`
  >   in `executeInsideComponentOperation` in this same step. If
  >   not, record the verification result and file a follow-up
  >   ticket for the defensive wrapping.
  > - Tests: add a unit test for the new method's two engine paths
  >   (disk + in-memory) — in-memory test must exercise the
  >   `loadOrAddForWrite returns null → fallback to loadOrAdd` branch
  >   and confirm the page is installed in `MemoryFile`. Add a test
  >   asserting `maxNewPageIndex` bumps correctly for both fresh-file
  >   and extend cases.
  > - Spotless apply on `core` after the change.

- [x] Step 2: Migrate BTree + SharedLinkBagBTree call sites + BTree.doAssertFreePages pure-sizing
  - [x] Context: info
  > **Risk:** high — crash-safety/durability (modifies storage
  > components on the BTree-insert hot path; probe collapse changes
  > allocation semantics).
  >
  > **What was done:**
  > Migrated 10 production `addPage` call sites in `BTree`
  > (singlevalue v3) and `SharedLinkBagBTree` to the new
  > `loadOrAddPageForWrite(fileId, knownIndex)` SPI: 3 fresh-file
  > sites in `BTree.create`, 2 fresh-file sites in `SLBB.create`,
  > the single probe at `BTree.allocateNewPage` (free-list reuse
  > branch preserved), the probe at `SLBB.splitNonRootBucket`, and
  > the two-page probe at `SLBB.splitRootBucket` (using
  > `leftPageIndex + 1` for the second target to advance without
  > re-reading `getPagesSize()`). Migrated `BTree.doAssertFreePages`
  > away from `getFilledUpTo` to `entryPoint.getPagesSize() + 1` via
  > a `loadPageForRead` on `ENTRY_POINT_INDEX` (test-only `-ea`
  > path). The step landed across three commits: implementer
  > `a4d49bf4bd`, iteration-1 review fix `48a83793cb`, iteration-2
  > review fix `a44171e632`. The iteration-1 fix resolved a
  > DESIGN_DECISION_NEEDED escalation (BC2) by reshaping the AO API
  > contract: `loadOrAddPageForWrite` is now **allocator-only**, the
  > previously-defensive cache-install branch is deleted as dead
  > code, calling the method with a non-new pageIndex throws
  > `IllegalStateException` with a descriptive message (Option D).
  > The iteration-2 fix closed the BC3/CQ3 blocker the gate-check
  > surfaced (IHM HLL-spill cross-TX regression) by migrating the
  > two pre-existing IHM call sites at `writeSnapshotToPage:1912`
  > and `flushSnapshotToPage:1967` to discriminate at the caller via
  > `op.filledUpTo(fileId)`: load existing page 1 via
  > `loadPageForWrite` on the second-and-later flush, allocate via
  > `loadOrAddPageForWrite` on the first spill. The per-component
  > exclusive lock acquired upstream keeps the read consistent with
  > the subsequent load/allocate call. The same iteration-2 commit
  > applied six should-fix items: CQ4 strengthened the
  > `loadOrAddPageForWrite` javadoc on the interface and impl to
  > make the allocator-only contract unmistakable; CQ5 renamed the
  > AOBT local `committedFilledUpTo` to `allocationFloor` and fixed
  > the comment + throw-message accordingly; TB4 tightened the
  > `loadOrAddPageForWriteThrowsForExistingPage` assertion to pin
  > fileId and the actionable `loadPageForWrite` hint; TC1 added a
  > fast-path coverage test
  > (`secondAllocationOnExistingFileUsesMaxNewPageIndexFastPath`)
  > that pins the `maxNewPageIndex + 1` floor flowing into the
  > throw branch; TC2 added a boundary off-by-one throw test
  > (`loadOrAddPageForWriteThrowsAtBoundaryPageIndex`); TC3 added an
  > AOBT-level unit pin for the SLBB.splitRootBucket two-page recipe
  > (`twoConsecutiveAllocationsProduceDistinctOverlays`). The
  > iteration-1 commit also applied: CQ1 (stale source-line refs in
  > AOBT comments replaced with method-name refs), CQ2 (SLBB.create
  > literal `1` → `ROOT_INDEX` constant), TY2 (branch-invariant
  > assert at the stub-shape `CacheEntryImpl` construction site),
  > TB1/TB2 (`freshBookedFileSkipsReadCacheUntilCommit` asserts
  > `getBuffer() == null` and probes `pageChangesMap` via the SPI),
  > TB3 (new `secondCallReturnsExistingOverlayOnFreshBookedFile`
  > test), PF fast-path on `committedFilledUpTo` (avoid `filesLock`
  > read when `maxNewPageIndex > -2`), BC1 (SLBB.splitRootBucket
  > comment names AOBT idempotency early-return as the failure
  > mode). Iteration-3 gate-check on the four dimensions with
  > applied iter-2 fixes (BC, TB, TC, CQ) all returned PASS.
  >
  > **What was discovered:**
  > - The Step 1 implementer made two unsafe assumptions that
  >   surfaced when the first BTree caller migrated in Step 2: (a)
  >   `readCache.loadOrAddForWrite` cannot be called for a
  >   fresh-booked fileId because the file isn't registered with
  >   WOWCache until commit time; (b) for non-fresh files, calling
  >   `readCache.loadOrAddForWrite` and then re-accessing via
  >   `loadPageForWrite` causes a usage-counter underflow because
  >   `loadPageForWrite` returns the existing `CacheEntryChanges`
  >   without re-loading the delegate. Both led to the initial Step 2
  >   patch routing `isNew=true` through a null-Pointer stub shape.
  >   The subsequent Option D collapse formalises this: the entire
  >   cache-install branch was always dead code, and the stub-shape
  >   path is the ONLY path the method now takes.
  > - **The iteration-1 implementer's trace claim ("every production
  >   allocator targets either a fresh-booked file or a pageIndex
  >   strictly past the committed horizon") was wrong for two
  >   pre-existing IHM call sites at `writeSnapshotToPage:1912` and
  >   `flushSnapshotToPage:1967`.** Those callers — added before
  >   Step 1 — relied on the original "loadPageForWrite-then-addPage
  >   fallback" semantics of `StorageComponent.loadOrAddPageForWrite`
  >   that Step 1 rewired and Step 2 then narrowed. Caught by the
  >   iteration-2 gate-check's bugs-concurrency and code-quality
  >   reviewers (BC3/CQ3) and confirmed by direct inspection of the
  >   IHM comment at line 1909-1910 that documented the load-or-add
  >   intent. The fix discriminates at the caller. Lesson worth
  >   carrying forward: when narrowing an API's contract, audit ALL
  >   reachable callers (including pre-existing ones), not just the
  >   ones explicitly migrated in the current step.
  > - A test-completeness trap surfaced inside the iter-2 fix
  >   itself: a draft of the TC1 fast-path test probed the throw
  >   branch by re-asking for an already-allocated pageIndex; that
  >   pattern hits the idempotency early-return (pageChangesMap
  >   lookup) before the allocator-only guard, so the test silently
  >   passed without exercising the throw. Fixed by probing a
  >   different pageIndex absent from `pageChangesMap`. Worth
  >   documenting for future allocator-only tests: any "below-floor"
  >   assertion must use a pageIndex never previously registered in
  >   this operation. Capture lives in the test file's inline
  >   comments and in the TC3 javadoc.
  > - The iteration-1 review surfaced 30+ findings across 8
  >   dimensions; 8 should-fix items applied. The iteration-2
  >   gate-check surfaced one blocker (BC3/CQ3) plus six should-fix
  >   items, all applied in `a44171e632`. The iteration-3 gate-check
  >   verified all applied fixes and surfaced one cumulative
  >   should-fix (TB8/CQ9 — five stale `committedFilledUpTo`
  >   references in `LoadOrAddPageForWriteTest.java` javadocs and
  >   inline comments that the CQ5 rename missed; the tests
  >   themselves are correct, only surrounding prose is out of
  >   sync) plus three suggestion-level items (TB9 javadoc
  >   correctness on `twoConsecutiveAllocationsProduceDistinctOverlays`,
  >   TB10 defense-in-depth pin on TC1, TC6 cross-track hint for
  >   Track 6, CQ10 DRY cross-reference between twin IHM sites).
  >   The iteration-3 cap was reached with these items open; they
  >   are recorded here for natural-home pickup in Step 3 (test-file
  >   touches), Track 5 / Track 6 (cross-track items), or a
  >   subsequent cleanup pass.
  >
  > **What changed from the plan:**
  > - **Step 1's API contract is narrower than planned**:
  >   `loadOrAddPageForWrite` is now allocator-only (callers must
  >   target a new pageIndex). The "or Add" semantics for existing
  >   pages is removed. This narrowing strengthens the design — every
  >   migrated site has a known-new target, so the contract matches
  >   reality. Step 5's replay-loop collapse must continue to go
  >   through `readCache.loadOrAddForWrite` directly (the cache
  >   primitive remains "load or add" — only the AO-layer wrapper is
  >   narrowed).
  > - **Scope expansion**: this step touches
  >   `IndexHistogramManager.writeSnapshotToPage` and
  >   `flushSnapshotToPage` (HLL page-1 call sites at lines 1912 and
  >   1967), which the plan lists as Step 3 / Step 7 territory. The
  >   natural-home rule placed the BC3/CQ3 blocker fix here because
  >   Step 2's iter-1 Option D collapse introduced the regression
  >   at those pre-existing call sites. **Step 3 is unaffected** —
  >   the only IHM site Step 3 migrates is `createEmptyStatsPage:1800`
  >   (fresh-file at pageIndex 0), a statically-known-new target
  >   that the allocator-only contract handles correctly. **Step 7
  >   may inherit small test-comment hygiene** (TB8/CQ9 — the five
  >   stale `committedFilledUpTo` references) along with the
  >   suggestion-level defensive asserts TY3 (SLBB.splitRootBucket
  >   adjacency) and TY4 (fast-path consistency).
  > - **Step 5 inherits BC4** (deferred should-fix on the
  >   BTree/SLBB defensive reuse path): the iter-1 reviewer flagged
  >   that the OLD `if (pagesSize < filledUpTo - 1) { reuse } else
  >   { extend }` probe handled a partial-replay residue that the
  >   new allocator-only contract throws on. The natural home is
  >   Step 5's replay-loop collapse audit of the partial-replay
  >   invariants in `commitChanges` / `restoreAtomicUnit` /
  >   `restoreFromIncrementalBackup`.
  >
  > **Key files:**
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperationBinaryTracking.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/AtomicOperation.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/ridbagbtree/SharedLinkBagBTree.java`
  > - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/IndexHistogramManager.java`
  > - `core/src/test/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/paginated/atomicoperations/LoadOrAddPageForWriteTest.java`
  >
  > **Critical context:**
  > - **Track 5 helper-set constraint**: the Track 5 lockdown must
  >   keep both surfaces accessible from production code:
  >   `WriteCache.getFilledUpTo(fileId)` is called by AOBT for the
  >   in-method `isNew` classification on the slow path
  >   (`!fileIsNew && maxNewPageIndex == -2`); `AtomicOperation.filledUpTo(fileId)`
  >   is called from the new IHM HLL-spill discriminator at IHM:1919
  >   and IHM:1984. Both reads happen inside the per-component
  >   exclusive lock, so the gated-helper design from Track 5 Phase A
  >   must not block either path.
  > - **Track 6 regression-test constraint**: the integration test
  >   workload should exercise an HLL-spill pattern that triggers
  >   page-1 writes more than once in the same lifecycle — i.e., a
  >   sequence that flushes the HLL snapshot, mutates the index,
  >   flushes again, and verifies both branches of the new IHM
  >   discriminator. The iter-1 narrowing would have surfaced as a
  >   hard crash on the second spill, so this scenario is now a
  >   load-bearing test target.
  > - **Open should-fix carried forward** (iter-3 cap reached):
  >   TB8/CQ9 — five stale `committedFilledUpTo` references in
  >   `LoadOrAddPageForWriteTest.java` (lines 289, 318, 323, 335,
  >   339, 347). The CQ5 rename caught the production code path
  >   but missed the test-file documentation prose. Comment-only
  >   fix, safe to land alongside any future test-file touch in
  >   Step 3 or Step 7.
  > - **Open suggestions carried forward**: TB9 (javadoc on TC3
  >   misdescribes which hazard the test catches), TB10 (TC1
  >   defense-in-depth on `hasChangesForPage`), TC6 (Track 6 should
  >   pin both branches of the IHM discriminator), CQ10 (one-line
  >   cross-reference comment linking the twin IHM HLL-spill
  >   sites). None blocker-grade; pick up opportunistically.

- [ ] Step 3: Migrate Collection v2 + PaginatedCollection v2 + FSM + IHM + CDPB call sites
  > **Risk:** high — crash-safety/durability (modifies durable
  > storage components; growth-loop body change touches the durable
  > size-tracking semantics).
  >
  > **Scope:**
  > - `CollectionPositionMapV2`: migrate `create:138` (fresh-file)
  >   and `allocate:216, :243` (two probes; target =
  >   `mapEntryPoint.getFileSize() + 1`; setter =
  >   `setFileSize`).
  > - `PaginatedCollectionV2`: migrate
  >   `initCollectionState:2257` (fresh-file) and
  >   `allocateNewPage:2237` (probe; target =
  >   `collectionState.getFileSize() + 1`; setter =
  >   `setFileSize`).
  > - `FreeSpaceMap`: migrate `create:112` (fresh-file) and the
  >   growth-loop body at `updatePageFreeSpace:229` (loop persists;
  >   body migrates from `addPage` to
  >   `loadOrAddPageForWrite(fileId, i)`; no setter — EP-less).
  > - `IndexHistogramManager`: migrate `createEmptyStatsPage:1800`
  >   (fresh-file at pageIndex 0).
  > - `CollectionDirtyPageBitSet`: migrate `create:60` (fresh-file)
  >   and the growth-loop body at `ensureCapacity:197` (same
  >   shape as FSM; EP-less).
  > - Tests: existing component unit tests
  >   (`CollectionPositionMapV2Test`, `PaginatedCollectionV2Test`
  >   if present, `FreeSpaceMapTest`,
  >   `IndexHistogramManagerTest` if present,
  >   `CollectionDirtyPageBitSetTest`). Verify coverage with the
  >   coverage gate.
  > - Spotless apply on `core`.

- [ ] Step 4: Migrate AtomicOperation* and cache test fixture sites
  > **Risk:** medium — test infrastructure (touches shared test
  > fixtures; no production code change in this step but the migrations
  > enable safe deletion in step 7).
  >
  > **Scope:**
  > - Migrate `op.addPage(fileId)` callers:
  >   `FlushPendingOperationsTest` (4), `PageOperationAccumulationLifecycleTest`
  >   (4), `RegisterPageOperationTest` (1), `AtomicOperationSnapshotProxyTest`
  >   (3), `AtomicOperationBinaryTrackingWALSkipTest` (1
  >   `verify(readCache).allocateNewPage(...)`),
  >   `CollectionDirtyPageBitSetTest:375` (Mockito stub),
  >   `CollectionPositionMapV2Test:1204`. Each becomes
  >   `op.loadOrAddPageForWrite(fileId, expectedIndex)` where
  >   `expectedIndex` is computed from surrounding test setup.
  >   Mockito stubs change signature accordingly.
  > - Migrate cache `allocateNewPage` callers:
  >   `LockFreeReadCacheBatchingTest`, `WOWCacheTestIT` (~10 sites),
  >   `WOWCacheNonDurableFileTrackingTest`. Each becomes
  >   `loadOrAdd(...)` or `loadOrAddForWrite(...)` depending on the
  >   tested contract.
  > - PSI ReferencesSearch confirms zero remaining callers of
  >   `AtomicOperation.addPage`, `AtomicOperationBinaryTracking.addPage`,
  >   `StorageComponent.addPage`, and all four `allocateNewPage`
  >   declarations after this step lands.
  > - Tests: build all touched test classes; run them; confirm pass.
  > - Spotless apply on `core`.

- [ ] Step 5: Collapse replay-loop reconciliation + add WOWCache `assert false`
  > **Risk:** high — crash-safety/durability (modifies WAL replay
  > and commit paths; foundation of recovery semantics).
  >
  > **Scope:**
  > - `AtomicOperationBinaryTracking.commitChanges`: delete the
  >   `if (cacheEntry == null) { do { allocateNewPage } while
  >   (cacheEntry.getPageIndex() != pageIndex) }` block at lines
  >   860-872. Remove the surrounding TODO comment at 853-859.
  >   `pageChangesMap` iteration order is preserved (Long2ObjectMap
  >   key-sorted iteration on actual pageIndex).
  > - `AbstractStorage.restoreAtomicUnit`: collapse both reconciliation
  >   loops — `UpdatePageRecord` branch at 5400-5408 and `PageOperation`
  >   branch at 5479-5486 — to single `loadOrAddForWrite` calls.
  > - `DiskStorage.restoreFromIncrementalBackup`: collapse the do/while
  >   at 1826-1833 to a single `loadOrAddForWrite` call.
  > - **Do NOT drop `internalFilledUpTo`** in this step — its
  >   `addPage:355` caller is still alive until step 7 removes
  >   `AtomicOperationBinaryTracking.addPage`. The helper is dropped
  >   in step 7 alongside the `addPage` deletion.
  > - Add `assert false : "loadFileContent returned null on load
  >   branch — dispatch prelude invariant violated; pageIndex <
  >   currentSize should always find a page on disk";` to the
  >   defensive fallback in `WOWCache.loadOrAddLoadBranch` where
  >   `loadFileContent` returns null. The existing Javadoc says
  >   this path is dead code today; the assertion surfaces a
  >   regression in `-ea` test runs without runtime cost in
  >   production.
  > - Tests: existing `RestoreAtomicUnitPageOperationTest`,
  >   `LocalPaginatedStorageRestoreFromWALIT`,
  >   `StorageBackupMTStateTest`. Add a gap-fill replay regression
  >   test (a WAL record stream with an artificial gap exercising
  >   the `recordedPageIdx > currentSize` case) if existing tests
  >   don't cover that branch.
  > - Spotless apply on `core`.

- [ ] Step 6: Add rationale-bearing inline comments at the 6 stay-on-physical sites
  > **Risk:** low — comments-only (Javadoc / inline comments
  > documenting the physical-size access contract).
  >
  > **Scope:**
  > - `CollectionPositionMapV2.create:136` — comment: "Bootstrap
  >   emptiness check on the EntryPoint page (chicken-and-egg); the
  >   page being checked IS the EntryPoint, so logical bookkeeping
  >   is unavailable. Track 5 routes this through the gated
  >   `physicalSize`-shaped helper."
  > - `PaginatedCollectionV2.initCollectionState:2256` — comment:
  >   same bootstrap pattern as CPMV2.create.
  > - `PaginatedCollectionV2.open:391` — comment: "FSM-rebuild
  >   recovery scan; logical bookkeeping was lost in a prior crash,
  >   so physical extent is the only source of truth here. Routes
  >   through Track 5's gated helper."
  > - `IndexHistogramManager.readSnapshotFromPage:1833` — comment:
  >   "Defensive physical-presence probe for the optional HLL page
  >   1. Pages 0 and 1 are written in the same atomic op; this probe
  >   guards against partial crash between page-0 and page-1
  >   writes. Routes through Track 5's gated `physicalSize` helper
  >   for tx-uniform behavior with the rest of IHM." **Option A
  >   pre-committed; do NOT migrate to `loadIfPresent`.**
  > - `CollectionDirtyPageBitSet.clear:141` — comment: "EP-less
  >   pure-sizing bounds check under per-component lock; physical
  >   extent is the only available source. Routes through Track 5's
  >   gated helper."
  > - `CollectionDirtyPageBitSet.nextSetBit:168` — comment: same
  >   shape as `clear:141`.
  > - No code change; no tests needed.
  > - Spotless apply on `core` (no-op expected).

- [ ] Step 7: Delete dead-code surfaces (`addPage`, `internalFilledUpTo`, `allocateNewPage`)
  > **Risk:** high — public API surface (interface method
  > deletions); architecture (load-bearing SPI). Irreversible point
  > in the track — once landed, prior `addPage`/`allocateNewPage`
  > callers cannot compile.
  >
  > **Scope:**
  > - PSI ReferencesSearch on each method below; confirm zero
  >   remaining callers after steps 1-6.
  > - Use `mcp-steroid://ide/safe-delete` recipe to drive each
  >   deletion through the IDE refactoring engine; the engine will
  >   refuse if any caller remains.
  > - Delete:
  >   - `AtomicOperation.addPage(long)` (interface method)
  >   - `AtomicOperationBinaryTracking.addPage(long)` (impl;
  >     removes the last `internalFilledUpTo` caller at AOBT:355)
  >   - `StorageComponent.addPage(AtomicOperation, long)` (protected
  >     method)
  >   - `WriteCache.allocateNewPage(long, ...)` (interface method)
  >   - `LockFreeReadCache.allocateNewPage(...)` (deprecated stub)
  >   - `WOWCache.allocateNewPage(...)` (deprecated stub)
  >   - `DirectMemoryOnlyDiskCache.allocateNewPage(...)`
  >     (deprecated stub)
  > - Inline `AtomicOperationBinaryTracking.internalFilledUpTo`
  >   private helper (lines 517-528) into
  >   `AtomicOperationBinaryTracking.filledUpTo` (line 514, the
  >   impl of the `AtomicOperation.filledUpTo` interface method).
  >   The three-arm logic becomes inline: new file →
  >   `maxNewPageIndex + 1`, truncated → 0, committed →
  >   `writeCache.getFilledUpTo(fileId)`. Delete the private helper
  >   itself; `maxNewPageIndex` field survives.
  > - Tests: full `core` build + test suite (`./mvnw -pl core clean
  >   test`) to confirm compilation + behavioral correctness.
  > - Spotless apply on `core`.

## Base commit

`62fc621c83`
