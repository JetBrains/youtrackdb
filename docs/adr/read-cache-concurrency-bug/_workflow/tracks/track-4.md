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
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed
- [x] Technical: PASS at iteration 3 (9 findings; 4 should-fix accepted as plan amendments — T1 test scope, T2 per-component recipe, T3 doAssertFreePages wording, T4 lock audit; 4 suggestions actioned or noted; 1 SKIP rejected — Track 4 required)
- [x] Risk: PASS at iteration 3 (12 findings; 5 should-fix accepted — R1 maxNewPageIndex preservation, R2 recipe table, R3 doAssertFreePages, R4 IHM flushSnapshot audit deferred to Phase B, R6 test fixture step; 4 suggestions deferred/actioned; 1 SKIP rejected; R11 progress check resolved by populating Steps section)
- [x] Adversarial: PASS at iteration 3 (10 findings + 1 regression A11; 2 blockers fixed — A1 in-memory engine fallback spec, A2 step reorder; A11 regression — `internalFilledUpTo` drop moved from Step 5 to Step 7 alongside `addPage` deletion; 5 should-fix accepted — A3 test scope, A4 SLBB two-page, A5 IHM Option A pre-commit, A6 track stays unsplit per user, A7-A8 wording fixes; 1 SKIP rejected; suggestions A9-A10 deferred)

## Steps

- [ ] Step 1: Add `op.loadOrAddPageForWrite` + Phase A audits + IHM flushSnapshot wrap
  > **Risk:** high — concurrency (new AtomicOperation method must handle
  > both engines' totality contracts; per-component lock interaction);
  > public API (new method on the `AtomicOperation` interface affects
  > every implementer).
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

- [ ] Step 2: Migrate BTree + SharedLinkBagBTree call sites + BTree.doAssertFreePages pure-sizing
  > **Risk:** high — crash-safety/durability (modifies storage
  > components on the BTree-insert hot path; probe collapse changes
  > allocation semantics).
  >
  > **Scope:**
  > - BTree (singlevalue v3): migrate fresh-file sites at `create:195,
  >   :201, :208` and the single probe at `allocateNewPage:2163`
  >   (preserve the outer `if (freeListHead > -1) { reuse freelist
  >   page }` branch; collapse only the `else` extend branch to
  >   `loadOrAddPageForWrite(fileId, entryPoint.getPagesSize() + 1)`
  >   + `entryPoint.setPagesSize(...)`).
  > - SharedLinkBagBTree: migrate fresh-file sites at `create:59, :64`,
  >   the single probe at `splitNonRootBucket:929`, and the **two
  >   consecutive probes at `splitRootBucket:1057, :1066`** per the
  >   two-page recipe (re-read `entryPoint.getPagesSize() + 1`
  >   between calls; single `setPagesSize(newSecondPageIndex)` at
  >   the end).
  > - `BTree.doAssertFreePages:1389` pure-sizing migration: add
  >   `loadPageForRead(ENTRY_POINT_INDEX)` at method entry, wrap in
  >   `CellBTreeSingleValueEntryPointV3`, replace
  >   `getFilledUpTo(...)` with `entryPoint.getPagesSize() + 1` as
  >   the upper iteration bound. Test-only assertion path (`-ea`).
  > - Tests: existing BTree unit tests (`BTreeReadMethodsTest`,
  >   `BTreeTestIT`) plus any SharedLinkBagBTree tests under
  >   `core/src/test/java/.../ridbag/`. Verify coverage with the
  >   coverage gate.
  > - Spotless apply on `core`.

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
