# Zero-Copy EntityImpl

## High-level plan

### Goals

Eliminate per-record `byte[]` allocation and `System.arraycopy` overhead on the read path by
letting `EntityImpl` deserialize directly from the disk cache page buffer. For read-heavy
workloads this removes:

- **Allocation overhead**: one `byte[]` per record load
- **Copy overhead**: `System.arraycopy`-equivalent from direct memory to heap
- **GC pressure**: short-lived `byte[]` arrays in young generation

The implementation is split into two stages: (1) deferred copy via thread-local buffer (eliminates
per-record allocation), and (2) true zero-copy via `ByteBuffer`-aware serializer (eliminates both
allocation and copy).

**Expected improvements:**
- Stage 1 (deferred copy): ~30-50% reduction in young-gen allocation for read-heavy workloads
- Stage 2 (full zero-copy): ~50-200ns savings per record read. For a scan of 10K records x
  500 bytes avg, that's ~5MB of avoided allocation + copy
- Under warm cache (typical production), optimistic stamps should be valid >99% of the time
- Fallback frequency depends on cache hit ratio and eviction rate -- never worse than today

### Constraints

1. **Builds on pinless-disk-cache infrastructure** -- assumes `PageFrame` + `PageFramePool` +
   `StampedLock`-based optimistic reads + `OptimisticReadScope` + `loadPageOptimistic()` +
   `executeOptimisticStorageRead()` are all in place from the pinless-disk-cache branch.
2. **No cache pinning** -- `EntityImpl` must NOT hold `CacheEntry` references or prevent
   eviction. Only stores a `PageFrame` reference (keeps Java object alive, does not pin cache).
3. **No use-after-free** -- speculative reads must never touch unmapped memory. `PageFrame` pool
   never deallocates during normal operation (invariant from pinless-disk-cache).
4. **Graceful degradation** -- fallback to current copy behavior on stamp failure. Never worse
   than today.
5. **Multi-chunk records always copy** -- records spanning multiple pages use current copy
   behavior. Zero-copy applies only to single-page records.
6. **WAL overlay always copies** -- pages with local modifications in `AtomicOperation` use
   current copy behavior (via `requiresPinnedRead()` from pinless-disk-cache).
7. **Allocation safety** -- all size-driven allocations during speculative reads must be
   bounded to prevent OOM from garbage data in reused frames.
8. **Sealed interfaces for JIT optimization** -- `RecordData` and `BytesSource` use `sealed`
   for bimorphic inline caching (HotSpot C2 exploits sealed hierarchies since JDK 17+).

### Architecture Notes

#### Component Map

```mermaid
graph TD
    DSE[DatabaseSessionEmbedded] -->|executeReadRecord| AS[AbstractStorage]
    AS -->|readRecord| PCV2[PaginatedCollectionV2]
    PCV2 -->|internalReadRecord| CP[CollectionPage]
    PCV2 -->|returns| RD{RecordData}
    RD -->|PageRecordRef| PF[PageFrame]
    RD -->|RawBuffer| HEAP[byte array]
    DSE -->|fillFromPageRef / fill| RA[RecordAbstract]
    RA <|-- EI[EntityImpl]
    EI -->|deserializeProperties| SER[RecordSerializerBinaryV1]
    SER -->|reads via| BC[BytesContainer]
    BC -->|backed by| BS{BytesSource}
    BS -->|HeapBytesSource| HEAP
    BS -->|DirectBytesSource| BB[ByteBuffer]
    BB -.->|from| PF
    SER -->|varint decoding| VIS[VarIntSerializer]
```

- **PaginatedCollectionV2** -- modified: `internalReadRecord()` returns `RecordData` (sealed
  interface) instead of always `RawBuffer`. For single-page records with valid optimistic stamp,
  returns `PageRecordRef` (coordinates only, no byte copy).
- **CollectionPage** -- modified: exposes `getRecordDataOffset(recordPosition)` to compute the
  absolute byte offset within the page buffer for a record's content.
- **RecordAbstract** -- modified: adds `@Nullable PageRecordRef pageRecordRef` field,
  `fillFromPageRef()` method, cleanup in `unload()`/`fromStream()`.
- **EntityImpl** -- modified: `deserializeProperties()` dispatches to `deserializeFromPageFrame()`
  when `pageRecordRef != null`. Fast path: copy to thread-local buffer + validate stamp (Stage 1)
  or deserialize directly from `ByteBuffer` (Stage 2).
- **DatabaseSessionEmbedded** -- modified: `executeReadRecord()` dispatches on `RecordData` type
  (`PageRecordRef` vs `RawBuffer`).
- **AbstractStorage / DiskStorage** -- modified: `readRecord()` return type changes to
  `RecordData`.
- **RecordSerializerBinaryV1** -- modified: replaces direct `bytes.bytes[offset]` array access
  with `bytes.source.get(offset)` throughout deserialization methods.
- **VarIntSerializer** -- modified: adds `BytesSource` overloads for `readAsInteger()`,
  `readUnsignedVarLong()`, `readSignedVarLong()`.
- **BytesContainer** -- modified: `byte[] bytes` replaced with `BytesSource source`. Adds
  `maxBound` field for allocation safety during speculative reads.
- **PageFrame** -- unchanged (from pinless-disk-cache). Referenced by `PageRecordRef`.

New components:
- **RecordData** -- sealed interface: `sealed interface RecordData permits RawBuffer, PageRecordRef`
- **PageRecordRef** -- record: `(PageFrame, stamp, offsetInPage, length, recordVersion,
  recordType, fileId, pageIndex)`
