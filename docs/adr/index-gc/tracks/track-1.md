# Track 1: Core GC implementation in BTree and AbstractStorage

## Progress
- [x] Review + decomposition
- [x] Step implementation (1/1 complete)
- [x] Track-level code review (skipped — single-step track, fully reviewed in Phase B)

## Base commit
`412c2d9b62`

## Reviews completed
- [x] Technical (0 blockers, 5 suggestions — defensive asserts and perf optimizations)
- [x] Risk (0 blockers, 4 suggestions — defensive asserts, pre-existing patterns)

## Steps

- [x] Step: Add filterAndRebuildBucket(), demoteMarkerRawBytes(), put() loop GC integration, and hasActiveIndexSnapshotEntries()
  - [x] Context: unavailable
  > **What was done:** Implemented tombstone GC during leaf bucket overflow
  > in `BTree.put()`. Added `filterAndRebuildBucket()` — iterates bucket
  > entries, removes TombstoneRID below LWM, demotes SnapshotMarkerRID
  > below LWM when no active snapshot entries exist, and rebuilds the
  > bucket via `shrink(0)` + `addAll(survivors)`. Added
  > `demoteMarkerRawBytes()` static helper for position encoding
  > reversal. Integrated into the `update()` while loop with
  > `gcAttempted` flag (at most once per insert). Added
  > `hasActiveIndexSnapshotEntries()` to `AbstractStorage` — queries
  > `ConcurrentSkipListMap` via `subMap()` range query with
  > `stateLock.readLock()` protection. Also added
  > `getIndexSnapshotByEngineName()` and
  > `getNullIndexSnapshotByEngineName()` helpers for Track 2 tests.
  > Applied all Phase A review suggestions: T1 (LWM >= 0), T2
  > (demote position assert), T3/R2 (insertion index assert), T5
  > (skip key deserialization for plain RecordId).
  >
  > **What was discovered:** `indexEngineNameMap` is a plain `HashMap`
  > with all existing accesses guarded by `stateLock`. The new
  > `hasActiveIndexSnapshotEntries()` call from BTree runs under the
  > BTree component lock, not `stateLock`. Fixed by adding
  > `stateLock.readLock()` around the map lookup (review fix BC1).
  >
  > **Key files:** `BTree.java` (modified), `AbstractStorage.java`
  > (modified)
