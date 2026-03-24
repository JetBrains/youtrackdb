# Track 7 — Risk Review

## Findings

### Finding R1 [should-fix]
**Location**: Cuckoo construction algorithm specification
**Issue**: Algorithm underspecified — displacement chain termination, insertion order, greedy policy need clarification.
**Decision**: Accept. Step 1 will implement with clear pseudocode in comments. Displacement max=500, seed retry max=10, capacity doubling on all-seeds-fail.

### Finding R2 [should-fix]
**Location**: LINEAR_MODE_THRESHOLD = 12
**Issue**: Threshold based on hand-calculation, not empirical validation.
**Decision**: Accept as reasonable default. Track 8 will validate with JMH benchmarks and adjust if needed.

### Finding R3 [should-fix] (downgraded from blocker)
**Location**: Deserialization comments assuming perfect hashing
**Issue**: Current code has "Perfect hash guarantees no collisions" comments that are invalid for cuckoo.
**Decision**: Downgraded. This is exactly what Track 7 changes — comments and logic will be rewritten in Step 2.

### Finding R4 [should-fix]
**Location**: Wire format incompatibility — same version byte
**Issue**: No way to distinguish cuckoo V2 from perfect hash V2 at wire level.
**Decision**: Accept as-is. No perfect hash V2 records exist in production (feature branch). Incrementing version byte would waste a version number for a format that was never shipped.

### Finding R5 [suggestion]
**Location**: Hash8 distribution and performance assumptions
**Decision**: Accept. MurmurHash3 is a well-tested hash function with excellent distribution. Track 8 benchmarks will validate empirically.

### Finding R6 [should-fix]
**Location**: Test coverage for cuckoo-specific scenarios
**Issue**: Need displacement chain tests, seed retry tests, dual-bucket lookup tests.
**Decision**: Accept. Covered in Steps 3-4.

### Finding R7 [suggestion]
**Location**: 64 KB offset limit documentation
**Decision**: Accept. Add comment in implementation.

### Finding R8 [should-fix]
**Location**: Embedded entity recursion — mixed-tier scenarios
**Issue**: Small parent with large embedded child uses different tiers.
**Decision**: Accept. Existing recursive serialization handles this naturally. Add mixed-tier test in Step 4.

### Finding R9 [should-fix]
**Location**: Power-of-two bucket rounding overhead
**Issue**: 30 properties → 47% load vs target 85%.
**Decision**: Accept. Absolute space cost is small (192 bytes max). Document in comments.

### Finding R10 [suggestion]
**Location**: Corruption detection strategy
**Decision**: Accept. Add documentation comments.

## Summary
- 0 blockers (1 downgraded — describes work the track already plans)
- 7 should-fix (all accepted)
- 3 suggestions (all accepted)
- Gate: **PASS**
