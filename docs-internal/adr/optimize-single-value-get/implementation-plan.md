# Optimize BTreeSingleValueIndexEngine.get() — Avoid Range Scan Overhead

## Design Document
[design.md](design.md)

## High-level plan

### Goals

Replace the current `BTreeSingleValueIndexEngine.get(key)` implementation — which
uses `iterateEntriesBetween` + stream pipeline (~14 allocations per call, shared
lock) — with a direct leaf-page lookup via `BTree.getVisible()` that preserves
the pre-SI `get()` cost profile (~2 allocations, lock-free optimistic reads).

`get(key)` on a unique index is the single hottest index path — called for every
`WHERE indexed_field = ?` query, every unique constraint check during insert, and
every FK validation. The optimization eliminates the cursor/spliterator/stream
infrastructure overhead while adding only the inline version/visibility check
cost, which is O(K) where K is typically 1.

### Constraints

- **No lambdas or unnecessary allocations on the hot path** — per reviewer's
  explicit instruction. Where lambdas are unavoidable (e.g., stream pipeline
  in `visibilityFilterMapped()`), minimize captured state and delegate logic
  to plain methods.
- **Lock-free optimistic reads on happy path** — must use
  `executeOptimisticStorageRead()`, same as the pre-SI `get()`. No shared
  lock acquisition on the hot path.
- **BTree remains a pure storage structure** — visibility logic is not a BTree
  concept. `IndexesSnapshot` is passed as a parameter to `getVisible()`, not
  stored as a field.
- **Null keys use the same code path** — null keys are stored as
  `CompositeKey(null, version)` in the main B-tree. The `getVisible()` method
  handles them uniformly via prefix matching.
- **Cross-page entries must be handled** — if versions of the same key span two
  leaf pages (after a page split), the scan must follow `getRightSibling()`.
- **Existing stream-based methods are unchanged** — range scans, iterations, and
  `stream()` continue to use the current `iterateEntriesBetween` + visibility
  filter pipeline.
- **CellBTreeSingleValue interface must be extended** — `getVisible()` is added
  to the interface so the engine calls it through the same abstraction.

### Architecture Notes

#### Component Map

```mermaid
graph LR
    Engine["BTreeSingleValueIndexEngine"]
    Interface["CellBTreeSingleValue&lt;K&gt;"]
    BTree["BTree&lt;K&gt;"]
    Bucket["CellBTreeSingleValueBucketV3"]
    Snapshot["IndexesSnapshot"]
    DurableComponent["DurableComponent"]

    Engine -->|"get() calls getVisible()"| Interface
    Interface <|.. BTree
    BTree -->|"inherits"| DurableComponent
    BTree -->|"reads leaf entries"| Bucket
    BTree -->|"param: visibility check"| Snapshot
```

- **BTreeSingleValueIndexEngine** — The engine's `get()` method is simplified:
  converts key to `CompositeKey`, calls `sbTree.getVisible()`, wraps result in
  `Stream.of(rid)` or `Stream.empty()`. No longer constructs stream pipelines.
- **CellBTreeSingleValue\<K\>** — Interface extended with `getVisible()` method.
- **BTree\<K\>** — New `getVisible()` method added alongside existing `get()`.
  Uses `executeOptimisticStorageRead` for lock-free happy path. Descends tree
  via same loop as `getOptimistic()`, then scans leaf entries forward applying
  inline visibility logic via the passed `IndexesSnapshot`.
- **DurableComponent** — Unchanged. Shown for context: BTree inherits
  `executeOptimisticStorageRead()` and `protected final AbstractStorage storage`
  from this base class.
- **CellBTreeSingleValueBucketV3** — Existing `getEntry()`, `getKey()`,
  `getValue()`, `find()`, `size()`, `getRightSibling()` are used by the new
  scan logic. No modifications needed.
- **IndexesSnapshot** — Passed as parameter. Gains a new `checkVisibility()`
  method that encapsulates the full visibility decision (in-progress check,
  committed check, phantom check, snapshot fallback) for a single entry,
  returning `@Nullable RID`. Both `visibilityFilterMapped()` (stream path)
  and `BTree.scanLeafForVisible()` (direct path) delegate to it — single
  source of truth for visibility logic.

