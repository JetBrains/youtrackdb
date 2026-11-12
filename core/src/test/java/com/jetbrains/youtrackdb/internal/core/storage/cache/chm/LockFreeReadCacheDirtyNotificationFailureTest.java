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

  // A held frame does not delay pinning. Reservation contention is cache-wide, not per caller.
  @Test
  public void pinDoesNotWaitForFrameAndBudgetIsShared() throws Exception {
    var pool = new ByteBufferPool(4096);
    var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
    var write = loadingCache(pool);
    var entry = read.loadForRead(1, 0, write, false);
    var worker = Executors.newSingleThreadExecutor();
    entry.acquireExclusiveLock();
    try (var pins = read.tryReserveWritePins(3)) {
      var pinned = worker.submit(() -> {
        org.junit.Assert.assertNull(read.tryReserveWritePins(1));
        return pins.pin(0, 1, 0, write, false);
      }).get(10, TimeUnit.SECONDS);
      assertSame(entry, pinned);
      verify(write, never()).updateDirtyPagesTable(any(), any());
      verify(write, never()).store(anyLong(), anyLong(), any());
    } finally {
      entry.releaseExclusiveLock();
      read.releaseFromRead(entry);
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
    }
    assertEquals(0, read.reservedWritePins());
    read.clear();
    pool.clear();
  }

  // Load, overflow and dirty-mark failures release every acquired pin, including a prior page.
  // Both RuntimeException and Error keep their identity and leave no exclusive lock held.
  @Test
  public void acquisitionFailuresBalanceReservationAndEarlierPages() throws Exception {
    for (int stage = 0; stage < 3; stage++) {
      for (Throwable failure : new Throwable[] {
          new IllegalStateException("acquisition"), new OutOfMemoryError("acquisition")}) {
        var pool = new ByteBufferPool(4096);
        var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
        var write = loadingCache(pool);
        var pins = read.tryReserveWritePins(3);
        var first = pins.pin(0, 1, 0, write, false);
        if (stage == 0) {
          doAnswer(call -> {
            throw failure;
          }).when(write)
              .loadOrAdd(anyLong(), org.mockito.ArgumentMatchers.eq(1L), anyBoolean());
        } else if (stage == 1) {
          doAnswer(call -> {
            throw failure;
          }).when(write).checkCacheOverflow();
        } else {
          pins.lock(0);
          doAnswer(call -> {
            throw failure;
          }).when(write).updateDirtyPagesTable(any(), any());
        }
        try {
          assertSame(failure, assertThrows(failure.getClass(), () -> {
            if (first.getCachePointer().getPageFrame().tryOptimisticRead() == 0) {
              pins.prepare(0, null, null);
            } else {
              pins.pin(1, 1, 1, write, false);
            }
          }));
        } finally {
          pins.close();
        }
        assertEquals(0, read.reservedWritePins());
        verify(write, never()).store(anyLong(), anyLong(), any());
        assertTrue(first.isNewlyAllocatedPage());
        read.clear();
        assertEquals(0, read.getUsedMemory());
        pool.clear();
      }
    }
  }

  // Repeated throws of the same object must not self-suppress or interrupt later unlocks.
  @Test
  public void repeatedCleanupFailureKeepsOriginalAndUnlocksEveryPage() throws Exception {
    for (boolean same : new boolean[] {false, true}) {
      var pool = new ByteBufferPool(4096);
      var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
      var write = loadingCache(pool);
      var pins = read.tryReserveWritePins(3);
      var first = pins.pin(0, 1, 0, write, false);
      var second = pins.pin(1, 1, 1, write, false);
      pins.lock(0);
      pins.lock(1);
      pins.prepare(0, null, null);
      pins.prepare(1, null, null);
      var failure = new IllegalStateException("first");
      var other = same ? failure : new IllegalStateException("other");
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      doAnswer(call -> {
        throw calls.getAndIncrement() == 0 ? failure : other;
      })
          .when(write).store(anyLong(), anyLong(), any());
      var cleanup = assertThrows(IllegalStateException.class, pins::close);
      assertSame(failure, cleanup.getCause());
      assertEquals(1, cleanup.getSuppressed().length);
      assertSame(other, cleanup.getSuppressed()[0]);
      assertEquals(2, calls.get());
      assertEquals(2, read.reservedWritePins());
      for (var entry : new com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry[] {
          first, second}) {
        long stamp = entry.getCachePointer().getPageFrame().tryAcquireExclusiveLock();
        assertTrue(stamp != 0);
        entry.getCachePointer().getPageFrame().releaseExclusiveLock(stamp);
        entry.releaseEntry();
      }
      pins.close();
      read.clear();
      pool.clear();
    }
  }

  // Reservation misuse cannot leak or return budget units twice.
  // Misuse cannot load a page in the frame-lock window or return a budget unit twice.
  @Test
  public void reservationStateIsChecked() throws Exception {
    var pool = new ByteBufferPool(4096);
    var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
    var write = loadingCache(pool);
    assertThrows(IllegalArgumentException.class, () -> read.tryReserveWritePins(0));
    org.junit.Assert.assertNull(read.tryReserveWritePins(4));
    var pins = read.tryReserveWritePins(1);
    var entry = pins.pin(0, 1, 0, write, false);
    assertThrows(IllegalStateException.class, () -> pins.pin(1, 1, 1, write, false));
    assertThrows(IllegalStateException.class, () -> pins.prepare(0, null, null));

    assertThrows(IllegalStateException.class, () -> pins.publishAndUnlock(0));
    pins.lock(0);
    assertThrows(IllegalStateException.class, () -> pins.lock(0));
    pins.prepare(0, null, null);
    assertThrows(IllegalStateException.class, () -> pins.prepare(0, null, null));
    doAnswer(call -> {
      assertEquals(0, entry.getCachePointer().getPageFrame().tryOptimisticRead());
      return null;
    }).when(write).store(anyLong(), anyLong(), any());
    pins.publishAndUnlock(0);
    assertTrue(!entry.isNewlyAllocatedPage());
    pins.close();
    pins.close();
    assertThrows(IllegalStateException.class, () -> pins.lock(0));
    assertEquals(0, read.reservedWritePins());
    read.clear();
    pool.clear();
  }

  // Reject duplicates, descending coordinates and skipped slots before acquiring another pin.
  // Invalid operations leave the earlier pin intact and close returns all reserved units.
  @Test
  public void orderedRegistrationRejectsDuplicateAndDescendingPagesWithoutAcquisition()
      throws Exception {
    var pool = new ByteBufferPool(4096);
    var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
    var write = loadingCache(pool);
    try (var pins = read.tryReserveWritePins(3)) {
      var first = pins.pin(0, 1, 2, write, false);
      assertThrows(IllegalArgumentException.class, () -> pins.pin(1, 1, 2, write, false));
      assertThrows(IllegalArgumentException.class, () -> pins.pin(1, 1, 1, write, false));
      assertThrows(IllegalStateException.class, () -> pins.pin(2, 1, 3, write, false));
      assertThrows(IllegalStateException.class, () -> pins.lock(-1));
      assertThrows(IllegalStateException.class, () -> pins.lock(1));
      var second = pins.pin(1, 1, 3, write, false);
      verify(write, org.mockito.Mockito.times(2)).loadOrAdd(anyLong(), anyLong(), anyBoolean());
      assertEquals(3, read.reservedWritePins());
      pins.lock(0);
      pins.lock(1);
      pins.prepare(0, null, null);
      pins.prepare(1, null, null);
      pins.publishAndUnlock(0);
      pins.publishAndUnlock(1);
      assertTrue(first.getCachePointer().getPageFrame().tryOptimisticRead() != 0);
      assertTrue(second.getCachePointer().getPageFrame().tryOptimisticRead() != 0);
    }
    assertEquals(0, read.reservedWritePins());
    read.clear();
    pool.clear();
  }

  // Both hit paths must undo acquisition if the read notification fails before pin registration.
  @Test
  @SuppressWarnings("unchecked")
  public void hitNotificationFailuresReleaseUnregisteredPins() throws Exception {
    for (boolean concurrentHit : new boolean[] {false, true}) {
      var pool = new ByteBufferPool(4096);
      var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
      var write = loadingCache(pool);
      var entry = read.loadForRead(1, 0, write, false);
      read.releaseFromRead(entry);
      var batchField = LockFreeReadCache.class.getDeclaredField("readBatch");
      batchField.setAccessible(true);
      var originalBatch = batchField.get(read);
      var failure = new OutOfMemoryError("read batch allocation");
      if (concurrentHit) {
        var dataField = LockFreeReadCache.class.getDeclaredField("data");
        dataField.setAccessible(true);
        var data = org.mockito.Mockito.spy(
            (com.jetbrains.youtrackdb.internal.common.collection.ConcurrentLongIntHashMap<
                com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry>) dataField
                    .get(read));
        org.mockito.Mockito.doReturn(null).when(data).get(1, 0);
        dataField.set(read, data);
      }
      batchField.set(read, new ThreadLocal<Object>() {
        @Override
        public Object get() {
          throw failure;
        }
      });
      try (var pins = read.tryReserveWritePins(1)) {
        assertSame(failure, assertThrows(OutOfMemoryError.class,
            () -> pins.pin(0, 1, 0, write, false)));
      } finally {
        batchField.set(read, originalBatch);
      }
      assertEquals(0, read.reservedWritePins());
      // clear fails if either acquisition leaked a usage count.
      read.clear();
      assertEquals(0, read.getUsedMemory());
      pool.clear();
    }
  }

  // Automatic cleanup must keep a reused publish failure primary for exceptions and errors.
  @Test
  public void automaticCleanupDoesNotSelfSuppressBodyFailure() throws Exception {
    for (Throwable failure : new Throwable[] {
        new IllegalStateException("shared"), new OutOfMemoryError("shared")}) {
      var pool = new ByteBufferPool(4096);
      var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
      var write = loadingCache(pool);
      var pins = read.tryReserveWritePins(2);
      var first = pins.pin(0, 1, 0, write, false);
      var second = pins.pin(1, 1, 1, write, false);
      doAnswer(call -> {
        throw failure;
      }).when(write).store(anyLong(), anyLong(), any());
      assertSame(failure, assertThrows(failure.getClass(), () -> {
        try (pins) {
          pins.lock(0);
          pins.lock(1);
          pins.prepare(0, null, null);
          pins.prepare(1, null, null);

          pins.publishAndUnlock(0);
        }
      }));
      assertEquals(2, read.reservedWritePins());
      for (var entry : new com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry[] {
          first, second}) {
        long stamp = entry.getCachePointer().getPageFrame().tryAcquireExclusiveLock();
        assertTrue(stamp != 0);
        entry.getCachePointer().getPageFrame().releaseExclusiveLock(stamp);
        entry.releaseEntry();
      }
      read.clear();
      pool.clear();
    }
  }

  // A direct body throw can reuse the cleanup failure without any publishAndUnlock call.
  // The body stays primary, and both cleanup failures remain visible on a fresh suppressed object.
  @Test
  public void directBodyFailureDoesNotSelfSuppressDuringCleanup() throws Exception {
    checkReusedCleanupFailure(false);
  }

  // Catching an earlier publish failure does not excuse a later cleanup publication failure.
  // A normal body exit must still throw a fresh RuntimeException or Error from close.
  @Test
  public void handledPublishFailureDoesNotHideCleanupFailure() throws Exception {
    checkReusedCleanupFailure(true);
  }

  private static void checkReusedCleanupFailure(boolean handledPublish) throws Exception {
    for (Throwable failure : new Throwable[] {
        new IllegalStateException("shared"), new OutOfMemoryError("shared")}) {
      var pool = new ByteBufferPool(4096);
      var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
      var write = loadingCache(pool);
      var pins = read.tryReserveWritePins(3);
      var first = pins.pin(0, 1, 0, write, false);
      var second = pins.pin(1, 1, 1, write, false);
      var unprepared = pins.pin(2, 1, 2, write, false);
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      doAnswer(call -> {
        calls.incrementAndGet();
        throw failure;
      }).when(write).store(anyLong(), anyLong(), any());
      Class<? extends Throwable> expected =
          failure instanceof Error ? Error.class : RuntimeException.class;
      var thrown = assertThrows(expected,
          () -> {
            try (pins) {
              pins.lock(0);
              pins.lock(1);
              pins.lock(2);
              pins.prepare(0, null, null);
              pins.prepare(1, null, null);
              if (handledPublish) {
                assertSame(failure, assertThrows(failure.getClass(),
                    () -> pins.publishAndUnlock(0)));
              } else if (failure instanceof Error error) {
                throw error;
              } else {
                throw (RuntimeException) failure;
              }
            }
          });
      if (!handledPublish) {
        assertSame(failure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
      }
      var cleanup = handledPublish ? thrown : thrown.getSuppressed()[0];
      org.junit.Assert.assertNotSame(failure, cleanup);
      assertEquals(failure instanceof Error, cleanup instanceof Error);
      assertSame(failure, cleanup.getCause());
      assertEquals(handledPublish ? 0 : 1, cleanup.getSuppressed().length);
      if (!handledPublish) {
        assertSame(failure, cleanup.getSuppressed()[0]);
      }
      assertEquals(2, calls.get());
      assertEquals(2, read.reservedWritePins());
      assertTrue(unprepared.isReleased());
      for (var entry : new com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry[] {
          first, second}) {
        long stamp = entry.getCachePointer().getPageFrame().tryAcquireExclusiveLock();
        assertTrue(stamp != 0);
        entry.getCachePointer().getPageFrame().releaseExclusiveLock(stamp);
        org.junit.Assert.assertFalse(entry.isReleased());
        entry.releaseEntry();
      }
      pins.close();
      assertEquals(2, read.reservedWritePins());
      read.clear();
      pool.clear();
    }
  }

  // A later Error promotes the fresh aggregate without losing the earlier cause or failures.
  @Test
  public void cleanupErrorAfterRuntimeFailureKeepsEveryFailure() throws Exception {
    var pool = new ByteBufferPool(4096);
    var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
    var write = loadingCache(pool);
    var pins = read.tryReserveWritePins(3);
    var entries = new com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry[3];
    for (int i = 0; i < entries.length; i++) {
      entries[i] = pins.pin(i, 1, i, write, false);
    }
    for (int slot = 0; slot < entries.length; slot++) {
      pins.lock(slot);
      pins.prepare(slot, null, null);
    }
    var first = new IllegalStateException("first");
    var second = new IllegalStateException("second");
    var third = new OutOfMemoryError("third");
    Throwable[] failures = {first, second, third};
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> {
      throw failures[calls.getAndIncrement()];
    }).when(write).store(anyLong(), anyLong(), any());
    var cleanup = assertThrows(Error.class, () -> {
      try (pins) {
        // Closing the resource performs all three publications.
      }
    });
    assertSame(first, cleanup.getCause());
    org.junit.Assert.assertArrayEquals(new Throwable[] {second, third}, cleanup.getSuppressed());
    assertEquals(3, read.reservedWritePins());
    for (var entry : entries) {
      assertTrue(entry.getCachePointer().getPageFrame().tryOptimisticRead() != 0);
      entry.releaseEntry();
    }
    pins.close();
    read.clear();
    pool.clear();
  }

  private static WriteCache loadingCache(ByteBufferPool pool) throws Exception {
    var write = mock(WriteCache.class);
    when(write.loadOrAdd(anyLong(), anyLong(), anyBoolean())).thenAnswer(call -> {
      int page = ((Long) call.getArgument(1)).intValue();
      var frame = pool.pageFramePool().acquire(true, Intention.TEST);
      var pointer = new CachePointer(frame, pool.pageFramePool(), 1, page);
      pointer.incrementReadersReferrer();
      return pointer;
    });
    return write;
  }

  // Reservation denial is normal. Every acquired resource balances on clean and failing exits.
  // A publish failure keeps exactly one pin and one budget unit, but never a frame lock.
  @Test
  public void reservationCleanupCoversMarkApplyAndPublishFailures() throws Exception {
    for (int stage = 0; stage < 4; stage++) {
      var pool = new ByteBufferPool(4096);
      var read = new LockFreeReadCache(pool, 128L * 4096, 4096, 3);
      var write = mock(WriteCache.class);
      when(write.loadOrAdd(anyLong(), anyLong(), anyBoolean())).thenAnswer(call -> {
        int page = ((Long) call.getArgument(1)).intValue();
        var frame = pool.pageFramePool().acquire(true, Intention.TEST);
        var pointer = new CachePointer(frame, pool.pageFramePool(), 1, page);
        pointer.incrementReadersReferrer();
        return pointer;
      });
      var reservation = read.tryReserveWritePins(3);
      org.junit.Assert.assertNotNull(reservation);
      org.junit.Assert.assertNull(read.tryReserveWritePins(1));
      var first = reservation.pin(0, 1, 0, write, false);
      var second = reservation.pin(1, 1, 1, write, false);
      var firstFrame = first.getCachePointer().getPageFrame();
      var secondFrame = second.getCachePointer().getPageFrame();
      assertTrue(firstFrame.tryOptimisticRead() != 0);
      verify(write, never()).updateDirtyPagesTable(any(), any());
      verify(write, never()).store(anyLong(), anyLong(), any());
      reservation.lock(0);
      reservation.lock(1);
      var failure = new IllegalStateException("injected");
      if (stage == 1) {
        doAnswer(call -> {
          throw failure;
        }).when(write).updateDirtyPagesTable(any(), any());
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> reservation.prepare(0, null, null)));
      } else {
        reservation.prepare(0, null, null);
        reservation.prepare(1, null, null);
        if (stage >= 2) {

          first.getCachePointer().getBuffer().putInt(100, 42);
          if (stage == 3) {
            doAnswer(call -> {
              assertEquals(0, firstFrame.tryOptimisticRead());
              throw failure;
            }).when(write).store(anyLong(), org.mockito.ArgumentMatchers.eq(0L), any());
          }
        }
      }
      if (stage == 3) {
        assertSame(failure,
            assertThrows(IllegalStateException.class, reservation::close).getCause());
        assertEquals(1, read.reservedWritePins());
      } else {
        reservation.close();
        assertEquals(0, read.reservedWritePins());
      }
      reservation.close();
      long firstStamp = firstFrame.tryAcquireExclusiveLock();
      long secondStamp = secondFrame.tryAcquireExclusiveLock();
      assertTrue(firstStamp != 0);
      assertTrue(secondStamp != 0);
      firstFrame.releaseExclusiveLock(firstStamp);
      secondFrame.releaseExclusiveLock(secondStamp);
      if (stage == 3) {
        // Test-only disposal of the deliberately retained failed page.
        first.releaseEntry();
      }
      read.clear();
      pool.clear();
    }
  }

  // The legacy write release must unlock even when store fails. Its changed page stays pinned.
  @Test
  public void failedLegacyPublishUnlocksAndKeepsChangedPage() throws Exception {
    var pool = new ByteBufferPool(4096);
    var read = new LockFreeReadCache(pool, 128L * 4096, 4096);
    var write = mock(WriteCache.class);
    when(write.loadOrAdd(anyLong(), anyLong(), anyBoolean())).thenAnswer(call -> {
      var frame = pool.pageFramePool().acquire(true, Intention.TEST);
      var pointer = new CachePointer(frame, pool.pageFramePool(), 1, 0);
      pointer.incrementReadersReferrer();
      return pointer;
    });
    var entry = read.loadOrAddForWrite(1, 0, write, false, null);
    var frame = entry.getCachePointer().getPageFrame();
    var failure = new IllegalStateException("store");
    doAnswer(call -> {
      assertEquals(0, frame.tryOptimisticRead());
      throw failure;
    }).when(write).store(anyLong(), anyLong(), any());
    assertSame(failure, assertThrows(IllegalStateException.class,
        () -> read.releaseFromWrite(entry, write, true)));
    long stamp = frame.tryAcquireExclusiveLock();
    assertTrue(stamp != 0);
    frame.releaseExclusiveLock(stamp);
    org.junit.Assert.assertFalse(entry.isReleased());
    entry.releaseEntry();
    read.clear();
    pool.clear();
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
