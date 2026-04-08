package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

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
 * Tests for DirtyPageBitSetPage and MapEntryPoint (v2) logical WAL operations:
 * {@link DirtyPageBitSetPageInitOp}, {@link DirtyPageBitSetPageSetBitOp},
 * {@link DirtyPageBitSetPageClearBitOp}, and {@link MapEntryPointSetFileSizeOp}.
 */
public class DirtyPageBitSetAndMapEntryPointOpTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        DirtyPageBitSetPageInitOp.RECORD_ID, DirtyPageBitSetPageInitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        DirtyPageBitSetPageSetBitOp.RECORD_ID, DirtyPageBitSetPageSetBitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        DirtyPageBitSetPageClearBitOp.RECORD_ID, DirtyPageBitSetPageClearBitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        MapEntryPointSetFileSizeOp.RECORD_ID, MapEntryPointSetFileSizeOp.class);
  }

  // --- Record ID tests ---

  @Test
  public void testDirtyPageBitSetInitOpRecordId() {
    Assert.assertEquals(215, DirtyPageBitSetPageInitOp.RECORD_ID);
  }

  @Test
  public void testDirtyPageBitSetSetBitOpRecordId() {
    Assert.assertEquals(216, DirtyPageBitSetPageSetBitOp.RECORD_ID);
  }

  @Test
  public void testDirtyPageBitSetClearBitOpRecordId() {
    Assert.assertEquals(217, DirtyPageBitSetPageClearBitOp.RECORD_ID);
  }

  @Test
  public void testMapEntryPointSetFileSizeOpRecordId() {
    Assert.assertEquals(218, MapEntryPointSetFileSizeOp.RECORD_ID);
  }

  // --- Serialization roundtrip tests ---

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new DirtyPageBitSetPageInitOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new DirtyPageBitSetPageInitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetBitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var original = new DirtyPageBitSetPageSetBitOp(15, 25, 35, initialLsn, 42);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new DirtyPageBitSetPageSetBitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(42, deserialized.getBitIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testClearBitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 300);
    var original = new DirtyPageBitSetPageClearBitOp(5, 10, 15, initialLsn, 99);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new DirtyPageBitSetPageClearBitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(99, deserialized.getBitIndex());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testMapEntryPointSetFileSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(1, 100);
    var original = new MapEntryPointSetFileSizeOp(7, 42, 0, initialLsn, 55);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new MapEntryPointSetFileSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(55, deserialized.getSize());
    Assert.assertEquals(original, deserialized);
  }

  // --- WALRecordsFactory roundtrip tests ---

  @Test
  public void testSetBitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new DirtyPageBitSetPageSetBitOp(10, 20, 30, initialLsn, 777);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof DirtyPageBitSetPageSetBitOp);
    var result = (DirtyPageBitSetPageSetBitOp) deserialized;
    Assert.assertEquals(777, result.getBitIndex());
    Assert.assertEquals(DirtyPageBitSetPageSetBitOp.RECORD_ID, result.getId());
  }

  @Test
  public void testClearBitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 2048);
    var original = new DirtyPageBitSetPageClearBitOp(11, 22, 33, initialLsn, 500);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof DirtyPageBitSetPageClearBitOp);
    var result = (DirtyPageBitSetPageClearBitOp) deserialized;
    Assert.assertEquals(500, result.getBitIndex());
    Assert.assertEquals(DirtyPageBitSetPageClearBitOp.RECORD_ID, result.getId());
  }

  @Test
  public void testMapEntryPointSetFileSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 512);
    var original = new MapEntryPointSetFileSizeOp(3, 6, 9, initialLsn, 123);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof MapEntryPointSetFileSizeOp);
    var result = (MapEntryPointSetFileSizeOp) deserialized;
    Assert.assertEquals(123, result.getSize());
    Assert.assertEquals(MapEntryPointSetFileSizeOp.RECORD_ID, result.getId());
  }

  // --- Redo correctness tests ---

  /**
   * Redo correctness for DirtyPageBitSetPage: init on dirty page, set bits, clear bits.
   * Applies operations via direct path and redo path, verifies byte-level equality.
   */
  @Test
  public void testDirtyPageBitSetRedoCorrectness() {
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

      // Direct path: init, set bits, clear one bit
      var page1 = new DirtyPageBitSetPage(entry1);
      page1.init();
      page1.setBit(0);
      page1.setBit(7);
      page1.setBit(100);
      page1.setBit(DirtyPageBitSetPage.BITS_PER_PAGE - 1); // last bit
      page1.clearBit(7);

      // Redo path: same sequence
      var page2 = new DirtyPageBitSetPage(entry2);
      new DirtyPageBitSetPageInitOp(0, 0, 0, lsn).redo(page2);
      new DirtyPageBitSetPageSetBitOp(0, 0, 0, lsn, 0).redo(page2);
      new DirtyPageBitSetPageSetBitOp(0, 0, 0, lsn, 7).redo(page2);
      new DirtyPageBitSetPageSetBitOp(0, 0, 0, lsn, 100).redo(page2);
      new DirtyPageBitSetPageSetBitOp(0, 0, 0, lsn, DirtyPageBitSetPage.BITS_PER_PAGE - 1)
          .redo(page2);
      new DirtyPageBitSetPageClearBitOp(0, 0, 0, lsn, 7).redo(page2);

      // Concrete expected values
      Assert.assertTrue(page1.isBitSet(0));
      Assert.assertTrue(page2.isBitSet(0));
      Assert.assertFalse(page1.isBitSet(7));
      Assert.assertFalse(page2.isBitSet(7));
      Assert.assertTrue(page1.isBitSet(100));
      Assert.assertTrue(page2.isBitSet(100));
      Assert.assertTrue(page1.isBitSet(DirtyPageBitSetPage.BITS_PER_PAGE - 1));
      Assert.assertTrue(page2.isBitSet(DirtyPageBitSetPage.BITS_PER_PAGE - 1));

      // nextSetBit consistency
      Assert.assertEquals(0, page1.nextSetBit(0));
      Assert.assertEquals(0, page2.nextSetBit(0));
      Assert.assertEquals(100, page1.nextSetBit(1));
      Assert.assertEquals(100, page2.nextSetBit(1));

      // Byte-level buffer comparison
      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after redo", 0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Redo correctness for init on dirty page — verifies that init clears pre-existing bits.
   */
  @Test
  public void testDirtyPageBitSetInitRedoOnDirtyPage() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    try {
      var page = new DirtyPageBitSetPage(entry1);
      page.init();
      page.setBit(0);
      page.setBit(1000);
      Assert.assertTrue(page.isBitSet(0));

      // Redo init on dirty page — must clear all bits
      new DirtyPageBitSetPageInitOp(0, 0, 0, new LogSequenceNumber(0, 0)).redo(page);

      Assert.assertFalse(page.isBitSet(0));
      Assert.assertFalse(page.isBitSet(1000));
      Assert.assertEquals(-1, page.nextSetBit(0));
    } finally {
      entry1.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
    }
  }

  /**
   * Redo correctness for MapEntryPoint setFileSize.
   */
  @Test
  public void testMapEntryPointSetFileSizeRedoCorrectness() {
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
      // Direct path
      var page1 = new MapEntryPoint(entry1);
      page1.setFileSize(42);

      // Redo path
      var page2 = new MapEntryPoint(entry2);
      var op = new MapEntryPointSetFileSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 42);
      op.redo(page2);

      Assert.assertEquals(42, page1.getFileSize());
      Assert.assertEquals(42, page2.getFileSize());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  // --- Registration from mutation methods ---

  @Test
  public void testDirtyPageBitSetInitRegistersOp() {
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

      var page = new DirtyPageBitSetPage(changes);
      page.init();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      Assert.assertTrue(opCaptor.getValue() instanceof DirtyPageBitSetPageInitOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testDirtyPageBitSetSetBitRegistersOp() {
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

      var page = new DirtyPageBitSetPage(changes);
      page.init();
      org.mockito.Mockito.reset(atomicOp);

      page.setBit(55);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var op = (DirtyPageBitSetPageSetBitOp) opCaptor.getValue();
      Assert.assertEquals(55, op.getBitIndex());
      Assert.assertEquals(7, op.getPageIndex());
      Assert.assertEquals(42, op.getFileId());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testDirtyPageBitSetClearBitRegistersOp() {
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

      var page = new DirtyPageBitSetPage(changes);
      page.init();
      page.setBit(77);
      org.mockito.Mockito.reset(atomicOp);

      page.clearBit(77);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var op = (DirtyPageBitSetPageClearBitOp) opCaptor.getValue();
      Assert.assertEquals(77, op.getBitIndex());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testMapEntryPointSetFileSizeRegistersOp() {
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

      var page = new MapEntryPoint(changes);
      page.setFileSize(88);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var op = (MapEntryPointSetFileSizeOp) opCaptor.getValue();
      Assert.assertEquals(88, op.getSize());
      Assert.assertEquals(7, op.getPageIndex());
      Assert.assertEquals(42, op.getFileId());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // --- Redo suppression (D4) ---

  @Test
  public void testNoRegistrationDuringRedoPathDirtyPageBitSet() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new DirtyPageBitSetPage(entry);
      page.init();
      page.setBit(10);
      page.clearBit(10);

      Assert.assertFalse(page.isBitSet(10));
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testNoRegistrationDuringRedoPathMapEntryPoint() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new MapEntryPoint(entry);
      page.setFileSize(99);

      Assert.assertEquals(99, page.getFileSize());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // --- Equals and hashCode ---

  @Test
  public void testSetBitOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new DirtyPageBitSetPageSetBitOp(5, 10, 15, lsn, 42);
    var op2 = new DirtyPageBitSetPageSetBitOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new DirtyPageBitSetPageSetBitOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testClearBitOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new DirtyPageBitSetPageClearBitOp(5, 10, 15, lsn, 42);
    var op2 = new DirtyPageBitSetPageClearBitOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new DirtyPageBitSetPageClearBitOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testMapEntryPointOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new MapEntryPointSetFileSizeOp(5, 10, 15, lsn, 42);
    var op2 = new MapEntryPointSetFileSizeOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new MapEntryPointSetFileSizeOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }
}
