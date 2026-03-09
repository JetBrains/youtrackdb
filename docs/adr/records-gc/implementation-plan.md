# Records GC Implementation Plan

## Context

The database uses MVCC to provide snapshot isolation. When a record is updated or deleted,
the previous version is preserved in a snapshot index so that concurrent readers with older
snapshots can still access it. Once all transactions that could observe the old version have
completed, the snapshot index entry is evicted (existing snapshot index GC in
`AbstractStorage.cleanupSnapshotIndex()`).

However, the **physical record data on collection pages is never reclaimed**. Stale record
versions remain on `CollectionPage`s, consuming disk space and degrading page utilization
over time. (Note: `CollectionPositionMapV2` tombstones (REMOVED entries) are **not**
reclaimed — they must be preserved permanently to guarantee RID uniqueness across the
lifetime of the database.)

This plan introduces a **Records GC** that periodically reclaims dead records from
`PaginatedCollectionV2` storage.

### Multi-Page Records (Chunks)

A record in `PaginatedCollectionV2` may be larger than a single `CollectionPage` can hold.
When a record exceeds `MAX_ENTRY_SIZE` (~8 095 bytes for default 8 KB pages), it is split
into multiple **chunks** stored on separate pages. Each chunk carries a `nextPagePointer`
(packed `pageIndex + recordPosition`) that links to the next chunk; the last chunk in the
chain stores `-1`. Chunks are written in **reverse order** (tail-first): the entry-point
chunk — the one referenced by the `CollectionPositionMapV2` `PositionEntry` — is written
last so that a crash during a multi-chunk write leaves the old version intact.

The on-page layout of every chunk ends with:
```
[chunk data ...][firstRecordFlag: 1 B][nextPagePointer: 8 B]
```
`firstRecordFlag == 1` only on the entry-point (start) chunk. The record metadata
(`[recordType][contentSize][collectionPosition]`) is embedded in the **entry-point chunk
only**.

This has two important consequences for GC:

1. **Dirty page bit set tracks only start-chunk pages.** The `PositionEntry.pageIndex`
   always points to the entry-point chunk. Only that page is marked in the dirty page bit
   set, because it is the only page from which the GC can discover the record (read its
   `collectionPosition` and version) and follow the chunk chain.
2. **GC must delete the entire record across all pages.** When the GC determines that a
   record is stale, it follows the `nextPagePointer` chain starting from the entry-point
   chunk and deletes every chunk on every page in the chain, not just the entry-point chunk.

## Goals

1. Reclaim disk pages occupied by stale record versions that are no longer visible to any
   transaction.
2. Keep the GC overhead low — it should not interfere with normal read/write throughput.
3. Make the GC self-tuning: it activates only when the ratio of dead records justifies the
   cost.

## Design Overview

### Key Insight: Records Contain Their Collection Position

Every record's identity is captured by a `RID(collectionId, collectionPosition)`. The
`collectionPosition` maps via `CollectionPositionMapV2` to a physical
`PositionEntry(pageIndex, recordPosition, recordVersion)`. The `PositionEntry` always
references the **entry-point (start) chunk** of the record — even when the record spans
multiple pages. This means during GC we can:
- Identify the start-chunk page of a dead record via the position map.
- Read the record's `collectionPosition` and version from the entry-point chunk metadata.
- Follow the `nextPagePointer` chain to locate all continuation chunks.
- Free the space on every page in the chain.

### Three New Components

```
┌─────────────────────────────────────────────────────────┐
│                   Per-Collection State                   │
│                                                         │
│  1. Dirty Page Bit Set (durable)                        │
│     - One bit per collection data page                  │
│     - Set when keepPreviousRecordVersion() is called    │
│     - Persists across restarts                          │
│                                                         │
│  2. Dead Record Counter (in-memory, AtomicLong)         │
│     - Incremented when snapshot index entry is evicted  │
│     - Decremented when GC reclaims a record             │
│     - Reset to 0 on clean restart (rebuilt lazily)      │
│                                                         │
│  3. GC Trigger Condition                                │
│     deadRecords > threshold + scaleFactor * collSize    │
└─────────────────────────────────────────────────────────┘
```

## Detailed Design

### 1. Durable Dirty Page Bit Set

**Purpose**: Track which collection data pages contain at least one stale record's
**entry-point (start) chunk**, so the GC only scans pages that are known to be starting
points for reclaimable records. Continuation-chunk pages are **not** tracked in the bit set
— they are discovered by following the `nextPagePointer` chain from the start chunk during
GC.

