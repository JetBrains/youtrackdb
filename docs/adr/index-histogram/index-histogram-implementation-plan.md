# Index Histogram Implementation Plan

Standalone implementation plan for persistent equi-depth histograms in YouTrackDB's
index engine, grounded in code-level analysis of the current B-tree, index engine,
and query planning infrastructure.

---

## 1. Problem Statement

### 1.1 Current State

YouTrackDB's query planners lack persistent statistics for cost-based decisions.

**MATCH planner** (`MatchExecutionPlanner.java`): Already cost-driven — sorts candidate
roots by estimated cardinality (ascending) and greedily expands via depth-first traversal
(`getTopologicalSortedSchedule()`, line 698; called at line 595). However, the underlying
estimates are poor: `estimateRootEntries()` (line 1322) delegates to
`SQLWhereClause.estimate()` which uses the `count / 2` heuristic. The planner's
cost-driven structure is sound; only the input
estimates need improvement.

**SELECT planner** (`SelectExecutionPlanner.java`): Ranks index candidates via
`IndexSearchDescriptor.cost()`, which consults `QueryStats` — a volatile in-memory
EMA cache (`ConcurrentHashMap<String, Long>`) of past query result counts. On cold
start or for never-before-seen queries, cost defaults to `Integer.MAX_VALUE`
(effectively blind). The `cost()` method at line 97 has a `// TODO query the
index!` comment (line 113) acknowledging this gap.

**`SQLWhereClause.estimate()`** (line 82): Default heuristic is `count / 2` when no
index matches. For indexed estimation, `estimateFromIndex()` actually *executes* an
index lookup during planning (`index.getRids(key).count()`), which is accurate but
expensive and unpredictable for partial key matches.

| Component | Method | Quality |
|---|---|---|
| Root cardinality | `SQLWhereClause.estimate()` | `count/2` heuristic + index probe for equality |
| Index selectivity | `IndexSearchDescriptor.cost()` | `QueryStats` (volatile EMA) or `Integer.MAX_VALUE` |
| Edge fan-out | None | No statistics at all |
| NDV | None | Not tracked |
| Index size | `BTree.size()` — O(1) from entry point page | Available but unused for cost estimation |

### 1.2 Goal

Add **persistent equi-depth histograms** managed at the **index engine level** and use
them for **cost-based operator selection** within the existing plan structure. This is NOT
a full plan-enumerating optimizer (that would be Phase 2 — DP-based join ordering). This
is the minimal foundation that enables:

1. Better root selection (replace `count/2` with actual selectivity estimates)
2. Traverse+filter vs index-seek decisions at intermediate MATCH steps (future)
3. Accurate cost ranking for SELECT index candidate selection (replace `Integer.MAX_VALUE`)

There is no separate ANALYZE command — statistics are incrementally maintained and always
up to date.

---

## 2. Current Infrastructure: Code-Level Analysis

### 2.1 B-Tree Entry Point Page Layout

The entry point page is the sole metadata page per B-tree index — page 0 in the index
file.

**File:** `core/.../storage/index/sbtree/singlevalue/v3/CellBTreeSingleValueEntryPointV3.java`

All pages inherit from `DurablePage`, which reserves a fixed header:

```
DurablePage header (bytes 0–27):
  Offset 0–7:   MAGIC_NUMBER       (long, 8 bytes)
  Offset 8–11:  CRC32              (int, 4 bytes)
  Offset 12–19: WAL_SEGMENT        (long, 8 bytes)
  Offset 20–27: WAL_POSITION       (8 bytes allocated, read/written as int — only 4 used;
                                     bytes 24–27 are reserved padding)
  ─────────────────────────────────────────────────
  NEXT_FREE_POSITION = 28  (first byte available to subclasses)
```

**V3 single-value entry point fields** (starting at offset 28):

```
Offset  Field                Size     Java Type   Description
28      KEY_SERIALIZER       1 byte   byte        Serializer ID (unused in v3, legacy)
29–32   KEY_SIZE             4 bytes  int         Number of key fields
33–40   TREE_SIZE            8 bytes  long        Total entries in tree
41–44   PAGES_SIZE           4 bytes  int         Number of allocated data pages
45–48   FREE_LIST_HEAD       4 bytes  int         Page index of free list head (-1 if empty)
───────────────────────────────────────────────────────────────────────────────
Total used: 49 bytes out of 8192 (default page = 8 KB)
Free space on entry point page: ~8143 bytes
```

**Binary compatibility note:** `getFreeListHead()` treats `0` as `-1` because older v1
entry points lack this field and zero-initialized memory reads as `0`.

**V2 multi-value entry point** (`CellBTreeMultiValueV2EntryPoint.java`) replaces
`FREE_LIST_HEAD` with an 8-byte `ENTRY_ID` field at offset 45–52 (total used: 53 bytes).
The multi-value B-tree is actually implemented as **two separate single-value B-trees**
inside `BTreeMultiValueIndexEngine`: one for non-null keys (using `CompositeKey` with RID
appended) and one for null keys.

**Implication:** The histogram is managed as a separate component with its own file,
so no modifications to either entry point page format are needed (Section 3).

### 2.2 B-Tree Put/Remove Paths

**File:** `core/.../sbtree/singlevalue/v3/BTree.java` (2194 LOC)

#### Put path (method `update()`, line 253):

```
update(atomicOperation, key, value, validator):
  acquireExclusiveLock()
  if key != null:
    1. key = keySerializer.preprocess(key)
    2. serializedKey = keySerializer.serializeNativeAsWhole(key)
    3. Check MAX_KEY_SIZE constraint
    4. bucketSearchResult = findBucketForUpdate(key)          ← O(log N) tree descent
    5. Load leaf page for write
    6. If key already exists (itemIndex >= 0):
       a. Read old RID value
       b. If same size → updateValue() in-place, RETURN      ← no size change
       c. If different size → removeLeafEntry() + re-insert   ← sizeDiff = 0
    7. If key is new (itemIndex < 0):
       a. insertionIndex = -(itemIndex) - 1                   ← sizeDiff = 1
    8. While !addLeafEntry(insertionIndex, serializedKey, serializedValue):
       a. splitBucket() → may cascade to root                 ← page allocation
    9. If sizeDiff != 0:
       a. updateSize(sizeDiff)                                ← ENTRY POINT WRITE
  else (key is null):
    1. Load null bucket
    2. Check if value already exists (oldValue)
    3. nullBucket.setValue(value)
    4. updateSize(sizeDiff)                                   ← ENTRY POINT WRITE
```

#### Remove path (method `remove()`, line 488):

```
remove(atomicOperation, key):
  acquireExclusiveLock()
  if key != null:
    1. bucketSearchResult = findBucketForRemove(key)          ← O(log N) tree descent
    2. If found:
       a. Load leaf page for write
       b. Read raw value (RID)
       c. keyBucket.removeLeafEntry(itemIndex, serializedKey)
       d. updateSize(-1)                                      ← ENTRY POINT WRITE
    3. If not found: return null
  else (key is null):
    1. Load null bucket
    2. Read and clear value
    3. If was present: updateSize(-1)                          ← ENTRY POINT WRITE
```

#### The `updateSize()` method:

```java
private void updateSize(final long diffSize, final AtomicOperation atomicOperation)
    throws IOException {
  try (final var entryPointCacheEntry =
      loadPageForWrite(atomicOperation, fileId, ENTRY_POINT_INDEX, true)) {
    final var entryPoint =
        new CellBTreeSingleValueEntryPointV3<K>(entryPointCacheEntry);
    entryPoint.setTreeSize(entryPoint.getTreeSize() + diffSize);
  }
}
```

`updateSize()` modifies the entry point's `treeSize` field. The entry point is also
modified by `addToFreeList()` (line 937, sets `freeListHead`) and `allocateNewPage()`
(line 1614, sets `freeListHead` and `pagesSize`) during splits. The histogram manager
receives its notifications at the engine level, *after* the B-tree operation completes
(Section 3.2).

### 2.3 Index Engine Layer

The engine layer wraps the B-tree and is where the histogram manager hooks in.

**Single-value engine** (`BTreeSingleValueIndexEngine.java`):

```java
// Current signatures (void put, boolean remove):
public void put(AtomicOperation op, Object key, RID value) {
  sbTree.put(op, key, value);   // key is the original key
}

public boolean remove(AtomicOperation op, Object key) {
  return sbTree.remove(op, key) != null;  // key is the original key
}
```

**Multi-value engine** (`BTreeMultiValueIndexEngine.java`):

```java
// Current signatures (void put, boolean remove):
public void put(AtomicOperation op, Object key, RID value) {
  if (key != null) {
    svTree.put(op, createCompositeKey(key, value), value);  // wraps here
  } else {
    nullTree.put(op, value, value);
  }
}

public boolean remove(AtomicOperation op, Object key, RID value) {
  if (key != null) {
    // Range scan on compositeKey to find and remove
    svTree.iterateEntriesBetween(compositeKey, ...).forEach(pair ->
        svTree.remove(op, pair.first()));
  } else {
    nullTree.remove(op, value);
  }
}
```

**Note:** This plan changes `put()` from `void` to `boolean` for both engines (Section 3.1,
Step 5). The return value propagates `wasInsert` from the B-tree — essential for histogram
incremental maintenance (Section 5.6).

**Critical observation:** The engine always holds the **original key** before any
`CompositeKey` wrapping. This is why the histogram must live at the engine level, not
inside the B-tree.

The engine also provides `keyStream()` which already extracts original keys for
multi-value:

```java
// BTreeMultiValueIndexEngine line 283:
public Stream<Object> keyStream(AtomicOperation op) {
  return svTree.keyStream(op).map(BTreeMultiValueIndexEngine::extractKey);
}
```

### 2.4 B-Tree Leaf Page Structure

**File:** `CellBTreeSingleValueBucketV3.java`

```
Offset  Field                Size       Description
28      FREE_POINTER         4 bytes    Points to start of free area (grows down)
32      SIZE                 4 bytes    Number of entries in this bucket
36      IS_LEAF              1 byte     1 = leaf, 0 = internal
37      LEFT_SIBLING         8 bytes    Page index of left sibling (-1 if none)
45      RIGHT_SIBLING        8 bytes    Page index of right sibling (-1 if none)
53      POSITIONS_ARRAY      SIZE × 4   Array of 4-byte offsets to entries (grows up)
...     (free area)
...     ENTRY_DATA                      Key-value pairs (grows down from page end)
```

Leaf entry: `[serialized_key:variable][collection_id:2][collection_position:8]`

Available data space: `8192 - 53 = 8139 bytes`. Each entry also requires a 4-byte
position array slot. Effective entries per page:
- Integer keys (4 bytes): `8139 / (4 + 10 + 4)` ≈ 452 entries/page
- 50-char UTF-8 strings (~52 bytes): `8139 / (52 + 10 + 4)` ≈ 123 entries/page

This determines the sequential I/O cost of a full key stream scan for histogram
construction.

### 2.5 Key Serialization and Comparison

**Interface:** `BinarySerializer<T>` in `core/.../common/serialization/types/`

| Type               | Serializer            | Fixed? | Size (bytes)           |
|--------------------|-----------------------|--------|------------------------|
| `int`              | IntegerSerializer     | Yes    | 4                      |
| `long`             | LongSerializer        | Yes    | 8                      |
| `double`           | DoubleSerializer      | Yes    | 8                      |
| `Date`/`DateTime`  | DateSerializer        | Yes    | 8 (epoch millis)       |
| `String`           | StringSerializer      | **No** | 4 + length×2 (UTF-16)  |
| `String` (UTF-8)   | UTF8Serializer        | **No** | 2 + UTF-8 byte count   |
| `byte[]`           | BinaryTypeSerializer  | **No** | 4 + array length       |
| `Decimal`          | DecimalSerializer     | **No** | variable               |
| `LINK`             | LinkSerializer        | Yes    | 10 (short+long)        |
| `CompositeKey`     | CompositeKeySerializer| **No** | 4 + 4 + Σ(1+keySizeᵢ) |

**Comparison** (`DefaultComparator.java`): All B-tree key comparisons use
`DefaultComparator.INSTANCE` → `((Comparable) one).compareTo(two)`. Operates on Java
objects, not byte arrays. The histogram's `findBucket()` must use the same comparator
on deserialized boundary keys.

**Variable-length keys are the critical constraint for histogram storage** — boundary
keys for string indexes can be arbitrarily large (Section 5.3).

### 2.6 Key Stream and Iteration

`keyStream()` and `allEntries()` return sorted streams by traversing leaf pages
left-to-right via sibling pointers using `SpliteratorForward`:
- Prefetches `SPLITERATOR_CACHE_SIZE` (default 10, from `GlobalConfiguration.INDEX_CURSOR_PREFETCH_SIZE`) entries per batch
- Each leaf page read is a single sequential I/O operation
- 1M entries: ~2,200 page reads (int keys) to ~8,100 page reads (50-char strings)
- On SSD (~100 μs per sequential read): 0.2–0.8 seconds for a full scan

`firstKey()` / `lastKey()`: Traverse root → leftmost/rightmost leaf. Cost: O(tree_depth),
typically 3–5 page reads.

### 2.7 Concurrency Model

The BTree uses **two-level locking**:
1. `AtomicOperationsManager` lock (read lock for reads, atomic op context for writes)
2. Component lock (`acquireSharedLock()` / `acquireExclusiveLock()`)

Write operations acquire an exclusive lock, execute inside
`calculateInsideComponentOperation()`, then release. The engine-level histogram update
runs *after* the B-tree lock is released but within the same `AtomicOperation` →
crash-consistent via WAL, though concurrent readers may momentarily see stale statistics.
This is benign for approximate statistics.

**Background rebalance:** `SpliteratorForward` reads from the live cache, not a
point-in-time snapshot. Rebalance scans may see concurrent mutations — acceptable for
histogram construction since it's approximate by nature.

### 2.8 Current Cost Estimation

**`QueryStats.java`:** `ConcurrentHashMap<String, Long>`. Volatile (lost on restart).
EMA smoothing: `newVal = oldVal × 0.9 + observed × 0.1`. On miss: returns `-1`.
Special case: unique index with full key match → returns `1` without prior observations.

**`IndexSearchDescriptor.cost()`** (line 97): Queries `QueryStats`. Returns
`Integer.MAX_VALUE` when no stats are available.

**`SQLWhereClause.estimate()`** (line 82): Default `count / 2`. For indexed estimation:
flattens conditions, matches index prefixes, calls `estimateFromIndex()` which actually
executes an index lookup for exact key matches or creates a spliterator for partial
matches — expensive and unpredictable.

---

## 3. Architecture: Engine-Level Histogram Manager

### 3.1 Why Engine Level, Not B-Tree Level

The histogram is managed as a **separate component at the index engine level**, not
inside the B-tree. This is driven by concrete code-level constraints:

| Concern | B-tree level | Engine level |
|---------|-------------|--------------|
| Has original key for multi-value | No (sees CompositeKey) | **Yes** |
| Has AtomicOperation | Yes | **Yes** |
| Modifies core data structure | Yes | **Minimal** (`put()` return type only) |
| Requires entry point page change | Yes | **No** |
| NDV tracking for multi-value | Requires prefix range scan per op | **Computed during build** |
| Storage management | Linked from entry point | **Independent file** |
| Testability | Coupled to BTree | **Independent component** |

**Multi-value resolution.** `BTreeMultiValueIndexEngine` wraps keys as
`CompositeKey(originalKey, rid)` before passing them to the B-tree. Only the engine
knows the original key. Histogram boundaries and selectivity estimates must operate on
original keys (what the planner queries), not composite keys.

**NDV simplification.** Inside the B-tree, every composite key insert is "new." Detecting
whether the *original* key is new requires an O(log N + fanout) prefix range scan per
operation. At the engine level:
- Single-value: NDV = totalCount (by definition — one entry per key)
- Multi-value: NDV computed only during histogram build/rebalance, not incrementally

**B-tree nearly untouched.** The only B-tree modification is changing
`CellBTreeSingleValue.put()` and `BTree.put()` from `void` to `boolean` return type —
propagating the already-computed `sizeDiff` (1 = insert, 0 = update) from the internal
`update()` method, which already returns `boolean`. This is a minimal signature change
(no logic change, no new fields, no page format change). `CellBTreeSingleValueEntryPointV3`
and all other B-tree classes remain untouched. No entry point page format change, no
migration concern for existing index files.

### 3.2 Component Layout

```
┌─────────────────────────────────────────────────┐
│              Index Engine                        │
│  (BTreeSingleValueIndexEngine or                │
│   BTreeMultiValueIndexEngine)                   │
│                                                  │
│  ┌──────────────────┐  ┌──────────────────────┐ │
│  │     BTree         │  │  IndexHistogramManager│ │
│  │  (put→boolean)    │  │  (new DurableComponent)│ │
│  │                   │  │                       │ │
│  │  .cbt data file   │  │  .ixs stats file      │ │
│  │  .nbt null file   │  │                       │ │
│  └──────────────────┘  └──────────────────────┘ │
│                                                  │
│  Engine.put(op, key, value):                    │
│    wasInsert = btree.put(op, key/compositeKey,   │
│                          value)                  │
│    histogramManager.onPut(op, key, wasInsert)    │
│                                                  │
│  Engine.remove(op, key, ...):                   │
│    btree.remove(op, key/compositeKey)            │
│    histogramManager.onRemove(op, key)            │
└─────────────────────────────────────────────────┘
```

**Note:** The `key` passed to `onPut()`/`onRemove()` is the **original** key (before
any `CompositeKey` wrapping). For composite indexes, the histogram manager internally
extracts the leading (first) field for bucket lookup. See Sections 5.6 and 6.4.

**Insert-vs-update detection (single-value only):** For single-value indexes, `put()` can
be either an *insert* (new key) or an *update* (existing key, new value). The B-tree's
internal `update()` method (line 253) already returns `boolean` (true = new key inserted,
false = existing key updated), computed from its `sizeDiff` variable (1 for insert, 0 for
update). Currently, the public `put()` method (line 239) discards this return value
(`void` return type). This plan changes `CellBTreeSingleValue.put()` (interface, line 26)
and `BTree.put()` (implementation, line 239) from `void` to `boolean`, simply returning
the result of the existing `update()` call — no logic change, just a signature change. The
engine then propagates this as `boolean wasInsert = sbTree.put(op, key, value)`. The
histogram manager only increments `totalCountDelta` and `distinctCountDelta` when
`wasInsert == true`. Without this distinction, update-heavy workloads would inflate
`totalCount` and `distinctCount` between rebalances. For multi-value indexes this is not
needed — every `put(key, value)` always inserts a new composite entry `(key, RID)`.

**Delta accumulation model:** The `onPut()`/`onRemove()` calls do **not** modify the
CHM cache directly. Instead, they accumulate deltas in a transaction-local
`HistogramDelta` structure attached to the `AtomicOperation`:

