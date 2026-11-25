package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.concur.lock.ThreadInterruptedException;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class LockFreeReadCacheDirtyNotificationFailureTest {

  @Test(timeout = 30_000)
  public void overflowOnReadAndWriteMissReleasesPinForCrossThreadReuseAndEviction()
      throws Exception {
    var pool = new ByteBufferPool(4096);
    var read = new LockFreeReadCache(pool, 16L * 4096, 4096);
    var write = mock(WriteCache.class);
    when(write.loadOrAdd(anyLong(), anyLong(), anyBoolean())).thenAnswer(call -> {
      long pageIndex = call.getArgument(1);
      var frame = pool.pageFramePool().acquire(true, Intention.TEST);
      var pointer = new CachePointer(frame, pool.pageFramePool(), 1, (int) pageIndex);
      pointer.incrementReadersReferrer();
      return pointer;
    });
    var interrupted = new InterruptedException("overflow interrupted");
    var failed = new AssertionError("overflow failed");
    doThrow(interrupted, failed).doNothing().when(write).checkCacheOverflow();

    try {
      assertSame(interrupted, assertThrows(ThreadInterruptedException.class,
          () -> read.loadForRead(1, 0, write, false)).getCause());
      assertSame(failed, assertThrows(AssertionError.class,
          () -> read.loadOrAddForWrite(1, 1, write, false, null)));
      verify(write, never()).updateDirtyPagesTable(any(), any());

      onWorker(() -> {
        for (int i = 0; i < 2; i++) {
          var frame = read.getPageFrameOptimistic(1, i);
          var entry = read.loadForRead(1, i, write, false);
          read.releaseFromRead(entry);
          assertSame(frame, entry.getCachePointer().getPageFrame());
          assertTrue(entry.isReleased());
        }
        verify(write).loadOrAdd(1, 0, false);
        verify(write).loadOrAdd(1, 1, false);

        assertEquals(2L * 4096, read.getUsedMemory());
        read.closeFile(1, false, write);
        verify(write).close(1, false);
        assertNull(read.getPageFrameOptimistic(1, 0));
        assertNull(read.getPageFrameOptimistic(1, 1));
        assertEquals(0, read.getUsedMemory());
        read.assertSize();
      });
    } finally {
      onWorker(() -> {
        read.clear();
        assertEquals(0, read.getUsedMemory());
        pool.clear();
      });
    }
  }

  @Test(timeout = 30_000)
  public void admissionFailureReleasesMissPinBeforeOverflowCheck() throws Exception {
    var pool = new ByteBufferPool(4096);
    var read = new LockFreeReadCache(pool, 16L * 4096, 4096);
    var write = mock(WriteCache.class);
    var failure = new IllegalStateException("victim release failed during admission");
    var rejectRelease = new AtomicBoolean(true);
    doAnswer(call -> {
      int page = Math.toIntExact(call.<Long>getArgument(1));
      var frames = pool.pageFramePool();
      var pointer = new CachePointer(frames.acquire(true, Intention.TEST), frames, 1, page);
      pointer.incrementReadersReferrer();
      return pointer;
    }).when(write).loadOrAdd(anyLong(), anyLong(), anyBoolean());

    try {
      // Make either eviction choice fail, independent of the frequency sketch seed.
      for (int i = 0; i < 16; i++) {
        var frames = pool.pageFramePool();
        var pointer = spy(new CachePointer(frames.acquire(true, Intention.TEST), frames, 1, i));
        pointer.incrementReadersReferrer();
        doAnswer(call -> {
          call.callRealMethod();
          if (rejectRelease.getAndSet(false)) {
            throw failure;
          }
          return null;
        }).when(pointer).decrementReadersReferrer();
        doReturn(pointer).when(write).loadOrAdd(1, i, false);
        read.releaseFromRead(read.loadForRead(1, i, write, false));
      }

      assertSame(failure, assertThrows(IllegalStateException.class,
          () -> read.loadForRead(1, 16, write, false)));
      verify(write, times(16)).checkCacheOverflow();

      onWorker(() -> {
        var entry = read.loadForRead(1, 16, write, false);
        read.releaseFromRead(entry);
        assertTrue(entry.isReleased());
        verify(write).loadOrAdd(1, 16, false);
      });
    } finally {
      onWorker(() -> {
        read.clear();
        pool.clear();
      });
    }
  }

  private static void onWorker(CheckedAction action) throws Exception {
    var task = new FutureTask<Void>(() -> {
      action.run();
      return null;
    });
    Thread.ofPlatform().daemon().start(task);
    task.get(10, TimeUnit.SECONDS);
  }

  @FunctionalInterface
  private interface CheckedAction {

    void run() throws Exception;
  }

  // Runtime failures and VM Errors on both cache hits and fresh allocations must reach the
  // caller unchanged. No store occurs. A different thread can reacquire the page, and clear
  // can evict it, proving that neither the exclusive lock nor the entry reference leaked.
  @Test
  public void throwingNotificationsBalanceLocksAndReferencesOnHitsAndMisses() throws Exception {
    for (boolean hit : new boolean[] {false, true}) {
      for (Throwable failure : new Throwable[] {new IllegalStateException("dirty failure"),
          new OutOfMemoryError("mark allocation failure")}) {
        var pool = new ByteBufferPool(4096);
        var read = new LockFreeReadCache(pool, 128L * 4096, 4096);
        var write = mock(WriteCache.class);
        var pointer = new AtomicReference<CachePointer>();
        when(write.loadOrAdd(anyLong(), anyLong(), anyBoolean())).thenAnswer(call -> {
          var frame = pool.pageFramePool().acquire(true, Intention.ADD_NEW_PAGE_IN_DISK_CACHE);
          var loaded = new CachePointer(frame, pool.pageFramePool(), 1, 0);
          loaded.incrementReadersReferrer();
          pointer.set(loaded);
          return loaded;
        });
        if (hit) {
          read.releaseFromRead(read.loadForRead(1, 0, write, false));
        }
        doAnswer(call -> {
          throw failure;
        }).when(write).updateDirtyPagesTable(any(), any());
        var worker = Executors.newSingleThreadExecutor();
        try {
          assertSame(failure, assertThrows(failure.getClass(),
              () -> read.loadOrAddForWrite(1, 0, write, false, null)));
          verify(write, never()).store(anyLong(), anyLong(), any());
          var entry = worker.submit(() -> {
            var loaded = read.loadForRead(1, 0, write, false);
            loaded.acquireExclusiveLock();
            loaded.releaseExclusiveLock();
            assertEquals(0, loaded.getUsagesCount());
            read.releaseFromRead(loaded);
            return loaded;
          }).get(10, TimeUnit.SECONDS);
          assertSame(pointer.get(), entry.getCachePointer());
          read.clear();
          assertEquals(0, read.getUsedMemory());
        } finally {
          worker.shutdownNow();
          assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
          read.clear();
          pool.clear();
        }
      }
    }
  }
}
