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
    Serializer["IndexMultiValuKeySerializer"]
    BTree -->|"binary search delegates to"| Serializer
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
- **IndexMultiValuKeySerializer** — Gains `compareInByteBuffer()` and
  `compareInByteBufferWithWALChanges()` overrides for zero-allocation field-by-field
  comparison during `bucket.find(byte[])` binary search. *(Track 5)*
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

#### D4: Partial deserialization to avoid full CompositeKey allocation

- **Problem**: `scanLeafForVisible()` calls `bucket.getEntry()` per entry,
  which deserializes the full `CompositeKey` (allocates ArrayList, boxes each
  field, constructs CompositeKey wrapper) and the RID value. Post-Track-3
  profiling on DISK storage confirms `deserializeKeyFromByteBuffer` (+43
  self-time samples) and `IndexMultiValuKeySerializer.deserialize` (+33) as
  the remaining regression vs the old stream cursor path. The old path spent
  ~200 samples on stream/pipeline overhead (now eliminated) but only ~43 on
  deserialization. The new path concentrates ALL work in the leaf scan,
  making deserialization the dominant cost.
- **Alternatives considered**:
  1. Use `compareKeyInDirectMemory()` to skip the terminator entry without
     deserializing — **rejected during Phase A review**: on a forward scan
     from `bucket.find()`'s insertion point, all entries (including the
     terminator) compare ≥ 0 vs the search key padded with `Long.MIN_VALUE`.
     The `cmp < 0` guard never fires in the common case.
  2. Split `getEntry()` into separate key and value deserialization, skip
     key deserialization for committed entries where only version + RID are
     needed — partial win, still deserializes the key.
  3. Byte-level prefix comparison + partial deserialization: compare prefix
     bytes (all key bytes except the last 8 version bytes) against the
     serialized search key for scan termination; read only the version Long
     and RID directly from page buffer positions without full CompositeKey
     deserialization. `IndexMultiValuKeySerializer.getObjectSizeInByteBuffer()`
     is O(1) (reads pre-stored 4-byte total size), so version position =
     `entryPosition + keySize - 8` and RID position = `entryPosition + keySize`.
  4. Full on-page byte-level prefix comparison with version-field-offset
     computation by walking N-1 fields — more complex, same result.
- **Rationale**: Option 3 chosen. For the common fast path (committed,
  `version < visibleVersion`, regular RecordId), `checkVisibility()` only
  needs the version number (Long) and RID — not the full CompositeKey.
  The `CompositeKey` is only needed for the rare snapshot-fallback path
  (`lookupSnapshotRid()`). Byte-level prefix comparison (comparing
  `keySize - 8` bytes of the entry against the search key) correctly
  identifies prefix matches (returns true) vs mismatches (returns false)
  because the serialized format is deterministic: same user-key prefix →
  identical bytes. `serializedKey` is already available in
  `getVisibleOptimistic/Pinned`. Full `getEntry()` deserialization is
  deferred to the rare snapshot-fallback case only.
- **Risks/Caveats**: The scan loop now uses raw byte positions to read
  version and RID, coupling it to the `IndexMultiValuKeySerializer`
  serialization layout (version = last 8 bytes of key, RID = 10 bytes
  after key). This coupling is acceptable because the layout is
  well-established and the optimization is contained within `BTree`.
  WAL-change handling is addressed by using `DurablePage.getLongValue()`
  and `getShortValue()` which are WAL-aware.
- **Implemented in**: *(deferred — removed from current plan pending profiling
  results after Track 5)*

#### D5: Zero-deserialization `compareInByteBuffer` for `IndexMultiValuKeySerializer`

- **Problem**: `IndexMultiValuKeySerializer` does not override
  `compareInByteBuffer()`, so `bucket.find(byte[])` falls through to the
  default `BinarySerializer` implementation which deserializes both the
  on-page key and the search key into full `CompositeKey` objects on every
  binary search step. Profiling confirms this as the dominant regression
  source: +23% more samples in `bucket.find()` on HEAD vs the old stream
  path. The old `find(K key)` only deserializes the on-page key (the search
  key is already a Java object), making it cheaper despite using objects.
