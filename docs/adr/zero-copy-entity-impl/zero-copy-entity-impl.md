# Zero-Copy EntityImpl: Implementation Plan

## Problem Statement

`EntityImpl` is backed by a `byte[]` (`RecordAbstract.source`) that is **copied** from disk
cache pages via `DurablePage.getBinaryValue()` → `ByteBuffer.get(pageOffset, result)`. For
read-heavy workloads this incurs:

- **Allocation overhead**: one `byte[]` per record load
- **Copy overhead**: `System.arraycopy`-equivalent from direct memory to heap
- **GC pressure**: short-lived `byte[]` arrays in young generation

The goal is to let `EntityImpl` read directly from the page buffer in the disk cache
(**zero-copy**), falling back to today's copy behavior only when necessary.

## Design Principles

1. **No cache pinning** — EntityImpl must NOT hold cache entries acquired or prevent eviction.
   Pinning causes memory barriers and memory pressure in long-running transactions.
2. **No use-after-free** — speculative reads must never touch unmapped memory.
3. **Graceful degradation** — fallback to current copy behavior, never worse than today.

## Approach: PageFrame Pool + Optimistic Read + Deferred Page Access

Inspired by **LeanStore** (TU Munich) optimistic latching pattern.

### Core Idea

Introduce a `PageFrame` abstraction that pairs a direct memory `Pointer` with a `StampedLock`.
`PageFrame` objects are **pooled** (replacing `ByteBufferPool` with `PageFramePool`). Because
frames are pooled and never deallocated during normal operation, EntityImpl can safely hold a
reference to a `PageFrame` without pinning any cache entry. The `StampedLock` stamp detects
both page modifications and eviction/reuse of the frame.

**EntityImpl stores only coordinates** — `(PageFrame ref, stamp, offsetInPage, recordLength,
recordVersion)` — no cache entry reference, no ref count manipulation. At deserialization
time (first field access), it validates the stamp and reads directly from the frame's buffer.

### Key Invariants

1. **PageFrame pool never deallocates frames during normal operation** — frames are recycled,
   not freed. Memory is always mapped. Speculative reads may hit stale or reused data, but
   never unmapped memory. **This is the safety guarantee against segfaults.**

2. **Eviction acquires the exclusive lock on PageFrame** before returning it to the pool. This
   bumps the StampedLock state, invalidating all outstanding optimistic stamps. Any EntityImpl
   holding an old stamp will detect the invalidation on `validate()`.

3. **Reuse acquires the exclusive lock on PageFrame** when taking it from the pool for a new
   page. This further bumps the stamp, providing a second invalidation barrier.

4. **Page defragmentation** (`CollectionPage.doDefragmentation()`, line 683) happens only
   during write operations (record append) under the exclusive page lock. StampedLock's
   optimistic read validation detects this.

5. **No cache entry lifecycle dependency** — EntityImpl does not hold `CacheEntry` references.
   The cache is free to evict pages at any time. EntityImpl's `PageFrame` reference keeps
   the Java object alive (preventing GC) but does not prevent cache eviction.

6. **Multi-chunk records** (spanning multiple pages) always fall back to the current copy
   behavior. Zero-copy applies only to single-page records.

## Architecture Overview

```
                          PageFramePool
                    ┌──────────────────────┐
                    │ ConcurrentLinkedQueue │
                    │   [PageFrame]         │─── recycled on eviction
                    └──────────────────────┘
                              │
                              ▼ acquire / release
                    ┌──────────────────────┐
                    │     PageFrame         │
                    │  ┌────────────────┐   │
                    │  │ Pointer        │   │ ← direct memory (native address + ByteBuffer)
                    │  │ StampedLock    │   │ ← optimistic read / exclusive write
                    │  └────────────────┘   │
                    └──────────────────────┘
                         ▲            ▲
                         │            │
                  CachePointer     EntityImpl
                  (active page)    (deferred read)
                         │
                    ┌─────────┐
                    │CacheEntry│ ← evictable, no pinning
                    └─────────┘

EntityImpl holds:
  - PageFrame ref  ← keeps Java object alive, does NOT pin cache entry
  - stamp          ← from StampedLock.tryOptimisticRead()
  - offsetInPage   ← byte offset of record data within page buffer
  - recordLength   ← record data length
  - recordVersion  ← for MVCC version validation

Fast path (zero-copy, at deserialization time):
  validate(stamp) → read fields from PageFrame's buffer → validate(stamp) → done

Fallback path (stamp invalid):
  re-load page from cache → copy bytes → deserialize from byte[]
```

