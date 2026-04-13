# Skip SI Visibility Filtering During Histogram Build

## Problem

`buildInitialHistogram()` and histogram rebalance scans currently route through
`IndexesSnapshot.visibilityFilterMapped()`, applying full snapshot-isolation
visibility checks to every B-tree entry. This adds per-entry overhead that is
unnecessary given the histogram's inherent approximation tolerance.

## Why SI Filtering Is Unnecessary for Histograms

### One entry per logical key in the B-tree

The SI B-tree stores **exactly one physical entry per logical key**. Every
`put()` and `remove()` in `BTreeSingleValueIndexEngine` does
`sbTree.remove(old)` + `sbTree.put(new)` — it replaces, never accumulates.
Old versions are stored in the snapshot index (`ConcurrentSkipListMap`), not
in the B-tree. So raw iteration sees each user key at most once.

### Non-visible entries are bounded by concurrent TX count

The only non-visible entries during raw iteration come from uncommitted
transactions:

| RID type | Meaning | Count bound |
|---|---|---|
| `RecordId` from in-progress TX | Uncommitted new insert | concurrent writers |
| `SnapshotMarkerRID` from in-progress TX | Uncommitted update (same user key) | zero extra keys |
| `TombstoneRID` from in-progress TX | Uncommitted delete | concurrent writers |
| Any RID with version > snapshotTs | Phantom (committed after snapshot) | concurrent writers |

Typical concurrent writer count: 1-50. Typical index size: 100K-10M+.
Error: **< 0.01%**.

### Histogram tolerance is orders of magnitude larger

- Rebalance threshold: **30%** of `totalCountAtLastBuild` (configurable via
  `QUERY_STATS_REBALANCE_MUTATION_FRACTION`, default 0.3).
- Between rebuilds, bucket frequencies drift via deltas and are clamped to >= 0.
- Bucket model assumes uniform distribution within each bucket.
- `SelectivityEstimator` clamps all values to `[0.0, 1.0]` and intentionally
  overestimates (conservative bias toward full scans).

A 0.01% error from non-visible entries is noise within the 30% tolerance.

### Per-entry cost of SI filtering on the fast path

For each entry (committed & visible, which is 99.99%+ of entries):

1. `key.getKeys().getLast()` — extract version from CompositeKey
2. `inProgressVersions.isEmpty()` — check on `LongOpenHashSet`
3. `inProgressVersions.contains(version)` — hash lookup if set non-empty
4. `version > snapshotTs` — long comparison
5. 2-3 `instanceof` checks on the RID

Plus `visibilityFilterMapped()` allocates a new `RawPair<K, RID>` via
`mapMulti` for every visible entry — an allocation the raw path avoids.

On the rare slow path (TombstoneRID/SnapshotMarkerRID from in-progress or
phantom TX), `lookupSnapshotRid()` allocates a search key and does an
O(log n) lookup in the `ConcurrentSkipListMap`.

## What to Change

### Files to modify

1. **`BTreeSingleValueIndexEngine.java`**
   (`core/src/main/java/.../index/engine/v1/BTreeSingleValueIndexEngine.java`)

2. **`BTreeMultiValueIndexEngine.java`**
   (`core/src/main/java/.../index/engine/v1/BTreeMultiValueIndexEngine.java`)

### For single-value engine

In `BTreeSingleValueIndexEngine`, add a new method that provides a raw
(unfiltered) key stream for histogram use:

```java
/**
 * Returns a raw key stream that skips TombstoneRID entries but does NOT
 * apply SI visibility filtering. Suitable only for histogram building
 * where approximate counts are acceptable.
 */
private Stream<Object> rawKeyStreamForHistogram(
    @Nonnull AtomicOperation atomicOperation) {
  final var firstKey = sbTree.firstKey(atomicOperation);
  if (firstKey == null) {
    return Stream.empty();
  }
  return sbTree.iterateEntriesMajor(firstKey, true, true, atomicOperation)
      .filter(pair -> !(pair.second() instanceof TombstoneRID))
      .map(pair -> extractKey(pair.first()));
}
```

Then change `buildInitialHistogram()` to use this raw stream:

```java
@Override
public void buildInitialHistogram(@Nonnull AtomicOperation atomicOperation)
    throws IOException {
  var mgr = histogramManager;
  if (mgr == null) {
    return;
  }

  long approxTotal = approximateIndexEntriesCount.get();
  long exactNullCount = countNulls(atomicOperation);

  long scannedNonNull;
  try (var keyStream = rawKeyStreamForHistogram(atomicOperation)) {
    scannedNonNull = mgr.buildHistogram(
        atomicOperation, keyStream,
        approxTotal, exactNullCount,
        mgr.getKeyFieldCount());
  }

  long exactTotal = scannedNonNull + exactNullCount;
  approximateIndexEntriesCount.set(exactTotal);
  approximateNullCount.set(exactNullCount);
  sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
}
```

Also update the `keyStreamSupplier` wiring (used by rebalance) if it
currently goes through the SI-filtered `keyStream()`. Check
`IndexHistogramManager.setKeyStreamSupplier()` and where it's called.

### For multi-value engine

Apply the same pattern in `BTreeMultiValueIndexEngine` — add a raw key
stream method that skips TombstoneRID entries, and wire it into
`buildInitialHistogram()` and the rebalance key stream supplier.

### countNulls()

Check whether `countNulls()` also goes through SI filtering. If it does, the
same optimization applies — a raw count of non-tombstone null entries is
sufficient for histogram purposes.

Current implementation (`BTreeSingleValueIndexEngine:524-527`):
```java
private long countNulls(@Nonnull AtomicOperation atomicOperation) {
  try (var nullStream = get(null, atomicOperation)) {
    return nullStream.count();
  }
}
```

`get()` uses `sbTree.getVisible()` which applies inline SI filtering. For
histogram purposes, replace with a raw check: look up the null key in the
B-tree, count it if the RID is not a TombstoneRID.

## What NOT to Change

- **Regular query streams** (`stream()`, `descStream()`, `iterateEntriesBetween()`,
  `get()`) — these MUST keep SI filtering for correctness.
- **`approximateIndexEntriesCount` / `approximateNullCount`** scalar counters — these
  are maintained via `IndexCountDelta` on put/remove and remain correct.
- **Histogram delta tracking** (`onPut`, `onRemove`) — unaffected.

## Testing

1. **Existing histogram tests** should continue to pass — the optimization does not
   change histogram semantics in any observable way for single-threaded tests.

2. **Add a targeted test**: build a histogram while a concurrent transaction has
   uncommitted inserts and deletes. Verify the histogram is built successfully
   and the bucket boundaries/frequencies are reasonable (within the existing
   tolerance). This confirms the raw scan handles TombstoneRID/SnapshotMarkerRID
   entries without crashing.

3. **Run full core unit tests**: `./mvnw -pl core clean test`

4. **Run coverage gate**: verify coverage thresholds are met for modified code.

## Verification

After implementation, the following should hold:

- `buildInitialHistogram()` no longer calls `visibilityFilterMapped()` or
  `checkVisibility()`.
- Histogram rebalance scans (via `keyStreamSupplier`) also use the raw path.
- No `AtomicOperationsSnapshot` is created or accessed during histogram scans
  (unless needed for other reasons in the same transaction).
- All existing histogram tests pass unchanged.