**Structure**:
- A new `DurableComponent` (e.g., `CollectionDirtyPageBitSet`) backed by a dedicated file
  (extension `.dpb` or similar) within the collection's storage.
- Organized as pages of bits. Each bit corresponds to one data page in the collection's
  `.cdt` file. With 8 KB pages and 1 bit per page, a single bit-set page can track
  ~65,000 data pages.
- Operations: `set(pageIndex)`, `clear(pageIndex)`, `nextSetBit(fromIndex)`, `clear()`.
- All mutations go through `AtomicOperation` (WAL-logged) for crash consistency.
- **Concurrency control**: The dirty page bit set does not have its own component lock.
  Instead, all bit set access is serialized by the **collection's** component lock — the same
  `executeInsideComponentOperation()` that guards `keepPreviousRecordVersion()` and the GC
  page-processing loop. Because both the write path (setting bits) and the GC path (reading
  and clearing bits) run under the collection's exclusive lock, there is no race where the GC
  could inadvertently clear a bit that was just set by a concurrent write transaction.

**When to set bits**:
- In `PaginatedCollectionV2.keepPreviousRecordVersion()`, after successfully writing the
  snapshot entry, set the bit for `positionEntry.getPageIndex()`. This method already runs
  inside a component operation on the collection (`executeInsideComponentOperation()` on the
  update path, `calculateInsideComponentOperation()` on the delete path), so the bit set
  write is automatically serialized. Because the position map entry always points to the **start
  chunk**, this marks the start-chunk page as containing a record version that will eventually
  become stale. Continuation-chunk pages are not tracked — they are reachable by following
  the `nextPagePointer` chain from the start chunk.

**When to clear bits**:
- During GC, after processing a start-chunk page and confirming no more stale start chunks
  remain on it, clear the bit. The entire per-page GC loop body runs inside
  `executeInsideComponentOperation()` on the collection, so the clear is serialized against
  concurrent `keepPreviousRecordVersion()` bit sets. Note that a page may simultaneously
  serve as a start-chunk page for some records and a continuation-chunk page for others; the
  bit set only tracks the start-chunk role.

### 2. In-Memory Dead Record Counter

**Purpose**: Provide an O(1) check for whether GC is worth running, analogous to
`snapshotIndexSize` for snapshot index cleanup.

**Location**: Per-collection counter stored in `PaginatedCollectionV2` as an `AtomicLong`
field (e.g., `deadRecordCount`).

**Increment**: When a snapshot index entry is evicted, increment the dead record counter for
the corresponding collection. This is wired by adding a `List<StorageCollection> collections`
parameter to the existing static `evictStaleSnapshotEntries()`:

```
// Instance method — called from cleanupSnapshotIndex() and periodicGcTask().
void evictStaleSnapshotEntries() {
    long lwm = computeGlobalLowWaterMark();
    evictStaleSnapshotEntries(
        lwm, sharedSnapshotIndex, visibilityIndex, snapshotIndexSize,
        collections);
}

// Extended static method — the collections parameter is nullable so that
// unit tests can pass null to preserve the current behavior.
static void evictStaleSnapshotEntries(
        long lwm,
        ConcurrentSkipListMap<SnapshotKey, PositionEntry> snapshotIndex,
        ConcurrentSkipListMap<VisibilityKey, SnapshotKey> visibilityIdx,
        AtomicLong sizeCounter,
        @Nullable List<StorageCollection> collections) {
    if (lwm == Long.MAX_VALUE) { return; }

    var staleEntries = visibilityIdx.headMap(
        new VisibilityKey(lwm, Integer.MIN_VALUE, Long.MIN_VALUE), false);
    var iterator = staleEntries.entrySet().iterator();
    while (iterator.hasNext()) {
        var entry = iterator.next();
        var snapshotKey = entry.getValue();
        if (snapshotIndex.remove(snapshotKey) != null) {
            sizeCounter.decrementAndGet();
            // Increment the per-collection dead record counter.
            // collections is indexed by collection id (= componentId).
            if (collections != null) {
                int id = snapshotKey.componentId();
                if (id >= 0 && id < collections.size()) {
                    var coll = collections.get(id);
                    if (coll instanceof PaginatedCollectionV2 pc) {
                        pc.deadRecordCount.incrementAndGet();
                    }
                }
            }
        }
        iterator.remove();
    }
}
```

