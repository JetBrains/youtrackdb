# Track 1 Risk Review — Index BTree Tombstone GC

## Result: PASS (0 blockers, 0 should-fix, 4 suggestions)

### Finding R1 [suggestion]
**Certificate**: EC-1 (HashMap thread-safety)
**Location**: `AbstractStorage.hasActiveIndexSnapshotEntries()` line 6423
**Issue**: `indexEngineNameMap` (plain `HashMap`) accessed without `stateLock`. Pre-existing pattern shared by other methods.
**Proposed fix**: No action for Track 1. Pre-existing pattern.

### Finding R2 [suggestion]
**Certificate**: EC-5 (insertion index after GC)
**Location**: `BTree.update()` lines 748-750
**Issue**: After GC rebuild, `find()` re-derives insertion index. If uniqueness invariant violated, produces negative index. Extremely low likelihood.
**Proposed fix**: Add defensive assertion `assert insertionIndex >= 0`.

### Finding R3 [suggestion]
**Certificate**: EC-3 (LWM assertion in tests)
**Location**: `BTree.filterAndRebuildBucket()` line 2785
**Issue**: `assert lwm > 0` may fail in unit tests with improperly initialized storage.
**Proposed fix**: Ensure Track 2 test setup initializes atomic operation lifecycle properly.

### Finding R4 [suggestion]
**Certificate**: EC-4 (per-entry deserialization cost)
**Location**: `BTree.filterAndRebuildBucket()` lines 2793-2830
**Issue**: 2-3 page reads per entry. Bounded by bucket size, consistent with edge GC pattern.
**Proposed fix**: No action. Optimize only if profiling identifies bottleneck.

## Evidence Summary
- All modifications within single AtomicOperation — rollback safe
- WAL correctness maintained without new record types
- Concurrency safe: exclusive lock held, concurrent reads to ConcurrentSkipListMap safe
- indexEngineNameMap access is pre-existing pattern (not introduced by this track)
- Bucket rebuild after demotion-only verified safe (entry count/order unchanged)
- Snapshot query range key construction verified consistent
- Blast radius: limited to the single overflowing leaf page within an atomic operation
