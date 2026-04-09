package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the optimistic (no-CAS) page lookup methods in LockFreeReadCache:
 * getPageFrameOptimistic() and recordOptimisticAccess().
 */
public class LockFreeReadCacheOptimisticTest {

  private static final int PAGE_SIZE = 4 * 1024;

  private DirectMemoryAllocator allocator;
  private ByteBufferPool bufferPool;
  private PageFramePool pageFramePool;
  private LockFreeReadCache readCache;
  private WriteCache writeCache;

  @Before
  public void setUp() {
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(true);
    allocator = new DirectMemoryAllocator();
    bufferPool = new ByteBufferPool(PAGE_SIZE, allocator, 256);
    pageFramePool = bufferPool.pageFramePool();
    long maxMemory = 1024L * PAGE_SIZE;
    readCache = new LockFreeReadCache(bufferPool, maxMemory, PAGE_SIZE);
    writeCache = new PageFrameWriteCache(pageFramePool);
  }

  @After
  public void tearDown() {
    readCache.clear();
    GlobalConfiguration.DIRECT_MEMORY_TRACK_MODE.setValue(false);
  }

  @Test
  public void testCacheHitReturnsPageFrame() {
    // Load a page via the normal CAS path, then look it up optimistically.
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    PageFrame frame = readCache.getPageFrameOptimistic(0, 0);
    assertNotNull("Cache hit should return a non-null PageFrame", frame);
  }

  @Test
  public void testCacheMissReturnsNull() {
    // Look up a page that was never loaded — should return null.
    PageFrame frame = readCache.getPageFrameOptimistic(0, 99);
    assertNull("Cache miss should return null", frame);
  }

