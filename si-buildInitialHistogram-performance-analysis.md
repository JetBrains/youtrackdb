# SI `buildInitialHistogram` Full-Materialization Performance Analysis

## Before (develop)

```java
// BTreeSingleValueIndexEngine.buildInitialHistogram() on develop
public void buildInitialHistogram(AtomicOperation atomicOperation) throws IOException {
    var mgr = histogramManager;
    if (mgr == null) return;

    long nonNullCount = sbTree.size(atomicOperation);           // O(1) from TREE_SIZE
    long nullCount = sbTree.get(null, atomicOperation) != null ? 1 : 0;
    long totalCount = nonNullCount + nullCount;

    try (var keys = sbTree.keyStream(atomicOperation)) {        // streaming — no materialization
        mgr.buildHistogram(atomicOperation, keys, totalCount, nullCount,
            mgr.getKeyFieldCount());
    }
}
```

The old code:
1. Gets counts in O(1) from the B-tree's `TREE_SIZE` field and a single null-bucket lookup.
2. Streams keys directly into `buildHistogram` → `scanAndBuild`. The `scanAndBuild` method iterates the stream with `iterator.hasNext()`/`iterator.next()` — it never collects into a collection. Keys are processed one at a time and only the current bucket state (boundaries, frequencies, NDV counters) is held in memory.

**Peak heap overhead**: O(targetBuckets) — just the histogram arrays. The index data streams through without retention.

## After (ytdb-523-si-indexes)

### Single-value engine

```java
// BTreeSingleValueIndexEngine.buildInitialHistogram() on this branch
List<RawPair<Object, RID>> nonNullEntries;
long nullCount;
try (var allVisible = indexesSnapshot.visibilityFilterMapped(atomicOperation,
    sbTree.iterateEntriesMajor(firstKey, true, true, atomicOperation),
    BTreeSingleValueIndexEngine::extractKey)) {
    var partitioned = allVisible.collect(
        Collectors.partitioningBy(p -> p.first() != null));   // ← full materialization
    nonNullEntries = partitioned.get(true);
    nullCount = partitioned.get(false).size();
}
// ... later ...
mgr.buildHistogram(atomicOperation,
    nonNullEntries.stream().map(RawPair::first), ...);        // ← re-stream from list
```

### Multi-value engine

```java
// BTreeMultiValueIndexEngine.buildInitialHistogram() on this branch
List<RawPair<Object, RID>> nonNullEntries;
try (var svStream = indexesSnapshot.visibilityFilterMapped(atomicOperation,
    svTree.iterateEntriesMajor(firstKey, true, true, atomicOperation),
    BTreeMultiValueIndexEngine::extractKey)) {
    nonNullEntries = svStream.toList();                       // ← full materialization
}
```

## Why full materialization happens

The root cause is that the new code needs two pieces of information from the same scan:

1. **Counts** (`totalCount`, `nullCount`) — for recalibrating `approximateIndexEntriesCount` and persisting to the entry point page.
2. **Key stream** — for passing to `mgr.buildHistogram()`.

On `develop`, counts were O(1) (read `TREE_SIZE` from the entry point page), so the scan only served purpose #2 and could stream. Now, with snapshot isolation, `TREE_SIZE` includes tombstones and markers — it's no longer the visible entry count. The code must scan to get the accurate visible count, and then it needs the keys for the histogram. Rather than scanning twice, it collects everything into a list, extracts the count, then re-streams the list to the histogram builder.

For the single-value engine, `Collectors.partitioningBy` is used to simultaneously separate null from non-null entries. This creates **two** `ArrayList` instances internally — one for `true` (non-null) and one for `false` (null). Both hold `RawPair<Object, RID>` objects.

## Memory impact

Each `RawPair<Object, RID>` is a Java record (16-byte header + 2 reference fields = 32 bytes with compressed oops). The `Object` key (first field) is whatever the user's key type is (e.g., `String`, `Long`, `CompositeKey`). The `RID` (second field) is a `RecordId` (16-byte header + int + long = 32 bytes).

For an index with N visible entries:

| Component | Size per entry | Total for N entries |
|---|---|---|
| `RawPair` object | ~32 bytes | 32N bytes |
| ArrayList internal `Object[]` backing | 8 bytes (ref) | 8N bytes |
| Key object (already exists from scan) | 0 (shared ref) | 0 |
| RID object (already exists from scan) | 0 (shared ref) | 0 |
| **Per-entry overhead** | **~40 bytes** | **40N bytes** |

**Concrete examples:**

| Index size (N) | Materialization overhead | Context |
|---|---|---|
| 100K entries | ~4 MB | Negligible |
| 1M entries | ~40 MB | Noticeable GC pressure |
| 10M entries | ~400 MB | Major heap spike, likely GC pause |
| 50M entries | ~2 GB | May cause OOM on typical heap configs |

