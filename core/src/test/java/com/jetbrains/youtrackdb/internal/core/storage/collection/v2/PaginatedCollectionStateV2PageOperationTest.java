package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
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
 * Tests for the PaginatedCollectionStateV2 logical WAL operations:
 * {@link PaginatedCollectionStateV2SetFileSizeOp} and
 * {@link PaginatedCollectionStateV2SetApproxRecordsCountOp}.
 * Covers serialization roundtrip, WALRecordsFactory integration, redo correctness,
 * registration pattern from mutation methods, and redo suppression.
 */
public class PaginatedCollectionStateV2PageOperationTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        PaginatedCollectionStateV2SetFileSizeOp.RECORD_ID,
        PaginatedCollectionStateV2SetFileSizeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        PaginatedCollectionStateV2SetApproxRecordsCountOp.RECORD_ID,
        PaginatedCollectionStateV2SetApproxRecordsCountOp.class);
  }

  // --- CacheEntryChanges.registerPageOperation delegation ---

  /**
   * Verifies that CacheEntryChanges.registerPageOperation delegates to
   * atomicOp.registerPageOperation with the correct fileId and pageIndex.
   */
  @Test
  public void testCacheEntryChangesRegisterPageOperationDelegation() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    // Set up a delegate CacheEntry with known fileId and pageIndex
    var delegate = mock(CacheEntry.class);
    when(delegate.getFileId()).thenReturn(42L);
    when(delegate.getPageIndex()).thenReturn(7);
    changes.setDelegate(delegate);

    var op = new PaginatedCollectionStateV2SetFileSizeOp(
        7, 42, 0, new LogSequenceNumber(1, 10), 100);

    changes.registerPageOperation(op);

    // Verify delegation to AtomicOperation with correct fileId and pageIndex
    var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
    verify(atomicOp).registerPageOperation(
        org.mockito.ArgumentMatchers.eq(42L),
        org.mockito.ArgumentMatchers.eq(7L),
        opCaptor.capture());
    Assert.assertSame(op, opCaptor.getValue());
  }

  // --- Serialization roundtrip tests ---

  /**
   * SetFileSizeOp: serialize to byte array and deserialize back.
   * All fields (pageIndex, fileId, operationUnitId, initialLsn, size) must survive.
   */
  @Test
  public void testSetFileSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new PaginatedCollectionStateV2SetFileSizeOp(
        10, 20, 30, initialLsn, 42);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new PaginatedCollectionStateV2SetFileSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getSize(), deserialized.getSize());
    Assert.assertEquals(original, deserialized);
  }

  /**
   * SetApproxRecordsCountOp: serialize to byte array and deserialize back.
   * All fields must survive, including the long count value.
   */
  @Test
  public void testSetApproxRecordsCountOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var original = new PaginatedCollectionStateV2SetApproxRecordsCountOp(
        15, 25, 35, initialLsn, 999_999L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new PaginatedCollectionStateV2SetApproxRecordsCountOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getCount(), deserialized.getCount());
    Assert.assertEquals(original, deserialized);
  }

  /**
   * SetApproxRecordsCountOp with large long value to verify no truncation.
   */
  @Test
  public void testSetApproxRecordsCountOpLargeValue() {
    var initialLsn = new LogSequenceNumber(0, 0);
    var original = new PaginatedCollectionStateV2SetApproxRecordsCountOp(
        0, 0, 0, initialLsn, Long.MAX_VALUE);

    var content = new byte[original.serializedSize()];
    original.toStream(content, 0);

    var deserialized = new PaginatedCollectionStateV2SetApproxRecordsCountOp();
    deserialized.fromStream(content, 0);

    Assert.assertEquals(Long.MAX_VALUE, deserialized.getCount());
  }

  // --- WALRecordsFactory roundtrip tests ---

  /**
   * Full factory roundtrip for SetFileSizeOp: toStream → fromStream through WALRecordsFactory.
   */
  @Test
  public void testSetFileSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new PaginatedCollectionStateV2SetFileSizeOp(
        10, 20, 30, initialLsn, 77);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof PaginatedCollectionStateV2SetFileSizeOp);
    var result = (PaginatedCollectionStateV2SetFileSizeOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(original.getSize(), result.getSize());
    Assert.assertEquals(
        PaginatedCollectionStateV2SetFileSizeOp.RECORD_ID, result.getId());
  }

  /**
   * Full factory roundtrip for SetApproxRecordsCountOp.
   */
  @Test
  public void testSetApproxRecordsCountOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 2048);
    var original = new PaginatedCollectionStateV2SetApproxRecordsCountOp(
        11, 22, 33, initialLsn, 123_456_789L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof PaginatedCollectionStateV2SetApproxRecordsCountOp);
    var result = (PaginatedCollectionStateV2SetApproxRecordsCountOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(original.getCount(), result.getCount());
    Assert.assertEquals(
        PaginatedCollectionStateV2SetApproxRecordsCountOp.RECORD_ID, result.getId());
  }

  // --- Record ID tests ---

  @Test
  public void testSetFileSizeOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_FILE_SIZE_OP,
        PaginatedCollectionStateV2SetFileSizeOp.RECORD_ID);
    Assert.assertEquals(201,
        PaginatedCollectionStateV2SetFileSizeOp.RECORD_ID);
  }

  @Test
  public void testSetApproxRecordsCountOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_APPROX_RECORDS_COUNT_OP,
        PaginatedCollectionStateV2SetApproxRecordsCountOp.RECORD_ID);
    Assert.assertEquals(202,
        PaginatedCollectionStateV2SetApproxRecordsCountOp.RECORD_ID);
  }

  // --- Redo correctness tests ---

  /**
   * Redo correctness for setFileSize: apply the mutation via the normal overlay path,
   * then apply via redo on a fresh page with changes=null. Both pages must have the
   * same file size value.
   */
  @Test
  public void testSetFileSizeRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    // Page 1: apply via normal path (changes=null, simulating direct buffer write)
    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cachePointer1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cachePointer1, false, null);
    entry1.acquireExclusiveLock();

    // Page 2: apply via redo
    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cachePointer2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cachePointer2, false, null);
    entry2.acquireExclusiveLock();

    try {
      // Apply directly — simulates what redo does (changes=null path)
      var page1 = new PaginatedCollectionStateV2(entry1);
      page1.setFileSize(42);

      // Apply via redo
      var page2 = new PaginatedCollectionStateV2(entry2);
      var op = new PaginatedCollectionStateV2SetFileSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 42);
      op.redo(page2);

      // Both pages must have the same value
      Assert.assertEquals(42, page1.getFileSize());
      Assert.assertEquals(42, page2.getFileSize());
      Assert.assertEquals(page1.getFileSize(), page2.getFileSize());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Redo correctness for setApproximateRecordsCount: direct apply vs redo on fresh page.
   */
  @Test
  public void testSetApproxRecordsCountRedoCorrectness() {
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
      var page1 = new PaginatedCollectionStateV2(entry1);
      page1.setApproximateRecordsCount(999_999L);

      var page2 = new PaginatedCollectionStateV2(entry2);
      var op = new PaginatedCollectionStateV2SetApproxRecordsCountOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 999_999L);
      op.redo(page2);

      Assert.assertEquals(999_999L, page1.getApproximateRecordsCount());
      Assert.assertEquals(999_999L, page2.getApproximateRecordsCount());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  // --- Registration from mutation methods ---

  /**
   * When setFileSize is called on a page backed by CacheEntryChanges,
   * a SetFileSizeOp must be registered via the atomic operation.
   */
  @Test
  public void testSetFileSizeRegistersOp() {
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

      var page = new PaginatedCollectionStateV2(changes);
      page.setFileSize(55);

      // Verify a PageOperation was registered
      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(
          "Expected SetFileSizeOp but got " + registeredOp.getClass().getName(),
          registeredOp instanceof PaginatedCollectionStateV2SetFileSizeOp);

      var fileSizeOp = (PaginatedCollectionStateV2SetFileSizeOp) registeredOp;
      Assert.assertEquals(55, fileSizeOp.getSize());
      Assert.assertEquals(7, fileSizeOp.getPageIndex());
      Assert.assertEquals(42, fileSizeOp.getFileId());
      Assert.assertEquals(new LogSequenceNumber(1, 100), fileSizeOp.getInitialLsn());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * When setApproximateRecordsCount is called on a page backed by CacheEntryChanges,
   * a SetApproxRecordsCountOp must be registered via the atomic operation.
   */
  @Test
  public void testSetApproxRecordsCountRegistersOp() {
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

      var page = new PaginatedCollectionStateV2(changes);
      page.setApproximateRecordsCount(12345L);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(
          registeredOp instanceof PaginatedCollectionStateV2SetApproxRecordsCountOp);

      var countOp = (PaginatedCollectionStateV2SetApproxRecordsCountOp) registeredOp;
      Assert.assertEquals(12345L, countOp.getCount());
      Assert.assertEquals(new LogSequenceNumber(2, 200), countOp.getInitialLsn());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // --- Redo suppression (D4) ---

  /**
   * When mutation methods are called on a page backed by a plain CacheEntry
   * (not CacheEntryChanges), no PageOperation must be registered.
   * This is the redo path — changes == null, no active atomic operation.
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
      // Plain CacheEntry — not CacheEntryChanges. No atomic operation context.
      var page = new PaginatedCollectionStateV2(entry);
      // These calls should NOT throw or attempt to register anything
      page.setFileSize(10);
      page.setApproximateRecordsCount(20L);

      // Verify values were written directly to the buffer
      Assert.assertEquals(10, page.getFileSize());
      Assert.assertEquals(20L, page.getApproximateRecordsCount());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // --- Equals and hashCode ---

  @Test
  public void testSetFileSizeOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new PaginatedCollectionStateV2SetFileSizeOp(5, 10, 15, lsn, 42);
    var op2 = new PaginatedCollectionStateV2SetFileSizeOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different size
    var op3 = new PaginatedCollectionStateV2SetFileSizeOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetApproxRecordsCountOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new PaginatedCollectionStateV2SetApproxRecordsCountOp(
        5, 10, 15, lsn, 42L);
    var op2 = new PaginatedCollectionStateV2SetApproxRecordsCountOp(
        5, 10, 15, lsn, 42L);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different count
    var op3 = new PaginatedCollectionStateV2SetApproxRecordsCountOp(
        5, 10, 15, lsn, 99L);
    Assert.assertNotEquals(op1, op3);
  }
}
