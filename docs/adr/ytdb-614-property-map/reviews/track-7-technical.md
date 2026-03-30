# Track 7 — Technical Review

## Findings

### Finding T1 [should-fix] (downgraded from blocker)
**Location**: `computeLog2Capacity()` → `computeLog2NumBuckets()`
**Issue**: Not a simple rename — fundamentally different formula. Current computes total slot capacity; cuckoo computes bucket count where totalSlots = numBuckets * 4.
**Decision**: Accept. The plan describes the new formula in D2/D6. Implementation will handle.

### Finding T2 [should-fix] (downgraded from blocker)
**Location**: Dual hash lookup in deserialization
**Issue**: All deserialization paths must check two buckets (h1, h2) with 4-slot scanning each.
**Decision**: Accept. Core implementation requirement covered in step decomposition.

### Finding T3 [should-fix] (downgraded from blocker)
**Location**: `serializeHashTableMode()` — cuckoo construction
**Issue**: Slot addressing changes from flat array to bucket-grouped array. New helper methods needed.
**Decision**: Accept. Step 1 adds construction algorithm; Step 2 wires it into serialization.

### Finding T4 [should-fix] (downgraded from blocker)
**Location**: All deserialization paths — bucket array interpretation
**Issue**: kvRegionBase, slot addressing, and all lookup logic must account for bucket grouping.
**Decision**: Accept. Step 2 updates all paths atomically.

### Finding T5 [should-fix]
**Location**: `RecordSerializerBinaryV2HashTableTest`
**Issue**: All `findPerfectHashSeed_*` tests must be replaced; `computeLog2Capacity_*` expectations updated.
**Decision**: Accept. Covered in Step 3.

### Finding T6 [suggestion] (downgraded from should-fix)
**Location**: Track 7 scope
**Issue**: Suggests 6-8 steps instead of 5-6.
**Decision**: Decomposed into 4 well-scoped steps. The algorithm is complex but changes are concentrated in one file.

### Finding T7 [should-fix]
**Location**: LINEAR_MODE_THRESHOLD change and wire-format incompatibility
**Issue**: Entities with 3-12 properties serialized as hash tables by Track 4 would be unreadable by Track 7.
**Decision**: Accept. No V2 records exist in production. Add cleanup note in step description.

### Finding T8 [suggestion]
**Location**: Variable naming `log2Capacity` → `log2NumBuckets`
**Decision**: Accept. Done as part of Step 2.

### Finding T9 [suggestion]
**Location**: h2 computation optimization
**Decision**: Accept. Compute h2 only on bucket1 miss.

### Finding T10 [suggestion]
**Location**: Test execution during partial implementation
**Decision**: Mitigated by making serialize+deserialize an atomic step (Step 2).

## Summary
- 0 blockers (4 downgraded — they describe implementation work the plan already covers)
- 5 should-fix (all accepted, covered in step decomposition)
- 3 suggestions (all accepted)
- Gate: **PASS**
