package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.CoreMetrics;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.MetricsRegistry;
import com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.BoundedBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.readbuffer.Buffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.writequeue.MPSCLinkedQueue;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;

/**
 * Disk cache based on ConcurrentHashMap and eviction policy which is asynchronously processed by
 * handling set of events logged in lock free event buffer. This feature first was introduced in
 * Caffeine framework <a href="https://github.com/ben-manes/caffeine">...</a> and in
 * ConcurrentLinkedHashMap library
 * <a href="https://github.com/ben-manes/concurrentlinkedhashmap">...</a>. The difference is that if
 * consumption of
 * memory in cache is bigger than 1% disk cache is switched from asynchronous processing of stream
 * of events to synchronous processing. But that is true only for threads which cause loading of
 * additional pages from write cache to disk cache. Window TinyLFU policy is used as cache eviction
 * policy because it prevents usage of ghost entries and as result considerably decrease usage of
 * heap memory.
 */
public final class LockFreeReadCache implements ReadCache {

  private static final int N_CPU = Runtime.getRuntime().availableProcessors();
  private static final int WRITE_BUFFER_MAX_BATCH = 128 * ceilingPowerOfTwo(N_CPU);

  /**
   * Batch size for accumulating read buffer entries before flushing to the striped buffer.
   * This amortizes the per-entry overhead of striped buffer probe lookup, volatile table
   * read, CAS contention, and drain-status check — all of which are paid once per batch
   * instead of once per cache hit.
   */
  private static final int READ_BATCH_SIZE = 16;

  private final ConcurrentLongIntHashMap<CacheEntry> data;
  private final Lock evictionLock = new ReentrantLock();

  private final WTinyLFUPolicy policy;

  private final Buffer readBuffer = new BoundedBuffer();
  private final MPSCLinkedQueue<CacheEntry> writeBuffer = new MPSCLinkedQueue<>();
  private final AtomicInteger cacheSize = new AtomicInteger();
  private final int maxCacheSize;

  /**
   * Status which indicates whether flush of buffers should be performed or may be delayed.
   */
  private final AtomicReference<DrainStatus> drainStatus = new AtomicReference<>(DrainStatus.IDLE);

  /**
   * Thread-local batch for accumulating cache entries before offering them to the
   * striped read buffer. Access is single-threaded (thread-local), so no synchronization
   * is needed on the batch or its index.
   */
  private final ThreadLocal<ReadBatch> readBatch =
      ThreadLocal.withInitial(() -> new ReadBatch(READ_BATCH_SIZE));

  private final int pageSize;

  private final Ratio cacheHitRatio;

  public LockFreeReadCache(
      final ByteBufferPool bufferPool,
      final long maxCacheSizeInBytes,
      final int pageSize) {
    evictionLock.lock();
    try {
      this.pageSize = pageSize;

      this.maxCacheSize = (int) (maxCacheSizeInBytes / pageSize);
      // Section count rounded up to power of two from N_CPU << 1 (matching
      // ConcurrentHashMap's concurrency level). ConcurrentLongIntHashMap requires
      // power-of-two section count for bit-mask section selection.
      this.data =
          new ConcurrentLongIntHashMap<>(this.maxCacheSize, ceilingPowerOfTwo(N_CPU << 1));
      policy = new WTinyLFUPolicy(data, new FrequencySketch(), cacheSize);
      policy.setMaxSize(this.maxCacheSize);
    } finally {
      evictionLock.unlock();
    }

    this.cacheHitRatio = resolveCacheHitRatio(
        YouTrackDBEnginesManager.instance().getMetricsRegistry());
  }

  /**
   * Resolves the cache-hit ratio metric from the given registry. Returns {@link Ratio#NOOP} when
   * the registry is {@code null}, which happens during early engine startup when the profiler has
   * not yet been initialized. Package-private for testability.
   */
  static Ratio resolveCacheHitRatio(@Nullable final MetricsRegistry registry) {
    return registry != null
        ? registry.globalMetric(CoreMetrics.CACHE_HIT_RATIO)
        : Ratio.NOOP;
  }

  @Override
  public long addFile(final String fileName, final WriteCache writeCache) throws IOException {
    return writeCache.addFile(fileName);
  }

  @Override
  public long addFile(final String fileName, long fileId, final WriteCache writeCache)
      throws IOException {
    assert fileId >= 0;
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    return writeCache.addFile(fileName, fileId);
  }

