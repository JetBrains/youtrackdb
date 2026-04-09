# Fix 07: Add visibilityFilterValues() to avoid unnecessary RawPair allocation

## Problem

`IndexesSnapshot.visibilityFilter()` delegates to `visibilityFilterMapped()` with
`Function.identity()`. Inside `visibilityFilterMapped`, each visible entry creates
`new RawPair<>(keyMapper.apply(pair.first()), visibleRid)` — when keyMapper is
identity, this allocates a new `RawPair` with the same key reference, only for the
caller (`BTreeMultiValueIndexEngine.get()`) to immediately discard it via
`.map(RawPair::second)`.

One unnecessary `RawPair` allocation per visible entry on the multi-value `get()` path.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/IndexesSnapshot.java`
  - `visibilityFilter()` lines 111-115: delegates with Function.identity()
  - `visibilityFilterMapped()` lines 122-139: creates RawPair per entry
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `get()` lines 308-325: calls visibilityFilter + .map(RawPair::second)

## Fix Strategy

Add a `visibilityFilterValues()` method to `IndexesSnapshot` that returns
`Stream<RID>` directly, skipping the key mapping and RawPair allocation:

```java
public Stream<RID> visibilityFilterValues(
    @Nonnull AtomicOperation atomicOperation,
    Stream<RawPair<CompositeKey, RID>> stream) {
  var opsSnapshot = atomicOperation.getAtomicOperationsSnapshot();
  var snapshotTs = opsSnapshot.snapshotTs();
  var inProgressVersions = opsSnapshot.inProgressTxs();

  return stream
      .mapMulti((pair, downstream) -> {
        var visibleRid = checkVisibility(
            pair.first(), pair.second(), snapshotTs, inProgressVersions);
        if (visibleRid != null) {
          downstream.accept(visibleRid);
        }
      });
}
```

Then update `BTreeMultiValueIndexEngine.get()` to use it:
```java
return indexesSnapshot.visibilityFilterValues(atomicOperation, stream);
```

instead of:
```java
return indexesSnapshot.visibilityFilter(atomicOperation, stream)
    .map(RawPair::second);
```

Note: If Fix 02 (getVisibleStream) is implemented, this fix becomes unnecessary for
`get()` since `get()` would use `getVisibleStream()` instead. However, this method
is still useful for any other caller that needs only RIDs from a visibility-filtered
stream.

## Implementation Steps

1. Add `visibilityFilterValues()` to `IndexesSnapshot`.
2. Update `BTreeMultiValueIndexEngine.get()` to use it (both non-null and null paths).
3. Run `./mvnw -pl core spotless:apply`
4. Run `./mvnw -pl core clean test`
5. Commit: `YTDB-523: Add visibilityFilterValues() to avoid RawPair allocation in get()`

## Severity

Suggestion (performance). One allocation per visible entry on multi-value get() path.
If Fix 02 is done first, this becomes lower priority.
