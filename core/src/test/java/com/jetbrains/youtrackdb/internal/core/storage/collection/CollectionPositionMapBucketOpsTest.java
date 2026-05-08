package com.jetbrains.youtrackdb.internal.core.storage.collection;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
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
import org.mockito.ArgumentMatchers;

/**
 * Tests for the 5 CollectionPositionMapBucket logical WAL operations.
 * Covers serialization roundtrip, factory roundtrip, redo correctness,
 * registration from mutation methods, and no-double-emission for remove().
 */
public class CollectionPositionMapBucketOpsTest {

  private static final ByteBufferPool BUFFER_POOL = ByteBufferPool.instance(null);

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPositionMapBucketInitOp.RECORD_ID,
        CollectionPositionMapBucketInitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPositionMapBucketAllocateOp.RECORD_ID,
        CollectionPositionMapBucketAllocateOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPositionMapBucketSetOp.RECORD_ID,
        CollectionPositionMapBucketSetOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPositionMapBucketRemoveOp.RECORD_ID,
        CollectionPositionMapBucketRemoveOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPositionMapBucketUpdateVersionOp.RECORD_ID,
        CollectionPositionMapBucketUpdateVersionOp.class);
  }

  private CacheEntry createRawCacheEntry() {
    var pointer = BUFFER_POOL.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, BUFFER_POOL, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();
    return entry;
  }

  private void releaseEntry(CacheEntry entry) {
    entry.releaseExclusiveLock();
    entry.getCachePointer().decrementReferrer();
  }

  // --- Record ID tests ---

  @Test
  public void testRecordIds() {
    Assert.assertEquals(208, CollectionPositionMapBucketInitOp.RECORD_ID);
    Assert.assertEquals(209, CollectionPositionMapBucketAllocateOp.RECORD_ID);
    Assert.assertEquals(210, CollectionPositionMapBucketSetOp.RECORD_ID);
    Assert.assertEquals(211, CollectionPositionMapBucketRemoveOp.RECORD_ID);
    Assert.assertEquals(212, CollectionPositionMapBucketUpdateVersionOp.RECORD_ID);
  }

  // --- InitOp ---

  @Test
  public void testInitOpSerializationAndFactory() {
    var original = new CollectionPositionMapBucketInitOp(
        1, 2, 3, new LogSequenceNumber(10, 20));
    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);
    var result = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(result instanceof CollectionPositionMapBucketInitOp);
  }

  @Test
  public void testInitRedoCorrectness() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var page1 = new CollectionPositionMapBucket(entry1);
      page1.init();

      var page2 = new CollectionPositionMapBucket(entry2);
      new CollectionPositionMapBucketInitOp(0, 0, 0, new LogSequenceNumber(0, 0)).redo(page2);

      Assert.assertEquals(page1.getSize(), page2.getSize());
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  // --- AllocateOp ---

  @Test
  public void testAllocateOpSerializationAndFactory() {
    var original = new CollectionPositionMapBucketAllocateOp(
        1, 2, 3, new LogSequenceNumber(10, 20));
    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);
    var result = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(result instanceof CollectionPositionMapBucketAllocateOp);
  }

  @Test
  public void testAllocateRedoDeterministic() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var page1 = new CollectionPositionMapBucket(entry1);
      page1.init();
      var idx1 = page1.allocate();

      var page2 = new CollectionPositionMapBucket(entry2);
      page2.init();
      new CollectionPositionMapBucketAllocateOp(
          0, 0, 0, new LogSequenceNumber(0, 0)).redo(page2);

      Assert.assertEquals(idx1, 0); // First allocate always returns 0
      Assert.assertEquals(page1.getSize(), page2.getSize());
      Assert.assertEquals(page1.getStatus(0), page2.getStatus(0));
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  // --- SetOp ---

  @Test
  public void testSetOpSerializationRoundtrip() {
    var original = new CollectionPositionMapBucketSetOp(
        10, 20, 30, new LogSequenceNumber(5, 100), 3, 42L, 7, 99L);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new CollectionPositionMapBucketSetOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(3, deserialized.getIndex());
    Assert.assertEquals(42L, deserialized.getEntryPageIndex());
    Assert.assertEquals(7, deserialized.getRecordPosition());
    Assert.assertEquals(99L, deserialized.getRecordVersion());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetOpFactoryRoundtrip() {
    var original = new CollectionPositionMapBucketSetOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 0, 100L, 5, 77L);
    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);
    var result = (CollectionPositionMapBucketSetOp) WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertEquals(100L, result.getEntryPageIndex());
    Assert.assertEquals(77L, result.getRecordVersion());
  }

  @Test
  public void testSetRedoCorrectness() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var page1 = new CollectionPositionMapBucket(entry1);
      page1.init();
      page1.allocate();
      page1.set(0, new PositionEntry(42, 7, 99L));

      var page2 = new CollectionPositionMapBucket(entry2);
      page2.init();
      page2.allocate();
      new CollectionPositionMapBucketSetOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, 42, 7, 99L).redo(page2);

      var e1 = page1.get(0);
      var e2 = page2.get(0);
      Assert.assertNotNull(e1);
      Assert.assertNotNull(e2);
      Assert.assertEquals(e1.getPageIndex(), e2.getPageIndex());
      Assert.assertEquals(e1.getRecordPosition(), e2.getRecordPosition());
      Assert.assertEquals(e1.getRecordVersion(), e2.getRecordVersion());
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  // --- RemoveOp ---

  @Test
  public void testRemoveOpSerializationRoundtrip() {
    var original = new CollectionPositionMapBucketRemoveOp(
        10, 20, 30, new LogSequenceNumber(5, 100), 3, 77L);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new CollectionPositionMapBucketRemoveOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(3, deserialized.getIndex());
    Assert.assertEquals(77L, deserialized.getDeletionVersion());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveRedoCorrectness() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var page1 = new CollectionPositionMapBucket(entry1);
      page1.init();
      page1.allocate();
      page1.set(0, new PositionEntry(42, 7, 1L));
      page1.remove(0, 99L);

      var page2 = new CollectionPositionMapBucket(entry2);
      page2.init();
      page2.allocate();
      page2.set(0, new PositionEntry(42, 7, 1L));
      new CollectionPositionMapBucketRemoveOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, 99L).redo(page2);

      Assert.assertEquals(page1.getStatus(0), page2.getStatus(0));
      Assert.assertEquals(page1.getRecordVersionAt(0), page2.getRecordVersionAt(0));
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  /**
   * Verify that remove() registers exactly 1 op (RemoveOp), not RemoveOp + UpdateVersionOp.
   */
  @Test
  public void testRemoveRegistersOnlyOneOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);
    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 50));

      var bucket = new CollectionPositionMapBucket(changes);
      bucket.init();
      bucket.allocate();
      bucket.set(0, new PositionEntry(42, 7, 1L));

      reset(atomicOp);

      bucket.remove(0, 99L);

      // Exactly 1 registration call — the RemoveOp
      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp, times(1)).registerPageOperation(
          ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong(),
          opCaptor.capture());
      Assert.assertTrue(opCaptor.getValue() instanceof CollectionPositionMapBucketRemoveOp);
    } finally {
      releaseEntry(entry);
    }
  }

  // --- UpdateVersionOp ---

  @Test
  public void testUpdateVersionOpSerializationRoundtrip() {
    var original = new CollectionPositionMapBucketUpdateVersionOp(
        10, 20, 30, new LogSequenceNumber(5, 100), 2, 42L);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new CollectionPositionMapBucketUpdateVersionOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getIndex());
    Assert.assertEquals(42L, deserialized.getRecordVersion());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testUpdateVersionRedoCorrectness() {
    var entry1 = createRawCacheEntry();
    var entry2 = createRawCacheEntry();
    try {
      var page1 = new CollectionPositionMapBucket(entry1);
      page1.init();
      page1.allocate();
      page1.set(0, new PositionEntry(42, 7, 1L));
      page1.updateVersion(0, 55L);

      var page2 = new CollectionPositionMapBucket(entry2);
      page2.init();
      page2.allocate();
      page2.set(0, new PositionEntry(42, 7, 1L));
      new CollectionPositionMapBucketUpdateVersionOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, 55L).redo(page2);

      Assert.assertEquals(55L, page1.getRecordVersionAt(0));
      Assert.assertEquals(55L, page2.getRecordVersionAt(0));
    } finally {
      releaseEntry(entry1);
      releaseEntry(entry2);
    }
  }

  // --- Redo suppression ---

  @Test
  public void testNoRegistrationDuringRedoPath() {
    var entry = createRawCacheEntry();
    try {
      var bucket = new CollectionPositionMapBucket(entry);
      // Plain CacheEntry — instanceof CacheEntryChanges guard prevents registration
      bucket.init();
      Assert.assertEquals(0, bucket.getSize());

      bucket.allocate();
      Assert.assertEquals(1, bucket.getSize());
      Assert.assertEquals(CollectionPositionMapBucket.ALLOCATED, bucket.getStatus(0));

      bucket.set(0, new PositionEntry(42, 7, 1L));
      Assert.assertEquals(CollectionPositionMapBucket.FILLED, bucket.getStatus(0));
      var e = bucket.get(0);
      Assert.assertNotNull(e);
      Assert.assertEquals(42, e.getPageIndex());
      Assert.assertEquals(7, e.getRecordPosition());
      Assert.assertEquals(1L, e.getRecordVersion());

      bucket.updateVersion(0, 10L);
      Assert.assertEquals(10L, bucket.getRecordVersionAt(0));

      bucket.remove(0, 20L);
      Assert.assertEquals(CollectionPositionMapBucket.REMOVED, bucket.getStatus(0));
      Assert.assertEquals(20L, bucket.getRecordVersionAt(0));
    } finally {
      releaseEntry(entry);
    }
  }

  // --- Equals/hashCode ---

  @Test
  public void testSetOpEquals() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new CollectionPositionMapBucketSetOp(5, 10, 15, lsn, 0, 42, 7, 99L);
    var op2 = new CollectionPositionMapBucketSetOp(5, 10, 15, lsn, 0, 42, 7, 99L);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new CollectionPositionMapBucketSetOp(5, 10, 15, lsn, 0, 42, 7, 100L);
    Assert.assertNotEquals(op1, op3);
  }

  // --- toString coverage for all 5 ops ---

  @Test
  public void testInitOpToString() {
    // toString() on all CollectionPositionMapBucket ops must return a non-null,
    // non-empty string that is identifiable in logs.
    var op = new CollectionPositionMapBucketInitOp(
        1, 2, 3, new LogSequenceNumber(10, 20));
    Assert.assertFalse(op.toString().isEmpty());
  }

  @Test
  public void testAllocateOpToString() {
    var op = new CollectionPositionMapBucketAllocateOp(
        1, 2, 3, new LogSequenceNumber(10, 20));
    Assert.assertFalse(op.toString().isEmpty());
  }

  @Test
  public void testSetOpToString() {
    var op = new CollectionPositionMapBucketSetOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 0, 42, 7, 99L);
    Assert.assertFalse(op.toString().isEmpty());
  }

  @Test
  public void testRemoveOpToString() {
    var op = new CollectionPositionMapBucketRemoveOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 3, 77L);
    Assert.assertFalse(op.toString().isEmpty());
  }

  @Test
  public void testUpdateVersionOpToString() {
    var op = new CollectionPositionMapBucketUpdateVersionOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 2, 42L);
    Assert.assertFalse(op.toString().isEmpty());
  }

  // --- PositionEntry equals / hashCode / toString ---

  @Test
  public void testPositionEntryEqualsHashCodeToString() {
    // Two PositionEntry instances with the same fields must be equal and have the same hashCode.
    var e1 = new CollectionPositionMapBucket.PositionEntry(10L, 5, 99L);
    var e2 = new CollectionPositionMapBucket.PositionEntry(10L, 5, 99L);
    Assert.assertEquals(e1, e2);
    Assert.assertEquals(e1.hashCode(), e2.hashCode());

    // Different pageIndex makes them unequal.
    var e3 = new CollectionPositionMapBucket.PositionEntry(11L, 5, 99L);
    Assert.assertNotEquals(e1, e3);

    // Different recordPosition makes them unequal.
    var e4 = new CollectionPositionMapBucket.PositionEntry(10L, 6, 99L);
    Assert.assertNotEquals(e1, e4);

    // Different recordVersion makes them unequal.
    var e5 = new CollectionPositionMapBucket.PositionEntry(10L, 5, 100L);
    Assert.assertNotEquals(e1, e5);

    // toString must contain the field values.
    var str = e1.toString();
    Assert.assertTrue(str.contains("10"));
    Assert.assertTrue(str.contains("5"));
    Assert.assertTrue(str.contains("99"));
  }

  // --- add() method ---

  @Test
  public void testAddMethodWritesEntryDirectly() {
    // add() writes a raw entry (status, pageIndex, recordPosition, recordVersion)
    // without registering a WAL op. The written entry should be readable via
    // getEntryWithStatus() and getRecordVersionAt().
    var entry = createRawCacheEntry();
    try {
      var bucket = new CollectionPositionMapBucket(entry);
      bucket.init();

      // Write two entries using add() with FILLED and REMOVED status.
      var idx0 = bucket.add(42L, 7, 100L, CollectionPositionMapBucket.FILLED);
      var idx1 = bucket.add(99L, 3, 200L, CollectionPositionMapBucket.REMOVED);

      Assert.assertEquals(0, idx0);
      Assert.assertEquals(1, idx1);
      Assert.assertEquals(2, bucket.getSize());

      // FILLED entry should be readable as a PositionEntry.
      var e0 = bucket.getEntryWithStatus(idx0);
      Assert.assertEquals(CollectionPositionMapBucket.FILLED, e0.status());
      Assert.assertNotNull(e0.entry());
      Assert.assertEquals(42L, e0.entry().getPageIndex());
      Assert.assertEquals(7, e0.entry().getRecordPosition());
      Assert.assertEquals(100L, e0.entry().getRecordVersion());

      // REMOVED entry should carry the deletion version.
      var e1 = bucket.getEntryWithStatus(idx1);
      Assert.assertEquals(CollectionPositionMapBucket.REMOVED, e1.status());
      Assert.assertNotNull(e1.entry());
      Assert.assertEquals(99L, e1.entry().getPageIndex());

      // Confirm getRecordVersionAt returns the stored version.
      Assert.assertEquals(100L, bucket.getRecordVersionAt(idx0));
      Assert.assertEquals(200L, bucket.getRecordVersionAt(idx1));
    } finally {
      releaseEntry(entry);
    }
  }

  // --- getEntryWithStatus() edge cases ---

  @Test
  public void testGetEntryWithStatusOutOfBoundsReturnsNotExistent() {
    // getEntryWithStatus() on an index past the bucket size must return NOT_EXISTENT
    // and a null entry — this exercises the index-out-of-range branch.
    var entry = createRawCacheEntry();
    try {
      var bucket = new CollectionPositionMapBucket(entry);
      bucket.init();

      // Bucket is empty; querying index 0 must return NOT_EXISTENT.
      var status = bucket.getEntryWithStatus(0);
      Assert.assertEquals(CollectionPositionMapBucket.NOT_EXISTENT, status.status());
      Assert.assertNull(status.entry());
    } finally {
      releaseEntry(entry);
    }
  }

  @Test
  public void testGetEntryWithStatusAllocatedReturnsNullEntry() {
    // An ALLOCATED slot should return ALLOCATED status and a null entry because
    // the slot has not been filled yet — exercises the ALLOCATED branch in
    // getEntryWithStatus().
    var entry = createRawCacheEntry();
    try {
      var bucket = new CollectionPositionMapBucket(entry);
      bucket.init();
      bucket.allocate();

      var status = bucket.getEntryWithStatus(0);
      Assert.assertEquals(CollectionPositionMapBucket.ALLOCATED, status.status());
      Assert.assertNull(status.entry());
    } finally {
      releaseEntry(entry);
    }
  }

  // --- exists() method ---

  @Test
  public void testExistsReturnsTrueForFilledEntry() {
    // exists() must return true for a FILLED slot and false for an index past
    // the bucket size (the out-of-range branch).
    var entry = createRawCacheEntry();
    try {
      var bucket = new CollectionPositionMapBucket(entry);
      bucket.init();
      bucket.allocate();
      bucket.set(0, new CollectionPositionMapBucket.PositionEntry(42L, 7, 100L));

      Assert.assertTrue(bucket.exists(0));

      // Out-of-range index must return false.
      Assert.assertFalse(bucket.exists(1));
    } finally {
      releaseEntry(entry);
    }
  }

  @Test
  public void testExistsReturnsFalseForRemovedEntry() {
    // exists() must return false for an entry that has been removed — exercises
    // the non-FILLED branch in exists().
    var entry = createRawCacheEntry();
    try {
      var bucket = new CollectionPositionMapBucket(entry);
      bucket.init();
      bucket.allocate();
      bucket.set(0, new CollectionPositionMapBucket.PositionEntry(42L, 7, 100L));
      bucket.remove(0, 999L);

      Assert.assertFalse(bucket.exists(0));
    } finally {
      releaseEntry(entry);
    }
  }

  @Test
  public void testRemoveOpEquals() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new CollectionPositionMapBucketRemoveOp(5, 10, 15, lsn, 3, 77L);
    var op2 = new CollectionPositionMapBucketRemoveOp(5, 10, 15, lsn, 3, 77L);
    Assert.assertEquals(op1, op2);
    var op3 = new CollectionPositionMapBucketRemoveOp(5, 10, 15, lsn, 3, 88L);
    Assert.assertNotEquals(op1, op3);
  }
}