### Eviction Flow (No Pinning)

```
1. WTinyLFUPolicy selects victim CacheEntry
2. victim.freeze() → prevents new acquireEntry()
3. data.remove(victim) → removes from ConcurrentHashMap
4. victim.makeDead()
5. *** NEW: pageFrame.acquireExclusiveLock() → invalidates all outstanding stamps ***
6. *** NEW: pageFrame.releaseExclusiveLock() ***
7. pointer.decrementReadersReferrer() → when refcount hits 0:
   - PageFrame returned to PageFramePool (not deallocated)
8. victim.clearCachePointer()

EntityImpl holding old stamp → validate() returns false → fallback to copy
```

### Read Flow (Zero-Copy Path)

```
internalReadRecord():
  1. loadPageForRead() → acquires CacheEntry (ref count++)
  2. stamp = pageFrame.tryOptimisticRead()
  3. Read metadata: recordVersion, deleted flag, size, next-page pointer
  4. If single-page AND stamp valid:
     - Record coordinates: (pageFrame, stamp, offset, length, version)
  5. releasePageFromRead() → releases CacheEntry (ref count--)
     *** Cache is free to evict this page immediately ***
  6. Return coordinates (no byte[] copy)

deserializeProperties() (on first field access):
  1. validate(stamp) → check if page is still intact
  2. If valid:
     - Read fields directly from pageFrame.getBuffer()
     - validate(stamp) again after reading
     - If still valid: done (true zero-copy)
     - If invalid: fallback
  3. If invalid (page was evicted/modified/reused):
     - Re-load page from cache: readCache.loadForRead(fileId, pageIndex)
     - Copy bytes under shared lock (current behavior)
     - Release page
     - Deserialize from byte[]
```

## Implementation Phases

---

### Phase 0: PageFrame Abstraction + PageFramePool

**Goal:** Introduce `PageFrame` wrapping `Pointer` + `StampedLock`, replace `ByteBufferPool`
with `PageFramePool`.

**New class: `PageFrame`**

```java
/// A pooled page-sized direct memory frame with an associated StampedLock.
/// Frames are recycled (never deallocated during normal operation), guaranteeing
/// that any reference to a PageFrame always points to valid mapped memory.
public final class PageFrame {

  private final Pointer pointer;        // direct memory (native address + size)
  private final StampedLock stampedLock; // protects the frame's content

  PageFrame(Pointer pointer) {
    this.pointer = pointer;
    this.stampedLock = new StampedLock();
  }

  // --- Lock API ---

  public long tryOptimisticRead() {
    return stampedLock.tryOptimisticRead();
  }

  public boolean validate(long stamp) {
    return stampedLock.validate(stamp);
  }

  public long acquireExclusiveLock() {
    return stampedLock.writeLock();
  }

  public void releaseExclusiveLock(long stamp) {
    stampedLock.unlockWrite(stamp);
  }

  public long acquireSharedLock() {
    return stampedLock.readLock();
  }

  public void releaseSharedLock(long stamp) {
    stampedLock.unlockRead(stamp);
  }

  public boolean tryAcquireSharedLock() {
    return stampedLock.tryReadLock() != 0;
    // Note: need to track stamp for unlock — refine API as needed
  }

  // --- Memory access ---

  public ByteBuffer getBuffer() {
    return pointer.getNativeByteBuffer();
  }

  public Pointer getPointer() {
    return pointer;
  }

  public void clear() {
    pointer.clear();
  }
}
```

**New class: `PageFramePool`** (replaces `ByteBufferPool`)

