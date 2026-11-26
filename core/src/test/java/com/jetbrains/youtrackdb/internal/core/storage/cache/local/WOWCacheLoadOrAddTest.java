package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.LogRecordCollector;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.concur.lock.PartitionedLockManager;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ReadersWriterSpinLock;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFrame;
import com.jetbrains.youtrackdb.internal.common.directmemory.PageFramePool;
import com.jetbrains.youtrackdb.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.RecoveryPageContext;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.RecoveryFilePermission;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLog;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.fs.AsyncFile;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileNames;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.PageFileRole;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

/**
 * Smoke coverage for {@link WOWCache#loadOrAdd} and its three branches
 * (load existing / one-page extend / multi-page gap-fill).
 *
 * <p>Each test exercises the new total cache primitive against a real {@link WOWCache}
 * over a temporary on-disk storage. The tests are intentionally narrow ("does each branch
 * fire and return a usable {@code CachePointer}?") rather than exhaustive; comprehensive
 * cache-coverage tests, multi-thread stress, eviction/flush races, and non-durable
 * extension all land in a follow-up dedicated test track.
 *
 * <p><b>Coverage scope note.</b> The defensive load-branch totality fallback
 * (return-magic-stamped-empty-buffer when {@code loadFileContent} returns {@code null})
 * cannot be exercised in this test class without a test seam (a custom
 * {@code loadFileContent} override or reflection on internal state). Exercising the dead
 * branch is therefore deferred to the dedicated cache-coverage test track that introduces
 * the necessary seams.
 */
// The fixture changes process-global file access flags.
@Category(SequentialTest.class)
public class WOWCacheLoadOrAddTest {

  // With only one WAL segment, the real periodic task must use the exclusive phase.
  @Test
  public void periodicTaskMaterializesPendingExclusivePageWithoutOldestPhase() throws Exception {
    assertPendingPeriodicPhase(false);
  }

  // A reader-owned page is not exclusive. Only the oldest-segment phase can write it.
  @Test
  public void periodicTaskMaterializesPendingOldestPageWithoutExclusiveWork() throws Exception {
    assertPendingPeriodicPhase(true);
  }

  private void assertPendingPeriodicPhase(boolean oldest) throws Exception {
    final long id = preparePendingPage(9400, null);
    final var held = oldest ? wowCache.loadIfPresent(id, 2, false) : null;
    final var wal = Mockito.spy(writeAheadLog);
    when(wal.begin()).thenReturn(new LogSequenceNumber(3, 0));
    when(wal.end()).thenReturn(new LogSequenceNumber(oldest ? 4 : 3, 20));
    final var walField = WOWCache.class.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(wowCache, wal);
    final var poolField = WOWCache.class.getDeclaredField("bufferPool");
    poolField.setAccessible(true);
    final var pool = Mockito.spy(bufferPool);
    poolField.set(wowCache, pool);
    wowCache = Mockito.spy(wowCache);
    Mockito.doReturn(java.util.concurrent.CompletableFuture.completedFuture(null))
        .when(wowCache).schedulePeriodicFlush(any(), Mockito.anyLong());
    final var paused = WOWCache.class.getDeclaredField("backgroundFlushPaused");
    paused.setAccessible(true);
    try {
      assertEquals(oldest ? 0 : 1, wowCache.getExclusiveWriteCachePagesSize());
      paused.set(wowCache, false);
      onFlushExecutor(() -> new PeriodicFlushTask(wowCache).run());
      assertMaterializedContents(id, 3, 2, 728);
      assertNull(earliestRetainedSegment());
      Mockito.verify(pool, Mockito.times(oldest ? 0 : 1))
          .acquireDirect(false, Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
      Mockito.verify(pool, Mockito.times(oldest ? 1 : 0))
          .acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
      Mockito.verify(wowCache).createFileHandle(any(), Mockito.anyInt());
    } finally {
      paused.set(wowCache, true);
      walField.set(wowCache, writeAheadLog);
      poolField.set(wowCache, bufferPool);
      if (held != null) {
        held.decrementReadersReferrer();
      }
    }
  }

  // The submitted public task must deduplicate a newer lower barrier shared by old segments.
  @Test(timeout = 30_000)
  public void publicSegmentFlushMaterializesPendingPagesAcrossDirtySegments() throws Exception {
    final long id = preparePendingPage(9401, null);
    cacheUnwrittenImage(id, 0, 100);
    cacheUnwrittenImage(id, 1, 101);
    final var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
    dirtyField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var dirty = (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyField.get(wowCache);
    for (int page = 0; page < 3; page++) {
      dirty.put(new PageKey(wowCache.internalFileId(id), page),
          new LogSequenceNumber(page == 0 ? 4 : page + 1, 10));
    }
    final var poolField = WOWCache.class.getDeclaredField("bufferPool");
    poolField.setAccessible(true);
    final var pool = Mockito.spy(bufferPool);
    poolField.set(wowCache, pool);
    final var counts = new TreeMap<Long, Integer>();
    final var held = new java.util.ArrayList<CachePointer>();
    for (int page = 0; page < 3; page++) {
      held.add(wowCache.loadIfPresent(id, page, false));
    }
    wowCache = Mockito.spy(wowCache);
    Mockito.doAnswer(inv -> {
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(write -> {
        final List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        for (final var buffer : buffers) {
          for (long offset = buffer.first;
              offset < buffer.first + buffer.second.limit(); offset += PAGE_SIZE) {
            counts.merge(offset / PAGE_SIZE, 1, Integer::sum);
          }
        }
        return original.write(buffers);
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    try {
      wowCache.flushTillSegment(5);
      assertMaterializedContents(id, 3, Map.of(0, 100, 1, 101, 2, 728));
      assertEquals(Map.of(0L, 1, 1L, 1, 2L, 1), counts);
      Mockito.verify(pool, Mockito.times(3))
          .acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
      assertNull(earliestRetainedSegment());
      assertTrue(dirty.isEmpty());
    } finally {
      for (final var pointer : held) {
        pointer.decrementReadersReferrer();
      }
      poolField.set(wowCache, bufferPool);
    }
  }

  // Later batch submission observes only the earlier batch's protection released.
  @Test
  public void successfulExclusiveBatchesReleaseOnlyPublishedRequirements() throws Exception {
    assertExclusiveBatchOutcome(false, false);
  }

  // A direct failure leaves tracker-only work for explicit retry, not oldest-LSN selection.
  @Test
  public void laterExclusiveFailureRetainsTrackerOnlyImageUntilExplicitRetry() throws Exception {
    assertExclusiveBatchOutcome(true, false);
  }

  // Periodic dispatch latches the later failure and preserves the earlier published prefix.
  @Test
  public void periodicLaterExclusiveFailureRetainsImagesAndKnownUnownedCopy() throws Exception {
    assertExclusiveBatchOutcome(true, true);
  }

  private void assertExclusiveBatchOutcome(boolean fail, boolean periodic) throws Exception {
    final long id = preparePendingPage(9402, null);
    cacheUnwrittenImage(id, 3, 303);
    cacheUnwrittenImage(id, 4, 404);
    final var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
    dirtyField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var dirty = (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyField.get(wowCache);
    for (int page = 2; page <= 4; page++) {
      dirty.put(new PageKey(wowCache.internalFileId(id), page),
          new LogSequenceNumber(page, 10));
    }
    final var chunkField = WOWCache.class.getDeclaredField("chunkSize");
    chunkField.setAccessible(true);
    final int originalChunk = chunkField.getInt(wowCache);
    chunkField.set(wowCache, 1);
    final var poolField = WOWCache.class.getDeclaredField("bufferPool");
    poolField.setAccessible(true);
    final var pool = Mockito.spy(bufferPool);
    final var copies = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<
            com.jetbrains.youtrackdb.internal.common.directmemory.Pointer, Boolean>());
    Mockito.doAnswer(inv -> {
      final var copy = bufferPool.acquireDirect(false,
          Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
      assertTrue(copies.add(copy));
      return copy;
    }).when(pool).acquireDirect(false, Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
    Mockito.doAnswer(inv -> {
      final com.jetbrains.youtrackdb.internal.common.directmemory.Pointer copy = inv.getArgument(0);
      copies.remove(copy);
      bufferPool.release(copy);
      return null;
    }).when(pool).release(any());
    poolField.set(wowCache, pool);
    wowCache = Mockito.spy(wowCache);
    final var injected = new IllegalStateException("later exclusive batch failed");
    final var observed = new java.util.concurrent.atomic.AtomicBoolean();
    Mockito.doAnswer(inv -> {
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(write -> {
        final List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        if (buffers.getFirst().first == 4L * PAGE_SIZE) {
          assertEquals(Long.valueOf(4), earliestRetainedSegment());
          assertEquals(4, wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskExtent());
        }
        if (buffers.getFirst().first == 3L * PAGE_SIZE && !observed.getAndSet(true)) {
          assertEquals(3, wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskExtent());
          assertEquals(Long.valueOf(3), earliestRetainedSegment());
          assertEquals("Current batch and next copied page are still owned", 2, copies.size());
          Mockito.verify(pool, Mockito.times(3))
              .acquireDirect(false, Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
          if (fail) {
            return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
              throw injected;
            };
          }
        }
        return original.write(buffers);
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    final var error = WOWCache.class.getDeclaredField("flushError");
    error.setAccessible(true);
    Mockito.doReturn(java.util.concurrent.CompletableFuture.completedFuture(null))
        .when(wowCache).schedulePeriodicFlush(any(), Mockito.anyLong());
    try {
      if (periodic) {
        flushWithSelector(id, 3);
        assertCause((Throwable) error.get(wowCache), injected);
      } else if (fail) {
        assertCause(assertThrows(InvocationTargetException.class,
            () -> flushWithSelector(id, 1)), injected);
        assertNull(error.get(wowCache));
      } else {
        flushWithSelector(id, 1);
        assertMaterializedContents(id, 5, Map.of(2, 728, 3, 303, 4, 404));
        assertNull(earliestRetainedSegment());
        assertTrue(copies.isEmpty());
        assertTrue(observed.get());
        return;
      }
      assertTrue(observed.get());
      assertEquals(3, wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskExtent());
      assertEquals(Long.valueOf(3), earliestRetainedSegment());
      for (int page = 3; page <= 4; page++) {
        final var retained = wowCache.loadIfPresent(id, page, false);
        assertNotNull(retained);
        try {
          assertEquals(page == 3 ? 303 : 404,
              retained.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
        } finally {
          retained.decrementReadersReferrer();
        }
      }
      assertMaterializedContents(id, 3, 2, 728);
      // YTDB-1702: the next copied page has not joined a batch when the preceding write fails.
      assertEquals("One unowned copy remains outside sink cleanup", 1, copies.size());
      if (!periodic) {
        assertTrue(dirty.isEmpty());
        final var oldest = WOWCache.class.getDeclaredMethod(
            "flushWriteCacheFromMinLSN", long.class, long.class, int.class);
        oldest.setAccessible(true);
        onFlushExecutor(() -> oldest.invoke(wowCache, 3L, 5L, 100));
        assertEquals(Long.valueOf(3), earliestRetainedSegment());
        assertEquals(3, wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskExtent());
        wowCache.flush(id);
        assertMaterializedContents(id, 5, Map.of(2, 728, 3, 303, 4, 404));
        assertNull(earliestRetainedSegment());
        assertEquals(1, copies.size());
      }
    } finally {
      error.set(wowCache, null);
      // The fixture owns cleanup of the known unowned copy.
      for (final var copy : copies) {
        bufferPool.release(copy);
      }
      copies.clear();
      poolField.set(wowCache, bufferPool);
      chunkField.set(wowCache, originalChunk);
    }
  }

  // A shared pending batch drains each owned handle before admitting the next file.
  @Test
  public void periodicPendingBatchBalancesCopiesAndHandlesAtOneFileLimit() throws Exception {
    assertPendingAdmission(false);
  }

  // Fatal creation after an earlier completion keeps images protected and reaches the latch.
  @Test
  public void periodicPendingCreationFailureDrainsEarlierFileAtOneFileLimit() throws Exception {
    assertPendingAdmission(true);
  }

  private void assertPendingAdmission(boolean failCreation) throws Exception {
    final var originalFiles = files;
    files = Mockito.spy(new ClosableLinkedContainer<Long, File>(1));
    final var handles = WOWCache.class.getDeclaredField("files");
    handles.setAccessible(true);
    handles.set(wowCache, files);
    final var held = new java.util.ArrayList<CachePointer>();
    final var ids = new java.util.ArrayList<Long>();
    for (int component = 9403; component < 9406; component++) {
      final long id = preparePendingPage(component, null);
      ids.add(id);
      held.add(wowCache.loadIfPresent(id, 2, false));
    }
    final var acquired = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<Object, Boolean>());
    final var acquisitions = new java.util.concurrent.atomic.AtomicInteger();
    Mockito.doAnswer(inv -> {
      final var entry = inv.callRealMethod();
      if (entry != null) {
        assertTrue(acquired.add(entry));
        acquisitions.incrementAndGet();
      }
      return entry;
    }).when(files).tryAcquire(Mockito.anyLong());
    Mockito.doAnswer(inv -> {
      assertTrue("Release must match a live acquisition", acquired.remove(inv.getArgument(0)));
      return inv.callRealMethod();
    }).when(files).release(any());
    final var poolField = WOWCache.class.getDeclaredField("bufferPool");
    poolField.setAccessible(true);
    final var pool = Mockito.spy(bufferPool);
    final var copies = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<
            com.jetbrains.youtrackdb.internal.common.directmemory.Pointer, Boolean>());
    Mockito.doAnswer(inv -> {
      final var copy = bufferPool.acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
      assertTrue(copies.add(copy));
      return copy;
    }).when(pool).acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
    Mockito.doAnswer(inv -> {
      assertTrue("A copy must be released exactly once", copies.remove(inv.getArgument(0)));
      bufferPool.release(inv.getArgument(0));
      return null;
    }).when(pool).release(any());
    poolField.set(wowCache, pool);
    final var walField = WOWCache.class.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    final var wal = Mockito.spy(writeAheadLog);
    when(wal.begin()).thenReturn(new LogSequenceNumber(3, 0));
    when(wal.end()).thenReturn(new LogSequenceNumber(4, 20));
    walField.set(wowCache, wal);
    wowCache = Mockito.spy(wowCache);
    Mockito.doReturn(java.util.concurrent.CompletableFuture.completedFuture(null))
        .when(wowCache).schedulePeriodicFlush(any(), Mockito.anyLong());
    final var creations = new java.util.concurrent.atomic.AtomicInteger();
    final var completions = new java.util.concurrent.atomic.AtomicInteger();
    final var firstId = new java.util.concurrent.atomic.AtomicLong();
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var injected = new IOException("later pending creation failed");
    Mockito.doAnswer(inv -> {
      assertTrue("Admission must follow completion and handle release", acquired.isEmpty());
      final int number = creations.incrementAndGet();
      assertEquals(number - 1, completions.get());
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      if (failCreation && number == 2) {
        Mockito.doThrow(injected).when(wrapper).create();
      }
      Mockito.doAnswer(write -> {
        final List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        final var result = original.write(buffers);
        if (buffers.getFirst().first != 2L * PAGE_SIZE) {
          return result;
        }
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          if (number == 1) {
            firstId.set(wowCache.externalFileId((int) inv.getArgument(1)));
            entered.countDown();
            try {
              assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
              throw new IllegalStateException(interrupted);
            }
          }
          assertEquals(1, acquired.size());
          result.await();
          completions.incrementAndGet();
        };
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    final var paused = WOWCache.class.getDeclaredField("backgroundFlushPaused");
    paused.setAccessible(true);
    final var error = WOWCache.class.getDeclaredField("flushError");
    error.setAccessible(true);
    paused.set(wowCache, false);
    final var task = flushExecutor().submit(() -> new PeriodicFlushTask(wowCache).run());
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertEquals(1, creations.get());
      assertEquals(0, completions.get());
      release.countDown();
      task.get(10, TimeUnit.SECONDS);
      assertEquals(failCreation ? 2 : 3, creations.get());
      assertEquals(failCreation ? 1 : 3, completions.get());
      assertEquals(completions.get(), acquisitions.get());
      assertTrue(acquired.isEmpty());
      assertTrue(copies.isEmpty());
      Mockito.doCallRealMethod().when(files).release(any());
      Mockito.verify(pool, Mockito.times(3))
          .acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
      if (failCreation) {
        assertCause((Throwable) error.get(wowCache), injected);
        assertEquals(Long.valueOf(3), earliestRetainedSegment());
        assertMaterializedContents(firstId.get(), 3, 2, 728);
        for (final long id : ids) {
          final var pointer = wowCache.loadIfPresent(id, 2, false);
          assertNotNull(pointer);
          assertEquals(728, pointer.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
          pointer.decrementReadersReferrer();
          if (id != firstId.get()) {
            assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskPresent());
          }
        }
      } else {
        assertNull(error.get(wowCache));
        assertNull(earliestRetainedSegment());
        for (final long id : ids) {
          assertMaterializedContents(id, 3, 2, 728);
        }
      }
    } finally {
      release.countDown();
      task.get(10, TimeUnit.SECONDS);
      paused.set(wowCache, true);
      error.set(wowCache, null);
      walField.set(wowCache, writeAheadLog);
      poolField.set(wowCache, bufferPool);
      Mockito.doCallRealMethod().when(wowCache).createFileHandle(any(), Mockito.anyInt());
      Mockito.doCallRealMethod().when(files).release(any());
      for (final var pointer : held) {
        pointer.decrementReadersReferrer();
      }
      for (final long id : ids) {
        final var file = files.remove(id);
        if (file != null) {
          if (!file.isOpen()) {
            file.open();
          }
          originalFiles.add(id, file);
        }
      }
      files = originalFiles;
      handles.set(wowCache, files);
    }
  }

  // A paused reservation caller must not hold up growth of a different page in the same file.
  @Test
  public void logicalGrowerDoesNotBlockAnotherGrower() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9120);
    final var fileId = wowCache.addFile(PageFileNames.layout28(identity),
        wowCache.externalFileId(identity.internalId()));
    final var original = files.remove(fileId);
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doAnswer(inv -> {
      entered.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS));
      original.reserveSpaceThrough(inv.getArgument(0));
      return null;
    }).when(wrapper).reserveSpaceThrough(4L * PAGE_SIZE);
    files.add(fileId, wrapper);
    final var first = asyncFileExecutor.submit(() -> {
      wowCache.loadOrAdd(fileId, 3, false).decrementReadersReferrer();
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      final var second = asyncFileExecutor.submit(() -> {
        wowCache.loadOrAdd(fileId, 6, false).decrementReadersReferrer();
        return null;
      });
      second.get(10, TimeUnit.SECONDS);
      assertEquals(7, wowCache.getFilledUpTo(fileId));
    } finally {
      release.countDown();
      first.get(10, TimeUnit.SECONDS);
    }
    assertEquals(7L * PAGE_SIZE, original.getFileSize());
    wowCache.flush(fileId);
  }

  // Pending files reject oversized requests before the extent or pointer index can wrap.
  @Test
  public void pendingLogicalAllocationRejectsOversizedIndex() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9121);
    final var fileId = wowCache.externalFileId(identity.internalId());
    wowCache.logicalFiles().register(PageFileNames.layout28(identity), identity.internalId(),
        false, 0, 0, false);
    try {
      for (long index : new long[] {4294967296L, Long.MAX_VALUE,
          Integer.MAX_VALUE / PAGE_SIZE}) {
        assertThrows(StorageException.class, () -> wowCache.loadOrAdd(fileId, index, false));
        assertEquals(0, wowCache.getFilledUpTo(fileId));
      }
    } finally {
      wowCache.logicalFiles().forget(identity.internalId());
    }
  }

  // No blank-stamp tasks run here. Only batch completion can publish the changed page's extent.
  @Test
  public void batchWritePublishesExtentBeforeEvictionAndReload() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final int internalId = wowCache.internalFileId(fileId);
    final var changed = wowCache.loadIfPresent(fileId, 2, false);
    changed.decrementReadersReferrer();
    wowCache.flush(fileId);
    assertEquals(3, wowCache.logicalFiles().live(internalId).diskExtent());
    final var loaded = wowCache.loadIfPresent(fileId, 2, true);
    assertNotNull(loaded);
    try {
      assertNotSame(changed, loaded);
      assertEquals(728, loaded.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
    } finally {
      loaded.decrementReadersReferrer();
    }
  }

  // A failed batch completion must leave both the old written extent and the changed cache image.
  @Test
  public void failedBatchWriteKeepsExtentAndChangedImage() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doAnswer(invocation -> {
      final List<
          com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> batch =
              invocation.getArgument(0);
      if (batch.getFirst().first < 2L * PAGE_SIZE) {
        return original.write(batch);
      }
      return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
        throw new IllegalStateException("injected batch failure");
      };
    }).when(wrapper).write(Mockito.anyList());
    files.add(fileId, wrapper);
    try {
      assertThrows(Exception.class, () -> wowCache.flush(fileId));
      assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      final var cached = wowCache.loadIfPresent(fileId, 2, false);
      assertNotNull(cached);
      try {
        assertEquals(728, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        cached.decrementReadersReferrer();
      }
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
      wowCache.flush(fileId);
    }
  }

  // Each selector must initialize every uncached gap on both the first and later high write.
  @Test
  public void materializedGapsAreInitializedByEveryFlushEntryPoint() throws Exception {
    final var walSpy = Mockito.spy(writeAheadLog);
    final var walField = WOWCache.class.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(wowCache, walSpy);
    for (int selector = 0; selector < 3; selector++) {
      final long fileId = prepareUnwrittenMaterializedPage();
      Mockito.clearInvocations(walSpy);
      flushWithSelector(fileId, selector);
      // Explicit flush has one existing force. Gap initialization adds none on any path.
      Mockito.verify(walSpy, Mockito.times(selector == 0 ? 1 : 0)).flush();
      assertMaterializedContents(fileId, 3, 2, 728);
      cacheUnwrittenImage(fileId, 0, 0);
      cacheUnwrittenImage(fileId, 6, 901);
      Mockito.clearInvocations(walSpy);
      flushWithSelector(fileId, selector);
      Mockito.verify(walSpy, Mockito.times(selector == 0 ? 1 : 0)).flush();
      assertMaterializedContents(fileId, 7, Map.of(2, 728, 6, 901));
      final var page = wowCache.loadIfPresent(fileId, 6, true);
      try {
        assertEquals(901, page.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        page.decrementReadersReferrer();
      }
      wowCache.deleteFile(fileId);
    }
  }

  private static ScheduledExecutorService flushExecutor() {
    return YouTrackDBEnginesManager.instance().getWowCacheFlushExecutor();
  }

  @FunctionalInterface
  private interface FlushAction {
    void run() throws Exception;
  }

  // Invoke private selectors on the owner thread and preserve the exception seen by the caller.
  private static void onFlushExecutor(FlushAction action) throws Exception {
    try {
      flushExecutor().submit(() -> {
        action.run();
        return null;
      }).get(10, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException failure) {
      if (failure.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw (Error) failure.getCause();
    }
  }

  private void flushWithSelector(long fileId, int selector) throws Exception {
    if (selector == 0) {
      wowCache.flush(fileId);
    } else if (selector == 3) {
      final var paused = WOWCache.class.getDeclaredField("backgroundFlushPaused");
      paused.setAccessible(true);
      paused.set(wowCache, false);
      try {
        // Re-armed tasks are no-ops so only this controlled cycle accesses the fixture.
        onFlushExecutor(() -> wowCache.executePeriodicFlush(mock(PeriodicFlushTask.class)));
      } finally {
        wowCache.pauseBackgroundFlush();
      }
    } else if (selector == 1) {
      final var method = WOWCache.class.getDeclaredMethod(
          "flushExclusiveWriteCache", CountDownLatch.class, long.class);
      method.setAccessible(true);
      onFlushExecutor(() -> method.invoke(wowCache, null, 100L));
    } else {
      final var method = WOWCache.class.getDeclaredMethod(
          "flushWriteCacheFromMinLSN", long.class, long.class, int.class);
      method.setAccessible(true);
      onFlushExecutor(() -> method.invoke(wowCache, 3L, 4L, 100));
    }
  }

  // Holding the intervening changed page prevents its copy, not the upper write.
  // Neither the upper image nor WAL protection may be removed across that barrier.
  @Test
  public void cachedGapBlocksPublicationAndBlankTaskUntilProtectedWrite() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var blank = new EnsurePageIsValidInFileTask(wowCache.internalFileId(fileId), 2,
        wowCache);
    cacheUnwrittenImage(fileId, 1, 119);
    final var lower = wowCache.loadIfPresent(fileId, 1, false);
    final var stamp = lower.acquireExclusiveLock();
    try {
      blank.run();
      assertEquals(1, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      wowCache.flush(fileId);
      assertEquals(1, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      assertEquals(119, lower.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      flushWithSelector(fileId, 1);
      flushWithSelector(fileId, 2);
      flushWithSelector(fileId, 3);
      assertEquals(1, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      final var upper = wowCache.loadIfPresent(fileId, 2, false);
      try {
        assertEquals(728, upper.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        upper.decrementReadersReferrer();
      }
    } finally {
      lower.releaseExclusiveLock(stamp);
      lower.decrementReadersReferrer();
    }
    flushWithSelector(fileId, 2);
    assertMaterializedContents(fileId, 3, Map.of(1, 119, 2, 728));
  }

  // A locked unpublished barrier must stop both selectors before an upper image reaches the
  // DWL, even if the physical file already spans that image. Publication alone cannot prove this.
  @Test
  public void lockedBarrierPreventsUpperProtectedWrite() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      final long fileId = prepareUnwrittenMaterializedPage();
      cacheUnwrittenImage(fileId, 1, 119);
      final var lower = wowCache.loadIfPresent(fileId, 1, false);
      final var stamp = lower.acquireExclusiveLock();
      final var original = files.remove(fileId);
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      when(wrapper.getUnderlyingFileSize()).thenReturn(3L * PAGE_SIZE);
      files.add(fileId, wrapper);
      final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
      dwlField.setAccessible(true);
      final var originalDwl = dwlField.get(wowCache);
      final var dwl = mock(DoubleWriteLog.class);
      dwlField.set(wowCache, dwl);
      try {
        flushWithSelector(fileId, selector);
        Mockito.verify(dwl, Mockito.never()).write(any(), any(), any());
        Mockito.verify(wrapper, Mockito.never()).write(Mockito.anyList());
        assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      } finally {
        dwlField.set(wowCache, originalDwl);
        files.remove(fileId);
        files.add(fileId, original);
        lower.releaseExclusiveLock(stamp);
        lower.decrementReadersReferrer();
      }
      flushWithSelector(fileId, selector);
      assertMaterializedContents(fileId, 3, Map.of(1, 119, 2, 728));
      wowCache.deleteFile(fileId);
    }
  }

  // Observe scheduling synchronously. A blocked cycle keeps the normal interval, whereas a
  // shrinking exclusive cache with pages remaining requests the 1 ms cadence.
  @Test
  public void periodicFastRearmRequiresExclusiveCacheShrink() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    cacheUnwrittenImage(fileId, 1, 119);
    wowCache = Mockito.spy(wowCache);
    Mockito.doReturn(java.util.concurrent.CompletableFuture.completedFuture(null))
        .when(wowCache).schedulePeriodicFlush(any(), Mockito.anyLong());
    final var lower = wowCache.loadIfPresent(fileId, 1, false);
    final var stamp = lower.acquireExclusiveLock();
    final var paused = WOWCache.class.getDeclaredField("backgroundFlushPaused");
    paused.setAccessible(true);
    final var task = mock(PeriodicFlushTask.class);
    try {
      paused.set(wowCache, false);
      final long before = wowCache.getExclusiveWriteCachePagesSize();
      onFlushExecutor(() -> wowCache.executePeriodicFlush(task));
      assertEquals(before, wowCache.getExclusiveWriteCachePagesSize());
      Mockito.verify(wowCache).schedulePeriodicFlush(task, PAGES_FLUSH_INTERVAL);
      Mockito.verify(wowCache, Mockito.never()).schedulePeriodicFlush(task, 1);
    } finally {
      paused.set(wowCache, true);
      lower.releaseExclusiveLock(stamp);
      lower.decrementReadersReferrer();
    }
    for (int page = 3; page < 8; page++) {
      cacheUnwrittenImage(fileId, page, page);
    }
    final var chunk = WOWCache.class.getDeclaredField("chunkSize");
    chunk.setAccessible(true);
    final int originalChunk = chunk.getInt(wowCache);
    chunk.set(wowCache, 1);
    Mockito.clearInvocations(wowCache);
    try {
      paused.set(wowCache, false);
      final long before = wowCache.getExclusiveWriteCachePagesSize();
      onFlushExecutor(() -> wowCache.executePeriodicFlush(task));
      assertTrue(wowCache.getExclusiveWriteCachePagesSize() > 0);
      assertTrue(wowCache.getExclusiveWriteCachePagesSize() < before);
      Mockito.verify(wowCache).schedulePeriodicFlush(task, 1);
    } finally {
      paused.set(wowCache, true);
      chunk.set(wowCache, originalChunk);
    }
  }

  // Completion merges concurrent logical growth and happens before cache-image removal.
  @Test
  public void batchCompletionPreservesGrowthAndNewerCachedImage() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doAnswer(invocation -> {
      final var result = original.write(invocation.getArgument(0));
      assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      asyncFileExecutor.submit(() -> {
        wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).extents().allocateThrough(9);
        final var cached = wowCache.loadIfPresent(fileId, 2, false);
        final var stamp = cached.acquireExclusiveLock();
        try {
          cached.getBuffer().putInt(DurablePage.NEXT_FREE_POSITION, 812);
        } finally {
          cached.releaseExclusiveLock(stamp);
          cached.decrementReadersReferrer();
        }
        return null;
      }).get(10, TimeUnit.SECONDS);
      return result;
    }).when(wrapper).write(Mockito.anyList());
    files.add(fileId, wrapper);
    try {
      wowCache.flush(fileId);
      assertEquals(9, wowCache.getFilledUpTo(fileId));
      assertEquals(3, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      final var cached = wowCache.loadIfPresent(fileId, 2, false);
      try {
        assertEquals(812, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        cached.decrementReadersReferrer();
      }
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
    }
    wowCache.flush(fileId);
    assertMaterializedContents(fileId, 3, 2, 812);
  }

  // A queued task cannot act on a replacement lifetime, and repeated blanks preserve data.
  @Test
  public void queuedBlankRejectsStaleLifetimeAndPreservesCompletedPages() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final int internalId = wowCache.internalFileId(fileId);
    final var blank = new EnsurePageIsValidInFileTask(internalId, 2, wowCache);
    final var state = wowCache.logicalFiles().live(internalId);
    wowCache.logicalFiles().forget(internalId);
    wowCache.logicalFiles().register(PageFileNames.layout28(state.identity()), internalId,
        false, 3, 0, true);
    blank.run();
    assertEquals(0, wowCache.logicalFiles().live(internalId).diskExtent());
    wowCache.flush(fileId);
    new EnsurePageIsValidInFileTask(internalId, 2, wowCache).run();
    assertMaterializedContents(fileId, 3, 2, 728);
  }

  // A blank initialization failure must remain observable even when its task has no waiter.
  @Test
  public void failedBlankKeepsChangedImageAndLatchesFlushError() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doThrow(new IOException("injected blank failure"))
        .when(wrapper).write(Mockito.anyList());
    files.add(fileId, wrapper);
    assertThrows(StorageException.class,
        () -> new EnsurePageIsValidInFileTask(wowCache.internalFileId(fileId), 1, wowCache).run());
    assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
    assertThrows(Exception.class, () -> wowCache.flush(fileId));
    final var cached = wowCache.loadIfPresent(fileId, 2, false);
    try {
      assertEquals(728, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
    } finally {
      cached.decrementReadersReferrer();
    }
  }

  // A segment flush must include a newer-segment barrier below an older upper page.
  @Test
  public void segmentFlushIncludesNewerBarrierAndTerminates() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    cacheUnwrittenImage(fileId, 1, 119);
    final var held = wowCache.loadIfPresent(fileId, 1, false);
    final var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
    dirtyField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var dirty = (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyField.get(wowCache);
    dirty.put(new PageKey(wowCache.internalFileId(fileId), 2), new LogSequenceNumber(2, 10));
    try {
      flushExecutor().submit(() -> wowCache.executeFlushTillSegment(3))
          .get(10, TimeUnit.SECONDS);
      assertMaterializedContents(fileId, 3, Map.of(1, 119, 2, 728));
    } finally {
      held.decrementReadersReferrer();
    }
  }

  // Every submitted file result must drain even when another file fails. Successful files may
  // publish, but the failed file retains its image and cannot publish across the failed range.
  @Test
  public void mixedFileFailureDrainsAllStartedWrites() throws Exception {
    final long first = prepareUnwrittenMaterializedPage();
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9133);
    final long second = wowCache.addFile(PageFileNames.layout28(identity),
        wowCache.externalFileId(identity.internalId()));
    cacheUnwrittenImage(second, 2, 337);
    final var firstOriginal = files.remove(first);
    final var secondOriginal = files.remove(second);
    final var drained = new java.util.concurrent.atomic.AtomicInteger();
    for (long id : new long[] {first, second}) {
      final var original = id == first ? firstOriginal : secondOriginal;
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(invocation -> {
        final List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> batch =
                invocation.getArgument(0);
        final var result = original.write(batch);
        if (batch.getFirst().first < 2L * PAGE_SIZE) {
          return result;
        }
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          result.await();
          drained.incrementAndGet();
          if (id == second) {
            throw new IllegalStateException("second file failed");
          }
        };
      }).when(wrapper).write(Mockito.anyList());
      files.add(id, wrapper);
    }
    try {
      assertThrows(Exception.class, () -> wowCache.flush());
      assertEquals(2, drained.get());
      assertEquals(3, wowCache.logicalFiles().live(wowCache.internalFileId(first)).diskExtent());
      assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(second)).diskExtent());
      final var cached = wowCache.loadIfPresent(second, 2, false);
      try {
        assertEquals(337, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        cached.decrementReadersReferrer();
      }
    } finally {
      files.remove(first);
      files.remove(second);
      files.add(first, firstOriginal);
      files.add(second, secondOriginal);
    }
  }

  // A read-cache-owned barrier must be copied by both background selectors, not blanked or
  // skipped forever. A second cycle must perform no additional data writes.
  @Test
  public void backgroundSelectorsFlushReadCachedBarrierWithoutRewritingUpperPage()
      throws Exception {
    for (int selector = 1; selector <= 3; selector++) {
      final long fileId = prepareUnwrittenMaterializedPage();
      cacheUnwrittenImage(fileId, 1, 119);
      final var lower = wowCache.loadIfPresent(fileId, 1, false);
      final var original = files.remove(fileId);
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      files.add(fileId, wrapper);
      try {
        flushWithSelector(fileId, selector);
        assertMaterializedContents(fileId, 3, Map.of(1, 119, 2, 728));
        Mockito.clearInvocations(wrapper);
        flushWithSelector(fileId, selector);
        Mockito.verify(wrapper, Mockito.never()).write(Mockito.anyList());
        // The segment entry point must terminate even when the lower page belongs to a
        // newer segment than an upper page left by a previous exclusive cycle.
        flushExecutor().submit(() -> wowCache.executeFlushTillSegment(4))
            .get(10, TimeUnit.SECONDS);
      } finally {
        lower.decrementReadersReferrer();
        files.remove(fileId);
        files.add(fileId, original);
      }
      wowCache.deleteFile(fileId);
    }
  }

  // The final absence check must run after acquisition of the page-key lock. Inserting a
  // cached image while the blank task is just before that lock must prevent a blank write.
  @Test
  public void concurrentStoreWinsBeforeBlankAbsenceCheck() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    wowCache = Mockito.spy(wowCache);
    final var beforeLock = new CountDownLatch(1);
    final var proceed = new CountDownLatch(1);
    Mockito.doAnswer(invocation -> {
      beforeLock.countDown();
      assertTrue(proceed.await(10, TimeUnit.SECONDS));
      return null;
    }).when(wowCache).beforeBlankPageLock();
    final var task = new EnsurePageIsValidInFileTask(wowCache.internalFileId(fileId), 0, wowCache);
    final var future = asyncFileExecutor.submit(task);
    try {
      assertTrue(beforeLock.await(10, TimeUnit.SECONDS));
      cacheUnwrittenImage(fileId, 0, 153);
    } finally {
      proceed.countDown();
    }
    future.get(10, TimeUnit.SECONDS);
    assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
    wowCache.flush(fileId);
    assertMaterializedContents(fileId, 3, Map.of(0, 153, 2, 728));
  }

  // Interrupted blank I/O must retain its native buffer and lock until the started write
  // completes. The drain restores the interrupt status before reporting the failure.
  @Test
  public void interruptedBlankRetainsNativeBufferUntilCompletion() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    final var waiting = new CountDownLatch(1);
    final var interrupted = new CountDownLatch(1);
    final var complete = new CountDownLatch(1);
    final var exited = new CountDownLatch(1);
    final var interruptRestored = new java.util.concurrent.atomic.AtomicBoolean();
    final var retained = new java.util.concurrent.atomic.AtomicLong();
    Mockito
        .doAnswer(invocation -> (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          retained.set(com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator
              .instance().getMemoryConsumption());
          waiting.countDown();
          boolean wasInterrupted = false;
          while (true) {
            try {
              complete.await();
              break;
            } catch (InterruptedException e) {
              wasInterrupted = true;
              interrupted.countDown();
            }
          }
          if (wasInterrupted) {
            Thread.currentThread().interrupt();
          }
        }).when(wrapper).write(Mockito.anyList());
    files.add(fileId, wrapper);
    final var thread = new Thread(() -> {
      try {
        new EnsurePageIsValidInFileTask(wowCache.internalFileId(fileId), 0, wowCache).run();
        interruptRestored.set(Thread.currentThread().isInterrupted());
      } finally {
        exited.countDown();
      }
    });
    try {
      thread.start();
      assertTrue(waiting.await(10, TimeUnit.SECONDS));
      thread.interrupt();
      assertTrue(interrupted.await(10, TimeUnit.SECONDS));
      assertEquals(1, exited.getCount());
      assertEquals(retained.get(),
          com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.instance()
              .getMemoryConsumption());
    } finally {
      complete.countDown();
      thread.join(10_000);
      files.remove(fileId);
      files.add(fileId, original);
    }
    assertFalse(thread.isAlive());
    assertTrue(interruptRestored.get());
    assertEquals(retained.get() - PAGE_SIZE,
        com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.instance()
            .getMemoryConsumption());
    // The test double completed without writing bytes, so reset its synthetic publication.
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(fileId));
    wowCache.logicalFiles().forget(wowCache.internalFileId(fileId));
    wowCache.logicalFiles().register(PageFileNames.layout28(state.identity()),
        wowCache.internalFileId(fileId), false, 3, 0, true);
  }

  // A gap write failure inside the changed-page sink must preserve the only changed image.
  @Test
  public void failedGapInsideChangedWriteKeepsExtentAndImage() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doThrow(new IOException("gap failure")).when(wrapper).write(Mockito.anyList());
    files.add(fileId, wrapper);
    try {
      assertThrows(Exception.class, () -> wowCache.flush(fileId));
      assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      final var cached = wowCache.loadIfPresent(fileId, 2, false);
      assertNotNull(cached);
      try {
        assertEquals(728, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        cached.decrementReadersReferrer();
      }
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
    }
  }

  // Pending blank work is covered only by a retained image at or above the requested page.
  @Test
  public void pendingBlanksRequireCoveringImageAndNeverTouchFiles() throws Exception {
    wowCache.pauseBackgroundFlush();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9190);
    final var name = PageFileNames.layout28(identity);
    final var fileId = wowCache.externalFileId(identity.internalId());
    wowCache.logicalFiles().register(name, identity.internalId(), false, 5, 0, false);
    final var token = wowCache.lifetimeToken(identity.internalId());
    final var file = mock(File.class);
    when(file.isOpen()).thenReturn(true);
    files.add(fileId, file);
    try {
      final var page = wowCache.loadOrAdd(fileId, 3, false);
      wowCache.store(fileId, 3, page);
      page.decrementReadersReferrer();
      wowCache.writeValidPageInFile(identity.internalId(), 2, token);
      wowCache.writeValidPageInFile(identity.internalId(), 3, token);
      Mockito.verify(file, Mockito.never()).write(Mockito.anyList());
      assertThrows(StorageException.class,
          () -> wowCache.writeValidPageInFile(identity.internalId(), 4, token));
      final var errorField = WOWCache.class.getDeclaredField("flushError");
      errorField.setAccessible(true);
      final var first = errorField.get(wowCache);
      assertNotNull("blank task has no waiter, so it must latch its failure", first);
      final var later = assertThrows(IOException.class,
          () -> wowCache.executeFileFlush(new IntOpenHashSet()));
      assertSame(first, later.getCause());
      Mockito.verify(file, Mockito.never()).create();
      Mockito.verify(file, Mockito.never()).write(Mockito.anyList());
      assertThrows(Exception.class, () -> wowCache.flush(fileId));
      assertThrows(Exception.class, () -> wowCache.flush());
    } finally {
      files.remove(fileId);
      wowCache.shrinkFile(fileId, 0);
      wowCache.logicalFiles().forget(identity.internalId());
    }
  }

  // Forgotten, deleted and replaced lifetimes cancel blank tasks without physical I/O.
  @Test
  public void obsoleteBlankLifetimesDoNotWrite() throws Exception {
    wowCache.pauseBackgroundFlush();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9191);
    final var name = PageFileNames.layout28(identity);
    for (int kind = 0; kind < 3; kind++) {
      wowCache.logicalFiles().register(name, identity.internalId(), false, 4, 0, false);
      final var token = wowCache.lifetimeToken(identity.internalId());
      wowCache.logicalFiles().forget(identity.internalId());
      if (kind == 1) {
        wowCache.logicalFiles().delete(name, identity.internalId());
      } else if (kind == 2) {
        wowCache.logicalFiles().register(name, identity.internalId(), false, 4, 0, false);
      }
      wowCache.writeValidPageInFile(identity.internalId(), 2, token);
      assertNull(files.get(wowCache.externalFileId(identity.internalId())));
    }
    wowCache.logicalFiles().forget(identity.internalId());
    wowCache.flush();
  }

  // Every changed-page entry path reports lost registration and retains its only changed image.
  @Test
  public void missingMaterializedRegistrationLatchesAcrossEntryPaths() throws Exception {
    for (int mode = 0; mode < 7; mode++) {
      final long fileId = prepareUnwrittenMaterializedPage();
      final var original = files.remove(fileId);
      final var errorField = WOWCache.class.getDeclaredField("flushError");
      errorField.setAccessible(true);
      final var notifications = new java.util.ArrayList<Throwable>();
      final BackgroundExceptionListener listener = failure -> {
        try {
          assertSame(failure, errorField.get(wowCache));
          notifications.add(failure);
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      };
      wowCache.addBackgroundExceptionListener(listener);
      final var healthy = new PageFileIdentity(PageFileRole.RECORD_DATA, 9123);
      final long healthyId = wowCache.addFile(PageFileNames.layout28(healthy),
          wowCache.externalFileId(healthy.internalId()));
      Exception reported = null;
      try {
        if (mode == 0) {
          reported = assertThrows(Exception.class, () -> wowCache.flush(fileId));
        } else if (mode == 1) {
          reported = assertThrows(Exception.class, () -> wowCache.flush());
        } else if (mode == 4) {
          reported = assertThrows(Exception.class, () -> wowCache.flushTillSegment(4));
        } else if (mode == 5) {
          final var nonDurable = WOWCache.class.getDeclaredField("nonDurableFileIds");
          nonDurable.setAccessible(true);
          nonDurable.set(wowCache, new IntOpenHashSet(new int[] {wowCache.internalFileId(fileId)}));
          reported = assertThrows(Exception.class, () -> wowCache.flush(fileId));
        } else if (mode == 6) {
          reported = assertThrows(Exception.class,
              () -> new EnsurePageIsValidInFileTask(wowCache.internalFileId(fileId), 0, wowCache)
                  .run());
        } else {
          final var paused = WOWCache.class.getDeclaredField("backgroundFlushPaused");
          paused.setAccessible(true);
          paused.set(wowCache, false);
          try {
            if (mode == 2) {
              onFlushExecutor(() -> wowCache.executePeriodicFlush(mock(PeriodicFlushTask.class)));
            } else {
              final var limit = WOWCache.class.getDeclaredField("exclusiveWriteCacheMaxSize");
              limit.setAccessible(true);
              limit.set(wowCache, 0);
              onFlushExecutor(() -> wowCache.executeFlush(null, null));
            }
          } finally {
            wowCache.pauseBackgroundFlush();
          }
        }
        assertNotNull(errorField.get(wowCache));
        final var first = (Throwable) errorField.get(wowCache);
        assertTrue(first instanceof PageFileFailure);
        assertTrue(first.getMessage(), first.getMessage().contains(
            Integer.toString(wowCache.internalFileId(fileId))));
        assertEquals(List.of(first), notifications);
        if (reported != null) {
          assertCause(reported, first);
        }
        assertCause(assertThrows(Exception.class, () -> wowCache.flush(healthyId)), first);
        final var latch = WOWCache.class.getDeclaredMethod("latchFlushError", Throwable.class);
        latch.setAccessible(true);
        latch.invoke(wowCache, new PageFileFailure("distinct later failure"));
        assertEquals(List.of(first), notifications);
        assertEquals(Long.valueOf(3), earliestRetainedSegment());
        assertThrows(Exception.class, () -> wowCache.flush());
        assertSame(first, errorField.get(wowCache));
        assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
        final var retained = wowCache.loadIfPresent(fileId, 2, false);
        assertNotNull(retained);
        retained.decrementReadersReferrer();
      } finally {
        wowCache.removeBackgroundExceptionListener(listener);
        errorField.set(wowCache, null);
        final var nonDurable = WOWCache.class.getDeclaredField("nonDurableFileIds");
        nonDurable.setAccessible(true);
        nonDurable.set(wowCache, new IntOpenHashSet());
        files.add(fileId, original);
        wowCache.deleteFile(healthyId);
        wowCache.shrinkFile(fileId, 0);
        wowCache.deleteFile(fileId);
      }
    }
  }

  // Missing backing data is fatal even if a materialized handle is still registered.
  @Test
  public void missingBackingFileBlankFailsWithoutReplacement() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    when(wrapper.exists()).thenReturn(false);
    files.add(fileId, wrapper);
    try {
      assertThrows(StorageException.class,
          () -> wowCache.writeValidPageInFile(wowCache.internalFileId(fileId), 0));
      Mockito.verify(wrapper, Mockito.never()).create();
      Mockito.verify(wrapper, Mockito.never()).write(Mockito.anyList());
      assertThrows(Exception.class, () -> wowCache.flush(fileId));
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
    }
  }

  // Fatal identity errors bypass non-durable retry and retain the original cached image.
  @Test
  public void nonDurableMissingBackingFileIsFatal() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var nonDurable = WOWCache.class.getDeclaredField("nonDurableFileIds");
    nonDurable.setAccessible(true);
    nonDurable.set(wowCache, new IntOpenHashSet(new int[] {wowCache.internalFileId(fileId)}));
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    when(wrapper.exists()).thenReturn(false);
    files.add(fileId, wrapper);
    try {
      assertThrows(Exception.class, () -> wowCache.flush(fileId));
      assertThrows(Exception.class, () -> wowCache.flush());
      final var cached = wowCache.loadIfPresent(fileId, 2, false);
      assertNotNull(cached);
      cached.decrementReadersReferrer();
      Mockito.verify(wrapper, Mockito.never()).create();
      Mockito.verify(wrapper, Mockito.never()).write(Mockito.anyList());
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
    }
  }

  // An out-of-EOF orphan ends layout 28 selection after a nonempty batch. The epilogue must
  // not write that batch or release its copy again, and the orphan must remain for later cleanup.
  @Test
  public void exclusiveFlushReleasesBatchOnceBeforeStoppingAtOrphan() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    wowCache.flush(fileId);
    cacheUnwrittenImage(fileId, 2, 901);
    wowCache.addOnlyWriters(fileId, 7);
    assertEquals(2, wowCache.getExclusiveWriteCachePagesSize());
    assertNull(wowCache.loadIfPresent(fileId, 7, false));

    final var original = files.remove(fileId);
    final var file = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    files.add(fileId, file);
    final var poolField = WOWCache.class.getDeclaredField("bufferPool");
    poolField.setAccessible(true);
    final var pool = mock(ByteBufferPool.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(bufferPool)));
    final var copies =
        new java.util.ArrayList<com.jetbrains.youtrackdb.internal.common.directmemory.Pointer>();
    final var released = java.util.Collections.newSetFromMap(
        new java.util.IdentityHashMap<
            com.jetbrains.youtrackdb.internal.common.directmemory.Pointer, Boolean>());
    Mockito.doAnswer(invocation -> {
      final var copy = bufferPool.acquireDirect(false,
          Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
      copies.add(copy);
      return copy;
    }).when(pool).acquireDirect(false, Intention.COPY_PAGE_DURING_EXCLUSIVE_PAGE_FLUSH);
    Mockito.doAnswer(invocation -> {
      final com.jetbrains.youtrackdb.internal.common.directmemory.Pointer copy =
          invocation.getArgument(0);
      // Reject a duplicate before returning it to the real pool, even in a failing regression.
      assertTrue("Copy released twice", released.add(copy));
      bufferPool.release(copy);
      return null;
    }).when(pool).release(any());
    poolField.set(wowCache, pool);
    try {
      flushWithSelector(fileId, 1);
      assertEquals(1, copies.size());
      assertEquals(1, released.size());
      assertTrue(released.contains(copies.getFirst()));
      Mockito.verify(pool).release(copies.getFirst());
      Mockito.verify(file).write(Mockito.anyList());
      assertNull(earliestRetainedSegment());
      assertEquals(1, wowCache.getExclusiveWriteCachePagesSize());
      assertEquals(1, wowCache.getExclusiveWritePagesCountForTest());
      assertMaterializedContents(fileId, 3, 2, 901);
      // A later cycle still leaves the orphan unmaterialized and submits no second write.
      flushWithSelector(fileId, 1);
      Mockito.verify(file).write(Mockito.anyList());
      assertEquals(1, copies.size());
      assertEquals(3L * PAGE_SIZE, original.getUnderlyingFileSize());
      assertEquals(1, wowCache.getExclusiveWriteCachePagesSize());
    } finally {
      poolField.set(wowCache, bufferPool);
      files.remove(fileId);
      files.add(fileId, original);
    }
  }

  // A failed selection restores copied pages and dirty requirements without retaining copy memory.
  @Test
  public void selectionFailureReleasesCopiesAndRestoresDirtyProtection() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      final int selected = selector;
      final long firstId = prepareUnwrittenMaterializedPage(9121);
      final long secondId = prepareUnwrittenMaterializedPage();
      // The exclusive selector visits encoded file IDs, not component IDs, in ascending order.
      final long healthyId = Math.min(firstId, secondId);
      final long missingId = Math.max(firstId, secondId);
      final var poolField = WOWCache.class.getDeclaredField("bufferPool");
      poolField.setAccessible(true);
      final var pool = mock(ByteBufferPool.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(bufferPool)));
      poolField.set(wowCache, pool);
      final var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
      dirtyField.setAccessible(true);
      @SuppressWarnings("unchecked")
      final var dirty = (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyField.get(wowCache);
      // Separate segments make the oldest-LSN selector copy A before inspecting B.
      dirty.put(new PageKey(wowCache.internalFileId(missingId), 2), new LogSequenceNumber(4, 10));
      final var original = files.remove(missingId);
      try {
        final var failure = assertThrows(InvocationTargetException.class,
            () -> {
              if (selected == 1) {
                flushWithSelector(healthyId, selected);
              } else {
                final var method = WOWCache.class.getDeclaredMethod(
                    "flushWriteCacheFromMinLSN", long.class, long.class, int.class);
                method.setAccessible(true);
                onFlushExecutor(() -> method.invoke(wowCache, 3L, 5L, 100));
              }
            });
        assertTrue("selector " + selector + ": " + failure.getCause(),
            failure.getCause() instanceof PageFileFailure);
        final var acquired = org.mockito.ArgumentCaptor.forClass(
            com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention.class);
        Mockito.verify(pool, Mockito.atLeastOnce()).acquireDirect(Mockito.eq(false),
            acquired.capture());
        final var copies = org.mockito.ArgumentCaptor.forClass(
            com.jetbrains.youtrackdb.internal.common.directmemory.Pointer.class);
        Mockito.verify(pool, Mockito.atLeastOnce()).release(copies.capture());
        assertEquals(acquired.getAllValues().size(), copies.getAllValues().size());
        assertEquals(new LogSequenceNumber(3, 10),
            dirty.get(new PageKey(wowCache.internalFileId(healthyId), 2)));
        final var trackerField = WOWCache.class.getDeclaredField("pageWriteTracker");
        trackerField.setAccessible(true);
        assertNull(((PageWriteTracker) trackerField.get(wowCache)).earliestNotWrittenLsn());
        assertEquals(Long.valueOf(3), earliestRetainedSegment());
        final var retained = wowCache.loadIfPresent(healthyId, 2, false);
        assertNotNull(retained);
        retained.decrementReadersReferrer();
      } finally {
        poolField.set(wowCache, bufferPool);
        files.add(missingId, original);
        final var error = WOWCache.class.getDeclaredField("flushError");
        error.setAccessible(true);
        error.set(wowCache, null);
        wowCache.deleteFile(healthyId);
        wowCache.deleteFile(missingId);
      }
    }
  }

  // Multiple segment passes must submit each pending image once and release all protection.
  @Test
  public void pendingMultiSegmentSelectionCopiesEachPageOnce() throws Exception {
    assertMultiSegmentSelectionCopiesEachPageOnce(true);
  }

  // Materialized files also have cached barriers above their published extent.
  @Test
  public void materializedMultiSegmentSelectionCopiesEachPageOnce() throws Exception {
    assertMultiSegmentSelectionCopiesEachPageOnce(false);
  }

  private void assertMultiSegmentSelectionCopiesEachPageOnce(boolean pending) throws Exception {
    final long id = pending ? preparePendingPage(9380, null)
        : prepareUnwrittenMaterializedPage(9381);
    cacheUnwrittenImage(id, 0, 100);
    cacheUnwrittenImage(id, 1, 101);
    final var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
    dirtyField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var dirty = (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyField.get(wowCache);
    // Segment 3 selects page 1 and pulls page 0 from newer segment 4 as a barrier.
    dirty.put(new PageKey(wowCache.internalFileId(id), 0), new LogSequenceNumber(4, 10));
    dirty.put(new PageKey(wowCache.internalFileId(id), 2), new LogSequenceNumber(4, 20));
    final var counts = new java.util.HashMap<Long, Integer>();
    final var poolField = WOWCache.class.getDeclaredField("bufferPool");
    poolField.setAccessible(true);
    final var pool = mock(ByteBufferPool.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(bufferPool)));
    poolField.set(wowCache, pool);
    final java.util.function.Function<File, File> counting = original -> {
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      try {
        Mockito.doAnswer(inv -> {
          final List<
              com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                  inv.getArgument(0);
          for (final var buffer : buffers) {
            for (long offset = buffer.first;
                offset < buffer.first + buffer.second.limit(); offset += PAGE_SIZE) {
              counts.merge(offset / PAGE_SIZE, 1, Integer::sum);
            }
          }
          return original.write(buffers);
        }).when(wrapper).write(Mockito.anyList());
      } catch (IOException failure) {
        throw new AssertionError(failure);
      }
      return wrapper;
    };
    final File original = pending ? null : files.remove(id);
    if (pending) {
      wowCache = Mockito.spy(wowCache);
      Mockito.doAnswer(inv -> counting.apply((File) inv.callRealMethod()))
          .when(wowCache).createFileHandle(any(), Mockito.anyInt());
    } else {
      files.add(id, counting.apply(original));
    }
    try {
      final var method = WOWCache.class.getDeclaredMethod(
          "flushWriteCacheFromMinLSN", long.class, long.class, int.class);
      method.setAccessible(true);
      onFlushExecutor(() -> method.invoke(wowCache, 3L, 5L, 100));
      assertEquals(Map.of(0L, 1, 1L, 1, 2L, 1), counts);
      Mockito.verify(pool, Mockito.times(3))
          .acquireDirect(false, Intention.COPY_PAGE_DURING_FLUSH);
      assertNull(earliestRetainedSegment());
      assertTrue(dirty.isEmpty());
      assertMaterializedContents(id, 3, Map.of(0, 100, 1, 101, 2, 728));
    } finally {
      poolField.set(wowCache, bufferPool);
      if (!pending) {
        files.remove(id);
        files.add(id, original);
      }
    }
  }

  // A dirty lower page without an image cannot become a blank gap under an upper changed page.
  @Test
  public void pendingMissingDirtyImageStopsMinLsnSelectionUntilAvailable() throws Exception {
    final long id = preparePendingPage(9382, null);
    final var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
    dirtyField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var dirty = (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyField.get(wowCache);
    final var lower = new PageKey(wowCache.internalFileId(id), 1);
    dirty.put(lower, new LogSequenceNumber(3, 5));
    final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
    dwlField.setAccessible(true);
    final var original = dwlField.get(wowCache);
    final var dwl = mock(DoubleWriteLog.class);
    dwlField.set(wowCache, dwl);
    try {
      flushWithSelector(id, 2);
      Mockito.verify(dwl, Mockito.never()).write(any(), any(), any());
      assertNull(files.get(id));
      assertFalse(Files.exists(pendingPath(id)));
      final var localField = WOWCache.class.getDeclaredField("localDirtyPages");
      localField.setAccessible(true);
      assertTrue(((Map<?, ?>) localField.get(wowCache)).containsKey(lower));
      assertEquals(Long.valueOf(3), earliestRetainedSegment());
      final var upper = wowCache.loadIfPresent(id, 2, false);
      assertNotNull(upper);
      upper.decrementReadersReferrer();
    } finally {
      dwlField.set(wowCache, original);
    }
    cacheUnwrittenImage(id, 1, 119);
    flushWithSelector(id, 2);
    assertMaterializedContents(id, 3, Map.of(1, 119, 2, 728));
    assertNull(earliestRetainedSegment());
  }

  // Each selector starts with no path or handle and completes checksummed gaps and data.
  @Test
  public void pendingBackgroundSelectorsMaterializeChangedPages() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      final long id = preparePendingPage(9300 + selector, null);
      flushWithSelector(id, selector);
      assertMaterializedContents(id, 3, 2, 728);
      assertNull(earliestRetainedSegment());
      wowCache.deleteFile(id);
    }
  }

  // A read-cache-owned lower page in a newer dirty segment must stop an older upper page.
  // With no handle, the failed-lock branch must still honor the published extent of zero.
  @Test(timeout = 30_000)
  public void pendingLockedNewerBarrierStopsBothSelectors() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      final long id = preparePendingPage(9310 + selector, null);
      cacheUnwrittenImage(id, 1, 119);
      final var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
      dirtyField.setAccessible(true);
      @SuppressWarnings("unchecked")
      final var dirty = (ConcurrentHashMap<PageKey, LogSequenceNumber>) dirtyField.get(wowCache);
      dirty.put(new PageKey(wowCache.internalFileId(id), 1), new LogSequenceNumber(4, 10));
      final var lower = wowCache.loadIfPresent(id, 1, false);
      final long stamp = lower.acquireExclusiveLock();
      final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
      dwlField.setAccessible(true);
      final var original = dwlField.get(wowCache);
      final var dwl = mock(DoubleWriteLog.class);
      dwlField.set(wowCache, dwl);
      try {
        flushWithSelector(id, selector);
        Mockito.verify(dwl, Mockito.never()).write(any(), any(), any());
        assertNull(files.get(id));
        assertFalse(Files.exists(pendingPath(id)));
        assertEquals(Long.valueOf(3), earliestRetainedSegment());
      } finally {
        dwlField.set(wowCache, original);
        lower.releaseExclusiveLock(stamp);
        lower.decrementReadersReferrer();
      }
      flushWithSelector(id, selector);
      assertMaterializedContents(id, 3, Map.of(1, 119, 2, 728));
      assertNull(earliestRetainedSegment());
      wowCache.deleteFile(id);
    }
  }

  // A locked page with a completed disk copy stays cached but does not stop a higher write.
  @Test
  public void lockedBelowPublishedExtentAllowsBothSelectorsToProgress() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      final long id = preparePendingPage(9320 + selector, null);
      flushWithSelector(id, selector);
      cacheUnwrittenImage(id, 1, 119);
      cacheUnwrittenImage(id, 4, 901);
      final var lower = wowCache.loadIfPresent(id, 1, false);
      final long stamp = lower.acquireExclusiveLock();
      try {
        flushWithSelector(id, selector);
        assertEquals(5, wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskExtent());
        final var retained = wowCache.loadIfPresent(id, 1, false);
        assertNotNull(retained);
        retained.decrementReadersReferrer();
        assertEquals(Long.valueOf(3), earliestRetainedSegment());
      } finally {
        lower.releaseExclusiveLock(stamp);
        lower.decrementReadersReferrer();
      }
      flushWithSelector(id, selector);
      assertMaterializedContents(id, 5, Map.of(1, 119, 2, 728, 4, 901));
      wowCache.deleteFile(id);
    }
  }

  // An orphan between pending images stops one exclusive cycle without skipping the
  // null-handle barrier, resubmitting released copies, or spinning inside the selector.
  @Test(timeout = 10_000)
  public void pendingOrphanStopsExclusiveSelectionAfterOnePrefixWrite() throws Exception {
    final long id = preparePendingPage(9330, null);
    wowCache.addOnlyWriters(id, 3);
    cacheUnwrittenImage(id, 4, 901);
    wowCache = Mockito.spy(wowCache);
    final var handle = new java.util.concurrent.atomic.AtomicReference<File>();
    Mockito.doAnswer(inv -> {
      final var file = Mockito.spy((File) inv.callRealMethod());
      handle.set(file);
      return file;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    flushWithSelector(id, 1);
    assertMaterializedContents(id, 3, 2, 728);
    Mockito.verify(handle.get(), Mockito.times(3)).write(Mockito.anyList());
    final var upper = wowCache.loadIfPresent(id, 4, false);
    assertNotNull(upper);
    upper.decrementReadersReferrer();
    flushWithSelector(id, 1);
    Mockito.verify(handle.get(), Mockito.times(3)).write(Mockito.anyList());
    assertEquals(Long.valueOf(3), earliestRetainedSegment());
  }

  // A fatal second-file classification drains started data I/O without confirming either image.
  @Test
  public void fatalMultiFileFlushRetainsBothImagesAndRecoveryProtection() throws Exception {
    final long firstId = prepareUnwrittenMaterializedPage(9121);
    final long secondId = prepareUnwrittenMaterializedPage();
    wowCache.flush();
    cacheUnwrittenImage(firstId, 2, 101);
    cacheUnwrittenImage(secondId, 2, 202);
    final var chosen = new java.util.concurrent.atomic.AtomicReference<File>();
    final var drained = new java.util.concurrent.atomic.AtomicBoolean();
    final var originals = new java.util.HashMap<Long, File>();
    for (final long fileId : new long[] {firstId, secondId}) {
      final var original = files.remove(fileId);
      originals.put(fileId, original);
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(invocation -> chosen.get() == null || chosen.get() == wrapper)
          .when(wrapper).exists();
      Mockito.doAnswer(invocation -> {
        assertTrue(chosen.compareAndSet(null, wrapper));
        final var result = original.write(invocation.getArgument(0));
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          result.await();
          drained.set(true);
        };
      }).when(wrapper).write(Mockito.anyList());
      files.add(fileId, wrapper);
    }
    try {
      assertThrows(Exception.class, () -> wowCache.flush());
      assertNotNull(chosen.get());
      assertTrue("Started I/O must drain before the fatal future completes", drained.get());
      assertEquals(Long.valueOf(3), earliestRetainedSegment());
      for (final long fileId : new long[] {firstId, secondId}) {
        final var retained = wowCache.loadIfPresent(fileId, 2, false);
        assertNotNull(retained);
        assertEquals(fileId == firstId ? 101 : 202,
            retained.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
        retained.decrementReadersReferrer();
      }
    } finally {
      for (final var entry : originals.entrySet()) {
        files.remove(entry.getKey());
        files.add(entry.getKey(), entry.getValue());
      }
    }
  }

  // A listener failure cannot hide fatal non-durable I/O or prevent later listener delivery.
  @Test
  public void throwingListenerCannotTurnFatalNonDurableFlushIntoSuccess() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    for (int page = 3; page < 8; page++) {
      cacheUnwrittenImage(fileId, page, page);
    }
    final var chunkLimit = WOWCache.class.getDeclaredField("chunkSize");
    chunkLimit.setAccessible(true);
    chunkLimit.set(wowCache, 1);
    final var nonDurable = WOWCache.class.getDeclaredField("nonDurableFileIds");
    nonDurable.setAccessible(true);
    nonDurable.set(wowCache, new IntOpenHashSet(new int[] {wowCache.internalFileId(fileId)}));
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    when(wrapper.exists()).thenReturn(false);
    files.add(fileId, wrapper);
    final var reported = new java.util.ArrayList<Throwable>();
    final BackgroundExceptionListener throwing = failure -> {
      throw new IllegalStateException("listener fault");
    };
    final BackgroundExceptionListener observer = reported::add;
    wowCache.addBackgroundExceptionListener(throwing);
    wowCache.addBackgroundExceptionListener(observer);
    try {
      final var failure = assertThrows(Exception.class, () -> wowCache.flush(fileId));
      assertEquals(1, reported.size());
      assertTrue(reported.getFirst() instanceof PageFileFailure);
      assertCause(failure, reported.getFirst());
      assertCause(assertThrows(Exception.class, () -> wowCache.flush(fileId)), reported.getFirst());
      assertEquals(Long.valueOf(3), earliestRetainedSegment());
      Mockito.verify(wrapper).exists();
      Mockito.verify(wrapper, Mockito.never()).write(Mockito.anyList());
      Mockito.verify(wrapper, Mockito.never()).create();
    } finally {
      files.remove(fileId);
      wowCache.removeBackgroundExceptionListener(throwing);
      wowCache.removeBackgroundExceptionListener(observer);
      files.add(fileId, original);
    }
  }

  // An ordinary non-durable data error retains the image without latching and permits retry.
  @Test
  public void ordinaryNonDurableWriteFailureRetriesExpectedBytes() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var nonDurable = WOWCache.class.getDeclaredField("nonDurableFileIds");
    nonDurable.setAccessible(true);
    nonDurable.set(wowCache, new IntOpenHashSet(new int[] {wowCache.internalFileId(fileId)}));
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doThrow(new IOException("ordinary write fault")).when(wrapper).write(Mockito.anyList());
    files.add(fileId, wrapper);
    try {
      wowCache.flush(fileId);
      final var error = WOWCache.class.getDeclaredField("flushError");
      error.setAccessible(true);
      assertNull(error.get(wowCache));
      final var retained = wowCache.loadIfPresent(fileId, 2, false);
      assertNotNull(retained);
      assertEquals(728, retained.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      retained.decrementReadersReferrer();
      Mockito.doAnswer(invocation -> original.write(invocation.getArgument(0)))
          .when(wrapper).write(Mockito.anyList());
      wowCache.flush(fileId);
      assertMaterializedContents(fileId, 3, 2, 728);
      assertNull(error.get(wowCache));
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
    }
  }

  // Ordinary first-data failure leaves a sink-owned handle unpublished. Selection must use
  // extent zero, retain a locked lower barrier, then retry without a second creation attempt.
  @Test
  public void backgroundSelectorsRetryOwnedUnpublishedHandle() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      final long id = preparePendingPage(9340 + selector, null);
      if (!Mockito.mockingDetails(wowCache).isSpy()) {
        wowCache = Mockito.spy(wowCache);
      }
      final var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
      final var creates = new java.util.concurrent.atomic.AtomicInteger();
      Mockito.doAnswer(inv -> {
        creates.incrementAndGet();
        final File original = (File) inv.callRealMethod();
        final var wrapper = mock(File.class, Mockito.withSettings()
            .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
        Mockito.doAnswer(write -> {
          final List<
              com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                  write.getArgument(0);
          if (buffers.getFirst().first == 2L * PAGE_SIZE && fail.getAndSet(false)) {
            throw new IOException("retryable first data failure");
          }
          return original.write(buffers);
        }).when(wrapper).write(Mockito.anyList());
        return wrapper;
      }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
      assertThrows(InvocationTargetException.class, () -> flushWithSelector(id, 1));
      assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskPresent());
      assertNotNull(files.get(id));
      assertEquals(Long.valueOf(3), earliestRetainedSegment());
      // A new commit makes this image dirty again while its first-write handle is unpublished.
      // The segment selector selects dirty entries, not the failed-attempt tracker by itself.
      cacheUnwrittenImage(id, 2, 728);
      cacheUnwrittenImage(id, 1, 119);
      final var lower = wowCache.loadIfPresent(id, 1, false);
      final long stamp = lower.acquireExclusiveLock();
      try {
        flushWithSelector(id, selector);
        assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskExtent());
      } finally {
        lower.releaseExclusiveLock(stamp);
        lower.decrementReadersReferrer();
      }
      flushWithSelector(id, selector);
      assertEquals(1, creates.get());
      assertMaterializedContents(id, 3, Map.of(1, 119, 2, 728));
      wowCache.deleteFile(id);
    }
  }

  // A failed first write can leave physical bytes without publishing a logical disk extent.
  // Shrink must keep the DWL copy on a failed truncate and discard it only after success.
  @Test
  public void shrinkOfUnpublishedHandleProtectsDwlUntilPhysicalTruncation() throws Exception {
    final long id = prepareUnpublishedHandleWithRetainedTail();
    final var file = files.get(id);
    final var retention = pageRetentionState();
    Mockito.doThrow(new IOException("Truncate failed")).doCallRealMethod()
        .when(file).shrink(PAGE_SIZE);
    assertThrows(IOException.class, () -> wowCache.shrinkFile(id, PAGE_SIZE));
    assertTrue(retention.hasRetainedContents(id));
    assertTrue(wowCache.shrinkFile(id, PAGE_SIZE));
    assertEquals(PAGE_SIZE, file.getUnderlyingFileSize());
    assertFalse(retention.hasRetainedContents(id));
  }

  @Test
  public void shrinkRetryRejectsUnchangedPhysicalLengthAfterSizeCounterDrops() throws Exception {
    final long id = prepareUnpublishedHandleWithRetainedTail();
    final var file = files.get(id);
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));
    final var retention = pageRetentionState();
    final var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    final var channelField = AsyncFile.class.getDeclaredField("fileChannel");
    channelField.setAccessible(true);
    final var realChannel = (AsynchronousFileChannel) channelField.get(file);
    final var channel = mock(AsynchronousFileChannel.class,
        org.mockito.AdditionalAnswers.delegatesTo(realChannel));
    final var truncateFailure = new IOException("Channel truncate failed");
    // Fail at the channel boundary so the real shrink lowers the size counter first.
    Mockito.doThrow(truncateFailure).when(channel).truncate(Mockito.anyLong());

    try {
      final var tail = readCache.loadForRead(id, 2, wowCache, false);
      assertNotNull(tail);
      readCache.releaseFromRead(tail);
      assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));
      channelField.set(file, channel);

      assertSame(truncateFailure,
          assertThrows(IOException.class, () -> readCache.shrinkFile(id, PAGE_SIZE, wowCache)));
      assertEquals(PAGE_SIZE, file.getFileSize());
      assertEquals(3L * PAGE_SIZE, file.getUnderlyingFileSize());
      assertEquals(3, state.logicalExtent());
      assertTrue(retention.hasRetainedContents(id));

      assertThrows("Retry must reject the physical tail", IOException.class,
          () -> readCache.shrinkFile(id, PAGE_SIZE, wowCache));
      Mockito.verify(channel).truncate(Mockito.anyLong());
      assertEquals(3L * PAGE_SIZE, file.getUnderlyingFileSize());
      assertEquals(3, state.logicalExtent());
      assertTrue(retention.hasRetainedContents(id));
      assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));
    } finally {
      channelField.set(file, realChannel);
      readCache.clear();
    }
  }

  @Rule
  public final TestRule orderedShrinkTimeout =
      (base, description) -> description.getMethodName().startsWith("orderedShrink")
          ? Timeout.seconds(90).apply(base, description) : base;

  @Rule
  public final TestRule orderedDeleteTimeout =
      (base, description) -> description.getMethodName().startsWith("orderedDelete")
          || description.getMethodName()
              .equals("legacyDeleteInterruptedCallerReturnsBeforeStepEnds")
                  ? Timeout.seconds(90).apply(base, description) : base;

  @Rule
  public final TestRule orderedRegistrationTimeout = (base, description) -> {
    final var name = description.getMethodName();
    return name.startsWith("orderedRegistration")
        || name.startsWith("orderedDirectCreation")
        || name.startsWith("orderedCreatingForms")
        || name.startsWith("orderedClose")
        || name.startsWith("pendingFixtureShutdown")
        || name.startsWith("pendingFixtureBackgroundAdmission")
        || name.equals("pendingReservationFailureIsFatalAfterAdmission")
        || name.equals("pendingBlankInitializationFailureIsFatalAfterAdmission")
        || name.equals("pendingNonDurableBackgroundFailureRetriesOwnedHandle")
            ? Timeout.seconds(90).apply(base, description) : base;
  };

  @Test
  public void orderedCloseAfterCreationClosesHandleAndPreservesPagesInPendingFixture()
      throws Exception {
    int component = 9460;
    for (boolean flush : List.of(false, true)) {
      for (boolean sync : List.of(false, true)) {
        setCacheField("callFsync", sync);
        final long id = preparePendingPage(component++, null);
        final var probe = new CloseProbe(id);
        final var gate = new ShrinkGate();
        final var submitted = witnessCloseSubmission();
        try (var observation = wowCache.observePhysicalCreation(created -> {
          probe.created(created);
          gate.block();
        })) {
          final var tasks = new RegistrationTasks<Void>(gate);
          try (tasks) {
            tasks.predecessor = submitTestFileFlush(id);
            awaitShrinkGate(gate.entered);
            tasks.caller = asyncFileExecutor.submit(() -> {
              wowCache.close(id, flush);
              return null;
            });
            awaitShrinkGate(submitted);
            assertFalse("Close must wait for first creation", tasks.caller.isDone());
            assertNull(files.get(id));
          }
          probe.assertClosed();
          assertClosedPageBytes(id, 728);
          cacheUnwrittenImage(id, 2, 719);
          wowCache.flush(id);
          assertSame(probe.handle, files.get(id));
          assertTrue(probe.handle.isOpen());
          assertClosedPageBytes(id, 719);
          assertEquals(1, probe.creations);
        } finally {
          wowCache.deleteFile(id);
        }
      }
    }
  }

  @Test
  public void orderedCloseBeforeQueuedFlushWritesOrDiscardsPagesInPendingFixture()
      throws Exception {
    int component = 9470;
    for (boolean flush : List.of(false, true)) {
      for (boolean sync : List.of(false, true)) {
        setCacheField("callFsync", sync);
        final long id = preparePendingPage(component++, null);
        final var probe = new CloseProbe(id);
        final var gate = new ShrinkGate();
        final var submitted = witnessCloseSubmission();
        try (var observation = wowCache.observePhysicalCreation(probe::created)) {
          final var tasks = new RegistrationTasks<Void>(gate);
          try (tasks) {
            tasks.predecessor = flushExecutor().submit(gate::block);
            awaitShrinkGate(gate.entered);
            tasks.caller = asyncFileExecutor.submit(() -> {
              wowCache.close(id, flush);
              return null;
            });
            awaitShrinkGate(submitted);
            final var later = flushExecutor().submit(() -> {
              if (flush) {
                probe.assertClosed();
                assertClosedPageBytes(id, 728);
              } else {
                assertAbsentPendingClose(id, probe);
              }
              return new FileFlushTask(wowCache, Set.of(wowCache.internalFileId(id))).call();
            });
            gate.release.countDown();
            later.get(10, TimeUnit.SECONDS);
          }
          wowCache.flush(id);
          wowCache.flush();
          if (flush) {
            assertEquals(1, probe.creations);
            assertSame(probe.handle, files.get(id));
            // Explicit flush may reopen an existing handle for fsync.
            assertEquals(sync, probe.handle.isOpen());
            assertEquals(sync ? 1 : 0, files.openFilesCount());
            assertClosedPageBytes(id, 728);
          } else {
            assertAbsentPendingClose(id, probe);
            cacheUnwrittenImage(id, 2, 719);
            wowCache.flush(id);
            assertEquals(1, probe.creations);
            assertSame(probe.handle, files.get(id));
            assertTrue(probe.handle.isOpen());
            assertClosedPageBytes(id, 719);
          }
          assertNull(cacheField("flushError"));
        } finally {
          wowCache.deleteFile(id);
        }
      }
    }
  }

  @Test
  public void orderedCloseWithNoPagesOrWithWriterHeldPageLeavesPendingFixture()
      throws Exception {
    for (boolean held : List.of(false, true)) {
      final long id = held ? preparePendingPage(9481, null) : registerPendingFixture(9480, null);
      final var probe = new CloseProbe(id);
      final var pointer = held ? wowCache.loadIfPresent(id, 2, false) : null;
      try (var observation = wowCache.observePhysicalCreation(probe::created)) {
        final long writeStamp = held ? pointer.acquireExclusiveLock() : 0;
        try {
          wowCache.close(id, true);
          assertAbsentPendingClose(id, probe);
        } finally {
          if (held) {
            pointer.releaseExclusiveLock(writeStamp);
            pointer.decrementReadersReferrer();
          }
        }
        if (held) {
          wowCache.flush(id);
          assertEquals(1, probe.creations);
          assertTrue(probe.handle.isOpen());
          assertClosedPageBytes(id, 728);
        }
      } finally {
        wowCache.deleteFile(id);
      }
    }
  }

  @Test
  public void orderedCloseRetryableWriteLeavesPageForLaterFlushInPendingFixture() throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9496);
    final var probe = new CloseProbe(id);
    failFirstChangedPageWrite(probe);
    try (var observation = wowCache.observePhysicalCreation(probe::created)) {
      wowCache.close(id, true);
      probe.assertClosed();
      assertPendingExtents(id);
      assertNull(cacheField("flushError"));

      wowCache.flush(id);
      assertClosedPageBytes(id, 728);
      assertSame(probe.handle, files.get(id));
      assertTrue(probe.handle.isOpen());
      assertEquals(1, files.openFilesCount());
      assertEquals(1, probe.creations);
      assertNull(cacheField("flushError"));
    }
  }

  @Test
  public void orderedCloseKeepsUnpublishedHandleForAllRecoveryFormsAndFlushInPendingFixture()
      throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9482);
    final var probe = new CloseProbe(id);
    failFirstChangedPageWrite(probe);
    try (var observation = wowCache.observePhysicalCreation(probe::created)) {
      // The first close attempts the write, and the retryable failure leaves work for later.
      wowCache.close(id, true);
      probe.assertClosed();
      assertPendingExtents(id);
      assertNull(cacheField("flushError"));
      final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));
      final var original = probe.handle;

      // Close without a flush must close the real channel and leave the unpublished owner.
      final var acquired = files.acquire(id);
      assertSame(original, acquired.get());
      files.release(acquired);
      probe.closeThread = null;
      wowCache.close(id, false);
      probe.assertClosed();
      assertPendingExtents(id);
      assertNull(wowCache.loadIfPresent(id, 2, false));
      assertSame(state.extents(), pendingOwners().get(wowCache.internalFileId(id)));
      final var bytes = Files.readAllBytes(pendingPath(id));
      assertArrayEquals(new byte[File.HEADER_SIZE],
          java.util.Arrays.copyOf(bytes, File.HEADER_SIZE));
      for (var permission : registrationModes()) {
        assertTrue(registerRecoveryMode(id, permission));
        assertSame(original, files.get(id));
        assertFalse(original.isOpen());
        assertArrayEquals(bytes, Files.readAllBytes(pendingPath(id)));
        assertPendingExtents(id);
      }
      cacheUnwrittenImage(id, 2, 719);
      wowCache.flush(id);
      assertSame(original, files.get(id));
      assertTrue(original.isOpen());
      assertEquals(1, probe.creations);
      assertClosedPageBytes(id, 719);
    }
  }

  private static void failFirstChangedPageWrite(CloseProbe probe) {
    final var retry = new AtomicBoolean(true);
    probe.onHandle = handle -> Mockito.doAnswer(call -> {
      final List<
          com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
              call.getArgument(0);
      if (buffers.getFirst().first == 2L * PAGE_SIZE && retry.getAndSet(false)) {
        throw new IOException("Retryable non-durable write failure");
      }
      return call.callRealMethod();
    }).when(handle).write(Mockito.anyList());
  }

  @Test
  public void orderedCloseRunsBodyAndHandleCloseInOneTask() throws Exception {
    final var taskGaps = new ArrayList<Long>();
    final var executor = (ScheduledThreadPoolExecutor) flushExecutor();
    for (boolean flush : List.of(false, true)) {
      // Materialize the pending fixture so both close modes reach a real channel.
      final long id = preparePendingPage(flush ? 9498 : 9497, null);
      final var probe = new CloseProbe(id);
      try (var observation = wowCache.observePhysicalCreation(probe::created)) {
        wowCache.flush(id);
        cacheUnwrittenImage(id, 2, 719);
        final var bodyTask = new AtomicLong(-1);
        final Answer<Object> recordBody = call -> {
          assertSame("Close work must run on the commitExecutor() thread",
              probe.commitThread, Thread.currentThread());
          bodyTask.set(executor.getCompletedTaskCount());
          return call.callRealMethod();
        };
        final int internal = wowCache.internalFileId(id);
        if (flush) {
          final var ids = new IntOpenHashSet(new int[] {internal});
          Mockito.doAnswer(recordBody).when(wowCache).executeFileFlush(eq(ids));
        } else {
          Mockito.doAnswer(recordBody).when(wowCache).doRemoveCachePages(internal, 0);
        }
        Mockito.doAnswer(call -> {
          probe.closeThread = Thread.currentThread();
          // On this single worker the count advances only between tasks.
          taskGaps.add(executor.getCompletedTaskCount() - bodyTask.get());
          return call.callRealMethod();
        }).when(probe.handle).close();
        wowCache.close(id, flush);
        probe.assertClosed();
        assertClosedPageBytes(id, flush ? 719 : 728);
        wowCache.deleteFile(id);
      }
    }
    assertEquals("Discard and flush must each close the handle in the same task",
        List.of(0L, 0L), taskGaps);
  }

  @Test
  public void orderedCloseRejectsCommitExecutorThreadBeforeTakingFilesLock() throws Exception {
    // The pending fixture supplies a live file without opening a channel.
    final long id = preparePendingPage(9483, null);
    final var lock = (ReadersWriterSpinLock) cacheField("filesLock");
    lock.acquireWriteLock();
    try {
      onFlushExecutor(() -> assertThrows(IllegalStateException.class,
          () -> wowCache.close(id, true)));
    } finally {
      lock.releaseWriteLock();
    }
    assertNull(files.get(id));
    assertFalse(Files.exists(pendingPath(id)));
    assertPendingExtents(id);
  }

  @Test
  public void orderedCloseKeepsLockAcrossInterruptAndReportsStepFailureFirstInPendingFixture()
      throws Exception {
    int component = 9484;
    for (var result : List.of(ThreadInterruptedException.class, StorageException.class,
        WriteCacheException.class, AssertionError.class)) {
      final long id = preparePendingPage(component++, null);
      final var probe = new CloseProbe(id);
      final var flushFailure = new IOException("File flush failed");
      final var error = new AssertionError("File flush invariant");
      try (var observation = wowCache.observePhysicalCreation(probe::created)) {
        wowCache.flush(id);
        final boolean refuse = result == StorageException.class;
        final var entry = refuse ? files.acquire(id) : null;
        final var gate = new ShrinkGate();
        final var retrying = new CountDownLatch(1);
        final var submitted = witnessCloseSubmission(retrying);
        final var callerThread = new AtomicReference<Thread>();
        final var tasks = new RegistrationTasks<Throwable>(gate);
        try {
          if (result == WriteCacheException.class || result == AssertionError.class) {
            final var ids = new IntOpenHashSet(new int[] {wowCache.internalFileId(id)});
            Mockito.doThrow(result == AssertionError.class ? error : flushFailure)
                .when(wowCache).executeFileFlush(eq(ids));
          }
          try (tasks) {
            tasks.predecessor = flushExecutor().submit(gate::block);
            awaitShrinkGate(gate.entered);
            tasks.caller = asyncFileExecutor.submit(() -> {
              callerThread.set(Thread.currentThread());
              try {
                final var type = result == AssertionError.class ? WriteCacheException.class
                    : result;
                final var failure = assertThrows(type, () -> wowCache.close(id, !refuse));
                assertTrue("Close must restore interruption",
                    Thread.currentThread().isInterrupted());
                return failure;
              } finally {
                Thread.interrupted();
              }
            });
            awaitShrinkGate(submitted);
            callerThread.get().interrupt();
            assertTrue("Close must retry the wait after consuming the interrupt",
                retrying.await(5, TimeUnit.SECONDS));
            final var lock = (ReadersWriterSpinLock) cacheField("filesLock");
            final boolean acquired = lock.tryAcquireReadLock(TimeUnit.MILLISECONDS.toNanos(100));
            if (acquired) {
              lock.releaseReadLock();
            }
            assertFalse("An accepted close must retain the write lock", acquired);
            assertFalse(tasks.caller.isDone());
          }
          if (result == ThreadInterruptedException.class) {
            probe.assertClosed();
            assertTrue(tasks.result.getCause() instanceof InterruptedException);
          } else {
            assertTrue(probe.handle.isOpen());
            assertEquals(1, tasks.result.getSuppressed().length);
            assertTrue(tasks.result.getSuppressed()[0] instanceof ThreadInterruptedException);
            if (result == WriteCacheException.class) {
              assertCause(tasks.result, flushFailure);
            } else if (result == AssertionError.class) {
              assertCause(tasks.result, error);
            } else {
              assertTrue(tasks.result.getMessage().contains("because it is still in use"));
            }
          }
          assertClosedPageBytes(id, 728);
        } finally {
          if (entry != null) {
            files.release(entry);
          }
          wowCache.deleteFile(id);
        }
      }
    }
  }

  @Test
  public void orderedCloseReportsExistingFileRefusalAndFlushFailureWithoutClosingHandle()
      throws Exception {
    // The pending fixture file becomes an existing file before either close attempt.
    final long id = preparePendingPage(9488, null);
    final var probe = new CloseProbe(id);
    try (var observation = wowCache.observePhysicalCreation(probe::created)) {
      wowCache.flush(id);
      cacheUnwrittenImage(id, 2, 719);
      final var entry = files.acquire(id);
      try {
        final var refused = assertThrows(StorageException.class, () -> wowCache.close(id, true));
        assertTrue(refused.getMessage().contains("because it is still in use"));
        assertTrue(probe.handle.isOpen());
        assertClosedPageBytes(id, 719);
      } finally {
        files.release(entry);
      }
      final var ids = new IntOpenHashSet(new int[] {wowCache.internalFileId(id)});
      final var io = new IOException("File flush failed");
      Mockito.doThrow(io).doCallRealMethod().when(wowCache).executeFileFlush(eq(ids));
      final var failed = assertThrows(WriteCacheException.class, () -> wowCache.close(id, true));
      assertCause(failed, io);
      assertTrue(probe.handle.isOpen());
      assertEquals(1, files.openFilesCount());
      wowCache.close(id, true);
      probe.assertClosed();
      assertClosedPageBytes(id, 719);
    }
  }

  @Test
  public void orderedCloseWrapsFlushBodyErrorWithoutClosingHandle() throws Exception {
    assertOrderedCloseWrapsBodyError(true);
  }

  @Test
  public void orderedCloseWrapsDiscardBodyErrorWithoutClosingHandle() throws Exception {
    assertOrderedCloseWrapsBodyError(false);
  }

  private void assertOrderedCloseWrapsBodyError(boolean flush) throws Exception {
    // Materialize the pending fixture file before injecting a failure in the close step.
    final long id = preparePendingPage(9494, null);
    final var probe = new CloseProbe(id);
    final var error = new LinkageError("Close body failed");
    try (var observation = wowCache.observePhysicalCreation(probe::created)) {
      wowCache.flush(id);
      final int internal = wowCache.internalFileId(id);
      if (flush) {
        final var ids = new IntOpenHashSet(new int[] {internal});
        Mockito.doThrow(error).when(wowCache).executeFileFlush(eq(ids));
      } else {
        Mockito.doThrow(error).doCallRealMethod().when(wowCache).doRemoveCachePages(internal, 0);
      }
      assertCause(assertThrows(WriteCacheException.class, () -> wowCache.close(id, flush)), error);
      assertSame(probe.handle, files.get(id));
      assertTrue(probe.handle.isOpen());
      assertNull(probe.closeThread);
      assertEquals(1, files.openFilesCount());
      assertClosedPageBytes(id, 728);
    }
  }

  @Test
  public void orderedClosePropagatesSameErrorFromHandleClose() throws Exception {
    // Materialize the pending fixture file so the close reaches an open channel.
    final long id = preparePendingPage(9495, null);
    final var probe = new CloseProbe(id);
    final var error = new LinkageError("Channel close failed");
    try (var observation = wowCache.observePhysicalCreation(probe::created)) {
      wowCache.flush(id);
      Mockito.doThrow(error).doCallRealMethod().when(probe.handle).close();
      assertSame(error, assertThrows(LinkageError.class, () -> wowCache.close(id, true)));
      assertTrue(probe.handle.isOpen());
      assertClosedPageBytes(id, 728);
    }
  }

  @Test
  public void orderedCloseLeavesLayout27HandleCloseOnCallerThread() throws Exception {
    final long id = wowCache.addFile(AUX, 123456);
    files = Mockito.spy(files);
    setCacheField("files", files);
    final var caller = Thread.currentThread();
    final var file = files.get(id);
    Mockito.doAnswer(call -> {
      assertSame(caller, Thread.currentThread());
      return call.callRealMethod();
    }).when(files).close(id);
    wowCache.close(id, false);
    Mockito.verify(files).close(id);
    assertSame(file, files.get(id));
    assertFalse(file.isOpen());
    assertEquals(0, files.openFilesCount());
  }

  @Test
  public void pendingFixtureShutdownWholeCloseWritesAllPagesBeforeLaterFlush() throws Exception {
    assertWholeCloseOrder(true);
  }

  @Test
  public void pendingFixtureShutdownWholeCloseWaitsForPriorPhysicalCreation() throws Exception {
    assertWholeCloseOrder(false);
  }

  private void assertWholeCloseOrder(boolean closeFirst) throws Exception {
    setCacheField("callFsync", true);
    final long first = preparePendingPage(9490, null);
    final long second = preparePendingPage(9491, null);
    cacheUnwrittenImage(second, 2, 719);
    final var firstProbe = new CloseProbe(first);
    final var secondProbe = new CloseProbe(second);
    final int firstInternal = wowCache.internalFileId(first);
    final var gate = new ShrinkGate();
    final var caller = new AtomicReference<Thread>();
    final var tasks = new RegistrationTasks<long[]>(gate);
    final var cache = wowCache;
    try (var observation = cache.observePhysicalCreation(internal -> {
      if (internal == firstInternal) {
        firstProbe.created(internal);
        gate.block();
      } else {
        secondProbe.created(internal);
      }
    })) {
      try (tasks) {
        if (!closeFirst) {
          tasks.predecessor = submitTestFileFlush(first);
          awaitShrinkGate(gate.entered);
        }
        tasks.caller = asyncFileExecutor.submit(() -> {
          caller.set(Thread.currentThread());
          return cache.close();
        });
        if (closeFirst) {
          awaitShrinkGate(gate.entered);
          tasks.predecessor = flushExecutor().submit(() -> {
            final var state = cache.logicalFiles().live(firstInternal);
            assertTrue("Whole-cache close must publish before the queued flush",
                state.diskPresent());
            assertEquals(3, state.diskExtent());
            assertRawClosePages(first, 728);
            return new FileFlushTask(cache, Set.of(firstInternal)).call();
          });
        } else {
          awaitShutdownWait(caller, "close");
        }
        assertFalse("Whole-cache close must wait for physical creation", tasks.caller.isDone());
      }
      assertEquals(Set.of(first, second),
          java.util.Arrays.stream(tasks.result).boxed()
              .collect(java.util.stream.Collectors.toSet()));
      assertEquals(0, files.openFilesCount());
      assertEquals(2, tasks.result.length);
      assertRegistrationPathCount(2);
      for (final var probe : List.of(firstProbe, secondProbe)) {
        assertEquals(1, probe.creations);
        assertNull(files.get(probe.id));
        assertFalse(probe.handle.isOpen());
        assertSame(caller.get(), probe.closeThread);
        assertNull(cache.fileNameById(probe.id));
      }
      assertEquals(true, cacheField("closed"));
      assertRawClosePages(first, 728);
      assertRawClosePages(second, 719);
    } finally {
      // The fixture must not call delete on a cache that completed close.
      if (Boolean.TRUE.equals(cacheField("closed"))) {
        wowCache = null;
        for (final var probe : List.of(firstProbe, secondProbe)) {
          if (probe.handle != null && probe.handle.isOpen()) {
            probe.handle.close();
          }
        }
      }
    }
  }

  @Test
  public void pendingFixtureShutdownWholeDeletePrecedesQueuedCreation() throws Exception {
    assertWholeDeleteOrder(true, false);
  }

  @Test
  public void pendingFixtureShutdownWholeDeleteRemovesPublishedCreation() throws Exception {
    assertWholeDeleteOrder(false, false);
  }

  @Test
  public void pendingFixtureShutdownWholeDeleteRemovesUnpublishedCreation() throws Exception {
    assertWholeDeleteOrder(false, true);
  }

  private void assertWholeDeleteOrder(boolean deleteFirst, boolean unpublished) throws Exception {
    final long id = unpublished ? prepareSavedNonDurablePendingPage(9493)
        : preparePendingPage(9492, null);
    final int internal = wowCache.internalFileId(id);
    final var probe = new CloseProbe(id);
    final var deleteThread = new AtomicReference<Thread>();
    probe.onHandle = file -> {
      if (unpublished) {
        Mockito.doAnswer(write -> {
          final List<
              com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                  write.getArgument(0);
          if (buffers.getFirst().first == 2L * PAGE_SIZE) {
            throw new IOException("Retryable first write failure");
          }
          return write.callRealMethod();
        }).when(file).write(Mockito.anyList());
      }
      Mockito.doAnswer(call -> {
        if (probe.handle == file) {
          assertTrue("Delete must close the admitted channel", file.isOpen());
        } else {
          // Delete can construct an unopened channel wrapper for an absent canonical path.
          assertTrue(deleteFirst);
          assertFalse(file.isOpen());
        }
        final var result = call.callRealMethod();
        assertFalse(file.isOpen());
        deleteThread.set(Thread.currentThread());
        return result;
      }).when(file).delete();
    };
    final var gate = new ShrinkGate();
    Mockito.doAnswer(call -> {
      if (deleteFirst) {
        gate.block();
      } else if (unpublished) {
        final var state = wowCache.logicalFiles().live(internal);
        assertFalse(state.diskPresent());
        assertSame(state.extents(), pendingOwners().get(internal));
        assertSame(probe.handle, files.get(id));
        assertTrue(Files.exists(standaloneIdentityPath(id)));
      } else {
        assertClosedPageBytes(id, 728);
      }
      return call.callRealMethod();
    }).when(wowCache).executeDeleteFile(id);
    final var caller = new AtomicReference<Thread>();
    final var tasks = new RegistrationTasks<long[]>(gate);
    try (var observation = wowCache.observePhysicalCreation(created -> {
      probe.created(created);
      if (!deleteFirst) {
        gate.block();
      }
    })) {
      try (tasks) {
        if (!deleteFirst) {
          tasks.predecessor = submitTestFileFlush(id);
          awaitShrinkGate(gate.entered);
        }
        tasks.caller = asyncFileExecutor.submit(() -> {
          caller.set(Thread.currentThread());
          return wowCache.delete();
        });
        if (deleteFirst) {
          awaitShrinkGate(gate.entered);
          assertFalse(Files.exists(standaloneIdentityPath(id)));
          tasks.predecessor = submitTestFileFlush(id);
        } else {
          awaitShutdownWait(caller, "delete");
        }
        assertFalse("Whole-cache delete must wait for its ordered work", tasks.caller.isDone());
      }
      assertFalse("Whole-cache delete must remove the canonical path",
          Files.exists(standaloneIdentityPath(id)));
      assertRegistrationPathCount(0);
      assertNull(files.get(id));
      assertEquals(0, files.openFilesCount());
      assertNull(wowCache.logicalFiles().live(internal));
      assertNull(pendingOwners().get(internal));
      assertEquals(deleteFirst ? 0 : 1, probe.creations);
      if (deleteFirst) {
        assertNull(probe.handle);
        assertArrayEquals(new long[0], tasks.result);
      } else {
        assertFalse(probe.handle.isOpen());
        assertSame("Unlink must run on the commitExecutor() thread",
            probe.commitThread, deleteThread.get());
        assertArrayEquals(new long[] {id}, tasks.result);
      }
    }
  }

  private static void awaitShutdownWait(AtomicReference<Thread> caller, String method) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      final var thread = caller.get();
      if (thread != null && thread.getState() == Thread.State.WAITING
          && java.util.Arrays.stream(thread.getStackTrace())
              .anyMatch(frame -> frame.getClassName().equals(WOWCache.class.getName())
                  && frame.getMethodName().equals(method))) {
        return;
      }
      Thread.yield();
    }
    throw new AssertionError("Caller did not wait in whole-cache " + method);
  }

  private Future<Void> submitTestFileFlush(long id) {
    return flushExecutor().submit(new FileFlushTask(wowCache, Set.of(wowCache.internalFileId(id))));
  }

  private CountDownLatch witnessCloseSubmission() {
    return witnessCloseSubmission(null);
  }

  private CountDownLatch witnessCloseSubmission(CountDownLatch retrying) {
    final var submitted = new CountDownLatch(1);
    Mockito.doAnswer(call -> {
      final var future = (Future<?>) call.callRealMethod();
      if (retrying != null) {
        // Observe only the untimed wait. Other calls go to the real future.
        final var observed = mock(Future.class, AdditionalAnswers.delegatesTo(future));
        final var interrupted = new AtomicBoolean();
        Mockito.doAnswer(wait -> {
          if (interrupted.get()) {
            retrying.countDown();
          }
          try {
            return future.get(20, TimeUnit.SECONDS);
          } catch (InterruptedException failure) {
            interrupted.set(true);
            throw failure;
          }
        }).when(observed).get();
        submitted.countDown();
        return observed;
      }
      submitted.countDown();
      return future;
    }).when(wowCache).submitCloseStep(Mockito.anyLong(), Mockito.anyInt(), Mockito.anyBoolean(),
        any());
    return submitted;
  }

  private void assertAbsentPendingClose(long id, CloseProbe probe) throws Exception {
    assertPendingExtents(id);
    assertEquals(0, probe.creations);
    assertEquals(0, files.openFilesCount());
    assertNull(probe.handle);
    assertNull(files.get(id));
    assertFalse(Files.exists(pendingPath(id)));
    assertRegistrationPathCount(0);
    assertNull(cacheField("flushError"));
  }

  private void assertClosedPageBytes(long id, int value) throws Exception {
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));
    assertTrue(state.diskPresent());
    assertEquals(3, state.diskExtent());
    assertEquals(3, state.logicalExtent());
    assertRegistrationPathCount(1);
    assertRawClosePages(id, value);
  }

  private void assertRawClosePages(long id, int value) throws IOException {
    final var bytes = Files.readAllBytes(standaloneIdentityPath(id));
    assertEquals(File.HEADER_SIZE + 3 * PAGE_SIZE, bytes.length);
    assertArrayEquals(new byte[File.HEADER_SIZE],
        java.util.Arrays.copyOf(bytes, File.HEADER_SIZE));
    for (int page = 0; page < 3; page++) {
      final var raw = ByteBuffer.wrap(bytes, File.HEADER_SIZE + page * PAGE_SIZE, PAGE_SIZE)
          .slice().order(ByteOrder.nativeOrder());
      assertEquals(WOWCache.MAGIC_NUMBER_WITH_CHECKSUM, raw.getLong(0));
      final var crc = new java.util.zip.CRC32();
      crc.update(raw.duplicate().position(WOWCache.CHECKSUM_OFFSET + Integer.BYTES));
      assertEquals((int) crc.getValue(), raw.getInt(WOWCache.CHECKSUM_OFFSET));
      if (page < 2) {
        assertEquals("blank LSN page " + page, new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(raw));
      }
      assertEquals("raw payload page " + page, page == 2 ? value : 0,
          raw.getInt(DurablePage.NEXT_FREE_POSITION));
    }
  }

  @FunctionalInterface
  private interface CloseHandleSetup {

    void configure(File handle) throws IOException, InterruptedException;
  }

  private final class CloseProbe {

    private final long id;
    private final Thread commitThread;
    private File candidate;
    private File handle;
    private Thread closeThread;
    private int creations;
    private CloseHandleSetup onHandle = file -> {
    };

    private CloseProbe(long id) throws Exception {
      this.id = id;
      commitThread = flushExecutor().submit(Thread::currentThread).get(10, TimeUnit.SECONDS);
      if (!Mockito.mockingDetails(wowCache).isSpy()) {
        wowCache = Mockito.spy(wowCache);
      }
      Mockito.doAnswer(call -> {
        final var file = Mockito.spy((File) call.callRealMethod());
        candidate = file;
        Mockito.doAnswer(close -> {
          closeThread = Thread.currentThread();
          return close.callRealMethod();
        }).when(file).close();
        onHandle.configure(file);
        return file;
      }).when(wowCache).createFileHandle(any(), eq((int) id));
    }

    private void created(int internal) {
      assertEquals(wowCache.internalFileId(id), internal);
      assertSame("Creation must run on the commitExecutor() thread",
          commitThread, Thread.currentThread());
      handle = candidate;
      creations++;
    }

    private void assertClosed() {
      assertEquals(1, creations);
      assertNotNull(handle);
      assertSame(handle, files.get(id));
      assertFalse(handle.isOpen());
      assertEquals(0, files.openFilesCount());
      assertSame("Close must run on the commitExecutor() thread", commitThread, closeThread);
    }
  }

  @Test(timeout = 60_000)
  public void orderedRegistrationReusesBackgroundPublishedFileFromPendingFixture()
      throws Exception {
    int component = 9420;
    final var commitThread = flushExecutor().submit(Thread::currentThread)
        .get(10, TimeUnit.SECONDS);
    for (final var permission : registrationModes()) {
      final long id = preparePendingPage(component++, null);
      final int internalId = wowCache.internalFileId(id);
      final var gate = new ShrinkGate();
      final var registrationStep = new AtomicReference<Future<?>>();
      final var submitted = witnessRegistrationSubmission(registrationStep);
      final var backgroundHandle = new AtomicReference<File>();
      Mockito.doAnswer(call -> {
        final var created = (File) call.callRealMethod();
        backgroundHandle.set(created);
        return created;
      }).when(wowCache).createFileHandle(any(), eq(internalId));
      final int openBefore = files.openFilesCount();
      try (var observation = wowCache.observePhysicalCreation(created -> {
        assertSame(commitThread, Thread.currentThread());
        gate.block();
      })) {
        final var tasks = new RegistrationTasks<Boolean>(gate);
        try (tasks) {
          tasks.predecessor = asyncFileExecutor.submit(() -> {
            flushWithSelector(id, 1);
            return null;
          });
          awaitShrinkGate(gate.entered);
          tasks.caller = asyncFileExecutor.submit(() -> {
            final var admitted = registerRecoveryMode(id, permission);
            assertEquals(3, wowCache.logicalFiles().live(internalId).diskExtent());
            return admitted;
          });
          assertTrue("Registration must submit behind background creation",
              submitted.await(5, TimeUnit.SECONDS));
          assertFalse("Registration step must wait for background completion",
              registrationStep.get().isDone());
          assertFalse(tasks.caller.isDone());
          assertNull(files.get(id));
        }
        assertTrue(tasks.result);
      }
      assertPendingFileWritten(id, openBefore + 1);
      assertSame(backgroundHandle.get(), files.get(id));
      Mockito.verify(wowCache).createFileHandle(any(), eq(internalId));
    }
  }

  @Test
  public void orderedRegistrationLeavesAbsentPendingFixtureForLaterBackgroundCreation()
      throws Exception {
    for (final boolean expected : List.of(true, false)) {
      final long id = preparePendingPage(expected ? 9422 : 9423, null);
      final var gate = new ShrinkGate();
      final int openBefore = files.openFilesCount();
      final var tasks = new RegistrationTasks<Boolean>(gate);
      try (tasks) {
        tasks.predecessor = flushExecutor().submit(gate::block);
        awaitShrinkGate(gate.entered);
        final var submitted = witnessRegistrationSubmission();
        tasks.caller = asyncFileExecutor.submit(() -> registerOpenOnly(id, expected));
        awaitShrinkGate(submitted);
        assertFalse(tasks.caller.isDone());
      }
      assertFalse(tasks.result);
      assertNull(files.get(id));
      assertFalse(Files.exists(pendingPath(id)));
      assertEquals(openBefore, files.openFilesCount());
      assertPendingExtents(id);
      flushWithSelector(id, 1);
      assertPendingFileWritten(id, openBefore + 1);
    }
  }

  @Test
  public void orderedRegistrationRefusesForeignPathInPendingFixture() throws Exception {
    final long id = preparePendingPage(9424, null);
    final var path = pendingPath(id);
    final byte[] sentinel = {4, 7, 8};
    Files.write(path, sentinel);
    wowCache = Mockito.spy(wowCache);
    try {
      for (final var permission : registrationModes()) {
        assertThrows(IOException.class, () -> registerRecoveryMode(id, permission));
        assertPendingExtents(id);
        assertEquals(0, files.openFilesCount());
        assertNull(files.get(id));
        assertArrayEquals(sentinel, Files.readAllBytes(path));
        assertNull(cacheField("flushError"));
        Mockito.verify(wowCache, Mockito.never()).createFileHandle(any(), Mockito.anyInt());
      }
    } finally {
      Files.delete(path);
    }
    flushWithSelector(id, 1);
    assertPendingFileWritten(id, 1);
  }

  @Test
  public void orderedRegistrationRejectsCommitExecutorThreadBeforeLockAndSubmission()
      throws Exception {
    final long id = useIdentityRecovery(9425);
    wowCache = Mockito.spy(wowCache);
    final var lock = (ReadersWriterSpinLock) cacheField("filesLock");
    lock.acquireWriteLock();
    try {
      onFlushExecutor(() -> {
        for (final var permission : registrationModes()) {
          assertTrue(assertThrows(IOException.class, () -> registerRecoveryMode(id, permission))
              .getMessage().contains("commitExecutor() thread"));
        }
      });
    } catch (java.util.concurrent.TimeoutException timeout) {
      throw new AssertionError("The commitExecutor() thread must reject before taking filesLock",
          timeout);
    } finally {
      lock.releaseWriteLock();
    }
    Mockito.verify(wowCache, Mockito.never()).submitRegistrationStep(any(), any());
    assertNull(files.get(id));
  }

  @Test
  public void orderedRegistrationOpensFreshExistingFilesOnCommitExecutorThread() throws Exception {
    wowCache.setStorageFormat(StorageFormat.LAYOUT_28);
    final var commitThread = flushExecutor().submit(Thread::currentThread)
        .get(10, TimeUnit.SECONDS);
    final var openThread = new AtomicReference<Thread>();
    wowCache = Mockito.spy(wowCache);
    Mockito.doAnswer(call -> {
      final var handle = Mockito.spy((File) call.callRealMethod());
      Mockito.doAnswer(open -> {
        openThread.set(Thread.currentThread());
        return open.callRealMethod();
      }).when(handle).open();
      return handle;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());

    int component = 9432;
    for (final var permission : registrationModes()) {
      final long id = wowCache.externalFileId(
          new PageFileIdentity(PageFileRole.RECORD_DATA, component++).internalId());
      final int count = files.openFilesCount() + 1;
      final var bytes = new byte[File.HEADER_SIZE + PAGE_SIZE];
      bytes[bytes.length - 1] = 42;
      final var path = standaloneIdentityPath(id);
      Files.write(path, bytes);
      openThread.set(null);
      assertTrue(registerRecoveryMode(id, permission));
      assertSame("Fresh file must open on the commitExecutor() thread", commitThread,
          openThread.get());
      assertTrue(files.get(id).isOpen());
      assertEquals(1, wowCache.getFilledUpTo(id));
      assertEquals(count, files.openFilesCount());
      assertRegistrationPathCount(count);
      assertArrayEquals(bytes, Files.readAllBytes(path));
    }
  }

  @Test
  public void orderedRegistrationRefusesRegisteredHandleWithoutOwnerInPendingFixture()
      throws Exception {
    final long id = prepareUnpublishedHandleWithRetainedTail();
    final int internal = wowCache.internalFileId(id);
    final var path = pendingPath(id);
    final var handle = files.get(id);
    final var bytes = Files.readAllBytes(path);
    final var state = wowCache.logicalFiles().live(internal);
    assertSame(state.extents(), pendingOwners().remove(internal));
    assertPendingExtents(id);
    for (final var permission : registrationModes()) {
      assertThrows(IOException.class, () -> registerRecoveryMode(id, permission));
      assertSame(handle, files.get(id));
      assertTrue(handle.isOpen());
      assertNull(pendingOwners().get(internal));
      assertSame(state, wowCache.logicalFiles().live(internal));
      assertPendingExtents(id);
      assertEquals(1, files.openFilesCount());
      assertRegistrationPathCount(1);
      assertArrayEquals(bytes, Files.readAllBytes(path));
    }
  }

  @Test
  public void orderedRegistrationPreservesRetainedTailAndCachedPageInOwnedPendingFixture()
      throws Exception {
    final long id = prepareUnpublishedHandleWithRetainedTail();
    final int internal = wowCache.internalFileId(id);
    final var path = pendingPath(id);
    final var handle = files.get(id);
    final var bytes = Files.readAllBytes(path);
    final var state = wowCache.logicalFiles().live(internal);
    final var retention = pageRetentionState();
    final var cached = wowCache.loadIfPresent(id, 2, false);
    assertNotNull(cached);
    try {
      final var image = new byte[PAGE_SIZE];
      cached.getBuffer().get(0, image);
      assertEquals(728, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      assertRetainedSegments(Set.of(1L));
      assertTrue(retention.hasRetainedContents(id));
      assertPendingExtents(id);
      for (final var permission : registrationModes()) {
        assertTrue(registerRecoveryMode(id, permission));
        assertRetainedSegments(Set.of(1L));
        assertTrue(retention.hasRetainedContents(id));
        final var after = wowCache.loadIfPresent(id, 2, false);
        assertNotNull(after);
        try {
          final var actualImage = new byte[PAGE_SIZE];
          after.getBuffer().get(0, actualImage);
          assertSame(cached, after);
          assertArrayEquals(image, actualImage);
        } finally {
          after.decrementReadersReferrer();
        }
        assertSame(state, wowCache.logicalFiles().live(internal));
        assertSame(state.extents(), pendingOwners().get(internal));
        assertPendingExtents(id);
        assertSame(handle, files.get(id));
        assertTrue("Reused pending handle must stay open", handle.isOpen());
        assertEquals(1, files.openFilesCount());
        assertRegistrationPathCount(1);
        assertArrayEquals(bytes, Files.readAllBytes(path));
      }
    } finally {
      cached.decrementReadersReferrer();
    }
  }

  @Test
  public void orderedRegistrationInterruptedWaiterKeepsRealStepAndPublishedHandle()
      throws Exception {
    final long id = useIdentityRecovery(9426);
    final var bytes = new byte[File.HEADER_SIZE + PAGE_SIZE];
    bytes[bytes.length - 1] = 42;
    final var path = standaloneIdentityPath(id);
    Files.write(path, bytes);
    assertInterruptedRegistration(id, false, null);
    assertEquals(1, files.openFilesCount());
    assertRegistrationPathCount(1);
    assertNotNull(files.get(id));
    assertEquals(1, wowCache.getFilledUpTo(id));
    assertArrayEquals(bytes, Files.readAllBytes(path));
  }

  @Test
  public void orderedRegistrationInterruptedRecoveryReportsCheckedFailureAfterRealOpenFails()
      throws Exception {
    final long id = useIdentityRecovery(9427);
    final var path = standaloneIdentityPath(id);
    Files.createDirectory(path);
    try {
      assertInterruptedRegistration(id, true, RecoveryFilePermission.OPEN_ONLY);
      assertTrue(Files.isDirectory(path));
      assertRegistrationPathCount(1);
      assertNull(files.get(id));
      assertEquals(0, files.openFilesCount());
      assertNull(wowCache.logicalFiles().live(wowCache.internalFileId(id)));
    } finally {
      Files.delete(path);
    }
  }

  @Test(timeout = 60_000)
  public void orderedDirectCreationInterruptedWaiterKeepsNewFileOrReportsOpenFailure()
      throws Exception {
    final long id = useIdentityRecovery(9442);
    final var path = standaloneIdentityPath(id);
    for (final var permission : creatingPermissions()) {
      assertInterruptedRegistration(id, false, permission);
      assertEquals(1, files.openFilesCount());
      assertRegistrationPathCount(1);
      assertTrue(files.get(id).isOpen());
      assertArrayEquals(new byte[File.HEADER_SIZE], Files.readAllBytes(path));
      wowCache.deleteFile(id);
      Files.createDirectory(path);
      try {
        assertInterruptedRegistration(id, true, permission);
        assertTrue(Files.isDirectory(path));
        assertNull(files.get(id));
        assertEquals(0, files.openFilesCount());
      } finally {
        Files.delete(path);
      }
    }
  }

  private void assertInterruptedRegistration(long id, boolean failedOpen,
      RecoveryFilePermission permission) throws Exception {
    final var gate = new ShrinkGate();
    final var realStep = new AtomicReference<Future<?>>();
    final var caller = new AtomicReference<Thread>();
    final var enteredWait = new CountDownLatch(1);
    final var enteredRetry = new CountDownLatch(1);
    final var observedInterrupt = new AtomicReference<InterruptedException>();
    if (!Mockito.mockingDetails(wowCache).isSpy()) {
      wowCache = Mockito.spy(wowCache);
    }
    Mockito.doAnswer(call -> {
      final var real = (Future<?>) call.callRealMethod();
      realStep.set(real);
      // Only instrument get(). Cancellation and state calls go to the real future.
      final var observed = mock(Future.class, AdditionalAnswers.delegatesTo(real));
      Mockito.doAnswer(get -> {
        if (observedInterrupt.get() == null) {
          enteredWait.countDown();
        } else {
          enteredRetry.countDown();
        }
        try {
          return real.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          observedInterrupt.set(interrupted);
          throw interrupted;
        }
      }).when(observed).get();
      return observed;
    }).when(wowCache).submitRegistrationStep(any(), any());

    final var tasks = new RegistrationTasks<IOException>(gate);
    try (tasks) {
      tasks.predecessor = flushExecutor().submit(gate::block);
      awaitShrinkGate(gate.entered);
      tasks.caller = asyncFileExecutor.submit(() -> {
        caller.set(Thread.currentThread());
        try {
          final var failure = assertThrows(IOException.class,
              () -> registerRecoveryMode(id, permission));
          assertTrue(Thread.currentThread().isInterrupted());
          return failure;
        } finally {
          Thread.interrupted();
        }
      });
      awaitShrinkGate(enteredWait);
      caller.get().interrupt();
      awaitShrinkGate(enteredRetry);
      assertFalse(realStep.get().isDone());
      final var lock = (ReadersWriterSpinLock) cacheField("filesLock");
      if (lock.tryAcquireReadLock(TimeUnit.MILLISECONDS.toNanos(100))) {
        lock.releaseReadLock();
        throw new AssertionError("Interrupted admission released filesLock before completion");
      }
      assertFalse(tasks.caller.isDone());
    }
    final var report = tasks.result;
    assertNotNull(report);
    assertSame(observedInterrupt.get(), report.getCause());
    final var real = realStep.get();
    assertFalse(real.isCancelled());
    if (failedOpen) {
      final var cause =
          assertThrows(ExecutionException.class, () -> real.get(10, TimeUnit.SECONDS)).getCause();
      assertTrue(cause instanceof StorageException);
      assertEquals(1, report.getSuppressed().length);
      assertSame(cause, report.getSuppressed()[0]);
    } else {
      assertEquals(Boolean.TRUE, real.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  public void orderedRegistrationExpectedFreshOpenFailureStaysUnchecked() throws Exception {
    assertFreshOpenFailure(true);
  }

  @Test
  public void orderedRegistrationRecoveryFreshOpenFailureStaysUnchecked() throws Exception {
    assertFreshOpenFailure(false);
  }

  @Test(timeout = 60_000)
  public void orderedCreatingFormsPreserveFreshCreateAndOpenFailureTypes() throws Exception {
    final long id = useIdentityRecovery(9443);
    final int internal = wowCache.internalFileId(id);
    final var path = standaloneIdentityPath(id);
    // An absent target below a regular file fails at the real create operation.
    final var parent = Files.createTempFile(storagePath, "blocked-parent", ".tmp");
    final var absent = parent.resolve(path.getFileName());
    final var originalRoot = storagePath;
    try {
      for (final var permission : creatingPermissions()) {
        setCacheField("storagePath", parent);
        assertFalse(Files.exists(absent));
        assertThrows(IOException.class, () -> registerRecoveryMode(id, permission));
        assertFalse(Files.exists(absent));
        setCacheField("storagePath", originalRoot);
        Files.createDirectory(path);
        try {
          assertThrows(StorageException.class, () -> registerRecoveryMode(id, permission));
          assertTrue(Files.isDirectory(path));
          assertNull(files.get(id));
          assertEquals(0, files.openFilesCount());
          assertNull(wowCache.logicalFiles().live(internal));
        } finally {
          Files.delete(path);
        }
      }
    } finally {
      setCacheField("storagePath", originalRoot);
      Files.delete(parent);
    }
  }

  @Test(timeout = 60_000)
  public void orderedCreatingFormsKeepFreshTrackerResetAndStartupPreservation() throws Exception {
    int component = 9444;
    final var tracker = (ChangedPageTracker) cacheField("changedPageTracker");
    for (final var permission : creatingPermissions()) {
      for (final boolean preserving : List.of(false, true)) {
        final long id = useIdentityRecovery(component++);
        final int internal = wowCache.internalFileId(id);
        tracker.mark(internal, 7);
        if (preserving) {
          wowCache.beginStartupReplayPreservation();
        }
        try {
          assertTrue(registerRecoveryMode(id, permission));
          final var handle = files.get(id);
          assertNotNull(handle);
          assertTrue(registerRecoveryMode(id, permission));
          assertSame(handle, files.get(id));
          assertEquals(1, files.openFilesCount());
          assertRegistrationPathCount(1);
          assertArrayEquals(new byte[File.HEADER_SIZE],
              Files.readAllBytes(standaloneIdentityPath(id)));
          assertEquals(permission == RecoveryFilePermission.RESTORE && !preserving
              ? List.of() : List.of(7L), changedPages(tracker, internal));
        } finally {
          if (preserving) {
            wowCache.endStartupReplayPreservation();
          }
          wowCache.deleteFile(id);
        }
      }
    }
  }

  @Test(timeout = 60_000)
  public void orderedCreatingFormsRefuseTransientMissOfPublishedHandle() throws Exception {
    assertCreatingFormsRefusePublishedMiss(false);
  }

  @Test(timeout = 60_000)
  public void orderedCreatingFormsRefuseMissingPathWithoutClosingPublishedHandle()
      throws Exception {
    assertCreatingFormsRefusePublishedMiss(true);
  }

  private void assertCreatingFormsRefusePublishedMiss(boolean movePath) throws Exception {
    wowCache = Mockito.spy(wowCache);
    int component = 9460;
    for (final var permission : creatingPermissions()) {
      final long id = preparePendingPage(component++, null);
      final int internal = wowCache.internalFileId(id);
      Mockito.doAnswer(call -> Mockito.spy((AsyncFile) call.callRealMethod()))
          .when(wowCache).createFileHandle(any(), eq(internal));
      flushWithSelector(id, 1);
      assertPendingFileWritten(id, 1);
      final var handle = (AsyncFile) files.get(id);
      final var path = pendingPath(id);
      final var bytes = Files.readAllBytes(path);
      final var state = wowCache.logicalFiles().live(internal);
      assertTrue(state.diskPresent());
      assertEquals(3, state.diskExtent());

      // Keep a newer cached image and retained content without changing the written extent.
      cacheUnwrittenImage(id, 2, 729);
      final var cached = wowCache.loadIfPresent(id, 2, false);
      assertNotNull(cached);
      try {
        final var retention = pageRetentionState();
        onFlushExecutor(() -> retention.appendSucceeded(cached, 1L, true));
        assertRetainedSegments(Set.of(1L));
        assertTrue(retention.hasRetainedContents(id));
        final var tracker = (ChangedPageTracker) cacheField("changedPageTracker");
        tracker.mark(internal, 19);
        assertEquals(List.of(2L, 19L), changedPages(tracker, internal));

        Mockito.clearInvocations(wowCache, handle);
        if (movePath) {
          final var aside = path.resolveSibling(path.getFileName() + ".aside");
          Files.move(path, aside);
          try {
            assertFalse(Files.exists(path));
            assertPublishedMissRefused(id, permission);
            assertFalse(Files.exists(path));
            assertTrue(handle.isOpen());
          } finally {
            Files.move(aside, path);
          }
        } else {
          // Only the first probe misses. A second probe would see the real file.
          Mockito.doReturn(false).doCallRealMethod().when(handle).exists();
          assertPublishedMissRefused(id, permission);
        }
        Mockito.verify(handle).exists();
        assertSame(handle, files.get(id));
        assertTrue(handle.isOpen());
        assertSame(state, wowCache.logicalFiles().live(internal));
        assertEquals(3, state.diskExtent());
        assertEquals(3, state.logicalExtent());
        assertArrayEquals(bytes, Files.readAllBytes(path));
        assertEquals(List.of(2L, 19L), changedPages(tracker, internal));
        assertRetainedSegments(Set.of(1L));
        assertTrue(retention.hasRetainedContents(id));
        final var after = wowCache.loadIfPresent(id, 2, false);
        assertNotNull(after);
        try {
          assertSame(cached, after);
          assertEquals(729, after.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
        } finally {
          after.decrementReadersReferrer();
        }
        assertNull(cacheField("flushError"));

        for (final boolean expected : List.of(false, true)) {
          Mockito.doReturn(false).doCallRealMethod().when(handle).exists();
          assertFalse(registerOpenOnly(id, expected));
        }
        assertTrue(registerRecoveryMode(id, permission));
        assertSame(handle, files.get(id));
        assertTrue(handle.isOpen());
        assertSame(state.extents(), wowCache.logicalFiles().live(internal).extents());
        assertEquals(1, files.openFilesCount());
        assertArrayEquals(bytes, Files.readAllBytes(path));
        Mockito.verify(wowCache, Mockito.never()).createFileHandle(any(), eq(internal));
        Mockito.verify(handle, Mockito.never()).create();
        Mockito.verify(handle, Mockito.never()).open();
        Mockito.verify(handle, Mockito.never()).shrink(Mockito.anyLong());
        Mockito.verify(handle, Mockito.never()).close();
        Mockito.verify(handle, Mockito.never()).closeAfterFailedCreate();
      } finally {
        cached.decrementReadersReferrer();
        wowCache.deleteFile(id);
      }
    }
  }

  private void assertPublishedMissRefused(long id, RecoveryFilePermission permission) {
    final var refusal = assertThrows("A missed probe must not authorize creation on a live handle",
        IOException.class, () -> registerRecoveryMode(id, permission));
    assertTrue(refusal.getMessage().contains("Registered page file is not visible"));
  }

  private void assertFreshOpenFailure(boolean expected) throws Exception {
    final long id = useIdentityRecovery(9427);
    final var path = standaloneIdentityPath(id);
    Files.createDirectory(path);
    try {
      assertThrows(StorageException.class, () -> registerOpenOnly(id, expected));
      assertTrue(Files.isDirectory(path));
      assertNull(files.get(id));
      assertEquals(0, files.openFilesCount());
      assertNull(wowCache.logicalFiles().live(wowCache.internalFileId(id)));
    } finally {
      Files.delete(path);
    }
  }

  @Test
  public void orderedRegistrationKeepsLegacyExpectedAndRecoveryContracts() throws Exception {
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9428);
    final long id = wowCache.addFile(PageFileNames.layout28(identity),
        wowCache.externalFileId(identity.internalId()));
    final var path = storagePath.resolve(wowCache.nativeFileNameById(id));
    final var bytes = Files.readAllBytes(path);
    final var handle = files.get(id);
    onFlushExecutor(() -> {
      assertThrows(UnsupportedOperationException.class, () -> wowCache.admitExpectedFile(identity));
      assertTrue(registerOpenOnly(id, false));
      for (final var permission : creatingPermissions()) {
        assertTrue(registerRecoveryMode(id, permission));
      }
    });
    assertSame(handle, files.get(id));
    assertEquals(1, files.openFilesCount());
    assertArrayEquals(new byte[File.HEADER_SIZE], bytes);
    assertArrayEquals(bytes, Files.readAllBytes(path));
  }

  @Test
  public void orderedRegistrationReportsSubmissionRejectionAsChecked() throws Exception {
    final long id = useIdentityRecovery(9429);
    final var rejection = new java.util.concurrent.RejectedExecutionException("not accepted");
    wowCache = Mockito.spy(wowCache);
    Mockito.doThrow(rejection).when(wowCache).submitRegistrationStep(any(), any());
    for (final var permission : registrationModes()) {
      assertSame(rejection,
          assertThrows(IOException.class, () -> registerRecoveryMode(id, permission)).getCause());
    }
    assertNull(files.get(id));
    assertFalse(Files.exists(standaloneIdentityPath(id)));
  }

  @Test
  public void orderedRegistrationClosesChannelOnlyBeforeContainerOwnership() throws Exception {
    wowCache.setStorageFormat(StorageFormat.LAYOUT_28);
    files = Mockito.spy(files);
    setCacheField("files", files);
    wowCache = Mockito.spy(wowCache);
    final var opened = new AtomicReference<File>();
    Mockito.doAnswer(call -> {
      final var handle = (File) call.callRealMethod();
      opened.set(handle);
      return handle;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());

    for (final boolean inserted : List.of(false, true)) {
      final long id = wowCache.externalFileId(
          new PageFileIdentity(PageFileRole.RECORD_DATA, inserted ? 9430 : 9431).internalId());
      final var path = standaloneIdentityPath(id);
      final var header = new byte[File.HEADER_SIZE];
      Files.write(path, header);
      final var rejection = new IllegalStateException("container add failed");
      Mockito.doAnswer(call -> {
        if (inserted) {
          call.callRealMethod();
        }
        throw rejection;
      }).when(files).add(eq(id), any());
      try {
        assertSame(rejection,
            assertThrows(IllegalStateException.class, () -> registerOpenOnly(id, true)));
        assertEquals(inserted, opened.get().isOpen());
        assertSame(inserted ? opened.get() : null, files.get(id));
        assertEquals(inserted ? 1 : 0, files.openFilesCount());
        assertNull(wowCache.logicalFiles().live(wowCache.internalFileId(id)));
        assertArrayEquals(header, Files.readAllBytes(path));
      } finally {
        if (files.remove(id) != null || opened.get() != null && opened.get().isOpen()) {
          opened.get().close();
        }
        Files.delete(path);
      }
    }
  }

  @Test(timeout = 60_000)
  public void orderedDirectCreationPublishesZeroInPendingFixtureBeforeLaterFlush()
      throws Exception {
    int component = 9440;
    final var commitThread = flushExecutor().submit(Thread::currentThread)
        .get(10, TimeUnit.SECONDS);
    final var lsn = new LogSequenceNumber(2, 7);
    // The fixture creation LSN models a durable WAL record, not a new live WAL entry.
    final var wal = Mockito.spy(writeAheadLog);
    Mockito.doReturn(lsn).when(wal).getFlushedLsn();
    setCacheField("writeAheadLog", wal);
    for (final var permission : creatingPermissions()) {
      for (final boolean sync : List.of(false, true)) {
        assertDirectCreationPublishesZero(component++, commitThread, lsn, permission, sync);
      }
    }
  }

  private void assertDirectCreationPublishesZero(int component, Thread commitThread,
      LogSequenceNumber lsn, RecoveryFilePermission permission, boolean sync) throws Exception {
    final long id = preparePendingPage(component, lsn);
    final int internal = wowCache.internalFileId(id);
    final var original = wowCache.logicalFiles();
    final var state = original.live(internal);
    final var table = Mockito.spy(original);
    Mockito.doAnswer(call -> {
      final boolean published = (boolean) call.callRealMethod();
      assertSame("Zero-prefix publication must run on the commitExecutor() thread",
          commitThread, Thread.currentThread());
      assertTrue(published);
      return published;
    }).when(table).publishCompleted(internal, state.extents(), 0);
    final var retained = pageRetentionState();
    final var tracker = (ChangedPageTracker) cacheField("changedPageTracker");
    tracker.mark(internal, 19);
    setCacheField("callFsync", sync);
    setCacheField("logicalFiles", table);
    final var submitted = witnessRegistrationSubmission();
    final var handle = new AtomicReference<File>();
    Mockito.doAnswer(call -> {
      final var created = Mockito.spy((File) call.callRealMethod());
      handle.set(created);
      return created;
    }).when(wowCache).createFileHandle(any(), eq(internal));
    try {
      final var cached = wowCache.loadIfPresent(id, 2, false);
      assertNotNull(cached);
      final var image = new byte[PAGE_SIZE];
      try {
        cached.getBuffer().get(0, image);
        onFlushExecutor(() -> retained.appendSucceeded(cached, 1L, true));
      } finally {
        // The exclusive-write selector needs a page with no readers.
        cached.decrementReadersReferrer();
      }
      final var gate = new ShrinkGate();
      final var creations = new java.util.concurrent.CopyOnWriteArrayList<Thread>();
      try (var observation = wowCache.observePhysicalCreation(
          created -> creations.add(Thread.currentThread()))) {
        final var tasks = new RegistrationTasks<Boolean>(gate);
        try (tasks) {
          tasks.predecessor = flushExecutor().submit(gate::block);
          awaitShrinkGate(gate.entered);
          tasks.caller = asyncFileExecutor.submit(() -> registerRecoveryMode(id, permission));
          awaitShrinkGate(submitted);
          assertFalse(tasks.caller.isDone());
          assertFalse(Files.exists(pendingPath(id)));
        }
        assertTrue(tasks.result);
        Mockito.verify(table).publishCompleted(internal, state.extents(), 0);
        assertEquals(List.of(commitThread), creations);
        final var published = wowCache.logicalFiles().live(internal);
        assertSame(state.extents(), published.extents());
        assertEquals(lsn, published.creationLsn());
        assertTrue("Direct creation must publish disk presence", published.diskPresent());
        assertEquals(0, published.diskExtent());
        assertEquals(3, published.logicalExtent());
        assertSame(handle.get(), files.get(id));
        assertEquals(1, files.openFilesCount());
        assertRegistrationPathCount(1);
        assertArrayEquals(new byte[File.HEADER_SIZE], Files.readAllBytes(pendingPath(id)));
        Mockito.verify(handle.get(), Mockito.times(sync ? 1 : 0)).synch();
        assertRetainedSegments(Set.of(1L));
        assertTrue(retained.hasRetainedContents(id));
        assertEquals(List.of(2L, 19L), changedPages(tracker, internal));
        final var after = wowCache.loadIfPresent(id, 2, false);
        assertNotNull(after);
        try {
          final var actual = new byte[PAGE_SIZE];
          after.getBuffer().get(0, actual);
          assertSame(cached, after);
          assertArrayEquals(image, actual);
        } finally {
          after.decrementReadersReferrer();
        }
        flushWithSelector(id, 1);
        assertPendingFileWritten(id, 1);
        assertSame(handle.get(), files.get(id));
        assertEquals(List.of(commitThread), creations);
      }
    } finally {
      setCacheField("logicalFiles", original);
      wowCache.deleteFile(id);
    }
  }

  @Test(timeout = 60_000)
  public void orderedDirectCreationRequiresSavedRegistryInPendingFixture() throws Exception {
    int component = 9450;
    for (final var permission : creatingPermissions()) {
      final long id = prepareSavedNonDurablePendingPage(component++);
      final var saved = cacheField("savedNonDurableFileIds");
      try {
        setCacheField("savedNonDurableFileIds", new IntOpenHashSet());
        final var refusal = assertThrows(IOException.class, () -> registerRecoveryMode(id,
            permission));
        assertTrue(refusal.getMessage().contains("lacks registry evidence"));
        assertNull(cacheField("flushError"));
        assertNull(files.get(id));
        assertRegistrationPathCount(0);
        assertPendingExtents(id);
        setCacheField("savedNonDurableFileIds", saved);
        assertTrue(registerRecoveryMode(id, permission));
        assertSame(saved, cacheField("savedNonDurableFileIds"));
        assertArrayEquals(new byte[File.HEADER_SIZE], Files.readAllBytes(pendingPath(id)));
        flushWithSelector(id, 1);
        assertPendingFileWritten(id, 1);
      } finally {
        setCacheField("savedNonDurableFileIds", saved);
        wowCache.deleteFile(id);
      }
    }
  }

  @Test(timeout = 60_000)
  public void orderedDirectCreationNeverOpensPathThatAppearsInPendingFixture() throws Exception {
    int component = 9452;
    final var sentinel = new byte[File.HEADER_SIZE + PAGE_SIZE];
    sentinel[sentinel.length - 1] = 42;
    wowCache = Mockito.spy(wowCache);
    for (final var permission : creatingPermissions()) {
      final long id = preparePendingPage(component++, null);
      final int internal = wowCache.internalFileId(id);
      final var path = pendingPath(id);
      final var attempted = new AtomicReference<File>();
      Mockito.doAnswer(call -> {
        final var handle = Mockito.spy((File) call.callRealMethod());
        attempted.set(handle);
        Files.write(path, sentinel, java.nio.file.StandardOpenOption.CREATE_NEW);
        return handle;
      }).when(wowCache).createFileHandle(any(), eq(internal));
      try {
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> registerRecoveryMode(id, permission));
        Mockito.verify(attempted.get()).create();
        Mockito.verify(attempted.get(), Mockito.never()).open();
        Mockito.verify(attempted.get(), Mockito.never()).shrink(Mockito.anyLong());
        assertNull(cacheField("flushError"));
        assertNull(files.get(id));
        assertEquals(0, files.openFilesCount());
        assertRegistrationPathCount(1);
        assertArrayEquals(sentinel, Files.readAllBytes(path));
        assertPendingExtents(id);
      } finally {
        Files.deleteIfExists(path);
        wowCache.deleteFile(id);
      }
    }
  }

  @Test(timeout = 60_000)
  public void orderedDirectCreationDoesNotPublishAfterPendingFixtureAdmissionFails()
      throws Exception {
    files = Mockito.spy(files);
    setCacheField("files", files);
    wowCache = Mockito.spy(wowCache);
    int component = 9454;
    final var handle = new AtomicReference<File>();
    Mockito.doAnswer(call -> {
      final var created = (File) call.callRealMethod();
      handle.set(created);
      return created;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    for (final var permission : creatingPermissions()) {
      for (final boolean inserted : List.of(false, true)) {
        final long id = preparePendingPage(component++, null);
        final var failure = new IllegalStateException("Admission failed");
        Mockito.doAnswer(call -> {
          assertTrue(handle.get().isOpen());
          if (inserted) {
            call.callRealMethod();
          }
          throw failure;
        }).when(files).add(eq(id), any());
        try {
          assertSame(failure,
              assertThrows(IllegalStateException.class,
                  () -> registerRecoveryMode(id, permission)));
          assertEquals("Container ownership decides whether to close", inserted,
              handle.get().isOpen());
          assertSame(inserted ? handle.get() : null, files.get(id));
          assertEquals(inserted ? 1 : 0, files.openFilesCount());
          assertRegistrationPathCount(1);
          assertArrayEquals(new byte[File.HEADER_SIZE], Files.readAllBytes(pendingPath(id)));
          assertPendingExtents(id);
          assertNull(cacheField("flushError"));
          assertThrows(IOException.class, () -> registerRecoveryMode(id, permission));
        } finally {
          if (files.get(id) == null && handle.get() != null && handle.get().isOpen()) {
            handle.get().close();
          }
          wowCache.deleteFile(id);
        }
      }
    }
  }

  @Test(timeout = 60_000)
  public void orderedDirectCreationReportsPendingFixturePublicationFailure() throws Exception {
    int component = 9458;
    for (final var permission : creatingPermissions()) {
      final long id = preparePendingPage(component++, null);
      final int internal = wowCache.internalFileId(id);
      final var original = wowCache.logicalFiles();
      final var table = Mockito.spy(original);
      final var token = table.live(internal).extents();
      setCacheField("logicalFiles", table);
      try {
        Mockito.doReturn(false).when(table).isCurrent(internal, token);
        final var obsolete = assertThrows(IOException.class, () -> registerRecoveryMode(id,
            permission));
        assertTrue(obsolete.getMessage().contains("Obsolete"));
        assertFalse(Files.exists(pendingPath(id)));
        assertNull(files.get(id));
        Mockito.doCallRealMethod().when(table).isCurrent(internal, token);
        Mockito.doReturn(false).when(table).publishCompleted(internal, token, 0);
        final var unpublished = assertThrows(IOException.class, () -> registerRecoveryMode(id,
            permission));
        assertTrue(unpublished.getMessage().contains("Cannot publish"));
        assertPendingExtents(id);
        assertNotNull(files.get(id));
        assertNull(cacheField("flushError"));
        assertArrayEquals(new byte[File.HEADER_SIZE], Files.readAllBytes(pendingPath(id)));
      } finally {
        setCacheField("logicalFiles", original);
        wowCache.deleteFile(id);
      }
    }
  }

  private static List<Long> changedPages(ChangedPageTracker tracker, int internal) {
    tracker.saveOrderLock().lock();
    try {
      final var pages = new java.util.ArrayList<Long>();
      tracker.capture().active().forEachCandidate(internal, pages::add);
      return pages;
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static final class RegistrationTasks<T> implements AutoCloseable {

    private final ShrinkGate gate;
    private Future<?> predecessor;
    private Future<T> caller;
    private T result;

    private RegistrationTasks(ShrinkGate gate) {
      this.gate = gate;
    }

    @Override
    public void close() throws Exception {
      gate.release.countDown();
      // Join every submitted caller even if the witness or the predecessor fails.
      try (AutoCloseable joinCaller = () -> {
        if (caller != null) {
          result = caller.get(10, TimeUnit.SECONDS);
        }
      }) {
        if (predecessor != null) {
          predecessor.get(10, TimeUnit.SECONDS);
        }
      }
    }
  }

  private CountDownLatch witnessRegistrationSubmission() {
    return witnessRegistrationSubmission(new AtomicReference<>());
  }

  private CountDownLatch witnessRegistrationSubmission(AtomicReference<Future<?>> step) {
    if (!Mockito.mockingDetails(wowCache).isSpy()) {
      wowCache = Mockito.spy(wowCache);
    }
    final var submitted = new CountDownLatch(1);
    Mockito.doAnswer(call -> {
      final var future = (Future<?>) call.callRealMethod();
      step.set(future);
      submitted.countDown();
      return future;
    }).when(wowCache).submitRegistrationStep(any(), any());
    return submitted;
  }

  private static List<RecoveryFilePermission> creatingPermissions() {
    return List.of(RecoveryFilePermission.REPLAY_CREATE, RecoveryFilePermission.RESTORE);
  }

  private static List<RecoveryFilePermission> registrationModes() {
    // A null permission selects expected admission rather than recovery resolution.
    return java.util.Arrays.asList(null, RecoveryFilePermission.OPEN_ONLY,
        RecoveryFilePermission.REPLAY_CREATE, RecoveryFilePermission.RESTORE);
  }

  private boolean registerRecoveryMode(long id, RecoveryFilePermission permission)
      throws IOException {
    if (permission == null) {
      return registerOpenOnly(id, true);
    }
    final var name = permission == RecoveryFilePermission.OPEN_ONLY ? null
        : PageFileNames.layout28(PageFileIdentity.decode(wowCache.internalFileId(id)));
    return wowCache.resolveRecoveryFile(name, id, permission);
  }

  private boolean registerOpenOnly(long id, boolean expected) throws IOException {
    return expected
        ? wowCache.admitExpectedFile(PageFileIdentity.decode(wowCache.internalFileId(id)))
        : wowCache.resolveRecoveryFile(null, id, WriteCache.RecoveryFilePermission.OPEN_ONLY);
  }

  private long useIdentityRecovery(int component) {
    wowCache.setStorageFormat(StorageFormat.LAYOUT_28);
    return wowCache.externalFileId(new PageFileIdentity(PageFileRole.RECORD_DATA, component)
        .internalId());
  }

  private Path standaloneIdentityPath(long id) {
    return storagePath.resolve(
        PageFileNames.layout28DiskName(PageFileIdentity.decode(wowCache.internalFileId(id))));
  }

  private void assertPendingExtents(long id) {
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));
    assertFalse(state.diskPresent());
    assertEquals(0, state.diskExtent());
    assertEquals(3, state.logicalExtent());
  }

  private void assertPendingFileWritten(long id, int openFiles) throws Exception {
    assertEquals(openFiles, files.openFilesCount());
    assertNotNull(files.get(id));
    final var path = pendingPath(id);
    assertRegistrationPathCount(openFiles);
    final var bytes = Files.readAllBytes(path);
    assertEquals(File.HEADER_SIZE + 3 * PAGE_SIZE, bytes.length);
    assertArrayEquals(new byte[File.HEADER_SIZE], java.util.Arrays.copyOf(bytes, File.HEADER_SIZE));
    assertMaterializedContents(id, 3, 2, 728);
  }

  private void assertRegistrationPathCount(int count) throws IOException {
    try (var paths = Files.list(storagePath)) {
      assertEquals(count, paths.map(candidate -> candidate.getFileName().toString())
          .filter(name -> name.startsWith("ytdb-p28-") && name.endsWith(".pcl")).count());
    }
  }

  @Test
  public void orderedDeleteWaitsThroughFirstPageWriteCompletionAndDiscardsRetention()
      throws Exception {
    final long id = preparePendingPage(9410, null);
    final int internal = wowCache.internalFileId(id);
    final var path = pendingPath(id);
    final var gate = new ShrinkGate();
    final var dwl = mock(DoubleWriteLog.class);
    when(dwl.write(any(), any(), any())).thenReturn(new DoubleWriteLog.AppendResult(41L, false, 0));
    setCacheField("doubleWriteLog", dwl);
    setCacheField("callFsync", true);
    final var retention = pageRetentionState();
    wowCache = Mockito.spy(wowCache);
    Mockito.doAnswer(call -> {
      final File original = (File) call.callRealMethod();
      final var handle = Mockito.spy(original);
      Mockito.doAnswer(unlink -> {
        assertEquals(3, wowCache.logicalFiles().live(internal).diskExtent());
        assertEquals(Set.of(41L), retention.retainedSegments());
        return unlink.callRealMethod();
      }).when(handle).delete();
      Mockito.doAnswer(write -> {
        final var io =
            (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) write.callRealMethod();
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          gate.block();
          io.await();
        };
      }).when(handle).write(Mockito.anyList());
      return handle;
    }).when(wowCache).createFileHandle(any(), Mockito.eq(internal));
    final var submitted = witnessDeleteSubmission();
    final var write = asyncFileExecutor.submit(() -> {
      flushWithSelector(id, 1);
      return null;
    });
    try {
      awaitShrinkGate(gate.entered);
      final var delete = asyncFileExecutor.submit(() -> {
        wowCache.deleteFile(id);
        return null;
      });
      awaitShrinkGate(submitted);
      assertFalse(delete.isDone());
      assertTrue(Files.exists(path));
      assertNotNull(pendingOwners().get(internal));
      assertEquals(0, wowCache.logicalFiles().live(internal).diskExtent());
      gate.release.countDown();
      write.get(10, TimeUnit.SECONDS);
      delete.get(10, TimeUnit.SECONDS);
      assertTrue(wowCache.logicalFiles().deleted(internal));
      assertFalse(Files.exists(path));
      assertNull(files.get(id));
      assertNull(pendingOwners().get(internal));
      Mockito.verify(dwl).write(any(), any(), any());
      assertRetainedSegments(Set.of());
    } finally {
      gate.release.countDown();
    }
  }

  @Test
  public void orderedDeleteCancelsQueuedFirstCreationAndOldBlankAfterIdReuse() throws Exception {
    final long id = preparePendingPage(9411, null);
    final int internal = wowCache.internalFileId(id);
    final var path = pendingPath(id);
    final var oldState = wowCache.logicalFiles().live(internal);
    final var blank = new EnsurePageIsValidInFileTask(internal, 2, wowCache);
    final var submitted = witnessDeleteSubmission();
    final var beforeDelete = new ShrinkGate();
    final var beforeBlank = new ShrinkGate();
    final var blocker = flushExecutor().submit(beforeDelete::block);
    try {
      awaitShrinkGate(beforeDelete.entered);
      final var delete = asyncFileExecutor.submit(() -> {
        wowCache.deleteFile(id);
        return null;
      });
      awaitShrinkGate(submitted);
      final var flush = flushExecutor().submit(new FileFlushTask(wowCache, Set.of(internal)));
      final var stale = flushExecutor().submit(() -> {
        beforeBlank.block();
        blank.run();
      });
      beforeDelete.release.countDown();
      blocker.get(10, TimeUnit.SECONDS);
      delete.get(10, TimeUnit.SECONDS);
      flush.get(10, TimeUnit.SECONDS);
      awaitShrinkGate(beforeBlank.entered);
      assertTrue(wowCache.logicalFiles().deleted(internal));
      assertFalse(Files.exists(path));
      assertNull(files.get(id));

      assertEquals(id, wowCache.addFile(PageFileNames.layout28(oldState.identity()), id));
      final var replacement = wowCache.logicalFiles().live(internal);
      assertNotSame(oldState.extents(), replacement.extents());
      replacement.extents().allocateThrough(3);
      beforeBlank.release.countDown();
      stale.get(10, TimeUnit.SECONDS);
      assertEquals(File.HEADER_SIZE, Files.size(path));
      assertEquals(0, replacement.diskExtent());
      cacheUnwrittenImage(id, 2, 876);
      wowCache.flush(id);
      assertMaterializedContents(id, 3, 2, 876);
    } finally {
      beforeDelete.release.countDown();
      beforeBlank.release.countDown();
    }
  }

  @Test
  public void orderedDeleteRejectsCommitExecutorThreadBeforeTakingFilesLock() throws Exception {
    final long id = preparePendingPage(9412, null);
    wowCache = Mockito.spy(wowCache);
    final var lock = (ReadersWriterSpinLock) cacheField("filesLock");
    lock.acquireWriteLock();
    try {
      onFlushExecutor(() -> {
        final var error = assertThrows(IllegalStateException.class, () -> wowCache.deleteFile(id));
        assertTrue(error.getMessage().contains("commitExecutor() thread"));
      });
    } finally {
      lock.releaseWriteLock();
    }
    Mockito.verify(wowCache, Mockito.never()).submitDeleteStep(Mockito.anyLong());
    assertTrue(wowCache.exists(id));
  }

  @Test
  public void orderedDeleteInterruptedCallerPersistsRegistryAndClearsMarksBeforeReturning()
      throws Exception {
    assertInterruptedDelete(false);
  }

  @Test
  public void orderedDeleteInterruptedCallerStillReceivesUnlinkFailure() throws Exception {
    assertInterruptedDelete(true);
  }

  private void assertInterruptedDelete(boolean fail) throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9413);
    final long survivor = prepareSavedNonDurablePendingPage(9414);
    final int internal = wowCache.internalFileId(id);
    wowCache.flush(id);
    final var tracker = (ChangedPageTracker) cacheField("changedPageTracker");
    tracker.mark(internal, 1);
    final var marks = tracker.beginBackup();
    final var gate = new ShrinkGate();
    final var handle = spyAsyncHandle(id);
    final var path = pendingPath(id);
    final var cause = new java.nio.file.AccessDeniedException(path.toString());
    Mockito.doAnswer(call -> {
      gate.block();
      if (fail) {
        handle.closeAfterFailedCreate();
        throw cause;
      }
      return call.callRealMethod();
    }).doCallRealMethod().when(handle).delete();

    final var sawInterrupt = new CountDownLatch(1);
    final var realStep = new AtomicReference<Future<?>>();
    final var observed = mock(Future.class);
    wowCache = Mockito.spy(wowCache);
    Mockito.doAnswer(call -> {
      final var real = (Future<?>) call.callRealMethod();
      realStep.set(real);
      when(observed.get()).thenAnswer(get -> {
        try {
          return real.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          sawInterrupt.countDown();
          throw interrupted;
        }
      });
      return observed;
    }).when(wowCache).submitDeleteStep(id);
    final var caller = new AtomicReference<Thread>();
    final var delete = asyncFileExecutor.submit(() -> {
      caller.set(Thread.currentThread());
      try {
        if (fail) {
          final var error = assertThrows(WriteCacheException.class, () -> wowCache.deleteFile(id));
          assertCause(error, cause);
          assertEquals(1, error.getSuppressed().length);
          assertTrue(error.getSuppressed()[0] instanceof ThreadInterruptedException);
        } else {
          assertThrows(ThreadInterruptedException.class, () -> wowCache.deleteFile(id));
          assertTrue(wowCache.logicalFiles().deleted(internal));
        }
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      if (!fail) {
        assertEquals(new IntOpenHashSet(new int[] {wowCache.internalFileId(survivor)}),
            savedRegistry());
      }
      return null;
    });
    try {
      awaitShrinkGate(gate.entered);
      caller.get().interrupt();
      awaitShrinkGate(sawInterrupt);
      final var lock = (ReadersWriterSpinLock) cacheField("filesLock");
      if (lock.tryAcquireReadLock(TimeUnit.MILLISECONDS.toNanos(100))) {
        lock.releaseReadLock();
        throw new AssertionError("Interrupted delete released filesLock before completion");
      }
      assertFalse(delete.isDone());
    } finally {
      gate.release.countDown();
      delete.get(10, TimeUnit.SECONDS);
    }
    Mockito.verify(observed, Mockito.never()).cancel(Mockito.anyBoolean());
    if (fail) {
      assertCause(assertThrows(ExecutionException.class,
          () -> realStep.get().get(10, TimeUnit.SECONDS)), cause);
    } else {
      assertNotNull(realStep.get().get(10, TimeUnit.SECONDS));
    }
    final var pages = new java.util.ArrayList<Long>();
    marks.pages().forEachCandidate(internal, pages::add);
    assertEquals(fail ? List.of(1L) : List.of(), pages);
    assertEquals(fail, wowCache.isNonDurable(id));
    assertEquals(fail, Files.exists(path));
    onFlushExecutor(() -> {
    });
  }

  @Test
  public void legacyDeleteInterruptedCallerReturnsBeforeStepEnds() throws Exception {
    wowCache.setStorageFormat(StorageFormat.LAYOUT_27);
    final long id = wowCache.addFile(AUX, 123456);
    final var path = storagePath.resolve(wowCache.nativeFileNameById(id));
    final var commitThread = flushExecutor().submit(Thread::currentThread)
        .get(10, TimeUnit.SECONDS);
    final var gate = new ShrinkGate();
    wowCache = Mockito.spy(wowCache);
    Mockito.doAnswer(call -> {
      assertSame(commitThread, Thread.currentThread());
      gate.block();
      return call.callRealMethod();
    }).when(wowCache).executeDeleteFile(id);

    final var caller = new AtomicReference<Thread>();
    final var delete = asyncFileExecutor.submit(() -> {
      caller.set(Thread.currentThread());
      final var error =
          assertThrows(ThreadInterruptedException.class, () -> wowCache.deleteFile(id));
      assertEquals("File data removal was interrupted", error.getMessage());
      assertTrue(error.getCause() instanceof InterruptedException);
      assertNull(error.getCause().getCause());
      assertFalse(Thread.currentThread().isInterrupted());
      return null;
    });
    try {
      awaitShrinkGate(gate.entered);
      caller.get().interrupt();
      delete.get(10, TimeUnit.SECONDS);
      assertTrue(Files.exists(path));
    } finally {
      gate.release.countDown();
      try {
        if (!delete.isDone()) {
          delete.get(10, TimeUnit.SECONDS);
        }
      } finally {
        onFlushExecutor(() -> {
        });
      }
    }
    assertFalse(Files.exists(path));
    assertFalse(wowCache.exists(id));
  }

  @Test
  public void orderedDeleteKeepsRegistryIoFailureWhenWriteConsumesInterrupt() throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9415);
    prepareSavedNonDurablePendingPage(9416);
    final var failure = new java.nio.channels.ClosedByInterruptException();
    try (var io = Mockito.mockStatic(com.jetbrains.youtrackdb.internal.common.io.IOUtils.class,
        Mockito.CALLS_REAL_METHODS)) {
      io.when(() -> com.jetbrains.youtrackdb.internal.common.io.IOUtils.writeByteBuffer(
          any(ByteBuffer.class), any(FileChannel.class), Mockito.eq(0L))).thenAnswer(call -> {
            Thread.currentThread().interrupt();
            assertTrue(Thread.interrupted());
            throw failure;
          });
      try {
        assertSame(failure, assertThrows(IOException.class, () -> wowCache.deleteFile(id)));
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
    }
    assertFalse(wowCache.isNonDurable(id));
    assertTrue(wowCache.logicalFiles().deleted(wowCache.internalFileId(id)));
  }

  @Test
  public void orderedDeletePreservesReplayMarksAndBatchesRegistryWrite() throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9417);
    final int internal = wowCache.internalFileId(id);
    wowCache.beginStartupReplayPreservation();
    final var tracker = (ChangedPageTracker) cacheField("changedPageTracker");
    tracker.mark(internal, 1);
    final var marks = tracker.beginBackup();
    final var readCache =
        mock(com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache.class);
    Mockito.doAnswer(call -> {
      wowCache.deleteFile(id);
      assertFalse(wowCache.isNonDurable(id));
      assertEquals(new IntOpenHashSet(new int[] {internal}), savedRegistry());
      return null;
    }).when(readCache).deleteFile(id, wowCache);
    try {
      assertEquals(new IntOpenHashSet(new int[] {internal}),
          wowCache.deleteNonDurableFilesOnRecovery(readCache));
    } finally {
      wowCache.endStartupReplayPreservation();
    }
    assertTrue(savedRegistry().isEmpty());
    final var pages = new java.util.ArrayList<Long>();
    marks.pages().forEachCandidate(internal, pages::add);
    assertEquals(List.of(1L), pages);
  }

  @Test
  public void orderedDeleteReopensSameHandleAndKeepsReadablePublishedPagesAndRetention()
      throws Exception {
    final long id = preparePublishedFileWithRetainedPrefixAndTail();
    final int internal = wowCache.internalFileId(id);
    final var before = wowCache.logicalFiles().live(internal);
    final long logicalExtent = before.logicalExtent();
    final long diskExtent = before.diskExtent();
    final var path = pendingPath(id);
    final var bytes = Files.readAllBytes(path);
    final var handle = spyAsyncHandle(id);
    final var unlink = failNextUnlink(handle, path, () -> {
    });

    assertCause(assertThrows(WriteCacheException.class, () -> wowCache.deleteFile(id)), unlink);
    assertEquals(0, unlink.getSuppressed().length);
    assertSame(handle, files.get(id));
    final var after = wowCache.logicalFiles().live(internal);
    assertSame(before, after);
    assertEquals(logicalExtent, after.logicalExtent());
    assertEquals(diskExtent, after.diskExtent());
    assertArrayEquals(bytes, Files.readAllBytes(path));
    assertRetainedSegments(Set.of(41L, 42L));
    Mockito.verify(handle).open();
    Mockito.verify(handle, Mockito.never()).synch();
    Mockito.verify(handle, Mockito.never()).create();
    assertUncachedPublishedPageReadable(id, handle);
    wowCache.deleteFile(id);
    assertTrue(wowCache.logicalFiles().deleted(internal));
    assertFalse(Files.exists(path));
    assertRetainedSegments(Set.of());
  }

  private void assertUncachedPublishedPageReadable(long id, AsyncFile handle) throws IOException {
    // A fresh read cache does not contain this page. The write-cache purge removed its image.
    final var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    try {
      final var page = readCache.loadForRead(id, 2, wowCache, true);
      try {
        assertEquals(728,
            page.getCachePointer().getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        readCache.releaseFromRead(page);
      }
    } finally {
      readCache.clear();
    }

    Mockito.verify(handle).read(Mockito.eq(2L * PAGE_SIZE), any(ByteBuffer.class), eq(true));
  }

  @Test
  public void orderedDeleteRestoresCurrentPendingOwnershipAfterUnlinkFailure() throws Exception {
    assertPendingOwnershipAfterFailedUnlink(false);
  }

  @Test
  public void orderedDeleteDoesNotRestoreAnObsoletePendingOwnershipToken() throws Exception {
    assertPendingOwnershipAfterFailedUnlink(true);
  }

  private void assertPendingOwnershipAfterFailedUnlink(boolean replaceToken) throws Exception {
    final long id = prepareUnpublishedHandleWithRetainedTail();
    final int internal = wowCache.internalFileId(id);
    final var path = pendingPath(id);
    final var before = wowCache.logicalFiles().live(internal);
    assertSame(before.extents(), pendingOwners().get(internal));
    if (replaceToken) {
      wowCache.logicalFiles().forget(internal);
      wowCache.logicalFiles().register(PageFileNames.layout28(before.identity()), internal,
          false, before.logicalExtent(), before.diskExtent(), false);
    }
    final var state = wowCache.logicalFiles().live(internal);
    final long logicalExtent = state.logicalExtent();
    final long diskExtent = state.diskExtent();
    final var handle = spyAsyncHandle(id);
    final var unlink = failNextUnlink(handle, path, () -> {
    });

    assertCause(assertThrows(WriteCacheException.class, () -> wowCache.deleteFile(id)), unlink);
    assertEquals(0, unlink.getSuppressed().length);
    assertSame(handle, files.get(id));
    assertTrue(handle.isOpen());
    final var after = wowCache.logicalFiles().live(internal);
    assertSame(state, after);
    assertEquals(logicalExtent, after.logicalExtent());
    assertEquals(diskExtent, after.diskExtent());
    assertSame(replaceToken ? null : before.extents(), pendingOwners().get(internal));
    assertRetainedSegments(Set.of(1L));
    wowCache.deleteFile(id);
    assertFalse(Files.exists(path));
    assertNull(pendingOwners().get(internal));
    assertRetainedSegments(Set.of());
  }

  @Test
  public void orderedDeleteSuppressesReopenFailureAndDoesNotRecreateMissingPath()
      throws Exception {
    final long id = preparePendingPage(9418, null);
    wowCache.flush(id);
    final int internal = wowCache.internalFileId(id);
    final var before = wowCache.logicalFiles().live(internal);
    final var path = pendingPath(id);
    final var handle = spyAsyncHandle(id);
    final var unlink = failNextUnlink(handle, path, () -> Files.delete(path));

    assertCause(assertThrows(WriteCacheException.class, () -> wowCache.deleteFile(id)), unlink);
    assertEquals(1, unlink.getSuppressed().length);
    assertTrue(unlink.getSuppressed()[0].getCause() instanceof java.nio.file.NoSuchFileException);
    assertFalse(handle.isOpen());
    assertNull(files.get(id));
    assertFalse(Files.exists(path));
    assertSame(before, wowCache.logicalFiles().live(internal));
    wowCache.deleteFile(id);
    assertTrue(wowCache.logicalFiles().deleted(internal));
  }

  @Test
  public void orderedDeleteClosesReopenedChannelWhenRegistrationFails() throws Exception {
    final long id = preparePendingPage(9419, null);
    wowCache.flush(id);
    final int internal = wowCache.internalFileId(id);
    final var before = wowCache.logicalFiles().live(internal);
    final var path = pendingPath(id);
    final var handle = spyAsyncHandle(id);
    final var unlink = failNextUnlink(handle, path, () -> {
    });
    final var rejected = new InterruptedException("File admission interrupted");
    files = Mockito.spy(files);
    setCacheField("files", files);
    Mockito.doAnswer(call -> {
      assertTrue(handle.isOpen());
      throw rejected;
    }).when(files).add(id, handle);

    try {
      assertCause(assertThrows(WriteCacheException.class, () -> wowCache.deleteFile(id)), unlink);
      assertArrayEquals(new Throwable[] {rejected}, unlink.getSuppressed());
      assertFalse(handle.isOpen());
      assertNull(files.get(id));
      assertSame(before, wowCache.logicalFiles().live(internal));
      assertTrue(Files.exists(path));
      Mockito.verify(handle).open();
      Mockito.verify(handle, Mockito.never()).synch();
      wowCache.deleteFile(id);
      assertFalse(Files.exists(path));
    } finally {
      handle.closeAfterFailedCreate();
    }
  }

  @Test
  public void orderedDeleteKeepsPublishedChannelWhenRegistrationThrowsAfterInsertion()
      throws Exception {
    final long id = preparePublishedFileWithRetainedPrefixAndTail();
    final var path = pendingPath(id);
    final var handle = spyAsyncHandle(id);
    final var unlink = failNextUnlink(handle, path, () -> {
    });
    final var registrationFailure = new AssertionError("Admission failed after insertion");
    files = Mockito.spy(files);
    setCacheField("files", files);
    Mockito.doAnswer(call -> {
      call.callRealMethod();
      throw registrationFailure;
    }).when(files).add(id, handle);

    final var error = assertThrows(WriteCacheException.class, () -> wowCache.deleteFile(id));
    assertSame(unlink, error.getCause().getCause());
    assertArrayEquals(new Throwable[] {registrationFailure}, unlink.getSuppressed());
    assertSame(handle, files.get(id));
    assertTrue(handle.isOpen());
    assertUncachedPublishedPageReadable(id, handle);
    wowCache.deleteFile(id);
    assertFalse(Files.exists(path));
  }

  @Test
  public void orderedDeleteRepairDoesNotRunDuringWholeCacheDelete() throws Exception {
    final long id = preparePendingPage(9420, null);
    wowCache.flush(id);
    final var path = pendingPath(id);
    final var handle = spyAsyncHandle(id);
    final var unlink = failNextUnlink(handle, path, () -> {
    });

    assertCause(assertThrows(WriteCacheException.class, wowCache::delete), unlink);
    assertEquals(0, unlink.getSuppressed().length);
    Mockito.verify(handle, Mockito.never()).open();
    assertFalse(handle.isOpen());
    assertNull(files.get(id));
    assertTrue(Files.exists(path));
  }

  @Test
  public void orderedDeleteDoesNotReopenAfterHandleFreeUnlinkFailure() throws Exception {
    final long id = preparePendingPage(9421, null);
    final int internal = wowCache.internalFileId(id);
    final var path = pendingPath(id);
    Files.createDirectory(path);
    final var child = Files.createFile(path.resolve("blocks-unlink"));
    wowCache = Mockito.spy(wowCache);
    final var attempted = new AtomicReference<File>();
    Mockito.doAnswer(call -> {
      final var file = Mockito.spy((File) call.callRealMethod());
      attempted.set(file);
      return file;
    }).when(wowCache).createFileHandle(any(), Mockito.eq(internal));

    try {
      assertThrows(WriteCacheException.class, () -> wowCache.deleteFile(id));
      Mockito.verify(attempted.get(), Mockito.never()).open();
      assertNull(files.get(id));
      assertTrue(wowCache.exists(id));
      assertTrue(Files.exists(child));
    } finally {
      Files.deleteIfExists(child);
    }
    wowCache.deleteFile(id);
    assertFalse(Files.exists(path));
  }

  private AsyncFile spyAsyncHandle(long id) throws Exception {
    final var original = (AsyncFile) files.remove(id);
    final var handle = Mockito.mockingDetails(original).isSpy() ? original : Mockito.spy(original);
    files.add(id, handle);
    return handle;
  }

  private static IOException failNextUnlink(AsyncFile handle, Path path, FlushAction afterClose)
      throws Exception {
    final var unlink = new java.nio.file.AccessDeniedException(path.toString());
    Mockito.doAnswer(call -> {
      // Match real deletion: close the real channel without forcing before unlink fails.
      handle.closeAfterFailedCreate();
      assertFalse(handle.isOpen());
      afterClose.run();
      throw unlink;
    }).doCallRealMethod().when(handle).delete();
    return unlink;
  }

  private CountDownLatch witnessDeleteSubmission() {
    if (!Mockito.mockingDetails(wowCache).isSpy()) {
      wowCache = Mockito.spy(wowCache);
    }
    final var submitted = new CountDownLatch(1);
    Mockito.doAnswer(call -> {
      final var future = call.callRealMethod();
      submitted.countDown();
      return future;
    }).when(wowCache).submitDeleteStep(Mockito.anyLong());
    return submitted;
  }

  private Object cacheField(String name) throws ReflectiveOperationException {
    final var field = WOWCache.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(wowCache);
  }

  private Map<?, ?> pendingOwners() throws ReflectiveOperationException {
    return (Map<?, ?>) cacheField("pendingCreationHandles");
  }

  private IntOpenHashSet savedRegistry() throws ReflectiveOperationException {
    final var read = WOWCache.class.getDeclaredMethod("readNonDurableRegistry");
    read.setAccessible(true);
    return (IntOpenHashSet) read.invoke(wowCache);
  }

  @Test
  public void orderedShrinkWaitsForHandleOwnershipAndFirstPublication() throws Exception {
    final long id = preparePendingPage(9344, null);
    cacheUnwrittenImage(id, 1, 119);
    files = Mockito.spy(files);
    setCacheField("files", files);
    final var gate = new ShrinkGate();
    Mockito.doAnswer(call -> {
      call.callRealMethod();
      gate.block();
      return null;
    }).when(files).add(eq(id), any());
    assertShrinkAfterWrite(id, 119, gate);
  }

  @Test
  public void orderedShrinkClampsExtentAfterSurvivingPageCompletion() throws Exception {
    final long id = preparePendingPage(9345, null);
    cacheUnwrittenImage(id, 1, 119);
    wowCache = Mockito.spy(wowCache);
    final var gate = new ShrinkGate();
    Mockito.doAnswer(call -> {
      final File original = (File) call.callRealMethod();
      final var file = mock(File.class, org.mockito.AdditionalAnswers.delegatesTo(original));
      Mockito.doAnswer(write -> {
        final var result = original.write(write.getArgument(0));
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          result.await();
          gate.block();
        };
      }).when(file).write(Mockito.anyList());
      return file;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    gate.release.countDown();
    wowCache.flush(id);
    gate.reset();
    cacheUnwrittenImage(id, 1, 219);
    assertShrinkAfterWrite(id, 219, gate);
    cacheUnwrittenImage(id, 1, 319);
    flushWithSelector(id, 1);
    assertShrunkPrefix(id, 319);

    // Regrow with a reader-owned page at the old boundary. Only page 3 is exclusive.
    cacheUnwrittenImage(id, 2, 421);
    cacheUnwrittenImage(id, 3, 528);
    final var barrier = wowCache.loadIfPresent(id, 2, false);
    assertNotNull(barrier);
    try {
      flushWithSelector(id, 1);
      assertEquals(File.HEADER_SIZE + 4L * PAGE_SIZE, Files.size(pendingPath(id)));
      assertMaterializedContents(id, 4, Map.of(1, 319, 2, 421, 3, 528));
    } finally {
      barrier.decrementReadersReferrer();
    }
  }

  private void assertShrinkAfterWrite(long id, int value, ShrinkGate gate) throws Exception {
    final var submitted = witnessShrinkSubmission();
    final var flush = flushExecutor().submit(new FileFlushTask(wowCache,
        Set.of(wowCache.internalFileId(id))));
    try {
      awaitShrinkGate(gate.entered);
      final var shrink = asyncFileExecutor.submit(() -> wowCache.shrinkFile(id, 2L * PAGE_SIZE));
      awaitShrinkGate(submitted);
      gate.release.countDown();
      flush.get(10, TimeUnit.SECONDS);
      assertTrue(shrink.get(10, TimeUnit.SECONDS));
      assertShrunkPrefix(id, value);
    } finally {
      gate.release.countDown();
    }
  }

  @Test
  public void orderedShrinkPrecedesQueuedCreationOnAbsentAndPresentFiles() throws Exception {
    final long id = preparePendingPage(9346, null);
    cacheUnwrittenImage(id, 1, 119);
    final var path = pendingPath(id);
    assertShrinkBeforeCreation(id, id, () -> {
      assertFalse(Files.exists(path));
      final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));
      assertEquals(2, state.logicalExtent());
      assertEquals(0, state.diskExtent());
    });
    assertShrunkPrefix(id, 119);

    cacheUnwrittenImage(id, 2, 728);
    wowCache.flush(id);
    assertEquals(File.HEADER_SIZE + 3L * PAGE_SIZE, Files.size(path));
    final long next = preparePendingPage(9347, null);
    assertShrinkBeforeCreation(id, next,
        () -> assertEquals(File.HEADER_SIZE + 2L * PAGE_SIZE, Files.size(path)));
    assertShrunkPrefix(id, 119);
    assertMaterializedContents(next, 3, 2, 728);
  }

  private void assertShrinkBeforeCreation(long shrinkId, long createId, FlushAction beforeCreation)
      throws Exception {
    final var submitted = witnessShrinkSubmission();
    final var gate = new ShrinkGate();
    final var blocker = flushExecutor().submit(gate::block);
    try {
      awaitShrinkGate(gate.entered);
      final var shrink = asyncFileExecutor.submit(
          () -> wowCache.shrinkFile(shrinkId, 2L * PAGE_SIZE));
      awaitShrinkGate(submitted);
      final var creation = flushExecutor().submit(() -> {
        beforeCreation.run();
        return new FileFlushTask(wowCache, Set.of(wowCache.internalFileId(createId))).call();
      });
      gate.release.countDown();
      blocker.get(10, TimeUnit.SECONDS);
      assertTrue(shrink.get(10, TimeUnit.SECONDS));
      creation.get(10, TimeUnit.SECONDS);
    } finally {
      gate.release.countDown();
    }
  }

  @Test
  public void orderedShrinkRejectsUnownedHandleAndCallsFromCommitExecutorThread()
      throws Exception {
    final long id = preparePendingPage(9348, null);
    final var lockField = WOWCache.class.getDeclaredField("filesLock");
    lockField.setAccessible(true);
    final var filesLock = (ReadersWriterSpinLock) lockField.get(wowCache);
    filesLock.acquireWriteLock();
    try {
      onFlushExecutor(() -> assertThrows(IllegalStateException.class,
          () -> wowCache.shrinkFile(id, PAGE_SIZE)));
    } finally {
      filesLock.releaseWriteLock();
    }
    final var unowned = mock(File.class);
    when(unowned.isOpen()).thenReturn(true);
    files.add(id, unowned);
    try {
      final var error = assertThrows(IOException.class, () -> wowCache.shrinkFile(id, PAGE_SIZE));
      assertTrue(error.getMessage().contains("unowned pending page file"));
      Mockito.verify(unowned, Mockito.never()).shrink(Mockito.anyLong());
      assertEquals(3, wowCache.getFilledUpTo(id));
    } finally {
      files.remove(id);
    }
  }

  @Test
  public void orderedShrinkInterruptedWaiterStillClearsReadCacheTail() throws Exception {
    assertInterruptedShrink(false);
  }

  @Test
  public void orderedShrinkInterruptedWaiterStillReceivesTruncateFailure() throws Exception {
    assertInterruptedShrink(true);
  }

  @Test
  public void orderedShrinkPreservesUncheckedStepFailuresAndWrapsCheckedFailure() throws Exception {
    final long id = preparePendingPage(9349, null);
    wowCache.flush(id);
    final var path = pendingPath(id);
    files = Mockito.spy(files);
    setCacheField("files", files);
    final var runtime = new IllegalStateException("Cannot acquire file");
    final var error = new AssertionError("File container invariant failed");
    final var interrupted = new InterruptedException("File slot wait was interrupted");
    Mockito.doThrow(runtime).doThrow(error).doThrow(interrupted).doCallRealMethod()
        .when(files).acquire(id);
    final var clearTail = mock(Runnable.class);

    assertSame(runtime, assertThrows(IllegalStateException.class,
        () -> wowCache.shrinkFile(id, PAGE_SIZE, clearTail)));
    assertSame(error, assertThrows(AssertionError.class,
        () -> wowCache.shrinkFile(id, PAGE_SIZE, clearTail)));
    final var wrapped = assertThrows(WriteCacheException.class,
        () -> wowCache.shrinkFile(id, PAGE_SIZE, clearTail));
    assertSame(interrupted, wrapped.getCause());
    assertTrue(wrapped.getMessage().contains("File shrink failed"));
    assertFalse(Thread.currentThread().isInterrupted());
    assertEquals(3, wowCache.getFilledUpTo(id));
    assertEquals(File.HEADER_SIZE + 3L * PAGE_SIZE, Files.size(path));
    Mockito.verifyNoInteractions(clearTail);
  }

  @Test
  public void orderedShrinkReportsInterruptDuringCallerCleanupAfterPhysicalTruncate()
      throws Exception {
    final long id = preparePendingPage(9349, null);
    cacheUnwrittenImage(id, 1, 119);
    wowCache.flush(id);
    final var caller = Thread.currentThread();
    final var cleanup = new AtomicReference<Thread>();
    final var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    final var cache = wowCache;
    wowCache = Mockito.spy(cache);
    Mockito.doAnswer(call -> {
      final Runnable clearTail = call.getArgument(2);
      return cache.shrinkFile(id, 2L * PAGE_SIZE, () -> {
        assertSame(caller, Thread.currentThread());
        assertEquals(2, cache.getFilledUpTo(id));
        caller.interrupt();
        clearTail.run();
        cleanup.set(Thread.currentThread());
      });
    }).when(wowCache).shrinkFile(eq(id), eq(2L * PAGE_SIZE), any(Runnable.class));
    try {
      final var tail = readCache.loadForRead(id, 2, wowCache, false);
      assertNotNull(tail);
      readCache.releaseFromRead(tail);
      assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));
      assertThrows(ThreadInterruptedException.class,
          () -> readCache.shrinkFile(id, 2L * PAGE_SIZE, wowCache));
      assertTrue(Thread.interrupted());
      assertSame(caller, cleanup.get());
      assertNull(readCache.getCacheEntryOptimistic(id, 2));
      assertShrunkPrefix(id, 119);
    } finally {
      Thread.interrupted();
      readCache.clear();
    }
  }

  @Test
  public void orderedShrinkRestoresInterruptConsumedByOverflowWait() throws Exception {
    final long id = preparePendingPage(9349, null);
    cacheUnwrittenImage(id, 1, 119);
    wowCache.flush(id);
    cacheUnwrittenImage(id, 0, 0);
    wowCache = Mockito.spy(wowCache);
    final var overflow = new ShrinkGate();
    Mockito.doAnswer(call -> {
      overflow.block();
      return call.callRealMethod();
    }).when(wowCache).executeFlush(any(), any());

    final var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    final var caller = new AtomicReference<Thread>();
    try {
      final var tail = readCache.loadForRead(id, 2, wowCache, false);
      assertNotNull(tail);
      readCache.releaseFromRead(tail);
      assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));

      setCacheField("exclusiveWriteCacheMaxSize", 0);
      final var shrink = asyncFileExecutor.submit(() -> {
        caller.set(Thread.currentThread());
        try {
          final var failure = assertThrows(ThreadInterruptedException.class,
              () -> readCache.shrinkFile(id, 2L * PAGE_SIZE, wowCache));
          assertTrue(
              failure.getMessage().contains("Check of write cache overflow was interrupted"));
          assertTrue(failure.getCause() instanceof InterruptedException);
          assertTrue("Overflow wait interrupt must be restored",
              Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
        }
      });
      try {
        // The flush task witnesses submission from cleanup. Hold it so the latch cannot open.
        awaitShrinkGate(overflow.entered);
        assertEquals(File.HEADER_SIZE + 2L * PAGE_SIZE, Files.size(pendingPath(id)));
        final var waitingCaller = caller.get();
        awaitOverflowWait(waitingCaller);
        waitingCaller.interrupt();
        shrink.get(10, TimeUnit.SECONDS);
        assertNull(readCache.getCacheEntryOptimistic(id, 2));
      } finally {
        overflow.release.countDown();
      }
      onFlushExecutor(() -> {
      });
      assertShrunkPrefix(id, 119);
    } finally {
      overflow.release.countDown();
      readCache.clear();
    }
  }

  private static void awaitOverflowWait(Thread caller) {
    final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (caller.getState() == Thread.State.WAITING
          && java.util.Arrays.stream(caller.getStackTrace())
              .anyMatch(frame -> frame.getClassName().equals(WOWCache.class.getName())
                  && frame.getMethodName().equals("checkCacheOverflow"))) {
        return;
      }
      Thread.yield();
    }
    throw new AssertionError("Caller did not enter the overflow latch wait");
  }

  @Test
  public void orderedShrinkRetainsPendingTailUntilPathIsConfirmedAbsent() throws Exception {
    final long id = preparePendingPage(9349, null);
    final var path = pendingPath(id);
    final byte[] foreign = {1, 4, 9, 16};
    Files.write(path, foreign);
    final var dwl = mock(DoubleWriteLog.class);
    when(dwl.write(any(), any(), any())).thenReturn(new DoubleWriteLog.AppendResult(1L, false, 0));
    setCacheField("doubleWriteLog", dwl);
    assertThrows(InvocationTargetException.class, () -> flushWithSelector(id, 1));
    final var retention = pageRetentionState();
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));
    final var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    try {
      assertNull(files.get(id));
      assertFalse(state.diskPresent());
      assertTrue(retention.hasRetainedContents(id));
      final var tail = readCache.loadForRead(id, 2, wowCache, false);
      assertNotNull(tail);
      readCache.releaseFromRead(tail);
      assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));

      final var failure = assertThrows(IOException.class,
          () -> readCache.shrinkFile(id, PAGE_SIZE, wowCache));
      assertTrue(failure.getMessage().contains("Cannot confirm absence of pending page file"));
      assertArrayEquals(foreign, Files.readAllBytes(path));
      assertEquals(3, state.logicalExtent());
      assertEquals(0, state.diskExtent());
      assertTrue(retention.hasRetainedContents(id));
      assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));

      Files.delete(path);
      readCache.shrinkFile(id, PAGE_SIZE, wowCache);
      assertTrue(Files.notExists(path));
      assertNull(files.get(id));
      assertEquals(1, state.logicalExtent());
      assertEquals(0, state.diskExtent());
      assertFalse(retention.hasRetainedContents(id));
      assertNull(readCache.getCacheEntryOptimistic(id, 2));
    } finally {
      readCache.clear();
    }
  }

  @Test
  public void orderedShrinkAtLogicalExtentDoesNotSubmitOrClearTail() throws Exception {
    final long id = preparePendingPage(9349, null);
    wowCache = Mockito.spy(wowCache);
    final var clearTail = mock(Runnable.class);

    assertFalse(wowCache.shrinkFile(id, 3L * PAGE_SIZE, clearTail));
    Mockito.verify(wowCache, Mockito.never())
        .submitShrinkStep(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
    Mockito.verifyNoInteractions(clearTail);
    assertEquals(3, wowCache.getFilledUpTo(id));
    assertTrue(Files.notExists(pendingPath(id)));
  }

  @Test
  public void orderedShrinkRejectsClosedCacheBeforeSubmitting() throws Exception {
    final long id = preparePendingPage(9349, null);
    wowCache.close();
    try {
      wowCache = Mockito.spy(wowCache);
      final var clearTail = mock(Runnable.class);

      final var failure = assertThrows(StorageException.class,
          () -> wowCache.shrinkFile(id, PAGE_SIZE, clearTail));
      assertTrue(failure.getMessage().contains("Write cache is closed"));
      Mockito.verify(wowCache, Mockito.never())
          .submitShrinkStep(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
      Mockito.verifyNoInteractions(clearTail);
    } finally {
      // The fixture must not call delete() on the already closed cache.
      wowCache = null;
    }
  }

  @Test
  public void orderedShrinkRejectsMissingLogicalFileAndHandle() throws Exception {
    preparePendingPage(9349, null);
    final long id = wowCache.externalFileId(
        new PageFileIdentity(PageFileRole.RECORD_DATA, 9350).internalId());
    final var clearTail = mock(Runnable.class);

    final var failure = assertThrows(StorageException.class,
        () -> wowCache.shrinkFile(id, PAGE_SIZE, clearTail));
    assertTrue(failure.getMessage().contains("no file entry is open"));
    Mockito.verifyNoInteractions(clearTail);
  }

  @Test
  public void orderedShrinkRejectsOpenHandleWithoutLiveEntryEvenOnRetry() throws Exception {
    final long id = preparePublishedFileWithRetainedPrefixAndTail();
    final int internalId = wowCache.internalFileId(id);
    final var table = wowCache.logicalFiles();
    final var name = PageFileNames.layout28(table.live(internalId).identity());
    final var path = pendingPath(id);
    final var bytes = Files.readAllBytes(path);
    final var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    try {
      final var tail = readCache.loadForRead(id, 2, wowCache, false);
      assertNotNull(tail);
      readCache.releaseFromRead(tail);
      table.forget(internalId);
      assertNotNull(files.get(id));

      for (int attempt = 0; attempt < 2; attempt++) {
        final var failure = assertThrows(IOException.class,
            () -> readCache.shrinkFile(id, PAGE_SIZE, wowCache));
        assertTrue(failure.getMessage().contains("without a live logical entry"));
        assertEquals(File.HEADER_SIZE + 3L * PAGE_SIZE, Files.size(path));
        assertArrayEquals(bytes, Files.readAllBytes(path));
        assertRetainedSegments(Set.of(41L, 42L));
        assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));
      }
    } finally {
      table.register(name, internalId, false, 3, 3, true);
      readCache.clear();
    }
  }

  @Test
  public void orderedShrinkDiscardsPublishedTailRetentionOnlyAfterConfirmedTruncate()
      throws Exception {
    final long id = preparePublishedFileWithRetainedPrefixAndTail();
    final var path = pendingPath(id);
    final var bytes = Files.readAllBytes(path);
    final var original = files.remove(id);
    final var file = mock(File.class, org.mockito.AdditionalAnswers.delegatesTo(original));
    // The first truncate returns without changing physical length.
    Mockito.doNothing().doAnswer(call -> {
      original.shrink(call.getArgument(0));
      return null;
    }).when(file).shrink(2L * PAGE_SIZE);
    files.add(id, file);
    try {
      final var failure = assertThrows(IOException.class,
          () -> wowCache.shrinkFile(id, 2L * PAGE_SIZE));
      assertTrue(failure.getMessage().contains("still exceeds"));
      assertEquals(File.HEADER_SIZE + 3L * PAGE_SIZE, Files.size(path));
      assertArrayEquals(bytes, Files.readAllBytes(path));
      assertRetainedSegments(Set.of(41L, 42L));

      assertTrue(wowCache.shrinkFile(id, 2L * PAGE_SIZE));
      assertShrunkPrefix(id, 119);
      assertRetainedSegments(Set.of(41L));
    } finally {
      files.remove(id);
      files.add(id, original);
    }
  }

  @Test
  public void orderedShrinkWrapsPublishedDiscardErrorWithoutChangingFileOrExtents()
      throws Exception {
    final long id = preparePublishedFileWithRetainedPrefixAndTail();
    final var path = pendingPath(id);
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));

    assertDiscardLinkageErrorIsWrapped(id);

    assertEquals(File.HEADER_SIZE + 3L * PAGE_SIZE, Files.size(path));
    assertEquals(3, state.logicalExtent());
    assertEquals(3, state.diskExtent());
    assertRetainedSegments(Set.of(41L, 42L));
  }

  @Test
  public void orderedShrinkWrapsDiscardErrorWithoutChangingExtentsInPendingFixture()
      throws Exception {
    final long id = preparePendingPage(9351, null);
    final var path = pendingPath(id);
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(id));
    // Retain the tail through an attempted first write that cannot create its file.
    Files.write(path, new byte[] {1});
    final var dwl = mock(DoubleWriteLog.class);
    when(dwl.write(any(), any(), any())).thenReturn(new DoubleWriteLog.AppendResult(42L, false, 0));
    setCacheField("doubleWriteLog", dwl);
    assertThrows(InvocationTargetException.class, () -> flushWithSelector(id, 1));
    Files.delete(path);
    assertNull(files.get(id));
    assertRetainedSegments(Set.of(42L));

    assertDiscardLinkageErrorIsWrapped(id);

    assertTrue(Files.notExists(path));
    assertNull(files.get(id));
    assertEquals(3, state.logicalExtent());
    assertEquals(0, state.diskExtent());
    assertRetainedSegments(Set.of(42L));
  }

  private void assertDiscardLinkageErrorIsWrapped(long id) throws Exception {
    wowCache = Mockito.spy(wowCache);
    final var error = new LinkageError("Discard body failed");
    final var commitThread = flushExecutor().submit(Thread::currentThread)
        .get(10, TimeUnit.SECONDS);
    final int internalId = wowCache.internalFileId(id);
    Mockito.doAnswer(call -> {
      assertSame("Discard must run on the commitExecutor() thread",
          commitThread, Thread.currentThread());
      throw error;
    }).doCallRealMethod().when(wowCache).doRemoveCachePages(internalId, 2);

    final var wrapped = assertThrows(WriteCacheException.class,
        () -> wowCache.shrinkFile(id, 2L * PAGE_SIZE));
    assertCause(wrapped, error);
    assertTrue(wrapped.getMessage().contains("File data removal was abnormally terminated"));
    assertTrue(wrapped.getMessage().contains(storageName));
  }

  @Test
  public void orderedShrinkPropagatesSameErrorFromPhysicalTruncate() throws Exception {
    final long id = preparePendingPage(9351, null);
    wowCache.flush(id);
    final var file = files.get(id);
    final var channelField = AsyncFile.class.getDeclaredField("fileChannel");
    channelField.setAccessible(true);
    final var realChannel = (AsynchronousFileChannel) channelField.get(file);
    final var channel = mock(AsynchronousFileChannel.class,
        org.mockito.AdditionalAnswers.delegatesTo(realChannel));
    final var error = new LinkageError("Channel truncate failed");
    Mockito.doThrow(error).when(channel).truncate(Mockito.anyLong());

    try {
      channelField.set(file, channel);
      assertSame(error, assertThrows(LinkageError.class,
          () -> wowCache.shrinkFile(id, 2L * PAGE_SIZE)));
    } finally {
      channelField.set(file, realChannel);
    }
  }

  private long preparePublishedFileWithRetainedPrefixAndTail() throws Exception {
    final long id = preparePendingPage(9351, null);
    cacheUnwrittenImage(id, 1, 119);
    wowCache.flush(id);
    assertMaterializedContents(id, 3, Map.of(1, 119, 2, 728));

    final var dwl = mock(DoubleWriteLog.class);
    when(dwl.write(any(), any(), any()))
        .thenReturn(new DoubleWriteLog.AppendResult(41L, false, 0),
            new DoubleWriteLog.AppendResult(42L, false, 0));
    setCacheField("doubleWriteLog", dwl);
    setCacheField("callFsync", true);
    cacheUnwrittenImage(id, 1, 119);
    flushWithSelector(id, 1);
    cacheUnwrittenImage(id, 2, 728);
    flushWithSelector(id, 1);
    assertRetainedSegments(Set.of(41L, 42L));
    return id;
  }

  private void assertRetainedSegments(Set<Long> expected) throws Exception {
    final var retention = pageRetentionState();
    onFlushExecutor(() -> assertEquals("Retained DWL segments",
        expected, retention.retainedSegments()));
  }

  private void assertInterruptedShrink(boolean fail) throws Exception {
    final long id = preparePendingPage(9349, null);
    cacheUnwrittenImage(id, 1, 119);
    wowCache.flush(id);
    final var file = files.get(id);
    final var channelField = AsyncFile.class.getDeclaredField("fileChannel");
    channelField.setAccessible(true);
    final var realChannel = (AsynchronousFileChannel) channelField.get(file);
    final var gate = new ShrinkGate();
    final var cause = new IOException("Cannot truncate channel");
    final var channel = mock(AsynchronousFileChannel.class,
        org.mockito.AdditionalAnswers.delegatesTo(realChannel));
    Mockito.doAnswer(call -> {
      gate.block();
      if (fail) {
        throw cause;
      }
      return realChannel.truncate(call.getArgument(0));
    }).when(channel).truncate(Mockito.anyLong());
    final var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    final var caller = new AtomicReference<Thread>();
    final var tail = readCache.loadForRead(id, 2, wowCache, false);
    readCache.releaseFromRead(tail);
    channelField.set(file, channel);
    try {
      final var shrink = asyncFileExecutor.submit(() -> {
        caller.set(Thread.currentThread());
        try {
          if (fail) {
            final var failure = assertThrows(IOException.class,
                () -> readCache.shrinkFile(id, 2L * PAGE_SIZE, wowCache));
            assertSame(cause, failure);
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0] instanceof ThreadInterruptedException);
          } else {
            assertThrows(ThreadInterruptedException.class,
                () -> readCache.shrinkFile(id, 2L * PAGE_SIZE, wowCache));
          }
          assertTrue(Thread.currentThread().isInterrupted());
        } finally {
          Thread.interrupted();
        }
      });
      try {
        awaitShrinkGate(gate.entered);
        caller.get().interrupt();
      } finally {
        gate.release.countDown();
        shrink.get(10, TimeUnit.SECONDS);
      }
      onFlushExecutor(() -> {
      });
      if (fail) {
        assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));
        assertThrows(IOException.class, () -> readCache.shrinkFile(id, 2L * PAGE_SIZE, wowCache));
        assertSame(tail, readCache.getCacheEntryOptimistic(id, 2));
        assertEquals(3, wowCache.getFilledUpTo(id));
        assertEquals(File.HEADER_SIZE + 3L * PAGE_SIZE, Files.size(pendingPath(id)));
      } else {
        assertNull(readCache.getCacheEntryOptimistic(id, 2));
        assertShrunkPrefix(id, 119);
      }
    } finally {
      channelField.set(file, realChannel);
      readCache.clear();
    }
  }

  private CountDownLatch witnessShrinkSubmission() {
    if (!Mockito.mockingDetails(wowCache).isSpy()) {
      wowCache = Mockito.spy(wowCache);
    }
    final var submitted = new CountDownLatch(1);
    Mockito.doAnswer(call -> {
      final var result = call.callRealMethod();
      submitted.countDown();
      return result;
    }).when(wowCache).submitShrinkStep(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt());
    return submitted;
  }

  private void assertShrunkPrefix(long id, int value) throws Exception {
    assertEquals(File.HEADER_SIZE + 2L * PAGE_SIZE, Files.size(pendingPath(id)));
    assertEquals(2, wowCache.getFilledUpTo(id));
    assertMaterializedContents(id, 2, 1, value);
    assertNull(wowCache.loadIfPresent(id, 2, false));
  }

  private static void awaitShrinkGate(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      throw new AssertionError(interrupted);
    }
  }

  private static final class ShrinkGate {
    private CountDownLatch entered = new CountDownLatch(1);
    private CountDownLatch release = new CountDownLatch(1);

    void reset() {
      entered = new CountDownLatch(1);
      release = new CountDownLatch(1);
    }

    void block() {
      entered.countDown();
      awaitShrinkGate(release);
    }
  }

  private long prepareUnpublishedHandleWithRetainedTail() throws Exception {
    final long id = preparePendingPage(9343, null);
    final var dwl = mock(DoubleWriteLog.class);
    when(dwl.write(any(), any(), any())).thenReturn(new DoubleWriteLog.AppendResult(1L, false, 0));
    setCacheField("doubleWriteLog", dwl);
    wowCache = Mockito.spy(wowCache);
    Mockito.doAnswer(inv -> {
      final File file = (File) inv.callRealMethod();
      final var wrapper = Mockito.spy(file);
      Mockito.doAnswer(write -> {
        final List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        final var result =
            (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) write.callRealMethod();
        if (buffers.getFirst().first != 2L * PAGE_SIZE) {
          return result;
        }
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          result.await();
          throw new IllegalStateException("First write completion failed");
        };
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    assertThrows(InvocationTargetException.class, () -> flushWithSelector(id, 1));
    assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskPresent());
    assertEquals(3L * PAGE_SIZE, files.get(id).getUnderlyingFileSize());
    return id;
  }

  private PageRetentionState pageRetentionState() throws Exception {
    final var field = WOWCache.class.getDeclaredField("pageRetentionState");
    field.setAccessible(true);
    return (PageRetentionState) field.get(wowCache);
  }

  // Both selectors force the maximum creating/changing dependency before DWL, creation,
  // data submission and the existing file-force boundary. Null creation adds no dependency.
  @Test
  public void pendingBackgroundDependencyOrderAndForce() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      for (int variant = 0; variant < 3; variant++) {
        final var creating = variant == 2 ? null : new LogSequenceNumber(8, 40);
        final var changing = new LogSequenceNumber(variant == 0 ? 7 : 9, 20);
        final var required = variant == 0 ? creating : changing;
        final long id = preparePendingPage(9350 + selector * 3 + variant, creating);
        cacheUnwrittenImage(id, 0, 100);
        cacheUnwrittenImage(id, 1, 101);
        final var setEnd = CachePointer.class.getDeclaredMethod("setEndLSN",
            LogSequenceNumber.class);
        setEnd.setAccessible(true);
        for (int page = 0; page < 3; page++) {
          final var pointer = wowCache.loadIfPresent(id, page, false);
          // The largest changing dependency is in the middle, not at either scan endpoint.
          setEnd.invoke(pointer, page == 1 ? changing : new LogSequenceNumber(6, page + 1));
          pointer.decrementReadersReferrer();
        }
        final var events = new java.util.ArrayList<String>();
        final var flushed = new java.util.concurrent.atomic.AtomicReference<>(
            new LogSequenceNumber(5, 0));
        final var advances = new java.util.ArrayDeque<>(List.of(
            new LogSequenceNumber(6, 2), new LogSequenceNumber(7, 20),
            new LogSequenceNumber(8, 40), new LogSequenceNumber(9, 20)));
        final var wal = Mockito.spy(writeAheadLog);
        when(wal.getFlushedLsn()).thenAnswer(inv -> flushed.get());
        Mockito.doAnswer(inv -> {
          events.add("wal");
          flushed.set(advances.removeFirst());
          return null;
        }).when(wal).flush();
        final var walField = WOWCache.class.getDeclaredField("writeAheadLog");
        walField.setAccessible(true);
        walField.set(wowCache, wal);
        final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
        dwlField.setAccessible(true);
        final var original = dwlField.get(wowCache);
        final var dwl = mock(DoubleWriteLog.class);
        when(dwl.write(any(), any(), any())).thenAnswer(inv -> {
          assertEquals(required, flushed.get());
          events.add("dwl");
          return new DoubleWriteLog.AppendResult(1L, true, 1L);
        });
        dwlField.set(wowCache, dwl);
        final var sync = WOWCache.class.getDeclaredField("callFsync");
        sync.setAccessible(true);
        sync.setBoolean(wowCache, true);
        if (!Mockito.mockingDetails(wowCache).isSpy()) {
          wowCache = Mockito.spy(wowCache);
        }
        Mockito.doAnswer(inv -> {
          final File file = (File) inv.callRealMethod();
          final var wrapper = mock(File.class, Mockito.withSettings()
              .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(file)));
          Mockito.doAnswer(write -> {
            assertTrue(events.contains("create"));
            events.add("data");
            return file.write(write.getArgument(0));
          }).when(wrapper).write(Mockito.anyList());
          Mockito.doAnswer(force -> {
            assertTrue(events.contains("data"));
            events.add("force");
            file.synch();
            return null;
          }).when(wrapper).synch();
          return wrapper;
        }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
        try (var observation = wowCache.observePhysicalCreation(file -> {
          assertEquals(required, flushed.get());
          assertEquals("dwl", events.getLast());
          assertTrue(events.subList(0, events.size() - 1).stream().allMatch("wal"::equals));
          events.add("create");
        })) {
          flushWithSelector(id, selector);
          assertEquals("force", events.getLast());
          assertMaterializedContents(id, 3, Map.of(0, 100, 1, 101, 2, 728));
        } finally {
          dwlField.set(wowCache, original);
          walField.set(wowCache, writeAheadLog);
          sync.setBoolean(wowCache, false);
        }
        wowCache.deleteFile(id);
      }
    }
  }

  // Creation failure from either selector latches the original cause, keeps the only image
  // and its recovery boundary, and prevents a public retry from touching the path again.
  @Test
  public void pendingBackgroundCreationFailureLatchesAndRetainsProtection() throws Exception {
    for (int selector = 1; selector <= 2; selector++) {
      final long id = preparePendingPage(9370 + selector, null);
      final var cause = new IOException("background header failure");
      final var notifications = new java.util.ArrayList<Integer>();
      try (var observation = wowCache.observePhysicalCreation(file -> {
        notifications.add(file);
        throw new java.io.UncheckedIOException(cause);
      })) {
        final int selected = selector;
        assertCause(assertThrows(Exception.class, () -> flushWithSelector(id, selected)), cause);
        assertEquals(Long.valueOf(3), earliestRetainedSegment());
        final var cached = wowCache.loadIfPresent(id, 2, false);
        assertNotNull(cached);
        assertEquals(728, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
        cached.decrementReadersReferrer();
        assertCause(assertThrows(Exception.class, () -> wowCache.flush(id)), cause);
        assertEquals(1, notifications.size());
      } finally {
        final var error = WOWCache.class.getDeclaredField("flushError");
        error.setAccessible(true);
        error.set(wowCache, null);
        wowCache.deleteFile(id);
      }
    }
  }

  @Test(timeout = 60_000)
  public void pendingFixtureBackgroundAdmissionClosesOnlyIfContainerDoesNotOwnHandle()
      throws Exception {
    files = Mockito.spy(files);
    setCacheField("files", files);
    wowCache = Mockito.spy(wowCache);
    final var handle = new AtomicReference<File>();
    Mockito.doAnswer(call -> {
      final var created = (File) call.callRealMethod();
      handle.set(created);
      return created;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    final var commitThread = flushExecutor().submit(Thread::currentThread)
        .get(10, TimeUnit.SECONDS);

    for (final boolean inserted : List.of(false, true)) {
      final long id = preparePendingPage(inserted ? 9373 : 9374, null);
      final int internalId = wowCache.internalFileId(id);
      final var path = pendingPath(id);
      final var failure = new IllegalStateException("Background admission failed");
      Mockito.doAnswer(call -> {
        assertTrue(handle.get().isOpen());
        if (inserted) {
          call.callRealMethod();
        }
        throw failure;
      }).when(files).add(eq(id), any());
      final var creationThread = new AtomicReference<Thread>();
      try (var observation =
          wowCache.observePhysicalCreation(created -> creationThread.set(Thread.currentThread()))) {
        final var reported = assertThrows(Exception.class, () -> flushWithSelector(id, 1));
        final var latched = (PageFileFailure) cacheField("flushError");
        assertNotNull(latched);
        assertSame(failure, latched.getCause());
        assertCause(reported, latched);
        assertSame(commitThread, creationThread.get());
        assertSame(inserted ? handle.get() : null, files.get(id));
        assertEquals("Container ownership decides whether to close", inserted,
            handle.get().isOpen());
        assertEquals(inserted ? 1 : 0, files.openFilesCount());
        assertRegistrationPathCount(1);
        assertArrayEquals(new byte[File.HEADER_SIZE], Files.readAllBytes(path));
        assertNull(pendingOwners().get(internalId));
        assertPendingExtents(id);
        final var cached = wowCache.loadIfPresent(id, 2, false);
        assertNotNull(cached);
        try {
          assertEquals(728, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
        } finally {
          cached.decrementReadersReferrer();
        }
      } finally {
        setCacheField("flushError", null);
        if (files.get(id) == null && handle.get() != null && handle.get().isOpen()) {
          handle.get().close();
        }
        wowCache.deleteFile(id);
      }
    }
  }

  // A real page end LSN must be forced before DWL protection, gap/data writes and file force.
  @Test
  public void gapPathPreservesWalDwlDataForceOrder() throws Exception {
    final long fileId = prepareUnwrittenMaterializedPage();
    final var syncField = WOWCache.class.getDeclaredField("callFsync");
    syncField.setAccessible(true);
    syncField.set(wowCache, true);
    final var events = new java.util.ArrayList<String>();
    final var end = new LogSequenceNumber(8, 20);
    final var cached = wowCache.loadIfPresent(fileId, 2, false);
    final var setEnd = CachePointer.class.getDeclaredMethod("setEndLSN", LogSequenceNumber.class);
    setEnd.setAccessible(true);
    setEnd.invoke(cached, end);
    cached.decrementReadersReferrer();
    final var wal = Mockito.spy(writeAheadLog);
    final var forced = new java.util.concurrent.atomic.AtomicBoolean();
    Mockito.doAnswer(invocation -> forced.get() ? end : null).when(wal).getFlushedLsn();
    Mockito.doAnswer(invocation -> {
      events.add("wal");
      forced.set(true);
      return null;
    }).when(wal).flush();
    final var walField = WOWCache.class.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(wowCache, wal);
    final var dwl = mock(DoubleWriteLog.class);
    when(dwl.write(any(), any(), any())).thenAnswer(invocation -> {
      assertTrue(events.toString(), forced.get());
      events.add("dwl");
      return new DoubleWriteLog.AppendResult(1L, true, 1L);
    });
    final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
    dwlField.setAccessible(true);
    final var originalDwl = dwlField.get(wowCache);
    dwlField.set(wowCache, dwl);
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doAnswer(invocation -> {
      assertTrue(events.toString(), events.contains("dwl"));
      events.add("data");
      return original.write(invocation.getArgument(0));
    }).when(wrapper).write(Mockito.anyList());
    Mockito.doAnswer(invocation -> {
      assertTrue(events.toString(), events.contains("data"));
      events.add("force");
      original.synch();
      return null;
    }).when(wrapper).synch();
    files.add(fileId, wrapper);
    try {
      flushWithSelector(fileId, 1);
      assertEquals(List.of("wal", "dwl", "data", "data", "data", "force"), events);
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
      dwlField.set(wowCache, originalDwl);
    }
  }

  // Both explicit flush forms create absent files, fill gaps and publish only completed data.
  @Test
  public void explicitFlushCreatesPendingFilesAndVerificationIncludesThem() throws Exception {
    final long first = preparePendingPage(9210, null);
    final long second = preparePendingPage(9211, null);
    final var notifications = new java.util.ArrayList<Integer>();
    try (var observation = wowCache.observePhysicalCreation(notifications::add)) {
      wowCache.flush(first);
      assertMaterializedContents(first, 3, 2, 728);
      assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(second)).diskPresent());
      wowCache.flush();
      assertMaterializedContents(second, 3, 2, 728);
      final long third = preparePendingPage(9212, null);
      final var messages = new StringBuilder();
      assertEquals(0, wowCache.checkStoredPages(messages::append).length);
      assertMaterializedContents(third, 3, 2, 728);
      assertTrue(messages.toString().contains("Start verification"));
      assertEquals(3, notifications.size());
      assertEquals(3, new java.util.HashSet<>(notifications).size());
      final var empty = new PageFileIdentity(PageFileRole.RECORD_DATA, 9213);
      wowCache.logicalFiles().register(PageFileNames.layout28(empty), empty.internalId(),
          false, 0, 0, false);
      assertEquals(0, wowCache.checkStoredPages(null).length);
      assertNull(files.get(wowCache.externalFileId(empty.internalId())));
      assertEquals(3, notifications.size());
    }
  }

  // The maximum creating/changing dependency must be durable before DWL and actual creation.
  @Test
  public void pendingCreationForcesMaximumDependencyBeforeDwl() throws Exception {
    final var creating = new LogSequenceNumber(8, 40);
    final var changing = new LogSequenceNumber(9, 20);
    final long first = preparePendingPage(9220, creating);
    final long second = preparePendingPage(9221, null);
    final var pointer = wowCache.loadIfPresent(second, 2, false);
    final var setEnd = CachePointer.class.getDeclaredMethod("setEndLSN", LogSequenceNumber.class);
    setEnd.setAccessible(true);
    setEnd.invoke(pointer, changing);
    pointer.decrementReadersReferrer();
    final var wal = Mockito.spy(writeAheadLog);
    final var flushed = new java.util.concurrent.atomic.AtomicReference<LogSequenceNumber>();
    final var target = new java.util.concurrent.atomic.AtomicReference<>(creating);
    when(wal.getFlushedLsn()).thenAnswer(inv -> flushed.get());
    final var forces = new java.util.concurrent.atomic.AtomicInteger();
    Mockito.doAnswer(inv -> {
      // The cycle-start force has not covered the selected dependency yet.
      flushed.set(forces.incrementAndGet() % 2 == 1 ? new LogSequenceNumber(3, 10) : target.get());
      return null;
    }).when(wal).flush();
    final var walField = WOWCache.class.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(wowCache, wal);
    final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
    dwlField.setAccessible(true);
    final var original = dwlField.get(wowCache);
    final var dwl = mock(DoubleWriteLog.class);
    when(dwl.write(any(), any(), any())).thenAnswer(inv -> {
      assertTrue(flushed.get().compareTo(target.get()) >= 0);
      return new DoubleWriteLog.AppendResult(1L, false, 1L);
    });
    dwlField.set(wowCache, dwl);
    try (var observation = wowCache.observePhysicalCreation(id -> {
      assertTrue(flushed.get().compareTo(target.get()) >= 0);
      assertFalse(wowCache.logicalFiles().live(id).diskPresent());
    })) {
      wowCache.flush(first);
      assertEquals(creating, flushed.get());
      target.set(changing);
      wowCache.flush(second);
      assertEquals(changing, flushed.get());
      assertMaterializedContents(first, 3, 2, 728);
      assertMaterializedContents(second, 3, 2, 728);
    } finally {
      dwlField.set(wowCache, original);
    }
  }

  // Exclusive creation refuses foreign bytes and latches the original filesystem cause.
  @Test
  public void pendingCreationRefusesExistingPathAndRetainsImage() throws Exception {
    final long fileId = preparePendingPage(9230, null);
    final var path = pendingPath(fileId);
    final byte[] sentinel = {1, 4, 9, 16};
    Files.write(path, sentinel);
    final var notifications = new java.util.ArrayList<Integer>();
    try (var observation = wowCache.observePhysicalCreation(notifications::add)) {
      final var failure = assertThrows(Exception.class, () -> wowCache.flush(fileId));
      Throwable cause = failure;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }
      assertTrue(cause.toString(), cause instanceof java.nio.file.FileAlreadyExistsException);
      assertArrayEquals(sentinel, Files.readAllBytes(path));
      assertTrue(notifications.isEmpty());
      assertNull(files.get(fileId));
      final var retained = wowCache.loadIfPresent(fileId, 2, false);
      assertNotNull(retained);
      retained.decrementReadersReferrer();
      assertThrows(Exception.class, () -> wowCache.flush());
    }
  }

  // Notification precedes header setup. Failure afterward is fatal even for a non-durable file.
  @Test
  public void pendingHeaderFailureNotifiesOnceAndNeverRetries() throws Exception {
    final long fileId = prepareSavedNonDurablePendingPage(9231);
    final int internalId = wowCache.internalFileId(fileId);
    final var originalFailure = new IOException("header setup denied");
    final var notifications = new java.util.ArrayList<Integer>();
    try (var observation = wowCache.observePhysicalCreation(id -> {
      notifications.add(id);
      // The callback is the exact boundary between directory creation and header setup.
      throw new java.io.UncheckedIOException(originalFailure);
    })) {
      final var failure = assertThrows(Exception.class, () -> wowCache.flush(fileId));
      assertCause(failure, originalFailure);
      assertEquals(List.of(internalId), notifications);
      assertTrue(Files.exists(pendingPath(fileId)));
      assertFalse(wowCache.logicalFiles().live(internalId).diskPresent());
      assertNull(files.get(fileId));
      assertCause(assertThrows(Exception.class, () -> wowCache.flush(fileId)), originalFailure);
      assertEquals(1, notifications.size());
      final var retained = wowCache.loadIfPresent(fileId, 2, false);
      assertNotNull(retained);
      assertEquals(728, retained.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      retained.decrementReadersReferrer();
    }
  }

  // Failure opening the created directory entry still follows exactly one creation notification.
  @Test
  public void pendingHeaderOpenFailureLatchesFilesystemCause() throws Exception {
    final long fileId = preparePendingPage(9232, null);
    final var notifications = new java.util.ArrayList<Integer>();
    try (var observation = wowCache.observePhysicalCreation(id -> {
      notifications.add(id);
      try {
        Files.delete(pendingPath(fileId));
        Files.createDirectory(pendingPath(fileId));
      } catch (IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    })) {
      final var failure = assertThrows(Exception.class, () -> wowCache.flush(fileId));
      Throwable original = failure;
      while (original.getCause() != null) {
        original = original.getCause();
      }
      assertTrue(original.toString(), original instanceof java.nio.file.FileSystemException);
      assertEquals(List.of(wowCache.internalFileId(fileId)), notifications);
      assertNull(files.get(fileId));
      assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskPresent());
      assertCause(assertThrows(Exception.class, () -> wowCache.flush()), original);
    }
  }

  // A locked lower image is a publication barrier, not a gap that may be overwritten.
  @Test
  public void pendingCreationPreservesBarrierAndConcurrentGrowth() throws Exception {
    final long fileId = preparePendingPage(9240, null);
    cacheUnwrittenImage(fileId, 0, 111);
    final var lower = wowCache.loadIfPresent(fileId, 0, false);
    final long stamp = lower.acquireExclusiveLock();
    final var token = wowCache.lifetimeToken(wowCache.internalFileId(fileId));
    try (var observation = wowCache.observePhysicalCreation(id -> token.allocateThrough(9))) {
      wowCache.flush(fileId);
      assertTrue(wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskPresent());
      assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
      assertEquals(9, wowCache.getFilledUpTo(fileId));
      assertSame(token, wowCache.lifetimeToken(wowCache.internalFileId(fileId)));
      final var retained = wowCache.loadIfPresent(fileId, 2, false);
      assertNotNull(retained);
      retained.decrementReadersReferrer();
    } finally {
      lower.releaseExclusiveLock(stamp);
      lower.decrementReadersReferrer();
    }
    wowCache.flush(fileId);
    assertMaterializedContents(fileId, 3, Map.of(0, 111, 2, 728));
    assertEquals(9, wowCache.getFilledUpTo(fileId));
  }

  // Creation cannot evict a newer cached image copied before the physical-create callback.
  @Test
  public void pendingCreationRetainsNewerImageUntilNextFlush() throws Exception {
    final long fileId = preparePendingPage(9241, null);
    final var page = wowCache.loadIfPresent(fileId, 2, false);
    try (var observation = wowCache.observePhysicalCreation(id -> {
      final long stamp = page.acquireExclusiveLock();
      try {
        page.getBuffer().putInt(DurablePage.NEXT_FREE_POSITION, 999);
        wowCache.store(fileId, 2, page);
      } finally {
        page.releaseExclusiveLock(stamp);
      }
    })) {
      wowCache.flush(fileId);
    } finally {
      page.decrementReadersReferrer();
    }
    final var retained = wowCache.loadIfPresent(fileId, 2, false);
    assertNotNull(retained);
    assertEquals(999, retained.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
    retained.decrementReadersReferrer();
    wowCache.flush(fileId);
    assertMaterializedContents(fileId, 3, 2, 999);
  }

  // A one-handle cache must drain the current file before admitting another pending file.
  @Test
  public void pendingMultiFileFlushCompletesAtOneHandleLimitAcrossBatches() throws Exception {
    files = new ClosableLinkedContainer<>(1);
    final var handles = WOWCache.class.getDeclaredField("files");
    handles.setAccessible(true);
    handles.set(wowCache, files);
    final var chunkLimit = WOWCache.class.getDeclaredField("chunkSize");
    chunkLimit.setAccessible(true);
    chunkLimit.set(wowCache, 1);
    final var ids = new java.util.ArrayList<Long>();
    for (int component = 9250; component < 9256; component++) {
      ids.add(preparePendingPage(component, null));
    }
    wowCache.flush();
    for (final long fileId : ids) {
      assertMaterializedContents(fileId, 3, 2, 728);
    }
    assertTrue(files.openFilesCount() <= 1);
    for (final long fileId : ids) {
      final var entry = files.acquire(fileId);
      assertNotNull(entry);
      files.release(entry);
    }
    assertTrue(files.openFilesCount() <= 1);
  }

  // Several files share each durable batch. Both dependency orders span two batch boundaries.
  @Test
  public void sharedPendingBatchesForceEveryCreationAndCopyDependency() throws Exception {
    final var wal = Mockito.spy(writeAheadLog);
    final var durable = new java.util.concurrent.atomic.AtomicLong();
    when(wal.getFlushedLsn()).thenAnswer(inv -> new LogSequenceNumber(durable.get(), 0));
    Mockito.doAnswer(inv -> {
      durable.incrementAndGet();
      return null;
    }).when(wal).flush();
    final var walField = WOWCache.class.getDeclaredField("writeAheadLog");
    walField.setAccessible(true);
    walField.set(wowCache, wal);
    final var chunkLimit = WOWCache.class.getDeclaredField("chunkSize");
    chunkLimit.setAccessible(true);
    chunkLimit.set(wowCache, 1);
    final var dependencies = new java.util.HashMap<Integer, Long>();
    final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
    dwlField.setAccessible(true);
    final var original = dwlField.get(wowCache);
    final var dwl = mock(DoubleWriteLog.class);
    final var batches = new java.util.concurrent.atomic.AtomicInteger();
    when(dwl.write(any(), any(), any())).thenAnswer(inv -> {
      final it.unimi.dsi.fastutil.ints.IntArrayList ids = inv.getArgument(1);
      assertTrue("A batch must contain several files", ids.size() > 1);
      for (int i = 0; i < ids.size(); i++) {
        assertTrue("DWL dependency " + ids.getInt(i),
            durable.get() >= dependencies.get(ids.getInt(i)));
      }
      batches.incrementAndGet();
      return new DoubleWriteLog.AppendResult(1L, false, 1L);
    });
    dwlField.set(wowCache, dwl);
    try (var observation =
        wowCache.observePhysicalCreation(internal -> assertTrue("creation dependency " + internal,
            durable.get() >= dependencies.get(internal)))) {
      for (int order = 0; order < 2; order++) {
        durable.set(0);
        for (int index = 0; index < 8; index++) {
          final long creating = order == 0 ? 8 + index : 2;
          final long changing = order == 0 ? 2 : 20 + index;
          final long id = preparePendingPage(9280 + order * 8 + index,
              new LogSequenceNumber(creating, 0));
          dependencies.put(wowCache.internalFileId(id), Math.max(creating, changing));
          final var pointer = wowCache.loadIfPresent(id, 2, false);
          final var setEnd = CachePointer.class.getDeclaredMethod("setEndLSN",
              LogSequenceNumber.class);
          setEnd.setAccessible(true);
          setEnd.invoke(pointer, new LogSequenceNumber(changing, 0));
          pointer.decrementReadersReferrer();
        }
        wowCache.flush();
      }
      assertEquals(4, batches.get());
    } finally {
      dwlField.set(wowCache, original);
    }
  }

  // Memory-only registry membership cannot authorize physical creation or lose the changed image.
  @Test
  public void pendingCreationRejectsUnsavedRegistryMembership() throws Exception {
    final long id = preparePendingPage(9260, null);
    final int internal = wowCache.internalFileId(id);
    final var state = wowCache.logicalFiles().live(internal);
    wowCache.logicalFiles().register(PageFileNames.layout28(state.identity()), internal,
        true, 3, 0, false);
    final var registry = WOWCache.class.getDeclaredField("nonDurableFileIds");
    registry.setAccessible(true);
    registry.set(wowCache, new IntOpenHashSet(new int[] {internal}));
    assertThrows(Exception.class, () -> wowCache.flush(id));
    assertFalse(Files.exists(pendingPath(id)));
    assertNull(files.get(id));
    assertNotNull(cachedCoverage());
    final var retained = wowCache.loadIfPresent(id, 2, false);
    assertNotNull(retained);
    retained.decrementReadersReferrer();
    assertThrows(Exception.class, () -> wowCache.flush(id));
  }

  // A primary write failure after removal must not authorize a later memory-only re-registration.
  @Test
  public void pendingCreationRejectsRegistryEvidenceAfterFailedRemoval() throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9296);
    final long retainedId = prepareSavedNonDurablePendingPage(9297);
    final int internal = wowCache.internalFileId(id);
    final int retainedInternal = wowCache.internalFileId(retainedId);
    final var registry = WOWCache.class.getDeclaredField("nonDurableFileIds");
    registry.setAccessible(true);
    registry.set(wowCache, new IntOpenHashSet(new int[] {retainedInternal}));
    final var save = WOWCache.class.getDeclaredMethod("writeNonDurableRegistry");
    save.setAccessible(true);
    final var originalFailure = new IOException("primary registry write denied");
    final var writes = new java.util.concurrent.atomic.AtomicInteger();
    try (var io = Mockito.mockStatic(com.jetbrains.youtrackdb.internal.common.io.IOUtils.class,
        Mockito.CALLS_REAL_METHODS)) {
      io.when(() -> com.jetbrains.youtrackdb.internal.common.io.IOUtils.writeByteBuffer(
          any(ByteBuffer.class), any(java.nio.channels.FileChannel.class), Mockito.eq(0L)))
          .thenAnswer(inv -> {
            if (writes.incrementAndGet() == 2) {
              throw originalFailure;
            }
            return inv.callRealMethod();
          });
      assertSame(originalFailure,
          assertThrows(InvocationTargetException.class, () -> save.invoke(wowCache)).getCause());
    }
    assertEquals("shadow must finish before the primary write fails", 2, writes.get());
    final var read = WOWCache.class.getDeclaredMethod("readNonDurableRegistry");
    read.setAccessible(true);
    assertEquals(new IntOpenHashSet(new int[] {retainedInternal}), read.invoke(wowCache));

    // Re-register only in memory. The failed save must not restore this ID's creation evidence.
    registry.set(wowCache, new IntOpenHashSet(new int[] {internal, retainedInternal}));
    final var failure = assertThrows(Exception.class, () -> wowCache.flush(id));
    final var error = WOWCache.class.getDeclaredField("flushError");
    error.setAccessible(true);
    final var fatal = (Throwable) error.get(wowCache);
    assertNotNull(fatal);
    assertTrue(fatal.getMessage().contains("lacks registry evidence"));
    assertCause(failure, fatal);
    assertFalse(Files.exists(pendingPath(id)));
    assertNull(files.get(id));
    assertFalse(wowCache.logicalFiles().live(internal).diskPresent());
    final var retained = wowCache.loadIfPresent(id, 2, false);
    assertNotNull(retained);
    retained.decrementReadersReferrer();
    assertCause(assertThrows(Exception.class, () -> wowCache.flush(id)), fatal);
    assertSame(fatal, error.get(wowCache));
  }

  // A stale task cannot create the backing path of a replacement incarnation.
  @Test
  public void pendingCreationRejectsObsoleteIncarnation() throws Exception {
    final long id = preparePendingPage(9261, null);
    final int internal = wowCache.internalFileId(id);
    final var state = wowCache.logicalFiles().live(internal);
    wowCache.logicalFiles().forget(internal);
    wowCache.logicalFiles().register(PageFileNames.layout28(state.identity()), internal,
        false, 3, 0, false);
    final var create = WOWCache.class.getDeclaredMethod("createPendingPageFile",
        int.class, LogicalPageFileTable.State.class);
    create.setAccessible(true);
    final var failure = assertThrows(InvocationTargetException.class,
        () -> create.invoke(wowCache, internal, state));
    assertTrue(failure.getCause().getMessage().contains("Obsolete"));
    assertFalse(Files.exists(pendingPath(id)));
    assertThrows(Exception.class, () -> wowCache.flush(id));
  }

  // Real stores, shrink, replacement and successful flush must all update suffix coverage.
  @Test
  public void cacheCoverageFollowsStorePurgeReplacementAndFlush() throws Exception {
    final long id = preparePendingPage(9262, null);
    final int internal = wowCache.internalFileId(id);
    final var first = wowCache.lifetimeToken(internal);
    final var coverage = cachedCoverage();
    assertTrue(coverage.covers(internal, 2, first));
    cacheUnwrittenImage(id, 3, 333);
    wowCache.shrinkFile(id, 3L * PAGE_SIZE);
    assertFalse(coverage.covers(internal, 3, first));
    wowCache.close(id, false);
    assertFalse(coverage.covers(internal, 2, first));
    final var state = wowCache.logicalFiles().live(internal);
    wowCache.logicalFiles().forget(internal);
    wowCache.logicalFiles().register(PageFileNames.layout28(state.identity()), internal,
        false, 3, 0, false);
    cacheUnwrittenImage(id, 2, 728);
    final var next = wowCache.lifetimeToken(internal);
    assertNotSame(first, next);
    assertFalse(coverage.covers(internal, 0, first));
    assertTrue(coverage.covers(internal, 2, next));
    wowCache.flush(id);
    assertFalse(coverage.covers(internal, 0, next));
  }

  // Removing the only higher image must not leave permission for an uncovered pending blank.
  @Test
  public void pendingBlankRejectsCoveragePurgedByShrink() throws Exception {
    final long id = preparePendingPage(9263, null);
    wowCache.close(id, false);
    cacheUnwrittenImage(id, 3, 333);
    wowCache.shrinkFile(id, 3L * PAGE_SIZE);
    final var blank = WOWCache.class.getDeclaredMethod("writeMaterializedBlank",
        int.class, int.class, LogicalPageFileTable.Extents.class);
    blank.setAccessible(true);
    assertThrows(InvocationTargetException.class, () -> blank.invoke(wowCache,
        wowCache.internalFileId(id), 2, wowCache.lifetimeToken(wowCache.internalFileId(id))));
    assertFalse(Files.exists(pendingPath(id)));
  }

  // A store cannot publish its cache entry while a coverage lookup holds the shared file lock.
  @Test
  public void storeWaitsForSharedCoverageLockBeforeCacheInsertion() throws Exception {
    final long id = preparePendingPage(92991, null);
    wowCache.close(id, false);
    final int internal = wowCache.internalFileId(id);
    final var pointer = wowCache.loadOrAdd(id, 2, false);
    final var coverage = cachedCoverage();
    final var locksField = CachedPageCoverage.class.getDeclaredField("locks");
    locksField.setAccessible(true);
    @SuppressWarnings("unchecked")
    final var locks = (PartitionedLockManager<Integer>) locksField.get(coverage);
    final var pagesField = WOWCache.class.getDeclaredField("writeCachePages");
    pagesField.setAccessible(true);
    final var pages = (Map<?, ?>) pagesField.get(wowCache);
    final var shared = locks.acquireSharedLock(internal);
    final var started = new CountDownLatch(1);
    final var store = asyncFileExecutor.submit(() -> {
      final long stamp = pointer.acquireExclusiveLock();
      try {
        started.countDown();
        wowCache.store(id, 2, pointer);
      } finally {
        pointer.releaseExclusiveLock(stamp);
      }
    });
    try {
      assertTrue(started.await(10, TimeUnit.SECONDS));
      assertThrows(java.util.concurrent.TimeoutException.class,
          () -> store.get(100, TimeUnit.MILLISECONDS));
      assertFalse(pages.containsKey(new PageKey(internal, 2)));
      assertFalse(coverage.covers(internal, 2, wowCache.lifetimeToken(internal)));
    } finally {
      shared.unlock();
      try {
        store.get(10, TimeUnit.SECONDS);
      } finally {
        pointer.decrementReadersReferrer();
      }
    }
    assertTrue(pages.containsKey(new PageKey(internal, 2)));
    assertTrue(coverage.covers(internal, 2, wowCache.lifetimeToken(internal)));
  }

  // A lookup waits until the store publishes both the cached image and its coverage.
  @Test
  public void coverageLookupAndStoreShareFilePartition() throws Exception {
    final long id = preparePendingPage(9264, null);
    wowCache.close(id, false);
    final int internal = wowCache.internalFileId(id);
    final var pointer = wowCache.loadOrAdd(id, 2, false);
    final var coverage = Mockito.spy(cachedCoverage());
    final var field = WOWCache.class.getDeclaredField("cachedPageCoverage");
    field.setAccessible(true);
    field.set(wowCache, coverage);
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    Mockito.doAnswer(inv -> {
      entered.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS));
      return inv.callRealMethod();
    }).when(coverage).add(eq(internal), eq(2L), any());
    final var store = asyncFileExecutor.submit(() -> {
      final long stamp = pointer.acquireExclusiveLock();
      try {
        wowCache.store(id, 2, pointer);
      } finally {
        pointer.releaseExclusiveLock(stamp);
      }
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      final var started = new CountDownLatch(1);
      final var lookup = asyncFileExecutor.submit(() -> {
        started.countDown();
        return coverage.covers(internal, 2, wowCache.lifetimeToken(internal));
      });
      assertTrue(started.await(10, TimeUnit.SECONDS));
      assertThrows(java.util.concurrent.TimeoutException.class,
          () -> lookup.get(100, TimeUnit.MILLISECONDS));
      release.countDown();
      store.get(10, TimeUnit.SECONDS);
      assertTrue(lookup.get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      store.get(10, TimeUnit.SECONDS);
      pointer.decrementReadersReferrer();
    }
  }

  // Real creation and admission precede reservation failure, which is fatal for non-durable data.
  @Test
  public void pendingReservationFailureIsFatalAfterAdmission() throws Exception {
    pendingInitializationFailure(false);
  }

  // Failure of a blank gap write after admission keeps the image and the original fatal cause.
  @Test
  public void pendingBlankInitializationFailureIsFatalAfterAdmission() throws Exception {
    pendingInitializationFailure(true);
  }

  private void pendingInitializationFailure(boolean blank) throws Exception {
    final long id = prepareSavedNonDurablePendingPage(blank ? 9266 : 9265);
    final var originalFailure = new IOException("pending initialization denied");
    wowCache = Mockito.spy(wowCache);
    final var admitted = new java.util.concurrent.atomic.AtomicReference<File>();
    Mockito.doAnswer(inv -> {
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      if (blank) {
        Mockito.doThrow(originalFailure).when(wrapper).write(Mockito.anyList());
      } else {
        Mockito.doAnswer(reserve -> {
          throw originalFailure;
        })
            .when(wrapper).reserveSpaceThrough(Mockito.anyLong());
      }
      admitted.set(wrapper);
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    final var failure = assertThrows(Exception.class, () -> wowCache.flush(id));
    assertCause(failure, originalFailure);
    final var error = WOWCache.class.getDeclaredField("flushError");
    error.setAccessible(true);
    final var fatal = (Throwable) error.get(wowCache);
    assertNotNull(fatal);
    assertCause(fatal, originalFailure);
    // Every registration form reports the fatal failure in this pending fixture.
    for (final var permission : registrationModes()) {
      assertCause(assertThrows(IOException.class, () -> registerRecoveryMode(id, permission)),
          fatal);
      assertPendingExtents(id);
    }
    assertSame(admitted.get(), files.get(id));
    assertTrue(Files.exists(pendingPath(id)));
    assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskPresent());
    final var retained = wowCache.loadIfPresent(id, 2, false);
    assertNotNull(retained);
    retained.decrementReadersReferrer();
    assertCause(assertThrows(Exception.class, () -> wowCache.flush(id)), originalFailure);
    assertSame(fatal, error.get(wowCache));
    final var entry = files.acquire(id);
    assertNotNull(entry);
    files.release(entry);
  }

  // Ordinary data-write failure does not invalidate ownership when close purges all images.
  @Test
  public void pendingHandleOwnershipSurvivesCloseWithoutFlush() throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9267);
    wowCache = Mockito.spy(wowCache);
    final var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
    Mockito.doAnswer(inv -> {
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(write -> {
        final java.util.List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        if (buffers.getFirst().first == 2L * PAGE_SIZE && fail.getAndSet(false)) {
          throw new IOException("retryable data failure");
        }
        return original.write(buffers);
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    wowCache.flush(id);
    assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskPresent());
    wowCache.close(id, false);
    cacheUnwrittenImage(id, 2, 728);
    wowCache.flush(id);
    assertMaterializedContents(id, 3, 2, 728);
  }

  // In this pending fixture, both registration forms reuse the handle after retryable data I/O.
  @Test
  public void pendingNonDurableBackgroundFailureRetriesOwnedHandle() throws Exception {
    final long id = prepareSavedNonDurablePendingPage(9267);
    wowCache = Mockito.spy(wowCache);
    final var fail = new java.util.concurrent.atomic.AtomicBoolean(true);
    Mockito.doAnswer(inv -> {
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(write -> {
        final java.util.List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        if (buffers.getFirst().first == 2L * PAGE_SIZE && fail.getAndSet(false)) {
          throw new IOException("retryable data failure");
        }
        return original.write(buffers);
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    flushWithSelector(id, 1);
    assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskPresent());
    final var error = WOWCache.class.getDeclaredField("flushError");
    error.setAccessible(true);
    assertNull(error.get(wowCache));
    final var ownedHandle = files.get(id);
    final var bytes = Files.readAllBytes(pendingPath(id));
    for (final boolean expected : List.of(true, false)) {
      assertTrue(registerOpenOnly(id, expected));
      assertSame(ownedHandle, files.get(id));
      assertArrayEquals(bytes, Files.readAllBytes(pendingPath(id)));
      assertPendingExtents(id);
    }
    flushWithSelector(id, 1);
    assertMaterializedContents(id, 3, 2, 728);
    Mockito.verify(wowCache).createFileHandle(any(), Mockito.anyInt());
  }

  // Incomplete first data I/O must retain cache images and keep the disk route unpublished.
  @Test
  public void pendingFirstWritePublishesOnlyAfterCompletion() throws Exception {
    final long id = preparePendingPage(9268, null);
    final var dwlField = WOWCache.class.getDeclaredField("doubleWriteLog");
    dwlField.setAccessible(true);
    final var originalDwl = dwlField.get(wowCache);
    final var dwl = mock(DoubleWriteLog.class);
    final var saved = new java.util.concurrent.atomic.AtomicReference<byte[]>();
    when(dwl.write(any(), any(), any())).thenAnswer(inv -> {
      final java.util.ArrayList<ByteBuffer> buffers = inv.getArgument(0);
      final byte[] bytes = new byte[PAGE_SIZE];
      buffers.getFirst().duplicate().position(0).get(bytes);
      saved.set(bytes);
      return new DoubleWriteLog.AppendResult(1L, false, 1L);
    });
    when(dwl.loadPage(eq(wowCache.internalFileId(id)), eq(2), any())).thenAnswer(inv -> {
      final var direct = bufferPool.acquireDirect(false, Intention.LOAD_PAGE_FROM_DISK);
      direct.getNativeByteBuffer().put(0, saved.get());
      return direct;
    });
    dwlField.set(wowCache, dwl);
    wowCache = Mockito.spy(wowCache);
    final var entered = new CountDownLatch(1);
    final var release = new CountDownLatch(1);
    Mockito.doAnswer(inv -> {
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(write -> {
        final var result = original.write(write.getArgument(0));
        final java.util.List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        if (buffers.getFirst().first != 2L * PAGE_SIZE) {
          return result;
        }
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          entered.countDown();
          try {
            assertTrue(release.await(10, TimeUnit.SECONDS));
          } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
          }
          result.await();
        };
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    final var flush = asyncFileExecutor.submit(() -> {
      wowCache.flush(id);
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      assertFalse(wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskPresent());
      assertEquals(0, wowCache.logicalFiles().live(wowCache.internalFileId(id)).diskExtent());
      assertTrue(cachedCoverage().covers(wowCache.internalFileId(id), 2,
          wowCache.lifetimeToken(wowCache.internalFileId(id))));
      final var retained = wowCache.loadIfPresent(id, 2, false);
      assertNotNull(retained);
      assertEquals(728, retained.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      retained.decrementReadersReferrer();
      assertEquals(Long.valueOf(3), earliestRetainedSegment());
      final var load = WOWCache.class.getDeclaredMethod("loadFileContent",
          int.class, long.class, boolean.class);
      load.setAccessible(true);
      final var fromDwl = (CachePointer) load.invoke(wowCache,
          wowCache.internalFileId(id), 2L, true);
      assertNotNull(fromDwl);
      fromDwl.incrementReadersReferrer();
      assertEquals(728, fromDwl.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      fromDwl.decrementReadersReferrer();
      Mockito.verify(dwl).loadPage(eq(wowCache.internalFileId(id)), eq(2), any());
    } finally {
      release.countDown();
      flush.get(10, TimeUnit.SECONDS);
      dwlField.set(wowCache, originalDwl);
    }
    assertFalse(cachedCoverage().covers(wowCache.internalFileId(id), 2,
        wowCache.lifetimeToken(wowCache.internalFileId(id))));
    final var entry = files.acquire(id);
    assertNotNull(entry);
    files.release(entry);
    assertMaterializedContents(id, 3, 2, 728);
  }

  // Pending initialization failure drains writes already submitted for another pending file.
  @Test
  public void pendingInitializationFailureDrainsEarlierFileAndRetainsProtection() throws Exception {
    final long first = preparePendingPage(9270, null);
    final long second = preparePendingPage(9271, null);
    wowCache = Mockito.spy(wowCache);
    final var writes = new java.util.concurrent.atomic.AtomicInteger();
    final var drained = new java.util.concurrent.atomic.AtomicBoolean();
    final var cause = new IOException("second pending reservation");
    Mockito.doAnswer(inv -> {
      final File original = (File) inv.callRealMethod();
      final var wrapper = mock(File.class, Mockito.withSettings()
          .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
      Mockito.doAnswer(reserve -> {
        if (writes.get() > 0) {
          throw cause;
        }
        original.reserveSpaceThrough(reserve.getArgument(0));
        return null;
      }).when(wrapper).reserveSpaceThrough(Mockito.anyLong());
      Mockito.doAnswer(write -> {
        final var result = original.write(write.getArgument(0));
        final java.util.List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<ByteBuffer>> buffers =
                write.getArgument(0);
        if (buffers.getFirst().first != 2L * PAGE_SIZE) {
          return result;
        }
        writes.incrementAndGet();
        return (com.jetbrains.youtrackdb.internal.core.storage.fs.IOResult) () -> {
          result.await();
          drained.set(true);
        };
      }).when(wrapper).write(Mockito.anyList());
      return wrapper;
    }).when(wowCache).createFileHandle(any(), Mockito.anyInt());
    assertCause(assertThrows(Exception.class, () -> wowCache.flush()), cause);
    assertTrue(drained.get());
    assertEquals(Long.valueOf(3), earliestRetainedSegment());
    for (long id : new long[] {first, second}) {
      final var retained = wowCache.loadIfPresent(id, 2, false);
      assertNotNull(retained);
      retained.decrementReadersReferrer();
      final var entry = files.acquire(id);
      assertNotNull(entry);
      files.release(entry);
    }
  }

  private CachedPageCoverage cachedCoverage() throws Exception {
    final var field = WOWCache.class.getDeclaredField("cachedPageCoverage");
    field.setAccessible(true);
    return (CachedPageCoverage) field.get(wowCache);
  }

  private long prepareSavedNonDurablePendingPage(int component) throws Exception {
    wowCache.pauseBackgroundFlush();
    if (wowCache.logicalFiles() == null) {
      wowCache.setStorageFormat(
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    }
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, component);
    final var name = PageFileNames.layout28(identity);
    final long id = wowCache.addFile(name, wowCache.externalFileId(identity.internalId()), true);
    files.remove(id).delete();
    wowCache.logicalFiles().forget(identity.internalId());
    wowCache.logicalFiles().register(name, identity.internalId(), true, 3, 0, false);
    cacheUnwrittenImage(id, 2, 728);
    return id;
  }

  private Path pendingPath(long fileId) {
    final var state = wowCache.logicalFiles().live(wowCache.internalFileId(fileId));
    final var name = PageFileNames.layout28(state.identity());
    final int dot = name.lastIndexOf('.');
    return storagePath.resolve(name.substring(0, dot) + "_" + state.identity().internalId()
        + name.substring(dot));
  }

  private long preparePendingPage(int component, LogSequenceNumber creating) throws Exception {
    final long fileId = registerPendingFixture(component, creating);
    cacheUnwrittenImage(fileId, 2, 728);
    return fileId;
  }

  private long registerPendingFixture(int component, LogSequenceNumber creating) throws Exception {
    wowCache.pauseBackgroundFlush();
    if (wowCache.logicalFiles() == null) {
      wowCache.setStorageFormat(
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    }
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, component);
    final long fileId = wowCache.externalFileId(identity.internalId());
    wowCache.logicalFiles().register(PageFileNames.layout28(identity), identity.internalId(),
        false, 3, 0, false, creating);
    assertNull(files.get(fileId));
    assertFalse(Files.exists(pendingPath(fileId)));
    return fileId;
  }

  private void cacheUnwrittenImage(long fileId, int pageIndex, int value) throws Exception {
    wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).extents()
        .allocateThrough(pageIndex + 1L);
    final var pointer = wowCache.loadOrAdd(fileId, pageIndex, false);
    final var stamp = pointer.acquireExclusiveLock();
    try {
      pointer.getBuffer().putInt(DurablePage.NEXT_FREE_POSITION, value);
      wowCache.updateDirtyPagesTable(pointer, new LogSequenceNumber(3, 10));
      wowCache.store(fileId, pageIndex, pointer);
    } finally {
      pointer.releaseExclusiveLock(stamp);
      pointer.decrementReadersReferrer();
    }
  }

  private void assertMaterializedContents(long fileId, int pages, int changed, int value)
      throws Exception {
    assertMaterializedContents(fileId, pages, Map.of(changed, value));
  }

  private void assertMaterializedContents(long fileId, int pages, Map<Integer, Integer> values)
      throws Exception {
    assertEquals(pages, wowCache.logicalFiles().live(wowCache.internalFileId(fileId)).diskExtent());
    final var bytes = Files.readAllBytes(storagePath.resolve(wowCache.nativeFileNameById(fileId)));
    for (int index = 0; index < pages; index++) {
      final var raw = ByteBuffer.wrap(bytes, File.HEADER_SIZE + index * PAGE_SIZE, PAGE_SIZE)
          .slice().order(ByteOrder.nativeOrder());
      assertEquals("magic page " + index, WOWCache.MAGIC_NUMBER_WITH_CHECKSUM, raw.getLong(0));
      final var crc = new java.util.zip.CRC32();
      crc.update(raw.duplicate().position(WOWCache.CHECKSUM_OFFSET + Integer.BYTES));
      assertEquals("checksum page " + index, (int) crc.getValue(),
          raw.getInt(WOWCache.CHECKSUM_OFFSET));
      if (!values.containsKey(index)) {
        assertEquals("blank LSN page " + index, new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(raw));
      }
      assertEquals("raw payload page " + index, (int) values.getOrDefault(index, 0),
          raw.getInt(DurablePage.NEXT_FREE_POSITION));
      final var page = wowCache.loadIfPresent(fileId, index, true);
      assertNotNull(page);
      try {
        assertEquals((int) values.getOrDefault(index, 0),
            page.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        page.decrementReadersReferrer();
      }
    }
  }

  private static void assertCause(Throwable failure, Throwable expected) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause == expected) {
        return;
      }
    }
    throw new AssertionError("Original failure is missing", failure);
  }

  private Long earliestRetainedSegment() throws Exception {
    final var method = WOWCache.class.getDeclaredMethod("earliestNotWrittenSegment");
    method.setAccessible(true);
    return (Long) method.invoke(wowCache);
  }

  private long prepareUnwrittenMaterializedPage() throws Exception {
    return prepareUnwrittenMaterializedPage(9122);
  }

  private long prepareUnwrittenMaterializedPage(int componentId) throws Exception {
    wowCache.pauseBackgroundFlush();
    if (wowCache.logicalFiles() == null) {
      wowCache.setStorageFormat(
          com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    }
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, componentId);
    final var fileId = wowCache.addFile(PageFileNames.layout28(identity),
        wowCache.externalFileId(identity.internalId()));
    files.get(fileId).reserveSpaceThrough(3L * PAGE_SIZE);
    wowCache.logicalFiles().live(identity.internalId()).extents().allocateThrough(3);
    final var pointer = wowCache.loadOrAdd(fileId, 2, false);
    try {
      pointer.getBuffer().putInt(DurablePage.NEXT_FREE_POSITION, 728);
      wowCache.updateDirtyPagesTable(pointer, new LogSequenceNumber(3, 10));
      wowCache.store(fileId, 2, pointer);
    } finally {
      pointer.decrementReadersReferrer();
    }
    return fileId;
  }

  // A completion during disk-size sampling must survive publication of the sampled stale size.
  @Test
  public void reregistrationPreservesWriteCompletionDuringSizeSample() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9123);
    final var name = PageFileNames.layout28(identity);
    final var fileId = wowCache.addFile(name, wowCache.externalFileId(identity.internalId()));
    wowCache.logicalFiles().forget(identity.internalId());
    final var original = files.remove(fileId);
    final var wrapper = mock(File.class, Mockito.withSettings()
        .defaultAnswer(org.mockito.AdditionalAnswers.delegatesTo(original)));
    Mockito.doAnswer(inv -> {
      final var state = wowCache.logicalFiles().live(identity.internalId());
      assertNotNull(state);
      state.extents().writtenThrough(7);
      return 0L;
    }).when(wrapper).getUnderlyingFileSize();
    files.add(fileId, wrapper);
    try {
      var registration = WOWCache.class.getDeclaredMethod("registerLogicalFile",
          String.class, int.class, boolean.class);
      registration.setAccessible(true);
      registration.invoke(wowCache, name, identity.internalId(), false);
      assertEquals(7, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
    } finally {
      files.remove(fileId);
      files.add(fileId, original);
    }
  }

  // A pending canonical file exposes cached allocation to readers and backup without a handle.
  @Test
  public void pendingLogicalExtentSurvivesCacheMissAndShrinksWithoutDisk() throws Exception {
    wowCache.pauseBackgroundFlush();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9100);
    final var name = PageFileNames.layout28(identity);
    final var fileId = wowCache.externalFileId(identity.internalId());
    wowCache.logicalFiles().register(name, identity.internalId(), false, 0, 0, false);
    try {
      final var page = wowCache.loadOrAdd(fileId, 4, false);
      try {
        page.getBuffer().putInt(DurablePage.NEXT_FREE_POSITION, 731);
        wowCache.store(fileId, 4, page);
      } finally {
        page.decrementReadersReferrer();
      }
      assertEquals(5, wowCache.getFilledUpTo(fileId));
      assertEquals(5, wowCache.physicalSizeForBackupSnapshot(fileId));
      assertEquals(0, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
      for (int reader = 0; reader < 3; reader++) {
        final var cached = reader == 0 ? wowCache.loadOrAdd(fileId, 4, false)
            : reader == 1 ? wowCache.loadIfPresent(fileId, 4, false)
                : wowCache.load(fileId, 4, new ModifiableBoolean(), false);
        try {
          assertEquals(731, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
        } finally {
          cached.decrementReadersReferrer();
        }
      }
      final var gap = wowCache.loadOrAdd(fileId, 2, false);
      try {
        assertEquals(0, gap.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      } finally {
        gap.decrementReadersReferrer();
      }
      assertNull(wowCache.loadIfPresent(fileId, 3, false));
      assertNull(files.get(fileId));
      assertTrue(wowCache.shrinkFile(fileId, 2L * PAGE_SIZE));
      assertEquals(2, wowCache.physicalSizeForBackupSnapshot(fileId));
      assertNull(wowCache.loadIfPresent(fileId, 4, false));
      assertFalse(wowCache.shrinkFile(fileId, 2L * PAGE_SIZE));
    } finally {
      wowCache.shrinkFile(fileId, 0);
      wowCache.logicalFiles().forget(identity.internalId());
    }
  }

  // Written extent gates disk reads without asking the file for either reserved or actual size.
  // A failed materialized read remains an error rather than becoming an allocated blank page.
  @Test
  public void logicalReadUsesWrittenExtentWithoutSizeQueriesAndPreservesReadErrors()
      throws Exception {
    wowCache.pauseBackgroundFlush();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9101);
    final var fileId = wowCache.externalFileId(identity.internalId());
    final var file = mock(File.class);
    when(file.isOpen()).thenReturn(true);
    files.add(fileId, file);
    wowCache.logicalFiles().register(PageFileNames.layout28(identity), identity.internalId(),
        false, 3, 1, true);
    try {
      assertEquals(3, wowCache.getFilledUpTo(fileId));
      final var page = wowCache.load(fileId, 0, new ModifiableBoolean(), false);
      assertNotNull(page);
      page.decrementReadersReferrer();
      assertNull(wowCache.loadIfPresent(fileId, 1, false));
      Mockito.doThrow(new IOException("lost materialized page"))
          .when(file).read(eq(0L), any(ByteBuffer.class), eq(true));
      assertThrows(IOException.class, () -> wowCache.loadOrAdd(fileId, 0, false));
      Mockito.verify(file, Mockito.never()).getFileSize();
      Mockito.verify(file, Mockito.never()).getUnderlyingFileSize();
    } finally {
      files.remove(fileId);
      wowCache.logicalFiles().forget(identity.internalId());
    }
  }

  // Canonical allocation follows logical state even when the file wrapper reserves farther ahead.
  // Concurrent shared-lock growers retain the maximum, and queued writes cannot undo shrink.
  @Test
  public void logicalGrowthIgnoresReservationsAndShrinkOrdersQueuedWrites() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9110);
    final var fileId = wowCache.addFile(PageFileNames.layout28(identity),
        wowCache.externalFileId(identity.internalId()));
    final var file = files.get(fileId);
    file.allocateSpace(12 * PAGE_SIZE);
    assertEquals(0, wowCache.getFilledUpTo(fileId));
    final var start = new CountDownLatch(1);
    final var first = asyncFileExecutor.submit(() -> {
      start.await();
      wowCache.loadOrAdd(fileId, 3, false).decrementReadersReferrer();
      return null;
    });
    final var second = asyncFileExecutor.submit(() -> {
      start.await();
      wowCache.loadOrAdd(fileId, 6, false).decrementReadersReferrer();
      return null;
    });
    start.countDown();
    first.get(10, TimeUnit.SECONDS);
    second.get(10, TimeUnit.SECONDS);
    assertEquals(7, wowCache.getFilledUpTo(fileId));
    assertEquals(12L * PAGE_SIZE, file.getFileSize());
    wowCache.flush(fileId);
    assertEquals(7, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
    final var dirty = wowCache.loadOrAdd(fileId, 6, false);
    wowCache.store(fileId, 6, dirty);
    dirty.decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 8, false).decrementReadersReferrer();
    assertTrue(wowCache.shrinkFile(fileId, 2L * PAGE_SIZE));
    wowCache.flush(fileId);
    assertEquals(2, wowCache.getFilledUpTo(fileId));
    assertEquals(2, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
    assertEquals(2L * PAGE_SIZE, file.getUnderlyingFileSize());
    assertNull(wowCache.loadIfPresent(fileId, 6, false));
    assertTrue(wowCache.shrinkFile(fileId, 0));
    assertEquals(0, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
    wowCache.loadOrAdd(fileId, 0, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    assertEquals(1, wowCache.getFilledUpTo(fileId));
    assertEquals(1, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
  }

  // Further writes and cold opens initialize current written length, not an old open snapshot.
  @Test
  public void canonicalReregistrationAndColdOpenUseCurrentWrittenExtent() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9111);
    final var name = PageFileNames.layout28(identity);
    final var fileId = wowCache.addFile(name, wowCache.externalFileId(identity.internalId()));
    wowCache.loadOrAdd(fileId, 1, false).decrementReadersReferrer();
    wowCache.flush(fileId);

    wowCache.loadOrAdd(fileId, 4, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    assertEquals(5, wowCache.getFilledUpTo(fileId));
    assertEquals(5, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
    wowCache.close();
    files = new ClosableLinkedContainer<>(1024);
    wowCache = newCache();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    wowCache.loadRegisteredFiles();
    assertFalse(wowCache.exists(fileId));
    assertTrue(wowCache.admitExpectedFile(identity));
    assertEquals(5, wowCache.getFilledUpTo(fileId));
    assertEquals(5, wowCache.logicalFiles().live(identity.internalId()).diskExtent());
    wowCache.loadOrAdd(fileId, 4, true).decrementReadersReferrer();
  }

  // Missing bytes within the written range and a deleted materialized file remain real errors.
  @Test
  public void lostMaterializedFileAndMissingWrittenPageAreNotBlankAllocations() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9112);
    final var fileId = wowCache.addFile(PageFileNames.layout28(identity),
        wowCache.externalFileId(identity.internalId()));
    wowCache.loadOrAdd(fileId, 2, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    final var path = storagePath.resolve(wowCache.nativeFileNameById(fileId));
    try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
      channel.truncate(File.HEADER_SIZE + PAGE_SIZE);
    }
    assertThrows(java.io.EOFException.class, () -> wowCache.loadOrAdd(fileId, 2, false));
    assertThrows(java.io.EOFException.class, () -> wowCache.loadIfPresent(fileId, 2, false));
    assertTrue(files.close(fileId));
    Files.delete(path);
    assertFalse(Files.exists(path));
    assertThrows(StorageException.class, () -> wowCache.loadOrAdd(fileId, 0, false));
    files.remove(fileId);
    assertThrows(StorageException.class, () -> wowCache.loadOrAdd(fileId, 0, false));
    wowCache.logicalFiles().forget(identity.internalId());
  }

  // Unwritten and absent images still consult DWL, including silent backup-style loads.
  // A changed cache image wins over DWL, and strict checksum failures are not rebuilt.
  @Test
  public void unwrittenLogicalPagesRecoverFromDwlAndPreferChangedCache() throws Exception {
    wowCache.pauseBackgroundFlush();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.RECORD_DATA, 9113);
    final var fileId = wowCache.externalFileId(identity.internalId());
    wowCache.logicalFiles().register(PageFileNames.layout28(identity), identity.internalId(),
        false, 3, 0, false);
    final var dwl = mock(DoubleWriteLog.class);
    final var field = WOWCache.class.getDeclaredField("doubleWriteLog");
    field.setAccessible(true);
    field.set(wowCache, dwl);
    when(dwl.loadPage(eq(identity.internalId()), eq(1), any())).thenAnswer(inv -> {
      final var pointer = bufferPool.acquireDirect(true, Intention.LOAD_PAGE_FROM_DISK);
      pointer.getNativeByteBuffer().putInt(DurablePage.NEXT_FREE_POSITION, 912);
      return pointer;
    });
    try {
      final var recovered = wowCache.loadIfPresent(fileId, 1, false);
      assertNotNull(recovered);
      assertEquals(912, recovered.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      recovered.getBuffer().putInt(DurablePage.NEXT_FREE_POSITION, 913);
      wowCache.store(fileId, 1, recovered);
      recovered.decrementReadersReferrer();
      final var cached = wowCache.loadIfPresent(fileId, 1, false);
      assertEquals(913, cached.getBuffer().getInt(DurablePage.NEXT_FREE_POSITION));
      cached.decrementReadersReferrer();
      Mockito.verify(dwl, Mockito.times(1)).loadPage(eq(identity.internalId()), eq(1), any());
      wowCache.shrinkFile(fileId, 0);
      wowCache.logicalFiles().live(identity.internalId()).extents().allocateThrough(3);
      wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
      assertThrows(StorageException.class, () -> wowCache.loadOrAdd(fileId, 1, true));
      assertNull(wowCache.loadIfPresent(fileId, 2, true));
    } finally {
      wowCache.shrinkFile(fileId, 0);
      wowCache.logicalFiles().forget(identity.internalId());
    }
  }

  /** Negative inventory is a detached snapshot, includes bookings, and never changes live files. */
  @Test
  public void deletionInventorySeparatesLiveFilesAndSurvivesReopen() throws Exception {
    final long deleted = wowCache.addFile(AUX, 123456);
    wowCache.deleteFile(deleted);
    wowCache.bookFileId(OTHER, 123457, false);
    final long live = wowCache.addFile("live.dat", 123458);
    final var snapshot = wowCache.deletedFiles();
    assertEquals(java.util.Set.of(AUX, OTHER), new java.util.HashSet<>(snapshot));
    assertEquals(Map.of("live.dat", live), wowCache.files());
    snapshot.clear();
    assertEquals(2, wowCache.deletedFiles().size());
    wowCache.close();
    files = new ClosableLinkedContainer<>(1024);
    wowCache = newCache();
    wowCache.loadRegisteredFiles();
    assertEquals(java.util.Set.of(AUX, OTHER),
        new java.util.HashSet<>(wowCache.deletedFiles()));
    assertEquals(Map.of("live.dat", live), wowCache.files());
    assertThrows(StorageException.class, () -> wowCache.bookFileId(AUX, deleted, false));
  }

  /** Layout 28 cold open ignores stale live, deleted and booked map records until admission. */
  @Test
  public void layout28ColdOpenDoesNotHydrateMapInventory() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var deletedIdentity = new PageFileIdentity(PageFileRole.FREE_SPACE_MAP, 42);
    final var bookedIdentity = new PageFileIdentity(PageFileRole.FREE_SPACE_MAP, 43);
    final var liveIdentity = new PageFileIdentity(PageFileRole.RECORD_DATA, 44);
    final var deletedName = PageFileNames.layout28(deletedIdentity);
    final var bookedName = PageFileNames.layout28(bookedIdentity);
    final var liveName = PageFileNames.layout28(liveIdentity);
    final long deleted = wowCache.addFile(deletedName, deletedIdentity.internalId());
    wowCache.deleteFile(deleted);
    wowCache.bookFileId(bookedName, bookedIdentity.internalId(), false);
    final long live = wowCache.addFile(liveName, liveIdentity.internalId());
    // Seed independent stale live evidence so cold open must not install its handle.
    com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport
        .seedNameIdMap(wowCache, liveName, liveIdentity.internalId(),
            PageFileNames.layout28DiskName(liveIdentity));
    com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport
        .seedNameIdMap(wowCache, deletedName, -deletedIdentity.internalId(), "");
    assertEquals(java.util.Set.of(deletedName, bookedName),
        new java.util.HashSet<>(wowCache.deletedFiles()));
    // Booking is session-only. Seed the negative record that a legacy close would persist.
    final var bookedRecord =
        new NameFileIdEntry(bookedName, -bookedIdentity.internalId(), bookedName);
    com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport
        .seedNameIdMap(wowCache, bookedName, -bookedIdentity.internalId(), bookedName);
    final var map = storagePath.resolve("name_id_map_v3.cm");
    final var reader = WOWCache.class.getDeclaredMethod("readNextNameIdEntryV3", FileChannel.class);
    reader.setAccessible(true);
    try (var channel = FileChannel.open(map)) {
      NameFileIdEntry last = null;
      NameFileIdEntry entry;
      while ((entry = (NameFileIdEntry) reader.invoke(wowCache, channel)) != null) {
        last = entry;
      }
      assertEquals(bookedRecord, last);
    }
    final var bytes = Files.readAllBytes(map);
    assertTrue(bytes.length > 0);
    wowCache.close();
    assertArrayEquals(bytes, Files.readAllBytes(map));
    files = new ClosableLinkedContainer<>(1024);
    wowCache = newCache();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    wowCache.loadRegisteredFiles();
    assertTrue(wowCache.files().isEmpty());
    assertTrue(wowCache.deletedFiles().isEmpty());
    assertNull(wowCache.registeredFileId(deletedName));
    assertNull(wowCache.registeredFileId(bookedName));
    assertNull(wowCache.registeredFileId(liveName));
    assertFalse(wowCache.exists(live));
    // Logical inventory alone cannot reveal legacy replay opening the persisted live file.
    assertEquals(0, files.openFilesCount());
    assertNull(files.get(live));
    assertFalse(wowCache.admitExpectedFile(deletedIdentity));
    assertTrue(wowCache.admitExpectedFile(liveIdentity));
    assertEquals(Map.of(liveName, live), wowCache.files());
    assertArrayEquals(bytes, Files.readAllBytes(map));
  }

  /** Crash-style layout 28 reopen ignores an appended tombstone and permits explicit booking. */
  @Test
  public void layout28CrashReopenDoesNotReplayMapDeletion() throws Exception {
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    final var identity = new PageFileIdentity(PageFileRole.FREE_SPACE_MAP, 42);
    final var name = PageFileNames.layout28(identity);
    final var fileId = wowCache.addFile(name, identity.internalId());
    wowCache.deleteFile(fileId);
    com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport
        .seedNameIdMap(wowCache, name, -identity.internalId(), "");
    final var map = storagePath.resolve("name_id_map_v3.cm");
    final var bytes = Files.readAllBytes(map);
    assertTrue(bytes.length > 0);
    final var abandoned = wowCache;
    files = new ClosableLinkedContainer<>(1024);
    wowCache = newCache();
    wowCache.setStorageFormat(
        com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageFormat.LAYOUT_28);
    try {
      wowCache.loadRegisteredFiles();
      assertNull(wowCache.registeredFileId(name));
      assertTrue(wowCache.deletedFiles().isEmpty());
      assertFalse(wowCache.exists(fileId));
      assertArrayEquals(bytes, Files.readAllBytes(map));
    } finally {
      abandoned.close();
    }
    assertEquals(fileId, wowCache.bookFileId(name, fileId, false));
    wowCache.addFile(name, fileId);
    assertTrue(wowCache.exists(fileId));
  }

  /** All normal conflicts, including identical live and negative pairs, reject without mutation. */
  @Test
  public void suppliedBookingRejectsLiveAndNegativeConflicts() throws Exception {
    final int supplied = 123456;
    final String name = AUX;
    assertNull(wowCache.registeredFileId(name));
    final long booked = wowCache.bookFileId(name, supplied, false);
    assertEquals(supplied, wowCache.internalFileId(booked));
    assertEquals(Integer.valueOf(-supplied), wowCache.registeredFileId(name));
    assertEquals(-1L, wowCache.fileIdByName(name));
    assertThrows(StorageException.class, () -> wowCache.bookFileId(name, supplied, false));
    for (boolean auxiliary : new boolean[] {false, true}) {
      assertThrows(StorageException.class,
          () -> wowCache.bookFileId(name, supplied + 1, auxiliary));
      assertThrows(StorageException.class,
          () -> wowCache.bookFileId(OTHER, booked, auxiliary));
    }
    assertNull(wowCache.registeredFileId(OTHER));
    assertEquals(Integer.valueOf(-supplied), wowCache.registeredFileId(name));
    // Only the explicit live-owner auxiliary permission may reuse this matching negative pair.
    assertEquals(booked, wowCache.bookFileId(name, booked, true));
    wowCache.addFile(name, booked);
    assertEquals(Integer.valueOf(supplied), wowCache.registeredFileId(name));
    for (boolean auxiliary : new boolean[] {false, true}) {
      assertThrows(StorageException.class, () -> wowCache.bookFileId(name, booked, auxiliary));
      assertThrows(StorageException.class,
          () -> wowCache.bookFileId(name, supplied + 1, auxiliary));
      assertThrows(StorageException.class,
          () -> wowCache.bookFileId(OTHER, booked, auxiliary));
    }
    assertThrows(IllegalArgumentException.class, () -> wowCache.bookFileId("invalid", 0, false));
    assertThrows(IllegalArgumentException.class, () -> wowCache.bookFileId("invalid", -1, false));
    assertNull(wowCache.registeredFileId("invalid"));
  }

  /** A legacy NUL-name booking must report its conflict before any disk path is built. */
  @Test
  public void suppliedBookingRejectsLegacyNulNameBeforeProbingDisk() {
    final String name = "bad" + (char) 0 + ".dat";
    final long booked = wowCache.bookFileId(name);
    final int internalId = wowCache.internalFileId(booked);
    final var failure = assertThrows(StorageException.class,
        () -> wowCache.bookFileId(name, booked, false));
    assertEquals(new StorageException(storageName,
        "Conflicting registration or backing file for " + name + " and id " + internalId)
        .getMessage(), failure.getMessage());
    assertEquals(Integer.valueOf(-internalId), wowCache.registeredFileId(name));
  }

  /** Wrong deletion entries stay visible and reject. Only a matching auxiliary may rebuild. */
  @Test
  public void suppliedBookingChecksDeletionEntriesWithoutReactivatingThem() throws Exception {
    final long deleted = wowCache.addFile(AUX, 123456);
    wowCache.deleteFile(deleted);
    assertEquals(Integer.valueOf(-123456), wowCache.registeredFileId(AUX));
    for (boolean auxiliary : new boolean[] {false, true}) {
      assertThrows(StorageException.class, () -> wowCache.bookFileId(AUX, 123457, auxiliary));
      assertThrows(StorageException.class, () -> wowCache.bookFileId(OTHER, deleted, auxiliary));
    }
    // Strict creation rejects even a matching deletion entry.
    assertThrows(StorageException.class, () -> wowCache.bookFileId(AUX, deleted, false));
    assertEquals(Integer.valueOf(-123456), wowCache.registeredFileId(AUX));
    assertEquals(deleted, wowCache.bookFileId(AUX, deleted, true));
    wowCache.addFile(AUX, deleted);
    assertEquals(Integer.valueOf(123456), wowCache.registeredFileId(AUX));
  }

  /** A test double cannot silently replace a supplied id with random name-based allocation. */
  @Test
  public void unsupportedCacheBookingFailsExplicitly() {
    final var cache = mock(com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class,
        Mockito.CALLS_REAL_METHODS);
    assertThrows(UnsupportedOperationException.class, () -> cache.bookFileId("a", 1, false));
    assertThrows(UnsupportedOperationException.class, () -> cache.registeredFileId("a"));
    assertTrue(cache.deletedFiles().isEmpty());
  }

  /** Rejected booking never truncates an existing file or adopts an unregistered leftover. */
  @Test
  public void suppliedBookingPreservesBackingBytesAndRejectsLeftovers() throws Exception {
    final int supplied = 123456;
    final long live = wowCache.addFile(AUX, supplied);
    final Path backing = storagePath.resolve(wowCache.nativeFileNameById(live));
    final byte[] bytes = {1, 3, 5, 7};
    Files.write(backing, bytes);
    assertThrows(StorageException.class,
        () -> wowCache.bookFileId(AUX, live, true));
    assertArrayEquals(bytes, Files.readAllBytes(backing));
    assertEquals(bytes.length, Files.size(backing));
    final Path orphan = storagePath.resolve("ytdb-p28-collection-43-free-space-map_123457.fsm");
    Files.write(orphan, bytes);
    for (boolean auxiliary : new boolean[] {false, true}) {
      assertThrows(StorageException.class,
          () -> wowCache.bookFileId(OTHER, 123457, auxiliary));
      assertArrayEquals(bytes, Files.readAllBytes(orphan));
      assertEquals(bytes.length, Files.size(orphan));
      assertNull(wowCache.registeredFileId(OTHER));
    }
  }

  /** Reopen the appended registry without close, then rebuild the auxiliary, not a disk alias. */
  @Test
  public void deletionRecordSurvivesRegistryReopenWithoutCleanClose() throws Exception {
    final String logical = AUX;
    final long fileId = wowCache.addFile(logical, 123456);
    final String physical = wowCache.nativeFileNameById(fileId);
    wowCache.deleteFile(fileId);
    final var reader = WOWCache.class.getDeclaredMethod("readNextNameIdEntryV3", FileChannel.class);
    reader.setAccessible(true);
    try (var channel = FileChannel.open(storagePath.resolve("name_id_map_v3.cm"))) {
      NameFileIdEntry last = null;
      NameFileIdEntry entry;
      while ((entry = (NameFileIdEntry) reader.invoke(wowCache, channel)) != null) {
        last = entry;
      }
      assertNotNull(last);
      assertEquals(logical, last.getName());
      assertEquals(physical, last.getFileSystemName());
      assertEquals(-123456, last.getFileId());
    }
    // Use empty maps in a fresh cache. No close or registry compaction occurs first.
    final WOWCache abandoned = wowCache;
    files = new ClosableLinkedContainer<>(1024);
    wowCache = newCache();
    try {
      wowCache.loadRegisteredFiles();
    } finally {
      // Release the old cache only after the crash-style registry replay.
      abandoned.close();
    }
    assertNull(wowCache.fileNameById(fileId));
    assertEquals(Integer.valueOf(-123456), wowCache.registeredFileId(logical));
    assertNull(wowCache.registeredFileId(physical));
    assertEquals(fileId, wowCache.bookFileId(logical, fileId, true));
    wowCache.addFile(logical, fileId);
    assertEquals(Integer.valueOf(123456), wowCache.registeredFileId(logical));
    assertTrue(Files.exists(storagePath.resolve(physical)));
  }

  /** A matching tombstone cannot authorize reconstruction over leftover backing bytes. */
  @Test
  public void reconstructionRejectsMatchingDeletionWithLeftover() throws Exception {
    final long deleted = wowCache.addFile(AUX, 123456);
    final Path backing = storagePath.resolve(wowCache.nativeFileNameById(deleted));
    wowCache.deleteFile(deleted);
    final byte[] bytes = {9, 8, 7};
    Files.write(backing, bytes);
    assertThrows(StorageException.class, () -> wowCache.bookFileId(AUX, deleted, true));
    assertEquals(Integer.valueOf(-123456), wowCache.registeredFileId(AUX));
    assertArrayEquals(bytes, Files.readAllBytes(backing));
    assertEquals(bytes.length, Files.size(backing));
  }

  /** Only strict layout 28 rebuildable roles authorize reconstruction, never primary data. */
  @Test
  public void reconstructionPermissionRequiresRebuildableLogicalRole() {
    for (var role : PageFileRole.values()) {
      final var identity = new PageFileIdentity(role, 99);
      final String logical = PageFileNames.layout28(identity);
      final boolean rebuildable = switch (role) {
        case FREE_SPACE_MAP, DIRTY_PAGE_BIT_SET, CONFIG_FREE_SPACE_MAP,
            CONFIG_DIRTY_PAGE_BIT_SET, HISTOGRAM -> true;
        default -> false;
      };
      if (rebuildable) {
        final long booked = wowCache.bookFileId(logical, identity.internalId(), true);
        assertEquals(identity.internalId(), wowCache.internalFileId(booked));
        assertEquals(booked, wowCache.bookFileId(logical, booked, true));
      } else {
        final var failure = assertThrows(StorageException.class,
            () -> wowCache.bookFileId(logical, identity.internalId(), true));
        assertTrue(failure.getMessage().contains("rebuildable role"));
        assertNull(wowCache.registeredFileId(logical));
      }
    }
    for (String invalid : new String[] {"legacy.fsm",
        "ytdb-p28-collection-099-free-space-map.fsm",
        "ytdb-p28-collection-99-free-space-map_1830586723.fsm"}) {
      assertThrows(StorageException.class, () -> wowCache.bookFileId(invalid, 123456, true));
      assertNull(wowCache.registeredFileId(invalid));
    }
  }

  private static final String AUX = "ytdb-p28-collection-42-free-space-map.fsm";
  private static final String OTHER = "ytdb-p28-collection-43-free-space-map.fsm";

  private static final int PAGE_SIZE = DurablePage.NEXT_FREE_POSITION + 8;
  private static final long PAGES_FLUSH_INTERVAL = 10L;
  private static final int SHUTDOWN_TIMEOUT = 10_000;
  private static final long EXCLUSIVE_WRITE_CACHE_MAX_SIZE = 100L;
  private static final String FILE_NAME = "wowCacheLoadOrAdd.tst";

  private static boolean previousExclusiveFileAccess;
  private static boolean previousFileLock;
  private static Path storagePath;
  private static String storageName;
  private static final ByteBufferPool bufferPool = new ByteBufferPool(PAGE_SIZE);

  private CASDiskWriteAheadLog writeAheadLog;
  private WOWCache wowCache;
  private ClosableLinkedContainer<Long, File> files;
  // The cached-thread-pool the WOWCache uses internally for AsyncFile I/O. Held so the
  // executor can be shut down in tearDown; without an explicit shutdown it would leak
  // non-daemon threads across test methods.
  private ExecutorService asyncFileExecutor;

  @BeforeClass
  public static void beforeClass() {
    previousExclusiveFileAccess =
        GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.getValueAsBoolean();
    previousFileLock = GlobalConfiguration.FILE_LOCK.getValueAsBoolean();
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var buildDirectory = System.getProperty("buildDirectory", ".");
    storageName = "WOWCacheLoadOrAddTest";
    storagePath = Paths.get(buildDirectory).resolve(storageName);
  }

  @AfterClass
  public static void afterClass() {
    try {
      bufferPool.clear();
    } finally {
      GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(previousExclusiveFileAccess);
      GlobalConfiguration.FILE_LOCK.setValue(previousFileLock);
    }
  }

  @Before
  public void setUp() throws Exception {
    cleanUp();

    Files.createDirectories(storagePath);
    files = new ClosableLinkedContainer<>(1024);

    writeAheadLog =
        new CASDiskWriteAheadLog(
            storageName,
            storagePath,
            storagePath,
            ContextConfiguration.WAL_DEFAULT_NAME,
            12_000,
            128,
            null,
            null,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            25,
            true,
            Locale.US,
            -1,
            1000,
            false,
            false,
            true,
            10);
    asyncFileExecutor = Executors.newCachedThreadPool();
    wowCache = newCache();
    wowCache.loadRegisteredFiles();
  }

  private WOWCache newCache() {
    return new WOWCache(
        PAGE_SIZE,
        false,
        bufferPool,
        writeAheadLog,
        new DoubleWriteLogNoOP(),
        PAGES_FLUSH_INTERVAL,
        SHUTDOWN_TIMEOUT,
        EXCLUSIVE_WRITE_CACHE_MAX_SIZE,
        storagePath,
        storageName,
        files,
        1,
        ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME,
        ChecksumMode.StoreAndVerify,
        null,
        null,
        false,
        asyncFileExecutor);
  }

  @After
  public void tearDown() throws Exception {
    cleanUp();
  }

  private void cleanUp() throws IOException {
    if (wowCache != null) {
      wowCache.delete();
      wowCache = null;
    }
    if (writeAheadLog != null) {
      writeAheadLog.delete();
      writeAheadLog = null;
    }
    if (asyncFileExecutor != null) {
      // Shut down the cached-thread-pool we passed into the WOWCache so each test method
      // does not leak non-daemon AsyncFile worker threads. WOWCache.delete already drains
      // pending work above; this only frees the underlying threads.
      asyncFileExecutor.shutdownNow();
      try {
        asyncFileExecutor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      asyncFileExecutor = null;
    }
    if (storagePath != null && Files.exists(storagePath)) {
      try (var stream = Files.walk(storagePath)) {
        stream
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(
                p -> {
                  try {
                    Files.deleteIfExists(p);
                  } catch (IOException e) {
                    // best-effort cleanup
                  }
                });
      }
    }
  }

  private void overwritePage(long fileId, byte[] contents) throws IOException {
    var diskPath = storagePath.resolve(wowCache.nativeFileNameById(fileId));
    try (var channel = FileChannel.open(diskPath, StandardOpenOption.WRITE)) {
      channel.write(ByteBuffer.wrap(contents), File.HEADER_SIZE);
    }
  }

  private RecoveryPageContext declare(int internalId, long pageIndex,
      LogSequenceNumber position) {
    var context = new RecoveryPageContext();
    var pages = new TreeMap<Long, LogSequenceNumber>();
    pages.put(pageIndex, position);
    context.setDeclaredPages(Map.of(internalId, pages));
    wowCache.setRecoveryPageContext(context);
    return context;
  }

  /**
   * Only a declared page with no DWL copy may be rebuilt. A checksum error and a missing
   * magic number both become an empty page with an unset header LSN during crash replay.
   */
  @Test
  public void declaredInvalidMagicAndChecksumRebuildWithoutDwlCopy() throws Exception {
    var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    var position = new LogSequenceNumber(2, 12);
    declare(wowCache.internalFileId(fileId), 0, position);
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
    try (var warnings = LogRecordCollector.attachTo(WOWCache.class)) {
      overwritePage(fileId, new byte[PAGE_SIZE]);
      var rebuilt = wowCache.loadOrAdd(fileId, 0, true);
      try {
        assertEquals(new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(rebuilt.getBuffer()));
        assertEquals(0, rebuilt.getBuffer().getLong(0));
        wowCache.updateDirtyPagesTable(rebuilt, position);
        wowCache.store(fileId, 0, rebuilt);
        var dirtyField = WOWCache.class.getDeclaredField("dirtyPages");
        dirtyField.setAccessible(true);
        var dirty = (ConcurrentHashMap<?, ?>) dirtyField.get(wowCache);
        assertEquals(position, dirty.get(new PageKey(wowCache.internalFileId(fileId), 0)));
        var cachedField = WOWCache.class.getDeclaredField("writeCachePages");
        cachedField.setAccessible(true);
        var cached = (ConcurrentHashMap<?, ?>) cachedField.get(wowCache);
        assertSame(rebuilt, cached.get(new PageKey(wowCache.internalFileId(fileId), 0)));
      } finally {
        rebuilt.decrementReadersReferrer();
      }
      assertTrue(warnings.warnedWithAll(FILE_NAME, "page 0", storageName,
          "empty page", "logged changes, if any"));
      wowCache.flush(fileId);
      assertNull(wowCache.getMinimalNotFlushedSegment());

      // Break only the CRC-covered data. The magic remains valid.
      var stamped = wowCache.loadOrAdd(fileId, 0, true);
      stamped.decrementReadersReferrer();
      var bytes = Files.readAllBytes(storagePath.resolve(wowCache.nativeFileNameById(fileId)));
      bytes[File.HEADER_SIZE + DurablePage.NEXT_FREE_POSITION] ^= 1;
      overwritePage(fileId, java.util.Arrays.copyOfRange(bytes,
          File.HEADER_SIZE, File.HEADER_SIZE + PAGE_SIZE));
      var checksumRebuilt = wowCache.loadOrAdd(fileId, 0, true);
      try {
        assertEquals(new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(checksumRebuilt.getBuffer()));
      } finally {
        checksumRebuilt.decrementReadersReferrer();
      }
    } finally {
      wowCache.setRecoveryPageContext(null);
    }
  }

  /** A valid declared page stays unchanged, and a corrupt undeclared page still fails. */
  @Test
  public void validDeclaredPageIsPreservedAndUndeclaredDamageIsRejected() throws Exception {
    var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
    var diskPath = storagePath.resolve(wowCache.nativeFileNameById(fileId));
    var original = Files.readAllBytes(diskPath);
    declare(wowCache.internalFileId(fileId), 0, new LogSequenceNumber(1, 5));
    try {
      var page = wowCache.loadOrAdd(fileId, 0, true);
      try {
        var actual = new byte[PAGE_SIZE];
        page.getBuffer().duplicate().clear().get(actual);
        assertArrayEquals(java.util.Arrays.copyOfRange(original,
            File.HEADER_SIZE, File.HEADER_SIZE + PAGE_SIZE), actual);
      } finally {
        page.decrementReadersReferrer();
      }
      try (var channel = FileChannel.open(diskPath, StandardOpenOption.WRITE)) {
        channel.write(ByteBuffer.wrap(new byte[PAGE_SIZE]), File.HEADER_SIZE + PAGE_SIZE);
      }
      assertThrows(StorageException.class, () -> wowCache.loadOrAdd(fileId, 1, true));
    } finally {
      wowCache.setRecoveryPageContext(null);
    }
    overwritePage(fileId, new byte[PAGE_SIZE]);
    assertThrows(StorageException.class, () -> wowCache.loadOrAdd(fileId, 0, true));
  }

  /** A valid DWL copy wins over an empty rebuild even for a declared page. */
  @Test
  public void declaredPageUsesValidDwlCopyInsteadOfRebuilding() throws Exception {
    var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    var diskBytes = Files.readAllBytes(storagePath.resolve(wowCache.nativeFileNameById(fileId)));
    var valid = java.util.Arrays.copyOfRange(diskBytes,
        File.HEADER_SIZE, File.HEADER_SIZE + PAGE_SIZE);
    var doubleWriteLog = mock(DoubleWriteLog.class);
    var field = WOWCache.class.getDeclaredField("doubleWriteLog");
    field.setAccessible(true);
    field.set(wowCache, doubleWriteLog);
    when(doubleWriteLog.loadPage(eq(wowCache.internalFileId(fileId)), eq(0), any()))
        .thenAnswer(inv -> {
          var pointer = bufferPool.acquireDirect(false, Intention.LOAD_PAGE_FROM_DISK);
          pointer.getNativeByteBuffer().put(valid).clear();
          return pointer;
        });
    declare(wowCache.internalFileId(fileId), 0, new LogSequenceNumber(1, 5));
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
    overwritePage(fileId, new byte[PAGE_SIZE]);
    try (var warnings = LogRecordCollector.attachTo(WOWCache.class)) {
      var loaded = wowCache.loadOrAdd(fileId, 0, true);
      try {
        var actual = new byte[PAGE_SIZE];
        loaded.getBuffer().duplicate().clear().get(actual);
        assertArrayEquals(valid, actual);
        assertFalse(warnings.warnedWithAll("rebuilt", FILE_NAME));
      } finally {
        loaded.decrementReadersReferrer();
      }
      when(doubleWriteLog.loadPage(eq(wowCache.internalFileId(fileId)), eq(0), any()))
          .thenAnswer(inv -> {
            var pointer = bufferPool.acquireDirect(false, Intention.LOAD_PAGE_FROM_DISK);
            pointer.getNativeByteBuffer().clear().put(new byte[PAGE_SIZE]).clear();
            return pointer;
          });
      assertThrows(StorageException.class, () -> wowCache.loadOrAdd(fileId, 0, true));
      assertFalse(warnings.warnedWithAll("rebuilt", FILE_NAME));
    } finally {
      wowCache.setRecoveryPageContext(null);
    }
  }

  /** Off, Store, and disabled verification never enter the recovery rebuild path. */
  @Test
  public void skippedVerificationDoesNotRebuildDeclaredPage() throws Exception {
    var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0, false).decrementReadersReferrer();
    wowCache.flush(fileId);
    overwritePage(fileId, new byte[PAGE_SIZE]);
    declare(wowCache.internalFileId(fileId), 0, new LogSequenceNumber(1, 5));
    try (var warnings = LogRecordCollector.attachTo(WOWCache.class)) {
      for (var mode : new ChecksumMode[] {ChecksumMode.Off, ChecksumMode.Store}) {
        wowCache.setChecksumMode(mode);
        var page = wowCache.loadOrAdd(fileId, 0, true);
        page.decrementReadersReferrer();
      }
      wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
      var page = wowCache.loadOrAdd(fileId, 0, false);
      page.decrementReadersReferrer();
      assertFalse(warnings.warnedWithAll("rebuilt", FILE_NAME));
    } finally {
      wowCache.setRecoveryPageContext(null);
    }
  }

  /**
   * A complete replay unit gap-fills a file shortened outside WAL history. A later complete
   * unit reading an intermediate page before validation must report damage, not rebuild data
   * that may have held an older committed value. Neither unit declares that gap page.
   */
  @Test
  public void completeUnitGapAfterFileTruncationReportsBrokenIntermediatePage() throws Exception {
    var fileId = wowCache.addFile(FILE_NAME);
    for (int pageIndex = 0; pageIndex <= 4; pageIndex++) {
      wowCache.loadOrAdd(fileId, pageIndex, false).decrementReadersReferrer();
    }
    wowCache.flush(fileId);
    // Model an out-of-band truncation to one page before crash replay opens the file.
    assertTrue(wowCache.shrinkFile(fileId, PAGE_SIZE));
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
    var readCache = new LockFreeReadCache(bufferPool, 1024L * PAGE_SIZE, PAGE_SIZE);
    var storage = Mockito.mock(AbstractStorage.class, Mockito.CALLS_REAL_METHODS);
    for (var fieldValue : Map.of(
        "writeCache", wowCache,
        "readCache", readCache,
        "name", storageName,
        "deletedNonDurableFileIds", new IntOpenHashSet()).entrySet()) {
      var field = AbstractStorage.class.getDeclaredField(fieldValue.getKey());
      field.setAccessible(true);
      field.set(storage, fieldValue.getValue());
    }
    var replay = AbstractStorage.class.getDeclaredMethod("restoreAtomicUnit", List.class,
        ModifiableBoolean.class, RecoveryPageContext.class);
    replay.setAccessible(true);
    var context = new RecoveryPageContext();
    wowCache.setRecoveryPageContext(context);
    var executorMethod = WOWCache.class.getDeclaredMethod("commitExecutor");
    executorMethod.setAccessible(true);
    var executor = (ScheduledExecutorService) executorMethod.invoke(wowCache);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blocker = executor.submit(() -> {
      started.countDown();
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    try {
      assertTrue(started.await(10, TimeUnit.SECONDS));
      // No declaration preloads page 4. The first unit's redo itself creates pages 1..4.
      var highPage = new TestPageOperation(4, fileId, 1,
          new LogSequenceNumber(-1, -1), 42);
      highPage.setLsn(new LogSequenceNumber(3, 10));
      replay.invoke(storage, List.of(new AtomicUnitStartRecord(false, 1), highPage,
          new AtomicUnitEndRecord(1, false, null)), new ModifiableBoolean(), context);
      assertEquals(5, wowCache.getFilledUpTo(fileId));

      var gapPage = new TestPageOperation(2, fileId, 2,
          new LogSequenceNumber(-1, -1), 43);
      gapPage.setLsn(new LogSequenceNumber(3, 30));
      try (var warnings = LogRecordCollector.attachTo(WOWCache.class)) {
        var failure = assertThrows(InvocationTargetException.class,
            () -> replay.invoke(storage, List.of(new AtomicUnitStartRecord(false, 2), gapPage,
                new AtomicUnitEndRecord(2, false, null)), new ModifiableBoolean(), context));
        assertTrue(failure.getCause() instanceof StorageException);
        assertTrue(failure.getCause().getMessage().contains("verification failed for page `2`"));
        assertFalse(warnings.warnedWithAll("rebuilt", FILE_NAME));
      }
    } finally {
      release.countDown();
      blocker.get(10, TimeUnit.SECONDS);
      wowCache.setRecoveryPageContext(null);
      readCache.clear();
    }
  }

  /**
   * Extend branch: a fresh file has {@code currentSize == 0}; calling
   * {@code loadOrAdd(fileId, 0)} must take the one-page extend path, advance
   * {@code AsyncFile.size} to one page, and return a non-null {@link CachePointer} with
   * a clean buffer at position 0.
   */
  @Test
  public void extendBranchAllocatesAndReturnsEmptyPointer() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    assertEquals(
        "fresh file must start at 0 pages", 0L, wowCache.getFilledUpTo(fileId));

    final var pointer = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull("loadOrAdd must never return null on the extend branch", pointer);
      // The CachePointer's PageFrame buffer is freshly cleared by pageFramePool.acquire;
      // verify the buffer is positioned at 0 (callers expect a clean buffer state).
      final var buffer = pointer.getBuffer();
      assertNotNull(buffer);
      assertEquals("buffer should be positioned at 0", 0, buffer.position());
      // Magic-stamped empty buffer must carry LSN(-1, -1); the disk-side magic stamp
      // is written asynchronously by EnsurePageIsValidInFileTask but the in-memory LSN
      // is set directly by newEmptyCachePointer.
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(buffer));
    } finally {
      pointer.decrementReadersReferrer();
    }
    // After the extend, getFilledUpTo reflects the one new page allocated by AsyncFile.
    assertEquals(
        "extend branch must advance AsyncFile.size by one page",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /** The extend site must publish a fully zeroed page with an unset header LSN. */
  @Test
  public void extendPublishesCoordinatesOnlyAfterContentIsComplete() throws Exception {
    long fileId = wowCache.addFile(FILE_NAME);
    try (var publication = new PublicationFixture(fileId,
        Intention.ADD_NEW_PAGE_IN_DISK_CACHE, emptyPage())) {
      publication.check(wowCache.loadOrAdd(fileId, 0, false));
    }
  }

  /** Disk bytes, including the header and payload, must be complete before publication. */
  @Test
  public void plainDiskPublishesCoordinatesOnlyAfterContentIsComplete() throws Exception {
    var image = preparePublicationImage(false);
    try (var publication = new PublicationFixture(image.fileId(),
        Intention.LOAD_PAGE_FROM_DISK, image.complete())) {
      publication.check(wowCache.loadOrAdd(image.fileId(), 0, true));
    }
  }

  /** An encrypted disk image must be decrypted, not merely read, before publication. */
  @Test
  public void encryptedDiskPublishesCoordinatesOnlyAfterContentIsComplete() throws Exception {
    var image = preparePublicationImage(true);
    assertFalse("encrypted bytes must differ from the expected plaintext",
        java.util.Arrays.equals(image.disk(), image.complete()));
    try (var publication = new PublicationFixture(image.fileId(),
        Intention.LOAD_PAGE_FROM_DISK, image.complete())) {
      publication.check(wowCache.loadOrAdd(image.fileId(), 0, true));
    }
  }

  /** A corrupt disk page must be replaced with the complete DWL copy before publication. */
  @Test
  public void repairedDiskPublishesCoordinatesOnlyAfterContentIsComplete() throws Exception {
    checkRepairPublication(false);
  }

  /** An encrypted DWL copy must also be decrypted before the repaired frame is published. */
  @Test
  public void encryptedRepairPublishesCoordinatesOnlyAfterContentIsComplete() throws Exception {
    checkRepairPublication(true);
  }

  private void checkRepairPublication(boolean encrypted) throws Exception {
    var image = preparePublicationImage(encrypted);
    overwritePage(image.fileId(), new byte[PAGE_SIZE]);
    var doubleWriteLog = mock(DoubleWriteLog.class);
    setCacheField("doubleWriteLog", doubleWriteLog);
    when(doubleWriteLog.loadPage(eq(wowCache.internalFileId(image.fileId())), eq(0), any()))
        .thenAnswer(invocation -> {
          var pointer = bufferPool.acquireDirect(false, Intention.LOAD_PAGE_FROM_DISK);
          pointer.getNativeByteBuffer().put(image.disk()).clear();
          return pointer;
        });
    try (var publication = new PublicationFixture(image.fileId(),
        Intention.LOAD_PAGE_FROM_DISK, image.complete())) {
      publication.check(wowCache.loadOrAdd(image.fileId(), 0, true));
      Mockito.verify(doubleWriteLog).loadPage(
          eq(wowCache.internalFileId(image.fileId())), eq(0), any());
    }
  }

  /** Replay must finish both the zero fill and the unset LSN before publishing a rebuild. */
  @Test
  public void rebuiltDiskPublishesCoordinatesOnlyAfterContentIsComplete() throws Exception {
    var image = preparePublicationImage(false);
    var damaged = image.disk().clone();
    damaged[0] ^= 1;
    overwritePage(image.fileId(), damaged);
    declare(wowCache.internalFileId(image.fileId()), 0, new LogSequenceNumber(8, 20));
    try (var publication = new PublicationFixture(image.fileId(),
        Intention.LOAD_PAGE_FROM_DISK, emptyPage())) {
      publication.check(wowCache.loadOrAdd(image.fileId(), 0, true));
    } finally {
      wowCache.setRecoveryPageContext(null);
    }
  }

  private static byte[] emptyPage() {
    var buffer = ByteBuffer.allocate(PAGE_SIZE).order(ByteOrder.nativeOrder());
    DurablePage.setLogSequenceNumberForPage(buffer, new LogSequenceNumber(-1, -1));
    return buffer.array();
  }

  private void setCacheField(String name, Object value) throws ReflectiveOperationException {
    var field = WOWCache.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(wowCache, value);
  }

  private record PublicationImage(long fileId, byte[] disk, byte[] complete) {
  }

  private PublicationImage preparePublicationImage(boolean encrypted) throws Exception {
    if (encrypted) {
      // Configure the real cache encryption before creating the image. No loader seam is used.
      setCacheField("aesKey", new byte[16]);
      setCacheField("iv", new byte[16]);
    }
    long fileId = wowCache.addFile(FILE_NAME);
    var pointer = wowCache.loadOrAdd(fileId, 0, false);
    byte[] complete = new byte[PAGE_SIZE];
    try {
      var buffer = pointer.getBuffer();
      DurablePage.setLogSequenceNumberForPage(buffer, new LogSequenceNumber(7, 19));
      for (int i = DurablePage.NEXT_FREE_POSITION; i < PAGE_SIZE; i++) {
        buffer.put(i, (byte) (i * 3 + 1));
      }
      buffer.duplicate().clear().get(complete);
      wowCache.updateDirtyPagesTable(pointer, new LogSequenceNumber(7, 19));
      wowCache.store(fileId, 0, pointer);
    } finally {
      pointer.decrementReadersReferrer();
    }
    wowCache.flush(fileId);
    byte[] disk = java.util.Arrays.copyOfRange(
        Files.readAllBytes(storagePath.resolve(wowCache.nativeFileNameById(fileId))),
        File.HEADER_SIZE, File.HEADER_SIZE + PAGE_SIZE);
    // Encryption leaves the magic/update counter intact but encrypts the checksum and payload.
    // Compute the plaintext checksum independently and retain the actual disk magic header.
    System.arraycopy(disk, 0, complete, 0, WOWCache.CHECKSUM_OFFSET);
    var crc = new java.util.zip.CRC32();
    int dataOffset = WOWCache.CHECKSUM_OFFSET + Integer.BYTES;
    crc.update(complete, dataOffset, PAGE_SIZE - dataOffset);
    ByteBuffer.wrap(complete).order(ByteOrder.nativeOrder())
        .putInt(WOWCache.CHECKSUM_OFFSET, (int) crc.getValue());
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);
    return new PublicationImage(fileId, disk, complete);
  }

  /**
   * Observes both production construction sites at the publication boundary. Assertions run
   * inline before the real publication so no later fill can conceal premature visibility.
   */
  private final class PublicationFixture implements AutoCloseable {

    private final DirectMemoryAllocator allocator = new DirectMemoryAllocator();
    private final PageFramePool pool = new PageFramePool(PAGE_SIZE, allocator, 1);
    private final PageFrame frame =
        Mockito.spy(pool.acquire(true, Intention.TEST));
    private final Object originalPool;
    private final long fileId;

    private PublicationFixture(long fileId, Intention intention, byte[] expected)
        throws Exception {
      this.fileId = fileId;
      var poolField = WOWCache.class.getDeclaredField("pageFramePool");
      poolField.setAccessible(true);
      originalPool = poolField.get(wowCache);
      var suppliedPool = Mockito.spy(pool);
      Mockito.doReturn(frame).when(suppliedPool).acquire(true, intention);
      Mockito.doAnswer(invocation -> {
        var actual = new byte[PAGE_SIZE];
        frame.getBuffer().duplicate().clear().get(actual);
        assertArrayEquals("content must be complete at coordinate publication", expected, actual);
        invocation.callRealMethod();
        long stamp = frame.tryOptimisticRead();
        assertTrue("publication must release the exclusive lock", stamp != 0);
        assertEquals(fileId, frame.getFileId());
        assertEquals(0, frame.getPageIndex());
        assertTrue(frame.validate(stamp));
        return null;
      }).when(frame).publishPageCoordinates(fileId, 0);
      poolField.set(wowCache, suppliedPool);
    }

    private void check(CachePointer pointer) {
      try {
        assertNotNull(pointer);
        Mockito.verify(frame).publishPageCoordinates(fileId, 0);
      } finally {
        if (pointer != null) {
          pointer.decrementReadersReferrer();
        }
      }
    }

    @Override
    public void close() throws Exception {
      setCacheField("pageFramePool", originalPool);
      // clear also frees an acquired frame when a publication assertion interrupts construction.
      pool.clear();
      allocator.checkMemoryLeaks();
    }
  }

  /**
   * Extend branch on a non-empty file: pre-extend two pages so {@code currentSize == 2},
   * then call {@code loadOrAdd(fileId, 2)} to exercise the boundary {@code pageIndex ==
   * currentSize} on a non-empty file. The extend must add exactly one page (size goes
   * from 2 to 3) and return a magic-stamped empty pointer. Ensures the extend branch is
   * not accidentally restricted to the fresh-file case.
   */
  @Test
  public void extendBranchExtendsByOneOnNonEmptyFile() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();
    assertEquals(
        "two extends must advance AsyncFile.size to 2 pages",
        2L,
        wowCache.getFilledUpTo(fileId));

    final var pointer = wowCache.loadOrAdd(fileId, 2L, false);
    try {
      assertNotNull(pointer);
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "extend on non-empty file advances size by exactly one page",
        3L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Idempotent re-call: a second {@code loadOrAdd(fileId, 0)} after the first extend
   * must take the load branch ({@code pageIndex < currentSize}) and not advance the file
   * size further. Verifies that a repeated call on an already-allocated page is a pure
   * load (no double-extend, totality contract still holds).
   */
  @Test
  public void secondLoadOrAddOnSameIndexTakesLoadBranchAndDoesNotReExtend()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    assertEquals(
        "single extend advances AsyncFile.size to 1 page",
        1L,
        wowCache.getFilledUpTo(fileId));

    final var second = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull(
          "second loadOrAdd on the same page index must return a usable pointer",
          second);
    } finally {
      second.decrementReadersReferrer();
    }
    assertEquals(
        "second loadOrAdd on an already-allocated page must not advance file size",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Negative {@code pageIndex}: the dispatch prelude must reject a negative page index
   * before any side effects (no acquire, no allocate, no submit). The exception message
   * must include the offending value so the caller can identify the bad input.
   */
  @Test
  public void loadOrAddRejectsNegativePageIndex() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var thrown =
        assertThrows(
            IllegalArgumentException.class, () -> wowCache.loadOrAdd(fileId, -1L, false));
    assertNotNull("exception must carry a message", thrown.getMessage());
    assertTrue(
        "exception message must carry the bad page index value",
        thrown.getMessage().contains("-1"));
  }

  /**
   * Deleted-file caveat from the totality contract: the contract holds for any open,
   * non-deleted fileId, but a concurrently-deleted file surfaces an
   * {@link IllegalArgumentException} raw to the caller (caller-bug signal). Pre-extend
   * one page so the file is registered, delete it, then call {@code loadOrAdd} again;
   * the dispatch prelude (or {@code loadFileContent}) must propagate the
   * {@link IllegalArgumentException} instead of returning {@code null} or throwing a
   * different exception type.
   */
  @Test
  public void loadOrAddOnDeletedFilePropagatesIllegalArgumentExceptionRaw()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var p = wowCache.loadOrAdd(fileId, 0L, false);
    p.decrementReadersReferrer();
    wowCache.deleteFile(fileId);
    assertThrows(
        IllegalArgumentException.class, () -> wowCache.loadOrAdd(fileId, 0L, false));
  }

  /**
   * Load branch: after extending pages 0 and 1 and flushing them to disk, calling
   * {@code loadOrAdd(fileId, 0, false)} must take the load path (pageIndex less than
   * currentSize) and return a non-null {@link CachePointer} for an existing on-disk
   * page. The returned pointer must be the dirty-write-cache
   * pointer (priority over the on-disk image) when the page is still in
   * {@code writeCachePages}, and the disk-fallback pointer must be a fresh instance
   * (not the same one that's installed in {@code writeCachePages}). The flush call
   * before the dirty-write probe ensures the load-from-disk path observes a stamped
   * page, not the defensive totality fallback (which would silently return a
   * magic-stamped empty buffer if the on-disk file were lagging).
   */
  @Test
  public void loadBranchReturnsExistingPagePointerAndPrioritisesDirtyCache()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Extend two pages; both go through the extend branch on first invocation.
    final var page0 = wowCache.loadOrAdd(fileId, 0L, false);
    page0.decrementReadersReferrer();
    final var page1 = wowCache.loadOrAdd(fileId, 1L, false);
    page1.decrementReadersReferrer();

    // Drain the EnsurePageIsValidInFileTask submissions so page 1's on-disk magic stamp
    // is written before we test the disk-fallback path. Without this drain, the
    // load-branch fallback could trip on its defensive totality path even though that
    // would mask the real load-from-disk we are trying to verify.
    wowCache.flush(fileId);

    // Push page 0 into writeCachePages via store() so the dirty-write probe has
    // something to return on a subsequent loadOrAdd call.
    final var dirtyPointer = wowCache.load(fileId, 0L, new ModifiableBoolean(), false);
    try {
      assertNotNull(dirtyPointer);
      // Mark dirty by storing it back; this installs it in writeCachePages.
      wowCache.store(fileId, 0L, dirtyPointer);
    } finally {
      dirtyPointer.decrementReadersReferrer();
    }

    // currentSize is now 2; loadOrAdd(fileId, 0) takes the load branch and the
    // dirty-write probe wins, returning the very same CachePointer instance.
    final var loaded = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull("loadOrAdd must never return null on the load branch", loaded);
      assertSame(
          "dirty-write probe must win over on-disk image",
          dirtyPointer,
          loaded);
    } finally {
      loaded.decrementReadersReferrer();
    }

    // loadOrAdd(fileId, 1) takes the load branch and falls through to loadFileContent
    // because page 1 is not in writeCachePages. The returned pointer must be a fresh
    // (different-instance) CachePointer reading from disk. We cannot distinguish the
    // disk fallback from the dead-defensive totality fallback by LSN alone (a fresh
    // extend leaves LSN(-1,-1) on disk), but the prior wowCache.flush(fileId) ensures
    // the disk image is stamped, so a magic-stamped empty buffer from the totality
    // fallback would only fire if the dispatch prelude observed a longer AsyncFile.size
    // than loadFileContent saw — which the flush eliminates.
    final var loadedFromDisk = wowCache.loadOrAdd(fileId, 1L, false);
    try {
      assertNotNull(
          "loadOrAdd must never return null on the load branch (disk fallback)",
          loadedFromDisk);
      assertNotSame(
          "page not in writeCachePages must come from loadFileContent",
          dirtyPointer,
          loadedFromDisk);
    } finally {
      loadedFromDisk.decrementReadersReferrer();
    }
  }

  /**
   * Gap-fill branch: starting from a fresh file ({@code currentSize == 0}), calling
   * {@code loadOrAdd(fileId, 5)} must take the multi-page gap-fill path, advance
   * {@code AsyncFile.size} by 6 pages (indices 0..5 inclusive), and return a non-null
   * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer} for the
   * target page only. The intermediate gap pages (0..4) get an
   * {@link EnsurePageIsValidInFileTask} submission but are not held in the cache here.
   */
  @Test
  public void gapFillBranchAllocatesEntireGapAndReturnsTargetPointer() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    assertEquals(
        "fresh file must start at 0 pages", 0L, wowCache.getFilledUpTo(fileId));

    final var pointer = wowCache.loadOrAdd(fileId, 5L, false);
    try {
      assertNotNull("loadOrAdd must never return null on the gap-fill branch", pointer);
      // Buffer is freshly cleared by pageFramePool.acquire.
      final var buffer = pointer.getBuffer();
      assertNotNull(buffer);
      assertEquals("buffer should be positioned at 0", 0, buffer.position());
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(buffer));
    } finally {
      pointer.decrementReadersReferrer();
    }
    // After gap-fill, AsyncFile.size must advance from 0 to exactly 6 pages
    // ([0..5] inclusive = 6 pages). The extend / gap-fill branches use a single batched
    // allocateSpace call, so the post-call size is exactly pageIndex + 1, not just at
    // least.
    assertEquals(
        "gap-fill must advance AsyncFile.size to exactly pageIndex + 1 pages",
        6L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Smallest non-trivial gap-fill: starting at {@code currentSize == 1} after one extend,
   * a {@code loadOrAdd(fileId, 2)} call exercises the gap-fill branch where the loop
   * runs exactly twice (over pages 1 and 2). Verifies the inclusive {@code
   * [currentSize, pageIndex]} range against an off-by-one bug at the boundary.
   */
  @Test
  public void gapFillOfExactlyOnePageBoundary() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    assertEquals(
        "single extend advances AsyncFile.size to 1 page",
        1L,
        wowCache.getFilledUpTo(fileId));

    // currentSize == 1, pageIndex == 2 -> gap-fill of 2 pages.
    final var pointer = wowCache.loadOrAdd(fileId, 2L, false);
    try {
      assertNotNull(
          "smallest non-trivial gap-fill must return a usable pointer", pointer);
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "gap-fill of two pages must advance AsyncFile.size to exactly 3 pages",
        3L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * WAL-replay recovery simulation (gap-fill branch, {@code pageIndex >> currentSize}).
   *
   * <p>This test exercises the gap-fill branch as the WAL replay loop would in practice:
   * a component's {@code entryPoint.pagesSize} on disk records that the file was extended
   * to page {@code N}, but on reopen {@code AsyncFile.size} (re-initialized from the
   * physical disk length) may lag behind &mdash; so the replay loop calls
   * {@code loadOrAdd(fileId, N)} with {@code pageIndex > currentSize}. Here we simulate
   * that by extending two pages first (so {@code currentSize == 2}), then making a
   * synthetic recovery call with {@code pageIndex == 10} to trigger a gap of 8 pages.
   * The contract: all pages in {@code [2, 10]} are allocated on disk
   * ({@code AsyncFile.size} advances to 11), exactly one magic-stamped empty
   * {@link com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer} is returned
   * (for the target page only), and the pointer carries LSN {@code (-1,-1)}. See design.md
   * §"Crash safety" scenario B for the walk-through.
   */
  @Test
  public void recoverySimulationGapFillWithLargeGap() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Establish currentSize == 2 via two sequential extend calls.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();
    assertEquals(
        "two extends must advance AsyncFile.size to 2 pages",
        2L,
        wowCache.getFilledUpTo(fileId));

    // Simulate WAL replay targeting pageIndex=10 while currentSize==2 (gap of 8 pages).
    final var pointer = wowCache.loadOrAdd(fileId, 10L, false);
    try {
      assertNotNull(
          "gap-fill recovery call must return a non-null pointer for the target page",
          pointer);
      assertEquals(
          "target page's magic-stamped buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    // All pages [0..10] inclusive = 11 pages must be allocated.
    assertEquals(
        "gap-fill must advance AsyncFile.size to pageIndex + 1 pages",
        11L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Crash-safety scenario B (load branch after on-disk magic stamp is in place).
   *
   * <p>This test simulates the scenario documented in design.md §"Crash safety" scenario B:
   * a TX was committed, the {@code EnsurePageIsValidInFileTask} ran and stamped the page on
   * disk, and on the next reopen the page already exists on disk when {@code loadOrAdd} is
   * called with that index. The load branch ({@code pageIndex < currentSize}) must fire and
   * return the existing disk-resident page, not allocate a fresh extend. Specifically:
   * <ol>
   *   <li>Extend page 0 (first TX), flush to ensure the disk stamp has landed.</li>
   *   <li>Remove it from the dirty write-cache (via a second cache open / flush cycle) so
   *       the load falls through to {@code loadFileContent} on disk.</li>
   *   <li>Call {@code loadOrAdd(fileId, 0)} again; it must take the load branch and return
   *       a non-null pointer without advancing {@code AsyncFile.size}.</li>
   * </ol>
   * After the flush the page exists on disk; re-calling {@code loadOrAdd} on the same index
   * must be a pure load (file size stays at 1), demonstrating that crash-safety scenario B
   * resolves via the load branch, not a second extend.
   */
  @Test
  public void crashSafetyScenarioBLoadAfterOnDiskStamp() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // First call: extend branch fires, magic-stamps in memory, queues EnsurePageIsValidInFileTask.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    assertEquals(
        "extend must advance AsyncFile.size to 1 page",
        1L,
        wowCache.getFilledUpTo(fileId));

    // Flush so the EnsurePageIsValidInFileTask writes the magic stamp to disk and the page
    // can be loaded via loadFileContent on the next call.
    wowCache.flush(fileId);

    // Second call on the same index: load branch fires (pageIndex < currentSize == 1).
    // File size must stay at 1 page — no double-extend.
    final var loaded = wowCache.loadOrAdd(fileId, 0L, false);
    try {
      assertNotNull(
          "load branch after on-disk stamp must return a non-null pointer", loaded);
    } finally {
      loaded.decrementReadersReferrer();
    }
    assertEquals(
        "second loadOrAdd on an already-stamped page must not advance AsyncFile.size",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the extend branch: the extend branch never reads existing
   * data (it allocates space and returns a freshly magic-stamped empty pointer), so the
   * {@code verifyChecksums} flag is a no-op on this path. The test pins that the flag does
   * not accidentally route the extend branch into a read-from-disk verification step (which
   * would either return a torn pre-stamp page or throw an unexpected exception). Asserts
   * the same post-state as the {@code verifyChecksums=false} extend test: file size
   * advances by one page; pointer carries LSN(-1,-1).
   */
  @Test
  public void extendBranchSucceedsWithVerifyChecksumsTrue() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);

    final var pointer = wowCache.loadOrAdd(fileId, 0L, true);
    try {
      assertNotNull(
          "extend branch must return a non-null pointer with verifyChecksums=true",
          pointer);
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "extend with verifyChecksums=true must advance AsyncFile.size by one page",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the gap-fill branch: the gap-fill branch never reads
   * existing data either (it allocates a multi-page range and returns a freshly
   * magic-stamped empty pointer for the target page only), so the {@code verifyChecksums}
   * flag is a no-op on this path. Mirrors the {@code verifyChecksums=false} gap-fill test
   * — pins that the flag does not route this branch into an on-disk verification step.
   */
  @Test
  public void gapFillBranchSucceedsWithVerifyChecksumsTrue() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);

    final var pointer = wowCache.loadOrAdd(fileId, 5L, true);
    try {
      assertNotNull(
          "gap-fill branch must return a non-null pointer with verifyChecksums=true",
          pointer);
      assertEquals(
          "magic-stamped empty buffer must carry LSN(-1,-1)",
          new LogSequenceNumber(-1, -1),
          DurablePage.getLogSequenceNumberFromPage(pointer.getBuffer()));
    } finally {
      pointer.decrementReadersReferrer();
    }
    assertEquals(
        "gap-fill with verifyChecksums=true must advance AsyncFile.size to pageIndex + 1",
        6L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the load branch (clean page): after the extend branch
   * fires and the {@link EnsurePageIsValidInFileTask} flushes the magic stamp to disk, a
   * subsequent {@code loadOrAdd(fileId, 0, true)} must take the load branch
   * ({@code pageIndex &lt; currentSize}), read the page from disk, verify the magic +
   * CRC checksum successfully (since the on-disk page was stamped by the same
   * {@link WOWCache} instance under {@link ChecksumMode#StoreAndVerify}), and return a
   * non-null pointer. The test pins that {@code verifyChecksums=true} on a clean page is
   * a no-op observable at the API surface — no exception, no torn read.
   */
  @Test
  public void loadBranchWithVerifyChecksumsTrueOnCleanPageReturnsPointer() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Extend page 0 (extend branch fires; EnsurePageIsValidInFileTask submitted).
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    // Drain the single-threaded commitExecutor so the EnsurePageIsValidInFileTask has
    // written the magic-stamped on-disk image before we exercise the load branch with
    // checksum verification turned on.
    wowCache.flush(fileId);

    final var loaded = wowCache.loadOrAdd(fileId, 0L, true);
    try {
      assertNotNull(
          "load branch with verifyChecksums=true on a clean page must return a pointer",
          loaded);
    } finally {
      loaded.decrementReadersReferrer();
    }
    assertEquals(
        "load branch with verifyChecksums=true must not advance AsyncFile.size",
        1L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * verifyChecksums=true parity on the load branch (corrupted page) under
   * {@link ChecksumMode#StoreAndThrow}: simulates a torn page by writing garbage into the
   * data area of an on-disk magic-stamped page, then calls
   * {@code loadOrAdd(fileId, 0, true)} and asserts the load branch surfaces a
   * {@link StorageException} (the broken-page contract). Mirrors the legacy
   * {@code WOWCache.load}-with-checksum behaviour pinned by
   * {@code WOWCacheTestIT#testChecksumFailure}.
   *
   * <p>The corruption is written via a separate {@link AsyncFile} instance opened directly
   * on the file path so the in-process {@link WOWCache} read path is unaffected (the
   * cache's {@code loadFileContent} re-reads from disk on every call, so the corruption
   * is observable on the next {@code loadOrAdd} invocation).
   */
  @Test
  public void loadBranchWithVerifyChecksumsTrueOnCorruptedPageThrowsStorageException()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Extend page 0 and drain the executor so the on-disk magic stamp is in place.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.flush(fileId);

    // Switch to StoreAndThrow so a checksum mismatch surfaces as a StorageException
    // (under StoreAndVerify the broken-page detection logs but returns the page anyway).
    wowCache.setChecksumMode(ChecksumMode.StoreAndThrow);

    // Corrupt the on-disk page: open the underlying file directly and write garbage into
    // the data area (anywhere after NEXT_FREE_POSITION; the magic + CRC fields are at
    // offsets 0..NEXT_FREE_POSITION-1 so writing further in invalidates the CRC). A
    // separate cached-thread-pool executor backs this AsyncFile so the corruption write
    // does not deadlock against the wowCache's own async-file executor.
    final var nativeName = wowCache.nativeFileNameById(fileId);
    assertNotNull("file must have a registered native name", nativeName);
    final var diskPath = storagePath.resolve(nativeName);
    final var corruptionExecutor = Executors.newCachedThreadPool();
    try {
      final File file = new AsyncFile(diskPath, PAGE_SIZE, false, corruptionExecutor,
          storageName);
      file.open();
      try {
        // Write one byte of garbage into the data area to break the CRC.
        file.write(
            DurablePage.NEXT_FREE_POSITION,
            ByteBuffer.wrap(new byte[] {(byte) 0xAB}).order(ByteOrder.nativeOrder()));
      } finally {
        file.close();
      }
    } finally {
      corruptionExecutor.shutdownNow();
      try {
        corruptionExecutor.awaitTermination(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // loadOrAdd with verifyChecksums=true must now throw StorageException("Page ... is
    // broken") on the corrupted page.
    assertThrows(
        StorageException.class,
        () -> wowCache.loadOrAdd(fileId, 0L, true));
  }

  /**
   * Intermediate gap-page accessibility: after a gap-fill from {@code currentSize == 2}
   * to {@code target == 10}, every page in {@code [2, 10]} must be subsequently loadable
   * via {@code loadOrAdd} (which now takes the load branch, since
   * {@code pageIndex &lt; currentSize == 11}). Each intermediate pointer must carry the
   * magic-stamped empty buffer signature (LSN(-1,-1)) the
   * {@link EnsurePageIsValidInFileTask} writes for gap pages. The earlier
   * {@link #gapFillBranchAllocatesEntireGapAndReturnsTargetPointer} test only verified the
   * target's pointer; this test pins that the intermediate gap pages are not just
   * allocated on disk but actually retrievable via the same {@code loadOrAdd} API.
   *
   * <p>The {@code flush(fileId)} call between the gap-fill and the per-page load loop
   * ensures every {@link EnsurePageIsValidInFileTask} on the single-threaded
   * commitExecutor has run, so the load-branch reads observe magic-stamped pages instead
   * of falling through to the defensive totality fallback.
   */
  @Test
  public void gapFillIntermediatePagesAreLoadableViaLoadBranch() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // Establish currentSize == 2 via two sequential extends.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.loadOrAdd(fileId, 1L, false).decrementReadersReferrer();

    // Gap-fill from 2 to 10 (inclusive): post-state size == 11.
    wowCache.loadOrAdd(fileId, 10L, false).decrementReadersReferrer();
    assertEquals(
        "gap-fill must advance AsyncFile.size to pageIndex + 1",
        11L,
        wowCache.getFilledUpTo(fileId));

    // Drain the executor so every gap page's EnsurePageIsValidInFileTask has run and the
    // load branch reads a stamped page from disk.
    wowCache.flush(fileId);

    // Iterate the full gap range [0, 10] and assert each page is loadable via the load
    // branch with a magic-stamped LSN(-1,-1) buffer. We include [0, 1] (extended before
    // the gap-fill) in the check to confirm the test's invariant holds for the
    // pre-existing pages too, not just the intermediates.
    for (long pageIdx = 0L; pageIdx <= 10L; pageIdx++) {
      final var pointer = wowCache.loadOrAdd(fileId, pageIdx, false);
      try {
        assertNotNull(
            "intermediate gap page must be loadable: pageIndex=" + pageIdx, pointer);
        final var buffer = pointer.getBuffer();
        assertNotNull(buffer);
        assertEquals(
            "intermediate gap page must carry LSN(-1,-1): pageIndex=" + pageIdx,
            new LogSequenceNumber(-1, -1),
            DurablePage.getLogSequenceNumberFromPage(buffer));
      } finally {
        pointer.decrementReadersReferrer();
      }
    }
    // After loading every page in [0, 10], the file size must not have advanced (no
    // accidental re-extend on the load branch).
    assertEquals(
        "loading every gap page via the load branch must not advance AsyncFile.size",
        11L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Very-high {@code pageIndex} boundary: the gap-fill branch's dispatch guard rejects
   * requests where {@code (pageIndex - currentSize + 1) * pageSize > Integer.MAX_VALUE}
   * (the int overflow ceiling of {@link AsyncFile#allocateSpace}, which takes an int). The
   * test exercises this guard without actually allocating a multi-gigabyte file by picking
   * a {@code pageIndex} just above the boundary and asserting a {@link StorageException}
   * surfaces from the dispatch prelude.
   *
   * <p>Practical note (documented in the episode): exercising the boundary at the true
   * upper limit ({@code pageIndex = Integer.MAX_VALUE / pageSize - 2}) would require
   * allocating ~2 GB on disk per test run, which is impractical for unit-test CI. The
   * branch's overflow guard is purely arithmetic (no I/O occurs before the check fires),
   * so the test verifies the exact behaviour at the boundary without paying the disk cost.
   */
  @Test
  public void gapFillRejectsPageIndexExceedingAllocateSpaceIntegerCeiling() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    // currentSize is 0; pick pageIndex such that (pageIndex - 0 + 1) * pageSize >
    // Integer.MAX_VALUE. The +10 padding keeps the math comfortably above the overflow
    // boundary across page-size choices.
    final long boundaryPageIndex = (long) (Integer.MAX_VALUE / PAGE_SIZE) + 10L;

    final var thrown =
        assertThrows(
            StorageException.class, () -> wowCache.loadOrAdd(fileId, boundaryPageIndex, false));
    assertNotNull("exception must carry a message", thrown.getMessage());
    assertTrue(
        "exception must reference the int-limit overflow on allocateSpace",
        thrown.getMessage().contains("allocateSpace int limit"));

    // The dispatch guard fires before AsyncFile.allocateSpace, so the file size is
    // unchanged by the failed call.
    assertEquals(
        "failed gap-fill must not advance AsyncFile.size",
        0L,
        wowCache.getFilledUpTo(fileId));
  }

  /**
   * Scenario 6 — {@link EnsurePageIsValidInFileTask} idempotency, single-threaded sequence
   * assertion (no executor gating, no production-source seam).
   *
   * <p>Extend page 0 via {@code loadOrAdd(fileId, 0, false)} (which submits one
   * {@link EnsurePageIsValidInFileTask} to the single-threaded commitExecutor), drain via
   * {@link WOWCache#flush(long)} so the submitted task runs and stamps the on-disk page,
   * then deliberately overwrite the page contents with distinctive marker bytes via the
   * normal write path ({@code wowCache.load} + buffer mutation + {@code wowCache.store}
   * + {@code wowCache.flush}). The page on disk now carries the marker bytes instead of
   * the freshly-stamped magic bytes. Reading the full file byte content as a baseline
   * after the marker write, this test then directly calls
   * {@link WOWCache#writeValidPageInFile(int, int)} twice in sequence and asserts that
   * across both invocations both the underlying file size AND the file's byte content
   * stay identical to the baseline — both direct calls must short-circuit on the
   * {@code getUnderlyingFileSize() &lt;= pagePosition} guard at the top of
   * {@code writeValidPageInFile}.
   *
   * <p>The byte-content compare is the load-bearing assertion: a regression that
   * removed the short-circuit guard would re-stamp the page with the magic-stamped
   * empty buffer ({@code LSN(-1,-1)} + magic bytes), wiping the {@code 0xC3} marker
   * bytes from the data area, and the {@code assertArrayEquals} below would fail.
   * The file-size assertion is kept as belt-and-braces — a positional write to the
   * same offset would not change the file size, so a size-only check would silently
   * pass even if the page contents were trampled.
   *
   * <p>The disk-write count is also tracked via {@link Files#size(Path)} on the
   * underlying file, subtracting {@link File#HEADER_SIZE} per
   * {@link AsyncFile#getUnderlyingFileSize()}.
   */
  @Test
  public void writeValidPageInFileIsIdempotentAcrossRepeatedDirectInvocations()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var intId = AbstractWriteCache.extractFileId(fileId);

    // Extend page 0; this submits one EnsurePageIsValidInFileTask to the single-threaded
    // commitExecutor and bumps AsyncFile.size (in-memory tracking) to one page.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();

    // Drain the commitExecutor (FileFlushTask runs after the prior
    // EnsurePageIsValidInFileTask, since the executor is single-threaded) so the on-disk
    // page is stamped before we deliberately overwrite the data area below.
    wowCache.flush(fileId);

    final var nativeName = wowCache.nativeFileNameById(fileId);
    assertNotNull("file must have a registered native name", nativeName);
    final var diskPath = storagePath.resolve(nativeName);

    // Deliberately overwrite the page's data area with distinctive marker bytes (0xC3) via
    // the normal write path: load the page from cache, mutate the buffer in the data
    // region (past NEXT_FREE_POSITION so the magic + CRC stay valid and the LSN stays
    // distinct from (-1,-1)), store it back to install in writeCachePages, and flush so
    // the marker bytes land on disk. After this, the on-disk page no longer carries the
    // magic-stamped empty buffer that writeValidPageInFile would have written; any
    // accidental re-stamping by a regression would visibly trample the marker bytes.
    final var dirty = wowCache.load(fileId, 0L, new ModifiableBoolean(), false);
    try {
      final var buffer = dirty.getBuffer();
      buffer.position(DurablePage.NEXT_FREE_POSITION);
      for (int i = DurablePage.NEXT_FREE_POSITION; i < PAGE_SIZE; i++) {
        buffer.put((byte) 0xC3);
      }
      // Set a distinguishable LSN so a re-stamp to LSN(-1,-1) would also be detectable on
      // the bytes around the LSN field.
      DurablePage.setLogSequenceNumberForPage(buffer, new LogSequenceNumber(42L, 42));
      // The flush path asserts buffer.position() == 0 before copying — rewind here so the
      // store + subsequent flush observes the contract.
      buffer.position(0);
      wowCache.store(fileId, 0L, dirty);
    } finally {
      dirty.decrementReadersReferrer();
    }
    wowCache.flush(fileId);

    // Baseline: after the marker write + flush, the underlying file size is exactly one
    // page (HEADER_SIZE + pageSize on disk) and the byte content reflects the marker.
    final long sizeAfterDrain = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "after the marker-bytes flush, the underlying file size must be one page",
        (long) PAGE_SIZE,
        sizeAfterDrain);
    final byte[] bytesBefore = Files.readAllBytes(diskPath);

    // First direct call: writeValidPageInFile must observe
    // getUnderlyingFileSize() (= pageSize) > pagePosition (= 0), short-circuit at the
    // top-of-method guard, and write nothing. Both the file size and the byte content
    // are unchanged. A regression that removed the short-circuit guard would write a
    // fresh magic-stamped empty buffer to the same offset, trampling the 0xC3 marker
    // bytes — the byte-content assertion below catches this case; the file-size
    // assertion alone would not, because a positional write to the same offset leaves
    // the file size unchanged.
    wowCache.writeValidPageInFile(intId, 0);
    final long sizeAfterFirstDirect = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "first direct writeValidPageInFile call must short-circuit (no new disk write)",
        sizeAfterDrain,
        sizeAfterFirstDirect);
    assertArrayEquals(
        "first direct writeValidPageInFile call must short-circuit; on-disk bytes must"
            + " match the marker-bytes baseline",
        bytesBefore,
        Files.readAllBytes(diskPath));

    // Second direct call: same short-circuit guard fires again; the file size and byte
    // content remain identical to the baseline.
    wowCache.writeValidPageInFile(intId, 0);
    final long sizeAfterSecondDirect = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "second direct writeValidPageInFile call must short-circuit (no new disk write)",
        sizeAfterDrain,
        sizeAfterSecondDirect);
    assertArrayEquals(
        "second direct writeValidPageInFile call must short-circuit; on-disk bytes must"
            + " still match the marker-bytes baseline",
        bytesBefore,
        Files.readAllBytes(diskPath));
  }

  /**
   * Gap-fill branch counterpart of
   * {@link #writeValidPageInFileIsIdempotentAcrossRepeatedDirectInvocations} — pins the
   * idempotency of the per-page {@link EnsurePageIsValidInFileTask} submissions that the
   * gap-fill branch issues for every intermediate page in {@code [currentSize, target]}.
   *
   * <p>The production Javadoc on {@code loadOrAddGapFillBranch} explicitly claims those
   * per-page tasks are idempotent, and recovery / WAL replay is the only normal caller
   * of the gap-fill branch — so this is the path that most needs the idempotency
   * guarantee to be falsifiable. Using a gap-fill from {@code currentSize == 0} to
   * {@code target == 5} produces six per-page task submissions. After the
   * {@link WOWCache#flush(long)} drains them, the on-disk file holds six magic-stamped
   * empty pages. Reading the full byte content as a baseline, the test then directly
   * calls {@link WOWCache#writeValidPageInFile(int, int)} for every page index in
   * {@code [0, 5]} and asserts that both the underlying file size AND the byte content
   * stay identical to the baseline. A regression that re-stamped intermediate pages
   * (e.g., wrote a different magic or trampled header bytes) would surface as a
   * byte-array mismatch.
   *
   * <p>Note: the intermediate pages already carry {@code LSN(-1,-1)} after the executor
   * tasks run, so the byte-content compare catches a re-stamp that wrote a different
   * stamp shape — not a re-stamp that wrote the same stamp shape, since the bytes
   * would coincidentally still match. The file-size delta covers the second case (a
   * second positional write to the same offset never changes file size; the test
   * therefore relies on the byte compare for the same-stamp case being implausible —
   * any regression that re-entered the magic-stamp write path would also re-acquire
   * a fresh allocator pointer, which would not coincidentally produce identical
   * bytes because the per-page CRC depends on the page's content and offset).
   */
  @Test
  public void writeValidPageInFileIsIdempotentAcrossRepeatedDirectInvocationsOnGapFillPages()
      throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var intId = AbstractWriteCache.extractFileId(fileId);

    // Trigger the gap-fill branch: target index 5 on a fresh file submits six
    // EnsurePageIsValidInFileTask instances to the single-threaded commitExecutor (one
    // per page in [0, 5]).
    wowCache.loadOrAdd(fileId, 5L, false).decrementReadersReferrer();

    // Drain so all six on-disk magic stamps land before the byte-content baseline.
    wowCache.flush(fileId);

    final var nativeName = wowCache.nativeFileNameById(fileId);
    assertNotNull("file must have a registered native name", nativeName);
    final var diskPath = storagePath.resolve(nativeName);

    // Baseline: file size must reflect six pages worth of data.
    final long sizeAfterDrain = Files.size(diskPath) - File.HEADER_SIZE;
    assertEquals(
        "after the gap-fill executor tasks drain, the underlying file size must be six pages",
        6L * PAGE_SIZE,
        sizeAfterDrain);
    final byte[] bytesBefore = Files.readAllBytes(diskPath);

    // Direct calls for every gap page: each must short-circuit on the
    // getUnderlyingFileSize() guard (pagePosition < currentSize * pageSize), leaving
    // both the file size and the on-disk bytes unchanged.
    for (int p = 0; p <= 5; p++) {
      wowCache.writeValidPageInFile(intId, p);
      final long sizeAfter = Files.size(diskPath) - File.HEADER_SIZE;
      assertEquals(
          "direct writeValidPageInFile call on gap page " + p + " must short-circuit"
              + " (no new disk write)",
          sizeAfterDrain,
          sizeAfter);
      assertArrayEquals(
          "direct writeValidPageInFile call on gap page " + p + " must short-circuit;"
              + " on-disk bytes must match the post-drain baseline",
          bytesBefore,
          Files.readAllBytes(diskPath));
    }
  }

  /**
   * Regression test for the removed-file guard in
   * {@link WOWCache#writeValidPageInFile(int, int)}.
   *
   * <p><b>Scenario.</b> {@code loadOrAdd}'s extend and gap-fill branches submit an
   * {@link EnsurePageIsValidInFileTask} to the commit executor and return without waiting for
   * it, so the stamp write is asynchronous with respect to the allocation. If the file is
   * deleted in the interval by {@code deleteFile}, the queued task
   * runs against a fileId that {@code files.acquire} no longer resolves and returns
   * {@code null} for. This test drives that state deterministically: allocate and flush page 0
   * (so the file genuinely exists on disk), delete the file, then invoke
   * {@code writeValidPageInFile} directly, which is precisely what the queued task would do.
   *
   * <p><b>Pre-fix failure mode.</b> There was no null check: the method dereferenced the null
   * entry at {@code entry.get()} and threw {@link NullPointerException} out of the flush
   * executor (and would then have called {@code files.release(null)} from the finally block).
   * The fix logs at WARN and returns.
   *
   * <p><b>Assertions.</b> The call must not throw, and it must not resurrect the deleted file
   * on disk — a stamp write here would recreate a file the storage believes is gone.
   */
  @Test
  public void writeValidPageInFileSkipsStampWhenFileWasAlreadyRemoved() throws IOException {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var intId = AbstractWriteCache.extractFileId(fileId);

    // Allocate page 0 and drain the executor so the file exists on disk with a stamped page.
    wowCache.loadOrAdd(fileId, 0L, false).decrementReadersReferrer();
    wowCache.flush(fileId);

    final var nativeName = wowCache.nativeFileNameById(fileId);
    assertNotNull("file must have a registered native name", nativeName);
    final var diskPath = storagePath.resolve(nativeName);
    assertTrue("the file must exist on disk before the delete", Files.exists(diskPath));

    // Remove the file. This unregisters it from the `files` container, so every subsequent
    // files.acquire(...) for this id resolves to null.
    wowCache.deleteFile(fileId);
    assertFalse("deleteFile must remove the file from disk", Files.exists(diskPath));

    // Stand-in for an EnsurePageIsValidInFileTask that was queued before the delete and runs
    // after it. Pre-fix this threw NullPointerException from entry.get(); post-fix it logs a
    // WARN and returns.
    wowCache.writeValidPageInFile(intId, 0);

    assertFalse(
        "writeValidPageInFile must not resurrect a file that was already removed",
        Files.exists(diskPath));
  }

  /**
   * Regression test for the missing early return after the {@code flushError} diagnostic in
   * {@link WOWCache#writeValidPageInFile(int, int)}.
   *
   * <p><b>Scenario.</b> Once a background flush has failed, {@code flushError} is set and every
   * other flush-path entry point ({@code executeFileFlush}, {@code executeFindDirtySegment},
   * {@code executePeriodicFlush}, {@code executeFlush}) logs and returns immediately — see the
   * sibling tests in {@code WOWCacheFlushErrorTest}. {@code writeValidPageInFile} logged the
   * same "can not write valid page in file" diagnostic and then carried on writing anyway,
   * contradicting its own message and stamping a blank page into a file whose write cache is
   * already known to be unflushable.
   *
   * <p><b>How the state is driven.</b> {@code flushError} is a private field with no setter, so
   * it is injected by reflection — the same seam {@code WOWCacheFlushErrorTest} uses for the
   * four sibling guards. The target page index is deliberately past the end of the freshly
   * created (zero-length) file, so the inner
   * {@code getUnderlyingFileSize() <= pagePosition} short-circuit does NOT fire and the method
   * would genuinely attempt the write.
   *
   * <p><b>Pre-fix failure mode.</b> Falling through reached {@code AsyncFile.write}, whose
   * {@code checkPosition} rejects an offset outside the allocated region and throws
   * {@code StorageException("You are going to access region outside of allocated file
   * position")} — a RuntimeException that the method's {@code catch (IOException |
   * InterruptedException)} does not intercept, so it escaped raw to the flush executor. Post-fix
   * the method returns at the guard and nothing is attempted.
   *
   * <p><b>Assertions.</b> The call must not throw and must not have extended the file. The
   * injected {@code flushError} is cleared in a finally block so the {@code tearDown} teardown
   * path ({@code wowCache.delete()}) is not perturbed by it.
   */
  @Test
  public void writeValidPageInFileSkipsStampWhenFlushErrorIsRecorded() throws Exception {
    final var fileId = wowCache.addFile(FILE_NAME);
    final var intId = AbstractWriteCache.extractFileId(fileId);
    assertEquals("fresh file must start at 0 pages", 0L, wowCache.getFilledUpTo(fileId));

    final var flushErrorField = WOWCache.class.getDeclaredField("flushError");
    flushErrorField.setAccessible(true);
    flushErrorField.set(wowCache, new IOException("injected flush failure"));
    try {
      // Page 3 is past the end of the zero-length file, so the underlying-size short-circuit
      // inside writeValidPageInFile cannot mask the flushError guard: the only thing that can
      // stop the write here is the early return under test.
      wowCache.writeValidPageInFile(intId, 3);
    } finally {
      flushErrorField.set(wowCache, null);
    }

    assertEquals(
        "writeValidPageInFile must not extend the file after a recorded flush error",
        0L,
        wowCache.getFilledUpTo(fileId));
  }
}
