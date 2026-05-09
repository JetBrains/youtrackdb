package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.CacheEntryChanges;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for CellBTreeMultiValueV2Bucket bulk PageOperation subclasses: addAllLeafEntries,
 * addAllNonLeafEntries, shrinkLeafEntries, shrinkNonLeafEntries. Covers record ID verification,
 * serialization roundtrip, WALRecordsFactory integration, redo correctness, registration in
 * addAll/shrink methods, and redo suppression.
 */
public class BTreeMVBucketV2BulkOpsTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2AddAllLeafEntriesOp.RECORD_ID,
        BTreeMVBucketV2AddAllLeafEntriesOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2AddAllNonLeafEntriesOp.RECORD_ID,
        BTreeMVBucketV2AddAllNonLeafEntriesOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2ShrinkLeafEntriesOp.RECORD_ID,
        BTreeMVBucketV2ShrinkLeafEntriesOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVBucketV2ShrinkNonLeafEntriesOp.RECORD_ID,
        BTreeMVBucketV2ShrinkNonLeafEntriesOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testRecordIds() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_LEAF_ENTRIES_OP,
        BTreeMVBucketV2AddAllLeafEntriesOp.RECORD_ID);
    Assert.assertEquals(260, BTreeMVBucketV2AddAllLeafEntriesOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_NON_LEAF_ENTRIES_OP,
        BTreeMVBucketV2AddAllNonLeafEntriesOp.RECORD_ID);
    Assert.assertEquals(261, BTreeMVBucketV2AddAllNonLeafEntriesOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_LEAF_ENTRIES_OP,
        BTreeMVBucketV2ShrinkLeafEntriesOp.RECORD_ID);
    Assert.assertEquals(262, BTreeMVBucketV2ShrinkLeafEntriesOp.RECORD_ID);

    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_NON_LEAF_ENTRIES_OP,
        BTreeMVBucketV2ShrinkNonLeafEntriesOp.RECORD_ID);
    Assert.assertEquals(263, BTreeMVBucketV2ShrinkNonLeafEntriesOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testAddAllLeafEntriesOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var entries = List.of(
        new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
            new byte[] {1, 2, 3}, 42L, 2,
            List.of(
                new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 100L),
                new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 200L))),
        new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
            new byte[] {4, 5}, 99L, 0, List.of()));
    var original = new BTreeMVBucketV2AddAllLeafEntriesOp(10, 20, 30, initialLsn, entries);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVBucketV2AddAllLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(2, deserialized.getEntries().size());
    Assert.assertEquals(42L, deserialized.getEntries().get(0).mId);
    Assert.assertEquals(2, deserialized.getEntries().get(0).values.size());
    Assert.assertEquals(0, deserialized.getEntries().get(1).values.size());
  }

  @Test
  public void testAddAllLeafEntriesOpEmptyList() {
    var initialLsn = new LogSequenceNumber(1, 10);
    var original = new BTreeMVBucketV2AddAllLeafEntriesOp(1, 2, 3, initialLsn, List.of());

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2AddAllLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(0, deserialized.getEntries().size());
    // Wire-format pin: byte-level entry-count must be 0. Layout: 1 (toStream offset) +
    // 8 (operationUnitId) + 8 (pageIndex) + 8 (fileId) + 8 (initialLsn.segment) +
    // 4 (initialLsn.position) = 37, where the entry-count int begins.
    Assert.assertEquals(
        "empty list must serialise entry-count == 0 at the wire-format offset",
        0, IntegerSerializer.deserializeNative(content, 37));
  }

  @Test
  public void testAddAllNonLeafEntriesOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(6, 300);
    var entries = List.of(
        new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
            new byte[] {10, 20}, 1, 2),
        new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
            new byte[] {30}, 3, 4));
    var original = new BTreeMVBucketV2AddAllNonLeafEntriesOp(10, 20, 30, initialLsn, entries);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVBucketV2AddAllNonLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(2, deserialized.getEntries().size());
    Assert.assertEquals(1, deserialized.getEntries().get(0).leftChild);
    Assert.assertEquals(4, deserialized.getEntries().get(1).rightChild);
  }

  @Test
  public void testShrinkLeafEntriesOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 400);
    var retained = List.of(
        new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
            new byte[] {1}, 10L, 1,
            List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 3, 50L))));
    var original = new BTreeMVBucketV2ShrinkLeafEntriesOp(5, 10, 15, initialLsn, retained);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2ShrinkLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(1, deserialized.getRetainedEntries().size());
  }

  @Test
  public void testShrinkNonLeafEntriesOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 500);
    var retained = List.of(
        new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
            new byte[] {1, 2}, 10, 20));
    var original =
        new BTreeMVBucketV2ShrinkNonLeafEntriesOp(5, 10, 15, initialLsn, retained);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2ShrinkNonLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(1, deserialized.getRetainedEntries().size());
    Assert.assertEquals(10, deserialized.getRetainedEntries().get(0).leftChild);
    Assert.assertEquals(20, deserialized.getRetainedEntries().get(0).rightChild);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testAddAllLeafEntriesOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var entries = List.of(
        new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
            new byte[] {1, 2}, 42L, 1,
            List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 100L))));
    var original = new BTreeMVBucketV2AddAllLeafEntriesOp(10, 20, 30, initialLsn, entries);

    var serialized = WALRecordsFactory.toStream(original);
    var bytes = new byte[serialized.limit()];
    serialized.get(0, bytes);
    var deserialized = WALRecordsFactory.INSTANCE.fromStream(bytes);

    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2AddAllLeafEntriesOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddAllNonLeafEntriesOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(6, 300);
    var entries = List.of(
        new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
            new byte[] {10}, 1, 2));
    var original = new BTreeMVBucketV2AddAllNonLeafEntriesOp(10, 20, 30, initialLsn, entries);

    var serialized = WALRecordsFactory.toStream(original);
    var bytes = new byte[serialized.limit()];
    serialized.get(0, bytes);
    var deserialized = WALRecordsFactory.INSTANCE.fromStream(bytes);

    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2AddAllNonLeafEntriesOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testShrinkLeafEntriesOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 400);
    var retained = List.of(
        new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
            new byte[] {1}, 10L, 1,
            List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 3, 50L))));
    var original = new BTreeMVBucketV2ShrinkLeafEntriesOp(5, 10, 15, initialLsn, retained);

    var serialized = WALRecordsFactory.toStream(original);
    var bytes = new byte[serialized.limit()];
    serialized.get(0, bytes);
    var deserialized = WALRecordsFactory.INSTANCE.fromStream(bytes);

    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2ShrinkLeafEntriesOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testShrinkNonLeafEntriesOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 500);
    var retained = List.of(
        new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
            new byte[] {1, 2}, 10, 20));
    var original =
        new BTreeMVBucketV2ShrinkNonLeafEntriesOp(5, 10, 15, initialLsn, retained);

    var serialized = WALRecordsFactory.toStream(original);
    var bytes = new byte[serialized.limit()];
    serialized.get(0, bytes);
    var deserialized = WALRecordsFactory.INSTANCE.fromStream(bytes);

    Assert.assertTrue(deserialized instanceof BTreeMVBucketV2ShrinkNonLeafEntriesOp);
    Assert.assertEquals(original, deserialized);
  }

  // ---------- Redo correctness tests (byte-level comparison) ----------

  /**
   * addAll leaf redo: apply addAll via direct API on page1, apply via redo on page2,
   * compare byte-level.
   */
  @Test
  public void testAddAllLeafEntriesRedoCorrectness() {
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
      var leafEntries = List.of(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 42L,
              List.of(new RecordId(5, 100L)), 1),
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 3, 5, 6, 7}, 99L,
              List.of(new RecordId(6, 200L)), 1));

      // Page 1: direct API
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.addAll(leafEntries);

      // Page 2: init + redo
      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);

      var entryData = new ArrayList<BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData>();
      for (var le : leafEntries) {
        var ridData = new ArrayList<BTreeMVBucketV2AddAllLeafEntriesOp.RidData>();
        for (var rid : le.values) {
          ridData.add(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData(
              (short) rid.getCollectionId(), rid.getCollectionPosition()));
        }
        entryData.add(new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
            le.key, le.mId, le.entriesCount, ridData));
      }

      var op = new BTreeMVBucketV2AddAllLeafEntriesOp(
          0, 0, 0, new LogSequenceNumber(0, 0), entryData);
      op.redo(new DurablePage(entry2));

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

  /**
   * addAll non-leaf redo: apply addAll via direct API on page1, apply via redo on page2,
   * compare byte-level.
   */
  @Test
  public void testAddAllNonLeafEntriesRedoCorrectness() {
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
      var nonLeafEntries = List.of(
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 3, 1, 2, 3}, 1, 2),
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 3, 4, 5, 6}, 3, 4));

      // Page 1: direct API
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);
      page1.addAll(nonLeafEntries);

      // Page 2: init + redo
      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(false);

      var entryData = List.of(
          new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
              new byte[] {0, 0, 0, 3, 1, 2, 3}, 1, 2),
          new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
              new byte[] {0, 0, 0, 3, 4, 5, 6}, 3, 4));

      var op = new BTreeMVBucketV2AddAllNonLeafEntriesOp(
          0, 0, 0, new LogSequenceNumber(0, 0), entryData);
      op.redo(new DurablePage(entry2));

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

  /**
   * addAll leaf redo with zero-values entry: verifies the null-RID path in doCreateMainLeafEntry.
   */
  @Test
  public void testAddAllLeafEntriesRedoWithEmptyValues() {
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
      // Entry with empty values list (null RID path)
      var leafEntries = List.of(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 42L, List.of(), 0));

      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.addAll(leafEntries);

      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);

      var entryData = List.of(
          new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 42L, 0, List.of()));

      new BTreeMVBucketV2AddAllLeafEntriesOp(
          0, 0, 0, new LogSequenceNumber(0, 0), entryData)
          .redo(new DurablePage(entry2));

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
   * addAll leaf redo with multi-value entry (3 RIDs): exercises the appendNewLeafEntries code
   * path during redo, which allocates linked-list blocks for RIDs beyond the first.
   */
  @Test
  public void testAddAllLeafEntriesRedoWithMultipleRids() {
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
      var rids = new ArrayList<RID>();
      rids.add(new RecordId(5, 100L));
      rids.add(new RecordId(5, 200L));
      rids.add(new RecordId(5, 300L));
      var leafEntries = List.of(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 42L, rids, 3));

      // Page 1: direct API
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.addAll(leafEntries);

      // Page 2: init + redo
      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);

      var ridData = List.of(
          new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 100L),
          new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 200L),
          new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 300L));
      var entryData = List.of(
          new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 42L, 3, ridData));

      new BTreeMVBucketV2AddAllLeafEntriesOp(
          0, 0, 0, new LogSequenceNumber(0, 0), entryData)
          .redo(new DurablePage(entry2));

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
   * shrink leaf redo: add 3 entries on page1, shrink to 1 entry. Apply same shrink via redo on
   * page2 (which also starts with 3 entries). Compare byte-level.
   */
  @Test
  public void testShrinkLeafEntriesRedoCorrectness() {
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
      var threeEntries = List.of(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 10L,
              List.of(new RecordId(1, 1L)), 1),
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 5, 6, 7, 8}, 20L,
              List.of(new RecordId(2, 2L)), 1),
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 9, 10, 11, 12}, 30L,
              List.of(new RecordId(3, 3L)), 1));

      // Page 1: addAll 3 entries, then resetAndAddAll with retained first entry
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(true);
      page1.addAll(threeEntries);
      page1.resetAndAddAll(List.of(threeEntries.get(0)));

      // Page 2: addAll 3 entries, then redo shrink
      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(true);
      page2.addAll(threeEntries);

      var retained = List.of(
          new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 10L, 1,
              List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 1, 1L))));

      new BTreeMVBucketV2ShrinkLeafEntriesOp(
          0, 0, 0, new LogSequenceNumber(0, 0), retained)
          .redo(new DurablePage(entry2));

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
   * shrink non-leaf redo: add 3 entries, shrink to 2. Compare byte-level.
   */
  @Test
  public void testShrinkNonLeafEntriesRedoCorrectness() {
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
      var threeEntries = List.of(
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 3, 1, 2, 3}, 1, 2),
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 3, 4, 5, 6}, 3, 4),
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 3, 7, 8, 9}, 5, 6));

      var retained = List.of(threeEntries.get(0), threeEntries.get(1));

      // Page 1: addAll 3 entries, then resetAndAddAll with retained 2 entries
      var page1 = new CellBTreeMultiValueV2Bucket<>(entry1);
      page1.init(false);
      page1.addAll(threeEntries);
      page1.resetAndAddAll(retained);

      // Page 2: addAll 3 entries, then redo shrink
      var page2 = new CellBTreeMultiValueV2Bucket<>(entry2);
      page2.init(false);
      page2.addAll(threeEntries);

      var retainedData = List.of(
          new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
              new byte[] {0, 0, 0, 3, 1, 2, 3}, 1, 2),
          new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
              new byte[] {0, 0, 0, 3, 4, 5, 6}, 3, 4));

      new BTreeMVBucketV2ShrinkNonLeafEntriesOp(
          0, 0, 0, new LogSequenceNumber(0, 0), retainedData)
          .redo(new DurablePage(entry2));

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

  // ---------- Registration tests ----------

  @Test
  public void testAddAllLeafRegistersOp() {
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

      var bucket = new CellBTreeMultiValueV2Bucket<>(changes);
      bucket.init(true);
      reset(atomicOp);

      bucket.addAll(List.of(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 42L,
              List.of(new RecordId(5, 100L)), 1)));

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          eq(42L),
          eq(7L),
          captor.capture());
      Assert.assertTrue(captor.getValue() instanceof BTreeMVBucketV2AddAllLeafEntriesOp);
      var leafOp = (BTreeMVBucketV2AddAllLeafEntriesOp) captor.getValue();
      Assert.assertEquals(1, leafOp.getEntries().size());
      Assert.assertEquals(42L, leafOp.getEntries().get(0).mId);
      Assert.assertEquals(1, leafOp.getEntries().get(0).values.size());
      Assert.assertEquals((short) 5, leafOp.getEntries().get(0).values.get(0).collectionId());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testAddAllNonLeafRegistersOp() {
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

      var bucket = new CellBTreeMultiValueV2Bucket<>(changes);
      bucket.init(false);
      reset(atomicOp);

      bucket.addAll(List.of(
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 3, 1, 2, 3}, 1, 2)));

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          eq(42L),
          eq(7L),
          captor.capture());
      Assert.assertTrue(captor.getValue() instanceof BTreeMVBucketV2AddAllNonLeafEntriesOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testShrinkLeafRegistersOp() {
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

      var bucket = new CellBTreeMultiValueV2Bucket<>(changes);
      bucket.init(true);
      // Add 2 entries with IntegerSerializer-compatible 4-byte keys, then shrink to 1
      bucket.addAll(List.of(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 1}, 10L,
              List.of(new RecordId(1, 1L)), 1),
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 2}, 20L,
              List.of(new RecordId(2, 2L)), 1)));
      reset(atomicOp);

      //noinspection unchecked,rawtypes
      bucket.shrink(1, (BinarySerializer) IntegerSerializer.INSTANCE, null);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          eq(42L),
          eq(7L),
          captor.capture());
      Assert.assertTrue(captor.getValue() instanceof BTreeMVBucketV2ShrinkLeafEntriesOp);
      var shrinkOp = (BTreeMVBucketV2ShrinkLeafEntriesOp) captor.getValue();
      Assert.assertEquals(1, shrinkOp.getRetainedEntries().size());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testShrinkNonLeafRegistersOp() {
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

      var bucket = new CellBTreeMultiValueV2Bucket<>(changes);
      bucket.init(false);
      bucket.addAll(List.of(
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 1}, 1, 2),
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 2}, 3, 4)));
      reset(atomicOp);

      //noinspection unchecked,rawtypes
      bucket.shrink(1, (BinarySerializer) IntegerSerializer.INSTANCE, null);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          eq(42L),
          eq(7L),
          captor.capture());
      Assert.assertTrue(captor.getValue() instanceof BTreeMVBucketV2ShrinkNonLeafEntriesOp);
      var shrinkOp = (BTreeMVBucketV2ShrinkNonLeafEntriesOp) captor.getValue();
      Assert.assertEquals(1, shrinkOp.getRetainedEntries().size());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression tests ----------

  @Test
  public void testAddAllLeafRedoDoesNotRegisterOp() {
    // Redo uses a plain CacheEntry (not CacheEntryChanges), so no registration should happen.
    // Verifying D4 redo suppression: changes=null => no registerPageOperation call.
    var cacheEntry = createLeafBucketCacheEntry();
    try {
      var bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);

      bucket.addAll(List.of(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 42L,
              List.of(new RecordId(5, 100L)), 1)));

      // No CacheEntryChanges means no registerPageOperation call — nothing to verify
      // but the method should succeed without errors
      Assert.assertEquals(1, bucket.size());
    } finally {
      releaseCacheEntry(cacheEntry);
    }
  }

  @Test
  public void testAddAllNonLeafRedoDoesNotRegisterOp() {
    var cacheEntry = createNonLeafBucketCacheEntry();
    try {
      var bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);

      bucket.addAll(List.of(
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              new byte[] {0, 0, 0, 3, 1, 2, 3}, 1, 2)));

      Assert.assertEquals(1, bucket.size());
    } finally {
      releaseCacheEntry(cacheEntry);
    }
  }

  // ---------- equals/hashCode tests ----------

  @Test
  public void testLeafEntryDataEquals() {
    var a = new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
        new byte[] {1, 2}, 42L, 1,
        List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 100L)));
    var b = new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
        new byte[] {1, 2}, 42L, 1,
        List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 100L)));
    var c = new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
        new byte[] {1, 3}, 42L, 1,
        List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 5, 100L)));

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, c);
  }

  @Test
  public void testNonLeafEntryDataEquals() {
    var a = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {1, 2}, 1, 2);
    var b = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {1, 2}, 1, 2);
    var c = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {1, 2}, 1, 3);

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, c);
  }

  @Test
  public void testAddAllLeafEntriesOpEquals() {
    var lsn = new LogSequenceNumber(1, 100);
    var entries = List.of(
        new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
            new byte[] {1}, 10L, 1, List.of()));
    var a = new BTreeMVBucketV2AddAllLeafEntriesOp(10, 20, 30, lsn, entries);
    var b = new BTreeMVBucketV2AddAllLeafEntriesOp(10, 20, 30, lsn, entries);
    var c = new BTreeMVBucketV2AddAllLeafEntriesOp(10, 20, 30, lsn, List.of());

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, c);
  }

  /**
   * AddAllNonLeafEntriesOp equals/hashCode: equal instances compare equal; different list size
   * returns false (covers the size-check branch); same size but different entry returns false
   * (covers the entry-diff branch in the loop).
   */
  @Test
  public void testAddAllNonLeafEntriesOpEquals() {
    var lsn = new LogSequenceNumber(2, 200);
    var entry1 = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {1}, 1, 2);
    var entry2 = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {2}, 3, 4);

    var a = new BTreeMVBucketV2AddAllNonLeafEntriesOp(10, 20, 30, lsn, List.of(entry1));
    var b = new BTreeMVBucketV2AddAllNonLeafEntriesOp(10, 20, 30, lsn, List.of(entry1));
    // Different size: 1 vs 2 — exercises the size-check early-exit branch
    var diffSize = new BTreeMVBucketV2AddAllNonLeafEntriesOp(
        10, 20, 30, lsn, List.of(entry1, entry2));
    // Same size but different entry — exercises the loop-entry-diff branch
    var diffEntry = new BTreeMVBucketV2AddAllNonLeafEntriesOp(
        10, 20, 30, lsn, List.of(entry2));

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, diffSize);
    Assert.assertNotEquals(a, diffEntry);
  }

  /**
   * AddAllNonLeafEntriesOp: empty-list serialization roundtrip via toStream/fromStream.
   * Covers the "0 iterations" branch of both serialise and deserialise loops.
   */
  @Test
  public void testAddAllNonLeafEntriesOpEmptyList() {
    var initialLsn = new LogSequenceNumber(1, 10);
    var original = new BTreeMVBucketV2AddAllNonLeafEntriesOp(1, 2, 3, initialLsn, List.of());

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2AddAllNonLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(0, deserialized.getEntries().size());
    // Wire-format pin: byte-level entry-count must be 0 at offset 37 (1 toStream offset +
    // 36 bytes of parent header).
    Assert.assertEquals(
        "empty list must serialise entry-count == 0 at the wire-format offset",
        0, IntegerSerializer.deserializeNative(content, 37));
  }

  /**
   * ShrinkLeafEntriesOp equals/hashCode: equal instances compare equal; different retained size
   * returns false; same size but different entry returns false.
   */
  @Test
  public void testShrinkLeafEntriesOpEquals() {
    var lsn = new LogSequenceNumber(3, 300);
    var leafEntry1 = new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
        new byte[] {1}, 10L, 1,
        List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 1, 1L)));
    var leafEntry2 = new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
        new byte[] {2}, 20L, 1,
        List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 2, 2L)));

    var a = new BTreeMVBucketV2ShrinkLeafEntriesOp(5, 10, 15, lsn, List.of(leafEntry1));
    var b = new BTreeMVBucketV2ShrinkLeafEntriesOp(5, 10, 15, lsn, List.of(leafEntry1));
    // Different size: 1 vs 2 — exercises the retained-size-check early-exit branch
    var diffSize = new BTreeMVBucketV2ShrinkLeafEntriesOp(
        5, 10, 15, lsn, List.of(leafEntry1, leafEntry2));
    // Same size but different entry — exercises the loop-entry-diff branch
    var diffEntry = new BTreeMVBucketV2ShrinkLeafEntriesOp(
        5, 10, 15, lsn, List.of(leafEntry2));

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, diffSize);
    Assert.assertNotEquals(a, diffEntry);
  }

  /**
   * ShrinkLeafEntriesOp: empty retained list serialization roundtrip. Covers the "0 iterations"
   * loop branch of both serializeToByteBuffer and deserializeFromByteBuffer.
   */
  @Test
  public void testShrinkLeafEntriesOpEmptyList() {
    var initialLsn = new LogSequenceNumber(1, 10);
    var original = new BTreeMVBucketV2ShrinkLeafEntriesOp(1, 2, 3, initialLsn, List.of());

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2ShrinkLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(0, deserialized.getRetainedEntries().size());
    // Wire-format pin: byte-level retained-count must be 0 at offset 37.
    Assert.assertEquals(
        "empty retained list must serialise count == 0 at the wire-format offset",
        0, IntegerSerializer.deserializeNative(content, 37));
  }

  /**
   * ShrinkNonLeafEntriesOp equals/hashCode: equal instances compare equal; different retained size
   * returns false; same size but different entry returns false.
   */
  @Test
  public void testShrinkNonLeafEntriesOpEquals() {
    var lsn = new LogSequenceNumber(4, 400);
    var nlEntry1 = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {1, 2}, 10, 20);
    var nlEntry2 = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {3, 4}, 30, 40);

    var a = new BTreeMVBucketV2ShrinkNonLeafEntriesOp(5, 10, 15, lsn, List.of(nlEntry1));
    var b = new BTreeMVBucketV2ShrinkNonLeafEntriesOp(5, 10, 15, lsn, List.of(nlEntry1));
    // Different size: 1 vs 2 — exercises the retained-size-check early-exit branch
    var diffSize = new BTreeMVBucketV2ShrinkNonLeafEntriesOp(
        5, 10, 15, lsn, List.of(nlEntry1, nlEntry2));
    // Same size but different entry — exercises the loop-entry-diff branch
    var diffEntry = new BTreeMVBucketV2ShrinkNonLeafEntriesOp(
        5, 10, 15, lsn, List.of(nlEntry2));

    Assert.assertEquals(a, b);
    Assert.assertEquals(a.hashCode(), b.hashCode());
    Assert.assertNotEquals(a, diffSize);
    Assert.assertNotEquals(a, diffEntry);
  }

  /**
   * ShrinkNonLeafEntriesOp: empty retained list serialization roundtrip. Covers the "0 iterations"
   * loop branch of both serializeToByteBuffer and deserializeFromByteBuffer.
   */
  @Test
  public void testShrinkNonLeafEntriesOpEmptyList() {
    var initialLsn = new LogSequenceNumber(1, 10);
    var original = new BTreeMVBucketV2ShrinkNonLeafEntriesOp(1, 2, 3, initialLsn, List.of());

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeMVBucketV2ShrinkNonLeafEntriesOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
    Assert.assertEquals(0, deserialized.getRetainedEntries().size());
    // Wire-format pin: byte-level retained-count must be 0 at offset 37.
    Assert.assertEquals(
        "empty retained list must serialise count == 0 at the wire-format offset",
        0, IntegerSerializer.deserializeNative(content, 37));
  }

  /**
   * The inherited {@code PageOperation.equals} chain compares {@code fileId}, {@code
   * pageIndex}, and {@code operationUnitId} as part of equality, in addition to the
   * subclass-specific entry list. Two ops with the same entry list but differing in any one
   * of those three header fields must NOT be equal. Pinned with one bulk-op class
   * ({@code AddAllNonLeafEntriesOp}); the other three subclasses inherit the same equals
   * chain via {@code super.equals(o)}.
   */
  @Test
  public void testAddAllNonLeafEntriesOpEqualsRespectsHeaderFields() {
    var lsn = new LogSequenceNumber(1, 100);
    var entry = new BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData(
        new byte[] {1, 2}, 1, 2);
    var entries = List.of(entry);

    // Constructor signature: (pageIndex, fileId, operationUnitId, lsn, entries)
    var base = new BTreeMVBucketV2AddAllNonLeafEntriesOp(10, 20, 30, lsn, entries);
    var diffPageIndex = new BTreeMVBucketV2AddAllNonLeafEntriesOp(11, 20, 30, lsn, entries);
    var diffFileId = new BTreeMVBucketV2AddAllNonLeafEntriesOp(10, 21, 30, lsn, entries);
    var diffOperationUnitId =
        new BTreeMVBucketV2AddAllNonLeafEntriesOp(10, 20, 31, lsn, entries);

    Assert.assertNotEquals(
        "ops with different pageIndex but same entries must NOT be equal",
        base, diffPageIndex);
    Assert.assertNotEquals(
        "ops with different fileId but same entries must NOT be equal",
        base, diffFileId);
    Assert.assertNotEquals(
        "ops with different operationUnitId but same entries must NOT be equal",
        base, diffOperationUnitId);
  }

  // ---------- Multi-op redo sequence test ----------

  @Test
  public void testAddAllThenShrinkRedoSequence() {
    // Simulate a split scenario: addAll to new bucket, then shrink original
    var newCacheEntry = createLeafBucketCacheEntry();
    var originalCacheEntry = createLeafBucketCacheEntry();
    try {
      var lsn = new LogSequenceNumber(0, 0);

      // First: addAll 3 entries into the original bucket
      var allEntries = List.of(
          new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
              new byte[] {0, 0, 0, 4, 1, 2, 3, 4}, 10L, 1,
              List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 1, 1L))),
          new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
              new byte[] {0, 0, 0, 4, 5, 6, 7, 8}, 20L, 1,
              List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 2, 2L))),
          new BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData(
              new byte[] {0, 0, 0, 4, 9, 10, 11, 12}, 30L, 1,
              List.of(new BTreeMVBucketV2AddAllLeafEntriesOp.RidData((short) 3, 3L))));

      var addAllOp = new BTreeMVBucketV2AddAllLeafEntriesOp(0, 0, 0, lsn, allEntries);
      addAllOp.redo(new DurablePage(originalCacheEntry));
      Assert.assertEquals(3, new CellBTreeMultiValueV2Bucket<>(originalCacheEntry).size());

      // Second: addAll 2 entries to the new bucket (simulating split target)
      var newBucketEntries = List.of(allEntries.get(1), allEntries.get(2));
      var addAllNewOp =
          new BTreeMVBucketV2AddAllLeafEntriesOp(0, 0, 0, lsn, newBucketEntries);
      addAllNewOp.redo(new DurablePage(newCacheEntry));
      Assert.assertEquals(2, new CellBTreeMultiValueV2Bucket<>(newCacheEntry).size());

      // Third: shrink original to retain only the first entry
      var shrinkRetained = List.of(allEntries.get(0));
      var shrinkOp =
          new BTreeMVBucketV2ShrinkLeafEntriesOp(0, 0, 0, lsn, shrinkRetained);
      shrinkOp.redo(new DurablePage(originalCacheEntry));
      Assert.assertEquals(1, new CellBTreeMultiValueV2Bucket<>(originalCacheEntry).size());
    } finally {
      releaseCacheEntry(newCacheEntry);
      releaseCacheEntry(originalCacheEntry);
    }
  }

  // ---------- Helper methods ----------

  private CacheEntry createLeafBucketCacheEntry() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    var cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    var bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
    bucket.init(true);
    return cacheEntry;
  }

  private CacheEntry createNonLeafBucketCacheEntry() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    var cacheEntry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    cacheEntry.acquireExclusiveLock();
    var bucket = new CellBTreeMultiValueV2Bucket<>(cacheEntry);
    bucket.init(false);
    return cacheEntry;
  }

  /** Releases exclusive lock and direct memory for a cache entry created by the helpers. */
  private static void releaseCacheEntry(CacheEntry cacheEntry) {
    cacheEntry.releaseExclusiveLock();
    cacheEntry.getCachePointer().decrementReferrer();
  }
}
