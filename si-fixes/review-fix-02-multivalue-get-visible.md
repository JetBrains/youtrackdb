# Fix 02: Add getVisibleStream() for multi-value index get()

## Problem

`BTreeSingleValueIndexEngine.get()` uses the optimized `BTree.getVisible()` path — a
direct leaf-page descent with inline visibility check, no stream/spliterator/mapMulti
pipeline. But `BTreeMultiValueIndexEngine.get()` falls back to `iterateEntriesBetween()`
+ `visibilityFilter()` + `.map(RawPair::second)`, which creates a full stream pipeline
per lookup (~3-5 μs stream setup vs ~0.5 μs direct leaf scan).

For queries that fan out to many index lookups (MATCH patterns, graph traversals), this
constant overhead per call adds up significantly.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `get()` lines 308-325: uses stream pipeline instead of direct leaf scan
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/v3/BTree.java`
  - `getVisible()` lines 320-365: returns single RID, needs multi-RID variant
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/storage/index/sbtree/singlevalue/CellBTreeSingleValue.java`
  - Interface: needs `getVisibleStream()` declaration

## Fix Strategy

1. Add `Stream<RID> getVisibleStream(K key, IndexesSnapshot snapshot, AtomicOperation atomicOperation)` to `CellBTreeSingleValue` interface and `BTree` implementation.

2. The implementation reuses the same leaf-descent logic as `getVisible()` but instead of
   returning the first visible entry, it collects ALL visible entries for the same user-key
   prefix into a stream. The `scanLeafForVisible()` method already scans all entries with
   matching prefix — modify it (or add a variant) to return a `List<RID>` of all visible
   entries instead of just the first.

3. Wire `BTreeMultiValueIndexEngine.get()` to use `getVisibleStream()`:
   - For non-null keys: `svTree.getVisibleStream(compositeKey, indexesSnapshot, atomicOperation)`
   - For null keys: `nullTree.getVisibleStream(prefixKey, nullIndexesSnapshot, atomicOperation)`

4. The existing `visibilityFilter` stream pipeline path in `get()` can be removed or kept
   as a fallback (but should not be needed).

## Key Design Considerations

- `getVisible()` returns at most 1 RID (single-value index: unique key). `getVisibleStream()`
  returns 0..N RIDs (multi-value index: same key, multiple values). The leaf scan must
  continue past the first visible entry.
- Cross-page entries: The pinned path follows right sibling pointers. The multi-value
  variant must do the same to find all entries with the matching prefix.
- The optimistic path may need to fall back to pinned if the scan crosses a page boundary.
- Memory: collecting all visible RIDs into a list before streaming is acceptable because
  multi-value cardinality per key is typically small (< 100).

## Implementation Steps

1. Add `getVisibleStream()` to `CellBTreeSingleValue` interface (default method returning
   empty stream, or abstract).
2. Implement in `BTree`:
   - Add `scanLeafForVisibleAll()` variant that collects all visible RIDs (not just first).
   - Add `getVisibleStreamOptimistic()` and `getVisibleStreamPinned()` methods.
   - Wire into `executeOptimisticStorageRead()`.
3. Update `BTreeMultiValueIndexEngine.get()` to use `getVisibleStream()`.
4. Add tests: verify multi-value `get()` returns all visible entries for a key.
5. Run: `./mvnw -pl core clean test`
6. Commit: `YTDB-523: Add getVisibleStream() for multi-value index get() optimization`

## Severity

Should-fix (performance). Direct impact on every multi-value index lookup.
