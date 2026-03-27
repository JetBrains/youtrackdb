# Track 8 Technical Review

## Review scope
Track 8: Fix pre-existing test failures from rebase conflict resolution

## Findings

### Finding T1 [should-fix]
**Location**: Track description, category (1) "Histogram engine tests" — root cause misidentified
**Issue**: The track description attributes all 13 histogram test failures to "conflict resolution errors in histogram keyStreamSupplier merge." In reality, there are two distinct root causes:

**Root cause A (9 tests)**: The `keyStreamSupplier` type was changed from `Supplier<Stream<Object>>` to `Function<AtomicOperation, Stream<Object>>` during the rebase. This added a call to `storage.getAtomicOperationsManager().startAtomicOperation()` inside `doRebalance()` (IndexHistogramManager.java). All histogram unit test fixtures use `mock(AtomicOperationsManager.class)` without stubbing `startAtomicOperation()`, so it returns `null`. When `doRebalance` calls `atomicOp.deactivate()` on `null`, a NullPointerException silently fails the background rebalance. Affected: IncrementalMaintenanceTest (4), RebalanceTriggerTest (4), ThreeTierTransitionTest (1).

**Root cause B (1 test)**: `BTreeEngineHistogramBuildTest.histogramManager_statsFileExists_delegatesToWriteCache` passes `null` to `statsFileExists()`. The null fallback was removed (Track 1's "AtomicOperation is always non-null" design principle), so this fails with an assertion error.

**Proposed fix**: Stub `startAtomicOperation()` on mock `AtomicOperationsManager` in histogram test fixtures. Update `BTreeEngineHistogramBuildTest` to pass a mock `AtomicOperation` instead of `null`.

### Finding T2 [blocker]
**Location**: Track description, category (2) "StorageBackupTest" — root cause is wrong
**Issue**: The actual root cause is a WOWCache NPE in `flushExclusiveWriteCache`: during backup restore, files are deleted and recreated. A periodic background flush task tries to flush pages from `exclusiveWritePages` for a file that no longer exists in the `files` container. The NPE is stored in `AbstractStorage.error`, and subsequent operations fail with "Internal error happened in storage."

This affects 14 tests (not 11): StorageBackupTest (11), IndexHistogramDurabilityTest (2), DatabaseImportTest (1). The histogram durability and import tests were miscategorized in the track description.

**Proposed fix**: In `WOWCache.flushExclusiveWriteCache`, add a null guard for the file lookup. Skip pages whose file no longer exists — they'll be cleaned up by `doRemoveCachePages`.

### Finding T3 [should-fix]
**Location**: Track description, failure categorization
**Issue**: The actual breakdown across three root causes:
- Mock `AtomicOperationsManager.startAtomicOperation()` not stubbed: 9 tests
- `DurableComponent.isFileExists()` null fallback removed: 1 test
- WOWCache `flushExclusiveWriteCache` NPE during periodic flush: 14 tests
- Total: 24 (count is correct, categorization is wrong)

**Proposed fix**: Restructure steps around the three actual root causes.

### Finding T4 [should-fix]
**Location**: `DiskStorage.postProcessIncrementalRestore` (lines ~1655-1665)
**Issue**: The if/else branches appear identical — both do `configuration = new CollectionBasedStorageConfiguration(this)` followed by `load()`. This looks like a merge conflict resolution error. May not cause test failures directly (WOWCache NPE happens first) but is a latent bug.

**Proposed fix**: Compare with develop to determine correct behavior for the else branch.

### Finding T5 [suggestion]
**Location**: Track scope estimate
**Issue**: The WOWCache fix is a production code change in a concurrent data structure, not just a test fix. The track description understates this complexity.

**Proposed fix**: Step decomposition should treat the WOWCache fix as the most complex step requiring careful review of `exclusiveWritePages` lifecycle.

## Summary
- 0 blockers after accounting for the corrected root causes (T2 is blocker for the track description's accuracy, not for executability — the fix approach is clear)
- The track is feasible with 3 steps, but steps should be organized around the 3 actual root causes rather than the 2 originally assumed categories
- **PASS** — proceed with corrected step decomposition