#### D1: Inline visibility in BTree vs. engine-level filtering

- **Alternatives considered**:
  1. Keep visibility at engine level, add a new BTree method returning all
     prefix-matched entries as a list — engine filters.
  2. Add `getVisible()` to BTree accepting `IndexesSnapshot` as parameter —
     visibility applied inline during leaf scan.
  3. Add a callback/predicate parameter to BTree for visibility — generic but
     adds abstraction.
- **Rationale**: Option 2 chosen. Inlining visibility in the leaf scan enables
  short-circuit return on first visible entry (critical for unique indexes) and
  avoids allocating collections for intermediate results. Passing
  `IndexesSnapshot` as a parameter keeps BTree free of SI field references.
  Option 1 would still allocate a list and iterate it. Option 3 adds unnecessary
  abstraction for a single caller.
- **Risks/Caveats**: BTree now has a method that understands `TombstoneRID`,
  `SnapshotMarkerRID`, and `emitSnapshotVisibility()` — a slight conceptual
  coupling. Acceptable because the method signature clearly marks this as an
  SI-aware operation via the `IndexesSnapshot` parameter.
- **Implemented in**: Track 2

#### D2: Optimistic read scope for multi-entry prefix scan

- **Alternatives considered**:
  1. Single optimistic scope covering tree descent + leaf scan — validates
     all pages at the end.
  2. Optimistic descent only, pinned read for leaf scan.
  3. Full optimistic scope with per-page validation during right-sibling
     traversal.
- **Rationale**: Option 1 chosen for the common case (all versions on one leaf
  page). For the rare right-sibling case, option 3 is used — validate the
  optimistic stamp before following the sibling pointer. This matches the
  existing `getOptimistic()` pattern (validates after each internal node hop).
  The optimistic scope naturally covers the `IndexesSnapshot` lookups too since
  those are in-memory ConcurrentSkipListMap reads.
- **Risks/Caveats**: If the leaf page is evicted during the scan,
  `OptimisticReadFailedException` falls through to the pinned path which retries
  with a shared lock — same as existing `get()` on develop.
- **Implemented in**: Track 2

#### D3: Prefix key matching via raw prefix key (same as pre-SI get())

- **Alternatives considered**:
  1. Use `enhanceCompositeKey()` with `LOWEST_BOUNDARY`/`HIGHEST_BOUNDARY`
     sentinels to define from/to range (2 extra CompositeKey allocations).
  2. Use raw prefix key (`CompositeKey(userKey)` without version) and rely on
     `CompositeKey.compareTo()` returning 0 for prefix matches — same key
     that the pre-SI `get()` uses.
  3. Serialize the key prefix and use byte-level comparison in the bucket.
- **Rationale**: Option 2 chosen. The default `compareInByteBuffer` in
  `BinarySerializer` deserializes both keys and delegates to
  `CompositeKey.compareTo()`, which returns 0 when the shorter key's elements
  all match. So `bucket.find(serialized(CompositeKey(userKey)))` will find a
  matching entry among the versioned entries. From the found index, scan left
  to find the first version (typically 0-1 steps for a unique index), then
  scan forward applying visibility. This matches the pre-SI `get()` path
  (preprocess + serialize + findBucket) and avoids the 2 extra
  `enhanceCompositeKey` allocations from option 1.
- **Risks/Caveats**: `find()` returns an arbitrary match within the prefix
  range (binary search semantics). The leftward scan to find the first version
  is O(K) where K is typically 1-2 for unique indexes — negligible. If prefix
  has no entries, `find()` returns a negative insertion point — the scan
  immediately terminates.
- **Implemented in**: Track 2

#### Invariants

- `getVisible()` must return the same RID as the existing `get()` +
  `visibilityFilter()` pipeline for all key types, RID types (RecordId,
  TombstoneRID, SnapshotMarkerRID), and transaction states (committed,
  in-progress, phantom). *(tested in Track 3)*
