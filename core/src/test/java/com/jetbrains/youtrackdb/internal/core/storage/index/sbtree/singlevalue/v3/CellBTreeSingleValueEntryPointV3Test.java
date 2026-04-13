package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link CellBTreeSingleValueEntryPointV3} page layout,
 * verifying that the APPROXIMATE_ENTRIES_COUNT field is correctly positioned
 * after FREE_LIST_HEAD and that PAGES_SIZE / FREE_LIST_HEAD offsets are unchanged.
 */
public class CellBTreeSingleValueEntryPointV3Test {

  private static final int PAGE_SIZE =
      GlobalConfiguration.DISK_CACHE_PAGE_SIZE.getValueAsInteger() << 10;

  private ByteBufferPool bufferPool;

  @Before
  public void setUp() {
    bufferPool = new ByteBufferPool(PAGE_SIZE);
  }

  @After
  public void tearDown() {
    bufferPool.clear();
  }

  private CacheEntry allocatePage() {
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();
    return entry;
  }

  private void releasePage(CacheEntry entry) {
    entry.releaseExclusiveLock();
    entry.getCachePointer().decrementReferrer();
  }

  // Verifies init() sets all fields to their expected default values:
  // treeSize=0, approximateEntriesCount=0, pagesSize=1, freeListHead=-1.
  @Test
  public void initSetsAllFieldsToDefaults() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      assertEquals("treeSize should be 0 after init", 0, ep.getTreeSize());
      assertEquals(
          "approximateEntriesCount should be 0 after init",
          0, ep.getApproximateEntriesCount());
      assertEquals("pagesSize should be 1 after init", 1, ep.getPagesSize());
      assertEquals(
          "freeListHead should be -1 after init", -1, ep.getFreeListHead());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that setApproximateEntriesCount/getApproximateEntriesCount
  // round-trips correctly and does not corrupt adjacent fields.
  @Test
  public void approximateEntriesCountRoundTrip() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      ep.setApproximateEntriesCount(42_000L);
      assertEquals(42_000L, ep.getApproximateEntriesCount());

      // Adjacent fields must not be corrupted.
      assertEquals("treeSize unchanged", 0, ep.getTreeSize());
      assertEquals("pagesSize unchanged", 1, ep.getPagesSize());
      assertEquals("freeListHead unchanged", -1, ep.getFreeListHead());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that large values (including Long.MAX_VALUE) survive a
  // round-trip through the 8-byte field.
  @Test
  public void approximateEntriesCountHandlesLargeValues() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      ep.setApproximateEntriesCount(Long.MAX_VALUE);
      assertEquals(Long.MAX_VALUE, ep.getApproximateEntriesCount());

      // Adjacent fields must not be corrupted by the all-bits-set value.
      assertEquals("treeSize unchanged", 0, ep.getTreeSize());
      assertEquals("pagesSize unchanged", 1, ep.getPagesSize());
      assertEquals("freeListHead unchanged", -1, ep.getFreeListHead());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that writing to all fields does not produce cross-field
  // corruption — each field is independently addressable after the
  // APPROXIMATE_ENTRIES_COUNT insertion shifted PAGES_SIZE and FREE_LIST_HEAD.
  @Test
  public void allFieldsIndependent() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      // Interleaved write-read to detect cross-field overwrites in order.
      ep.setTreeSize(100);
      assertEquals("treeSize after set", 100, ep.getTreeSize());

      ep.setApproximateEntriesCount(200);
      assertEquals("approximateEntriesCount after set", 200,
          ep.getApproximateEntriesCount());
      assertEquals("treeSize not overwritten", 100, ep.getTreeSize());

      ep.setPagesSize(300);
      assertEquals("pagesSize after set", 300, ep.getPagesSize());
      assertEquals("approximateEntriesCount not overwritten", 200,
          ep.getApproximateEntriesCount());

      ep.setFreeListHead(400);
      assertEquals("freeListHead after set", 400, ep.getFreeListHead());
      assertEquals("pagesSize not overwritten", 300, ep.getPagesSize());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that PAGES_SIZE at its new shifted offset (49) works correctly
  // with both positive values and the init default (1).
  @Test
  public void pagesSizeAtShiftedOffset() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      assertEquals("default pagesSize", 1, ep.getPagesSize());
      ep.setPagesSize(12345);
      assertEquals("updated pagesSize", 12345, ep.getPagesSize());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that FREE_LIST_HEAD at its new shifted offset (53) works
  // correctly, including -1 (empty list sentinel).
  @Test
  public void freeListHeadAtShiftedOffset() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      assertEquals("default freeListHead", -1, ep.getFreeListHead());
      ep.setFreeListHead(42);
      assertEquals("updated freeListHead", 42, ep.getFreeListHead());
      ep.setFreeListHead(-1);
      assertEquals("reset freeListHead", -1, ep.getFreeListHead());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that getFreeListHead() maps stored 0 to -1 for backward
  // compatibility — in previous versions free list head was absent, so 0
  // (zero-initialized bytes) is treated as "no free list".
  @Test
  public void freeListHeadMapsZeroToMinusOne() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      ep.setFreeListHead(0);
      assertEquals("freeListHead should map 0 to -1 for backward compat",
          -1, ep.getFreeListHead());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that treeSize and approximateEntriesCount, which are adjacent
  // 8-byte fields, do not overlap when both hold large values with
  // high-order bits set.
  @Test
  public void adjacentLongFieldsDoNotOverlapAtFullWidth() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      ep.setTreeSize(0xDEAD_BEEF_CAFE_BABEL);
      ep.setApproximateEntriesCount(0x1234_5678_9ABC_DEF0L);

      assertEquals(0xDEAD_BEEF_CAFE_BABEL, ep.getTreeSize());
      assertEquals(0x1234_5678_9ABC_DEF0L,
          ep.getApproximateEntriesCount());
      assertEquals("pagesSize unchanged", 1, ep.getPagesSize());
    } finally {
      releasePage(page);
    }
  }

  // Verifies that calling init() on a page that already holds non-default
  // values resets all four managed fields to their defaults, acting as a
  // full re-initialization.
  @Test
  public void initResetsNonDefaultValues() {
    CacheEntry page = allocatePage();
    try {
      var ep = new CellBTreeSingleValueEntryPointV3<>(page);
      ep.init();

      // Set all fields to non-default values.
      ep.setTreeSize(999);
      ep.setApproximateEntriesCount(888);
      ep.setPagesSize(777);
      ep.setFreeListHead(666);

      // Re-initialize — all fields must return to defaults.
      ep.init();

      assertEquals("treeSize reset", 0, ep.getTreeSize());
      assertEquals("approximateEntriesCount reset",
          0, ep.getApproximateEntriesCount());
      assertEquals("pagesSize reset", 1, ep.getPagesSize());
      assertEquals("freeListHead reset", -1, ep.getFreeListHead());
    } finally {
      releasePage(page);
    }
  }
}
