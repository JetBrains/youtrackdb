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
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexBuildState;
import com.jetbrains.youtrackdb.internal.core.index.lifecycle.IndexLifecycle;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.BootstrapMetadataTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.FeatureFormatIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageBootstrapMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageLineageIdentity;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Covers disk storage bootstrap creation and open wiring. */
public class DiskStorageBootstrapWiringTest {

  private static final String DATABASE = "bootstrapWiring";
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
