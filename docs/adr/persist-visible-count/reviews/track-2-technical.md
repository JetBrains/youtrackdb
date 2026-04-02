# Track 2 Technical Review

## Summary

Track 2 is architecturally sound. The plan correctly identifies the commit flow
structure, integration points, and changes needed across `BTreeIndexEngine`,
engine implementations, and `AbstractStorage`. The placement of
`persistIndexCountDeltas` between `commitIndexes()` and the catch clause is
correct and ensures WAL atomicity. Track 1's completed APIs are properly
leveraged.

## Findings

### Finding T1 [suggestion]
**Location**: Track 2, `persistCountDelta` method signature on `BTreeIndexEngine` interface
**Issue**: The plan does not specify whether `persistCountDelta` declares `throws IOException`. BTree's `addToApproximateEntriesCount` is invoked via `executeInsideComponentOperation` (in `DurableComponent`), which catches all exceptions and wraps them in `RuntimeException` (`BaseException`). Therefore `persistCountDelta` does NOT need `throws IOException`.
**Proposed fix**: Add a note in the track description: "`persistCountDelta` does not declare `throws IOException` because BTree component operations wrap I/O exceptions into `RuntimeException`."

### Finding T2 [should-fix]
**Location**: Track 2, `clear()` update combined with `persistIndexCountDeltas` ordering
**Issue**: `clear()` must persist `setApproximateEntriesCount(0)` to the entry point page, and then `persistIndexCountDeltas` adds the delta from re-inserted entries on top. The ordering is guaranteed (`clear()` runs inside `commitIndexes()`, `persistIndexCountDeltas` runs after). However, the plan does not explicitly call out this ordering dependency. `IndexCountDeltaHolder` is NOT reset on `clear()` — the delta reflects only puts/removes that happen during `commitIndexes`, which is correct when `clear()` has already reset the persisted base to 0.
**Proposed fix**: Add an explicit note to the `clear()` step documenting this ordering dependency.

### Finding T3 [should-fix]
**Location**: Track 2, `buildInitialHistogram()` update for multi-value engine
**Issue**: The plan says "call `setApproximateEntriesCount(atomicOp, count)` after recalibrating from scan" but does not distinguish between single-value (one tree) and multi-value (two trees: `svTree` and `nullTree`). The multi-value engine must make two separate `setApproximateEntriesCount` calls.
**Proposed fix**: Expand the `buildInitialHistogram()` step to explicitly state both engine variants.

### Finding T4 [should-fix]
**Location**: Track 2, `persistIndexCountDeltas` in `AbstractStorage` — defensive checks
**Issue**: The existing `applyIndexCountDeltas` has defensive checks: it skips engines with out-of-bounds IDs and non-`BTreeIndexEngine` instances. The new `persistIndexCountDeltas` should mirror these.
**Proposed fix**: Add a note that `persistIndexCountDeltas` should mirror the same defensive bounds/instanceof checks as `applyIndexCountDeltas`.

### Finding T5 [suggestion]
**Location**: Track 2, integration tests
**Issue**: The test plan does not explicitly mention testing the multi-value engine's independent tree counts across restart.
**Proposed fix**: Add a test case for multi-value engine that verifies both trees' persisted counts survive restart.

## Overall: No blockers. 3 should-fix, 2 suggestions.
