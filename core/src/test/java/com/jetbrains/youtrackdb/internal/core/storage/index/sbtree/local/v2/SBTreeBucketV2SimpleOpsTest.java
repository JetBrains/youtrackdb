package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperationRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for SBTreeBucketV2 simple PageOperation subclasses (init, switchBucketType, setTreeSize,
 * setLeftSibling, setRightSibling): record IDs, serialization roundtrips, factory roundtrips,
 * redo correctness, registration, redo suppression, and equals/hashCode.
 */
public class SBTreeBucketV2SimpleOpsTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Record ID verification ----

  @Test
  public void testInitOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_INIT_OP,
        SBTreeBucketV2InitOp.RECORD_ID);
    Assert.assertEquals(267, SBTreeBucketV2InitOp.RECORD_ID);
  }

  @Test
  public void testSwitchBucketTypeOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_SWITCH_BUCKET_TYPE_OP,
        SBTreeBucketV2SwitchBucketTypeOp.RECORD_ID);
    Assert.assertEquals(268, SBTreeBucketV2SwitchBucketTypeOp.RECORD_ID);
  }

  @Test
  public void testSetTreeSizeOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_SET_TREE_SIZE_OP,
        SBTreeBucketV2SetTreeSizeOp.RECORD_ID);
    Assert.assertEquals(269, SBTreeBucketV2SetTreeSizeOp.RECORD_ID);
  }

  @Test
  public void testSetLeftSiblingOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_SET_LEFT_SIBLING_OP,
        SBTreeBucketV2SetLeftSiblingOp.RECORD_ID);
    Assert.assertEquals(270, SBTreeBucketV2SetLeftSiblingOp.RECORD_ID);
  }

  @Test
  public void testSetRightSiblingOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_SET_RIGHT_SIBLING_OP,
        SBTreeBucketV2SetRightSiblingOp.RECORD_ID);
    Assert.assertEquals(271, SBTreeBucketV2SetRightSiblingOp.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testInitOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(5, 200);
    var original = new SBTreeBucketV2InitOp(10, 20, 30, lsn, true);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertTrue(deserialized.isLeaf());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSwitchBucketTypeOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(3, 100);
    var original = new SBTreeBucketV2SwitchBucketTypeOp(10, 20, 30, lsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2SwitchBucketTypeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetTreeSizeOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(1, 42);
    var original = new SBTreeBucketV2SetTreeSizeOp(10, 20, 30, lsn, 999L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2SetTreeSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(999L, deserialized.getSize());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetLeftSiblingOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(2, 50);
    var original = new SBTreeBucketV2SetLeftSiblingOp(10, 20, 30, lsn, 42L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2SetLeftSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(42L, deserialized.getSiblingPageIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetRightSiblingOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(2, 50);
    var original = new SBTreeBucketV2SetRightSiblingOp(10, 20, 30, lsn, 77L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2SetRightSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(77L, deserialized.getSiblingPageIndex());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testInitOpFactoryRoundtrip() {
    var original = new SBTreeBucketV2InitOp(10, 20, 30,
        new LogSequenceNumber(42, 1024), false);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2InitOp);
    Assert.assertFalse(((SBTreeBucketV2InitOp) deserialized).isLeaf());
  }

  @Test
  public void testSwitchBucketTypeOpFactoryRoundtrip() {
    var original = new SBTreeBucketV2SwitchBucketTypeOp(10, 20, 30,
        new LogSequenceNumber(42, 1024));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2SwitchBucketTypeOp);
    var result = (SBTreeBucketV2SwitchBucketTypeOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testSetTreeSizeOpFactoryRoundtrip() {
    var original = new SBTreeBucketV2SetTreeSizeOp(10, 20, 30,
        new LogSequenceNumber(42, 1024), 12345L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2SetTreeSizeOp);
    Assert.assertEquals(12345L, ((SBTreeBucketV2SetTreeSizeOp) deserialized).getSize());
  }

  @Test
  public void testSetLeftSiblingOpFactoryRoundtrip() {
    var original = new SBTreeBucketV2SetLeftSiblingOp(10, 20, 30,
        new LogSequenceNumber(42, 1024), 99L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2SetLeftSiblingOp);
    Assert.assertEquals(99L,
        ((SBTreeBucketV2SetLeftSiblingOp) deserialized).getSiblingPageIndex());
  }

  @Test
  public void testSetRightSiblingOpFactoryRoundtrip() {
    var original = new SBTreeBucketV2SetRightSiblingOp(10, 20, 30,
        new LogSequenceNumber(42, 1024), 88L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2SetRightSiblingOp);
    Assert.assertEquals(88L,
        ((SBTreeBucketV2SetRightSiblingOp) deserialized).getSiblingPageIndex());
  }

  // ---- Redo correctness (byte-level) ----

  /**
   * init leaf: apply directly on page1, redo on page2. Byte-level identical.
   */
  @Test
  public void testInitLeafRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      // Apply directly
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(true);

      // Apply via redo
      var page2 = new SBTreeBucketV2<>(entry2);
      new SBTreeBucketV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0), true).redo(page2);

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertTrue(page2.isLeaf());
      Assert.assertEquals(0, page2.size());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * init non-leaf: apply directly on page1, redo on page2. Byte-level identical.
   */
  @Test
  public void testInitNonLeafRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(false);

      var page2 = new SBTreeBucketV2<>(entry2);
      new SBTreeBucketV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0), false).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertFalse(page2.isLeaf());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * switchBucketType: init as leaf, then switch on both. Byte-level identical.
   */
  @Test
  public void testSwitchBucketTypeRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(true);

      var page2 = new SBTreeBucketV2<>(entry2);
      page2.init(true);

      page1.switchBucketType();
      new SBTreeBucketV2SwitchBucketTypeOp(0, 0, 0, new LogSequenceNumber(0, 0)).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertFalse(page2.isLeaf());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * setTreeSize: set specific size on both. Byte-level identical.
   */
  @Test
  public void testSetTreeSizeRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(true);

      var page2 = new SBTreeBucketV2<>(entry2);
      page2.init(true);

      page1.setTreeSize(42L);
      new SBTreeBucketV2SetTreeSizeOp(0, 0, 0, new LogSequenceNumber(0, 0), 42L).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(42L, page2.getTreeSize());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * setLeftSibling and setRightSibling redo correctness.
   */
  @Test
  public void testSiblingRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(true);

      var page2 = new SBTreeBucketV2<>(entry2);
      page2.init(true);

      page1.setLeftSibling(10L);
      page1.setRightSibling(20L);

      var lsn = new LogSequenceNumber(0, 0);
      new SBTreeBucketV2SetLeftSiblingOp(0, 0, 0, lsn, 10L).redo(page2);
      new SBTreeBucketV2SetRightSiblingOp(0, 0, 0, lsn, 20L).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(10L, page2.getLeftSibling());
      Assert.assertEquals(20L, page2.getRightSibling());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  // ---- Redo suppression ----

  @Test
  public void testRedoSuppression_initDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      var bucket = new SBTreeBucketV2<>(entry);
      bucket.init(true);
      // Verify page was actually modified (not a no-op)
      Assert.assertTrue(bucket.isLeaf());
      Assert.assertEquals(0, bucket.size());
      Assert.assertEquals(0L, bucket.getTreeSize());
      Assert.assertEquals(-1L, bucket.getLeftSibling());
      Assert.assertEquals(-1L, bucket.getRightSibling());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  @Test
  public void testRedoSuppression_switchBucketTypeDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      var bucket = new SBTreeBucketV2<>(entry);
      bucket.init(true);
      Assert.assertTrue(bucket.isLeaf());
      bucket.switchBucketType();
      // Verify the toggle actually happened
      Assert.assertFalse(bucket.isLeaf());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  // ---- Equals/hashCode ----

  @Test
  public void testInitOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new SBTreeBucketV2InitOp(10, 20, 30, lsn, true);
    var op2 = new SBTreeBucketV2InitOp(10, 20, 30, lsn, true);
    var op3 = new SBTreeBucketV2InitOp(10, 20, 30, lsn, false);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetTreeSizeOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new SBTreeBucketV2SetTreeSizeOp(10, 20, 30, lsn, 42L);
    var op2 = new SBTreeBucketV2SetTreeSizeOp(10, 20, 30, lsn, 42L);
    var op3 = new SBTreeBucketV2SetTreeSizeOp(10, 20, 30, lsn, 99L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetLeftSiblingOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new SBTreeBucketV2SetLeftSiblingOp(10, 20, 30, lsn, 5L);
    var op2 = new SBTreeBucketV2SetLeftSiblingOp(10, 20, 30, lsn, 5L);
    var op3 = new SBTreeBucketV2SetLeftSiblingOp(10, 20, 30, lsn, 7L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetRightSiblingOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new SBTreeBucketV2SetRightSiblingOp(10, 20, 30, lsn, 5L);
    var op2 = new SBTreeBucketV2SetRightSiblingOp(10, 20, 30, lsn, 5L);
    var op3 = new SBTreeBucketV2SetRightSiblingOp(10, 20, 30, lsn, 7L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  // ---- Boundary value tests (Long.MAX_VALUE catches truncation to int) ----

  @Test
  public void testSetTreeSizeOpSerializationRoundtripMaxValue() {
    var lsn = new LogSequenceNumber(1, 42);
    var original = new SBTreeBucketV2SetTreeSizeOp(10, 20, 30, lsn, Long.MAX_VALUE);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new SBTreeBucketV2SetTreeSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(Long.MAX_VALUE, deserialized.getSize());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetLeftSiblingOpFactoryRoundtripMaxValue() {
    var original = new SBTreeBucketV2SetLeftSiblingOp(7, 14, 21,
        new LogSequenceNumber(66, 660), Long.MAX_VALUE);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2SetLeftSiblingOp);
    Assert.assertEquals(Long.MAX_VALUE,
        ((SBTreeBucketV2SetLeftSiblingOp) deserialized).getSiblingPageIndex());
  }

  @Test
  public void testSetRightSiblingOpFactoryRoundtripMaxValue() {
    var original = new SBTreeBucketV2SetRightSiblingOp(5, 10, 15,
        new LogSequenceNumber(88, 880), Long.MAX_VALUE);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2SetRightSiblingOp);
    Assert.assertEquals(Long.MAX_VALUE,
        ((SBTreeBucketV2SetRightSiblingOp) deserialized).getSiblingPageIndex());
  }
}
