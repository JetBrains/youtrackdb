# Track 2 Risk Review

## Summary

Risk level: Low-to-moderate. The track is well-scoped and follows established
patterns. The main risk is the behavioral change from non-fatal to fatal failure
for count persistence. All necessary infrastructure exists from Track 1.

## Findings

### Finding R1 [should-fix]
**Location**: Track 2, `persistIndexCountDeltas()` in `AbstractStorage.java`, engine resolution by ID
**Issue**: `persistIndexCountDeltas` runs inside the try block where failure triggers transaction rollback. If an engine was concurrently dropped, calling on a null engine would throw NPE, rolling back a transaction that would otherwise succeed. Likelihood: low (engine drop requires exclusive lock). Impact: high (unnecessary rollback).
**Proposed fix**: Mirror the null/bounds guard from `applyIndexCountDeltas` in `persistIndexCountDeltas`.

### Finding R2 [should-fix]
**Location**: Track 2, `persistIndexCountDeltas()` — fatal vs. non-fatal failure
**Issue**: The plan states failure rolls back the transaction (unlike the post-commit `applyIndexCountDeltas` which is non-fatal). This is a behavioral change. The non-negative assert in `BTree.addToApproximateEntriesCount` could trigger if accumulated delta is incorrect, killing transactions. Likelihood: low under normal conditions. Impact: high (rollback of valid mutations).
**Proposed fix**: Make an explicit design decision during implementation: either (a) soft check (log + clamp to 0), or (b) wrap `persistIndexCountDeltas` in try-catch that degrades gracefully.

### Finding R3 [suggestion]
**Location**: Track 2, `buildInitialHistogram()` — ordering of `setApproximateEntriesCount` vs `mgr.buildHistogram()`
**Issue**: `setApproximateEntriesCount` should be called before `mgr.buildHistogram()` so count persistence is not conditional on histogram success.
**Proposed fix**: Note in step description that `setApproximateEntriesCount` should be called immediately after the scan.

### Finding R4 [suggestion]
**Location**: Track 2, multi-value engine `load()` rewrite
**Issue**: The approximation window is widened slightly vs scan-on-load. Acceptable given "approximate" semantics and `buildInitialHistogram` as self-healing.
**Proposed fix**: No code change needed, just note the widened approximation.

### Finding R5 [suggestion]
**Location**: Track 2, `clear()` — `setApproximateEntriesCount` ordering
**Issue**: `setApproximateEntriesCount(0)` should be called after `doClearTree` for efficiency.
**Proposed fix**: Note ordering in step description.

### Finding R6 [should-fix]
**Location**: Track 2, integration tests — WAL crash recovery testing
**Issue**: Testing WAL crash recovery requires simulating mid-commit crash. Existing infrastructure may not support this. The test may need to be scoped to clean restart verification.
**Proposed fix**: Scope "WAL-protected" test to insert-commit-restart-verify, not crash injection.

### Finding R7 [suggestion]
**Location**: Track 2, performance — `persistIndexCountDeltas` on commit path
**Issue**: Entry point page is likely already in write cache from `commitIndexes()`. Additional I/O cost is minimal (1-2 cached page modifications per commit).
**Proposed fix**: No fix needed. Performance impact is negligible.

## Overall: No blockers. 3 should-fix, 4 suggestions.