```java
/**
 * Transaction-local accumulator for histogram changes. Carried by the
 * AtomicOperation and applied to the CHM cache only on successful commit.
 * On rollback, simply discarded (write-nothing-on-error).
 */
class HistogramDelta {
    long totalCountDelta;
    long nullCountDelta;
    long distinctCountDelta;     // meaningful only for single-value indexes
    int[] frequencyDeltas;       // per-bucket deltas (null if no histogram);
                                 // int suffices — bounded by mutations in a single
                                 // transaction, well within Integer.MAX_VALUE
    long snapshotVersion;        // version of the HistogramSnapshot that was current when
                                 // this delta's frequencyDeltas were computed (i.e., the
                                 // boundaries used for findBucket). Captured once on the
                                 // first onPut/onRemove that allocates frequencyDeltas[]
                                 // (i.e., first non-null key operation with a live histogram).
                                 // On commit, if the snapshot's version has changed
                                 // (rebalance occurred), frequencyDeltas are discarded
                                 // because the bucket layout no longer matches (Section 5.7).
                                 // Only meaningful when frequencyDeltas != null; applyDelta()
                                 // skips the version check when frequencyDeltas is null
                                 // (e.g., transactions that only insert/remove null keys).
    long mutationCount;          // number of put/remove ops in this transaction
    HyperLogLog hllSketch;       // per-transaction HLL sketch (multi-value only; Section 6.2);
                                 // ~1 KB fixed size regardless of transaction size;
                                 // on commit, merged into snapshot's persisted HLL sketch
                                 // and distinctCount updated from hll.estimate()
    // nonNullCount is NOT tracked here — it is recomputed as sum(clamped frequencies)
    // during applyDelta to maintain the invariant nonNullCount == sum(frequencies).
    // Tracking it separately would break the invariant when per-bucket clamping
    // and aggregate clamping produce different results.
}
```

The `AtomicOperation` holds a `Map<Integer, HistogramDelta>` keyed by engine ID.
Each `onPut()`/`onRemove()` call finds or creates the delta for the engine and
updates it (pure in-memory, no I/O, no locking beyond the existing component lock).

**Commit path:** After `commitIndexes()` succeeds in `AbstractStorage.commit()`, deltas
are applied to the CHM cache: for each affected engine ID, the current
`HistogramSnapshot` is read from the CHM, a new snapshot is computed by applying the
delta, and the CHM entry is swapped via `cache.compute(engineId, ...)`. This ensures
the CHM always reflects **committed** state. If the engine has been closed or deleted
between the B-tree mutation and the delta application (e.g., concurrent `DROP INDEX`),
the CHM entry will be absent (`cache.get(engineId)` returns `null`). In this case, the
delta is silently discarded — the engine no longer exists, so there is nothing to update.

**Rollback:** On rollback, the `AtomicOperation` (including its delta map) is discarded.
The CHM is never modified — no invalidation needed. This is fully consistent with
YouTrackDB's write-nothing-on-error model.

**Batched persistence (near-zero WAL overhead):** The CHM cache is the authoritative
in-memory state. The `.ixs` statistics page is flushed to disk periodically:

1. **Dirty counter:** Each `IndexHistogramManager` tracks `dirtyMutations` as a plain
   `long` (incremented when deltas are applied to the CHM on commit). When
   `dirtyMutations >= PERSIST_BATCH_SIZE` (default: 500), the committing thread flushes
   the current CHM snapshot to the `.ixs` page and resets the counter. No atomic/volatile
   guard is needed: the threshold check runs on every commit (hot path), so the cost of
   `LongAdder.sum()` (which iterates over all internal striped cells) would add overhead
   for no real benefit. Concurrent races on the plain `long` may cause an occasional
   duplicate flush (idempotent — second write overwrites with a near-identical snapshot)
   or a slightly delayed flush (caught by the checkpoint fallback within 300 seconds).
   Both are harmless for approximate statistics.
2. **Checkpoint hook:** `AbstractStorage.makeFuzzyCheckpoint()` (called every
   `WAL_FUZZY_CHECKPOINT_INTERVAL` seconds, default: 300) iterates all histogram managers
   and flushes any with `dirtyMutations > 0`. This ensures persistence even for indexes
   with low write rates that never hit `PERSIST_BATCH_SIZE`.
3. **Engine close:** `closeStatsFile()` flushes remaining dirty state.

On crash, at most `PERSIST_BATCH_SIZE` committed mutations are lost per index. The next
engine `load()` reads the (slightly stale) persistent page into the CHM cache. The
staleness is bounded and self-correcting via subsequent incremental updates.

**WAL cost:** Instead of 1 WAL entry per mutation (~33–50% overhead), the batched approach
generates 1 WAL entry per `PERSIST_BATCH_SIZE` mutations — effectively negligible.

### 3.3 In-Memory Statistics Cache

Planner threads read statistics frequently during query compilation. To avoid
`loadPageForRead()` on every planner call, the **storage** maintains a shared in-memory
cache of histogram snapshots for all indexes.

**Design:** A single `ConcurrentHashMap<Integer, HistogramSnapshot>` keyed by engine ID,
owned by `DiskStorage` (field on `AbstractStorage`). Engine IDs are stable across database
restarts (persisted in storage metadata), so the cache naturally starts empty on open and
is populated on first access. Each `IndexHistogramManager` receives a reference to this
shared cache at construction time. Deltas are applied to the cache on commit (Section 3.2).
Planners query the cache directly via `storage.getHistogramSnapshot(engineId)`. Each entry
holds an immutable `HistogramSnapshot` record containing `IndexStatistics` +
`EquiDepthHistogram` (nullable).

```java
/**
 * Immutable point-in-time snapshot of index statistics and histogram.
 * Represents committed state only. Replaced atomically on transaction commit
 * (delta application) and on histogram rebalance.
 */
record HistogramSnapshot(
    IndexStatistics stats,
    @Nullable EquiDepthHistogram histogram,
    long mutationsSinceRebalance,
    long totalCountAtLastBuild,
    long version,                     // incremented on rebalance; used by delta application to
                                      // detect stale deltas and discard frequencyDeltas (Section 5.7).
                                      // NOT persisted — resets to 0 on restart. This is safe because
                                      // all in-flight transactions are rolled back on crash recovery,
                                      // so no pending deltas with old versions can exist after restart.
    boolean hasDriftedBuckets,        // true if any bucket frequency was clamped from negative to 0
                                      // during delta application (Section 5.7); causes the rebalance
                                      // threshold to be halved to trigger earlier correction.
                                      // NOT persisted — resets to false on restart and rebalance.
    @Nullable HyperLogLog hllSketch   // multi-value NDV tracking (Section 6.2); null for single-value
                                      // and for multi-value indexes below HISTOGRAM_MIN_SIZE (lazy
                                      // init); persisted to .ixs page; survives restarts
) {}
```

**Cache lifecycle:**

| Event | Action |
|---|---|
| Engine `create()` / `load()` | Histogram manager reads statistics page → `cache.put(engineId, snapshot)` |
| Engine `put()` / `remove()` | Accumulate delta in `AtomicOperation` (CHM not touched) |
| Transaction commit | Apply deltas to CHM → `cache.compute(engineId, ...)` per affected engine; increment `dirtyMutations` |
| Transaction rollback | Deltas discarded with `AtomicOperation` (CHM not touched — no invalidation needed) |
| Histogram rebalance | Build new snapshot → `cache.compute(engineId, ...)` with incremented version |
| Engine `close()` / `delete()` | Flush dirty state to page; `cache.remove(engineId)` |
| Engine `clear()` (truncate) | Reset statistics: zero all counters, discard histogram, install empty snapshot in CHM; persist empty `.ixs` page. See below |
| Planner `getStatistics()` / `getHistogram()` | `cache.get(engineId)`; on miss, histogram manager loads from page |

**Index clear/truncate handling:** When an index is cleared (e.g., via `TRUNCATE CLASS`
which calls `doClearTree()` or equivalent), the histogram manager must be reset to match
the now-empty B-tree. The engine calls `histogramManager.resetOnClear(op)` which:
1. Creates an empty `IndexStatistics(0, 0, 0)` and a `HistogramSnapshot` with no histogram
2. Installs it in the CHM cache via `cache.put(engineId, emptySnapshot)`
3. Persists the zeroed `.ixs` page
4. Resets `dirtyMutations`, `mutationsSinceRebalance`, `totalCountAtLastBuild` to 0

Without this, the histogram would retain stale boundaries and frequencies from the
pre-truncate state, causing incorrect selectivity estimates until a rebalance or restart.

**Thread safety:** The storage-level `ConcurrentHashMap` provides safe concurrent access.
The `HistogramSnapshot` record is immutable — readers never see partially-updated state.
Delta application on commit uses `cache.compute(engineId, ...)` to atomically read the
current snapshot, apply the delta, and install the new snapshot. This ensures correct
interaction with concurrent rebalance operations (Section 5.7). A fresh clone of the HLL
sketch is created for multi-value indexes — the old snapshot's HLL is never mutated.
The HLL is persisted to the `.ixs` page as part of batched persistence (Section 6.2).
The CHM always reflects committed state, so no rollback invalidation is needed.

---

## 4. Three-Tier Statistics Model

Empty indexes and small indexes do not get a histogram. Statistics follow a graduated
approach:

| Tier | Condition | Statistics Source | Accuracy |
|---|---|---|---|
| **Empty** | `totalCount == 0` | All estimates return 0 | Exact |
| **Uniform** | `0 < totalCount` and `nonNullCount < HISTOGRAM_MIN_SIZE` | `IndexStatistics` counters only; uniform-distribution formulas | Coarse but >> `count/2` |
| **Histogram** | `nonNullCount >= HISTOGRAM_MIN_SIZE` | Full equi-depth histogram | High (≤1% error for 128 buckets) |

`HISTOGRAM_MIN_SIZE` (configurable, default: 1000) is the minimum number of **non-null**
entries before a histogram is built. Below this threshold, building equi-depth boundaries
produces too few entries per bucket to be meaningful. An all-null index stays in Uniform
mode regardless of `totalCount` (where `nonNullCount = totalCount - nullCount`).

### 4.1 Uniform-Distribution Selectivity (Small Indexes)

When the index has entries but no histogram, use summary counters:

```
selectivity(f = X):
  return 1.0 / distinctCount

selectivity(f > X):
  return 1.0 / 3.0     // standard PostgreSQL default for unbounded range

selectivity(f IS NULL):
  return nullCount / totalCount

selectivity(f IS NOT NULL):
  return (totalCount - nullCount) / totalCount

selectivity(f IN (v1,...,vN)):
  return min(N / distinctCount, 1.0)

selectivity(unknown — non-indexed predicate):
  return DEFAULT_SELECTIVITY    // configurable, default 0.1
```

These are coarse but dramatically better than `count / 2` and require zero histogram
storage — only the summary counters in `IndexStatistics`.

### 4.2 Transitions Between Tiers

**Empty → Uniform:** First `put()` increments `totalCount` to 1.

**Uniform → Histogram:** When `totalCount - nullCount` (non-null entries) crosses
`HISTOGRAM_MIN_SIZE`, schedule a background histogram build. Until the build completes,
continue using uniform estimates. This check is coordinated with the rebalance trigger —
both are evaluated in a single `maybeScheduleHistogramWork()` method called from
`getHistogram()` (Section 5.7). An all-null index never transitions to histogram mode
regardless of `totalCount`.

**Histogram → Uniform:** If `nonNullCount` drops below `HISTOGRAM_MIN_SIZE` (e.g., mass
deletion), the existing histogram becomes stale but remains usable. On the next rebalance
check, skip rebalance. The histogram is not actively discarded — it serves as a
better-than-uniform approximation until it becomes too stale.

---

## 5. Index Statistics & Equi-Depth Histograms

### 5.1 The `IndexStatistics` Record

```java
/**
 * Per-index summary statistics for cost-based query optimization.
 *
 * Managed by IndexHistogramManager (a DurableComponent separate from the
 * B-tree). Incrementally updated on every put/remove via engine callbacks.
 *
 * @param totalCount     total number of entries in the index (including nulls)
 * @param distinctCount  number of distinct keys (NDV)
 *                       - single-value: always equals totalCount
 *                       - multi-value: updated incrementally via HLL estimate on each commit
 *                         (~3.25% error); exact value recomputed during rebalance (Section 6.2)
 * @param nullCount      number of entries with null key
 *
 * Invariant (approximate): when a histogram exists,
 *   totalCount ≈ histogram.nonNullCount + stats.nullCount
 * Holds exactly at build/rebalance time. Between rebalances, per-bucket frequency
 * clamping (Section 5.4) can cause nonNullCount (= sum of clamped frequencies) to
 * diverge slightly from totalCount - nullCount. Rebalance restores exact equality.
 */
public record IndexStatistics(
    long totalCount,
    long distinctCount,
    long nullCount
) {}
```

**Min/max omitted.** For variable-length types (strings, byte arrays, decimals), min/max
values have unbounded serialized size and complicate storage. The histogram boundaries
already capture the effective range (boundaries[0] ≈ min, boundaries[bucketCount] ≈ max).
When exact min/max are needed (e.g., for out-of-range short-circuit), use
`btree.firstKey()` / `btree.lastKey()` directly — O(log N), cacheable by the planner
for the duration of a single query plan.

### 5.2 Equi-Depth Histogram Design

**Type:** Equi-depth (quantile) — each bucket contains approximately the same number of
entries. Dense regions get narrow buckets (high resolution), sparse regions get wide
buckets (low resolution). Contrast with equi-width which wastes resolution on empty
regions.

**Boundary format (PostgreSQL convention):** The boundaries array stores `bucketCount + 1`
values — explicit lower and upper bounds for every bucket. Bucket `i` spans
`[boundaries[i], boundaries[i+1])` for `i < bucketCount - 1`. The **last bucket** spans
`[boundaries[bucketCount-1], boundaries[bucketCount]]` — **closed on both ends** — because
`boundaries[bucketCount]` is the maximum key and must be included. The `findBucket()`
implementation handles this: keys `≥ boundaries[bucketCount]` map to the last bucket
(Section 5.4). This eliminates implicit `-∞` / `+∞` and makes all interpolation formulas
uniform across buckets including the first and last.

```java
/**
 * Equi-depth histogram for selectivity estimation.
 *
 * Present only when totalCount >= HISTOGRAM_MIN_SIZE.
 *
 * <p><b>Immutability contract:</b> This record is treated as deeply immutable.
 * The {@code boundaries}, {@code frequencies}, and {@code distinctCounts} arrays
 * must NOT be mutated after construction. Callers receiving a histogram from
 * {@link HistogramSnapshot} must never modify its arrays. The constructor does
 * not make defensive copies (to avoid allocation on the hot read path); correct
 * usage is enforced by convention, not by the type system.</p>
 *
 * Null entries are tracked exclusively in {@link IndexStatistics#nullCount()},
 * not duplicated here. IS NULL / IS NOT NULL selectivity is computed from
 * {@code stats.nullCount} and {@code histogram.nonNullCount}.
 *
 * @param bucketCount     number of buckets (target: HISTOGRAM_BUCKETS, may be reduced
 *                        for variable-length keys to fit within a single page)
 * @param boundaries      sorted array of (bucketCount + 1) boundary values;
 *                        bucket i spans [boundaries[i], boundaries[i+1])
 *                        boundaries[0] = min key, boundaries[bucketCount] = max key
 * @param frequencies     frequencies[i] = number of entries in bucket i;
 *                        clamped to >= 0 (may drift below actual due to deletions
 *                        of pre-histogram entries; corrected on rebalance)
 * @param distinctCounts  distinctCounts[i] = NDV within bucket i
 *                        (computed during build/rebalance only, not updated incrementally).
 *                        For composite indexes, counts distinct leading-field values
 *                        per bucket (Section 6.4). Note: IndexStatistics.distinctCount
 *                        tracks full-key NDV, not leading-field NDV.
 * @param nonNullCount    sum of all frequencies (excludes null entries);
 *                        clamped to >= 0
 */
public record EquiDepthHistogram(
    int bucketCount,
    Comparable<?>[] boundaries,
    long[] frequencies,
    long[] distinctCounts,
    long nonNullCount,
    @Nullable Comparable<?> mcvValue,  // most common value (MCV); null if NDV == 0
    long mcvFrequency                  // exact frequency of mcvValue at build/rebalance time;
                                       // not maintained incrementally — stales between rebalances
                                       // but always >= bucket-averaged estimate, so still valuable
) {}
```

**Note on `nonNullCount` vs `IndexStatistics.totalCount`:** `IndexStatistics.totalCount`
includes nulls. `EquiDepthHistogram.nonNullCount` excludes nulls. The approximate
invariant is: `stats.totalCount ≈ histogram.nonNullCount + stats.nullCount` — exact at
build/rebalance time, may drift slightly between rebalances due to per-bucket frequency
clamping (Section 5.4). Null tracking lives exclusively in `IndexStatistics.nullCount` —
the histogram has no separate `nullCount` field (Section 5.2).

### 5.3 Variable-Length Boundary Keys

For fixed-length key types (int, long, double, date), boundary storage is compact and
predictable. For variable-length types (strings, byte arrays, decimals), boundary storage
depends on actual key values and can be large.

**Page budget calculation** (8 KB default page, 128-bucket target, 129 boundaries):

For **single-value indexes** (no HLL registers on page):

```
Available payload:    8164 bytes  (8192 - 28 DurablePage header)
Fixed fields:           73 bytes  (format, counters, metadata, serializerId,
                                   hllRegisterCount, mcvFrequency, mcvKeyLength)
mcvKey:             ~0–260 bytes  (typically small; same type as boundary keys;
                                   truncated to MAX_BOUNDARY_BYTES if needed)
frequencies[128]:     1024 bytes
distinctCounts[128]:  1024 bytes  (long[] — future-proof for very large indexes)
───────────────────────────────────
Remaining for 129 boundaries: ~6043–5783 bytes → ~44–46 bytes per boundary
(MCV key is typically a few bytes for numeric types; budget impact negligible)
```

For **multi-value indexes** (1024-byte HLL register array on page):

```
Available payload:    8164 bytes
Fixed fields:           73 bytes
mcvKey:             ~0–260 bytes
frequencies[128]:     1024 bytes
distinctCounts[128]:  1024 bytes
hllRegisters[1024]:   1024 bytes
───────────────────────────────────
Remaining for 129 boundaries: ~5019–4759 bytes → ~36–38 bytes per boundary
```

The HLL reduces the boundary budget by ~1 KB for multi-value indexes. For fixed-size keys
(int, long, date) this is negligible. For variable-length keys, the bucket count reduction
fallback (Strategy 2) accommodates the reduced budget. When boundaries exhaust page 0, the
HLL spills to page 1 (Section 5.8).

#### Strategy 1 — Boundary truncation (primary, inspired by PostgreSQL)

PostgreSQL's `WIDTH_THRESHOLD = 1024` excludes overly wide values from histogram
boundaries entirely. We adopt a similar but more refined approach: **truncate** boundary
keys to a configurable maximum serialized size (`MAX_BOUNDARY_BYTES`, default: 256).

