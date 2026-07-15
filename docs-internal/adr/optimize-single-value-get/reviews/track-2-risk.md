# Track 2 Risk Review

## Iteration 1

### Finding R1 [should-fix]
**Location**: `OptimisticReadScope.java`, `BTree.java` right-sibling traversal
**Issue**: OptimisticReadScope API does not support mid-traversal validate-then-continue-recording pattern needed for right-sibling traversal. Likelihood: high if implemented as plan D2 option 3 describes.
**Proposed fix**: Same as T1 — optimistic path handles single-page only; cross-page falls through to pinned. This eliminates the scope lifecycle issue entirely.

### Finding R2 [should-fix]
**Location**: `CompositeKey.compareTo()`, `CellBTreeSingleValueBucketV3.find()`
**Issue**: Prefix matching relies on `CompositeKey.compareTo()` returning 0 for shorter-key matches. While correct for the current key format, the assumption is not explicitly validated.
**Proposed fix**: After `find()` returns a match, the forward scan already validates key prefix via `checkVisibility()` which extracts version from the last element — if the key doesn't match, the version extraction will use wrong data. Add a defensive prefix check in the leftward scan boundary condition.

### Finding R3 [rejected]
**Location**: Null key handling in BTree
**Issue**: Reviewers claimed null keys are not in the main B-tree. Investigation confirmed they ARE in the main B-tree as `CompositeKey(null, version)` for the SI engine path.
**Proposed fix**: No change needed.

### Finding R4 [suggestion]
**Location**: `IndexesSnapshot.checkVisibility()` line 121
**Issue**: Unchecked `(Long)` cast for version extraction. Pre-existing code from Track 1, not a Track 2 concern.
**Proposed fix**: Out of scope for Track 2.

### Finding R5 [should-fix]
**Location**: Visibility decision consistency
**Issue**: `getVisible()` (direct path) and `iterateEntriesBetween()` + `visibilityFilterMapped()` (stream path) must return identical results for all scenarios.
**Proposed fix**: Add equivalence test comparing both paths for all visibility scenarios (committed, tombstone, snapshot marker, in-progress, phantom, null key).

## Decisions
- R1: Accepted — same resolution as T1
- R2: Accepted — add defensive check during implementation
- R3: Rejected — plan is correct
- R4: Out of scope
- R5: Accepted — add equivalence tests

## Summary: PASS (no blockers after T1/R1 resolution)
