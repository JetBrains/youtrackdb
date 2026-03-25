# Track 2 Risk Review

## Iteration 1

### Finding R1 [should-fix]
**Location**: Track 2 + D5 — InPlaceResult enum vs Optional<Boolean>
**Issue**: Same as T1. Plan D5 outdated. Compiler catches immediately.
**Proposed fix**: Addressed in step decomposition.

### Finding R2 [should-fix]
**Location**: Track 2 — evaluate(Identifiable) collation check
**Issue**: Same as T5. `getCollate()` takes `Result`, not `Identifiable`. Existing overload never applies collation anyway.
**Proposed fix**: Skip collation guard in Identifiable overload; document intent.

### Finding R3 [should-fix]
**Location**: Track 2 — hot path allocation
**Issue**: `left.getDefaultAlias().getStringValue()` allocates `SQLIdentifier` per record. Use direct identifier traversal instead.
**Proposed fix**: Extract property name via `((SQLBaseExpression) left.mathExpression).getIdentifier().getSuffix().getIdentifier().getStringValue()`.

### Finding R4 [should-fix]
**Location**: Track 2 — double right.execute() on FALLBACK
**Issue**: Same as T3. Compute once, reuse.
**Proposed fix**: Addressed in step decomposition.

### Finding R5 [suggestion]
**Location**: Track 2 — SQLGetInternalPropertyExpression
**Issue**: `isBaseIdentifier()` returns true for internal property expressions. These safely FALLBACK (not in properties map or serialized index).
**Proposed fix**: Add code comment documenting the safe FALLBACK behavior.

### Finding R6 [suggestion]
**Location**: Track 2 — test strategy
**Issue**: No mechanism to disable optimization. Tests verify correctness, not "with vs without."
**Proposed fix**: Rename test goal to "SQL-level correctness tests for all 7 operators."

## Critical Path Exposure
SQLBinaryCondition.evaluate() is called per-record for WHERE-filtered scans. Blast radius: silent wrong query results. Mitigated by FALLBACK mechanism — unsupported cases always fall through to existing path.

## Performance
Guard cost is ~5 instanceof/null checks (O(1), no allocation). R3 allocation on hot path needs fixing. Non-optimizable records pay guard cost but it's negligible vs record processing.

## Rollback
Full revert possible — Track 1 code stays but goes unused.

## Summary
- 0 blockers, 4 should-fix, 2 suggestions
- **PASS** — all should-fix items addressed in step decomposition
