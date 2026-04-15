# Index BTree Tombstone GC During Leaf Bucket Overflow — Final Design

## Overview

Tombstone garbage collection was added to `BTree` (the shared B-tree
implementation used by both `BTreeSingleValueIndexEngine` and
`BTreeMultiValueIndexEngine`), triggered when a leaf bucket overflows during
`put()`. When a bucket is full, the GC filters out removable `TombstoneRID`
entries and demotes stale `SnapshotMarkerRID` entries, rebuilds the bucket
with survivors only, and retries the insert. A split occurs only if the
bucket is still full after filtering.

The implementation follows the filter-rebuild-retry pattern established by
the edge tombstone GC in `SharedLinkBagBTree`, with two key differences:
(1) tombstones are removed unconditionally below LWM (no snapshot entry
check needed), and (2) `SnapshotMarkerRID` entries are demoted to plain
`RecordId` rather than removed.

No deviations from the original design plan were needed — the
implementation matches the planned architecture.

## Class Design

```mermaid
classDiagram
    class BTree~K~ {
        <<final>>
        -long fileId
        -BinarySerializer~K~ keySerializer
        -BinarySerializerFactory serializerFactory
        +put(AtomicOperation, K, RID) boolean
        -update(AtomicOperation, K, RID, IndexEngineValidator) int
        -splitBucket(...) UpdateBucketSearchResult
        -filterAndRebuildBucket(CellBTreeSingleValueBucketV3~K~) int
        -demoteMarkerRawBytes(byte[]) byte[]$
        -updateSize(long, AtomicOperation) void
    }

    class StorageComponent {
        <<abstract>>
        #storage: AbstractStorage
        +getName() String
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
        -stateLock: ReentrantReadWriteLock
        +computeGlobalLowWaterMark() long
        +hasActiveIndexSnapshotEntries(String, CompositeKey, long) boolean
        +getIndexSnapshotByEngineName(String) IndexesSnapshot
        +getNullIndexSnapshotByEngineName(String) IndexesSnapshot
    }

    class RID {
        <<interface>>
        +getCollectionId() int
        +getCollectionPosition() long
        +getIdentity() RID
    }

    class TombstoneRID {
        <<final>>
        -collectionId: int
        -collectionPosition: long
        -identity: RecordId
    }

    class SnapshotMarkerRID {
        <<final>>
        -collectionId: int
        -collectionPosition: long
        -identity: RecordId
    }

    StorageComponent <|-- BTree
    RID <|.. TombstoneRID
    RID <|.. SnapshotMarkerRID
    BTree --> CellBTreeSingleValueBucketV3 : reads/rebuilds leaf pages
    BTree --> AbstractStorage : computes LWM, queries snapshot
    CellBTreeSingleValueBucketV3 ..> TombstoneRID : decodes from raw bytes
    CellBTreeSingleValueBucketV3 ..> SnapshotMarkerRID : decodes from raw bytes
```

`BTree<K>` extends `StorageComponent` (which provides the `storage`
reference to `AbstractStorage`) and implements `CellBTreeSingleValue<K>`.
Two private methods were added: `filterAndRebuildBucket()` performs the
GC scan and rebuild, `demoteMarkerRawBytes()` rewrites SnapshotMarkerRID
encoding in raw bytes. Both are contained entirely within `BTree`.

`AbstractStorage` gained three new public methods:
`hasActiveIndexSnapshotEntries()` queries the `ConcurrentSkipListMap` for
active snapshot entries by engine name and user-key prefix;
`getIndexSnapshotByEngineName()` and `getNullIndexSnapshotByEngineName()`
resolve scoped snapshots for test infrastructure. All three guard
`indexEngineNameMap` access with `stateLock.readLock()`.

`TombstoneRID` and `SnapshotMarkerRID` are final classes implementing
`RID`, storing primitive fields directly (avoiding intermediate `RecordId`
allocation on the hot decode path). `TombstoneRID` encodes collectionId
as `-(id + 1)`; `SnapshotMarkerRID` encodes collectionPosition as
`-(pos + 1)`. `CellBTreeSingleValueBucketV3.decodeRID()` distinguishes
the three types by sign.

## Workflow

### Filter-Rebuild-Retry in put()

```mermaid
sequenceDiagram
    participant Put as BTree.update()<br/>insert loop
    participant Bucket as CellBTreeSingleValue<br/>BucketV3 (leaf)
    participant Filter as filterAndRebuild<br/>Bucket()
    participant Storage as AbstractStorage
    participant Split as splitBucket()

    Put->>Bucket: addLeafEntry() → false (overflow)

    alt gcAttempted == false
        Put->>Filter: filterAndRebuildBucket(bucket)
        Filter->>Storage: computeGlobalLowWaterMark()
        Storage-->>Filter: lwm

        loop each entry i in bucket
            Filter->>Bucket: getValue(i) — check type
            alt TombstoneRID or SnapshotMarkerRID
                Filter->>Bucket: getKey(i) — extract version
                alt TombstoneRID && version < lwm
                    Note over Filter: discard (GC'd)
                else SnapshotMarkerRID && version < lwm
                    Filter->>Storage: hasActiveIndexSnapshotEntries()
                    alt no active entries
                        Note over Filter: demote via demoteMarkerRawBytes()
                    else has active entries
                        Note over Filter: keep as-is
                    end
                else version >= lwm
                    Note over Filter: keep as-is
                end
            else plain RecordId
                Note over Filter: keep (skip key deserialization)
            end
        end

        alt removedCount > 0
            Filter->>Bucket: shrink(0) + addAll(survivors)
            Filter-->>Put: removedCount
            Put->>Put: updateSize(-removedCount)
            Put->>Put: re-derive insertionIndex via find()
            Put->>Bucket: addLeafEntry() — retry
            alt succeeds
                Note over Put: done, no split needed
            else still overflows
                Put->>Split: splitBucket()
            end
        else removedCount == 0 but demoted
            Filter->>Bucket: shrink(0) + addAll(survivors)
            Filter-->>Put: 0
            Put->>Split: splitBucket()
        else no changes
            Filter-->>Put: 0 (no rebuild)
            Put->>Split: splitBucket()
        end
    else gcAttempted == true
        Put->>Split: splitBucket()
    end
```

