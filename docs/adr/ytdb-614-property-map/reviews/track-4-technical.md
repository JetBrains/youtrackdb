# Track 4 Technical Review

## Findings

### Finding T1 [blocker]
**Location**: RecordSerializerBinary.init() — version dispatch
**Issue**: `init()` hardcodes `new EntitySerializer[1]`. Must resize to 2 and register
V2 at index 1. Plan says set `CURRENT_RECORD_VERSION = 1` for new records.
**Proposed fix**: Resize array to 2, register both V1 (index 0) and V2 (index 1).
**Decision**: Address in Step 4 (registration).

### Finding T3 [blocker]
**Location**: Binary format — empty slot sentinel
**Issue**: 0x00/0x0000 sentinel with 0x01 substitution creates ambiguity.
**Proposed fix**: Use 0xFF/0xFFFF as empty sentinel instead.
**Decision**: Adopt 0xFF/0xFFFF sentinel. Eliminates ambiguity entirely.

### Finding T6 [blocker]
**Location**: BinaryComparatorV1 dependency — Track 4 needs comparator but Track 5 builds it
**Issue**: `EntitySerializer.getComparator()` is required but `BinaryComparatorV1` is Track 5.
**Proposed fix**: V2 returns `BinaryComparatorV0` as stub. Track 5 replaces with V1.
**Decision**: Adopt. BinaryComparatorV0 works correctly (just slower — linear scan).

### Finding T9 [blocker]
**Location**: RecordSerializerBinary.fromStream() — ArrayIndexOutOfBoundsException
**Issue**: Version byte 1 + array size 1 = AIOOBE.
**Proposed fix**: Part of T1 fix — resize array before any V2 data is created.
**Decision**: Address in Step 4 (registration).

### Finding T2 [should-fix]
**Location**: Slot format — 2-byte offset limit
**Issue**: 2-byte offset limits KV region to 64 KB. No overflow handling specified.
**Proposed fix**: Throw clear exception if KV region exceeds 64 KB during serialization.
**Decision**: Adopt. 64 KB is sufficient for entity properties; fail loudly if exceeded.

### Finding T5 [should-fix]
**Location**: Schema-aware property encoding formula
**Issue**: `(propertyId+1)*-1` formula ambiguous in plan description.
**Proposed fix**: Use exactly V1's encoding: `(id + 1) * -1` for write, `(len * -1) - 1` for read.
**Decision**: Adopt. V2 uses identical schema-aware encoding as V1.

### Finding T8 [should-fix]
**Location**: Embedded entity serialization
**Issue**: V2 must ensure embedded entities use V2 format recursively.
**Proposed fix**: V2 overrides `serializeWithClassName()` to use V2 format.
**Decision**: Adopt. V2's serialize methods handle embedded recursion naturally.

### Finding T4 [suggestion]
**Location**: Fibonacci hash utility
**Issue**: No shared utility method for Fibonacci hashing.
**Proposed fix**: Create `fibonacciIndex(int hash, int log2Capacity)` as static method.
**Decision**: Adopt. Package-private static method in V2, accessible to BinaryComparatorV1 in Track 5.

### Finding T7 [suggestion]
**Location**: Full deserialization strategy
**Issue**: Iterate hash table slots vs scan KV region linearly.
**Proposed fix**: Use linear KV scan for full deserialization (simpler, O(n) anyway).
**Decision**: Adopt. Hash table iteration only needed for partial deserialization.
