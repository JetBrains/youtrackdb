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
 * Tests for CellBTreeMultiValueV2NullBucket logical WAL operations. Covers record ID verification,
 * serialization roundtrip, WALRecordsFactory integration, redo correctness, conditional
 * registration (addValue threshold, removeValue not-found paths), redo suppression, and
 * idempotency.
 */
public class BTreeMVNullBucketV2OpTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVNullBucketV2InitOp.RECORD_ID, BTreeMVNullBucketV2InitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVNullBucketV2AddValueOp.RECORD_ID, BTreeMVNullBucketV2AddValueOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVNullBucketV2RemoveValueOp.RECORD_ID,
        BTreeMVNullBucketV2RemoveValueOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVNullBucketV2IncrementSizeOp.RECORD_ID,
        BTreeMVNullBucketV2IncrementSizeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVNullBucketV2DecrementSizeOp.RECORD_ID,
        BTreeMVNullBucketV2DecrementSizeOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testInitOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INIT_OP,
        BTreeMVNullBucketV2InitOp.RECORD_ID);
    Assert.assertEquals(243, BTreeMVNullBucketV2InitOp.RECORD_ID);
  }

  @Test
  public void testAddValueOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_ADD_VALUE_OP,
        BTreeMVNullBucketV2AddValueOp.RECORD_ID);
    Assert.assertEquals(244, BTreeMVNullBucketV2AddValueOp.RECORD_ID);
  }

  @Test
  public void testRemoveValueOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_REMOVE_VALUE_OP,
        BTreeMVNullBucketV2RemoveValueOp.RECORD_ID);
    Assert.assertEquals(245, BTreeMVNullBucketV2RemoveValueOp.RECORD_ID);
  }

  @Test
  public void testIncrementSizeOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INCREMENT_SIZE_OP,
        BTreeMVNullBucketV2IncrementSizeOp.RECORD_ID);
    Assert.assertEquals(246, BTreeMVNullBucketV2IncrementSizeOp.RECORD_ID);
  }

  @Test
  public void testDecrementSizeOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_DECREMENT_SIZE_OP,
        BTreeMVNullBucketV2DecrementSizeOp.RECORD_ID);
    Assert.assertEquals(247, BTreeMVNullBucketV2DecrementSizeOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new BTreeMVNullBucketV2InitOp(10, 20, 30, initialLsn, 999L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVNullBucketV2InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(999L, deserialized.getMId());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddValueOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(6, 600);
    var original = new BTreeMVNullBucketV2AddValueOp(
        5, 10, 15, initialLsn, (short) 23, 456789L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVNullBucketV2AddValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals((short) 23, deserialized.getCollectionId());
    Assert.assertEquals(456789L, deserialized.getCollectionPosition());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveValueOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 700);
    var original = new BTreeMVNullBucketV2RemoveValueOp(
        13, 26, 39, initialLsn, (short) 11, 222333L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVNullBucketV2RemoveValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals((short) 11, deserialized.getCollectionId());
    Assert.assertEquals(222333L, deserialized.getCollectionPosition());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testIncrementSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 800);
    var original = new BTreeMVNullBucketV2IncrementSizeOp(1, 2, 3, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVNullBucketV2IncrementSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testDecrementSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(9, 900);
    var original = new BTreeMVNullBucketV2DecrementSizeOp(4, 8, 12, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVNullBucketV2DecrementSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new BTreeMVNullBucketV2InitOp(10, 20, 30, initialLsn, 777L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVNullBucketV2InitOp);
    var result = (BTreeMVNullBucketV2InitOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(777L, result.getMId());
  }

  @Test
  public void testAddValueOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(50, 500);
    var original = new BTreeMVNullBucketV2AddValueOp(
        1, 2, 3, initialLsn, (short) 55, 1234567890L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVNullBucketV2AddValueOp);
    var result = (BTreeMVNullBucketV2AddValueOp) deserialized;
    Assert.assertEquals((short) 55, result.getCollectionId());
    Assert.assertEquals(1234567890L, result.getCollectionPosition());
  }

  @Test
  public void testRemoveValueOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(60, 600);
    var original = new BTreeMVNullBucketV2RemoveValueOp(
        1, 2, 3, initialLsn, (short) 33, 9876543L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVNullBucketV2RemoveValueOp);
    var result = (BTreeMVNullBucketV2RemoveValueOp) deserialized;
    Assert.assertEquals((short) 33, result.getCollectionId());
    Assert.assertEquals(9876543L, result.getCollectionPosition());
  }

  @Test
  public void testIncrementSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(70, 700);
    var original = new BTreeMVNullBucketV2IncrementSizeOp(1, 2, 3, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVNullBucketV2IncrementSizeOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testDecrementSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(80, 800);
    var original = new BTreeMVNullBucketV2DecrementSizeOp(4, 5, 6, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeMVNullBucketV2DecrementSizeOp);
    Assert.assertEquals(original, deserialized);
  }

  // ---------- Redo correctness tests ----------

  /**
   * NullBucket init: apply directly on page1, redo on page2. Both pages must have identical
   * state (mId set, embeddedSize=0, totalSize=0).
   */
  @Test
  public void testInitRedoCorrectness() {
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
      var page1 = new CellBTreeMultiValueV2NullBucket(entry1);
      page1.init(42L);

      var op = new BTreeMVNullBucketV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0), 42L);
      var page2 = new CellBTreeMultiValueV2NullBucket(entry2);
      op.redo(page2);

      Assert.assertEquals(42L, page1.getMid());
      Assert.assertEquals(42L, page2.getMid());
      Assert.assertEquals(0, page1.getSize());
      Assert.assertEquals(0, page2.getSize());

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
   * addValue: apply directly on page1, redo on page2. Verify RID appears in values and size
   * incremented.
   */
  @Test
  public void testAddValueRedoCorrectness() {
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
      var page1 = new CellBTreeMultiValueV2NullBucket(entry1);
      page1.init(100L);
      page1.addValue(new RecordId(5, 1000L));

      var page2 = new CellBTreeMultiValueV2NullBucket(entry2);
      page2.init(100L);
      var op = new BTreeMVNullBucketV2AddValueOp(
          0, 0, 0, new LogSequenceNumber(0, 0), (short) 5, 1000L);
      op.redo(page2);

      Assert.assertEquals(1, page1.getSize());
      Assert.assertEquals(1, page2.getSize());

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
   * removeValue: apply directly on page1, redo on page2. Verify RID is removed.
   */
  @Test
  public void testRemoveValueRedoCorrectness() {
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
      // Setup: init + add 2 values on both pages
      var page1 = new CellBTreeMultiValueV2NullBucket(entry1);
      page1.init(100L);
      page1.addValue(new RecordId(5, 1000L));
      page1.addValue(new RecordId(7, 2000L));

      var page2 = new CellBTreeMultiValueV2NullBucket(entry2);
      page2.init(100L);
      page2.addValue(new RecordId(5, 1000L));
      page2.addValue(new RecordId(7, 2000L));

      // Remove first value directly on page1
      page1.removeValue(new RecordId(5, 1000L));

      // Redo remove on page2
      var op = new BTreeMVNullBucketV2RemoveValueOp(
          0, 0, 0, new LogSequenceNumber(0, 0), (short) 5, 1000L);
      op.redo(page2);

      Assert.assertEquals(1, page1.getSize());
      Assert.assertEquals(1, page2.getSize());

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
   * incrementSize + decrementSize: apply directly on page1, redo on page2. Verify size.
   */
  @Test
  public void testIncrementDecrementSizeRedoCorrectness() {
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
      var page1 = new CellBTreeMultiValueV2NullBucket(entry1);
      page1.init(100L);
      page1.incrementSize();
      page1.incrementSize();
      page1.decrementSize();

      var page2 = new CellBTreeMultiValueV2NullBucket(entry2);
      new BTreeMVNullBucketV2InitOp(0, 0, 0, lsn, 100L).redo(page2);
      new BTreeMVNullBucketV2IncrementSizeOp(0, 0, 0, lsn).redo(page2);
      new BTreeMVNullBucketV2IncrementSizeOp(0, 0, 0, lsn).redo(page2);
      new BTreeMVNullBucketV2DecrementSizeOp(0, 0, 0, lsn).redo(page2);

      Assert.assertEquals(1, page1.getSize());
      Assert.assertEquals(1, page2.getSize());

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

  // ---------- Conditional registration tests ----------

  /**
   * Verifies that addValue does NOT register a PageOp when the embedded list is full
   * (threshold exceeded — returns mId instead of -1).
   */
  @Test
  public void testAddValueDoesNotRegisterOnThresholdExceeded() {
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

      var page = new CellBTreeMultiValueV2NullBucket(changes);
      page.init(500L);
      org.mockito.Mockito.reset(atomicOp);

      // Fill embedded list to the boundary (64 entries)
      for (int i = 0; i < 64; i++) {
        page.addValue(new RecordId(1, i));
      }
      org.mockito.Mockito.reset(atomicOp);

      // This call should hit the threshold — returns mId, NOT -1
      long result = page.addValue(new RecordId(1, 999));
      Assert.assertEquals(500L, result);

      // Verify no PageOperation was registered for the threshold-exceeded call
      verify(atomicOp, never()).registerPageOperation(
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.any(PageOperation.class));
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * Verifies that removeValue does NOT register when the value is not found (returns -1).
   */
  @Test
  public void testRemoveValueDoesNotRegisterWhenNotFound() {
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

      var page = new CellBTreeMultiValueV2NullBucket(changes);
      page.init(500L);
      page.addValue(new RecordId(5, 1000L));
      org.mockito.Mockito.reset(atomicOp);

      // Try to remove a non-existent value — embeddedSize(1) <= size(1) so returns 0
      int result = page.removeValue(new RecordId(99, 99999L));
      Assert.assertEquals(0, result);

      // Verify no PageOperation was registered
      verify(atomicOp, never()).registerPageOperation(
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.any(PageOperation.class));
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * Verifies that addValue DOES register when below threshold (returns -1).
   */
  @Test
  public void testAddValueRegistersOnSuccess() {
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

      var page = new CellBTreeMultiValueV2NullBucket(changes);
      page.init(500L);
      org.mockito.Mockito.reset(atomicOp);

      long result = page.addValue(new RecordId(5, 1000L));
      Assert.assertEquals(-1, result);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          captor.capture());

      var captured = captor.getValue();
      Assert.assertTrue(captured instanceof BTreeMVNullBucketV2AddValueOp);
      Assert.assertEquals((short) 5, ((BTreeMVNullBucketV2AddValueOp) captured).getCollectionId());
      Assert.assertEquals(
          1000L, ((BTreeMVNullBucketV2AddValueOp) captured).getCollectionPosition());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * Verifies that removeValue DOES register when value is found (returns 1).
   */
  @Test
  public void testRemoveValueRegistersOnSuccess() {
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

      var page = new CellBTreeMultiValueV2NullBucket(changes);
      page.init(500L);
      page.addValue(new RecordId(5, 1000L));
      org.mockito.Mockito.reset(atomicOp);

      int result = page.removeValue(new RecordId(5, 1000L));
      Assert.assertEquals(1, result);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          captor.capture());

      var captured = captor.getValue();
      Assert.assertTrue(captured instanceof BTreeMVNullBucketV2RemoveValueOp);
      Assert.assertEquals(
          (short) 5, ((BTreeMVNullBucketV2RemoveValueOp) captured).getCollectionId());
      Assert.assertEquals(
          1000L, ((BTreeMVNullBucketV2RemoveValueOp) captured).getCollectionPosition());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression tests ----------

  /**
   * Verifies that redo does NOT register PageOperations (plain CacheEntry, not CacheEntryChanges).
   */
  @Test
  public void testRedoDoesNotRegisterPageOperation() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);
      var page = new CellBTreeMultiValueV2NullBucket(entry);

      new BTreeMVNullBucketV2InitOp(0, 0, 0, lsn, 100L).redo(page);
      new BTreeMVNullBucketV2AddValueOp(0, 0, 0, lsn, (short) 5, 1000L).redo(page);
      new BTreeMVNullBucketV2IncrementSizeOp(0, 0, 0, lsn).redo(page);
      new BTreeMVNullBucketV2RemoveValueOp(0, 0, 0, lsn, (short) 5, 1000L).redo(page);
      new BTreeMVNullBucketV2DecrementSizeOp(0, 0, 0, lsn).redo(page);

      // All redos complete without error — plain CacheEntry skips registration
      Assert.assertEquals(100L, page.getMid());
      Assert.assertEquals(0, page.getSize());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Multi-op redo sequence test ----------

  /**
   * Replays a realistic sequence (init + addValue*3 + removeValue + incrementSize + decrementSize)
   * and compares byte-for-byte with direct mutation.
   */
  @Test
  public void testMultiOpRedoSequence() {
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

      // Direct mutation on page1
      var page1 = new CellBTreeMultiValueV2NullBucket(entry1);
      page1.init(42L);
      page1.addValue(new RecordId(1, 100L));
      page1.addValue(new RecordId(2, 200L));
      page1.addValue(new RecordId(3, 300L));
      page1.removeValue(new RecordId(2, 200L));
      page1.incrementSize();
      page1.decrementSize();

      // Redo on page2
      var page2 = new CellBTreeMultiValueV2NullBucket(entry2);
      new BTreeMVNullBucketV2InitOp(0, 0, 0, lsn, 42L).redo(page2);
      new BTreeMVNullBucketV2AddValueOp(0, 0, 0, lsn, (short) 1, 100L).redo(page2);
      new BTreeMVNullBucketV2AddValueOp(0, 0, 0, lsn, (short) 2, 200L).redo(page2);
      new BTreeMVNullBucketV2AddValueOp(0, 0, 0, lsn, (short) 3, 300L).redo(page2);
      new BTreeMVNullBucketV2RemoveValueOp(0, 0, 0, lsn, (short) 2, 200L).redo(page2);
      new BTreeMVNullBucketV2IncrementSizeOp(0, 0, 0, lsn).redo(page2);
      new BTreeMVNullBucketV2DecrementSizeOp(0, 0, 0, lsn).redo(page2);

      Assert.assertEquals(2, page2.getSize());

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

  // ---------- Equals/hashCode tests ----------

  @Test
  public void testInitOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 2);
    var op1 = new BTreeMVNullBucketV2InitOp(1, 2, 3, lsn, 42L);
    var op2 = new BTreeMVNullBucketV2InitOp(1, 2, 3, lsn, 42L);
    var op3 = new BTreeMVNullBucketV2InitOp(1, 2, 3, lsn, 99L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testAddValueOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 2);
    var op1 = new BTreeMVNullBucketV2AddValueOp(1, 2, 3, lsn, (short) 5, 100L);
    var op2 = new BTreeMVNullBucketV2AddValueOp(1, 2, 3, lsn, (short) 5, 100L);
    var op3 = new BTreeMVNullBucketV2AddValueOp(1, 2, 3, lsn, (short) 5, 200L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }
}