- `getVisible()` must use `executeOptimisticStorageRead()` — no shared lock
  on the happy path. *(verified by code review in Track 2)*
- `getVisible()` must handle cross-page entries via right-sibling traversal.
  *(tested in Track 3)*
- Null keys (`CompositeKey(null, version)`) must be handled by the same code
  path as non-null keys. *(tested in Track 3)*

#### Integration Points

- `BTreeSingleValueIndexEngine.get()` is the sole caller of `getVisible()`.
- `IndexesSnapshot.emitSnapshotVisibility()` is called from within BTree's
  `getVisible()` when entries need historical version lookup.
- The existing `get()` on `CellBTreeSingleValue` interface remains for
  non-SI callers (if any) and for the null bucket path.

#### Non-Goals

- Optimizing `BTreeMultiValueIndexEngine.get()` — different engine, lower
  priority.
- Optimizing range scans (`iterateEntriesBetween`, `iterateEntriesMajor`) —
  these use the stream pipeline which is appropriate for multi-result iteration.
- Removing the null bucket file (`.nbt`) — it remains for
  `BTree.get(null)`/`put(null)`/`remove(null)` direct access.

## Checklist

- [x] Track 1: Extract `checkVisibility()` in IndexesSnapshot
  > Add a `@Nullable RID checkVisibility(CompositeKey key, RID rid,
  > long visibleVersion, LongOpenHashSet inProgressVersions)` method to
  > `IndexesSnapshot` that encapsulates the full visibility decision for a
  > single entry: in-progress check, committed check, phantom check, and
  > snapshot fallback. Returns the visible RID, or null if not visible.
  >
  > The existing `emitSnapshotVisibility()` has a consumer-based signature
  > (`Consumer<RawPair<K, RID>>`) incompatible with returning a value.
  > Extract the core snapshot lookup logic (build search key →
  > `lowerEntry()` → prefix validation → RID extraction) into a
  > return-value-based package-private helper. Both `emitSnapshotVisibility()`
  > (stream path) and `checkVisibility()` (direct path) delegate to it.
  >
  > Refactor `visibilityFilterMapped()` to delegate to `checkVisibility()`
  > per entry inside its `mapMulti`, eliminating the duplicated logic.
  > Existing tests must continue passing — this is a pure refactoring.
  > Key test classes for regression verification:
  > `IndexesSnapshotVisibilityFilterTest`, `SnapshotIsolationIndexesGetTest`,
  > `SnapshotIsolationIndexesUniqueTest`.
  >
  > **Scope:** ~2-3 steps covering snapshot helper extraction,
  > checkVisibility() method, and refactoring of visibilityFilterMapped()
  >
  > **Track episode:**
  > Extracted `checkVisibility()` and `lookupSnapshotRid()` from the inline
  > visibility logic in `IndexesSnapshot`. `visibilityFilterMapped()` now
  > delegates to `checkVisibility()` per entry, establishing a single source
  > of truth for visibility decisions. The refactored `emitSnapshotVisibility()`
  > also delegates to `lookupSnapshotRid()`, eliminating one `CompositeKey`
  > allocation on the snapshot-fallback path. No cross-track impact — Track 2
  > can call `checkVisibility()` directly from `BTree.getVisible()` as planned.
  >
  > **Step file:** `tracks/track-1.md` (2 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected.

- [x] Track 2: Add `getVisible()` to BTree and CellBTreeSingleValue interface
  > Add the `getVisible(K key, IndexesSnapshot snapshot, @Nonnull AtomicOperation)` method
  > to the `CellBTreeSingleValue<K>` interface and implement it in `BTree<K>`.
  >
  > The implementation follows the existing `get()` pattern exactly:
  > - `keySerializer.preprocess(key)` + `serializeNativeAsWhole(key)` — same as
  >   the pre-SI `get()`
  > - `executeOptimisticStorageRead()` with optimistic + pinned fallback
  > - Optimistic path: descend to leaf (same loop as `getOptimistic()`), then
  >   use `bucket.find(serializedKey)` to locate the prefix match. From the
  >   found index, scan left to find the first version entry (0-1 steps for
  >   unique indexes), then scan forward calling
  >   `snapshot.checkVisibility()` per entry, short-circuit on first visible.
  > - Pinned path: `findBucketSerialized()` + `loadPageForRead()` + same
  >   forward scan
  > - Cross-page handling via `getRightSibling()` with optimistic stamp
  >   validation before following the pointer
  >
  > Constraints:
  > - No lambdas or stream construction
  > - Minimal allocations: 1 serialized key byte[] (same as pre-SI get()),
  >   1 BucketSearchResult (pinned path only)
  > - Must handle null keys uniformly (CompositeKey(null, version) in main B-tree)
  >
  > **Scope:** ~3-4 steps covering interface extension, optimistic+pinned
  > implementation, leaf scan with checkVisibility(), null key handling
  > **Depends on:** Track 1
  >
  > **Track episode:**
  > Added `getVisible()` to `CellBTreeSingleValue<K>` interface and implemented
  > in `BTree<K>` with optimistic+pinned two-path pattern. Key adaptation from
  > plan (D3): raw prefix key serialization fails because `IndexMultiValuKeySerializer`
  > requires full element count, so `buildSearchKey()` pads with `Long.MIN_VALUE`
  > instead — this eliminates the leftward scan, as `bucket.find()` returns the
  > insertion point at the first matching entry directly. 26 tests cover committed,
  > tombstone, multi-version, null key, empty tree, cross-page (both distinct keys
  > and same-key spanning pages), snapshot markers, equivalence vs stream path,
  > and `Long.MIN_VALUE` exact-match path. No cross-track impact — Track 3 can
  > call `getVisible()` from the engine as planned.
  >
  > **Step file:** `tracks/track-2.md` (2 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — no downstream impact detected.

- [x] Track 3: Wire `getVisible()` into engine's `get()` and add tests
  > Replace `BTreeSingleValueIndexEngine.get()` implementation to call
  > `sbTree.getVisible()` instead of `iterateEntriesBetween` + visibilityFilter
  > pipeline. The engine method becomes:
  > - `convertToCompositeKey(key)` → `sbTree.getVisible(compositeKey, indexesSnapshot, atomicOperation)`
  > - Return `Stream.of(rid)` or `Stream.empty()`
  >
  > Test coverage:
  > - Existing `SnapshotIsolationIndexesGetTest` must continue passing
  >   (functional equivalence)
  > - Existing `SnapshotIsolationIndexesUniqueTest` covers visibility scenarios
  > - Add unit tests for `getVisible()` directly: committed RecordId,
  >   TombstoneRID, SnapshotMarkerRID, in-progress entries, phantom entries,
  >   null keys, empty index, cross-page entries
  >
  > **Scope:** ~3-4 steps covering engine wiring, unit tests, integration test
  > verification
  > **Depends on:** Track 2
  >
  > **Track episode:**
  > Wired `getVisible()` into `BTreeSingleValueIndexEngine.get()` for all keys
  > (null and non-null), eliminating the stream pipeline entirely. Key deviation
  > from plan: null keys were initially excluded from `getVisible()` due to a
  > mistaken ClassCastException concern, but code review revealed `buildSearchKey()`
  > only pads the version slot (always LONG), so null keys work uniformly. Added
  > partial-key guard in `BTree.getVisible()` for composite indexes (no "null key"
  > concept). Code review also drove cleanup: removed dead `emitSnapshotVisibility()`
  > method, added `@Nonnull` to `visibilityFilterMapped` atomicOperation parameter,
  > and extracted `snapshotUserKeyMatches()` from `lookupSnapshotRid()`. Added 4
  > UNIQUE engine-level integration tests and strengthened RID value assertions.
  > No cross-track impact — this completes the optimization feature.
  >
  > **Step file:** `tracks/track-3.md` (2 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — all tracks complete, no downstream impact.

## Final Artifacts
- [x] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