**Why not ICU/Collator sort keys?** ICU's `CollationKey.toByteArray()` produces sort key
bytes that can be safely truncated at any byte position (no multi-byte character issues),
making them attractive for fixed-size boundary prefixes. However, sort key byte ordering
follows the Unicode Collation Algorithm (UCA), which differs from the B-tree's key
ordering (`DefaultComparator` → `String.compareTo()`, which uses UTF-16 code unit order).
For example, UCA orders `"a" < "Z"` while `String.compareTo()` orders `"Z" < "a"`. This
mismatch would cause `findBucket()` to assign keys to incorrect buckets. The codebase
already uses `java.text.Collator` in `StringKeyNormalizer` for the nkbtree, but that
B-tree uses a different comparison model. For the sbtree histograms, we must preserve
the `DefaultComparator` ordering.

**Character-boundary-aware truncation:** Instead, truncation operates on the original
String value at character boundaries, then re-serializes the truncated string:

```
for each boundary key:
  serialized = serialize(key)
  if serialized.length > MAX_BOUNDARY_BYTES:
    if key is String:
      // Truncate the String at the last complete character whose serialized
      // form fits within MAX_BOUNDARY_BYTES.
      // For UTF8Serializer: walk backward from max offset to find the last
      //   byte that is NOT a UTF-8 continuation byte (10xxxxxx); truncate there.
      // For StringSerializer (UTF-16): truncate at a 2-byte boundary, ensuring
      //   we don't split a surrogate pair (check for leading surrogate 0xD800–0xDBFF).
      boundary = truncateStringAtCharBoundary(key, MAX_BOUNDARY_BYTES)
    else:
      boundary = truncateToPrefix(key, MAX_BOUNDARY_BYTES)
  if boundary equals previous boundary after truncation:
    merge adjacent buckets (sum frequencies and NDV)
```

Truncation preserves sort order for strings (a prefix is always ≤ the full string in
lexicographic order, and character-boundary truncation ensures the result is a valid
string). Bucket merging handles the rare case where two adjacent boundaries share a
prefix longer than `MAX_BOUNDARY_BYTES`.

| Key Type | Boundary Size | 128 Buckets (129 boundaries) | Fits (SV / MV)? |
|----------|--------------|-------------|-------|
| Integer (4 bytes) | 4 + 4 = 8 | 129 × 8 = 1032 | Yes / Yes |
| Long (8 bytes) | 4 + 8 = 12 | 129 × 12 = 1548 | Yes / Yes |
| 30-char ASCII (UTF8Serializer) | 4 + (2 + 30) = 36 | 129 × 36 = 4644 | Yes / Yes |
| 100-char ASCII (UTF8Serializer) | 4 + (2 + 100) = 106 | 129 × 106 = 13674 | **No** / **No** → Strategy 2 |
| 500-char ASCII (UTF8Serializer) | truncated to 4 + 256 = 260 | 129 × 260 = 33540 | **No** / **No** → Strategy 2 |
| Decimal (typical) | 4 + 12 = 16 | 129 × 16 = 2064 | Yes / Yes |

Boundary sizes include a 4-byte length prefix (for the serialized-key-with-length
encoding on the statistics page). The key payload size depends on the serializer:
UTF8Serializer = 2 (short header) + UTF-8 bytes; StringSerializer = 4 (int header) +
length × 2 (UTF-16). The examples above use UTF8Serializer (the more common path).

SV = single-value (~6043 bytes for boundaries, minus MCV key size), MV = multi-value
(~5019 bytes, minus MCV key size, due to 1024-byte HLL register array on page). When MV
boundaries exceed page 0, HLL spills to
page 1.

**Decimal note:** `DecimalSerializer` stores `scale (4 bytes) + length (4 bytes) +
unscaledValue.toByteArray() (variable)`. Typical serialized size is 10–20 bytes; even
pathological DECIMAL128 values reach only ~32 bytes. Since this is well below the
per-boundary budget (~50 bytes), **no truncation is applied to Decimal boundaries**.
The bucket-count-reduction fallback (Strategy 2) handles any theoretical edge cases.
Truncating BigDecimal by reducing scale (`setScale(n, RoundingMode.FLOOR)`) preserves
sort order but increases collisions between adjacent boundaries (especially for negative
values where FLOOR moves further from zero), and provides no benefit for keys already
well within the per-boundary budget. It is explicitly avoided.

#### Strategy 2 — Dynamic bucket count reduction (fallback)

If boundaries still exceed page space after truncation (e.g., very long common prefixes),
halve the bucket count. **Important:** `maxBoundarySpace` must be recalculated at each
step because reducing the bucket count also shrinks the `frequencies[]` and
`distinctCounts[]` arrays, freeing additional page space for boundaries:

```
targetBuckets = HISTOGRAM_BUCKETS  // e.g. 128

// maxBoundarySpace depends on targetBuckets because frequencies[] and distinctCounts[]
// scale with bucket count: each bucket contributes 8 + 8 = 16 bytes of array storage.
// hllSize = 1024 for multi-value indexes (HLL registers on page 0), 0 for single-value.
// mcvKeySize = serialized size of the MCV key (0 if no MCV, typically small).
maxBoundarySpace(B) = pagePayload - fixedFields - B × 8 - B × 8 - hllSize - mcvKeySize
// where pagePayload = pageSize - 28 (DurablePage header), fixedFields = 73

while totalSerializedBoundarySize(targetBuckets + 1) > maxBoundarySpace(targetBuckets):
  targetBuckets = targetBuckets / 2

if targetBuckets < 4:
  if isMultiValue AND hllSize > 0:
    // Retry without HLL on page 0 — spill HLL to page 1 instead
    hllSize = 0
    targetBuckets = HISTOGRAM_BUCKETS
    // re-run the loop above with hllSize = 0
  else:
    // Keys are too large for any useful histogram; stay in uniform mode
    return null
```

Most real-world string indexes have boundaries well under 256 bytes, so Strategy 1
preserves full 128-bucket resolution. Strategy 2 is the safety net for pathological cases.

### 5.4 Selectivity Estimation (Histogram Mode)

#### Scalar conversion for range interpolation (inspired by PostgreSQL)

Range interpolation requires computing a fractional position of a value within a bucket.
For numeric types, this is arithmetic. For strings and other non-numeric types, PostgreSQL
uses `convert_string_to_scalar()`: strip common prefix of the value and bucket bounds,
then encode the remaining suffix as a fractional double.

**Encoding must match comparison order.** The B-tree uses `DefaultComparator` →
`String.compareTo()`, which compares by **UTF-16 code unit** (`char`) values. PostgreSQL's
original implementation encodes raw bytes in the database encoding (typically UTF-8), but
UTF-8 byte order differs from UTF-16 code-unit order for non-ASCII characters (e.g., UCA
orders `"a" < "Z"` differently from `String.compareTo()`). To preserve monotonicity with
the B-tree's key ordering, the scalar conversion operates on `String.charAt()` values
(UTF-16 code units, range 0–65535) using base-65536 encoding, not on raw bytes.

```java
/**
 * Converts a key value to a double for interpolation within a bucket.
 * Used by range selectivity formulas.
 */
static double scalarize(Object value, Object lo, Object hi) {
  if (value instanceof Number n)
    return n.doubleValue();
  if (value instanceof Date d)
    return (double) d.getTime();
  if (value instanceof String s)
    return stringToScalar(s, (String) lo, (String) hi);
  // byte[], Decimal, etc. — similar conversions possible
  return 0.5;  // fallback: assume midpoint (uniform within bucket)
}

/**
 * String-to-scalar conversion using UTF-16 code units (matching
 * DefaultComparator → String.compareTo() ordering).
 * 1. Strip common char-prefix of all three strings
 * 2. Encode remaining chars as a base-65536 fractional double
 *    (each char position contributes charAt(i) / 65536^(i - prefix + 1))
 * Only the first few chars after the prefix contribute meaningfully
 * (double has ~15–17 significant decimal digits, so ~4 chars of base-65536
 * exhaust the mantissa precision).
 */
static double stringToScalar(String value, String lo, String hi) {
  int prefix = commonCharPrefixLength(value, lo, hi);
  return charEncode(value, prefix);
}

/**
 * Encodes chars starting at offset as a base-65536 fractional double.
 * At most 4 chars contribute meaningfully (65536^4 ≈ 1.8×10^19 > 2^53).
 */
private static double charEncode(String s, int startOffset) {
  double result = 0.0;
  double base = 1.0;
  int maxChars = Math.min(s.length() - startOffset, 4);
  for (int i = 0; i < maxChars; i++) {
    base *= 65536.0;
    result += s.charAt(startOffset + i) / base;
  }
  return result;
}
```

This makes range interpolation work uniformly for all key types, with ordering consistent
with the B-tree's `DefaultComparator`.

#### Selectivity formulas

With N+1 boundaries (PostgreSQL convention), bucket `B` spans
`[boundaries[B], boundaries[B+1])`:

```
// All formulas return clamp(result, 0.0, 1.0) — guards against any residual
// drift from incremental maintenance producing out-of-range values.

selectivity(f = X):
  if nonNullCount <= 0:
    return 0.0
  // Out-of-range short-circuit: X is outside [min, max] of the index.
  // Uses in-memory boundary data (no btree I/O). Returns a minimal non-zero
  // value rather than 0.0 to avoid division-by-zero in cost models that divide
  // by selectivity (e.g., estimatedRows = totalCount × selectivity).
  if X < boundaries[0] OR X > boundaries[bucketCount]:
    return 1.0 / nonNullCount
  // MCV short-circuit: if X matches the most common value, use its exact frequency
  // instead of the bucket-averaged estimate. Provides a marginal accuracy improvement
  // for edge cases where the MCV shares a bucket with lower-frequency values
  // (see simulation results in Section 5.5 — equi-depth histograms already handle
  // heavy skew well because high-frequency values get their own buckets).
  if mcvValue != null AND X equals mcvValue:
    return clamp(mcvFrequency / nonNullCount)
  B = findBucket(X)
  if frequencies[B] <= 0 OR distinctCounts[B] <= 0:
    return 0.0                // empty or drained bucket — no matches possible
  return clamp((1.0 / distinctCounts[B]) × (frequencies[B] / nonNullCount))

selectivity(f > X):
  if nonNullCount <= 0:
    return 0.0
  B = findBucket(X)
  // Single-value bucket optimization: when distinctCounts[B] == 1, the bucket
  // contains duplicates of a single value. The continuous interpolation model
  // breaks down (all entries are at a single point, not uniformly distributed).
  // Use discrete logic instead: if X >= that value, nothing in this bucket is > X.
  if distinctCounts[B] == 1 AND frequencies[B] > 0:
    bucketVal = boundaries[B]   // the single value in this bucket
    if comparator.compare(X, bucketVal) >= 0:
      fraction = 1.0           // X >= bucketVal → nothing above X in this bucket
    else:
      fraction = 0.0           // X < bucketVal → everything in this bucket is > X
  else:
    scaledX  = scalarize(X, boundaries[B], boundaries[B+1])
    scaledLo = scalarize(boundaries[B], boundaries[B], boundaries[B+1])
    scaledHi = scalarize(boundaries[B+1], boundaries[B], boundaries[B+1])
    if scaledHi == scaledLo:
      fraction = 0.5   // degenerate bucket, assume midpoint
    else:
      fraction = clamp((scaledX - scaledLo) / (scaledHi - scaledLo))
  remainingInB = (1.0 - fraction) × max(frequencies[B], 0)
  matchingRows = remainingInB + sum(max(frequencies[i], 0) for i in B+1..bucketCount-1)
  return clamp(matchingRows / nonNullCount)

selectivity(f >= X):
  // Logically: selectivity(f > X) + selectivity(f = X), clamped to [0, 1].
  // When X falls exactly on boundaries[B], the entire bucket B contributes
  // (fraction = 0.0, so remainingInB = frequencies[B]).
  // Implementation note: use a combined method that calls findBucket(X) once
  // and computes both the range and equality contributions from the same bucket,
  // avoiding the redundant binary search of calling the two formulas separately.
  B = findBucket(X)
  return clamp(rangeAboveFraction(B, X) + equalityFraction(B))

selectivity(X ≤ f ≤ Y):
  // Note: the inclusive endpoints are handled by continuous interpolation, which
  // assumes uniform distribution within each bucket. This is a standard
  // simplification (PostgreSQL uses the same approach): for continuous-ish
  // distributions, the probability mass at an exact point is negligible. For
  // discrete distributions with few distinct values per bucket, the error is
  // bounded by 1/distinctCounts[B] per endpoint. The clamp() to [0, 1] further
  // bounds any residual error.
  if nonNullCount <= 0:
    return 0.0
  Bx = findBucket(X)
  By = findBucket(Y)
  // Helper: compute the fractional position of V within bucket B.
  // Uses the single-value bucket optimization when distinctCounts[B] == 1
  // (same logic as selectivity(f > X)), otherwise continuous interpolation.
  fractionOf(V, B):
    if distinctCounts[B] == 1 AND frequencies[B] > 0:
      bucketVal = boundaries[B]
      if comparator.compare(V, bucketVal) < 0:  return 0.0   // V below the value
      if comparator.compare(V, bucketVal) > 0:  return 1.0   // V above the value
      return 0.5   // V equals the single value — assume half (discrete midpoint)
    scaledV  = scalarize(V, boundaries[B], boundaries[B+1])
    scaledLo = scalarize(boundaries[B], boundaries[B], boundaries[B+1])
    scaledHi = scalarize(boundaries[B+1], boundaries[B], boundaries[B+1])
    if scaledHi == scaledLo: return 0.5
    return clamp((scaledV - scaledLo) / (scaledHi - scaledLo))

  if Bx == By:
    // X and Y in the same bucket — interpolate the sub-range
    fracX = fractionOf(X, Bx)
    fracY = fractionOf(Y, Bx)
    rangeFraction = fracY - fracX
    matchingRows = rangeFraction × max(frequencies[Bx], 0)
  else:
    // Partial lower bucket (fraction of Bx above X)
    fractionX = fractionOf(X, Bx)
    lowerPart = (1.0 - fractionX) × max(frequencies[Bx], 0)
    // Full middle buckets
    middlePart = sum(max(frequencies[i], 0) for i in Bx+1..By-1)
    // Partial upper bucket (fraction of By up to Y)
    fractionY = fractionOf(Y, By)
    upperPart = fractionY × max(frequencies[By], 0)
    matchingRows = lowerPart + middlePart + upperPart
  return clamp(matchingRows / nonNullCount)

selectivity(f IS NULL):
  total = nonNullCount + stats.nullCount
  if total <= 0: return 0.0
  return clamp(stats.nullCount / total)

selectivity(f IS NOT NULL):
  total = nonNullCount + stats.nullCount
  if total <= 0: return 0.0
  return clamp(nonNullCount / total)

selectivity(f IN (v1, v2, ..., vN)):
  // Note: assumes distinct values. If vi == vj for some i != j, the result
  // overestimates (double-counts). This matches PostgreSQL's simplification;
  // the clamp() to [0, 1] bounds the error.
  return clamp(sum(selectivity(f = vi) for each vi))

selectivity(unknown — non-indexed predicate):
  return DEFAULT_SELECTIVITY   // configurable, default 0.1

// where clamp(x) = min(max(x, 0.0), 1.0)
```

**Negative frequency clamping:** After incremental maintenance, individual
`frequencies[B]` values can drift below zero if entries inserted before the histogram
was built are subsequently deleted. The `HistogramDelta` application (Section 5.6)
clamps each bucket: `newFreq = max(oldFreq + delta, 0)`, then recomputes
`nonNullCount = sum(clamped frequencies)` to maintain the invariant
`nonNullCount == sum(frequencies)`. (Tracking `nonNullCount` as an independent
aggregate counter would break this invariant: per-bucket clamping and aggregate
clamping produce different results when some buckets go negative.) The selectivity
formulas additionally clamp via `max(frequencies[B], 0)` as a safety net. Rebalance
(Section 5.7) recomputes all values from scratch, eliminating any accumulated drift.

**`findBucket(key)` implementation:** Binary search on `boundaries[0..bucketCount]`
using `DefaultComparator.INSTANCE`. Returns index `B` such that
`boundaries[B] <= key < boundaries[B+1]`. Keys below `boundaries[0]` → bucket 0.
Keys ≥ `boundaries[bucketCount]` → last bucket. For 128 buckets (129 boundaries):
7–8 comparisons. For histograms with very few buckets (≤ 8), a linear scan is used
instead of binary search — fewer comparisons with early exit and better branch
prediction. Boundaries are cached in memory via the `HistogramSnapshot` (Section 3.3),
so this is a pure in-memory operation with no I/O.

**Single-value bucket optimization:** When a bucket has `distinctCounts[B] == 1`, it
contains duplicates of exactly one distinct value. The continuous interpolation model
(uniform distribution within the bucket) breaks down — all entries are at a single
point. Range formulas switch to **discrete logic**: if `X >= bucketVal`, nothing in
the bucket is `> X` (fraction = 1.0); if `X < bucketVal`, everything is (fraction =
0.0). This is critical for low-NDV indexes (booleans, enums, status fields) where
equi-depth construction naturally produces `distinctCounts[B] = 1` for every bucket.
Without this optimization, `selectivity(f > 'active')` for a 2-value enum field would
use continuous interpolation and include a fraction of the `'active'` bucket, even
though no `'active'` entries satisfy `f > 'active'`.

