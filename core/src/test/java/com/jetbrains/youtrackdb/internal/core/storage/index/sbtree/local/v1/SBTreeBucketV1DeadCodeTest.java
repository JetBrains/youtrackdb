package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v1;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Dead-code shape pin for {@link SBTreeBucketV1}.
 *
 * <p>PSI {@code ReferencesSearch} confirmed 0 main references and 23 test references
 * (all in the legacy {@code SBTreeLeafBucketV1Test} and {@code SBTreeNonLeafBucketV1Test}). The
 * v1 SBTree bucket is unreachable from any production code path.
 *
 * <p>These tests pin falsifiable behavioural observables — init flags, isEmpty, size, treeSize
 * set/get, and switchBucketType toggle — that are not all covered by the legacy test files, so
 * that the eventual deletion commit either removes this file in lockstep or fails at compile time.
 *
 * <p>WHEN-FIXED: YTDB-749 — delete this file in the same commit that deletes the v1 source classes
 * ({@code SBTreeBucketV1}, {@code SBTreeNullBucketV1}, {@code SBTreeValue}) along with the legacy
 * test files ({@code SBTreeLeafBucketV1Test}, {@code SBTreeNonLeafBucketV1Test},
 * {@code SBTreeNullBucketV1Test}).
 */
public class SBTreeBucketV1DeadCodeTest {

  /**
   * A leaf bucket initialises with isLeaf=true, isEmpty=true, size=0, treeSize=0.
   */
  @Test
  public void init_asLeaf_setsExpectedInitialState() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new SBTreeBucketV1<String, String>(entry);
      bucket.init(true);

      Assert.assertTrue("init(true) must set leaf flag", bucket.isLeaf());
      Assert.assertTrue("freshly initialised leaf bucket must be empty", bucket.isEmpty());
      Assert.assertEquals("size must be 0 on init", 0, bucket.size());
      Assert.assertEquals("treeSize must be 0 on init", 0L, bucket.getTreeSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * A non-leaf bucket initialises with isLeaf=false and isEmpty=true.
   */
  @Test
  public void init_asNonLeaf_setsNonLeafFlag() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new SBTreeBucketV1<String, String>(entry);
      bucket.init(false);

      Assert.assertFalse("init(false) must produce a non-leaf bucket", bucket.isLeaf());
      Assert.assertTrue("freshly initialised non-leaf bucket must be empty", bucket.isEmpty());
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
      var bucket = new SBTreeBucketV1<String, String>(entry);
      bucket.init(true);

      bucket.setTreeSize(54321L);
      Assert.assertEquals("getTreeSize must return value set by setTreeSize",
          54321L, bucket.getTreeSize());

      bucket.setTreeSize(0L);
      Assert.assertEquals("getTreeSize must return 0 after reset", 0L, bucket.getTreeSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * switchBucketType toggles isLeaf on an empty bucket in both directions.
   * WHEN-FIXED: this test is deleted together with the v1 source classes.
   */
  @Test
  public void switchBucketType_onEmptyBucket_togglesBothDirections() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new SBTreeBucketV1<String, String>(entry);
      bucket.init(true);
      Assert.assertTrue("bucket must start as leaf", bucket.isLeaf());

      bucket.switchBucketType(); // leaf → non-leaf
      Assert.assertFalse("after first switch, bucket must be non-leaf", bucket.isLeaf());

      bucket.switchBucketType(); // non-leaf → leaf
      Assert.assertTrue("after second switch, bucket must be leaf again", bucket.isLeaf());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }
}