- **BytesSource** -- sealed interface with `HeapBytesSource` and `DirectBytesSource`
- **ThreadLocalBuffer** -- thread-local reusable `byte[]` for Stage 1 deferred copy
- **SpeculativeReadException** -- unchecked exception for allocation guard failures

#### D1: Sealed RecordData interface for type-safe record read results

- **Alternatives considered**:
  - (a) Return `RawBuffer` with a nullable `PageRecordRef` field
  - (b) Return `Object` and `instanceof` check at call site
  - (c) Sealed interface with pattern matching (chosen)
- **Rationale**: Alternative (a) mixes two concerns in one class and wastes a field on every
  RawBuffer. Alternative (b) loses type safety. Alternative (c) provides exhaustive pattern
  matching (`switch (data) { case PageRecordRef ref -> ...; case RawBuffer buf -> ...; }`) with
  compile-time completeness checking. The JIT handles sealed bimorphic dispatch with zero overhead.
- **Risks/Caveats**: Adds a new interface to the storage layer API. The interface must include a
  `toRawBuffer()` materialization method so non-EntityImpl callers (CollectionBasedStorageConfiguration,
  DatabaseCompare) can extract raw bytes from any `RecordData` variant. `RawBuffer.toRawBuffer()`
  returns `this`; `PageRecordRef.toRawBuffer()` copies bytes from the page frame (with stamp
  validation + fallback reload).
- **Implemented in**: Leaf 1.1.1, Leaf 1.1.2

#### D2: Thread-local buffer for deferred copy (Stage 1)

- **Alternatives considered**:
  - (a) Per-read `new byte[]` allocation (current behavior)
  - (b) Thread-local reusable buffer (chosen)
  - (c) Skip Stage 1 and go directly to full zero-copy
- **Rationale**: Alternative (a) is what we're trying to eliminate. Alternative (c) requires the
  full serializer refactoring (Area 3) before any benefit is realized -- a large, risky change
  with delayed feedback. Alternative (b) delivers most of the allocation savings immediately:
  the copy still happens, but into a reusable buffer rather than a fresh allocation. Size the
  buffer to common record sizes (e.g., 2KB); records larger than the buffer fall back to heap
  allocation. This gives incremental, measurable improvement before the serializer is touched.
- **Risks/Caveats**: Thread-local buffer must be properly sized. Too small = frequent heap
  fallback (still better than today). Too large = wasted thread-local memory. 2KB is a reasonable
  default for typical entity records.
- **Implemented in**: Leaf 2.1.2

#### D3: Sealed BytesSource interface for JIT-friendly bimorphic dispatch

- **Alternatives considered**:
  - (a) `if (bytes != null)` branching at every read site
  - (b) Abstract class with virtual dispatch
  - (c) Sealed interface with exactly 2 implementations (chosen)
- **Rationale**: Alternative (a) is invasive and error-prone (every read site must branch).
  Alternative (b) works but the JIT may generate megamorphic dispatch if the hierarchy grows.
  Alternative (c) gives the JIT a **closed-world guarantee** -- exactly 2 implementations, never
  subclassed. HotSpot C2/Graal inlines BOTH implementations at each call site with a type check
  branch, eliminating virtual dispatch. Within a single `deserialize()` call, every `BytesSource`
  method hits the same implementation, so the branch predictor is 100% accurate. Sealed interface
  ~ same runtime cost as manual `if` branching but with cleaner code and type safety.
- **Risks/Caveats**: Adds an indirection layer to the serializer hot path. Profile to confirm
  near-zero overhead; if measurable, fall back to manual branching.
- **Implemented in**: Leaf 3.1.1, Leaf 3.1.2

#### D4: Bounds-checked allocations during speculative reads

- **Alternatives considered**:
  - (a) Catch only `OptimisticReadFailedException` during speculative deserialization
  - (b) Guard every possible buffer read site individually
  - (c) Centralized `maxBound` on `BytesContainer` + per-site guards for collections (chosen)
- **Rationale**: During speculative reads from a reused/dirty frame, decoded sizes can be garbage
  (e.g., `VarIntSerializer.readAsInteger()` returns 2 billion). If the deserializer does
  `new ArrayList<>(size)` or `new byte[len]`, that triggers OOM **before** the post-read
  `validate(stamp)` check. Alternative (a) misses these OOM scenarios. Alternative (b) is
  fragile and incomplete. Alternative (c) provides a centralized guard (`maxBound` on
  `BytesContainer` set to record length for speculative reads) that catches most cases, plus
  explicit guards in the serializer for collection/string allocations computed from multiple
  decoded values.
- **Risks/Caveats**: Must audit all allocation sites in the serializer. Missing a guard means
  potential OOM on speculative read (caught by `executeOptimisticStorageRead()`'s exception
  handler, but wasteful).
- **Implemented in**: Leaf 2.1.3, Leaf 2.1.4

### Invariants

1. **EntityImpl never holds CacheEntry references.** Only stores `PageRecordRef` containing a
   `PageFrame` reference (Java object stays alive) and coordinates. The cache is free to evict
   pages at any time after `internalReadRecord()` releases the CacheEntry.
   Tests: verify `PageRecordRef` contains no `CacheEntry` field (Leaf 1.1.2).

2. **Stamp validation MUST precede any buffer access** in the deferred deserialization path.
   The `StampedLock`'s acquire fence in `validate()` ensures the stamp check is visible before
   any subsequent memory access. Compiler/JIT cannot reorder past the fence.
   Tests: stamp invalidation → fallback test (Leaf 2.1.2).