```java
/// Pool of PageFrame objects. Frames are recycled, not deallocated.
/// When pool capacity is exceeded, excess frames may be deallocated,
/// but only after acquiring the exclusive lock (invalidating stamps).
public final class PageFramePool {

  private final ConcurrentLinkedQueue<PageFrame> pool;
  private final AtomicInteger poolSize;
  private final int maxPoolSize;
  private final int pageSize;
  private final DirectMemoryAllocator allocator;

  public PageFrame acquire(boolean clear) {
    PageFrame frame = pool.poll();
    if (frame != null) {
      poolSize.decrementAndGet();
      // Acquire+release exclusive lock to invalidate any stale stamps
      long stamp = frame.acquireExclusiveLock();
      if (clear) frame.clear();
      frame.releaseExclusiveLock(stamp);
      return frame;
    }
    // Allocate new frame
    Pointer ptr = allocator.allocate(pageSize, clear, Intention.ADD_NEW_PAGE);
    return new PageFrame(ptr);
  }

  public void release(PageFrame frame) {
    // Acquire+release exclusive lock BEFORE pooling — invalidates all outstanding stamps
    long stamp = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(stamp);

    if (poolSize.incrementAndGet() > maxPoolSize) {
      poolSize.decrementAndGet();
      // Deallocate — frame is no longer in pool, and stamps are already invalidated.
      // Any EntityImpl holding this frame: validate() fails → fallback to copy.
      // The PageFrame Java object stays alive (EntityImpl holds reference),
      // but the native memory is freed. Speculative read before validate()
      // could touch freed memory — but the exclusive lock above ensures
      // validate() fails BEFORE the read (StampedLock acquire fence).
      allocator.deallocate(frame.getPointer());
    } else {
      pool.add(frame);
    }
  }
}
```

**Deallocate safety note:** When pool capacity is exceeded and a frame IS deallocated:
- The exclusive lock was acquired before deallocation → all stamps are invalidated
- EntityImpl calls `validate(stamp)` BEFORE reading → returns false → fallback path
- The fallback path re-loads the page from cache (fresh frame) → no freed memory access
- The StampedLock itself lives on the Java heap (in the PageFrame object), so `validate()`
  is always safe to call regardless of native memory state
- **Critical ordering**: EntityImpl MUST call `validate()` before ANY read from the buffer.
  The StampedLock's acquire fence in `validate()` ensures the stamp check is visible before
  any subsequent memory access.

**Migration from ByteBufferPool:**
- `ByteBufferPool` currently pools `Pointer` objects
- Replace with `PageFramePool` that pools `PageFrame` objects
- All existing callers of `ByteBufferPool.acquireDirect()` get a `PageFrame` instead
- `ByteBufferPool` can be kept as a thin wrapper delegating to `PageFramePool` during
  migration, or replaced entirely

**Files to modify:**
- New: `PageFrame.java` in `internal.common.directmemory`
- New: `PageFramePool.java` in `internal.common.directmemory`
- `ByteBufferPool.java` — delegate to PageFramePool or replace
- `Pointer.java` — no changes (stays as pure memory address wrapper)

**Files to update (callers of ByteBufferPool):**
- `CachePointer.java` — hold `PageFrame` instead of `Pointer` + `ByteBufferPool`
- `WOWCache.java` — page loading/creation
- `LockFreeReadCache.java` — `addNewPagePointerToTheCache()`
- `MemoryFile.java` — in-memory page creation
- `DirectMemoryAllocator.java` — if API changes

---

### Phase 1: CachePointer Refactoring — Delegate Lock to PageFrame

**Goal:** Remove `ReentrantReadWriteLock` and `version` field from `CachePointer`. Delegate
all locking to the underlying `PageFrame`.

**Changes to `CachePointer`:**

```java
public final class CachePointer {
  // REMOVED:
  // private final ReentrantReadWriteLock readWriteLock;
  // private long version;
  // private final Pointer pointer;
  // private final ByteBufferPool bufferPool;

  // NEW:
  private final PageFrame pageFrame;

  public CachePointer(PageFrame pageFrame, long fileId, int pageIndex) {
    this.pageFrame = pageFrame;
    // ...
  }

  // Lock delegation:
  public long acquireExclusiveLock() {
    return pageFrame.acquireExclusiveLock();
  }

  public void releaseExclusiveLock(long stamp) {
    pageFrame.releaseExclusiveLock(stamp);
  }

  public long acquireSharedLock() {
    return pageFrame.acquireSharedLock();
  }

  public void releaseSharedLock(long stamp) {
    pageFrame.releaseSharedLock(stamp);
  }

  public long tryOptimisticRead() {
    return pageFrame.tryOptimisticRead();
  }

  public boolean validate(long stamp) {
    return pageFrame.validate(stamp);
  }

  // Buffer access:
  public ByteBuffer getBuffer() {
    return pageFrame.getBuffer();
  }

  public PageFrame getPageFrame() {
    return pageFrame;
  }

  // Reference counting remains unchanged (manages cache lifecycle, not memory safety)
  // ...
}
```

