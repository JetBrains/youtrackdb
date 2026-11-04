package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrackdb.internal.core.storage.RawPageBuffer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ApplyPhaseEpoch;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailedException;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadFailureCounters.Cause;
import com.jetbrains.youtrackdb.internal.core.storage.cache.OptimisticReadScope;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageDataVerificationError;
import com.jetbrains.youtrackdb.internal.core.storage.cache.PageView;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.BackgroundExceptionListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.PageIsBrokenListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.memory.MemoryFile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the optimistic (no-CAS) page lookup methods in LockFreeReadCache:
 * getCacheEntryOptimistic() and recordOptimisticAccess().
 */
@org.junit.experimental.categories.Category(com.jetbrains.youtrackdb.internal.SequentialTest.class)
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

  /**
   * The {@code PageFrameWriteCache} mock is not exercised by the recovery-time
   * orphan-truncation pass, so its {@code shrinkFile} override throws UOE. This assertion
   * pins that behaviour — a regression to a silent no-op or a real truncate would hide the
   * fact that a future test misroutes through this mock instead of the production WOWCache.
   */
  @Test
  public void testShrinkFileMockThrowsUnsupportedOperation() {
    assertThrows(UnsupportedOperationException.class, () -> writeCache.shrinkFile(0L, 0L));
  }

  @Test
  public void testCacheHitReturnsPageFrame() {
    // Load a page via the normal CAS path, then look it up optimistically.
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);

    PageFrame frame = lookupFrame(0, 0);
    assertNotNull("Cache hit should return a non-null PageFrame", frame);
  }

  @Test
  public void testCacheMissReturnsNull() {
    // Look up a page that was never loaded — should return null.
    PageFrame frame = lookupFrame(0, 99);
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
      if (lookupFrame(0, i) == null) {
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

    PageFrame frame = lookupFrame(0, 0);
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

    PageFrame optimisticFrame = lookupFrame(0, 0);
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
      PageFrame frame = lookupFrame(0, i);
      assertNotNull("Page " + i + " should be found", frame);
    }
  }

  @Test
  public void testConcurrentEvictionDuringOptimisticLookup() throws Exception {
    // One thread calls getCacheEntryOptimistic() while another forces eviction by
    // loading pages beyond cache capacity. Lookup returns an unpinned CacheEntry or null.
    // Eviction can follow the liveness check, so the later identity check is required.
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
          PageFrame frame = lookupFrame(fileId, targetPage);
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

  /** Eviction leaves a referenced frame's stamp valid, but a reload changes the cached identity. */
  @Test
  public void testEvictionThenReloadRejectsOldFrame() {
    var old = evictedFrame();
    try {
      var replacement = readCache.loadForRead(0, old.entry().getPageIndex(), writeCache, false);
      try {
        assertNotSame(old.frame(), replacement.getCachePointer().getPageFrame());
        long lock = replacement.acquireExclusiveLock();
        replacement.getCachePointer().getPageFrame().getBuffer().putInt(0, 123);
        replacement.releaseExclusiveLock();
        assertTrue(lock != 0);
      } finally {
        readCache.releaseFromRead(replacement);
      }
      assertTrue(old.frame().validate(old.stamp()));
      assertIdentityFailure(old.scope());
    } finally {
      old.pointer().decrementReadersReferrer();
    }
  }

  /** A lookup suspended before stamp acquisition must reject an already-evicted retained frame. */
  @Test
  public void testStampAfterEvictionAndLaterDropRejectsOldFrame() {
    var old = evictedFrame();
    var scope = new OptimisticReadScope();
    scope.record(old.frame(), old.frame().tryOptimisticRead(), old.entry());
    try {
      var replacement = readCache.loadForRead(0, old.entry().getPageIndex(), writeCache, false);
      assertNotSame(old.frame(), replacement.getCachePointer().getPageFrame());
      readCache.releaseFromRead(replacement);
    } finally {
      old.pointer().decrementReadersReferrer();
    }
    assertIdentityFailure(scope);
  }

  /** A disk frame reused by the pool for another page must fail identity, not just its stamp. */
  @Test
  public void testPoolReuseForAnotherPageRejectsOldFrame() {
    var old = evictedFrame();
    var parked = drainPool();
    old.pointer().decrementReadersReferrer();
    var reused = pageFramePool.acquire(false, Intention.TEST);
    try {
      assertSame(old.frame(), reused);
      new CachePointer(reused, pageFramePool, 99, 123);
      assertIdentityFailure(old.scope());
    } finally {
      pageFramePool.release(reused);
      parked.forEach(pageFramePool::release);
    }
  }

  /**
   * MemoryFile and disk storage zero share the pool. Even a fresh stamp on a reused
   * frame with matching coordinates must not make the suspended disk lookup succeed.
   */
  @Test
  public void testMemoryFileReuseWithMatchingCoordinatesRejectsDiskRead() {
    readCache.clear();
    bufferPool = ByteBufferPool.instance(null);
    pageFramePool = bufferPool.pageFramePool();
    int pageSize = GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() * 1024;
    readCache = new LockFreeReadCache(bufferPool, 1024L * pageSize, pageSize);
    writeCache = new PageFrameWriteCache(pageFramePool);
    var old = evictedFrame();
    var memoryFile = new MemoryFile(0, 0);
    for (int i = 0; i < old.entry().getPageIndex(); i++) {
      memoryFile.addNewPage(readCache);
    }
    var parked = drainPool();
    old.pointer().decrementReadersReferrer();
    try {
      var memoryEntry = memoryFile.addNewPage(readCache);
      assertSame(old.frame(), memoryEntry.getCachePointer().getPageFrame());
      var lateScope = new OptimisticReadScope();
      lateScope.record(old.frame(), old.frame().tryOptimisticRead(), old.entry());
      assertEquals(0, old.frame().getFileId());
      assertEquals(old.entry().getPageIndex(), old.frame().getPageIndex());
      assertIdentityFailure(lateScope);
    } finally {
      memoryFile.clear();
      parked.forEach(pageFramePool::release);
    }
  }

  /** Unchanged live entries pass both scope checks and zero-copy validation without counter work. */
  @Test
  public void testUnchangedCachedFramePassesWithoutFailureCount() {
    long before = OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY);
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    var frame = entry.getCachePointer().getPageFrame();
    var scope = new OptimisticReadScope();
    scope.record(frame, frame.tryOptimisticRead(), entry);
    readCache.releaseFromRead(entry);
    scope.validateLastOrThrow();
    scope.validateOrThrow();
    new RawPageBuffer(frame, frame.tryOptimisticRead(), 0, 4, 0, (byte) 0, entry).toRawBuffer();
    assertEquals(before, OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY));
  }

  /** Zero-copy extraction must repeat identity validation after the optimistic scope closes. */
  @Test
  public void testZeroCopyRejectsEvictedRetainedFrame() {
    var old = evictedFrame();
    try {
      long before = OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY);
      var result = new RawPageBuffer(old.frame(), old.stamp(), 0, 4, 0, (byte) 0, old.entry());
      assertThrows(OptimisticReadFailedException.class, result::toRawBuffer);
      assertEquals(before + 1, OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY));
    } finally {
      old.pointer().decrementReadersReferrer();
    }
  }

  private record Evicted(CacheEntry entry, CachePointer pointer, PageFrame frame, long stamp,
      OptimisticReadScope scope) {
  }

  private Evicted evictedFrame() {
    // Capture every page before eviction. At least one must leave a 1024-page cache,
    // independent of the admission sketch's random seed. Extra references model backup.
    var captured = new ArrayList<Evicted>();
    for (int i = 0; i < 1100; i++) {
      var entry = readCache.loadForRead(0, i, writeCache, false);
      var pointer = entry.getCachePointer();
      pointer.incrementReadersReferrer();
      var frame = pointer.getPageFrame();
      var scope = new OptimisticReadScope();
      long stamp = frame.tryOptimisticRead();
      scope.record(frame, stamp, entry);
      captured.add(new Evicted(entry, pointer, frame, stamp, scope));
      readCache.releaseFromRead(entry);
    }
    readCache.assertSize();
    var evicted = captured.stream().filter(e -> !e.entry().isAlive()).findFirst().orElseThrow();
    captured.stream().filter(e -> e != evicted)
        .forEach(e -> e.pointer().decrementReadersReferrer());
    assertNull(lookupFrame(0, evicted.entry().getPageIndex()));
    assertTrue(evicted.frame().validate(evicted.stamp()));
    return evicted;
  }

  private PageFrame lookupFrame(long fileId, long pageIndex) {
    var entry = readCache.getCacheEntryOptimistic(fileId, pageIndex);
    var pointer = entry == null ? null : entry.getCachePointer();
    return pointer == null ? null : pointer.getPageFrame();
  }

  /** clearFile freezes a removed entry while a backup reference retains its valid frame. */
  @Test
  public void testClearFileRejectsRetainedFrame() {
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    var pointer = entry.getCachePointer();
    pointer.incrementReadersReferrer();
    var frame = pointer.getPageFrame();
    var scope = new OptimisticReadScope();
    long stamp = frame.tryOptimisticRead();
    scope.record(frame, stamp, entry);
    readCache.releaseFromRead(entry);
    try {
      readCache.closeFile(0, false, writeCache);
      assertTrue(frame.validate(stamp));
      assertIdentityFailure(scope);
    } finally {
      pointer.decrementReadersReferrer();
    }
  }

  /** A write-cache hit can reload the same frame under a new read-cache entry. */
  @Test
  public void testSameFrameReloadUnderNewEntryRejectsOldEntry() throws IOException {
    var old = evictedFrame();
    var retainingWriteCache = mock(WriteCache.class);
    when(retainingWriteCache.loadOrAdd(eq(0L), eq((long) old.entry().getPageIndex()), eq(false)))
        .thenAnswer(invocation -> {
          old.pointer().incrementReadersReferrer();
          return old.pointer();
        });
    try {
      var replacement = readCache.loadForRead(
          0, old.entry().getPageIndex(), retainingWriteCache, false);
      assertNotSame(old.entry(), replacement);
      assertSame(old.frame(), replacement.getCachePointer().getPageFrame());
      readCache.releaseFromRead(replacement);
      assertTrue(old.frame().validate(old.stamp()));
      assertIdentityFailure(old.scope());
    } finally {
      old.pointer().decrementReadersReferrer();
    }
  }

  /** Production lookup must record identity because capacity eviction preserves retained stamps. */
  @Test
  public void testStorageComponentLookupRejectsEvictedRetainedPage() {
    var storage = mock(AbstractStorage.class);
    var manager = mock(AtomicOperationsManager.class);
    when(storage.getReadCache()).thenReturn(readCache);
    when(storage.getWriteCache()).thenReturn(writeCache);
    when(storage.getAtomicOperationsManager()).thenReturn(manager);
    when(manager.getApplyPhaseEpoch("test")).thenReturn(new ApplyPhaseEpoch());
    var component = new LookupComponent(storage);
    var captured = new ArrayList<Evicted>();
    try {
      for (int i = 0; i < 1100; i++) {
        var entry = readCache.loadForRead(0, i, writeCache, false);
        var pointer = entry.getCachePointer();
        pointer.incrementReadersReferrer();
        readCache.releaseFromRead(entry);
        var scope = new OptimisticReadScope();
        var op = mock(AtomicOperation.class);
        when(op.getOptimisticReadScope()).thenReturn(scope);
        var view = component.lookup(op, i);
        captured.add(new Evicted(entry, pointer, view.pageFrame(), view.stamp(), scope));
      }
      readCache.assertSize();
      var removed = captured.stream().filter(e -> !e.entry().isAlive()).findFirst().orElseThrow();
      assertTrue(removed.frame().validate(removed.stamp()));
      assertIdentityFailure(removed.scope());
    } finally {
      captured.forEach(e -> e.pointer().decrementReadersReferrer());
    }
  }

  private static final class LookupComponent extends StorageComponent {

    private LookupComponent(AbstractStorage storage) {
      super(storage, "test", ".test", "test", true);
    }

    private PageView lookup(AtomicOperation op, int pageIndex) {
      return loadPageOptimistic(op, 0, pageIndex);
    }
  }

  private ArrayList<PageFrame> drainPool() {
    var parked = new ArrayList<PageFrame>();
    while (pageFramePool.getPoolSize() > 0) {
      parked.add(pageFramePool.acquire(false, Intention.TEST));
    }
    return parked;
  }

  private static void assertIdentityFailure(OptimisticReadScope scope) {
    long before = OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY);
    assertThrows(OptimisticReadFailedException.class, scope::validateOrThrow);
    assertThrows(OptimisticReadFailedException.class, scope::validateLastOrThrow);
    assertEquals(before + 2, OptimisticReadFailureCounters.count(Cause.FRAME_IDENTITY));
  }

  /**
   * A WriteCache mock that creates PageFrame-backed CachePointers, enabling
   * getCacheEntryOptimistic() to return entries with PageFrame-backed pointers.
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

    /**
     * Stub for the new total primitive introduced in Track 1: delegates to the existing mock
     * {@link #load} so this test class compiles while still exercising its original load path.
     */
    @Override
    public CachePointer loadOrAdd(long fileId, long pageIndex, boolean verifyChecksums) {
      return load(fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
    }

    /**
     * Stub for the non-extending silent-read probe: delegates to {@link #load} so the mock
     * keeps its always-allocate semantics while satisfying the {@link WriteCache} contract.
     */
    @Override
    public CachePointer loadIfPresent(long fileId, long pageIndex, boolean verifyChecksums) {
      return load(fileId, pageIndex, new ModifiableBoolean(), verifyChecksums);
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
    public void syncDataFiles() {
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
    public long physicalSizeForBackupSnapshot(long fileId) {
      // Mock parallel to WOWCache.physicalSizeForBackupSnapshot: delegates to the
      // existing getFilledUpTo mock value so wrapper tests see the same answer.
      return getFilledUpTo(fileId);
    }

    @Override
    public long getExclusiveWriteCachePagesSize() {
      return 0;
    }

    @Override
    public void deleteFile(long fileId) {
    }

    @Override
    public boolean shrinkFile(long fileId, long targetBytes) {
      // Mock not exercised by the recovery-time orphan-truncation pass; surfacing UOE
      // catches an accidental call from a future test that should use the production
      // WriteCache impl instead. The throw satisfies the new boolean return type, so the
      // void->boolean migration leaves this deliberate trip-wire body intact.
      throw new UnsupportedOperationException("shrinkFile is not supported by test mock");
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
    public String getStorageName() {
      return "test";
    }
  }
}
