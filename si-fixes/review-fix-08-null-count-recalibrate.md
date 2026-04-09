# Fix 08: Recalibrate null count in multi-value buildInitialHistogram()

## Problem

`BTreeMultiValueIndexEngine.buildInitialHistogram()` (lines 543-568) recalibrates
the non-null count from a visibility-filtered scan of `svTree`, but `approximateNullCount`
remains at its approximate value (read from the persisted counter at load time). If the
null count has drifted due to crashes or other anomalies, it won't be corrected by
histogram recalibration.

The total approximate count (`scannedNonNull + approxNull`) will also be incorrect
if `approxNull` has drifted.

The comment says "counting visible nulls would require a full scan", which is true,
but the null tree is typically small (one entry per null-keyed document), so the scan
cost may be negligible.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `buildInitialHistogram()` lines 543-568

## Fix Strategy

Add a visibility-filtered count of the null tree during `buildInitialHistogram()`:

```java
// Count visible null entries for recalibration. The null tree is typically
// small (one entry per null-keyed document), so the scan cost is negligible
// compared to the svTree scan.
long exactNullCount;
var nullFirstKey = nullTree.firstKey(atomicOperation);
if (nullFirstKey == null) {
  exactNullCount = 0;
} else {
  try (var nullStream = nullIndexesSnapshot.visibilityFilterValues(
      atomicOperation,
      nullTree.iterateEntriesMajor(nullFirstKey, true, true, atomicOperation))) {
    exactNullCount = nullStream.count();
  }
}
```

Then use `exactNullCount` instead of `approxNull` for the total count, and
persist it to the null tree's entry point page:

```java
nullTree.setApproximateEntriesCount(atomicOperation, exactNullCount);
approximateNullCount.set(exactNullCount);
approximateIndexEntriesCount.set(scannedNonNull + exactNullCount);
```

Note: `visibilityFilterValues` is from Fix 07. If Fix 07 is not done first,
use `visibilityFilter(atomicOperation, stream).count()` instead.

## Implementation Steps

1. Add null tree scan to `buildInitialHistogram()`.
2. Persist exact null count to null tree entry point page.
3. Update in-memory `approximateNullCount` with exact value.
4. Update total count to use exact null count.
5. Run `./mvnw -pl core spotless:apply`
6. Run `./mvnw -pl core clean test`
7. Commit: `YTDB-523: Recalibrate null count in multi-value buildInitialHistogram()`

## Severity

Suggestion. The null tree is typically small, so drift impact is limited. But
this makes the recalibration complete and eliminates a source of inaccuracy.
