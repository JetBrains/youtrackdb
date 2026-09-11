package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.exception.GenesisIncompleteException;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.StorageBirthTestSupport;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.FeatureFormatIdentity;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageAdmissionException;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageBootstrapMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.memory.DirectMemoryStorage;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the delayed publication of the active lifecycle state of Track 24.
 *
 * <p>Storage birth is the creation of a new storage image. Genesis is the creation of the initial
 * database metadata. Genesis creates the schema, the index manager, the index statistics, and the
 * default users. Storage admission is the decision that accepts an existing storage image for use.
 *
 * <p>Each test of this class states one scenario and one expected outcome in its own comment.
 */
public class StorageBirthActivationTest {

  private static final String ADMIN = "admin";
  private static final String PASSWORD = "adminpwd";
  private static final String LOCK_FILE_NAME = "storage-bootstrap.bsml";

  private Path directory;

  @Before
  public void createDirectory() throws Exception {
    directory = Files.createTempDirectory("storage-birth-activation-");
  }

  @After
  public void deleteDirectory() {
    FileUtils.deleteRecursively(directory.toFile());
  }

  /**
   * The active lifecycle state appears only after genesis finished.
   *
   * <p>The scenario probes storage admission from inside every genesis commit of one disk
   * creation. The expected outcome has three parts. Every probe during genesis reports the
   * interrupted-birth reason. The finished creation carries the active lifecycle state. A later
   * open of the same database succeeds.
   */
  @Test
  public void activeStateAppearsOnlyAfterGenesisFinished() throws Exception {
    var databaseName = "delayedActivation";
    var probe = new AdmissionProbeListener(directory.resolve(databaseName));

    try (var youTrackDB = openManager()) {
      youTrackDB.create(
          databaseName, DatabaseType.DISK, listenerConfig(probe), ADMIN, PASSWORD, ADMIN);

      // The recorded list holds one entry per commit of the creating session. The genesis
      // commits come first and must report the interrupted-birth reason. The user-creation
      // commit of the create call follows the activation and must report an accepted image.
      assertTrue(
          "genesis must run at least one commit that the probe can observe",
          probe.observedReasons.size() > 0);
      assertEquals(
          "the first genesis commit must run while the image is still inadmissible",
          StorageAdmissionException.Reason.INTERRUPTED_BIRTH,
          probe.observedReasons.get(0));
      var acceptedSeen = false;
      for (var reason : probe.observedReasons) {
        if (reason == null) {
          acceptedSeen = true;
          continue;
        }
        assertEquals(
            "a rejection must never follow the activation",
            false,
            acceptedSeen);
        assertEquals(
            "every probe before the activation must report the interrupted-birth reason",
            StorageAdmissionException.Reason.INTERRUPTED_BIRTH,
            reason);
      }
      assertEquals(
          "the finished creation must publish the active lifecycle state",
          StorageBootstrapMetadata.State.ACTIVE,
          authority(databaseName).readActiveRequired().state());
    }

    // A clean creation stays openable after the activation moved behind genesis.
    try (var youTrackDB = openManager();
        var session = youTrackDB.open(databaseName, ADMIN, PASSWORD)) {
      assertTrue(session.getMetadata().getSchema().existsClass("OUser"));
    }
  }

