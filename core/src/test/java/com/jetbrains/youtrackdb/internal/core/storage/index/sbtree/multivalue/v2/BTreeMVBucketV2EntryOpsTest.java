package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * Tests for CellBTreeMultiValueV2Bucket entry PageOperation subclasses: createMainLeafEntry,
 * removeMainLeafEntry, appendNewLeafEntry, removeLeafEntry, addNonLeafEntry, removeNonLeafEntry.
 * Covers record ID verification, serialization roundtrip, WALRecordsFactory integration, redo
 * correctness, conditional registration, and redo suppression.
 */
public class BTreeMVBucketV2EntryOpsTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2CreateMainLeafEntryOp.RECORD_ID,
        BTreeMVBucketV2CreateMainLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2RemoveMainLeafEntryOp.RECORD_ID,
        BTreeMVBucketV2RemoveMainLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2AppendNewLeafEntryOp.RECORD_ID,
        BTreeMVBucketV2AppendNewLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2RemoveLeafEntryOp.RECORD_ID,
        BTreeMVBucketV2RemoveLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2AddNonLeafEntryOp.RECORD_ID,
        BTreeMVBucketV2AddNonLeafEntryOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2RemoveNonLeafEntryOp.RECORD_ID,
        BTreeMVBucketV2RemoveNonLeafEntryOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testRecordIds() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_CREATE_MAIN_LEAF_ENTRY_OP,
        BTreeMVBucketV2CreateMainLeafEntryOp.RECORD_ID);
    Assert.assertEquals(254, BTreeMVBucketV2CreateMainLeafEntryOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_MAIN_LEAF_ENTRY_OP,
        BTreeMVBucketV2RemoveMainLeafEntryOp.RECORD_ID);
    Assert.assertEquals(255, BTreeMVBucketV2RemoveMainLeafEntryOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_APPEND_NEW_LEAF_ENTRY_OP,
        BTreeMVBucketV2AppendNewLeafEntryOp.RECORD_ID);
    Assert.assertEquals(256, BTreeMVBucketV2AppendNewLeafEntryOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_LEAF_ENTRY_OP,
        BTreeMVBucketV2RemoveLeafEntryOp.RECORD_ID);
    Assert.assertEquals(257, BTreeMVBucketV2RemoveLeafEntryOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP,
        BTreeMVBucketV2AddNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(258, BTreeMVBucketV2AddNonLeafEntryOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_NON_LEAF_ENTRY_OP,
        BTreeMVBucketV2RemoveNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(259, BTreeMVBucketV2RemoveNonLeafEntryOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testCreateMainLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var key = new byte[] {1, 2, 3, 4};
    var original = new BTreeMVBucketV2CreateMainLeafEntryOp(
        10, 20, 30, initialLsn, 0, key, (short) 5, 1000L, 42L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVBucketV2CreateMainLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(0, deserialized.getIndex());
    Assert.assertArrayEquals(key, deserialized.getSerializedKey());
    Assert.assertEquals((short) 5, deserialized.getCollectionId());
    Assert.assertEquals(1000L, deserialized.getCollectionPosition());
    Assert.assertEquals(42L, deserialized.getMId());
  }

  @Test
  public void testCreateMainLeafEntryOpNullValueRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var key = new byte[] {1, 2, 3};
    // collectionId=-1, collectionPosition=-1 for null value
    var original = new BTreeMVBucketV2CreateMainLeafEntryOp(
        10, 20, 30, initialLsn, 0, key, (short) -1, -1L, 99L);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2CreateMainLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals((short) -1, deserialized.getCollectionId());
    Assert.assertEquals(-1L, deserialized.getCollectionPosition());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveMainLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(6, 300);
    var original = new BTreeMVBucketV2RemoveMainLeafEntryOp(10, 20, 30, initialLsn, 3, 8);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2RemoveMainLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(3, deserialized.getEntryIndex());
    Assert.assertEquals(8, deserialized.getKeySize());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAppendNewLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 400);
    var original = new BTreeMVBucketV2AppendNewLeafEntryOp(
        10, 20, 30, initialLsn, 2, (short) 7, 2000L);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2AppendNewLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getIndex());
    Assert.assertEquals((short) 7, deserialized.getCollectionId());
    Assert.assertEquals(2000L, deserialized.getCollectionPosition());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 500);
    var original = new BTreeMVBucketV2RemoveLeafEntryOp(
        10, 20, 30, initialLsn, 1, (short) 5, 1000L);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2RemoveLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(1, deserialized.getEntryIndex());
    Assert.assertEquals((short) 5, deserialized.getCollectionId());
    Assert.assertEquals(1000L, deserialized.getCollectionPosition());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddNonLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(9, 600);
    var key = new byte[] {10, 20, 30};
    var original = new BTreeMVBucketV2AddNonLeafEntryOp(
        10, 20, 30, initialLsn, 1, key, 5, 7, true);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2AddNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(1, deserialized.getIndex());
    Assert.assertArrayEquals(key, deserialized.getSerializedKey());
    Assert.assertEquals(5, deserialized.getLeftChild());
    Assert.assertEquals(7, deserialized.getRightChild());
    Assert.assertTrue(deserialized.isUpdateNeighbors());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveNonLeafEntryOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(10, 700);
    var key = new byte[] {1, 2, 3, 4};
    var original = new BTreeMVBucketV2RemoveNonLeafEntryOp(
        10, 20, 30, initialLsn, 2, key, 3);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2RemoveNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getEntryIndex());
    Assert.assertArrayEquals(key, deserialized.getKey());
    Assert.assertEquals(3, deserialized.getPrevChild());
    Assert.assertEquals(original, deserialized);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testCreateMainLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var key = new byte[] {1, 2, 3, 4};
    var original = new BTreeMVBucketV2CreateMainLeafEntryOp(
        10, 20, 30, initialLsn, 0, key, (short) 5, 1000L, 42L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2CreateMainLeafEntryOp);
    var result = (BTreeMVBucketV2CreateMainLeafEntryOp) deserialized;
    Assert.assertEquals(42L, result.getMId());
    Assert.assertArrayEquals(key, result.getSerializedKey());
  }

  @Test
  public void testAddNonLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(55, 550);
    var key = new byte[] {10, 20};
    var original = new BTreeMVBucketV2AddNonLeafEntryOp(
        4, 8, 12, initialLsn, 1, key, 5, 7, true);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2AddNonLeafEntryOp);
    var result = (BTreeMVBucketV2AddNonLeafEntryOp) deserialized;
    Assert.assertEquals(5, result.getLeftChild());
    Assert.assertEquals(7, result.getRightChild());
    Assert.assertTrue(result.isUpdateNeighbors());
  }

  @Test
  public void testRemoveNonLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(66, 660);
    var key = new byte[] {1, 2, 3, 4};
    var original = new BTreeMVBucketV2RemoveNonLeafEntryOp(7, 14, 21, initialLsn, 0, key, 3);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2RemoveNonLeafEntryOp);
    Assert.assertEquals(3,
        ((BTreeMVBucketV2RemoveNonLeafEntryOp) deserialized).getPrevChild());
  }

  // ---------- Additional WALRecordsFactory roundtrip tests ----------

  @Test
  public void testRemoveMainLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(43, 430);
    var original = new BTreeMVBucketV2RemoveMainLeafEntryOp(5, 10, 15, initialLsn, 3, 8);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2RemoveMainLeafEntryOp);
    var result = (BTreeMVBucketV2RemoveMainLeafEntryOp) deserialized;
    Assert.assertEquals(3, result.getEntryIndex());
    Assert.assertEquals(8, result.getKeySize());
  }

  @Test
  public void testAppendNewLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(44, 440);
    var original = new BTreeMVBucketV2AppendNewLeafEntryOp(
        10, 20, 30, initialLsn, 2, (short) 7, 2000L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2AppendNewLeafEntryOp);
    var result = (BTreeMVBucketV2AppendNewLeafEntryOp) deserialized;
    Assert.assertEquals(2, result.getIndex());
    Assert.assertEquals((short) 7, result.getCollectionId());
    Assert.assertEquals(2000L, result.getCollectionPosition());
  }

  @Test
  public void testRemoveLeafEntryOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(45, 450);
    var original = new BTreeMVBucketV2RemoveLeafEntryOp(
        10, 20, 30, initialLsn, 1, (short) 5, 1000L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2RemoveLeafEntryOp);
    var result = (BTreeMVBucketV2RemoveLeafEntryOp) deserialized;
    Assert.assertEquals(1, result.getEntryIndex());
    Assert.assertEquals((short) 5, result.getCollectionId());
    Assert.assertEquals(1000L, result.getCollectionPosition());
  }

  // ---------- Redo correctness tests ----------

  /**
   * createMainLeafEntry redo: insert a single leaf entry, compare byte-level.
   */
  @Test
  public void testCreateMainLeafEntryRedoCorrectness() {
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
      var rid = new RecordId(5, 100);

      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, rid, 1L);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeMVBucketV2CreateMainLeafEntryOp(
          0, 0, 0, lsn, 0, key, (short) 5, 100L, 1L).redo(page2);

      Assert.assertEquals(1, page1.size());
      Assert.assertEquals(1, page2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * removeMainLeafEntry redo: add then remove, compare byte-level.
   */
  @Test
  public void testRemoveMainLeafEntryRedoCorrectness() {
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
      var rid = new RecordId(5, 100);

      // Setup both pages identically
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, rid, 1L);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);
      page2.createMainLeafEntry(0, key, rid, 1L);

      // Apply removeMainLeafEntry on page1 directly
      page1.removeMainLeafEntry(0, key.length);

      // Apply via redo on page2
      new BTreeMVBucketV2RemoveMainLeafEntryOp(0, 0, 0, lsn, 0, key.length).redo(page2);

      Assert.assertEquals(0, page1.size());
      Assert.assertEquals(0, page2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * appendNewLeafEntry redo: add main entry + append second value, compare byte-level.
   */
  @Test
  public void testAppendNewLeafEntryRedoCorrectness() {
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
      var rid1 = new RecordId(5, 100);
      var rid2 = new RecordId(6, 200);

      // Setup both pages: init + createMainLeafEntry
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, rid1, 1L);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);
      page2.createMainLeafEntry(0, key, rid1, 1L);

      // Append second value on page1
      var result1 = page1.appendNewLeafEntry(0, rid2);
      Assert.assertEquals(-1, result1);

      // Apply via redo on page2
      new BTreeMVBucketV2AppendNewLeafEntryOp(
          0, 0, 0, lsn, 0, (short) 6, 200L).redo(page2);

      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * removeLeafEntry redo: add main entry with one value, then remove that value.
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
      var key = new byte[] {1, 2, 3, 4};
      var rid = new RecordId(5, 100);

      // Setup both pages: init + createMainLeafEntry
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, rid, 1L);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);
      page2.createMainLeafEntry(0, key, rid, 1L);

      // Remove the value on page1 (single element)
      var result1 = page1.removeLeafEntry(0, rid);
      Assert.assertEquals(0, result1); // entriesCount was 1, now 0

      // Apply via redo on page2
      new BTreeMVBucketV2RemoveLeafEntryOp(
          0, 0, 0, lsn, 0, (short) 5, 100L).redo(page2);

      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * addNonLeafEntry redo: add a single non-leaf entry, compare byte-level.
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
      var key = new byte[] {1, 2, 3, 4};

      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, key, 1, 2, false);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, false).redo(page2);
      new BTreeMVBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 0, key, 1, 2, false).redo(page2);

      Assert.assertEquals(1, page1.size());
      Assert.assertEquals(1, page2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * removeNonLeafEntry redo: add then remove a non-leaf entry, compare byte-level.
   */
  @Test
  public void testRemoveNonLeafEntryRedoCorrectness() {
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

      // Setup both pages identically
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, key, 1, 2, false);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(false);
      page2.addNonLeafEntry(0, key, 1, 2, false);

      // Remove on page1
      page1.removeNonLeafEntry(0, key, -1);

      // Apply via redo on page2
      new BTreeMVBucketV2RemoveNonLeafEntryOp(0, 0, 0, lsn, 0, key, -1).redo(page2);

      Assert.assertEquals(0, page1.size());
      Assert.assertEquals(0, page2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * createMainLeafEntry redo with null value (collectionId=-1): verifies the null-value code
   * path in redo produces byte-identical page state.
   */
  @Test
  public void testCreateMainLeafEntryNullValueRedoCorrectness() {
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

      // Direct mutation with null value
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, null, 99L);

      // Redo with null value encoding (collectionId=-1, collectionPosition=-1)
      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeMVBucketV2CreateMainLeafEntryOp(
          0, 0, 0, lsn, 0, key, (short) -1, -1L, 99L).redo(page2);

      Assert.assertEquals(1, page1.size());
      Assert.assertEquals(1, page2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * removeLeafEntry redo with linked-list values: add a main entry with two values, then remove
   * the second value from the linked list. Exercises the multi-value linked-list removal path.
   */
  @Test
  public void testRemoveLeafEntryFromLinkedListRedoCorrectness() {
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
      var rid1 = new RecordId(5, 100);
      var rid2 = new RecordId(6, 200);

      // Setup both pages: init + createMainLeafEntry + appendNewLeafEntry
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.createMainLeafEntry(0, key, rid1, 1L);
      page1.appendNewLeafEntry(0, rid2);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);
      page2.createMainLeafEntry(0, key, rid1, 1L);
      page2.appendNewLeafEntry(0, rid2);

      // Remove the second value from the linked list on page1
      var result1 = page1.removeLeafEntry(0, rid2);
      Assert.assertTrue(result1 >= 0);

      // Apply via redo on page2
      new BTreeMVBucketV2RemoveLeafEntryOp(
          0, 0, 0, lsn, 0, (short) 6, 200L).redo(page2);

      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * addNonLeafEntry redo with updateNeighbors=true: add three entries with neighbor updates,
   * verifying the neighbor child-pointer adjustments are reproduced by redo.
   */
  @Test
  public void testAddNonLeafEntryWithUpdateNeighborsRedoCorrectness() {
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
      var key1 = new byte[] {1, 2, 3, 4};
      var key2 = new byte[] {5, 6, 7, 8};
      var key3 = new byte[] {3, 4, 5, 6};

      // Direct mutation: init non-leaf, add 2 entries without neighbors, then insert between
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, key1, 10, 20, false);
      page1.addNonLeafEntry(1, key2, 30, 40, false);
      page1.addNonLeafEntry(1, key3, 50, 60, true);

      // Redo
      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, false).redo(page2);
      new BTreeMVBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 0, key1, 10, 20, false).redo(page2);
      new BTreeMVBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 1, key2, 30, 40, false).redo(page2);
      new BTreeMVBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 1, key3, 50, 60, true).redo(page2);

      Assert.assertEquals(3, page1.size());
      Assert.assertEquals(3, page2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * removeNonLeafEntry redo with prevChild >= 0: exercises the neighbor child-pointer restoration
   * path during removal.
   */
  @Test
  public void testRemoveNonLeafEntryWithPrevChildRedoCorrectness() {
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
      var key1 = new byte[] {1, 2, 3, 4};
      var key2 = new byte[] {5, 6, 7, 8};
      var key3 = new byte[] {9, 10, 11, 12};

      // Setup both pages: init non-leaf + 3 entries with neighbor updates
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);
      page1.addNonLeafEntry(0, key1, 10, 20, false);
      page1.addNonLeafEntry(1, key2, 30, 40, true);
      page1.addNonLeafEntry(2, key3, 50, 60, true);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(false);
      page2.addNonLeafEntry(0, key1, 10, 20, false);
      page2.addNonLeafEntry(1, key2, 30, 40, true);
      page2.addNonLeafEntry(2, key3, 50, 60, true);

      // Remove middle entry with prevChild restoration on page1
      page1.removeNonLeafEntry(1, key2, 5);

      // Apply via redo on page2
      new BTreeMVBucketV2RemoveNonLeafEntryOp(0, 0, 0, lsn, 1, key2, 5).redo(page2);

      Assert.assertEquals(2, page1.size());
      Assert.assertEquals(2, page2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  // ---------- Conditional registration tests ----------

  /**
   * createMainLeafEntry registers op only on success (not when page is full).
   */
  @Test
  public void testCreateMainLeafEntryRegistersOnSuccess() {
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

      var result = page.createMainLeafEntry(0, new byte[] {1, 2, 3, 4},
          new RecordId(5, 100), 1L);
      Assert.assertTrue(result); // success

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      Assert.assertTrue(
          opCaptor.getValue() instanceof BTreeMVBucketV2CreateMainLeafEntryOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * removeLeafEntry does NOT register when value is not found (returns -1).
   */
  @Test
  public void testRemoveLeafEntryDoesNotRegisterOnNotFound() {
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
      page.createMainLeafEntry(0, new byte[] {1, 2, 3, 4}, new RecordId(5, 100), 1L);
      org.mockito.Mockito.reset(atomicOp);

      // Try to remove a value that doesn't exist
      var result = page.removeLeafEntry(0, new RecordId(99, 999));
      Assert.assertEquals(-1, result);

      // Should NOT have registered any op
      verify(atomicOp, never()).registerPageOperation(
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.any());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * removeMainLeafEntry unconditionally registers.
   */
  @Test
  public void testRemoveMainLeafEntryRegistersUnconditionally() {
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
      page.createMainLeafEntry(0, new byte[] {1, 2, 3, 4}, new RecordId(5, 100), 1L);
      org.mockito.Mockito.reset(atomicOp);

      page.removeMainLeafEntry(0, 4);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      Assert.assertTrue(
          opCaptor.getValue() instanceof BTreeMVBucketV2RemoveMainLeafEntryOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * appendNewLeafEntry registers on success path (returns -1), but NOT on threshold-exceeded
   * path (returns mId > 0).
   */
  @Test
  public void testAppendNewLeafEntryRegistersOnSuccessNotOnThreshold() {
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
      page.createMainLeafEntry(0, new byte[] {1, 2, 3, 4}, new RecordId(5, 100), 1L);

      // Success path: append second value, should register
      org.mockito.Mockito.reset(atomicOp);
      var result = page.appendNewLeafEntry(0, new RecordId(6, 200));
      Assert.assertEquals(-1, result);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());
      Assert.assertTrue(
          opCaptor.getValue() instanceof BTreeMVBucketV2AppendNewLeafEntryOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * removeLeafEntry registers when value is found (result >= 0).
   */
  @Test
  public void testRemoveLeafEntryRegistersOnSuccess() {
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
      page.createMainLeafEntry(0, new byte[] {1, 2, 3, 4}, new RecordId(5, 100), 1L);
      org.mockito.Mockito.reset(atomicOp);

      var result = page.removeLeafEntry(0, new RecordId(5, 100));
      Assert.assertTrue(result >= 0);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());
      Assert.assertTrue(
          opCaptor.getValue() instanceof BTreeMVBucketV2RemoveLeafEntryOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression test ----------

  /**
   * Verifies D4 redo suppression for all 6 entry operations.
   */
  @Test
  public void testRedoSuppressionNoRegistration() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);
      var key = new byte[] {1, 2, 3, 4};

      // Test leaf ops
      var page = new CellBTreeMultiValueV2Bucket<>(entry);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, true).redo(page);
      new BTreeMVBucketV2CreateMainLeafEntryOp(
          0, 0, 0, lsn, 0, key, (short) 5, 100L, 1L).redo(page);
      new BTreeMVBucketV2AppendNewLeafEntryOp(
          0, 0, 0, lsn, 0, (short) 6, 200L).redo(page);
      new BTreeMVBucketV2RemoveLeafEntryOp(
          0, 0, 0, lsn, 0, (short) 6, 200L).redo(page);
      new BTreeMVBucketV2RemoveMainLeafEntryOp(0, 0, 0, lsn, 0, key.length).redo(page);

      Assert.assertEquals(0, page.size());

      // No exception means redo suppression works (plain CacheEntryImpl, not CacheEntryChanges)
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * Verifies D4 redo suppression for non-leaf entry operations.
   */
  @Test
  public void testRedoSuppressionNonLeafOps() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);
      var key = new byte[] {1, 2, 3, 4};

      var page = new CellBTreeMultiValueV2Bucket<>(entry);
      new BTreeMVBucketV2InitOp(0, 0, 0, lsn, false).redo(page);
      new BTreeMVBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 0, key, 1, 2, false).redo(page);
      new BTreeMVBucketV2RemoveNonLeafEntryOp(0, 0, 0, lsn, 0, key, -1).redo(page);

      Assert.assertEquals(0, page.size());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Equals/HashCode tests ----------

  @Test
  public void testCreateMainLeafEntryOpEquality() {
    var lsn = new LogSequenceNumber(1, 100);
    var key = new byte[] {1, 2, 3};
    var op1 = new BTreeMVBucketV2CreateMainLeafEntryOp(
        10, 20, 30, lsn, 0, key, (short) 5, 100L, 42L);
    var op2 = new BTreeMVBucketV2CreateMainLeafEntryOp(
        10, 20, 30, lsn, 0, key, (short) 5, 100L, 42L);
    var op3 = new BTreeMVBucketV2CreateMainLeafEntryOp(
        10, 20, 30, lsn, 0, key, (short) 5, 100L, 99L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testAddNonLeafEntryOpEquality() {
    var lsn = new LogSequenceNumber(2, 200);
    var key = new byte[] {4, 5, 6};
    var op1 = new BTreeMVBucketV2AddNonLeafEntryOp(1, 2, 3, lsn, 0, key, 1, 2, true);
    var op2 = new BTreeMVBucketV2AddNonLeafEntryOp(1, 2, 3, lsn, 0, key, 1, 2, true);
    var op3 = new BTreeMVBucketV2AddNonLeafEntryOp(1, 2, 3, lsn, 0, key, 1, 2, false);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }
}
