# Track 1 Risk Review

## Findings

### Finding R1 [blocker]
**Location**: Track 1, `compute()` null-return semantics
**Issue**: `silentLoadForRead()` depends on compute returning null for absent keys without inserting. Track 1 description does not explicitly call this out as a constraint.
**Proposed fix**: Add explicit constraint and tests for null-return cases.
**Decision**: ACCEPTED (merged with T2).

### Finding R2 [should-fix]
**Location**: Track 1, `compute()` key parameters for absent keys
**Issue**: Remapping function must receive caller-supplied fileId/pageIndex, not stale array contents. Related to R8.
**Proposed fix**: Add unit test verifying correct key values in remapping function.
**Decision**: ACCEPTED (merged with R8).

### Finding R3 [should-fix]
**Location**: Track 1, concurrent rehash + get stress test
**Issue**: BookKeeper had BOOKKEEPER-4317 race in rehash + get. Two-field key adds another dimension.
**Proposed fix**: Include basic rehash correctness test in Track 1 unit tests. Full concurrent stress in Track 3.
**Decision**: PARTIALLY ACCEPTED — basic rehash test in Track 1, full stress in Track 3.

### Finding R4 [should-fix]
**Location**: Track 1, `removeByFileId` tombstone cleanup
**Issue**: Non-contiguous tombstones may not be cleaned by backward sweep.
**Proposed fix**: Same-capacity rehash compaction after bulk removal if tombstones exceed threshold.
**Decision**: ACCEPTED (merged with T3).

### Finding R5 [suggestion]
**Location**: Track 1, hash function change impact on eviction
**Issue**: Murmur hash will alter frequency sketch collision patterns.
**Proposed fix**: Validate with `AsyncReadCacheTestIT` in Track 2.
**Decision**: Noted for Track 2.

### Finding R6 [suggestion]
**Location**: Track 1, Section encapsulation
**Issue**: `Section extends StampedLock` exposes all lock methods.
**Proposed fix**: Make Section a private static final inner class.
**Decision**: ACCEPTED — use composition instead.

### Finding R7 [should-fix]
**Location**: Track 1, segment count vs CHM concurrency level
**Issue**: 16 segments is coarser than CHM's per-bin locking. Disk I/O under lock blocks entire segment. Potential latency regression.
**Proposed fix**: Make segment count configurable in constructor. Default 16 for the map; LockFreeReadCache passes appropriate value in Track 2.
**Decision**: ACCEPTED.

### Finding R8 [blocker]
**Location**: Track 1, `compute()` for absent key — key values passed to function
**Issue**: For absent keys, array slots contain stale/zero values. Implementation must pass caller-supplied fileId/pageIndex to the remapping function, not read from arrays. Wrong values = silent data corruption.
**Proposed fix**: Always pass caller-supplied values. Add unit test for absent-key compute.
**Decision**: ACCEPTED.

### Finding R9 [suggestion]
**Location**: Track 1, probe-through-tombstone test
**Issue**: `get()` must correctly skip tombstones during probing.
**Proposed fix**: Add specific test for probe-through-tombstone scenario.
**Decision**: ACCEPTED — include in unit tests.
