# Index BTree Tombstone GC During Leaf Bucket Overflow — Design

## Overview

The design adds tombstone garbage collection to `BTree` (the shared B-tree
implementation used by both `BTreeSingleValueIndexEngine` and
`BTreeMultiValueIndexEngine`), triggered when a leaf bucket overflows during
`put()`. When a bucket is full, the GC filters out removable `TombstoneRID`
entries and demotes stale `SnapshotMarkerRID` entries, rebuilds the bucket
with survivors only, and retries the insert. A split occurs only if the
bucket is still full after filtering.

The approach mirrors the edge tombstone GC in `SharedLinkBagBTree` but with
two key differences: (1) tombstones are removed unconditionally below LWM
(no snapshot entry check needed), and (2) `SnapshotMarkerRID` entries are
demoted to plain `RecordId` rather than removed.

## Class Design

```mermaid
classDiagram
    class BTree~K~ {
        -long fileId
        -BinarySerializer~K~ keySerializer
        -BinarySerializerFactory serializerFactory
        -AbstractStorage storage
        +put(AtomicOperation, K, RID) boolean
        -update(AtomicOperation, K, RID, IndexEngineValidator) int
        -splitBucket(...) UpdateBucketSearchResult
        -filterAndRebuildBucket(CellBTreeSingleValueBucketV3) int
        -demoteMarkerRawBytes(byte[]) byte[]$
        -updateSize(long, AtomicOperation) void
    }

    class CellBTreeSingleValueBucketV3~K~ {
        +addLeafEntry(int, byte[], byte[]) boolean
        +size() int
        +isLeaf() boolean
        +getKey(int, BinarySerializer, BinarySerializerFactory) K
        +getValue(int, BinarySerializer, BinarySerializerFactory) RID
        +getRawEntry(int, BinarySerializer, BinarySerializerFactory) byte[]
        +find(byte[], BinarySerializer, BinarySerializerFactory) int
        +shrink(int, BinarySerializer, BinarySerializerFactory) void
        +addAll(List~byte[]~, BinarySerializer) void
        +decodeRID(int, long) RID$
    }

    class AbstractStorage {
        -sharedIndexesSnapshot: ConcurrentSkipListMap
        -sharedNullIndexesSnapshot: ConcurrentSkipListMap
        -indexEngineNameMap: Map~String, BaseIndexEngine~
        +computeGlobalLowWaterMark() long
        +hasActiveIndexSnapshotEntries(String, CompositeKey, long) boolean
    }

    class TombstoneRID {
        -collectionId: int
        -collectionPosition: long
        -identity: RecordId
        +getCollectionId() int
        +getCollectionPosition() long
        +getIdentity() RID
    }

    class SnapshotMarkerRID {
        -collectionId: int
        -collectionPosition: long
        -identity: RecordId
        +getCollectionId() int
        +getCollectionPosition() long
        +getIdentity() RID
    }

    class CompositeKey {
        -keys: List~Object~
        +getKeys() List~Object~
        +addKey(Object) void
        +addKeyDirect(Object) void
    }

    BTree --> CellBTreeSingleValueBucketV3 : reads/rebuilds leaf pages
    BTree --> AbstractStorage : computes LWM, queries snapshot
    CellBTreeSingleValueBucketV3 ..> TombstoneRID : decodes from raw bytes
    CellBTreeSingleValueBucketV3 ..> SnapshotMarkerRID : decodes from raw bytes
    BTree ..> CompositeKey : extracts version from key
```

**`BTree`** is the only class with new methods. Two private methods are added:
`filterAndRebuildBucket()` (iterates bucket entries, filters tombstones,
demotes markers, rebuilds) and `demoteMarkerRawBytes()` (rewrites the
`SnapshotMarkerRID` encoding in raw bytes). One call site is modified: the
`while (!addLeafEntry(...))` loop in `update()` (called by `put()`).

**`AbstractStorage`** gains one new public method:
`hasActiveIndexSnapshotEntries()` — resolves an engine by name (handling
the `$null` suffix for null-key trees), constructs range keys from the
user-key prefix and LWM, and queries the shared `ConcurrentSkipListMap` via
`subMap()`. Returns `true` if any snapshot entries exist with
`version >= LWM` for the user-key prefix.

**`CellBTreeSingleValueBucketV3`** is unchanged. **`TombstoneRID`** and
**`SnapshotMarkerRID`** were refactored from records to final classes with
primitive fields (avoiding intermediate `RecordId` allocation on the hot
decode path), but their on-disk encoding is unchanged — `instanceof` checks
and `demoteMarkerRawBytes()` byte-level rewriting remain valid.
**`CompositeKey`** gained `addKeyDirect()` (bypasses unmodifiable-view cache
invalidation) but `addKey()` is still the public API used by the GC.

