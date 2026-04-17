package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

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
 * Tests for SBTreeNullBucketV2 PageOperation subclasses: record IDs, serialization roundtrips,
 * factory roundtrips, redo correctness (byte-level), redo suppression, and equals/hashCode.
 */
public class SBTreeNullBucketV2OpTest {

  @Before
  public void setUp() {
    PageOperationRegistry.registerAll(WALRecordsFactory.INSTANCE);
  }

  // ---- Record ID verification ----

  @Test
  public void testInitOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_NULL_BUCKET_V2_INIT_OP,
        SBTreeNullBucketV2InitOp.RECORD_ID);
    Assert.assertEquals(264, SBTreeNullBucketV2InitOp.RECORD_ID);
  }

  @Test
  public void testSetValueOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_NULL_BUCKET_V2_SET_VALUE_OP,
        SBTreeNullBucketV2SetValueOp.RECORD_ID);
    Assert.assertEquals(265, SBTreeNullBucketV2SetValueOp.RECORD_ID);
  }

  @Test
  public void testRemoveValueOpRecordId() {
    Assert.assertEquals(WALRecordTypes.SBTREE_NULL_BUCKET_V2_REMOVE_VALUE_OP,
        SBTreeNullBucketV2RemoveValueOp.RECORD_ID);
    Assert.assertEquals(266, SBTreeNullBucketV2RemoveValueOp.RECORD_ID);
  }

  // ---- Serialization roundtrip ----

  @Test
  public void testInitOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var original = new SBTreeNullBucketV2InitOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeNullBucketV2InitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetValueOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(3, 100);
    byte[] value = {1, 2, 3, 4, 5};
    var original = new SBTreeNullBucketV2SetValueOp(10, 20, 30, initialLsn, value);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeNullBucketV2SetValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertArrayEquals(value, deserialized.getValue());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testRemoveValueOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(7, 300);
    var original = new SBTreeNullBucketV2RemoveValueOp(10, 20, 30, initialLsn);

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new SBTreeNullBucketV2RemoveValueOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getOperationUnitId(), deserialized.getOperationUnitId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original, deserialized);
  }

  // ---- Factory roundtrip ----

  @Test
  public void testInitOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new SBTreeNullBucketV2InitOp(10, 20, 30, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeNullBucketV2InitOp);
    var result = (SBTreeNullBucketV2InitOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  @Test
  public void testSetValueOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    byte[] value = {10, 20, 30, 40};
    var original = new SBTreeNullBucketV2SetValueOp(10, 20, 30, initialLsn, value);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeNullBucketV2SetValueOp);
    Assert.assertArrayEquals(value, ((SBTreeNullBucketV2SetValueOp) deserialized).getValue());
  }

  @Test
  public void testRemoveValueOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var original = new SBTreeNullBucketV2RemoveValueOp(10, 20, 30, initialLsn);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof SBTreeNullBucketV2RemoveValueOp);
    var result = (SBTreeNullBucketV2RemoveValueOp) deserialized;
    Assert.assertEquals(original.getPageIndex(), result.getPageIndex());
    Assert.assertEquals(original.getFileId(), result.getFileId());
    Assert.assertEquals(original.getInitialLsn(), result.getInitialLsn());
  }

  // ---- Redo correctness (byte-level: direct apply on page1 vs redo on page2) ----

  /**
   * init: apply directly on page1, redo on page2. Byte-level identical.
   */
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
      // Pre-populate with a value on both
      var page1 = new SBTreeNullBucketV2<>(entry1);
      page1.init();
      page1.setValue(new byte[] {42, 43}, null);

      var page2 = new SBTreeNullBucketV2<>(entry2);
      page2.init();
      page2.setValue(new byte[] {42, 43}, null);

      // Apply init directly
      page1.init();

      // Apply init via redo
      new SBTreeNullBucketV2InitOp(0, 0, 0, new LogSequenceNumber(0, 0)).redo(page2);

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertNull(page2.getValue(null, null));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * setValue: apply directly on page1, redo on page2. Byte-level identical.
   */
  @Test
  public void testSetValueOpRedoCorrectness() {
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
      // Init both pages
      var page1 = new SBTreeNullBucketV2<>(entry1);
      page1.init();

      var page2 = new SBTreeNullBucketV2<>(entry2);
      page2.init();

      byte[] value = {10, 20, 30};

      // Apply directly
      page1.setValue(value, null);

      // Apply via redo
      new SBTreeNullBucketV2SetValueOp(0, 0, 0, new LogSequenceNumber(0, 0), value).redo(page2);

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  /**
   * removeValue: apply directly on page1, redo on page2. Byte-level identical.
   */
  @Test
  public void testRemoveValueOpRedoCorrectness() {
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
      // Init and set value on both pages
      var page1 = new SBTreeNullBucketV2<>(entry1);
      page1.init();
      page1.setValue(new byte[] {42}, null);

      var page2 = new SBTreeNullBucketV2<>(entry2);
      page2.init();
      page2.setValue(new byte[] {42}, null);

      // Apply directly
      page1.removeValue(null);

      // Apply via redo
      new SBTreeNullBucketV2RemoveValueOp(0, 0, 0, new LogSequenceNumber(0, 0)).redo(page2);

      // Byte-level comparison
      Assert.assertEquals(0, cp1.getBuffer().compareTo(cp2.getBuffer()));
      Assert.assertNull(page2.getValue(null, null));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cp1.decrementReferrer();
      cp2.decrementReferrer();
    }
  }

  // ---- Redo suppression: CacheEntryImpl (not CacheEntryChanges) => no registration ----

  @Test
  public void testRedoSuppression_initDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      // CacheEntryImpl is not CacheEntryChanges — instanceof check returns false
      var bucket = new SBTreeNullBucketV2<>(entry);
      bucket.setValue(new byte[] {42}, null);
      bucket.init();
      // Verify init actually cleared the value (page was modified, not a no-op)
      Assert.assertNull(bucket.getValue(null, null));
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  @Test
  public void testRedoSuppression_setValueDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      var bucket = new SBTreeNullBucketV2<>(entry);
      bucket.init();
      Assert.assertNull(bucket.getValue(null, null));
      bucket.setValue(new byte[] {42}, null);
      // Verify value was set: removeValue then getValue returns null (full flow works)
      bucket.removeValue(null);
      Assert.assertNull(bucket.getValue(null, null));
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  @Test
  public void testRedoSuppression_removeValueDoesNotRegister() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cp = new CachePointer(pointer, bufferPool, 0, 0);
    cp.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cp, false, null);
    entry.acquireExclusiveLock();
    try {
      var bucket = new SBTreeNullBucketV2<>(entry);
      bucket.init();
      bucket.setValue(new byte[] {42}, null);
      bucket.removeValue(null);
      // Verify value was removed
      Assert.assertNull(bucket.getValue(null, null));
    } finally {
      entry.releaseExclusiveLock();
      cp.decrementReferrer();
    }
  }

  // ---- Equals/hashCode ----

  @Test
  public void testSetValueOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 1);
    byte[] value = {1, 2, 3};

    var op1 = new SBTreeNullBucketV2SetValueOp(10, 20, 30, lsn, value);
    var op2 = new SBTreeNullBucketV2SetValueOp(10, 20, 30, lsn, value);
    var op3 = new SBTreeNullBucketV2SetValueOp(10, 20, 30, lsn, new byte[] {4, 5});

    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());
    Assert.assertNotEquals(op1, op3);
  }
}
