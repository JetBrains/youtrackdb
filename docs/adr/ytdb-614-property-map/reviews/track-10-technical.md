# Track 10 Technical Review

## Finding T1 [should-fix]
**Location**: Track 10, Fix #1 — `RecordSerializerBinaryV2.java` `serializePropertyEntry()`
**Issue**: Reserve-and-backpatch approach for eliminating per-property BytesContainer
is overly complex — shifting value bytes backward by up to 4 bytes per property risks
O(n * valueSize) total shifting. Simpler approach: reuse a single temp BytesContainer
per `serializeEntity` call, resetting `offset = 0` between properties.
**Proposed fix**: Declare one temp BytesContainer in `serializeLinearMode`/
`serializeHashTableMode`, pass it into each `serializePropertyEntry` call, reset
between uses. Reduces 50 allocations to 1 per nesting level.

**Decision**: Accept — reuse temp BytesContainer instead of backpatch.

## Finding T2 [should-fix]
**Location**: Track 10, Fix #1 — interaction with EMBEDDED recursive serialization
**Issue**: Reusable temp buffer works for EMBEDDED because `serializeValue` writes
the full embedded entity recursively into whatever buffer is passed. Each nesting
level's `serializeEntity` creates its own temp buffer.
**Proposed fix**: Document that each `serializeEntity` call creates one temp
BytesContainer. Nested calls for EMBEDDED get their own. This reduces allocations
from N_props to 1 per nesting level.

**Decision**: Accept — each nesting level gets its own temp buffer.

## Finding T3 [suggestion]
**Location**: Track 10, Fix #2 — hash table construction arrays in `buildHashTable`
**Issue**: Thread-local approach for construction arrays is premature — total ~2KB in
O(1) allocations per entity vs 50 * ~80B = ~4KB in 100 allocations for source #1.
Merging `slotHash8`/`slotPropertyIndex` into the slot-array construction loop is
simpler and eliminates 2 arrays.
**Proposed fix**: Merge intermediate arrays into construction loop. Skip thread-locals.

**Decision**: Accept — merge arrays, skip thread-locals.

## Finding T4 [should-fix]
**Location**: Track 10, Fix #3 — `serializeHashTableMode` lines 308-320
**Issue**: Schema-less property names are UTF-8 encoded twice: once for `nameBytes`
array (hash computation) and once in `serializePropertyEntry` via `writeString`.
**Proposed fix**: Pass `nameBytes` into `serializePropertyEntry` to avoid re-encoding.
Or restructure to interleave hash construction with KV serialization.

**Decision**: Accept — note double encoding, pass nameBytes where feasible.

## Finding T5 [blocker → downgraded to should-fix]
**Location**: Track 10 — CCX33 requirement for all profiling
**Issue**: No existing CI infrastructure to run `RecordSerializerBenchmark` on CCX33.
Allocation rates (`alloc/op`) are hardware-independent.
**Proposed fix**: Local `-prof gc` for iterative development. CCX33 for final
throughput comparison only.

**Decision**: Accept — downgrade from blocker. Local profiling for iteration.

## Finding T6 [suggestion]
**Location**: Track 10, Fix #4 — benchmark BytesContainer
**Issue**: Per-invocation BytesContainer in benchmark mirrors production and affects
V1/V2 equally. Not a V2-specific problem.
**Proposed fix**: Drop source #4 from track scope.

**Decision**: Accept — drop from scope.

## Finding T7 [suggestion]
**Location**: Track 10 — `HashTableResult` object allocation
**Issue**: Minor allocation (~32 bytes) eliminable by inlining `buildHashTable`.
Reduces testability.
**Proposed fix**: Consider inlining, but trade off against testability.

**Decision**: Defer — minor gain, testability cost.

## Finding T8 [suggestion]
**Location**: Track 10 — schema-aware properties in hash table mode
**Issue**: Schema-aware props only have source #3 (getBytes for hashing) as
per-property allocation — no double encoding since KV entry uses varint ID.
**Proposed fix**: Informational, no action needed.

**Decision**: Note — no action.