## Workflow

### Filter-Rebuild-Retry in put()

```mermaid
sequenceDiagram
    participant Put as BTree.update()<br/>insert loop
    participant Bucket as CellBTreeSingleValue<br/>BucketV3 (leaf)
    participant Filter as filterAndRebuild<br/>Bucket()
    participant Storage as AbstractStorage
    participant Split as splitBucket()

    Put->>Bucket: addLeafEntry() -> false (overflow)

    alt gcAttempted == false
        Put->>Filter: filterAndRebuildBucket(bucket)
        Filter->>Storage: computeGlobalLowWaterMark()
        Storage-->>Filter: lwm

        loop each entry i in bucket
            Filter->>Bucket: getKey(i), getValue(i)
            alt TombstoneRID && version < lwm
                Note over Filter: discard (GC'd)
            else SnapshotMarkerRID && version < lwm
                Filter->>Storage: hasActiveIndexSnapshotEntries(name, prefix, lwm)
                alt no active entries
                    Note over Filter: demote to RecordId
                else has active entries
                    Note over Filter: keep as-is
                end
            else live or version >= lwm
                Note over Filter: keep as-is
            end
        end

        alt removedCount > 0
            Filter->>Bucket: shrink(0) + addAll(survivors)
            Filter-->>Put: removedCount
            Put->>Put: updateSize(-removedCount)
            Put->>Put: re-derive insertionIndex via find()
            Put->>Bucket: addLeafEntry() - retry
            alt succeeds
                Note over Put: done, no split needed
            else still overflows
                Put->>Split: splitBucket()
            end
        else removedCount == 0 but demoted
            Note over Filter: bucket rebuilt with demoted<br/>markers but no space freed
            Filter->>Bucket: shrink(0) + addAll(survivors)
            Filter-->>Put: 0
            Put->>Split: splitBucket()
        else removedCount == 0 and no demotion
            Filter-->>Put: 0 (no rebuild)
            Put->>Split: splitBucket()
        end
    else gcAttempted == true
        Put->>Split: splitBucket()
    end
```

The `update()` method (called by `put()`) already contains a
`while (!addLeafEntry(...))` loop that handles bucket overflow by splitting.
The GC is inserted as a first-attempt optimization before splitting. The
`gcAttempted` boolean ensures filtering runs at most once per `put()` call,
preventing repeated scans.

### Tombstone Eligibility Flowchart

```mermaid
flowchart TD
    A[Entry from bucket] --> B{key instanceof CompositeKey?}
    B -->|no| KEEP[Keep entry as-is]
    B -->|yes| C{value instanceof TombstoneRID?}
    C -->|yes| D{version < lwm?}
    D -->|no| KEEP
    D -->|yes| REMOVE[Remove - GC'd]
    C -->|no| E{value instanceof SnapshotMarkerRID?}
    E -->|no| KEEP
    E -->|yes| F{version < lwm?}
    F -->|no| KEEP
    F -->|yes| G["hasActiveIndexSnapshotEntries()\nfor user-key prefix?"]
    G -->|yes| KEEP
    G -->|no| DEMOTE[Demote to RecordId]
```

Three outcomes for each entry:
1. **Remove** — TombstoneRID below LWM is discarded entirely. The tree
   size is decremented.
2. **Demote** — SnapshotMarkerRID below LWM with no active snapshot
   entries is rewritten to a plain RecordId in the raw bytes. The tree
   size is unchanged (entry is preserved, just re-typed).
3. **Keep** — all other entries are preserved as-is.

### Engine-Level Call Chain

```mermaid
sequenceDiagram
    participant Engine as BTreeSingleValue<br/>IndexEngine
    participant BTree as BTree
    participant GC as filterAndRebuild<br/>Bucket()

    Note over Engine: put(key, value)
    Engine->>BTree: put(atomicOp, compositeKey, value)
    BTree->>BTree: addLeafEntry() -> overflow
    BTree->>GC: filterAndRebuildBucket()
    GC-->>BTree: removedCount
    BTree->>BTree: retry addLeafEntry()

    Note over Engine: remove(key)
    Engine->>BTree: remove(atomicOp, oldKey)
    Note over BTree: physical deletion (no overflow)
    Engine->>BTree: put(atomicOp, newKey, TombstoneRID)
    BTree->>BTree: addLeafEntry() -> may overflow
    BTree->>GC: filterAndRebuildBucket()
    GC-->>BTree: removedCount
```

