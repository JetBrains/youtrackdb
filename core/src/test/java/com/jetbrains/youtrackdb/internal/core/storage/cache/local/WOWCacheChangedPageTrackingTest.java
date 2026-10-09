package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.directmemory.ByteBufferPool;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.FreezeKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EntryPoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;

@Category(SequentialTest.class)
public class WOWCacheChangedPageTrackingTest {

  @Rule
  public TemporaryFolder directory = new TemporaryFolder();
  private final int pageSize = 4096;
  private ByteBufferPool pool;
  private LockFreeReadCache readCache;
  private WOWCache cache;
  private CASDiskWriteAheadLog wal;
  private ChangedPageTracker tracker;
  private java.util.concurrent.ExecutorService io;
  private Object exclusiveAccess;
  private Object fileLock;

  @Before
  public void setUp() throws Exception {
    exclusiveAccess = GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.getValue();
    fileLock = GlobalConfiguration.FILE_LOCK.getValue();
    GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(false);
    GlobalConfiguration.FILE_LOCK.setValue(false);
    var path = directory.newFolder("cache").toPath();
    pool = new ByteBufferPool(pageSize);
    readCache = new LockFreeReadCache(pool, 128L * pageSize, pageSize);
    tracker = new ChangedPageTracker();
    io = Executors.newCachedThreadPool();
    wal = new CASDiskWriteAheadLog("marks", path, path, ContextConfiguration.WAL_DEFAULT_NAME,
        12000, 128, null, null, Integer.MAX_VALUE, Integer.MAX_VALUE, 25, true, Locale.US,
        -1, 1000, false, false, true, 10);
    cache = new WOWCache(pageSize, false, pool, wal, new DoubleWriteLogNoOP(), 0, 10000,
        128L * pageSize, path, "marks", new ClosableLinkedContainer<Long, File>(128), 1,
        ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME, ChecksumMode.StoreAndVerify,
        null, null, false, io, tracker);
    cache.loadRegisteredFiles();
  }

  @After
  public void tearDown() throws Exception {
    try {
      readCache.clear();
      cache.delete();
      wal.delete();
    } finally {
      io.shutdownNow();
      assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
      pool.clear();
      GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(exclusiveAccess);
      GlobalConfiguration.FILE_LOCK.setValue(fileLock);
    }
  }

  // A page stays dirty across a switch. Its second real write acquisition must mark the new
  // generation too, while dirty bookkeeping retains the earlier operation start LSN.
  @Test
  public void alreadyDirtyPageIsMarkedAgainThroughRealWriteAcquisition() throws Exception {
    long file = cache.addFile("dirty.tst");
    var entry = readCache.loadOrAddForWrite(file, 0, cache, false, new LogSequenceNumber(3, 1));
    assertPages(file, 0L);
    readCache.releaseFromWrite(entry, cache, true);
    var seal = tracker.beginBackup();
    assertPages(file);
    entry = readCache.loadOrAddForWrite(file, 0, cache, false, new LogSequenceNumber(5, 1));
    assertPages(file, 0L);
    readCache.releaseFromWrite(entry, cache, true);
    assertEquals(Long.valueOf(3), cache.getMinimalNotFlushedSegment());
    var sealedPages = new ArrayList<Long>();
    seal.pages().forEachCandidate(cache.internalFileId(file), sealedPages::add);
    assertEquals(List.of(0L), sealedPages);
  }

  // Non-durable acquisitions still allocate and store pages but never mark them or retain WAL.
  @Test
  public void nonDurableGrowthAndTruncationDoNotAddMarks() throws Exception {
    long file = cache.addFile("nondurable.tst", cache.bookFileId("nondurable.tst"), true);
    var entry = readCache.loadOrAddForWrite(file, 2, cache, false, null);
    readCache.releaseFromWrite(entry, cache, true);
    assertTrue(cache.isNonDurable(file));
    assertPages(file);
    assertEquals(null, cache.getMinimalNotFlushedSegment());
    readCache.shrinkFile(file, 0, cache);
    assertPages(file);
  }

  // Gap growth marks only the acquired durable page. Physical truncation keeps conservative
  // bits without a page loop. Backup page bounds, not bitmap clearing, exclude removed pages.
  @Test
  public void durableGapGrowthAndPhysicalTruncationNeedNoExtraMarks() throws Exception {
    long file = cache.addFile("growth.tst");
    write(file, 3);
    assertPages(file, 3L);
    readCache.shrinkFile(file, 0, cache);
    assertPages(file, 3L);
    assertEquals(0, cache.physicalSizeForBackupSnapshot(file));
  }

