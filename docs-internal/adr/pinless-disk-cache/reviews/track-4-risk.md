# Track 4: Optimistic Read Infrastructure — Risk Review

## Review Summary

**Track**: Track 4 — Optimistic Read Infrastructure
**Reviewer**: Risk review sub-agent
**Iteration**: 1
**Outcome**: PASS (no blockers after analysis)

## Findings

### Finding R1 [should-fix]
**Location**: Plan Phase 2e — DurablePage allocation guard completeness
**Issue**: The plan's allocation audit table lists 8+ allocation sites in DurablePage
and subclasses, plus serializer deserialization paths. The `guardSize()` approach in
DurablePage covers `getBinaryValue()` and `getIntArray()` directly, and
`getObjectSizeInDirectMemory()` catches serializer-computed sizes. However, subclass
`getSize()`/`getRecordsCount()` methods that drive loop bounds and array allocations
need tighter structural bounds.
**Risk**: Medium likelihood that an unguarded allocation site causes OOM during
speculative reads under eviction pressure.
**Proposed fix**: During the DurablePage step, add `guardSize()` calls to all
size-reading methods in DurablePage (`getBinaryValue`, `getIntArray`,
`getObjectSizeInDirectMemory`). Subclass-specific guards (e.g.,
`CellBTreeSingleValueBucketV3.getSize()`) are deferred to Track 5 when those
subclasses get PageView constructors. The DurablePage-level guards are sufficient
for Track 4 since no production code uses `executeOptimisticStorageRead()` yet.

### Finding R2 [suggestion]
**Location**: Plan Phase 2 — unit test coverage for fallback path
**Issue**: The testing strategy defers eviction-pressure and concurrent tests to
Tracks 5+. Phase 2 unit tests cover OptimisticReadScope, PageView, and guardSize
in isolation but don't exercise the stamp invalidation → fallback flow end-to-end.
**Risk**: Low — Track 4 adds infrastructure without wiring it into production code.
Bugs in the fallback path would be caught in Track 5.
**Proposed fix**: Add a unit test in the OptimisticReadScope test class that manually
invalidates a stamp (via PageFrame.acquireExclusiveLock + release from another thread)
and verifies that `validateOrThrow()` throws `OptimisticReadFailedException`. This
catches validation logic bugs early without requiring full integration tests.

### Finding R3 [suggestion]
**Location**: Plan Phase 2f — `executeOptimisticStorageRead` fallback + reentrancy
**Issue**: The fallback path calls `acquireSharedLock()` on the component. If the
current thread already holds the exclusive component lock (writer calling a read
method internally), this could deadlock with StampedLock.
**Risk**: Already mitigated — `SharedResourceAbstract.acquireSharedLock()` checks
`exclusiveOwner == Thread.currentThread()` and returns immediately. The fallback
path is safe for writer-initiated reads.
**Proposed fix**: No code change needed. Add a comment in `executeOptimisticStorageRead()`
documenting that reentrancy is handled by `SharedResourceAbstract`.

### Finding R4 [suggestion]
**Location**: Plan Phase 2c — frequency recording after optimistic read
**Issue**: Only successful optimistic reads record access frequency. Under high
eviction pressure, frequent fallbacks cause undercount in the W-TinyLFU admission
policy. The plan assumes <1% fallback rate under normal conditions.
**Proposed fix**: Record frequency on both successful optimistic reads AND successful
fallback reads. The fallback path already calls `loadForRead()` which calls
`afterRead()`, so frequency is recorded on the fallback path automatically via the
existing CAS-pinned flow. Only purely optimistic successes need explicit
`recordOptimisticAccess()`. This is already correct in the plan's design.

## Decisions

| ID | Decision | Rationale |
|---|---|---|
| R1 | Fix during implementation | Guard all DurablePage allocation methods |
| R2 | Fix during implementation | Add stamp invalidation unit test |
| R3 | Document only | Already handled by SharedResourceAbstract |
| R4 | Accept as-is | Fallback path records via existing loadForRead |

## Risk Summary

Track 4 is **low risk** overall. It adds infrastructure without wiring into production
code paths. The new classes (OptimisticReadScope, PageView, OptimisticReadFailedException)
are self-contained. The modifications to existing classes (AtomicOperation,
ReadCache, LockFreeReadCache, DurablePage, DurableComponent) add new methods/constructors
without changing existing behavior. The blast radius is contained to the new API surface.

Real risk materializes in Track 5 when the infrastructure is wired into production
read paths. Track 4's role is to ensure the infrastructure is correct and well-tested
so Track 5 can focus on migration without surprises.
