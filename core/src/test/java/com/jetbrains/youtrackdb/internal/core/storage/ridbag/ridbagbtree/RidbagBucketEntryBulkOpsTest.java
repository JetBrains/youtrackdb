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
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for Ridbag Bucket entry, bulk, and updateValue PageOperation subclasses: addLeafEntry,
 * addNonLeafEntry, removeLeafEntry, removeNonLeafEntry, addAll, shrink, updateValue.
 * Covers record IDs, serialization roundtrips, factory roundtrips, redo correctness,
 * conditional registration, and equals/hashCode.
 */
public class RidbagBucketEntryBulkOpsTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Record ID verification ----

  @Test
  public void testAddLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_ADD_LEAF_ENTRY_OP,
        RidbagBucketAddLeafEntryOp.RECORD_ID);
    Assert.assertEquals(289, RidbagBucketAddLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testAddNonLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_ADD_NON_LEAF_ENTRY_OP,
        RidbagBucketAddNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(290, RidbagBucketAddNonLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testRemoveLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_REMOVE_LEAF_ENTRY_OP,
        RidbagBucketRemoveLeafEntryOp.RECORD_ID);
    Assert.assertEquals(291, RidbagBucketRemoveLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testRemoveNonLeafEntryOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_REMOVE_NON_LEAF_ENTRY_OP,
        RidbagBucketRemoveNonLeafEntryOp.RECORD_ID);
    Assert.assertEquals(292, RidbagBucketRemoveNonLeafEntryOp.RECORD_ID);
  }

  @Test
  public void testAddAllOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_ADD_ALL_OP,
        RidbagBucketAddAllOp.RECORD_ID);
    Assert.assertEquals(293, RidbagBucketAddAllOp.RECORD_ID);
  }

  @Test
  public void testShrinkOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_SHRINK_OP,
        RidbagBucketShrinkOp.RECORD_ID);
    Assert.assertEquals(294, RidbagBucketShrinkOp.RECORD_ID);
  }

  @Test
  public void testUpdateValueOpRecordId() {
    Assert.assertEquals(WALRecordTypes.RIDBAG_BUCKET_UPDATE_VALUE_OP,
        RidbagBucketUpdateValueOp.RECORD_ID);
    Assert.assertEquals(295, RidbagBucketUpdateValueOp.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testAddLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(5, 200);
    byte[] key = {1, 2, 3};
    byte[] value = {4, 5};
    var original = new RidbagBucketAddLeafEntryOp(10, 20, 30, lsn, 0, key, value);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new RidbagBucketAddLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(0, deserialized.getIndex());
    Assert.assertArrayEquals(key, deserialized.getSerializedKey());
    Assert.assertArrayEquals(value, deserialized.getSerializedValue());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddNonLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(3, 100);
    var original = new RidbagBucketAddNonLeafEntryOp(
        10, 20, 30, lsn, 1, 5, 10, new byte[] {7, 8}, true);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new RidbagBucketAddNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(1, deserialized.getIndex());
    Assert.assertEquals(5, deserialized.getLeftChild());
    Assert.assertEquals(10, deserialized.getRightChild());
    Assert.assertTrue(deserialized.isUpdateNeighbors());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(7, 300);
    var original = new RidbagBucketRemoveLeafEntryOp(10, 20, 30, lsn, 2, 4, 6);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new RidbagBucketRemoveLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getEntryIndex());
    Assert.assertEquals(4, deserialized.getKeySize());
    Assert.assertEquals(6, deserialized.getValueSize());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveNonLeafEntryOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(7, 300);
    var original = new RidbagBucketRemoveNonLeafEntryOp(
        10, 20, 30, lsn, 1, new byte[] {1, 2}, 42);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new RidbagBucketRemoveNonLeafEntryOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(1, deserialized.getEntryIndex());
    Assert.assertArrayEquals(new byte[] {1, 2}, deserialized.getKey());
    Assert.assertEquals(42, deserialized.getPrevChild());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddAllOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(1, 50);
    var entries = List.of(new byte[] {1, 2}, new byte[] {3, 4, 5});
    var original = new RidbagBucketAddAllOp(10, 20, 30, lsn, entries);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new RidbagBucketAddAllOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(2, deserialized.getRawEntries().size());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testShrinkOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(1, 50);
    var entries = List.of(new byte[] {10, 20, 30});
    var original = new RidbagBucketShrinkOp(10, 20, 30, lsn, entries);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new RidbagBucketShrinkOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(1, deserialized.getRetainedEntries().size());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testUpdateValueOpSerializationRoundtrip() {
    var lsn = new LogSequenceNumber(2, 100);
    var original = new RidbagBucketUpdateValueOp(10, 20, 30, lsn, 0, new byte[] {5, 6}, 3);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new RidbagBucketUpdateValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(0, deserialized.getIndex());
    Assert.assertArrayEquals(new byte[] {5, 6}, deserialized.getValue());
    Assert.assertEquals(3, deserialized.getKeySize());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testAddLeafEntryOpFactoryRoundtrip() {
    var lsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketAddLeafEntryOp(
        10, 20, 30, lsn, 0, new byte[] {1}, new byte[] {2});

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketAddLeafEntryOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddNonLeafEntryOpFactoryRoundtrip() {
    var lsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketAddNonLeafEntryOp(
        10, 20, 30, lsn, 0, 1, 2, new byte[] {3}, false);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketAddNonLeafEntryOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testShrinkOpFactoryRoundtrip() {
    var lsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketShrinkOp(
        10, 20, 30, lsn, List.of(new byte[] {1, 2}));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketShrinkOp);
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testUpdateValueOpFactoryRoundtrip() {
    var lsn = new LogSequenceNumber(42, 1024);
    var original = new RidbagBucketUpdateValueOp(
        10, 20, 30, lsn, 0, new byte[] {10, 20}, 3);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof RidbagBucketUpdateValueOp);
    Assert.assertEquals(original, deserialized);
  }

  // ---- Redo correctness ----

  /** addLeafEntry: direct on page1, redo on page2. Byte-level identical. */
  @Test
  public void testAddLeafEntryOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new Bucket(entry1).init(true);
      new Bucket(entry2).init(true);

      byte[] key = {1, 2, 3};
      byte[] val = {4, 5};

      Assert.assertTrue(new Bucket(entry1).addLeafEntry(0, key, val));
      new RidbagBucketAddLeafEntryOp(0, 0, 0, new LogSequenceNumber(0, 0), 0, key, val)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    });
  }

  /** addNonLeafEntry: direct on page1, redo on page2. */
  @Test
  public void testAddNonLeafEntryOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new Bucket(entry1).init(false);
      new Bucket(entry2).init(false);

      byte[] key = {1, 2};

      Assert.assertTrue(new Bucket(entry1).addNonLeafEntry(0, 5, 10, key, false));
      new RidbagBucketAddNonLeafEntryOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, 5, 10, key, false)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    });
  }

  /** removeLeafEntry: add then remove on both pages. */
  @Test
  public void testRemoveLeafEntryOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      byte[] key = {1, 2, 3};
      byte[] val = {4, 5};

      new Bucket(entry1).init(true);
      new Bucket(entry1).addLeafEntry(0, key, val);

      new Bucket(entry2).init(true);
      new Bucket(entry2).addLeafEntry(0, key, val);

      new Bucket(entry1).removeLeafEntry(0, key.length, val.length);
      new RidbagBucketRemoveLeafEntryOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, key.length, val.length)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    });
  }

  /** addAll: add entries on both pages. */
  @Test
  public void testAddAllOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      new Bucket(entry1).init(true);
      new Bucket(entry2).init(true);

      // Raw entries: each is key+value bytes
      var entries = List.of(new byte[] {1, 2, 3, 4, 5}, new byte[] {6, 7, 8, 9, 10});

      new Bucket(entry1).addAll(entries);
      new RidbagBucketAddAllOp(0, 0, 0, new LogSequenceNumber(0, 0), entries)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    });
  }

  /** resetAndAddAll (shrink redo): reset + re-add on both pages. */
  @Test
  public void testShrinkOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      // Set up identical state: init + add 3 entries
      var entries = List.of(
          new byte[] {1, 2, 3, 4, 5},
          new byte[] {6, 7, 8, 9, 10},
          new byte[] {11, 12, 13, 14, 15});

      new Bucket(entry1).init(true);
      new Bucket(entry1).addAll(entries);
      new Bucket(entry2).init(true);
      new Bucket(entry2).addAll(entries);

      // Keep only first 2 entries
      var retained = List.of(
          new byte[] {1, 2, 3, 4, 5},
          new byte[] {6, 7, 8, 9, 10});

      // Direct: resetAndAddAll (same as shrink redo path)
      new Bucket(entry1).resetAndAddAll(retained);

      // Redo
      new RidbagBucketShrinkOp(0, 0, 0, new LogSequenceNumber(0, 0), retained)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertEquals(2, new Bucket(entry2).size());
    });
  }

  /** updateValueInPlace: in-place value update. */
  @Test
  public void testUpdateValueOpRedoCorrectness() {
    withTwoPages((entry1, cp1, entry2, cp2) -> {
      byte[] key = {1, 2, 3};
      byte[] val = {4, 5};
      byte[] newVal = {6, 7}; // Same size as val (in-place path)

      new Bucket(entry1).init(true);
      new Bucket(entry1).addLeafEntry(0, key, val);
      new Bucket(entry2).init(true);
      new Bucket(entry2).addLeafEntry(0, key, val);

      // Direct in-place update
      new Bucket(entry1).updateValueInPlace(0, newVal, key.length);

      // Redo
      new RidbagBucketUpdateValueOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, newVal, key.length)
          .redo(new Bucket(entry2));

      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    });
  }

  // ---- Equals/hashCode ----

  @Test
  public void testAddLeafEntryOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new RidbagBucketAddLeafEntryOp(10, 20, 30, lsn, 0, new byte[] {1}, new byte[] {2});
    var op2 = new RidbagBucketAddLeafEntryOp(10, 20, 30, lsn, 0, new byte[] {1}, new byte[] {2});
    var op3 = new RidbagBucketAddLeafEntryOp(10, 20, 30, lsn, 1, new byte[] {1}, new byte[] {2});

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testUpdateValueOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    var op1 = new RidbagBucketUpdateValueOp(10, 20, 30, lsn, 0, new byte[] {5}, 3);
    var op2 = new RidbagBucketUpdateValueOp(10, 20, 30, lsn, 0, new byte[] {5}, 3);
    var op3 = new RidbagBucketUpdateValueOp(10, 20, 30, lsn, 0, new byte[] {9}, 3);

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }

  // ---- Test helper ----

  @FunctionalInterface
  private interface TwoPageAction {
    void run(CacheEntry entry1, CachePointer cp1, CacheEntry entry2, CachePointer cp2);
  }

  private void withTwoPages(TwoPageAction action) {
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
      action.run(entry1, cp1, entry2, cp2);
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }
}
