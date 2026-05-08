package com.jetbrains.youtrackdb.internal.core.storage.collection;

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
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

/**
 * Tests for the four simple CollectionPage logical WAL operations:
 * {@link CollectionPageInitOp}, {@link CollectionPageDeleteRecordOp},
 * {@link CollectionPageSetRecordVersionOp}, {@link CollectionPageDoDefragmentationOp}.
 * Covers serialization roundtrip, WALRecordsFactory integration, redo correctness,
 * and registration from mutation methods.
 */
public class CollectionPageSimpleOpsTest {

  private static final ByteBufferPool BUFFER_POOL = ByteBufferPool.instance(null);

  @Before
  public void registerRecordTypes() {
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPageInitOp.RECORD_ID, CollectionPageInitOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPageDeleteRecordOp.RECORD_ID, CollectionPageDeleteRecordOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPageSetRecordVersionOp.RECORD_ID, CollectionPageSetRecordVersionOp.class);
    WALRecordsFactory.INSTANCE.registerNewRecord(
        CollectionPageDoDefragmentationOp.RECORD_ID, CollectionPageDoDefragmentationOp.class);
  }

  // --- Helper methods ---

  private CacheEntry createRawCacheEntry() {
    var pointer = BUFFER_POOL.acquireDirect(true, Intention.TEST);
    var cachePointer = new CachePointer(pointer, BUFFER_POOL, 0, 0);
    cachePointer.incrementReferrer();
    try {
      CacheEntry entry = new CacheEntryImpl(0, 0, cachePointer, false, null);
      entry.acquireExclusiveLock();
      return entry;
    } catch (RuntimeException | Error e) {
      // If CacheEntryImpl constructor or acquireExclusiveLock throws, we still own the
      // referrer increment from above — release it before propagating so the page tracker
      // (enabled via -Dyoutrackdb.memory.directMemory.trackMode=true) does not report a
      // leaked direct-memory page at JVM shutdown and abort surefire with System.exit(1).
      cachePointer.decrementReferrer();
      throw e;
    }
  }

  private void releaseEntry(CacheEntry entry) {
    try {
      entry.releaseExclusiveLock();
    } finally {
      // Run the referrer release in finally so a buggy redo that corrupted entry state
      // (causing releaseExclusiveLock to throw) does not leak the direct-memory referrer
      // — see TX3 in the Track 19 Phase C iter-1 review findings for the full rationale.
      entry.getCachePointer().decrementReferrer();
    }
  }

  /**
   * Allocates a pair of cache entries for a two-page redo test, runs the action, and releases
   * both entries deterministically — even if entry-2 allocation throws (which would otherwise
   * leak entry-1's referrer and trigger the page-tracker JVM abort under
   * {@code -Dyoutrackdb.memory.directMemory.trackMode=true}).
   *
   * <p>The {@code try} block opens immediately after entry-1 is allocated, so a failure in
   * entry-2 setup unwinds through the {@code finally} that releases entry-1.
   */
  private void withTwoPages(java.util.function.BiConsumer<CacheEntry, CacheEntry> action) {
    var entry1 = createRawCacheEntry();
    try {
      var entry2 = createRawCacheEntry();
      try {
        action.accept(entry1, entry2);
      } finally {
        releaseEntry(entry2);
      }
    } finally {
      releaseEntry(entry1);
    }
  }

  // --- Record ID tests ---

  @Test
  public void testRecordIds() {
    Assert.assertEquals(203, CollectionPageInitOp.RECORD_ID);
    Assert.assertEquals(204, CollectionPageDeleteRecordOp.RECORD_ID);
    Assert.assertEquals(205, CollectionPageSetRecordVersionOp.RECORD_ID);
    Assert.assertEquals(206, CollectionPageDoDefragmentationOp.RECORD_ID);
  }

  // --- InitOp tests ---

  @Test
  public void testInitOpSerializationRoundtrip() {
    var original = new CollectionPageInitOp(10, 20, 30, new LogSequenceNumber(5, 100));

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new CollectionPageInitOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
  }

  @Test
  public void testInitOpFactoryRoundtrip() {
    var original = new CollectionPageInitOp(1, 2, 3, new LogSequenceNumber(10, 20));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var result = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(result instanceof CollectionPageInitOp);
    Assert.assertEquals(original.getPageIndex(), ((CollectionPageInitOp) result).getPageIndex());
  }

  @Test
  public void testInitOpRedoCorrectness() {
    // Use withTwoPages so a failure between the two acquireDirect() calls cannot leak
    // entry1's referrer (which would trigger the page-tracker JVM abort under
    // -Dyoutrackdb.memory.directMemory.trackMode=true).
    withTwoPages((entry1, entry2) -> {
      // Apply init directly
      var page1 = new CollectionPage(entry1);
      page1.init();

      // Apply init via redo
      var page2 = new CollectionPage(entry2);
      var op = new CollectionPageInitOp(0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page2);

      // Both pages should have the same initial state
      Assert.assertEquals(page1.getFreePosition(), page2.getFreePosition());
      Assert.assertEquals(page1.getFreeSpace(), page2.getFreeSpace());
      Assert.assertEquals(page1.getPageIndexesLength(), page2.getPageIndexesLength());
      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
    });
  }

  @Test
  public void testInitRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 50));

      var page = new CollectionPage(changes);
      page.init();

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyLong(),
          opCaptor.capture());
      Assert.assertTrue(opCaptor.getValue() instanceof CollectionPageInitOp);
    } finally {
      releaseEntry(entry);
    }
  }

  // --- DeleteRecordOp tests ---

  @Test
  public void testDeleteRecordOpSerializationRoundtrip() {
    var original = new CollectionPageDeleteRecordOp(
        10, 20, 30, new LogSequenceNumber(5, 100), 3, true);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new CollectionPageDeleteRecordOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
    Assert.assertEquals(original.getPosition(), deserialized.getPosition());
    Assert.assertEquals(original.isPreserveFreeListPointer(),
        deserialized.isPreserveFreeListPointer());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testDeleteRecordOpSerializationPreserveFalse() {
    var original = new CollectionPageDeleteRecordOp(
        1, 2, 3, new LogSequenceNumber(0, 0), 5, false);

    var content = new byte[original.serializedSize()];
    original.toStream(content, 0);

    var deserialized = new CollectionPageDeleteRecordOp();
    deserialized.fromStream(content, 0);

    Assert.assertFalse(deserialized.isPreserveFreeListPointer());
    Assert.assertEquals(5, deserialized.getPosition());
  }

  @Test
  public void testDeleteRecordOpFactoryRoundtrip() {
    var original = new CollectionPageDeleteRecordOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 7, true);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var result = (CollectionPageDeleteRecordOp) WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertEquals(7, result.getPosition());
    Assert.assertTrue(result.isPreserveFreeListPointer());
  }

  @Test
  public void testDeleteRecordRedoWithPreserveTrue() {
    withTwoPages((entry1, entry2) -> {
      // Set up pages with a record
      var page1 = new CollectionPage(entry1);
      page1.init();
      var record = new byte[] {1, 2, 3, 4, 5};
      page1.appendRecord(1L, record, -1, it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      var page2 = new CollectionPage(entry2);
      page2.init();
      page2.appendRecord(1L, record, -1, it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      // Delete on page1 directly
      page1.deleteRecord(0, true);

      // Delete on page2 via redo
      var op = new CollectionPageDeleteRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, true);
      op.redo(page2);

      // Both pages should have the same state
      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
      Assert.assertEquals(page1.getFreeSpace(), page2.getFreeSpace());
      Assert.assertTrue(page1.isDeleted(0));
      Assert.assertTrue(page2.isDeleted(0));
    });
  }

  @Test
  public void testDeleteRecordRedoWithPreserveFalse() {
    withTwoPages((entry1, entry2) -> {
      var page1 = new CollectionPage(entry1);
      page1.init();
      var record = new byte[] {10, 20, 30};
      page1.appendRecord(1L, record, -1, it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      var page2 = new CollectionPage(entry2);
      page2.init();
      page2.appendRecord(1L, record, -1, it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      // Delete last position without preserving free list pointer
      page1.deleteRecord(0, false);

      var op = new CollectionPageDeleteRecordOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, false);
      op.redo(page2);

      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
      Assert.assertEquals(page1.getFreeSpace(), page2.getFreeSpace());
      Assert.assertEquals(page1.getPageIndexesLength(), page2.getPageIndexesLength());
    });
  }

  @Test
  public void testDeleteRecordRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 50));

      var page = new CollectionPage(changes);
      page.init();
      page.appendRecord(1L, new byte[] {1, 2, 3}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      // Reset mock to ignore init and append registrations
      org.mockito.Mockito.reset(atomicOp);

      page.deleteRecord(0, true);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyLong(),
          opCaptor.capture());
      Assert.assertTrue(opCaptor.getValue() instanceof CollectionPageDeleteRecordOp);
      var deleteOp = (CollectionPageDeleteRecordOp) opCaptor.getValue();
      Assert.assertEquals(0, deleteOp.getPosition());
      Assert.assertTrue(deleteOp.isPreserveFreeListPointer());
    } finally {
      releaseEntry(entry);
    }
  }

  // --- SetRecordVersionOp tests ---

  @Test
  public void testSetRecordVersionOpSerializationRoundtrip() {
    var original = new CollectionPageSetRecordVersionOp(
        10, 20, 30, new LogSequenceNumber(5, 100), 2, 42);

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new CollectionPageSetRecordVersionOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPosition(), deserialized.getPosition());
    Assert.assertEquals(original.getVersion(), deserialized.getVersion());
    Assert.assertEquals(original, deserialized);
  }

  @Test
  public void testSetRecordVersionOpFactoryRoundtrip() {
    var original = new CollectionPageSetRecordVersionOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 5, 99);

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var result = (CollectionPageSetRecordVersionOp) WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertEquals(5, result.getPosition());
    Assert.assertEquals(99, result.getVersion());
  }

  @Test
  public void testSetRecordVersionRedoCorrectness() {
    withTwoPages((entry1, entry2) -> {
      var page1 = new CollectionPage(entry1);
      page1.init();
      page1.appendRecord(1L, new byte[] {1, 2, 3}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      var page2 = new CollectionPage(entry2);
      page2.init();
      page2.appendRecord(1L, new byte[] {1, 2, 3}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      // Set version directly
      page1.setRecordVersion(0, 42);

      // Set version via redo
      var op = new CollectionPageSetRecordVersionOp(
          0, 0, 0, new LogSequenceNumber(0, 0), 0, 42);
      op.redo(page2);

      Assert.assertEquals(page1.getRecordVersion(0), page2.getRecordVersion(0));
    });
  }

  @Test
  public void testSetRecordVersionRegistersOp() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 50));

      var page = new CollectionPage(changes);
      page.init();
      page.appendRecord(1L, new byte[] {1, 2}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());

      org.mockito.Mockito.reset(atomicOp);

      page.setRecordVersion(0, 10);

      var opCaptor = ArgumentCaptor.forClass(PageOperation.class);
      verify(atomicOp).registerPageOperation(
          org.mockito.ArgumentMatchers.anyLong(),
          org.mockito.ArgumentMatchers.anyLong(),
          opCaptor.capture());
      Assert.assertTrue(opCaptor.getValue() instanceof CollectionPageSetRecordVersionOp);
      var versionOp = (CollectionPageSetRecordVersionOp) opCaptor.getValue();
      Assert.assertEquals(0, versionOp.getPosition());
      Assert.assertEquals(10, versionOp.getVersion());
    } finally {
      releaseEntry(entry);
    }
  }

  @Test
  public void testSetRecordVersionNoRegistrationWhenFails() {
    var atomicOp = mock(AtomicOperation.class);
    var changes = new CacheEntryChanges(false, atomicOp);

    var entry = createRawCacheEntry();
    try {
      changes.setDelegate(entry);
      changes.setInitialLSN(new LogSequenceNumber(1, 50));

      var page = new CollectionPage(changes);
      page.init();

      org.mockito.Mockito.reset(atomicOp);

      // Try to set version on non-existent position — returns false, no op registered
      boolean result = page.setRecordVersion(0, 10);
      Assert.assertFalse(result);

      org.mockito.Mockito.verifyNoInteractions(atomicOp);
    } finally {
      releaseEntry(entry);
    }
  }

  // --- DoDefragmentationOp tests ---

  @Test
  public void testDoDefragmentationOpSerializationRoundtrip() {
    var original = new CollectionPageDoDefragmentationOp(
        10, 20, 30, new LogSequenceNumber(5, 100));

    var content = new byte[original.serializedSize() + 1];
    original.toStream(content, 1);

    var deserialized = new CollectionPageDoDefragmentationOp();
    deserialized.fromStream(content, 1);

    Assert.assertEquals(original.getPageIndex(), deserialized.getPageIndex());
    Assert.assertEquals(original.getFileId(), deserialized.getFileId());
    Assert.assertEquals(original.getInitialLsn(), deserialized.getInitialLsn());
  }

  @Test
  public void testDoDefragmentationOpFactoryRoundtrip() {
    var original = new CollectionPageDoDefragmentationOp(
        1, 2, 3, new LogSequenceNumber(10, 20));

    ByteBuffer serialized = WALRecordsFactory.toStream(original);
    var content = new byte[serialized.limit()];
    serialized.get(0, content);

    var result = WALRecordsFactory.INSTANCE.fromStream(content);
    Assert.assertTrue(result instanceof CollectionPageDoDefragmentationOp);
  }

  @Test
  public void testDoDefragmentationRedoCorrectness() {
    withTwoPages((entry1, entry2) -> {
      // Set up pages with fragmented state: add 3 records, delete the middle one
      var page1 = new CollectionPage(entry1);
      page1.init();
      page1.appendRecord(1L, new byte[] {1, 2, 3, 4}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());
      page1.appendRecord(2L, new byte[] {5, 6, 7}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());
      page1.appendRecord(3L, new byte[] {8, 9}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());
      page1.deleteRecord(1, true);

      var page2 = new CollectionPage(entry2);
      page2.init();
      page2.appendRecord(1L, new byte[] {1, 2, 3, 4}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());
      page2.appendRecord(2L, new byte[] {5, 6, 7}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());
      page2.appendRecord(3L, new byte[] {8, 9}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());
      page2.deleteRecord(1, true);

      // Defragment directly
      page1.doDefragmentation();

      // Defragment via redo
      var op = new CollectionPageDoDefragmentationOp(
          0, 0, 0, new LogSequenceNumber(0, 0));
      op.redo(page2);

      // Both pages should have the same state after defrag
      Assert.assertEquals(page1.getFreePosition(), page2.getFreePosition());
      Assert.assertEquals(page1.getFreeSpace(), page2.getFreeSpace());
      Assert.assertEquals(page1.getRecordsCount(), page2.getRecordsCount());
    });
  }

  // --- No registration during redo path ---

  @Test
  public void testNoRegistrationDuringRedoPath() {
    var entry = createRawCacheEntry();
    try {
      var page = new CollectionPage(entry);
      // Plain CacheEntry — instanceof CacheEntryChanges guard prevents registration
      page.init();
      Assert.assertEquals(0, page.getRecordsCount());

      int idx = page.appendRecord(1L, new byte[] {1, 2, 3}, -1,
          it.unimi.dsi.fastutil.ints.IntSets.emptySet());
      Assert.assertNotEquals(-1, idx);
      Assert.assertEquals(1, page.getRecordsCount());

      boolean versionSet = page.setRecordVersion(0, 5);
      Assert.assertTrue(versionSet);
      Assert.assertEquals(5, page.getRecordVersion(0));

      page.deleteRecord(0, true);
      Assert.assertTrue(page.isDeleted(0));
    } finally {
      releaseEntry(entry);
    }
  }

  // --- Equals / hashCode ---

  @Test
  public void testDeleteRecordOpEquals() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new CollectionPageDeleteRecordOp(5, 10, 15, lsn, 3, true);
    var op2 = new CollectionPageDeleteRecordOp(5, 10, 15, lsn, 3, true);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new CollectionPageDeleteRecordOp(5, 10, 15, lsn, 3, false);
    Assert.assertNotEquals(op1, op3);

    var op4 = new CollectionPageDeleteRecordOp(5, 10, 15, lsn, 99, true);
    Assert.assertNotEquals(op1, op4);
  }

  // --- toString coverage ---

  @Test
  public void testInitOpToString() {
    // CollectionPageInitOp does not override toString(), so the call resolves to
    // PageOperation.toString() which renders the simple class name and the
    // initialLsn value (PageOperation's only op-level field). A regression that
    // dropped PageOperation.toString() would fall back to AbstractWALRecord's
    // null-LSN format and lose the initialLsn substring.
    var op = new CollectionPageInitOp(7, 11, 13, new LogSequenceNumber(10, 20));
    var s = op.toString();
    Assert.assertTrue("toString must name the op class: " + s,
        s.contains("CollectionPageInitOp"));
    Assert.assertTrue("toString must include initialLsn segment value: " + s,
        s.contains("segment=10"));
    Assert.assertTrue("toString must include initialLsn position value: " + s,
        s.contains("position=20"));
  }

  @Test
  public void testDeleteRecordOpToString() {
    // DeleteRecordOp.toString() must render its op-specific fields position and
    // preserveFreeListPointer (from CollectionPageDeleteRecordOp.toString()) so the op is
    // identifiable in WAL logs.
    var op = new CollectionPageDeleteRecordOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 17, true);
    var s = op.toString();
    Assert.assertTrue("toString must name the op class: " + s,
        s.contains("CollectionPageDeleteRecordOp"));
    Assert.assertTrue("toString must include position value: " + s,
        s.contains("position=17"));
    Assert.assertTrue("toString must include preserveFreeListPointer: " + s,
        s.contains("preserveFreeListPointer=true"));
  }

  @Test
  public void testSetRecordVersionOpToString() {
    // SetRecordVersionOp.toString() must render position and version (the two op-specific
    // fields appended by CollectionPageSetRecordVersionOp.toString()).
    var op = new CollectionPageSetRecordVersionOp(
        1, 2, 3, new LogSequenceNumber(10, 20), 19, 23);
    var s = op.toString();
    Assert.assertTrue("toString must name the op class: " + s,
        s.contains("CollectionPageSetRecordVersionOp"));
    Assert.assertTrue("toString must include position value: " + s,
        s.contains("position=19"));
    Assert.assertTrue("toString must include version value: " + s,
        s.contains("version=23"));
  }

  @Test
  public void testDoDefragmentationOpToString() {
    // DoDefragmentationOp also inherits PageOperation.toString() (no op-specific fields
    // and no own override). Pin the class name and the initialLsn values — a regression
    // that dropped PageOperation.toString() would lose the initialLsn segment substring.
    var op = new CollectionPageDoDefragmentationOp(
        29, 2, 3, new LogSequenceNumber(31, 37));
    var s = op.toString();
    Assert.assertTrue("toString must name the op class: " + s,
        s.contains("CollectionPageDoDefragmentationOp"));
    Assert.assertTrue("toString must include initialLsn segment value: " + s,
        s.contains("segment=31"));
    Assert.assertTrue("toString must include initialLsn position value: " + s,
        s.contains("position=37"));
  }

  @Test
  public void testSetRecordVersionOpEquals() {
    var lsn = new LogSequenceNumber(1, 10);
    var op1 = new CollectionPageSetRecordVersionOp(5, 10, 15, lsn, 2, 42);
    var op2 = new CollectionPageSetRecordVersionOp(5, 10, 15, lsn, 2, 42);
    Assert.assertEquals(op1, op2);
    Assert.assertEquals(op1.hashCode(), op2.hashCode());

    var op3 = new CollectionPageSetRecordVersionOp(5, 10, 15, lsn, 2, 99);
    Assert.assertNotEquals(op1, op3);
  }
}