**Out-of-range equality short-circuit:** For `selectivity(f = X)`, if `X` falls
outside `[boundaries[0], boundaries[bucketCount]]`, the value is known to be absent
from the index (it's below the minimum or above the maximum key). Instead of mapping
to the boundary bucket and returning a bucket-averaged estimate, return a minimal
non-zero value `1.0 / nonNullCount`. A non-zero result (rather than 0.0) avoids
division-by-zero in cost models that compute `estimatedRows = totalCount × selectivity`.
When exact out-of-range detection is needed (e.g., the histogram boundary is truncated
and the actual min/max differs), callers can use `btree.firstKey()` / `btree.lastKey()`
as a refinement (O(log N), cacheable for the duration of a single plan).

### 5.5 Histogram Construction

Built by a single sequential scan of the sorted key stream provided by the engine.

**Composite index handling:** For composite (multi-field) indexes, the histogram is built
on the **leading (first) field only**. During construction, each key from the stream is
mapped to its first component:

```java
Stream<Object> effectiveKeys;
if (keyDefinition.getFieldCount() > 1) {
  // Composite index → histogram on leading field only
  effectiveKeys = sortedKeys.map(k -> ((CompositeKey) k).getKeys().getFirst());
} else {
  effectiveKeys = sortedKeys;
}
```

This handles the common access pattern (prefix queries on the leading field). Full-key
equality uses `1 / NDV` from `IndexStatistics`. See Section 6.4 for details.

**Construction algorithm** (N+1 boundary convention):

```
Input:  sorted effective key stream, target bucket count B
Output: boundaries[B+1], frequencies[B], distinctCounts[B], mcvValue, mcvFrequency

nonNullCount = engine.size() - nullCount
if nonNullCount < HISTOGRAM_MIN_SIZE:
  return null                 // stay in uniform mode (too few non-null keys)

// Use cumulative threshold for even bucket distribution.
// Instead of a fixed targetBucketSize (which underfills early buckets and
// overfills the last), compute each bucket's split point as a fraction of total.
// Bucket b should end when totalSeen * B >= (b+1) * nonNullCount.
// Using cross-multiplication avoids integer division truncation.
// This distributes entries evenly: each bucket gets floor or ceil of
// nonNullCount / B entries.
totalSeen = 0
currentBucket = 0
currentCount = 0
currentNDV = 0
prevKey = SENTINEL
firstKey = null
// MCV tracking: one extra comparison per key transition (negligible overhead)
currentRunLength = 0          // length of current run of identical keys
mcvValue = null               // most common value seen so far
mcvFrequency = 0              // frequency of mcvValue

for each key in effectiveKeyStream:
  if firstKey == null:
    firstKey = key
    boundaries[0] = key       // lower bound of first bucket
  totalSeen++
  if key != prevKey:
    // MCV update: check if the completed run is the longest so far.
    // Guard against SENTINEL: on the first key transition (prevKey == SENTINEL),
    // currentRunLength is 0, so the check 0 > 0 is false — SENTINEL never
    // becomes the MCV. The explicit guard is defensive clarity.
    if prevKey != SENTINEL AND currentRunLength > mcvFrequency:
      mcvValue = prevKey
      mcvFrequency = currentRunLength
    currentRunLength = 0
    // Check if we should start a new bucket. Boundary is set at a key
    // transition point: the current bucket ends with prevKey, the next
    // bucket starts with key. This ensures:
    //   1. All duplicates of prevKey stay in the current bucket
    //   2. key becomes the lower bound of the next bucket (inclusive)
    //   3. No off-by-one: frequencies[currentBucket] excludes key
    if totalSeen * B >= (currentBucket + 1) * nonNullCount AND currentBucket < B - 1:
      boundaries[currentBucket + 1] = key   // lower bound of NEXT bucket
      frequencies[currentBucket] = currentCount
      distinctCounts[currentBucket] = currentNDV
      currentBucket++
      currentCount = 0
      currentNDV = 0
    currentNDV++
    prevKey = key
  currentCount++
  currentRunLength++

// Guard: empty key stream (all entries are null)
if firstKey == null:
  return null                 // no non-null keys — stay in uniform mode

// Final MCV check for the last run
if currentRunLength > mcvFrequency:
  mcvValue = prevKey
  mcvFrequency = currentRunLength

// Close last bucket
frequencies[currentBucket] = currentCount
distinctCounts[currentBucket] = currentNDV
boundaries[currentBucket + 1] = prevKey   // upper bound = max key
actualBucketCount = currentBucket + 1

// Handle degenerate case: all keys identical (NDV = 1)
// Only one bucket was ever used; collapse to 1 bucket explicitly.
if actualBucketCount == 1 AND boundaries[0] equals boundaries[1]:
  // Single-value histogram: one bucket spanning [key, key].
  // selectivity(f = key) = 1.0, selectivity(f = other) = 0.0.
  // findBucket() returns 0 for any input (both below-min and above-max cases).

// Apply boundary truncation (Section 5.3 Strategy 1)
// Then verify boundaries fit in a single page; reduce B if not (Strategy 2)
```

**Complexity:** O(N) single pass. Memory: O(B × max_boundary_key_size) — the
boundaries array holds B+1 keys, each up to `MAX_BOUNDARY_BYTES` after truncation.

**MCV-1 (Most Common Value):** The construction algorithm tracks the most common value
with negligible overhead: one comparison per key transition (when `key != prevKey`),
checking if the completed run of duplicates is the longest seen so far. The sorted key
stream guarantees that all duplicates of a value are contiguous, so a single `maxRunLength`
variable suffices — no hash map or heap needed. The MCV is stored in the
`EquiDepthHistogram` record (`mcvValue`, `mcvFrequency`) and persisted to the `.ixs` page
(Section 5.8). It is recomputed on each build/rebalance, not maintained incrementally —
`mcvFrequency` may stale between rebalances but remains a useful lower bound for the true
frequency (the MCV's bucket frequency can only grow or shrink by at most
`mutationsSinceRebalance` entries). The selectivity formula (Section 5.4) short-circuits
equality queries for the MCV, returning `mcvFrequency / nonNullCount` instead of the
bucket-averaged estimate.

**MCV accuracy impact (simulation results):** A simulation across 10 distributions (Zipf
with s=0.5–1.5, hotspot, uniform; NDV=50–10000; 100K entries; 128 buckets) showed that a
well-constructed equi-depth histogram **already handles skewed distributions effectively**:
high-frequency values receive their own oversized buckets with `distinctCounts[B] = 1`,
yielding exact selectivity without MCV. MCV provides measurable improvement only when
high-frequency values share buckets with lower-frequency values, which occurs mainly with
mild skew (Zipf s≤0.7), hotspot patterns, and uniform distributions. The weighted mean
absolute relative error (WMARE) improvement from MCV-1 was 0–3% across all tested
distributions. Higher MCV-K (K=10, 20, 100) was also simulated: gains scale roughly
linearly with K, with **no clear "knee" or sweet spot** — each additional MCV entry
contributes approximately the same marginal improvement. Given this, MCV-1 is included as
a near-zero-cost safety net (~20 LOC, 12 bytes + key on page) rather than a critical
accuracy feature. MCV-K (K>1) is deferred to future extensions pending real-world
evidence of equality estimation gaps (see Section 15).

**Adaptive bucket count:** The target bucket count is adjusted based on available
information to avoid both over-bucketing (too few entries per bucket for statistical
significance) and under-bucketing (wasted resolution for large indexes):

```
effectiveBuckets = HISTOGRAM_BUCKETS                            // e.g. 128
effectiveBuckets = min(effectiveBuckets, floor(sqrt(nonNullCount)))  // statistical significance
if NDV is known:
  effectiveBuckets = min(effectiveBuckets, NDV)                 // no more buckets than distinct values
effectiveBuckets = max(effectiveBuckets, 4)                     // absolute minimum
```

The `sqrt(nonNullCount)` cap ensures each bucket has at least `sqrt(nonNullCount)` entries
on average, providing meaningful per-bucket statistics:
- 1000 entries (at threshold) → ~31 buckets, ~32 entries each
- 10K entries → 100 buckets, ~100 entries each
- 16K+ entries → 128 buckets (full resolution)

The NDV cap avoids allocating 128-entry arrays for low-NDV indexes (e.g., a boolean field
with NDV = 2 only needs 2 buckets). For the initial build where NDV is not yet known, the
scan discovers the effective bucket count naturally (the split condition simply never fires
more than NDV − 1 times).

**Cumulative threshold vs fixed target:** A fixed `targetBucketSize = totalCount / B`
systematically underfills early buckets and overfills the last (e.g., 1000 entries with
128 buckets: integer division gives 7, so early buckets get 7 entries each while the last
bucket absorbs the remaining ~111 entries). The cumulative threshold
`totalSeen * B >= (currentBucket + 1) * nonNullCount` distributes entries evenly — each
bucket gets `floor(nonNullCount/B)` or `ceil(nonNullCount/B)` entries, matching the
standard streaming equi-depth construction used by PostgreSQL and other databases. The
cross-multiplication form avoids integer division truncation that could cause systematic
underfilling. Note: for indexes with > 2^53 / 128 entries the multiplication could
overflow `long`, but this is not a practical concern (would require 10^16 entries).

**Duplicate keys at boundary:** The bucket split check is inside the `key != prevKey`
guard, so a run of identical keys is never split across buckets. The bucket size may
exceed the cumulative threshold if a large run of duplicates straddles it — this is
intentional and preserves per-bucket NDV correctness.

**Low-NDV indexes:** When NDV < `HISTOGRAM_BUCKETS` (e.g., an enum field with 5 distinct
values), the algorithm naturally produces at most NDV buckets — each containing exactly
one distinct value. This yields an **exact** frequency histogram: `distinctCounts[i] = 1`
for every bucket, and `frequencies[i]` is the precise count of that value. No special-case
code is needed; the split condition simply never fires more than NDV − 1 times.

### 5.6 Incremental Maintenance

After every put/remove, the engine calls the histogram manager. Bucket boundaries remain
fixed; only frequencies change. Per-bucket `distinctCounts[]` are **NOT updated
incrementally** — only recomputed during build/rebalance.

**Global NDV tracking:**
- Single-value indexes: `distinctCount` always equals `totalCount` (trivially maintained).
- Multi-value indexes: `distinctCount` tracked incrementally via HyperLogLog sketch
  (Section 6.2). The HLL is updated on put (O(1) hash + register update) but not on
  remove (insert-only). Exact NDV recomputed on rebalance.

**Note:** For composite indexes, `findBucket()` operates on the leading field extracted
from the key. The histogram manager's `onPut()` / `onRemove()` receives the full key from
the engine and internally calls `extractLeadingField(key)` (identity for single-field
indexes, `((CompositeKey) key).getKeys().getFirst()` for composite indexes).

#### On engine `put(key, wasInsert)`:

The `wasInsert` flag is provided by the engine: for single-value indexes, it is the return
value of `sbTree.put()` (true when a new key was inserted, false when an existing key's
value was updated in-place). For multi-value indexes, every `put()` inserts a new composite
entry, so `wasInsert` is always `true`.

```
delta = atomicOperation.getOrCreateDelta(engineId)
delta.mutationCount++
effectiveKey = extractLeadingField(key)   // identity for single-field indexes

// Read current snapshot from CHM to determine bucket (read-only, no modification)
snapshot = cache.get(engineId)

// For single-value updates (wasInsert == false), the key already exists in the index.
// The B-tree's treeSize is unchanged, so totalCount/distinctCount must not change either.
// Only wasInsert == true (genuine new entry) increments counters and frequencies.
if wasInsert:
  delta.totalCountDelta++
  if key is null:
    delta.nullCountDelta++
  else:
    if snapshot is not null AND snapshot.histogram is not null:
      B = findBucket(effectiveKey, snapshot.histogram)  // in-memory binary search, O(log B)
      delta.frequencyDeltas[B]++
  if single-value index:
    delta.distinctCountDelta++
  else:
    // Lazy HLL: only update if the snapshot has an HLL sketch (i.e., the index
    // has crossed HISTOGRAM_MIN_SIZE and built its first histogram). Below the
    // threshold, the HLL is null and this is a no-op (Section 6.2).
    if snapshot is not null AND snapshot.hllSketch is not null:
      delta.hllSketch.add(hash(effectiveKey))    // O(1) HLL register update (Section 6.2)
else:
  // Update-in-place for single-value index: no counter changes.
  // The HLL is not updated either — the key is already counted from the original insert.
  // Multi-value indexes never reach this branch (wasInsert is always true).
  pass
```

#### On engine `remove(key)`:

```
delta = atomicOperation.getOrCreateDelta(engineId)
delta.mutationCount++
effectiveKey = extractLeadingField(key)

snapshot = cache.get(engineId)   // may be null if engine was just created or cache miss

delta.totalCountDelta--
if key is null:
  delta.nullCountDelta--
else:
  if snapshot is not null AND snapshot.histogram is not null:
    B = findBucket(effectiveKey, snapshot.histogram)
    delta.frequencyDeltas[B]--
if single-value index:
  delta.distinctCountDelta--
```

#### On transaction commit (after `commitIndexes()` succeeds):

```
for each (engineId, delta) in atomicOperation.histogramDeltas:
  cache.compute(engineId, (id, old) -> {
    if (old == null)
      return null;     // engine was closed/deleted — discard delta silently

    return applyDelta(old, delta);
    // applyDelta (produces a new immutable HistogramSnapshot):
    //   - stats counters: add deltas, clamp to >= 0
    //   - histogram frequencies: if old.version == delta.snapshotVersion (i.e.,
    //     the snapshot has not been replaced by a rebalance since the delta was
    //     computed), add per-bucket deltas to frequencies and clamp each to >= 0.
    //     If old.version != delta.snapshotVersion (rebalance occurred), discard
    //     frequencyDeltas — the new histogram's frequencies are already accurate
    //     from the full key scan; the stale per-bucket deltas (indexed against old
    //     boundaries) cannot be meaningfully re-mapped. Only the scalar deltas
    //     (totalCountDelta, nullCountDelta, distinctCountDelta, mutationCount,
    //     hllSketch) are applied. Subsequent incremental updates will naturally
    //     correct the new histogram's frequencies.
    //   - nonNullCount: recompute as sum(clamped frequencies) — NOT tracked
    //     incrementally, to maintain the invariant nonNullCount == sum(frequencies)
    //     (independent clamping of per-bucket frequencies and a separate aggregate
    //     counter can diverge; see Section 5.4 "Negative frequency clamping")
    //   - mutationsSinceRebalance: += delta.mutationCount
    //   - multi-value: clone old HLL sketch, merge delta.hllSketch into clone;
    //     update distinctCount = (long) mergedHll.estimate() — the persisted HLL
    //     retains all historical inserts, so the estimate is always meaningful
    //     (no stale-on-restart risk; see Section 6.2)
  })
  // cache.compute() ensures atomicity with concurrent rebalance snapshots.
  // The null guard handles the case where the engine was closed/deleted between
  // the B-tree mutation and commit (e.g., concurrent DROP INDEX). Returning null
  // from compute() is a no-op since the entry is already absent.
  dirtyMutations += delta.mutationCount
  if dirtyMutations >= PERSIST_BATCH_SIZE:
    flush current snapshot to .ixs page (within a separate AtomicOperation)
    dirtyMutations = 0
```

On rollback: the `AtomicOperation` (including its delta map) is discarded. The CHM is
never modified — no invalidation needed. See Section 3.2.

**Overhead per mutation:** For inserts (`wasInsert == true`): ~7 in-memory key comparisons
(binary search in `findBucket()`) + 1 delta update (pure in-memory). For single-value
updates (`wasInsert == false`): negligible (no `findBucket()`, no counter changes). No CHM
write, no WAL entry until commit. On commit: 1 CHM `compute()` per affected engine.
See Section 3.2 for batched persistence details.

### 5.7 Automatic Background Rebalancing

#### When to build or rebalance

Both the initial histogram build (Uniform → Histogram transition, Section 4.2) and
rebalance are evaluated in a single `maybeScheduleHistogramWork()` method, called from
`getHistogram()` on each planner read:

```
maybeScheduleHistogramWork():
  nonNullCount = totalCount - nullCount
  if nonNullCount < HISTOGRAM_MIN_SIZE:
    return    // too few non-null keys for histogram

  if histogram is null AND totalCountAtLastBuild == 0:
    schedule background initial build     // Uniform → Histogram transition
    return

  rebalanceThreshold = max(
      min(totalCountAtLastBuild × REBALANCE_MUTATION_FRACTION,
          MAX_REBALANCE_MUTATIONS),                // cap for very large indexes
      MIN_REBALANCE_MUTATIONS)                     // floor for small indexes
  // Drift-biased trigger: if any bucket frequency has been clamped to 0
  // (indicating accumulated negative drift from deletions of pre-histogram
  // entries), halve the threshold to trigger rebalance sooner. Checked via a
  // boolean flag `hasDriftedBuckets` set by applyDelta() when any frequency
  // is clamped from negative to 0 (cheap — no scan of frequencies[] on read).
  if hasDriftedBuckets:
    rebalanceThreshold = rebalanceThreshold / 2
  if mutationsSinceRebalance > rebalanceThreshold:
    schedule background rebalance
```

**Cold-start behavior:** `mutationsSinceRebalance` and `totalCountAtLastBuild` are
persisted to the `.ixs` page (Section 5.8). On restart after a crash, these values are
loaded from disk. If `mutationsSinceRebalance` exceeds the rebalance threshold (because
mutations accumulated before the crash were persisted but a rebalance never ran), the
first planner read after restart will trigger a background rebalance — no special-case
code needed. Additionally, `openStatsFile()` proactively checks the threshold after
loading the snapshot into the CHM cache: if `mutationsSinceRebalance > rebalanceThreshold`,
it schedules a background rebalance immediately. This ensures that write-heavy databases
that see no queries for a long time after restart still get their histograms refreshed,
rather than waiting for the first planner read.

`REBALANCE_MUTATION_FRACTION` defaults to `0.3`. `MIN_REBALANCE_MUTATIONS` defaults to
`1000` (prevents thrashing on small indexes just above the histogram threshold).
`MAX_REBALANCE_MUTATIONS` defaults to `10_000_000` — caps the trigger for very large
indexes (100M+ entries) where 30% would mean 30M mutations before rebalance, causing a
potentially expensive O(N) scan. The cap ensures rebalance happens at most every 10M
mutations regardless of index size.
Check on planner read (not on every put/remove) to avoid overhead on write-heavy workloads.

#### Threading model

Rebalance runs on the **IO executor** (`YouTrackDBInternalEmbedded.getIoExecutor()`) — a
scaling thread pool (`ScalingThreadPoolExecutor`, 1 to N threads where N defaults to CPU
count, configurable via `GlobalConfiguration.EXECUTOR_POOL_IO_MAX_SIZE`) designed for
background I/O tasks. This is preferred over the `fuzzyCheckpointExecutor` (single-thread,
checkpoint-critical) and the WAL commit executor (single-thread, write-path-critical).

The planner does **not** block waiting for rebalance — it returns the current (stale)
histogram immediately and the rebalance runs asynchronously.

An `AtomicBoolean rebalanceInProgress` flag prevents concurrent rebalances for the same
index. If a rebalance fails (any exception — `IOException`, `ClosedChannelException`,
etc.), the flag is reset in a `finally` block. To prevent repeated failed attempts under
persistent failure conditions (e.g., sustained I/O errors), a `lastRebalanceFailureTime`
field records the timestamp of the most recent failure. Rebalance is only re-triggered
if at least `REBALANCE_FAILURE_COOLDOWN` (default: 60 seconds) has elapsed since the
last failure. On success, `lastRebalanceFailureTime` is reset to 0.

**Storage-level rebalance throttling:** A `Semaphore` on `DiskStorage` (field
`rebalanceSemaphore`, permits = `MAX_CONCURRENT_REBALANCES`, default:
`max(2, Runtime.getRuntime().availableProcessors() / 4)`) limits the number of
concurrent rebalance tasks across all indexes. Each rebalance acquires a permit before
starting the key scan (step 4) and releases it in the `finally` block (step 8). If no
permit is available, the rebalance is deferred — `maybeScheduleHistogramWork()` will
re-trigger it on the next planner read. This prevents bulk imports (which can push many
indexes past their rebalance thresholds simultaneously) from saturating the IO executor
with O(N) key scans, leaving capacity for other background tasks (checkpoints, WAL
operations).

#### How to rebalance

1. Check cooldown: if `System.currentTimeMillis() - lastRebalanceFailureTime <
   REBALANCE_FAILURE_COOLDOWN`, skip this trigger
2. Set `rebalanceInProgress = true` (CAS from false; if already true, skip)
3. Check that the engine is still open (not closed or deleted); if not, reset flag and
   return — the engine may have been dropped while a rebalance was queued
4. Acquire the engine's key stream via a standard read operation (live cache, not MVCC
   snapshot — accepted inconsistency for approximate statistics)
5. Scan sorted keys → build new equi-depth boundaries, frequencies, per-bucket NDV,
   and exact `distinctCount` (global NDV from scan)
