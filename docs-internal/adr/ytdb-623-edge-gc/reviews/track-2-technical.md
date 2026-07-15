# Track 2 Technical Review

## Summary

**Recommendation: SKIP Track 2 entirely.**

Track 1 delivered both the implementation and comprehensive tests in a single
commit, covering all 7 scenarios listed in Track 2's description plus 6
additional edge cases. Executing Track 2 would produce duplicate test coverage.

## Findings

### Finding T1 [skip]
**Location**: Track 2 — entire track vs. existing `SharedLinkBagBTreeTombstoneGCTest.java`
**Issue**: Track 2 is entirely redundant with tests already delivered by Track 1.

| Track 2 planned scenario | Existing test |
|---|---|
| Tombstones below LWM with no snapshots removed | `testTombstonesBelowLwmWithNoSnapshotsAreRemovedDuringPut` + `testTombstoneWithTsJustBelowLwmIsRemoved` |
| Tombstones below LWM WITH snapshots preserved | `testTombstonesWithSnapshotEntriesArePreserved` |
| Tombstones above LWM preserved | `testTombstoneWithTsEqualToLwmIsPreserved` (boundary test with pinned LWM) |
| Tree size consistency after splits | `testTreeSizeConsistencyAfterGC` |
| `findVisibleEntry()` correct after GC (no ghost resurrection) | `testNoGhostResurrectionAfterGC` |
| Mixed tombstone/live scenarios | `testMixOfRemovableAndNonRemovableTombstones` |
| All-tombstone bucket cleared | `testAllTombstoneBucketIsFullyClearedByGC` |

Additional tests not even planned in Track 2:
- `testBTreeOrderingPreservedAfterGC`
- `testGCOnlyRunsOncePerInsert`
- `testLiveEntriesAreNeverRemovedByGC`
- `testGCWithNoTombstonesDoesNotCorruptBucket`
- `testGCDuringCrossTxRemoveTombstoneInsertion`
- `testTombstoneWithTsJustBelowLwmIsRemoved` (LWM boundary pair)

**Proposed fix**: Skip Track 2. Mark `[~]` in plan file.

### Finding T2 [suggestion]
**Location**: Track 2 Constraints — framing as "split GC" tests
**Issue**: Track 2's description frames these as "tests for GC during split" but
the actual implementation uses filter-rebuild-retry **before** splitting. GC fires
during bucket overflow, which may prevent a split entirely. Existing tests already
account for this correctly. Moot if Track 2 is skipped.
**Proposed fix**: No action needed.

### Finding T3 [suggestion]
**Location**: Track 2 Constraints — LWM manipulation via TsMinHolder
**Issue**: The existing test class already implements LWM pinning via reflection
helpers (`pinLwm()`/`unpinLwm()`) and uses them in boundary tests. This further
confirms Track 2 would produce no new testing methodology.
**Proposed fix**: No action needed.
