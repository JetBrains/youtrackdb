# Track 1 Technical Review — Index BTree Tombstone GC

## Result: PASS (0 blockers, 0 should-fix, 5 suggestions)

### Finding T1 [suggestion]
**Certificate**: EC12 (LWM assert on fresh database)
**Location**: `BTree.filterAndRebuildBucket()` — `assert lwm > 0`
**Issue**: Could theoretically fail on fresh DB where `idGen.getLastId()` returns 0. In practice safe since `put()` runs within an atomic operation.
**Proposed fix**: Change to `assert lwm >= 0` or add comment explaining guarantee.

### Finding T2 [suggestion]
**Certificate**: EC4, EC3 (demotion encoding)
**Location**: `BTree.demoteMarkerRawBytes()`
**Issue**: No defensive assertion that decoded `realPosition` is non-negative.
**Proposed fix**: Add `assert realPosition >= 0` after computing it.

### Finding T3 [suggestion]
**Certificate**: EC6 (insertion index re-derivation)
**Location**: `BTree.update()` line 748-750
**Issue**: Re-derivation assumes key not in bucket; defensive assert would catch bugs.
**Proposed fix**: Add `assert insertionIndex >= 0` after re-derivation.

### Finding T5 [suggestion]
**Certificate**: EC11 (performance)
**Location**: `BTree.filterAndRebuildBucket()` lines 2793-2830
**Issue**: Key deserialization is unconditional for all entries. For plain RecordId entries (common case), key is not needed — only value type check matters.
**Proposed fix**: Check `value instanceof TombstoneRID || value instanceof SnapshotMarkerRID` before deserializing key. Skip key deserialization for plain RecordId.

### Finding T7 [suggestion]
**Certificate**: EC8 (double page read)
**Location**: `BTree.filterAndRebuildBucket()`
**Issue**: Each survivor entry causes two page reads (value/key deserialization + getRawEntry). Could read raw entry once and decode RID from it.
**Proposed fix**: Read `getRawEntry` first, decode RID type from last 10 bytes, only deserialize key when needed.

## Evidence Summary
- All component references verified (StorageComponent, AbstractStorage, CellBTreeSingleValueBucketV3)
- TombstoneRID/SnapshotMarkerRID encoding confirmed correct
- Filter-rebuild-retry pattern verified with correct atomicity
- WAL correctness confirmed (no new record types needed)
- Concurrency verified (exclusive lock held, concurrent reads safe)
- Tree size accounting verified
- Partition invariant enforced by assertion
- gcAttempted flag prevents infinite loops
- Null-key tree resolution ($null suffix) verified