- **Alternatives considered**:
  1. Revert to `find(K key)` in `getVisible()` — eliminates the double
     deserialization but still allocates CompositeKey per binary search step.
  2. Add `compareInByteBuffer` to `IndexMultiValuKeySerializer` with
     field-by-field in-buffer comparison — zero allocation on the hot path.
  3. Add raw `memcmp`-style byte comparison ignoring field structure —
     incorrect because field serialization is type-dependent (variable-length
     strings, different numeric widths).
- **Rationale**: Option 2 chosen. `CompositeKeySerializer` already has a
  working field-by-field `compareInByteBuffer` (line 464) that can serve as
  a template. The key difference: `IndexMultiValuKeySerializer` uses its own
  serialization layout with a `[typeId][fieldData]` format and type-specific
  serializers. Each per-type serializer (`LongSerializer`, `IntegerSerializer`,
  `UTF8Serializer`, etc.) already has optimized `compareInByteBuffer` overrides
  that compare directly from the buffer. The composite-level method walks
  fields in both the page buffer and search key array, delegating to each
  field's serializer.
- **Risks/Caveats**: Must handle all field types that `IndexMultiValuKeySerializer`
  supports (INTEGER, LONG, SHORT, STRING, etc.) and null fields. The WAL-aware
  variant must delegate to `compareInByteBufferWithWALChanges` per field.
- **Implemented in**: Track 5

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
- Partial deserialization in `scanLeafForVisible()` (D4) — deferred pending
  profiling after Track 5 confirms the overall optimization is positive.

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

- [~] Track 4: Add `compareInByteBuffer` to `IndexMultiValuKeySerializer`
  > *(Originally: field-by-field in-buffer comparison to fix +23% regression
  > in `bucket.find(byte[])` caused by the default deserialization-based
  > `compareInByteBuffer`.)*
  >
  > **Skipped:** Profiling showed the regression was caused by using
  > `find(byte[])` which triggers `IndexMultiValuKeySerializer`'s default
  > `compareInByteBuffer` (deserializes both sides). Switching `getVisible()`
  > back to `find(K key)` (object-based binary search, deserializes only the
  > on-page key) eliminates the regression entirely and delivers +11%
  > improvement over the old stream path. The `compareInByteBuffer` override
  > remains a valid future optimization if zero-allocation binary search is
  > pursued, but has no current regression motivating it.
  >
  > **Strategy refresh:** CONTINUE — no downstream impact from skipping.

