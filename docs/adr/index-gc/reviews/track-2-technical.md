# Track 2 Technical Review

## Summary

Track 2 has an existing untracked test file (`BTreeTombstoneGCTest.java`)
that covers most planned scenarios. The test infrastructure (LWM pinning,
stub engine registration, entry counting) is sound.

## Findings

### Finding T1 [should-fix]
**Location**: `BTreeTombstoneGCTest.java:afterMethod`
**Issue**: `afterMethod` does not clean `sharedIndexesSnapshot` /
`indexesSnapshotVisibilityIndex` after tests that call `addSnapshotPair`.
Leftover entries could cause test pollution. The edge GC test correctly
clears its equivalent maps.
**Proposed fix**: Add reflection-based cleanup of `sharedIndexesSnapshot`
and `indexesSnapshotVisibilityIndex` in `afterMethod`.

### Finding T2 [suggestion]
**Location**: Missing test
**Issue**: No test for LWM boundary (`version == lwm`). The implementation
uses strict `<`, so boundary entries should be preserved.
**Proposed fix**: Add `testTombstonesAtExactLwmArePreserved`.

### Finding T3 [suggestion]
**Location**: Missing test
**Issue**: No explicit "no ghost resurrection" test. The invariant is
enforced by `assert` in production code but not tested at API level.
**Proposed fix**: Add test that inserts key, deletes (tombstone), triggers
GC, then verifies `get()` returns null.

### Finding T4 [suggestion]
**Location**: `testTombstonesBelowLwmAreRemovedDuringPut` assertion
**Issue**: `isLessThan(tombstonesBefore)` is intentionally weak (GC only
runs on overflowing buckets). A comment explaining this would help.
**Proposed fix**: Add explanatory comment.
