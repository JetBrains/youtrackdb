# Track 7: Recovery-time orphan-truncation pass

## Description

Add a recovery-time pass to `AbstractStorage.recoverIfNeeded()` (after
`restoreFromWAL()`, before `flushAllData()`) that walks each EP-equipped
storage component, reads its entry-point logical page count, and truncates
physical orphans via a new `WriteCache.shrinkFile(fileId, targetBytes)`
primitive backed by `AsyncFile.shrink`. Scope is intentionally limited to
the four EP-equipped components subject to CS1 (`BTree`,
`SharedLinkBagBTree`, `CollectionPositionMapV2`, `PaginatedCollectionV2`).
EP-less components (`FreeSpaceMap`, `CollectionDirtyPageBitSet`) and
`IndexHistogramManager` are deliberately excluded — see Non-Goals in
`implementation-plan.md`.

> **What**:
> - **New `WriteCache.shrinkFile(long fileId, long targetBytes)`
>   primitive.** Today `WriteCache` exposes `truncateFile(long fileId)`
>   (truncate to zero, used for `DROP_FILE`-style operations);
>   `shrinkFile` is distinct because it takes a target size and the
>   shrink direction is one-way (no growth). The WOWCache implementation
>   delegates to `AsyncFile.shrink(targetBytes)` — currently only
>   referenced by `AsyncFileTest.java:387`, so this pass is the first
>   production call site. The `DirectMemoryOnlyDiskCache` implementation
>   is a no-op (in-memory engine cannot produce on-disk orphans).
>   Pre-flight check: if `AsyncFile.getFileSize() <= targetBytes`, the
>   method returns without invoking `shrink` so a clean shutdown is a
>   true no-op.
> - **Per-component `verifyAndTruncateOrphans(AtomicOperation,
>   WriteCache)` helper.** Each of the four EP-equipped components
>   implements a helper that:
>   1. Loads its entry-point page read-only via
>      `op.loadPageForRead(fileId, entryPointIndex)`.
>   2. Reads logical page count from the EP — `pagesSize` for
>      `CellBTreeSingleValueEntryPointV3` and `ridbagbtree.EntryPoint`;
>      `fileSize` for `MapEntryPoint` and `PaginatedCollectionStateV2`.
>      Releases the page.
>   3. Computes `targetBytes = (logicalPages + 1) * pageSize` (the +1
>      accounts for the entry-point page at index 0). Phase A locks
>      down the exact +1 / +0 arithmetic per component by reading the
>      existing `create()` initialization paths.
>   4. Calls `writeCache.shrinkFile(fileId, targetBytes)`. The shrinkFile
>      pre-flight makes this a no-op when the file is already at or
>      below the target.
>   The helper runs under the same atomic-operation boundary as the rest
>   of `recoverIfNeeded()`'s component-touching code.
> - **`AbstractStorage.recoverIfNeeded()` wiring.** Between
>   `restoreFromWAL()` and `flushAllData()`, iterate the four
>   EP-equipped component groups via the existing fields on
>   `AbstractStorage`:
>   - `collections : List<StorageCollection>` — each entry is a
>     `PaginatedCollectionV2` (the sole concrete/instantiable
>     production inheritor of `StorageCollection`; the only other
>     inheritor `PaginatedCollection` (V1) is `abstract` and never
>     instantiated by `StorageCollectionFactory`); call
>     `verifyAndTruncateOrphans` on the PCV2 instance AND on its
>     embedded `collectionPositionMap` field (`CollectionPositionMapV2`).
>   - `indexEngines : List<BaseIndexEngine>` — filter by
>     `instanceof BTreeSingleValueIndexEngine || instanceof
>     BTreeMultiValueIndexEngine` (other `BaseIndexEngine` inheritors
>     like `V1IndexEngine`, `RemoteIndexEngine`, the non-BTree
>     single/multi-value engines must be skipped); the BTree-family
>     engines hold one or more `CellBTreeSingleValue<CompositeKey>`-typed
>     fields (`BTreeSingleValueIndexEngine` has `sbTree`;
>     `BTreeMultiValueIndexEngine` has `svTree` + `nullTree` — both
>     are separately-managed BTree instances and both need
>     `verifyAndTruncateOrphans`) whose runtime impl is the concrete
>     `BTree`. Phase A locks the exact accessor shape (cast,
>     `instanceof BTree` filter on the field value, or new getter on
>     the engine).
>   - `linkCollectionsBTreeManager` — holds the `SharedLinkBagBTree`
>     instance.
>   For each component instance: call its `verifyAndTruncateOrphans`
>   helper. The pass is silent on no-op (clean shutdown); on truncate it
>   emits a one-line info log naming the component + fileId + delta
>   pages so operators see when CS1 actually fires.
> - **Unit + integration tests.** The cumulative test surface is the
>   per-component helper unit test (orphan-present → truncate; clean →
>   no-op; logical > physical → no-op with assertion) plus the
>   `AbstractStorage` wiring integration test (open after partial-flush
>   crash → physical = logical post-replay). Track 6 owns the
>   end-to-end CS1 verification; Track 7's tests are scoped to the
>   recovery pass itself.
>
> **How**:
> - Step ordering (provisional):
>   1. Introduce `WriteCache.shrinkFile`; implement on `WOWCache` +
>      `DirectMemoryOnlyDiskCache`. Add unit tests for both, plus a
>      regression test pinning the pre-flight no-op when target ≥
>      current size. **Risk: medium** — adds new SPI method (affects
>      every WriteCache implementer) but the contract is narrow.
>   2. Add `verifyAndTruncateOrphans` to each of the four EP-equipped
>      components. Phase A audits each component's `create()` path to
>      lock the +0 / +1 entry-point offset. **Risk: medium** — touches
>      four storage components; each has its own EP shape.
>   3. Wire into `AbstractStorage.recoverIfNeeded()`. Integration test
>      drives `commitChanges` to its WAL-buffered state on the disk
>      engine, kills the JVM mid-flight, reopens, asserts physical =
>      logical post-replay. **Risk: high** — recovery path, crash-test
>      coordination, side-effect ordering vs `flushDirtyHistograms()` /
>      `flushAllData()`.
>   4. (Optional, decided in Phase A.) Update the existing CPMV2
>      comment blocks at `CollectionPositionMapV2.java:218-224` and
>      `:245-249` to reference the Track 7 recovery pass instead of
>      "hard `IllegalStateException` rather than silent reuse"
>      framing — was deferred from Track 4 Phase C as the TX-6
>      advisory. **Risk: low** — comments only.
> - Phase A audit targets:
>   - The exact `entryPointIndex` per component (BTree uses page 0 with
>     EP; CPMV2 uses page 0 with `MapEntryPoint`; SLBB uses page 0 with
>     `ridbagbtree.EntryPoint`; PCV2 uses page 0 with
>     `PaginatedCollectionStateV2`).
>   - The exact +0 / +1 byte-offset arithmetic for `shrinkFile`'s
>     `targetBytes` — driven by whether `logicalPages` already counts
>     the EP page or not (each component's `create()` path is the
>     authoritative source).
>   - Whether `recoverIfNeeded` already holds an atomic operation when
>     the pass runs, or whether the pass should run inside its own
>     `executeInsideAtomicOperation` like the other post-replay calls
>     (`flushDirtyHistograms`, configuration load).
>   - Confirm `AsyncFile.shrink` is a no-op when `targetBytes >=
>     currentSize` (currently only test-callers; impl needs reading).
>     If not, add the pre-flight in `WOWCache.shrinkFile`.
>
> **Constraints**:
> - **In-scope files**:
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/WriteCache.java` (new method)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/cache/local/WOWCache.java` (impl)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/memory/DirectMemoryOnlyDiskCache.java` (no-op impl)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java` (new helper)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/ridbag/ridbagbtree/SharedLinkBagBTree.java` (new helper)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/CollectionPositionMapV2.java` (new helper + optional comment refresh)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/collection/v2/PaginatedCollectionV2.java` (new helper)
>   - `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/AbstractStorage.java` (wiring in `recoverIfNeeded`)
>   - New unit and integration tests under `core/src/test/...`
> - **Out of scope**: EP-less components (FSM, CDPB) and `IndexHistogram
>   Manager` — see D6 Risks/Caveats. Public API renames. Any change to
>   `WriteCache.getFilledUpTo` / `truncateFile` semantics. Any change to
>   `AsyncFile.shrink`'s signature.
> - **Performance constraint**: the recovery pass adds 4 EP-page-load
>   reads per storage component group, executed exactly once per
>   storage open. Per-component cost: one `loadPageForRead` + one
>   `AsyncFile.getFileSize` + one comparison + (rare) one `shrink`.
>   Expected impact on storage-open latency is negligible (single-
>   page I/O per component); Track 7 does NOT add periodic or
>   per-TX cost.
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
> - **Independent of Track 5** — the recovery pass uses
>   `AsyncFile.getFileSize()` internally to its new `shrinkFile`
>   primitive; it does NOT call `WriteCache.getFilledUpTo`. Track 5's
>   gated-helper work is parallel and non-conflicting; either track
>   can land first.
> - **Feeds Track 6** — Track 6's CS1 integration test asserts the
>   post-replay invariant Track 7 establishes. Track 6 ordering
>   moves to depend on both Track 4 and Track 7.
> - **Implements D6 and establishes I6.** See `implementation-plan.md`
>   §Architecture Notes.
> - **Crash-recovery coordination**: the pass runs **after**
>   `restoreFromWAL()` (so WAL replay has settled logical state) and
>   **before** `flushAllData()` (so the truncated state is flushed in
>   the recovery's atomic close). It does not interact with the WAL
>   directly; the truncate is a pure file-system-level operation
>   protected by the same `stateLock.writeLock()` that wraps `open()`.

## Progress
- [ ] Review + decomposition
- [ ] Step implementation
- [ ] Track-level code review

## Reviews completed

## Steps
