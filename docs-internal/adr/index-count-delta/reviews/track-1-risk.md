# Risk Review — Track 1

### Finding R1 [should-fix]
**Location**: Track 1, engine method delta changes — `BTreeSingleValueIndexEngine.put()`, `validatedPut()`, `BTreeMultiValueIndexEngine.doPut()`
**Issue**: Delta accumulation is conditional: only increment when `removedRID instanceof TombstoneRID` (resurrecting tombstoned entry) or when no existing entry found. NOT for live-to-live overwrites. "Same TX re-put" also skips. An implementer reading "totalDelta ±1" could naively add +1 on every put.
**Proposed fix**: Document conditional logic explicitly. Consider extracting delta-decision into a helper.

### Finding R2 [should-fix]
**Location**: Track 1, `clear()` + rollback interaction
**Issue**: `clear()` in `commitIndexes()` sets counters to 0. On rollback, counters stay at 0 (wrong). Self-heals on next `load()`/`buildInitialHistogram()`.
**Proposed fix**: Add test for rollback after `clear()`. Document as known transient-inaccuracy window.

### Finding R3 [should-fix]
**Location**: Track 1, `load()` initialization
**Issue**: Full visibility-filtered scan in `load()` adds O(n) startup cost per index. Could be meaningful for large databases.
**Proposed fix**: Log scan duration. Consider deferring accurate scan to `buildInitialHistogram()` and using `sbTree.size()` as rough initial estimate.

### Finding R4 [suggestion]
**Location**: Track 1, `applyIndexCountDeltas()` in `AbstractStorage`
**Issue**: Dropped engine may leave null entry. Must use null-safe `instanceof` pattern.
**Proposed fix**: Copy pattern from `applyHistogramDeltas()`.

### Finding R5 [should-fix]
**Location**: Track 1, null-key delta tracking in `BTreeMultiValueIndexEngine`
**Issue**: `doPut`/`doRemove` don't know null-ness. Need `boolean isNullKey` parameter. Same conditional logic for `nullDelta`.
**Proposed fix**: Pass `isNullKey` parameter. Add null-key-specific tests.

### Finding R6 [suggestion]
**Location**: Track 1, `AtomicOperation` interface change
**Issue**: Only one implementation (`AtomicOperationBinaryTracking`). Low risk.
**Proposed fix**: None needed.

### Finding R7 [should-fix]
**Location**: Track 1, `getTotalCount()`/`getNullCount()` migration path
**Issue**: Histogram migration (`wireHistogramManagerOnLoad`) calls these methods for accurate initial values. After becoming approximate, migration values could be slightly stale.
**Proposed fix**: Verify migration tolerates approximate values (recalibrated by `buildInitialHistogram()` anyway) and update Javadoc. Simpler than keeping two sets of methods.

## Overall: No blockers. 5 should-fix, 2 suggestions.
