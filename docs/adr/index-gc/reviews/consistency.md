# Consistency Review — Index BTree Tombstone GC

## Iteration 1

### Finding CR1 [should-fix] — RESOLVED
**Location**: Implementation plan, Invariants section
**Issue**: `findVisibleEntry()` is a method on `SharedLinkBagBTree` (edge B-tree), not on `BTree` (index B-tree). Phantom reference.
**Evidence**: `findVisibleEntry` found only in `SharedLinkBagBTree.java`, not in any index B-tree code.
**Fix applied**: Reworded invariant to reference `iterateEntriesBetween()` and snapshot lookups.

### Finding CR2 [should-fix] — RESOLVED
**Location**: Design document, workflow sequence diagram
**Issue**: Diagram implied rebuild only happens when `removedCount > 0`. Code also rebuilds when `demoted == true` (demotion-only case).
**Evidence**: `BTree.java` line 2536: `if (removedCount == 0 && !demoted) { return 0; }`
**Fix applied**: Added third `alt` branch for demotion-only case showing rebuild but returning 0.

### Finding CR3 [suggestion] — RESOLVED
**Location**: Design document, Snapshot Query section
**Issue**: Design said `subMap(lower, upper)` but code uses `subMap(lower, true, upper, true)`.
**Fix applied**: Updated to `subMap(lower, true, upper, true)` with "inclusive on both bounds" note.

### Finding CR4 [suggestion] — RESOLVED
**Location**: Implementation plan, Integration Points
**Issue**: Said "BTree.put() while loop" but loop is in `update()`.
**Fix applied**: Changed to "BTree.update() while loop (called by put())".

### Finding CR5 [suggestion] — RESOLVED
**Location**: Design document, class diagram
**Issue**: `indexEngineNameMap: Map` missing value type.
**Fix applied**: Updated to `indexEngineNameMap: Map~String, BaseIndexEngine~`.

### Finding CR6 [suggestion] — SKIPPED
**Location**: Design document, demotion encoding section
**Issue**: Already covered by existing layout diagram. No change needed.

## Result: PASS
All findings resolved. No blockers.
