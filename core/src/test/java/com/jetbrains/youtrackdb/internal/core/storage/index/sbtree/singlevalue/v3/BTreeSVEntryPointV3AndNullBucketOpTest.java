package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import static org.mockito.Mockito.mock;
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
 * Tests for CellBTreeSingleValueEntryPointV3 and CellBTreeSingleValueV3NullBucket logical WAL
 * operations. Covers serialization roundtrip, WALRecordsFactory integration, redo correctness,
 * registration from mutation methods, redo suppression, redo idempotency, and equals/hashCode.
 */
public class BTreeSVEntryPointV3AndNullBucketOpTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVEntryPointV3InitOp.RECORD_ID, BTreeSVEntryPointV3InitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVEntryPointV3SetTreeSizeOp.RECORD_ID,
        BTreeSVEntryPointV3SetTreeSizeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVEntryPointV3SetPagesSizeOp.RECORD_ID,
        BTreeSVEntryPointV3SetPagesSizeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVEntryPointV3SetFreeListHeadOp.RECORD_ID,
        BTreeSVEntryPointV3SetFreeListHeadOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVEntryPointV3SetApproxEntriesCountOp.RECORD_ID,
        BTreeSVEntryPointV3SetApproxEntriesCountOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVNullBucketV3InitOp.RECORD_ID, BTreeSVNullBucketV3InitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVNullBucketV3SetValueOp.RECORD_ID, BTreeSVNullBucketV3SetValueOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVNullBucketV3RemoveValueOp.RECORD_ID,
        BTreeSVNullBucketV3RemoveValueOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testEntryPointInitOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_INIT_OP,
        BTreeSVEntryPointV3InitOp.RECORD_ID);
    Assert.assertEquals(219, BTreeSVEntryPointV3InitOp.RECORD_ID);
  }

  @Test
  public void testEntryPointSetTreeSizeOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_TREE_SIZE_OP,
        BTreeSVEntryPointV3SetTreeSizeOp.RECORD_ID);
    Assert.assertEquals(220, BTreeSVEntryPointV3SetTreeSizeOp.RECORD_ID);
  }

  @Test
  public void testEntryPointSetPagesSizeOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_PAGES_SIZE_OP,
        BTreeSVEntryPointV3SetPagesSizeOp.RECORD_ID);
    Assert.assertEquals(221, BTreeSVEntryPointV3SetPagesSizeOp.RECORD_ID);
  }

  @Test
  public void testEntryPointSetFreeListHeadOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_FREE_LIST_HEAD_OP,
        BTreeSVEntryPointV3SetFreeListHeadOp.RECORD_ID);
    Assert.assertEquals(222, BTreeSVEntryPointV3SetFreeListHeadOp.RECORD_ID);
  }

  @Test
  public void testEntryPointSetApproxEntriesCountOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_APPROX_ENTRIES_COUNT_OP,
        BTreeSVEntryPointV3SetApproxEntriesCountOp.RECORD_ID);
    Assert.assertEquals(296,
        BTreeSVEntryPointV3SetApproxEntriesCountOp.RECORD_ID);
  }

  @Test
  public void testNullBucketInitOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_INIT_OP,
        BTreeSVNullBucketV3InitOp.RECORD_ID);
    Assert.assertEquals(223, BTreeSVNullBucketV3InitOp.RECORD_ID);
  }

  @Test
  public void testNullBucketSetValueOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_SET_VALUE_OP,
        BTreeSVNullBucketV3SetValueOp.RECORD_ID);
    Assert.assertEquals(224, BTreeSVNullBucketV3SetValueOp.RECORD_ID);
  }

  @Test
  public void testNullBucketRemoveValueOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_REMOVE_VALUE_OP,
        BTreeSVNullBucketV3RemoveValueOp.RECORD_ID);
    Assert.assertEquals(225, BTreeSVNullBucketV3RemoveValueOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testEntryPointInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new BTreeSVEntryPointV3InitOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVEntryPointV3InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testEntryPointSetTreeSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var original = new BTreeSVEntryPointV3SetTreeSizeOp(15, 25, 35, initialLsn, 123456789L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVEntryPointV3SetTreeSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(123456789L, deserialized.getSize());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testEntryPointSetPagesSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var original = new BTreeSVEntryPointV3SetPagesSizeOp(7, 14, 21, initialLsn, 42);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVEntryPointV3SetPagesSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(42, deserialized.getPages());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testEntryPointSetFreeListHeadOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(9, 800);
    var original = new BTreeSVEntryPointV3SetFreeListHeadOp(1, 2, 3, initialLsn, 99);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVEntryPointV3SetFreeListHeadOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(99, deserialized.getFreeListHead());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testEntryPointSetApproxEntriesCountOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(11, 900);
    var original = new BTreeSVEntryPointV3SetApproxEntriesCountOp(
        2, 4, 6, initialLsn, 777888999L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVEntryPointV3SetApproxEntriesCountOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(777888999L, deserialized.getCount());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testNullBucketInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(4, 300);
    var original = new BTreeSVNullBucketV3InitOp(11, 22, 33, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVNullBucketV3InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testNullBucketSetValueOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(6, 600);
    var original = new BTreeSVNullBucketV3SetValueOp(
        5, 10, 15, initialLsn, (short) 23, 456789L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVNullBucketV3SetValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals((short) 23, deserialized.getCollectionId());
    Assert.assertEquals(456789L, deserialized.getCollectionPosition());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testNullBucketRemoveValueOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 700);
    var original = new BTreeSVNullBucketV3RemoveValueOp(13, 26, 39, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVNullBucketV3RemoveValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testEntryPointInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new BTreeSVEntryPointV3InitOp(10, 20, 30, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVEntryPointV3InitOp);
    var result = (BTreeSVEntryPointV3InitOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testEntryPointSetTreeSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 2048);
    var original = new BTreeSVEntryPointV3SetTreeSizeOp(11, 22, 33, initialLsn, 987654321L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVEntryPointV3SetTreeSizeOp);
    var result = (BTreeSVEntryPointV3SetTreeSizeOp) deserialized;
    Assert.assertEquals(987654321L, result.getSize());
  }

  @Test
  public void testNullBucketSetValueOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(50, 500);
    var original = new BTreeSVNullBucketV3SetValueOp(
        1, 2, 3, initialLsn, (short) 55, 1234567890L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVNullBucketV3SetValueOp);
    var result = (BTreeSVNullBucketV3SetValueOp) deserialized;
    Assert.assertEquals((short) 55, result.getCollectionId());
    Assert.assertEquals(1234567890L, result.getCollectionPosition());
  }

  // ---------- Redo correctness tests ----------

  /**
   * EntryPoint init: apply directly on page1, redo on page2. Both pages must have
   * identical state (treeSize=0, pagesSize=1, freeListHead=-1).
   */
  @Test
  public void testEntryPointInitRedoCorrectness() {
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
      // Pre-populate both pages with non-default values
      var page1 = new CellBTreeSingleValueEntryPointV3<>(entry1);
      page1.init();
      page1.setTreeSize(999);
      page1.setPagesSize(50);
      page1.setFreeListHead(42);

      var page2 = new CellBTreeSingleValueEntryPointV3<>(entry2);
      page2.init();
      page2.setTreeSize(999);
      page2.setPagesSize(50);
      page2.setFreeListHead(42);

      // Apply init directly on page1
      page1.init();

      // Apply init via redo on page2
      var op = new BTreeSVEntryPointV3InitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page2);

      // Verify both pages have the init state
      Assert.assertEquals(0, page1.getTreeSize());
      Assert.assertEquals(0, page2.getTreeSize());
      Assert.assertEquals(1, page1.getPagesSize());
      Assert.assertEquals(1, page2.getPagesSize());
      Assert.assertEquals(-1, page1.getFreeListHead());
      Assert.assertEquals(-1, page2.getFreeListHead());

      // Byte-level comparison
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
   * EntryPoint setTreeSize: apply directly on page1, redo on page2. Verify concrete value.
   */
  @Test
  public void testEntryPointSetTreeSizeRedoCorrectness() {
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
      var page1 = new CellBTreeSingleValueEntryPointV3<>(entry1);
      page1.init();
      var page2 = new CellBTreeSingleValueEntryPointV3<>(entry2);
      page2.init();

      page1.setTreeSize(Long.MAX_VALUE);

      var op = new BTreeSVEntryPointV3SetTreeSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), Long.MAX_VALUE);
      op.redo(page2);

      Assert.assertEquals(Long.MAX_VALUE, page1.getTreeSize());
      Assert.assertEquals(Long.MAX_VALUE, page2.getTreeSize());

      // Byte-level comparison
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
   * EntryPoint setPagesSize: apply directly on page1, redo on page2.
   */
  @Test
  public void testEntryPointSetPagesSizeRedoCorrectness() {
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
      var page1 = new CellBTreeSingleValueEntryPointV3<>(entry1);
      page1.init();
      var page2 = new CellBTreeSingleValueEntryPointV3<>(entry2);
      page2.init();

      page1.setPagesSize(1024);

      var op = new BTreeSVEntryPointV3SetPagesSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 1024);
      op.redo(page2);

      Assert.assertEquals(1024, page1.getPagesSize());
      Assert.assertEquals(1024, page2.getPagesSize());

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
   * EntryPoint setFreeListHead: apply directly on page1, redo on page2.
   */
  @Test
  public void testEntryPointSetFreeListHeadRedoCorrectness() {
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
      var page1 = new CellBTreeSingleValueEntryPointV3<>(entry1);
      page1.init();
      var page2 = new CellBTreeSingleValueEntryPointV3<>(entry2);
      page2.init();

      page1.setFreeListHead(77);

      var op = new BTreeSVEntryPointV3SetFreeListHeadOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 77);
      op.redo(page2);

      Assert.assertEquals(77, page1.getFreeListHead());
      Assert.assertEquals(77, page2.getFreeListHead());

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
   * SetApproxEntriesCount: apply directly on page1, redo on page2.
   * Both pages should have identical byte-level content.
   */
  @Test
  public void testEntryPointSetApproxEntriesCountRedoCorrectness() {
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
      var page1 = new CellBTreeSingleValueEntryPointV3<>(entry1);
      page1.init();
      var page2 = new CellBTreeSingleValueEntryPointV3<>(entry2);
      page2.init();

      page1.setApproximateEntriesCount(777888999L);

      var op = new BTreeSVEntryPointV3SetApproxEntriesCountOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 777888999L);
      op.redo(page2);

      Assert.assertEquals(777888999L, page1.getApproximateEntriesCount());
      Assert.assertEquals(777888999L, page2.getApproximateEntriesCount());

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
   * NullBucket init: apply directly on page1, redo on page2.
   * Pre-populate with a value to verify init clears it.
   */
  @Test
  public void testNullBucketInitRedoCorrectness() {
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
      // Pre-populate both pages with a value
      var page1 = new CellBTreeSingleValueV3NullBucket(entry1);
      page1.setValue(new RecordId(10, 100));
      Assert.assertNotNull(page1.getValue());

      var page2 = new CellBTreeSingleValueV3NullBucket(entry2);
      page2.setValue(new RecordId(10, 100));
      Assert.assertNotNull(page2.getValue());

      // Apply init directly on page1
      page1.init();

      // Apply init via redo on page2
      var op = new BTreeSVNullBucketV3InitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page2);

      // Both pages must have no value
      Assert.assertNull(page1.getValue());
      Assert.assertNull(page2.getValue());

      // Byte-level comparison
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
   * NullBucket setValue: apply directly on page1, redo on page2. Verify the RID round-trips.
   */
  @Test
  public void testNullBucketSetValueRedoCorrectness() {
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
      var page1 = new CellBTreeSingleValueV3NullBucket(entry1);
      page1.init();
      var page2 = new CellBTreeSingleValueV3NullBucket(entry2);
      page2.init();

      var rid = new RecordId(23, 456789L);
      page1.setValue(rid);

      var op = new BTreeSVNullBucketV3SetValueOp(
          0, 0, 0, new LogSequenceNumber(0, 0), (short) 23, 456789L);
      op.redo(page2);

      Assert.assertEquals(rid, page1.getValue());
      Assert.assertEquals(rid, page2.getValue());

      // Byte-level comparison
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
   * NullBucket removeValue: pre-populate both pages, apply removeValue directly on page1
   * and via redo on page2. Both must have no value.
   */
  @Test
  public void testNullBucketRemoveValueRedoCorrectness() {
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
      var rid = new RecordId(15, 12345L);

      var page1 = new CellBTreeSingleValueV3NullBucket(entry1);
      page1.init();
      page1.setValue(rid);

      var page2 = new CellBTreeSingleValueV3NullBucket(entry2);
      page2.init();
      page2.setValue(rid);

      // Apply removeValue directly on page1
      page1.removeValue();

      // Apply via redo on page2
      var op = new BTreeSVNullBucketV3RemoveValueOp(
          0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page2);

      Assert.assertNull(page1.getValue());
      Assert.assertNull(page2.getValue());

      // Byte-level comparison
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

  // ---------- Registration from mutation methods ----------

  /**
   * EntryPoint init() must register BTreeSVEntryPointV3InitOp when backed by CacheEntryChanges.
   */
  @Test
  public void testEntryPointInitRegistersOp() {
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

      var page = new CellBTreeSingleValueEntryPointV3<>(changes);
      page.init();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVEntryPointV3InitOp);
      Assert.assertEquals(7, registeredOp.getPageIndex());
      Assert.assertEquals(42, registeredOp.getFileId());
      Assert.assertEquals(
          new LogSequenceNumber(1, 100), registeredOp.getInitialLsn());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * EntryPoint setTreeSize() must register BTreeSVEntryPointV3SetTreeSizeOp with the
   * correct size value.
   */
  @Test
  public void testEntryPointSetTreeSizeRegistersOp() {
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

      var page = new CellBTreeSingleValueEntryPointV3<>(changes);
      page.init();
      org.mockito.Mockito.reset(atomicOp);

      page.setTreeSize(555L);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVEntryPointV3SetTreeSizeOp);
      Assert.assertEquals(555L,
          ((BTreeSVEntryPointV3SetTreeSizeOp) registeredOp).getSize());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * NullBucket setValue() must register BTreeSVNullBucketV3SetValueOp with
   * correct collectionId and collectionPosition.
   */
  @Test
  public void testNullBucketSetValueRegistersOp() {
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

      var page = new CellBTreeSingleValueV3NullBucket(changes);
      page.init();
      org.mockito.Mockito.reset(atomicOp);

      page.setValue(new RecordId(23, 456789L));

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVNullBucketV3SetValueOp);
      var setValueOp = (BTreeSVNullBucketV3SetValueOp) registeredOp;
      Assert.assertEquals((short) 23, setValueOp.getCollectionId());
      Assert.assertEquals(456789L, setValueOp.getCollectionPosition());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * NullBucket removeValue() must register BTreeSVNullBucketV3RemoveValueOp.
   */
  @Test
  public void testNullBucketRemoveValueRegistersOp() {
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

      var page = new CellBTreeSingleValueV3NullBucket(changes);
      page.init();
      page.setValue(new RecordId(5, 100));
      org.mockito.Mockito.reset(atomicOp);

      page.removeValue();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVNullBucketV3RemoveValueOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression (D4) ----------

  /**
   * When mutation methods are called on pages backed by a plain CacheEntry
   * (not CacheEntryChanges), no PageOperation must be registered.
   */
  @Test
  public void testNoRegistrationDuringRedoPathEntryPoint() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeSingleValueEntryPointV3<>(entry);
      // These calls should NOT throw or attempt to register anything
      page.init();
      page.setTreeSize(42);
      page.setPagesSize(10);
      page.setFreeListHead(5);
      page.setApproximateEntriesCount(100L);

      // Verify values were written correctly
      Assert.assertEquals(42, page.getTreeSize());
      Assert.assertEquals(10, page.getPagesSize());
      Assert.assertEquals(5, page.getFreeListHead());
      Assert.assertEquals(100L, page.getApproximateEntriesCount());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testNoRegistrationDuringRedoPathNullBucket() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeSingleValueV3NullBucket(entry);
      page.init();
      Assert.assertNull(page.getValue());

      page.setValue(new RecordId(10, 100));
      Assert.assertEquals(new RecordId(10, 100), page.getValue());

      page.removeValue();
      Assert.assertNull(page.getValue());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo idempotency ----------

  @Test
  public void testEntryPointInitRedoIsIdempotent() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeSingleValueEntryPointV3<>(entry);
      page.init();
      page.setTreeSize(999);

      var op = new BTreeSVEntryPointV3InitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page);

      var buf = cachePointer.getBuffer();
      var snapshot = new byte[buf.capacity()];
      buf.get(0, snapshot);

      op.redo(page);

      var afterSecond = new byte[buf.capacity()];
      buf.get(0, afterSecond);
      Assert.assertArrayEquals("Init redo must be idempotent", snapshot, afterSecond);
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testNullBucketSetValueRedoIsIdempotent() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeSingleValueV3NullBucket(entry);
      page.init();

      var op = new BTreeSVNullBucketV3SetValueOp(
          0, 0, 0, new LogSequenceNumber(0, 0), (short) 23, 456789L);
      op.redo(page);

      var buf = cachePointer.getBuffer();
      var snapshot = new byte[buf.capacity()];
      buf.get(0, snapshot);

      op.redo(page);

      var afterSecond = new byte[buf.capacity()];
      buf.get(0, afterSecond);
      Assert.assertArrayEquals(
          "SetValue redo must be idempotent", snapshot, afterSecond);
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Equals and hashCode ----------

  @Test
  public void testEntryPointSetTreeSizeOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new BTreeSVEntryPointV3SetTreeSizeOp(5, 10, 15, lsn, 42);
    var op2 = new BTreeSVEntryPointV3SetTreeSizeOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different size
    var op3 = new BTreeSVEntryPointV3SetTreeSizeOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testNullBucketSetValueOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new BTreeSVNullBucketV3SetValueOp(5, 10, 15, lsn, (short) 23, 456789L);
    var op2 = new BTreeSVNullBucketV3SetValueOp(5, 10, 15, lsn, (short) 23, 456789L);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different collectionId
    var op3 = new BTreeSVNullBucketV3SetValueOp(5, 10, 15, lsn, (short) 99, 456789L);
    Assert.assertNotEquals(op1, op3);

    // Different collectionPosition
    var op4 = new BTreeSVNullBucketV3SetValueOp(5, 10, 15, lsn, (short) 23, 999L);
    Assert.assertNotEquals(op1, op4);
  }

  @Test
  public void testEntryPointSetFreeListHeadOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new BTreeSVEntryPointV3SetFreeListHeadOp(5, 10, 15, lsn, 42);
    var op2 = new BTreeSVEntryPointV3SetFreeListHeadOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new BTreeSVEntryPointV3SetFreeListHeadOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testEntryPointSetPagesSizeOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new BTreeSVEntryPointV3SetPagesSizeOp(5, 10, 15, lsn, 42);
    var op2 = new BTreeSVEntryPointV3SetPagesSizeOp(5, 10, 15, lsn, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new BTreeSVEntryPointV3SetPagesSizeOp(5, 10, 15, lsn, 99);
    Assert.assertNotEquals(op1, op3);
  }

  // ---------- Missing registration tests (review fix SF1) ----------

  @Test
  public void testEntryPointSetPagesSizeRegistersOp() {
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

      var page = new CellBTreeSingleValueEntryPointV3<>(changes);
      page.init();
      org.mockito.Mockito.reset(atomicOp);

      page.setPagesSize(1024);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVEntryPointV3SetPagesSizeOp);
      Assert.assertEquals(1024,
          ((BTreeSVEntryPointV3SetPagesSizeOp) registeredOp).getPages());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testEntryPointSetFreeListHeadRegistersOp() {
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

      var page = new CellBTreeSingleValueEntryPointV3<>(changes);
      page.init();
      org.mockito.Mockito.reset(atomicOp);

      page.setFreeListHead(77);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVEntryPointV3SetFreeListHeadOp);
      Assert.assertEquals(77,
          ((BTreeSVEntryPointV3SetFreeListHeadOp) registeredOp).getFreeListHead());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testEntryPointSetApproxEntriesCountRegistersOp() {
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

      var page = new CellBTreeSingleValueEntryPointV3<>(changes);
      page.init();
      org.mockito.Mockito.reset(atomicOp);

      page.setApproximateEntriesCount(555L);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(
          registeredOp instanceof BTreeSVEntryPointV3SetApproxEntriesCountOp);
      Assert.assertEquals(555L,
          ((BTreeSVEntryPointV3SetApproxEntriesCountOp) registeredOp).getCount());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testNullBucketInitRegistersOp() {
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

      var page = new CellBTreeSingleValueV3NullBucket(changes);
      page.init();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = opCaptor.getValue();
      Assert.assertTrue(registeredOp instanceof BTreeSVNullBucketV3InitOp);
      Assert.assertEquals(7, registeredOp.getPageIndex());
      Assert.assertEquals(42, registeredOp.getFileId());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Missing factory roundtrip tests (review fix SF2) ----------

  @Test
  public void testEntryPointSetPagesSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 2048);
    var original = new BTreeSVEntryPointV3SetPagesSizeOp(11, 22, 33, initialLsn, 512);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVEntryPointV3SetPagesSizeOp);
    var result = (BTreeSVEntryPointV3SetPagesSizeOp) deserialized;
    Assert.assertEquals(512, result.getPages());
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testEntryPointSetFreeListHeadOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(11, 1100);
    var original = new BTreeSVEntryPointV3SetFreeListHeadOp(3, 6, 9, initialLsn, -1);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVEntryPointV3SetFreeListHeadOp);
    var result = (BTreeSVEntryPointV3SetFreeListHeadOp) deserialized;
    Assert.assertEquals(-1, result.getFreeListHead());
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testNullBucketInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(55, 550);
    var original = new BTreeSVNullBucketV3InitOp(4, 8, 12, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVNullBucketV3InitOp);
    var result = (BTreeSVNullBucketV3InitOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testNullBucketRemoveValueOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(66, 660);
    var original = new BTreeSVNullBucketV3RemoveValueOp(7, 14, 21, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeSVNullBucketV3RemoveValueOp);
    var result = (BTreeSVNullBucketV3RemoveValueOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  // ---------- FreeListHead backward-compat test (review fix SF3) ----------

  /**
   * When freeListHead=0 is stored and redone, getFreeListHead() must return -1
   * due to backward-compatibility remapping (value 0 in old format means "no free list").
   */
  @Test
  public void testFreeListHeadZeroBackwardCompatSurvivesRedo() {
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
      var page1 = new CellBTreeSingleValueEntryPointV3<>(entry1);
      page1.init();
      page1.setFreeListHead(0);

      var page2 = new CellBTreeSingleValueEntryPointV3<>(entry2);
      page2.init();
      var op = new BTreeSVEntryPointV3SetFreeListHeadOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0);
      op.redo(page2);

      // Backward compat: stored 0 reads back as -1
      Assert.assertEquals(-1, page1.getFreeListHead());
      Assert.assertEquals(-1, page2.getFreeListHead());

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

  // ---------- Multi-operation redo sequence tests (review fix SF5) ----------

  /**
   * Replay a realistic sequence of entry point operations: init, setTreeSize, setPagesSize,
   * setFreeListHead. Verifies that sequential redo produces the same state as direct mutation.
   */
  @Test
  public void testEntryPointMultiOpRedoSequence() {
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

      // Apply directly on page1
      var page1 = new CellBTreeSingleValueEntryPointV3<>(entry1);
      page1.init();
      page1.setTreeSize(5000);
      page1.setPagesSize(50);
      page1.setFreeListHead(7);
      page1.setApproximateEntriesCount(12345L);

      // Replay same sequence via redo on page2
      var page2 = new CellBTreeSingleValueEntryPointV3<>(entry2);
      new BTreeSVEntryPointV3InitOp(0, 0, 0, lsn).redo(page2);
      new BTreeSVEntryPointV3SetTreeSizeOp(0, 0, 0, lsn, 5000).redo(page2);
      new BTreeSVEntryPointV3SetPagesSizeOp(0, 0, 0, lsn, 50).redo(page2);
      new BTreeSVEntryPointV3SetFreeListHeadOp(0, 0, 0, lsn, 7).redo(page2);
      new BTreeSVEntryPointV3SetApproxEntriesCountOp(
          0, 0, 0, lsn, 12345L).redo(page2);

      Assert.assertEquals(5000, page2.getTreeSize());
      Assert.assertEquals(50, page2.getPagesSize());
      Assert.assertEquals(7, page2.getFreeListHead());
      Assert.assertEquals(12345L, page2.getApproximateEntriesCount());

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
   * Replay a realistic null bucket sequence: init, setValue, removeValue, setValue with
   * different RID. Verifies sequential redo converges to same state as direct mutation.
   */
  @Test
  public void testNullBucketMultiOpRedoSequence() {
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
      var rid1 = new RecordId(10, 100);
      var rid2 = new RecordId(20, 200);

      // Apply directly on page1
      var page1 = new CellBTreeSingleValueV3NullBucket(entry1);
      page1.init();
      page1.setValue(rid1);
      page1.removeValue();
      page1.setValue(rid2);

      // Replay same sequence via redo on page2
      var page2 = new CellBTreeSingleValueV3NullBucket(entry2);
      new BTreeSVNullBucketV3InitOp(0, 0, 0, lsn).redo(page2);
      new BTreeSVNullBucketV3SetValueOp(0, 0, 0, lsn, (short) 10, 100).redo(page2);
      new BTreeSVNullBucketV3RemoveValueOp(0, 0, 0, lsn).redo(page2);
      new BTreeSVNullBucketV3SetValueOp(0, 0, 0, lsn, (short) 20, 200).redo(page2);

      Assert.assertEquals(rid2, page2.getValue());

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
}
