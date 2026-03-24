# Track 8 Risk Review

## Findings

### Finding R1 [should-fix]
**Location**: Track 8 scope — all planned integration test steps
**Issue**: ~80% of planned integration tests already completed by Tracks 6-7: all 3 tiers, displacement chains, bucket boundary, 50/100-property stress, backward compat, DB lifecycle, binary comparator.
**Proposed fix**: Reduce scope to JMH benchmark + targeted gap-filling tests.
**Decision**: Accept — reduce integration test scope significantly.

### Finding R2 [should-fix]
**Location**: Track 8 JMH benchmark step
**Issue**: No serialization-focused JMH benchmark exists. Creating one requires deciding placement, JMH dependency setup, and defining scenarios. More infrastructure work than plan acknowledges.
**Proposed fix**: Place in core/src/test/ alongside existing benchmarks. Define 3 scenarios: 5-property linear, 20-property cuckoo, 50-property cuckoo. Compare V1 vs V2.
**Decision**: Accept — bounded scope with 3 scenarios.

### Finding R3 [suggestion]
**Location**: Track 8, "confirm write path improvement over the perfect hash baseline"
**Issue**: Perfect hash no longer exists. Meaningful comparison is V1 vs V2-cuckoo.
**Proposed fix**: Reframe: verify V2 write path is acceptable vs V1, V2 read path is faster for partial deserialization.
**Decision**: Accept — compare V1 vs V2-cuckoo.

### Finding R4 [suggestion]
**Location**: Track 8, disk storage testing
**Issue**: Existing lifecycle tests use DatabaseType.MEMORY, bypassing CI disk storage flag.
**Proposed fix**: Update existing lifecycle test to use DISK when youtrackdb.test.env=ci.
**Decision**: Accept — one-line fix as part of lifecycle test step.

### Finding R5 [suggestion]
**Location**: Track 8, overall scope
**Issue**: Given comprehensive existing coverage, Track 8's only high-value deliverable is the JMH benchmark.
**Proposed fix**: Reduce to 2 steps: gap-filling integration tests + JMH benchmark.
**Decision**: Accept — reduce scope.

## Critical Path Assessment
Track 8 does NOT modify production code — test-only. Zero blast radius.

## Summary
0 blockers, 2 should-fix (all accepted), 3 suggestions (all accepted). Scope reduced significantly.
