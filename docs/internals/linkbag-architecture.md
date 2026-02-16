# LinkBag Architecture: Edge Storage in YouTrackDB

## Overview

In YouTrackDB's graph model, edges between vertices are stored as collections of Record IDs (RIDs)
attached to vertex properties. Each vertex maintains outgoing edge references in properties named
`out_<edgeLabel>` and incoming edge references in properties named `in_<edgeLabel>`. The data
structure that holds these RID collections is called a **LinkBag** (also known historically as a
"RidBag").

A LinkBag is a **multiset** (bag) of RIDs -- it can contain duplicate entries of the same RID, with
each RID associated with an integer counter tracking the number of occurrences. This design enables
O(1) edge traversal because navigating from one vertex to its neighbors requires only reading the
LinkBag stored on the vertex rather than scanning a separate edge index.

The LinkBag subsystem consists of two storage strategies:

- **Embedded**: Stores link data inline within the owning vertex record. Suited for vertices with
  few edges.
- **BTree-based**: Stores link data in a separate on-disk BTree structure. Suited for vertices with
  many edges (high fan-out).

The system automatically converts between these representations based on configurable size
thresholds, providing transparent optimization: small collections stay inline for minimal overhead,
while large collections migrate to BTrees for efficient random access and iteration.

## Class Hierarchy and Responsibilities

```
LinkBag (facade)
  |
  +-- delegates to --> LinkBagDelegate (interface)
                          |
                          +-- AbstractLinkBag (abstract base)
                                |
                                +-- EmbeddedLinkBag
                                +-- BTreeBasedLinkBag
                                      |
                                      +-- uses --> LinkCollectionsBTreeManagerShared
                                                      |
                                                      +-- manages --> SharedLinkBagBTree (physical BTree)
                                                                        |
                                                                        +-- wrapped by --> IsolatedLinkBagBTreeImpl
```

The following sections document each class in detail.

---

## `LinkBag`

**Package**: `com.jetbrains.youtrackdb.internal.core.db.record.ridbag`

**Role**: Public facade for edge link collections.

### Purpose

`LinkBag` is the entry point that vertex entities use to manage their edge references. It delegates
all actual work to an internal `LinkBagDelegate` implementation (either `EmbeddedLinkBag` or
`BTreeBasedLinkBag`), and manages the automatic conversion between them.

### Interfaces Implemented

| Interface | Purpose |
|---|---|
| `Iterable<RID>` | Allows iteration over all contained RIDs |
| `Sizeable` | Provides `size()` and `isSizeable()` |
| `TrackedMultiValue<RID, RID>` | Enables change tracking for index maintenance and rollback |
| `DataContainer<RID>` | Marks the class as a container of RID data |
| `RecordElement` | Supports owner/dirty tracking within the record framework |
| `StorageBackedMultiValue` | Indicates this collection is backed by storage |

### Key Fields

| Field | Type | Description |
|---|---|---|
| `delegate` | `LinkBagDelegate` | Current implementation: `EmbeddedLinkBag` or `BTreeBasedLinkBag` |
| `topThreshold` | `int` | Size at which an embedded bag converts to BTree. Configured via `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD` |
| `bottomThreshold` | `int` | Size at which a BTree bag converts back to embedded. Configured via `LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD` |
| `session` | `DatabaseSessionEmbedded` | The database session that owns this bag |

### Construction

- `LinkBag(session)` -- Creates a new empty bag. If `topThreshold >= 0`, starts as
  `EmbeddedLinkBag`; otherwise starts as `BTreeBasedLinkBag`.
- `LinkBag(session, source)` -- Copy constructor. Iterates the source and adds all entries to a new
  bag.
- `LinkBag(session, delegate)` -- Wraps an existing delegate directly (used during deserialization).

### Automatic Threshold-Based Conversion

The method `checkAndConvert()` is called during entity serialization (save). It triggers conversion
when:

1. **Embedded to BTree**: `delegate.size() >= topThreshold` and a BTree collection manager is
   available.
2. **BTree to Embedded**: `delegate.size() <= bottomThreshold` and `bottomThreshold >= 0`.

Conversion preserves the change tracker state and transaction-modified flag, then calls
`requestDelete()` on the old delegate to clean up its resources (for BTree delegates, this schedules
a tree deletion within the transaction).

### Serialization Decision

`isToSerializeEmbedded()` determines how the bag should be serialized:

- Always embedded if the delegate is `EmbeddedLinkBag`.
- Always embedded if the owner record is not yet persistent (has no stable RID).
- Always embedded if the collection pointer is null or invalid.
- Otherwise, serialized as BTree-backed (only a `LinkBagPointer` is stored in the record).

