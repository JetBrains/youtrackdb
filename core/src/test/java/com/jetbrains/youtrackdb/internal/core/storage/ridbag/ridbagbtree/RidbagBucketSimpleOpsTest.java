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
 * Tests for Ridbag Bucket simple PageOperation subclasses: init, switchBucketType,
 * setLeftSibling, setRightSibling. Covers record IDs, serialization roundtrips,
 * factory roundtrips, redo correctness (byte-level), redo suppression, and equals/hashCode.
 */
public class RidbagBucketSimpleOpsTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Direct-memory-safe two-page helper ----

  @FunctionalInterface
  private interface TwoPageAction {
    void run(CacheEntry entry1, CachePointer cp1, CacheEntry entry2, CachePointer cp2);
  }

  /**
   * Allocates two raw cache entries (page1 + page2) for a redo-correctness comparison test,
   * runs the action, and releases both deterministically — even if the second allocation
   * throws.
   *
   * <p>The {@code try} block opens immediately after entry-1 is allocated, so if entry-2's
   * setup fails (e.g., out-of-direct-memory mid-allocation), the {@code finally} releases
   * entry-1's referrer. Without this scoping, entry-1 would leak and the page tracker
   * (enabled via {@code -Dyoutrackdb.memory.directMemory.trackMode=true} in {@code core/pom.xml})
   * would call {@code System.exit(1)} at JVM shutdown, aborting the surefire JVM and masking
   * the real failure as "Tests run: 0".
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
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_INIT_OP,
        RidbagBucketInitOp.RECORD_ID);
    Assert.assertEquals(285, RidbagBucketInitOp.RECORD_ID);
  }

  @Test
  public void testSwitchBucketTypeOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_SWITCH_BUCKET_TYPE_OP,
        RidbagBucketSwitchBucketTypeOp.RECORD_ID);
    Assert.assertEquals(286, RidbagBucketSwitchBucketTypeOp.RECORD_ID);
  }

  @Test
  public void testSetLeftSiblingOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_SET_LEFT_SIBLING_OP,
        RidbagBucketSetLeftSiblingOp.RECORD_ID);
    Assert.assertEquals(287, RidbagBucketSetLeftSiblingOp.RECORD_ID);
  }

  @Test
  public void testSetRightSiblingOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_SET_RIGHT_SIBLING_OP,
        RidbagBucketSetRightSiblingOp.RECORD_ID);
    Assert.assertEquals(288, RidbagBucketSetRightSiblingOp.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new RidbagBucketInitOp(10, 20, 30, initialLsn, true);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagBucketInitOp();
    deserialized.fromStream(content, 1);

    Assert.assertTrue(deserialized.isLeaf());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSwitchBucketTypeOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var original = new RidbagBucketSwitchBucketTypeOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagBucketSwitchBucketTypeOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetLeftSiblingOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 300);
    var original = new RidbagBucketSetLeftSiblingOp(10, 20, 30, initialLsn, 42L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagBucketSetLeftSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(42L, deserialized.getPageIdx());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetRightSiblingOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 300);
    var original = new RidbagBucketSetRightSiblingOp(10, 20, 30, initialLsn, 99L);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagBucketSetRightSiblingOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(99L, deserialized.getPageIdx());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketInitOp(10, 20, 30, initialLsn, false);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketInitOp);
    Assert.assertFalse(((RidbagBucketInitOp) deserialized).isLeaf());
  }

  @Test
  public void testSwitchBucketTypeOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketSwitchBucketTypeOp(10, 20, 30, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketSwitchBucketTypeOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetLeftSiblingOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketSetLeftSiblingOp(10, 20, 30, initialLsn, 77L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketSetLeftSiblingOp);
    Assert.assertEquals(77L,
        ((RidbagBucketSetLeftSiblingOp) deserialized).getPageIdx());
  }

  @Test
  public void testSetRightSiblingOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketSetRightSiblingOp(10, 20, 30, initialLsn, 88L);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketSetRightSiblingOp);
    Assert.assertEquals(88L,
        ((RidbagBucketSetRightSiblingOp) deserialized).getPageIdx());
  }

  // ---- Redo correctness ----

  /** init(leaf): apply directly on page1, redo on page2. Byte-level identical. */
  @Test
  public void testInitOpRedoCorrectness_leaf() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new Bucket(entry1).init(true);
      new RidbagBucketInitOp(0, 0, 0, new LogSequenceNumber(0, 0), true)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertTrue(new Bucket(entry2).isLeaf());
    });
  }

  /** init(non-leaf): apply directly on page1, redo on page2. */
  @Test
  public void testInitOpRedoCorrectness_nonLeaf() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new Bucket(entry1).init(false);
      new RidbagBucketInitOp(0, 0, 0, new LogSequenceNumber(0, 0), false)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertFalse(new Bucket(entry2).isLeaf());
    });
  }

  /** switchBucketType: leaf → non-leaf. */
  @Test
  public void testSwitchBucketTypeOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      // Init both as leaf (empty)
      new Bucket(entry1).init(true);
      new Bucket(entry2).init(true);

      // Switch type directly
      new Bucket(entry1).switchBucketType();

      // Switch type via redo
      new RidbagBucketSwitchBucketTypeOp(0, 0, 0, new LogSequenceNumber(0, 0))
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertFalse(new Bucket(entry2).isLeaf());
    });
  }

  /** setLeftSibling: set a specific page index. */
  @Test
  public void testSetLeftSiblingOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new Bucket(entry1).init(true);
      new Bucket(entry2).init(true);

      new Bucket(entry1).setLeftSibling(42L);
      new RidbagBucketSetLeftSiblingOp(0, 0, 0, new LogSequenceNumber(0, 0), 42L)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(42L, new Bucket(entry2).getLeftSibling());
    });
  }

  /** setRightSibling: set a specific page index. */
  @Test
  public void testSetRightSiblingOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new Bucket(entry1).init(true);
      new Bucket(entry2).init(true);

      new Bucket(entry1).setRightSibling(99L);
      new RidbagBucketSetRightSiblingOp(0, 0, 0, new LogSequenceNumber(0, 0), 99L)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(99L, new Bucket(entry2).getRightSibling());
    });
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
      var bucket = new Bucket(entry);
      bucket.init(true);
      Assert.assertTrue(bucket.isLeaf());
      Assert.assertTrue(bucket.isEmpty());
      Assert.assertEquals(-1L, bucket.getLeftSibling());
      Assert.assertEquals(-1L, bucket.getRightSibling());
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  // ---- Equals/hashCode ----

  @Test
  public void testInitOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new RidbagBucketInitOp(10, 20, 30, lsn, true);
    var op2 = new RidbagBucketInitOp(10, 20, 30, lsn, true);
    var op3 = new RidbagBucketInitOp(10, 20, 30, lsn, false);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetLeftSiblingOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new RidbagBucketSetLeftSiblingOp(10, 20, 30, lsn, 42L);
    var op2 = new RidbagBucketSetLeftSiblingOp(10, 20, 30, lsn, 42L);
    var op3 = new RidbagBucketSetLeftSiblingOp(10, 20, 30, lsn, 99L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testSetRightSiblingOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new RidbagBucketSetRightSiblingOp(10, 20, 30, lsn, 42L);
    var op2 = new RidbagBucketSetRightSiblingOp(10, 20, 30, lsn, 42L);
    var op3 = new RidbagBucketSetRightSiblingOp(10, 20, 30, lsn, 99L);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  // ---- toString coverage for all simple ops ----

  /**
   * toString() on all four simple bucket ops must return a non-null, non-empty string so
   * that ops are identifiable in debug logs.
   */
  @Test
  public void testAllSimpleOpsToString() {
    var lsn = new LogSequenceNumber(1, 10);
    Assert.assertFalse(
        new RidbagBucketInitOp(1, 2, 3, lsn, true).toString().isEmpty());
    Assert.assertFalse(
        new RidbagBucketSwitchBucketTypeOp(1, 2, 3, lsn).toString().isEmpty());
    Assert.assertFalse(
        new RidbagBucketSetLeftSiblingOp(1, 2, 3, lsn, 42L).toString().isEmpty());
    Assert.assertFalse(
        new RidbagBucketSetRightSiblingOp(1, 2, 3, lsn, 99L).toString().isEmpty());
  }
}
