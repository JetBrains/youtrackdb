package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.EndAtomicOperationHookTestSupport.primeFreezer;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable.AtomicOperationsSnapshot;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.MemoryWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.TestPageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryOnlyDiskCache;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Exercises supplied creation through real booking and manager completion, not a constant-id stub. */
public class AtomicOperationSuppliedIdTest {

  @Rule
  public TemporaryFolder diskDirectory = new TemporaryFolder();
  private AbstractStorage storage;
  private ScalableRWLock stateLock;
  private DirectMemoryOnlyDiskCache cache;
  private WriteAheadLog wal;
  private AtomicOperationsTable table;

  @Before
  public void setUp() throws ReflectiveOperationException {
    // The cache constructor is package-private. Keep this fixture in the atomic-operation package.
    var constructor = DirectMemoryOnlyDiskCache.class
        .getDeclaredConstructor(int.class, int.class, String.class);
    constructor.setAccessible(true);
    cache = spy(constructor.newInstance(4096, 1, "supplied"));
    storage = mock(AbstractStorage.class);
    wal = mock(WriteAheadLog.class);
    table = mock(AtomicOperationsTable.class);
    when(storage.getName()).thenReturn("supplied");
    when(storage.getWALInstance()).thenReturn(wal);
    // Exercise the real ownership accessor on an otherwise isolated storage fixture.
    stateLock = new ScalableRWLock();
    var field = AbstractStorage.class.getDeclaredField("stateLock");
    field.setAccessible(true);
    field.set(storage, stateLock);
    doCallRealMethod().when(storage).isStateWriteLockedByCurrentThread();
    stateLock.exclusiveLock();
  }

  @After
  public void tearDown() {
    cache.delete();
    if (stateLock != null && stateLock.isWriteLockedByCurrentThread()) {
      stateLock.exclusiveUnlock();
    }
  }

  private AtomicOperationBinaryTracking operation(ReadCache readCache, WriteCache writeCache) {
    var op = new AtomicOperationBinaryTracking(storage, readCache, writeCache, wal, 1,
        new AtomicOperationsSnapshot(0, 100, new LongOpenHashSet(), 0),
        new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(), new AtomicLong(),
        new ConcurrentSkipListMap<>(), new ConcurrentSkipListMap<>(), new AtomicLong());
    op.startToApplyOperations(42);
    return op;
  }

  private void end(AtomicOperation op) throws IOException {
    var manager = new AtomicOperationsManager(storage, table);
    primeFreezer(manager);
    manager.endAtomicOperation(op, null);
  }

  private void assertRolledBack(AtomicOperationBinaryTracking op) throws IOException {
    assertTrue(op.isRollbackInProgress());
    end(op);
    verify(table).rollbackOperation(42);
    verify(table, never()).commitOperation(anyLong());
    verify(table, never()).persistOperation(anyLong());
    verifyNoInteractions(wal);
  }

  @Test
  public void missingLockRejectsBeforeAnyCacheInspectionAndCaughtFailureCannotCommit()
      throws IOException {
    // Even an existing conflicting file is not inspected until exclusion is established.
    cache.addFile("existing", 7);
    clearInvocations(cache);
    stateLock.exclusiveUnlock();
    var op = operation(cache, cache);
    assertThrows(StorageException.class, () -> op.addFile("existing", 8, false, false));
    verify(cache).getStorageName();
    verifyNoMoreInteractions(cache);
    assertRolledBack(op);
    assertEquals(Integer.valueOf(7), cache.registeredFileId("existing"));
  }

  @Test
  public void cacheConflictAfterEarlierCreationRollsBackWithoutCommittingEndRecord()
      throws IOException {
    // A caller catches the conflict after another file was buffered. The manager must not apply it.
    cache.addFile("existing", 7);
    var readCache = mock(ReadCache.class);
    var op = operation(readCache, cache);
    op.addFile("earlier", 8, false, false);
    assertThrows(StorageException.class, () -> op.addFile("existing", 9, false, false));
    assertRolledBack(op);
    verifyNoInteractions(readCache);
    assertEquals(Integer.valueOf(7), cache.registeredFileId("existing"));
    assertFalse(cache.exists("earlier"));
  }