  // Creation clears stale marks, rename and an already registered load preserve them, deletion
  // clears both generations, and the name-only and explicit-ID recreation paths start empty.
  @Test
  public void creationDeletionReuseAndRenameRespectFileIdentity() throws Exception {
    long file = cache.bookFileId("identity.tst");
    tracker.mark(cache.internalFileId(file), 7);
    assertEquals(file, cache.addFile("identity.tst"));
    assertPages(file);
    write(file, 0);
    cache.renameFile(file, "renamed.tst");
    assertEquals(file, cache.loadFile("renamed.tst"));
    assertPages(file, 0L);
    var seal = tracker.beginBackup();
    write(file, 1);
    readCache.deleteFile(file, cache);
    assertPages(file);
    var sealed = new ArrayList<Long>();
    seal.pages().forEachCandidate(cache.internalFileId(file), sealed::add);
    assertTrue(sealed.isEmpty());
    tracker.mark(cache.internalFileId(file), 8);
    assertEquals(file, cache.addFile("renamed.tst"));
    assertPages(file);
    readCache.deleteFile(file, cache);
    tracker.mark(cache.internalFileId(file), 9);
    assertEquals(file, cache.addFile("renamed.tst", file));
    assertPages(file);
  }

  // A booked ID can load an existing physical file. An explicit add can also reuse a registered
  // handle after shrink. Both paths discard old identity marks before publishing the new name.
  @Test
  public void loadAndShrinkReuseClearMarks() throws Exception {
    long file = cache.bookFileId("loaded.tst");
    Path path = cache.getRootDirectory().resolve("loaded_" + cache.internalFileId(file) + ".tst");
    Files.createFile(path);
    tracker.mark(cache.internalFileId(file), 11);
    assertEquals(file, cache.loadFile("loaded.tst"));
    assertPages(file);
    write(file, 0);
    // Model the negative name registration with a still-owned handle used by explicit add.
    var names = (java.util.Map<String, Integer>) field(cache, "nameIdMap");
    names.put("loaded.tst", -cache.internalFileId(file));
    readCache.clear();
    assertEquals(file, cache.addFile("loaded.tst", file));
    assertPages(file);
    assertEquals(0, cache.physicalSizeForBackupSnapshot(file));
  }

  // A real mark allocation failure revokes established trust and reaches the write caller
  // before dirty bookkeeping can retain WAL. The same entry can then be acquired and cleared,
  // proving balanced lock and reference use.
  @Test
  public void failedMarkRevokesTrustAndReleasesRealCacheEntry() throws Exception {
    var failure = new OutOfMemoryError("injected mark failure");
    var armed = new java.util.concurrent.atomic.AtomicBoolean();
    tracker = new ChangedPageTracker(kind -> {
      if (armed.get() && kind == ChangedPageTracker.Allocation.SEGMENT) {
        throw failure;
      }
    });
    setField(cache, "changedPageTracker", tracker);
    tracker.saveOrderLock().lock();
    try {
      tracker.saveSucceeded(tracker.capture());
    } finally {
      tracker.saveOrderLock().unlock();
    }
    tracker.retire(tracker.beginBackup());
    assertTrue(tracker.isTrusted());
    long file = cache.addFile("failure.tst");
    armed.set(true);
    assertSame(failure, assertThrows(OutOfMemoryError.class,
        () -> readCache.loadOrAddForWrite(file, 0, cache, false, null)));
    assertFalse(tracker.isTrusted());
    assertNull("a failed mark must not publish dirty bookkeeping before the retry",
        cache.getMinimalNotFlushedSegment());
    armed.set(false);
    var worker = Executors.newSingleThreadExecutor();
    try {
      worker.submit(() -> write(file, 0)).get(10, TimeUnit.SECONDS);
    } finally {
      worker.shutdownNow();
      assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
    }
    readCache.clear();
    assertEquals(0, readCache.getUsedMemory());
  }

