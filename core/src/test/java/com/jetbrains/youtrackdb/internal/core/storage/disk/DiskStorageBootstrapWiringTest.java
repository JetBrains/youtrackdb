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
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
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

  /** Open rejects a disk storage whose authority files are absent. */
  @Test
  public void openRejectsAbsentAuthority() throws Exception {
    try (var ignored = createDatabase()) {
      // Closing the manager leaves a complete disk image for the open attempt.
    }
    deleteAuthorityFiles();

    try (var youTrackDB = openManager()) {
      assertThrows(RuntimeException.class, () -> youTrackDB.open(DATABASE, ADMIN, ADMIN));
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

  /** A real restore rotates lineage and removes every registered stale lifecycle holder. */
  @Test
  public void restoreRotatesLineageAndDropsStaleLifecycleHolders() throws Exception {
    var backupDirectory = Files.createDirectory(directory.resolve("backup"));
    try (var youTrackDB = createDatabase();
        var session = youTrackDB.open(DATABASE, ADMIN, ADMIN)) {
      var storage = (AbstractStorage) session.getStorage();
      var firstDescriptor = new RecordId(42, 1);
      var secondDescriptor = new RecordId(42, 2);
      var firstHolder = storage.getOrCreateIndexLifecycle(firstDescriptor);
      var secondHolder = storage.getOrCreateIndexLifecycle(secondDescriptor);
      assertEquals(IndexLifecycle.EXISTS, firstHolder.get());
      assertEquals(IndexLifecycle.EXISTS, secondHolder.get());

      var storageIdentity = storage.getStorageIdentity();
      var lineageIdentity = storage.getStorageLineageIdentity();
      storage.backup(backupDirectory);
      storage.restoreFromBackup(backupDirectory, null);

      assertEquals(storageIdentity, storage.getStorageIdentity());
      assertNotEquals(lineageIdentity, storage.getStorageLineageIdentity());
      assertThrows(IllegalStateException.class, firstHolder::get);
      assertThrows(IllegalStateException.class, secondHolder::get);
      var currentFirstHolder = storage.getOrCreateIndexLifecycle(firstDescriptor);
      var currentSecondHolder = storage.getOrCreateIndexLifecycle(secondDescriptor);
      assertNotSame(firstHolder, currentFirstHolder);
      assertNotSame(secondHolder, currentSecondHolder);
      assertEquals(IndexLifecycle.EXISTS, currentFirstHolder.get());
      assertEquals(IndexLifecycle.EXISTS, currentSecondHolder.get());
      assertEquals(storage.getStorageLineageIdentity(), authority().readActiveRequired()
          .lineageIdentity());
    }
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
