package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.junit.Assert.assertArrayEquals;
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
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
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
import java.util.Map;
import java.util.Random;
import java.util.UUID;
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
    var initialSeal = seedTrustedGenerations(file);
    write(file, 0);
    cache.renameFile(file, "renamed.tst");
    assertTrue(tracker.isTrusted());
    assertEquals(file, cache.loadFile("renamed.tst"));
    assertTrue(tracker.isTrusted());
    assertPages(file, 0L, 8L);
    assertSealedPages(initialSeal, file, 7L);
    tracker.mergeBack(initialSeal);
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

  // Loading unknown existing content keeps marks but distrusts history. An ordinary explicit add
  // reuses the registered handle after shrink and clears old identity marks.
  @Test
  public void loadKeepsMarksAndDistrustsWhileShrinkReuseClearsMarks() throws Exception {
    long file = cache.bookFileId("loaded.tst");
    Path path = cache.getRootDirectory().resolve("loaded_" + cache.internalFileId(file) + ".tst");
    Files.createFile(path);
    tracker.mark(cache.internalFileId(file), 11);
    assertEquals(file, cache.loadFile("loaded.tst"));
    assertPages(file, 11L);
    assertFalse(tracker.isTrusted());
    write(file, 0);
    // Model the negative name registration with a still-owned handle used by explicit add.
    var names = (java.util.Map<String, Integer>) field(cache, "nameIdMap");
    names.put("loaded.tst", -cache.internalFileId(file));
    readCache.clear();
    assertEquals(file, cache.addFile("loaded.tst", file));
    assertPages(file);
    assertEquals(0, cache.physicalSizeForBackupSnapshot(file));
  }

  // Startup creation and removal keep both generations and trust. Unknown registration keeps
  // marks but distrusts inside and outside startup. End restores ordinary create/delete clearing.
  @Test
  public void startupReplayPreservesMarksAndTrustExceptUnknownRegistration()
      throws Exception {
    for (int event = 0; event < 6; event++) {
      String name = "replay" + event + ".tst";
      long file = cache.bookFileId(name);
      prepareFileEvent(event, file, name);
      var seal = seedTrustedGenerations(file);
      var before = capture();
      cache.beginStartupReplayPreservation();
      try {
        assertTrue(tracker.isTrusted());
        applyFileEvent(event, file, name);
        assertPages(file, 8L);
        assertSealedPages(seal, file, 7L);
        assertEquals("only unknown registration distrusts for event " + event,
            event != 0, tracker.isTrusted());
        if (event != 0) {
          assertEquals(before, capture());
        }
      } finally {
        cache.endStartupReplayPreservation();
      }
      readCache.deleteFile(file, cache);
      prepareFileEvent(event, file, name);
      seal = seedTrustedGenerations(file);
      applyFileEvent(event, file, name);
      assertPages(file, event == 0 ? new Long[] {8L} : new Long[0]);
      assertSealedPages(seal, file, event == 0 ? new Long[] {7L} : new Long[0]);
      assertEquals("only unknown registration distrusts outside startup", event != 0,
          tracker.isTrusted());
    }
  }

  // A rejected nested begin keeps the outer interval active. A missing physical file fails inside
  // that interval. Finally cleanup permits a new begin and restores ordinary creation clearing.
  @Test
  public void startupReplayRejectsNestedBeginAndFinallyCleanupSurvivesFileFailure()
      throws Exception {
    long file = cache.bookFileId("missing.tst");
    var seal = seedTrustedGenerations(file);
    cache.beginStartupReplayPreservation();
    try {
      assertThrows(IllegalStateException.class, cache::beginStartupReplayPreservation);
      cache.deleteFile(file);
      assertPages(file, 8L);
      assertSealedPages(seal, file, 7L);
      assertThrows(StorageException.class,
          () -> cache.loadFile("missing.tst"));
      // Unknown-name fallback precedes the final disk check, even when that check fails.
      assertFalse(tracker.isTrusted());
    } finally {
      cache.endStartupReplayPreservation();
    }
    cache.endStartupReplayPreservation();
    cache.beginStartupReplayPreservation();
    try {
      assertPages(file, 8L);
    } finally {
      cache.endStartupReplayPreservation();
    }
    assertEquals(file, cache.addFile("missing.tst", file));
    assertPages(file);
    assertSealedPages(seal, file);
  }

  // A positive inventory entry never adopts unknown content. Both normal and startup opens keep
  // exact active/sealed candidates and established trust.
  @Test
  public void knownFileLoadKeepsBothGenerationsAndTrustInsideAndOutsideStartup() throws Exception {
    long file = cache.addFile("known.tst");
    var seal = seedTrustedGenerations(file);
    assertEquals(file, cache.loadFile("known.tst"));
    assertTrue(tracker.isTrusted());
    cache.beginStartupReplayPreservation();
    try {
      assertEquals(file, cache.loadFile("known.tst"));
      assertTrue(tracker.isTrusted());
      assertPages(file, 8L);
      assertSealedPages(seal, file, 7L);
    } finally {
      cache.endStartupReplayPreservation();
    }
  }

  // A missing name-id entry also adopts unknown content. Predict the allocated ID so both
  // generations can be seeded before registration, without installing an inventory entry.
  @Test
  public void missingEntryRegistrationKeepsContentAndMarksButDistrustsInBothIntervals()
      throws Exception {
    for (boolean startup : new boolean[] {false, true}) {
      long seed = startup ? 22 : 21;
      int internal = new Random(seed).nextInt(Integer.MAX_VALUE - 1) + 1;
      setField(cache, "fileIdGen", new Random(seed));
      String name = "unknown" + seed + ".tst";
      Path path = cache.getRootDirectory().resolve("unknown" + seed + "_" + internal + ".tst");
      // A complete physical page follows the file header. Opening must not truncate this fixture.
      byte[] content = new byte[File.HEADER_SIZE + pageSize];
      new Random(seed).nextBytes(content);
      Files.write(path, content);
      long file = cache.externalFileId(internal);
      var seal = seedTrustedGenerations(file);
      assertEquals(-1, cache.fileIdByName(name));
      if (startup) {
        cache.beginStartupReplayPreservation();
      }
      try {
        assertEquals(file, cache.loadFile(name));
        assertFalse(tracker.isTrusted());
        assertPages(file, 8L);
        assertSealedPages(seal, file, 7L);
        assertArrayEquals(content, Files.readAllBytes(path));
      } finally {
        cache.endStartupReplayPreservation();
      }
    }
  }

  // Startup create, shrink/reuse and deletion never upgrade untrusted history or remove marks.
  @Test
  public void startupFileEventsKeepUntrustedHistoryUntrusted() throws Exception {
    for (int event = 1; event < 6; event++) {
      String name = "untrusted" + event + ".tst";
      long file = cache.bookFileId(name);
      prepareFileEvent(event, file, name);
      var seal = seedTrustedGenerations(file);
      tracker.invalidate();
      long failures = capture().failureVersion();
      cache.beginStartupReplayPreservation();
      try {
        applyFileEvent(event, file, name);
        assertFalse(tracker.isTrusted());
        assertEquals(failures, capture().failureVersion());
        assertPages(file, 8L);
        assertSealedPages(seal, file, 7L);
      } finally {
        cache.endStartupReplayPreservation();
      }
    }
  }

  // Invalid explicit creation IDs must be rejected before physical or inventory changes, with
  // unrelated external storage bits, both inside and outside the preservation interval.
  @Test
  public void explicitAddRejectsNonpositiveInternalIdsBeforeMutationInBothIntervals()
      throws Exception {
    assertInvalidFileEvents(true);
  }

  // Invalid deletion IDs must be rejected even for absent files. Both generations stay intact,
  // and finally cleanup restores ordinary file-event clearing after each interval.
  @Test
  public void deleteRejectsNonpositiveInternalIdsBeforeMutationInBothIntervals()
      throws Exception {
    assertInvalidFileEvents(false);
  }

  private void assertInvalidFileEvents(boolean add) throws Exception {
    long valid = cache.addFile("valid.tst");
    for (boolean startup : new boolean[] {true, false}) {
      var seal = seedTrustedGenerations(valid);
      if (startup) {
        cache.beginStartupReplayPreservation();
      }
      try {
        for (int internal : new int[] {0, -1}) {
          long external = (31L << 32) | (internal & 0xffffffffL);
          assertEquals(internal, cache.internalFileId(external));
          var inventory = cache.files();
          var names = Map.copyOf((Map<?, ?>) field(cache, "nameIdMap"));
          var ids = Map.copyOf((Map<?, ?>) field(cache, "idNameMap"));
          var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
          var before = capture();
          var physical = new java.util.HashMap<Path, byte[]>();
          try (var paths = Files.list(cache.getRootDirectory())) {
            for (var path : paths.toList()) {
              physical.put(path, Files.readAllBytes(path));
            }
          }
          var failure = assertThrows(IllegalArgumentException.class, () -> {
            if (add) {
              cache.addFile("invalid.tst", external);
            } else {
              cache.deleteFile(external);
            }
          });
          assertEquals("Internal file identifier must be positive", failure.getMessage());
          assertEquals(inventory, cache.files());
          assertEquals(names, field(cache, "nameIdMap"));
          assertEquals(ids, field(cache, "idNameMap"));
          assertNull(handles.get(cache.externalFileId(internal)));
          try (var paths = Files.list(cache.getRootDirectory())) {
            assertEquals(physical.keySet(), new java.util.HashSet<>(paths.toList()));
          }
          for (var entry : physical.entrySet()) {
            assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
          }
          assertEquals(before, capture());
          assertPages(valid, 8L);
          assertSealedPages(seal, valid, 7L);
        }
      } finally {
        if (startup) {
          cache.endStartupReplayPreservation();
        }
      }
      cache.beginStartupReplayPreservation();
      cache.endStartupReplayPreservation();
      cache.deleteFile(valid);
      assertPages(valid);
      assertSealedPages(seal, valid);
      assertEquals(valid, cache.addFile("valid.tst", valid));
    }
  }

  /** Failed durable fallback changes no inventory or registry bytes, including tombstones. */
  @Test
  public void adoptionFallbackFailureRegistersNothingAndRetryKeepsMarks() throws Exception {
    for (boolean tombstone : new boolean[] {false, true}) {
      String name = tombstone ? "tombstone.tst" : "absent.tst";
      long seed = tombstone ? 32 : 31;
      int internal = new Random(seed).nextInt(Integer.MAX_VALUE - 1) + 1;
      setField(cache, "fileIdGen", new Random(seed));
      long file = tombstone ? cache.bookFileId(name) : cache.externalFileId(internal);
      internal = cache.internalFileId(file);
      int dot = name.lastIndexOf('.');
      Files.write(cache.getRootDirectory().resolve(name.substring(0, dot) + "_" + internal
          + name.substring(dot)), new byte[File.HEADER_SIZE + pageSize]);
      var seal = seedTrustedGenerations(file);
      var names = Map.copyOf((Map<?, ?>) field(cache, "nameIdMap"));
      var ids = Map.copyOf((Map<?, ?>) field(cache, "idNameMap"));
      var registry = cache.getRootDirectory().resolve("name_id_map_v3.cm");
      var bytes = Files.readAllBytes(registry);
      var side = cache.getRootDirectory().resolve(ChangedPageTrackerFile.FILE_NAME);
      Files.createDirectory(side);
      Files.writeString(side.resolve("blocker"), "cannot delete");
      assertThrows(java.io.IOException.class, () -> cache.loadFile(name));
      assertEquals(names, field(cache, "nameIdMap"));
      assertEquals(ids, field(cache, "idNameMap"));
      assertArrayEquals(bytes, Files.readAllBytes(registry));
      assertNull(((ClosableLinkedContainer<Long, File>) field(cache, "files")).get(file));
      Files.delete(side.resolve("blocker"));
      Files.delete(side);
      assertEquals(file, cache.loadFile(name));
      assertPages(file, 8L);
      assertSealedPages(seal, file, 7L);
      assertFalse(tracker.isTrusted());
    }
  }

  /** Retaking inventory observes registration, changed tombstones and removal during fallback. */
  @Test
  public void adoptionRetakesInventoryOutsideInvalidationAndUsesCurrentState() throws Exception {
    for (int event = 0; event < 3; event++) {
      String name = "retake" + event + ".tst";
      long original = cache.bookFileId(name);
      int internal = cache.internalFileId(original);
      var physical = cache.getRootDirectory().resolve("retake" + event + "_" + internal + ".tst");
      Files.write(physical, new byte[File.HEADER_SIZE + pageSize]);
      var observed = spy(tracker);
      setField(cache, "changedPageTracker", observed);
      var worker = Executors.newSingleThreadExecutor();
      int selected = event;
      long replacement = cache.externalFileId(internal == 1 ? 2 : internal - 1);
      doAnswer(call -> {
        call.callRealMethod();
        // A cooperating inventory writer must finish while invalidation is on this stack.
        worker.submit(() -> {
          if (selected == 0) {
            cache.addFile(name, original);
          } else if (selected == 1) {
            var lock =
                (com.jetbrains.youtrackdb.internal.common.concur.lock.ReadersWriterSpinLock) field(
                    cache,
                    "filesLock");
            lock.acquireWriteLock();
            try {
              ((Map<String, Integer>) field(cache, "nameIdMap"))
                  .put(name, -cache.internalFileId(replacement));
              ((Map<Integer, String>) field(cache, "idNameMap"))
                  .put(-cache.internalFileId(replacement), name);
              Files.move(physical, cache.getRootDirectory().resolve("retake" + selected + "_"
                  + cache.internalFileId(replacement) + ".tst"));
            } finally {
              lock.releaseWriteLock();
            }
          } else {
            Files.delete(physical);
          }
          return null;
        }).get(5, TimeUnit.SECONDS);
        return null;
      }).when(observed).invalidate();
      try {
        if (event == 2) {
          assertThrows(StorageException.class, () -> cache.loadFile(name));
          assertEquals(-1, cache.fileIdByName(name));
        } else {
          assertEquals(event == 0 ? original : replacement, cache.loadFile(name));
        }
      } finally {
        worker.shutdownNow();
        assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        setField(cache, "changedPageTracker", tracker);
      }
      // Rearm the conservative default for the next independent release-window scenario.
      tracker = new ChangedPageTracker();
      setField(cache, "changedPageTracker", tracker);
    }
  }

  /** Real registry loading opens handles before a DWL fault. Close must release those handles. */
  @Test
  public void closeReleasesHandlesAfterFailedRegisteredFilesLoad() throws Exception {
    long file = cache.addFile("partial.tst");
    cache.close();
    var log = spy(new DoubleWriteLogNoOP());
    org.mockito.Mockito.doThrow(new java.io.IOException("DWL open failed"))
        .when(log).open(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), anyInt());
    setField(cache, "doubleWriteLog", log);
    assertThrows(java.io.IOException.class, cache::loadRegisteredFiles);
    var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
    assertTrue(handles.get(file).isOpen());
    try {
      cache.close();
      assertNull(handles.get(file));
      org.mockito.Mockito.verify(log).close();
    } finally {
      // Reopen only for the fixture's normal destructive teardown.
      setField(cache, "doubleWriteLog", new DoubleWriteLogNoOP());
      cache.loadRegisteredFiles();
    }
  }

  private ChangedPageTracker.SaveState capture() {
    tracker.saveOrderLock().lock();
    try {
      return tracker.capture();
    } finally {
      tracker.saveOrderLock().unlock();
    }
  }

  private ChangedPageTracker.SealedGeneration seedTrustedGenerations(long file) throws Exception {
    tracker = ChangedPageTracker.restoration(UUID.randomUUID(), UUID.randomUUID(), true, null)
        .build();
    setField(cache, "changedPageTracker", tracker);
    tracker.mark(cache.internalFileId(file), 7);
    var seal = tracker.beginBackup();
    tracker.mark(cache.internalFileId(file), 8);
    assertTrue(tracker.isTrusted());
    return seal;
  }

  private void prepareFileEvent(int event, long file, String name) throws Exception {
    if (event == 0) {
      int dot = name.lastIndexOf('.');
      Files.write(cache.getRootDirectory().resolve(name.substring(0, dot) + "_"
          + cache.internalFileId(file) + name.substring(dot)),
          new byte[File.HEADER_SIZE + pageSize]);
    } else if (event == 3 || event == 4) {
      cache.addFile(name, file);
      if (event == 3) {
        write(file, 0);
        readCache.clear();
        var names = (java.util.Map<String, Integer>) field(cache, "nameIdMap");
        names.put(name, -cache.internalFileId(file));
      }
    }
  }

  private void applyFileEvent(int event, long file, String name) throws Exception {
    switch (event) {
      case 0 -> {
        assertEquals(file, cache.loadFile(name));
        assertEquals(1, cache.physicalSizeForBackupSnapshot(file));
      }
      case 1 -> assertEquals(file, cache.addFile(name));
      case 2, 3 -> {
        assertEquals(file, cache.addFile(name, file));
        assertEquals(0, cache.physicalSizeForBackupSnapshot(file));
      }
      case 4, 5 -> {
        readCache.deleteFile(file, cache);
        assertEquals(-1, cache.fileIdByName(name));
      }
      default -> throw new AssertionError("Unexpected event " + event);
    }
  }

  private void assertSealedPages(ChangedPageTracker.SealedGeneration seal, long file,
      Long... expected) {
    var pages = new ArrayList<Long>();
    seal.pages().forEachCandidate(cache.internalFileId(file), pages::add);
    assertEquals(List.of(expected), pages);
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
    assertAtomicOperationExcludesFreeze(false);
  }

  /** Runtime adoption remains counted by the freezer while its durable fallback is unresolved. */
  @Test
  public void runtimeAdoptionExcludesBackupFreezeThroughDurableFallback() throws Exception {
    assertAtomicOperationExcludesFreeze(true);
  }

  private void assertAtomicOperationExcludesFreeze(boolean adoption) throws Exception {
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
        var authority = wow.getRootDirectory().resolve(ChangedPageTrackerFile.FILE_NAME);
        if (adoption) {
          observed.saveOrderLock().lock();
          try {
            observed.saveSucceeded(observed.capture());
          } finally {
            observed.saveOrderLock().unlock();
          }
          observed.retire(observed.beginBackup());
          assertTrue(observed.isTrusted());
          assertEquals(ChangedPageTrackerFile.SaveResult.SAVED,
              ChangedPageTrackerFile.save(authority, observed, new LogSequenceNumber(0, 0)));
        }
        var manager = storage.getAtomicOperationsManager();
        var operation = new AtomicReference<AtomicOperation>();
        var marked = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var freezeEntered = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> pause = call -> {
          call.callRealMethod();
          if (operation.get() == null || !operation.get().isActive()) {
            return null;
          }
          if (!adoption) {
            assertTrue("the real notification must publish its candidate",
                pages(observed, call.getArgument(0)).contains(call.getArgument(1)));
          }
          marked.countDown();
          assertTrue(finish.await(10, TimeUnit.SECONDS));
          return null;
        };
        if (adoption) {
          doAnswer(pause).when(observed).invalidate();
        } else {
          doAnswer(pause).when(observed).mark(anyInt(), anyLong());
        }
        var workers = Executors.newFixedThreadPool(2);
        try {
          var writer = workers.submit(() -> {
            manager.executeInsideAtomicOperation(op -> {
              operation.set(op);
              if (adoption) {
                long file = wow.bookFileId("adopted.tst");
                Files.write(wow.getRootDirectory().resolve("adopted_"
                    + wow.internalFileId(file) + ".tst"), new byte[File.HEADER_SIZE + pageSize]);
                assertEquals(file, op.loadFile("adopted.tst"));
                assertFalse(Files.exists(authority));
              } else {
                long file = op.addFile("atomic.tst");
                try (var entry = op.allocatePageForWrite(file, 0)) {
                  new EntryPoint(entry).init();
                }
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