**Signature change propagation:** `acquireExclusiveLock()` now returns `long` (stamp),
`releaseExclusiveLock(stamp)` takes a `long`. Same for shared lock. This propagates through:
- `CacheEntry` interface
- `CacheEntryImpl`
- `LockFreeReadCache.loadForWrite()` / `releaseFromWrite()`
- `DurableComponent.loadPageForWrite()` / `releasePageFromWrite()`
- `DurablePage` and all subclasses
- `AtomicOperation.loadPageForWrite()` / `releasePageFromWrite()`
- `DiskStorage` — all acquireSharedLock/releaseSharedLock call sites

**Eviction path update** (`WTinyLFUPolicy`):
Add exclusive lock acquisition before returning frame to pool:

```java
// In eviction (purgeEden / onRemove):
final var cachePointer = victim.getCachePointer();
// NEW: invalidate stamps before releasing to pool
long stamp = cachePointer.acquireExclusiveLock();
cachePointer.releaseExclusiveLock(stamp);
// Existing:
cachePointer.decrementReadersReferrer();
victim.clearCachePointer();
```

**Reentrancy audit:** StampedLock is not reentrant. Audit all code paths for nested lock
acquisition on the same CachePointer/PageFrame. The `SharedResourceAbstract` StampedLock
migration (already completed) used thread-owner tracking — determine if similar is needed.

**Files to modify:**
- `CachePointer.java` — replace lock + pointer with PageFrame delegation
- `CacheEntry.java` — interface signature change (lock methods return/take long stamp)
- `CacheEntryImpl.java` — delegation update
- `LockFreeReadCache.java` — load/release methods
- `WTinyLFUPolicy.java` — eviction stamp invalidation
- `DurableComponent.java` — loadPage/releasePage stamp threading
- `DurablePage.java` — lock stamp threading
- `AtomicOperation.java` / `AtomicOperationBinaryTracking.java` — page lock forwarding
- `DiskStorage.java` — all lock call sites
- All `DurablePage` subclasses (CollectionPage, index pages, etc.)

**Note:** This phase has a large blast radius. Consider as a standalone PR with
comprehensive test coverage before proceeding to zero-copy phases.

---

### Phase 2: Record Coordinate Capture + Deferred Read

**Goal:** Capture record coordinates during `internalReadRecord()` instead of copying bytes.
EntityImpl stores coordinates and defers the actual read to deserialization time.

**New class: `PageRecordRef`**

```java
/// Lightweight reference to a record's location on a page frame.
/// No cache entry pinning — the page may be evicted at any time.
/// The stamp detects eviction/modification; fallback is to re-load and copy.
public record PageRecordRef(
    PageFrame pageFrame,    // pooled frame — Java object stays alive
    long stamp,             // from tryOptimisticRead() at capture time
    int offsetInPage,       // byte offset of record content within page buffer
    int length,             // record content length (excluding metadata)
    long recordVersion,     // MVCC version
    byte recordType,        // record type byte
    long fileId,            // for re-loading on fallback
    int pageIndex           // for re-loading on fallback
) {

  public boolean isValid() {
    return pageFrame.validate(stamp);
  }
}
```

**Sealed interface for record read results:**

```java
public sealed interface RecordData permits RawBuffer, PageRecordRef {}
```

**Modify `PaginatedCollectionV2.internalReadRecord()`** (line 918):

```java
private RecordData internalReadRecord(...) {
  // Load page and acquire cache entry (ref count)
  try (final var cacheEntry = loadPageForRead(atomicOperation, fileId, pageIndex)) {
    final var cachePointer = cacheEntry.getCachePointer();
    final var pageFrame = cachePointer.getPageFrame();

    // Take optimistic stamp BEFORE reading metadata
    long stamp = pageFrame.tryOptimisticRead();

    final var localPage = new CollectionPage(cacheEntry);
    var recordVersion = localPage.getRecordVersion(recordPosition);
    // ... visibility check, deleted check ...

    var recordSize = localPage.getRecordSize(recordPosition);
    var nextPagePointer = localPage.getNextPagePointer(recordPosition);

    // Single-page record AND stamp still valid?
    if (nextPagePointer < 0 && pageFrame.validate(stamp)) {
      // Compute offset within page buffer
      int offsetInPage = localPage.getRecordDataOffset(recordPosition);

      // Return coordinates — NO byte[] copy, NO cache entry held
      // (cacheEntry released by try-with-resources)
      return new PageRecordRef(
          pageFrame, stamp, offsetInPage, recordSize,
          recordVersion, recordType, fileId, pageIndex);
    }

    // Multi-page or stamp invalid: fall back to current copy behavior
    // ... existing chunk reading loop ...
    return new RawBuffer(recordContent, recordVersion, recordType);
  }
  // ← CacheEntry released here. Cache can evict this page immediately.
}
```

