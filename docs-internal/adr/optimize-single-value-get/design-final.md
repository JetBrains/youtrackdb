# Optimize BTreeSingleValueIndexEngine.get() — Final Design

## Overview

Replaced the `BTreeSingleValueIndexEngine.get(key)` stream pipeline
(`iterateEntriesBetween` + `visibilityFilter` + `map`) with a direct leaf-page
lookup via `BTree.getVisible()`. The optimization eliminates ~14 allocations per
call and removes shared-lock acquisition on the hot path, restoring the pre-SI
`get()` cost profile with lock-free optimistic reads.

Key deviations from the original design:

- **Search key construction** (D3 adaptation): raw prefix key serialization
  fails because `IndexMultiValuKeySerializer` requires the full element count.
  Instead, `buildSearchKey()` pads with `Long.MIN_VALUE` as the version
  component, which eliminates the leftward scan entirely — `bucket.find()`
  returns the insertion point at the first matching entry directly.
- **Null key handling**: null keys use the same `getVisible()` path as non-null
  keys. The original design anticipated this, and it works because
  `buildSearchKey()` only pads the version slot (always `LONG` type), so null
  user-key elements are not affected. A partial-key guard in `getVisible()`
  rejects composite-index calls where the key has fewer user elements than
  expected (no "null key" concept for composite indexes).
- **Dead code removal**: `emitSnapshotVisibility()` was removed after
  `visibilityFilterMapped()` was refactored to delegate to `checkVisibility()`.
  `snapshotUserKeyMatches()` was extracted from `lookupSnapshotRid()` for
  clarity.

## Class Design

```mermaid
classDiagram
    class CellBTreeSingleValue~K~ {
        <<interface>>
        +get(K key, AtomicOperation op) RID
        +getVisible(K key, IndexesSnapshot snapshot, AtomicOperation op) RID
        +iterateEntriesBetween(...) Stream
    }

    class BTree~K~ {
        -fileId: long
        -keySerializer: BinarySerializer
        -keySize: int
        +get(K key, AtomicOperation op) RID
        +getVisible(K key, IndexesSnapshot snapshot, AtomicOperation op) RID
        -getVisibleOptimistic(...) RID
        -getVisiblePinned(...) RID
        -scanLeafForVisible(...) RID
        -buildSearchKey(K prefixKey) K
        -userKeyPrefixMatches(List, int, CompositeKey) boolean
    }

    class BTreeSingleValueIndexEngine {
        -sbTree: CellBTreeSingleValue
        -indexesSnapshot: IndexesSnapshot
        +get(Object key, AtomicOperation op) Stream~RID~
    }

    class IndexesSnapshot {
        -indexesSnapshot: NavigableMap
        +checkVisibility(CompositeKey, RID, long, LongOpenHashSet) RID
        +visibilityFilterMapped(AtomicOperation, Stream, Function) Stream
        -lookupSnapshotRid(CompositeKey, long) RID
        -snapshotUserKeyMatches(List, List) boolean
    }

    class CellBTreeSingleValueBucketV3~K~ {
        +find(byte[] key, ...) int
        +getEntry(int index, ...) Entry
        +size() int
        +getRightSibling() long
    }

    CellBTreeSingleValue <|.. BTree
    BTreeSingleValueIndexEngine --> CellBTreeSingleValue : sbTree
    BTreeSingleValueIndexEngine --> IndexesSnapshot : indexesSnapshot
    BTree --> CellBTreeSingleValueBucketV3 : reads leaf pages
    BTree ..> IndexesSnapshot : param in getVisible
```

**BTree\<K\>** implements `getVisible()` with a two-path pattern: an optimistic
lock-free happy path (`getVisibleOptimistic`) and a pinned shared-lock fallback
(`getVisiblePinned`). Both delegate to `scanLeafForVisible()` for the forward
entry scan with inline visibility checking. Helper methods `buildSearchKey()`
(pads user key with `Long.MIN_VALUE` version) and `userKeyPrefixMatches()`
(compares all elements except version) support the scan logic.

**IndexesSnapshot** provides `checkVisibility()` as the single source of truth
for visibility decisions. Both `visibilityFilterMapped()` (stream path for range
scans) and `scanLeafForVisible()` (direct path for point lookups) delegate to
it. The `lookupSnapshotRid()` helper handles historical version lookup in the
snapshot index, with `snapshotUserKeyMatches()` guarding against cross-key
contamination from the non-atomic write order in `addSnapshotPair()`.

**BTreeSingleValueIndexEngine** simplifies `get()` to: convert key →
`sbTree.getVisible()` → `Stream.of(rid)` or `Stream.empty()`.

## Workflow

### Engine `get()` Flow

