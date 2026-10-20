package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.collection.closabledictionary.ClosableLinkedContainer;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.config.ContextConfiguration;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexBuildState;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycle;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.BootstrapMetadataTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.FeatureFormatIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.LogicalSequenceFloor;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageBootstrapMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageLineageIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileCreatedWALRecord;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Covers disk storage bootstrap creation and open wiring. */
public class DiskStorageBootstrapWiringTest {

  private static final String DATABASE = "bootstrapWiring";
  private static final String RESTORED_DATABASE = "bootstrapWiringRestored";
  private static final String DIRTY_DATABASE = "bootstrapWiringDirty";
  private static final String CRASH_CHILD_ARGUMENT = "restore-and-halt";
  private static final String BIRTH_CHILD_ARGUMENT = "birth-and-halt";
  private static final String DIRTY_CHILD_ARGUMENT = "commit-and-halt";
  private static final String COMMIT_RACE_CHILD_ARGUMENT = "commit-race-and-halt";
  private static final String DIRTY_CLASS = "DirtyWork";
  private static final String REGISTRY_CHILD_ARGUMENT = "registry-and-halt";

  /**
   * Commit count before the child reports its high-water mark. The count keeps that mark far above
   * the identifiers one open consumes, so a reopen without recovery cannot reach it.
   */
  private static final int DIRTY_COMMITS_BEFORE_REPORT = 1_000;

  /** Commit count after the report, so recovered evidence exceeds the reported mark. */
  private static final int DIRTY_COMMITS_AFTER_REPORT = 25;

  private static final long RAISED_FLOOR_DISTANCE = 1_000_000;
  private static final String ADMIN = "admin";

  private Path directory;

  @Before
  public void createDirectory() throws Exception {
    directory = Files.createTempDirectory("disk-bootstrap-wiring-");
  }

  @After
  public void deleteDirectory() {
    FileUtils.deleteRecursively(directory.toFile());
  }

  /** Creation publishes active format one and open reloads the same identities. */
  @Test
  public void creationPublishesActiveAuthorityAndOpenReloadsIdentity() throws Exception {
    StorageIdentity storageIdentity;
    StorageLineageIdentity lineageIdentity;
    try (var youTrackDB = createDatabase()) {
      try (var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
        var storage = (AbstractStorage) session.getStorage();
        storageIdentity = storage.getStorageIdentity();
        lineageIdentity = storage.getStorageLineageIdentity();
      }
    }

    var authority = authority();
    var active = authority.readActiveRequired();
    assertEquals(1, active.format().version());
    assertEquals(storageIdentity, active.storageIdentity());
    assertEquals(lineageIdentity, active.lineageIdentity());

    try (var youTrackDB = openManager();
        var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      assertEquals(storageIdentity, storage.getStorageIdentity());
      assertEquals(lineageIdentity, storage.getStorageLineageIdentity());
    }
  }

  /**
   * Open reports the missing-record reason for a directory without any bootstrap artifact.
   *
   * <p>The scenario removes every authority copy and also removes the authority lock file, so the
   * directory holds content files only. The expected outcome is one open failure whose cause names
   * the missing-record reason. The scenario keeps the lock file out on purpose, because a
   * remaining lock file would turn the directory into birth residue.
   */
  @Test
  public void openRejectsAbsentAuthority() throws Exception {
    try (var ignored = createDatabase()) {
      // Closing the manager leaves a complete disk image for the open attempt.
    }
    deleteAuthorityFiles();
    Files.delete(directory.resolve(DATABASE).resolve("storage-bootstrap.bsml"));

    try (var youTrackDB = openManager()) {
      var failure =
          assertThrows(RuntimeException.class, () -> youTrackDB.open(DATABASE, ADMIN, ADMIN));
      assertTrue(hasCauseMessage(failure, "AUTHORITY_MISSING"));
    }
  }

  /** Open rejects a disk storage carrying an interrupted birth record. */
  @Test
  public void openRejectsInterruptedBirth() throws Exception {
    try (var ignored = createDatabase()) {
      // Closing the manager leaves a complete disk image for authority replacement.
    }
    deleteAuthorityFiles();
    authority().createBirth(StorageIdentity.random(), StorageLineageIdentity.random());

    try (var youTrackDB = openManager()) {
      assertThrows(RuntimeException.class, () -> youTrackDB.open(DATABASE, ADMIN, ADMIN));
    }
  }

  /**
   * Open reports the named interrupted-birth reason for a directory with lock-file residue.
   *
   * <p>The scenario keeps only the authority lock file of a complete image. The expected outcome is
   * a storage failure whose message names the interrupted-birth admission reason.
   */
  @Test
  public void openReportsInterruptedBirthForLockFileResidue() throws Exception {
    try (var ignored = createDatabase()) {
      // Closing the manager leaves a complete disk image for the residue construction.
    }
    deleteAuthorityFiles();
    assertTrue(Files.exists(directory.resolve(DATABASE).resolve("storage-bootstrap.bsml")));

    try (var youTrackDB = openManager()) {
      var failure =
          assertThrows(RuntimeException.class, () -> youTrackDB.open(DATABASE, ADMIN, ADMIN));
      assertTrue(hasCauseMessage(failure, "INTERRUPTED_BIRTH"));
    }
  }

  /**
   * The directory scanner lists a database directory that holds only birth residue.
   *
   * <p>The scenario creates a directory with the authority lock file alone. The expected outcome is
   * one listing entry, so the listing agrees with the existence probe.
   */
  @Test
  public void directoryScannerListsBirthResidueDirectory() throws Exception {
    var residueDirectory = Files.createDirectory(directory.resolve("residueOnly"));
    Files.createFile(residueDirectory.resolve("storage-bootstrap.bsml"));

    try (var youTrackDB = openManager()) {
      assertTrue(youTrackDB.listDatabases().contains("residueOnly"));
      assertTrue(youTrackDB.exists("residueOnly"));
    }
  }

  /** Disk open installs a higher authority floor before issuing startup operations. */
  @Test
  public void openConsumesHigherAuthorityFloor() throws Exception {
    long authorityFloor;
    try (var ignored = createDatabase()) {
      // A graceful close supplies ordinary startup progress before authority is raised above it.
    }
    var metadata = authority();
    var active = metadata.readActiveRequired();
    authorityFloor = 1_000_000;
    metadata.advanceFloor(
        active,
        new LogicalSequenceFloor(
            active.storageIdentity(), active.lineageIdentity(), authorityFloor));

    try (var youTrackDB = openManager();
        var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      assertTrue(storage.getIdGen().getLastId() > authorityFloor);
    }
  }