  /**
   * A crash-like interruption between two genesis steps leaves an inadmissible image.
   *
   * <p>The scenario copies the storage directory during one genesis commit. That copy is the image
   * that a crash between two genesis steps leaves behind. The expected outcome has three parts.
   * The copy fails every open with the interrupted-birth reason. The copy stays visible to the
   * existence probe. A clean creation under another name still opens.
   */
  @Test
  public void interruptedGenesisImageStaysInadmissible() throws Exception {
    var crashName = "interruptedGenesis";
    createCrashCopy("genesisSource", crashName);

    try (var youTrackDB = openManager()) {
      assertTrue("the incomplete image must stay visible", youTrackDB.exists(crashName));
      var failure =
          assertThrows(RuntimeException.class, () -> youTrackDB.open(crashName, ADMIN, PASSWORD));
      assertEquals(
          "the incomplete image must be rejected with the interrupted-birth reason",
          StorageAdmissionException.Reason.INTERRUPTED_BIRTH,
          admissionReason(failure));

      // A second open repeats the same rejection, so the image stays permanently inadmissible.
      var secondFailure =
          assertThrows(RuntimeException.class, () -> youTrackDB.open(crashName, ADMIN, PASSWORD));
      assertEquals(
          StorageAdmissionException.Reason.INTERRUPTED_BIRTH, admissionReason(secondFailure));

      youTrackDB.create("cleanAfterCrash", DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
      try (var session = youTrackDB.open("cleanAfterCrash", ADMIN, PASSWORD)) {
        assertTrue(session.getMetadata().getSchema().existsClass("OUser"));
      }
    }
  }

  /**
   * Drop tolerates the interrupted-birth reason and deletes the incomplete image.
   *
   * <p>The scenario drops the copy of an interrupted genesis. The expected outcome has two parts.
   * The drop reports no failure. The image is gone afterwards.
   */
  @Test
  public void dropDeletesInterruptedBirthImageWithoutFailure() throws Exception {
    var crashName = "dropInterruptedBirth";
    createCrashCopy("dropSource", crashName);

    try (var youTrackDB = openManager()) {
      youTrackDB.drop(crashName);

      assertFalse("the incomplete image must be gone after the drop", youTrackDB.exists(crashName));
      assertEquals(
          "the drop must leave no bootstrap authority artifact behind",
          Set.of(),
          bootstrapArtifactNames(directory.resolve(crashName)));
    }
  }

  /**
   * Drop stays loud for an admission reason other than the interrupted birth.
   *
   * <p>The scenario replaces the authority lock file of a complete image with a directory. Storage
   * admission then reports the non-regular-authority-file reason. The expected outcome is a failed
   * drop that names that reason.
   */
  @Test
  public void dropStaysLoudForAnotherAdmissionReason() throws Exception {
    var databaseName = "loudDrop";
    try (var youTrackDB = openManager()) {
      youTrackDB.create(databaseName, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
    }
    var lockPath = directory.resolve(databaseName).resolve(LOCK_FILE_NAME);
    Files.deleteIfExists(lockPath);
    Files.createDirectory(lockPath);

    try (var youTrackDB = openManager()) {
      var failure = assertThrows(RuntimeException.class, () -> youTrackDB.drop(databaseName));

      assertEquals(
          "an unrelated admission reason must still reach the operator",
          StorageAdmissionException.Reason.NON_REGULAR_AUTHORITY_FILE,
          admissionReason(failure));
    }
  }

  /**
   * The create-if-absent path keeps its tailored explanation for an incomplete image.
   *
   * <p>The tolerant create is the create call that accepts an existing database. The scenario
   * calls the tolerant create over the copy of an interrupted genesis. The expected outcome has
   * five parts. The call fails with the genesis-incomplete refusal. The refusal names the
   * interrupted-birth reason. The refusal names the interrupted creation as one cause. The
   * refusal names damaged authority records of a complete database as the other cause. The
   * refusal offers a backup restore beside the drop, and the image stays droppable.
   *
   * <p>The last three parts matter, because damaged authority records of a complete database
   * reach the same refusal. A refusal that prescribes only the drop would then advise the
   * destruction of real data.
   */
  @Test
  public void createIfNotExistsReportsTailoredExplanationForInterruptedBirth() throws Exception {
    var crashName = "tolerantCreateInterruptedBirth";
    createCrashCopy("tolerantCreateSource", crashName);

    try (var youTrackDB = openManager()) {
      var internal = (YouTrackDBInternalEmbedded) youTrackDB.internal;
      try {
        youTrackDB.createIfNotExists(crashName, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
        fail("the tolerant create must refuse an image whose genesis never finished");
      } catch (RuntimeException failure) {
        GenesisIncompleteException refusal = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
          if (cause instanceof GenesisIncompleteException incomplete) {
            refusal = incomplete;
            break;
          }
        }
        assertNotNull("the refusal must be the tailored genesis-incomplete refusal", refusal);
        assertTrue(
            "the refusal must name the interrupted-birth reason, saw: " + refusal.getMessage(),
            refusal.getMessage().contains("interrupted-birth reason"));
        assertTrue(
            "the refusal must name the interrupted creation as one cause, saw: "
                + refusal.getMessage(),
            refusal.getMessage().contains("An interrupted creation produces this state."));
        assertTrue(
            "the refusal must name damaged authority records as the other cause, saw: "
                + refusal.getMessage(),
            refusal
                .getMessage()
                .contains("Damaged bootstrap authority records of a complete database"));
        assertTrue(
            "the refusal must offer a backup restore, saw: " + refusal.getMessage(),
            refusal.getMessage().contains("A backup restores a complete database."));
        assertTrue(
            "the refusal must still offer the drop, saw: " + refusal.getMessage(),
            refusal.getMessage().contains("A drop removes a database"));
      }

      assertEquals(
          "the refused image must leave no storage registration behind",
          null,
          internal.getStorage(crashName));
      youTrackDB.drop(crashName);
      assertFalse(youTrackDB.exists(crashName));
    }
  }

  /**
   * A refused open frees the storage identifier of the refused image.
   *
   * <p>Every storage carries one storage identifier, and the manager keeps every live identifier
   * in one static set. A refused open discards the storage, so the identifier of that storage
   * must return to the pool of free identifiers. The scenario refuses forty tolerant creations
   * over the copy of an interrupted genesis. The expected outcome is a growth of the identifier
   * set far below the number of refused creations.
   *
   * <p>The identifier set is static and the test runner runs four test classes at once, so a
   * database of another test class can enter and leave the set during this test. The assertion
   * therefore uses a generous noise bound instead of an exact count. A retained identifier per
   * refused creation would add forty entries, which the bound below still catches.
   */
  @Test
  public void refusedOpenFreesTheStorageIdentifier() throws Exception {
    var crashName = "identifierInterruptedBirth";
    var refusedCreations = 40;
    var concurrencyNoiseBound = 20;
    createCrashCopy("identifierSource", crashName);

    try (var youTrackDB = openManager()) {
      var identifierCountBefore = liveStorageIdentifierCount();

      for (var attempt = 0; attempt < refusedCreations; attempt++) {
        assertThrows(
            RuntimeException.class,
            () -> youTrackDB.createIfNotExists(
                crashName, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN));
      }

      var growth = liveStorageIdentifierCount() - identifierCountBefore;
      assertTrue(
          "every refused open must free the storage identifier of the refused image, saw a"
              + " growth of "
              + growth
              + " identifiers over "
              + refusedCreations
              + " refused creations",
          growth < concurrencyNoiseBound);
    }
  }

  /**
   * Returns the number of live storage identifiers of the embedded manager.
   *
   * <p>The set of live identifiers is private, so this helper reads that set by reflection. No
   * public accessor exists, and a leak of that set is only observable through its size.
   */
  @SuppressWarnings("unchecked")
  private static int liveStorageIdentifierCount() throws Exception {
    var identifierField =
        YouTrackDBInternalEmbedded.class.getDeclaredField("currentStorageIds");
    identifierField.setAccessible(true);
    return ((Set<Integer>) identifierField.get(null)).size();
  }

  /**
   * The barrier writes index histogram content of genesis before the activation.
   *
   * <p>An index histogram is index statistics data that the index engine keeps in a separate file.
   * The plain flush helper of the storage skips that data by contract, so only the wider barrier
   * writes that data out. The scenario inspects a freshly created image without any close and
   * copies that image. The expected outcome has three parts. Genesis produced at least one index
   * histogram file. Every such file carries content of a positive size. The copy holds the same
   * files with the same sizes.
   *
   * <p>This test proves no durability under power loss. The copy reads the files through the page
   * cache of the operating system, so the copy sees written bytes that no file synchronization
   * needs to have reached the storage medium. Power-loss certification is an explicit non-goal of
   * the Track 24 design.
   */
  @Test
  public void barrierWritesIndexHistogramContentAtCreation() throws Exception {
    var databaseName = "barrierSource";
    var copyName = "barrierCopy";

    try (var youTrackDB = openManager()) {
      // The creation without an extra user keeps the barrier as the last write act of the image.
      // The copy is taken while the manager stays open, so no close and no shutdown flush can
      // contribute. Every byte of the copy therefore comes from the barrier or from genesis.
      youTrackDB.create(databaseName, DatabaseType.DISK);
      copyDirectory(directory.resolve(databaseName), directory.resolve(copyName));
    }

    var originalHistograms = histogramFileSizes(directory.resolve(databaseName));
    var copiedHistograms = histogramFileSizes(directory.resolve(copyName));
    assertFalse(
        "genesis must create at least one index histogram file", originalHistograms.isEmpty());
    for (var entry : originalHistograms.entrySet()) {
      assertTrue(
          "the barrier must leave written content in index histogram file " + entry.getKey(),
          entry.getValue() > 0);
    }
    assertEquals(
        "the copy must carry every index histogram file of the original",
        originalHistograms,
        copiedHistograms);
  }

  /**
   * Memory storage keeps the default barrier and the default activation.
   *
   * <p>The scenario has two parts. The first part inspects the declared methods of the three
   * storage classes. The second part calls the birth completion of one memory storage a second
   * time, and calls the birth completion of one disk storage a second time.
   *
   * <p>The expected outcome has three parts. The memory storage class declares neither the barrier
   * nor the activation. The second call on the memory storage performs no work and reports no
   * failure, which proves that memory storage runs two inherited empty method bodies. The second
   * call on the disk storage fails, because the disk activation refuses an already active record.
   *
   * <p>This test proves the absence of an activation and the absence of any failing work. This
   * test proves no flush count, because a flush count needs a metric seam that no build offers.
   */
  @Test
  public void memoryStorageKeepsTheDefaultBarrier() throws Exception {
    assertNotNull(
        "the abstract storage must declare the barrier",
        AbstractStorage.class.getDeclaredMethod("barrierOverGenesisArtifacts"));
    assertNotNull(
        "disk storage must override the barrier",
        DiskStorage.class.getDeclaredMethod("barrierOverGenesisArtifacts"));
    assertThrows(
        NoSuchMethodException.class,
        () -> DirectMemoryStorage.class.getDeclaredMethod("barrierOverGenesisArtifacts"));
    assertThrows(
        NoSuchMethodException.class,
        () -> DirectMemoryStorage.class.getDeclaredMethod("activateStorageBirth"));

    try (var youTrackDB = openManager()) {
      youTrackDB.create("memoryBirth", DatabaseType.MEMORY, ADMIN, PASSWORD, ADMIN);
      youTrackDB.create("diskBirth", DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
      var internal = (YouTrackDBInternalEmbedded) youTrackDB.internal;
      var memoryStorage = internal.getStorage("memoryBirth");
      var diskStorage = internal.getStorage("diskBirth");
      assertNotNull("the memory database must hold one registered storage", memoryStorage);
      assertNotNull("the disk database must hold one registered storage", diskStorage);

      // Both hooks of the memory storage are inherited empty bodies, so a repeated call is safe.
      memoryStorage.completeStorageBirth();
      // The disk storage overrides both hooks, so the repeated activation refuses the record.
      assertThrows(RuntimeException.class, diskStorage::completeStorageBirth);

      try (var session = youTrackDB.open("memoryBirth", ADMIN, PASSWORD)) {
        assertTrue(session.getMetadata().getSchema().existsClass("OUser"));
      }
    }
  }

  /**
   * A crash after genesis and before the durability barrier leaves an inadmissible image.
   *
   * <p>This test covers crash point three of the design. The scenario copies the storage directory
   * at the boundary between the end of genesis and the start of the durability barrier. That copy
   * is the image that a crash at that boundary leaves behind. The copy reads the files through the
   * page cache of the operating system, so the copy proves no power-loss behavior. The expected
   * outcome has three parts. The copy stays visible to the existence probe. Every open of the copy reports the
   * interrupted-birth reason. A drop discards the copy and reports success.
   */
  @Test
  public void crashAfterGenesisAndBeforeTheBarrierStaysInadmissible() throws Exception {
    var crashName = "crashBeforeBarrier";
    createBirthBoundaryCopy("beforeBarrierSource", crashName, true);

    assertInadmissibleAndDroppable(crashName);
  }

  /**
   * A crash after the durability barrier and before the activation leaves an inadmissible image.
   *
   * <p>This test covers crash point four of the design. The scenario copies the storage directory
   * at the boundary between the end of the durability barrier and the start of the activation.
   * That copy holds complete genesis content under a birth record. The expected outcome has four
   * parts. The copy stays visible to the existence probe. Every open of the copy reports the
   * interrupted-birth reason. The copy carries written index histogram content, which proves that
   * the barrier ran before the copy. A drop discards the copy and reports success.
   *
   * <p>This test proves no durability under power loss. The copy runs inside the same process and
   * reads the files through the page cache of the operating system.
   */
  @Test
  public void crashAfterTheBarrierAndBeforeActivationStaysInadmissible() throws Exception {
    var crashName = "crashBeforeActivation";
    createBirthBoundaryCopy("beforeActivationSource", crashName, false);

    var histograms = histogramFileSizes(directory.resolve(crashName));
    assertFalse("the barrier must leave at least one index histogram file", histograms.isEmpty());
    for (var entry : histograms.entrySet()) {
      assertTrue(
          "the barrier must leave written content in index histogram file " + entry.getKey(),
          entry.getValue() > 0);
    }
    assertInadmissibleAndDroppable(crashName);
  }

  /**
   * A failed durability barrier keeps the new image out of the active lifecycle state.
   *
   * <p>The scenario moves the storage into the internal error state at the end of genesis. A
   * storage in that state flushes nothing, so the barrier cannot make genesis content durable. The
   * expected outcome has four parts. The creation reports a failure that names the durability
   * barrier. The creation never reaches the boundary after the barrier, so the activation never
   * runs. The creation publishes no active lifecycle state, so no accepted image survives. The
   * database name is free again, because the failed creation removed its own residue.
   */
  @Test
  public void barrierFailureKeepsTheImageOutOfTheActiveState() throws Exception {
    var databaseName = "barrierFailure";
    var reachedTheActivationBoundary = new java.util.concurrent.atomic.AtomicBoolean();

    try (var youTrackDB = openManager()) {
      try (var ignored =
          StorageBirthTestSupport.observeBirthCompletion(
              storage -> storage.moveToErrorStateIfNeeded(
                  new IllegalStateException("injected internal storage error")),
              storage -> reachedTheActivationBoundary.set(true))) {
        var failure =
            assertThrows(
                RuntimeException.class,
                () -> youTrackDB.create(databaseName, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN));

        assertTrue(
            "the failure must name the durability barrier, saw: " + messageChain(failure),
            messageChain(failure).contains("durability barrier"));
        assertFalse(
            "a failed barrier must stop the creation before the activation",
            reachedTheActivationBoundary.get());
      }

      assertFalse(
          "a failed barrier must leave no usable database", youTrackDB.exists(databaseName));
    }

    assertEquals(
        "a failed barrier must publish no active lifecycle state",
        Set.of(),
        bootstrapArtifactNames(directory.resolve(databaseName)));
  }

  /**
   * Creates one database and copies the storage directory at one birth completion boundary.
   *
   * @param beforeBarrier true copies after genesis and before the barrier, false copies after the
   *                      barrier and before the activation
   */
  private void createBirthBoundaryCopy(String sourceName, String copyName, boolean beforeBarrier)
      throws Exception {
    var sourceDirectory = directory.resolve(sourceName);
    var copyDirectory = directory.resolve(copyName);
    Consumer<AbstractStorage> copy =
        storage -> {
          try {
            copyDirectory(sourceDirectory, copyDirectory);
          } catch (IOException failure) {
            throw new UncheckedIOException(failure);
          }
        };
    Consumer<AbstractStorage> skip = storage -> {
    };

    try (var youTrackDB = openManager()) {
      try (var ignored =
          StorageBirthTestSupport.observeBirthCompletion(
              beforeBarrier ? copy : skip, beforeBarrier ? skip : copy)) {
        youTrackDB.create(sourceName, DatabaseType.DISK, ADMIN, PASSWORD, ADMIN);
      }
      youTrackDB.drop(sourceName);
    }
    assertTrue("the boundary copy must exist", Files.isDirectory(copyDirectory));
  }

  /** Asserts that one image stays visible, stays inadmissible, and stays droppable. */
  private void assertInadmissibleAndDroppable(String crashName) throws Exception {
    try (var youTrackDB = openManager()) {
      assertTrue("the incomplete image must stay visible", youTrackDB.exists(crashName));
      var failure =
          assertThrows(RuntimeException.class, () -> youTrackDB.open(crashName, ADMIN, PASSWORD));
      assertEquals(
          "the incomplete image must be rejected with the interrupted-birth reason",
          StorageAdmissionException.Reason.INTERRUPTED_BIRTH,
          admissionReason(failure));

      var secondFailure =
          assertThrows(RuntimeException.class, () -> youTrackDB.open(crashName, ADMIN, PASSWORD));
      assertEquals(
          "the second open must repeat the same rejection",
          StorageAdmissionException.Reason.INTERRUPTED_BIRTH,
          admissionReason(secondFailure));

      youTrackDB.drop(crashName);
      assertFalse("the drop must discard the incomplete image", youTrackDB.exists(crashName));
    }
  }

  /** Joins every message of one failure chain. */
  private static String messageChain(Throwable failure) {
    var message = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      message.append(cause.getMessage()).append(' ');
    }
    return message.toString();
  }

  /**
   * Creates one database and copies the storage directory during one genesis commit.
   *
   * <p>The copy is the image shape that a crash between two genesis steps leaves behind.
   */
  private void createCrashCopy(String sourceName, String copyName) {
    var copier =
        new CrashCopyListener(directory.resolve(sourceName), directory.resolve(copyName));
    try (var youTrackDB = openManager()) {
      youTrackDB.create(
          sourceName, DatabaseType.DISK, listenerConfig(copier), ADMIN, PASSWORD, ADMIN);
      youTrackDB.drop(sourceName);
    }
    assertTrue("the crash copy must exist", Files.isDirectory(directory.resolve(copyName)));
  }

  /** Probes storage admission from inside every genesis commit and records each reason. */
  private static final class AdmissionProbeListener implements SessionListener {

    private final Path storageDirectory;
    private final List<StorageAdmissionException.Reason> observedReasons = new ArrayList<>();

    private AdmissionProbeListener(Path storageDirectory) {
      this.storageDirectory = storageDirectory;
    }

    @Override
    public void onBeforeTxCommit(Transaction transaction) {
      try {
        new StorageBootstrapMetadata(storageDirectory, new FeatureFormatIdentity(1))
            .readActiveRequired();
        observedReasons.add(null);
      } catch (StorageAdmissionException rejection) {
        observedReasons.add(rejection.reason());
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
  }

  /** Copies the storage directory once, during the first genesis commit. */
  private static final class CrashCopyListener implements SessionListener {

    private final Path storageDirectory;
    private final Path copyDirectory;
    private boolean copied;

    private CrashCopyListener(Path storageDirectory, Path copyDirectory) {
      this.storageDirectory = storageDirectory;
      this.copyDirectory = copyDirectory;
    }

    @Override
    public void onBeforeTxCommit(Transaction transaction) {
      if (copied) {
        return;
      }
      copied = true;
      try {
        copyDirectory(storageDirectory, copyDirectory);
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }
  }

  private static YouTrackDBConfig listenerConfig(SessionListener listener) {
    return YouTrackDBConfig.builder().addSessionListener(listener).build();
  }

  private YouTrackDBImpl openManager() {
    return (YouTrackDBImpl) YourTracks.instance(directory.toString());
  }

  private StorageBootstrapMetadata authority(String databaseName) throws IOException {
    return new StorageBootstrapMetadata(
        directory.resolve(databaseName), new FeatureFormatIdentity(1));
  }

  private static StorageAdmissionException.Reason admissionReason(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof StorageAdmissionException admission) {
        return admission.reason();
      }
    }
    return null;
  }

  private static Set<String> bootstrapArtifactNames(Path storageDirectory) throws IOException {
    if (!Files.isDirectory(storageDirectory)) {
      return Set.of();
    }
    try (var paths = Files.list(storageDirectory)) {
      return paths
          .map(path -> path.getFileName().toString())
          .filter(StorageBootstrapMetadata::isBootstrapArtifactName)
          .collect(Collectors.toCollection(TreeSet::new));
    }
  }

  private static Map<String, Long> histogramFileSizes(Path storageDirectory) throws IOException {
    var sizes = new TreeMap<String, Long>();
    try (var paths = Files.list(storageDirectory)) {
      for (var path : paths.toList()) {
        var fileName = path.getFileName().toString();
        if (fileName.endsWith(".ixs")) {
          sizes.put(fileName, Files.size(path));
        }
      }
    }
    return sizes;
  }

  private static void copyDirectory(Path source, Path target) throws IOException {
    try (var paths = Files.walk(source)) {
      for (var path : paths.toList()) {
        var destination = target.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(destination);
        } else {
          Files.createDirectories(destination.getParent());
          Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}
