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
 * Tests for CellBTreeSingleValueBucketV3 simple PageOperation subclasses:
 * init, switchBucketType, setLeftSibling, setRightSibling, setNextFreeListPage, updateValue.
 */
public class BTreeSVBucketV3SimpleOpsTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3InitOp.RECORD_ID, BTreeSVBucketV3InitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3SwitchBucketTypeOp.RECORD_ID,
        BTreeSVBucketV3SwitchBucketTypeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3SetLeftSiblingOp.RECORD_ID,
        BTreeSVBucketV3SetLeftSiblingOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3SetRightSiblingOp.RECORD_ID,
        BTreeSVBucketV3SetRightSiblingOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3SetNextFreeListPageOp.RECORD_ID,
        BTreeSVBucketV3SetNextFreeListPageOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3UpdateValueOp.RECORD_ID, BTreeSVBucketV3UpdateValueOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testRecordIds() {
    Assert.assertEquals(226, BTreeSVBucketV3InitOp.RECORD_ID);
    Assert.assertEquals(227, BTreeSVBucketV3SwitchBucketTypeOp.RECORD_ID);
    Assert.assertEquals(228, BTreeSVBucketV3SetLeftSiblingOp.RECORD_ID);
    Assert.assertEquals(229, BTreeSVBucketV3SetRightSiblingOp.RECORD_ID);
    Assert.assertEquals(230, BTreeSVBucketV3SetNextFreeListPageOp.RECORD_ID);
    Assert.assertEquals(231, BTreeSVBucketV3UpdateValueOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new BTreeSVBucketV3InitOp(10, 20, 30, initialLsn, true);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertTrue(deserialized.isLeaf());
    Assert.assertEquals(original, deserialized);

    // Non-leaf variant
    var nonLeaf = new BTreeSVBucketV3InitOp(10, 20, 30, initialLsn, false);
    var content2 = new byte[nonLeaf.serializedSize() + 1];
    nonLeaf.toStream(content2, 1);
    var deserialized2 = new BTreeSVBucketV3InitOp();
    deserialized2.fromStream(content2, 1);
    Assert.assertFalse(deserialized2.isLeaf());
  }

  @Test
  public void testSwitchBucketTypeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var original = new BTreeSVBucketV3SwitchBucketTypeOp(15, 25, 35, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3SwitchBucketTypeOp();
    deserialized.fromStream(content, 1);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetLeftSiblingOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var original = new BTreeSVBucketV3SetLeftSiblingOp(7, 14, 21, initialLsn, 42);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeSVBucketV3SetLeftSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(42, deserialized.getSiblingPageIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetRightSiblingOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(9, 800);
    var original = new BTreeSVBucketV3SetRightSiblingOp(1, 2, 3, initialLsn, 99);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeSVBucketV3SetRightSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(99, deserialized.getSiblingPageIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetNextFreeListPageOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(4, 300);
    var original = new BTreeSVBucketV3SetNextFreeListPageOp(11, 22, 33, initialLsn, 77);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeSVBucketV3SetNextFreeListPageOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(77, deserialized.getNextFreeListPage());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testUpdateValueOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(6, 600);
    var value = new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    var original = new BTreeSVBucketV3UpdateValueOp(5, 10, 15, initialLsn, 3, value, 8);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeSVBucketV3UpdateValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(3, deserialized.getIndex());
    Assert.assertArrayEquals(value, deserialized.getValue());
    Assert.assertEquals(8, deserialized.getKeyLength());
    Assert.assertEquals(original, deserialized);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new BTreeSVBucketV3InitOp(10, 20, 30, initialLsn, true);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3InitOp);
    var result = (BTreeSVBucketV3InitOp) deserialized;
    Assert.assertTrue(result.isLeaf());
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testUpdateValueOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 2048);
    var value = new byte[] {10, 20, 30};
    var original = new BTreeSVBucketV3UpdateValueOp(11, 22, 33, initialLsn, 0, value, 5);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3UpdateValueOp);
    var result = (BTreeSVBucketV3UpdateValueOp) deserialized;
    Assert.assertEquals(0, result.getIndex());
    Assert.assertArrayEquals(value, result.getValue());
    Assert.assertEquals(5, result.getKeyLength());
  }

  @Test
  public void testSwitchBucketTypeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(55, 550);
    var original = new BTreeSVBucketV3SwitchBucketTypeOp(4, 8, 12, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3SwitchBucketTypeOp);
  }

  @Test
  public void testSetLeftSiblingOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(66, 660);
    var original = new BTreeSVBucketV3SetLeftSiblingOp(7, 14, 21, initialLsn, Long.MAX_VALUE);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3SetLeftSiblingOp);
    Assert.assertEquals(Long.MAX_VALUE,
        ((BTreeSVBucketV3SetLeftSiblingOp) deserialized).getSiblingPageIndex());
  }

  @Test
  public void testSetNextFreeListPageOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(77, 770);
    var original = new BTreeSVBucketV3SetNextFreeListPageOp(3, 6, 9, initialLsn, -1);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3SetNextFreeListPageOp);
    Assert.assertEquals(-1,
        ((BTreeSVBucketV3SetNextFreeListPageOp) deserialized).getNextFreeListPage());
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
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);

      var op = new BTreeSVBucketV3InitOp(0, 0, 0, new LogSequenceNumber(0, 0), true);
      op.redo(new CellBTreeSingleValueBucketV3<>(entry2));

      var bucket2 = new CellBTreeSingleValueBucketV3<>(entry2);
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
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(false);

      var op = new BTreeSVBucketV3InitOp(0, 0, 0, new LogSequenceNumber(0, 0), false);
      op.redo(new CellBTreeSingleValueBucketV3<>(entry2));

      var bucket2 = new CellBTreeSingleValueBucketV3<>(entry2);
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

      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.switchBucketType();

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeSVBucketV3SwitchBucketTypeOp(0, 0, 0, lsn).redo(page2);

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

      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.setLeftSibling(42);
      page1.setRightSibling(99);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeSVBucketV3SetLeftSiblingOp(0, 0, 0, lsn, 42).redo(page2);
      new BTreeSVBucketV3SetRightSiblingOp(0, 0, 0, lsn, 99).redo(page2);

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
   * setNextFreeListPage redo correctness.
   */
  @Test
  public void testSetNextFreeListPageRedoCorrectness() {
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
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      // setNextFreeListPage shares the NEXT_FREE_POSITION offset
      page1.setNextFreeListPage(55);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3SetNextFreeListPageOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 55).redo(page2);

      Assert.assertEquals(55, page1.getNextFreeListPage());
      Assert.assertEquals(55, page2.getNextFreeListPage());

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
   * updateValue redo correctness: add a leaf entry, then update its value.
   * The RID value (10 bytes: short collectionId + long collectionPosition)
   * is written at the position after the key within the entry.
   */
  @Test
  public void testUpdateValueLeafRedoCorrectness() {
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
      var key = new byte[] {1, 2, 3, 4}; // 4-byte key
      var value1 = new byte[] {10, 20, 30, 40, 50, 60, 70, 80, 90, 100}; // 10-byte RID
      var value2 = new byte[] {99, 88, 77, 66, 55, 44, 33, 22, 11, 0}; // updated RID

      // Setup both pages identically: init + addLeafEntry
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addLeafEntry(0, key, value1);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      page2.init(true);
      page2.addLeafEntry(0, key, value1);

      // Apply updateValue directly on page1
      page1.updateValue(0, value2, key.length);

      // Apply via redo on page2
      new BTreeSVBucketV3UpdateValueOp(0, 0, 0, lsn, 0, value2, key.length).redo(page2);

      // Byte-level comparison
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after updateValue redo",
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVBucketV3InitOp);
      Assert.assertTrue(((BTreeSVBucketV3InitOp) registeredOp).isLeaf());
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      page.switchBucketType();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      Assert.assertTrue(opCaptor.getValue() instanceof BTreeSVBucketV3SwitchBucketTypeOp);
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      page.setLeftSibling(123);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3SetLeftSiblingOp) opCaptor.getValue();
      Assert.assertEquals(123, registeredOp.getSiblingPageIndex());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testUpdateValueRegistersOp() {
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

      var newValue = new byte[] {99, 88, 77, 66, 55, 44, 33, 22, 11, 0};
      page.updateValue(0, newValue, key.length);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3UpdateValueOp) opCaptor.getValue();
      Assert.assertEquals(0, registeredOp.getIndex());
      Assert.assertArrayEquals(newValue, registeredOp.getValue());
      Assert.assertEquals(key.length, registeredOp.getKeyLength());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression (D4) ----------

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
      page.setLeftSibling(10);
      page.setRightSibling(20);
      page.setNextFreeListPage(30);

      Assert.assertTrue(page.isLeaf());
      Assert.assertEquals(10, page.getLeftSibling());
      Assert.assertEquals(20, page.getRightSibling());
      Assert.assertEquals(30, page.getNextFreeListPage());

      page.switchBucketType();
      Assert.assertFalse(page.isLeaf());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Equals and hashCode ----------

  @Test
  public void testInitOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new BTreeSVBucketV3InitOp(5, 10, 15, lsn, true);
    var op2 = new BTreeSVBucketV3InitOp(5, 10, 15, lsn, true);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new BTreeSVBucketV3InitOp(5, 10, 15, lsn, false);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testUpdateValueOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var value = new byte[] {1, 2, 3};
    var op1 = new BTreeSVBucketV3UpdateValueOp(5, 10, 15, lsn, 0, value, 4);
    var op2 = new BTreeSVBucketV3UpdateValueOp(5, 10, 15, lsn, 0, value, 4);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different index
    var op3 = new BTreeSVBucketV3UpdateValueOp(5, 10, 15, lsn, 1, value, 4);
    Assert.assertNotEquals(op1, op3);

    // Different value
    var op4 = new BTreeSVBucketV3UpdateValueOp(5, 10, 15, lsn, 0, new byte[] {9}, 4);
    Assert.assertNotEquals(op1, op4);
  }

  @Test
  public void testSetLeftSiblingOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new BTreeSVBucketV3SetLeftSiblingOp(5, 10, 15, lsn, 42);
    var op2 = new BTreeSVBucketV3SetLeftSiblingOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new BTreeSVBucketV3SetLeftSiblingOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }

  // ---------- Review fix: missing registration tests ----------

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
      changes.setInitialLSN(new LogSequenceNumber(2, 200));

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      page.setRightSibling(456);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3SetRightSiblingOp) opCaptor.getValue();
      Assert.assertEquals(456, registeredOp.getSiblingPageIndex());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testSetNextFreeListPageRegistersOp() {
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
      page.setNextFreeListPage(88);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3SetNextFreeListPageOp) opCaptor.getValue();
      Assert.assertEquals(88, registeredOp.getNextFreeListPage());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Review fix: missing factory roundtrip ----------

  @Test
  public void testSetRightSiblingOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(88, 880);
    var original =
        new BTreeSVBucketV3SetRightSiblingOp(5, 10, 15, initialLsn, Long.MAX_VALUE);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3SetRightSiblingOp);
    Assert.assertEquals(Long.MAX_VALUE,
        ((BTreeSVBucketV3SetRightSiblingOp) deserialized).getSiblingPageIndex());
  }

}