  /** Disk open uses graceful-close progress when it exceeds the active authority floor. */
  @Test
  public void openConsumesHigherStartupFloor() throws Exception {
    try (var ignored = createDatabase()) {
      // A graceful close creates valid startup metadata before this test raises its evidence.
    }
    var active = authority().readActiveRequired();
    var startupFloor = active.sequenceFloor().highestIssued() + 1_000_000;
    var startup =
        new StorageStartupMetadata(
            directory.resolve(DATABASE).resolve("dirty.fl"),
            directory.resolve(DATABASE).resolve("dirty.flb"));
    startup.open(YouTrackDBConstants.getRawVersion());
    try {
      startup.setLastTxId(startupFloor);
    } finally {
      startup.close();
    }

    try (var youTrackDB = openManager();
        var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      assertTrue(storage.getIdGen().getLastId() > startupFloor);
    }
  }

  /** Disk open refuses an exhausted authority floor before any identifier can wrap. */
  @Test
  public void openRejectsExhaustedAuthorityFloor() throws Exception {
    try (var ignored = createDatabase()) {
      // A complete image is required before its authority floor can be advanced.
    }
    var metadata = authority();
    var active = metadata.readActiveRequired();
    metadata.advanceFloor(
        active,
        new LogicalSequenceFloor(
            active.storageIdentity(), active.lineageIdentity(), Long.MAX_VALUE));

    try (var youTrackDB = openManager()) {
      var failure =
          assertThrows(RuntimeException.class, () -> youTrackDB.open(DATABASE, ADMIN, ADMIN));
      assertTrue(hasCauseMessage(failure, "identifier space is exhausted"));
    }
  }

  /**
   * A new image resumes above its final creation timestamp after an abrupt stop without writes.
   *
   * <p>The child performs normal database creation, reports the generator mark, and halts without
   * closing the manager. The parent deletes both startup metadata copies so the bootstrap authority
   * is the only remaining durable opening floor. Restart must not reuse a creation timestamp.
   */
  @Test
  public void birthAuthorityFloorSurvivesAbruptStopWithoutLaterWrite() throws Exception {
    var handshake = directory.resolve("birth-floor.txt");
    runCrashChild(BIRTH_CHILD_ARGUMENT, handshake);

    var issuedBeforeHalt = Long.parseLong(Files.readString(handshake).trim());
    var storageDirectory = directory.resolve(DATABASE);
    // Remove both startup copies. Otherwise their checkpoint floor masks a missing birth floor.
    Files.deleteIfExists(storageDirectory.resolve("dirty.fl"));
    Files.deleteIfExists(storageDirectory.resolve("dirty.flb"));
    assertFalse(Files.exists(storageDirectory.resolve("dirty.fl")));
    assertFalse(Files.exists(storageDirectory.resolve("dirty.flb")));
    var durable = authority().readActiveRequired();
    assertTrue("genesis must issue timestamps", issuedBeforeHalt > 0);
    assertEquals("activation must publish the creation generator mark",
        issuedBeforeHalt, durable.sequenceFloor().highestIssued());

    try (var manager = openManager();
        var session = ((YouTrackDBInternalEmbedded) manager.internal)
            .openNoAuthorization(DATABASE)) {
      assertTrue("restart must resume above every creation timestamp",
          ((DiskStorage) session.getStorage()).getIdGen().getLastId() > issuedBeforeHalt);
    }
  }