  @Test
  public void duplicatePendingNameRejectsEvenWithAnotherId() throws IOException {
    var op = operation(mock(ReadCache.class), cache);
    op.addFile("one", 7, false, false);
    assertThrows(StorageException.class, () -> op.addFile("one", 8, false, false));
    assertEquals((1L << 32) | 7, op.loadFile("one", 7));
    assertThrows(StorageException.class, () -> op.loadFile("one", 8));
    assertFalse(op.isFileExists("two", 8));
    verify(cache, times(1)).bookFileId(anyString(), anyLong(), anyBoolean());
    assertNull(cache.registeredFileId("two"));
    assertRolledBack(op);
  }

  @Test
  public void duplicatePendingIdUsesInternalBitsAcrossStorageQualifications() throws IOException {
    var op = operation(mock(ReadCache.class), cache);
    op.addFile("one", 7, false, false);
    assertThrows(StorageException.class,
        () -> op.addFile("two", (2L << 32) | 7, false, false));
    assertEquals((1L << 32) | 7, op.loadFile("one", 7));
    assertFalse(op.isFileExists("two", 7));
    assertThrows(StorageException.class, () -> op.loadFile("two", 7));
    verify(cache, times(1)).bookFileId(anyString(), anyLong(), anyBoolean());
    assertNull(cache.registeredFileId("two"));
    assertRolledBack(op);
  }

  @Test
  public void invalidIdIsRollbackOnlyBeforeBooking() throws IOException {
    var op = operation(cache, cache);
    assertThrows(IllegalArgumentException.class, () -> op.addFile("bad", 0, false, false));
    verify(cache, never()).bookFileId(anyString(), anyLong(), anyBoolean());
    assertRolledBack(op);
  }

  @Test
  public void pendingCreationIsPresentAndOpensBeforePhysicalInstallation() throws IOException {
    var op = operation(mock(ReadCache.class), cache);
    long id = op.addFile("pending", 7, true, false);
    assertFalse(cache.exists("pending"));
    assertTrue(op.isFileExists("pending", (9L << 32) | 7));
    assertEquals(id, op.loadFile("pending", 7));
    assertThrows(StorageException.class, () -> op.isFileExists("pending", 8));
    assertThrows(StorageException.class, () -> op.loadFile("pending", 8));
    verify(cache, never()).loadFile(anyString());
    assertTrue(op.isFileNonDurable(id));
  }

  @Test
  public void pendingDeletionIsAbsentButWrongExpectedIdentityStillFails() throws IOException {
    long id = cache.addFile("committed", 7);
    var op = operation(cache, cache);
    op.deleteFile(id);
    assertFalse(op.isFileExists("committed", 7));
    assertThrows(StorageException.class, () -> op.loadFile("committed", 7));
    assertThrows(StorageException.class, () -> op.isFileExists("committed", 8));
    assertThrows(StorageException.class, () -> op.loadFile("committed", 8));
    verify(cache, never()).loadFile(anyString());
  }

  @Test
  public void committedDeleteAndReaddReusesMatchingIdentityWithoutBooking() throws IOException {
    long id = cache.addFile("committed", 7);
    var op = operation(cache, cache);
    op.deleteFile(id);
    assertEquals(id, op.addFile("committed", (2L << 32) | 7, false, false));
    assertTrue(op.isFileExists("committed", 7));
    assertEquals(id, op.loadFile("committed", 7));
    verify(cache, never()).bookFileId(anyString(), anyLong(), anyBoolean());
    end(op);
    assertTrue(cache.exists("committed"));
    verify(table).commitOperation(42);
    verifyNoInteractions(wal);
  }

  @Test
  public void legacyCommittedDeleteReaddDeleteCommitsDeletion() throws IOException {
    assertCommittedDeleteReaddDelete(false);
  }

  @Test
  public void suppliedCommittedDeleteReaddDeleteCommitsDeletion() throws IOException {
    assertCommittedDeleteReaddDelete(true);
  }

  private void assertCommittedDeleteReaddDelete(boolean supplied) throws IOException {
    // Removing a reused entry must delete the committed file, not just its local overlay.
    wal = spy(new MemoryWriteAheadLog());
    when(storage.getWALInstance()).thenReturn(wal);
    long id = cache.addFile("committed", 7);
    var op = operation(cache, cache);
    op.deleteFile(id);
    assertEquals(id, supplied
        ? op.addFile("committed", id, false, false) : op.addFile("committed", false));
    op.deleteFile(id);
    assertFalse(op.isFileExists("committed", id));
    assertEquals(-1, op.fileIdByName("committed"));
    end(op);
    assertFalse(cache.exists("committed"));
    verify(cache).deleteFile(id, cache);
    verify(cache, never()).bookFileId(anyString());
    verify(cache, never()).bookFileId(anyString(), anyLong(), anyBoolean());
    verify(wal).log(any(FileDeletedWALRecord.class));
    verify(wal, never()).log(any(FileCreatedWALRecord.class));
    verify(table).commitOperation(42);
  }