This is a **transient** spike — the list is eligible for GC immediately after `buildHistogram` returns — but with a 4 GB test heap (`-Xmx4096m`), a 50M-entry index would consume half the heap in a single allocation burst.

### Additional overhead from `partitioningBy` (single-value engine only)

`Collectors.partitioningBy` internally creates two `ArrayList` instances and grows them dynamically. The `false` partition (null entries) is typically 0 or 1 entries for a single-value unique index, but the `true` partition holds *all* non-null entries. The `ArrayList` growth strategy (1.5x) means up to 50% wasted capacity in the backing array at peak. So the actual memory usage can be up to **60N bytes** rather than 40N.

### The list is re-streamed wastefully

After materialization, the list is re-streamed:

```java
mgr.buildHistogram(atomicOperation,
    nonNullEntries.stream().map(RawPair::first), ...);
```

This creates a new `Stream` pipeline over the list, applies `.map(RawPair::first)` to extract keys, and feeds them into `scanAndBuild` which iterates them one-by-one. The entire purpose of `scanAndBuild` is streaming iteration — it holds only O(targetBuckets) state. So the list exists solely as a buffer between the count step and the key-streaming step.

## Why this is solvable without two scans

The `scanAndBuild` method already counts entries as it iterates (the `totalSeen` variable at line 1030 of `IndexHistogramManager.java`). The count is available after the scan completes. The challenge is that `buildHistogram` needs `totalCount` *before* calling `scanAndBuild` — it's used to compute `targetBuckets`:

```java
targetBuckets = Math.min(targetBuckets, (int) Math.floor(Math.sqrt(nonNullCount)));
```

And to check `if (nonNullCount < histogramMinSize)`.

However, there are several approaches that avoid full materialization:

### Approach A: Use `approximateIndexEntriesCount` for bucket sizing

The approximate count (from delta accumulation) is already available before the scan. It's "approximate" but for the purpose of `sqrt(N)` bucket sizing, it's more than sufficient — being off by a few percent doesn't meaningfully change the histogram quality.

```java
// Use approximate count for bucket sizing, scan for exact count + keys
long approxNonNull = approximateIndexEntriesCount.get() - approximateNullCount.get();
long exactNullCount = countNulls(atomicOperation);  // single null lookup
long exactTotal;

try (var keyStream = visibilityFilteredKeyStream(atomicOperation)) {
    // scanAndBuild streams keys, returns exact count in result
    var result = mgr.buildHistogramStreaming(
        atomicOperation, keyStream, approxNonNull, exactNullCount, ...);
    exactTotal = result.totalSeen + exactNullCount;
}

// Recalibrate from exact count
approximateIndexEntriesCount.set(exactTotal);
sbTree.setApproximateEntriesCount(atomicOperation, exactTotal);
```

### Approach B: Two-pass streaming (count then keys)

Scan the index twice — once for counting (cheap, no object retention), once for keys. This doubles the I/O but keeps memory at O(targetBuckets):

```java
// Pass 1: count visible entries (streaming, no materialization)
long[] counts = countVisibleEntries(atomicOperation); // [nonNull, null]
approximateIndexEntriesCount.set(counts[0] + counts[1]);
sbTree.setApproximateEntriesCount(atomicOperation, counts[0] + counts[1]);

// Pass 2: stream keys to histogram (no materialization)
try (var keyStream = visibilityFilteredKeyStream(atomicOperation)) {
    mgr.buildHistogram(atomicOperation, keyStream,
        counts[0] + counts[1], counts[1], mgr.getKeyFieldCount());
}
```

### Approach C: Refactor `buildHistogram` to accept unknown count

Change `scanAndBuild` to not require `nonNullCount` upfront. Instead:
- Use a default `targetBuckets` initially
- Optionally resize after the scan if the actual count is very different
- Or accept that `targetBuckets` based on approximate count is fine

This is the simplest change and preserves the single-pass property.

## Summary

| Aspect | develop | This branch |
|---|---|---|
| Count source | O(1) from `TREE_SIZE` | Full scan (materialized) |
| Key delivery | Streaming to `scanAndBuild` | Collect → List → Re-stream |
| Peak memory | O(targetBuckets) | O(N) where N = visible entries |
| GC impact | Negligible | Proportional to index size |
| I/O passes | 1 (keys only) | 1 (but held in memory) |

The core issue is that materialization is used as a convenience to extract counts from the same scan that provides keys. This is solvable by either accepting the approximate count for bucket sizing (Approach A/C) or scanning twice (Approach B), both of which keep peak memory at O(targetBuckets) regardless of index size.
