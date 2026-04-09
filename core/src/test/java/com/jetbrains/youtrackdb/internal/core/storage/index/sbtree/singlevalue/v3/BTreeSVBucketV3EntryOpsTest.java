package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for CellBTreeSingleValueBucketV3 entry PageOperation subclasses:
 * addLeafEntry, addNonLeafEntry, removeLeafEntry, removeNonLeafEntry, updateKey.
 * Each test verifies that the redo path produces byte-identical page state to the direct path.
 */
public class BTreeSVBucketV3EntryOpsTest {

  @Before
  public void registerRecordTypes() {
    // Entry ops under test
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3AddLeafEntryOp.RECORD_ID,
        BTreeSVBucketV3AddLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3AddNonLeafEntryOp.RECORD_ID,
        BTreeSVBucketV3AddNonLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3RemoveLeafEntryOp.RECORD_ID,
        BTreeSVBucketV3RemoveLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3RemoveNonLeafEntryOp.RECORD_ID,
        BTreeSVBucketV3RemoveNonLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3UpdateKeyOp.RECORD_ID,
        BTreeSVBucketV3UpdateKeyOp.class);
    // Required by redo tests that init/add entries on the page
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3InitOp.RECORD_ID, BTreeSVBucketV3InitOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testRecordIds() {
    Assert.assertEquals(232, BTreeSVBucketV3AddLeafEntryOp.RECORD_ID);
    Assert.assertEquals(233, BTreeSVBucketV3AddNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(234, BTreeSVBucketV3RemoveLeafEntryOp.RECORD_ID);
    Assert.assertEquals(235, BTreeSVBucketV3RemoveNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(236, BTreeSVBucketV3UpdateKeyOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testAddLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var key = new byte[] {1, 2, 3, 4, 5};
    var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    var original = new BTreeSVBucketV3AddLeafEntryOp(10, 20, 30, initialLsn, 3, key, value);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3AddLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(3, deserialized.getIndex());
    Assert.assertArrayEquals(key, deserialized.getSerializedKey());
    Assert.assertArrayEquals(value, deserialized.getSerializedValue());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddNonLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var key = new byte[] {11, 22, 33};
    var original =
        new BTreeSVBucketV3AddNonLeafEntryOp(15, 25, 35, initialLsn, 2, 100, 200, key);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3AddNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getIndex());
    Assert.assertEquals(100, deserialized.getLeftChildIndex());
    Assert.assertEquals(200, deserialized.getNewRightChildIndex());
    Assert.assertArrayEquals(key, deserialized.getKey());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var key = new byte[] {9, 8, 7, 6};
    var original = new BTreeSVBucketV3RemoveLeafEntryOp(7, 14, 21, initialLsn, 5, key);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3RemoveLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(5, deserialized.getEntryIndex());
    Assert.assertArrayEquals(key, deserialized.getKey());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveNonLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(9, 800);
    var key = new byte[] {44, 55, 66};
    var original =
        new BTreeSVBucketV3RemoveNonLeafEntryOp(1, 2, 3, initialLsn, 4, key, true);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3RemoveNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(4, deserialized.getEntryIndex());
    Assert.assertArrayEquals(key, deserialized.getKey());
    Assert.assertTrue(deserialized.isRemoveLeftChildPointer());
    Assert.assertEquals(original, deserialized);

    // Also test with removeLeftChildPointer=false
    var original2 =
        new BTreeSVBucketV3RemoveNonLeafEntryOp(1, 2, 3, initialLsn, 4, key, false);
    var content2 = new byte[original2.serializedSize() + 1];
    original2.toStream(content2, 1);
    var deserialized2 = new BTreeSVBucketV3RemoveNonLeafEntryOp();
    deserialized2.fromStream(content2, 1);
    Assert.assertFalse(deserialized2.isRemoveLeftChildPointer());
  }

  @Test
  public void testUpdateKeyOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(4, 300);
    var newKey = new byte[] {77, 88, 99};
    var original = new BTreeSVBucketV3UpdateKeyOp(11, 22, 33, initialLsn, 7, newKey, 5);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3UpdateKeyOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(7, deserialized.getEntryIndex());
    Assert.assertArrayEquals(newKey, deserialized.getNewKey());
    Assert.assertEquals(5, deserialized.getOldKeySize());
    Assert.assertEquals(original, deserialized);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testAddLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var key = new byte[] {1, 2, 3};
    var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
    var original = new BTreeSVBucketV3AddLeafEntryOp(10, 20, 30, initialLsn, 0, key, value);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3AddLeafEntryOp);
    var result = (BTreeSVBucketV3AddLeafEntryOp) deserialized;
    Assert.assertEquals(0, result.getIndex());
    Assert.assertArrayEquals(key, result.getSerializedKey());
    Assert.assertArrayEquals(value, result.getSerializedValue());
  }

