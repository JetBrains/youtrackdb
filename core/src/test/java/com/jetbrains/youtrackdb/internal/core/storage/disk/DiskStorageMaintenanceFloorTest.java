package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Durable floor ordering for both background WAL segment removal paths. */
public class DiskStorageMaintenanceFloorTest {

  private static final String DATABASE = "maintenanceFloor";
  private static final String ADMIN = "admin";
  private Path root;
  private AbstractStorage hookedStorage;

  @Before
  public void createDirectory() throws IOException {
    root = Files.createTempDirectory("maintenance-floor-");
  }

  @After
  public void removeDirectory() {
    if (hookedStorage != null) {
      hookedStorage.clearCheckpointActionsForTesting();
      ((DiskStorage) hookedStorage).clearFloorActionsForTesting();
    }
    FileUtils.deleteRecursively(root.toFile());
  }

  /** A fuzzy checkpoint cuts a segment only after its floor is on disk and survives a halt. */
  @Test
  public void fuzzyCheckpointPublishesDurableFloorBeforeAbruptStop() throws Exception {
    assertRestartAboveChildTimestamp("fuzzy");
  }

  /** WAL vacuum cuts a segment only after its floor is on disk and survives a halt. */
  @Test
  public void walVacuumPublishesDurableFloorBeforeAbruptStop() throws Exception {
    assertRestartAboveChildTimestamp("vacuum");
  }

  private void assertRestartAboveChildTimestamp(String mode) throws Exception {
    var issued = crashChild(mode);
    assertTrue("floor read from disk must cover the value observed before removal",
        readFloor() >= issued);
    try (var manager = manager(root); var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue(((AbstractStorage) session.getStorage()).getIdGen().getLastId() > issued);
    }
  }

  /** A fuzzy checkpoint reads the floor after its removal guard, including a newly issued ID. */
  @Test
  public void fuzzyFloorReadIncludesIdentifierIssuedAfterBoundaryDecision() throws Exception {
    assertFloorReadIncludesLateIdentifier(false);
  }

  /** WAL vacuum reads the floor after its removal guard, including a newly issued ID. */
  @Test
  public void vacuumFloorReadIncludesIdentifierIssuedAfterBoundaryDecision() throws Exception {
    assertFloorReadIncludesLateIdentifier(true);
  }

  private void assertFloorReadIncludesLateIdentifier(boolean vacuum) throws Exception {
    try (var manager = manager(root)) {
      manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      try (var session = manager.open(DATABASE, ADMIN, ADMIN)) {
        var storage = (DiskStorage) session.getStorage();
        hookedStorage = storage;
        prepareRemovableSegment(session, storage);
        var original = storage.getWALInstance().begin().getSegment();
        var before = storage.getIdGen().getLastId();
        var issuedAfterBoundary = new AtomicLong(-1);
        // Issuing an ID directly cannot wait for a writer lock held by maintenance.
        storage.setBeforeMaintenanceFloorReadActionForTesting(
            () -> issuedAfterBoundary.set(storage.getIdGen().nextId()));
        if (vacuum) {
          runVacuum(storage);
        } else {
          storage.makeFuzzyCheckpoint();
        }
        assertTrue("the pass must reach its post-boundary observation",
            issuedAfterBoundary.get() > before);
        assertTrue("the pass must remove WAL segments",
            storage.getWALInstance().begin().getSegment() > original);
        assertTrue("the durable floor must include an ID issued after the boundary decision",
            readFloor() >= issuedAfterBoundary.get());
      }
    }
  }

  /** A failed fuzzy floor save retains the old segment and reports an ERROR before a retry. */
  @Test
  public void fuzzyFloorFailureRetainsSegmentAndRetriesWithoutErrorState() throws Exception {
    assertFloorFailureAndRetry(false);
  }

  /** A failed vacuum floor save retains the old segment and reports an ERROR before a retry. */
  @Test
  public void vacuumFloorFailureRetainsSegmentAndRetriesWithoutErrorState() throws Exception {
    assertFloorFailureAndRetry(true);
  }