  @Test
  public void repeatedCommittedReuseKeepsIdentityAndDurabilityChecks() throws IOException {
    // A third add still reuses committed state. Failed checks must leave deletion pending.
    long id = cache.addFile("committed", 7);
    for (boolean supplied : new boolean[] {false, true}) {
      var op = operation(cache, cache);
      op.deleteFile(id);
      op.addFile("committed", false);
      op.deleteFile(id);
      if (supplied) {
        assertThrows(StorageException.class,
            () -> op.addFile("committed", 8, false, false));
        assertThrows(StorageException.class, () -> op.addFile("other", id, false, false));
        assertThrows(StorageException.class, () -> op.addFile("committed", id, true, false));
      } else {
        assertThrows(StorageException.class, () -> op.addFile("committed", true));
        assertFalse(op.isRollbackInProgress());
      }
      assertFalse(op.isFileExists("committed", id));
      assertEquals(id, supplied
          ? op.addFile("committed", (2L << 32) | 7, false, false)
          : op.addFile("committed", false));
      assertEquals(id, op.loadFile("committed", id));
      assertFalse(op.isFileNonDurable(id));
      op.deleteFile(id);
    }
    verify(cache, never()).bookFileId(anyString());
    verify(cache, never()).bookFileId(anyString(), anyLong(), anyBoolean());
  }

  @Test
  public void deletingReusedFileDiscardsPageApplyAndLogsDeletionAfterPageOperations()
      throws IOException {
    // Cover both routes and both pending and already-flushed logical page operations.
    for (boolean supplied : new boolean[] {false, true}) {
      for (boolean flushBeforeDelete : new boolean[] {false, true}) {
        wal = spy(new MemoryWriteAheadLog());
        when(storage.getWALInstance()).thenReturn(wal);
        long id = cache.addFile("changed", 7);
        var op = operation(cache, cache);
        op.deleteFile(id);
        if (supplied) {
          op.addFile("changed", id, false, false);
        } else {
          op.addFile("changed", false);
        }
        var page = op.allocatePageForWrite(id, 0);
        page.getChanges().setByteValue(page.getCachePointer().getBuffer(), (byte) 99, 100);
        op.registerPageOperation(id, 0,
            new TestPageOperation(0, id, 42, new LogSequenceNumber(0, 0), 99));
        op.releasePageFromWrite(page);
        if (flushBeforeDelete) {
          op.flushPendingOperations();
        }
        op.deleteFile(id);
        clearInvocations(cache);
        end(op);
        assertFalse(cache.exists("changed"));
        verify(cache).deleteFile(id, cache);
        verify(cache, never()).loadOrAddForWrite(anyLong(), anyLong(), any(),
            anyBoolean(), any());
        // Recovery sees the deletion after any redo records for the discarded overlay.
        var order = inOrder(wal);
        order.verify(wal).log(any(TestPageOperation.class));
        order.verify(wal).log(any(FileDeletedWALRecord.class));
        order.verify(wal).log(any(AtomicUnitEndRecord.class));
        verify(wal, never()).log(any(FileCreatedWALRecord.class));
      }
    }
  }

  @Test
  public void committedDeleteAndReaddRejectsWrongExpectedId() throws IOException {
    long id = cache.addFile("committed", 7);
    var op = operation(cache, cache);
    op.deleteFile(id);
    assertThrows(StorageException.class, () -> op.addFile("committed", 8, true, false));
    assertRolledBack(op);
    assertTrue(cache.exists("committed"));
  }

  @Test
  public void deletedIdCannotBeReusedUnderAnotherName() throws IOException {
    long id = cache.addFile("committed", 7);
    var op = operation(cache, cache);
    op.deleteFile(id);
    assertThrows(StorageException.class, () -> op.addFile("other", 7, true, false));
    assertRolledBack(op);
    assertNull(cache.registeredFileId("other"));
  }