**Note on `CollectionPage` methods:** We need to expose `getRecordDataOffset(recordPosition)`
which returns the absolute byte offset within the page buffer for a record's content.
Currently `getRecordBinaryValue()` computes this internally but doesn't expose the offset.
Add a new method or refactor to expose offset computation.

**Files to modify:**
- New: `PageRecordRef.java` in `internal.core.storage`
- New: `RecordData.java` (sealed interface)
- `RawBuffer.java` — implement `RecordData`
- `PaginatedCollectionV2.java` — `internalReadRecord()`, `doReadRecord()`
- `CollectionPage.java` — expose `getRecordDataOffset()`
- `AbstractStorage.java` — `readRecord()` return type to `RecordData`

---

### Phase 3: EntityImpl Integration — Deferred Deserialization from PageFrame

**Goal:** EntityImpl stores `PageRecordRef` and reads directly from PageFrame at
deserialization time, with fallback to copy on stamp failure.

**New fields on `RecordAbstract`:**

```java
// Existing:
protected byte[] source;
protected int size;

// New:
@Nullable
protected PageRecordRef pageRecordRef;  // non-null = zero-copy mode
```

**Modified record loading path** (`DatabaseSessionEmbedded.java`):

```java
RecordData data = storage.readRecord(rid, tx.getAtomicOperation());

if (data instanceof PageRecordRef ref) {
  record.fillFromPageRef(ref);    // stores ref, sets status=LOADED, source=null
} else if (data instanceof RawBuffer buf) {
  record.fill(buf.version(), buf.buffer(), false);  // existing path
}
```

**Modified `EntityImpl.deserializeProperties()`:**

```java
public boolean deserializeProperties(String... propertyNames) {
  if (status != STATUS.LOADED) return false;
  if (source == null && pageRecordRef == null) return true;  // already unmarshalled

  // ... existing field name parsing ...

  status = STATUS.UNMARSHALLING;
  try {
    if (pageRecordRef != null) {
      deserializeFromPageFrame(propertyNames);
    } else {
      recordSerializer.fromStream(session, source, this, propertyNames);
    }
  } finally {
    status = STATUS.LOADED;
  }
  // ...
}
```

**Phase 3a: Deferred copy with reusable buffer (intermediate step)**

Before the serializer supports ByteBuffer (Phase 4), use a thread-local buffer:

```java
private void deserializeFromPageFrame(String[] propertyNames) {
  var ref = this.pageRecordRef;

  // Fast path: stamp valid → copy to thread-local buffer, validate again
  if (ref.isValid()) {
    try {
      byte[] localBuf = ThreadLocalBuffer.acquire(ref.length());
      ref.pageFrame().getBuffer().get(ref.offsetInPage(), localBuf, 0, ref.length());

      if (ref.isValid()) {
        // Data is consistent — deserialize from local buffer
        recordSerializer.fromStream(session, localBuf, this, propertyNames);
        this.pageRecordRef = null;
        return;
      }
    } catch (Exception e) {
      // Garbage from reused frame — fall through to reload
    }
  }

  // Fallback: stamp invalid → re-load page from cache, copy bytes (current behavior)
  source = reloadAndCopyFromCache(ref);
  size = source.length;
  this.pageRecordRef = null;
  recordSerializer.fromStream(session, source, this, propertyNames);
}

private byte[] reloadAndCopyFromCache(PageRecordRef ref) {
  // Standard page load — acquires cache entry, copies bytes, releases
  // Uses readCache.loadForRead(ref.fileId(), ref.pageIndex())
  // Falls back to full readRecord() if page structure changed
  // ...
}
```

**Thread-local buffer:** Avoid per-read allocation by reusing a buffer per thread.
Size it to the most common record size (e.g., 2KB). Records larger than the buffer
fall back to heap allocation. This gives most of the allocation savings even before
the serializer supports ByteBuffer.

