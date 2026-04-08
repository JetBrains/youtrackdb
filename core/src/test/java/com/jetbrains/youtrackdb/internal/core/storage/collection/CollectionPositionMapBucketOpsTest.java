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
      bucket.init();
      bucket.allocate();
      bucket.set(0, new PositionEntry(42, 7, 1L));
      bucket.updateVersion(0, 10L);
      bucket.remove(0, 20L);
      // No throws — plain CacheEntry, no registration
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
