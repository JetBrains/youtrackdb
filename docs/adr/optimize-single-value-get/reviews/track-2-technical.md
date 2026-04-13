# Track 2 Technical Review

## Iteration 1

### Finding T1 [should-fix]
**Location**: Track 2, cross-page handling; `BTree.java`, `OptimisticReadScope.java`
**Issue**: The plan describes validating the optimistic stamp before following right-sibling pointers during leaf scan. However, `OptimisticReadScope` does not support a validate-then-continue-recording pattern — `validateOrThrow()` is designed for end-of-operation use only. The existing `readKeysFromBucketsForward()` uses pinned reads for sibling traversal, not optimistic reads.
**Proposed fix**: Optimistic path handles single-page leaf scan only. If the scan exhausts the first leaf page without finding a visible entry, throw `OptimisticReadFailedException` to fall through to the pinned path, which handles cross-page via `loadPageForRead()` + `getRightSibling()`. This aligns with D2's design and the existing codebase pattern.

### Finding T2 [suggestion]
**Location**: Track 2 allocation budget
**Issue**: The plan's allocation budget ("1 serialized key byte[], 1 BucketSearchResult") omits lambda objects from `executeOptimisticStorageRead()` and potential `CompositeKey` allocations in `checkVisibility()` → `lookupSnapshotRid()` on the snapshot fallback path.
**Proposed fix**: Document realistic allocation budget. Hot path (single visible entry, no snapshot fallback) adds only the lambdas. Snapshot fallback path allocates 1 CompositeKey per `lookupSnapshotRid()` call — same as the stream path, acceptable.

### Finding T3 [should-fix]
**Location**: Track 2 leaf scan; D3 prefix matching
**Issue**: `bucket.find(serializedKey)` with a prefix key returns an arbitrary match within the prefix range (binary search). The leftward scan to find the first version is correct but untested for the `getVisible()` use case.
**Proposed fix**: Include explicit tests for prefix matching with multiple versions: verify leftward scan finds first version, forward scan visits all versions in order.

### Finding T4 [suggestion]
**Location**: `CellBTreeSingleValue.java` interface
**Issue**: `getVisible()` introduces SI-aware coupling to a storage-layer interface.
**Proposed fix**: Add Javadoc noting SI-awareness and that only index engine code should call it.

### Finding T5 [rejected]
**Location**: Track 2 null key handling
**Issue**: Reviewers flagged that null keys are in a separate null bucket, not the main B-tree. **Investigation disproved this for the SI path**: `BTreeSingleValueIndexEngine.get()` uses `iterateEntriesBetween()` with `CompositeKey(null)` on the main B-tree, not the null bucket. Null keys ARE stored as `CompositeKey(null, version)` in the main tree. The null bucket is a legacy non-SI path.
**Proposed fix**: No change needed. Plan is correct.

## Decisions
- T1: Accepted — optimistic path single-page only, pinned fallback for cross-page
- T2: Accepted as documentation note
- T3: Accepted — add tests during implementation
- T4: Accepted — add Javadoc during implementation
- T5: Rejected — investigation confirmed plan is correct

## Summary: PASS (no blockers)
