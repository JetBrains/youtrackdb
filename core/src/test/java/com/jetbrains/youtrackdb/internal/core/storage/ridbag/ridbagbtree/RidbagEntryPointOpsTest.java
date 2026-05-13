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

  // ---- Direct-memory-safe one-page and two-page helpers ----

  @FunctionalInterface
  private interface SinglePageAction {
    void run(CacheEntry entry, CachePointer cp);
  }

  @FunctionalInterface
  private interface TwoPageAction {
    void run(CacheEntry entry1, CachePointer cp1, CacheEntry entry2, CachePointer cp2);
  }

  /**
   * Allocates one raw cache entry (page) for a single-page test and routes the
   * {@code incrementReferrer} → entry construction → lock-acquire sequence through a
   * single try/finally so a throw at any of the three steps still releases the
   * referrer. Mirrors {@link #withTwoPages} for the single-page case.
   */
  private static void withSinglePage(SinglePageAction action) {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    try {
      CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
      entry.acquireExclusiveLock();
      try {
        action.run(entry, cp);
      } finally {
        entry.releaseExclusiveLock();
      }
    } finally {
      cp.decrementReferrer();
    }
  }

  /**
   * Allocates two raw cache entries for a redo-correctness comparison test, runs the action,
   * and releases both deterministically — even if the second allocation throws.
   *
   * <p>The {@code try} block opens immediately after entry-1 is allocated, so if entry-2's
   * setup fails, the {@code finally} releases entry-1's referrer. Without this scoping,
   * entry-1 would leak and the page tracker (enabled via
   * {@code -Dyoutrackdb.memory.directMemory.trackMode=true} in {@code core/pom.xml}) would
   * call {@code System.exit(1)} at JVM shutdown, aborting the surefire JVM and masking the
   * real failure as "Tests run: 0".
   */
  private static void withTwoPages(TwoPageAction action) {
    var bufferPool = ByteBufferPool.instance(null);

    var pointer1 = bufferPool.acquireDirect(true, Intention.TEST);
    var cp1 = new CachePointer(pointer1, bufferPool, 0, 0);
    cp1.incrementReferrer();
    try {
      CacheEntry entry1 = new CacheEntryImpl(0, 0, cp1, false, null);
      entry1.acquireExclusiveLock();
      try {
        var pointer2 = bufferPool.acquireDirect(true, Intention.TEST);
        var cp2 = new CachePointer(pointer2, bufferPool, 0, 0);
        cp2.incrementReferrer();
        try {
          CacheEntry entry2 = new CacheEntryImpl(0, 0, cp2, false, null);
          entry2.acquireExclusiveLock();
          try {
            action.run(entry1, cp1, entry2, cp2);
          } finally {
            entry2.releaseExclusiveLock();
          }
        } finally {
          cp2.decrementReferrer();
        }
      } finally {
        entry1.releaseExclusiveLock();
      }
    } finally {
      cp1.decrementReferrer();
    }
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
    withTwoPages((entry1, cp1, entry2, cp2) -> {
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
    });
  }

  @Test
  public void testSetTreeSizeOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new EntryPoint(entry1).init();
      new EntryPoint(entry2).init();

      new EntryPoint(entry1).setTreeSize(42L);
      new RidbagEntryPointSetTreeSizeOp(0, 0, 0, new LogSequenceNumber(0, 0), 42L)
          .redo(new EntryPoint(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    });
  }

  @Test
  public void testSetPagesSizeOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new EntryPoint(entry1).init();
      new EntryPoint(entry2).init();

      new EntryPoint(entry1).setPagesSize(5);
      new RidbagEntryPointSetPagesSizeOp(0, 0, 0, new LogSequenceNumber(0, 0), 5)
          .redo(new EntryPoint(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    });
  }

  // ---- Redo suppression ----

  @Test
  public void testRedoSuppression_initDoesNotRegister() {
    // Routed through withSinglePage so a throw between incrementReferrer and the
    // lock acquire (e.g., out-of-direct-memory at CacheEntryImpl construction)
    // still releases the referrer. The previous shape opened the try block AFTER
    // entry construction and lock-acquire, so a throw at either site leaked the
    // referrer and aborted the surefire JVM via the page tracker.
    withSinglePage((entry, cp) -> {
      var ep = new EntryPoint(entry);
      ep.setTreeSize(100L);
      ep.setPagesSize(10);
      ep.init();
      Assert.assertEquals(0L, ep.getTreeSize());
      Assert.assertEquals(1, ep.getPagesSize());
    });
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

  // ---- toString coverage for all entry-point ops ----

  /**
   * toString() on all three entry-point ops must render the simple class name plus its
   * op-specific fields. The pins are op-specific so a regression that drops or
   * mis-routes the @Override is detectable.
   */
  @Test
  public void testAllEntryPointOpsToString() {
    var lsn = new LogSequenceNumber(1, 10);

    // InitOp has no op-specific fields and its own toString() passes an empty
    // append string, so only the class name and the "lsn =" header survive in the
    // output. Pin both pieces so a regression that drops AbstractWALRecord.toString()
    // is detectable.
    var init = new RidbagEntryPointInitOp(17, 2, 3, lsn).toString();
    Assert.assertTrue("toString must name InitOp: " + init,
        init.contains("RidbagEntryPointInitOp"));
    Assert.assertTrue(
        "InitOp.toString must include the inherited 'lsn =' header: " + init,
        init.contains("lsn ="));

    // SetTreeSizeOp.toString() appends size (the new tree size).
    var setTreeSize = new RidbagEntryPointSetTreeSizeOp(1, 2, 3, lsn, 19L).toString();
    Assert.assertTrue("toString must name SetTreeSizeOp: " + setTreeSize,
        setTreeSize.contains("RidbagEntryPointSetTreeSizeOp"));
    Assert.assertTrue("SetTreeSizeOp.toString must include size=19: " + setTreeSize,
        setTreeSize.contains("size=19"));

    // SetPagesSizeOp.toString() appends pages (the new pages count).
    var setPagesSize = new RidbagEntryPointSetPagesSizeOp(1, 2, 3, lsn, 23).toString();
    Assert.assertTrue("toString must name SetPagesSizeOp: " + setPagesSize,
        setPagesSize.contains("RidbagEntryPointSetPagesSizeOp"));
    Assert.assertTrue("SetPagesSizeOp.toString must include pages=23: " + setPagesSize,
        setPagesSize.contains("pages=23"));
  }
}