**Allocation safety during speculative reads:**

During an optimistic read from a reused/dirty frame, a "size" or "length" field decoded by
`VarIntSerializer.readAsInteger()` could return a garbage value like 2 billion. If the
deserializer naively does `new ArrayList<>(size)` or `new byte[len]`, that triggers OOM
**before** we reach the post-read `validate(stamp)` check.

This affects every allocation driven by deserialized sizes:
- Collection sizes: `new Object[size]`, `new ArrayList<>(size)`, `new HashMap<>(size)`
- String lengths: `new byte[strLen]` for UTF-8 decoding
- Embedded record sizes
- `BytesContainer.alloc(toAlloc)` / `allocExact(toAlloc)`

**Mitigation: bounds-check all size-driven allocations against the record length.**

For speculative reads, we know the record fits in a single page and its length is
`PageRecordRef.length()`. Any decoded size exceeding this bound is garbage.

Add a `maxBound` field to `BytesContainer`:

```java
public class BytesContainer {
  public byte[] bytes;
  public int offset;
  public final int maxBound;  // NEW: max valid allocation size

  // Normal path: no bound
  public BytesContainer(byte[] bytes) {
    this.bytes = bytes;
    this.maxBound = Integer.MAX_VALUE;
  }

  // Speculative path: bounded by record length
  public BytesContainer(byte[] bytes, int maxBound) {
    this.bytes = bytes;
    this.maxBound = maxBound;
  }

  public int alloc(final int toAlloc) {
    if (toAlloc < 0 || toAlloc > maxBound) {
      throw new SpeculativeReadException();  // caught by try-catch in caller
    }
    // ... existing logic
  }
}
```

Additionally, in `RecordSerializerBinaryV1.deserializeValue()`, wrap size reads:

```java
int collectionSize = VarIntSerializer.readAsInteger(bytes);
if (collectionSize < 0 || collectionSize > bytes.maxBound) {
  throw new SpeculativeReadException();
}
var result = new ArrayList<>(collectionSize);
```

`SpeculativeReadException` extends `RuntimeException` (unchecked) and is caught by the
try-catch in `deserializeFromPageFrame()`, triggering the fallback path.

**Key principle**: every allocation whose size comes from the byte stream must be guarded
when deserializing speculatively. The `maxBound` on `BytesContainer` provides a centralized
guard, but individual collection/string allocations in the serializer should also check,
as the serializer may compute sizes from multiple decoded values.

---

**Phase 3b: True zero-copy (after Phase 4)**

Once the serializer supports `ByteBuffer` (Phase 4), the fast path becomes:

```java
private void deserializeFromPageFrame(String[] propertyNames) {
  var ref = this.pageRecordRef;

  if (ref.isValid()) {
    try {
      ByteBuffer buf = ref.pageFrame().getBuffer();
      // Deserialize directly from ByteBuffer — NO copy at all
      recordSerializer.fromStream(session, buf, ref.offsetInPage(),
          ref.length(), this, propertyNames);

      if (ref.isValid()) {
        this.pageRecordRef = null;
        return;
      }
    } catch (Exception e) {
      // fall through
    }
  }

  // Fallback...
}
```

**Lifecycle cleanup:**
- `RecordAbstract.unload()`: set `pageRecordRef = null`
- `RecordAbstract.fromStream()`: set `pageRecordRef = null`
- No `Cleaner` needed — `PageFrame` is pooled, not pinned. Setting `pageRecordRef = null`
  just drops a Java reference. GC handles the rest.
- No cache entry leak risk — we never held the cache entry beyond `internalReadRecord()`.

**Files to modify:**
- `RecordAbstract.java` — add `pageRecordRef` field, `fillFromPageRef()`, cleanup in
  `unload()`/`fromStream()`
- `EntityImpl.java` — `deserializeFromPageFrame()`, modify `deserializeProperties()`
- `DatabaseSessionEmbedded.java` — record loading dispatch

---

### Phase 4: ByteBuffer-Aware Deserialization (Full Zero-Copy)

**Goal:** Eliminate the intermediate `byte[]` copy entirely by making the serializer read
directly from the page's `ByteBuffer`.

**Sealed interface for byte sources:**

