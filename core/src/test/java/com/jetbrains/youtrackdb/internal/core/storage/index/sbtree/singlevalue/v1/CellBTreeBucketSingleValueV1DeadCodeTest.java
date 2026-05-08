package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v1;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import org.junit.Assert;
import org.junit.Test;

/**
 * Dead-code shape pin for {@link CellBTreeBucketSingleValueV1}.
 *
 * <p>Phase A PSI {@code ReferencesSearch} confirmed <strong>zero production references</strong>
 * to this class across the entire module graph (0 main + 0 test refs). The v1 single-value
 * cell B-tree bucket has been superseded by the v3 implementation and is unreachable from
 * any production or test code.
 *
 * <p>These tests pin falsifiable behavioural observables — init flags leaf/non-leaf correctly,
 * switchBucketType works on an empty bucket, and the empty-bucket invariants hold — so that a
 * deletion commit in Track 22 either removes this file in lockstep or fails at compile time.
 *
 * <p>WHEN-FIXED: delete the entire {@code sbtree/singlevalue/v1} package
 * ({@code CellBTreeBucketSingleValueV1}, {@code CellBTreeSingleValueEntryPointV1}) together with
 * this dead-code test file in a single coordinated commit when the Track 22 deletion sweep runs.
 * No production callers exist, so the deletion needs only to remove this test file and the
 * {@code CellBTreeSingleValueEntryPointV1DeadCodeTest} file alongside the source.
 */
public class CellBTreeBucketSingleValueV1DeadCodeTest {

  /**
   * A leaf bucket initialises with isLeaf=true, isEmpty=true, size=0.
   */
  @Test
  public void init_asLeaf_setsLeafFlagAndEmptyState() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new CellBTreeBucketSingleValueV1<String>(entry);
      bucket.init(true);

      Assert.assertTrue("init(true) must produce a leaf bucket", bucket.isLeaf());
      Assert.assertTrue("newly initialised leaf bucket must be empty", bucket.isEmpty());
      Assert.assertEquals("newly initialised bucket must have size 0", 0, bucket.size());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * A non-leaf bucket initialises with isLeaf=false and isEmpty=true.
   */
  @Test
  public void init_asNonLeaf_setsNonLeafFlagAndEmptyState() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new CellBTreeBucketSingleValueV1<String>(entry);
      bucket.init(false);

      Assert.assertFalse("init(false) must produce a non-leaf bucket", bucket.isLeaf());
      Assert.assertTrue("newly initialised non-leaf bucket must be empty", bucket.isEmpty());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  /**
   * switchBucketType toggles isLeaf from true to false on an empty bucket, and back.
   * Two round-trips verify the toggle is idempotent and correct in both directions.
   *
   * <p>The non-empty guard path (throws {@link IllegalStateException}) requires adding
   * entries via the full serializer context (IT-scoped) and is not exercised here; the
   * shape pin covers the structural toggle invariant only.
   * WHEN-FIXED: Track 22 deletes this test together with the source.
   */
  @Test
  public void switchBucketType_onEmptyLeaf_togglesCorrectlyBothDirections() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();

    try {
      var bucket = new CellBTreeBucketSingleValueV1<String>(entry);
      bucket.init(true);
      Assert.assertTrue("bucket must start as leaf", bucket.isLeaf());

      bucket.switchBucketType(); // leaf → non-leaf
      Assert.assertFalse("after first switch, bucket must be non-leaf", bucket.isLeaf());

      bucket.switchBucketType(); // non-leaf → leaf
      Assert.assertTrue("after second switch, bucket must be leaf again", bucket.isLeaf());
      Assert.assertEquals("size must remain 0 after two switchBucketType calls", 0, bucket.size());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }
}
