# Optimize BTreeSingleValueIndexEngine.get() — Architecture Decision Record

## Summary

Replaced the `BTreeSingleValueIndexEngine.get(key)` implementation — which used
`iterateEntriesBetween` + stream pipeline (~14 allocations, shared lock) — with a
direct leaf-page lookup via `BTree.getVisible()` that preserves the pre-SI
`get()` cost profile (~2 allocations, lock-free optimistic reads). `get(key)` on
a unique index is the hottest index path, called for every `WHERE field = ?`
query, unique constraint check, and FK validation.

Additionally, `IndexMultiValuKeySerializer` gained zero-deserialization
`compareInByteBuffer()` overrides, enabling `getVisible()` to use serialized-key
binary search (`find(byte[])`) with field-by-field in-buffer comparison —
eliminating `CompositeKey` allocation during every binary search step. Profiling
confirmed 98% reduction in deserialization allocations and -8.3% vs develop
(down from -22% before the optimization).

## Goals

- **Eliminate stream pipeline overhead**: Achieved. The engine's `get()` is now a
  single `sbTree.getVisible()` call returning `@Nullable RID`, wrapped in
  `Stream.of()`/`Stream.empty()`.
- **Lock-free optimistic reads on happy path**: Achieved. Uses
  `executeOptimisticStorageRead()` with pinned fallback, identical to pre-SI
  `get()`.
- **Minimal allocations**: Achieved. 1 padded search key (`buildSearchKey()`),
  1 serialized key byte array (`serializeNativeAsWhole()`), plus per-entry
  deserialization during the leaf scan (for `checkVisibility()`). Binary search
  steps are zero-allocation thanks to `compareInByteBuffer()`.
- **Null keys use same code path**: Achieved. Initially excluded from
  `getVisible()` due to a mistaken `ClassCastException` concern; resolved when
  code review confirmed `buildSearchKey()` only pads the version slot (LONG type).

## Constraints

- **No lambdas on hot path**: Met. `scanLeafForVisible()` is a plain loop calling
  `checkVisibility()` — no lambda captures.
- **BTree remains a pure storage structure**: Met. `IndexesSnapshot` is passed as
  a parameter; BTree holds no SI fields.
- **Existing stream-based methods unchanged**: Met. Range scans and iterations
  continue using `iterateEntriesBetween` + `visibilityFilterMapped()`.
- **Cross-page entries handled**: Met. Pinned path follows `getRightSibling()`;
  optimistic path falls back to pinned when entries span pages.

## Architecture Notes

### Component Map

```mermaid
graph LR
    Engine["BTreeSingleValueIndexEngine"]
    Interface["CellBTreeSingleValue&lt;K&gt;"]
    BTree["BTree&lt;K&gt;"]
    Bucket["CellBTreeSingleValueBucketV3"]
    Snapshot["IndexesSnapshot"]
    Serializer["IndexMultiValuKeySerializer"]

    Engine -->|"get() calls getVisible()"| Interface
    Interface <|.. BTree
    BTree -->|"reads leaf entries"| Bucket
    BTree -->|"param: visibility check"| Snapshot
    Bucket -->|"find(byte[]) delegates to"| Serializer
```

- **BTreeSingleValueIndexEngine** — `get()` converts key to `CompositeKey`,
  calls `sbTree.getVisible()`, wraps result in `Stream.of(rid)` or
  `Stream.empty()`.
- **CellBTreeSingleValue\<K\>** — Interface extended with `getVisible(K key,
  IndexesSnapshot snapshot, @Nonnull AtomicOperation op)`.
- **BTree\<K\>** — Implements `getVisible()` with optimistic + pinned two-path
  pattern. `buildSearchKey()` pads with `Long.MIN_VALUE`;
  `serializeNativeAsWhole()` produces the byte[] for `find(byte[])`;
  `scanLeafForVisible()` applies inline visibility; `userKeyPrefixMatches()`
  checks prefix equality.
- **CellBTreeSingleValueBucketV3** — Unchanged. Existing `find(byte[])`,
  `find(K)`, `getEntry()`, `size()`, `getRightSibling()` used by the scan logic.
- **IndexMultiValuKeySerializer** — `compareInByteBuffer()` provides
  zero-deserialization field-by-field comparison for `bucket.find(byte[])`.
  `compareInByteBufferWithWALChanges()` provides the WAL-aware variant.
- **IndexesSnapshot** — `checkVisibility()` encapsulates the full visibility
  decision. `lookupSnapshotRid()` handles snapshot-fallback lookups.
  `snapshotUserKeyMatches()` validates prefix matches between B-tree and snapshot
  key layouts. `visibilityFilterMapped()` delegates to `checkVisibility()` per
  entry (refactored from inline logic).

### Decision Records

#### D1: Inline visibility in BTree vs. engine-level filtering

