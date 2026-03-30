# Track 4 Risk Review

## Findings

### Finding R1 [should-fix]
**Location**: Seed search algorithm — unbounded capacity growth
**Issue**: Recursive capacity doubling could overflow. No max capacity limit.
**Proposed fix**: Convert to iterative loop. Cap max capacity at 1024. Throw if exceeded.
**Decision**: Adopt. Iterative loop with max capacity 1024.

### Finding R2 [should-fix]
**Location**: Empty slot sentinel collision
**Issue**: 0x00/0x0000 sentinel + 0x01 substitution is fragile.
**Proposed fix**: Use 0xFF/0xFFFF sentinel.
**Decision**: Adopted (same as T3).

### Finding R3 [blocker]
**Location**: V1's `getFieldType()` is private — V2 can't access it
**Issue**: V2 may need type resolution during serialization.
**Proposed fix**: Extract to shared utility or V2 resolves types from EntityEntry.
**Decision**: Not a real blocker. V2 resolves types from EntityEntry directly
(the entry already carries type info). `getFieldType()` was only needed by
deleted `EntitySerializerDelta`.

### Finding R4 [should-fix]
**Location**: Schema-aware property name hashing
**Issue**: Plan unclear on whether hash uses name string or encoded integer.
**Proposed fix**: Hash the property name string always. Resolve from schema for
schema-aware properties.
**Decision**: Adopt. Hash property name strings. Schema-aware → resolve name from
GlobalProperty at serialization time.

### Finding R6 [should-fix]
**Location**: Hash table-specific partial deserialization coverage
**Issue**: Track 2 tests may not fully exercise hash table lookup paths.
**Proposed fix**: Add V2-specific tests with 5+ properties (forces hash mode).
**Decision**: Adopt. Each step tests both linear and hash table modes.

### Finding R7 [should-fix]
**Location**: Dual-path (linear vs hash) test coverage
**Issue**: Two code paths double test surface area.
**Proposed fix**: Both paths tested in each step.
**Decision**: Adopt. Each test class covers both modes explicitly.

### Finding R8 [should-fix]
**Location**: Version dispatch backward compatibility
**Issue**: Changing CURRENT_RECORD_VERSION to 1 must not break V1 reads.
**Proposed fix**: Array holds both V1 (0) and V2 (1). Add backward compat tests.
**Decision**: Adopt. Part of registration step.

### Finding R10 [should-fix]
**Location**: Embedded entities and 64 KB offset limit
**Issue**: Large embedded entities could exceed 2-byte offset range.
**Proposed fix**: Throw if KV region exceeds 64 KB.
**Decision**: Adopted (same as T2).

### Finding R11 [blocker]
**Location**: Missing mixed-version backward compatibility tests
**Issue**: Need unit tests that V1 records still deserialize after V2 registration.
**Proposed fix**: Add in registration step.
**Decision**: Adopt. Part of Step 4.

### Finding R5 [suggestion]
**Location**: Fibonacci hash log2 capacity bounds
**Issue**: log2cap=0 causes shift by 32 (no-op in Java).
**Proposed fix**: Assert log2cap >= 1.
**Decision**: Adopt. Linear mode handles count ≤ 2, so log2cap is always >= 2.

### Finding R9 [suggestion]
**Location**: BinaryComparatorV1 stub approach
**Issue**: V2 needs a comparator before Track 5.
**Proposed fix**: Return BinaryComparatorV0 as stub.
**Decision**: Adopted (same as T6).

### Finding R12 [suggestion]
**Location**: Fibonacci hash signed/unsigned arithmetic
**Issue**: Java int multiplication wraps around for negative values.
**Proposed fix**: Clarify in comments.
**Decision**: Note. Java int overflow is well-defined (wraps). Fibonacci hashing
works correctly with signed ints — the bit pattern is what matters.
