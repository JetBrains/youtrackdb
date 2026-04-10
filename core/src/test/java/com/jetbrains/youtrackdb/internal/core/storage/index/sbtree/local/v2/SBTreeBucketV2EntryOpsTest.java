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
 * Tests for SBTreeBucketV2 entry/update PageOperation subclasses (addLeafEntry, addNonLeafEntry,
 * removeLeafEntry, removeNonLeafEntry, updateValue): record IDs, serialization roundtrips,
 * factory roundtrips, redo correctness, conditional registration, redo suppression,
 * equals/hashCode.
 */
public class SBTreeBucketV2EntryOpsTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Record ID verification ----

  @Test
  public void testAddLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_ADD_LEAF_ENTRY_OP,
        SBTreeBucketV2AddLeafEntryOp.RECORD_ID);
    Assert.assertEquals(272, SBTreeBucketV2AddLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testAddNonLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP,
        SBTreeBucketV2AddNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(273, SBTreeBucketV2AddNonLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testRemoveLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_REMOVE_LEAF_ENTRY_OP,
        SBTreeBucketV2RemoveLeafEntryOp.RECORD_ID);
    Assert.assertEquals(274, SBTreeBucketV2RemoveLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testRemoveNonLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_REMOVE_NON_LEAF_ENTRY_OP,
        SBTreeBucketV2RemoveNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(275, SBTreeBucketV2RemoveNonLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testUpdateValueOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_UPDATE_VALUE_OP,
        SBTreeBucketV2UpdateValueOp.RECORD_ID);
    Assert.assertEquals(276, SBTreeBucketV2UpdateValueOp.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testAddLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(5, 200);
    byte[] key = {1, 2, 3};
    byte[] value = {4, 5, 6, 7};
    var original = new SBTreeBucketV2AddLeafEntryOp(10, 20, 30, lsn, 5, key, value);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2AddLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(5, deserialized.getIndex());
    Assert.assertArrayEquals(key, deserialized.getSerializedKey());
    Assert.assertArrayEquals(value, deserialized.getSerializedValue());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddNonLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(3, 100);
    byte[] key = {10, 20};
    var original = new SBTreeBucketV2AddNonLeafEntryOp(
        10, 20, 30, lsn, 2, key, 100L, 200L, true);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2AddNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getIndex());
    Assert.assertArrayEquals(key, deserialized.getKey());
    Assert.assertEquals(100L, deserialized.getLeftChild());
    Assert.assertEquals(200L, deserialized.getRightChild());
    Assert.assertTrue(deserialized.isUpdateNeighbours());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddNonLeafEntryOpSerializationRoundtripUpdateNeighboursFalse() {
    var lsn = new LogSequenceNumber(3, 100);
    byte[] key = {10, 20};
    var original = new SBTreeBucketV2AddNonLeafEntryOp(
        10, 20, 30, lsn, 2, key, 100L, 200L, false);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new SBTreeBucketV2AddNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertFalse(deserialized.isUpdateNeighbours());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(7, 300);
    byte[] key = {1, 2};
    byte[] value = {3, 4};
    var original = new SBTreeBucketV2RemoveLeafEntryOp(10, 20, 30, lsn, 0, key, value);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2RemoveLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(0, deserialized.getEntryIndex());
    Assert.assertArrayEquals(key, deserialized.getOldRawKey());
    Assert.assertArrayEquals(value, deserialized.getOldRawValue());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveNonLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(2, 50);
    byte[] key = {5, 6, 7};
    var original = new SBTreeBucketV2RemoveNonLeafEntryOp(10, 20, 30, lsn, 1, key, 42);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2RemoveNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(1, deserialized.getEntryIndex());
    Assert.assertArrayEquals(key, deserialized.getKey());
    Assert.assertEquals(42, deserialized.getPrevChild());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveNonLeafEntryOpSerializationRoundtripNegativePrevChild() {
    // T3: prevChild=-1 means "no prev child"
    var lsn = new LogSequenceNumber(2, 50);
    var original = new SBTreeBucketV2RemoveNonLeafEntryOp(
        10, 20, 30, lsn, 0, new byte[] {1}, -1);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new SBTreeBucketV2RemoveNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(-1, deserialized.getPrevChild());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testUpdateValueOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(1, 42);
    byte[] value = {10, 20, 30};
    var original = new SBTreeBucketV2UpdateValueOp(10, 20, 30, lsn, 3, value, 8);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2UpdateValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(3, deserialized.getIndex());
    Assert.assertArrayEquals(value, deserialized.getValue());
    Assert.assertEquals(8, deserialized.getKeySize());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testAddLeafEntryOpFactoryRoundtrip() {
    byte[] key = {1, 2, 3};
    byte[] value = {4, 5};
    var original = new SBTreeBucketV2AddLeafEntryOp(
        10, 20, 30, new LogSequenceNumber(42, 1024), 0, key, value);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2AddLeafEntryOp);
    var result = (SBTreeBucketV2AddLeafEntryOp) deserialized;
    Assert.assertEquals(0, result.getIndex());
    Assert.assertArrayEquals(key, result.getSerializedKey());
    Assert.assertArrayEquals(value, result.getSerializedValue());
  }

  @Test
  public void testAddNonLeafEntryOpFactoryRoundtrip() {
    byte[] key = {10};
    var original = new SBTreeBucketV2AddNonLeafEntryOp(
        10, 20, 30, new LogSequenceNumber(42, 1024), 1, key,
        Long.MAX_VALUE, Long.MIN_VALUE, true);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2AddNonLeafEntryOp);
    var result = (SBTreeBucketV2AddNonLeafEntryOp) deserialized;
    Assert.assertEquals(Long.MAX_VALUE, result.getLeftChild());
    Assert.assertEquals(Long.MIN_VALUE, result.getRightChild());
    Assert.assertTrue(result.isUpdateNeighbours());
  }

  @Test
  public void testUpdateValueOpFactoryRoundtrip() {
    byte[] value = {99};
    var original = new SBTreeBucketV2UpdateValueOp(
        10, 20, 30, new LogSequenceNumber(42, 1024), 0, value, 4);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2UpdateValueOp);
    var result = (SBTreeBucketV2UpdateValueOp) deserialized;
    Assert.assertArrayEquals(value, result.getValue());
    Assert.assertEquals(4, result.getKeySize());
  }

  @Test
  public void testRemoveLeafEntryOpFactoryRoundtrip() {
    byte[] key = {1, 2};
    byte[] value = {3, 4};
    var original = new SBTreeBucketV2RemoveLeafEntryOp(
        10, 20, 30, new LogSequenceNumber(42, 1024), 5, key, value);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2RemoveLeafEntryOp);
    var result = (SBTreeBucketV2RemoveLeafEntryOp) deserialized;
    Assert.assertEquals(5, result.getEntryIndex());
    Assert.assertArrayEquals(key, result.getOldRawKey());
    Assert.assertArrayEquals(value, result.getOldRawValue());
  }

  @Test
  public void testRemoveNonLeafEntryOpFactoryRoundtrip() {
    byte[] key = {5, 6, 7};
    var original = new SBTreeBucketV2RemoveNonLeafEntryOp(
        10, 20, 30, new LogSequenceNumber(42, 1024), 2, key, 42);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2RemoveNonLeafEntryOp);
    var result = (SBTreeBucketV2RemoveNonLeafEntryOp) deserialized;
    Assert.assertEquals(2, result.getEntryIndex());
    Assert.assertArrayEquals(key, result.getKey());
    Assert.assertEquals(42, result.getPrevChild());
  }

  // ---- Redo correctness (byte-level) ----

  /**
   * addLeafEntry + removeLeafEntry: apply directly on page1, redo on page2. Byte-level.
   */
  @Test
  public void testAddAndRemoveLeafEntryRedoCorrectness() {
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
      // Init both as leaf
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(true);
      var page2 = new SBTreeBucketV2<>(entry2);
      page2.init(true);

      byte[] key = {1, 2, 3, 4};
      byte[] value = {5, 6, 7, 8};
      var lsn = new LogSequenceNumber(0, 0);

      // Add leaf entry on both
      page1.addLeafEntry(0, key, value);
      new SBTreeBucketV2AddLeafEntryOp(0, 0, 0, lsn, 0, key, value).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(1, page2.size());

      // Remove leaf entry on both
      page1.removeLeafEntry(0, key, value);
      new SBTreeBucketV2RemoveLeafEntryOp(0, 0, 0, lsn, 0, key, value).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(0, page2.size());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * addNonLeafEntry + removeNonLeafEntry with prevChild: apply directly vs redo. Byte-level.
   */
  @Test
  public void testAddAndRemoveNonLeafEntryRedoCorrectness() {
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
      page2.init(false);

      byte[] key = {10, 20, 30, 40};
      var lsn = new LogSequenceNumber(0, 0);

      // Add non-leaf entry
      page1.addNonLeafEntry(0, key, 100L, 200L, false);
      new SBTreeBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 0, key, 100L, 200L, false).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(1, page2.size());

      // Remove non-leaf entry with prevChild=-1
      page1.removeNonLeafEntry(0, key, -1);
      new SBTreeBucketV2RemoveNonLeafEntryOp(0, 0, 0, lsn, 0, key, -1).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(0, page2.size());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * updateValue: add a leaf entry, then update its value. Byte-level comparison.
   */
  @Test
  public void testUpdateValueRedoCorrectness() {
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

      byte[] key = {1, 2, 3, 4};
      byte[] oldValue = {5, 6, 7, 8};

      // Add a leaf entry on both
      page1.addLeafEntry(0, key, oldValue);
      page2.addLeafEntry(0, key, oldValue);

      // Update value on both
      byte[] newValue = {9, 10, 11, 12};
      var lsn = new LogSequenceNumber(0, 0);

      page1.updateValue(0, newValue, key.length);
      new SBTreeBucketV2UpdateValueOp(0, 0, 0, lsn, 0, newValue, key.length).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * addNonLeafEntry with updateNeighbours=true: add 3 entries then verify redo. Byte-level.
   */
  @Test
  public void testAddNonLeafWithNeighborUpdateRedoCorrectness() {
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
      page2.init(false);

      byte[] key1 = {1, 2, 3, 4};
      byte[] key2 = {5, 6, 7, 8};
      byte[] key3 = {3, 4, 5, 6};
      var lsn = new LogSequenceNumber(0, 0);

      // Add first entry — no neighbor update needed (size == 1)
      page1.addNonLeafEntry(0, key1, 10L, 20L, false);
      new SBTreeBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 0, key1, 10L, 20L, false).redo(page2);

      // Add second entry — with neighbor update
      page1.addNonLeafEntry(1, key2, 20L, 30L, true);
      new SBTreeBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 1, key2, 20L, 30L, true).redo(page2);

      // Add third entry in the middle — with neighbor update
      page1.addNonLeafEntry(1, key3, 20L, 25L, true);
      new SBTreeBucketV2AddNonLeafEntryOp(
          0, 0, 0, lsn, 1, key3, 20L, 25L, true).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(3, page2.size());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * removeNonLeafEntry with prevChild >= 0: exercises the neighbor pointer update path.
   * Add 3 non-leaf entries, then remove the middle one with a positive prevChild.
   */
  @Test
  public void testRemoveNonLeafEntryWithPositivePrevChildRedoCorrectness() {
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
      page2.init(false);

      byte[] key1 = {1, 2, 3, 4};
      byte[] key2 = {5, 6, 7, 8};
      byte[] key3 = {9, 10, 11, 12};
      var lsn = new LogSequenceNumber(0, 0);

      // Add 3 entries to both pages
      page1.addNonLeafEntry(0, key1, 10L, 20L, false);
      page1.addNonLeafEntry(1, key2, 20L, 30L, true);
      page1.addNonLeafEntry(2, key3, 30L, 40L, true);

      page2.addNonLeafEntry(0, key1, 10L, 20L, false);
      page2.addNonLeafEntry(1, key2, 20L, 30L, true);
      page2.addNonLeafEntry(2, key3, 30L, 40L, true);

      // Remove middle entry with positive prevChild — triggers neighbor update path
      page1.removeNonLeafEntry(1, key2, 20);
      new SBTreeBucketV2RemoveNonLeafEntryOp(
          0, 0, 0, lsn, 1, key2, 20).redo(page2);

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(2, page2.size());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  // ---- Redo suppression ----

  @Test
  public void testRedoSuppression_addLeafEntryDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      var bucket = new SBTreeBucketV2<>(entry);
      bucket.init(true);
      Assert.assertTrue(bucket.addLeafEntry(0, new byte[] {1, 2}, new byte[] {3, 4}));
      Assert.assertEquals(1, bucket.size());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  // ---- Equals/hashCode ----

  @Test
  public void testAddLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] key = {1, 2};
    byte[] value = {3, 4};

    var op1 = new SBTreeBucketV2AddLeafEntryOp(10, 20, 30, lsn, 0, key, value);
    var op2 = new SBTreeBucketV2AddLeafEntryOp(10, 20, 30, lsn, 0, key, value);
    var op3 = new SBTreeBucketV2AddLeafEntryOp(10, 20, 30, lsn, 1, key, value);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testRemoveNonLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] key = {5, 6};

    var op1 = new SBTreeBucketV2RemoveNonLeafEntryOp(10, 20, 30, lsn, 0, key, 42);
    var op2 = new SBTreeBucketV2RemoveNonLeafEntryOp(10, 20, 30, lsn, 0, key, 42);
    var op3 = new SBTreeBucketV2RemoveNonLeafEntryOp(10, 20, 30, lsn, 0, key, -1);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testUpdateValueOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] value = {10, 20};

    var op1 = new SBTreeBucketV2UpdateValueOp(10, 20, 30, lsn, 0, value, 4);
    var op2 = new SBTreeBucketV2UpdateValueOp(10, 20, 30, lsn, 0, value, 4);
    var op3 = new SBTreeBucketV2UpdateValueOp(10, 20, 30, lsn, 0, value, 8);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testAddNonLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] key = {1, 2};

    var op1 = new SBTreeBucketV2AddNonLeafEntryOp(10, 20, 30, lsn, 0, key, 5L, 6L, true);
    var op2 = new SBTreeBucketV2AddNonLeafEntryOp(10, 20, 30, lsn, 0, key, 5L, 6L, true);
    var op3 = new SBTreeBucketV2AddNonLeafEntryOp(10, 20, 30, lsn, 0, key, 5L, 6L, false);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testRemoveLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] key = {1, 2};
    byte[] value = {3, 4};

    var op1 = new SBTreeBucketV2RemoveLeafEntryOp(10, 20, 30, lsn, 0, key, value);
    var op2 = new SBTreeBucketV2RemoveLeafEntryOp(10, 20, 30, lsn, 0, key, value);
    var op3 = new SBTreeBucketV2RemoveLeafEntryOp(10, 20, 30, lsn, 1, key, value);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }
}
