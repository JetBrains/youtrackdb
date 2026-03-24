# Track 8 Technical Review

## Findings

### Finding T1 [should-fix]
**Location**: Track 8 description, "Update existing Track 6 integration tests for the new format"
**Issue**: Track 7 already updated all serialization/deserialization code atomically. Existing Track 6 integration tests already run against the cuckoo-based format — there is nothing to "update."
**Proposed fix**: Reword — existing tests already validate cuckoo. Track 8 adds new cuckoo-specific edge cases and benchmarks, not updates.
**Decision**: Accept — existing tests already cover cuckoo format.

### Finding T2 [should-fix]
**Location**: Track 8 description, "Displacement chain scenarios"
**Issue**: `RecordSerializerBinaryV2HashTableTest` already covers adversarial property names and comprehensive sweeps (Track 7). Track 8 should focus on DB-lifecycle integration, not re-testing hash table construction.
**Proposed fix**: Focus integration tests on end-to-end scenarios (persist-to-disk, reopen, query) at tier boundaries.
**Decision**: Accept — focus on DB lifecycle at tier boundaries.

### Finding T3 [should-fix]
**Location**: Track 8 description, "JMH benchmark"
**Issue**: No serialization-focused JMH benchmark exists. `jmh-ldbc` is the wrong module. Benchmark should go in `core/src/test/` alongside existing benchmarks.
**Proposed fix**: Create JMH benchmark in core module, comparing V1 vs V2 at various property counts.
**Decision**: Accept — place in core/src/test/.

### Finding T4 [suggestion]
**Location**: Track 8, "Bucket boundary: entities with exactly 13 properties"
**Issue**: Already covered by existing tests.
**Decision**: Accept — acknowledge existing coverage, add DB-lifecycle variant only if gap found.

### Finding T5 [suggestion]
**Location**: Track 8, "Backward compatibility: V1 records still readable"
**Issue**: Already thoroughly tested in RecordSerializerBinaryVersionDispatchTest (Track 6).
**Decision**: Accept — remove from scope.

### Finding T6 [suggestion]
**Location**: Track 8, "Binary comparator: BinaryComparatorV0 with cuckoo-serialized fields"
**Issue**: Already tested in Track 6 with cuckoo-serialized fields.
**Decision**: Accept — add cuckoo-mode variant (13+ properties) as incremental improvement.

### Finding T7 [suggestion]
**Location**: Track 8, "~4-5 steps"
**Issue**: Unique contributions reduce to DB-lifecycle tier tests + JMH benchmark. 4-5 steps inflated.
**Proposed fix**: Scope to 2-3 steps.
**Decision**: Accept — reduce scope to match actual remaining work.

## Summary
0 blockers, 3 should-fix (all accepted), 4 suggestions (all accepted). Scope reduced from 4-5 steps to 2-3.
