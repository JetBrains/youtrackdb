# Track 1 Adversarial Review

## Findings

### Finding A1 [should-fix]
**Target**: Decision D1 — single-long key alternative
**Challenge**: Composed fileId is `(storageId << 32) | fileId` — both ints. Could pack into single long.
**Evidence**: `AbstractWriteCache.composeFileId()` shows int composition.
**Decision**: REJECTED — `fileId` is `long` in the API contract. Relying on internal composition details is fragile.

### Finding A2 [suggestion]
**Target**: Decision D2 — write ordering in put()
**Challenge**: Value must be written before key fields to prevent readers seeing matching keys with null values.
**Decision**: ACCEPTED — document write ordering explicitly in implementation.

### Finding A3 [should-fix]
**Target**: Decision D1 — Koloboke alternative not considered
**Challenge**: Koloboke provides primitive concurrent maps.
**Decision**: REJECTED — Koloboke doesn't provide StampedLock optimistic reads or the compute() contract needed.

### Finding A4 [suggestion]
**Target**: Scope — removeByFileId could be split
**Challenge**: Novel algorithm mixed with core fork increases blast radius.
**Decision**: ACCEPTED as ordering — removeByFileId implemented after all base operations pass tests.

### Finding A5 [suggestion]
**Target**: Scope — values() iteration support
**Challenge**: `clear()` and `assertConsistency()` use `data.values()`.
**Decision**: ACCEPTED — add `forEachValue(Consumer<V>)` (merged with T5).

### Finding A6 [blocker]
**Target**: Invariant — "No allocation" overstated
**Challenge**: `StampedLock.readLock()` fallback may allocate under contention.
**Decision**: ACCEPTED as should-fix (not blocker) — narrow invariant to "optimistic read fast path is allocation-free."

### Finding A7 [should-fix]
**Target**: Invariant — tombstone accumulation after removeByFileId
**Challenge**: Interleaved tombstones may trigger unnecessary resize.
**Decision**: ACCEPTED (merged with T3/R4 — compaction rehash after bulk removal).

### Finding A8 [should-fix]
**Target**: Invariant — removal order change
**Challenge**: Changes from page-index order to segment order.
**Decision**: ACCEPTED as suggestion — document behavioral difference. Not a real concern since clearFile caller doesn't depend on ordering.

### Finding A9 [withdrawn]
**Target**: Assumption — compute() key parameter pattern
**Challenge**: API already handles this correctly.

### Finding A10 [should-fix]
**Target**: Assumption — section-level locking coarser than CHM per-bin locking
**Challenge**: Disk I/O under lock blocks entire segment.
**Decision**: ACCEPTED (merged with R7 — configurable segment count).

### Finding A11 [suggestion]
**Target**: `Section extends StampedLock` vs composition
**Challenge**: Codebase style favors composition.
**Decision**: ACCEPTED as suggestion — use composition.

### Finding A12 [should-fix]
**Target**: Simplification — profiling evidence for PageKey allocation
**Challenge**: Need to verify PageKey is not scalar-replaced by JIT.
**Decision**: Noted — plan motivation is grounded in observed GC pressure. No Track 1 action.

### Finding A13 [suggestion]
**Target**: `hashForFrequencySketch` belongs in Track 2
**Challenge**: Only used by WTinyLFUPolicy.
**Decision**: ACCEPTED — moved to Track 2.
