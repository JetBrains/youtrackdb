# SI `get(key)` Performance Regression Analysis

## Before (develop)

```java
// BTreeSingleValueIndexEngine.get() on develop
public Stream<RID> get(Object key, AtomicOperation atomicOperation) {
    final var rid = sbTree.get(key, atomicOperation);
    if (rid == null) return Stream.empty();
    return Stream.of(rid);
}
```

This is a single B-tree `get()` — one O(log N) tree descent via `findBucket()`, a binary search within the leaf page, and a direct RID return. The only allocations are the `BucketSearchResult` and a single `Stream.of(rid)`. No cursor, no iteration.

## After (ytdb-523-si-indexes)

```java
// BTreeSingleValueIndexEngine.get() on ytdb-523-si-indexes
public Stream<RID> get(Object key, AtomicOperation atomicOperation) {
    var compositeKey = convertToCompositeKey(key);          // (1)
    var stream = sbTree.iterateEntriesBetween(              // (2)
        compositeKey, true, compositeKey, true, true, atomicOperation);
    var filtered = indexesSnapshot.visibilityFilter(         // (3)
        atomicOperation, stream);
    if (key == null) {                                       // (4)
        filtered = filtered.filter(pair -> extractKey(pair.first()) == null);
    }
    return filtered.map(RawPair::second);                    // (5)
}
```

## Breakdown of the cost per `get(key)` call

### Step (1) — `convertToCompositeKey(key)`

Allocates a `new CompositeKey(key)` wrapping an `ArrayList` with 1 element.
*Small but new per-call allocation.*

### Step (2) — `iterateEntriesBetween(from=key, to=key)`

This goes through:

1. `executeOptimisticStorageRead()` — optimistic/retry wrapper with lambda captures
2. `keySerializer.preprocess()` — called *twice* (once for fromKey, once for toKey, even though they're the same object)
3. `enhanceFromCompositeKeyBetweenAsc()` + `enhanceToCompositeKeyBetweenAsc()` — adds `PartialSearchMode` boundaries (allocates new `CompositeKey` objects for both from/to bounds)
4. `new SpliteratorForward(...)` — allocates the spliterator + its internal `ArrayList<>` dataCache
5. `StreamSupport.stream(spliterator, false)` — wraps in a `ReferencePipeline`

On the *first* `tryAdvance()` call, the spliterator calls `fetchNextForwardCachePortion()` which:

- Acquires a **shared lock** (`acquireSharedLock()`) — the old `get()` used `executeOptimisticStorageRead` which is lock-free on the happy path
- Calls `findBucket()` — same O(log N) descent as before
- Calls `readKeysFromBucketsForward()` — reads the leaf page, iterates entries into the `dataCache` ArrayList, creating `RawPair<K, RID>` objects for each entry that falls within the from/to range

For a unique index with versioned keys, the prefix scan `[key, -∞]..[key, +∞]` typically finds 1 entry (the latest version). But the cursor infrastructure doesn't know that — it fills the entire page-worth of matching entries into `dataCache`.

### Step (3) — `visibilityFilter()`

Wraps the stream in another `mapMulti` pipeline stage. Per entry this:

- Calls `pair.first().getKeys().getLast()` — `getKeys()` allocates an `UnmodifiableList` wrapper, `getLast()` returns a boxed `Long`, unboxed to `long`
- Checks `inProgressVersions.contains(version)` — hash set lookup
- Checks `rid instanceof SnapshotMarkerRID / TombstoneRID / RecordId`
- Allocates `new RawPair<>(keyMapper.apply(...), rid)` for each surviving entry

### Step (5) — `.map(RawPair::second)`

Adds yet another pipeline stage.

## Total per-call object allocation count (approximate, happy path, 1 entry found)

| Object | Count | Source |
|---|---|---|
| `CompositeKey` (user key wrapper) | 1 | `convertToCompositeKey` |
| `CompositeKey` (enhanced from-key) | 1 | `enhanceFromCompositeKeyBetweenAsc` |
| `CompositeKey` (enhanced to-key) | 1 | `enhanceToCompositeKeyBetweenAsc` |
| `SpliteratorForward` + its `ArrayList` | 1+1 | `iterateEntriesBetweenAscOrder` |
| `ReferencePipeline` (stream) | 1 | `StreamSupport.stream()` |
| `RawPair` (from bucket read) | 1 | `readKeysFromBucketsForward` |
| `UnmodifiableList` wrapper | 1 | `getKeys()` in visibility filter |
| `RawPair` (after visibility filter) | 1 | `mapMulti` downstream.accept |
| Pipeline intermediate stages | 2 | `.mapMulti()`, `.map()` |
| Lambda captures | 3+ | closures for mapMulti, map, filter |
| **Total** | **~14+** | |

Compare to the old path: **~2 objects** (`BucketSearchResult` + `Stream.of(rid)`).

## Locking difference

The old `get()` used `executeOptimisticStorageRead` — an optimistic read that avoids locking on the happy path (reads the page, checks LSN, retries if stale). The new cursor path calls `acquireSharedLock()` in `fetchForwardCachePortionInner()` (line 2264), which takes a `ReentrantReadWriteLock` shared lock. Under high read concurrency this is fine, but any concurrent structural B-tree modification (split/merge) that takes the write lock will block all `get()` calls, whereas the old optimistic path would retry without blocking.

## Why it matters

`get(key)` on a unique index is the single hottest index path — it's called for every `WHERE indexed_field = ?` query, every unique constraint check during insert, and every FK validation. Even a 3-5x overhead in object allocation and pipeline setup translates to measurable latency under load.

## Possible mitigation

A dedicated `getVisible(key, atomicOperation)` method could:

1. Use the existing `findBucket()` for the initial O(log N) tree descent (same as old `get`)
2. Scan forward from the found position within the same leaf page (no cursor/spliterator needed)
3. Apply visibility inline without Stream construction
4. Return after the first visible entry (short-circuit for unique indexes)

This would preserve the old `get()` cost profile while adding only the version/visibility check overhead, which is O(K) where K is typically 1.