  // Commit applies a real durable page through LockFreeReadCache. Observe its mark while the
  // operation is still active. A concurrent TRANSIENT_QUIESCE cannot complete until that
  // notification and the operation finish. Reopening installs fresh, untrusted tracker state.
  @Test
  public void atomicCommitPublishesMarksBeforeCompletionAndWriteFreeze() throws Exception {
    String root = directory.newFolder("database").getAbsolutePath();
    ChangedPageTracker first;
    try (var tracks = (YouTrackDBImpl) YourTracks.instance(root)) {
      tracks.create("disk", DatabaseType.DISK,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
      try (var session = tracks.open("disk", "admin", "admin")) {
        var storage = (DiskStorage) session.getStorage();
        var wow = (WOWCache) storage.getWriteCache();
        first = (ChangedPageTracker) field(storage, "changedPageTracker");
        assertSame(first, field(wow, "changedPageTracker"));
        assertFalse(first.isTrusted());
        var observed = spy(first);
        setField(wow, "changedPageTracker", observed);
        var manager = storage.getAtomicOperationsManager();
        var operation = new AtomicReference<AtomicOperation>();
        var marked = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var freezeEntered = new CountDownLatch(1);
        doAnswer(call -> {
          call.callRealMethod();
          if (operation.get() == null || !operation.get().isActive()) {
            return null;
          }
          assertTrue("the real notification must publish its candidate",
              pages(observed, call.getArgument(0)).contains(call.getArgument(1)));
          marked.countDown();
          assertTrue(finish.await(10, TimeUnit.SECONDS));
          return null;
        }).when(observed).mark(anyInt(), anyLong());
        var workers = Executors.newFixedThreadPool(2);
        try {
          var writer = workers.submit(() -> {
            manager.executeInsideAtomicOperation(op -> {
              operation.set(op);
              long file = op.addFile("atomic.tst");
              try (var entry = op.allocatePageForWrite(file, 0)) {
                new EntryPoint(entry).init();
              }
            });
            return null;
          });
          if (!marked.await(10, TimeUnit.SECONDS)) {
            writer.get(10, TimeUnit.SECONDS);
            throw new AssertionError("Commit did not reach the marking notification");
          }
          var freezer = workers.submit(() -> {
            freezeEntered.countDown();
            long id = manager.freezeWriteOperations(FreezeKind.TRANSIENT_QUIESCE, null);
            manager.unfreezeWriteOperations(id);
            return null;
          });
          assertTrue(freezeEntered.await(10, TimeUnit.SECONDS));
          var requests = (java.util.concurrent.atomic.AtomicInteger) field(
              field(manager, "writeOperationsFreezer"), "freezeRequests");
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
          while (requests.get() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
          }
          assertEquals("freeze must register while the marked operation is active", 1,
              requests.get());
          assertFalse(freezer.isDone());
          finish.countDown();
          writer.get(10, TimeUnit.SECONDS);
          freezer.get(10, TimeUnit.SECONDS);
        } finally {
          finish.countDown();
          workers.shutdownNow();
          assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
      }
    }
    try (var tracks = (YouTrackDBImpl) YourTracks.instance(root);
        var session = tracks.open("disk", "admin", "admin")) {
      var reopened = (ChangedPageTracker) field(session.getStorage(), "changedPageTracker");
      assertNotSame(first, reopened);
      assertFalse(reopened.isTrusted());
    }
  }

  // Memory storage uses its own cache without a tracker and still commits page changes.
  @Test
  public void memoryOnlyStorageKeepsItsOwnWritePath() throws Exception {
    try (var tracks = (YouTrackDBImpl) YourTracks.instance(
        directory.newFolder("memory").getAbsolutePath())) {
      tracks.create("memory", DatabaseType.MEMORY,
          new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));
      try (var session = tracks.open("memory", "admin", "admin")) {
        assertTrue(session.getStorage()
            .getWriteCache() instanceof com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryOnlyDiskCache);
        session.executeInTx(tx -> tx.newVertex("V"));
        session.executeInTx(tx -> assertEquals(1, session.countClass("V")));
      }
    }
  }

  private void write(long file, int page) {
    CacheEntry entry = readCache.loadOrAddForWrite(file, page, cache, false, null);
    readCache.releaseFromWrite(entry, cache, true);
  }

  private void assertPages(long file, Long... expected) {
    assertEquals(List.of(expected), pages(tracker, cache.internalFileId(file)));
  }

  private static List<Long> pages(ChangedPageTracker tracker, int file) {
    tracker.saveOrderLock().lock();
    try {
      var pages = new ArrayList<Long>();
      tracker.capture().active().forEachCandidate(file, pages::add);
      return pages;
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private static Object field(Object target, String name) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    var field = target.getClass().getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }
}
