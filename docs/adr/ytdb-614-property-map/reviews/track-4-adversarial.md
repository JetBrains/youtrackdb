# Track 4 Adversarial Review

## Findings

### Finding A1 [blocker]
**Target**: Decision D2 — hash formula inconsistency
**Challenge**: Design doc uses both `hash & (capacity - 1)` and Fibonacci hashing
`(hash * 0x9E3779B9) >>> (32 - log2cap)` interchangeably. They produce different
distributions and different seed search results.
**Evidence**: Design.md lines 10, 166, 280 alternate between formulas.
**Proposed fix**: Use Fibonacci hashing consistently for ALL index computation.
**Decision**: Adopt. Fibonacci hashing everywhere. The `& (capacity-1)` references
in the design doc are shorthand simplifications — implementation uses Fibonacci.

### Finding A2 [blocker]
**Target**: Invariant — empty slot sentinel collision
**Challenge**: Sentinel 0x00/0x0000 with 0x01 substitution is fragile. A property
at offset 0 with hash8=0x00 is stored as 0x01, creating ambiguity.
**Evidence**: Design.md lines 223-227.
**Proposed fix**: Use 0xFF/0xFFFF sentinel — never collides with real data since
offsets are in range [0, 65534].
**Decision**: Adopted. 0xFF/0xFFFF sentinel.

### Finding A3 [should-fix]
**Target**: Decision D1 — seed search termination
**Challenge**: 10,000 attempts is large for write path. Unbounded capacity doubling.
**Proposed fix**: Iterative loop, max capacity 1024, throw if exceeded.
**Decision**: Adopted.

### Finding A4 [should-fix]
**Target**: Assumption — property name resolution for hash table
**Challenge**: V1 never serializes property names for schema-aware properties. V2 must
resolve names from GlobalProperty for hashing, adding complexity.
**Proposed fix**: Extract name resolution helper.
**Decision**: V2 resolves names from EntityEntry/GlobalProperty. Straightforward
since entity.getRawEntries() already provides names as map keys.

### Finding A5 [should-fix]
**Target**: Decision D3 — hash prefix necessity
**Challenge**: 1-byte hash prefix never saves a key comparison (key is always compared).
Removing it saves 25% of slot array size.
**Proposed fix**: Remove hash8, use 2-byte slots.
**Decision**: Keep hash8. Cost is minimal (1 byte/slot). Provides corruption detection
value and fast-path rejection for non-perfect scenarios (corrupted data). Decision D3
stands.

### Finding A6 [suggestion]
**Target**: Decision D4 — linear mode complexity
**Challenge**: Two code paths add complexity for marginal benefit.
**Proposed fix**: Always use hash table.
**Decision**: Keep linear mode for ≤2 properties. Space savings are significant
(44% overhead avoided). The linear path is trivially simple.

### Finding A7 [should-fix]
**Target**: Assumption — serialization order
**Challenge**: V2 might serialize properties in hash table slot order instead of
entity iteration order.
**Proposed fix**: Document and test: KV entries in entity iteration order.
**Decision**: Adopt. V2 iterates entity entries in their natural order, writes KV
entries sequentially, and backpatches slot offsets.

### Finding A9 [blocker]
**Target**: Invariant — hash table corruption detection
**Challenge**: Corrupted seed silently produces wrong slot lookups. V1 detects
corruption via varint underflow; V2 follows garbage offsets silently.
**Proposed fix**: Add offset bounds checking; check offset < KV region size.
**Decision**: Adopt bounds checking. Storage layer already has page-level checksums,
so V2 just needs basic sanity checks (offset in range, property count reasonable).
No additional checksums/sentinels needed.

### Finding A8 [suggestion]
**Target**: Non-goal — V1/V2 coexistence long-term
**Challenge**: No automatic migration means heterogeneous performance.
**Decision**: Acknowledged. Already documented as non-goal. Records upgrade on-write.

### Finding A10 [suggestion]
**Target**: Track scope
**Challenge**: 5-7 steps is at upper bound.
**Decision**: Decomposed to 4 steps. Manageable.