  private void assertFloorFailureAndRetry(boolean vacuum) throws Exception {
    try (var manager = manager(root)) {
      manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      try (var session = manager.open(DATABASE, ADMIN, ADMIN)) {
        var storage = (DiskStorage) session.getStorage();
        hookedStorage = storage;
        prepareRemovableSegment(session, storage);
        var original = storage.getWALInstance().begin().getSegment();
        var observed = new AtomicLong(-1);
        storage.setBeforeMaintenanceFloorActionForTesting(observed::set);
        var failure = new IOException("injected maintenance floor");
        storage.failNextCheckpointFloorSaveForTesting(failure);
        var logger = Logger.getLogger("");
        var errors = new ErrorHandler(failure);
        // A matching error from another pass must never satisfy this pass's log assertion.
        var unrelated = new LogRecord(Level.SEVERE, "Error during fuzzy checkpoint");
        unrelated.setThrown(new IOException("unrelated floor failure"));
        errors.publish(unrelated);
        assertTrue("an unrelated fuzzy error must not match", !errors.reported);
        logger.addHandler(errors);
        try {
          if (vacuum) {
            runVacuum(storage);
          } else {
            new PeriodicFuzzyCheckpoint(storage).run();
          }
        } finally {
          logger.removeHandler(errors);
        }
        assertTrue("the pass must reach its floor save", observed.get() >= 0);
        assertEquals("failure must not remove the WAL segment", original,
            storage.getWALInstance().begin().getSegment());
        assertTrue("failure must log an error", errors.reported);
        storage.checkErrorState();
        assertTrue("a failed floor save must not clear the recovery indication", isDirty());
        if (vacuum) {
          runVacuum(storage);
        } else {
          storage.makeFuzzyCheckpoint();
        }
        assertTrue("the next pass must remove the old segment",
            storage.getWALInstance().begin().getSegment() > original);
        assertTrue("the durable floor must cover the candidate seen before the failed save",
            readFloor() >= observed.get());
        assertTrue("maintenance must leave the recovery indication in place", isDirty());
      }
    }
  }

  /** A direct fuzzy-checkpoint caller receives a failed floor save instead of silent success. */
  @Test
  public void directFuzzyCallerReceivesFailureWithoutRemovingSegments() throws Exception {
    try (var manager = manager(root)) {
      manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      try (var session = manager.open(DATABASE, ADMIN, ADMIN)) {
        var storage = (DiskStorage) session.getStorage();
        hookedStorage = storage;
        prepareRemovableSegment(session, storage);
        var original = storage.getWALInstance().begin().getSegment();
        storage
            .failNextCheckpointFloorSaveForTesting(new IOException("injected maintenance floor"));
        assertThrows(RuntimeException.class, storage::makeFuzzyCheckpoint);
        assertEquals(original, storage.getWALInstance().begin().getSegment());
        storage.checkErrorState();
      }
    }
  }

  /** A forced close racing a fuzzy checkpoint cannot lower its saved floor. */
  @Test(timeout = 60_000)
  public void forcedCloseOverlappingFuzzyCheckpointKeepsDurableFloor() throws Exception {
    assertForcedCloseOverlap(false);
  }

  /** A forced close racing WAL vacuum cannot lower its saved floor. */
  @Test(timeout = 60_000)
  public void forcedCloseOverlappingVacuumKeepsDurableFloor() throws Exception {
    assertForcedCloseOverlap(true);
  }

  private void assertForcedCloseOverlap(boolean vacuum) throws Exception {
    var manager = manager(root);
    manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    var session = manager.open(DATABASE, ADMIN, ADMIN);
    var storage = (DiskStorage) session.getStorage();
    hookedStorage = storage;
    prepareRemovableSegment(session, storage);
    var saved = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var issued = new AtomicLong(-1);
    storage.setAfterMaintenanceFloorActionForTesting(value -> {
      issued.set(value);
      saved.countDown();
      await(release);
    });
    var maintenance = CompletableFuture.runAsync(() -> {
      if (vacuum) {
        try {
          runVacuum(storage);
        } catch (Exception e) {
          throw new AssertionError(e);
        }
      } else {
        try {
          storage.makeFuzzyCheckpoint();
        } catch (StorageException closedDuringCut) {
          // Closing the cache can reject the cut. Other failures must still fail this test.
          assertTrue(closedDuringCut.getMessage().contains("closed"));
        }
      }
    });
    try {
      assertTrue("maintenance did not save its floor", saved.await(30, TimeUnit.SECONDS));
      storage.close(session, true);
    } finally {
      release.countDown();
      maintenance.get(30, TimeUnit.SECONDS);
      session.close();
      manager.close();
    }
    assertTrue("the final close must not lower the background floor", readFloor() >= issued.get());
  }

