# Track 3 Technical Review

## Finding T1 [suggestion]
**Location**: Track 3 description, "Add unit tests for getVisible() directly" section
**Issue**: Track 2 already added 26 BTree-level tests in `BTreeGetVisibleTest.java` covering committed, tombstone, multi-version, null key, empty tree, cross-page, snapshot markers, and equivalence. Track 3's test list duplicates Track 2's completed work.
**Proposed fix**: Track 3 tests should focus on engine-level verification: (1) existing integration tests pass after wiring change, (2) optionally engine-level tests for `get()` returning correct `Stream<RID>`.

## Finding T2 [should-fix]
**Location**: Track 3 engine wiring, current `get()` null-key filter at lines 250-253
**Issue**: Current `get()` has a null-key filter for composite indexes that prevents matching `CompositeKey(null, "Smith", version)` when doing a true null-key lookup. The plan doesn't explain why this filter can be removed. With `getVisible()`, `userKeyPrefixMatches()` rejects mismatched composite keys, but this reasoning should be documented.
**Proposed fix**: Add a note explaining null-key filter is unnecessary because `getVisible()`'s prefix matching handles this. Reference `SnapshotIsolationIndexesNullKeyTest.unique_compositeIndex_nullFieldsAreNotNullKey()` as regression test. **NOTE: Superseded by R1 — null keys should keep the old path entirely.**

## Finding T3 [suggestion]
**Location**: Track 3 scope estimate
**Issue**: The engine wiring is ~10 lines replaced with ~5 lines. With Track 2's tests and existing integration tests, the track is closer to 1-2 steps.
**Proposed fix**: Adjust scope to ~2 steps.

## Finding T4 [suggestion]
**Location**: Track 3 integration points
**Issue**: The old `CellBTreeSingleValue.get()` is used internally by BTree for the null bucket (`getNullBucketValue()`), not by the engine. It's not a "non-SI callers" concern.
**Proposed fix**: Clarify in plan that old `get()` is internal BTree use only.
