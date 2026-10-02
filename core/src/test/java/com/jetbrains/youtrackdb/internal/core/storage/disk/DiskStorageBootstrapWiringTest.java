package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.YouTrackDBConstants;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexBuildState;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycle;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.BootstrapMetadataTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.FeatureFormatIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.LogicalSequenceFloor;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageBootstrapMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageLineageIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
  private static final String DIRTY_CHILD_ARGUMENT = "commit-and-halt";
  private static final String COMMIT_RACE_CHILD_ARGUMENT = "commit-race-and-halt";
  private static final String DIRTY_CLASS = "DirtyWork";

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
        assertFalse("checkpoint must clear the pre-admission indication", durableDirty());
      } finally {
        proceed.countDown();
      }
      commit.get(15, TimeUnit.SECONDS);
      assertTrue("admitted commit must re-mark after checkpoint", durableDirty());
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
          assertFalse("open must start with a clean indication", durableDirty());
          observedBefore.set(true);
          storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(() -> {
            assertTrue("open-time timestamp must follow the durable write", durableDirty());
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

  /** A failed open-time indication releases the startup metadata lock for a fresh open. */
  @Test(timeout = 60_000)
  public void failedOpenIndicationClosesMetadataAndPermitsRetry() throws Exception {
    try (var ignored = createDatabase()) {
      // The next open must perform its own atomic setup on a clean image.
    }
    var otherRoot = createOtherStorageWithSameName();
    DiskStorage.setOpenIndicationActionForTesting(directory.resolve(DATABASE), storage -> storage
        .failNextRecoveryIndicationForTesting(new IOException("open indication rejected")));
    try (var other = (YouTrackDBImpl) YourTracks.instance(otherRoot.toString());
        var unrelated = other.open(DATABASE, ADMIN, ADMIN);
        var manager = openManager()) {
      assertTrue("another storage must open without consuming the failure",
          unrelated.getStorage() != null);
      var failure = assertThrows(RuntimeException.class,
          () -> manager.open(DATABASE, ADMIN, ADMIN));
      assertTrue(hasCauseMessage(failure, "open indication rejected"));
      try (var retry = manager.open(DATABASE, ADMIN, ADMIN)) {
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
        assertFalse(durableDirty());
        session.rollback();
      } finally {
        Files.deleteIfExists(obstacle);
        Files.deleteIfExists(backup);
      }
      session.begin();
      session.newEntity("MetadataFailureCommit").setProperty("payload", "retry");
      session.commit();
      assertTrue(durableDirty());
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
      assertFalse("failed indication must not be treated as durable", durableDirty());
      session.rollback();
      session.begin();
      session.newEntity("RetryCommit").setProperty("payload", "second");
      session.commit();
      assertTrue(durableDirty());
    }
  }

  /** No operation may take a timestamp after a clear until the indication reaches disk. */
  @Test(timeout = 60_000)
  public void standaloneAtomicWriteMarksAfterCheckpointBeforeTimestamp() throws Exception {
    try (var manager = createDatabase();
        var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (DiskStorage) session.getStorage();
      storage.synch();
      assertFalse(durableDirty());
      storage.setBeforeRecoveryIndicationActionForTesting(
          () -> assertFalse("write must not yet have a durable indication", durableDirty()));
      assertThrows(IllegalStateException.class,
          () -> storage.setBeforeRecoveryIndicationActionForTesting(() -> {
          }));
      storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(
          () -> assertTrue("timestamp follows the durable indication", durableDirty()));
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(op -> {
        // The same entry point serves histogram, records GC, and open-time writers.
      });
      assertTrue(durableDirty());
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
      assertFalse(durableDirty());
      storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(
          () -> assertTrue("records GC must set indication before timestamp", durableDirty()));
      collection.collectDeadRecords(storage.getSharedSnapshotIndex());
      assertTrue(durableDirty());
      storage.checkErrorState();
    }
  }

  private boolean durableDirty() {
    try {
      return Files.readAllBytes(directory.resolve(DATABASE).resolve("dirty.fl"))[12] != 0;
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
      case DIRTY_CHILD_ARGUMENT -> commitAndHalt(root, handshake);
      case COMMIT_RACE_CHILD_ARGUMENT -> commitRaceAndHalt(root, handshake);
      default -> throw new IllegalArgumentException("Unknown child mode " + arguments[0]);
    }
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
      if (Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl"))[12] != 0) {
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