Both the engine-level `put()` and `remove()` paths flow through
`BTree.put()`. The engine's `remove()` first does a physical deletion
(`BTree.remove()`), then inserts a tombstone entry (`BTree.put()`) — the
tombstone insertion can trigger overflow and thus GC. This means GC in
`BTree.put()` covers all scenarios.

## SnapshotMarkerRID Demotion Encoding

`SnapshotMarkerRID` uses a negative `collectionPosition` encoding:
`-(realPosition + 1)`. A plain `RecordId` uses the real positive value.
Demotion rewrites the last 8 bytes of the raw entry (the `collectionPosition`
field) from the negative encoding back to the real value.

Raw leaf entry layout: `[serialized_key | 2-byte collectionId | 8-byte
collectionPosition]`.

For `SnapshotMarkerRID`:
```
collectionPosition = -(realPosition + 1)
```

After demotion:
```
collectionPosition = realPosition
```

The `collectionId` remains unchanged (it is always positive for
`SnapshotMarkerRID`). This is a single `LongSerializer` read-modify-write
on the raw byte array — no deserialization/reserialization of the full entry
is needed.

**Gotcha**: The demotion modifies the byte array in-place and returns it.
The caller must ensure the byte array is the one that will be passed to
`addAll()` during bucket rebuild.

## Snapshot Query for SnapshotMarkerRID Safety

Before demoting a `SnapshotMarkerRID`, the GC must verify that no active
snapshot entries exist for the same user-key prefix with `version >= LWM`.
This prevents a scenario where:

1. A `SnapshotMarkerRID` M is demoted to `RecordId`.
2. A concurrent reader using the snapshot index sees an older version of
   the same key (stored in `IndexesSnapshot`).
3. The reader's visibility logic misinterprets the demoted entry because
   the marker flag is gone.

The check queries `AbstractStorage.hasActiveIndexSnapshotEntries()`, which:
1. Resolves the engine by name from `indexEngineNameMap` (handles `$null`
   suffix for null-key trees in multi-value indexes).
2. Constructs range keys: `CompositeKey(indexId, userKeyPrefix..., lwm)` to
   `CompositeKey(indexId, userKeyPrefix..., Long.MAX_VALUE)`.
3. Queries the shared `ConcurrentSkipListMap` via
   `subMap(lower, true, upper, true)` (inclusive on both bounds).
4. Returns `true` if any entries exist in the range.

The `indexId` used in range key construction is resolved internally from
the `engineName` parameter via `indexEngineNameMap`.

**Gotcha**: The user-key prefix is extracted by stripping the last element
(version) from the `CompositeKey`. For single-value indexes, this is the
original user key. For multi-value indexes, this includes both the user key
and the RID (e.g., `CompositeKey(userKey, rid)` without the version).

## Tree Size Accounting

Tree size is tracked in the B-tree entry point page and must equal the
actual number of leaf entries. Size changes during GC-enabled insert:

| Scenario | Size change |
|---|---|
| New entry, no GC | +1 |
| New entry, GC removed N tombstones | -N (GC) then +1 (insert) |
| Replacement (SnapshotMarkerRID), no GC | 0 (remove old -1, insert new +1) |
| Replacement with GC | -N (GC) then 0 (replacement) |
| Demotion only (no removal) | 0 (entry preserved, just re-typed) |

`updateSize(-removedCount)` is called immediately after GC succeeds.
The final `updateSize(sizeDiff)` at the end of `update()` handles the
insert itself.

Note: Rows without "GC" in the scenario name document existing behavior
for context — no changes are needed for those paths.

## Performance Characteristics

| Operation | Cost | Notes |
|---|---|---|
| LWM computation | O(T), T = active threads | Once per GC attempt. Iterates `TsMinHolder` instances, typically < 100. |
| Entry iteration | O(N), N = bucket entries | Key + value deserialization per entry. N bounded by bucket capacity (~hundreds for 8 KB pages). |
| Snapshot query | O(log S) per marker | S = snapshot index size. Only for `SnapshotMarkerRID` entries passing the LWM check. Tombstones require no snapshot query. |
| Bucket rebuild | O(N), N = survivors | `shrink(0)` + `addAll()`. Only when removedCount > 0 or demotion occurred. |
| Overall per GC attempt | O(N + M log S), M = markers | M is typically a small fraction of N. Tombstones are filtered cheaply by `instanceof` check. |

The GC adds no cost when there are no tombstones or markers in the bucket
(the `instanceof` check on each entry is the only overhead). When tombstones
exist but no markers do, there are zero snapshot queries — removal is
unconditional.
