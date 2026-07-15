# Consistency Review — index-count-delta

## Iteration 1: Full review

### Finding CR1 [blocker] — FIXED
**Location**: Design document, "Null Key Tracking" section
**Issue**: References phantom method `putInternal` in `BTreeMultiValueIndexEngine`. Actual method is `put()` → `doPut()`.
**Fix applied**: Changed to `put()` and `remove()`.

### Finding CR2 [blocker] — FIXED
**Location**: Plan Track 1, Constraints, Non-Goals; Design lifecycle table
**Issue**: Claimed `buildInitialHistogram()` "unchanged" and "already recalibrates both counters." It only recalibrates `approximateIndexEntriesCount` — `approximateNullCount` doesn't exist yet.
**Fix applied**: Removed "unchanged" claims. Track 1 now includes updating `buildInitialHistogram()` to also set `approximateNullCount`. Non-Goals updated.

### Finding CR3 [should-fix] — FIXED
**Location**: Plan D1 Risks/Caveats
**Issue**: Claimed `clear()` only runs in standalone atomic operations. Also called from `commitIndexes()` within the transaction's atomic operation.
**Fix applied**: Documented both paths and explained why the sequence is safe (clear sets to 0, subsequent put deltas are applied on commit).

### Finding CR4 [should-fix] — FIXED
**Location**: Design class diagram, plan component map
**Issue**: Design specified `Int2ObjectOpenHashMap` (fastutil) but claimed to follow `HistogramDeltaHolder` pattern which uses `HashMap`.
**Fix applied**: Changed to `HashMap<Integer, IndexCountDelta>` throughout.

### Finding CR5 [should-fix] — FIXED
**Location**: Plan Track 1 description
**Issue**: Missing resilience pattern (try-catch) specification for `applyIndexCountDeltas()`.
**Fix applied**: Added to Track 1 description.

### Finding CR6 [suggestion] — FIXED
**Location**: Design class diagram
**Issue**: Missing `BTreeIndexEngine` interface that declares `getTotalCount`/`getNullCount`.
**Fix applied**: Added to class diagram with implementation relationships.

### Finding CR7 [suggestion] — FIXED
**Location**: Plan Track 1 description
**Issue**: Multi-value engine `load()` scan complexity not described (two trees, two snapshots).
**Fix applied**: Added explicit note about scanning both `svTree` and `nullTree`.

## Result: PASS (all findings fixed)