3. **All size-driven allocations during speculative reads are bounded.** Any decoded size
   exceeding `BytesContainer.maxBound` (set to record length) throws
   `SpeculativeReadException`, caught by the fallback handler.
   Tests: garbage size → SpeculativeReadException → fallback test (Leaf 2.1.3).

4. **Multi-chunk records always fall back to copy.** When `nextPagePointer >= 0` in
   `internalReadRecord()`, the current `RawBuffer` path is used unconditionally.
   Tests: multi-page record → RawBuffer test (Leaf 1.2.1).

5. **Lifecycle cleanup prevents stale references.** `RecordAbstract.unload()` and
   `RecordAbstract.fromStream()` set `pageRecordRef = null`, dropping the PageFrame reference.
   Tests: unload/fromStream clears pageRecordRef (Leaf 2.1.1).

### Integration Points

- **PaginatedCollectionV2.internalReadRecord()** -- capture point. Returns `PageRecordRef` for
  single-page records with valid optimistic stamp, `RawBuffer` otherwise.
- **AbstractStorage.readRecord()** / **DiskStorage.readRecord()** -- return type changes from
  `RawBuffer` to `RecordData`.
- **DatabaseSessionEmbedded.executeReadRecord()** -- dispatch point. Pattern-matches on
  `RecordData` to call `fillFromPageRef()` or existing `fill()`/`fromStream()`.
- **EntityImpl.deserializeProperties()** -- deserialization entry point. Dispatches to
  `deserializeFromPageFrame()` when `pageRecordRef != null`.
- **RecordSerializerBinaryV1.fromStream()** -- gains `BytesSource` overload (Stage 2).
  Existing `byte[]` overload wraps in `HeapBytesSource`.

### Non-Goals

- **Pinless disk cache infrastructure** -- `PageFrame`, `PageFramePool`, `CachePointer` lock
  delegation, `OptimisticReadScope`, `loadPageOptimistic()`, `executeOptimisticStorageRead()`,
  eviction stamp invalidation, DurableComponent optimistic read helpers -- all implemented in
  the pinless-disk-cache branch. This plan assumes that infrastructure is in place.
- **Multi-chunk records** -- records spanning multiple pages always use current copy behavior.
  Optimization not worth the complexity for large records.
- **Embedded entities** -- entities stored as embedded values within other entities are
  deserialized inline. No separate page reference.
- **In-memory engine** (`EngineMemory`) -- pages are never evicted, different trade-offs. Defer.
- **Write path changes** -- only the read/deserialization path is affected.

## Checklist

