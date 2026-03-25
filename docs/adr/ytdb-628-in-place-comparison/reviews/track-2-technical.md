# Track 2 Technical Review

## Iteration 1

### Finding T1 [should-fix]
**Location**: Track 2 description + D5 — `isPropertyEqualTo()` return type
**Issue**: Plan D5 still documents `Optional<Boolean>` but Track 1 implemented `InPlaceResult` enum. Track 2 dispatch must use `InPlaceResult.TRUE/FALSE/FALLBACK`, not `Optional` API.
**Proposed fix**: Address in step decomposition — specify InPlaceResult dispatch pattern.

### Finding T2 [should-fix]
**Location**: Track 2 — extracting EntityImpl from Result
**Issue**: `currentRecord.isEntity()` returns true even for unloaded RIDs. `asEntity()` may trigger disk loading. Safer to check `instanceof EntityImpl` directly on the underlying identifiable.
**Proposed fix**: Use pattern matching: check if the result's identifiable is an EntityImpl without triggering loading.

### Finding T3 [should-fix]
**Location**: Track 2 — FALLBACK path
**Issue**: If in-place returns FALLBACK, `right.execute()` would be called twice (once for optimization, once for standard path).
**Proposed fix**: Compute `rightVal` once before optimization attempt; reuse on FALLBACK.

### Finding T4 [suggestion]
**Location**: Track 2 — imports in generated file
**Issue**: SQLBinaryCondition.java is generated; imports must preserve JavaCC header.
**Proposed fix**: Note in step: preserve `OriginalChecksum` comment, run spotless:apply.

### Finding T5 [suggestion]
**Location**: Track 2 — evaluate(Identifiable) overload collation
**Issue**: Existing `evaluate(Identifiable, ctx)` never applies collation. The Identifiable overload should intentionally skip the collation guard.
**Proposed fix**: Document intentional omission; don't add collation guard to Identifiable path.

### Finding T6 [suggestion]
**Location**: Track 2 — operator dispatch
**Issue**: Range operators implement `isRangeOperator()`. Can simplify dispatch.
**Proposed fix**: Use `operator.isRangeOperator()` instead of four separate instanceof checks.

## Summary
- 0 blockers, 3 should-fix, 3 suggestions
- **PASS** — no blockers, should-fix items addressed in step decomposition