6. Write new histogram to statistics page within an `AtomicOperation`:
   - Exact `distinctCount` from scan (global NDV)
   - Reset `mutationsSinceRebalance = 0`
   - Update `totalCountAtLastBuild = totalCount`
7. Swap into the CHM cache via `cache.compute(engineId, ...)` with incremented `version`;
   reset `lastRebalanceFailureTime = 0`. **Scalar counter handling in the compute lambda:**
   ```java
   cache.compute(engineId, (id, old) -> {
     if (old == null) return null;  // engine deleted during rebalance

     // Preserve incrementally-maintained counters from the CHM snapshot
     // (most up-to-date — includes any concurrent commits after the scan):
     long currentTotal = old.stats().totalCount();
     long currentNull  = old.stats().nullCount();

     // Use exact NDV from scan (replaces approximate HLL estimate):
     long exactNDV = scannedDistinctCount;

     var newStats = new IndexStatistics(currentTotal, exactNDV, currentNull);
     return new HistogramSnapshot(
         newStats,
         newHistogram,          // fresh from scan
         0,                     // reset mutationsSinceRebalance
         currentTotal,          // totalCountAtLastBuild = current total
         old.version() + 1,     // increment version
         newHllSketch           // fresh HLL from scan (multi-value); null for single-value
     );
   });
   ```
   **Why preserve `totalCount`/`nullCount` from CHM?** Concurrent transactions may
   commit between the scan (step 4) and the `compute()` call (step 7), applying scalar
   deltas to the CHM snapshot. Using the scanned values would silently discard those
   deltas. The incrementally-maintained CHM values are always >= as current as the scan.
   For `distinctCount`, the scan's exact value is preferred over the approximate HLL
   estimate in the CHM — the one-time staleness from concurrent commits (at most a few
   missing distinct keys) is far smaller than the HLL's ~3.25% systematic error.
8. Set `rebalanceInProgress = false` (in `finally` block); on failure, record
   `lastRebalanceFailureTime = System.currentTimeMillis()` before resetting

**Engine close/delete during rebalance:** If the engine is closed or deleted while
a background rebalance is in progress, the key stream will fail with a
`ClosedChannelException` or similar I/O error. The `finally` block (step 8) resets
`rebalanceInProgress` regardless. The exception is caught and logged (not propagated) —
a background task must not crash the IO executor thread pool.

**Concurrency:** During rebalance, normal put/remove accumulates deltas against the old
snapshot. After the rebalance completes, it installs the new snapshot via
`cache.compute(engineId, ...)` with an incremented **version counter**. Concurrent
transaction commits also call `cache.compute()` (applying deltas to the current snapshot).

A race between rebalance and commit is handled by version checking in `applyDelta()`.
Each `HistogramDelta` records `snapshotVersion` — the version of the snapshot whose
boundaries were used for `findBucket()` during delta accumulation. On commit,
`applyDelta()` compares `delta.snapshotVersion` with the current snapshot's version:

- **Version matches** (no rebalance since delta was computed): apply `frequencyDeltas[]`
  to the histogram's frequencies normally. The bucket layout is unchanged, so the
  per-bucket indices are valid.
- **Version differs** (rebalance occurred): **discard `frequencyDeltas[]`** entirely. The
  new histogram's frequencies are already accurate from the rebalance's full key scan.
  The stale per-bucket deltas were indexed against the old bucket boundaries and cannot
  be meaningfully re-mapped to the new layout without the original keys (which the delta
  does not store). Only the scalar deltas (`totalCountDelta`, `nullCountDelta`,
  `distinctCountDelta`, `mutationCount`, `hllSketch`) are applied — these are
  bucket-independent. Subsequent incremental updates will naturally correct the new
  histogram's per-bucket frequencies.

This design ensures that a rebalance is never silently overwritten by a stale delta, and
the accuracy cost (briefly stale per-bucket frequencies for mutations during the rebalance
window) is minimal and self-correcting.

**Complexity:** O(N) sequential scan. For 1M entries: 2,000–10,000 page reads, typically
< 1 second on SSD.

#### Why not split/merge individual buckets?

Rejected. Split/merge is complex (variable-length boundary array manipulation), degrades
boundary quality over many cycles, and a full O(N) sequential rebuild is fast enough for
YouTrackDB index sizes.

### 5.8 Statistics Storage

The `IndexHistogramManager` extends `DurableComponent` with its own file (`.ixs`
extension). `DurableComponent` provides page/file utilities (`addFile()`, `openFile()`,
`deleteFile()`, `loadPageForWrite()`, `loadPageForRead()`); the histogram manager
implements its own lifecycle methods on top of these primitives.

The `.ixs` extension **must** be registered in `DiskStorage.ALL_FILE_EXTENSIONS`
(line 199) for proper database backup, restore, drop, and compaction.

This approach means:
- B-tree untouched — no entry point page changes, no format migration
- Independent lifecycle — statistics can be rebuilt without touching the B-tree
- Loads on demand — counter updates via write cache + WAL
- Simple migration — missing `.ixs` file on database open → create fresh, populate lazily

**Database export/import:** `.ixs` files are **not exported**. On import, the histogram
manager detects the missing file and creates a fresh one, scheduling a background build
if the index exceeds `HISTOGRAM_MIN_SIZE`. This avoids exporting potentially stale
statistics and keeps the export format simpler. The rebuild cost is a one-time O(N) scan
per index on import. For multi-value indexes, the HLL sketch is also rebuilt from scratch
during the background build (populated from the sorted key stream).

**Statistics page layout (page 0):**

Offsets are absolute from page start (DurablePage header occupies bytes 0–27;
NEXT_FREE_POSITION = 28), matching the convention used in Section 2.1.

```
Offset  Field                     Size
28      formatVersion             4 bytes (int)
32      serializerId              1 byte  (identifies boundary key type for deserialization;
                                         for composite indexes this is the leading field's
                                         serializer, not CompositeKeySerializer)
33      totalCount                8 bytes (long — includes nulls)
41      distinctCount             8 bytes (long)
49      nullCount                 8 bytes (long)
57      mutationsSinceRebalance   8 bytes (long)
65      totalCountAtLastBuild     8 bytes (long)
73      bucketCount               4 bytes (int — 0 if no histogram)
77      nonNullCount              8 bytes (long — histogram sum of frequencies)
85      hllRegisterCount          4 bytes (int — 0 for single-value indexes;
                                         1024 for multi-value with p=10)
89      mcvFrequency              8 bytes (long — 0 if no MCV; exact frequency of most
                                         common value at build/rebalance time)
97      mcvKeyLength              4 bytes (int — 0 if no MCV; serialized size of mcvValue)
101     mcvKey                    mcvKeyLength bytes (serialized MCV key; absent if 0)
...     boundaries[]              variable ((bucketCount + 1) serialized keys,
                                  each prefixed with 4-byte length)
...     frequencies[]             bucketCount × 8 bytes (long[])
...     distinctCounts[]          bucketCount × 8 bytes (long[])
...     hllRegisters[]            hllRegisterCount × 1 byte (byte[] — HLL register array;
                                  only present for multi-value indexes; each byte stores
                                  the max leading-zero count for that register's hash bucket)
─────────────────────────────────────────────────────────────────────
Total fixed fields: 101 - 28 = 73 bytes (payload; +12 for MCV fields).
MCV key is variable-length but typically small (same type as boundary keys).
```

When the histogram data plus HLL registers exceed page 0 capacity (rare — only for
multi-value indexes with large variable-length boundary keys after bucket reduction), the
HLL registers spill to **page 1** of the `.ixs` file. The `hllRegisterCount` field on
page 0 indicates the register count; a flag bit in `formatVersion` indicates whether the
registers are on page 0 (inline) or page 1 (overflow). Single-value indexes have
`hllRegisterCount = 0` and no HLL data on disk.

**Updated page budget** (8 KB page, 128-bucket target, multi-value index, integer MCV):

```
Available payload:    8164 bytes  (8192 - 28 DurablePage header)
Fixed fields:           73 bytes  (including mcvFrequency + mcvKeyLength)
mcvKey (int):            4 bytes  (typical; varies by key type)
frequencies[128]:     1024 bytes
distinctCounts[128]:  1024 bytes
hllRegisters[1024]:   1024 bytes
───────────────────────────────────
Remaining for 129 boundaries: ~5015 bytes → ~38 bytes per boundary
```

For integer keys (8 bytes per boundary): 129 × 8 = 1032 < 5015. Fits easily.
For single-value indexes (no HLL): the budget is ~6039 bytes for boundaries.
The MCV key's impact on the budget is negligible for typical key types (4–12 bytes);
for variable-length keys it is truncated to MAX_BOUNDARY_BYTES like boundaries.

---

## 6. Multi-Value and Composite Indexes

### 6.1 Multi-Value: Why It Works Naturally

The engine holds the original key before `CompositeKey` wrapping:

```java
// BTreeMultiValueIndexEngine.put() — current code:
public void put(AtomicOperation op, Object key, RID value) {
  if (key != null) {
    svTree.put(op, createCompositeKey(key, value), value);
  } else {
    nullTree.put(op, value, value);
  }
}
```

The original `key` is available in the method body **before** `createCompositeKey()` wraps
it. This plan adds a `histogramManager.onPut(op, key, ...)` call here (Section 5, Step 5),
passing the original key. Histogram boundaries are on original keys — exactly what the
planner needs.

### 6.2 NDV for Multi-Value