Implemented as planned (option 2). `getVisible()` accepts `IndexesSnapshot` as a
parameter and applies visibility inline during the leaf scan via
`checkVisibility()`. Short-circuits on first visible entry.

#### D2: Optimistic read scope for multi-entry prefix scan

Implemented as planned (option 1 for common case, option 3 for cross-page).
Single optimistic scope covers tree descent + leaf scan. Cross-page case falls
back to pinned path (optimistic path throws `OptimisticReadFailedException` when
the leaf is exhausted, triggering the pinned path which follows
`getRightSibling()` under shared lock).

#### D3: Prefix key matching via padded search key

**Modified from plan.** Option 2 (raw prefix key) cannot be implemented because
`IndexMultiValuKeySerializer.serialize()` iterates up to `types.length` elements
— a shorter `CompositeKey` causes `IndexOutOfBoundsException`. Instead,
`buildSearchKey()` pads the prefix key with `Long.MIN_VALUE` as the version
component. This sorts the search key before all real versioned entries, so
`bucket.find()` returns the insertion point at the first matching entry directly.
The functional result is identical to the original option 2, and the leftward
scan described in the plan is eliminated entirely.

**Trade-off:** One additional `CompositeKey` allocation for the padded key (the
original plan assumed zero extra allocations from raw prefix reuse). Acceptable
because the padded key replaces the 2 `enhanceCompositeKey` allocations from the
old stream path.

**Evolution through Tracks 4a→5:** Track 4a switched to `find(K key)` (object-
based) because `IndexMultiValuKeySerializer` lacked `compareInByteBuffer`,
causing double deserialization on `find(byte[])`. Track 5 added the
`compareInByteBuffer` override and switched back to `find(byte[])` +
`findBucketSerialized()`. The final implementation re-introduces
`serializeNativeAsWhole()` (one allocation per `getVisible()` call) but
eliminates `CompositeKey` deserialization on every binary search step (~7 steps
per leaf page with 100 entries).

#### D4: Snapshot lookup prefix validation (emerged during execution)

`lookupSnapshotRid()` uses `ConcurrentSkipListMap.lowerEntry()` to find the
latest snapshot entry visible at a given version. Because `addSnapshotPair()`
writes two entries non-atomically (TombstoneRID first, then RecordId), a reader
can temporarily see a foreign key's `TombstoneRID` via `lowerEntry()`.
`snapshotUserKeyMatches()` guards against this by validating that the returned
snapshot entry's user-key prefix matches the B-tree entry's prefix, accounting
for the layout difference (snapshot keys have an `indexId` prefix that B-tree
keys lack).

#### D5: Partial-key guard for composite indexes (emerged during execution)

`getVisible()` checks that the input key has at least `keySize - 1` user
elements before proceeding. For composite indexes (e.g., 3-field), a
`CompositeKey(null)` with only 1 element is not a valid "null key" lookup — it's
a partial key that would match unrelated entries. The guard returns null
immediately, preventing false matches.

#### D6: Zero-deserialization `compareInByteBuffer` for `IndexMultiValuKeySerializer` (Track 5)

**Problem:** The default `BinarySerializer.compareInByteBuffer()` deserializes
both the on-page key and the search key into full `CompositeKey` objects on every
binary search step. With `find(byte[])`, this doubled deserialization cost vs
`find(K key)` which only deserializes the on-page key.

**Solution:** Added `compareInByteBuffer()` and
`compareInByteBufferWithWALChanges()` overrides to `IndexMultiValuKeySerializer`
with field-by-field in-buffer comparison. The non-WAL path delegates to per-type
serializer overrides where the on-disk format matches (LONG, INTEGER, SHORT,
STRING via `UTF8Serializer`, BINARY); inlines comparison for FLOAT/DOUBLE (stored
as raw int/long bits), BOOLEAN/BYTE, LINK (compacted format), and DECIMAL
(BigDecimal fallback). The WAL path inlines all primitive reads via `walChanges`
methods; falls back to deserialization for STRING, LINK, BINARY, DECIMAL.

**Key constraint:** Delegation must use direct serializer references
(`UTF8Serializer.INSTANCE`, not `serializerFactory.getObjectSerializer()`).
The factory returns different serializers than what `IndexMultiValuKeySerializer`
uses for serialization (StringSerializer vs UTF8Serializer, LinkSerializer vs
CompactedLinkSerializer). FLOAT/DOUBLE are stored as raw int/long bits —
delegating to IntegerSerializer/LongSerializer would give wrong ordering for
negative values.

**Result:** 98% reduction in deserialization allocations during binary search.
The dominant cost is now `Integer.compare` (pure comparison work with zero
allocation) — the ideal profile.

### Invariants

- `getVisible()` returns the same RID as the old `get()` + `visibilityFilter()`
  pipeline for all key types, RID types, and transaction states. Verified by 6
  equivalence tests comparing both paths with independent expected values.
