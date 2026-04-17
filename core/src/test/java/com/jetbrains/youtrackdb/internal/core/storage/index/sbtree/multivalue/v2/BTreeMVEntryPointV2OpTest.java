package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for CellBTreeMultiValueV2EntryPoint logical WAL operations.
 * Covers record ID verification, serialization roundtrip, WALRecordsFactory integration,
 * redo correctness, registration from mutation methods, redo suppression, and redo idempotency.
 */
public class BTreeMVEntryPointV2OpTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVEntryPointV2InitOp.RECORD_ID, BTreeMVEntryPointV2InitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVEntryPointV2SetTreeSizeOp.RECORD_ID,
        BTreeMVEntryPointV2SetTreeSizeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVEntryPointV2SetPagesSizeOp.RECORD_ID,
        BTreeMVEntryPointV2SetPagesSizeOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeMVEntryPointV2SetEntryIdOp.RECORD_ID,
        BTreeMVEntryPointV2SetEntryIdOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testInitOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_INIT_OP,
        BTreeMVEntryPointV2InitOp.RECORD_ID);
    Assert.assertEquals(239, BTreeMVEntryPointV2InitOp.RECORD_ID);
  }

  @Test
  public void testSetTreeSizeOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_TREE_SIZE_OP,
        BTreeMVEntryPointV2SetTreeSizeOp.RECORD_ID);
    Assert.assertEquals(240, BTreeMVEntryPointV2SetTreeSizeOp.RECORD_ID);
  }

  @Test
  public void testSetPagesSizeOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_PAGES_SIZE_OP,
        BTreeMVEntryPointV2SetPagesSizeOp.RECORD_ID);
    Assert.assertEquals(241, BTreeMVEntryPointV2SetPagesSizeOp.RECORD_ID);
  }

  @Test
  public void testSetEntryIdOpRecordId() {
    Assert.assertEquals(
        WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_ENTRY_ID_OP,
        BTreeMVEntryPointV2SetEntryIdOp.RECORD_ID);
    Assert.assertEquals(242, BTreeMVEntryPointV2SetEntryIdOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new BTreeMVEntryPointV2InitOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVEntryPointV2InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetTreeSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var original = new BTreeMVEntryPointV2SetTreeSizeOp(15, 25, 35, initialLsn, 123456789L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVEntryPointV2SetTreeSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(123456789L, deserialized.getSize());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetPagesSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var original = new BTreeMVEntryPointV2SetPagesSizeOp(7, 14, 21, initialLsn, 42);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVEntryPointV2SetPagesSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(42, deserialized.getPages());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetEntryIdOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(9, 800);
    var original = new BTreeMVEntryPointV2SetEntryIdOp(1, 2, 3, initialLsn, 999888777L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeMVEntryPointV2SetEntryIdOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(999888777L, deserialized.getEntryId());
    Assert.assertEquals(original, deserialized);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new BTreeMVEntryPointV2InitOp(10, 20, 30, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVEntryPointV2InitOp);
    var result = (BTreeMVEntryPointV2InitOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testSetTreeSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(99, 2048);
    var original = new BTreeMVEntryPointV2SetTreeSizeOp(11, 22, 33, initialLsn, 987654321L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVEntryPointV2SetTreeSizeOp);
    var result = (BTreeMVEntryPointV2SetTreeSizeOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(987654321L, result.getSize());
  }

  @Test
  public void testSetPagesSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(77, 3072);
    var original = new BTreeMVEntryPointV2SetPagesSizeOp(5, 10, 15, initialLsn, 512);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVEntryPointV2SetPagesSizeOp);
    var result = (BTreeMVEntryPointV2SetPagesSizeOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(512, result.getPages());
  }

  @Test
  public void testSetEntryIdOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(50, 500);
    var original = new BTreeMVEntryPointV2SetEntryIdOp(1, 2, 3, initialLsn, 1234567890L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);

    Assert.assertTrue(deserialized instanceof BTreeMVEntryPointV2SetEntryIdOp);
    var result = (BTreeMVEntryPointV2SetEntryIdOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
    Assert.assertEquals(1234567890L, result.getEntryId());
  }

  // ---------- Redo correctness tests ----------

  /**
   * EntryPoint init: apply directly on page1, redo on page2. Both pages must have
   * identical state (treeSize=0, pagesSize=1, entryId=0).
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
      // Pre-populate both pages with non-default values
      var page1 = new CellBTreeMultiValueV2EntryPoint<>(entry1);
      page1.init();
      page1.setTreeSize(999);
      page1.setPagesSize(50);
      page1.setEntryId(777);

      var page2 = new CellBTreeMultiValueV2EntryPoint<>(entry2);
      page2.init();
      page2.setTreeSize(999);
      page2.setPagesSize(50);
      page2.setEntryId(777);

      // Apply init directly on page1
      page1.init();

      // Apply init via redo on page2
      var op = new BTreeMVEntryPointV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page2);

      // Verify both pages have the init state
      Assert.assertEquals(0, page1.getTreeSize());
      Assert.assertEquals(0, page2.getTreeSize());
      Assert.assertEquals(1, page1.getPagesSize());
      Assert.assertEquals(1, page2.getPagesSize());
      Assert.assertEquals(0, page1.getEntryId());
      Assert.assertEquals(0, page2.getEntryId());

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
  public void testSetTreeSizeRedoCorrectness() {
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
      var page1 = new CellBTreeMultiValueV2EntryPoint<>(entry1);
      page1.init();
      var page2 = new CellBTreeMultiValueV2EntryPoint<>(entry2);
      page2.init();

      page1.setTreeSize(Long.MAX_VALUE);

      var op = new BTreeMVEntryPointV2SetTreeSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), Long.MAX_VALUE);
      op.redo(page2);

      Assert.assertEquals(Long.MAX_VALUE, page1.getTreeSize());
      Assert.assertEquals(Long.MAX_VALUE, page2.getTreeSize());

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
   * EntryPoint setPagesSize: apply directly on page1, redo on page2. Verify concrete value.
   */
  @Test
  public void testSetPagesSizeRedoCorrectness() {
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
      var page1 = new CellBTreeMultiValueV2EntryPoint<>(entry1);
      page1.init();
      var page2 = new CellBTreeMultiValueV2EntryPoint<>(entry2);
      page2.init();

      page1.setPagesSize(Integer.MAX_VALUE);

      var op = new BTreeMVEntryPointV2SetPagesSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), Integer.MAX_VALUE);
      op.redo(page2);

      Assert.assertEquals(Integer.MAX_VALUE, page1.getPagesSize());
      Assert.assertEquals(Integer.MAX_VALUE, page2.getPagesSize());

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
   * EntryPoint setEntryId: apply directly on page1, redo on page2. Verify concrete value.
   */
  @Test
  public void testSetEntryIdRedoCorrectness() {
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
      var page1 = new CellBTreeMultiValueV2EntryPoint<>(entry1);
      page1.init();
      var page2 = new CellBTreeMultiValueV2EntryPoint<>(entry2);
      page2.init();

      page1.setEntryId(Long.MAX_VALUE);

      var op = new BTreeMVEntryPointV2SetEntryIdOp(
          0, 0, 0, new LogSequenceNumber(0, 0), Long.MAX_VALUE);
      op.redo(page2);

      Assert.assertEquals(Long.MAX_VALUE, page1.getEntryId());
      Assert.assertEquals(Long.MAX_VALUE, page2.getEntryId());

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
   * Verifies that init() registers a BTreeMVEntryPointV2InitOp via CacheEntryChanges.
   */
  @Test
  public void testInitRegistersPageOperation() {
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

      var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(changes);
      entryPoint.init();

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          captor.capture());

      var captured = captor.getValue();
      Assert.assertTrue(captured instanceof BTreeMVEntryPointV2InitOp);
      Assert.assertEquals(7, captured.getPageIndex());
      Assert.assertEquals(42, captured.getFileId());
      Assert.assertEquals(new LogSequenceNumber(1, 100), captured.getInitialLsn());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * Verifies that setTreeSize() registers a BTreeMVEntryPointV2SetTreeSizeOp.
   */
  @Test
  public void testSetTreeSizeRegistersPageOperation() {
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

      var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(changes);
      entryPoint.init();
      org.mockito.Mockito.reset(atomicOp);

      entryPoint.setTreeSize(42L);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          captor.capture());

      var captured = captor.getValue();
      Assert.assertTrue(captured instanceof BTreeMVEntryPointV2SetTreeSizeOp);
      Assert.assertEquals(42L, ((BTreeMVEntryPointV2SetTreeSizeOp) captured).getSize());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * Verifies that setPagesSize() registers a BTreeMVEntryPointV2SetPagesSizeOp.
   */
  @Test
  public void testSetPagesSizeRegistersPageOperation() {
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

      var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(changes);
      entryPoint.init();
      org.mockito.Mockito.reset(atomicOp);

      entryPoint.setPagesSize(17);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          captor.capture());

      var captured = captor.getValue();
      Assert.assertTrue(captured instanceof BTreeMVEntryPointV2SetPagesSizeOp);
      Assert.assertEquals(17, ((BTreeMVEntryPointV2SetPagesSizeOp) captured).getPages());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  /**
   * Verifies that setEntryId() registers a BTreeMVEntryPointV2SetEntryIdOp.
   */
  @Test
  public void testSetEntryIdRegistersPageOperation() {
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

      var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(changes);
      entryPoint.init();
      org.mockito.Mockito.reset(atomicOp);

      entryPoint.setEntryId(555L);

      var captor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          captor.capture());

      var captured = captor.getValue();
      Assert.assertTrue(captured instanceof BTreeMVEntryPointV2SetEntryIdOp);
      Assert.assertEquals(555L, ((BTreeMVEntryPointV2SetEntryIdOp) captured).getEntryId());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression tests ----------

  /**
   * Verifies that redo does NOT register a PageOperation — redo constructs the page with
   * a plain CacheEntry (not CacheEntryChanges), so the instanceof check fails.
   */
  @Test
  public void testRedoDoesNotRegisterPageOperation() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    // Plain CacheEntryImpl, not CacheEntryChanges — simulates recovery context
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeMultiValueV2EntryPoint<>(entry);

      // Redo init — should NOT register any PageOperation
      var initOp = new BTreeMVEntryPointV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      initOp.redo(page);

      // Redo setTreeSize
      var treeSizeOp = new BTreeMVEntryPointV2SetTreeSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 100L);
      treeSizeOp.redo(page);

      // Redo setPagesSize
      var pagesSizeOp = new BTreeMVEntryPointV2SetPagesSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 5);
      pagesSizeOp.redo(page);

      // Redo setEntryId
      var entryIdOp = new BTreeMVEntryPointV2SetEntryIdOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 200L);
      entryIdOp.redo(page);

      // If we got here without errors, redo suppression works — plain CacheEntry
      // does not have registerPageOperation, and the instanceof check skips it.
      Assert.assertEquals(100L, page.getTreeSize());
      Assert.assertEquals(5, page.getPagesSize());
      Assert.assertEquals(200L, page.getEntryId());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo idempotency tests ----------

  /**
   * Applying redo twice should produce the same page state as applying it once.
   */
  @Test
  public void testInitRedoIdempotency() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeMultiValueV2EntryPoint<>(entry);
      page.init();
      page.setTreeSize(500);

      var op = new BTreeMVEntryPointV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page);
      op.redo(page);

      Assert.assertEquals(0, page.getTreeSize());
      Assert.assertEquals(1, page.getPagesSize());
      Assert.assertEquals(0, page.getEntryId());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testSetTreeSizeRedoIdempotency() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeMultiValueV2EntryPoint<>(entry);
      page.init();

      var op = new BTreeMVEntryPointV2SetTreeSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 12345L);
      op.redo(page);
      op.redo(page);

      Assert.assertEquals(12345L, page.getTreeSize());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testSetPagesSizeRedoIdempotency() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeMultiValueV2EntryPoint<>(entry);
      page.init();

      var op = new BTreeMVEntryPointV2SetPagesSizeOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 77);
      op.redo(page);
      op.redo(page);

      Assert.assertEquals(77, page.getPagesSize());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testSetEntryIdRedoIdempotency() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeMultiValueV2EntryPoint<>(entry);
      page.init();

      var op = new BTreeMVEntryPointV2SetEntryIdOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 9999L);
      op.redo(page);
      op.redo(page);

      Assert.assertEquals(9999L, page.getEntryId());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Multi-operation redo sequence test ----------

  /**
   * Replays a realistic sequence of operations (init + setTreeSize + setPagesSize + setEntryId)
   * via redo on one page and via direct mutation on another, then compares byte-for-byte.
   * Catches offset collision bugs that individual redo tests cannot detect.
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
      var page1 = new CellBTreeMultiValueV2EntryPoint<>(entry1);
      page1.init();
      page1.setTreeSize(5000);
      page1.setPagesSize(50);
      page1.setEntryId(7777);

      // Replay same sequence via redo on page2
      var page2 = new CellBTreeMultiValueV2EntryPoint<>(entry2);
      new BTreeMVEntryPointV2InitOp(0, 0, 0, lsn).redo(page2);
      new BTreeMVEntryPointV2SetTreeSizeOp(0, 0, 0, lsn, 5000).redo(page2);
      new BTreeMVEntryPointV2SetPagesSizeOp(0, 0, 0, lsn, 50).redo(page2);
      new BTreeMVEntryPointV2SetEntryIdOp(0, 0, 0, lsn, 7777).redo(page2);

      Assert.assertEquals(5000, page2.getTreeSize());
      Assert.assertEquals(50, page2.getPagesSize());
      Assert.assertEquals(7777, page2.getEntryId());

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
  public void testSetTreeSizeEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 2);
    var op1 = new BTreeMVEntryPointV2SetTreeSizeOp(1, 2, 3, lsn, 42L);
    var op2 = new BTreeMVEntryPointV2SetTreeSizeOp(1, 2, 3, lsn, 42L);
    var op3 = new BTreeMVEntryPointV2SetTreeSizeOp(1, 2, 3, lsn, 99L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetPagesSizeEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 2);
    var op1 = new BTreeMVEntryPointV2SetPagesSizeOp(1, 2, 3, lsn, 10);
    var op2 = new BTreeMVEntryPointV2SetPagesSizeOp(1, 2, 3, lsn, 10);
    var op3 = new BTreeMVEntryPointV2SetPagesSizeOp(1, 2, 3, lsn, 20);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetEntryIdEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 2);
    var op1 = new BTreeMVEntryPointV2SetEntryIdOp(1, 2, 3, lsn, 100L);
    var op2 = new BTreeMVEntryPointV2SetEntryIdOp(1, 2, 3, lsn, 100L);
    var op3 = new BTreeMVEntryPointV2SetEntryIdOp(1, 2, 3, lsn, 200L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }
}