  @Test
  public void wrongLiveOrNegativeRegistrationFailsBeforeMutatingOpen() throws IOException {
    cache.addFile("live", 7);
    cache.bookFileId("negative", 8, false);
    var op = operation(cache, cache);
    for (String name : new String[] {"live", "negative"}) {
      assertThrows(StorageException.class, () -> op.isFileExists(name, 9));
      assertThrows(StorageException.class, () -> op.loadFile(name, 9));
    }
    verify(cache, never()).loadFile(anyString());
    assertEquals(Integer.valueOf(-8), cache.registeredFileId("negative"));
    assertTrue(op.isFileExists("live", 7));
    assertEquals((1L << 32) | 7, op.loadFile("live", 7));
    assertFalse(op.isFileExists("absent", 9));
    assertThrows(StorageException.class, () -> op.loadFile("absent", 9));
    assertNull(cache.registeredFileId("absent"));
  }

  @Test
  public void nonDurableMemoryCreationInstallsEagerlyAndCommitsWithoutWal() throws IOException {
    var op = operation(cache, cache);
    long id = op.addFile("eager", 7, true, false);
    assertTrue(cache.exists("eager"));
    assertTrue(op.isFileNonDurable(id));
    end(op);
    verify(cache, times(1)).addFile("eager", id, cache, true);
    verify(table).commitOperation(42);
    verify(table).persistOperation(42);
    verifyNoInteractions(wal);
  }

  @Test
  public void releasedCreationLockFailsBeforeCommitInsertionOrWal() throws IOException {
    table = spy(new AtomicOperationsTable(100, 0));
    table.startOperation(42, 3);
    var readCache = mock(ReadCache.class);
    var op = operation(readCache, cache);
    op.addFile("deferred", 7, false, false);
    op.getOrCreateIndexCountDeltas();
    op.getOrCreateHistogramDeltas();
    stateLock.exclusiveUnlock();
    assertThrows(StorageException.class, () -> end(op));
    verifyNoInteractions(readCache, wal);
    verify(table).rollbackOperation(42);
    verify(table, never()).commitOperation(anyLong());
    assertEquals(-1, table.getSegmentEarliestOperationInProgress());
    assertFalse(table.snapshotAtomicOperationTableState(42).inProgressTxs().contains(42));
    verify(storage, never()).persistIndexCountDeltas(any());
    verify(storage, never()).applyIndexCountDeltas(any());
    verify(storage, never()).applyHistogramDeltas(any());
  }

  @Test
  public void eagerMemoryInstallRetainsAcceptedRollbackLeftover() throws IOException {
    // Rollback prevents commit but does not undo the existing eager memory-install policy.
    var op = operation(cache, cache);
    op.addFile("leftover", 7, true, false);
    assertThrows(StorageException.class, () -> op.addFile("leftover", 8, true, false));
    assertRolledBack(op);
    assertTrue(cache.exists("leftover"));
    assertEquals(Integer.valueOf(7), cache.registeredFileId("leftover"));
  }

  @Test
  public void creationLockCoversRealBookingAndDeferredCacheInsertion() throws IOException {
    // Use the real lock and real booking, and observe exclusion at both mutation boundaries.
    doAnswer(invocation -> {
      assertTrue(stateLock.isWriteLockedByCurrentThread());
      return invocation.callRealMethod();
    }).when(cache).bookFileId("deferred", 7, false);
    var readCache = mock(ReadCache.class);
    when(readCache.addFile(eq("deferred"), anyLong(), eq(cache), eq(true)))
        .thenAnswer(invocation -> {
          assertTrue(stateLock.isWriteLockedByCurrentThread());
          return invocation.getArgument(1);
        });
    var op = operation(readCache, cache);
    long id = op.addFile("deferred", 7, true, false);
    end(op);
    verify(readCache).addFile("deferred", id, cache, true);
    verify(table).commitOperation(42);
    verifyNoInteractions(wal);
  }

  @Test
  public void durableCreationKeepsFileCreatedAndCommittingEndWalRecords() throws IOException {
    // Positive control: the supplied route retains ordinary durable WAL publication.
    wal = spy(new MemoryWriteAheadLog());
    when(storage.getWALInstance()).thenReturn(wal);
    var op = operation(cache, cache);
    op.addFile("durable", 7, false, false);
    end(op);
    verify(wal).log(any(FileCreatedWALRecord.class));
    verify(wal).log(any(AtomicUnitEndRecord.class));
    verify(table).commitOperation(42);
  }

