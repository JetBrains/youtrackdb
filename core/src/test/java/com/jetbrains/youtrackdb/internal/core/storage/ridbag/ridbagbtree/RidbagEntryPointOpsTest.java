package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.common.directmemory.DirectMemoryAllocator.Intention;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntryImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperationRegistry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for Ridbag EntryPoint PageOperation subclasses: record IDs, serialization roundtrips,
 * factory roundtrips, redo correctness (byte-level), redo suppression, and equals/hashCode.
 */
public class RidbagEntryPointOpsTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Record ID verification ----

  @Test
  public void testInitOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_ENTRY_POINT_INIT_OP,
        RidbagEntryPointInitOp.RECORD_ID);
    Assert.assertEquals(282, RidbagEntryPointInitOp.RECORD_ID);
  }

  @Test
  public void testSetTreeSizeOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_ENTRY_POINT_SET_TREE_SIZE_OP,
        RidbagEntryPointSetTreeSizeOp.RECORD_ID);
    Assert.assertEquals(283, RidbagEntryPointSetTreeSizeOp.RECORD_ID);
  }

  @Test
  public void testSetPagesSizeOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_ENTRY_POINT_SET_PAGES_SIZE_OP,
        RidbagEntryPointSetPagesSizeOp.RECORD_ID);
    Assert.assertEquals(284, RidbagEntryPointSetPagesSizeOp.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new RidbagEntryPointInitOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagEntryPointInitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetTreeSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var original = new RidbagEntryPointSetTreeSizeOp(10, 20, 30, initialLsn, 42L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagEntryPointSetTreeSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(42L, deserialized.getSize());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetPagesSizeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 300);
    var original = new RidbagEntryPointSetPagesSizeOp(10, 20, 30, initialLsn, 15);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagEntryPointSetPagesSizeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(15, deserialized.getPages());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagEntryPointInitOp(10, 20, 30, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagEntryPointInitOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetTreeSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagEntryPointSetTreeSizeOp(10, 20, 30, initialLsn, 999L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagEntryPointSetTreeSizeOp);
    Assert.assertEquals(999L,
        ((RidbagEntryPointSetTreeSizeOp) deserialized).getSize());
  }

  @Test
  public void testSetPagesSizeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagEntryPointSetPagesSizeOp(10, 20, 30, initialLsn, 7);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagEntryPointSetPagesSizeOp);
    Assert.assertEquals(7,
        ((RidbagEntryPointSetPagesSizeOp) deserialized).getPages());
  }

  // ---- Redo correctness ----

  @Test
  public void testInitOpRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      // Pre-populate with non-default values
      var page1 = new EntryPoint(entry1);
      page1.setTreeSize(100L);
      page1.setPagesSize(10);

      var page2 = new EntryPoint(entry2);
      page2.setTreeSize(100L);
      page2.setPagesSize(10);

      // Apply init directly
      page1.init();

      // Apply init via redo
      new RidbagEntryPointInitOp(0, 0, 0, new LogSequenceNumber(0, 0))
          .redo(page2);

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(0L, page2.getTreeSize());
      Assert.assertEquals(1, page2.getPagesSize());
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  @Test
  public void testSetTreeSizeOpRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      new EntryPoint(entry1).init();
      new EntryPoint(entry2).init();

      new EntryPoint(entry1).setTreeSize(42L);
      new RidbagEntryPointSetTreeSizeOp(0, 0, 0, new LogSequenceNumber(0, 0), 42L)
          .redo(new EntryPoint(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  @Test
  public void testSetPagesSizeOpRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
    entry1.acquireExclusiveLock();

    var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
    cp2.incrementReferrer();
    CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
    entry2.acquireExclusiveLock();

    try {
      new EntryPoint(entry1).init();
      new EntryPoint(entry2).init();

      new EntryPoint(entry1).setPagesSize(5);
      new RidbagEntryPointSetPagesSizeOp(0, 0, 0, new LogSequenceNumber(0, 0), 5)
          .redo(new EntryPoint(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  // ---- Redo suppression ----

  @Test
  public void testRedoSuppression_initDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      var ep = new EntryPoint(entry);
      ep.setTreeSize(100L);
      ep.setPagesSize(10);
      ep.init();
      Assert.assertEquals(0L, ep.getTreeSize());
      Assert.assertEquals(1, ep.getPagesSize());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  // ---- Equals/hashCode ----

  @Test
  public void testSetTreeSizeOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new RidbagEntryPointSetTreeSizeOp(10, 20, 30, lsn, 42L);
    var op2 = new RidbagEntryPointSetTreeSizeOp(10, 20, 30, lsn, 42L);
    var op3 = new RidbagEntryPointSetTreeSizeOp(10, 20, 30, lsn, 99L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetPagesSizeOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new RidbagEntryPointSetPagesSizeOp(10, 20, 30, lsn, 5);
    var op2 = new RidbagEntryPointSetPagesSizeOp(10, 20, 30, lsn, 5);
    var op3 = new RidbagEntryPointSetPagesSizeOp(10, 20, 30, lsn, 99);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }
}