`distinctCount != totalCount` for multi-value indexes. Global NDV is maintained
incrementally via a **HyperLogLog (HLL) sketch** — a compact probabilistic data
structure (~1 KB for 1024 registers, p=10) with ~3.25% standard error
(`1.04/sqrt(1024)`; see [Flajolet et al.](https://algo.inria.fr/flajolet/Publications/FlFuGaMe07.pdf)).

**How it works:**
- On each `onPut()` with a non-null key, the key is hashed and the HLL sketch registers
  are updated (one hash + one max operation — O(1), negligible overhead).
- `onRemove()` does **not** update the HLL (HLL is insert-only by design). This means
  NDV can only grow between rebalances, never shrink. This is acceptable — overestimating
  NDV makes equality selectivity (`1/NDV`) slightly pessimistic, which is safer than
  optimistic.
- On rebalance, exact NDV is computed from the sorted key stream (counting distinct
  consecutive keys) and the HLL is reset with the new exact value.
- The HLL sketch lives in the `HistogramSnapshot` (in-memory) and is **persisted** to the
  `.ixs` statistics page as a `byte[1024]` register array (Section 5.8). On restart, the
  HLL is loaded from the persisted page — it retains all historical inserts, so
  `distinctCount` is immediately accurate after restart.
- **`distinctCount` IS updated from `hll.estimate()` on every commit.** On commit, the
  delta's HLL sketch is merged into the snapshot's HLL, then `distinctCount` is set to
  `(long) hll.estimate()`. Since the HLL is persisted and survives restarts, there is no
  stale-on-restart risk — the HLL always reflects all inserts since the last rebalance.
  Between rebalances, `distinctCount` tracks NDV with ~3.25% error (HLL standard error).
  On rebalance, exact NDV replaces the approximate value.
- For single-value indexes, NDV = totalCount (trivial), so the HLL is not used.

**Lazy HLL initialization:** The HLL sketch is not allocated for multi-value indexes
until `nonNullCount` (= `totalCount - nullCount`) first crosses `HISTOGRAM_MIN_SIZE`.
Below this threshold, the index is in uniform mode and `distinctCount` is not used for
histogram selectivity. The `HistogramSnapshot.hllSketch` field remains `null`, and
`onPut()` skips the HLL register update. When the threshold is crossed and the first
histogram build runs, the HLL is populated from the sorted key stream (same as a
rebalance rebuild — Section 6.2.1). This avoids allocating 1 KB per multi-value index
for small indexes that may never reach histogram mode.

**HLL in HistogramDelta:** The delta carries a per-transaction HLL sketch (~1 KB) instead
of a set of individual key hashes. On each `onPut()`, the key hash is inserted directly
into the delta's HLL sketch (same O(1) cost). On commit, the delta's HLL sketch is merged
into the snapshot's HLL sketch (O(registers) ≈ 1024 operations). This approach caps delta
memory at ~1 KB regardless of transaction size, avoiding unbounded `LongSet` growth for
large transactions (e.g., a bulk import of 1M entries would require ~8 MB for a `LongSet`
vs ~1 KB for an HLL sketch).

**HLL persistence:** The HLL register array (1024 bytes for p=10) is stored on the `.ixs`
statistics page alongside the histogram data (Section 5.8). For most index configurations,
the 1 KB fits comfortably on page 0 (e.g., 128-bucket integer index leaves ~5 KB free).
When the histogram boundaries consume most of page 0 (e.g., long string boundaries after
bucket reduction), the HLL spills to page 1 of the `.ixs` file — `DurableComponent`
already supports multi-page files via `loadPageForWrite(op, fileId, 1, true)`. Single-value
indexes do not write HLL data (the field is absent from their `.ixs` page). The HLL is
flushed to disk as part of the batched persistence mechanism (Section 3.2) — no additional
I/O beyond what already occurs for counter persistence.

Per-bucket `distinctCounts[]` remain build-time-only (recomputed during rebalance from
the sorted stream). The HLL tracks only the global NDV for `IndexStatistics.distinctCount`.

### 6.2.1 HLL Implementation Design

The HLL sketch is implemented as a single self-contained class with no external
dependencies. The algorithm follows Flajolet et al.'s original HyperLogLog with the
standard bias corrections from the paper.

**New file:** `core/.../index/engine/HyperLogLogSketch.java` (~150 LOC)

#### Parameters

```
p  = 10                          // precision parameter (number of index bits)
m  = 1 << p = 1024               // number of registers
ALPHA_M = 0.7213 / (1 + 1.079 / m)  // bias correction constant for m = 1024
                                     // ≈ 0.7213 / 1.001053 ≈ 0.72054
```

The choice of p=10 yields:
- **1024 registers** — each stored as one byte (max value 64 fits in 7 bits; byte is
  simplest and sufficient)
- **~3.25% standard error**: `1.04 / sqrt(1024) ≈ 0.0325`
- **1 KB memory** for the register array (plus ~24 bytes object overhead)

#### Data structure

```java
/**
 * HyperLogLog sketch for approximate distinct value counting.
 *
 * <p>Compact probabilistic data structure with O(1) insert, O(m) merge and estimate,
 * and fixed 1 KB memory footprint. Used exclusively for multi-value index NDV tracking
 * (Section 6.2). Single-value indexes do not use this class.
 *
 * <p>Not thread-safe. External synchronization is provided by the delta accumulation
 * model (Section 3.2): each transaction has its own delta sketch, and the snapshot
 * sketch is updated atomically via {@code cache.compute()}.
 */
public final class HyperLogLogSketch {

  private static final int P = 10;
  private static final int M = 1 << P;              // 1024
  private static final double ALPHA_M = 0.7213 / (1.0 + 1.079 / M);

  private final byte[] registers = new byte[M];      // max leading-zero counts
}
```

#### Hash function

The existing `MurmurHash3` class (`com.jetbrains.youtrackdb.internal.common.hash.MurmurHash3`)
is already present in the codebase (currently unused). It provides
`murmurHash3_x64_64(byte[] key, int seed)` → 64-bit hash. This is the hash function
used by the HLL sketch.

**Key hashing:** The HLL operates on `long` hash values, not on raw key objects. The
engine's `onPut()` path hashes the effective key (leading field for composite indexes)
before passing it to the HLL:

```java
// In IndexHistogramManager.onPut():
long hash = hashKey(effectiveKey);
delta.hllSketch.add(hash);

/**
 * Hashes a key value to a 64-bit long for HLL register update.
 * Uses MurmurHash3 on the serialized key bytes.
 */
private long hashKey(Object key) {
  // Serialize the key to bytes using the index's key serializer.
  // The serializer is already available from the histogram manager's construction.
  byte[] bytes = keySerializer.serializeNativeAsWhole(key);
  return MurmurHash3.murmurHash3_x64_64(bytes, 0x9747b28c);  // fixed seed
}
```

The fixed seed `0x9747b28c` matches the default used by Guava and other MurmurHash3
implementations. Any fixed seed works — the HLL only requires good bit distribution,
which MurmurHash3 provides regardless of seed.

#### Register update (`add`)

The 64-bit hash is split into two parts:
- **Low p bits** → register index (which of the 1024 registers to update)
- **Remaining 64 − p = 54 bits** → the value whose leading zeros are counted

```java
/**
 * Adds a hashed value to the sketch. O(1).
 *
 * @param hash 64-bit hash of the key (from MurmurHash3)
 */
public void add(long hash) {
  // Low p bits select the register index (0..1023)
  int index = (int) (hash & (M - 1));

  // Remaining bits: count leading zeros + 1 (the "rho" function).
  // Shift right by p to isolate the remaining 54 bits in positions 0..53
  // of the 64-bit long. Long.numberOfLeadingZeros() counts from bit 63, so
  // it includes p spurious zero bits (54..63) from the shift. Subtract p to
  // get the true leading-zero count within the 54-bit value, then add 1
  // (rho is 1-indexed: leftmost bit set → rho = 1, not 0).
  //
  // Sentinel: OR with 1L sets bit 0, capping rho at 64 - p = 54 when all
  // 54 remaining bits are zero (probability 2^-54, essentially impossible).
  // Without the sentinel, rho would be 64 - 10 + 1 = 55 for the all-zeros case.
  long w = hash >>> P;
  int rho = Long.numberOfLeadingZeros(w | 1L) - P + 1;

  // Update register to max(current, rho)
  if (rho > registers[index]) {
    registers[index] = (byte) rho;
  }
}
```

**`rho` value range:** 1 to 54 (since we have 54 remaining bits). When bit 53 of `w`
(the leftmost bit of the 54-bit value) is set, `Long.numberOfLeadingZeros` returns 10
(= P), giving `rho = 10 - 10 + 1 = 1`. When only bit 0 is set (the sentinel),
`Long.numberOfLeadingZeros` returns 63, giving `rho = 63 - 10 + 1 = 54`. The theoretical
maximum of 54 fits in one byte (max value 127). In practice, `rho > 30` is astronomically
rare (requires 2^30 ≈ 1 billion distinct keys hashing to the same register).

#### Cardinality estimation (`estimate`)

```java
/**
 * Returns the estimated number of distinct values added to this sketch.
 * Uses the standard HyperLogLog algorithm with small-range and large-range
 * corrections from Flajolet et al.
 *
 * O(m) — iterates all 1024 registers.
 */
public long estimate() {
  // 1. Compute the raw HyperLogLog estimate (harmonic mean of 2^(-register[i]))
  double sum = 0.0;
  int zeroCount = 0;    // number of registers still at 0 (never updated)
  for (int i = 0; i < M; i++) {
    sum += 1.0 / (1L << registers[i]);
    if (registers[i] == 0) {
      zeroCount++;
    }
  }
  double rawEstimate = ALPHA_M * M * M / sum;

  // 2. Small-range correction (linear counting)
  //    When many registers are zero, the raw estimate is biased high.
  //    Use linear counting: m * ln(m / zeroCount), which is more accurate
  //    for small cardinalities.
  if (rawEstimate <= 2.5 * M && zeroCount > 0) {   // threshold: 2560
    return Math.round(M * Math.log((double) M / zeroCount));
  }

  // 3. Large-range correction
  //    For very large cardinalities approaching 2^64, hash collisions cause
  //    underestimation. Apply the correction: -2^64 * log(1 - E / 2^64).
  //    In practice this threshold (~2^63) is never reached for database indexes,
  //    but included for algorithmic completeness.
  double TWO_POW_64 = Math.pow(2, 64);
  if (rawEstimate > TWO_POW_64 / 30.0) {
    return Math.round(-TWO_POW_64 * Math.log(1.0 - rawEstimate / TWO_POW_64));
  }

  // 4. Normal range — raw estimate is unbiased
  return Math.round(rawEstimate);
}
```

**Accuracy characteristics for p=10 (1024 registers):**

| True NDV | Expected error | Correction used |
|----------|---------------|-----------------|
| 1–2,560 | < 1% | Small-range (linear counting) |
| 2,560–5.7×10^17 | ~3.25% (1.04/√1024) | None (raw estimate) |
| > 5.7×10^17 | ~3.25% | Large-range (never reached) |

#### Merge

Used on transaction commit to merge the delta HLL into the snapshot HLL, and during
rebalance to populate from the key stream.

```java
/**
 * Merges another sketch into this one. O(m).
 * After merge, this sketch's estimate reflects the union of both input sets.
 */
public void merge(HyperLogLogSketch other) {
  for (int i = 0; i < M; i++) {
    if (other.registers[i] > registers[i]) {
      registers[i] = other.registers[i];
    }
  }
}
```

Merge is commutative, associative, and idempotent — the order of merging transaction
deltas does not affect the result.

#### Clone

Used during delta application to create a fresh snapshot HLL without mutating the old
snapshot (immutability contract, Section 3.3).

```java
/**
 * Creates a deep copy of this sketch. O(m).
 */
public HyperLogLogSketch clone() {
  var copy = new HyperLogLogSketch();
  System.arraycopy(this.registers, 0, copy.registers, 0, M);
  return copy;
}
```

#### Reset on rebalance

During rebalance, the HLL is rebuilt from scratch by iterating the sorted key stream.
This produces an exact-at-build-time HLL that reflects the current index contents
(removing the insert-only drift from deletions).

```java
/**
 * Resets all registers to zero and re-populates from the given key stream.
 * Called during histogram rebalance (Section 5.7).
 *
 * @param sortedKeys non-null keys from the index (may contain duplicates)
 * @param hashFunction the same hash function used by onPut()
 */
public void rebuildFrom(Stream<Object> sortedKeys, ToLongFunction<Object> hashFunction) {
  Arrays.fill(registers, (byte) 0);
  sortedKeys.forEach(key -> add(hashFunction.applyAsLong(key)));
}
```

Note: the rebalance also computes exact NDV by counting distinct consecutive keys in the
sorted stream (Section 5.7 step 5). The HLL rebuild runs in the same pass — each key is
both counted for exact NDV and fed to `add()` for the HLL. The exact NDV is written to
`IndexStatistics.distinctCount`; the rebuilt HLL replaces the snapshot's sketch. Between
rebalances, `distinctCount` is updated from `hll.estimate()` on each commit.

#### Serialization (for `.ixs` page persistence)

The register array is written and read as a raw `byte[1024]` block with no framing
beyond the `hllRegisterCount` field in the page header (Section 5.8).

```java
/**
 * Writes the register array to a page at the given offset.
 * Called during batched persistence flush (Section 3.2).
 */
public void writeTo(CacheEntry page, int offset) {
  page.setByteArray(offset, registers);  // DurablePage provides setByteArray()
}

/**
 * Reads the register array from a page at the given offset.
 * Called during engine load (openStatsFile).
 */
public static HyperLogLogSketch readFrom(CacheEntry page, int offset) {
  var sketch = new HyperLogLogSketch();
  page.getByteArray(offset, sketch.registers, 0, M);
  return sketch;
}

/**
 * Returns the serialized size in bytes. Always 1024 (= M) for p=10.
 */
public static int serializedSize() {
  return M;
}
```

#### Summary

| Operation | Complexity | Cost |
|-----------|-----------|------|
| `add(hash)` | O(1) | 1 shift + 1 CLZ + 1 compare + 1 conditional store |
| `estimate()` | O(m) = O(1024) | 1024 additions + 1 division + branch corrections |
| `merge(other)` | O(m) = O(1024) | 1024 comparisons + conditional stores |
| `clone()` | O(m) = O(1024) | 1 array copy (1 KB) |
| `writeTo()` / `readFrom()` | O(m) = O(1024) | 1 KB sequential I/O |
| Memory | Fixed | 1024 bytes (register array) + ~24 bytes (object header) |

### 6.3 Key Stream

The engine's `keyStream()` already extracts original keys:

```java
// BTreeMultiValueIndexEngine line 283:
public Stream<Object> keyStream(AtomicOperation op) {
  return svTree.keyStream(op).map(BTreeMultiValueIndexEngine::extractKey);
}
```

Yields original keys sorted with duplicates. The histogram construction algorithm handles
duplicates naturally (they increase frequency, counted once for per-bucket NDV).

### 6.4 Composite (Multi-Field) Indexes

PostgreSQL builds statistics **per-column**, not per-index, and multiplies individual
selectivities under an independence assumption. YouTrackDB's plan attaches histograms to
**indexes**. For a composite index on `(firstName, lastName)`, the B-tree stores
`CompositeKey` objects ordered by (field1, field2, ...).

**Decision: histogram on leading field only.**

Building a histogram on full `CompositeKey` values would be problematic:
- Prefix queries (`WHERE firstName = 'Smith'`) can't use `findBucket()` on a partial key
- Range interpolation on multi-dimensional `CompositeKey` is undefined
- Per-bucket NDV loses meaning across mixed dimensions

Instead, during histogram construction, the leading (first) field is extracted from each
`CompositeKey` (Section 5.5). This handles the primary access patterns:

| Query pattern | Estimation method |
|---|---|
| `firstName = 'Smith'` (prefix equality) | Histogram lookup on leading field |
| `firstName > 'M'` (prefix range) | Histogram range interpolation |
| `firstName = 'Smith' AND lastName = 'John'` (full key) | `1 / NDV` from `IndexStatistics` |
| `lastName = 'John'` (non-leading field only) | Cannot use this index's histogram (correct — the index cannot serve this query efficiently either) |

**Incremental updates:** The engine extracts the leading field from the key before calling
`onPut()` / `onRemove()`, so `findBucket()` operates on scalar leading-field values.

**NDV:** `IndexStatistics.distinctCount` tracks full-key NDV (important for full-key
equality estimates). A `leadingFieldNDV` could be added in a future extension for more
accurate prefix-only equality estimates; for now, per-bucket `distinctCounts[]` in the
histogram serves this purpose.

**Multi-value + composite intersection:** A multi-value index on composite fields (e.g.,
`CREATE INDEX ON Person(tags, name)` where `tags` is multi-value) stores keys as a
**flat** `CompositeKey(tag, name, rid)` in the B-tree — not nested — because
`CompositeKey.addKey()` recursively flattens any `CompositeKey` argument (line 101–108).
When `createCompositeKey(CompositeKey(tag, name), rid)` is called, the constructor
`new CompositeKey(CompositeKey(tag, name))` flattens to `CompositeKey(tag, name)`, then
`.addKey(rid)` appends the RID, yielding `CompositeKey(tag, name, rid)` with 3 keys.
The engine's `extractKey()` sees `keys.size() == 3` (> 2), returns
`new CompositeKey(keys.subList(0, 2))` = `CompositeKey(tag, name)`. The leading-field
extraction then takes `getKeys().getFirst()` = `tag` as the histogram key.

---

## 7. Cost Model

### 7.1 Cost Units

```
COST_SEQ_PAGE_READ  = 1.0    // sequential page read (baseline)
COST_RANDOM_PAGE_READ = 4.0  // random page read (typical SSD ratio)
COST_INDEX_SEEK     = COST_RANDOM_PAGE_READ × tree_depth  // ~4-6 page reads
COST_PER_ROW_CPU    = 0.01   // per-row filtering/comparison cost
```

### 7.2 Cost Formulas

**Full class scan:**
```
cost = class.approximateCount() × COST_PER_ROW_CPU
     + (class.approximateCount() / rows_per_page) × COST_SEQ_PAGE_READ
```

**Index equality seek:**
```
estimated_rows = statistics-based estimate (histogram or uniform)
cost = COST_INDEX_SEEK + estimated_rows × COST_RANDOM_PAGE_READ
```

**Index range scan:**
```
estimated_rows = statistics-based estimate
cost = COST_INDEX_SEEK + estimated_rows × COST_SEQ_PAGE_READ
```

**Edge traversal (MATCH):**
```
cost_per_source = avg_fan_out(edgeType, sourceClass, direction) × COST_RANDOM_PAGE_READ
total_cost = source_rows × cost_per_source
```

### 7.3 Combining Selectivities

Independence assumption (standard approach — PostgreSQL, MySQL, etc.):
```
sel(A AND B) = sel(A) × sel(B)
sel(A OR B)  = sel(A) + sel(B) − sel(A) × sel(B)
sel(NOT A)   = 1.0 − sel(A)
```

Inaccurate for correlated predicates; multi-column histograms are out of scope.

---

## 8. Implementation Steps

### Step 1: Define `IndexStatistics` and `EquiDepthHistogram` records

**New files:**
- `core/.../index/engine/IndexStatistics.java` — record from Section 5.1
- `core/.../index/engine/EquiDepthHistogram.java` — record from Section 5.2 plus
  `findBucket(key)` binary search method and serialization/deserialization for page storage

### Step 2: Implement `IndexHistogramManager`

**New file:** `core/.../index/engine/IndexHistogramManager.java`

A `DurableComponent` managing a single statistics page (`.ixs` file). Implements
lifecycle on top of `DurableComponent` primitives (`addFile()`, `openFile()`,
`loadPageForWrite()`, etc.):

```java
public class IndexHistogramManager extends DurableComponent {

  // Lifecycle (called by engine; implemented via DurableComponent primitives)
  void createStatsFile(AtomicOperation op)   // addFile() + write initial page
  void openStatsFile(AtomicOperation op)     // openFile() + load page into cache
  void closeStatsFile()                      // flush dirty state + release resources
  void deleteStatsFile(AtomicOperation op)   // deleteFile()

  // Incremental updates — accumulate delta in AtomicOperation (no CHM/page write)
  // wasInsert: true if the key was newly inserted, false if an existing key's value was
  //            updated in-place (single-value only; always true for multi-value indexes).
  //            When false, no counters or frequencies are modified — the key already
  //            existed and the index size is unchanged.
  void onPut(AtomicOperation op, @Nullable Object key, boolean isSingleValue,
             boolean wasInsert)
  void onRemove(AtomicOperation op, @Nullable Object key, boolean isSingleValue)

  // Commit — apply accumulated deltas to CHM cache; maybe persist to page
  void applyDeltas(HistogramDelta delta)     // called by AbstractStorage after commitIndexes()

  // Rollback: no action needed — deltas discarded with AtomicOperation (Section 3.2)

  // Planner reads (from CHM cache; reload from page on miss)
  IndexStatistics getStatistics()
  @Nullable EquiDepthHistogram getHistogram()

  // Histogram build (called by engine after create/rebuild, or by background rebalancer)
  // sortedKeys: non-null keys only (null entries live in separate null bucket/tree)
  // nullCount: obtained from nullBucket (single-value) or nullTree.size() (multi-value)
  void buildHistogram(AtomicOperation op, Stream<Object> sortedKeys, long totalCount,
                      long nullCount, int keyFieldCount)

  // Reset on index clear/truncate (called by engine after doClearTree())
  void resetOnClear(AtomicOperation op)      // zero all counters, discard histogram, persist empty page

  // Checkpoint flush (called by AbstractStorage.makeFuzzyCheckpoint())
  void flushIfDirty(AtomicOperation op)      // persist CHM snapshot to .ixs if dirtyMutations > 0
}
```

Internally maintains:
- Reference to the storage-level `ConcurrentHashMap<Integer, HistogramSnapshot>` (Section 3.3);
  reads current snapshot for `findBucket()` during delta accumulation
- `long dirtyMutations` — committed mutations not yet persisted to `.ixs` page;
  plain `long` with no synchronization — races cause at most a duplicate flush (idempotent)
  or slightly delayed flush (caught by checkpoint within 300 seconds); `LongAdder` was
  considered but rejected because `sum()` iterates all striped cells on every commit
  (hot-path overhead) while the checkpoint fallback already bounds flush delay
- `AtomicBoolean rebalanceInProgress` flag (reset in `finally` on failure)
- `volatile long lastRebalanceFailureTime` — timestamp (ms) of last rebalance failure;
  prevents retry storms under persistent I/O errors (60-second cooldown, Section 5.7)
- Reference to engine's `keyStream()` supplier for rebalance scans
- Reference to storage-level `Semaphore rebalanceSemaphore` for concurrent rebalance
  throttling (Section 5.7); acquired before key scan, released in `finally`

### Step 3: Implement `SelectivityEstimator` and `ScalarConversion`

**New files:**
- `core/.../index/engine/SelectivityEstimator.java` — three-tier selectivity estimation
  with out-of-range equality short-circuit and single-value bucket optimization
- `core/.../index/engine/ScalarConversion.java` — `scalarize()` + `stringToScalar()` +
  `charEncode()` for range interpolation on non-numeric types (UTF-16 code unit encoding
  to match `DefaultComparator` → `String.compareTo()` ordering)

```java
public class SelectivityEstimator {
  /** Returns a selectivity in [0.0, 1.0]. All internal formulas are clamped. */
  public static double estimate(
      IndexStatistics stats,
      @Nullable EquiDepthHistogram histogram,
      SQLBooleanExpression predicate) {
    if (stats.totalCount() == 0) return 0.0;
    if (histogram != null) return estimateWithHistogram(histogram, predicate);
    return estimateUniform(stats, predicate);
  }

  private static double clamp(double v) {
    return Math.min(Math.max(v, 0.0), 1.0);
  }
}
```

Implements formulas from Sections 4.1 (uniform) and 5.4 (histogram), including
out-of-range equality short-circuit and single-value bucket optimization. All formulas
pass their result through `clamp()` before returning (see Section 5.4).

### Step 4: Add `getStatistics()` / `getHistogram()` to engine and index interfaces

**Files:**
- `BaseIndexEngine.java` — add default methods returning `null` (safe default for
  non-B-tree engines such as hash indexes; these engines have no sorted key stream
  and cannot support histograms)
- `Index.java` — add `getStatistics()` / `getHistogram()`
- `BTreeSingleValueIndexEngine.java` — delegate to `histogramManager`
- `BTreeMultiValueIndexEngine.java` — delegate to `histogramManager`
- `IndexOneValue.java` / `IndexMultiValues.java` — delegate through storage

**Null-safe contract:** All consumers of `getStatistics()` / `getHistogram()` (including
`SelectivityEstimator`) must handle `null` returns gracefully — falling back to the
existing heuristics (`count / 2` for `SQLWhereClause`, `Integer.MAX_VALUE` for
`IndexSearchDescriptor`). This ensures that non-B-tree engines and indexes whose
histogram manager has not yet loaded produce correct (if imprecise) estimates rather
than NPEs.

### Step 5: Wire histogram manager into engine lifecycle and put/remove

**Files:** `CellBTreeSingleValue.java` (interface), `BTree.java`,
`V1IndexEngine.java` (interface), `BTreeSingleValueIndexEngine.java`,
`BTreeMultiValueIndexEngine.java`, `DiskStorage.java`

**B-tree `put()` return type change:** Change `CellBTreeSingleValue.put()` (line 26) from
`void` to `boolean`. Change `BTree.put()` (line 239) to `return update(...)` instead of
bare `update(...)` call — a one-line change, since `update()` already returns `boolean`.
Change `V1IndexEngine.put()` (line 11) from `void` to `boolean`.

Add `IndexHistogramManager` field. Wire `onPut()` / `onRemove()` after each B-tree
mutation. Wire lifecycle methods (`create`, `load`, `delete`, `close`) alongside B-tree
lifecycle. Register `.ixs` in `DiskStorage.ALL_FILE_EXTENSIONS` (line 199).

```java
// BTreeSingleValueIndexEngine:
@Override
public boolean put(AtomicOperation op, Object key, RID value) {
  // sbTree.put() returns true if a new key was inserted, false if an existing
  // key's value was updated in-place (BTree.update() already computes this via
  // sizeDiff; this plan changes the public put() from void to boolean to expose it).
  // The histogram manager must distinguish these cases to avoid inflating
  // totalCount/distinctCount on updates.
  boolean wasInsert = sbTree.put(op, key, value);
  histogramManager.onPut(op, key, /* isSingleValue= */ true, wasInsert);
  return wasInsert;
}

@Override
public boolean remove(AtomicOperation op, Object key) {
  var removed = sbTree.remove(op, key) != null;
  if (removed) {
    histogramManager.onRemove(op, key, /* isSingleValue= */ true);
  }
  return removed;
}

@Override
public void delete(AtomicOperation op) {
  doClearTree(op);
  sbTree.delete(op);
  histogramManager.deleteStatsFile(op);  // remove .ixs file + cache entry
}

@Override
public void close() {
  sbTree.close();
  histogramManager.closeStatsFile();     // flush dirty state + release resources
}

// BTreeMultiValueIndexEngine:
@Override
public boolean put(AtomicOperation op, Object key, RID value) {
  if (key != null) {
    svTree.put(op, createCompositeKey(key, value), value);
  } else {
    nullTree.put(op, value, value);
  }
  // wasInsert is always true for multi-value: each (key, RID) composite is unique.
  histogramManager.onPut(op, key, /* isSingleValue= */ false, /* wasInsert= */ true);
  // One notification per original key, not per B-tree entry.
  return true;
}

@Override
public boolean remove(AtomicOperation op, Object key, RID value) {
  boolean removed = /* ... existing range-scan-and-remove logic ... */;
  if (removed) {
    // One notification per original key — the histogram tracks original key
    // frequencies, not composite key counts. Each remove(key, value) targets
    // one specific (key, RID) composite entry in the B-tree (the range scan
    // with from == to matches at most one entry in the single-value svTree).
    // onRemove is called once per remove() invocation.
    histogramManager.onRemove(op, key, /* isSingleValue= */ false);
  }
  return removed;
}

@Override
public void delete(AtomicOperation op) {
  doClearSVTree(op);
  svTree.delete(op);
  nullTree.delete(op);
  histogramManager.deleteStatsFile(op);  // remove .ixs file + cache entry
}

@Override
public void close() {
  svTree.close();
  nullTree.close();
  histogramManager.closeStatsFile();     // flush dirty state + release resources
}
```

### Step 6: Initial histogram build on create/rebuild/migration

After `create()` populates the B-tree, call:
```java
histogramManager.buildHistogram(op, keyStream(op), size(storage, null, op),
    nullCount(op), keyDefinition.getFieldCount());
// nullCount(op): for single-value, check nullBucket.getValue() != null ? 1 : 0
//                for multi-value, nullTree.size()
```
No-op if `size - nullCount < HISTOGRAM_MIN_SIZE` (non-null entries must meet the
threshold; an all-null index stays in uniform mode).

**Rebuild path:** `IndexAbstract.rebuild()` (line 286) creates a new engine via
`storage.addIndexEngine()` and repopulates via `fillIndex()` which calls `put()` for each
record. Histogram is rebuilt **after** `fillIndex()` completes by calling
`buildHistogram()` on the new engine — not by accumulating individual `onPut()` calls
during fillIndex (which would be O(N log B) total overhead and produce a suboptimal
histogram since entries arrive in record order, not key order).

**Bulk-load suppression:** During `fillIndex()`, the `IndexHistogramManager` is set to
bulk-load mode via `histogramManager.setBulkLoading(true)`. In this mode, `onPut()` is
a no-op (no delta accumulation — no counter updates, no `findBucket()` calls, no HLL
sketch updates). After `fillIndex()` completes,
`histogramManager.setBulkLoading(false)` is called, followed by `buildHistogram()`.
This avoids O(N log B) overhead from N individual `findBucket()` calls during population.
The post-fill `buildHistogram()` computes all statistics from scratch (exact NDV from the
sorted key stream, accurate frequencies, fresh HLL sketch for multi-value indexes), so
nothing is lost by suppressing individual notifications during the fill phase.

**Migration** (database open with no `.ixs` file): Create fresh, initialize counters:
- Single-value: `totalCount = btree.size()`, `distinctCount = totalCount`,
  `nullCount = nullBucket.getValue() != null ? 1 : 0`
- Multi-value: `totalCount = svTree.size() + nullTree.size()`,
  `distinctCount = totalCount` (overestimates — treats each composite entry as distinct;
  corrected on first rebalance when exact leading-field NDV and HLL are computed from the
  sorted key stream),
  `nullCount = nullTree.size()`

If `totalCount - nullCount >= HISTOGRAM_MIN_SIZE`: schedule background build (non-null
entries must meet the threshold; an all-null index stays in uniform mode).

### Step 7: Wire into `SQLWhereClause.estimate()`

**File:** `SQLWhereClause.java`, line 82

Replace `count = count / 2` with:

```java
var stats = index.getStatistics(session);
var histogram = index.getHistogram(session);
estimatedRows = (long) (count * SelectivityEstimator.estimate(stats, histogram, whereClause));
```

For compound predicates: `sel(A AND B) = sel(A) × sel(B)`,
`sel(A OR B) = sel(A) + sel(B) − sel(A) × sel(B)`.

### Step 8: Wire into `IndexSearchDescriptor.cost()`

**File:** `IndexSearchDescriptor.java`, line 97

Replace `return Integer.MAX_VALUE` fallback with:

```java
var stats = index.getStatistics(session);
var histogram = index.getHistogram(session);
return estimateFromStatistics(stats, histogram, keyParams, rangeFrom, rangeTo);
```

### Step 9: Edge fan-out estimation

Compute on-demand from existing class counts (no new storage). Uses
`approximateCount()` (O(1), reads stored metadata) rather than `count()` (O(n) scan).
The call is polymorphic (`isPolymorphic=true`, the default) so subclass instances are
included — e.g., `approximateCount("Knows")` includes all subclasses of `Knows`.
Direction determines which vertex class is the "starting" class (the denominator):

- **OUT traversal** from vertex A via edge E: each A has on average
  `E.approximateCount() / A.approximateCount()` outgoing E edges.
  Source class = A (the `PatternEdge.out` node's class).
- **IN traversal** from vertex B via edge E: each B has on average
  `E.approximateCount() / B.approximateCount()` incoming E edges.
  Source class = B (the `PatternEdge.in` node's class).
- **BOTH**: the vertex participates as both source and target. The fan-out is computed
  as the sum of outgoing and incoming estimates, using the actual OUT and IN vertex
  classes from the edge type's schema (not a blanket 2× multiplier, which overestimates
  for directed edges where the source class only appears on one end — e.g.,
  `Person→Works→Company` from Person: OUT fan-out = `edgeCount / personCount`,
  IN fan-out = 0 because Person is not the IN vertex of Works).

In the MATCH planner, `EdgeTraversal.out` (boolean) determines forward vs reverse
execution: forward starts from `PatternEdge.out`, reverse starts from `PatternEdge.in`.
The caller resolves the starting vertex class accordingly and passes it as `sourceClass`.

```java
/**
 * Estimates the average number of adjacent vertices reachable from one vertex
 * of sourceClass by traversing edges of edgeType in the given direction.
 *
 * Uses approximateCount() — O(1), polymorphic (includes subclasses).
 *
 * @param edgeType     edge class name (e.g., "Knows")
 * @param sourceClass  vertex class we are traversing FROM — the class whose
 *                     count becomes the denominator. Caller resolves this
 *                     based on traversal direction:
 *                       forward (EdgeTraversal.out=true)  → PatternEdge.out's class
 *                       reverse (EdgeTraversal.out=false) → PatternEdge.in's class
 * @param direction    syntactic direction from the MATCH pattern (OUT, IN, BOTH)
 * @param outVertexClass  the OUT vertex class of the edge type (from schema)
 * @param inVertexClass   the IN vertex class of the edge type (from schema)
 */
double estimateFanOut(String edgeType, String sourceClass, Direction direction,
                      String outVertexClass, String inVertexClass) {
  long edgeCount = schema.getClass(edgeType).approximateCount(session);
  long sourceCount = schema.getClass(sourceClass).approximateCount(session);
  if (sourceCount == 0) return 0.0;

  if (direction == Direction.BOTH) {
    // Compute OUT and IN fan-out separately using the actual vertex classes.
    // OUT edges from sourceClass exist only if sourceClass is (or extends) outVertexClass.
    // IN edges to sourceClass exist only if sourceClass is (or extends) inVertexClass.
    double outFanOut = 0.0;
    double inFanOut = 0.0;
    if (isSubclassOrEqual(sourceClass, outVertexClass)) {
      long outCount = schema.getClass(outVertexClass).approximateCount(session);
      outFanOut = outCount > 0 ? (double) edgeCount / outCount : 0.0;
    }
    if (isSubclassOrEqual(sourceClass, inVertexClass)) {
      long inCount = schema.getClass(inVertexClass).approximateCount(session);
      inFanOut = inCount > 0 ? (double) edgeCount / inCount : 0.0;
    }
    return outFanOut + inFanOut;
  }

  return (double) edgeCount / sourceCount;
}
```

### Step 10: Configuration parameters

**File:** `GlobalConfiguration.java`

```java
QUERY_STATS_DEFAULT_SELECTIVITY("query.stats.defaultSelectivity", ..., Double.class, 0.1),
QUERY_STATS_DEFAULT_FAN_OUT("query.stats.defaultFanOut", ..., Double.class, 10.0),
QUERY_STATS_HISTOGRAM_BUCKETS("query.stats.histogramBuckets", ..., Integer.class, 128),
QUERY_STATS_HISTOGRAM_MIN_SIZE("query.stats.histogramMinSize", ..., Integer.class, 1000),
QUERY_STATS_REBALANCE_MUTATION_FRACTION("query.stats.rebalanceMutationFraction", ..., Double.class, 0.3),
QUERY_STATS_MIN_REBALANCE_MUTATIONS("query.stats.minRebalanceMutations", ..., Long.class, 1000L),
QUERY_STATS_MAX_REBALANCE_MUTATIONS("query.stats.maxRebalanceMutations", ..., Long.class, 10_000_000L),
QUERY_STATS_MAX_BOUNDARY_BYTES("query.stats.maxBoundaryBytes", ..., Integer.class, 256),
QUERY_STATS_PERSIST_BATCH_SIZE("query.stats.persistBatchSize", ..., Integer.class, 500),
QUERY_STATS_REBALANCE_FAILURE_COOLDOWN("query.stats.rebalanceFailureCooldown", ..., Long.class, 60_000L),
QUERY_STATS_MAX_CONCURRENT_REBALANCES("query.stats.maxConcurrentRebalances", ..., Integer.class, -1),
// -1 = auto: max(2, availableProcessors / 4)
```

---

## 9. Implementation Sequence

### Phase 1A: Index Statistics with Histograms

| Step | Description | Files | LOC est. |
|---|---|---|---|
| 1 | `IndexStatistics`, `EquiDepthHistogram` records (incl. MCV fields) + serialization + `findBucket()`, and `HyperLogLogSketch` (Section 6.2.1) | New: 3 files | ~400 |
| 2 | `IndexHistogramManager` — DurableComponent with stats page, `HistogramDelta` accumulator, delta application on commit (incl. `hasDriftedBuckets` flag), batched persistence, build/rebalance (incl. MCV tracking + adaptive bucket count + drift-biased trigger), HLL page overflow, lazy HLL init, `resetOnClear()`, rebalance scalar counter preservation | New: 2 files (`IndexHistogramManager` + `HistogramDelta`) | ~700 |
| 3 | `SelectivityEstimator` + `ScalarConversion` — three-tier estimation (incl. MCV short-circuit + out-of-range equality short-circuit + single-value bucket optimization) + char-based string-to-scalar | New: 2 files | ~350 |
| 4 | Add `getStatistics()` / `getHistogram()` to engine and index interfaces | `BaseIndexEngine`, `Index`, engine + index impls | ~100 |
| 5 | Wire histogram manager into engine put/remove/lifecycle/clear + register `.ixs` in `DiskStorage` + change B-tree `put()` return type from `void` to `boolean` + rebalance semaphore on `DiskStorage` | `CellBTreeSingleValue`, `BTree`, `V1IndexEngine`, `BTreeSingleValueIndexEngine`, `BTreeMultiValueIndexEngine`, `DiskStorage` | ~170 |
| 6 | Initial build on create/rebuild + migration (missing `.ixs` file) | Engine impls, `IndexAbstract` | ~100 |
| 7 | Wire into `SQLWhereClause.estimate()` | `SQLWhereClause.java` | ~40 |
| 8 | Wire into `IndexSearchDescriptor.cost()` | `IndexSearchDescriptor.java` | ~30 |
| 9 | Edge fan-out estimation helper | `MatchExecutionPlanner` or new utility | ~30 |
| 10 | Configuration parameters | `GlobalConfiguration.java` | ~30 |
| 11 | Tests (incl. HLL unit tests, MCV tests, adaptive bucket count tests, out-of-range, single-value bucket, truncate, rebalance throttling, drift-biased rebalance, lazy HLL) | New test classes | ~1500 |
| | **Subtotal** | | **~3430** |

### Phase 1B: Improved MATCH Planner Estimates

| Step | Description | Files | LOC est. |
|---|---|---|---|
| 1 | Cost model constants and formulas | New: `MatchCostModel.java` | ~100 |
| 2 | Improve `estimateRootEntries()` input quality (flows automatically from Steps 7-8 improving `SQLWhereClause.estimate()`) | `MatchExecutionPlanner.java` | ~30 |
| 3 | `estimateEdgeCost()` for fan-out-based traversal | `MatchExecutionPlanner.java` | ~40 |
| 4 | Tests | New test classes | ~200 |
| | **Subtotal** | | **~370** |

**Total: ~3800 LOC**

**Phase 1B note:** The MATCH planner already uses cost-driven root selection via
`estimateRootEntries()` → `SQLWhereClause.estimate()`. Phase 1A Step 7 directly improves
these estimates. Phase 1B primarily adds edge fan-out costing and the cost model
abstraction — the scope is smaller than originally projected.

### Dependency Order

Step 1 now includes `HyperLogLogSketch` alongside the record types. It has no dependencies
on other steps and can be developed and unit-tested independently.

```
Phase 1A                                    Phase 1B
────────                                    ────────
Step 1 (records + HyperLogLogSketch)        Depends on 1A steps 1-8
  │
  ├─→ Step 2 (IndexHistogramManager + CHM cache)
  │     │
  │     ├─→ Step 4 (interfaces)
  │     │     │
  │     │     └─→ Step 5 (engine wiring + DiskStorage + BTree put() return type)
  │     │           │
  │     │           └─→ Step 6 (build + migration)
  │     │
  │     └─→ Step 3 (SelectivityEstimator + ScalarConversion)
  │           │
  │           ├─→ Step 7 (SQLWhereClause)
  │           └─→ Step 8 (IndexSearchDescriptor)
  │
  └─→ Steps 9-10 (fan-out + config) — independent
```

Steps 3 and 5-6 are independent and parallelizable once Step 2 is complete.

---

## 10. Testing Strategy

### 10.1 IndexHistogramManager Unit Tests

- Create manager, verify empty statistics (`totalCount=0`, no histogram)
- `onPut(wasInsert=true)` → verify counter increments; `onRemove()` → verify decrements
- `onPut(wasInsert=false)` for single-value → verify no counter or frequency changes
- Single-value repeated put with same key → `wasInsert=false`, totalCount unchanged
- Null key handling (nullCount tracking)
- `mutationsSinceRebalance` increments on each operation (both insert and update)
- Statistics page persistence (create, close, re-load, verify counters)
- CHM cache hit/miss behavior
- Null snapshot during `onPut()`/`onRemove()` → frequency deltas skipped, counters updated
- Rollback discards deltas (put → rollback → verify CHM unchanged and page unchanged)
- Delta application with null CHM entry (engine deleted) → delta silently discarded

### 10.2 Histogram Construction Tests

- Known data → verify bucket boundaries and frequencies match expected equi-depth
- Skewed data (Zipf) → verify histogram captures shape
- Per-bucket NDV computation during build
- Duplicate keys at boundary → all in same bucket
- All-identical keys (NDV=1) → single effective bucket
- Variable-length keys → boundary truncation at MAX_BOUNDARY_BYTES
- Long string keys → truncation preserves sort order
- Adjacent boundaries equal after truncation → buckets merged
- Boundary truncation + fallback bucket count reduction
- Small index (< `HISTOGRAM_MIN_SIZE`) → no histogram built
- N+1 boundary convention: boundaries[0] = min, boundaries[bucketCount] = max
- NDV-based bucket pre-computation: when NDV is known and < targetBuckets, actual bucket
  count equals NDV (no over-allocation)
- Adaptive bucket count: `sqrt(nonNullCount)` caps bucket count for small indexes
  (e.g., 1000 entries → ~31 buckets, not 128)
- `findBucket()` uses linear scan for bucketCount ≤ 8, binary search for larger
- MCV tracking: skewed data (e.g., 90% 'active', 10% other) → mcvValue = 'active',
  mcvFrequency = 900 (for 1000 entries)
- MCV tracking: uniform data → mcvValue is one of the values, mcvFrequency = count/NDV
- MCV tracking: all-identical keys → mcvValue = that key, mcvFrequency = totalCount
- MCV serialization round-trip: build, persist, reload → mcvValue and mcvFrequency match

### 10.3 Composite Index Tests

- Composite index (firstName, lastName) → histogram on firstName only
- Leading-field extraction from CompositeKey stream
- Prefix query selectivity (WHERE firstName = 'Smith') uses histogram
- Full-key equality uses 1/NDV
- Non-leading-field query returns default selectivity
- Incremental put/remove extracts leading field for findBucket()

### 10.4 Scalar Conversion Tests

- Integer/long/double scalarize() → direct numeric conversion
- Date scalarize() → epoch millis as double
- String scalarize() → common char-prefix stripping + base-65536 encoding
- Strings with long common prefix → high precision after stripping
- Non-ASCII strings → char-based encoding preserves `String.compareTo()` ordering
  (e.g., verify scalarize("ä") > scalarize("Z") iff "ä".compareTo("Z") > 0)
- Unknown type → fallback 0.5
- Degenerate bucket (lo == hi) → fraction = 0.5

### 10.5 Three-Tier Transition Tests

- Empty → Uniform: first put
- Uniform → Histogram: crossing `HISTOGRAM_MIN_SIZE`
- Histogram remains usable after mass deletion below threshold
- Uniform formulas produce reasonable estimates
- Histogram more accurate than uniform for skewed data

### 10.6 Incremental Maintenance Tests

- Build histogram, insert entries, verify frequency updates
- Build histogram, remove entries, verify frequency decrements
- `findBucket()` binary search boundary cases (below min, above max, on boundary)
- `mutationsSinceRebalance` triggers rebalance at threshold
- Rebalance produces fresh equi-depth boundaries
- Concurrent put/remove during rebalance — no corruption
- Delta application after rebalance (version mismatch) → `frequencyDeltas` discarded,
  scalar deltas applied, histogram frequencies remain from rebalance scan
- At-most-one rebalance (`AtomicBoolean` guard)
- Rebalance failure (IOException) → flag reset, cooldown enforced, subsequent trigger
  works after cooldown expires
- Rebalance failure cooldown → verify re-trigger is blocked within cooldown window
- Engine close/delete during rebalance → ClosedChannelException caught, flag reset, logged
- Index clear/truncate → `resetOnClear()` zeroes all counters, discards histogram, CHM
  reflects empty snapshot; subsequent put() starts accumulating from scratch
- Drift-biased rebalance: insert entries, build histogram, delete entries until a bucket
  frequency is clamped to 0 → `hasDriftedBuckets` flag set, rebalance threshold halved
- Storage-level rebalance throttling: trigger N > MAX_CONCURRENT_REBALANCES rebalances
  simultaneously → excess deferred; verify IO executor is not saturated

### 10.7 Multi-Value Index Tests

- Multi-value index with multiple values per key
- Histogram operates on original keys, not CompositeKeys
- NDV computed correctly during build (distinct original keys)
- `onPut()` / `onRemove()` with original keys
- HLL sketch persistence: create multi-value index, insert keys, close, re-open → verify
  `distinctCount` matches `hll.estimate()` and is accurate (not reset to 0)
- HLL sketch merge on commit: insert keys in multiple transactions → verify HLL registers
  reflect all inserts
- HLL `distinctCount` updates on commit: verify `stats.distinctCount` equals
  `(long) hll.estimate()` after each commit
- HLL page overflow: multi-value index with long string keys → verify HLL spills to page 1
  and loads correctly on restart
- Single-value index has no HLL data on `.ixs` page (`hllRegisterCount == 0`)
- Lazy HLL init: multi-value index with < HISTOGRAM_MIN_SIZE entries → no HLL allocated
  (`hllSketch == null`); after crossing threshold and building histogram, HLL is populated
  from key stream and `distinctCount` becomes accurate

### 10.7.1 HyperLogLogSketch Unit Tests

- **add + estimate:** Insert N distinct hashes → estimate within 3.25% of N
- **Accuracy sweep:** Test with N = 10, 100, 1K, 10K, 100K, 1M distinct keys → verify
  relative error ≤ 5% (2σ) for each
- **Small-range correction:** Insert 1–100 distinct keys → verify linear counting produces
  accurate estimates (error < 1%)
- **Duplicate keys:** Insert same hash 1M times → estimate ≈ 1
- **Empty sketch:** `estimate()` on fresh sketch returns 0
- **Merge commutativity:** `a.merge(b)` produces same estimate as `b.merge(a)` (on clones)
- **Merge correctness:** Two sketches with disjoint key sets → merged estimate ≈ sum;
  two sketches with identical key sets → merged estimate ≈ either one
- **Merge idempotency:** `a.merge(a_clone)` → estimate unchanged
- **Clone independence:** Modify original after clone → clone's estimate unchanged
- **Serialization round-trip:** `writeTo()` → `readFrom()` → estimate matches original
- **Serialized size:** `serializedSize()` returns 1024
- **Register bounds:** Insert keys → all registers in [0, 54]
- **Hash distribution:** Insert 10K distinct keys via MurmurHash3 → no register is 0
  (verifies even distribution; probability of a zero register with 10K keys is ~10^-4)

### 10.8 Selectivity Estimation Tests

- Known data + known query → estimated selectivity within 1% (histogram, 128 buckets)
- Uniform-mode estimates within reasonable bounds
- Edge cases: single-value, all-null, very high NDV
- IS NULL, IS NOT NULL, IN formulas
- Compound predicates (AND, OR, NOT)
- String range queries use scalar conversion for interpolation
- Out-of-range equality: `selectivity(f = X)` where X < boundaries[0] returns
  `1.0 / nonNullCount`; same for X > boundaries[bucketCount]
- Out-of-range range: `selectivity(f > X)` where X > boundaries[bucketCount] returns 0.0;
  `selectivity(f > X)` where X < boundaries[0] returns ~1.0
- Single-value bucket optimization: boolean index with values {true, false} →
  `selectivity(f > true)` = 0.0 (not a positive interpolation fraction);
  `selectivity(f > false)` correctly includes only the `true` bucket
- Single-value bucket range: enum field {A, B, C} → `selectivity(A ≤ f ≤ B)` correctly
  counts frequencies[A_bucket] + frequencies[B_bucket] with discrete fractions
- MCV short-circuit: equality on most common value uses exact frequency (not bucket average)
- MCV vs bucket average: for skewed data, MCV selectivity > bucket-averaged selectivity
- MCV miss: equality on non-MCV value falls through to standard bucket formula

### 10.9 Cost Model and End-to-End Tests

- Index seek preferred over scan at low selectivity
- Scan preferred at high selectivity
- MATCH root selection order with multiple candidates
- EXPLAIN output shows improved estimates
- Query performance improves for known-suboptimal plans

---

## 11. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Histogram drift between rebalances | Medium | Reduced range accuracy | Automatic rebalancing; `REBUILD INDEX` restores balance |
| Rebalance scan sees concurrent mutations | Medium | Slightly inconsistent histogram | Self-correcting via subsequent incremental updates |
| Variable-length keys exceed page after truncation | Low | Reduced bucket count | Truncation (Strategy 1) + bucket halving (Strategy 2); minimum 4 buckets or fall back to uniform |
| Multi-value NDV approximation | Low | ~3.25% error from HLL sketch (1024 registers) | HyperLogLog tracks inserts incrementally and is persisted to `.ixs` page; `distinctCount` updated from HLL on every commit; exact NDV recomputed on rebalance; insert-only means NDV can only overestimate (safe direction) |
| Statistics loss on crash | Low | At most `PERSIST_BATCH_SIZE` mutations lost per index | Batched persistence (Section 3.2); self-correcting via subsequent updates; approximate stats tolerate bounded staleness |
| Missing `.ixs` file on upgrade | Low | No statistics initially | Lazy init: create file + schedule background build |
| Ordering gap (B-tree mutation before stats update) | Low | Momentarily stale statistics | Same AtomicOperation → crash-consistent; staleness is benign |
| Rebalance during heavy write load | Low | Background I/O | Sequential I/O, low priority; triggered only on planner read |
| Single-value update inflates counters | N/A (mitigated) | N/A | `wasInsert` flag from B-tree `put()` (return type changed from `void` to `boolean`) distinguishes insert from update; `onPut()` skips counter/frequency changes when `wasInsert == false` (Section 5.6) |
| Concurrent delta application / rebalance race | Low | Briefly stale per-bucket frequencies | CHM `compute()` ensures atomicity; versioned snapshots detect stale deltas and discard `frequencyDeltas` (scalar deltas still applied); self-correcting via subsequent incremental updates |
| Rebalance failure leaves flag stuck | Low | Temporarily no rebalances | `AtomicBoolean` reset in `finally`; 60-second cooldown prevents retry storms; engine close/delete during rebalance caught and logged gracefully |
| Scalar conversion precision for strings | Low | Slight interpolation error | Char-based base-65536 encoding matches `String.compareTo()` ordering; fallback to 0.5 for unknown types |
| Index clear without histogram reset | N/A (mitigated) | N/A | `resetOnClear()` called by engine after `doClearTree()` — zeroes counters and discards histogram (Section 3.3) |
| Many indexes rebalance simultaneously | Low | IO executor saturation | Storage-level `Semaphore` limits concurrent rebalances (Section 5.7); excess deferred to next planner read |

---

## 12. Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Histogram owner | Engine level, not B-tree | Resolves multi-value CompositeKey problem; BTree nearly untouched (only `put()` return type changed from `void` to `boolean`) |
| Storage | Separate `.ixs` file (DurableComponent) | No entry point page change; independent lifecycle; simple migration |
| Empty/small indexes | No histogram (uniform mode) | Avoids meaningless histograms; uniform still >> `count/2` |
| Min/max tracking | Omitted; histogram boundaries[0]/boundaries[N] serve as min/max | Variable-length types have unbounded size; explicit bounds suffice |
| NDV tracking | Single-value: trivial (=totalCount). Multi-value: HyperLogLog sketch (~1 KB for 1024 registers, ~3.25% error) updated incrementally on put and persisted to `.ixs` page; `distinctCount` updated from `hll.estimate()` on every commit; exact NDV recomputed on rebalance | O(1) per-op cost; insert-only (no remove tracking); persisted HLL survives restarts; reset on rebalance |
| Per-bucket NDV | Build-time only, not incremental | Same rationale; acceptable drift |
| Boundary convention | N+1 boundaries for N buckets (PostgreSQL style) | Every bucket has explicit bounds; uniform formulas; no -∞/+∞ special cases |
| Variable-length boundaries | Truncation to MAX_BOUNDARY_BYTES + bucket count reduction fallback | Preserves high bucket counts for most data; safety net for pathological cases |
| Range interpolation | Char-based scalar conversion (inspired by PostgreSQL `convert_string_to_scalar`, adapted for Java UTF-16) | Base-65536 encoding on `charAt()` values preserves `String.compareTo()` ordering; proven design; enables uniform formulas |
| Composite indexes | Histogram on leading field only | Handles prefix queries (primary access pattern); full-key equality uses 1/NDV |
| In-memory cache | Storage-level `ConcurrentHashMap<Integer, HistogramSnapshot>` keyed by engine ID; updated via `compute()` on commit (delta application) and rebalance; versioned snapshots detect stale deltas — `frequencyDeltas` discarded on version mismatch, scalar deltas always applied | Single lookup point for planners; lock-free reads; always reflects committed state; no rollback handling needed |
| Background rebalance | Live scan on IO executor (`YouTrackDBInternalEmbedded.getIoExecutor()`) | Scaling thread pool for background I/O; non-blocking for planner; avoids contention with checkpoint/WAL executors |
| Insert-vs-update detection | `wasInsert` boolean from B-tree `put()` (return type changed from `void` to `boolean` — minimal signature change, no logic change) propagated to `onPut()` | Prevents totalCount/distinctCount inflation for single-value updates; multi-value always inserts (wasInsert=true) |
| Update model | Delta accumulation in `AtomicOperation`; applied to CHM on commit only | Rollback-safe by construction (deltas discarded); CHM always reflects committed state; consistent with write-nothing-on-error model |
| Persistence model | Batched: `.ixs` page flushed every `PERSIST_BATCH_SIZE` committed mutations + on fuzzy checkpoint + on close | Near-zero WAL overhead; at most `PERSIST_BATCH_SIZE` mutations lost on crash; acceptable for approximate statistics |
| Rebalance trigger | Check on planner read, not on write | Avoids overhead on write-heavy workloads |
| Null tracking | `nullCount` in `IndexStatistics` only; not duplicated in `EquiDepthHistogram` | Single source of truth; IS NULL selectivity computed from `stats.nullCount` + `histogram.nonNullCount` |
| Database export/import | `.ixs` files not exported; rebuild on import | Avoids stale stats in exports; one-time O(N) rebuild cost |
| Decimal boundaries | No truncation; rely on bucket-count-reduction fallback | Typical serialized size 10–20 bytes (fits easily); `setScale()` truncation increases collisions between adjacent boundaries (especially for negatives where FLOOR moves values further from zero), providing no benefit for keys already well within the per-boundary budget |
| Engine ID stability | Assumed stable (persisted in storage metadata) | CHM cache keyed by engine ID; starts empty on open, populated on first access |
| MCV-1 (Most Common Value) | Track single most frequent value during build/rebalance; equality short-circuit in selectivity formula | Near-zero-cost safety net (~20 LOC, 12 bytes + key on page). Simulation showed equi-depth histograms already handle heavy skew well (high-frequency values get their own buckets); MCV-1 adds 0–3% WMARE improvement. MCV-K (K>1) simulated up to K=100: gains scale linearly with no sweet spot — deferred to future extensions |
| Adaptive bucket count | `min(HISTOGRAM_BUCKETS, floor(sqrt(nonNullCount)), NDV)` | Ensures statistical significance per bucket for small indexes; avoids over-bucketing at threshold |
| Out-of-range equality | Return `1.0 / nonNullCount` for X outside `[boundaries[0], boundaries[N]]` | Near-zero but non-zero (avoids division-by-zero in cost models); uses in-memory boundary data (no btree I/O); much more accurate than bucket-averaged estimate for absent values |
| Single-value bucket optimization | Discrete logic when `distinctCounts[B] == 1` in range formulas | Eliminates continuous interpolation error for low-NDV indexes (booleans, enums); critical for correct range estimation when all entries in a bucket share one value |
| Index clear/truncate | `resetOnClear()` zeroes counters, discards histogram, installs empty snapshot | Prevents stale histogram from persisting after truncation; engine lifecycle completeness |
| Rebalance throttling | Storage-level `Semaphore(MAX_CONCURRENT_REBALANCES)` | Prevents IO executor saturation when many indexes trigger rebalance simultaneously (e.g., after bulk import) |
| Drift-biased rebalance | Halve rebalance threshold when any bucket frequency is clamped to 0 | Triggers earlier correction for indexes with accumulated negative drift from deletions; cheap check (boolean flag, no frequency scan on read) |
| Lazy HLL initialization | HLL sketch allocated only when multi-value index crosses `HISTOGRAM_MIN_SIZE` | Avoids 1 KB allocation per small multi-value index; populated from key stream on first histogram build |
| Rebalance scalar counter preservation | `cache.compute()` uses CHM's `totalCount`/`nullCount` (incrementally maintained), scan's exact NDV for `distinctCount` | Preserves concurrent commit deltas; uses most accurate source for each counter type |

---

## 13. Configuration Parameters

| Parameter | Default | Purpose |
|---|---|---|
| `query.stats.defaultSelectivity` | 0.1 | Non-indexed predicate default |
| `query.stats.defaultFanOut` | 10.0 | Edge traversal default fan-out |
| `query.stats.histogramBuckets` | 128 | Target bucket count (may be reduced for large keys) |
| `query.stats.histogramMinSize` | 1000 | Min entries before histogram is built |
| `query.stats.rebalanceMutationFraction` | 0.3 | Fraction of totalCount mutations triggering rebalance |
| `query.stats.minRebalanceMutations` | 1000 | Floor for rebalance trigger (small index guard) |
| `query.stats.maxRebalanceMutations` | 10,000,000 | Cap for rebalance trigger (large index guard) |
| `query.stats.maxBoundaryBytes` | 256 | Max serialized size per boundary key (truncation threshold) |
| `query.stats.persistBatchSize` | 500 | Mutations accumulated in-memory before flushing to `.ixs` page (Section 3.2) |
| `query.stats.rebalanceFailureCooldown` | 60,000 (ms) | Minimum wait time after a failed rebalance before re-triggering (Section 5.7) |
| `query.stats.maxConcurrentRebalances` | -1 (auto) | Max concurrent rebalance tasks across all indexes; -1 = `max(2, availableProcessors / 4)` (Section 5.7) |

Existing parameters relevant to histogram I/O:

| Parameter | Default | Relevance |
|---|---|---|
| `youtrackdb.storage.diskCache.pageSize` | 8 (KB) | Determines statistics page capacity |
| `youtrackdb.btree.maxKeySize` | -1 (auto) | Upper bound on boundary key size |
| `youtrackdb.index.stream.prefetchSize` | 10 | Spliterator batch size during histogram build |
| `youtrackdb.storage.wal.fuzzyCheckpointInterval` | 300 (sec) | Fuzzy checkpoint interval; also flushes dirty histogram state |

---

## 14. File Reference Index

All paths relative to `core/src/main/java/com/jetbrains/youtrackdb/`.

**B-Tree implementation (minimal change: `put()` return type only):**

| File | Role |
|------|------|
| `internal/core/storage/index/sbtree/singlevalue/CellBTreeSingleValue.java` | Interface — `put()` changed from `void` to `boolean` |
| `internal/core/storage/index/sbtree/singlevalue/v3/BTree.java` | B+ tree — `put()` changed from `void` to `boolean` (returns existing `update()` result) |
| `internal/core/storage/index/sbtree/singlevalue/v3/CellBTreeSingleValueEntryPointV3.java` | Entry point page (UNCHANGED) |
| `internal/core/storage/index/sbtree/singlevalue/v3/CellBTreeSingleValueBucketV3.java` | Leaf/internal node page (UNCHANGED) |
| `internal/core/storage/index/sbtree/singlevalue/v3/CellBTreeSingleValueV3NullBucket.java` | Null key bucket (UNCHANGED) |
| `internal/core/storage/index/sbtree/singlevalue/v3/SpliteratorForward.java` | Forward iteration (UNCHANGED) |
| `internal/core/storage/index/sbtree/singlevalue/v3/SpliteratorBackward.java` | Backward iteration (UNCHANGED) |
| `internal/core/storage/index/sbtree/multivalue/v2/CellBTreeMultiValueV2EntryPoint.java` | Multi-value entry point (UNCHANGED) |

**Index engine layer (MODIFIED by this plan):**

| File | Role |
|------|------|
| `internal/core/index/engine/V1IndexEngine.java` | V1 engine interface — `put()` changed from `void` to `boolean` |
| `internal/core/index/engine/BaseIndexEngine.java` | Engine interface — add `getStatistics()`/`getHistogram()` |
| `internal/core/index/engine/v1/BTreeSingleValueIndexEngine.java` | Single-value engine — add histogram manager; `put()` returns `boolean` |
| `internal/core/index/engine/v1/BTreeMultiValueIndexEngine.java` | Multi-value engine — add histogram manager |
| `internal/core/index/Index.java` | Index interface — add statistics methods |
| `internal/core/index/IndexOneValue.java` | Unique index — delegate statistics |
| `internal/core/index/IndexMultiValues.java` | Non-unique index — delegate statistics |
| `internal/core/index/IndexAbstract.java` | Base index — rebuild hook |
| `internal/core/storage/disk/DiskStorage.java` | Register `.ixs` in `ALL_FILE_EXTENSIONS` |

**Query planning (MODIFIED by this plan):**

| File | Role |
|------|------|
| `internal/core/sql/executor/IndexSearchDescriptor.java` | `cost()` — replace `Integer.MAX_VALUE` fallback |
| `internal/core/sql/executor/QueryStats.java` | Volatile EMA cache (kept as supplementary) |
| `internal/core/sql/parser/SQLWhereClause.java` | `estimate()` — replace `count/2` heuristic |
| `internal/core/sql/executor/match/MatchExecutionPlanner.java` | MATCH planner — improved estimates flow through from Step 7 |

**New files (CREATED by this plan):**

| File | Role |
|------|------|
| `internal/core/index/engine/IndexStatistics.java` | Statistics record |
| `internal/core/index/engine/EquiDepthHistogram.java` | Histogram record + findBucket + serialization |
| `internal/core/index/engine/HyperLogLogSketch.java` | HLL sketch for multi-value NDV (Section 6.2.1) |
| `internal/core/index/engine/IndexHistogramManager.java` | DurableComponent — stats page, CHM cache, incremental updates, build/rebalance |
| `internal/core/index/engine/SelectivityEstimator.java` | Three-tier selectivity estimation |
| `internal/core/index/engine/ScalarConversion.java` | Char-based string-to-scalar (UTF-16 code unit encoding) and type-generic interpolation |

**Infrastructure (UNCHANGED, reference only):**

| File | Role |
|------|------|
| `internal/core/storage/impl/local/paginated/base/DurablePage.java` | Base page class |
| `internal/core/storage/impl/local/paginated/base/DurableComponent.java` | Page/file utilities base class |
| `internal/common/serialization/types/BinarySerializer.java` | Key serializer interface |
| `internal/common/comparator/DefaultComparator.java` | Key comparator |
| `internal/common/hash/MurmurHash3.java` | MurmurHash3 hash function (existing, currently unused) |
| `api/config/GlobalConfiguration.java` | Configuration parameters |

---

## 15. Future Extensions

| Extension | Description | Prerequisite |
|---|---|---|
| Phase 2: DP-based join ordering | System R-style join order optimization for MATCH | This plan (cost model) |
| `Apply` + `IndexSeekStep` for MATCH | Index-parameterized nested loop for intermediate vertices | This plan (cost model + fan-out stats) |
| Multi-column histograms | Correlated selectivity estimation for composite indexes | Histograms from this plan |
| Leading-field NDV in IndexStatistics | Separate NDV counter for composite index leading field | IndexStatistics from this plan |
| Adaptive re-optimization | Mid-execution plan switching when actuals deviate from estimates | Cost model + runtime cardinality tracking |
| Adaptive rebalance scheduling | Compare `QueryStats` actuals with histogram estimates; trigger earlier rebalance when relative error exceeds a threshold (e.g., 50%). Lightweight feedback loop: `abs(actual - estimated) / max(actual, 1) > 0.5` → boost rebalance priority | Histogram + QueryStats from this plan |
| Sampling-based rebalance | Rebalance from 1-10% sample for very large indexes | Background rebalancing from this plan |
| HLL-based remove tracking | Counting HyperLogLog or deletable sketch for NDV decrement on remove | HLL sketch from this plan |
| MCV-K (Most Common Values list) | Generalize MCV-1 to top-K values (PostgreSQL tracks ~100). Requires a bounded min-heap during build and per-value frequency tracking. Simulation showed gains scale linearly with K (no sweet spot): K=10 adds ~160–440 bytes on page for ~1–3% additional WMARE improvement; K=100 adds ~1.6–4.4 KB (significant page pressure for string keys). Implement only if real-world query plans show equality estimation gaps not addressed by the equi-depth histogram alone | MCV-1 from this plan |