```java
public sealed interface BytesSource permits HeapBytesSource, DirectBytesSource {
  byte get(int offset);
  void get(int offset, byte[] dst, int dstOff, int len);
  int getInt(int offset);
  long getLong(int offset);
  // ... etc
}

public final class HeapBytesSource implements BytesSource {
  private final byte[] bytes;
  // ... wraps byte[] with direct array access
}

public final class DirectBytesSource implements BytesSource {
  private final ByteBuffer buffer;
  // ... wraps ByteBuffer with absolute-position access
}
```

**Why sealed:** The `sealed` keyword gives the JIT a **closed-world guarantee** — exactly 2
implementations, can never be subclassed. This enables:
- **Bimorphic inline cache**: C2/Graal inlines BOTH implementations at each call site with
  a type check branch, eliminating virtual dispatch entirely.
- **Perfect branch prediction**: within a single `deserialize()` call, every `BytesSource`
  method hits the same implementation, so the branch predictor is 100% accurate.
- **No deoptimization traps**: sealed guarantees the optimization is stable.

Net result: sealed interface ≈ same runtime cost as manual `if (bytes != null)` branching,
but with cleaner code separation and type safety. HotSpot C2 exploits sealed hierarchies
since JDK 17+.

**Refactor `BytesContainer`:**

```java
public class BytesContainer {
  public BytesSource source;  // replaces byte[] bytes
  public int offset;

  public BytesContainer(byte[] bytes) {
    this.source = new HeapBytesSource(bytes);
  }

  public BytesContainer(ByteBuffer buffer, int baseOffset) {
    this.source = new DirectBytesSource(buffer);
    this.offset = baseOffset;
  }

  // alloc(), skip(), copy() — adapt to work with BytesSource
}
```

**Update serializer:**
- `RecordSerializerBinaryV1.java` — replace direct `bytes.bytes[offset]` access with
  `bytes.source.get(offset)` throughout `deserialize()`, `deserializePartial()`,
  `deserializeField()`, `deserializeValue()`
- `VarIntSerializer.java` — add `BytesSource` overloads for `readAsInteger()`,
  `readUnsignedVarLong()`
- Add `RecordSerializer.fromStream(session, ByteBuffer, offset, length, record, fields)`
  overload

**With Phase 4 complete, the full zero-copy path:**
```
validate(stamp) → deserialize directly from PageFrame's ByteBuffer → validate(stamp) → done
  (no byte[] allocated anywhere on the fast path)
```

**Files to modify:**
- New: `BytesSource.java`, `HeapBytesSource.java`, `DirectBytesSource.java`
- `BytesContainer.java` — use `BytesSource` instead of `byte[]`
- `RecordSerializerBinaryV1.java` — replace array access with BytesSource calls
- `VarIntSerializer.java` — add BytesSource overloads
- `RecordSerializer.java` — add ByteBuffer overload
- `RecordSerializerBinary.java` — delegate ByteBuffer overload

---

## Phase Summary and Dependencies

```
Phase 0: PageFrame + PageFramePool (replaces ByteBufferPool)
  ↓
Phase 1: CachePointer delegates lock to PageFrame, eviction invalidates stamps
  ↓
Phase 2: Record coordinate capture + deferred read (PageRecordRef)
  ↓
Phase 3a: EntityImpl integration — deferred copy with thread-local buffer
  ↓                                 (partial win: no per-record allocation)
Phase 3b: EntityImpl integration — true zero-copy via ByteBuffer deserialization
  ↑
Phase 4: ByteBuffer-aware serializer (sealed BytesSource)
```

- **Phase 0 + 1** are infrastructure — can be a standalone PR (or two)
- **Phase 2 + 3a** deliver the first measurable improvement
- **Phase 4 + 3b** deliver full zero-copy
- Phase 4 can be developed in parallel with Phase 2/3a

## Scope Exclusions

- **Multi-chunk records** (spanning multiple pages): always use current copy behavior.
  Optimization not worth the complexity for large records.
- **WAL changes overlay**: when `CacheEntry.getChanges() != null` (transactional writes in
  progress), fall back to current copy behavior. Zero-copy only applies to committed/clean
  pages read outside atomic operations.
- **Embedded entities**: entities stored as embedded values within other entities are
  deserialized inline — no separate page reference.
- **In-memory engine** (`EngineMemory`): pages are never evicted, so the stamp mechanism
  is not strictly needed. Can still benefit from zero-copy but has different trade-offs.
  Defer to a follow-up.