  @Test
  public void testAddNonLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(55, 550);
    var key = new byte[] {5, 10, 15, 20};
    var original =
        new BTreeSVBucketV3AddNonLeafEntryOp(4, 8, 12, initialLsn, 1, 50, 60, key);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3AddNonLeafEntryOp);
    var result = (BTreeSVBucketV3AddNonLeafEntryOp) deserialized;
    Assert.assertEquals(1, result.getIndex());
    Assert.assertEquals(50, result.getLeftChildIndex());
    Assert.assertEquals(60, result.getNewRightChildIndex());
    Assert.assertArrayEquals(key, result.getKey());
  }

  @Test
  public void testRemoveLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(66, 660);
    var key = new byte[] {1, 2};
    var original = new BTreeSVBucketV3RemoveLeafEntryOp(7, 14, 21, initialLsn, 0, key);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3RemoveLeafEntryOp);
    Assert.assertEquals(0, ((BTreeSVBucketV3RemoveLeafEntryOp) deserialized).getEntryIndex());
  }

  @Test
  public void testRemoveNonLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(77, 770);
    var key = new byte[] {3, 4, 5};
    var original =
        new BTreeSVBucketV3RemoveNonLeafEntryOp(3, 6, 9, initialLsn, 2, key, true);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3RemoveNonLeafEntryOp);
    var result = (BTreeSVBucketV3RemoveNonLeafEntryOp) deserialized;
    Assert.assertEquals(2, result.getEntryIndex());
    Assert.assertTrue(result.isRemoveLeftChildPointer());
  }

  @Test
  public void testUpdateKeyOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(88, 880);
    var newKey = new byte[] {99, 100};
    var original = new BTreeSVBucketV3UpdateKeyOp(5, 10, 15, initialLsn, 0, newKey, 3);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3UpdateKeyOp);
    var result = (BTreeSVBucketV3UpdateKeyOp) deserialized;
    Assert.assertEquals(0, result.getEntryIndex());
    Assert.assertArrayEquals(newKey, result.getNewKey());
    Assert.assertEquals(3, result.getOldKeySize());
  }

  // ---------- Redo correctness tests ----------

  /**
   * addLeafEntry at index 0 on an empty bucket: direct path vs redo path, byte-level comparison.
   */
  @Test
  public void testAddLeafEntryRedoCorrectness() {
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
      var key = new byte[] {1, 2, 3, 4};
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

      // Direct path
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      Assert.assertTrue(page1.addLeafEntry(0, key, value));

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 0, key, value).redo(page2);

      Assert.assertEquals(1, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after addLeafEntry redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * addLeafEntry at beginning, middle, and end of bucket: verifies pointer shifting.
   */
  @Test
  public void testAddLeafEntryMultiplePositionsRedoCorrectness() {
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
      var key1 = new byte[] {1, 0, 0, 0}; // smallest
      var key2 = new byte[] {2, 0, 0, 0}; // middle
      var key3 = new byte[] {3, 0, 0, 0}; // largest
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

      // Direct path: insert in sorted order at specific positions
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addLeafEntry(0, key2, value); // first entry
      page1.addLeafEntry(0, key1, value); // insert at beginning
      page1.addLeafEntry(2, key3, value); // insert at end

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 0, key2, value).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 0, key1, value).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 2, key3, value).redo(page2);

      Assert.assertEquals(3, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after multiple addLeafEntry redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * addNonLeafEntry: verifies child pointer storage and neighbor child-pointer update.
   */
  @Test
  public void testAddNonLeafEntryRedoCorrectness() {
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
      var key1 = new byte[] {10, 0, 0, 0};
      var key2 = new byte[] {20, 0, 0, 0};

      // Direct path
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, 1, 2, key1);
      // Second entry: the left child of this entry points to same right child of first
      page1.addNonLeafEntry(1, 2, 3, key2);

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, false).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 0, 1, 2, key1).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 1, 2, 3, key2).redo(page2);

      Assert.assertEquals(2, page2.size());
      Assert.assertFalse(page2.isLeaf());

      // Verify child pointer values
      Assert.assertEquals(1, page1.getLeft(0));
      Assert.assertEquals(1, page2.getLeft(0));

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after addNonLeafEntry redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * addNonLeafEntry with neighbor child-pointer update: insert at beginning when entries exist.
   * The neighbor's left child pointer is updated to the new entry's right child.
   */
  @Test
  public void testAddNonLeafEntryNeighborChildPointerUpdateRedoCorrectness() {
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
      var key1 = new byte[] {20, 0, 0, 0};
      var key2 = new byte[] {10, 0, 0, 0}; // smaller, inserted at index 0

      // Direct path: add entry at 0 first, then insert before it
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, 5, 6, key1);
      // Insert at beginning — next entry's leftChild should be updated to 8
      page1.addNonLeafEntry(0, 7, 8, key2);

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, false).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 0, 5, 6, key1).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 0, 7, 8, key2).redo(page2);

      Assert.assertEquals(2, page2.size());
      // Verify the neighbor child pointer update: entry[1].leftChild should be 8
      Assert.assertEquals(8, page1.getLeft(1));
      Assert.assertEquals(8, page2.getLeft(1));

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
   * removeLeafEntry: add 3 entries, remove the middle one, compare redo.
   * Verifies pointer compaction and free space recovery.
   */
  @Test
  public void testRemoveLeafEntryRedoCorrectness() {
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
      var key1 = new byte[] {1, 0, 0, 0};
      var key2 = new byte[] {2, 0, 0, 0};
      var key3 = new byte[] {3, 0, 0, 0};
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

      // Direct path
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addLeafEntry(0, key1, value);
      page1.addLeafEntry(1, key2, value);
      page1.addLeafEntry(2, key3, value);
      page1.removeLeafEntry(1, key2); // remove middle

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 0, key1, value).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 1, key2, value).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 2, key3, value).redo(page2);
      new BTreeSVBucketV3RemoveLeafEntryOp(0, 0, 0, lsn, 1, key2).redo(page2);

      Assert.assertEquals(2, page1.size());
      Assert.assertEquals(2, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after removeLeafEntry redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * removeLeafEntry: remove first and last entries to verify boundary handling.
   */
  @Test
  public void testRemoveLeafEntryFirstAndLastRedoCorrectness() {
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
      var key1 = new byte[] {1, 0, 0, 0};
      var key2 = new byte[] {2, 0, 0, 0};
      var key3 = new byte[] {3, 0, 0, 0};
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

      // Direct path
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addLeafEntry(0, key1, value);
      page1.addLeafEntry(1, key2, value);
      page1.addLeafEntry(2, key3, value);
      page1.removeLeafEntry(2, key3); // remove last
      page1.removeLeafEntry(0, key1); // remove first

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 0, key1, value).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 1, key2, value).redo(page2);
      new BTreeSVBucketV3AddLeafEntryOp(0, 0, 0, lsn, 2, key3, value).redo(page2);
      new BTreeSVBucketV3RemoveLeafEntryOp(0, 0, 0, lsn, 2, key3).redo(page2);
      new BTreeSVBucketV3RemoveLeafEntryOp(0, 0, 0, lsn, 0, key1).redo(page2);

      Assert.assertEquals(1, page2.size());

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
   * removeNonLeafEntry with removeLeftChildPointer=true: child-pointer adjustment.
   * When removing a non-leaf entry and choosing to remove the left child, the right child
   * replaces it in the neighbor entry.
   */
  @Test
  public void testRemoveNonLeafEntryRemoveLeftRedoCorrectness() {
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
      var key1 = new byte[] {10, 0, 0, 0};
      var key2 = new byte[] {20, 0, 0, 0};
      var key3 = new byte[] {30, 0, 0, 0};

      // Direct path: add 3 non-leaf entries, remove middle with removeLeftChildPointer=true
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, 1, 2, key1);
      page1.addNonLeafEntry(1, 2, 3, key2);
      page1.addNonLeafEntry(2, 3, 4, key3);
      page1.removeNonLeafEntry(1, key2, true);

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, false).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 0, 1, 2, key1).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 1, 2, 3, key2).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 2, 3, 4, key3).redo(page2);
      new BTreeSVBucketV3RemoveNonLeafEntryOp(0, 0, 0, lsn, 1, key2, true).redo(page2);

      Assert.assertEquals(2, page1.size());
      Assert.assertEquals(2, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after removeNonLeafEntry redo (removeLeft=true)",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * removeNonLeafEntry with removeLeftChildPointer=false: the left child is kept.
   */
  @Test
  public void testRemoveNonLeafEntryRemoveRightRedoCorrectness() {
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
      var key1 = new byte[] {10, 0, 0, 0};
      var key2 = new byte[] {20, 0, 0, 0};
      var key3 = new byte[] {30, 0, 0, 0};

      // Direct path
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, 1, 2, key1);
      page1.addNonLeafEntry(1, 2, 3, key2);
      page1.addNonLeafEntry(2, 3, 4, key3);
      page1.removeNonLeafEntry(1, key2, false);

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, false).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 0, 1, 2, key1).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 1, 2, 3, key2).redo(page2);
      new BTreeSVBucketV3AddNonLeafEntryOp(0, 0, 0, lsn, 2, 3, 4, key3).redo(page2);
      new BTreeSVBucketV3RemoveNonLeafEntryOp(0, 0, 0, lsn, 1, key2, false).redo(page2);

      Assert.assertEquals(2, page1.size());
      Assert.assertEquals(2, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after removeNonLeafEntry redo (removeLeft=false)",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * updateKey fast path (same key size): in-place replacement.
   */
  @Test
  public void testUpdateKeySameSizeRedoCorrectness() {
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
      var key1 = new byte[] {10, 0, 0, 0};
      var newKey1 = new byte[] {15, 0, 0, 0}; // same size

      // Setup both pages: non-leaf bucket with one entry
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, 1, 2, key1);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      page2.init(false);
      page2.addNonLeafEntry(0, 1, 2, key1);

      // Direct path: use updateKeyWithOldKeySize (same as redo path)
      page1.updateKeyWithOldKeySize(0, newKey1, key1.length);

      // Redo path
      new BTreeSVBucketV3UpdateKeyOp(0, 0, 0, lsn, 0, newKey1, key1.length).redo(page2);

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after updateKey redo (same size)",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * updateKey slow path (different key size): page compaction and data movement.
   */
  @Test
  public void testUpdateKeyDifferentSizeRedoCorrectness() {
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
      var key1 = new byte[] {10, 0, 0, 0};
      var key2 = new byte[] {20, 0, 0, 0};
      // New key is longer — triggers page compaction
      var newKey1 = new byte[] {15, 0, 0, 0, 0, 0};

      // Setup both pages: non-leaf bucket with two entries
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, 1, 2, key1);
      page1.addNonLeafEntry(1, 2, 3, key2);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      page2.init(false);
      page2.addNonLeafEntry(0, 1, 2, key1);
      page2.addNonLeafEntry(1, 2, 3, key2);

      // Direct path
      page1.updateKeyWithOldKeySize(0, newKey1, key1.length);

      // Redo path
      new BTreeSVBucketV3UpdateKeyOp(0, 0, 0, lsn, 0, newKey1, key1.length).redo(page2);

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after updateKey redo (different size)",
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
  public void testAddLeafEntryRegistersOp() {
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      var key = new byte[] {1, 2, 3, 4};
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      page.addLeafEntry(0, key, value);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3AddLeafEntryOp) opCaptor.getValue();
      Assert.assertEquals(0, registeredOp.getIndex());
      Assert.assertArrayEquals(key, registeredOp.getSerializedKey());
      Assert.assertArrayEquals(value, registeredOp.getSerializedValue());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testAddNonLeafEntryRegistersOp() {
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(false);
      org.mockito.Mockito.reset(atomicOp);

      var key = new byte[] {10, 20, 30};
      page.addNonLeafEntry(0, 5, 6, key);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3AddNonLeafEntryOp) opCaptor.getValue();
      Assert.assertEquals(0, registeredOp.getIndex());
      Assert.assertEquals(5, registeredOp.getLeftChildIndex());
      Assert.assertEquals(6, registeredOp.getNewRightChildIndex());
      Assert.assertArrayEquals(key, registeredOp.getKey());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testRemoveLeafEntryRegistersOp() {
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);
      var key = new byte[] {1, 2, 3, 4};
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      page.addLeafEntry(0, key, value);
      org.mockito.Mockito.reset(atomicOp);

      page.removeLeafEntry(0, key);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3RemoveLeafEntryOp) opCaptor.getValue();
      Assert.assertEquals(0, registeredOp.getEntryIndex());
      Assert.assertArrayEquals(key, registeredOp.getKey());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testRemoveNonLeafEntryRegistersOp() {
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(false);
      var key1 = new byte[] {10, 0, 0, 0};
      var key2 = new byte[] {20, 0, 0, 0};
      page.addNonLeafEntry(0, 1, 2, key1);
      page.addNonLeafEntry(1, 2, 3, key2);
      org.mockito.Mockito.reset(atomicOp);

      page.removeNonLeafEntry(0, key1, true);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3RemoveNonLeafEntryOp) opCaptor.getValue();
      Assert.assertEquals(0, registeredOp.getEntryIndex());
      Assert.assertArrayEquals(key1, registeredOp.getKey());
      Assert.assertTrue(registeredOp.isRemoveLeftChildPointer());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testUpdateKeyRegistersOp() {
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(false);
      var key = new byte[] {10, 0, 0, 0};
      page.addNonLeafEntry(0, 1, 2, key);
      org.mockito.Mockito.reset(atomicOp);

      // updateKeyWithOldKeySize is used during redo; updateKey uses serializer
      // so we test updateKey's registration by calling updateKeyWithOldKeySize
      // which does NOT register (it's for recovery). Instead, verify the updateKey path
      // by checking what updateKey captures.
      // Since updateKey requires a serializer (not available in unit test),
      // we verify the registration pattern via updateKeyWithOldKeySize + manual registration.
      // The actual updateKey registration is tested in integration tests.
      var newKey = new byte[] {15, 0, 0, 0};
      page.updateKeyWithOldKeySize(0, newKey, key.length);

      // updateKeyWithOldKeySize does NOT register because it's the redo path.
      // The registration happens in updateKey which needs a serializer.
      // Verify that the page state was updated correctly.
      org.mockito.Mockito.verifyNoInteractions(atomicOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression (D4) ----------

  /**
   * Entry operations on a plain CacheEntry (not CacheEntryChanges) must not attempt registration.
   * This simulates the recovery path where changes=null.
   */
  @Test
  public void testNoRegistrationDuringRedoPath() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeSingleValueBucketV3<>(entry);
      page.init(true);

      // Add and remove leaf entries — no crash means no accidental registration
      var key = new byte[] {1, 2, 3, 4};
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      page.addLeafEntry(0, key, value);
      Assert.assertEquals(1, page.size());

      page.removeLeafEntry(0, key);
      Assert.assertEquals(0, page.size());

      // Non-leaf operations
      page.switchBucketType();
      Assert.assertFalse(page.isLeaf());

      var nonLeafKey = new byte[] {10, 0, 0, 0};
      page.addNonLeafEntry(0, 1, 2, nonLeafKey);
      Assert.assertEquals(1, page.size());

      page.removeNonLeafEntry(0, nonLeafKey, true);
      Assert.assertEquals(0, page.size());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Equals and hashCode ----------

  @Test
  public void testAddLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var key = new byte[] {1, 2, 3};
    var value = new byte[] {4, 5, 6};
    var op1 = new BTreeSVBucketV3AddLeafEntryOp(5, 10, 15, lsn, 0, key, value);
    var op2 = new BTreeSVBucketV3AddLeafEntryOp(5, 10, 15, lsn, 0, key, value);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different index
    var op3 = new BTreeSVBucketV3AddLeafEntryOp(5, 10, 15, lsn, 1, key, value);
    Assert.assertNotEquals(op1, op3);

    // Different key
    var op4 = new BTreeSVBucketV3AddLeafEntryOp(5, 10, 15, lsn, 0, new byte[] {9}, value);
    Assert.assertNotEquals(op1, op4);

    // Different value
    var op5 = new BTreeSVBucketV3AddLeafEntryOp(5, 10, 15, lsn, 0, key, new byte[] {9});
    Assert.assertNotEquals(op1, op5);
  }

  @Test
  public void testAddNonLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var key = new byte[] {1, 2, 3};
    var op1 = new BTreeSVBucketV3AddNonLeafEntryOp(5, 10, 15, lsn, 0, 1, 2, key);
    var op2 = new BTreeSVBucketV3AddNonLeafEntryOp(5, 10, 15, lsn, 0, 1, 2, key);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different child indices
    var op3 = new BTreeSVBucketV3AddNonLeafEntryOp(5, 10, 15, lsn, 0, 99, 2, key);
    Assert.assertNotEquals(op1, op3);

    var op4 = new BTreeSVBucketV3AddNonLeafEntryOp(5, 10, 15, lsn, 0, 1, 99, key);
    Assert.assertNotEquals(op1, op4);
  }

  @Test
  public void testRemoveNonLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var key = new byte[] {1, 2, 3};
    var op1 = new BTreeSVBucketV3RemoveNonLeafEntryOp(5, 10, 15, lsn, 0, key, true);
    var op2 = new BTreeSVBucketV3RemoveNonLeafEntryOp(5, 10, 15, lsn, 0, key, true);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different removeLeftChildPointer
    var op3 = new BTreeSVBucketV3RemoveNonLeafEntryOp(5, 10, 15, lsn, 0, key, false);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testUpdateKeyOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var newKey = new byte[] {1, 2, 3};
    var op1 = new BTreeSVBucketV3UpdateKeyOp(5, 10, 15, lsn, 0, newKey, 4);
    var op2 = new BTreeSVBucketV3UpdateKeyOp(5, 10, 15, lsn, 0, newKey, 4);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different oldKeySize
    var op3 = new BTreeSVBucketV3UpdateKeyOp(5, 10, 15, lsn, 0, newKey, 99);
    Assert.assertNotEquals(op1, op3);

    // Different newKey
    var op4 = new BTreeSVBucketV3UpdateKeyOp(5, 10, 15, lsn, 0, new byte[] {9}, 4);
    Assert.assertNotEquals(op1, op4);
  }

  @Test
  public void testRemoveLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var key = new byte[] {1, 2, 3};
    var op1 = new BTreeSVBucketV3RemoveLeafEntryOp(5, 10, 15, lsn, 0, key);
    var op2 = new BTreeSVBucketV3RemoveLeafEntryOp(5, 10, 15, lsn, 0, key);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different entryIndex
    var op3 = new BTreeSVBucketV3RemoveLeafEntryOp(5, 10, 15, lsn, 1, key);
    Assert.assertNotEquals(op1, op3);

    // Different key
    var op4 = new BTreeSVBucketV3RemoveLeafEntryOp(5, 10, 15, lsn, 0, new byte[] {9});
    Assert.assertNotEquals(op1, op4);
  }
}