  @Test
  public void diskConflictPreservesBytesAndNegativeLookupNeverReactivates() throws Exception {
    // JUnit owns the outside-checkout temporary directory and removes it after the test.
    var path = diskDirectory.getRoot().toPath();
    var executor = Executors.newCachedThreadPool();
    var pool = new ByteBufferPool(4096);
    var disk = spy(new WOWCache(4096, false, pool, new MemoryWriteAheadLog(),
        new DoubleWriteLogNoOP(), 10, 10000, 100, path, "supplied-disk",
        new ClosableLinkedContainer<>(1024), 1,
        ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, ChecksumMode.StoreAndVerify,
        null, null, false, executor));
    try {
      disk.loadRegisteredFiles();
      long liveId = disk.addFile("live.dat", 7);
      var backing = path.resolve(disk.nativeFileNameById(liveId));
      byte[] bytes = {1, 3, 5, 7};
      Files.write(backing, bytes);
      var op = operation(mock(ReadCache.class), disk);
      assertThrows(StorageException.class, () -> op.addFile("live.dat", 7, false, false));
      assertRolledBack(op);
      assertArrayEquals(bytes, Files.readAllBytes(backing));
      assertEquals(bytes.length, Files.size(backing));

      long deleted = disk.addFile("deleted.dat", 8);
      disk.deleteFile(deleted);
      var lookup = operation(mock(ReadCache.class), disk);
      assertThrows(StorageException.class, () -> lookup.loadFile("deleted.dat", 9));
      assertThrows(StorageException.class, () -> lookup.isFileExists("deleted.dat", 9));
      verify(disk, never()).loadFile(anyString());
      assertEquals(Integer.valueOf(-8), disk.registeredFileId("deleted.dat"));
      assertFalse(lookup.isFileExists("deleted.dat", 8));
    } finally {
      disk.delete();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      pool.clear();
    }
  }

  @Test
  public void suppliedCommittedReuseRejectsEitherDurabilityChangeBeforeCancellingDeletion()
      throws IOException {
    // Check both directions against cache-side classification and preserve pending deletion.
    for (boolean nonDurable : new boolean[] {false, true}) {
      String name = "durability-" + nonDurable;
      long id = cache.addFile(name, nonDurable ? 8 : 7);
      doAnswer(invocation -> nonDurable).when(cache).isNonDurable(id);
      var op = operation(cache, cache);
      op.deleteFile(id);
      assertThrows(StorageException.class,
          () -> op.addFile(name, id, !nonDurable, false));
      assertFalse(op.isFileExists(name, id));
      assertTrue(op.isRollbackInProgress());
      verify(cache, never()).bookFileId(eq(name), anyLong(), anyBoolean());
    }
  }

  @Test
  public void legacyCommittedReuseRejectsNonDurableToDurableWithoutMarkingRollback()
      throws IOException {
    // Match existing legacy addFile errors: rejection does not mark rollback.
    long id = cache.addFile("non-durable", 7);
    doAnswer(invocation -> true).when(cache).isNonDurable(id);
    var op = operation(cache, cache);
    op.deleteFile(id);
    assertThrows(StorageException.class, () -> op.addFile("non-durable", false));
    assertFalse(op.isFileExists("non-durable", id));
    assertFalse(op.isRollbackInProgress());
    assertEquals(id, op.addFile("non-durable", true));
    assertTrue(op.isFileNonDurable(id));
    verify(cache, never()).bookFileId(anyString());
  }

  @Test
  public void openedIdentityMustStillMatchAfterRegistrationInspection() throws IOException {
    // Model a concurrent replacement between inspection and the mutating open.
    cache.addFile("raced", 7);
    doAnswer(invocation -> (1L << 32) | 8).when(cache).loadFile("raced");
    var op = operation(cache, cache);
    assertThrows(StorageException.class, () -> op.loadFile("raced", 7));
  }

