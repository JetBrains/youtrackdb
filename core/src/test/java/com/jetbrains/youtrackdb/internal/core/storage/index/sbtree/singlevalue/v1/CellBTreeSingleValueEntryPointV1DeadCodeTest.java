package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v1;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Dead-code shape pin for {@link CellBTreeSingleValueEntryPointV1}.
 *
 * <p>PSI {@code ReferencesSearch} confirmed <strong>zero production references</strong> to this
 * class across the entire module graph (0 main + 0 test refs). The v1 entry-point page for
 * single-value cell B-tree has been superseded by the v3 implementation and is unreachable from
 * any production or test code.
 *
 * <p>These tests pin the falsifiable behavioural observables (init state, set/get treeSize,
 * set/get pagesSize) so that the eventual deletion commit either removes this file in lockstep
 * or fails at compile time.
 *
 * <p>WHEN-FIXED: delete this file in the same commit that deletes the v1 source classes
 * ({@code CellBTreeBucketSingleValueV1}, {@code CellBTreeSingleValueEntryPointV1}) along with
 * the sibling {@code CellBTreeBucketSingleValueV1DeadCodeTest}. No production callers exist;
 * the deletion needs only to remove the two test files alongside the source atomically.
 */
public class CellBTreeSingleValueEntryPointV1DeadCodeTest {

  /**
   * After init(), treeSize is 0 and pagesSize is 1 — the historical defaults.
   * Pinning these ensures a silent change to the init defaults cannot sneak in before
   * the dead-code deletion lands.
   */
  @Test
  public void init_setsTreeSizeZeroAndPagesSizeOne() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var ep = new CellBTreeSingleValueEntryPointV1(entry);
      ep.init();

      Assert.assertEquals("init() must set treeSize to 0", 0L, ep.getTreeSize());
      Assert.assertEquals("init() must set pagesSize to 1", 1, ep.getPagesSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * setTreeSize / getTreeSize round-trips the stored value.
   */
  @Test
  public void setTreeSize_getTreeSize_roundTrip() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var ep = new CellBTreeSingleValueEntryPointV1(entry);
      ep.init();

      ep.setTreeSize(12345L);
      Assert.assertEquals("getTreeSize must return the value set by setTreeSize",
          12345L, ep.getTreeSize());

      ep.setTreeSize(Long.MAX_VALUE);
      Assert.assertEquals("getTreeSize must survive Long.MAX_VALUE",
          Long.MAX_VALUE, ep.getTreeSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * setPagesSize / getPagesSize round-trips the stored value.
   */
  @Test
  public void setPagesSize_getPagesSize_roundTrip() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var ep = new CellBTreeSingleValueEntryPointV1(entry);
      ep.init();

      ep.setPagesSize(42);
      Assert.assertEquals("getPagesSize must return the value set by setPagesSize",
          42, ep.getPagesSize());

      ep.setPagesSize(Integer.MAX_VALUE);
      Assert.assertEquals("getPagesSize must survive Integer.MAX_VALUE",
          Integer.MAX_VALUE, ep.getPagesSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }
}