- [ ] Area 1: Record coordinate capture and deferred read
  > Introduce `RecordData` sealed interface and `PageRecordRef` record to capture record
  > coordinates (PageFrame reference, stamp, offset, length) during `internalReadRecord()`
  > instead of copying bytes. This is the foundation that enables both Stage 1 (deferred
  > copy) and Stage 2 (true zero-copy).
  >
  > **What**: Change `PaginatedCollectionV2.internalReadRecord()` to return either
  > `PageRecordRef` (single-page record, valid stamp) or `RawBuffer` (multi-page, stamp
  > invalid, or WAL overlay) via a sealed `RecordData` interface. Expose record data
  > offset from `CollectionPage`. Propagate return type change through `AbstractStorage`.
  >
  > **How**: Create sealed interface + record types, add `getRecordDataOffset()` to
  > `CollectionPage`, modify `internalReadRecord()` to capture coordinates on the
  > optimistic fast path, propagate `RecordData` return type upward.
  >
  > **Constraints**:
  > - Must use `loadPageOptimistic()` and `tryOptimisticRead()` from the pinless-disk-cache
  >   infrastructure to acquire stamps.
  > - `PageRecordRef` stores `fileId` + `pageIndex` for re-loading on fallback.
  > - `CacheEntry` is released by try-with-resources at the end of `internalReadRecord()`.
  >   Cache can evict the page immediately after.
  > - The optimistic read path in `internalReadRecord()` is nested inside the existing
  >   `executeOptimisticStorageRead()` from pinless-disk-cache.
  >
  > **Interactions**: Consumed by Area 2 (EntityImpl integration). Depends on
  > pinless-disk-cache being complete (PageFrame, optimistic read infrastructure).
  - [ ] Sub-area 1.1: RecordData types and CollectionPage offset exposure
    - [ ] Leaf: Create RecordData sealed interface and make RawBuffer implement it
      > Create `RecordData.java` as a sealed interface: `sealed interface RecordData permits
      > RawBuffer, PageRecordRef`. Add methods: `long version()`, `byte recordType()`,
      > `@Nullable byte[] buffer()` (returns raw bytes or null), `RawBuffer toRawBuffer()`
      > (materializes as RawBuffer -- needed by non-EntityImpl callers like
      > CollectionBasedStorageConfiguration and DatabaseCompare).
      >
      > `RawBuffer`: add `implements RecordData`. `toRawBuffer()` returns `this`. Record
      > components already satisfy `version()`, `recordType()`, `buffer()`.
      >
      > **Files**: new `core/.../internal/core/storage/RecordData.java`,
      > `RawBuffer.java` (add `implements RecordData`)
    - [ ] Leaf: Create PageRecordRef record
      > Create `PageRecordRef.java`: `public record PageRecordRef(PageFrame pageFrame,
      > long stamp, int offsetInPage, int length, long recordVersion, byte recordType,
      > long fileId, int pageIndex) implements RecordData`. Add `isValid()` method that
      > delegates to `pageFrame.validate(stamp)`. Implement `version()` returning
      > `recordVersion`, `recordType()` returning `recordType`.
      >
      > `buffer()` returns `null` (no pre-materialized byte array). `toRawBuffer()`:
      > validates stamp → if valid, copies bytes from `pageFrame.getBuffer()` at
      > `offsetInPage` for `length` bytes → validates again → if still valid, returns
      > `new RawBuffer(copiedBytes, recordVersion, recordType)`. If stamp invalid at any
      > point, falls back to re-loading page from cache via
      > `readCache.loadForRead(fileId, pageIndex)` → copies bytes under shared lock →
      > releases page → returns `RawBuffer`. This method requires a `ReadCache` parameter
      > or a functional callback for the fallback path.
      >
      > **Files**: new `core/.../internal/core/storage/PageRecordRef.java`
      > **Tests**: `PageRecordRefTest` (isValid delegates to PageFrame.validate, accessor
      > methods return correct values, toRawBuffer with valid stamp, toRawBuffer with
      > invalid stamp triggers fallback)
    - [ ] Leaf: CollectionPage -- expose record offset and next-page pointer methods
      > Add methods to `CollectionPage` that enable zero-copy reads without byte-array
      > allocation:
      >
      > 1. `public int getRecordDataOffset(int recordPosition)` -- returns the absolute byte
      >    offset within the page buffer where the record bytes begin (after the 3-INT entry
      >    header: version, entry size, and flags). This is `entryPosition + 3 * INT_SIZE`.
      >    Extract from `getRecordBinaryValue()` offset computation.
      >
      > 2. `public long getNextPagePointer(int recordPosition)` -- reads the 8-byte
      >    next-page pointer at the END of the record data without copying bytes. Located at
      >    `getRecordDataOffset(pos) + getRecordSize(pos) - LONG_SIZE`. Returns the raw long;
      >    negative means single-page record (no continuation).
      >
      > Record bytes layout (for single-page record):
      > ```
      > [metadata header: recordType(1B) + contentSize(4B) + collectionPos(8B)]
      > [entity data: contentSize bytes]
      > [chunk type byte(1B)]
      > [nextPagePointer(8B)]
      > ```
      >
      > Entity data offset for zero-copy = `getRecordDataOffset(pos) + 13` (skip 13-byte
      > metadata header). Entity data length = `readContentSize` (4 bytes at
      > `getRecordDataOffset(pos) + 1`), or computed as `getRecordSize(pos) - 22`.
      >
      > **Files**: `CollectionPage.java`
      > **Tests**: `CollectionPageTest` -- verify `getRecordDataOffset` + `getNextPagePointer`
      > match values extracted by existing `getRecordBinaryValue` + manual parsing
  - [ ] Sub-area 1.2: Modify internalReadRecord and propagate RecordData
    - [ ] Leaf: PaginatedCollectionV2 -- return PageRecordRef for single-page optimistic reads
      > Modify `internalReadRecord()` return type from `RawBuffer` to `RecordData`. On the
      > optimistic path:
      > 1. Read metadata: `recordVersion`, `isDeleted`, `recordSize` via existing methods
      > 2. Check `nextPagePointer` via new `CollectionPage.getNextPagePointer(recordPosition)`
      > 3. If single-page (`nextPagePointer < 0`) AND stamp valid:
      >    - Read record type byte from page buffer at metadata header offset (position 0
      >      within record data = `getRecordDataOffset(pos)`)
      >    - Read entity data length from embedded `contentSize` field (4 bytes at
      >      `getRecordDataOffset(pos) + 1`)
      >    - Compute entity data offset: `getRecordDataOffset(pos) + 13` (skip 13-byte
      >      metadata header: type(1B) + contentSize(4B) + collectionPos(8B))
      >    - Return `new PageRecordRef(pageFrame, stamp, entityDataOffset, entityDataLength,
      >      recordVersion, recordType, fileId, pageIndex)`
      >
      > `PageRecordRef.offsetInPage` points to where the **entity data** starts (same bytes
      > the serializer currently receives in `recordContent`). `PageRecordRef.length` is the
      > entity data length (same as `readContentSize` from `parseRecordContent()`).
      >
      > Multi-page records (`nextPagePointer >= 0`) and stamp-invalid cases fall through to
      > existing `RawBuffer` path via `parseRecordContent()`.
      >
      > Update `doReadRecord()`, `readRecord()` return types to `RecordData`.
      >
      > **Files**: `PaginatedCollectionV2.java`
      > **Tests**: unit test verifying single-page record returns `PageRecordRef` with
      > correct entity data offset/length, multi-page record returns `RawBuffer`
    - [ ] Leaf: Propagate RecordData return type through storage interfaces and callers
      > Change return type from `RawBuffer` to `RecordData` in the full call chain:
      > - `Storage.java` interface: `readRecord()` (line 81)
      > - `StorageCollection.java` interface: `readRecord()` (line 94)
      > - `AbstractStorage.java`: `readRecord()` (line 1649), `readRecordInternal()` (line 3782),
      >   `doReadRecord()` (line 4069)
      > - `PaginatedCollectionV2.java`: already changed in Leaf 1.2.1
      >
      > Note: `DiskStorage` does NOT override `readRecord()` -- it inherits from `AbstractStorage`.
      >
      > Update callers that access `RawBuffer`-specific methods:
      > - `AbstractStorage.doReadRecord()` (line 4082): log statement uses `buff.buffer().length`
      >   → use `buff.toRawBuffer().buffer().length` or conditional logging
      > - `CollectionBasedStorageConfiguration.java` (lines 1163, 1321, 1413, 1853): calls
      >   `collection.readRecord()` and accesses `.buffer()` → call `.toRawBuffer()` first
      >   (configuration reads always produce `RawBuffer` in practice, but API contract requires
      >   handling `RecordData`)
      > - `DatabaseCompare.java` (lines 722, 724): calls `storage.readRecord()` and accesses
      >   `.recordType()`, `.buffer()` → `.recordType()` is on the interface; `.buffer()` needs
      >   `.toRawBuffer()` for byte comparison
      >
      > **Files**: `Storage.java`, `StorageCollection.java`, `AbstractStorage.java`,
      > `CollectionBasedStorageConfiguration.java`, `DatabaseCompare.java`
      > **Tests**: existing tests (behavior-preserving -- all paths still produce valid data)

