package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.SharedContext;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternalEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.InconsistentStorageMetadataException;
import com.jetbrains.youtrackdb.internal.core.storage.config.CollectionBasedStorageConfiguration;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.FeatureFormatIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageAdmissionException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageBootstrapMetadata;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the two later consistency checks of Track 24.
 *
 * <p>A storage is one physical database image on disk. Storage admission is the decision that
 * accepts an existing storage image for use. Admission runs before recovery. Recovery is the
 * replay of write-ahead log content into the storage files.
 *
 * <p>A consistency check is a later check that compares two durable sources without deciding
 * admission. The first later check compares the storage layout version of the storage
 * configuration against the storage layout version of the bootstrap authority record. The second
 * later check compares the accepted lifecycle state against the genesis marker. Genesis is the
 * creation of the initial database metadata. The genesis marker is the durable value that records
 * a finished genesis.
 *
 * <p>Each test of this class states one scenario and one expected outcome in its own comment.
 */
public class MetadataConsistencyCheckTest {

  private static final String ADMIN = "admin";
  private static final String PASSWORD = "adminpwd";
  private static final FeatureFormatIdentity FEATURE_FORMAT = new FeatureFormatIdentity(1);

  private Path root;
  private Path databasesPath;
  private YouTrackDBImpl youTrackDB;

  @Before
  public void createDirectories() throws Exception {
    root = Files.createTempDirectory("metadata-consistency-");
    databasesPath = Files.createDirectories(root.resolve("databases"));
    youTrackDB = openContext();
  }

  @After
  public void deleteDirectories() {
    if (youTrackDB != null && youTrackDB.isOpen()) {
      youTrackDB.close();
    }
    FileUtils.deleteRecursively(root.toFile());
  }

  /**
   * Scenario: the storage configuration of an accepted image reports a storage layout version of
   * an earlier build, while the bootstrap authority record reports the supported version.
   *
   * <p>Expected outcome has three parts. The open reports the named inconsistent-metadata result
   * with the storage layout version value. The open leaves no registered storage, so the storage
   * stays closed for the caller. The bootstrap authority record still holds the active lifecycle
   * state, so the check never reverses the admission decision.
   */
  @Test
  public void layoutVersionInconsistencyKeepsTheStorageClosedAndKeepsAdmissionAccepting()
      throws Exception {
    var databaseName = "layoutInconsistent";
    createDatabaseWithTamperedLayoutVersion(databaseName, 23);

    var failure = openAndExpectFailure(databaseName);
    assertEquals("the check must name the storage layout version source",
        InconsistentStorageMetadataException.Inconsistency.STORAGE_LAYOUT_VERSION,
        inconsistencyOf(failure).inconsistency());
    assertNull("the refused image must leave no registered storage",
        internal().getStorage(databaseName));
    assertNull("the admission decision must stay an acceptance",
        admissionReasonOf(databaseName));

    // A second open repeats the same result, which proves that the first report changed nothing.
    var secondFailure = openAndExpectFailure(databaseName);
    assertEquals("the repeated open must report the same result",
        InconsistentStorageMetadataException.Inconsistency.STORAGE_LAYOUT_VERSION,
        inconsistencyOf(secondFailure).inconsistency());
  }

  /**
   * Scenario: an accepted image carries no genesis marker, while the bootstrap authority record
   * holds the active lifecycle state.
   *
   * <p>Expected outcome has three parts. The open reports the named inconsistent-metadata result
   * with the genesis marker value. The open leaves no registered storage. The bootstrap authority
   * record still holds the active lifecycle state.
   */
  @Test
  public void genesisMarkerInconsistencyKeepsTheStorageClosedAndKeepsAdmissionAccepting() {
    var databaseName = "genesisInconsistent";
    createDatabaseWithoutGenesisMarker(databaseName);

    var failure = openAndExpectFailure(databaseName);
    assertEquals("the check must name the genesis marker source",
        InconsistentStorageMetadataException.Inconsistency.GENESIS_MARKER,
        inconsistencyOf(failure).inconsistency());
    assertTrue("the message must name both disagreeing sources",
        messageChainOf(failure).contains("reports the active lifecycle state")
            && messageChainOf(failure).contains("genesis-completion marker"));
    assertNull("the refused image must leave no registered storage",
        internal().getStorage(databaseName));
    assertNull("the admission decision must stay an acceptance",
        admissionReasonOf(databaseName));
  }

