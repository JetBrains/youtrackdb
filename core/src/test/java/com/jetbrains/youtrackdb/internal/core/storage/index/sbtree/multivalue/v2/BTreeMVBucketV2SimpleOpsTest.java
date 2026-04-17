package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for CellBTreeMultiValueV2Bucket simple PageOperation subclasses: init,
 * switchBucketType, setLeftSibling, setRightSibling, incrementEntriesCount,
 * decrementEntriesCount. Covers record ID verification, serialization roundtrip,
 * WALRecordsFactory integration, redo correctness, registration, and redo suppression.
 */
public class BTreeMVBucketV2SimpleOpsTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2InitOp.RECORD_ID, BTreeMVBucketV2InitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2SwitchBucketTypeOp.RECORD_ID,
        BTreeMVBucketV2SwitchBucketTypeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2SetLeftSiblingOp.RECORD_ID,
        BTreeMVBucketV2SetLeftSiblingOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2SetRightSiblingOp.RECORD_ID,
        BTreeMVBucketV2SetRightSiblingOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2IncrementEntriesCountOp.RECORD_ID,
        BTreeMVBucketV2IncrementEntriesCountOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2DecrementEntriesCountOp.RECORD_ID,
        BTreeMVBucketV2DecrementEntriesCountOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testRecordIds() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_INIT_OP, BTreeMVBucketV2InitOp.RECORD_ID);
    Assert.assertEquals(248, BTreeMVBucketV2InitOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SWITCH_BUCKET_TYPE_OP,
        BTreeMVBucketV2SwitchBucketTypeOp.RECORD_ID);
    Assert.assertEquals(249, BTreeMVBucketV2SwitchBucketTypeOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SET_LEFT_SIBLING_OP,
        BTreeMVBucketV2SetLeftSiblingOp.RECORD_ID);
    Assert.assertEquals(250, BTreeMVBucketV2SetLeftSiblingOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SET_RIGHT_SIBLING_OP,
        BTreeMVBucketV2SetRightSiblingOp.RECORD_ID);
    Assert.assertEquals(251, BTreeMVBucketV2SetRightSiblingOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_INCREMENT_ENTRIES_COUNT_OP,
        BTreeMVBucketV2IncrementEntriesCountOp.RECORD_ID);
    Assert.assertEquals(252, BTreeMVBucketV2IncrementEntriesCountOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_DECREMENT_ENTRIES_COUNT_OP,
        BTreeMVBucketV2DecrementEntriesCountOp.RECORD_ID);
    Assert.assertEquals(253, BTreeMVBucketV2DecrementEntriesCountOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new BTreeMVBucketV2InitOp(10, 20, 30, initialLsn, true);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVBucketV2InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertTrue(deserialized.isLeaf());
    Assert.assertEquals(original, deserialized);

    // Non-leaf variant
    var nonLeaf = new BTreeMVBucketV2InitOp(10, 20, 30, initialLsn, false);
    var content2 = new byte[nonLeaf.serializedSize() + 1];
    nonLeaf.toStream(content2, 1);
    var deserialized2 = new BTreeMVBucketV2InitOp();
    deserialized2.fromStream(content2, 1);
    Assert.assertFalse(deserialized2.isLeaf());
  }

  @Test
  public void testSwitchBucketTypeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var original = new BTreeMVBucketV2SwitchBucketTypeOp(15, 25, 35, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVBucketV2SwitchBucketTypeOp();
    deserialized.fromStream(content, 1);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetLeftSiblingOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var original = new BTreeMVBucketV2SetLeftSiblingOp(7, 14, 21, initialLsn, 42);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2SetLeftSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(42, deserialized.getSiblingPageIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetRightSiblingOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(9, 800);
    var original = new BTreeMVBucketV2SetRightSiblingOp(1, 2, 3, initialLsn, 99);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2SetRightSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(99, deserialized.getSiblingPageIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testIncrementEntriesCountOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(6, 600);
    var original = new BTreeMVBucketV2IncrementEntriesCountOp(5, 10, 15, initialLsn, 3);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2IncrementEntriesCountOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(3, deserialized.getEntryIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testDecrementEntriesCountOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 700);
    var original = new BTreeMVBucketV2DecrementEntriesCountOp(8, 16, 24, initialLsn, 2);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2DecrementEntriesCountOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getEntryIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSiblingOpSerializationWithSentinelValue() {
    var initialLsn = new LogSequenceNumber(10, 1000);

    // -1 is the sentinel for "no sibling" used by init()
    var leftOp = new BTreeMVBucketV2SetLeftSiblingOp(1, 2, 3, initialLsn, -1);
    var content = new byte[leftOp.serializedSize() + 1];
    leftOp.toStream(content, 1);
    var deserialized = new BTreeMVBucketV2SetLeftSiblingOp();
    deserialized.fromStream(content, 1);
    Assert.assertEquals(-1, deserialized.getSiblingPageIndex());

    var rightOp = new BTreeMVBucketV2SetRightSiblingOp(1, 2, 3, initialLsn, -1);
    var content2 = new byte[rightOp.serializedSize() + 1];
    rightOp.toStream(content2, 1);
    var deserialized2 = new BTreeMVBucketV2SetRightSiblingOp();
    deserialized2.fromStream(content2, 1);
    Assert.assertEquals(-1, deserialized2.getSiblingPageIndex());
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new BTreeMVBucketV2InitOp(10, 20, 30, initialLsn, true);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2InitOp);
    var result = (BTreeMVBucketV2InitOp) deserialized;
    Assert.assertTrue(result.isLeaf());
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testSwitchBucketTypeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(55, 550);
    var original = new BTreeMVBucketV2SwitchBucketTypeOp(4, 8, 12, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2SwitchBucketTypeOp);
    var result = (BTreeMVBucketV2SwitchBucketTypeOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testSetLeftSiblingOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(66, 660);
    var original = new BTreeMVBucketV2SetLeftSiblingOp(7, 14, 21, initialLsn, Long.MAX_VALUE);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2SetLeftSiblingOp);
    Assert.assertEquals(Long.MAX_VALUE,
        ((BTreeMVBucketV2SetLeftSiblingOp) deserialized).getSiblingPageIndex());
  }

  @Test
  public void testIncrementEntriesCountOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(77, 770);
    var original = new BTreeMVBucketV2IncrementEntriesCountOp(3, 6, 9, initialLsn, 5);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2IncrementEntriesCountOp);
    Assert.assertEquals(5,
        ((BTreeMVBucketV2IncrementEntriesCountOp) deserialized).getEntryIndex());
  }

  @Test
  public void testSetRightSiblingOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(88, 880);
    var original = new BTreeMVBucketV2SetRightSiblingOp(2, 4, 6, initialLsn, Long.MAX_VALUE);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2SetRightSiblingOp);
    Assert.assertEquals(Long.MAX_VALUE,
        ((BTreeMVBucketV2SetRightSiblingOp) deserialized).getSiblingPageIndex());
  }

  @Test
  public void testDecrementEntriesCountOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 990);
    var original = new BTreeMVBucketV2DecrementEntriesCountOp(8, 16, 24, initialLsn, 7);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2DecrementEntriesCountOp);
    Assert.assertEquals(7,
        ((BTreeMVBucketV2DecrementEntriesCountOp) deserialized).getEntryIndex());
  }

  // ---------- Redo correctness tests ----------

  /**
   * Init leaf bucket: apply directly on page1, redo on page2. Byte-level comparison.
   */
  @Test
  public void testInitLeafRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);

      var op = new BTreeMVBucketV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0), true);
      op.redo(new CellBTreeMultiValueV2Bucket<>(entry2));

      var bucket2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      Assert.assertTrue(bucket2.isLeaf());
      Assert.assertTrue(bucket2.isEmpty());
      Assert.assertEquals(-1, bucket2.getLeftSibling());
      Assert.assertEquals(-1, bucket2.getRightSibling());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Init non-leaf bucket: verify isLeaf=false survives redo.
   */
  @Test
  public void testInitNonLeafRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);

      var op = new BTreeMVBucketV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0), false);
      op.redo(new CellBTreeMultiValueV2Bucket<>(entry2));

      var bucket2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      Assert.assertFalse(bucket2.isLeaf());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * switchBucketType: init as leaf, switch to non-leaf, compare redo.
   */
  @Test
  public void testSwitchBucketTypeRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);

      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.switchBucketType();

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeMVBucketV2SwitchBucketTypeOp(0, 0, 0, lsn).redo(page2);

      Assert.assertFalse(page1.isLeaf());
      Assert.assertFalse(page2.isLeaf());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * switchBucketType: init as non-leaf, switch to leaf, compare redo.
   * Covers the else branch (non-leaf→leaf) of switchBucketType.
   */
  @Test
  public void testSwitchBucketTypeNonLeafToLeafRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);

      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);
      page1.switchBucketType();

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, false).redo(page2);
      new BTreeMVBucketV2SwitchBucketTypeOp(0, 0, 0, lsn).redo(page2);

      Assert.assertTrue(page1.isLeaf());
      Assert.assertTrue(page2.isLeaf());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * setLeftSibling and setRightSibling redo correctness.
   */
  @Test
  public void testSiblingRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);

      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.setLeftSibling(42);
      page1.setRightSibling(99);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeMVBucketV2SetLeftSiblingOp(0, 0, 0, lsn, 42).redo(page2);
      new BTreeMVBucketV2SetRightSiblingOp(0, 0, 0, lsn, 99).redo(page2);

      Assert.assertEquals(42, page1.getLeftSibling());
      Assert.assertEquals(42, page2.getLeftSibling());
      Assert.assertEquals(99, page1.getRightSibling());
      Assert.assertEquals(99, page2.getRightSibling());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * incrementEntriesCount redo correctness: init a leaf bucket, add a main leaf entry,
   * then increment its entries count. Compare direct mutation vs redo.
   */
  @Test
  public void testIncrementEntriesCountRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var key = new byte[] {1, 2, 3, 4};
      var rid = new RecordId(5, 100);

      // Set up both pages identically: init + createMainLeafEntry
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, rid, 1L);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);
      page2.createMainLeafEntry(0, key, rid, 1L);

      // Apply incrementEntriesCount directly on page1
      page1.incrementEntriesCount(0);

      // Apply via redo on page2
      new BTreeMVBucketV2IncrementEntriesCountOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0).redo(page2);

      // Byte-level comparison
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after incrementEntriesCount redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * decrementEntriesCount redo correctness: init a leaf bucket, add a main leaf entry,
   * increment twice, then decrement once. Compare direct mutation vs redo.
   */
  @Test
  public void testDecrementEntriesCountRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      var key = new byte[] {1, 2, 3, 4};
      var rid = new RecordId(5, 100);

      // Set up both pages identically: init + createMainLeafEntry + increment twice
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, rid, 1L);
      page1.incrementEntriesCount(0);
      page1.incrementEntriesCount(0);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);
      page2.createMainLeafEntry(0, key, rid, 1L);
      page2.incrementEntriesCount(0);
      page2.incrementEntriesCount(0);

      // Apply decrementEntriesCount directly on page1
      page1.decrementEntriesCount(0);

      // Apply via redo on page2
      new BTreeMVBucketV2DecrementEntriesCountOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0).redo(page2);

      // Byte-level comparison
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after decrementEntriesCount redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  // ---------- Registration tests ----------

  @Test
  public void testInitRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(1, 100));

      var page = new CellBTreeMultiValueV2Bucket<>(changes);
      page.init(true);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeMVBucketV2InitOp);
      Assert.assertTrue(((BTreeMVBucketV2InitOp) registeredOp).isLeaf());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testSwitchBucketTypeRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(1, 100));

      var page = new CellBTreeMultiValueV2Bucket<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      page.switchBucketType();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      Assert.assertTrue(opCaptor.getValue() instanceof BTreeMVBucketV2SwitchBucketTypeOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testSetLeftSiblingRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(2, 200));

      var page = new CellBTreeMultiValueV2Bucket<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      page.setLeftSibling(123);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeMVBucketV2SetLeftSiblingOp) opCaptor.getValue();
      Assert.assertEquals(123, registeredOp.getSiblingPageIndex());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testSetRightSiblingRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(3, 300));

      var page = new CellBTreeMultiValueV2Bucket<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      page.setRightSibling(456);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeMVBucketV2SetRightSiblingOp) opCaptor.getValue();
      Assert.assertEquals(456, registeredOp.getSiblingPageIndex());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testIncrementEntriesCountRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(4, 400));

      var page = new CellBTreeMultiValueV2Bucket<>(changes);
      page.init(true);
      var key = new byte[] {1, 2, 3, 4};
      page.createMainLeafEntry(0, key, new RecordId(5, 100), 1L);
      org.mockito.Mockito.reset(atomicOp);

      page.incrementEntriesCount(0);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeMVBucketV2IncrementEntriesCountOp) opCaptor.getValue();
      Assert.assertEquals(0, registeredOp.getEntryIndex());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testDecrementEntriesCountRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry delegate = new CacheEntryImpl(42, 7, cachePointer, false, null);
    delegate.acquireExclusiveLock();

    try {
      changes.setDelegate(delegate);
      changes.setInitialLSN(new LogSequenceNumber(5, 500));

      var page = new CellBTreeMultiValueV2Bucket<>(changes);
      page.init(true);
      var key = new byte[] {1, 2, 3, 4};
      page.createMainLeafEntry(0, key, new RecordId(5, 100), 1L);
      page.incrementEntriesCount(0); // entriesCount=2, so decrement won't return "last"
      org.mockito.Mockito.reset(atomicOp);

      page.decrementEntriesCount(0);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeMVBucketV2DecrementEntriesCountOp) opCaptor.getValue();
      Assert.assertEquals(0, registeredOp.getEntryIndex());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression test ----------

  /**
   * Verifies D4 redo suppression: when redo calls mutation methods with changes=null
   * (direct buffer), no PageOperation is registered.
   */
  @Test
  public void testRedoSuppressionNoRegistration() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    // Plain CacheEntryImpl (not CacheEntryChanges) — no registration possible
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);
      var page = new CellBTreeMultiValueV2Bucket<>(entry);

      // These redo calls go through the mutation methods but should NOT try to
      // register any ops since the CacheEntry is not CacheEntryChanges
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, true).redo(page);
      new BTreeMVBucketV2SwitchBucketTypeOp(0, 0, 0, lsn).redo(page);
      new BTreeMVBucketV2SetLeftSiblingOp(0, 0, 0, lsn, 42).redo(page);
      new BTreeMVBucketV2SetRightSiblingOp(0, 0, 0, lsn, 99).redo(page);

      // No exception means D4 redo suppression works — the instanceof check
      // correctly skips registration when CacheEntry is not CacheEntryChanges.
      // init(true) -> leaf, switchBucketType -> non-leaf
      Assert.assertFalse(page.isLeaf());
      Assert.assertEquals(42, page.getLeftSibling());
      Assert.assertEquals(99, page.getRightSibling());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Equals/HashCode tests ----------

  @Test
  public void testInitOpEquality() {
    var lsn = new LogSequenceNumber(1, 100);
    var op1 = new BTreeMVBucketV2InitOp(10, 20, 30, lsn, true);
    var op2 = new BTreeMVBucketV2InitOp(10, 20, 30, lsn, true);
    var op3 = new BTreeMVBucketV2InitOp(10, 20, 30, lsn, false);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSiblingOpEquality() {
    var lsn = new LogSequenceNumber(2, 200);
    var left1 = new BTreeMVBucketV2SetLeftSiblingOp(1, 2, 3, lsn, 42);
    var left2 = new BTreeMVBucketV2SetLeftSiblingOp(1, 2, 3, lsn, 42);
    var left3 = new BTreeMVBucketV2SetLeftSiblingOp(1, 2, 3, lsn, 99);

    Assert.assertEquals(left1, left2);
    Assert.assertEquals(left1.hashCode(), left2.hashCode());
    Assert.assertNotEquals(left1, left3);
  }

  @Test
  public void testEntriesCountOpEquality() {
    var lsn = new LogSequenceNumber(3, 300);
    var inc1 = new BTreeMVBucketV2IncrementEntriesCountOp(1, 2, 3, lsn, 5);
    var inc2 = new BTreeMVBucketV2IncrementEntriesCountOp(1, 2, 3, lsn, 5);
    var inc3 = new BTreeMVBucketV2IncrementEntriesCountOp(1, 2, 3, lsn, 7);

    Assert.assertEquals(inc1, inc2);
    Assert.assertEquals(inc1.hashCode(), inc2.hashCode());
    Assert.assertNotEquals(inc1, inc3);
  }
}
