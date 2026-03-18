# Track 1 Technical Review: Remove legacy null checks in DurableComponent

## Finding T1 [should-fix]
**Location**: Track 1 description: "All public entry points already have `@Nonnull AtomicOperation`"
**Issue**: This claim is inaccurate. In `CellBTreeSingleValue` interface, only `get()` and `acquireAtomicExclusiveLock()` have `@Nonnull` on `AtomicOperation`. The remaining 13 methods lack `@Nonnull`. Similarly, `PaginatedCollectionV2`, `SharedLinkBagBTree`, `FreeSpaceMap`, and `CollectionPositionMapV2` public methods lack `@Nonnull` on their `atomicOperation` parameters.

While no caller currently passes null (all paths go through `executeInsideAtomicOperation` or `calculateInsideAtomicOperation`), the annotation gap means static analysis cannot enforce this invariant.

**Proposed fix**: As part of Track 1, add `@Nonnull` annotations to all `AtomicOperation` parameters on the public interfaces and DurableComponent subclass public methods.

**Decision**: ACCEPTED — add `@Nonnull` annotations as part of the null-check removal step.

## Finding T2 [should-fix]
**Location**: Track 1 approach: "Replace null branches with assertions"
**Issue**: Java assertions are disabled by default in production. If a null somehow reaches these methods in production, the `assert` would be silently skipped, resulting in an NPE with an unhelpful stack trace. A safer approach would be `Objects.requireNonNull()`.

However, the existing pattern in `DurableComponent` write methods (addPage, addFile, deleteFile, etc.) already uses bare `assert`. Using `assert` is at least consistent.

**Proposed fix**: Use `assert atomicOperation != null` for consistency with the existing write-method pattern. The production-assertions caveat is accepted since no null values flow through these paths.

**Decision**: ACCEPTED as-is — use `assert` for consistency with the existing DurableComponent pattern.

## Finding T3 [suggestion]
**Location**: `releasePageFromRead()` null branch
**Issue**: The null branch and non-null branch ultimately do similar work. Removal is low-risk. No external callers exist.
**Proposed fix**: None needed — confirming removal is safe.

## Finding T4 [suggestion]
**Location**: Component map accuracy
**Issue**: All 5 methods correctly identified. No missed methods. Component map is accurate.
**Proposed fix**: None needed.

## Summary

Track 1 is **feasible and safe to execute**. No callers pass null `atomicOperation` to any of the 5 target methods. Two should-fix findings accepted:
1. Add `@Nonnull` annotations broadly (T1 — accepted, included in scope)
2. Use `assert` for consistency with existing pattern (T2 — accepted as-is)

**PASS** — no blockers.
