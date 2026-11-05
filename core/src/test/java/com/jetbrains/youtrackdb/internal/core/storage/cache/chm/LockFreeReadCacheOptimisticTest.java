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
  private Object originalReadBatch;

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
  public void tearDown() throws Exception {
    if (originalReadBatch != null) {
      var field = LockFreeReadCache.class.getDeclaredField("readBatch");
      field.setAccessible(true);
      field.set(readCache, originalReadBatch);
    }
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

  /** Uncached attempt loads install the map entry and detect later frame writes. */
  @Test
  public void testPinnedUncachedLoadRecordsStampAndIdentity() {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    var entry = readCache.loadForRead(0, 0, wc, false, scope);
    assertNotNull(entry);
    assertSame(entry, readCache.getCacheEntryOptimistic(0, 0));
    assertEquals(1, scope.count());
    assertTrue(scope.isPinned(0));
    readCache.releaseFromRead(entry);
    var frame = entry.getCachePointer().getPageFrame();
    long lock = frame.acquireExclusiveLock();
    frame.releaseExclusiveLock(lock);
    assertThrows(OptimisticReadFailedException.class, scope::validateOrThrow);
    scope.endAttempt();
  }

  /** Locked frames undo their pin, latch failure, and prohibit subsequent loads. */
  @Test
  public void testPinnedLockedFrameStopsAttemptWithoutPinLeak() {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    var entry = readCache.loadForRead(0, 0, wc, false);
    readCache.releaseFromRead(entry);
    int pins = pinState(entry);
    long lock = entry.getCachePointer().getPageFrame().acquireExclusiveLock();
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    try {
      assertNull(readCache.loadForRead(0, 0, wc, false, scope));
      assertEquals(pins, pinState(entry));
      assertTrue(scope.isFailed());
      assertNull(readCache.loadForRead(0, 1, wc, false, scope));
      assertNull(readCache.getCacheEntryOptimistic(0, 1));
      assertThrows(OptimisticReadFailedException.class, scope::validateOrThrow);
      assertThrows(OptimisticReadFailedException.class, scope::validateLastOrThrow);
    } finally {
      entry.getCachePointer().getPageFrame().releaseExclusiveLock(lock);
      scope.endAttempt();
    }
  }

  /** Silent-load entries stay detached and are never recorded by a pinned attempt. */
  @Test
  public void testPinnedLoadRejectsEntryOutsideMap() {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    var detached = readCache.silentLoadForRead(0, 0, wc, false);
    assertNotNull(detached);
    assertNull(readCache.getCacheEntryOptimistic(0, 0));
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    var entry = readCache.loadForRead(0, 0, wc, false, scope);
    assertNotSame(detached, entry);
    assertSame(entry.getCachePointer().getPageFrame(), scope.getFrame(0));
    assertSame(entry, readCache.getCacheEntryOptimistic(0, 0));
    readCache.releaseFromRead(entry);
    readCache.releaseFromRead(detached);
    assertEquals(0, pinState(entry));
    scope.validateOrThrow();
    scope.endAttempt();
  }

  /** A page beyond EOF at the map update must not call the extending primitive. */
  @Test
  public void testPinnedMissingPageDoesNotGrowFile() throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(0L);
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    assertNull(readCache.loadForRead(0, 5, wc, false, scope));
    assertTrue(scope.isFailed());
    org.mockito.Mockito.verify(wc, org.mockito.Mockito.never()).loadOrAdd(0, 5, false);
    assertNull(readCache.getCacheEntryOptimistic(0, 5));
    scope.endAttempt();
  }

  /** Exceptions and Errors fail the attempt without escaping the load or leaking a pin. */
  @Test
  public void testPinnedFailureAndErrorCleanup() throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    org.mockito.Mockito.doThrow(new IOException("read failed"))
        .when(wc).loadIfPresent(0, 0, false);
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    assertNull(readCache.loadForRead(0, 0, wc, false, scope));
    assertTrue(scope.isFailed());
    scope.endAttempt();
    var entry = readCache.loadForRead(0, 1, wc, false);
    readCache.releaseFromRead(entry);
    var error = new OutOfMemoryError("test");
    failReadNotification(error);
    scope.beginAttempt(new ApplyPhaseEpoch());
    assertNull(readCache.loadForRead(0, 1, wc, false, scope));
    assertThrows(OptimisticReadFailedException.class, scope::validateOrThrow);
    assertSame(error, assertThrows(OutOfMemoryError.class, scope::checkLoadFailure));
    assertEquals(0, pinState(entry));
    scope.endAttempt();
  }

  /** The real component wrapper repeats a failed cache load under its lock and reports I/O. */
  @Test
  public void testPinnedLoadFailureIsReportedByLockedFallback() throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    org.mockito.Mockito.doThrow(new IOException("broken page"))
        .when(wc).loadIfPresent(0, 0, false);
    org.mockito.Mockito.doThrow(new IOException("broken page"))
        .when(wc).loadOrAdd(0, 0, false);
    var storage = mock(AbstractStorage.class);
    var manager = mock(AtomicOperationsManager.class);
    when(storage.getReadCache()).thenReturn(readCache);
    when(storage.getWriteCache()).thenReturn(wc);
    when(storage.getAtomicOperationsManager()).thenReturn(manager);
    when(manager.getApplyPhaseEpoch("test")).thenReturn(new ApplyPhaseEpoch());
    var component = new LookupComponent(storage);
    var op = mock(AtomicOperation.class);
    var scope = new OptimisticReadScope();
    when(op.getOptimisticReadScope()).thenReturn(scope);
    var failure = assertThrows(RuntimeException.class, () -> component.readPinned(op, wc));
    assertTrue(failure.getCause() instanceof IOException);
    assertEquals("broken page", failure.getCause().getMessage());
    org.junit.Assert.assertFalse(scope.isAttemptActive());
    org.junit.Assert.assertFalse(scope.isFailed());
    org.mockito.Mockito.verify(wc).loadIfPresent(0, 0, false);
    org.mockito.Mockito.verify(wc).loadOrAdd(0, 0, false);
    assertNull(readCache.getCacheEntryOptimistic(0, 0));
  }

  /** A pre-pin VM Error is reported unchanged, without a successful locked fallback. */
  @Test
  public void testPrePinOutOfMemoryErrorReachesCaller() throws Exception {
    assertPrePinErrorReachesCaller(new OutOfMemoryError("loadIfPresent"));
  }

  /** A pre-pin AssertionError is not mistaken for a speculative-read retry signal. */
  @Test
  public void testPrePinAssertionErrorReachesCaller() throws Exception {
    assertPrePinErrorReachesCaller(new AssertionError("loadIfPresent"));
  }

  private void assertPrePinErrorReachesCaller(Error error) throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    var resident = readCache.loadForRead(0, 1, wc, false);
    readCache.releaseFromRead(resident);
    org.mockito.Mockito.doThrow(error).when(wc).loadIfPresent(0, 0, false);
    var storage = mock(AbstractStorage.class);
    var manager = mock(AtomicOperationsManager.class);
    when(storage.getReadCache()).thenReturn(readCache);
    when(storage.getWriteCache()).thenReturn(wc);
    when(storage.getAtomicOperationsManager()).thenReturn(manager);
    when(manager.getApplyPhaseEpoch("test")).thenReturn(new ApplyPhaseEpoch());
    var component = new LookupComponent(storage);
    var op = mock(AtomicOperation.class);
    var scope = new OptimisticReadScope();
    when(op.getOptimisticReadScope()).thenReturn(scope);
    var loadReturned = new boolean[1];
    try {
      assertSame(error, assertThrows(error.getClass(),
          () -> component.readPrePinError(op, wc, loadReturned)));
      assertTrue("The load must return null rather than throw", loadReturned[0]);
      org.mockito.Mockito.verify(wc, org.mockito.Mockito.never()).loadOrAdd(0, 0, false);
      assertNull(readCache.getCacheEntryOptimistic(0, 0));
      assertEquals(0, pinState(resident));
      org.junit.Assert.assertFalse(scope.isAttemptActive());
      org.junit.Assert.assertFalse(scope.isFailed());
      scope.checkLoadFailure();

      org.mockito.Mockito.doCallRealMethod().when(wc).loadIfPresent(0, 0, false);
      var next = component.readPinned(op, wc);
      assertNotNull(next);
      readCache.releaseFromRead(next);
      assertEquals(0, pinState(next));
      assertEquals(0, pinState(resident));
      scope.checkLoadFailure();
    } finally {
      // A mutation may wrongly return a pinned fallback page to assertThrows.
      var entry = readCache.getCacheEntryOptimistic(0, 0);
      while (entry != null && pinState(entry) > 0) {
        readCache.releaseFromRead(entry);
      }
    }
  }

  private static final class LookupComponent extends StorageComponent {

    private LookupComponent(AbstractStorage storage) {
      super(storage, "test", ".test", "test", true);
    }

    private CacheEntry readPinned(AtomicOperation op, WriteCache wc) throws IOException {
      return executeOptimisticStorageRead(op,
          () -> readCache.loadForRead(0, 0, wc, false, op.getOptimisticReadScope()),
          () -> readCache.loadForRead(0, 0, wc, false));
    }

    private CacheEntry readPrePinError(
        AtomicOperation op, WriteCache wc, boolean[] loadReturned) throws IOException {
      return executeOptimisticStorageRead(op, () -> {
        var entry = readCache.loadForRead(0, 0, wc, false, op.getOptimisticReadScope());
        loadReturned[0] = entry == null;
        return entry;
      }, () -> readCache.loadForRead(0, 0, wc, false));
    }

    private int fallbackRuns;

    private CacheEntry readSwallowing(
        AtomicOperation op, WriteCache wc, boolean nested, boolean[] failed) throws IOException {
      return executeOptimisticStorageRead(op, () -> {
        try {
          if (nested) {
            readSwallowing(op, wc, false, failed);
          } else {
            var entry = readCache.loadForRead(0, 0, wc, false, op.getOptimisticReadScope());
            if (entry != null) {
              readCache.releaseFromRead(entry);
            }
          }
          op.getOptimisticReadScope().validateOrThrow();
        } catch (RuntimeException ignored) {
          failed[0] = op.getOptimisticReadScope().isFailed();
        }
        return null;
      }, () -> {
        fallbackRuns++;
        return null;
      });
    }

    private PageView lookup(AtomicOperation op, int pageIndex) {
      return loadPageOptimistic(op, 0, pageIndex);
    }
  }

  /** Notification failure must return the sole pin and preserve the original Error. */
  @Test
  public void testNotificationFailureReturnsOnlyPin() throws Exception {
    assertNotificationFailurePreservesPins(false);
  }

  /** Notification failure must not steal another owner's pin or allow eviction to freeze it. */
  @Test
  public void testNotificationFailurePreservesOtherPin() throws Exception {
    assertNotificationFailurePreservesPins(true);
  }

  private void assertNotificationFailurePreservesPins(boolean holdPin) throws Exception {
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    if (!holdPin) {
      readCache.releaseFromRead(entry);
    }
    int before = pinState(entry);
    var error = new OutOfMemoryError("ReadBatch allocation");
    failReadNotification(error);
    try {
      assertSame(error, assertThrows(OutOfMemoryError.class,
          () -> readCache.loadForRead(0, 0, writeCache, false)));
      assertEquals(before, pinState(entry));
      if (holdPin) {
        org.junit.Assert.assertFalse(entry.freeze());
      }
    } finally {
      if (holdPin) {
        readCache.releaseFromRead(entry);
      }
    }
    assertEquals(0, pinState(entry));
  }

  /** Cache hits in attempts must avoid the storage-wide file-size lock entirely. */
  @Test
  public void testPinnedCacheHitDoesNotCheckFileSize() {
    var entry = readCache.loadForRead(0, 0, writeCache, false);
    readCache.releaseFromRead(entry);
    var wc = org.mockito.Mockito.spy(writeCache);
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    try {
      var loaded = readCache.loadForRead(0, 0, wc, false, scope);
      assertSame(entry, loaded);
      readCache.releaseFromRead(loaded);
      org.mockito.Mockito.verify(wc, org.mockito.Mockito.never()).getFilledUpTo(0);
      assertEquals(0, pinState(entry));
    } finally {
      scope.endAttempt();
    }
  }

  /** An overflow interrupt after installation must escape the wrapper, not become a cache hit. */
  @Test
  public void testOverflowInterruptIsNotLostInFallback() throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    org.mockito.Mockito.doAnswer(call -> {
      throw new InterruptedException("overflow interrupted");
    }).when(wc).checkCacheOverflow();
    var storage = mock(AbstractStorage.class);
    var manager = mock(AtomicOperationsManager.class);
    when(storage.getReadCache()).thenReturn(readCache);
    when(storage.getWriteCache()).thenReturn(wc);
    when(storage.getAtomicOperationsManager()).thenReturn(manager);
    when(manager.getApplyPhaseEpoch("test")).thenReturn(new ApplyPhaseEpoch());
    var component = new LookupComponent(storage);
    var op = mock(AtomicOperation.class);
    var scope = new OptimisticReadScope();
    when(op.getOptimisticReadScope()).thenReturn(scope);
    try {
      var failure = assertThrows(
          com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException.class,
          () -> component.readPinned(op, wc));
      assertEquals("overflow interrupted", failure.getCause().getMessage());
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, pinState(readCache.getCacheEntryOptimistic(0, 0)));
      org.junit.Assert.assertFalse(scope.isAttemptActive());
      org.mockito.Mockito.verify(wc).checkCacheOverflow();
    } finally {
      Thread.interrupted();
    }
  }

  /** An installed page cannot hide an overflow AssertionError behind a successful cache hit. */
  @Test
  public void testPostInstallAssertionErrorReachesCaller() throws Exception {
    assertPreservedOverflowFailure(new AssertionError("overflow assertion"), false, false);
  }

  /** A swallowed post-install failure still rejects the direct attempt and is cleared on exit. */
  @Test
  public void testSwallowedLoadFailureRejectsAttempt() throws Exception {
    assertPreservedOverflowFailure(new IllegalStateException("overflow"), true, false);
  }

  /** Inner and outer catches cannot turn a preserved failure into a validated nested result. */
  @Test
  public void testSwallowedNestedLoadFailureRejectsAttempt() throws Exception {
    assertPreservedOverflowFailure(new IllegalStateException("nested overflow"), true, true);
  }

  private void assertPreservedOverflowFailure(
      Throwable failure, boolean swallow, boolean nested) throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    org.mockito.Mockito.doThrow(failure).when(wc).checkCacheOverflow();
    var storage = mock(AbstractStorage.class);
    var manager = mock(AtomicOperationsManager.class);
    when(storage.getReadCache()).thenReturn(readCache);
    when(storage.getWriteCache()).thenReturn(wc);
    when(storage.getAtomicOperationsManager()).thenReturn(manager);
    when(manager.getApplyPhaseEpoch("test")).thenReturn(new ApplyPhaseEpoch());
    var component = new LookupComponent(storage);
    var op = mock(AtomicOperation.class);
    var scope = new OptimisticReadScope();
    when(op.getOptimisticReadScope()).thenReturn(scope);
    var failed = new boolean[1];
    try {
      assertSame(failure, assertThrows(failure.getClass(), () -> {
        if (swallow) {
          component.readSwallowing(op, wc, nested, failed);
        } else {
          var entry = component.readPinned(op, wc);
          if (entry != null) {
            readCache.releaseFromRead(entry);
          }
        }
      }));
      assertEquals(0, component.fallbackRuns);
      if (swallow) {
        assertTrue("Preservation must mark the attempt failed", failed[0]);
      }
      assertEquals(0, pinState(readCache.getCacheEntryOptimistic(0, 0)));
      org.junit.Assert.assertFalse(scope.isAttemptActive());
      org.junit.Assert.assertFalse(scope.isFailed());
      scope.checkLoadFailure();
      // Reuse the same scope through the real wrapper. The installed page is now a cache hit.
      var next = component.readPinned(op, wc);
      assertNotNull(next);
      readCache.releaseFromRead(next);
      scope.checkLoadFailure();
      assertEquals(0, pinState(next));
    } finally {
      org.mockito.Mockito.doNothing().when(wc).checkCacheOverflow();
    }
  }

  /** A secondary metric Error cannot replace the original post-install failure or leak its pin. */
  @Test
  public void testMetricFailureDoesNotReplaceLoadFailure() throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    var originalFailure = new AssertionError("overflow");
    org.mockito.Mockito.doThrow(originalFailure).when(wc).checkCacheOverflow();
    var field = LockFreeReadCache.class.getDeclaredField("cacheHitRatio");
    field.setAccessible(true);
    var originalRatio = field.get(readCache);
    var ratio = mock(com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio.class);
    org.mockito.Mockito.doThrow(new OutOfMemoryError("metrics")).when(ratio)
        .record(org.mockito.ArgumentMatchers.anyBoolean());
    field.set(readCache, ratio);
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    try {
      assertNull(readCache.loadForRead(0, 0, wc, false, scope));
      assertThrows(OptimisticReadFailedException.class, scope::validateOrThrow);
      assertSame(originalFailure, assertThrows(AssertionError.class, scope::checkLoadFailure));
      assertEquals(0, pinState(readCache.getCacheEntryOptimistic(0, 0)));
    } finally {
      field.set(readCache, originalRatio);
      scope.endAttempt();
    }
  }

  /** Metric failure after a cache hit releases only the new pin, inside and outside attempts. */
  @Test
  public void testMetricFailureReturnsPinOnCacheHit() throws Exception {
    assertMetricFailureReturnsPin(true, false);
    assertMetricFailureReturnsPin(true, true);
  }

  /** Metric failure after installation cannot leak a pin or disappear into the locked fallback. */
  @Test
  public void testMetricFailureReturnsPinAfterInstall() throws Exception {
    assertMetricFailureReturnsPin(false, false);
    readCache.clear();
    assertMetricFailureReturnsPin(false, true);
  }

  private void assertMetricFailureReturnsPin(boolean hit, boolean attempt) throws Exception {
    var wc = org.mockito.Mockito.spy(writeCache);
    when(wc.getFilledUpTo(0)).thenReturn(10L);
    CacheEntry owner = null;
    if (hit) {
      owner = readCache.loadForRead(0, 0, writeCache, false);
    }
    var field = LockFreeReadCache.class.getDeclaredField("cacheHitRatio");
    field.setAccessible(true);
    var original = field.get(readCache);
    var ratio = mock(com.jetbrains.youtrackdb.internal.common.profiler.metrics.Ratio.class);
    var error = new AssertionError("metric recording");
    org.mockito.Mockito.doThrow(error).when(ratio)
        .record(org.mockito.ArgumentMatchers.anyBoolean());
    field.set(readCache, ratio);
    var scope = new OptimisticReadScope();
    scope.beginAttempt(new ApplyPhaseEpoch());
    try {
      if (attempt) {
        assertNull(readCache.loadForRead(0, 0, wc, false, scope));
      } else {
        assertSame(error, assertThrows(AssertionError.class,
            () -> readCache.loadForRead(0, 0, wc, false)));
      }
      assertEquals(hit ? 1 : 0, pinState(readCache.getCacheEntryOptimistic(0, 0)));
      if (attempt) {
        assertTrue(scope.isFailed());
        assertThrows(OptimisticReadFailedException.class, scope::validateOrThrow);
        assertSame(error, assertThrows(AssertionError.class, scope::checkLoadFailure));
      }
    } finally {
      field.set(readCache, original);
      scope.endAttempt();
      if (owner != null) {
        readCache.releaseFromRead(owner);
      }
      // Keep teardown useful when a mutation intentionally leaves the returned pin behind.
      var entry = readCache.getCacheEntryOptimistic(0, 0);
      while (entry != null && pinState(entry) > 0) {
        readCache.releaseFromRead(entry);
      }
    }
  }

  private void failReadNotification(Error failure) throws Exception {
    var field = LockFreeReadCache.class.getDeclaredField("readBatch");
    field.setAccessible(true);
    originalReadBatch = field.get(readCache);
    field.set(readCache, ThreadLocal.withInitial(() -> {
      throw failure;
    }));
  }

  private static int pinState(CacheEntry entry) {
    try {
      var field = com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl.class
          .getDeclaredField("state");
      field.setAccessible(true);
      return field.getInt(entry);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
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