  private static void prepareRemovableSegment(
      DatabaseSessionEmbedded session, AbstractStorage storage)
      throws IOException {
    session.getMetadata().getSchema().createClass("Work");
    for (var index = 0; index < 300; index++) {
      session.begin();
      ((EntityImpl) session.newEntity("Work")).setProperty("value", index);
      session.commit();
    }
    storage.getWALInstance().appendNewSegment();
    // Flush the old pages without cutting the old segment. The maintenance pass owns the cut.
    storage.getWriteCache().flush();
  }

  private static void runVacuum(AbstractStorage storage) throws Exception {
    var method = AbstractStorage.class.getDeclaredMethod("runWALVacuum");
    method.setAccessible(true);
    try {
      method.invoke(storage);
    } catch (InvocationTargetException e) {
      throw new AssertionError("vacuum must report its own failure", e.getCause());
    }
  }

  private long readFloor() throws IOException {
    return ByteBuffer.wrap(Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl")))
        .getLong(13);
  }

  private boolean isDirty() throws IOException {
    return Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl"))[12] != 0;
  }

  private static YouTrackDBImpl manager(Path root) {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.WAL_KEEP_SINGLE_SEGMENT.getKey(), false);
    return (YouTrackDBImpl) YourTracks.instance(root.toString(), config);
  }

  private long crashChild(String mode) throws Exception {
    var mark = root.resolve("mark-" + mode);
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    var process = new ProcessBuilder(java, "-cp", classpath,
        DiskStorageMaintenanceFloorTest.class.getName(), mode, root.toString(), mark.toString())
        .redirectErrorStream(true).start();
    var output = CompletableFuture.supplyAsync(() -> {
      try {
        return process.getInputStream().readAllBytes();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    try {
      assertTrue("child timed out", process.waitFor(90, TimeUnit.SECONDS));
      assertEquals(new String(output.get(10, TimeUnit.SECONDS)), 0, process.exitValue());
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }
    return Long.parseLong(Files.readString(mark).trim());
  }

  /** Run one maintenance pass and halt without a normal storage close. */
  public static void main(String[] args) throws Exception {
    var root = Path.of(args[1]);
    var manager = manager(root);
    manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    var session = manager.open(DATABASE, ADMIN, ADMIN);
    var storage = (DiskStorage) session.getStorage();
    prepareRemovableSegment(session, storage);
    var original = storage.getWALInstance().begin().getSegment();
    var issued = new AtomicLong(-1);
    storage.setBeforeMaintenanceFloorActionForTesting(issued::set);
    if (args[0].equals("fuzzy")) {
      storage.makeFuzzyCheckpoint();
    } else {
      runVacuum(storage);
    }
    assertTrue("the pass must observe the boundary before saving its floor", issued.get() >= 0);
    assertTrue("the pass must actually remove WAL evidence",
        storage.getWALInstance().begin().getSegment() > original);
    assertTrue("the durable floor must cover the value read before WAL removal",
        ByteBuffer.wrap(Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl")))
            .getLong(13) >= issued.get());
    Files.writeString(Path.of(args[2]), Long.toString(issued.get()), StandardOpenOption.CREATE_NEW);
    try (var channel = FileChannel.open(Path.of(args[2]), StandardOpenOption.WRITE)) {
      channel.force(true);
    }
    Runtime.getRuntime().halt(0);
  }

  private static void await(CountDownLatch latch) {
    try {
      assertTrue("maintenance must be released", latch.await(30, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static final class ErrorHandler extends Handler {

    private final IOException expectedFailure;
    private volatile boolean reported;

    private ErrorHandler(IOException expectedFailure) {
      this.expectedFailure = expectedFailure;
    }

    @Override
    public void publish(LogRecord record) {
      if (record.getLevel().intValue() < Level.SEVERE.intValue()
          || !record.getMessage().contains("fuzzy checkpoint")) {
        return;
      }
      for (var cause = record.getThrown(); cause != null; cause = cause.getCause()) {
        if (cause == expectedFailure) {
          reported = true;
          return;
        }
      }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }
}
