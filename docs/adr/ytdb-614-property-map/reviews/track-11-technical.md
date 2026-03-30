# Track 11 — Technical Review

## Findings

### Finding T1 [suggestion]
**Location**: Track 11 serialization / `serializePropertyEntry()` (line 382)
**Issue**: Hash prefix must be written before name-encoding. Current method signature needs a hash parameter or the hash must be written by the caller before calling the method.
**Proposed fix**: Write the 4-byte hash in the serialization loop before calling `serializePropertyEntry()`, keeping the method itself unchanged for name+type+value encoding.
**Decision**: Implementation detail — covered by step decomposition.

### Finding T2 [suggestion]
**Location**: Track 11 deserialization / `deserializePartialLinear()` (line 582)
**Issue**: Partial deserialization must read 4-byte hash first and skip entries without String construction on mismatch.
**Proposed fix**: Add hash-first rejection logic before `readNameAndType()`.
**Decision**: Implementation detail — this is the core of Track 11's approach.

### Finding T3 [suggestion]
**Location**: Track 11 / partial deserialization architecture
**Issue**: Removal of hash table means rewriting from O(1) slot lookup to O(n) linear scan with hash fast-rejection.
**Proposed fix**: Merge hash table and linear deserialization paths into a single linear-with-hash path.
**Decision**: This is exactly what Track 11 does. Not a finding.

### Finding T4 [suggestion]
**Location**: `deserializeFieldHashTable()` (line 728) and `getFieldNamesHashTable()` (line 822)
**Issue**: Both methods rely on hash table slot lookup and must be rewritten for linear scan.
**Proposed fix**: Merge with linear mode counterparts; skip 4-byte hash per entry.
**Decision**: Implementation detail — covered by step decomposition.

### Finding T5 [not applicable]
**Location**: Format backward compatibility
**Issue**: Reviewer asked about V2 format versioning and migration.
**Resolution**: V2 was never released to production — this is a feature branch (`ytdb-614-property-map`). No V2 records exist in any deployed database. The version byte (1) stays the same; the format simply changes. No migration needed.

### Finding T6 [suggestion]
**Location**: Hash table constants and methods
**Issue**: Dead code after removing hash table: `EMPTY_HASH8`, `EMPTY_OFFSET`, `FIBONACCI_CONSTANT`, `SLOT_SIZE`, `MAX_LOG2_CAPACITY`, `computeHash8()`, `computeLog2Capacity()`, `fibonacciSlotIndex()`, `buildHashTable()`, `HashTableResult`.
**Proposed fix**: Remove all as part of implementation.
**Decision**: Covered by implementation. `MAX_KV_REGION_SIZE` stays (still limits entry region).

### Finding T7 [not applicable]
**Location**: Hash seed storage
**Issue**: Plan's format diagram omits the seed. Reviewer asks whether seed is stored.
**Resolution**: Seed is not stored in the new format. All hashes use seed=0. The 4-byte hash seed was only needed for hash table reconstruction; with linear scan, there's no table to reconstruct. Hash is computed once during serialization and stored as a 4-byte prefix per entry.

### Finding T8 [not applicable]
**Location**: Test infrastructure parameterization
**Issue**: Tests parameterized across serializer versions might fail with format change.
**Resolution**: Tests serialize fresh data each run — they don't read pre-serialized V2 fixtures. The parameterized tests in `EntitySchemalessBinarySerializationTest` will work as-is because they serialize → deserialize within the same test run.

### Finding T9 [suggestion]
**Location**: Benchmark verification
**Issue**: Track should include benchmark step.
**Decision**: Already in the plan's constraints. Benchmark is run on CCX33 as a separate activity after implementation, not as a code step.

## Summary
No blockers. All findings are either implementation details covered by step decomposition or not applicable to this feature branch context.