### Owner Constraint

A `LinkBag` can only be owned by a non-embedded `EntityImpl` at root level. It cannot be nested
inside embedded entities. This is enforced in `setOwner()`.

### Equality

Two `LinkBag` instances are equal if they have the same delegate type and contain the same sequence
of RIDs in the same order.

---

## `AbstractLinkBag`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

**Role**: Abstract base class implementing shared logic for both `EmbeddedLinkBag` and
`BTreeBasedLinkBag`.

### Purpose

`AbstractLinkBag` provides the core add/remove/contains/iteration logic that is common to both
storage strategies. It manages three layers of data:

1. **`newEntries`** -- A `TreeMap<RID, int[]>` holding RIDs that are not yet persistent (temporary
   records that haven't been saved to storage). These entries are tracked separately because their
   identity (RID) may change when the record gets persisted.
2. **`localChanges`** -- A `BagChangesContainer` (sorted array of `(RID, Change)` pairs) that
   tracks modifications to persistent RIDs. Each entry maps a persistent RID to an `AbsoluteChange`
   recording the current counter value.
3. **BTree data** (BTree-based bags only) -- The persisted data in the on-disk BTree, accessible
   through the subclass's `getAbsoluteValue()` and `btreeSpliterator()` methods.

### Interfaces Implemented

| Interface | Purpose |
|---|---|
| `LinkBagDelegate` | Defines the operations that `LinkBag` delegates to |
| `IdentityChangeListener` | Receives notifications when a non-persistent RID changes identity |

### Key Fields

| Field | Type | Description |
|---|---|---|
| `localChanges` | `BagChangesContainer` | Sorted container of `(RID -> Change)` pairs for persistent RIDs |
| `newEntries` | `TreeMap<RID, int[]>` | Non-persistent RID entries. Value is a single-element `int[]` holding the counter |
| `newEntriesIdentityMap` | `IdentityHashMap<RID, int[]>` | Temporary holding area during identity change events |
| `size` | `int` | Total number of links (sum of all counters across all data layers) |
| `counterMaxValue` | `int` | Maximum allowed counter value per RID (prevents unbounded duplicates) |
| `tracker` | `SimpleMultiValueTracker<RID, RID>` | Records add/remove events for index maintenance and rollback |
| `newModificationsCount` | `long` | Monotonically increasing counter of changes to `newEntries` |
| `localChangesModificationsCount` | `long` | Monotonically increasing counter of changes to `localChanges` |

### Add Logic (`add(RID)`)

1. If the RID is **persistent**:
   - Look up the existing change in `localChanges`.
   - If no change exists, call `getAbsoluteValue(rid)` to read the current persisted value, create
     an `AbsoluteChange` with that value, increment it, and store in `localChanges`.
   - If a change exists, increment its counter (respecting `counterMaxValue`).
   - Increment `localChangesModificationsCount`.
2. If the RID is **not persistent**:
   - Refresh the RID via `session.refreshRid()` to get the latest reference.
   - Insert or update in `newEntries`. If the RID implements `ChangeableIdentity`, register this
     bag as an identity change listener.
   - Increment `newModificationsCount`.
3. If the counter was actually incremented (not capped), increment `size` and fire an add event.

### Remove Logic (`remove(RID)`)

1. Refresh non-persistent RIDs.
2. Try removing from `newEntries` first. If found and counter decremented, done.
3. Otherwise, look up in `localChanges`. If found, decrement; if not found and RID is persistent,
   read the absolute value and store `AbsoluteChange(absoluteValue - 1)`.
4. On successful removal, decrement `size` and fire a remove event.

### Contains Logic (`contains(RID)`)

Returns `true` if the RID exists in `newEntries` or if the effective counter (from `localChanges`
or `getAbsoluteValue()`) is greater than 0.

### Abstract Methods (Subclass Hooks)

| Method | Purpose |
|---|---|
| `getAbsoluteValue(RID)` | Returns the current effective counter for a persistent RID, considering both persisted BTree data and local changes |
| `btreeSpliterator()` | Returns a `Spliterator` over the on-disk BTree entries, or `null` if there is no BTree (embedded bags) |
| `createChangesContainer()` | Factory method for the `BagChangesContainer` implementation |

### Identity Change Handling

When a non-persistent record gets saved, its RID changes from a temporary value to a permanent one.
The `ChangeableIdentity` interface notifies listeners:

- **`onBeforeIdentityChange(source)`**: Removes the entry from the `TreeMap` (which is
  key-order-sensitive) and temporarily stores it in `newEntriesIdentityMap` (identity-based).
- **`onAfterIdentityChange(source)`**: Moves the entry back into the `TreeMap` under the new
  identity.

This ensures the `TreeMap`'s ordering invariant is maintained across identity changes.

### Iteration: `MergingSpliterator`

The `MergingSpliterator` is an inner class that produces a merged, sorted stream of all RIDs from
three sources:

1. **`newEntries`** -- Iterated first (non-persistent entries appear before persistent ones in the
   output).
2. **`localChanges`** -- Sorted by RID.
3. **BTree records** -- Sorted by RID (from `btreeSpliterator()`).

Sources (2) and (3) are merged in sorted order. When both `localChanges` and the BTree contain the
same RID, the `localChanges` value takes precedence (overwrites the BTree counter). Entries with
counter = 0 are skipped. Each RID is emitted as many times as its counter value indicates.

The spliterator handles concurrent modification gracefully: it tracks
`newModificationsCount` and `localChangesModificationsCount` to detect when the underlying data
has changed during iteration. When a modification is detected, the spliterator repositions itself
by creating a tail spliterator starting after the current position.

### Iteration: `EnhancedIterator`

An `Iterator<RID>` adapter built on top of `MergingSpliterator`. Supports `remove()` -- calling
`remove()` on the iterator delegates to `AbstractLinkBag.remove(currentRid)` and then notifies the
underlying spliterator so it can adjust the counter of the current entry if it was the one removed.

### Change Tracking

All add/remove operations fire events through the `SimpleMultiValueTracker`, which records a
`MultiValueChangeTimeLine`. This timeline serves two purposes:

- **Index maintenance**: The `ClassIndexManager` uses the timeline to determine what index entries
  need to be added or removed when an indexed multi-value property changes. It calls
  `returnOriginalState()` with the change events to reconstruct the pre-modification state of the
  collection, compares it with the current state, and applies the resulting diff to property indexes.
- **Change detection and rollback**: `isModified()` and `isTransactionModified()` check whether the
  timeline has any events. The `rollbackChanges()` method replays events in reverse to revert the
  bag to its state at the last callback boundary, keeping consistency between the entity and its
  property indexes.

---

## `EmbeddedLinkBag`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

**Role**: Stores link data inline within the owning vertex record.

### Purpose

`EmbeddedLinkBag` is the lightweight storage strategy for small link collections. All data lives
in `localChanges` -- there is no separate storage structure. When the bag is serialized, its content
is written directly into the owning entity's binary record.

### How It Works

- **No external storage**: `requestDelete()` is a no-op because there are no external resources.
- **`getAbsoluteValue(RID)`**: Returns the counter directly from `localChanges`. If the RID is not
  found, returns 0.
- **`btreeSpliterator()`**: Returns `null` -- there is no BTree.
- **`createChangesContainer()`**: Returns a new `ArrayBasedBagChangesContainer`.

### Construction

- `EmbeddedLinkBag(session, counterMaxValue)` -- New empty bag.
- `EmbeddedLinkBag(changes, session, size, counterMaxValue)` -- Deserialization constructor. Takes
  a pre-sorted list of `(RID, Change)` pairs and populates `localChanges` via `fillAllSorted()`.

### Serialization Format

When serialized into the owner record, the embedded bag is written as:

1. The total size (number of links).
2. For each entry: the RID and its counter value.

All data is inline; no pointer to external storage is needed.

### Original State Reconstruction and Rollback

Two methods support the change tracking lifecycle:

- **`returnOriginalState(transaction, changeEvents)`**: Reconstructs the state of the bag as it was
  before the given change events were applied. It creates a new `EmbeddedLinkBag`, copies all
  current entries, and replays the events in reverse (removing what was added, adding what was
  removed). This method does **not** modify the current bag. It is used by the **index system**
  (`ClassIndexManager`) to compute the diff between old and new values of an indexed property: the
  index manager calls `returnOriginalState()` to get the pre-modification collection, then compares
  it with the current collection to determine which index entries to add or remove. It is also used
  by `EntityEntry.getOnLoadValue()` to reconstruct the on-load state of a field for transaction
  tracking.

- **`rollbackChanges(transaction)`**: Reverts changes made to the bag since the last time the
  database session's entity callbacks (beforeCreate/beforeUpdate/afterCreate/afterUpdate) were
  invoked. Unlike `returnOriginalState()`, this method modifies the bag in-place. It is used to
  maintain consistency between the entity state and its property indexes when a callback boundary is
  crossed.

### When to Use

The embedded strategy is optimal when the number of edges per label on a vertex is below the
configured threshold (default controlled by `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD`). For
typical graph workloads where most vertices have moderate connectivity, this avoids the overhead of
maintaining a separate BTree.

---

## `BTreeBasedLinkBag`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

**Role**: Stores link data in a separate on-disk BTree for large collections.

### Purpose

`BTreeBasedLinkBag` is used when the number of links exceeds the embedding threshold. Instead of
storing all RIDs inline in the vertex record, only a small `LinkBagPointer` (file ID + linkBag ID)
is stored. The actual RID data lives in a `SharedLinkBagBTree` managed by
`LinkCollectionsBTreeManagerShared`.

### Key Fields

| Field | Type | Description |
|---|---|---|
| `collectionManager` | `LinkCollectionsBTreeManager` | Manager for creating/loading BTree instances |
| `collectionPointer` | `LinkBagPointer` | `(fileId, linkBagId)` pair that identifies the BTree and the slice within it |

### How It Works

#### Reading (`getAbsoluteValue(RID)`)

1. Load the `IsolatedLinkBagBTree` via `collectionManager.loadIsolatedBTree(collectionPointer)`.
2. Call `tree.get(rid)` to get the persisted counter value.
3. If `localChanges` has a change for this RID, apply it (via `change.applyTo(oldValue,
   counterMaxValue)`).
4. The result is the effective counter merging persisted state and local modifications.

Note: The tree is loaded lazily. If `collectionPointer` is null (the BTree hasn't been created yet),
`loadTree()` returns null and the persisted value is treated as 0.

#### Writing (`handleContextBTree()`)

During record serialization (when the entity is saved), `handleContextBTree()` is called:

1. The bag's `collectionPointer` is set to the pointer provided by the serialization context.
2. A `LinkBagUpdateSerializationOperation` is pushed onto the `RecordSerializationContext`. This
   operation captures the current stream of changes and will apply them to the BTree within the
   active atomic operation.

#### BTree Iteration (`btreeSpliterator()`)

Returns a `Spliterator<ObjectIntPair<RID>>` that scans the entire RID range
`[#0:0 ... #MAX:MAX]` within the bag's BTree slice. This spliterator is used by the
`MergingSpliterator` in `AbstractLinkBag` to merge BTree data with local changes.

#### Deletion (`requestDelete()`)

When a BTree-based bag is deleted (or converted to embedded), `requestDelete()` pushes a
`LinkBagDeleteSerializationOperation` onto the serialization context. This operation calls
`storage.deleteTreeLinkBag()` which removes all entries from the bag's BTree slice.

`confirmDelete()` is called after successful deletion to clear local state: resets
`collectionPointer` to null, clears `localChanges` and `newEntries`, sets `size` to 0, and removes
identity change listeners from non-persistent RIDs.

### Original State Reconstruction and Rollback

Two methods support the change tracking lifecycle:

- **`returnOriginalState(transaction, changeEvents)`**: Reconstructs the state of the bag as it was
  before the given change events were applied. It creates a new `BTreeBasedLinkBag`, copies all
  current entries (reading from both BTree and local changes), and replays the events in reverse.
  This method does **not** modify the current bag. It is used by the **index system**
  (`ClassIndexManager`) to compute the diff between old and new values of an indexed property, and
  by `EntityEntry.getOnLoadValue()` to reconstruct the on-load state for transaction tracking.

- **`rollbackChanges(transaction)`**: Reverts changes made to the bag since the last entity
  callback boundary, modifying the bag in-place. Used to maintain consistency between the entity
  state and its property indexes.

### Transaction Operations

The BTree bag participates in transactions through two serialization operations:

1. **`LinkBagUpdateSerializationOperation`**: Applied during commit. Iterates through all changes
   (`getChanges()` stream, which merges `newEntries` and `localChanges`). For each entry:
   - If counter = 0: removes the RID from the BTree.
   - If counter > 0: puts the RID with its counter into the BTree.
   - Validates counter bounds (not negative, not exceeding `counterMaxValue`).

2. **`LinkBagDeleteSerializationOperation`**: Applied during commit when the bag is deleted. Calls
   `storage.deleteTreeLinkBag()` to remove all entries.

---

## `LinkCollectionsBTreeManagerShared`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

**Role**: Manages the lifecycle of `SharedLinkBagBTree` files and allocates unique IDs for
individual link bags within them.

### Purpose

Multiple `BTreeBasedLinkBag` instances can share the same physical BTree file. This manager
handles:

- Creating new BTree files when needed.
- Assigning unique `linkBagId` values to new bags.
- Loading existing BTree files on database open.
- Deleting bags or entire BTree files.

### File Naming Convention

BTree files follow the pattern: `global_collection_<collectionId>.grb`

Where:
- `global_collection_` is the `FILE_NAME_PREFIX`
- `<collectionId>` is the integer ID of the cluster/collection
- `.grb` is the `FILE_EXTENSION` (Graph RidBag)

### Key Fields

| Field | Type | Description |
|---|---|---|
| `storage` | `AbstractStorage` | The underlying storage engine |
| `fileIdBTreeMap` | `ConcurrentHashMap<Integer, SharedLinkBagBTree>` | Maps integer file IDs to their `SharedLinkBagBTree` instances |
| `ridBagIdCounter` | `AtomicLong` | Monotonically increasing counter for assigning unique `linkBagId` values. Each new bag gets a unique negative ID |

### Loading (`load()`)

On database open, scans the write cache for files matching the `global_collection_*.grb` pattern.
For each file:

1. Creates a `SharedLinkBagBTree` instance.
2. Calls `bTree.load()` to open the file.
3. Registers it in `fileIdBTreeMap`.
4. Reads the first key from the BTree. If the `ridBagId` of the first key is negative (the IDs are
   negative by convention), updates `ridBagIdCounter` to be at least as large as its absolute value.
   This ensures newly created bags get IDs that don't collide with existing ones.

### Creating a New Bag (`createBTree()`)

1. Calls `doCreateRidBag()`:
   - Looks up the file by `generateLockName(collectionId)`.
   - If the file doesn't exist, creates a new `SharedLinkBagBTree` file.
   - Increments `ridBagIdCounter` to get a new negative `linkBagId`.
   - Creates and returns an `IsolatedLinkBagBTreeImpl` wrapping the shared BTree with the new ID.
2. Returns the `LinkBagPointer` (file ID + linkBag ID) from the isolated BTree.

### Loading an Existing Bag (`loadIsolatedBTree()`)

Given a `LinkBagPointer`:

1. Extracts the integer file ID.
2. Looks up the `SharedLinkBagBTree` in `fileIdBTreeMap`.
3. Creates a new `IsolatedLinkBagBTreeImpl` wrapping the shared BTree with the specified
   `linkBagId`.

Note: `IsolatedLinkBagBTreeImpl` is a lightweight wrapper -- creating it is cheap and doesn't
involve I/O. The actual BTree pages are loaded on demand.

### Deleting a Bag (`delete()`)

Given a `LinkBagPointer`:

1. Looks up the `SharedLinkBagBTree` by file ID.
2. Streams all entries in the bag's range: `EdgeKey(linkBagId, MIN, MIN)` to
   `EdgeKey(linkBagId, MAX, MAX)`.
3. Removes each entry from the BTree.

This removes only the entries belonging to the specific bag, leaving other bags in the same
shared BTree file intact.

### Deleting an Entire Component (`deleteComponentByCollectionId()`)

Deletes the entire BTree file for a given collection ID. Used when a cluster/collection is dropped.

---

## `SharedLinkBagBTree`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree`

**Role**: The physical BTree data structure that stores edge link data on disk.

### Purpose

`SharedLinkBagBTree` is a crash-recoverable BTree (extends `DurableComponent`) that stores
key-value pairs where:

- **Key**: `EdgeKey` -- a composite of `(ridBagId, targetCollection, targetPosition)`
- **Value**: `int` -- the counter (number of occurrences of the target RID in the bag)

Multiple logical link bags coexist in the same BTree file, distinguished by their `ridBagId`
prefix in the key. The `EdgeKey` comparison order ensures all entries for a given bag are contiguous,
which enables efficient range scans.

### Page Structure

The BTree uses a page-based storage model:

| Page Index | Content |
|---|---|
| 0 (`ENTRY_POINT_INDEX`) | Entry point metadata: tree size, page count |
| 1 (`ROOT_INDEX`) | Root bucket of the BTree |
| 2+ | Internal and leaf buckets |

Each bucket (page) is represented by the `Bucket` class and can be either:

- **Leaf bucket**: Contains `(key, value)` entries directly. Leaf buckets are linked via
  `leftSibling` / `rightSibling` pointers for efficient sequential scanning.
- **Internal bucket**: Contains keys and child page pointers (`left`, `right`) for navigation.

### Core Operations

#### `get(EdgeKey)` -- Point Lookup

1. Acquires a read lock.
2. Walks the BTree from root to leaf using `findBucket()`.
3. If the key is found (`itemIndex >= 0`), reads and returns the value from the bucket.
4. Returns `-1` if not found.

#### `put(AtomicOperation, EdgeKey, int)` -- Insert or Update

1. Acquires an exclusive lock.
2. Finds the target leaf bucket via `findBucketForUpdate()`.
3. If the key exists:
   - If the new serialized value has the same length as the old, updates in-place.
   - Otherwise, removes the old entry and re-inserts.
4. If the key doesn't exist, inserts at the appropriate position.
5. If the bucket is full, splits it (see Splitting below).
6. Updates the tree size counter.

#### `remove(AtomicOperation, EdgeKey)` -- Delete

1. Acquires an exclusive lock.
2. Finds the leaf bucket containing the key.
3. Removes the entry and decrements the tree size.
4. Returns the old value, or `-1` if the key wasn't found.

### Bucket Splitting

When a leaf bucket overflows (can't fit a new entry), the BTree splits it:

1. The bucket is divided at the midpoint (`bucketSize / 2`).
2. A new right bucket is allocated.
3. The right half of entries is moved to the new bucket.
4. The separation key is promoted to the parent.
5. Leaf sibling pointers are updated to maintain the doubly-linked leaf chain.

If the parent also overflows, splitting propagates recursively up the tree. If the root itself is
split, a new root is created with two children.

### Range Iteration

The BTree supports both forward and backward range scans:

- **`streamEntriesBetween(from, fromInclusive, to, toInclusive, ascSortOrder)`**: Returns a
  `Stream<RawPairObjectInteger<EdgeKey>>` of all entries in the specified range.
- **`spliteratorEntriesBetween(...)`**: Returns a `Spliterator` for the same range.

Iteration is implemented by `SpliteratorForward` and `SpliteratorBackward`:

- **Forward**: Starts by finding the `from` key, then walks right through leaf bucket sibling
  pointers, reading entries in batch (cache size = 10) until the `to` key is reached.
- **Backward**: Starts by finding the `to` key, then walks left through leaf bucket sibling
  pointers.

Both spliterators use LSN (Log Sequence Number) comparison to detect if a page has changed since
the last batch was read. If the page has been modified (by a concurrent write), the spliterator
re-seeks from the last known key to recover its position.

### Concurrency Control

- **Read operations**: Acquire a read lock on the `atomicOperationsManager` + a shared lock on the
  BTree component.
- **Write operations**: Execute inside a `componentOperation` + acquire an exclusive lock.
- All write operations require an `AtomicOperation` to ensure WAL logging and crash recovery.

### Crash Recovery

As a `DurableComponent`, the BTree participates in the storage engine's WAL (Write-Ahead Logging)
system. All page modifications are recorded in the WAL before being applied to the data file. On
crash recovery, the WAL is replayed to restore the BTree to a consistent state.

---

## `IsolatedLinkBagBTreeImpl`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree`

**Role**: Provides a logical view of a single link bag within a shared BTree.

### Purpose

`IsolatedLinkBagBTreeImpl` is a lightweight adapter that translates RID-level operations into
`EdgeKey`-level operations on the underlying `SharedLinkBagBTree`. It provides the illusion that
each link bag has its own private BTree, when in reality multiple bags share the same physical file.

### Key Fields

| Field | Type | Description |
|---|---|---|
| `bTree` | `SharedLinkBagBTree` | The shared physical BTree |
| `intFileId` | `int` | Integer file ID of the BTree file |
| `linkBagId` | `long` | Unique ID of this bag within the shared BTree |
| `keySerializer` | `BinarySerializer<RID>` | Serializer for RID keys |
| `valueSerializer` | `BinarySerializer<Integer>` | Serializer for integer counter values |

### Key Translation

Every RID operation is translated into an `EdgeKey` by prepending the `linkBagId`:

```
RID(collectionId, collectionPosition)
  --> EdgeKey(linkBagId, collectionId, collectionPosition)
```

This ensures that all entries for this bag are grouped together in the BTree's key space, and
operations on one bag never see or affect entries belonging to another bag.

### Core Operations

#### `get(RID)` -- Point Lookup

Translates to `bTree.get(new EdgeKey(linkBagId, rid.collectionId, rid.collectionPosition))`.
Returns `null` instead of `-1` for missing entries (adapter convention).

#### `put(AtomicOperation, RID, Integer)` -- Insert or Update

Translates to `bTree.put(atomicOperation, EdgeKey(...), value)`.

#### `remove(AtomicOperation, RID)` -- Delete

Translates to `bTree.remove(atomicOperation, EdgeKey(...))`.
Returns `null` instead of `-1` for missing entries.

#### `clear(AtomicOperation)` -- Remove All Entries

Streams all entries in the range `EdgeKey(linkBagId, MIN, MIN)` to `EdgeKey(linkBagId, MAX, MAX)`
and removes each one. This deletes all entries belonging to this bag without affecting other bags
in the same BTree.

#### `delete(AtomicOperation)` -- Same as `clear()`

For isolated bags, deletion is the same as clearing all entries, since the shared BTree file is
not deleted.

#### `isEmpty()` -- Check for Empty

Queries entries starting from `EdgeKey(linkBagId, MIN, MIN)` in ascending order. Returns `true`
if no entry is found for this bag's ID range.

#### `firstKey()` / `lastKey()` -- Boundary Keys

Scans the bag's range in ascending / descending order and returns the first / last RID found.
The `EdgeKey` is converted back to a `RecordId(targetCollection, targetPosition)`.

#### `getRealBagSize()` -- Total Counter Sum

Streams all entries in the bag's range and sums their counter values. This gives the total number
of links (accounting for duplicates).

### Range Iteration

#### `spliteratorEntriesBetween(keyFrom, fromInclusive, keyTo, toInclusive, ascSortOrder)`

Returns a `TransformingSpliterator` that wraps the shared BTree's spliterator. The
`TransformingSpliterator` converts each `RawPairObjectInteger<EdgeKey>` into an
`ObjectIntPair<RID>` by extracting `targetCollection` and `targetPosition` from the `EdgeKey`.

### `TransformingSpliterator` (Inner Class)

A `Spliterator<ObjectIntPair<RID>>` that wraps a `Spliterator<RawPairObjectInteger<EdgeKey>>`:

- `tryAdvance()`: Delegates to the underlying spliterator and converts the `EdgeKey` to a `RecordId`.
- `trySplit()`: Delegates and wraps the result.
- Preserves `estimateSize()` and `characteristics()` from the delegate.

### Collection Pointer

`getCollectionPointer()` returns a `LinkBagPointer(intFileId, linkBagId)` -- the pair that uniquely
identifies this bag in storage and is persisted in the vertex record when using BTree-based storage.

---

## Supporting Types

### `EdgeKey`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree`

A composite key used in the shared BTree:

| Field | Type | Description |
|---|---|---|
| `ridBagId` | `long` | Identifies which link bag this entry belongs to |
| `targetCollection` | `int` | Collection (cluster) ID of the target vertex |
| `targetPosition` | `long` | Position of the target vertex within the collection |

Ordering is lexicographic: first by `ridBagId`, then `targetCollection`, then `targetPosition`.
This ensures all entries for a given bag are contiguous in the BTree, enabling efficient range
scans.

### `LinkBagPointer`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

A record `(fileId, linkBagId)` that uniquely identifies a BTree-based link bag:

- `fileId` -- The file ID of the `.grb` file in the write cache.
- `linkBagId` -- The unique bag ID within that file.
- `INVALID` -- A sentinel constant `(-1, -1)` representing an uninitialized pointer.
- `isValid()` -- Returns `true` if both fields are non-negative.

### `Change` Interface and `AbsoluteChange`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

`Change` is an interface representing a modification to an RID's counter:

| Method | Description |
|---|---|
| `increment(maxCap)` | Increments the counter up to `maxCap`. Returns `true` if the value changed |
| `decrement()` | Decrements the counter. Returns `true` if the old value was > 0 |
| `applyTo(value, maxCap)` | Applies this change to a base value |
| `getValue()` | Returns the current counter value |
| `serialize(stream, offset)` | Serializes the change for persistence |

`AbsoluteChange` is the sole implementation. It stores an absolute counter value (not a delta). When
applied to a base value, it simply replaces it. The counter is clamped to `[0, maxCap]` and is
automatically floored to 0 if it goes negative.

### `BagChangesContainer` and `ArrayBasedBagChangesContainer`

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

`BagChangesContainer` is an interface for a sorted collection of `(RID, Change)` pairs.

`ArrayBasedBagChangesContainer` implements it using a sorted array with binary search:

- **Lookup**: `O(log n)` via `Arrays.binarySearch()`.
- **Insert**: `O(n)` due to `System.arraycopy()` for shifting, but entries are typically few.
- **Capacity**: Starts at 32, doubles when full (power-of-two sizing).
- **Spliterator**: Supports both full and tail-from-RID iteration.

This array-based approach is more cache-friendly than a tree-based map for the typical case where
link bags have moderate numbers of modified entries within a single transaction.

### `LinkCollectionsBTreeManager` Interface

**Package**: `com.jetbrains.youtrackdb.internal.core.storage.ridbag`

Interface for BTree lifecycle management:

| Method | Description |
|---|---|
| `createBTree(collectionId, atomicOperation, session)` | Creates a new bag in the BTree for the given collection. Returns a `LinkBagPointer` |
| `loadIsolatedBTree(collectionPointer)` | Loads an existing bag by its pointer. Returns an `IsolatedLinkBagBTree` |

---

## Data Flow

### Adding an Edge

```
Vertex.addEdge("knows", targetVertex)
  |
  v
EntityImpl: get or create LinkBag for "out_knows" property
  |
  v
LinkBag.add(targetRID)
  |
  v
AbstractLinkBag.add(targetRID)
  |
  +-- if targetRID is persistent:
  |     localChanges.putChange(targetRID, AbsoluteChange(currentValue + 1))
  |
  +-- if targetRID is not persistent:
        newEntries.put(targetRID, counter++)
```

### Saving the Vertex (Committing Changes)

```
Entity serialization
  |
  v
LinkBag.checkAndConvert()   [converts between embedded/btree if threshold crossed]
  |
  v
If embedded:
  Serialize (size, [RID, counter]...) inline in entity record
  |
If btree:
  1. Serialize only LinkBagPointer in entity record
  2. Push LinkBagUpdateSerializationOperation to context
     |
     v
  During atomic operation commit:
    LinkBagUpdateSerializationOperation.execute()
      |
      v
    For each (RID, Change) in getChanges():
      if counter == 0: tree.remove(rid)
      if counter > 0:  tree.put(rid, counter)
```

### Iterating Edges

```
Vertex.getEdges(OUT, "knows")
  |
  v
Read LinkBag from "out_knows" property
  |
  v
LinkBag.iterator() / stream()
  |
  v
AbstractLinkBag.MergingSpliterator
  |
  +-- 1. Emit all entries from newEntries (non-persistent RIDs)
  |
  +-- 2. Merge-sort:
  |     - localChanges spliterator (sorted by RID)
  |     - btreeSpliterator (sorted by RID)
  |
  |   When both have same RID: localChanges overrides btree value
  |   Skip entries with counter == 0
  |   Emit each RID `counter` times
  |
  v
Stream of RID values
```

### Deleting a Vertex

```
Vertex deletion
  |
  v
LinkBagDeleter.deleteAllRidBags(entity)
  |
  v
For each LinkBag property on the entity:
  LinkBag.delete()
    |
    v
  delegate.requestDelete(transaction)
    |
    +-- EmbeddedLinkBag: no-op (data is inline, deleted with entity)
    |
    +-- BTreeBasedLinkBag:
          Push LinkBagDeleteSerializationOperation
            |
            v
          During commit: storage.deleteTreeLinkBag()
            |
            v
          LinkCollectionsBTreeManagerShared.delete(pointer)
            stream all entries for this linkBagId
            remove each entry from shared BTree
```

---

## Configuration

| Parameter | Description | Default |
|---|---|---|
| `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD` | Number of links at which an embedded bag converts to BTree | (See `GlobalConfiguration`) |
| `LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD` | Number of links at which a BTree bag converts back to embedded | (See `GlobalConfiguration`) |
| `BTREE_MAX_DEPTH` | Maximum depth of the BTree before reporting corruption | (See `GlobalConfiguration`) |

Setting `LINK_COLLECTION_EMBEDDED_TO_BTREE_THRESHOLD` to `-1` forces all bags to start as
BTree-based. Setting `LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD` to `-1` disables conversion
back to embedded.

---

## Design Decisions and Trade-offs

### Why Two Storage Strategies?

1. **Embedded is fast for small collections**: No extra file I/O, no BTree overhead. Edge data is
   co-located with the vertex, so reading a vertex and its edges is a single page read.
2. **BTree scales for large collections**: When a vertex has thousands or millions of edges (a
   "supernode"), embedding all RIDs inline would make the vertex record excessively large and slow
   to read/write. A BTree keeps the vertex record small and allows efficient random access.

### Why Shared BTrees?

Instead of one BTree file per link bag, multiple bags share the same `.grb` file, distinguished by
`ridBagId` in the `EdgeKey`. This reduces the number of open files and simplifies lifecycle
management. The `ridBagId` prefix ensures bags don't interfere with each other.

### Why Negative Bag IDs?

New bag IDs are assigned as negative values (decremented from 0). This is a convention that makes it
easy to determine the next available ID by reading the first key in the BTree (the most negative
`ridBagId`), rather than scanning all entries.

### Why `AbsoluteChange` Instead of Delta?

Using absolute counters simplifies merge logic. When local changes override BTree values, there's
no need to compute deltas or worry about order of operations -- the absolute value is the truth.
