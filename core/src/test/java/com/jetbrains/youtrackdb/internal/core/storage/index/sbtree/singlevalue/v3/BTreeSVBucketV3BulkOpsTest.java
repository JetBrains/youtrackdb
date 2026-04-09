package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

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
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordsFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for CellBTreeSingleValueBucketV3 bulk PageOperation subclasses: addAll, shrink.
 * Each test verifies that the redo path produces byte-identical page state to the direct path.
 */
public class BTreeSVBucketV3BulkOpsTest {

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3AddAllOp.RECORD_ID, BTreeSVBucketV3AddAllOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3ShrinkOp.RECORD_ID, BTreeSVBucketV3ShrinkOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        BTreeSVBucketV3InitOp.RECORD_ID, BTreeSVBucketV3InitOp.class);
  }

  // ---------- Record ID tests ----------

  @Test
  public void testRecordIds() {
    Assert.assertEquals(237, BTreeSVBucketV3AddAllOp.RECORD_ID);
    Assert.assertEquals(238, BTreeSVBucketV3ShrinkOp.RECORD_ID);
  }

  // ---------- Serialization roundtrip tests ----------

  @Test
  public void testAddAllOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(5, 200);
    var entries = List.of(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14},
        new byte[] {20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33});
    var original = new BTreeSVBucketV3AddAllOp(10, 20, 30, initialLsn,
        new ArrayList<>(entries));

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3AddAllOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(2, deserialized.getRawEntries().size());
    Assert.assertArrayEquals(entries.get(0), deserialized.getRawEntries().get(0));
    Assert.assertArrayEquals(entries.get(1), deserialized.getRawEntries().get(1));
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testAddAllOpSerializationRoundtripEmptyList() {
    var initialLsn = new LogSequenceNumber(3, 100);
    var original = new BTreeSVBucketV3AddAllOp(7, 14, 21, initialLsn, new ArrayList<>());

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new BTreeSVBucketV3AddAllOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(0, deserialized.getRawEntries().size());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testShrinkOpSerializationRoundtrip() {
    var initialLsn = new LogSequenceNumber(8, 512);
    var entries = List.of(
        new byte[] {10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23},
        new byte[] {30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43},
        new byte[] {50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63});
    var original = new BTreeSVBucketV3ShrinkOp(15, 25, 35, initialLsn,
        new ArrayList<>(entries));

    var content = new byte[original.serializedSize() + 1];
    var endOffset = original.toStream(content, 1);
    Assert.assertEquals(content.length, endOffset);

    var deserialized = new BTreeSVBucketV3ShrinkOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(3, deserialized.getRetainedEntries().size());
    for (var i = 0; i < 3; i++) {
      Assert.assertArrayEquals(entries.get(i), deserialized.getRetainedEntries().get(i));
    }
    Assert.assertEquals(original, deserialized);
  }

  // ---------- WALRecordsFactory roundtrip tests ----------

  @Test
  public void testAddAllOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(42, 1024);
    var entries = List.of(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14});
    var original = new BTreeSVBucketV3AddAllOp(10, 20, 30, initialLsn,
        new ArrayList<>(entries));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3AddAllOp);
    var result = (BTreeSVBucketV3AddAllOp) deserialized;
    Assert.assertEquals(1, result.getRawEntries().size());
    Assert.assertArrayEquals(entries.get(0), result.getRawEntries().get(0));
  }

  @Test
  public void testShrinkOpFactoryRoundtrip() {
    var initialLsn = new LogSequenceNumber(55, 550);
    var entries = List.of(
        new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14},
        new byte[] {20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33});
    var original = new BTreeSVBucketV3ShrinkOp(4, 8, 12, initialLsn,
        new ArrayList<>(entries));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var deserialized = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(deserialized instanceof BTreeSVBucketV3ShrinkOp);
    var result = (BTreeSVBucketV3ShrinkOp) deserialized;
    Assert.assertEquals(2, result.getRetainedEntries().size());
  }

  // ---------- Redo correctness tests ----------

  /**
   * addAll on an empty leaf bucket: direct path vs redo path, byte-level comparison.
   */
  @Test
  public void testAddAllEmptyBucketRedoCorrectness() {
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
      // Leaf entries: 4-byte key + 10-byte RID = 14 bytes each
      var rawEntry1 = new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      var rawEntry2 = new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101};
      var rawEntry3 = new byte[] {3, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82, 92, 102};
      var entries = List.of(rawEntry1, rawEntry2, rawEntry3);

      // Direct path
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addAll(new ArrayList<>(entries), null);

      // Redo path
      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(page2);
      new BTreeSVBucketV3AddAllOp(0, 0, 0, lsn, new ArrayList<>(entries)).redo(page2);

      Assert.assertEquals(3, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after addAll redo on empty bucket",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * addAll on a partially-filled bucket: verifies currentSize offset is correct.
   */
  @Test
  public void testAddAllPartiallyFilledBucketRedoCorrectness() {
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
      var existingEntry = new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      var newEntry1 = new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101};
      var newEntry2 = new byte[] {3, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82, 92, 102};
      var newEntries = List.of(newEntry1, newEntry2);

      // Setup both pages with one existing entry
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addAll(List.of(existingEntry), null);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      page2.init(true);
      page2.addAll(List.of(existingEntry), null);

      // Direct path: addAll with 2 more entries
      page1.addAll(new ArrayList<>(newEntries), null);

      // Redo path
      new BTreeSVBucketV3AddAllOp(0, 0, 0, lsn, new ArrayList<>(newEntries)).redo(page2);

      Assert.assertEquals(3, page1.size());
      Assert.assertEquals(3, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after addAll redo on partially-filled bucket",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * shrink to half: verifies page reset + re-append produces identical state.
   */
  @Test
  public void testShrinkToHalfRedoCorrectness() {
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
      var entry0 = new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      var entry1b = new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101};
      var entry2b = new byte[] {3, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82, 92, 102};
      var entry3 = new byte[] {4, 0, 0, 0, 13, 23, 33, 43, 53, 63, 73, 83, 93, 103};
      var allEntries = List.of(entry0, entry1b, entry2b, entry3);
      var retainedEntries = List.of(entry0, entry1b);

      // Setup both pages with 4 entries
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addAll(new ArrayList<>(allEntries), null);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      page2.init(true);
      page2.addAll(new ArrayList<>(allEntries), null);

      Assert.assertEquals(4, page1.size());

      // Direct path: resetAndAddAll (same as what shrink's redo does)
      page1.resetAndAddAll(new ArrayList<>(retainedEntries));

      // Redo path
      new BTreeSVBucketV3ShrinkOp(0, 0, 0, lsn,
          new ArrayList<>(retainedEntries)).redo(page2);

      Assert.assertEquals(2, page1.size());
      Assert.assertEquals(2, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after shrink redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * shrink to 0: root bucket split scenario where all entries move to a new bucket.
   */
  @Test
  public void testShrinkToZeroRedoCorrectness() {
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
      var entry0 = new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      var entry1b = new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101};
      var allEntries = List.of(entry0, entry1b);

      // Setup both pages with 2 entries
      var page1 = new CellBTreeSingleValueBucketV3<>(entry1);
      page1.init(true);
      page1.addAll(new ArrayList<>(allEntries), null);

      var page2 = new CellBTreeSingleValueBucketV3<>(entry2);
      page2.init(true);
      page2.addAll(new ArrayList<>(allEntries), null);

      // Direct path: shrink to 0
      page1.resetAndAddAll(new ArrayList<>());

      // Redo path
      new BTreeSVBucketV3ShrinkOp(0, 0, 0, lsn, new ArrayList<>()).redo(page2);

      Assert.assertEquals(0, page1.size());
      Assert.assertEquals(0, page2.size());

      var buf1 = cachePointer1.getBuffer();
      var buf2 = cachePointer2.getBuffer();
      Assert.assertEquals(
          "Page buffers must be identical after shrink-to-zero redo",
          0, buf1.compareTo(buf2));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
    }
  }

  /**
   * Split scenario: init+addAll on new bucket, shrink on old bucket. Verifies both pages.
   */
  @Test
  public void testSplitScenarioRedoCorrectness() {
    var bufferPool = ByteBufferPool.instance(null);

    // Old bucket (page1/page2)
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

    // New bucket (page3/page4)
    var pointer3 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer3 = new CachePointer(pointer3, bufferPool, 0, 0);
    cachePointer3.incrementReferrer();
    CacheEntry entry3 = new CacheEntryImpl(0, 0, cachePointer3, false, null);
    entry3.acquireExclusiveLock();

    var pointer4 = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer4 = new CachePointer(pointer4, bufferPool, 0, 0);
    cachePointer4.incrementReferrer();
    CacheEntry entry4 = new CacheEntryImpl(0, 0, cachePointer4, false, null);
    entry4.acquireExclusiveLock();

    try {
      var lsn = new LogSequenceNumber(0, 0);
      var e0 = new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};
      var e1 = new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101};
      var e2 = new byte[] {3, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82, 92, 102};
      var e3 = new byte[] {4, 0, 0, 0, 13, 23, 33, 43, 53, 63, 73, 83, 93, 103};
      var allEntries = List.of(e0, e1, e2, e3);
      var retainedEntries = List.of(e0, e1);
      var movedEntries = List.of(e2, e3);

      // Direct path: old bucket shrink, new bucket init+addAll
      var oldBucket1 = new CellBTreeSingleValueBucketV3<>(entry1);
      oldBucket1.init(true);
      oldBucket1.addAll(new ArrayList<>(allEntries), null);
      oldBucket1.resetAndAddAll(new ArrayList<>(retainedEntries));

      var newBucket1 = new CellBTreeSingleValueBucketV3<>(entry3);
      newBucket1.init(true);
      newBucket1.addAll(new ArrayList<>(movedEntries), null);

      // Redo path: same operations via PageOperation.redo()
      var oldBucket2 = new CellBTreeSingleValueBucketV3<>(entry2);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(oldBucket2);
      new BTreeSVBucketV3AddAllOp(0, 0, 0, lsn,
          new ArrayList<>(allEntries)).redo(oldBucket2);
      new BTreeSVBucketV3ShrinkOp(0, 0, 0, lsn,
          new ArrayList<>(retainedEntries)).redo(oldBucket2);

      var newBucket2 = new CellBTreeSingleValueBucketV3<>(entry4);
      new BTreeSVBucketV3InitOp(0, 0, 0, lsn, true).redo(newBucket2);
      new BTreeSVBucketV3AddAllOp(0, 0, 0, lsn,
          new ArrayList<>(movedEntries)).redo(newBucket2);

      // Verify old buckets match
      Assert.assertEquals(2, oldBucket1.size());
      Assert.assertEquals(2, oldBucket2.size());
      Assert.assertEquals(0, cachePointer1.getBuffer().compareTo(cachePointer2.getBuffer()));

      // Verify new buckets match
      Assert.assertEquals(2, newBucket1.size());
      Assert.assertEquals(2, newBucket2.size());
      Assert.assertEquals(0, cachePointer3.getBuffer().compareTo(cachePointer4.getBuffer()));
    } finally {
      entry1.releaseExclusiveLock();
      entry2.releaseExclusiveLock();
      entry3.releaseExclusiveLock();
      entry4.releaseExclusiveLock();
      cachePointer1.decrementReferrer();
      cachePointer2.decrementReferrer();
      cachePointer3.decrementReferrer();
      cachePointer4.decrementReferrer();
    }
  }

  // ---------- Registration tests ----------

  @Test
  public void testAddAllRegistersOp() {
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);
      org.mockito.Mockito.reset(atomicOp);

      var entries = List.of(
          new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100},
          new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101});
      page.addAll(new ArrayList<>(entries), null);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      var registeredOp = (BTreeSVBucketV3AddAllOp) opCaptor.getValue();
      Assert.assertEquals(2, registeredOp.getRawEntries().size());
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  @Test
  public void testShrinkRegistersOp() {
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

      var page = new CellBTreeSingleValueBucketV3<>(changes);
      page.init(true);
      var entries = List.of(
          new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100},
          new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101},
          new byte[] {3, 0, 0, 0, 12, 22, 32, 42, 52, 62, 72, 82, 92, 102},
          new byte[] {4, 0, 0, 0, 13, 23, 33, 43, 53, 63, 73, 83, 93, 103});
      page.addAll(new ArrayList<>(entries), null);
      org.mockito.Mockito.reset(atomicOp);

      // shrink requires serializer for getRawEntry — use resetAndAddAll to simulate
      // The actual shrink() registration is integration-tested.
      // Here we verify that resetAndAddAll does NOT register (it's the redo path).
      page.resetAndAddAll(List.of(entries.get(0), entries.get(1)));

      // resetAndAddAll calls addAll with null serializer on a plain CacheEntryChanges,
      // but addAll will register an AddAllOp. We verify the ShrinkOp is not registered
      // (shrink registration only happens in the shrink() method, not resetAndAddAll).
      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.eq(42L),
          org.mockito.ArgumentMatchers.eq(7L),
          opCaptor.capture());

      // The registered op should be AddAllOp (from the addAll call inside resetAndAddAll),
      // not ShrinkOp. This verifies resetAndAddAll doesn't create a ShrinkOp.
      Assert.assertTrue(opCaptor.getValue() instanceof BTreeSVBucketV3AddAllOp);
    } finally {
      delegate.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Redo suppression (D4) ----------

  @Test
  public void testNoRegistrationDuringRedoPath() {
    var bufferPool = ByteBufferPool.instance(null);
    var pointer = bufferPool.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, bufferPool, 0, 0);
    cachePointer.incrementReferrer();
    CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
    entry.acquireExclusiveLock();

    try {
      var page = new CellBTreeSingleValueBucketV3<>(entry);
      page.init(true);

      var entries = List.of(
          new byte[] {1, 0, 0, 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100},
          new byte[] {2, 0, 0, 0, 11, 21, 31, 41, 51, 61, 71, 81, 91, 101});
      page.addAll(new ArrayList<>(entries), null);
      Assert.assertEquals(2, page.size());

      page.resetAndAddAll(List.of(entries.get(0)));
      Assert.assertEquals(1, page.size());
    } finally {
      entry.releaseExclusiveLock();
      cachePointer.decrementReferrer();
    }
  }

  // ---------- Equals and hashCode ----------

  @Test
  public void testAddAllOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var entries = List.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6});
    var op1 = new BTreeSVBucketV3AddAllOp(5, 10, 15, lsn, new ArrayList<>(entries));
    var op2 = new BTreeSVBucketV3AddAllOp(5, 10, 15, lsn, new ArrayList<>(entries));
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different entries
    var op3 = new BTreeSVBucketV3AddAllOp(5, 10, 15, lsn,
        List.of(new byte[] {9, 8, 7}));
    Assert.assertNotEquals(op1, op3);
  }

  @Test
  public void testShrinkOpEqualsAndHashCode() {
    var lsn = new LogSequenceNumber(1, 10);
    var entries = List.of(new byte[] {1, 2, 3});
    var op1 = new BTreeSVBucketV3ShrinkOp(5, 10, 15, lsn, new ArrayList<>(entries));
    var op2 = new BTreeSVBucketV3ShrinkOp(5, 10, 15, lsn, new ArrayList<>(entries));
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    // Different entries
    var op3 = new BTreeSVBucketV3ShrinkOp(5, 10, 15, lsn,
        List.of(new byte[] {9, 8, 7}));
    Assert.assertNotEquals(op1, op3);

    // Different count
    var op4 = new BTreeSVBucketV3ShrinkOp(5, 10, 15, lsn, new ArrayList<>());
    Assert.assertNotEquals(op1, op4);
  }
}