```mermaid
sequenceDiagram
    participant E as Engine.get()
    participant T as BTree.getVisible()
    participant B as BucketV3 (leaf)
    participant Snap as IndexesSnapshot

    E->>E: convertToCompositeKey(key)
    E->>T: getVisible(compositeKey, snapshot, op)
    T->>T: buildSearchKey() — pad with Long.MIN_VALUE
    T->>T: preprocess + serialize key
    Note over T: executeOptimisticStorageRead (lock-free)
    T->>B: descend tree to leaf (same as getOptimistic)
    T->>B: find(serializedKey) → foundIndex
    alt foundIndex < 0 (no match in leaf)
        T-->>E: return null
    end
    loop scan forward from foundIndex while prefix matches
        T->>B: getEntry(index)
        T->>T: userKeyPrefixMatches()?
        alt prefix mismatch
            T-->>E: return null (past range)
        end
        T->>Snap: checkVisibility(key, rid, visibleVersion, inProgress)
        alt visible RID returned
            T-->>E: return RID (short-circuit)
        else null (not visible)
            T->>T: continue to next entry
        end
    end
    T-->>E: return null (no visible entry)
    E->>E: rid != null ? Stream.of(rid) : Stream.empty()
```

The flow eliminates: `SpliteratorForward`, `ArrayList` dataCache,
`ReferencePipeline`, `mapMulti` stage, `map` stage, lambda captures, shared-lock
acquisition, and the 2 enhanced `CompositeKey` allocations from
`enhanceFromKey`/`enhanceToKey`. The tree descent and leaf page read are
identical to the pre-SI `getOptimistic()` path.

### Optimistic/Pinned Fallback

```mermaid
flowchart TD
    GV["getVisible()"]
    OPT["getVisibleOptimistic()"]
    SCAN_O["scanLeafForVisible(optimistic=true)"]
    PIN["getVisiblePinned()"]
    SCAN_P["scanLeafForVisible(optimistic=false)"]
    SIB["follow getRightSibling()"]

    GV -->|executeOptimisticStorageRead| OPT
    OPT --> SCAN_O
    SCAN_O -->|OptimisticReadFailedException| PIN
    OPT -->|OptimisticReadFailedException| PIN
    PIN --> SCAN_P
    SCAN_P -->|entries exhausted, sibling exists| SIB
    SIB --> SCAN_P
```

The optimistic path descends the tree and scans the leaf without acquiring locks.
If any page is evicted or modified concurrently (`OptimisticReadFailedException`),
the pinned path retries with a shared lock. The pinned path also handles
cross-page entries via `getRightSibling()`, which the optimistic path cannot
safely follow (it throws `OptimisticReadFailedException` to trigger the
fallback).

## Optimistic Read Scope for Leaf Scan

The pre-SI `getOptimistic()` reads a single entry and returns. The new
`getVisibleOptimistic()` scans multiple versioned entries within the leaf.

- **Single-page scan (common case):** All versions of a unique-index key fit on
  one leaf page. The optimistic scope covers tree descent + entire leaf scan,
  validated implicitly by successful return.
- **Cross-page scan (rare):** If the scan exhausts the leaf page without finding
  a visible entry, `scanLeafForVisible()` throws
  `OptimisticReadFailedException`, forcing fallback to the pinned path which
  follows `getRightSibling()` safely under shared lock.
- **IndexesSnapshot lookups** (`checkVisibility` → `lookupSnapshotRid` →
  `ConcurrentSkipListMap.lowerEntry()`) are pure in-memory operations outside
  the page scope — safe under either path.

## Visibility Logic — Single Source of Truth

`IndexesSnapshot.checkVisibility(CompositeKey key, RID rid, long visibleVersion,
LongOpenHashSet inProgressVersions)` returns `@Nullable RID`:

1. **In-progress check:** `inProgressVersions.contains(version)` →
   `TombstoneRID`/`SnapshotMarkerRID` delegates to `lookupSnapshotRid()`;
   plain `RecordId` returns null (pending insert, not yet visible).
2. **Committed check:** `version < visibleVersion` → `RecordId` returns as-is;
   `SnapshotMarkerRID` returns `rid.getIdentity()`; `TombstoneRID` returns null.
3. **Phantom check:** `version >= visibleVersion` with `RecordId` → null.
4. **Snapshot fallback:** `TombstoneRID`/`SnapshotMarkerRID` with
   `version >= visibleVersion` → `lookupSnapshotRid()`.

Both callers use it identically:
- `visibilityFilterMapped()`: calls per entry in `mapMulti`, emits non-null
  results via `downstream.accept()`
- `scanLeafForVisible()`: calls per entry in a plain loop, returns immediately
  on non-null (short-circuit)

## Search Key Construction

`buildSearchKey()` creates `CompositeKey(userKey..., Long.MIN_VALUE)` from the
user-facing prefix key. This approach replaced the original plan's raw prefix key
(D3 option 2) because `IndexMultiValuKeySerializer.serialize()` iterates up to
`types.length` elements — a shorter key causes `IndexOutOfBoundsException`.

`Long.MIN_VALUE` sorts before any real version number, so `bucket.find()` returns
the insertion point at or before the first versioned entry for the given user key.
This eliminates the leftward scan from the original design — the forward scan
starts at exactly the right position.

## Null Key Handling

Null keys (`CompositeKey(null, version)`) are handled uniformly by
`getVisible()`. `buildSearchKey()` pads only the version slot (always `LONG`
type), so null user-key elements pass through preprocessing and serialization
unchanged. The `userKeyPrefixMatches()` check correctly matches null elements
via `DefaultComparator`.

For composite indexes (multi-field keys), a partial-key guard at the top of
`getVisible()` returns null immediately if the key has fewer user elements than
`keySize - 1`. This prevents `CompositeKey(null)` from being misinterpreted as
a valid single-null-field lookup in a multi-field index.