- [x] Track 4a: Switch `getVisible()` from serialized to object-based binary search
  > Profiling revealed `getVisible()`'s use of `find(byte[])` with pre-serialized
  > keys was slower than the old `find(K key)` path because
  > `IndexMultiValuKeySerializer` lacks a `compareInByteBuffer` override,
  > falling through to the default which deserializes both sides. Switching
  > back to `find(K key)` eliminates the search key serialization overhead
  > (`preprocess` + `serializeNativeAsWhole`) and uses the cheaper object-based
  > binary search (only deserializes the on-page key).
  >
  > Changes to `BTree.java`:
  > - Remove `preprocess` + `serializeNativeAsWhole` from `getVisible()`
  > - Switch `bucket.find(serializedKey, ...)` → `bucket.find(searchKey, ...)`
  > - Switch `findBucketSerialized()` → `findBucket()` in pinned path
  > - Collapse `prefixKey` + `serializedKey` parameters to single `searchKey`
  >
  > Existing tests must continue passing — no behavioral change.
  >
  > **Scope:** ~1 step — single commit to BTree.java
  > **Depends on:** Track 3
  >
  > **Track episode:**
  > Switched `getVisible()` from `find(byte[])` + `findBucketSerialized()` to
  > `find(K key)` + `findBucket()`, eliminating `preprocess()` +
  > `serializeNativeAsWhole()` overhead. This avoids
  > `IndexMultiValuKeySerializer`'s default `compareInByteBuffer` which
  > deserializes both sides. The object-based path only deserializes the
  > on-page key, matching the existing `get()` method's approach. No behavioral
  > change — all 59 existing tests pass unchanged. No cross-track impact.
  >
  > **Step file:** `tracks/track-4a.md` (1 step, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — final track, no remaining tracks to impact.

- [x] Track 5: Zero-deserialization `compareInByteBuffer` for `IndexMultiValuKeySerializer`
  > Add `compareInByteBuffer()` and `compareInByteBufferWithWALChanges()` overrides
  > to `IndexMultiValuKeySerializer` that compare `CompositeKey` entries field-by-field
  > directly in the page buffer and search key byte array, without deserializing
  > either side into Java objects. Then switch `BTree.getVisible()` back to the
  > `find(byte[])` + `findBucketSerialized()` path (reversing Track 4a), which
  > now benefits from zero-allocation binary search.
  >
  > **Serialization layout** (both page buffer and search byte[]):
  > `[totalSize:4][keysCount:4]` then per field `[typeId:1][fieldData...]`.
  > Null fields have negative typeId `-(typeId+1)` and no field data.
  > This is distinct from `CompositeKeySerializer`'s layout, which uses
  > `NullSerializer.ID` for nulls — hence a new override is needed rather than
  > reusing the existing one.
  >
  > **Per-field comparison strategy** — inline for all types to avoid per-step
  > serializer lookups and virtual dispatch:
  > - `BOOLEAN`, `BYTE`: single byte comparison via `Byte.compare`
  > - `SHORT`: 2-byte comparison via `Short.compare`
  > - `INTEGER`: 4-byte comparison via `Integer.compare`
  > - `FLOAT`: 4-byte read → `Float.compare(intBitsToFloat(...))` — preserves
  >   NaN and negative-zero ordering that raw int comparison would break
  > - `LONG`, `DATE`, `DATETIME`: 8-byte comparison via `Long.compare`
  > - `DOUBLE`: 8-byte read → `Double.compare(longBitsToDouble(...))` — same
  >   NaN/negative-zero concern as FLOAT
  > - `STRING`: delegate to `UTF8Serializer.INSTANCE.compareInByteBuffer()` —
  >   already zero-alloc, handles UTF-8 code point decoding with surrogate pairs
  > - `LINK`: inline comparison — compare clusterId (short), then reconstruct
  >   clusterPosition (variable-length little-endian) and compare as long. Layout:
  >   `[clusterId:2][numberSize:1][clusterPosition:numberSize]`.
  > - `BINARY`: length-prefixed unsigned byte comparison — read 4-byte length from
  >   each side, compare `min(len1,len2)` bytes unsigned, then compare lengths
  > - `DECIMAL`: fallback to `BigDecimal` deserialization and `compareTo()` — no
  >   meaningful byte-level ordering exists for `(scale, unscaledValue)` pairs.
  >   Acceptable because DECIMAL index keys are rare in practice.
  >
  > **Reading from search byte[]**: Use `BinaryConverter` (Unsafe-accelerated) via
  > the static `CONVERTER` field for primitive reads (`getInt`, `getLong`, `getShort`)
  > from the search key byte array. This matches the pattern used by `LongSerializer`,
  > `IntegerSerializer`, and `ShortSerializer` in their own `compareInByteBuffer`
  > overrides. Single-byte reads use direct array indexing.
  >
  > **WAL-aware variant**: `compareInByteBufferWithWALChanges` uses the same
  > field-walking logic but reads page data through `WALChanges` overlay methods
  > (`getIntValue`, `getLongValue`, `getShortValue`, `getByteValue`,
  > `getBinaryValue`). Search key byte[] reads are identical (no WAL overlay for
  > the search key).
  >
  > **Performance regression risk**: The `find(byte[])` path re-introduces the cost
  > of `preprocess()` + `serializeNativeAsWhole()` (one-time per `getVisible()` call
  > — serializes the search key into a byte array). This is offset by eliminating
  > `CompositeKey` deserialization on every binary search step (~log2(N) steps per
  > lookup). For a leaf page with 100 entries, that's ~7 deserialization-free steps
  > vs 7 `CompositeKey` allocations. The net effect should be positive for all page
  > sizes. Key risk: if the inline comparison is slower than expected for STRING-heavy
  > indexes (UTF-8 code point decoding vs `String.compareTo()` on deserialized strings),
  > the optimization may not help for those indexes. INTEGER/LONG-keyed indexes
  > (the common case for SI version fields) will benefit the most.
  >
  > **Field size advancement**: After comparing each field, both the page offset and
  > search key offset must advance past the field data. This uses inline size
  > computation per type (matching `getKeySizeInByteBuffer`/`getKeySizeNative`
  > patterns) to avoid per-field virtual dispatch. STRING and LINK sizes require
  > reading a length prefix; fixed-size types use constants.
  >
  > **BTree.java changes** (reverse Track 4a):
  > - Add `serializeNativeAsWhole()` after the existing `preprocess()` in
  >   `getVisible()` — `preprocess()` was restored in commit 1a6c8c4dff for
  >   DATE key normalization; only `serializeNativeAsWhole()` needs to be
  >   re-added
  > - Both `getVisibleOptimistic()` and `getVisiblePinned()` gain a
  >   `byte[] serializedKey` parameter for the binary search, while retaining
  >   the `K searchKey` parameter for `scanLeafForVisible()`'s
  >   `userKeyPrefixMatches()` check
  > - Switch `bucket.find(searchKey, ...)` → `bucket.find(serializedKey, ...)`
  > - Switch `findBucket()` → `findBucketSerialized()` in pinned path
  >
  > **Test strategy**:
  > - Unit tests for `compareInByteBuffer`: serialize two CompositeKeys, compare
  >   via the new method, verify result matches `CompositeKey.compareTo()`. Cover
  >   all 12 field types, null fields, prefix comparison (fewer search keys than
  >   page keys), and mixed-type composite keys.
  > - Unit tests for `compareInByteBufferWithWALChanges`: same cases with WAL
  >   overlay.
  > - All existing `getVisible()` tests must continue passing — functional
  >   equivalence with the `find(K key)` path.
  >
  > **Scope:** ~3 steps covering compareInByteBuffer implementation + tests,
  > WAL-aware variant, BTree.java switchover + integration test verification
  > **Depends on:** Track 4a
  >
  > **Track episode:**
  > Added `compareInByteBuffer()` and `compareInByteBufferWithWALChanges()` overrides
  > to `IndexMultiValuKeySerializer` with field-by-field zero-deserialization comparison.
  > Non-WAL path delegates to existing serializer overrides for matching types; inlines
  > FLOAT/DOUBLE, BOOLEAN/BYTE, LINK, and DECIMAL. WAL path inlines all primitive reads
  > via walChanges methods; falls back to deserialization for STRING/LINK/BINARY/DECIMAL.
  > Switched `BTree.getVisible()` back to `find(byte[])` + `findBucketSerialized()` path.
  > Key deviation from plan: Track review correctly rejected delegation-to-factory approach
  > (T1/R1) — factory returns different serializers than what `IndexMultiValuKeySerializer`
  > uses (e.g., StringSerializer vs UTF8Serializer, LinkSerializer vs CompactedLinkSerializer).
  > No cross-track impact — this completes the optimization feature.
  >
  > **Step file:** `tracks/track-5.md` (2 steps, 0 failed)
  >
  > **Strategy refresh:** CONTINUE — final track, no downstream impact.
  > Phase 4 artifacts are stale (written before Track 5) and must be updated.

## Final Artifacts
- [x] Phase 4: Final artifacts (`design-final.md`, `adr.md`)
