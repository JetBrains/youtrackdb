package com.jetbrains.youtrack.db.internal.core.storage.collection.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrack.db.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrack.db.internal.common.directmemory.Pointer;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrack.db.internal.core.storage.cache.CachePointer;
import java.util.HashMap;
import java.util.Random;
import java.util.TreeMap;
import org.junit.Test;

public class FreeSpaceMapPageTest {

  @Test
  public void findSinglePageSameSpaceEvenIndex() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(42, 3);
      assertEquals(42, page.findPage(3));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void findSinglePageSameSpaceOddIndex() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(43, 3);
      assertEquals(43, page.findPage(3));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void findSinglePageLessSpaceEvenIndex() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(42, 3);
      assertEquals(42, page.findPage(2));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void findSinglePageLessSpaceOddIndex() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(43, 3);
      assertEquals(43, page.findPage(2));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void findCouplePagesSameSpaceOne() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(42, 1);
      page.updatePageMaxFreeSpace(43, 3);
      assertEquals(43, page.findPage(3));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void findCouplePagesSameSpaceTwo() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(42, 1);
      page.updatePageMaxFreeSpace(43, 3);
      assertEquals(42, page.findPage(1));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void findCouplePagesSmallerSpaceOne() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(42, 1);
      page.updatePageMaxFreeSpace(43, 3);
      assertEquals(43, page.findPage(2));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void findCouplePagesSmallerSpaceTwo() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      page.updatePageMaxFreeSpace(42, 3);
      page.updatePageMaxFreeSpace(43, 5);
      assertEquals(42, page.findPage(2));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void bigSpaceOne() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);

      page.updatePageMaxFreeSpace(42, 128);
      page.updatePageMaxFreeSpace(43, 130);
      page.updatePageMaxFreeSpace(44, 132);

      assertEquals(43, page.findPage(129));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void bigSpaceTwo() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);

      page.updatePageMaxFreeSpace(42, 128);
      page.updatePageMaxFreeSpace(43, 130);
      page.updatePageMaxFreeSpace(44, 132);

      assertEquals(44, page.findPage(131));
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void randomPages() {
    final var pages = 1_000;
    final var checks = 1_000;

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    final var seed = System.nanoTime();
    System.out.println("Random pages seed : " + seed);
    final var random = new Random(seed);
    final var spacePageMap = new HashMap<Integer, Integer>();
    var maxFreeSpace = -1;

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      for (var i = 0; i < pages; i++) {
        final var freeSpace = random.nextInt(256);
        spacePageMap.put(i, freeSpace);

        if (freeSpace > maxFreeSpace) {
          maxFreeSpace = freeSpace;
        }

        assertEquals(maxFreeSpace, page.updatePageMaxFreeSpace(i, freeSpace));
      }

      for (var i = 0; i < checks; i++) {
        final var freeSpace = random.nextInt(256);
        final var pageIndex = page.findPage(freeSpace);

        if (freeSpace <= maxFreeSpace) {
          assertTrue(spacePageMap.get(pageIndex) >= freeSpace);
        } else {
          assertEquals(-1, pageIndex);
        }
      }
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void randomPagesUpdate() {
    final var sizeCountMap = new TreeMap<Integer, Integer>();
    final var spacePageMap = new HashMap<Integer, Integer>();

    final var pages = 1_000;
    final var checks = 1_000;

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);

    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();

    final var seed = System.nanoTime();
    System.out.println("Random pages update seed : " + seed);

    final var random = new Random(seed);

    CacheEntry cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    try {
      final var page = new FreeSpaceMapPage(cacheEntry);
      for (var i = 0; i < pages; i++) {
        final var freeSpace = random.nextInt(256);
        spacePageMap.put(i, freeSpace);
        sizeCountMap.compute(
            freeSpace,
            (k, v) -> {
              if (v == null) {
                return 1;
              }

              return v + 1;
            });

        final int maxFreeSpace = sizeCountMap.lastKey();
        assertEquals(maxFreeSpace, page.updatePageMaxFreeSpace(i, freeSpace));
      }

      for (var i = 0; i < pages; i++) {
        final var freeSpace = random.nextInt(256);
        final int prevSpace = spacePageMap.get(i);
        spacePageMap.put(i, freeSpace);

        sizeCountMap.compute(
            prevSpace,
            (k, v) -> {
              //noinspection ConstantConditions
              if (v == 1) {
                return null;
              }

              return v - 1;
            });

        sizeCountMap.compute(
            freeSpace,
            (k, v) -> {
              if (v == null) {
                return 1;
              }

              return v + 1;
            });

        final int maxFreeSpace = sizeCountMap.lastKey();
        assertEquals(maxFreeSpace, page.updatePageMaxFreeSpace(i, freeSpace));
      }

      final int maxFreeSpace = sizeCountMap.lastKey();
      for (var i = 0; i < checks; i++) {
        final var freeSpace = random.nextInt(256);
        final var pageIndex = page.findPage(freeSpace);

        if (freeSpace <= maxFreeSpace) {
          assertTrue(spacePageMap.get(pageIndex) >= freeSpace);
        } else {
          assertEquals(-1, pageIndex);
        }
      }
    } finally {
      cacheEntry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }
}