- `getVisible()` uses `executeOptimisticStorageRead()` — no shared lock on the
  happy path. Verified by code review.
- Cross-page entries handled via `getRightSibling()` in pinned path. Tested with
  300-entry page-split scenarios.
- Null keys handled uniformly by `getVisible()`. Tested explicitly.
- `compareInByteBuffer()` produces the same comparison result as
  `CompositeKey.compareTo()` for all 12 field types, null fields, and prefix
  keys. Verified by 40 unit tests cross-checking both paths.

### Integration Points

- `BTreeSingleValueIndexEngine.get()` is the sole caller of `getVisible()`.
- `IndexesSnapshot.checkVisibility()` is called from both `scanLeafForVisible()`
  (direct path) and `visibilityFilterMapped()` (stream path) — single source of
  truth.
- `IndexMultiValuKeySerializer.compareInByteBuffer()` is called by
  `CellBTreeSingleValueBucketV3.find(byte[])` during binary search in
  `getVisible()`.
- The existing `get()` on `CellBTreeSingleValue` remains for non-SI callers and
  is unused by `BTreeSingleValueIndexEngine` after this change.

### Non-Goals

- Optimizing `BTreeMultiValueIndexEngine.get()` — different engine.
- Optimizing range scans — stream pipeline is appropriate for multi-result
  iteration.
- Removing the null bucket file (`.nbt`) — remains for direct null access.
- Partial deserialization in `scanLeafForVisible()` (D4 from plan) — deferred.
  The leaf scan still deserializes full `CompositeKey` via `getEntry()` for
  `checkVisibility()`. Profiling shows this is now a minor cost relative to
  the eliminated stream pipeline overhead.

## Key Discoveries

1. **`IndexMultiValuKeySerializer` requires full element count.** The serializer
   iterates `types.length` elements regardless of the key's actual size. A prefix
   `CompositeKey` with fewer elements causes `IndexOutOfBoundsException`. This
   drove the `buildSearchKey()` approach (pad with `Long.MIN_VALUE`) instead of
   raw prefix key serialization. Future work touching key serialization should be
   aware of this coupling between serializer and key element count.

2. **`Long.MIN_VALUE` eliminates leftward scan.** Padding the search key with
   `Long.MIN_VALUE` places it before all real versioned entries in sort order.
   `bucket.find()` returns the insertion point at the first matching entry
   directly, simplifying the scan from "find arbitrary match, scan left, then
   scan right" to just "scan right from insertion point."

3. **In-progress/phantom visibility cannot be tested through real BTree infra.**
   `AtomicOperationsSnapshot` derives `visibleVersion` from the real transaction
   counter, which cannot be controlled in tests. These paths are thoroughly
   tested at the `IndexesSnapshot.checkVisibility()` unit test level (15 tests),
   and `getVisible()` delegates to `checkVisibility()` without additional logic.

4. **Non-atomic snapshot writes require prefix validation.** `addSnapshotPair()`
   writes TombstoneRID before RecordId. A concurrent reader can see a foreign
   key's TombstoneRID via `lowerEntry()`. `snapshotUserKeyMatches()` guards
   against this by comparing the B-tree key prefix (no indexId) against the
   snapshot key prefix (has indexId offset).

5. **Null keys work uniformly with `buildSearchKey()`.** The version slot is
   always `LONG` type regardless of user-key types, so `Long.MIN_VALUE` padding
   works identically for null and non-null user keys. Initial concern about
   `ClassCastException` in preprocessing was unfounded.

6. **Factory serializers differ from `IndexMultiValuKeySerializer`'s actual
   serializers.** `serializerFactory.getObjectSerializer()` returns different
   serializers than what `IndexMultiValuKeySerializer` uses: STRING →
   `StringSerializer` (factory) vs `UTF8Serializer` (actual), LINK →
   `LinkSerializer` vs `CompactedLinkSerializer`, FLOAT/DOUBLE → stored as raw
   int/long bits. Delegation for `compareInByteBuffer` must use direct serializer
   references (`UTF8Serializer.INSTANCE`, etc.), not factory lookups. FLOAT/DOUBLE
   require `Float.compare(intBitsToFloat(...))` / `Double.compare(longBitsToDouble(...))`
   to preserve NaN and negative-zero ordering.

7. **WAL-aware serializers lack `compareInByteBufferWithWALChanges` overrides.**
   `LongSerializer`, `IntegerSerializer`, `ShortSerializer`, `UTF8Serializer` do
   not override `compareInByteBufferWithWALChanges` — they fall through to the
   default (deserialization). The WAL-aware `compareFieldWAL` must inline all
   primitive reads via `walChanges.getLongValue/getIntValue/getShortValue/
   getByteValue` and fall back to deserialization for complex types (STRING, LINK,
   BINARY, DECIMAL). This is acceptable because WAL overlays are transient.