  /**
   * Scenario: an operator drops an image whose genesis marker is absent, and an operator drops an
   * image whose storage layout version disagrees with the bootstrap authority record.
   *
   * <p>Expected outcome: the drop of the genesis marker case reports success, because the drop
   * tolerates that result. The drop of the storage layout version case stays loud and still
   * deletes the image, because an operator must see a layout version mismatch.
   */
  @Test
  public void dropToleratesTheGenesisMarkerResultAndStaysLoudForTheLayoutVersionResult()
      throws Exception {
    var markerLess = "genesisDiscard";
    createDatabaseWithoutGenesisMarker(markerLess);
    youTrackDB.drop(markerLess);
    assertFalse("the drop must discard the marker-less image", youTrackDB.exists(markerLess));

    var oldLayout = "layoutDiscard";
    createDatabaseWithTamperedLayoutVersion(oldLayout, 23);
    try {
      youTrackDB.drop(oldLayout);
      fail("the drop must stay loud for the storage layout version result");
    } catch (RuntimeException expected) {
      assertEquals("the loud drop must carry the storage layout version value",
          InconsistentStorageMetadataException.Inconsistency.STORAGE_LAYOUT_VERSION,
          inconsistencyOf(expected).inconsistency());
    }
    assertFalse("the loud drop must still delete the image", youTrackDB.exists(oldLayout));
  }

  /** Creates a disk database and rewrites the storage layout version of its configuration. */
  private void createDatabaseWithTamperedLayoutVersion(String databaseName, int layoutVersion)
      throws Exception {
    youTrackDB.create(databaseName, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
    try (var session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, ADMIN, PASSWORD)) {
      var storage = (AbstractStorage) session.getStorage();
      var configuration = (CollectionBasedStorageConfiguration) storage.configuration;
      storage.getAtomicOperationsManager().executeInsideAtomicOperation(
          operation -> configuration.updateVersionForTesting(operation, layoutVersion));
    }
    reopenContext();
  }

  /** Creates a disk database and removes the genesis marker of that database. */
  private void createDatabaseWithoutGenesisMarker(String databaseName) {
    youTrackDB.create(databaseName, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
    internal().getStorage(databaseName)
        .setProperty(SharedContext.GENESIS_COMPLETED_PROPERTY, "false");
    reopenContext();
  }

  /** Opens one database and returns the failure of that open. */
  private RuntimeException openAndExpectFailure(String databaseName) {
    try {
      youTrackDB.open(databaseName, ADMIN, PASSWORD).close();
      throw new AssertionError("the open of database " + databaseName + " must fail");
    } catch (RuntimeException failure) {
      return failure;
    }
  }

  /** Returns the named inconsistent-metadata result inside one failure chain. */
  private static InconsistentStorageMetadataException inconsistencyOf(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof InconsistentStorageMetadataException result) {
        return result;
      }
    }
    throw new AssertionError("the failure must carry the inconsistent-metadata result", failure);
  }

  /** Joins every message of one failure chain. */
  private static String messageChainOf(Throwable failure) {
    var builder = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      builder.append(cause.getMessage()).append('\n');
    }
    return builder.toString();
  }

  /** Returns the admission reason of one database directory, or null for an accepted image. */
  private StorageAdmissionException.Reason admissionReasonOf(String databaseName) {
    try {
      new StorageBootstrapMetadata(databasesPath.resolve(databaseName), FEATURE_FORMAT)
          .readActiveRequired();
      return null;
    } catch (StorageAdmissionException rejection) {
      return rejection.reason();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private YouTrackDBInternalEmbedded internal() {
    return (YouTrackDBInternalEmbedded) youTrackDB.internal;
  }

  private YouTrackDBImpl openContext() {
    var context = (YouTrackDBImpl) YourTracks.instance(databasesPath.toString());
    assertNotNull("the test needs one open context", context);
    return context;
  }

  /** Closes the context and opens a fresh context, so the next open reads every value again. */
  private void reopenContext() {
    youTrackDB.close();
    youTrackDB = openContext();
  }
}
