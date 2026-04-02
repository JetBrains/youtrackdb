# Technical Review — Track 1

### Finding T1 [should-fix]
**Location**: Engine `put`/`validatedPut`/`doPut` methods
**Issue**: Delta accumulation is conditional (only on tombstone-resurrection and new-entry, not live-to-live overwrites). Plan says "±1" without calling out the conditional logic.
**Proposed fix**: Add explicit guidance that delta must be placed at the exact same code points as current `incrementAndGet()`/`decrementAndGet()` calls.

### Finding T2 [should-fix]
**Location**: Track 1, `load()` scan implementation
**Issue**: Plan doesn't describe empty-tree handling or that `extractKey` must be used for null-ness detection.
**Proposed fix**: Add guidance for empty-tree case (set both to 0) and `extractKey` usage.

### Finding T3 [suggestion]
**Location**: D1 rationale
**Issue**: Alternative: always accumulate count deltas in `HistogramDelta` even when `histogramManager` is null. Would avoid second holder. Current approach is clean and decoupled though.
**Proposed fix**: No action needed — separate holder is the chosen approach.

### Finding T4 [should-fix]
**Location**: Track 1, multi-value engine null tree scan
**Issue**: Plan mentions scanning both trees but doesn't specify that null tree must use `nullIndexesSnapshot` (not `indexesSnapshot`).
**Proposed fix**: Explicitly specify `nullIndexesSnapshot.visibilityFilter()` for null tree.

### Finding T5 [should-fix] (downgraded from blocker)
**Location**: Track 1, `clear()` during `commitIndexes()` + runtime rollback
**Issue**: D1 risk description says "B-tree rollback also reverts the clear" — misleading. On runtime rollback (no crash), counters remain zeroed until next `buildInitialHistogram()`. Pre-existing issue.
**Proposed fix**: Update D1 risk to clarify runtime rollback limitation.

### Finding T6 [should-fix]
**Location**: Track 1, `buildInitialHistogram()` early return when `histogramManager == null`
**Issue**: Self-healing only works when histogram manager exists. When null, counter drift from `clear()` can't heal until restart.
**Proposed fix**: Consider moving counter recalibration before the null check, or document limitation.

### Finding T7 [suggestion]
**Location**: Track 1, `addToApproximate*` methods
**Issue**: Should be declared on `BTreeIndexEngine` interface, not just concrete classes.
**Proposed fix**: Explicitly state interface declaration.

### Finding T8 [suggestion]
**Location**: Track 1, load() startup cost
**Issue**: No logging for initialization scan duration.
**Proposed fix**: Add debug-level log.

## Overall: No blockers. 5 should-fix, 3 suggestions.