  @Override
  public long addFile(
      final String fileName, long fileId, final WriteCache writeCache, final boolean nonDurable)
      throws IOException {
    assert fileId >= 0;
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);
    return writeCache.addFile(fileName, fileId, nonDurable);
  }

  @Override
  public CacheEntry loadOrAddForWrite(
      final long fileId,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums,
      final LogSequenceNumber startLSN) {
    final var cacheEntry =
        doLoad(fileId, (int) pageIndex, writeCache, verifyChecksums, /*forWrite=*/ true);

    // Defensive guard: doLoad delegates to the total WriteCache#loadOrAdd primitive,
    // so the returned CacheEntry should never be null after the loadOrAdd collapse.
    // The null check is retained as a belt-and-braces measure against future
    // divergence in doLoad's miss-handling branches; if the contract ever weakens,
    // callers still see a clean null instead of an NPE on the lock acquisition below.
    assert cacheEntry != null
        : "doLoad returned null on forWrite path"
            + " fileId=" + fileId + " pageIndex=" + pageIndex
            + "; WriteCache.loadOrAdd totality contract violated";
    if (cacheEntry != null) {
      cacheEntry.acquireExclusiveLock();
      writeCache.updateDirtyPagesTable(cacheEntry.getCachePointer(), startLSN);
    }

    return cacheEntry;
  }

  @Override
  public CacheEntry loadForRead(
      final long fileId,
      final long pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    return doLoad(fileId, (int) pageIndex, writeCache, verifyChecksums, /*forWrite=*/ false);
  }

  @Nullable @Override
  public CacheEntry silentLoadForRead(
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), extFileId);

    for (;;) {
      var cacheEntry = data.get(fileId, pageIndex);

      if (cacheEntry == null) {
        final var updatedEntry = new CacheEntry[1];

        // The compute lambda receives (fId, pIdx, entry) but we use the outer-scope
        // captured fileId and pageIndex — they are guaranteed identical to the lambda
        // parameters since we pass (fileId, pageIndex) as the compute key.
        cacheEntry =
            data.compute(
                fileId,
                pageIndex,
                (fId, pIdx, entry) -> {
                  if (entry == null) {
                    try {
                      // Non-extending probe: a silent reader must not stamp a fresh empty
                      // buffer for an unallocated page. loadIfPresent honours the same
                      // dirty-write-priority + on-disk-fallback discipline as the legacy
                      // load() but returns null instead of magic-stamping a new page on
                      // miss, so silentLoadForRead can faithfully report "no such page"
                      // (the contract its diagnostic callers — backup, restore-mode probes
                      // — depend on).
                      final var pointer =
                          writeCache.loadIfPresent(fileId, pageIndex, verifyChecksums);
                      if (pointer == null) {
                        return null;
                      }

                      updatedEntry[0] =
                          new CacheEntryImpl(fileId, pageIndex, pointer, false, this);
                      return null;
                    } catch (final IOException e) {
                      throw BaseException.wrapException(
                          new StorageException(writeCache.getStorageName(),
                              "Error during loading of page " + pageIndex + " for file " + fileId),
                          e, writeCache.getStorageName());
                    }

                  } else {
                    return entry;
                  }
                });

        if (cacheEntry == null) {
          cacheEntry = updatedEntry[0];
        }

        if (cacheEntry == null) {
          return null;
        }
      }
      if (cacheEntry.acquireEntry()) {
        return cacheEntry;
      }
    }
  }

  // Documented internal caller of WriteCache.getFilledUpTo: the pre-call file-size snapshot
  // at line 307 reads the physical extent under the data.compute segment write lock so the
  // freshly-allocated branch can flag the resulting entry. See WriteCache.getFilledUpTo
  // Javadoc for the full retained-caller list.
  @SuppressWarnings("deprecation")
  private CacheEntry doLoad(
      final long extFileId,
      final int pageIndex,
      final WriteCache writeCache,
      final boolean verifyChecksums,
      final boolean forWrite) {
    final var fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), extFileId);

    var success = false;
    try {
      while (true) {
        checkWriteBuffer();

        CacheEntry cacheEntry;

        cacheEntry = data.get(fileId, pageIndex);

        if (cacheEntry != null) {
          if (cacheEntry.acquireEntry()) {
            afterRead(cacheEntry);
            success = true;

            return cacheEntry;
          }
        } else {
          final var read = new boolean[1];

          // The compute lambda receives (fId, pIdx, entry) but we use the outer-scope
          // captured fileId and pageIndex — they are guaranteed identical to the lambda
          // parameters since we pass (fileId, pageIndex) as the compute key.
          cacheEntry =
              data.compute(
                  fileId,
                  pageIndex,
                  (fId, pIdx, entry) -> {
                    if (entry == null) {
                      try {
                        // Snapshot the file's current logical page count BEFORE delegating
                        // to loadOrAdd. If pageIndex >= filledUpTo, loadOrAdd will take the
                        // one-page-extend or multi-page gap-fill branch and the resulting
                        // CacheEntry must be flagged as freshly-allocated so that
                        // releaseFromWrite's isNewlyAllocatedPage() check publishes it on
                        // the dirty-page list. Reading filledUpTo is cheap (in-memory size
                        // probe under filesLock.readLock).
                        //
                        // Staleness of the snapshot vs. loadOrAdd's own size read:
                        // per-component locks (BTree mutex, position-map mutex, etc.)
                        // serialize concurrent allocators on the same fileId, so two
                        // transactions cannot concurrently target the same
                        // (fileId, pageIndex). The cache primitive itself does not enforce
                        // this — if a different component concurrently extends a
                        // *different* pageIndex on the same fileId between this read and
                        // loadOrAdd's own size read, the snapshot can become stale. The
                        // benign worst case is a load-branch entry mis-flagged as
                        // newly-allocated, which causes a single redundant clean-page
                        // flush with no correctness impact (the page content came from
                        // disk and is unmodified).
                        final var preCallFilledUpTo = writeCache.getFilledUpTo(fileId);
                        final var pointer =
                            writeCache.loadOrAdd(fileId, pageIndex, verifyChecksums);
                        if (pointer == null) {
                          // The disk engine's loadOrAdd is total. Fail-fast in production
                          // builds with the same diagnostic content the prior assert had so
                          // a regressing impl (or a future in-memory variant routed through
                          // this path) cannot install a CacheEntry around a null pointer
                          // and surface the breakage frames later as an opaque NPE.
                          throw new IllegalStateException(
                              "WriteCache.loadOrAdd contract violation: null CachePointer for"
                                  + " fileId=" + fileId + " pageIndex=" + pageIndex);
                        }

                        cacheSize.incrementAndGet();
                        final var newEntry =
                            new CacheEntryImpl(fileId, pageIndex, pointer, true, this);
                        // markAllocated is a write-only contract: only the write-load
                        // primitive may flag the entry as freshly-allocated so that
                        // releaseFromWrite publishes it on the dirty-page list. Bleeding
                        // the flag onto the read path would publish a clean read load as
                        // dirty when the read happens to hit a fresh-extend race window.
                        if (forWrite && pageIndex >= preCallFilledUpTo) {
                          newEntry.markAllocated();
                        }
                        return newEntry;
                      } catch (final IOException e) {
                        throw BaseException.wrapException(
                            new StorageException(writeCache.getStorageName(),
                                "Error during loading of page " + pageIndex + " for file "
                                    + fileId),
                            e, writeCache.getStorageName());
                      }
                    } else {
                      read[0] = true;
                      return entry;
                    }
                  });

          if (cacheEntry.acquireEntry()) {
            if (read[0]) {
              success = true;
              afterRead(cacheEntry);
            } else {
              afterAdd(cacheEntry);

              try {
                writeCache.checkCacheOverflow();
              } catch (final java.lang.InterruptedException e) {
                throw BaseException.wrapException(
                    new ThreadInterruptedException("Check of write cache overflow was interrupted"),
                    e, writeCache.getStorageName());
              }
            }

            return cacheEntry;
          }
        }
      }
    } finally {
      cacheHitRatio.record(success);
    }
  }

  // NOTE: the legacy addNewPagePointerToTheCache(fileId, pageIndex) fallback used to live
  // here. It was unreachable (zero callers after the loadOrAdd collapse) and carried an
  // unsafe publication pattern — buffer LSN stamping after the CachePointer constructor —
  // so it was removed rather than fixed. WriteCache.loadOrAdd is the sole install path.

  @Override
  public void changeMaximumAmountOfMemory(final long maxMemory) {
    evictionLock.lock();
    try {
      policy.setMaxSize((int) (maxMemory / pageSize));
    } finally {
      evictionLock.unlock();
    }
  }

  @Override
  @Nullable public PageFrame getPageFrameOptimistic(final long fileId, final long pageIndex) {
    final var cacheEntry = data.get(fileId, (int) pageIndex);

    if (cacheEntry == null || !cacheEntry.isAlive()) {
      return null;
    }

    final var cachePointer = cacheEntry.getCachePointer();
    if (cachePointer == null) {
      return null;
    }

    return cachePointer.getPageFrame();
  }

  @Override
  public void recordOptimisticAccess(final long fileId, final long pageIndex) {
    final var cacheEntry = data.get(fileId, (int) pageIndex);

    // Entry may have been evicted between stamp validation and this call.
    // One missed frequency bump is harmless — skip silently.
    if (cacheEntry != null) {
      afterRead(cacheEntry);
    }
  }

  @Override
  public void releaseFromRead(final CacheEntry cacheEntry) {
    cacheEntry.releaseEntry();

    if (!cacheEntry.insideCache()) {
      cacheEntry.getCachePointer().decrementReadersReferrer();
    }
  }

  @Override
  public void releaseFromWrite(
      final CacheEntry cacheEntry, final WriteCache writeCache, final boolean changed) {
    final var cachePointer = cacheEntry.getCachePointer();
    assert cachePointer != null;

    if (cacheEntry.isNewlyAllocatedPage() || changed) {
      if (cacheEntry.isNewlyAllocatedPage()) {
        cacheEntry.clearAllocationFlag();
      }

      // Virtual-lock pattern: compute() holds the segment write lock while the
      // remapping function executes, even if the key is absent. This ensures
      // writeCache.store() runs atomically w.r.t. concurrent map operations on
      // the same key. If absent, remapping returns null → no-op (no insertion).
      data.compute(
          cacheEntry.getFileId(),
          cacheEntry.getPageIndex(),
          (fId, pIdx, entry) -> {
            writeCache.store(
                cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry.getCachePointer());
            return entry;
          });
    }

    // We need to release exclusive lock from cache pointer after we put it into the write cache so
    // both "dirty pages" of write
    // cache and write cache itself will contain actual values simultaneously. But because cache
    // entry can be cleared after we put it back to the
    // read cache we make copy of cache pointer before head.
    //
    // Following situation can happen, if we release exclusive lock before we put entry to the write
    // cache.
    // 1. Page is loaded for write, locked and related LSN is written to the "dirty pages" table.
    // 2. Page lock is released.
    // 3. Page is chosen to be flushed on disk and its entry removed from "dirty pages" table
    // 4. Page is added to write cache as dirty
    //
    // So we have situation when page is added as dirty into the write cache but its related entry
    // in "dirty pages" table is removed
    // it is treated as flushed during fuzzy checkpoint and portion of write ahead log which
    // contains not flushed changes is removed.
    // This can lead to the data loss after restore and corruption of data structures
    cacheEntry.releaseExclusiveLock();
    cacheEntry.releaseEntry();
  }

  private void afterRead(final CacheEntry entry) {
    var batch = readBatch.get();
    batch.entries[batch.size++] = entry;

    if (batch.size >= READ_BATCH_SIZE) {
      flushReadBatch(batch);
    }
  }

  private void flushReadBatch(final ReadBatch batch) {
    var bufferOverflow = false;
    for (int i = 0; i < batch.size; i++) {
      var result = readBuffer.offer(batch.entries[i]);
      if (result == Buffer.FULL) {
        bufferOverflow = true;
      }
    }
    Arrays.fill(batch.entries, 0, batch.size, null); // release references
    batch.size = 0;

    if (drainStatus.get().shouldBeDrained(bufferOverflow)) {
      tryToDrainBuffers();
    }
  }

  /**
   * Flushes the current thread's read batch to the striped buffer. Call this before any
   * operation that needs the eviction policy to be fully up-to-date (clear, assertSize, etc.).
   */
  private void flushCurrentThreadReadBatch() {
    var batch = readBatch.get();
    if (batch.size > 0) {
      flushReadBatch(batch);
    }
  }

  private void afterAdd(final CacheEntry entry) {
    afterWrite(entry);
  }

  private void afterWrite(final CacheEntry command) {
    writeBuffer.offer(command);

    drainStatus.lazySet(DrainStatus.REQUIRED);
    if (cacheSize.get() > 1.07 * maxCacheSize) {
      forceDrainBuffers();
    } else {
      tryToDrainBuffers();
    }
  }

  private void forceDrainBuffers() {
    evictionLock.lock();
    try {
      // optimization to avoid to call tryLock if it is not needed
      drainStatus.lazySet(DrainStatus.IN_PROGRESS);
      emptyBuffers();
    } finally {
      // cas operation because we do not want to overwrite REQUIRED status and to avoid false
      // optimization of
      // drain buffer by IN_PROGRESS status
      try {
        drainStatus.compareAndSet(DrainStatus.IN_PROGRESS, DrainStatus.IDLE);
      } finally {
        evictionLock.unlock();
      }
    }
  }

  private void checkWriteBuffer() {
    if (!writeBuffer.isEmpty()) {
      drainStatus.lazySet(DrainStatus.REQUIRED);
      tryToDrainBuffers();
    }
  }

  private void tryToDrainBuffers() {
    if (drainStatus.get() == DrainStatus.IN_PROGRESS) {
      return;
    }

    if (evictionLock.tryLock()) {
      try {
        // optimization to avoid to call tryLock if it is not needed
        drainStatus.lazySet(DrainStatus.IN_PROGRESS);
        drainBuffers();
      } finally {
        // cas operation because we do not want to overwrite REQUIRED status and to avoid false
        // optimization of
        // drain buffer by IN_PROGRESS status
        drainStatus.compareAndSet(DrainStatus.IN_PROGRESS, DrainStatus.IDLE);
        evictionLock.unlock();
      }
    }
  }

  private void drainBuffers() {
    drainWriteBuffer();
    drainReadBuffers();
  }

  private void emptyBuffers() {
    emptyWriteBuffer();
    drainReadBuffers();
  }

  private void drainReadBuffers() {
    readBuffer.drainTo(policy);
  }

  private void drainWriteBuffer() {
    for (var i = 0; i < WRITE_BUFFER_MAX_BATCH; i++) {
      final var entry = writeBuffer.poll();

      if (entry == null) {
        break;
      }

      this.policy.onAdd(entry);
    }
  }

  private void emptyWriteBuffer() {
    while (true) {
      final var entry = writeBuffer.poll();

      if (entry == null) {
        break;
      }

      this.policy.onAdd(entry);
    }
  }

  @Override
  public long getUsedMemory() {
    return ((long) cacheSize.get()) * pageSize;
  }

  @Override
  public void clear() {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      // Defense-in-depth: clamp cacheSize to zero to avoid IllegalArgumentException
      // from ArrayList if the counter ever drifts negative due to a future bug.
      var currentCacheSize = cacheSize.get();
      var entries = new ArrayList<CacheEntry>(Math.max(0, currentCacheSize));
      data.forEachValue(entries::add);

      for (final var entry : entries) {
        if (entry.freeze()) {
          policy.onRemove(entry);
        } else {
          throw new StorageException(null,
              "Page with index "
                  + entry.getPageIndex()
                  + " for file id "
                  + entry.getFileId()
                  + " is used and cannot be removed");
        }
      }

      data.clear();
      cacheSize.set(0);
    } finally {
      evictionLock.unlock();
    }
  }

  @Override
  public void truncateFile(long fileId, final WriteCache writeCache) throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    writeCache.truncateFile(fileId);
    clearFile(fileId, writeCache);
  }

  @Override
  public void shrinkFile(long fileId, final long targetBytes, final WriteCache writeCache)
      throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    // Two-phase orchestration mirroring truncateFile (above) and deleteFile (below):
    // write-back layer + AsyncFile first, then read-cache purge. The ordering matters
    // because doLoad readers consult the writeCachePages dirty map; a stale dirty
    // entry surviving past the shrink would let a periodic flush re-extend the file
    // past targetBytes. WriteCache.shrinkFile drops dirty entries at
    // pageIndex >= minPageIndex BEFORE the AsyncFile truncate, so by the time the
    // range-scoped clearFile call runs the write-back side is already settled.
    // Defensive invariants on the targetBytes -> minPageIndex cast. There is no
    // upstream argument guard at this orchestrator entry point (unlike
    // WOWCache.shrinkFile which throws on negative targets, but that path is
    // bypassed by DirectMemoryOnlyDiskCache.shrinkFile's no-op on the in-memory
    // engine — without these guards a negative target slips through to the
    // (int) cast below and the range filter purges every cached entry). The
    // checks run under default JVM flags (no -ea) and fire BEFORE the
    // writeCache delegate so a contract violation never lands a partial
    // truncate at the WriteCache layer.
    if (targetBytes < 0) {
      throw new IllegalArgumentException(
          "targetBytes must be non-negative: " + targetBytes);
    }
    if (targetBytes % pageSize != 0) {
      throw new IllegalArgumentException(
          "targetBytes must be a multiple of pageSize: targetBytes="
              + targetBytes
              + " pageSize="
              + pageSize);
    }
    if (targetBytes / pageSize > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "minPageIndex would overflow int: targetBytes="
              + targetBytes
              + " pageSize="
              + pageSize);
    }
    // shrinkFile reports whether the write-back layer physically truncated the
    // file. The read-cache purge below exists only to evict entries for pages that
    // were physically dropped; when the target is at or above the current size the
    // WriteCache no-ops (drops nothing), so there is nothing to purge. Gating the
    // purge on this flag keeps the recovery-time orphan pass O(1) per component
    // when nothing is truncated, instead of paying the O(capacity) removeByFileId
    // sweep on every clean-open dispatch. Invariant: the purge runs iff the
    // write-cache layer truncated (no dropped pages => no stale cache entries).
    final boolean truncated = writeCache.shrinkFile(fileId, targetBytes);
    if (truncated) {
      // LockFreeReadCache and its WriteCache always share the same page size by
      // construction (the cache stores pages frame-for-frame), so this.pageSize is
      // the right divisor. Using the local field avoids depending on the WriteCache
      // impl returning a non-zero pageSize() — TrackingWriteCache returns 0, and a
      // test-mock that stubs every method should not need to know the page size to
      // reach the LFRC range-purge.
      final int minPageIndex = (int) (targetBytes / pageSize);
      clearFile(fileId, minPageIndex, writeCache);
    }
  }

  @Override
  public void closeFile(long fileId, final boolean flush, final WriteCache writeCache) {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    clearFile(fileId, writeCache);
    writeCache.close(fileId, flush);
  }

  @Override
  public void deleteFile(long fileId, final WriteCache writeCache) throws IOException {
    fileId = AbstractWriteCache.checkFileIdCompatibility(writeCache.getId(), fileId);

    clearFile(fileId, writeCache);
    writeCache.deleteFile(fileId);
  }

  @Override
  public void deleteStorage(final WriteCache writeCache) throws IOException {
    drainAllEntries(writeCache);
    writeCache.delete();
  }

  @Override
  public void closeStorage(final WriteCache writeCache) throws IOException {
    drainAllEntries(writeCache);
    writeCache.close();
  }

  /**
   * Single-pass bulk drain used by {@code closeStorage} and {@code deleteStorage}:
   *
   * <ol>
   *   <li>{@code data.removeByStorageId(writeCache.getId())} — one write-locked sweep per
   *       section, atomically removing every entry whose fileId's high 32 bits match this
   *       storage's id. Entries from other storages sharing the same JVM cache are left intact.
   *   <li>For each removed entry: {@code freeze()} + {@code policy.onRemove()} +
   *       {@code cacheSize.decrementAndGet()}. If {@code freeze()} fails (entry still pinned),
   *       throw {@link StorageException} — remaining entries in the batch are left out of the
   *       map and not yet processed, matching the pre-existing semantics of {@link #clearFile}.
   * </ol>
   *
   * <p>This replaces the per-file {@link #clearFile} loop used by {@code closeStorage} and
   * {@code deleteStorage} before. That loop was O(files × total capacity) because every
   * {@code removeByFileId} call linearly swept all 16 sections and same-capacity rehashed any
   * section with a match — on a storage with thousands of files and a warm cache it pegged a
   * close thread at 100 % CPU inside {@code removeByFileId} for 30+ minutes. The new
   * {@code removeByStorageId} collapses that to a single O(total capacity) sweep per close.
   *
   * <p>The method acquires {@link #evictionLock} once for the whole drain rather than per entry
   * as {@link #clearFile} does; close has no progress obligation to other threads. The per-entry
   * {@code writeCache.checkCacheOverflow()} call is intentionally not made — the subsequent
   * {@code writeCache.close()}/{@code writeCache.delete()} triggers a full flush, making the
   * per-entry overflow check redundant and also avoiding its latch wait (see WOWCache#1198).
   *
   * <p><b>Critical ordering — remove from map first, freeze after:</b> freezing an entry while
   * it is still in the map would make concurrent {@code doLoad} callers (for this OR any other
   * live storage sharing the cache) spin forever in their {@code while (true)} loop, because
   * {@code data.get()} would keep returning the frozen entry and {@code acquireEntry()} would
   * keep failing. By removing entries from the map <em>before</em> freezing, concurrent loads
   * either find nothing (and re-load fresh) or find a fresh entry inserted after us — neither
   * can spin.
   *
   * <p><b>Storage-scoped filter:</b> {@link LockFreeReadCache} is a JVM singleton shared by
   * every open storage. A bulk drain that iterates ALL entries (e.g., {@link #clear()}) would
   * freeze entries belonging to other live storages and cause their in-flight {@code doLoad}
   * calls to spin. {@code removeByStorageId} restricts the sweep to entries whose fileId
   * encodes this {@code writeCache}'s id, leaving other storages untouched.
   *
   * <p><b>Freeze-failure handling — re-insert un-frozen entries:</b> freeze only fails when an
   * entry is still pinned (its refcount is {@code > 0}, i.e. some caller is holding it). If we
   * simply threw on the first failure, every already-removed entry AFTER the pinned one would
   * leak — they would be out of the map (retry can't find them), unfrozen (never released from
   * the policy), and their {@link CachePointer}s would never have their referrer counts
   * decremented (pinning the direct memory until JVM exit). To avoid this, we continue the
   * loop on failure, collect un-frozen entries, and re-insert them in the map before throwing.
   * A subsequent retry by the caller (after the pin is released) sees a fresh map containing
   * only the formerly-pinned pages and drains them correctly.
   *
   * <p><b>Durability note:</b> this drain only releases the read-cache's reference on each
   * cache entry. Dirty-page memory is still held by {@link WriteCache} via its own reference
   * count and is flushed/discarded only by {@code writeCache.close()}/{@code delete()}. Do not
   * move the {@code writeCache.close()}/{@code delete()} call ahead of this drain.
   */
  private void drainAllEntries(final WriteCache writeCache) {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      final var removedEntries = data.removeByStorageId(writeCache.getId());

      // First entry that freeze() rejected (still pinned). We use it to build the exception
      // message after re-inserting every un-frozen entry. Null means every entry was frozen.
      CacheEntry firstUnfrozen = null;
      for (final var cacheEntry : removedEntries) {
        if (cacheEntry.freeze()) {
          policy.onRemove(cacheEntry);
          cacheSize.decrementAndGet();
        } else {
          if (firstUnfrozen == null) {
            firstUnfrozen = cacheEntry;
          }
          // Re-insert so a retry after the caller releases the pin can drain this entry
          // through the normal path. Without this, a single pinned page would leak every
          // remaining cache entry for the whole storage (policy zombies + direct-memory
          // reference leak). Close holds the storage's exclusive lock at the caller level,
          // so no concurrent doLoad is racing us on this storage's pages here.
          data.put(cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry);
        }
      }
      if (firstUnfrozen != null) {
        throw new StorageException(writeCache.getStorageName(),
            "Page with index "
                + firstUnfrozen.getPageIndex()
                + " for file id "
                + firstUnfrozen.getFileId()
                + " is used and cannot be removed");
      }
    } finally {
      evictionLock.unlock();
    }
  }

  /**
   * "Remove every cache entry for {@code fileId}" delegate. Forwards to the range-scoped
   * overload with {@code minPageIndex = 0}, which matches every page index.
   */
  private void clearFile(final long fileId, final WriteCache writeCache) {
    clearFile(fileId, 0, writeCache);
  }

  /**
   * Drops every cache entry whose {@code (fileId, pageIndex)} pair satisfies
   * {@code pageIndex >= minPageIndex}; entries below the minimum survive (they belong to
   * file regions the truncate does NOT drop and must remain reachable for ongoing
   * readers). With {@code minPageIndex = 0} this is "remove every cache entry for this
   * fileId" — the shape that {@code closeFile} / {@code deleteFile} /
   * {@code truncateFile} use.
   *
   * <p>Same evictionLock acquisition, current-thread read batch flush, and freeze /
   * onRemove / checkCacheOverflow loop in every case. Pinned-entry recovery follows
   * {@link #drainAllEntries(WriteCache)}'s "re-insert un-frozen entries" pattern: on a
   * freeze failure the loop continues, every un-frozen entry is re-inserted into the
   * segment map, and the first-pinned exception is thrown only after the tail has been
   * fully processed. This keeps {@code policy} and {@code cacheSize} in lockstep with
   * the segment map even on partial failure — an earlier shape that threw on the first
   * pinned entry orphaned the un-processed tail (removed from the map but never
   * accounted for in the policy / cacheSize, leaking direct-memory references via the
   * never-decremented CachePointer referrers).
   *
   * <p>Precondition: this method assumes no concurrent {@code doLoad} for the same
   * fileId. A concurrent {@code doLoad} could re-insert an entry for a page of this
   * file after {@code removeByFileId} completes. Callers coordinate file lifecycle at
   * a higher level so no client TX can race the bulk removal:
   *
   * <ul>
   *   <li>{@code closeFile} / {@code deleteFile} / {@code truncateFile} hold the
   *       storage's exclusive lock.</li>
   *   <li>{@code shrinkFile} is invoked only by the recovery-time orphan-truncation
   *       pass, which runs under {@code stateLock.writeLock()}.</li>
   * </ul>
   *
   * <p>With no concurrent {@code doLoad}, no pinned entries are expected on the
   * recovery path — the re-insertion safety net is defensive against future callers
   * (or interleavings) that violate the precondition.
   */
  private void clearFile(
      final long fileId, final int minPageIndex, final WriteCache writeCache) {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();

      // Bulk removal: removeByFileId sweeps each segment linearly under its write lock
      // with a (fileId, pageIndex >= minPageIndex) match filter. Entries are returned
      // after the segment lock is released; the freeze / onRemove / checkCacheOverflow
      // loop below does not run under a segment write lock — avoiding StampedLock
      // reentrancy deadlock if callbacks re-enter the map.
      final var removedEntries = data.removeByFileId(fileId, minPageIndex);

      // First entry that freeze() rejected (still pinned). We use it to build the
      // exception message after re-inserting every un-frozen entry into the segment
      // map. Null means every entry was frozen.
      CacheEntry firstUnfrozen = null;
      for (final var cacheEntry : removedEntries) {
        if (cacheEntry.freeze()) {
          policy.onRemove(cacheEntry);
          cacheSize.decrementAndGet();

          try {
            writeCache.checkCacheOverflow();
          } catch (final java.lang.InterruptedException e) {
            throw BaseException.wrapException(
                new ThreadInterruptedException("Check of write cache overflow was interrupted"),
                e, writeCache.getStorageName());
          }
        } else {
          if (firstUnfrozen == null) {
            firstUnfrozen = cacheEntry;
          }
          // Re-insert so policy + cacheSize stay in lockstep with the segment map and
          // a retry by the caller (after the pin is released) can drain this entry
          // through the normal path.
          data.put(cacheEntry.getFileId(), cacheEntry.getPageIndex(), cacheEntry);
        }
      }
      if (firstUnfrozen != null) {
        throw new StorageException(writeCache.getStorageName(),
            "Page with index "
                + firstUnfrozen.getPageIndex()
                + " for file id "
                + firstUnfrozen.getFileId()
                + " is used and cannot be removed");
      }
    } finally {
      evictionLock.unlock();
    }
  }

  void assertSize() {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();
      policy.assertSize();
    } finally {
      evictionLock.unlock();
    }
  }

  void assertConsistency() {
    flushCurrentThreadReadBatch();
    evictionLock.lock();
    try {
      emptyBuffers();
      policy.assertConsistency();
    } finally {
      evictionLock.unlock();
    }
  }

  private enum DrainStatus {
    IDLE {
      @Override
      boolean shouldBeDrained(final boolean readBufferOverflow) {
        return readBufferOverflow;
      }
    },
    IN_PROGRESS {
      @Override
      boolean shouldBeDrained(final boolean readBufferOverflow) {
        return false;
      }
    },
    REQUIRED {
      @Override
      boolean shouldBeDrained(final boolean readBufferOverflow) {
        return true;
      }
    };

    abstract boolean shouldBeDrained(boolean readBufferOverflow);
  }

  private static final class ReadBatch {

    final CacheEntry[] entries;
    int size;

    ReadBatch(final int capacity) {
      this.entries = new CacheEntry[capacity];
    }
  }

  @SuppressWarnings("SameParameterValue")
  private static int ceilingPowerOfTwo(final int x) {
    // From Hacker's Delight, Chapter 3, Harry S. Warren Jr.
    return 1 << -Integer.numberOfLeadingZeros(x - 1);
  }
}
