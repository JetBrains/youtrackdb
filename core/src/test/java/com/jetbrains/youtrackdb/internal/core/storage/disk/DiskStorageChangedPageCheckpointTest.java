package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.INVALIDATED;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.NO_SAVE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.PREFLIGHT_UNAVAILABLE;
import static com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointOutcome.SAVED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.engine.Engine;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTracker.CheckpointResult;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.ChangedPageTrackerFile;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.CutPreflight;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Future;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Direct storage bridge contracts, independent of ordinary checkpoint caller routing. */
public class DiskStorageChangedPageCheckpointTest {

  private static final LogSequenceNumber COVERAGE = new LogSequenceNumber(2, 32);

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  private YouTrackDBImpl manager;
  private DatabaseSessionEmbedded session;
  private DiskStorage storage;
  private ScalableRWLock stateLock;
  private WriteAheadLog realWal;
  private WriteAheadLog wal;
  private ChangedPageTracker tracker;
  private Path sideFile;

  @Before
  public void openStorage() throws Exception {
    var config = new BaseConfiguration();
    // Keep periodic work away from the scoped WAL replacement used by these direct bridge tests.
    config.setProperty(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(), false);
    config.setProperty(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL.getKey(), 3600);
    config.setProperty(GlobalConfiguration.STORAGE_COLLECTION_GC_PAUSE_INTERVAL.getKey(), 3600);
    manager = (YouTrackDBImpl) YourTracks.instance(folder.getRoot().toString(), config);
    manager.create("bridge", DatabaseType.DISK, "admin", "admin", "admin");
    session = manager.open("bridge", "admin", "admin");
    storage = (DiskStorage) session.getStorage();
    sideFile = folder.getRoot().toPath().resolve("bridge")
        .resolve(ChangedPageTrackerFile.FILE_NAME);
    // Creation can publish tracker coverage. Normalize only its file, not the shared tracker.
    Files.deleteIfExists(sideFile);
    stateLock = (ScalableRWLock) field(AbstractStorage.class, storage, "stateLock");
    tracker = (ChangedPageTracker) field(DiskStorage.class, storage, "changedPageTracker");
    realWal = storage.getWALInstance();
    wal = mock(WriteAheadLog.class);
    when(wal.preflightCut(3)).thenReturn(new CutPreflight(true, 2, COVERAGE));
    when(wal.cutAllSegmentsSmallerThan(2)).thenReturn(true);
    replaceWal(wal);
  }

  @After
  public void closeStorage() throws Exception {
    // Clear only test cancellation before normal lifecycle teardown forces real storage channels.
    Thread.interrupted();
    if (storage != null && realWal != null) {
      replaceWal(realWal);
    }
    try {
      if (session != null) {
        session.close();
      }
    } finally {
      if (manager != null) {
        manager.close();
      }
    }
  }

  /** Real disk creation uses only the create initializer and shares one untrusted tracker. */
  @Test
  public void creationSelectsCreateInitializerAndSharesTracker() throws Exception {
    var created = createObservedStorage();
    verify(created).initWalAndDiskCache(any(ContextConfiguration.class));
    verify(created, never()).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
    assertSharedUntrustedTracker(created);
  }