  @Test
  public void memoryPendingRecreationReusesBookingAndDiscardsOldPages() throws IOException {
    wal = spy(new MemoryWriteAheadLog());
    when(storage.getWALInstance()).thenReturn(wal);
    var op = operation(cache, cache);
    long id = op.addFile("recreated", 7, false, false);
    op.allocatePageForWrite(id, 0);
    assertEquals(1, cache.getFilledUpTo(id));
    op.deleteFile(id);
    assertFalse(op.isFileExists("recreated", 7));
    assertFalse(cache.exists("recreated"));
    assertEquals(id, op.addFile("recreated", (2L << 32) | 7, false, false));
    assertEquals(0, cache.getFilledUpTo(id));
    end(op);
    verify(cache, times(1)).bookFileId(anyString(), anyLong(), anyBoolean());
    verify(cache, times(2)).addFile("recreated", id, cache, false);
    verify(wal).log(any(FileCreatedWALRecord.class));
    verify(wal).log(any(AtomicUnitEndRecord.class));
  }

  @Test
  public void cancelledPendingBookingRejectsDifferentNameOrId() throws IOException {
    // Ownership is for the exact pair, even while the creation is absent locally.
    for (boolean changeName : new boolean[] {false, true}) {
      String name = "cancelled-" + changeName;
      long id = changeName ? 8 : 7;
      var op = operation(cache, cache);
      long booked = op.addFile(name, id, false, false);
      op.deleteFile(booked);
      assertThrows(StorageException.class,
          () -> op.addFile(changeName ? "other" : name, changeName ? id : 9, false, false));
      assertFalse(op.isFileExists(name, id));
      assertTrue(op.isRollbackInProgress());
      verify(cache, times(1)).bookFileId(eq(name), anyLong(), anyBoolean());
    }
  }

  @Test
  public void diskPendingRecreationReusesBookingAndCreatesDurableFile() throws Exception {
    // A real disk booking remains negative until the single commit-time installation.
    var path = diskDirectory.getRoot().toPath();
    var executor = Executors.newCachedThreadPool();
    var pool = new ByteBufferPool(4096);
    wal = spy(new MemoryWriteAheadLog());
    when(storage.getWALInstance()).thenReturn(wal);
    var disk = spy(new WOWCache(4096, false, pool, wal,
        new DoubleWriteLogNoOP(), 10, 10000, 100, path, "recreated-disk",
        new ClosableLinkedContainer<>(1024), 1,
        ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, ChecksumMode.StoreAndVerify,
        null, null, false, executor));
    try {
      disk.loadRegisteredFiles();
      var readCache = mock(ReadCache.class);
      when(readCache.addFile(anyString(), anyLong(), eq(disk), anyBoolean()))
          .thenAnswer(invocation -> disk.addFile(invocation.getArgument(0),
              invocation.getArgument(1)));
      var op = operation(readCache, disk);
      long id = op.addFile("recreated.dat", 7, false, false);
      op.deleteFile(id);
      assertFalse(op.isFileExists("recreated.dat", 7));
      assertThrows(StorageException.class, () -> op.loadFile("recreated.dat", 7));
      assertEquals(id, op.addFile("recreated.dat", (2L << 32) | 7, false, false));
      end(op);
      verify(disk, times(1)).bookFileId(anyString(), anyLong(), anyBoolean());
      verify(readCache, times(1)).addFile("recreated.dat", id, disk, false);
      assertTrue(Files.exists(path.resolve(disk.nativeFileNameById(id))));
      verify(wal).log(any(FileCreatedWALRecord.class));
      verify(wal).log(any(AtomicUnitEndRecord.class));

      // The cancelled booking also blocks either kind of conflicting pair on disk.
      for (boolean changeName : new boolean[] {false, true}) {
        String name = "cancelled-" + changeName;
        long expectedId = changeName ? 9 : 8;
        var rejected = operation(readCache, disk);
        long booked = rejected.addFile(name, expectedId, false, false);
        rejected.deleteFile(booked);
        assertThrows(StorageException.class, () -> rejected.addFile(
            changeName ? "other" : name, changeName ? expectedId : 10, false, false));
        assertFalse(rejected.isFileExists(name, expectedId));
        assertTrue(rejected.isRollbackInProgress());
        verify(disk, times(1)).bookFileId(eq(name), anyLong(), anyBoolean());
      }
    } finally {
      disk.delete();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
      pool.clear();
    }
  }

  @Test
  public void preflaggedRollbackWithNullErrorIsRecordedAsRollback() throws IOException {
    // Direct N42 regression, independent of which creation rejection set the flag.
    var op = operation(cache, cache);
    op.rollbackInProgress();
    assertRolledBack(op);
  }
}