  /**
   * A fresh-target restore survives a halt after activation without reusing its highest timestamp.
   *
   * <p>The child writes only the post-restore generator, then uses {@link Runtime#halt(int)}.
   * After the halt, the parent confirms the durable authority floor and expects reopen to issue
   * only timestamps above the child's high-water mark.
   */
  @Test
  public void restoredAuthorityFloorSurvivesAbruptStopAfterActivation() throws Exception {
    var handshake = directory.resolve("restore-floor.txt");
    runCrashChild(CRASH_CHILD_ARGUMENT, handshake);

    var issuedBeforeHalt = Long.parseLong(Files.readString(handshake).trim());
    var durable = restoredAuthority().readRequired();
    assertEquals(issuedBeforeHalt, durable.sequenceFloor().highestIssued());

    try (var youTrackDB = openManager();
        var session = youTrackDB.open(RESTORED_DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      assertTrue(storage.getIdGen().getLastId() > issuedBeforeHalt);
    }
  }

  /**
   * A crashed disk image adopts recovered evidence that stands above its opening floor.
   *
   * <p>The child creates the database, commits {@value #DIRTY_COMMITS_BEFORE_REPORT}
   * transactions, reports the generator mark it reached, commits
   * {@value #DIRTY_COMMITS_AFTER_REPORT} further transactions, flushes the write-ahead log, and
   * halts. The image therefore stays dirty and its log holds completed operations above the
   * reported mark. The parent then opens that image through the ordinary open path.
   *
   * <p>The expected outcome is one replay pass whose generator ends above the reported mark. An
   * open that skipped the recovery call fails this test, as a measured mutation confirmed. Such an
   * open reads unrecovered pages and never reports a replay, and its generator would hold only the
   * low authority floor of a fresh birth.
   */
  @Test
  public void crashOpenAdoptsRecoveredEvidenceAboveOpeningFloor() throws Exception {
    var handshake = directory.resolve("dirty-floor.txt");
    runCrashChild(DIRTY_CHILD_ARGUMENT, handshake);

    var reportedMark = Long.parseLong(Files.readString(handshake).trim());
    var openingFloor = dirtyAuthority().readActiveRequired().sequenceFloor().highestIssued();
    assertTrue(
        "the child must leave recovered evidence above the opening floor",
        reportedMark > openingFloor);

    try (var youTrackDB = openManager();
        var session = youTrackDB.open(DIRTY_DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      assertTrue("open must replay the write ahead log", storage.wereDataRestoredAfterOpen());
      assertTrue(
          "recovered evidence must raise the generator",
          storage.getIdGen().getLastId() > reportedMark);
    }
  }

  /**
   * A crashed disk image keeps an opening floor that stands above its recovered evidence.
   *
   * <p>The scenario reuses the crashed image of the test above and then raises the durable
   * authority floor {@value #RAISED_FLOOR_DISTANCE} above the child's reported mark. Replay
   * evidence is therefore lower than the installed floor.
   *
   * <p>The expected outcome is one replay pass that leaves the higher installed floor in place. An
   * open that skipped the recovery call fails this test, as a measured mutation confirmed. Such an
   * open reads unrecovered pages and never reports a replay.
   */
  @Test
  public void crashOpenKeepsOpeningFloorAboveRecoveredEvidence() throws Exception {
    var handshake = directory.resolve("dirty-floor.txt");
    runCrashChild(DIRTY_CHILD_ARGUMENT, handshake);

    var reportedMark = Long.parseLong(Files.readString(handshake).trim());
    var metadata = dirtyAuthority();
    var active = metadata.readActiveRequired();
    var raisedFloor = reportedMark + RAISED_FLOOR_DISTANCE;
    metadata.advanceFloor(
        active,
        new LogicalSequenceFloor(
            active.storageIdentity(), active.lineageIdentity(), raisedFloor));

    try (var youTrackDB = openManager();
        var session = youTrackDB.open(DIRTY_DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      assertTrue("open must replay the write ahead log", storage.wereDataRestoredAfterOpen());
      assertTrue(
          "replay must not rewind the installed floor",
          storage.getIdGen().getLastId() > raisedFloor);
    }
  }

  /** A checkpoint between commit preparation and admission cannot clear its indication. */
  @Test(timeout = 60_000)
  public void checkpointBetweenOldCommitMarkAndTimestampLeavesRecoveryEnabled() throws Exception {
    try (var manager = createDatabase();
        var setup = manager.open(DATABASE, ADMIN, ADMIN)) {
      setup.getMetadata().getSchema().createClass("WindowCommit");
      var storage = (DiskStorage) setup.getStorage();
      var prepared = new CountDownLatch(1);
      var proceed = new CountDownLatch(1);
      storage.setBeforeCommitApplyActionForTesting(() -> {
        prepared.countDown();
        await(proceed);
      });
      assertThrows(IllegalStateException.class,
          () -> storage.setBeforeCommitApplyActionForTesting(() -> {
          }));
      var commit = CompletableFuture.runAsync(() -> {
        try (var session = manager.open(DATABASE, ADMIN, ADMIN)) {
          session.begin();
          session.newEntity("WindowCommit").setProperty("payload", "window");
          session.commit();
        }
      });
      try {
        assertTrue(prepared.await(15, TimeUnit.SECONDS));
        storage.synch();
        assertFalse("checkpoint must clear the pre-admission indication", durableDirty(storage));
      } finally {
        proceed.countDown();
      }
      commit.get(15, TimeUnit.SECONDS);
      assertTrue("admitted commit must re-mark after checkpoint", durableDirty(storage));
    }
  }

  /** After a checkpoint overtakes commit preparation, abrupt exit must replay the later commit. */
  @Test(timeout = 120_000)
  public void checkpointCommitRaceSurvivesAbruptStopAndResumesIds() throws Exception {
    var handshake = directory.resolve("commit-race-floor.txt");
    runCrashChild(COMMIT_RACE_CHILD_ARGUMENT, handshake);
    var committedMark = Long.parseLong(Files.readString(handshake).trim());
    try (var manager = openManager();
        var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (DiskStorage) session.getStorage();
      assertTrue("the raced commit must cause WAL replay", storage.wereDataRestoredAfterOpen());
      assertTrue("open must resume above the raced timestamp",
          storage.getIdGen().getLastId() > committedMark);
    }
  }

  /** Open-time atomic work must set the indication after a clean close and before its timestamp. */
  @Test(timeout = 60_000)
  public void openTimeAtomicWriteMarksBeforeTakingTimestamp() throws Exception {
    try (var ignored = createDatabase()) {
      // A clean close leaves open-time work responsible for enabling recovery again.
    }
    var otherRoot = createOtherStorageWithSameName();
    var observedBefore = new AtomicBoolean();
    var observedAtTimestamp = new AtomicBoolean();
    DiskStorage.setOpenIndicationActionForTesting(
        directory.resolve(DATABASE),
        storage -> storage.setBeforeRecoveryIndicationActionForTesting(() -> {
          assertFalse("open must start with a clean indication", durableDirty(storage));
          observedBefore.set(true);
          storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(() -> {
            assertTrue("open-time timestamp must follow the durable write", durableDirty(storage));
            observedAtTimestamp.set(true);
          });
        }));
    try (var other = (YouTrackDBImpl) YourTracks.instance(otherRoot.toString());
        var unrelated = other.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue(unrelated.getStorage() != null);
      assertFalse("another storage must not consume the observer", observedBefore.get());
      try (var manager = openManager();
          var session = manager.open(DATABASE, ADMIN, ADMIN)) {
        assertTrue(observedBefore.get());
        assertTrue(observedAtTimestamp.get());
      }
    } finally {
      DiskStorage.clearOpenIndicationActionForTesting();
    }
  }

  /**
   * A registry rewrite failure during cache loading fails open with the original IO error.
   * Both maintenance tasks and cache flushing stop, all opened data and map channels close,
   * and a fresh storage opens the directory after the injected fault is removed.
   */
  @Test(timeout = 60_000)
  public void failedRegistryRewriteDuringOpenReleasesTasksAndPartiallyLoadedCache()
      throws Exception {
    final var storageDirectory = directory.resolve(DATABASE);
    final var primary = storageDirectory.resolve("non_durable_files.cm");
    final var shadow = storageDirectory.resolve("non_durable_files_shadow.cm");
    final byte[] staleRegistry;
    try (var manager = createDatabase();
        var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      final var cache = session.getStorage().getWriteCache();
      final var deleted = cache.bookFileId("openDeleted.tst");
      cache.addFile("openDeleted.tst", deleted, true);
      cache.addFile("openSurvivor.tst");
      final var nonDurableSurvivor = cache.bookFileId("openNonDurableSurvivor.tst");
      cache.addFile("openNonDurableSurvivor.tst", nonDurableSurvivor, true);
      staleRegistry = Files.readAllBytes(primary);
      cache.deleteFile(deleted);
    }
    Files.write(primary, staleRegistry);
    Files.write(shadow, staleRegistry);

    final var observed = new AtomicReference<DiskStorage>();
    final var openedFiles = new HashMap<Long, File>();
    final var mapChannel = new AtomicReference<FileChannel>();
    final var injected = new IOException("open registry rewrite rejected");
    DiskStorage.setOpenIndicationActionForTesting(storageDirectory, observed::set);
    try (var manager = openManager()) {
      try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
        channels.when(() -> FileChannel.open(storageDirectory.resolve("name_id_map_v3.cm"),
            StandardOpenOption.WRITE, StandardOpenOption.READ)).thenAnswer(invocation -> {
              final var channel = (FileChannel) invocation.callRealMethod();
              mapChannel.set(channel);
              return channel;
            });
        // Both copies are valid, so filtering rewrites the primary first.
        channels
            .when(() -> FileChannel.open(primary, StandardOpenOption.WRITE))
            .thenAnswer(invocation -> {
              openedFiles.putAll(registeredFiles(observed.get()));
              try (var partial = (FileChannel) invocation.callRealMethod()) {
                partial.write(java.nio.ByteBuffer.wrap(new byte[] {1, 2}), 0);
                partial.force(true);
              }
              throw injected;
            });
        final var failure = assertThrows(RuntimeException.class,
            () -> manager.open(DATABASE, ADMIN, ADMIN));
        assertTrue("open must propagate the registry IO error", hasCause(failure, injected));
      }
      assertNotNull(mapChannel.get());
      assertFalse("the name-id map channel must close", mapChannel.get().isOpen());
      assertFailedOpenResourcesReleased(observed.get(), openedFiles);
      try (var retry = manager.open(DATABASE, ADMIN, ADMIN)) {
        assertNotSame("retry must use a fresh storage", observed.get(), retry.getStorage());
        final var cache = retry.getStorage().getWriteCache();
        assertTrue(cache.exists("openSurvivor.tst"));
        assertTrue(cache.isNonDurable(cache.fileIdByName("openNonDurableSurvivor.tst")));
      }
    } finally {
      DiskStorage.clearOpenIndicationActionForTesting();
    }
  }

  /**
   * An unchecked append-channel failure during WAL replay fails open with its cause intact.
   * The later committed file unit remains unapplied, and a fresh open replays both units.
   */
  @Test(timeout = 120_000)
  public void runtimeRegistryAppendFailureFailsOpenWithoutDiscardingLaterWalUnits()
      throws Exception {
    runCrashChild(REGISTRY_CHILD_ARGUMENT, directory.resolve("registry-ready.txt"));
    final var storageDirectory = directory.resolve(DATABASE);
    final var mapPath = storageDirectory.resolve("name_id_map_v3.cm");
    final var injected = new SecurityException("append channel denied");
    final var observed = new AtomicReference<DiskStorage>();
    final var failedAppend = new AtomicBoolean();
    DiskStorage.setOpenIndicationActionForTesting(storageDirectory, observed::set);
    try (var manager = openManager()) {
      try (var channels = mockStatic(FileChannel.class, CALLS_REAL_METHODS)) {
        channels.when(() -> FileChannel.open(mapPath,
            StandardOpenOption.READ, StandardOpenOption.WRITE)).thenAnswer(invocation -> {
              // Loading uses WRITE, READ. The append channel is opened with READ, WRITE.
              failedAppend.set(true);
              final var cache = observed.get().getWriteCache();
              assertTrue("the first unit must already be materializing its file",
                  cache.exists("replayFirst.tst"));
              assertFalse("the later unit must not be applied yet",
                  cache.exists("replayLater.tst"));
              throw injected;
            });
        final var failure = assertThrows(RuntimeException.class,
            () -> manager.open(DATABASE, ADMIN, ADMIN));
        assertTrue("the unchecked append failure must be exercised", failedAppend.get());
        assertTrue("open must retain the original cause", hasCause(failure, injected));
        assertTrue("the registry failure must use the checked IO path",
            hasIoCause(failure));
      }
      assertEquals(AbstractStorage.STATUS.CLOSED, observed.get().getStatus());
      try (var paths = Files.list(storageDirectory)) {
        assertFalse("failed replay must not create the later unit's file",
            paths.anyMatch(path -> path.getFileName().toString().startsWith("replayLater")));
      }
      assertTrue((boolean) fieldValue(observed.get().getWriteCache(), WOWCache.class,
          "nameIdMapAppendsStopped"));
      try (var retry = manager.open(DATABASE, ADMIN, ADMIN)) {
        final var cache = retry.getStorage().getWriteCache();
        assertTrue("retry must replay the failed unit", cache.exists("replayFirst.tst"));
        assertTrue("retry must replay the later unit too", cache.exists("replayLater.tst"));
      }
    } finally {
      DiskStorage.clearOpenIndicationActionForTesting();
    }
  }

  /**
   * A negative first or later V3 record length rejects both opens of the same storage object.
   * Each rejection must report the path, offset and length, keep error state clear and preserve bytes.
   */
  @Test
  public void negativeMapLengthsRejectRepeatedOpenWithoutErrorStateOrMapChanges() throws Exception {
    try (var ignored = createDatabase()) {
      // Use a complete disk image so each open reaches the name-id map reader.
    }
    final var path = directory.resolve(DATABASE).resolve("name_id_map_v3.cm");
    final var original = Files.readAllBytes(path);
    final var laterOffset = Long.BYTES + Integer.BYTES + ByteBuffer.wrap(original).getInt(8);
    for (final var offset : new int[] {0, laterOffset}) {
      // -1 marks the incomplete-rewrite guard in the first record.
      final var length = offset == 0 ? -1 : 0xFFFFFFF0;
      final var bytes = original.clone();
      ByteBuffer.wrap(bytes).putInt(offset + Long.BYTES, length);
      Files.write(path, bytes);
      assertMapRejectedOnRepeatedOpen(Map.of(path, bytes),
          "Negative record length " + length, path.toString(), "at byte " + offset);
    }
  }

  /**
   * Each legacy map rejects both opens of the same storage object with the migration remedy.
   * Neither rejection may set error state or change the legacy map or the existing V3 map.
   */
  @Test
  public void legacyMapsRejectRepeatedOpenWithoutErrorStateOrMapChanges() throws Exception {
    try (var ignored = createDatabase()) {
      // Keep a current map beside each legacy map to check that rejection preserves both.
    }
    final var storageDirectory = directory.resolve(DATABASE);
    final var current = storageDirectory.resolve("name_id_map_v3.cm");
    final var currentBytes = Files.readAllBytes(current);
    for (final var fileName : new String[] {"name_id_map.cm", "name_id_map_v2.cm"}) {
      final var legacy = storageDirectory.resolve(fileName);
      final var legacyBytes = new byte[] {1, 2, 3};
      Files.write(legacy, legacyBytes);
      assertMapRejectedOnRepeatedOpen(Map.of(current, currentBytes, legacy, legacyBytes),
          legacy.toString(), "no longer supported", "Export the database with the older release",
          "import it into a fresh database", "docs/operator-migration-procedure.md");
      Files.delete(legacy);
    }
  }

  private void assertMapRejectedOnRepeatedOpen(Map<Path, byte[]> maps, String... messageParts)
      throws Exception {
    final var observed = new AtomicReference<DiskStorage>();
    DiskStorage.setOpenIndicationActionForTesting(directory.resolve(DATABASE), observed::set);
    try (var manager = openManager()) {
      final var first = assertThrows(DatabaseException.class,
          () -> manager.open(DATABASE, ADMIN, ADMIN));
      assertTrue("the manager must retain the storage rejection",
          first.getCause() instanceof StorageException);
      final var storage = observed.get();
      assertNotNull("the failed storage must be observed", storage);
      assertMapRejection(storage, (StorageException) first.getCause(), maps, messageParts);

      // Bypass the manager, which removes failed storages and would hide a poisoned instance.
      final var second = assertThrows(StorageException.class,
          () -> storage.open(new ContextConfiguration()));
      assertMapRejection(storage, second, maps, messageParts);
    } finally {
      DiskStorage.clearOpenIndicationActionForTesting();
    }
  }

  private static void assertMapRejection(DiskStorage storage, StorageException failure,
      Map<Path, byte[]> maps, String[] messageParts) throws Exception {
    for (final var part : messageParts) {
      assertTrue(failure.getMessage(), failure.getMessage().contains(part));
    }
    assertEquals(AbstractStorage.STATUS.CLOSED, storage.getStatus());
    // This public guard throws if isInError() is true, even while the storage is CLOSED.
    storage.checkErrorState();
    for (final var entry : maps.entrySet()) {
      assertArrayEquals(entry.getKey().toString(), entry.getValue(),
          Files.readAllBytes(entry.getKey()));
    }
  }

  private static boolean hasIoCause(Throwable failure) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof IOException) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private static Map<Long, File> registeredFiles(DiskStorage storage) throws Exception {
    final var cache = (WOWCache) storage.getWriteCache();
    final var names = (Map<String, Integer>) fieldValue(cache, WOWCache.class, "nameIdMap");
    final var container = (ClosableLinkedContainer<Long, File>) fieldValue(
        storage, DiskStorage.class, "files");
    final var result = new HashMap<Long, File>();
    for (final var id : names.values()) {
      if (id >= 0) {
        final var externalId = cache.externalFileId(id);
        final var file = container.get(externalId);
        if (file != null) {
          result.put(externalId, file);
        }
      }
    }
    return result;
  }

  private static Object fieldValue(Object target, Class<?> owner, String name) throws Exception {
    final var field = owner.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(target);
  }

  @SuppressWarnings("unchecked")
  private static void assertFailedOpenResourcesReleased(
      DiskStorage storage, Map<Long, File> openedFiles) throws Exception {
    assertNotNull("the failed storage must be observed", storage);
    assertEquals(AbstractStorage.STATUS.CLOSED, storage.getStatus());
    for (final var name : new String[] {"fuzzyCheckpointTask", "recordsGcTask"}) {
      final var task = (Future<?>) fieldValue(storage, DiskStorage.class, name);
      assertNotNull("the task must have been scheduled", task);
      assertTrue(name + " must be cancelled", task.isCancelled());
    }
    final var cache = (WOWCache) storage.getWriteCache();
    assertTrue("the failed cache must be closed", (boolean) fieldValue(cache, WOWCache.class,
        "closed"));
    assertTrue("background flushing must stop", (boolean) fieldValue(cache, WOWCache.class,
        "stopFlush"));
    final var flush = (Future<?>) fieldValue(cache, WOWCache.class, "flushFuture");
    assertTrue("the flush task must finish", flush == null || flush.isDone());
    final var container = (ClosableLinkedContainer<Long, File>) fieldValue(
        storage, DiskStorage.class, "files");
    assertFalse("the failure must happen after data files open", openedFiles.isEmpty());
    for (final var entry : openedFiles.entrySet()) {
      assertFalse("data file channels must close", entry.getValue().isOpen());
      assertEquals("closed files must leave the container", null, container.get(entry.getKey()));
    }
  }

  private static boolean hasCause(Throwable failure, Throwable expected) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (cause == expected) {
        return true;
      }
    }
    return false;
  }

  /** A failed open-time indication releases tasks, cache files and metadata for a fresh open. */
  @Test(timeout = 60_000)
  public void failedOpenIndicationClosesMetadataAndPermitsRetry() throws Exception {
    try (var ignored = createDatabase()) {
      // The next open must perform its own atomic setup on a clean image.
    }
    var otherRoot = createOtherStorageWithSameName();
    final var observed = new AtomicReference<DiskStorage>();
    final var openedFiles = new HashMap<Long, File>();
    DiskStorage.setOpenIndicationActionForTesting(directory.resolve(DATABASE), storage -> {
      observed.set(storage);
      storage.setBeforeRecoveryIndicationActionForTesting(() -> {
        try {
          openedFiles.putAll(registeredFiles(storage));
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      });
      storage.failNextRecoveryIndicationForTesting(new IOException("open indication rejected"));
    });
    try (var other = (YouTrackDBImpl) YourTracks.instance(otherRoot.toString());
        var unrelated = other.open(DATABASE, ADMIN, ADMIN);
        var manager = openManager()) {
      assertTrue("another storage must open without consuming the failure",
          unrelated.getStorage() != null);
      var failure = assertThrows(RuntimeException.class,
          () -> manager.open(DATABASE, ADMIN, ADMIN));
      assertTrue(hasCauseMessage(failure, "open indication rejected"));
      assertFailedOpenResourcesReleased(observed.get(), openedFiles);
      try (var retry = manager.open(DATABASE, ADMIN, ADMIN)) {
        assertNotSame("retry must use a fresh storage", observed.get(), retry.getStorage());
        assertTrue("retry must acquire the metadata lock and open", retry.getStorage() != null);
      }
    } finally {
      DiskStorage.clearOpenIndicationActionForTesting();
    }
  }

  /** A real backup creation failure crosses the disk bridge and rejects the timestamp. */
  @Test(timeout = 60_000)
  public void metadataWriteFailureRejectsCommitBeforeTimestamp() throws Exception {
    try (var manager = createDatabase();
        var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      session.getMetadata().getSchema().createClass("MetadataFailureCommit");
      var storage = (DiskStorage) session.getStorage();
      storage.synch();
      var before = storage.getIdGen().getLastId();
      var backup = directory.resolve(DATABASE).resolve("dirty.flb");
      Files.createDirectory(backup);
      var obstacle = Files.createFile(backup.resolve("cannot-delete"));
      try {
        session.begin();
        session.newEntity("MetadataFailureCommit").setProperty("payload", "failed");
        var failure = assertThrows(RuntimeException.class, session::commit);
        assertTrue(hasCauseMessage(failure, "dirty.flb"));
        assertEquals("metadata failure must not allocate an id", before,
            storage.getIdGen().getLastId());
        assertFalse(durableDirty(storage));
        session.rollback();
      } finally {
        Files.deleteIfExists(obstacle);
        Files.deleteIfExists(backup);
      }
      session.begin();
      session.newEntity("MetadataFailureCommit").setProperty("payload", "retry");
      session.commit();
      assertTrue(durableDirty(storage));
      storage.checkErrorState();
    }
  }

  /** A failed indication write rejects a commit without issuing an identifier, then retries. */
  @Test(timeout = 60_000)
  public void commitIndicationFailureReportsAndRetriesWithoutErrorState() throws Exception {
    try (var manager = createDatabase();
        var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      session.getMetadata().getSchema().createClass("RetryCommit");
      var storage = (DiskStorage) session.getStorage();
      storage.synch();
      var before = storage.getIdGen().getLastId();
      storage.failNextRecoveryIndicationForTesting(new IOException("injected indication failure"));
      assertThrows(IllegalStateException.class,
          () -> storage.failNextRecoveryIndicationForTesting(new IOException("duplicate")));
      session.begin();
      session.newEntity("RetryCommit").setProperty("payload", "first");
      var failure = assertThrows(RuntimeException.class, session::commit);
      assertTrue(hasCauseMessage(failure, "injected indication failure"));
      assertEquals("failed write must take no timestamp", before,
          storage.getIdGen().getLastId());
      assertFalse("failed indication must not be treated as durable", durableDirty(storage));
      session.rollback();
      session.begin();
      session.newEntity("RetryCommit").setProperty("payload", "second");
      session.commit();
      assertTrue(durableDirty(storage));
    }
  }

  /** No operation may take a timestamp after a clear until the indication reaches disk. */
  @Test(timeout = 60_000)
  public void standaloneAtomicWriteMarksAfterCheckpointBeforeTimestamp() throws Exception {
    try (var manager = createDatabase();
        var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (DiskStorage) session.getStorage();
      storage.synch();
      assertFalse(durableDirty(storage));
      storage.setBeforeRecoveryIndicationActionForTesting(
          () -> assertFalse("write must not yet have a durable indication", durableDirty(storage)));
      assertThrows(IllegalStateException.class,
          () -> storage.setBeforeRecoveryIndicationActionForTesting(() -> {
          }));
      storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(
          () -> assertTrue("timestamp follows the durable indication", durableDirty(storage)));
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        // The same entry point serves histogram, records GC, and open-time writers.
      });
      assertTrue(durableDirty(storage));
    }
  }

  /** Record cleanup reports a failed indication at ERROR and retries on the next pass. */
  @Test(timeout = 60_000)
  public void recordsGcFailureLogsAndNextPassMarksBeforeTimestamp() throws Exception {
    try (var manager = createDatabase();
        var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (DiskStorage) session.getStorage();
      var collection = storage.getCollectionInstances().stream()
          .filter(PaginatedCollectionV2.class::isInstance)
          .map(PaginatedCollectionV2.class::cast).findFirst().orElseThrow();
      storage.synch();
      var before = storage.getIdGen().getLastId();
      var rejected = new IOException("records GC indication rejected");
      storage.failNextRecoveryIndicationForTesting(rejected);
      var logged = new java.util.concurrent.atomic.AtomicBoolean();
      var logger = Logger.getLogger("");
      var handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
          if (record.getLevel().intValue() < Level.SEVERE.intValue()
              || !record.getMessage().contains("records GC")) {
            return;
          }
          for (var cause = record.getThrown(); cause != null; cause = cause.getCause()) {
            if (cause == rejected) {
              logged.set(true);
            }
          }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      };
      logger.addHandler(handler);
      try {
        collection.collectDeadRecords(storage.getSharedSnapshotIndex());
      } finally {
        logger.removeHandler(handler);
      }
      assertTrue("failed records GC must log at ERROR", logged.get());
      assertEquals("failed records GC must not allocate a timestamp", before,
          storage.getIdGen().getLastId());
      assertFalse(durableDirty(storage));
      storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(
          () -> assertTrue("records GC must set indication before timestamp",
              durableDirty(storage)));
      collection.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertTrue(durableDirty(storage));
      storage.checkErrorState();
    }
  }

  private static boolean durableDirty(DiskStorage storage) {
    try {
      return storage.readStartupMetadataForTesting()[12] != 0;
    } catch (IOException failure) {
      throw new RuntimeException(failure);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(15, TimeUnit.SECONDS)) {
        throw new AssertionError("Timed out waiting for commit admission");
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new AssertionError(failure);
    }
  }

  /** Restore adopts stored progress without rewriting the lifecycle record. */
  @Test
  public void restoreAdoptsProgressWithoutLifecycleRewrite() throws Exception {
    var backupDirectory = Files.createDirectory(directory.resolve("backup"));
    try (var youTrackDB = createDatabase();
        var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      var manager = session.getSharedContext().getIndexManager();
      var index = manager.getIndex("OUser.name");
      var lifecycle =
          session.computeInTx(
              tx -> tx.loadEntity(index.getIdentity()).getLink(Index.LIFECYCLE_RECORD));
      var store = storage.getIndexBuildStateStore();
      var initial = store.read(index.getIdentity(), lifecycle);
      var state = initial.buildState();
      var backedUpState = lifecycleState(state, 31, 77L);
      var backedUp = store.publish(index.getIdentity(), lifecycle, initial, backedUpState);
      var firstHolder = storage.getIndexLifecycle(index.getIdentity());

      var storageIdentity = storage.getStorageIdentity();
      var lineageIdentity = storage.getStorageLineageIdentity();
      storage.backup(backupDirectory);
      store.publish(
          index.getIdentity(), lifecycle, backedUp, lifecycleState(backedUpState, 99, null));
      storage.restoreFromBackup(backupDirectory, null);
      manager.reload(session);

      assertEquals(storageIdentity, storage.getStorageIdentity());
      assertNotEquals(lineageIdentity, storage.getStorageLineageIdentity());
      assertThrows(IllegalStateException.class, firstHolder::get);
      var restoredIndex = manager.getIndex("OUser.name");
      var restored = storage.getIndexLifecycle(restoredIndex.getIdentity()).snapshot();
      assertNotSame(firstHolder, storage.getIndexLifecycle(restoredIndex.getIdentity()));
      assertEquals(IndexLifecycle.EXISTS, restored.lifecycle());
      assertEquals(31, restored.buildState().completedUnits());
      assertEquals(null, restored.buildState().ownerEpoch());
      var durable = store.read(restoredIndex.getIdentity(), lifecycle);
      assertEquals(31, durable.buildState().completedUnits());
      assertEquals(Long.valueOf(77), durable.buildState().ownerEpoch());
      assertEquals(backedUp.recordVersion(), durable.recordVersion());
      assertEquals(
          storage.getStorageLineageIdentity(),
          authority().readActiveRequired().lineageIdentity());
    }
  }

  private static IndexBuildState lifecycleState(
      IndexBuildState state, long completedUnits, Long ownerEpoch) {
    return new IndexBuildState(
        state.formatVersion(),
        state.descriptorIdentity(),
        state.lifecycle(),
        state.buildIncarnation(),
        ownerEpoch,
        state.completionCut(),
        completedUnits,
        state.suspended(),
        state.failure(),
        state.failureMessage());
  }

  /** Activation wraps an authority read failure with the creation context. */
  @Test
  public void activationWrapsBootstrapAuthorityFailure() throws Exception {
    try (var youTrackDB = createDatabase();
        var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (DiskStorage) session.getStorage();
      deleteAuthorityFiles();
      var failure = assertThrows(
          StorageException.class,
          () -> storage.activateBootstrapSnapshot(
              "Cannot activate the storage bootstrap birth"));

      assertEquals(
          "Cannot activate the storage bootstrap birth\r\n\tDB Name=\"bootstrapWiring\"",
          failure.getMessage());
    }
  }

  /** Restore publication wraps an authority read failure with the restore context. */
  @Test
  public void lineageReplacementWrapsBootstrapAuthorityFailure() throws Exception {
    try (var youTrackDB = createDatabase();
        var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (DiskStorage) session.getStorage();
      deleteAuthorityFiles();
      var failure = assertThrows(StorageException.class, storage::beginLineageReplacement);

      assertEquals(
          "Cannot publish the storage restore authority\r\n\tDB Name=\"bootstrapWiring\"",
          failure.getMessage());
    }
  }

  /** Drop removes bootstrap authority and permits creating the same database name again. */
  @Test
  public void dropThenCreateWithSameNameSucceeds() throws Exception {
    try (var youTrackDB = createDatabase()) {
      youTrackDB.drop(DATABASE);
      assertFalse(Files.exists(directory.resolve(DATABASE)));

      youTrackDB.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      try (var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
        assertEquals(DATABASE, session.getDatabaseName());
      }
    }
  }

  /** Real creation reports a failed durable birth move and leaves no openable storage. */
  @Test
  public void failedBirthPublicationLeavesNoActiveAuthority() throws Exception {
    var storageDirectory = directory.resolve(DATABASE);
    try (var failedPublication = BootstrapMetadataTestSupport.failNextPublication();
        var youTrackDB = openManager()) {
      var failure = assertThrows(
          RuntimeException.class,
          () -> youTrackDB.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN));

      assertTrue(hasCauseMessage(failure, "Cannot publish the storage bootstrap birth"));
      assertTrue(hasCauseMessage(failure, "injected birth publication failure"));
      assertTrue(failedPublication.candidateObserved().get());
    }

    assertFalse(hasActiveAuthorityFile(storageDirectory));
    try (var youTrackDB = openManager()) {
      assertThrows(RuntimeException.class, () -> youTrackDB.open(DATABASE, ADMIN, ADMIN));
    }
  }

  /** Runs the abrupt-stop half of one crash test named by the first argument. */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 3) {
      throw new IllegalArgumentException("Expected a child mode, root path, and handshake path");
    }
    var root = Path.of(arguments[1]);
    var handshake = Path.of(arguments[2]);
    switch (arguments[0]) {
      case CRASH_CHILD_ARGUMENT -> restoreAndHalt(root, handshake);
      case BIRTH_CHILD_ARGUMENT -> birthAndHalt(root, handshake);
      case DIRTY_CHILD_ARGUMENT -> commitAndHalt(root, handshake);
      case COMMIT_RACE_CHILD_ARGUMENT -> commitRaceAndHalt(root, handshake);
      case REGISTRY_CHILD_ARGUMENT -> registryAndHalt(root, handshake);
      default -> throw new IllegalArgumentException("Unknown child mode " + arguments[0]);
    }
  }

  /** Creates a database through the normal genesis path and halts without closing. */
  private static void birthAndHalt(Path root, Path handshake) throws Exception {
    var manager = (YouTrackDBImpl) YourTracks.instance(root.toString());
    manager.create(DATABASE, DatabaseType.DISK);
    var storage = ((YouTrackDBInternalEmbedded) manager.internal).getStorage(DATABASE);
    reportMark(handshake, storage.getIdGen().getLastId());
    Runtime.getRuntime().halt(0);
  }

  /** Restores a fresh target, reports the post-restore mark, and halts. */
  private static void restoreAndHalt(Path root, Path handshake) throws Exception {
    var backup = Files.createDirectory(root.resolve("crash-backup"));

    try (var sourceManager = (YouTrackDBImpl) YourTracks.instance(root.toString())) {
      sourceManager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      try (var source = sourceManager.open(DATABASE, ADMIN, ADMIN)) {
        ((AbstractStorage) source.getStorage()).backup(backup);
      }
    }

    DiskStorage.setRestoreBarrierActionForTesting(storage -> storage.getIdGen().nextId());
    var restoredManager = (YouTrackDBImpl) YourTracks.instance(root.toString());
    restoredManager.restore(RESTORED_DATABASE, backup.toString());
    var restored =
        ((YouTrackDBInternalEmbedded) restoredManager.internal).getStorage(RESTORED_DATABASE);
    var highestIssued = restored.getIdGen().getLastId();
    reportMark(handshake, highestIssued);
    Runtime.getRuntime().halt(0);
  }

  /**
   * Commits transactions, reports the mark reached before its last commits, and halts.
   *
   * <p>The manager stays open on purpose, so the halt leaves a dirty image for recovery. The
   * explicit log flush makes the last commits readable by the parent process. A checkpoint would
   * clear the dirty marker instead and would remove the recovery this child prepares.
   */
  private static void commitAndHalt(Path root, Path handshake) throws Exception {
    var manager = (YouTrackDBImpl) YourTracks.instance(root.toString());
    manager.create(DIRTY_DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    var session = manager.open(DIRTY_DATABASE, ADMIN, ADMIN);
    var storage = (AbstractStorage) session.getStorage();
    session.getMetadata().getSchema().createClass(DIRTY_CLASS);

    commitEntities(session, DIRTY_COMMITS_BEFORE_REPORT);
    var reportedMark = storage.getIdGen().getLastId();
    commitEntities(session, DIRTY_COMMITS_AFTER_REPORT);
    storage.getWALInstance().flush();

    reportMark(handshake, reportedMark);
    Runtime.getRuntime().halt(0);
  }

  /** Leaves two complete file-creation WAL units whose physical application requires replay. */
  private static void registryAndHalt(Path root, Path handshake) throws Exception {
    var manager = (YouTrackDBImpl) YourTracks.instance(root.toString());
    manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    var session = manager.open(DATABASE, ADMIN, ADMIN);
    var storage = (AbstractStorage) session.getStorage();
    session.getMetadata().getSchema().createClass("RegistryReplay");
    var cache = storage.getWriteCache();
    var wal = storage.getWALInstance();
    for (var name : new String[] {"replayFirst.tst", "replayLater.tst"}) {
      var operationId = storage.getIdGen().nextId();
      var fileId = cache.bookFileId(name);
      wal.log(new AtomicUnitStartRecord(false, operationId));
      wal.log(new FileCreatedWALRecord(operationId, name, fileId));
      wal.log(new AtomicUnitEndRecord(operationId, false, null));
    }
    wal.flush();
    reportMark(handshake, storage.getIdGen().getLastId());
    Runtime.getRuntime().halt(0);
  }

  /** Pause a prepared commit, checkpoint its old mark, then halt after its later timestamp. */
  private static void commitRaceAndHalt(Path root, Path handshake) throws Exception {
    var manager = (YouTrackDBImpl) YourTracks.instance(root.toString());
    manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    var setup = manager.open(DATABASE, ADMIN, ADMIN);
    setup.getMetadata().getSchema().createClass("WindowCommit");
    var writer = manager.open(DATABASE, ADMIN, ADMIN);
    var storage = (DiskStorage) setup.getStorage();
    var prepared = new CountDownLatch(1);
    var proceed = new CountDownLatch(1);
    storage.setBeforeCommitApplyActionForTesting(() -> {
      prepared.countDown();
      await(proceed);
    });
    var commit = CompletableFuture.runAsync(() -> {
      writer.begin();
      writer.newEntity("WindowCommit").setProperty("payload", "raced");
      writer.commit();
    });
    try {
      if (!prepared.await(15, TimeUnit.SECONDS)) {
        throw new AssertionError("commit did not reach the pre-admission pause");
      }
      storage.synch();
      if (durableDirty(storage)) {
        throw new AssertionError("checkpoint did not clear the indication");
      }
      // Widen the distance from the checkpoint floor so a skipped replay cannot pass by chance.
      for (var i = 0; i < 1_000; i++) {
        storage.getIdGen().nextId();
      }
    } finally {
      proceed.countDown();
    }
    commit.get(15, TimeUnit.SECONDS);
    var committedMark = storage.getIdGen().getLastId();
    storage.getWALInstance().flush();
    reportMark(handshake, committedMark);
    Runtime.getRuntime().halt(0);
  }

  private static void commitEntities(DatabaseSessionEmbedded session, int count) {
    for (var index = 0; index < count; index++) {
      session.begin();
      var entity = (EntityImpl) session.newEntity(DIRTY_CLASS);
      entity.setProperty("payload", index);
      session.commit();
    }
  }

  private static void reportMark(Path handshake, long mark) throws Exception {
    Files.writeString(
        handshake,
        Long.toString(mark),
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
    try (var channel = FileChannel.open(handshake, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  /**
   * Runs one crash child to completion and fails the calling test on any child problem.
   *
   * <p>The method drains child output on another thread, so a full pipe buffer cannot block the
   * child. The timeout path terminates the child forcibly and waits for that termination.
   */
  private void runCrashChild(String mode, Path handshake) throws Exception {
    var process = startCrashChild(mode, directory, handshake);
    var outputRead =
        CompletableFuture.supplyAsync(
            () -> {
              try {
                return process.getInputStream().readAllBytes();
              } catch (Exception failure) {
                throw new RuntimeException(failure);
              }
            });
    try {
      assertTrue(mode + " child timed out", process.waitFor(90, TimeUnit.SECONDS));
      var output = new String(outputRead.get(10, TimeUnit.SECONDS));
      assertEquals(mode + " child failed: " + output, 0, process.exitValue());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      assertTrue(mode + " child did not terminate", process.waitFor(10, TimeUnit.SECONDS));
    }
  }

  private static Process startCrashChild(String mode, Path root, Path handshake) throws Exception {
    var javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var testClasspath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    return new ProcessBuilder(
        javaExecutable,
        "-cp",
        testClasspath,
        DiskStorageBootstrapWiringTest.class.getName(),
        mode,
        root.toString(),
        handshake.toString())
        .redirectErrorStream(true)
        .start();
  }

  /** Creates a second disk image with the same name to exercise path-scoped open hooks. */
  private Path createOtherStorageWithSameName() throws IOException {
    var otherRoot = Files.createDirectory(directory.resolve("other-root"));
    try (var other = (YouTrackDBImpl) YourTracks.instance(otherRoot.toString())) {
      other.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    }
    return otherRoot;
  }

  private YouTrackDBImpl createDatabase() {
    var youTrackDB = openManager();
    youTrackDB.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    return youTrackDB;
  }

  private YouTrackDBImpl openManager() {
    return (YouTrackDBImpl) YourTracks.instance(directory.toString());
  }

  private StorageBootstrapMetadata authority() throws Exception {
    return new StorageBootstrapMetadata(
        directory.resolve(DATABASE), new FeatureFormatIdentity(1));
  }

  private StorageBootstrapMetadata restoredAuthority() throws Exception {
    return new StorageBootstrapMetadata(
        directory.resolve(RESTORED_DATABASE), new FeatureFormatIdentity(1));
  }

  private StorageBootstrapMetadata dirtyAuthority() throws Exception {
    return new StorageBootstrapMetadata(
        directory.resolve(DIRTY_DATABASE), new FeatureFormatIdentity(1));
  }

  private void deleteAuthorityFiles() throws Exception {
    try (var paths = Files.list(directory.resolve(DATABASE))) {
      for (var path : paths.filter(
          candidate -> candidate.getFileName().toString().startsWith("storage-bootstrap-"))
          .toList()) {
        Files.delete(path);
      }
    }
  }

  private static boolean hasCauseMessage(Throwable failure, String expectedMessage) {
    for (var current = failure; current != null; current = current.getCause()) {
      if (current.getMessage() != null && current.getMessage().contains(expectedMessage)) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasActiveAuthorityFile(Path storageDirectory) throws Exception {
    if (!Files.exists(storageDirectory)) {
      return false;
    }
    try (var paths = Files.list(storageDirectory)) {
      return paths.anyMatch(path -> path.getFileName().toString().matches(
          "storage-bootstrap-[0-2]\\.bsm"));
    }
  }
}