  /** An existing image uses only the open initializer and shares a fresh untrusted tracker. */
  @Test
  public void existingImageSelectsOpenInitializerAndSharesTracker() throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    clearInvocations(created);
    created.open(new ContextConfiguration());
    verify(created).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
    verify(created, never()).initWalAndDiskCache(any(ContextConfiguration.class));
    assertSharedUntrustedTracker(created);
  }

  /** Every physical reopen replaces both tracker references together, without trusting history. */
  @Test
  public void repeatedPhysicalOpenReplacesSharedTracker() throws Exception {
    var created = createObservedStorage();
    var previous = assertSharedUntrustedTracker(created);
    for (var attempt = 0; attempt < 2; attempt++) {
      created.close(null, true);
      clearInvocations(created);
      created.open(new ContextConfiguration());
      verify(created).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
      verify(created, never()).initWalAndDiskCache(any(ContextConfiguration.class));
      var current = assertSharedUntrustedTracker(created);
      assertNotSame(previous, current);
      previous = current;
    }
  }

  /** A real create failure at the cleanup barrier cannot write dirty.fl/configuration or OPEN. */
  @Test
  public void creationFailurePreventsDirtyFlagConfigurationAndOpenPublication() throws Exception {
    var directory = folder.getRoot().toPath().resolve("failedCreation");
    var created = mock(DiskStorage.class, withSettings().useConstructor("failedCreation",
        directory.toString(), 4321, storage.getReadCache(),
        field(DiskStorage.class, storage, "files"),
        field(DiskStorage.class, storage, "walMaxSegSize"),
        field(DiskStorage.class, storage, "doubleWriteLogMaxSegSize"), manager.internal)
        .defaultAnswer(CALLS_REAL_METHODS));
    var metadata = mock(StorageStartupMetadata.class);
    var metadataField = DiskStorage.class.getDeclaredField("startupMetadata");
    metadataField.setAccessible(true);
    metadataField.set(created, metadata);
    var failure = new IOException("injected cleanup folder force failure");
    try (var cleanup = mockStatic(ChangedPageTrackerFile.class,
        call -> call.getMethod().getName().equals("cleanupForCreation")
            && call.getArguments().length == 1
                ? null : call.callRealMethod())) {
      cleanup.when(() -> ChangedPageTrackerFile.cleanupForCreation(directory)).thenAnswer(call -> {
        // Observe before any factory deletion can hide ordering. Birth, IV, WAL and cache already
        // exist, but startup metadata and storage configuration must still be untouched.
        assertNotNull(created.getWALInstance());
        assertNotNull(created.getWriteCache());
        assertTrue(Files.exists(directory.resolve("data.iv")));
        assertTrue(DiskStorage.exists(directory));
        verifyNoInteractions(metadata);
        assertNull(field(AbstractStorage.class, created, "configuration"));
        throw failure;
      });
      assertThrows(RuntimeException.class, () -> created.create(new ContextConfiguration()));
      cleanup.verify(() -> ChangedPageTrackerFile.cleanupForCreation(directory));
      verifyNoInteractions(metadata);
      verify(created, never()).makeStorageDirty();
      assertTrue(created.isClosed(null));
      assertNull(field(AbstractStorage.class, created, "configuration"));
      assertFalse(Files.exists(directory.resolve("dirty.fl")));
      assertFalse(Files.exists(directory.resolve("database.ocf")));
      try (var paths = Files.list(directory)) {
        assertFalse(paths.anyMatch(path -> path.getFileName().toString().startsWith("config")));
      }
    } finally {
      // CLOSED lifecycle shutdown returns early. Release the initialized resources explicitly.
      created.preCloseSteps();
      try {
        if (created.getWriteCache() != null) {
          created.getReadCache().closeStorage(created.getWriteCache());
        }
      } finally {
        if (created.getWALInstance() != null) {
          created.getWALInstance().close();
        }
      }
    }
  }

  /** A failed cache close keeps the metadata lock and primary Error, but still closes the WAL. */
  @Test
  public void openErrorSurvivesReadCacheCloseErrorAndRunsLaterCleanup() throws Exception {
    assertOpenFailure(new Error("open failure"), 0, new Error("read-cache close failure"), null);
  }

  /** A WAL close Error stays suppressed and cannot prevent the later metadata close. */
  @Test
  public void openErrorSurvivesWalCloseErrorAndRunsMetadataCleanup() throws Exception {
    assertOpenFailure(new Error("open failure"), 1, new Error("WAL close failure"), null);
  }

  /** A post-close Error stays suppressed and storage always reaches CLOSED. */
  @Test
  public void openErrorSurvivesPostCloseErrorAndReachesClosed() throws Exception {
    assertOpenFailure(new Error("open failure"), 2, new Error("post-close failure"), null);
  }

  /** Diagnostics cannot replace an Error after the real disk open cleanup completes. */
  @Test
  public void openErrorSurvivesDiagnosticError() throws Exception {
    assertOpenFailure(new Error("open failure"), -1, null, new Error("diagnostic failure"));
  }

  /** An Error after MIGRATION must reset storage to CLOSED and retain the original failure. */
  @Test
  public void openErrorDuringMigrationResetsStatusToClosed() throws Exception {
    assertOpenFailure(new Error("migration failure"), -1, null, null, true);
  }

  /** Runtime open failures retain their identity when diagnostic reporting throws an Error. */
  @Test
  public void runtimeOpenFailureSurvivesDiagnosticError() throws Exception {
    assertOpenFailure(new IllegalStateException("open failure"), -1, null,
        new Error("diagnostic failure"));
  }

  /** Checked open failures remain the exact cause of a runtime wrapper if diagnostics fail. */
  @Test
  public void checkedOpenFailureSurvivesDiagnosticErrorAsExactCause() throws Exception {
    assertOpenFailure(new IOException("open failure"), -1, null, new Error("diagnostic failure"));
  }

  /** Reusing the primary in cleanup and diagnostics must not attempt self-suppression. */
  @Test
  public void openFailureSurvivesSameFailureFromCleanupAndDiagnostics() throws Exception {
    var failure = new Error("shared failure");
    assertOpenFailure(failure, 0, failure, failure);
  }

  /** Rejected metadata admission must not create an IV or touch earlier cache and WAL fields. */
  @Test
  public void rejectedMetadataLeavesFilesAndEarlierResourcesUntouched() throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var oldCache = mock(com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache.class);
    var oldWal = mock(WriteAheadLog.class);
    setField(created, "writeCache", oldCache);
    setField(created, "writeAheadLog", oldWal);
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    Files.delete(directory.resolve("data.iv"));
    var before = new java.util.HashMap<Path, byte[]>();
    doAnswer(call -> {
      call.callRealMethod();
      // Identity admission can publish bootstrap authority before metadata admission.
      // Snapshot after that unchanged step to isolate IV, WAL, cache and tracker effects.
      try (var paths = Files.list(directory)) {
        for (var path : paths.toList()) {
          if (!path.getFileName().toString().startsWith("dirty.fl")) {
            before.put(path, Files.readAllBytes(path));
          }
        }
      }
      return null;
    }).when(created).readStorageIdentity();
    try (var channel = FileChannel.open(directory.resolve("dirty.fl"), StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      assertThrows(RuntimeException.class, () -> created.open(new ContextConfiguration()));
      assertFalse(Files.exists(directory.resolve("data.iv")));
      verifyNoInteractions(oldCache, oldWal);
      verify(created, never()).postCloseSteps(eq(false), eq(true), anyLong());
      var after = new java.util.HashSet<Path>();
      try (var paths = Files.list(directory)) {
        paths.filter(path -> !path.getFileName().toString().startsWith("dirty.fl"))
            .forEach(after::add);
      }
      assertEquals(before.keySet(), after);
      for (var entry : before.entrySet()) {
        org.junit.Assert.assertArrayEquals(entry.getValue(), Files.readAllBytes(entry.getKey()));
      }
      assertTrue(created.isClosed(null));
    }
  }

  /** A registry-load failure must close the constructed cache before releasing metadata. */
  @Test
  public void registryLoadFailureClosesUnpublishedCacheBeforeMetadata() throws Exception {
    assertRegistryLoadFailure(false);
  }

  /** Failed partial-cache close must retain metadata while the WAL still closes. */
  @Test
  public void registryLoadFailureRetainsMetadataWhenPartialCacheCloseFails() throws Exception {
    assertRegistryLoadFailure(true);
  }

  private void assertRegistryLoadFailure(boolean failClose) throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var directory = (Path) field(DiskStorage.class, created, "storagePath");
    var registry = directory.resolve("name_id_map_v3.cm");
    Files.delete(registry);
    Files.createDirectory(registry);
    var steps = new ArrayList<String>();
    var earlierCache = created.getWriteCache();
    var actualReadCache = created.getReadCache();
    var partialCache = new WOWCache[1];
    var closeFailure = new Error("partial cache close failure");
    var observed = mock(ReadCache.class, delegatesTo(actualReadCache));
    doAnswer(call -> {
      steps.add("cache");
      var cache = (WOWCache) call.getArgument(0);
      assertNotSame(earlierCache, cache);
      partialCache[0] = cache;
      if (failClose) {
        throw closeFailure;
      }
      actualReadCache.closeStorage(cache);
      assertTrue((boolean) field(WOWCache.class, cache, "stopFlush"));
      return null;
    }).when(observed).closeStorage(any());
    setField(created, "readCache", observed);
    doAnswer(call -> {
      steps.add("metadata");
      return call.callRealMethod();
    }).when(created).postCloseSteps(eq(false), eq(true), anyLong());
    try {
      var failure = assertThrows(RuntimeException.class,
          () -> created.open(new ContextConfiguration()));
      assertEquals(failClose ? List.of("cache") : List.of("cache", "metadata"), steps);
      if (failClose) {
        assertTrue(Arrays.asList(failure.getCause().getSuppressed()).contains(closeFailure));
        try (var channel = FileChannel.open(directory.resolve("dirty.fl"),
            StandardOpenOption.WRITE)) {
          assertThrows(OverlappingFileLockException.class, channel::tryLock);
        }
      }
      assertTrue(created.isClosed(null));
    } finally {
      created.preCloseSteps();
      if (partialCache[0] != null) {
        actualReadCache.closeStorage(partialCache[0]);
      }
      ((StorageStartupMetadata) field(DiskStorage.class, created, "startupMetadata")).close();
      Files.delete(registry);
    }
  }

  private void assertOpenFailure(Throwable primary, int failedStep, Error cleanupFailure,
      Error diagnosticFailure) throws Exception {
    assertOpenFailure(primary, failedStep, cleanupFailure, diagnosticFailure, false);
  }

  private void assertOpenFailure(Throwable primary, int failedStep, Error cleanupFailure,
      Error diagnosticFailure, boolean failDuringMigration) throws Exception {
    var created = createObservedStorage();
    created.close(null, true);
    var steps = new ArrayList<String>();
    var closed = new boolean[3];
    var actualReadCache = created.getReadCache();
    var actualWal = new WriteAheadLog[1];
    var reachedMigration = new boolean[1];
    // Wrap only resources acquired by this attempt, after real WAL and cache initialization.
    doAnswer(call -> {
      call.callRealMethod();
      actualWal[0] = created.getWALInstance();
      var observedReadCache = mock(ReadCache.class, delegatesTo(actualReadCache));
      doAnswer(close -> {
        steps.add("read-cache");
        if (failedStep == 0) {
          // Leave the real writers alive to prove that releasing the metadata lock is unsafe.
          throw cleanupFailure;
        }
        actualReadCache.closeStorage(created.getWriteCache());
        closed[0] = true;
        return null;
      }).when(observedReadCache).closeStorage(any());
      var observedWal = mock(WriteAheadLog.class, delegatesTo(actualWal[0]));
      doAnswer(close -> {
        steps.add("WAL");
        actualWal[0].close();
        closed[1] = true;
        if (failedStep == 1) {
          throw cleanupFailure;
        }
        return null;
      }).when(observedWal).close();
      setField(created, "readCache", observedReadCache);
      setField(created, "writeAheadLog", observedWal);
      if (!failDuringMigration) {
        throw primary;
      }
      return null;
    }).when(created).initWalAndDiskCacheOnOpen(any(ContextConfiguration.class));
    doAnswer(call -> {
      steps.add("metadata");
      call.callRealMethod();
      closed[2] = true;
      if (failedStep == 2) {
        throw cleanupFailure;
      }
      return null;
    }).when(created).postCloseSteps(eq(false), eq(true), anyLong());
    var actualLogger = LogManager.instance();
    var observedLogger = mock(LogManager.class, call -> {
      if (call.getMethod().getName().equals("error") && call.getArguments().length > 2
          && call.getArgument(2) == primary) {
        steps.add("diagnostics");
        if (diagnosticFailure != null) {
          throw diagnosticFailure;
        }
      }
      return delegatesTo(actualLogger).answer(call);
    });
    try (var components = mockStatic(LinkCollectionsBTreeManagerShared.class, call -> {
      if (failDuringMigration && call.getMethod().getName().equals("isComponentPresent")
          && created.getStatus() == AbstractStorage.STATUS.MIGRATION) {
        reachedMigration[0] = true;
        throw primary;
      }
      return call.callRealMethod();
    }); var logging = mockStatic(LogManager.class)) {
      logging.when(LogManager::instance).thenReturn(observedLogger);
      var thrown = assertThrows(Throwable.class,
          () -> created.open(new ContextConfiguration()));
      if (primary instanceof IOException) {
        assertEquals(RuntimeException.class, thrown.getClass());
      }
      assertSame(primary, primary instanceof IOException ? thrown.getCause() : thrown);
      var suppressed = new ArrayList<Throwable>();
      if (cleanupFailure != null && cleanupFailure != primary) {
        suppressed.add(cleanupFailure);
      }
      if (diagnosticFailure != null && diagnosticFailure != primary) {
        suppressed.add(diagnosticFailure);
      }
      assertEquals(suppressed, Arrays.asList(primary.getSuppressed()));
      assertEquals(failDuringMigration, reachedMigration[0]);
      assertEquals(AbstractStorage.STATUS.CLOSED, created.getStatus());
      if (failedStep == 0) {
        var directory = (Path) field(DiskStorage.class, created, "storagePath");
        try (var channel = FileChannel.open(directory.resolve("dirty.fl"),
            StandardOpenOption.WRITE)) {
          assertThrows(OverlappingFileLockException.class, channel::tryLock);
        }
        assertEquals(List.of("read-cache", "WAL", "diagnostics"), steps);
      } else {
        assertEquals(List.of("read-cache", "WAL", "metadata", "diagnostics"), steps);
      }
    } finally {
      // Prevent manager teardown from repeating this fixture's explicit resource cleanup.
      setField(created, "status", AbstractStorage.STATUS.CLOSED);
      // CLOSED shutdown skips periodic-task cancellation. Do not retain this failed storage.
      for (var name : List.of("fuzzyCheckpointTask", "recordsGcTask")) {
        var task = (Future<?>) field(DiskStorage.class, created, name);
        if (task != null) {
          task.cancel(false);
          assertTrue(task.isCancelled());
        }
      }
      // Bypass fault-injecting wrappers and always release real resources, including in red runs.
      try {
        if (!closed[0]) {
          actualReadCache.closeStorage(created.getWriteCache());
        }
      } finally {
        try {
          if (!closed[1] && actualWal[0] != null) {
            actualWal[0].close();
          }
        } finally {
          if (!closed[2]) {
            ((StorageStartupMetadata) field(DiskStorage.class, created, "startupMetadata")).close();
          }
        }
      }
    }
  }

  private static void setField(AbstractStorage target, String name, Object value) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private DiskStorage createObservedStorage() throws Exception {
    // Replace only this manager's factory. The shared engine and other managers stay untouched.
    var engineField = manager.internal.getClass().getDeclaredField("disk");
    engineField.setAccessible(true);
    var realEngine = (Engine) engineField.get(manager.internal);
    var engine = spy(realEngine);
    doAnswer(call -> {
      var real = (DiskStorage) call.callRealMethod();
      // Construct the mock itself so storage components capture it, not a copied spy source.
      return mock(DiskStorage.class, withSettings().useConstructor(real.getName(),
          field(DiskStorage.class, real, "storagePath").toString(), call.getArgument(3),
          real.getReadCache(), field(DiskStorage.class, real, "files"), call.getArgument(1),
          call.getArgument(2), call.getArgument(4)).defaultAnswer(CALLS_REAL_METHODS));
    }).when(engine).createStorage(anyString(), anyLong(), anyLong(), anyInt(), any());
    engineField.set(manager.internal, engine);
    try {
      manager.create("startup", DatabaseType.DISK, "admin", "admin", "admin");
      try (var opened = manager.open("startup", "admin", "admin")) {
        return (DiskStorage) opened.getStorage();
      }
    } finally {
      engineField.set(manager.internal, realEngine);
    }
  }

  private static ChangedPageTracker assertSharedUntrustedTracker(DiskStorage target)
      throws Exception {
    var selected = (ChangedPageTracker) field(DiskStorage.class, target, "changedPageTracker");
    assertNotNull(selected);
    assertSame(selected, field(WOWCache.class, target.getWriteCache(), "changedPageTracker"));
    var trusted = ChangedPageTracker.class.getDeclaredMethod("isTrusted");
    trusted.setAccessible(true);
    assertFalse((boolean) trusted.invoke(selected));
    return selected;
  }

  /** The real publisher completes before cutting, and cache file events invalidate the same state. */
  @Test
  public void publicationPrecedesBoundedCutAndUsesWriteCacheTracker() throws Exception {
    assertSame(tracker, field(WOWCache.class, storage.getWriteCache(), "changedPageTracker"));
    doAnswer(call -> {
      assertTrue(stateLock.isReadLockedByCurrentThread());
      assertPublishedCoverage();
      return true;
    }).when(wal).cutAllSegmentsSmallerThan(2);

    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    var order = inOrder(wal);
    order.verify(wal).preflightCut(3);
    order.verify(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(NO_SAVE, true), checkpoint());

    // WOWCache owns this file-identity change. Reusing another tracker would miss it.
    storage.getWriteCache().addFile("bridge-event.bin");
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
  }

  /** Both inventory availability failures report a retryable result without writing or cutting. */
  @Test
  public void unavailablePreflightLeavesNoSideFileOrCutAndLaterCallRetries() throws Exception {
    for (var failure : List.of(new IllegalStateException("closed inventory"),
        new NoSuchElementException("changed inventory"))) {
      Files.deleteIfExists(sideFile);
      doThrow(failure).when(wal).preflightCut(3);
      assertEquals(new CheckpointResult(PREFLIGHT_UNAVAILABLE, false), checkpoint());
      assertFalse(Files.exists(sideFile));
      assertFalse(
          Files.exists(sideFile.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)));
      verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
    }
    doReturn(new CutPreflight(true, 2, COVERAGE)).when(wal).preflightCut(3);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** A non-empty authority directory fails publication and invalidation, so no cut is permitted. */
  @Test
  public void failedDurableInvalidationReportsIOExceptionAndRetryCanPublish() throws Exception {
    Files.createDirectory(sideFile);
    var blocker = sideFile.resolve("blocker");
    Files.writeString(blocker, "do not remove");

    var failure = assertThrows(IOException.class, this::checkpoint);
    assertTrue(failure.getMessage().contains("side-file invalidation failed"));
    assertTrue(Files.exists(blocker));
    verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());

    Files.delete(blocker);
    Files.delete(sideFile);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** Cancellation can invalidate a failed save, but the bounded cutter never inherits its flag. */
  @Test
  public void interruptedPublicationPreservesFlagAndLaterCheckpointRetries() throws Exception {
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      assertFalse(Files.exists(sideFile));
      return true;
    }).when(wal).cutAllSegmentsSmallerThan(2);
    Thread.currentThread().interrupt();
    try {
      assertEquals(new CheckpointResult(INVALIDATED, true), checkpoint());
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    doReturn(true).when(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(SAVED, true), checkpoint());
    assertPublishedCoverage();
  }

  /** A cut error follows publication without rollback, preserves interruption, and allows retry. */
  @Test
  public void cutterFailureKeepsPublicationAndInterruptStatusForCaller() throws Exception {
    assertEquals(SAVED, checkpoint().outcome());
    var failure = new IOException("cut failed");
    doAnswer(call -> {
      assertFalse(Thread.currentThread().isInterrupted());
      assertPublishedCoverage();
      throw failure;
    }).when(wal).cutAllSegmentsSmallerThan(2);
    Thread.currentThread().interrupt();
    try {
      assertSame(failure, assertThrows(IOException.class, this::checkpoint));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertPublishedCoverage();
    doReturn(true).when(wal).cutAllSegmentsSmallerThan(2);
    assertEquals(new CheckpointResult(NO_SAVE, true), checkpoint());
  }

  /** Null prediction is a contract error, and an I/O preflight failure retains its identity. */
  @Test
  public void invalidOrFailedPreflightDoesNotSaveOrCut() throws Exception {
    when(wal.preflightCut(3)).thenReturn(null);
    assertThrows(IllegalStateException.class, this::checkpoint);
    var failure = new IOException("preflight failed");
    doThrow(failure).when(wal).preflightCut(3);
    assertSame(failure, assertThrows(IOException.class, this::checkpoint));
    assertFalse(Files.exists(sideFile));
    verify(wal, never()).cutAllSegmentsSmallerThan(anyLong());
  }

  private CheckpointResult checkpoint() throws IOException {
    stateLock.readLock().lock();
    try {
      return storage.checkpointChangedPages(3);
    } finally {
      stateLock.readLock().unlock();
    }
  }

  private void assertPublishedCoverage() throws Exception {
    assertTrue(Files.isRegularFile(sideFile));
    assertFalse(Files.exists(sideFile.resolveSibling(ChangedPageTrackerFile.TEMPORARY_FILE_NAME)));
    try (var in = new DataInputStream(Files.newInputStream(sideFile))) {
      assertEquals(0x59544350, in.readInt());
      assertEquals(1, in.readInt());
      in.readUnsignedByte();
      assertEquals(COVERAGE, new LogSequenceNumber(in));
      assertEquals(field(ChangedPageTracker.class, tracker, "trackerId"),
          new UUID(in.readLong(), in.readLong()));
    }
  }

  private void replaceWal(WriteAheadLog replacement) throws Exception {
    stateLock.writeLock().lock();
    try {
      var field = AbstractStorage.class.getDeclaredField("writeAheadLog");
      field.setAccessible(true);
      field.set(storage, replacement);
    } finally {
      stateLock.writeLock().unlock();
    }
  }

  private static Object field(Class<?> owner, Object target, String name) throws Exception {
    var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }
}
