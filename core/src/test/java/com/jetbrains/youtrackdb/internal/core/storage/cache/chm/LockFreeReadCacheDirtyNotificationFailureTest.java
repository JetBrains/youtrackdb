package com.jetbrains.youtrackdb.internal.core.storage.cache.chm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class LockFreeReadCacheDirtyNotificationFailureTest {

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
