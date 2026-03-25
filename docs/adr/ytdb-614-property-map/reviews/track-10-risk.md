# Track 10 Risk Review

## Finding R1 [should-fix]
**Location**: Track 10, Fix #1 — backpatch strategy
**Issue**: Reserve-and-backpatch needs concrete specification. O(valueSize) copy per
property for the shift approach. Alternative: reuse single temp BytesContainer.
**Proposed fix**: Specify reusable temp buffer approach (see T1).

**Decision**: Accept — addressed by T1 decision (reuse temp BytesContainer).

## Finding R2 [should-fix]
**Location**: Track 10, Fix #2 — thread-local for hash table arrays
**Issue**: Thread-locals leak memory with pooled threads. Arrays are O(1) per entity.
**Proposed fix**: Quantify with `-prof gc` before implementing. Skip if minor.

**Decision**: Accept — skip thread-locals entirely (see T3).

## Finding R3 [suggestion]
**Location**: Track 10, Fix #4 — BytesContainer.reset() for benchmark
**Issue**: Benchmark-only optimization makes benchmark less representative.
**Proposed fix**: Don't add reset(). Drop source #4.

**Decision**: Accept — drop from scope (see T6).

## Finding R4 [should-fix]
**Location**: Track 10 — binary format identity
**Issue**: Optimizations change how bytes are laid out during construction. Risk of
accidental format change. Existing round-trip tests verify semantic equivalence but
not byte-level identity.
**Proposed fix**: Add byte-level identity test: serialize same entity pre/post
optimization, assert identical bytes.

**Decision**: Accept — add as part of first optimization step.

## Finding R5 [should-fix]
**Location**: Track 10 — CCX33 dependency for all steps
**Issue**: Blocking dependency on cloud infra for every profile-fix cycle. Allocation
rates are hardware-independent.
**Proposed fix**: Local `-prof gc` for iteration, CCX33 for final validation only.

**Decision**: Accept (see T5).

## Finding R6 [suggestion]
**Location**: Track 10, Fix #1 — V1 delegate interaction
**Issue**: V1 `serializeValue` must only write forward from `bytes.offset`. Need to
verify no V1 handler reads earlier positions.
**Proposed fix**: Trace V1 type handlers during implementation.

**Decision**: Accept — verify during implementation.

## Finding R7 [suggestion]
**Location**: Track 10, Fix #3 — per-property getBytes(UTF_8)
**Issue**: Small allocations (5-30 bytes) compared to Fix #1 (64-byte array + header).
**Proposed fix**: Deprioritize. Tackle only if profiling shows high impact.

**Decision**: Accept — deprioritize.