The `collections` list (`CopyOnWriteArrayList<StorageCollection>` in `AbstractStorage`) is
already indexed by collection id, so the lookup from `snapshotKey.componentId()` is O(1)
with no additional data structure.

**Decrement**: After the GC finishes processing a collection, subtract the number of
reclaimed records and clamp to zero:
```
deadRecordCount.updateAndGet(current -> Math.max(0, current - reclaimedCount))
```
The clamp is necessary because the counter may undercount: it starts at 0 on restart, but
GC can delete pre-restart dead records (whose snapshot entries were lost with the in-memory
index). Without the clamp, the counter would go negative and the trigger condition
(`deadRecords > threshold + …`) could never fire again, effectively disabling GC.

**Restart behavior**: The counter starts at 0 on restart. Dead records from before the
restart will not be counted until new snapshot entries for the same collection are evicted.
This means some space overhead persists until the collection sees enough write activity to
cross the GC threshold again. This trade-off is accepted to keep the restart path simple.

### 3. GC Trigger Condition and Scheduling

**Condition**:
```
deadRecords > minThreshold + scaleFactor * collectionSize
```

- `minThreshold` — minimum number of dead records before GC is even considered (avoids
  thrashing on small collections). Configurable via `GlobalConfiguration`, default TBD.
- `scaleFactor` — fraction of collection size that must be dead before GC triggers.
  Configurable, default TBD (e.g., 0.1 means 10% dead records).
- `collectionSize` — approximate number of live records in the collection, obtained via
  `PaginatedCollectionV2.approximateRecordsCount` (a volatile field already maintained by
  the insert/delete paths).

**Scheduling**:
- A periodic task runs every **1 minute** (configurable via `GlobalConfiguration` as
  `STORAGE_COLLECTION_GC_PAUSE_INTERVAL`).
- The task iterates over all collections in the storage and checks the trigger condition.
- If triggered for a collection, the GC processes dirty pages for that collection.
- Only one GC task runs at a time (use a lock similar to `snapshotCleanupLock`).
- The periodic task is scheduled on the existing `fuzzyCheckpointExecutor`.

### 4. Safe Low-Water-Mark (applies to both Snapshot Index GC and Records GC)

The existing `computeGlobalLowWaterMark()` returns `Long.MAX_VALUE` when all threads are
idle (no active transactions). Currently the snapshot index GC handles this by
short-circuiting (`if lwm == Long.MAX_VALUE` → return). This is safe but overly
conservative — when no transactions are active, **all** stale snapshot entries and dead
records are unreachable and could be reclaimed.

**Problem**: `Long.MAX_VALUE` as a sentinel is fragile. For snapshot index GC it merely
causes a missed cleanup opportunity. For records GC it would be dangerous if the guard were
ever removed or bypassed — every record would appear "below LWM".

**Solution**: Change `computeGlobalLowWaterMark()` itself to never return `Long.MAX_VALUE`.
When the minimum across all `TsMinHolder`s is `Long.MAX_VALUE` (no active transactions),
fall back to `idGen.getLastId()`:

```
procedure computeGlobalLowWaterMark():
    // Read the fallback BEFORE scanning tsMins. Because idGen is monotonic
    // and every new transaction sets tsMin >= idGen.getLastId() at its start
    // time, any transaction that begins after this read will have
    // tsMin >= fallbackLwm. This eliminates the race where a transaction
    // starts between the tsMins scan and the idGen read, ending up with
    // tsMin < idGen.getLastId() (which would have been used as LWM).
    fallbackLwm = idGen.getLastId()

    min = Long.MAX_VALUE
    for each holder in tsMins:
        if holder.tsMin < min:
            min = holder.tsMin
    if min == Long.MAX_VALUE:
        // No active transactions — use the pre-read idGen value as the
        // upper bound. Every committed record version has
        // version <= fallbackLwm, and any transaction that started after
        // the read has tsMin >= fallbackLwm, so all stale versions with
        // V_new <= fallbackLwm are unreachable.
        min = fallbackLwm
    return min
```

**Effects on existing callers**:
- **Snapshot index GC** (`evictStaleSnapshotEntries`): The `if lwm == Long.MAX_VALUE`
  early-return guard can be removed. When no transactions are active, the GC will now
  correctly evict all stale entries instead of skipping cleanup entirely. This is strictly
  better — previously, stale entries accumulated until the next transaction triggered cleanup.
- **Records GC**: Uses the same `computeGlobalLowWaterMark()` directly — no special-casing
  needed.