- [ ] Area 2: EntityImpl integration -- deferred copy with thread-local buffer
  > Wire `PageRecordRef` into `EntityImpl`'s deserialization path. On first property access,
  > validate the optimistic stamp and copy record bytes into a thread-local reusable buffer
  > (Stage 1). If stamp is invalid, re-load from cache (fallback). Includes allocation safety
  > guards for speculative reads.
  >
  > **What**: Add `pageRecordRef` field to `RecordAbstract`. Modify
  > `DatabaseSessionEmbedded.executeReadRecord()` to dispatch on `RecordData` type. Add
  > `deserializeFromPageFrame()` to `EntityImpl` with thread-local buffer fast path. Add
  > `SpeculativeReadException` and `BytesContainer.maxBound` for allocation safety.
  >
  > **How**: `EntityImpl` stores `PageRecordRef` instead of `byte[] source`. At
  > deserialization time: `validate(stamp)` → copy to thread-local buffer → `validate(stamp)`
  > again → deserialize from buffer. On failure: re-load page from cache, copy bytes, deserialize
  > (identical to today's behavior).
  >
  > ```mermaid
  > graph TD
  >     DP[deserializeProperties] -->|pageRecordRef != null| DPF[deserializeFromPageFrame]
  >     DP -->|source != null| EXISTING[existing fromStream path]
  >     DPF -->|validate stamp| VALID{stamp valid?}
  >     VALID -->|yes| COPY[copy to thread-local buffer]
  >     COPY --> VALIDATE2{validate again?}
  >     VALIDATE2 -->|yes| DESER[deserialize from buffer]
  >     VALIDATE2 -->|no| FALLBACK[re-load from cache + copy]
  >     VALID -->|no| FALLBACK
  >     FALLBACK --> DESER2[deserialize from byte array]
  > ```
  >
  > - **deserializeProperties** -- modified: checks `pageRecordRef` before `source`
  > - **deserializeFromPageFrame** -- new: thread-local buffer fast path
  > - **ThreadLocalBuffer** -- new: manages thread-local reusable byte array
  > - **reloadAndCopyFromCache** -- new: fallback using `readCache.loadForRead()`
  >
  > **Constraints**:
  > - Stamp validation MUST precede any buffer access (StampedLock acquire fence).
  > - `SpeculativeReadException` from allocation guards is caught by try-catch in
  >   `deserializeFromPageFrame()`, triggering fallback.
  > - Thread-local buffer sized to 2KB (common record size). Records larger than buffer
  >   fall back to heap allocation (still better than today's per-read allocation).
  > - `pageRecordRef` must be nulled in `unload()` and `fromStream()` to prevent stale refs.
  >
  > **Interactions**: Depends on Area 1 (PageRecordRef, RecordData). Area 3 depends on
  > BytesContainer changes made here (maxBound field).
  - [ ] Sub-area 2.1: RecordAbstract, EntityImpl, and allocation safety
    - [ ] Leaf: RecordAbstract -- add pageRecordRef field, fillFromPageRef(), lifecycle cleanup
      > Add `@Nullable protected PageRecordRef pageRecordRef` field to `RecordAbstract`.
      > Add `fillFromPageRef(PageRecordRef ref)` method: stores ref, sets
      > `recordVersion = ref.recordVersion()`, sets `status = STATUS.LOADED`, sets
      > `source = null`, `size = ref.length()`. In `unload()`: set `pageRecordRef = null`.
      > In `fromStream(byte[])`: set `pageRecordRef = null`. In `toStream()`: if
      > `pageRecordRef != null`, trigger `reloadAndCopyFromCache()` or throw (source is
      > needed for serialization).
      >
      > **Files**: `RecordAbstract.java`
      > **Tests**: `RecordAbstractTest` -- fillFromPageRef sets fields correctly, unload
      > clears ref, fromStream clears ref
    - [ ] Leaf: EntityImpl -- add deserializeFromPageFrame() with thread-local buffer
      > Create `ThreadLocalBuffer` utility class: `ThreadLocal<byte[]>` with `acquire(int size)`
      > (returns buffer if >= size, else allocates new) and `release()`. Default size 2KB.
      >
      > Add `private void deserializeFromPageFrame(String[] propertyNames)` to `EntityImpl`:
      > 1. `var ref = this.pageRecordRef;`
      > 2. `if (ref.isValid())` → try block:
      >    - `byte[] localBuf = ThreadLocalBuffer.acquire(ref.length());`
      >    - `ref.pageFrame().getBuffer().get(ref.offsetInPage(), localBuf, 0, ref.length());`
      >    - `if (ref.isValid())` → deserialize from `localBuf` via `recordSerializer.fromStream()`
      >    - Set `this.pageRecordRef = null`, return
      > 3. On `isValid()` failure or exception → call `reloadAndCopyFromCache(ref)`,
      >    set `source` and `size`, set `pageRecordRef = null`, deserialize from `source`.
      >
      > Modify `deserializeProperties()`: after the `source == null` check, add
      > `if (source == null && pageRecordRef == null) return true;` and after status =
      > UNMARSHALLING, add `if (pageRecordRef != null) { deserializeFromPageFrame(...); }`
      > before the existing `recordSerializer.fromStream()` call.
      >
      > Add `private byte[] reloadAndCopyFromCache(PageRecordRef ref)`: loads page via
      > `session.getStorage().readRecord()` using ref's fileId/pageIndex coordinates,
      > returns the byte array.
      >
      > **Files**: new `ThreadLocalBuffer.java`, `EntityImpl.java`
      > **Tests**: `EntityImplZeroCopyTest` -- valid stamp → thread-local buffer path,
      > invalid stamp → fallback path, large record → heap allocation fallback
    - [ ] Leaf: BytesContainer -- add maxBound field and SpeculativeReadException
      > Create `SpeculativeReadException` extending `RuntimeException` (unchecked). Singleton
      > instance with no stack trace (performance) plus `newWithStackTrace()` factory for debug
      > mode.
      >
      > Add `public final int maxBound` field to `BytesContainer`. Existing constructor
      > `BytesContainer(byte[])` sets `maxBound = Integer.MAX_VALUE`. New constructor
      > `BytesContainer(byte[], int maxBound)` for speculative reads (bounded by record length).
      >
      > In both `alloc(int toAlloc)` and `allocExact(int toAlloc)`: add guard
      > `if (toAlloc < 0 || toAlloc > maxBound) throw SpeculativeReadException.instance()`.
      >
      > **Files**: new `SpeculativeReadException.java`, `BytesContainer.java`
      > **Tests**: `BytesContainerMaxBoundTest` -- normal alloc passes, negative size throws,
      > exceeds-maxBound throws, Integer.MAX_VALUE maxBound allows all valid allocs
    - [ ] Leaf: RecordSerializerBinaryV1 -- add allocation guards for speculative reads
      > In `deserializeValue()` and `deserialize()`, guard collection/string size allocations
      > against `BytesContainer.maxBound` when it is set:
      > - Collection sizes: `int collectionSize = VarIntSerializer.readAsInteger(bytes);
      >   if (collectionSize < 0 || collectionSize > bytes.maxBound) throw
      >   SpeculativeReadException.instance();`
      > - String lengths: same pattern before `new byte[strLen]`
      > - Embedded record sizes: same pattern
      >
      > The `maxBound` is `Integer.MAX_VALUE` on normal paths (guards are no-ops). On
      > speculative paths, `maxBound = ref.length()` catches garbage values.
      >
      > **Files**: `RecordSerializerBinaryV1.java`
      > **Tests**: `RecordSerializerBinaryV1SpeculativeTest` -- garbage collection size
      > with bounded BytesContainer → SpeculativeReadException
  - [ ] Sub-area 2.2: DatabaseSessionEmbedded dispatch
    - [ ] Leaf: DatabaseSessionEmbedded -- dispatch on RecordData type for record loading
      > In `executeReadRecord()`, change the `recordBuffer` handling (lines ~1185-1221):
      > ```java
      > final RecordData recordData;
      > if (prefetchedBuffer != null) {
      >   recordData = prefetchedBuffer;
      > } else {
      >   recordData = storage.readRecord(rid, tx.getAtomicOperation());
      > }
      > // ... create record instance using recordData.recordType() ...
      > switch (recordData) {
      >   case PageRecordRef ref -> record.fillFromPageRef(ref);
      >   case RawBuffer buf -> {
      >     record.fill(buf.version(), buf.buffer(), false);
      >     record.fromStream(buf.buffer());
      >   }
      > }
      > ```
      > Handle the `prefetchedBuffer` parameter (always `RawBuffer` or null) by keeping its
      > type as `RawBuffer` in the method signature but widening the local variable.
      >
      > **Files**: `DatabaseSessionEmbedded.java`
      > **Tests**: existing tests (behavior-preserving for RawBuffer path; PageRecordRef path
      > tested via EntityImplZeroCopyTest)

- [ ] Area 3: ByteBuffer-aware serializer (full zero-copy)
  > Eliminate the intermediate `byte[]` copy entirely by making the serializer read directly
  > from the page's `ByteBuffer`. Introduces a sealed `BytesSource` interface that abstracts
  > over `byte[]` (heap) and `ByteBuffer` (direct memory).
  >
  > **What**: Create `BytesSource` sealed interface with `HeapBytesSource` and
  > `DirectBytesSource`. Refactor `BytesContainer` to use `BytesSource` instead of `byte[]`.
  > Update `RecordSerializerBinaryV1` and `VarIntSerializer` to read through `BytesSource`.
  >
  > **How**: `BytesSource` provides `get(int offset)`, `getInt(int offset)`,
  > `getLong(int offset)`, `get(int offset, byte[] dst, int dstOff, int len)`. `HeapBytesSource`
  > wraps `byte[]` with direct array access. `DirectBytesSource` wraps `ByteBuffer` with
  > absolute-position access. The serializer code changes from `bytes.bytes[bytes.offset]` to
  > `bytes.source.get(bytes.offset)`.
  >
  > **Constraints**:
  > - Sealed interface gives JIT closed-world guarantee for bimorphic inline caching.
  > - All `ByteBuffer` access must use absolute positioning (never relative get/put) since
  >   the buffer is shared among concurrent optimistic readers.
  > - Existing `byte[]`-based callers use `HeapBytesSource` transparently.
  > - Profile to confirm near-zero dispatch overhead before merging.
  >
  > **Interactions**: Independent of Areas 1-2 (can be developed in parallel). Consumed by
  > Area 4 (true zero-copy EntityImpl). The `maxBound` field added in Area 2 must be preserved.
  - [ ] Sub-area 3.1: BytesSource abstraction
    - [ ] Leaf: Create BytesSource sealed interface with HeapBytesSource and DirectBytesSource
      > Create `BytesSource.java`: `public sealed interface BytesSource permits
      > HeapBytesSource, DirectBytesSource` with methods: `byte get(int offset)`,
      > `void get(int offset, byte[] dst, int dstOff, int len)`, `int getInt(int offset)`,
      > `long getLong(int offset)`, `short getShort(int offset)`, `float getFloat(int offset)`,
      > `double getDouble(int offset)`.
      >
      > Create `HeapBytesSource.java`: wraps `byte[]`, implements all methods via direct array
      > access and manual byte manipulation (or `ByteBuffer.wrap()` for multi-byte reads).
      >
      > Create `DirectBytesSource.java`: wraps `ByteBuffer`, implements all methods via
      > absolute-position `ByteBuffer` access (`buffer.get(offset)`, `buffer.getInt(offset)`,
      > etc.).
      >
      > **Files**: new `BytesSource.java`, new `HeapBytesSource.java`,
      > new `DirectBytesSource.java`
      > **Tests**: `BytesSourceTest` -- both implementations read same data correctly,
      > boundary conditions, endianness consistency
    - [ ] Leaf: BytesContainer -- replace byte[] with BytesSource
      > Replace `public byte[] bytes` with `public BytesSource source`. Update constructors:
      > - `BytesContainer(byte[] iSource)` → `this.source = new HeapBytesSource(iSource)`
      > - `BytesContainer(byte[] iSource, int maxBound)` → same with maxBound
      > - `BytesContainer()` → creates default `HeapBytesSource(new byte[64])`
      > - New: `BytesContainer(ByteBuffer buffer, int baseOffset, int maxBound)` →
      >   `this.source = new DirectBytesSource(buffer)`, `this.offset = baseOffset`
      >
      > Update `alloc()`: grow logic needs refactoring since `DirectBytesSource` cannot grow.
      > For direct sources, `alloc()` should only advance offset (no array resize). Add
      > `isGrowable()` to `BytesSource` (true for heap, false for direct). Guard resize with
      > `if (source.isGrowable())`.
      >
      > Update `copy()`, `skip()`, and `fitBytes()` accordingly. `copy()` currently
      > creates `new BytesContainer(bytes, offset)` -- needs a `BytesSource`-accepting
      > constructor variant. `fitBytes()` only makes sense for heap sources -- for direct
      > sources, it should copy to a new byte array.
      >
      > **Files**: `BytesContainer.java`
      > **Tests**: existing tests + new tests for `DirectBytesSource` path
  - [ ] Sub-area 3.2: Serializer migration to BytesSource
    - [ ] Leaf: VarIntSerializer -- add BytesSource overloads
      > Add overloads for: `readUnsignedVarLong(BytesContainer)`,
      > `readSignedVarLong(BytesContainer)`, `readAsInteger(BytesContainer)`,
      > `readAsLong(BytesContainer)`.
      >
      > Current code accesses `bytes.bytes[bytes.offset++]` -- change to
      > `bytes.source.get(bytes.offset++)`. Since BytesContainer already uses the source
      > field after Leaf 3.1.2, this change makes existing methods work with both heap and
      > direct sources transparently.
      >
      > **Files**: `VarIntSerializer.java`
      > **Tests**: existing tests (verify both HeapBytesSource and DirectBytesSource produce
      > identical results)
    - [ ] Leaf: RecordSerializerBinaryV1 -- replace array access with BytesSource calls
      > Systematically replace all direct `bytes.bytes[offset]` access with
      > `bytes.source.get(offset)` throughout: `deserialize()`, `deserializePartial()`,
      > `deserializeField()`, `deserializeValue()`, and any helper methods.
      >
      > For bulk reads (e.g., string bytes, binary data), replace
      > `System.arraycopy(bytes.bytes, bytes.offset, dst, 0, len)` with
      > `bytes.source.get(bytes.offset, dst, 0, len)`.
      >
      > For multi-byte native reads (int, long, float, double), replace manual byte
      > assembly with `bytes.source.getInt(offset)`, etc.
      >
      > **Files**: `RecordSerializerBinaryV1.java`
      > **Tests**: existing serialization round-trip tests (must pass identically with both
      > HeapBytesSource and DirectBytesSource)
    - [ ] Leaf: Add RecordSerializer.fromStream() ByteBuffer overload
      > Add `fromStream(DatabaseSessionEmbedded session, ByteBuffer buffer, int offset,
      > int length, EntityImpl entity, String[] propertyNames)` to `RecordSerializer` interface.
      > Default implementation wraps in `DirectBytesSource` and delegates to existing
      > `fromStream()`.
      >
      > Implement in `RecordSerializerBinaryV1`: creates `BytesContainer(buffer, offset,
      > length)` and delegates to `deserialize()` / `deserializePartial()`.
      >
      > **Files**: `RecordSerializer.java`, `RecordSerializerBinaryV1.java`,
      > `RecordSerializerBinary.java`
      > **Tests**: round-trip test with ByteBuffer-backed deserialization

- [ ] Area 4: True zero-copy EntityImpl deserialization
  > Replace the thread-local buffer copy in `deserializeFromPageFrame()` with direct
  > `ByteBuffer` deserialization using the `ByteBuffer`-aware serializer from Area 3.
  >
  > **What**: On the fast path, `EntityImpl` deserializes directly from `PageFrame.getBuffer()`
  > without any `byte[]` copy. The thread-local buffer path (Area 2) becomes the intermediate
  > fallback (stamp invalid after first validate but before deserialization completes).
  >
  > **How**: In `deserializeFromPageFrame()`, replace the thread-local buffer copy + validate
  > cycle with:
  > ```
  > validate(stamp) → recordSerializer.fromStream(session, pageFrame.getBuffer(),
  >     ref.offsetInPage(), ref.length(), this, propertyNames) → validate(stamp) → done
  > ```
  > No `byte[]` allocated anywhere on the fast path.
  >
  > **Constraints**:
  > - Requires Area 3 (ByteBuffer-aware serializer) to be complete.
  > - `deserializeFromPageFrame()` must still catch exceptions from speculative reads (garbage
  >   data in reused frame) and fall back to reload+copy.
  > - The `BytesContainer` created for direct deserialization uses `maxBound = ref.length()`
  >   for allocation safety.
  >
  > **Interactions**: Depends on Areas 2 and 3. This is the final area.
  - [ ] Leaf: EntityImpl -- true zero-copy deserialization from PageFrame ByteBuffer
    > Modify `deserializeFromPageFrame()` fast path:
    > ```java
    > if (ref.isValid()) {
    >   try {
    >     ByteBuffer buf = ref.pageFrame().getBuffer();
    >     recordSerializer.fromStream(session, buf, ref.offsetInPage(),
    >         ref.length(), this, propertyNames);
    >     if (ref.isValid()) {
    >       this.pageRecordRef = null;
    >       return;
    >     }
    >   } catch (Exception e) {
    >     // Garbage from reused frame — fall through to reload
    >   }
    > }
    > // Fallback: reload from cache + copy (existing code from Area 2)
    > ```
    > The thread-local buffer code from Area 2 can be removed or kept as an additional
    > fallback layer (validate before direct deser, thread-local copy on first failure,
    > full reload on second failure). Recommend removing for simplicity -- single attempt
    > direct, then full reload.
    >
    > **Files**: `EntityImpl.java`
    > **Tests**: `EntityImplTrueZeroCopyTest` -- valid stamp → direct ByteBuffer
    > deserialization (no byte[] allocation), stamp invalidated during deser → fallback,
    > garbage data → fallback

## Phase Summary and Dependencies

```
Area 1: Record coordinate capture (PageRecordRef, RecordData, CollectionPage offset)
  ↓
Area 2: EntityImpl deferred copy (thread-local buffer, allocation safety)
  ↓                                  (Stage 1: no per-record allocation)
Area 4: True zero-copy EntityImpl deserialization
  ↑                                  (Stage 2: no allocation AND no copy)
Area 3: ByteBuffer-aware serializer (sealed BytesSource)
```

- **Area 1** is the foundation -- standalone PR
- **Area 2** delivers the first measurable improvement (Stage 1) -- standalone PR
- **Area 3** can be developed in parallel with Areas 1-2 -- standalone PR
- **Area 4** is the final integration -- depends on Areas 2 and 3

## Files Changed (Complete Summary)

### Area 1: Record Coordinate Capture
- New: `core/.../internal/core/storage/RecordData.java`
- New: `core/.../internal/core/storage/PageRecordRef.java`
- `RawBuffer.java` -- add `implements RecordData`
- `CollectionPage.java` -- add `getRecordDataOffset()`, `getNextPagePointer()`
- `PaginatedCollectionV2.java` -- `internalReadRecord()`, `doReadRecord()`, `readRecord()`
- `Storage.java` -- `readRecord()` return type
- `StorageCollection.java` -- `readRecord()` return type
- `AbstractStorage.java` -- `readRecord()`, `readRecordInternal()`, `doReadRecord()` return types
- `CollectionBasedStorageConfiguration.java` -- update `collection.readRecord()` callers
- `DatabaseCompare.java` -- update `storage.readRecord()` callers

### Area 2: EntityImpl Deferred Copy
- `RecordAbstract.java` -- add `pageRecordRef` field, `fillFromPageRef()`, lifecycle cleanup
- New: `ThreadLocalBuffer.java`
- `EntityImpl.java` -- `deserializeFromPageFrame()`, modify `deserializeProperties()`
- New: `SpeculativeReadException.java`
- `BytesContainer.java` -- add `maxBound` field, guarded `alloc()` and `allocExact()`
- `RecordSerializerBinaryV1.java` -- allocation guards
- `DatabaseSessionEmbedded.java` -- `RecordData` dispatch

### Area 3: ByteBuffer-Aware Serializer
- New: `BytesSource.java`, `HeapBytesSource.java`, `DirectBytesSource.java`
- `BytesContainer.java` -- `BytesSource` instead of `byte[]`
- `VarIntSerializer.java` -- `BytesSource` access
- `RecordSerializerBinaryV1.java` -- `BytesSource` access throughout
- `RecordSerializer.java` -- `ByteBuffer` overload
- `RecordSerializerBinary.java` -- delegate

### Area 4: True Zero-Copy EntityImpl
- `EntityImpl.java` -- direct `ByteBuffer` deserialization path