  @Test
  public void testEvictedEntryReturnsNull() {
    // Fill cache beyond capacity to trigger eviction, then check an evicted page.
    int capacity = 1024; // matches setUp(): maxMemory = 1024L * PAGE_SIZE
    int totalPages = 1100;
    int minEvicted = totalPages - capacity;

    for (int i = 0; i < totalPages; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    // Flush all internal buffers and verify the cache's structural invariants
    // (cacheSize counter == data map size == LRU list sizes, all <= maxCacheSize).
    readCache.assertSize();

    // The W-TinyLFU admission filter may evict either old victims or new candidates
    // depending on the frequency sketch's hash collision pattern (which uses a random
    // seed). Scan the full range to find evicted pages regardless of which end the
    // policy chose to evict from.
    int nullCount = 0;
    for (int i = 0; i < totalPages; i++) {
      if (readCache.getPageFrameOptimistic(0, i) == null) {
        nullCount++;
      }
    }

    int retainedCount = totalPages - nullCount;
    assertTrue(
        "At least " + minEvicted + " pages should have been evicted (" + totalPages
            + " loaded, " + capacity + " capacity), but found " + nullCount + " evicted",
        nullCount >= minEvicted);
    assertTrue(
        "Cache should retain up to its " + capacity + "-page capacity; retained only "
            + retainedCount + " pages",
        retainedCount >= capacity);
  }

  @Test
  public void testReturnedFrameHasValidStamp() {
    // The returned PageFrame should yield a valid optimistic stamp.
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    PageFrame frame = readCache.getPageFrameOptimistic(0, 0);
    assertNotNull(frame);

    long stamp = frame.tryOptimisticRead();
    assertTrue("Stamp should be non-zero (no exclusive lock held)", stamp != 0);
    assertTrue("Stamp should be valid", frame.validate(stamp));
  }

  @Test
  public void testReturnedFrameIsSameAsCacheEntryFrame() {
    // The optimistic lookup should return the same PageFrame as the CAS-pinned path.
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    PageFrame pinnedFrame = entry.getCachePointer().getPageFrame();
    readCache.releaseFromRead(entry);

    PageFrame optimisticFrame = readCache.getPageFrameOptimistic(0, 0);
    assertSame("Optimistic and pinned lookups should return the same PageFrame",
        pinnedFrame, optimisticFrame);
  }

  @Test
  public void testRecordOptimisticAccessDoesNotThrow() {
    // After a successful optimistic read, recording the access should not throw.
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    // Should not throw, even if called multiple times
    readCache.recordOptimisticAccess(0, 0);
    readCache.recordOptimisticAccess(0, 0);
  }

  @Test
  public void testRecordOptimisticAccessOnEvictedEntry() {
    // Recording access for an evicted page should silently succeed (skip).
    readCache.recordOptimisticAccess(0, 99);
    // No exception expected
  }

  @Test
  public void testOptimisticLookupMultiplePages() {
    // Load several pages and verify all can be looked up optimistically.
    for (int i = 0; i < 10; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
      readCache.releaseFromRead(entry);
    }

    for (int i = 0; i < 10; i++) {
      PageFrame frame = readCache.getPageFrameOptimistic(0, i);
      assertNotNull("Page " + i + " should be found", frame);
    }
  }

  @Test
  public void testConcurrentEvictionDuringOptimisticLookup() throws Exception {
    // Exercises the TOCTOU window between isAlive() and getCachePointer() in
    // getPageFrameOptimistic(): one thread reads optimistically while another
    // thread evicts by loading pages beyond cache capacity. The method must
    // return either a valid PageFrame or null — never a stale pointer.
    int iterations = 200;
    long fileId = 0;
    int targetPage = 0;

    // Pre-load the target page
    var entry = readCache.loadForRead(fileId, targetPage, writeCache, false);
    readCache.releaseFromRead(entry);

    var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
    var running = new java.util.concurrent.atomic.AtomicBoolean(true);

    // Evictor thread: load pages to force eviction of targetPage
    Thread evictor = new Thread(() -> {
      try {
        for (int i = 1; running.get() && i < 2000; i++) {
          var e = readCache.loadForRead(fileId, i, writeCache, false);
          readCache.releaseFromRead(e);
        }
      } catch (Throwable t) {
        errors.compareAndSet(null, t);
      }
    });

    // Reader thread: repeatedly does optimistic lookup on the target page
    Thread reader = new Thread(() -> {
      try {
        for (int i = 0; i < iterations; i++) {
          PageFrame frame = readCache.getPageFrameOptimistic(fileId, targetPage);
          // frame is either null (evicted) or a valid PageFrame with a live StampedLock
          if (frame != null) {
            long stamp = frame.tryOptimisticRead();
            // stamp may be 0 (exclusive lock held during eviction) or valid — both OK
            if (stamp != 0) {
              frame.validate(stamp); // must not throw
            }
          }
        }
      } catch (Throwable t) {
        errors.compareAndSet(null, t);
      }
    });

    evictor.start();
    reader.start();
    reader.join(10_000);
    running.set(false);
    evictor.join(10_000);

    if (errors.get() != null) {
      throw new AssertionError("Concurrent test failed", errors.get());
    }
  }

  /**
   * A WriteCache mock that creates PageFrame-backed CachePointers, enabling
   * getPageFrameOptimistic() to return non-null PageFrame references.
   */
  private record PageFrameWriteCache(PageFramePool framePool) implements WriteCache {

    @Override
    public CachePointer load(long fileId, long startPageIndex, ModifiableBoolean cacheHit,
        boolean verifyChecksums) {
      var frame = framePool.acquire(true, Intention.TEST);
      var cachePointer = new CachePointer(frame, framePool, fileId, (int) startPageIndex);
      cachePointer.incrementReadersReferrer();
      return cachePointer;
    }

    @Override
    public void addPageIsBrokenListener(PageIsBrokenListener listener) {
    }

    @Override
    public void removePageIsBrokenListener(PageIsBrokenListener listener) {
    }

    @Override
    public long bookFileId(String fileName) {
      return 0;
    }

    @Override
    public long loadFile(String fileName) {
      return 0;
    }

    @Override
    public long addFile(String fileName) {
      return 0;
    }

    @Override
    public long addFile(String fileName, long fileId) {
      return 0;
    }

    @Override
    public long fileIdByName(String fileName) {
      return 0;
    }

    @Override
    public boolean checkLowDiskSpace() {
      return false;
    }

    @Override
    public void syncDataFiles(long segmentId) {
    }

    @Override
    public void flushTillSegment(long segmentId) {
    }

    @Override
    public boolean exists(String fileName) {
      return false;
    }

    @Override
    public boolean exists(long fileId) {
      return false;
    }

    @Override
    public void restoreModeOn() {
    }

    @Override
    public void restoreModeOff() {
    }

    @Override
    public void store(long fileId, long pageIndex, CachePointer dataPointer) {
    }

    @Override
    public void checkCacheOverflow() {
    }

    @Override
    public int allocateNewPage(long fileId) {
      return 0;
    }

    @Override
    public void flush(long fileId) {
    }

    @Override
    public void flush() {
    }

    @Override
    public long getFilledUpTo(long fileId) {
      return 0;
    }

    @Override
    public long getExclusiveWriteCachePagesSize() {
      return 0;
    }

    @Override
    public void deleteFile(long fileId) {
    }

    @Override
    public void truncateFile(long fileId) {
    }

    @Override
    public void renameFile(long fileId, String newFileName) {
    }

    @Override
    public long[] close() {
      return new long[0];
    }

    @Override
    public void close(long fileId, boolean flush) {
    }

    @Override
    public PageDataVerificationError[] checkStoredPages(
        CommandOutputListener listener) {
      return new PageDataVerificationError[0];
    }

    @Override
    public long[] delete() {
      return new long[0];
    }

    @Override
    public String fileNameById(long fileId) {
      return null;
    }

    @Override
    public String nativeFileNameById(long fileId) {
      return null;
    }

    @Override
    public int getId() {
      return 0;
    }

    @Override
    public Map<String, Long> files() {
      return null;
    }

    @Override
    public int pageSize() {
      return 0;
    }

    @Override
    public String restoreFileById(long fileId) {
      return null;
    }

    @Override
    public void addBackgroundExceptionListener(BackgroundExceptionListener l) {
    }

    @Override
    public void removeBackgroundExceptionListener(BackgroundExceptionListener l) {
    }

    @Override
    public Path getRootDirectory() {
      return null;
    }

    @Override
    public int internalFileId(long fileId) {
      return 0;
    }

    @Override
    public long externalFileId(int fileId) {
      return 0;
    }

    @Override
    public boolean fileIdsAreEqual(long firsId, long secondId) {
      return false;
    }

    @Override
    public Long getMinimalNotFlushedSegment() {
      return null;
    }

    @Override
    public void updateDirtyPagesTable(CachePointer pointer,
        LogSequenceNumber startLSN) {
    }

    @Override
    public void create() {
    }

    @Override
    public void open() throws IOException {
    }

    @Override
    public void replaceFileId(long fileId, long newFileId) {
    }

    @Override
    public String getStorageName() {
      return "test";
    }
  }
}