**Note**: The `idGen` field is already available in `AbstractStorage` (line 225). The
`computeGlobalLowWaterMark()` method (both instance and static variants) needs access to it.
The static variant `computeGlobalLowWaterMark(Set<TsMinHolder>)` gains an additional
`long currentId` parameter.

**JavaDoc update**: The current JavaDoc on `evictStaleSnapshotEntries()` states that
`Long.MAX_VALUE` means "no active transactions — nothing is evicted" and that "in practice
lwm is never MAX_VALUE during cleanup because the committing thread's tsMin is still set".
After this change (LWM never returns `MAX_VALUE`) and the Section 5 change (cleanup moves
to `resetTsMin()`, where the thread's tsMin **has** been reset), both statements become
incorrect. Update the JavaDoc to reflect the new semantics: the `lwm` parameter is always a
concrete upper bound (either an active transaction's snapshot or `idGen.getLastId()`).

### 5. Move Snapshot Index Cleanup to End-of-Transaction

Currently `cleanupSnapshotIndex()` is called only on the **write path** — at the end of
`commit()` in `AbstractStorage`, after `endTxCommit()`. This means stale snapshot entries
are only evicted when a write transaction commits. Read-only transactions never trigger
cleanup, even though:
- The eviction itself is very fast (lock-free scan of a `ConcurrentSkipListMap.headMap()`).
- Read transactions also advance the low-water-mark when they close (via `resetTsMin()`).

**Change**: Move the `cleanupSnapshotIndex()` call from the commit path to `resetTsMin()`
in `AbstractStorage`. This method is called at the end of **every** storage transaction
(both read and write), from `FrontendTransactionImpl.close()`. This ensures stale snapshot
entries are cleaned up more promptly, especially in read-heavy workloads where write commits
are infrequent.

**Concrete changes**:
1. Remove the `cleanupSnapshotIndex()` call from the commit `finally` block (after
   `endTxCommit()`).
2. Add a `cleanupSnapshotIndex()` call at the end of `resetTsMin()`, after the
   `activeTxCount` is decremented and `tsMin` is reset. The cleanup only runs when
   `activeTxCount` reaches 0 (last transaction on the thread closes), matching the existing
   `tsMin` reset guard.
3. The `try/catch` around `cleanupSnapshotIndex()` is preserved — cleanup remains
   best-effort and must not mask transaction close errors.

**Why this is safe**: `cleanupSnapshotIndex()` already uses `tryLock()` (non-blocking) and
a threshold check, so calling it more frequently adds negligible overhead — most calls
short-circuit immediately.

### 6. GC Algorithm

The periodic GC task performs two duties: it opportunistically cleans the snapshot/visibility
indexes (same work as `cleanupSnapshotIndex()`) and then reclaims dead records from
collection pages. Both operations share the same LWM computation.

```
procedure periodicGcTask():
    // Step 1: Opportunistically clean snapshot/visibility indexes.
    // Uses tryLock() — if another thread (e.g., resetTsMin) is already
    // cleaning, skip this step and proceed directly to records GC.
    cleanupSnapshotIndex()

    // Step 2: Reclaim dead records from collections that exceed the threshold.
    for each collection in storage:
        if collection.deadRecordCount.get() > threshold + scaleFactor * collectionSize:
            collectDeadRecords(collection)

procedure collectDeadRecords(collection):
    snapshotIndex = storage.sharedSnapshotIndex
    bitSet = collection.dirtyPageBitSet
    pageIndex = -1
    totalReclaimed = 0

    while true:
        // One atomic operation per dirty-bit-set page — keeps memory usage
        // bounded. Each start-chunk page is fully processed in a single pass
        // to avoid re-scanning. Splitting across transactions is safe because
        // a crash simply leaves some pages uncleaned (dirty bits still set,
        // retried on next GC cycle).
        //
        // IMPORTANT: The entire loop body runs inside
        // executeInsideComponentOperation() on the *collection*. This
        // acquires the collection's exclusive component lock for the
        // duration of the atomic operation, serializing the GC's bit set
        // reads and clears against concurrent keepPreviousRecordVersion()
        // bit sets. Without this, a race exists: GC scans a page, finds
        // no stale records remaining, and clears the bit — but between
        // the scan and the clear, a concurrent write sets the bit for a
        // newly stale record on the same page. The clear would then
        // permanently lose track of that page, causing a space leak.
        //
        // IMPORTANT: When a stale record spans multiple pages (multi-chunk),
        // the atomic operation deletes chunks from ALL pages in the chain,
        // not just the start-chunk page. This means a single atomic
        // operation may touch pages beyond the one tracked in the bit set.
        // This is necessary for correctness — partially deleted records
        // (orphaned continuation chunks) would leak space permanently.
        atomicOp = beginAtomicOperation()
        try:
            collection.executeInsideComponentOperation(atomicOp, op -> {
                pageIndex = bitSet.nextSetBit(pageIndex + 1, op)
                if pageIndex == -1:
                    return  // nothing left — caller commits and breaks

                page = loadPageForWrite(pageIndex, op)
                positionMap = collection.collectionPositionMap
                touchedContinuationPages = new LinkedHashSet<Page>()
                anyStaleRemaining = false
                deletedAny = false
                reclaimedInPage = 0

                for each recordPosition on page:
                    // Skip slots already deleted by a previous GC cycle.
                    // getRecordVersion() returns -1 for deleted slots.
                    if page.isRecordDeleted(recordPosition):
                        continue

                    // Only process start chunks (firstRecordFlag == 1).
                    // Continuation chunks on this page belong to records
                    // whose start chunks live on other pages — they will
                    // be cleaned when those other pages are processed.
                    if not page.isFirstRecordChunk(recordPosition):
                        continue

                    recordVersion = page.getRecordVersion(recordPosition)
                    collectionPos = readCollectionPositionFromRecord(
                                        page, recordPosition)

                    // Check if this record version is stale:
                    // 1. The position map points elsewhere (or is REMOVED).
                    // 2. The snapshot index no longer contains an entry for
                    //    this version (already evicted → no active tx can
                    //    observe it).
                    //
                    // Note: checking recordVersion < lwm alone is NOT
                    // sufficient. The snapshot index visibility key uses
                    // the *new* record version (V_new) as recordTs, so
                    // eviction happens when V_new < lwm — not when
                    // V_old < lwm. A record with V_old < lwm < V_new
                    // would pass a version-only check but still be
                    // referenced by the snapshot index for active readers.
                    // The explicit snapshot index lookup below is the
                    // authoritative safety check.
                    currentEntry = positionMap.getWithStatus(collectionPos,
                                                            op)

                    if currentEntry.status == FILLED
                        && currentEntry.pageIndex == pageIndex
                        && currentEntry.recordPosition == recordPosition:
                        // This is the live version — skip
                        continue

                    // Check whether the snapshot index still holds an
                    // entry for this record version. If it does, some
                    // active transaction may still need to read this
                    // version — skip it for now.
                    snapshotKey = SnapshotKey(collection.id,
                                             collectionPos, recordVersion)
                    if snapshotIndex.containsKey(snapshotKey):
                        anyStaleRemaining = true
                        continue

                    // Safe to reclaim: delete the ENTIRE record across
                    // all pages. Follow the nextPagePointer chain from
                    // the start chunk and delete every chunk on every
                    // page in the chain.
                    deleteRecordChunks(page, recordPosition, op,
                                       touchedContinuationPages)
                    reclaimedInPage++
                    deletedAny = true

                if not anyStaleRemaining:
                    bitSet.clear(pageIndex, op)

                // Compact the start-chunk page and all continuation pages
                // that had chunks deleted. deleteRecordChunks() collects
                // touched continuation pages into a set; we defragment
                // them here in a single batch after all records on the
                // start-chunk page have been processed, so each
                // continuation page is defragmented at most once even if
                // multiple deleted records shared it.
                if deletedAny:
                    page.doDefragmentation()
                    for each contPage in touchedContinuationPages:
                        contPage.doDefragmentation()
            })

            if pageIndex == -1:
                commitAtomicOperation(atomicOp)
                break

            commitAtomicOperation(atomicOp)
            totalReclaimed += reclaimedInPage
        catch:
            rollbackAtomicOperation(atomicOp)

    // Subtract reclaimed records from the counter in one shot, clamped to zero.
    // The clamp handles the post-restart case where GC deletes pre-restart dead
    // records that were never counted (counter started at 0 on restart).
    if totalReclaimed > 0:
        deadRecordCount.updateAndGet(current -> Math.max(0, current - totalReclaimed))

procedure deleteRecordChunks(startPage, recordPosition, atomicOp,
                             touchedContinuationPages):
    // Delete the start chunk and follow the nextPagePointer chain to
    // delete all continuation chunks on their respective pages.
    //
    // Read the next pointer BEFORE deleting the chunk, then delete.
    // Continuation pages are added to touchedContinuationPages so the
    // caller can defragment them in a single batched pass.
    currentPage = startPage
    currentPosition = recordPosition

    while true:
        // Read the forward pointer before deleting this chunk's data.
        nextPagePointer = currentPage.getNextPagePointer(currentPosition)
        currentPage.deleteRecord(currentPosition, preserveFreeListPointer=true)

        if nextPagePointer == -1:
            break  // Last chunk in chain — done

        // Navigate to the continuation chunk (possibly on a different page).
        nextPageIndex = getPageIndex(nextPagePointer)
        nextRecordPosition = getRecordPosition(nextPagePointer)
        currentPage = loadPageForWrite(nextPageIndex, atomicOp)
        currentPosition = nextRecordPosition
        touchedContinuationPages.add(currentPage)
```

**Transaction granularity**: Each dirty-bit-set page (start-chunk page) is processed in its
own atomic operation. This keeps memory usage bounded — atomic operations buffer WAL entries
in memory, and processing all dirty pages in a single transaction could exhaust memory on
large collections. Splitting per start-chunk page is safe: a crash between two atomic
operations simply leaves some pages uncleaned (dirty bits still set, retried on next cycle).

**Component lock scope**: The entire per-page loop body runs inside
`executeInsideComponentOperation()` on the collection. This acquires the collection's
exclusive component lock for the duration of the atomic operation, serializing the GC's
bit set reads/clears against concurrent `keepPreviousRecordVersion()` bit sets on the write
path (which also runs under the collection's component lock). The lock is held only for a
single start-chunk page at a time, so contention with the write path is bounded by per-page
processing time.

Note that a single atomic operation may touch **multiple physical pages** when a stale record
spans several pages (multi-chunk). The atomic operation deletes the start chunk on the
dirty-bit-set page and then follows the `nextPagePointer` chain to delete all continuation
chunks on their respective pages. This is necessary — partially deleted records would leave
orphaned continuation chunks that leak space permanently. The atomic operation guarantees
that either the entire record (all chunks) is deleted, or none of it is (on crash/rollback).

This ensures that even if the `resetTsMin()` path is under contention and skips snapshot
cleanup (due to `tryLock()` failure), the periodic GC task picks up the slack.

**Key invariant**: A record is safe to delete from a collection page only if:
1. It is NOT the current live version (the position map points elsewhere or is REMOVED).
2. It has no entry in the snapshot index (already evicted by snapshot index GC).

Condition 2 is the authoritative safety check. The snapshot index entry for a record with
version `V_old` is evicted when the *visibility key's* `recordTs` (= `V_new`, the version
that superseded it) falls below the low-water-mark. Because `V_old < V_new`, checking
`V_old < lwm` alone is **not** sufficient — a record with `V_old < lwm < V_new` would still
be referenced by the snapshot index for active readers. The GC therefore performs an explicit
`snapshotIndex.containsKey()` lookup (O(log n) on the `ConcurrentSkipListMap`) instead of
relying on a version comparison.

**Resolving record version and collection position from page location**: The
`recordVersion` and `collectionPosition` are stored in **different places** on the page.

The `recordVersion` is stored in the page's **pointer array**, not in the record bytes. Each
slot in the pointer array is `INDEX_ITEM_SIZE` = 12 bytes:
```
Pointer array entry: [pointer: 4 B][version: 8 B]
```
The GC reads it via `page.getRecordVersion(recordPosition)`, which returns `-1` for deleted
slots.

The `collectionPosition` is embedded in the record's **serialized bytes**, not in the
page-level record structure. The page-level layout at each record's physical offset is:
```
[entrySize: 4 B][entryIndex: 4 B][bytesLength: 4 B][record bytes ...]
```
where `entryIndex` is the page's internal pointer-array slot (used by defragmentation) —
**not** the collection position.

The `collectionPosition` lives inside `[record bytes]`, which contains the chunk payload:
```
[chunk data ...][firstRecordFlag: 1 B][nextPagePointer: 8 B]
```
For a start chunk (`firstRecordFlag == 1`), the chunk data begins with the metadata header:
```
[recordType: 1 B][contentSize: 4 B][collectionPosition: 8 B][actual content ...]
```

To extract `collectionPosition` during GC, the algorithm must:
1. Dereference the page pointer array at the record's index slot to obtain the physical
   byte offset on the page.
2. Read the record bytes starting at `physicalOffset + 12` (skipping the 3 × 4 B page-level
   header fields).
3. Check the `firstRecordFlag` byte (at `recordBytes[bytesLength - 9]`). If it is not `1`,
   this is a continuation chunk — skip it.
4. For start chunks, read `collectionPosition` as the 8-byte long at offset `5` within the
   record bytes (after `recordType` (1 B) + `contentSize` (4 B)).

Continuation chunks do not contain the `collectionPosition` — they are only reachable by
following the `nextPagePointer` chain from their start chunk. A continuation chunk that
happens to reside on a dirty-bit-set page is simply skipped during the page scan; it will
be deleted when the GC processes the start-chunk page that owns it.

## Configuration Parameters

| Parameter | Default | Description |
|---|---|---|
| `STORAGE_COLLECTION_GC_PAUSE_INTERVAL` | `60` (seconds) | Interval between GC cycles |
| `STORAGE_COLLECTION_GC_MIN_THRESHOLD` | TBD | Minimum dead records before GC triggers |
| `STORAGE_COLLECTION_GC_SCALE_FACTOR` | TBD | Fraction of collection size added to threshold |

## Implementation Steps

### Phase 1: Durable Dirty Page Bit Set
1. Create `CollectionDirtyPageBitSet` extending `DurableComponent`.
2. Implement page-based bit storage with WAL-logged set/clear operations.
3. Integrate into `PaginatedCollectionV2`: create/open/close the bit set file alongside
   the collection.
4. Set bits in `keepPreviousRecordVersion()`.

### Phase 2a: Safe Low-Water-Mark Calculation
1. Modify `computeGlobalLowWaterMark()` in `AbstractStorage` to fall back to
   `idGen.getLastId()` when all `TsMinHolder`s are idle (`Long.MAX_VALUE`). Remove the
   `if lwm == Long.MAX_VALUE` early-return guard from `evictStaleSnapshotEntries()`.

### Phase 2b: Move Snapshot Index Cleanup to `resetTsMin()`
**Prerequisite**: Phase 2a must be completed first. Without the LWM fallback, cleanup called
from `resetTsMin()` would see `Long.MAX_VALUE` whenever the closing transaction is the last
active one (its own `tsMin` has just been reset), causing the `if lwm == Long.MAX_VALUE`
guard to short-circuit and skip cleanup entirely — defeating the purpose of the move.

1. Move `cleanupSnapshotIndex()` from the commit path to `resetTsMin()` — call it when
   `activeTxCount` reaches 0, so both read and write transactions trigger cleanup.

### Phase 3: Dead Record Counter and Trigger
1. Add `AtomicLong deadRecordCount` to `PaginatedCollectionV2`.
2. Add a `@Nullable List<StorageCollection> collections` parameter to the static
   `evictStaleSnapshotEntries()`. Inside the eviction loop, look up the collection via
   `collections.get(snapshotKey.componentId())` and increment `deadRecordCount`.
3. Create a new instance `evictStaleSnapshotEntries()` that passes `this.collections` to
   the static method. Update `cleanupSnapshotIndex()` to call the instance method.
4. Add configuration parameters to `GlobalConfiguration`.
5. Implement the trigger condition check.

### Phase 4: GC Algorithm
1. Widen `CollectionPage.doDefragmentation()` from `private` to package-private so that
   `PaginatedCollectionV2` (same package) can call it on start-chunk and continuation pages
   after deleting stale records.
2. Implement the core GC logic in `PaginatedCollectionV2` (e.g.,
   `collectDeadRecords(long lwm)`).
3. Handle the page scanning: add helper methods to `CollectionPage` that do not exist yet:
   - `isFirstRecordChunk(recordPosition)`: read record bytes and check `firstRecordFlag` at
     `recordBytes[bytesLength - 9]` (1 = start chunk, 0 = continuation chunk).
   - `readCollectionPositionFromRecord(page, recordPosition)`: read 8-byte long at offset 5
     within the record bytes (after `recordType` (1 B) + `contentSize` (4 B)).
   Skip continuation chunks (`firstRecordFlag != 1`), process only start chunks to identify
   stale records.
4. Implement `deleteRecordChunks()`: follow the `nextPagePointer` chain from the start
   chunk and delete every chunk on every page in the chain within a single atomic operation.
5. Handle bit set clearing within atomic operations.
6. Implement the in-memory GC work queue fed by snapshot index eviction (optimization to
   avoid full page scans).

### Phase 5: Periodic Scheduling
1. Add a scheduled task for records GC (similar to fuzzy checkpoint scheduling in
   `DiskStorage`).
2. Wire up start/stop lifecycle with storage open/close.
3. Ensure proper cancellation on storage shutdown.

### Phase 6: Testing
1. Unit tests for `CollectionDirtyPageBitSet` (set, clear, nextSetBit, persistence across
   simulated restarts).
2. Unit tests for dead record counter increment/decrement.
3. Integration tests: create records, update/delete them, verify GC reclaims space.
4. Crash recovery tests: verify dirty page bit set survives crashes and GC works correctly
   after restart.
5. Concurrent tests: GC running alongside read/write workload.

### Phase 7: Benchmarks and Threshold Tuning
Determine default values for `minThreshold` and `scaleFactor` by measuring GC impact on
representative workloads. JMH benchmarks go in `tests/src/main/java/.../benchmarks/`.

1. **GC overhead benchmark**: Measure the per-page cost of the GC pass (page load, record
   scan, delete, WAL write) to establish a baseline cost model. Vary page fill factor and
   number of stale records per page.
2. **Write-path overhead benchmark**: Measure the added cost of setting the dirty page bit
   in `keepPreviousRecordVersion()` compared to the baseline without the bit set. This
   validates that the write-path overhead is negligible.
3. **Throughput under GC benchmark**: Run a mixed read/write workload (e.g., LDBC-like
   update + read mix) with GC enabled vs. disabled. Measure throughput and latency
   percentiles to ensure GC does not cause unacceptable pauses.
4. **Space reclamation benchmark**: Start with a collection of known size, perform a burst
   of updates/deletes to create dead records, then measure:
   - Time until GC triggers at various `minThreshold` / `scaleFactor` values.
   - Disk space reclaimed vs. total dead record space.
   - Steady-state space overhead at different trigger points.
5. **Threshold tuning**: Use results from benchmarks above to pick defaults that balance
   prompt space reclamation against GC overhead. Target: GC should not degrade write
   throughput by more than ~1-2% under sustained load.

## Implementation Tracking

Each step = 1 commit = 1 session. Code review loop required before each commit.

| # | Phase | Step | Description | Status |
|---|-------|------|-------------|--------|
| 1 | Phase 1 | Create `CollectionDirtyPageBitSet` | New `DurableComponent` backed by `.dpb` file with page-based bit storage, WAL-logged set/clear ops | DONE |
| 2 | Phase 1 | Integrate bit set into `PaginatedCollectionV2` | Create/open/close bit set file alongside collection; set bits in `keepPreviousRecordVersion()` | DONE |
| 3 | Phase 2a | Safe Low-Water-Mark | Modify `computeGlobalLowWaterMark()` to fall back to `idGen.getLastId()` when idle; remove `Long.MAX_VALUE` guard from `evictStaleSnapshotEntries()` | DONE |
| 4 | Phase 2b | Move snapshot cleanup to `resetTsMin()` | Move `cleanupSnapshotIndex()` from commit path to `resetTsMin()`, call when `activeTxCount` reaches 0 | DONE |
| 5 | Phase 3 | Dead record counter & trigger | Add `AtomicLong deadRecordCount` to `PaginatedCollectionV2`; wire increment in `evictStaleSnapshotEntries()`; add GC config params; implement trigger condition | DONE |
| 6 | Phase 4 | GC algorithm — helper methods | Widen `doDefragmentation()` visibility; add `isFirstRecordChunk()`, `readCollectionPositionFromRecord()` to `CollectionPage` | TODO |
| 7 | Phase 4 | GC algorithm — core logic | Implement `collectDeadRecords()` and `deleteRecordChunks()` in `PaginatedCollectionV2` with atomic-op-per-page granularity | TODO |
| 8 | Phase 5 | Periodic scheduling | Add scheduled GC task on `fuzzyCheckpointExecutor`; wire start/stop lifecycle; cancellation on shutdown | TODO |
| 9 | Phase 6 | Testing — unit tests | Tests for `CollectionDirtyPageBitSet`, dead record counter, GC trigger condition | TODO |
| 10 | Phase 6 | Testing — integration & crash recovery | Integration tests (create/update/delete → GC reclaims); crash recovery tests; concurrent read/write + GC tests | TODO |
| 11 | Phase 7 | Benchmarks & threshold tuning | JMH benchmarks for GC overhead, write-path overhead, throughput under GC, space reclamation; tune defaults | TODO |

