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
import com.jetbrains.youtrackdb.internal.core.YouTrackDBEnginesManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.chm.LockFreeReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogGL;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.doublewritelog.DoubleWriteLogNoOP;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.operationsfreezer.FreezeKind;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas.CASDiskWriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.EntryPoint;
import java.io.IOException;
import java.nio.channels.FileChannel;
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
    // Wait for concurrent engine startup to install the metrics registry used by the WAL.
    YouTrackDBEnginesManager.startUp(false);
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
      try {
        io.shutdownNow();
        assertTrue(io.awaitTermination(10, TimeUnit.SECONDS));
        pool.clear();
      } finally {
        GlobalConfiguration.STORAGE_EXCLUSIVE_FILE_ACCESS.setValue(exclusiveAccess);
        GlobalConfiguration.FILE_LOCK.setValue(fileLock);
      }
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
      // A successful previous retry can leave an empty Windows invalidation tombstone.
      Files.deleteIfExists(side);
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

  /** Partial production log opens preserve recovery bytes and release all acquired handles. */
  @Test
  public void failedProductionLogOpenBeforeChannelPreservesSegmentsAndReleasesHandles()
      throws Exception {
    assertFailedProductionLogOpen(false);
  }

  /** A failure after channel assignment closes that channel without deleting recovery segments. */
  @Test
  public void failedProductionLogOpenAfterChannelPreservesSegmentsAndReleasesHandles()
      throws Exception {
    assertFailedProductionLogOpen(true);
  }

  private void assertFailedProductionLogOpen(boolean afterChannel) throws Exception {
    long file = cache.addFile("partial-production.tst");
    cache.close();
    var path = cache.getRootDirectory();
    var segment = path.resolve(ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME
        + (afterChannel ? "_0.dwl" : "_bad.dwl"));
    byte[] bytes = {3, 1, 4, 1, 5};
    Files.write(segment, bytes);
    var log = spy(new DoubleWriteLogGL(1024 * 1024, 4096, false));
    if (afterChannel) {
      doAnswer(call -> {
        call.callRealMethod();
        throw new IOException("after channel assignment");
      }).when(log).open(org.mockito.ArgumentMatchers.anyString(),
          org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), anyInt());
    }
    setField(cache, "doubleWriteLog", log);
    try {
      if (afterChannel) {
        assertThrows(IOException.class, cache::loadRegisteredFiles);
      } else {
        assertThrows(NumberFormatException.class, cache::loadRegisteredFiles);
      }
      var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
      var handle = handles.get(file);
      assertTrue(handle.isOpen());
      cache.close();
      assertFalse(handle.isOpen());
      assertNull(handles.get(file));
      assertArrayEquals(bytes, Files.readAllBytes(segment));
      var channel = (FileChannel) field(log, "currentFile");
      if (afterChannel) {
        assertFalse(channel.isOpen());
      }
      cache.close();
      assertArrayEquals(bytes, Files.readAllBytes(segment));
    } finally {
      var channel = (FileChannel) field(log, "currentFile");
      if (channel != null) {
        channel.close();
      }
      Files.deleteIfExists(segment);
      setField(cache, "doubleWriteLog", new DoubleWriteLogNoOP());
      cache.loadRegisteredFiles();
      cache.deleteFile(file);
    }
  }

  /** Real registry loading opens handles before a double-write log open failure. */
  @Test
  public void closeReleasesHandlesAfterFailedRegisteredFilesLoad() throws Exception {
    long file = cache.addFile("partial.tst");
    cache.close();
    var log = spy(new DoubleWriteLogNoOP());
    org.mockito.Mockito.doThrow(new java.io.IOException("double-write log open failed"))
        .when(log).open(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), anyInt());
    setField(cache, "doubleWriteLog", log);
    assertThrows(java.io.IOException.class, cache::loadRegisteredFiles);
    var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
    assertTrue(handles.get(file).isOpen());
    var handle = observeDataFile(file);
    setField(cache, "callFsync", true);
    var syncs = new java.util.concurrent.atomic.AtomicInteger();
    doAnswer(call -> {
      if (syncs.incrementAndGet() > 1) {
        throw new StorageException("marks", "must not run final sync after failed open");
      }
      return call.callRealMethod();
    }).when(handle).synch();
    try {
      cache.close();
      // The initial flush forces once. Failed-open cleanup adds no final force.
      org.mockito.Mockito.verify(handle).synch();
      assertFalse(handle.isOpen());
      assertNull(handles.get(file));
      org.mockito.Mockito.verify(log).close();
    } finally {
      // Reopen only for the fixture's normal destructive teardown.
      setField(cache, "doubleWriteLog", new DoubleWriteLogNoOP());
      cache.loadRegisteredFiles();
    }
  }

  /** Failed retention survives a normal flush that skips a locked page and keeps inherited DWL. */
  @Test
  public void rejectedCloseKeepsEverySegmentAndOpenDataFileDespiteNormalLockedPageFlush()
      throws Exception {
    long file = cache.addFile("close-locked.tst");
    var log = installCloseLog();
    var before = dwlBytes();
    var state = retention();
    seedRetention(file, 0, true, false);
    write(file, 0);
    var entry = readCache.loadForRead(file, 0, cache, true);
    var pointer = entry.getCachePointer();
    long stamp = pointer.acquireExclusiveLock();
    setField(cache, "callFsync", true);
    var handle = observeDataFile(file);
    var flushed = new java.util.concurrent.atomic.AtomicBoolean();
    var original = cache;
    cache = spy(cache);
    doAnswer(call -> {
      call.callRealMethod();
      assertFalse(((Map<?, ?>) field(cache, "writeCachePages")).isEmpty());
      rotationSync();
      flushed.set(true);
      return null;
    }).when(cache).flush();
    try {
      var failure = assertThrows(WriteCacheException.class, cache::close);
      assertTrue("the locked page must be skipped by a normally returning flush", flushed.get());
      assertTrue(failure.getCause().getMessage().contains("retained pages"));
      org.mockito.Mockito.verify(handle, org.mockito.Mockito.times(2)).synch();
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).acknowledgeSync(anyLong());
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).truncate(
          org.mockito.ArgumentMatchers.anySet());
      assertDwlBytes(before);
      var handles = (ClosableLinkedContainer<Long, File>) field(original, "files");
      assertTrue(handles.get(file).isOpen());
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).close();
      assertTrue(flushExecutor().submit(state::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    } finally {
      pointer.releaseExclusiveLock(stamp);
      readCache.releaseFromRead(entry);
      cache = original;
    }
  }

  /** Final synchronization releases successful writes, on the owner worker, before DWL close. */
  @Test
  public void finalSynchronizationReleasesPendingWriteBeforeDestructiveClose() throws Exception {
    long file = cache.addFile("close-success.tst");
    var log = installCloseLog();
    setField(cache, "callFsync", true);
    seedRetention(file, 0, false, true);
    var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
    var handle = spy(handles.get(file));
    handles.remove(file);
    handles.add(file, handle);
    var events = new ArrayList<String>();
    doAnswer(call -> {
      assertEquals(!(boolean) field(cache, "stopFlush"),
          retention().hasPendingSynchronization());
      events.add((boolean) field(cache, "stopFlush") ? "sync" : "initial-sync");
      return call.callRealMethod();
    }).when(handle).synch();
    var original = cache;
    cache = spy(cache);
    doAnswer(call -> {
      call.callRealMethod();
      events.add("flush");
      return null;
    }).when(cache).flush();
    doAnswer(call -> {
      assertFalse(flushExecutor().submit(retention()::hasPendingSynchronization)
          .get(10, TimeUnit.SECONDS));
      assertFalse(handle.isOpen());
      events.add("log-close");
      return call.callRealMethod();
    }).when(log).close();
    try {
      cache.close();
      assertEquals(List.of("initial-sync", "flush", "sync", "log-close"), events);
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).acknowledgeSync(anyLong());
      assertTrue(dwlBytes().isEmpty());
    } finally {
      cache = original;
    }
  }

  /** Failed final synchronization converts a successful write to unresolved and keeps all DWL. */
  @Test
  public void finalSynchronizationFailureKeepsCopiesAndCannotBeRepairedByAnotherSync()
      throws Exception {
    long file = cache.addFile("close-sync-failure.tst");
    installCloseLog();
    var before = dwlBytes();
    setField(cache, "callFsync", true);
    seedRetention(file, 0, false, true);
    var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
    var handle = spy(handles.get(file));
    handles.remove(file);
    handles.add(file, handle);
    var original = cache;
    cache = spy(cache);
    org.mockito.Mockito.doNothing().when(cache).flush();
    org.mockito.Mockito.doThrow(new StorageException("marks", "final force failed")).when(handle)
        .synch();
    try {
      assertThrows(com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException.class,
          cache::close);
      assertDwlBytes(before);
      assertTrue(handle.isOpen());
      var state = retention();
      flushExecutor().submit(() -> {
        assertTrue(state.hasUnresolvedPages());
        state.synchronizeOnFlushExecutor(() -> {
        });
        assertTrue(state.hasUnresolvedPages());
        return null;
      }).get(10, TimeUnit.SECONDS);
    } finally {
      org.mockito.Mockito.doCallRealMethod().when(handle).synch();
      cache = original;
    }
  }

  /** An append whose data write has not completed cannot pass the close guard. */
  @Test
  public void unfinishedWriteRejectsClose() throws Exception {
    long file = cache.addFile("close-pending.tst");
    seedRetention(file, 0, false, false);
    assertThrows(com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException.class,
        cache::close);
  }

  /** Unawaited work queued after the initial flush finishes before final sync and guard. */
  @Test
  public void closeBarrierObservesBackgroundWriteAfterInitialFlush() throws Exception {
    long file = cache.addFile("close-background.tst");
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var original = cache;
    var state = retention();
    var pointer = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
    org.mockito.Mockito.when(pointer.getFileId()).thenReturn(file);
    var background = new AtomicReference<java.util.concurrent.Future<?>>();
    cache = spy(cache);
    doAnswer(call -> {
      call.callRealMethod();
      background.set(flushExecutor().submit(() -> {
        started.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS));
        var attempt = state.appendSucceeded(pointer, 1L, true);
        state.writesFailed(List.of(attempt));
        return null;
      }));
      return null;
    }).when(cache).flush();
    var stopFuture = org.mockito.Mockito.mock(java.util.concurrent.Future.class);
    org.mockito.Mockito.when(stopFuture.get(anyLong(), org.mockito.ArgumentMatchers.any()))
        .thenAnswer(call -> {
          assertTrue(started.await(10, TimeUnit.SECONDS));
          release.countDown();
          return null;
        });
    setField(cache, "flushFuture", stopFuture);
    try {
      assertThrows(com.jetbrains.youtrackdb.internal.core.exception.WriteCacheException.class,
          cache::close);
      background.get().get(10, TimeUnit.SECONDS);
      assertTrue(flushExecutor().submit(state::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      cache = original;
    }
  }

  /** A locked dirty page without a retention record does not make close unsafe. */
  @Test
  public void lockedDirtyPageWithoutRetentionDoesNotRejectClose() throws Exception {
    long file = cache.addFile("close-locked-unretained.tst");
    var log = installCloseLog();
    setField(cache, "callFsync", true);
    var handle = observeDataFile(file);
    write(file, 0);
    var entry = readCache.loadForRead(file, 0, cache, true);
    var pointer = entry.getCachePointer();
    long stamp = pointer.acquireExclusiveLock();
    var flushed = new java.util.concurrent.atomic.AtomicBoolean();
    var original = cache;
    cache = spy(cache);
    doAnswer(call -> {
      call.callRealMethod();
      assertFalse(((Map<?, ?>) field(cache, "writeCachePages")).isEmpty());
      flushed.set(true);
      return null;
    }).when(cache).flush();
    try {
      cache.close();
      assertTrue(flushed.get());
      org.mockito.Mockito.verify(handle, org.mockito.Mockito.times(2)).synch();
      org.mockito.Mockito.verify(log).close();
      assertFalse(handle.isOpen());
      assertTrue(dwlBytes().isEmpty());
    } finally {
      pointer.releaseExclusiveLock(stamp);
      readCache.releaseFromRead(entry);
      cache = original;
    }
  }

  /** Rotation requests during close neither force nor acknowledge. The final barrier forces once. */
  @Test
  public void closeDefersRotationForcesUntilFinalSynchronization() throws Exception {
    long file = cache.addFile("close-rotation.tst");
    var log = installCloseLog();
    write(file, 0);
    setField(cache, "callFsync", true);
    seedRetention(file, 0, false, true);
    var handle = observeDataFile(file);
    var original = cache;
    cache = spy(cache);
    doAnswer(call -> {
      call.callRealMethod();
      // FileFlushTask itself still forces once. Rotation requests must add no further forces.
      org.mockito.Mockito.verify(handle).synch();
      rotationSync();
      rotationSync();
      org.mockito.Mockito.verify(handle).synch();
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).acknowledgeSync(anyLong());
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).truncate(
          org.mockito.ArgumentMatchers.anySet());
      assertFalse(flushExecutor().submit(retention()::hasPendingSynchronization)
          .get(10, TimeUnit.SECONDS));
      return null;
    }).when(cache).flush();
    try {
      cache.close();
      org.mockito.Mockito.verify(handle, org.mockito.Mockito.times(2)).synch();
      org.mockito.Mockito.verify(log).close();
      assertFalse(flushExecutor().submit(retention()::hasPendingSynchronization)
          .get(10, TimeUnit.SECONDS));
    } finally {
      cache = original;
    }
  }

  /** A background error stored after a successful initial flush reaches the close caller. */
  @Test
  public void closeBarrierReportsFlushErrorStoredAfterInitialFlush() throws Exception {
    long file = cache.addFile("close-background-error.tst");
    var log = installCloseLog();
    var before = dwlBytes();
    var failure = new IOException("late background write failure");
    var original = cache;
    cache = spy(cache);
    doAnswer(call -> {
      call.callRealMethod();
      flushExecutor().submit(() -> {
        setField(original, "flushError", failure);
        setField(cache, "flushError", failure);
        return null;
      });
      return null;
    }).when(cache).flush();
    try {
      var thrown = assertThrows(WriteCacheException.class, cache::close);
      assertTrue(thrown.getCause().getMessage().contains("Background flush failed before close"));
      assertSame(failure, thrown.getCause().getCause());
      assertDwlBytes(before);
      var handles = (ClosableLinkedContainer<Long, File>) field(original, "files");
      assertTrue(handles.get(file).isOpen());
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).close();
    } finally {
      setField(original, "flushError", null);
      cache = original;
    }
  }

  /** Close during a rotation force preserves inherited and current DWL files and the sync token. */
  @Test
  public void closeDuringRotationForceDefersCleanupAndAcknowledgement() throws Exception {
    long file = cache.addFile("close-inflight-rotation.tst");
    var log = installCloseLog();
    var request = log.write(new ArrayList<>(List.of(java.nio.ByteBuffer.allocate(pageSize))),
        it.unimi.dsi.fastutil.ints.IntArrayList.of(1),
        it.unimi.dsi.fastutil.ints.IntArrayList.of(3));
    assertTrue("rotation must require synchronization", request.syncRequired());
    var pendingToken = field(log, "pendingSyncToken");
    var before = dwlBytes();
    var inherited = cache.getRootDirectory()
        .resolve(ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_0.dwl");
    assertTrue(before.containsKey(inherited));
    assertTrue("rotation must have old files to remove", before.size() > 1);
    setField(cache, "callFsync", true);
    var handle = observeDataFile(file);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(call -> {
      started.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS));
      return call.callRealMethod();
    }).when(handle).synch();
    var sync = WOWCache.class.getDeclaredMethod("fsyncFiles", long.class);
    sync.setAccessible(true);
    var background = flushExecutor().submit(() -> {
      sync.invoke(cache, request.syncToken());
      return null;
    });
    try {
      assertTrue(started.await(10, TimeUnit.SECONDS));
      // Publish close entry exactly as close does, while the flush worker is inside synch.
      setField(cache, "deferDwlCleanup", true);
      release.countDown();
      background.get(10, TimeUnit.SECONDS);
      org.mockito.Mockito.verify(handle).synch();
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).acknowledgeSync(anyLong());
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).truncate(
          org.mockito.ArgumentMatchers.anySet());
      assertEquals(pendingToken, field(log, "pendingSyncToken"));
      assertDwlBytes(before);
      assertTrue(handle.isOpen());
    } finally {
      release.countDown();
      background.get(10, TimeUnit.SECONDS);
    }
  }

  /** Interrupting the barrier wait restores the flag and leaves data and DWL files open. */
  @Test
  public void interruptedCloseBarrierPreservesOpenFilesAndInterruptFlag() throws Exception {
    long file = cache.addFile("close-interrupted.tst");
    var log = installCloseLog();
    var before = dwlBytes();
    var handle = observeDataFile(file);
    var started = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var background = new AtomicReference<java.util.concurrent.Future<?>>();
    var original = cache;
    cache = spy(cache);
    doAnswer(call -> {
      call.callRealMethod();
      background.set(flushExecutor().submit(() -> {
        started.countDown();
        assertTrue(release.await(10, TimeUnit.SECONDS));
        return null;
      }));
      assertTrue(started.await(10, TimeUnit.SECONDS));
      Thread.currentThread().interrupt();
      return null;
    }).when(cache).flush();
    try {
      var thrown = assertThrows(WriteCacheException.class, cache::close);
      assertTrue(thrown.getMessage().contains("close was interrupted"));
      assertTrue(thrown.getCause() instanceof InterruptedException);
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(handle.isOpen());
      org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).close();
      org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).close();
      assertDwlBytes(before);
    } finally {
      Thread.interrupted();
      release.countDown();
      if (background.get() != null) {
        background.get().get(10, TimeUnit.SECONDS);
      }
      flushExecutor().submit(() -> {
      }).get(10, TimeUnit.SECONDS);
      cache = original;
    }
    assertTrue(handle.isOpen());
    assertDwlBytes(before);
  }

  /** An uncertain existence result must not discard the only complete copy of a torn page. */
  @Test
  public void uncertainAbsenceKeepsRetentionAndPhysicalCopy() throws Exception {
    installLiveLog(512);
    long file = cache.addFile("uncertain-absence.tst");
    var handle = tearWrites(file);
    writePattern(file, 0, (byte) 41);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    var before = dwlBytes();
    org.mockito.Mockito.doReturn(false).when(handle).exists();
    try {
      assertThrows(WriteCacheException.class, () -> cache.deleteFile(file));
      assertTrue(Files.exists(cache.getRootDirectory().resolve(handle.getName())));
      assertTrue(retention().hasRetainedContents(file));
      rotationSync();
      assertDwlBytes(before);
    } finally {
      // Deletion removes the container entry even when physical deletion fails.
      var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
      if (handles.get(file) == null) {
        handles.add(file, handle);
      }
      org.mockito.Mockito.doCallRealMethod().when(handle).exists();
    }
  }

  /** Failed replacement append cannot publish a new identity or submit a data write. */
  @Test
  public void failedReplacementAppendKeepsOldCopyAndPublicationPrecedesSubmission()
      throws Exception {
    var log = installLiveLog(512);
    long file = cache.addFile("replacement-append.tst");
    var handle = tearWrites(file);
    writePattern(file, 0, (byte) 51);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    var before = dwlBytes();
    var segments = flushExecutor().submit(() -> retention().retainedSegments())
        .get(10, TimeUnit.SECONDS);
    org.mockito.Mockito.clearInvocations(handle);
    org.mockito.Mockito.doThrow(new IOException("replacement append failed")).when(log).write(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).write(
        org.mockito.ArgumentMatchers.<List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<
                java.nio.ByteBuffer>>>any());
    assertEquals(segments, flushExecutor().submit(() -> retention().retainedSegments())
        .get(10, TimeUnit.SECONDS));
    org.mockito.Mockito.doCallRealMethod().when(log).write(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
    long healthy = cache.addFile("replacement-healthy.tst");
    writePattern(healthy, 0, (byte) 9);
    cache.flush(healthy);
    rotationSync();
    for (var entry : before.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }
    doAnswer(call -> {
      var current = retention().retainedSegments();
      assertEquals(1, current.size());
      assertFalse("replacement must be published before submission", current.equals(segments));
      return call.callRealMethod();
    }).when(handle).write(
        org.mockito.ArgumentMatchers.<List<
            com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<
                java.nio.ByteBuffer>>>any());
    cache.flush(file);
  }

  /** Checkpoint forces cannot acknowledge pending retention, even after real log rotation. */
  @Test
  public void checkpointForceDoesNotReleasePendingCopy() throws Exception {
    var log = installLiveLog(1_000_000);
    setField(cache, "callFsync", true);
    long file = cache.addFile("checkpoint-pending.tst");
    writePattern(file, 0, (byte) 61);
    liveBatch();
    var before = dwlBytes();
    assertTrue(retention().hasRetainedContents(file));
    cache.syncDataFiles();
    // Rotate without another data flush, then run cleanup without a covering executor force.
    log.startCheckpoint();
    log.endCheckpoint();
    flushExecutor().submit(() -> {
      log.truncate(retention().retainedSegments());
      return null;
    }).get(10, TimeUnit.SECONDS);
    assertTrue(retention().hasRetainedContents(file));
    for (var entry : before.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }
    rotationSync();
    assertFalse(retention().hasRetainedContents(file));
    for (var path : before.keySet()) {
      assertFalse(Files.exists(path));
    }
  }

  /** Both distinguishable pages in an uncertain batch need protection after one is removed. */
  @Test
  public void uncertainBatchKeepsEveryPageAfterPartialRemovalAndCleanup() throws Exception {
    var log = installLiveLog(512);
    long file = cache.addFile("uncertain-batch.tst");
    tearWrites(file);
    writePattern(file, 0, (byte) 81);
    writePattern(file, 1, (byte) 82);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    flushExecutor().submit(() -> {
      var files =
          (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) field(retention(), "files");
      var pages = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>) files.get(file);
      assertEquals(2, pages.size());
      for (var attempt : pages.values()) {
        assertEquals("UNRESOLVED", field(attempt, "state").toString());
      }
      return null;
    }).get(10, TimeUnit.SECONDS);
    var before = dwlBytes();
    assertTrue(cache.shrinkFile(file, pageSize));
    long healthy = cache.addFile("uncertain-batch-healthy.tst");
    writePattern(healthy, 0, (byte) 8);
    cache.flush(healthy);
    rotationSync();
    assertTrue(flushExecutor().submit(retention()::hasUnresolvedPages)
        .get(10, TimeUnit.SECONDS));
    for (var entry : before.entrySet()) {
      assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
    }
    log.restoreModeOn();
    var complete = log.loadPage(cache.internalFileId(file), 0, pool);
    try {
      assertEquals(81, complete.getNativeByteBuffer().get(pageSize - 1));
    } finally {
      pool.release(complete);
      log.restoreModeOff();
    }
  }

  /** An interrupt after physical shrink must still return success and finish retention discard. */
  @Test
  public void interruptedShrinkFinishesDiscardAndRestoresInterrupt() throws Exception {
    installLiveLog(512);
    long file = cache.addFile("interrupted-shrink.tst");
    var handle = tearWrites(file);
    writePattern(file, 0, (byte) 91);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    doAnswer(call -> {
      var result = call.callRealMethod();
      Thread.currentThread().interrupt();
      return result;
    }).when(handle).shrink(0);
    try {
      assertTrue(cache.shrinkFile(file, 0));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertFalse(retention().hasRetainedContents(file));
  }

  /** A new file id cannot have retention and must not wait for a busy flush executor. */
  @Test
  public void freshFileCreationDoesNotWaitForFlushExecutor() throws Exception {
    installLiveLog(512);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blocker = flushExecutor().submit(() -> {
      entered.countDown();
      assertTrue(release.await(10, TimeUnit.SECONDS));
      return null;
    });
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      io.submit(() -> cache.addFile("fresh-no-wait.tst")).get(5, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      blocker.get(10, TimeUnit.SECONDS);
    }
  }

  /** Torn bytes must keep their actual DWL segment while unrelated segments can be reclaimed. */
  @Test
  public void tornWriteRetainsOnlyRequiredCopyAndRetryEventuallyReclaimsIt() throws Exception {
    var log = installLiveLog(512);
    long damaged = cache.addFile("torn.tst");
    long healthy = cache.addFile("healthy.tst");
    var handle = tearWrites(damaged);
    writePattern(damaged, 0, (byte) 71);
    assertThrows(WriteCacheException.class, () -> cache.flush(damaged));
    var protectedFiles = dwlBytes();
    assertEquals(1, protectedFiles.size());
    var torn = java.nio.ByteBuffer.allocate(pageSize);
    handle.read(0, torn, true);
    assertEquals(71, torn.get(100));
    assertEquals(0, torn.get(pageSize - 1));
    log.restoreModeOn();
    var complete = log.loadPage(cache.internalFileId(damaged), 0, pool);
    try {
      assertEquals(71, complete.getNativeByteBuffer().get(pageSize - 1));
    } finally {
      pool.release(complete);
      log.restoreModeOff();
    }
    var state = retention();
    assertTrue(flushExecutor().submit(state::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    for (int i = 0; i < 3; i++) {
      writePattern(healthy, 0, (byte) (i + 1));
      cache.flush(healthy);
      for (var entry : protectedFiles.entrySet()) {
        assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
      }
      assertTrue("unrelated tails must not accumulate", dwlBytes().size() <= 2);
    }
    org.mockito.Mockito.doCallRealMethod().when(handle).write(
        org.mockito.ArgumentMatchers.<
            List<com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<
                java.nio.ByteBuffer>>>any());
    cache.flush(damaged);
    assertFalse(flushExecutor().submit(state::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    writePattern(healthy, 0, (byte) 90);
    cache.flush(healthy);
    for (var path : protectedFiles.keySet()) {
      assertFalse(Files.exists(path));
    }
    org.mockito.Mockito.verify(log, org.mockito.Mockito.atLeastOnce())
        .truncate(org.mockito.ArgumentMatchers.anySet());
  }

  /** A failed force makes a successful real write unresolved until a new append and rewrite. */
  @Test
  public void failedForceCannotBeResolvedByLaterForceAlone() throws Exception {
    installLiveLog(512);
    long file = cache.addFile("force.tst");
    var handle = observeDataFile(file);
    setField(cache, "callFsync", true);
    writePattern(file, 0, (byte) 11);
    // Drain asynchronous blank-page validation before stubbing the same file handle.
    flushExecutor().submit(() -> {
    }).get(10, TimeUnit.SECONDS);
    var failure = new StorageException("marks", "injected force");
    org.mockito.Mockito.doThrow(failure).when(handle).synch();
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    var before = dwlBytes();
    org.mockito.Mockito.doCallRealMethod().when(handle).synch();
    cache.flush(file);
    assertDwlBytes(before);
    assertTrue(flushExecutor().submit(retention()::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    writePattern(file, 0, (byte) 12);
    cache.flush(file);
    writePattern(file, 1, (byte) 13);
    cache.flush(file);
    assertFalse(flushExecutor().submit(retention()::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    for (var path : before.keySet()) {
      assertFalse(path + " remains in " + dwlBytes().keySet(), Files.exists(path));
    }
  }

  /** Fsync-off releases successful retries directly. Non-durable writes never enter the DWL. */
  @Test
  public void realLogExcludesNonDurableAndReleasesFsyncOffWrites() throws Exception {
    var log = installLiveLog(512);
    long durable = cache.addFile("durable.tst");
    writePattern(durable, 0, (byte) 1);
    cache.flush(durable);
    assertTrue(flushExecutor().submit(() -> retention().retainedSegments().isEmpty()).get(10,
        TimeUnit.SECONDS));
    long temporary = cache.addFile("temporary.tst", cache.bookFileId("temporary.tst"), true);
    var before = dwlBytes();
    org.mockito.Mockito.clearInvocations(log);
    writePattern(temporary, 0, (byte) 2);
    cache.flush(temporary);
    org.mockito.Mockito.verify(log, org.mockito.Mockito.never()).write(
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
        org.mockito.ArgumentMatchers.any());
    assertDwlBytes(before);
  }

  /** Delete and shrink release only physically superseded pages. Failures keep the original copy. */
  @Test
  public void lifecycleFailuresKeepCopiesAndSuccessfulSupersessionDiscardsOnlyRemovedPages()
      throws Exception {
    installLiveLog(512);
    long file = cache.addFile("lifecycle.tst");
    var handle = tearWrites(file);
    writePattern(file, 0, (byte) 1);
    writePattern(file, 1, (byte) 2);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    var before = dwlBytes();
    org.mockito.Mockito.doThrow(new IOException("injected shrink")).when(handle).shrink(pageSize);
    assertThrows(IOException.class, () -> cache.shrinkFile(file, pageSize));
    assertDwlBytes(before);
    assertTrue(flushExecutor().submit(retention()::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    org.mockito.Mockito.doCallRealMethod().when(handle).shrink(pageSize);
    assertTrue(cache.shrinkFile(file, pageSize));
    // The lower page still needs the old copy.
    assertTrue(flushExecutor().submit(retention()::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    org.mockito.Mockito.doThrow(new IOException("injected delete")).when(handle).delete();
    assertThrows(WriteCacheException.class, () -> cache.deleteFile(file));
    assertDwlBytes(before);
    assertTrue(flushExecutor().submit(retention()::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    // executeDeleteFile removes the handle before physical deletion. Restore it for the retry.
    var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
    handles.add(file, handle);
    org.mockito.Mockito.doCallRealMethod().when(handle).delete();
    cache.deleteFile(file);
    assertTrue(flushExecutor().submit(() -> retention().retainedSegments().isEmpty()).get(10,
        TimeUnit.SECONDS));
    long replacement = cache.addFile("lifecycle.tst");
    assertEquals(file, replacement);
    writePattern(replacement, 0, (byte) 3);
    cache.flush(replacement);
    assertFalse(flushExecutor().submit(retention()::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
  }

  /** A torn foreground failure rejects close even if its locked page makes the final flush skip. */
  @Test
  public void liveTornFailureRejectsCloseAndKeepsDwlBytes() throws Exception {
    installLiveLog(512);
    long file = cache.addFile("close-live.tst");
    tearWrites(file);
    writePattern(file, 0, (byte) 7);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    var before = dwlBytes();
    var entry = readCache.loadForRead(file, 0, cache, true);
    var pointer = entry.getCachePointer();
    long stamp = pointer.acquireExclusiveLock();
    try {
      assertThrows(WriteCacheException.class, cache::close);
      assertDwlBytes(before);
    } finally {
      pointer.releaseExclusiveLock(stamp);
      readCache.releaseFromRead(entry);
    }
  }

  /** Stored background failures reject close and keep real copies of the torn page. */
  @Test
  public void liveBackgroundFailureRejectsCloseAndPreservesDwl() throws Exception {
    installLiveLog(512);
    long file = cache.addFile("background-live.tst");
    tearWrites(file);
    writePattern(file, 0, (byte) 8);
    flushExecutor().submit(() -> cache.executePeriodicFlush(new PeriodicFlushTask(cache))).get(10,
        TimeUnit.SECONDS);
    assertTrue(field(cache, "flushError") != null);
    var before = dwlBytes();
    assertFalse(before.isEmpty());
    assertThrows(WriteCacheException.class, cache::close);
    assertDwlBytes(before);
  }

  /** Keeping an old segment does not request another force when no new rotation occurs. */
  @Test
  public void retainedTailDoesNotForceEveryDurableBatch() throws Exception {
    var log = installLiveLog(1_000_000);
    long file = cache.addFile("rotation-live.tst");
    var handle = tearWrites(file);
    writePattern(file, 0, (byte) 1);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    log.startCheckpoint();
    log.endCheckpoint();
    // Keep the unresolved page locked so unrelated background batches cannot retry it.
    var damagedEntry = readCache.loadForRead(file, 0, cache, true);
    var damagedPointer = damagedEntry.getCachePointer();
    long stamp = damagedPointer.acquireExclusiveLock();
    long healthy = cache.addFile("rotation-healthy.tst");
    try {
      setField(cache, "callFsync", true);
      writePattern(healthy, 0, (byte) 2);
      liveBatch();
      org.mockito.Mockito.clearInvocations(handle);
      writePattern(healthy, 0, (byte) 3);
      liveBatch();
      org.mockito.Mockito.verify(handle, org.mockito.Mockito.never()).synch();
      assertTrue(flushExecutor().submit(retention()::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    } finally {
      damagedPointer.releaseExclusiveLock(stamp);
      readCache.releaseFromRead(damagedEntry);
    }
  }

  /** A removed container entry is not covered by a force and cannot release a pending record. */
  @Test
  public void missingFileIsNotAcknowledgedByRotationOrCloseForce() throws Exception {
    installLiveLog(1_000_000);
    long file = cache.addFile("missing-live.tst");
    setField(cache, "callFsync", true);
    writePattern(file, 0, (byte) 1);
    liveBatch();
    var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
    var handle = handles.remove(file);
    var before = dwlBytes();
    try {
      rotationSync();
      assertTrue(
          flushExecutor().submit(retention()::hasPendingSynchronization).get(10, TimeUnit.SECONDS));
      assertThrows(WriteCacheException.class, cache::close);
      assertDwlBytes(before);
      assertTrue(
          flushExecutor().submit(retention()::hasPendingSynchronization).get(10, TimeUnit.SECONDS));
    } finally {
      handles.add(file, handle);
    }
  }

  /** Shrink to zero supersedes a torn page and permits cleanup before a new page incarnation. */
  @Test
  public void successfulShrinkReleasesTornPageAndReplacementCanBeWritten() throws Exception {
    installLiveLog(512);
    long file = cache.addFile("shrink-live.tst");
    var handle = tearWrites(file);
    writePattern(file, 0, (byte) 1);
    assertThrows(WriteCacheException.class, () -> cache.flush(file));
    var before = dwlBytes();
    assertTrue(cache.shrinkFile(file, 0));
    assertTrue(flushExecutor().submit(() -> retention().retainedSegments().isEmpty()).get(10,
        TimeUnit.SECONDS));
    org.mockito.Mockito.doCallRealMethod().when(handle).write(
        org.mockito.ArgumentMatchers.<
            List<com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<
                java.nio.ByteBuffer>>>any());
    writePattern(file, 0, (byte) 2);
    cache.flush(file);
    for (var path : before.keySet()) {
      assertFalse(Files.exists(path));
    }
  }

  /** The fixed limit forces once, releases the covered set and leaves no pending accumulation. */
  @Test
  public void pendingLimitRunsOneForceAndReleasesCoveredRecords() throws Exception {
    assertPendingLimit(false);
  }

  /** Restore mode suppresses cleanup but still allows the memory limit to release forced writes. */
  @Test
  public void restoreModeLimitReleasesCoveredRecordsWithoutCleanup() throws Exception {
    assertPendingLimit(true);
  }

  private void assertPendingLimit(boolean restoreMode) throws Exception {
    var log = installLiveLog(1_000_000_000);
    if (restoreMode) {
      log.restoreModeOn();
    }
    long file = cache.addFile("limit-live.tst");
    setField(cache, "callFsync", true);
    var handle = observeDataFile(file);
    writePattern(file, 0, (byte) 1);
    liveBatch();
    var state = retention();
    // Seed the boundary with identities of the real appended copy to avoid a 256 MiB fixture.
    flushExecutor().submit(() -> {
      long segment = state.retainedSegments().iterator().next();
      for (int page = 2; page < PageRetentionState.SYNCHRONIZATION_LIMIT; page++) {
        var pointer = new com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer(
            (com.jetbrains.youtrackdb.internal.common.directmemory.Pointer) null, null, file, page);
        state.writeSucceeded(state.appendSucceeded(pointer, segment, true), true);
      }
    }).get(10, TimeUnit.SECONDS);
    org.mockito.Mockito.clearInvocations(handle);
    writePattern(file, 1, (byte) 2);
    liveBatch();
    org.mockito.Mockito.verify(handle).synch();
    assertFalse(flushExecutor().submit(state::hasPendingSynchronization).get(10, TimeUnit.SECONDS));
    assertTrue(
        flushExecutor().submit(() -> state.retainedSegments().isEmpty()).get(10, TimeUnit.SECONDS));
    writePattern(file, 0, (byte) 4);
    liveBatch();
    org.mockito.Mockito.verify(handle).synch();
    if (restoreMode) {
      log.restoreModeOff();
    }
  }

  private void liveBatch() throws Exception {
    var future = flushExecutor().submit(
        () -> cache.executePeriodicFlush(new PeriodicFlushTask(cache)));
    try {
      future.get(10, TimeUnit.SECONDS);
    } finally {
      // A timed-out flush must not leave uncancelled fixture work on the shared executor.
      future.cancel(true);
    }
    if (field(cache, "flushError") != null) {
      throw new AssertionError((Throwable) field(cache, "flushError"));
    }
  }

  private DoubleWriteLogGL installLiveLog(long segmentSize) throws Exception {
    cache.close();
    var log = spy(new DoubleWriteLogGL(segmentSize, 512, false));
    setField(cache, "doubleWriteLog", log);
    cache.loadRegisteredFiles();
    setField(cache, "deferDwlCleanup", false);
    setField(cache, "stopFlush", false);
    return log;
  }

  private void writePattern(long file, int page, byte value) throws Exception {
    var pointer = cache.loadOrAdd(file, page, false);
    long stamp = pointer.acquireExclusiveLock();
    try {
      var buffer = pointer.getBuffer();
      for (int offset = 64; offset < pageSize; offset++) {
        buffer.put(offset, value);
      }
      cache.updateDirtyPagesTable(pointer, new LogSequenceNumber(0, 0));
    } finally {
      pointer.releaseExclusiveLock(stamp);
    }
    cache.store(file, page, pointer);
    pointer.decrementReadersReferrer();
  }

  private File tearWrites(long file) throws Exception {
    var handle = observeDataFile(file);
    doAnswer(call -> {
      List<com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<
          java.nio.ByteBuffer>> buffers = call.getArgument(0);
      var first = buffers.getFirst();
      var half = first.second.duplicate();
      half.rewind();
      half.limit(pageSize / 2);
      handle.write(first.first, half);
      throw new IOException("injected torn write");
    }).when(handle).write(
        org.mockito.ArgumentMatchers.<
            List<com.jetbrains.youtrackdb.internal.common.util.RawPairLongObject<
                java.nio.ByteBuffer>>>any());
    return handle;
  }

  private File observeDataFile(long file) throws Exception {
    var handles = (ClosableLinkedContainer<Long, File>) field(cache, "files");
    var handle = spy(handles.get(file));
    handles.remove(file);
    handles.add(file, handle);
    return handle;
  }

  private void rotationSync() throws Exception {
    flushExecutor().submit(() -> {
      var sync = WOWCache.class.getDeclaredMethod("fsyncFiles", long.class);
      sync.setAccessible(true);
      sync.invoke(cache, Long.MAX_VALUE);
      return null;
    }).get(10, TimeUnit.SECONDS);
  }

  /** The real retention owner rejects callers and unrelated executors. */
  @Test
  public void retentionOwnerIsOnlyTheGlobalFlushWorker() throws Exception {
    var state = retention();
    assertThrows(IllegalStateException.class, state::hasUnresolvedPages);
    assertFalse(flushExecutor().submit(state::hasUnresolvedPages).get(10, TimeUnit.SECONDS));
    io.submit(() -> assertThrows(IllegalStateException.class, state::hasUnresolvedPages))
        .get(10, TimeUnit.SECONDS);
  }

  private java.util.concurrent.ScheduledExecutorService flushExecutor() {
    return YouTrackDBEnginesManager.instance().getWowCacheFlushExecutor();
  }

  private PageRetentionState retention() throws Exception {
    return (PageRetentionState) field(cache, "pageRetentionState");
  }

  private void seedRetention(long file, int page, boolean failed, boolean successful)
      throws Exception {
    var pointer = org.mockito.Mockito.mock(
        com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer.class);
    org.mockito.Mockito.when(pointer.getFileId()).thenReturn(file);
    org.mockito.Mockito.when(pointer.getPageIndex()).thenReturn(page);
    var state = retention();
    flushExecutor().submit(() -> {
      var attempt = state.appendSucceeded(pointer, 0L, true);
      if (failed) {
        state.writesFailed(List.of(attempt));
      } else if (successful) {
        state.writeSucceeded(attempt, true);
      }
    }).get(10, TimeUnit.SECONDS);
  }

  private DoubleWriteLogGL installCloseLog() throws Exception {
    cache.close();
    var path = cache.getRootDirectory();
    Files.write(path.resolve(ContextConfiguration.DOUBLE_WRITE_LOG_DEFAULT_NAME + "_0.dwl"),
        new byte[] {3, 1, 4});
    var log = spy(new DoubleWriteLogGL(512, 512, false));
    setField(cache, "doubleWriteLog", log);
    cache.loadRegisteredFiles();
    // Reusing the fixture starts a new close attempt with ordinary cleanup enabled.
    setField(cache, "deferDwlCleanup", false);
    setField(cache, "stopFlush", false);
    for (int page = 0; page < 3; page++) {
      log.write(new ArrayList<>(List.of(java.nio.ByteBuffer.allocate(pageSize))),
          it.unimi.dsi.fastutil.ints.IntArrayList.of(1),
          it.unimi.dsi.fastutil.ints.IntArrayList.of(page));
    }
    return log;
  }

  private Map<Path, byte[]> dwlBytes() throws IOException {
    var result = new java.util.HashMap<Path, byte[]>();
    try (var paths = Files.list(cache.getRootDirectory())) {
      for (var path : paths.filter(p -> p.toString().endsWith(".dwl")).toList()) {
        result.put(path, Files.readAllBytes(path));
      }
    }
    return result;
  }

  private void assertDwlBytes(Map<Path, byte[]> expected) throws IOException {
    var actual = dwlBytes();
    assertEquals(expected.keySet(), actual.keySet());
    for (var path : expected.keySet()) {
      assertArrayEquals(expected.get(path), actual.get(path));
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
                assertTrue(!Files.exists(authority) || Files.size(authority) == 0);
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
