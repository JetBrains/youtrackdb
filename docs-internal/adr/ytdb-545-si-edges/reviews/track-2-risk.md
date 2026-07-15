# Track 2 Risk Review

## Findings

### Finding R1 [should-fix]
**Location**: Track 2 — EdgeSnapshotKey and EdgeVisibilityKey record types
**Issue**: Multi-field edge identity cannot be packed into existing `SnapshotKey` structure
(only 3 fields vs. needed 5). Parallel key types are the right approach (per D2) but mean
more code duplication in cleanup logic.
**Proposed fix**: Model tests after `SnapshotKeyTest` — comprehensive ordering and range scan
tests for both new key types. Implement parallel cleanup following exact same pattern.

### Finding R2 [blocker]
**Location**: Track 2 — AbstractStorage initialization and cleanup wiring
**Issue**: New shared maps must be initialized inline (like existing maps at lines 280-292).
Cleanup must use a separate `evictStaleEdgeSnapshotEntries()` static method (safer than
making existing method generic). Must be called from both `cleanupSnapshotIndex()` and
`periodicRecordsGc()` paths.
**Proposed fix**: Initialize maps as final fields. Create parallel static eviction method.
Wire into `cleanupSnapshotIndex()` after collection cleanup call. `periodicRecordsGc()`
already calls `cleanupSnapshotIndex()` so edge cleanup is automatically included.

### Finding R3 [should-fix]
**Location**: Track 2 — buffer flush in AtomicOperationBinaryTracking
**Issue**: Edge snapshot buffer flush must happen in same commit path as collection flush
(line 576), before page changes applied to cache. Approximate size counting is acceptable
(same trade-off as collection buffers).
**Proposed fix**: Add `flushEdgeSnapshotBuffers()` call immediately after `flushSnapshotBuffers()`
at line 576. Mirror exact pattern.

### Finding R4 [should-fix]
**Location**: Track 2 — constructor parameter growth (7 → 10 params)
**Issue**: Adding 3 edge snapshot parameters to `AtomicOperationBinaryTracking` constructor
increases risk of argument order mistakes.
**Proposed fix**: Accept parameter growth. Add `@Nonnull` annotations. The constructor is
called from only 2 sites (production + test), so risk is manageable.

### Finding R5 [suggestion]
**Location**: Track 2 — memory overhead of LinkBagValue in snapshot index
**Issue**: ~53 bytes per snapshot entry (21 bytes value + 32 bytes visibility key). Under
sustained heavy load with long-running readers, could accumulate. Threshold is configurable.
**Proposed fix**: Acceptable trade-off per D4. Add memory overhead comment near new fields.
Test that entries accumulate and are cleaned up correctly.

### Finding R6 [suggestion]
**Location**: Track 2 — MergingDescendingIterator for edge snapshots
**Issue**: Current iterator is not generic. Edge snapshots need different types.
**Proposed fix**: Type system enforces correctness if a separate edge version is created or
the existing class is made generic. Both approaches work.

### Finding R7 [suggestion]
**Location**: Track 2 — AtomicOperationsManager constructor call site
**Issue**: Single call site must be updated to pass 3 additional parameters.
**Proposed fix**: Update call site and corresponding test factory. Low risk — compile error
if parameters are missing.

## Summary

- **Blockers**: R2 (AbstractStorage initialization and cleanup wiring)
- **Should-fix**: R1, R3, R4
- **Suggestions**: R5, R6, R7
