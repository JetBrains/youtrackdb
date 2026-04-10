package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperationRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for SBTreeBucketV2 bulk PageOperation subclasses (addAll, shrink): record IDs,
 * serialization roundtrips, factory roundtrips, redo correctness (leaf/non-leaf, empty list,
 * split scenario), registration, redo suppression, equals/hashCode.
 */
public class SBTreeBucketV2BulkOpsTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Record ID verification ----

  @Test
  public void testAddAllOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_ADD_ALL_OP,
        SBTreeBucketV2AddAllOp.RECORD_ID);
    Assert.assertEquals(277, SBTreeBucketV2AddAllOp.RECORD_ID);
  }

  @Test
  public void testShrinkOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_BUCKET_V2_SHRINK_OP,
        SBTreeBucketV2ShrinkOp.RECORD_ID);
    Assert.assertEquals(278, SBTreeBucketV2ShrinkOp.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testAddAllOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(5, 200);
    // Leaf entry format for SBTreeBucketV2: key(4) + keyFlag(1) + value(8) = 13 bytes
    var entries = List.of(
        new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80},
        new byte[] {2, 0, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81});
    var original = new SBTreeBucketV2AddAllOp(10, 20, 30, lsn, new ArrayList<>(entries));

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2AddAllOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(2, deserialized.getRawEntries().size());
    Assert.assertArrayEquals(entries.get(0), deserialized.getRawEntries().get(0));
    Assert.assertArrayEquals(entries.get(1), deserialized.getRawEntries().get(1));
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddAllOpSerializationRoundtripEmptyList() {
    var lsn = new LogSequenceNumber(3, 100);
    var original = new SBTreeBucketV2AddAllOp(7, 14, 21, lsn, new ArrayList<>());

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new SBTreeBucketV2AddAllOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(0, deserialized.getRawEntries().size());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testShrinkOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(8, 512);
    var entries = List.of(
        new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80},
        new byte[] {2, 0, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81},
        new byte[] {3, 0, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82});
    var original = new SBTreeBucketV2ShrinkOp(15, 25, 35, lsn, new ArrayList<>(entries));

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeBucketV2ShrinkOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(3, deserialized.getRetainedEntries().size());
    for (var i = 0; i < 3; i++) {
      Assert.assertArrayEquals(entries.get(i), deserialized.getRetainedEntries().get(i));
    }
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testShrinkOpSerializationRoundtripEmptyList() {
    var lsn = new LogSequenceNumber(3, 100);
    var original = new SBTreeBucketV2ShrinkOp(7, 14, 21, lsn, new ArrayList<>());

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new SBTreeBucketV2ShrinkOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(0, deserialized.getRetainedEntries().size());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testAddAllOpFactoryRoundtrip() {
    var lsn = new LogSequenceNumber(42, 1024);
    var entries = List.of(new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80});
    var original = new SBTreeBucketV2AddAllOp(10, 20, 30, lsn, new ArrayList<>(entries));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2AddAllOp);
    var result = (SBTreeBucketV2AddAllOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(1, result.getRawEntries().size());
    Assert.assertArrayEquals(entries.get(0), result.getRawEntries().get(0));
  }

  @Test
  public void testShrinkOpFactoryRoundtrip() {
    var lsn = new LogSequenceNumber(55, 550);
    var entries = List.of(
        new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80},
        new byte[] {2, 0, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81});
    var original = new SBTreeBucketV2ShrinkOp(4, 8, 12, lsn, new ArrayList<>(entries));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeBucketV2ShrinkOp);
    var result = (SBTreeBucketV2ShrinkOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(2, result.getRetainedEntries().size());
    Assert.assertArrayEquals(entries.get(0), result.getRetainedEntries().get(0));
    Assert.assertArrayEquals(entries.get(1), result.getRetainedEntries().get(1));
  }

  // ---- Redo correctness: addAll leaf ----

  /**
   * addAll on an empty leaf bucket: direct path vs redo path, byte-level comparison.
   */
  @Test
  public void testAddAllEmptyLeafBucketRedoCorrectness() {
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
      // SBTreeBucketV2 leaf entry: key(4 bytes) + keyFlag(1 byte) + value(8 bytes) = 13 bytes
      var e0 = new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80};
      var e1 = new byte[] {2, 0, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81};
      var e2 = new byte[] {3, 0, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82};
      var entries = List.of(e0, e1, e2);

      // Direct path
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(true);
      page1.addAll(new ArrayList<>(entries), null, null);

      // Redo path
      var page2 = new SBTreeBucketV2<>(entry2);
      new SBTreeBucketV2InitOp(0, 0, 0, lsn, true).redo(page2);
      new SBTreeBucketV2AddAllOp(0, 0, 0, lsn, new ArrayList<>(entries)).redo(page2);

      Assert.assertEquals(3, page1.size());
      Assert.assertEquals(3, page2.size());

      Assert.assertEquals(
          "Page buffers must be identical after addAll redo on empty leaf bucket",
          0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * addAll on a partially-filled leaf bucket: verifies currentSize offset is correct.
   */
  @Test
  public void testAddAllPartiallyFilledLeafBucketRedoCorrectness() {
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
      var existingEntry = new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80};
      var newEntry1 = new byte[] {2, 0, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81};
      var newEntry2 = new byte[] {3, 0, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82};
      var newEntries = List.of(newEntry1, newEntry2);

      // Setup both pages with one existing entry
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(true);
      page1.addAll(List.of(existingEntry), null, null);

      var page2 = new SBTreeBucketV2<>(entry2);
      page2.init(true);
      page2.addAll(List.of(existingEntry), null, null);

      // Direct path: addAll with 2 more entries
      page1.addAll(new ArrayList<>(newEntries), null, null);

      // Redo path
      new SBTreeBucketV2AddAllOp(0, 0, 0, lsn, new ArrayList<>(newEntries)).redo(page2);

      Assert.assertEquals(3, page1.size());
      Assert.assertEquals(3, page2.size());

      Assert.assertEquals(
          "Page buffers must be identical after addAll redo on partially-filled bucket",
          0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  // ---- Redo correctness: addAll non-leaf ----

  /**
   * addAll on a non-leaf bucket: non-leaf entries have leftChild(8)+rightChild(8)+key(4)=20 bytes.
   */
  @Test
  public void testAddAllNonLeafBucketRedoCorrectness() {
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
      // SBTreeBucketV2 non-leaf: leftChild(8) + rightChild(8) + key(4) = 20 bytes
      var e0 = new byte[] {
          0, 0, 0, 0, 0, 0, 0, 1, // leftChild = 1
          0, 0, 0, 0, 0, 0, 0, 2, // rightChild = 2
          10, 0, 0, 0}; // key
      var e1 = new byte[] {
          0, 0, 0, 0, 0, 0, 0, 2, // leftChild = 2
          0, 0, 0, 0, 0, 0, 0, 3, // rightChild = 3
          20, 0, 0, 0}; // key
      var entries = List.of(e0, e1);

      // Direct path
      var page1 = new SBTreeBucketV2<>(entry1);
      page1.init(false);
      page1.addAll(new ArrayList<>(entries), null, null);

      // Redo path
      var page2 = new SBTreeBucketV2<>(entry2);
      new SBTreeBucketV2InitOp(0, 0, 0, lsn, false).redo(page2);
      new SBTreeBucketV2AddAllOp(0, 0, 0, lsn, new ArrayList<>(entries)).redo(page2);

      Assert.assertEquals(2, page2.size());
      Assert.assertFalse(page2.isLeaf());

      Assert.assertEquals(
          "Page buffers must be identical after addAll redo on non-leaf bucket",
          0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  // ---- Redo correctness: shrink ----

  /**
   * shrink to half using actual production shrink() method with IntegerSerializer: verifies
   * that the redo path (via resetAndAddAll) produces byte-identical state.
   */
  @Test
  public void testShrinkToHalfRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);
    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());

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
      // 4-byte int keys + 1-byte keyFlag + 8-byte value = 13 bytes per leaf entry
      var key1 = new byte[4];
      var key2 = new byte[4];
      var key3 = new byte[4];
      var key4 = new byte[4];
      IntegerSerializer.INSTANCE.serializeNativeObject(1, serializerFactory, key1, 0);
      IntegerSerializer.INSTANCE.serializeNativeObject(2, serializerFactory, key2, 0);
      IntegerSerializer.INSTANCE.serializeNativeObject(3, serializerFactory, key3, 0);
      IntegerSerializer.INSTANCE.serializeNativeObject(4, serializerFactory, key4, 0);
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80};

      // Setup both pages with 4 leaf entries using addLeafEntry
      var page1 = new SBTreeBucketV2<Integer, Long>(entry1);
      page1.init(true);
      page1.addLeafEntry(0, key1, value);
      page1.addLeafEntry(1, key2, value);
      page1.addLeafEntry(2, key3, value);
      page1.addLeafEntry(3, key4, value);

      var page2 = new SBTreeBucketV2<Integer, Long>(entry2);
      page2.init(true);
      page2.addLeafEntry(0, key1, value);
      page2.addLeafEntry(1, key2, value);
      page2.addLeafEntry(2, key3, value);
      page2.addLeafEntry(3, key4, value);

      Assert.assertEquals(4, page1.size());

      // Capture retained entries before shrink for the redo side
      var retained = new ArrayList<byte[]>();
      retained.add(page1.getRawEntry(
          0, IntegerSerializer.INSTANCE, LongSerializer.INSTANCE, serializerFactory));
      retained.add(page1.getRawEntry(
          1, IntegerSerializer.INSTANCE, LongSerializer.INSTANCE, serializerFactory));

      // Direct path: production shrink()
      page1.shrink(2, IntegerSerializer.INSTANCE, LongSerializer.INSTANCE, serializerFactory);

      // Redo path: replay via ShrinkOp
      new SBTreeBucketV2ShrinkOp(0, 0, 0, lsn, retained).redo(page2);

      Assert.assertEquals(2, page1.size());
      Assert.assertEquals(2, page2.size());

      Assert.assertEquals(
          "Page buffers must be identical after shrink redo",
          0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * shrink to 0: root bucket split scenario where all entries are moved to a new bucket.
   */
  @Test
  public void testShrinkToZeroRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);
    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());

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
      var key1 = new byte[4];
      var key2 = new byte[4];
      IntegerSerializer.INSTANCE.serializeNativeObject(1, serializerFactory, key1, 0);
      IntegerSerializer.INSTANCE.serializeNativeObject(2, serializerFactory, key2, 0);
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80};

      // Setup both pages with 2 entries
      var page1 = new SBTreeBucketV2<Integer, Long>(entry1);
      page1.init(true);
      page1.addLeafEntry(0, key1, value);
      page1.addLeafEntry(1, key2, value);

      var page2 = new SBTreeBucketV2<Integer, Long>(entry2);
      page2.init(true);
      page2.addLeafEntry(0, key1, value);
      page2.addLeafEntry(1, key2, value);

      // Direct path: shrink to 0
      page1.shrink(0, IntegerSerializer.INSTANCE, LongSerializer.INSTANCE, serializerFactory);

      // Redo path
      new SBTreeBucketV2ShrinkOp(0, 0, 0, lsn, new ArrayList<>()).redo(page2);

      Assert.assertEquals(0, page1.size());
      Assert.assertEquals(0, page2.size());

      Assert.assertEquals(
          "Page buffers must be identical after shrink-to-zero redo",
          0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Split scenario: init+addAll on new bucket, shrink on old bucket. Verifies both pages.
   */
  @Test
  public void testSplitScenarioRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    // Old bucket (page1/page2)
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

    // New bucket (page3/page4)
    var pointer3 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer3 = new CachePointer(pointer3, bufferPool, 0, 0);
    cachePointer3.incrementReferrer();
    CacheEntry entry3 = new CacheEntryImpl(0, 0, cachePointer3, false, null);
    entry3.acquireExclusiveLock();

    var pointer4 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer4 = new CachePointer(pointer4, bufferPool, 0, 0);
    cachePointer4.incrementReferrer();
    CacheEntry entry4 = new CacheEntryImpl(0, 0, cachePointer4, false, null);
    entry4.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);
      var e0 = new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80};
      var e1 = new byte[] {2, 0, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81};
      var e2 = new byte[] {3, 0, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82};
      var e3 = new byte[] {4, 0, 0, 0, 0, 13, 23, 33, 43, 53, 63, 73, 83};
      var allEntries = List.of(e0, e1, e2, e3);
      var retainedEntries = List.of(e0, e1);
      var movedEntries = List.of(e2, e3);

      // Direct path: old bucket shrink, new bucket init+addAll
      var oldBucket1 = new SBTreeBucketV2<>(entry1);
      oldBucket1.init(true);
      oldBucket1.addAll(new ArrayList<>(allEntries), null, null);
      oldBucket1.resetAndAddAll(new ArrayList<>(retainedEntries));

      var newBucket1 = new SBTreeBucketV2<>(entry3);
      newBucket1.init(true);
      newBucket1.addAll(new ArrayList<>(movedEntries), null, null);

      // Redo path
      var oldBucket2 = new SBTreeBucketV2<>(entry2);
      new SBTreeBucketV2InitOp(0, 0, 0, lsn, true).redo(oldBucket2);
      new SBTreeBucketV2AddAllOp(0, 0, 0, lsn,
          new ArrayList<>(allEntries)).redo(oldBucket2);
      new SBTreeBucketV2ShrinkOp(0, 0, 0, lsn,
          new ArrayList<>(retainedEntries)).redo(oldBucket2);

      var newBucket2 = new SBTreeBucketV2<>(entry4);
      new SBTreeBucketV2InitOp(0, 0, 0, lsn, true).redo(newBucket2);
      new SBTreeBucketV2AddAllOp(0, 0, 0, lsn,
          new ArrayList<>(movedEntries)).redo(newBucket2);

      // Verify old buckets match
      Assert.assertEquals(2, oldBucket1.size());
      Assert.assertEquals(2, oldBucket2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));

      // Verify new buckets match
      Assert.assertEquals(2, newBucket1.size());
      Assert.assertEquals(2, newBucket2.size());
      Assert.assertEquals(0, cachePointer3.getBuffer().compareTo(cachePointer4.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      entry3.releaseExclusiveLock();
      entry4.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
      cachePointer3.decrementReferrer();
      cachePointer4.decrementReferrer();
    }
  }

  // ---- Registration tests ----

  @Test
  public void testAddAllRegistersOp() {
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

      var page = new SBTreeBucketV2<>(changes);
      page.init(true);

      var entries = List.of(new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80});
      page.addAll(new ArrayList<>(entries), null, null);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      // init registers 1 op, addAll registers 1 more
      org.mockito.Mockito.verify(atomicOp, org.mockito.Mockito.atLeastOnce())
          .registerPageOperation(
              org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyLong(),
              captor.capture());

      var allOps = captor.getAllValues();
      var addAllOp = allOps.stream()
          .filter(op -> op instanceof SBTreeBucketV2AddAllOp)
          .findFirst()
          .orElseThrow(() -> new AssertionError("No SBTreeBucketV2AddAllOp registered"));

      Assert.assertEquals(7, addAllOp.getPageIndex());
      Assert.assertEquals(42, addAllOp.getFileId());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testShrinkRegistersOp() {
    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());
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

      var page = new SBTreeBucketV2<Integer, Long>(changes);
      page.init(true);
      var key1 = new byte[4];
      var key2 = new byte[4];
      IntegerSerializer.INSTANCE.serializeNativeObject(1, serializerFactory, key1, 0);
      IntegerSerializer.INSTANCE.serializeNativeObject(2, serializerFactory, key2, 0);
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80};
      page.addLeafEntry(0, key1, value);
      page.addLeafEntry(1, key2, value);

      page.shrink(1, IntegerSerializer.INSTANCE, LongSerializer.INSTANCE, serializerFactory);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      org.mockito.Mockito.verify(atomicOp, org.mockito.Mockito.atLeastOnce())
          .registerPageOperation(
              org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyLong(),
              captor.capture());

      var allOps = captor.getAllValues();
      var shrinkOp = allOps.stream()
          .filter(op -> op instanceof SBTreeBucketV2ShrinkOp)
          .findFirst()
          .orElseThrow(() -> new AssertionError("No SBTreeBucketV2ShrinkOp registered"));

      Assert.assertEquals(1, ((SBTreeBucketV2ShrinkOp) shrinkOp).getRetainedEntries().size());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---- Redo suppression: changes=null means no registration (D4) ----

  @Test
  public void testAddAllRedoSuppression() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    // CacheEntryImpl (not CacheEntryChanges) — changes=null path
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new SBTreeBucketV2<>(entry);
      page.init(true);
      var entries = List.of(new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80});
      // Should not throw or attempt registration since CacheEntryImpl is not CacheEntryChanges
      page.addAll(new ArrayList<>(entries), null, null);
      Assert.assertEquals(1, page.size());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testShrinkRedoSuppression() {
    var bufferPool = ByteBufferPool.instance(null);
    var serializerFactory = BinarySerializerFactory.create(
        BinarySerializerFactory.currentBinaryFormatVersion());

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new SBTreeBucketV2<Integer, Long>(entry);
      page.init(true);
      var key = new byte[4];
      IntegerSerializer.INSTANCE.serializeNativeObject(1, serializerFactory, key, 0);
      var value = new byte[] {10, 20, 30, 40, 50, 60, 70, 80};
      page.addLeafEntry(0, key, value);

      // Should not throw or attempt registration
      page.shrink(0, IntegerSerializer.INSTANCE, LongSerializer.INSTANCE, serializerFactory);
      Assert.assertEquals(0, page.size());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * resetAndAddAll on CacheEntryImpl (not CacheEntryChanges): verifies that the actual
   * method called by ShrinkOp.redo() works without attempting registration (D4 suppression).
   */
  @Test
  public void testResetAndAddAllRedoSuppression() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new SBTreeBucketV2<>(entry);
      page.init(true);
      var entries = List.of(
          new byte[] {1, 0, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80},
          new byte[] {2, 0, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81});
      page.addAll(new ArrayList<>(entries), null, null);
      Assert.assertEquals(2, page.size());

      // resetAndAddAll is the method called by ShrinkOp.redo()
      page.resetAndAddAll(List.of(entries.get(0)));
      Assert.assertEquals(1, page.size());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---- equals/hashCode ----

  @Test
  public void testAddAllOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(5, 200);
    var entries1 = List.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
    var entries2 = List.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
    var entries3 = List.of(new byte[] {1, 2, 3}, new byte[] {7, 8, 9});

    var op1 = new SBTreeBucketV2AddAllOp(10, 20, 30, lsn, new ArrayList<>(entries1));
    var op2 = new SBTreeBucketV2AddAllOp(10, 20, 30, lsn, new ArrayList<>(entries2));
    var op3 = new SBTreeBucketV2AddAllOp(10, 20, 30, lsn, new ArrayList<>(entries3));

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testShrinkOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(5, 200);
    var entries1 = List.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
    var entries2 = List.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
    var entries3 = List.of(new byte[] {1, 2, 3});

    var op1 = new SBTreeBucketV2ShrinkOp(10, 20, 30, lsn, new ArrayList<>(entries1));
    var op2 = new SBTreeBucketV2ShrinkOp(10, 20, 30, lsn, new ArrayList<>(entries2));
    var op3 = new SBTreeBucketV2ShrinkOp(10, 20, 30, lsn, new ArrayList<>(entries3));

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }
}