## Key Risks and Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| PageFrame deallocation on pool overflow | Freed native memory | Exclusive lock acquired before dealloc → stamps invalidated → validate() fails before read. **Critical**: validate() MUST precede any buffer access. |
| StampedLock not reentrant | Deadlock | Audit all call paths for nested lock acquisition. SharedResourceAbstract migration used thread-owner tracking — apply same pattern if needed. |
| Speculative read hits garbage (reused frame) | Exception in deserializer | Try-catch around optimistic path; exception → fallback to reload+copy. Rare: requires exact timing of eviction+reuse during deserialization window. |
| OOM from garbage size during speculative read | Allocating huge array from garbage "length" field | Bounds-check all size-driven allocations against `BytesContainer.maxBound` (set to record length for speculative reads). `SpeculativeReadException` caught by try-catch → fallback. |
| Stamp validation ordering | Could read freed memory if validate() happens after buffer access | StampedLock.validate() includes acquire fence. EntityImpl code must be structured: validate → branch → read. Compiler/JIT cannot reorder past the fence. |
| StampedLock signature change blast radius | Many files to modify | Phase 0+1 as standalone PR with comprehensive tests. |
| Branch overhead in sealed BytesSource | Deserialization regression | Sealed bimorphic dispatch is ~0 overhead. Profile to confirm; fall back to specialization (Option C) if needed. |

## Performance Expectations

- **Phase 3a (deferred copy + thread-local buffer)**: eliminates per-record `byte[]`
  allocation. Saves allocation + GC overhead. Copy still happens but into a reusable buffer.
  Expected: ~30-50% reduction in young-gen allocation for read-heavy workloads.

- **Phase 3b + 4 (full zero-copy)**: eliminates both allocation AND copy on the fast path.
  Expected: ~50-200ns savings per record read. For scan of 10K records × 500 bytes avg,
  that's ~5MB of avoided allocation + copy.

- **Fallback frequency**: depends on cache hit ratio and eviction rate. For warm caches
  (typical production), stamps should be valid >99% of the time. Cold cache or memory
  pressure → more fallbacks, but no worse than today.

- **GC pressure**: significantly reduced for read-heavy workloads (zero heap allocation
  on the fast path with full zero-copy).

## Files Changed (Complete Summary)

### Phase 0: PageFrame + Pool
- New: `core/.../internal/common/directmemory/PageFrame.java`
- New: `core/.../internal/common/directmemory/PageFramePool.java`
- `ByteBufferPool.java` — delegate to or replace with PageFramePool
- `Pointer.java` — no changes

### Phase 1: CachePointer + Lock Migration
- `CachePointer.java` — replace RRWL+Pointer with PageFrame delegation
- `CacheEntry.java` — lock method signatures (return/take long stamp)
- `CacheEntryImpl.java` — delegation
- `WTinyLFUPolicy.java` — exclusive lock on eviction
- `LockFreeReadCache.java` — load/release, page creation
- `WOWCache.java` — page loading/creation
- `DurableComponent.java` — stamp threading
- `DurablePage.java` — stamp threading
- `AtomicOperation.java` / `AtomicOperationBinaryTracking.java`
- `DiskStorage.java` — all lock call sites
- `MemoryFile.java` — in-memory page creation
- All DurablePage subclasses

### Phase 2: Record Coordinate Capture
- New: `PageRecordRef.java`
- New: `RecordData.java` (sealed interface)
- `RawBuffer.java` — implement RecordData
- `PaginatedCollectionV2.java` — internalReadRecord, doReadRecord
- `CollectionPage.java` — expose getRecordDataOffset()
- `AbstractStorage.java` — readRecord return type

### Phase 3: EntityImpl Integration
- `RecordAbstract.java` — pageRecordRef field, fillFromPageRef()
- `EntityImpl.java` — deserializeFromPageFrame(), lifecycle cleanup
- `DatabaseSessionEmbedded.java` — record loading dispatch
- New: `ThreadLocalBuffer.java` (for Phase 3a)

### Phase 4: ByteBuffer-Aware Serializer
- New: `BytesSource.java`, `HeapBytesSource.java`, `DirectBytesSource.java`
- `BytesContainer.java` — use BytesSource
- `RecordSerializerBinaryV1.java` — BytesSource access
- `VarIntSerializer.java` — BytesSource overloads
- `RecordSerializer.java` — ByteBuffer overload
- `RecordSerializerBinary.java` — delegate