The `update()` method's `while (!addLeafEntry(...))` loop handles bucket
overflow. GC is inserted as a first-attempt optimization before splitting.
The `gcAttempted` boolean ensures filtering runs at most once per `put()`
call. An important optimization (T5): plain `RecordId` entries skip key
deserialization entirely — only `TombstoneRID` and `SnapshotMarkerRID`
entries need the key to extract the version.

### Tombstone Eligibility Flowchart

```mermaid
flowchart TD
    A[Entry from bucket] --> B{value instanceof<br/>TombstoneRID or<br/>SnapshotMarkerRID?}
    B -->|no| KEEP[Keep as-is<br/>skip key deser]
    B -->|yes| C{key instanceof<br/>CompositeKey?}
    C -->|no| KEEP2[Keep as-is]
    C -->|yes| D{version < lwm?}
    D -->|no| KEEP3[Keep as-is]
    D -->|yes| E{TombstoneRID?}
    E -->|yes| REMOVE[Remove — GC'd]
    E -->|no| F["hasActiveIndexSnapshotEntries()<br/>for user-key prefix?"]
    F -->|yes| KEEP4[Keep as-is]
    F -->|no| DEMOTE[Demote to RecordId<br/>via demoteMarkerRawBytes]
```

Three outcomes for each entry:
1. **Remove** — TombstoneRID below LWM is discarded. Tree size decremented.
2. **Demote** — SnapshotMarkerRID below LWM with no active snapshot entries
   has its raw bytes rewritten to a plain RecordId. Tree size unchanged.
3. **Keep** — all other entries preserved as-is.

## SnapshotMarkerRID Demotion Encoding

`SnapshotMarkerRID` encodes `collectionPosition` as `-(realPos + 1)`.
Demotion rewrites the last 8 bytes of the raw leaf entry (the
`collectionPosition` field) back to the real positive value using
`LongSerializer` read-modify-write. The `collectionId` is unchanged
(always positive for `SnapshotMarkerRID`).

Raw leaf entry layout: `[serialized_key | 2-byte collectionId | 8-byte
collectionPosition]`.

The `demoteMarkerRawBytes()` method modifies the byte array in-place and
returns it. The caller passes the same array to `addAll()` during bucket
rebuild.

**Gotcha**: The demotion check requires a `subMap()` range query on the
`ConcurrentSkipListMap` for each `SnapshotMarkerRID` candidate. This is
O(log S) per marker, acceptable during overflow handling since markers
are typically a small fraction of bucket entries.

## Snapshot Query for Demotion Safety

Before demoting a `SnapshotMarkerRID`,
`AbstractStorage.hasActiveIndexSnapshotEntries()` checks whether any
snapshot entries with `version >= LWM` exist for the same user-key prefix.

The method:
1. Resolves the `$null` suffix — if the engine name ends with `$null`,
   strips it and uses `sharedNullIndexesSnapshot`; otherwise uses
   `sharedIndexesSnapshot`.
2. Looks up the engine by resolved name in `indexEngineNameMap` under
   `stateLock.readLock()` to avoid racing with concurrent index
   creation/deletion.
3. Constructs range keys: `CompositeKey(indexId, userKeyPrefix..., lwm)` to
   `CompositeKey(indexId, userKeyPrefix..., Long.MAX_VALUE)`.
4. Queries via `subMap(lower, true, upper, true).isEmpty()`.

**Gotcha**: `indexEngineNameMap` is a plain `HashMap`, not a concurrent
map. All access must be guarded by `stateLock`. The existing codebase
already follows this pattern; the new methods were aligned to match.

## Tree Size Accounting

Tree size is tracked in the B-tree entry point page. `updateSize()` is
called with `-removedCount` immediately after GC succeeds. The subsequent
insert's size change is handled by the existing `updateSize(sizeDiff)` at
the end of `update()`.

Partition invariant: `removedCount + survivors.size() == bucketSize` —
enforced by an assertion after the scan loop.

## Performance Characteristics

| Operation | Cost | Notes |
|---|---|---|
| LWM computation | O(T), T = active threads | Once per GC attempt |
| Entry iteration | O(N), N = bucket entries | Value deserialization for all; key deserialization only for tombstones/markers |
| Snapshot query | O(log S) per marker | S = snapshot index size. Only for SnapshotMarkerRID below LWM |
| Bucket rebuild | O(N), N = survivors | shrink(0) + addAll(). Only when changes found |
| Overall | O(N + M log S) | M = markers below LWM, typically small |

When no tombstones or markers exist in the bucket, the only overhead is
the `instanceof` check on each entry's value — key deserialization is
skipped entirely. When tombstones exist but no markers do, there are zero
snapshot queries.
