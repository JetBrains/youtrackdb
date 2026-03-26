# Track 11 — Risk Review

## Findings

### Finding R1 [not applicable]
**Location**: Format breaking change / migration
**Issue**: New format breaks wire compatibility with current V2.
**Resolution**: V2 was never released to production. Feature branch only. No migration needed.

### Finding R2 [suggestion]
**Location**: Hash table test files (`RecordSerializerBinaryV2HashTableTest.java`)
**Issue**: ~510 lines of hash-table-specific tests become obsolete.
**Proposed fix**: Delete hash-table-specific tests; add hash-accelerated linear scan tests.
**Decision**: Covered by test update step.

### Finding R3 [should-fix]
**Location**: Partial deserialization collision handling
**Issue**: 4-byte hash collision (probability ~1/2^32) must be guarded by string equality check. Plan specifies this (line 691) but explicit test coverage is needed.
**Proposed fix**: Add a test that verifies correct lookup when two properties have different names. The collision guard (string equality after hash match) is already specified in the plan. Natural property names won't collide with MurmurHash3, but the code path must be tested.
**Decision**: Accept. String equality check is mandatory after hash match. Test this path with multiple properties in a single entity.

### Finding R4 [suggestion]
**Location**: Seed hard-coded to 0
**Issue**: No per-record seed; all hashes deterministic from property name alone.
**Decision**: Accept. Seed=0 is the correct design choice. The hash is stored as a literal 4-byte prefix — no seed needed for reconstruction. Simpler and sufficient.

### Finding R5 [suggestion]
**Location**: Full deserialization behavior change
**Issue**: Full deserialization must skip 4-byte hash per entry.
**Decision**: Implementation detail. The skip happens at the start of each entry read loop iteration.

### Finding R6 [should-fix]
**Location**: Scope of format changes (linear vs hash table mode)
**Issue**: Plan says "single linear format for all property counts" but doesn't explicitly state LINEAR_MODE_THRESHOLD removal.
**Proposed fix**: Confirm: Track 11 removes the linear/hash-table mode distinction entirely. All entities use the same format with 4-byte hash prefix per property. `LINEAR_MODE_THRESHOLD` is removed. `serializeLinearMode` and `serializeHashTableMode` are merged into a single serialization path.
**Decision**: Accept. This is the plan's intent — "Modify `RecordSerializerBinaryV2` to use a single linear format for all property counts."

### Finding R7 [suggestion]
**Location**: `getFieldNames()` paths
**Issue**: Two separate paths must be merged.
**Decision**: Implementation detail — merge `getFieldNamesLinear` and `getFieldNamesHashTable` into one method that skips 4-byte hash per entry.

### Finding R8 [suggestion]
**Location**: Format-specific test assertions
**Issue**: Byte-level assertions in tests need updating.
**Decision**: Covered by test update step.

### Finding R9 [suggestion]
**Location**: Hash write order and name-bytes reuse
**Issue**: Hash must be written before name encoding.
**Decision**: Implementation detail. Track 10's pre-encoded `nameBytes` optimization is reused: compute hash from nameBytes, write 4-byte hash, then write name encoding.

### Finding R10 [suggestion]
**Location**: Benchmark verification
**Issue**: Benchmark should be run on CCX33 after implementation.
**Decision**: Already specified in plan constraints. Separate activity.

### Finding R11 [should-fix]
**Location**: Buffer offset management across all deserialization paths
**Issue**: Every entry reading point must consistently handle the 4-byte hash prefix. Missing a skip in any path causes offset misalignment and data corruption.
**Proposed fix**: Use a consistent pattern in all entry-reading loops: read 4 bytes as int hash, then proceed with existing name+type+value reading. For full deserialization and getFieldNames, simply skip 4 bytes. For partial/field deserialization, read as int and compare.
**Decision**: Accept. Critical implementation concern. Step decomposition handles serialization and all deserialization paths separately to reduce risk of missing a code path.

### Finding R12 [suggestion]
**Location**: Test coverage for collision handling
**Issue**: Need tests verifying correct lookup with multiple properties.
**Decision**: Existing round-trip tests already verify this (entities with multiple properties where partial deserialization retrieves specific ones). Hash-table-specific collision tests are replaced by the natural correctness tests.

## Summary
No blockers. Two should-fix findings (R3: collision guard testing, R6: confirm LINEAR_MODE_THRESHOLD removal, R11: consistent buffer offset handling) are addressed in step decomposition.
