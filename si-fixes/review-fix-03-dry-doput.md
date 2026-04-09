# Fix 03: Extract shared versioned-put logic from both engines

## Problem

`BTreeSingleValueIndexEngine.doPutSingleValue()` (lines 366-412) and
`BTreeMultiValueIndexEngine.doPut()` (lines 384-427) are structurally identical:
same versioning, same SnapshotMarkerRID wrapping, same snapshot pair creation,
same TombstoneRID/RecordId/SnapshotMarkerRID branching, same count delta accumulation.

Similarly, `BTreeSingleValueIndexEngine.remove()` (lines 165-209) and
`BTreeMultiValueIndexEngine.doRemove()` (lines 237-270) share the same core logic.

~80+ lines of duplication that must stay synchronized during future changes.

## Affected Files

- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java`
  - `doPutSingleValue()` lines 366-412
  - `remove()` lines 165-209
- `core/src/main/java/com/jetbrains/youtrackdb/internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java`
  - `doPut()` lines 384-427
  - `doRemove()` lines 237-270

## Fix Strategy

Extract the shared logic into static helper methods. The differences between the two
engines' implementations are:

### For put:
- Single-value: takes `@Nullable Optional<RawPair<...>> prefetched` for validator reuse
- Multi-value: always scans; takes explicit `tree` and `snapshot` parameters

Both can be unified with a method that:
- Takes `tree`, `snapshot`, `atomicOperation`, `compositeKey`, `value`, `engineId`, `isNullKey`
- Takes `@Nullable Optional<RawPair<...>> prefetched` (multi-value passes null)
- Returns `boolean wasInserted`

### For remove:
- Single-value: operates on `sbTree` + `indexesSnapshot` directly
- Multi-value: takes explicit `tree` and `snapshot` parameters

Both can be unified with a method that:
- Takes `tree`, `snapshot`, `atomicOperation`, `compositeKey`, `value`, `engineId`, `isNullKey`
- Returns `boolean wasRemoved`

### Location

Create a package-private utility class `VersionedIndexOps` in
`com.jetbrains.youtrackdb.internal.core.index.engine.v1` with:

```java
static boolean doVersionedPut(
    CellBTreeSingleValue<CompositeKey> tree,
    IndexesSnapshot snapshot,
    AtomicOperation atomicOperation,
    CompositeKey compositeKey,
    RID value,
    int engineId,
    boolean isNullKey,
    @Nullable Optional<RawPair<CompositeKey, RID>> prefetched) throws IOException

static boolean doVersionedRemove(
    CellBTreeSingleValue<CompositeKey> tree,
    IndexesSnapshot snapshot,
    AtomicOperation atomicOperation,
    CompositeKey compositeKey,
    RID value,
    int engineId,
    boolean isNullKey) throws IOException
```

## Implementation Steps

1. Create `VersionedIndexOps.java` with the extracted static methods.
2. Refactor `BTreeSingleValueIndexEngine.doPutSingleValue()` to delegate to
   `VersionedIndexOps.doVersionedPut()`.
3. Refactor `BTreeMultiValueIndexEngine.doPut()` to delegate to
   `VersionedIndexOps.doVersionedPut()`.
4. Refactor `BTreeSingleValueIndexEngine.remove()` to delegate to
   `VersionedIndexOps.doVersionedRemove()`.
5. Refactor `BTreeMultiValueIndexEngine.doRemove()` to delegate to
   `VersionedIndexOps.doVersionedRemove()`.
6. Run `./mvnw -pl core spotless:apply`
7. Run `./mvnw -pl core clean test`
8. Commit: `YTDB-523: Extract shared versioned put/remove into VersionedIndexOps`

## Severity

Suggestion (DRY). Reduces duplication and ensures both engines stay synchronized.
