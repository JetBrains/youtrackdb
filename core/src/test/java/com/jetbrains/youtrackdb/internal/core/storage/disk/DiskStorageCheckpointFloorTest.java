package com.jetbrains.youtrackdb.internal.core.storage.disk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.StorageException;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.StorageStartupMetadata;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Full-checkpoint floor ordering, failed publication, and forced-close writer exclusion. */
public class DiskStorageCheckpointFloorTest {

  private static final String DATABASE = "checkpointFloor";
  private static final String ADMIN = "admin";
  private Path root;
  private AbstractStorage hookedStorage;

  @Before
  public void createDirectory() throws IOException {
    root = Files.createTempDirectory("checkpoint-floor-");
  }

  @After
  public void removeDirectory() {
    if (hookedStorage != null) {
      hookedStorage.clearCheckpointActionsForTesting();
      ((DiskStorage) hookedStorage).clearFloorActionsForTesting();
      hookedStorage.getAtomicOperationsManager().clearTestActions();
    }
    FileUtils.deleteRecursively(root.toFile());
  }

  /** A clean checkpoint followed by a halted process must not reuse a committed timestamp. */
  @Test
  public void fullCheckpointFloorSurvivesAbruptStop() throws Exception {
    var mark = crashChild("synch");
    assertTrue(readStartupFloor() >= mark);
    try (var manager = manager(); var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue(((AbstractStorage) session.getStorage()).getIdGen().getLastId() > mark);
    }
  }

  /** A writer already inside the admission window delays forced close until its timestamp exists. */
  @Test
  public void forcedShutdownWaitsForWriterAndPreservesItsTimestamp() throws Exception {
    var mark = crashChild("forced");
    assertTrue(readStartupFloor() >= mark);
    try (var manager = manager(); var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue(((AbstractStorage) session.getStorage()).getIdGen().getLastId() > mark);
    }
  }

  /** A halt after the close operation's timestamp must recover above it. */
  @Test
  public void closeTimeTimestampKeepsRecoveryIndicationSet() throws Exception {
    var mark = crashChild("closeAtomicCrash");
    assertTrue("close-time work must remain recoverable", isDirty());
    try (var manager = manager(); var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue(((AbstractStorage) session.getStorage()).getIdGen().getLastId() > mark);
    }
  }

  /** A failed cache close must retain recovery evidence for the close-time timestamp. */
  @Test
  public void cacheCloseFailureRecoversAboveCloseTimeTimestamp() throws Exception {
    var mark = crashChild("cacheCloseFailure");
    assertTrue("cache close failure must retain recovery indication", isDirty());
    try (var manager = manager(); var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue("restart must advance beyond the close-time operation",
          ((AbstractStorage) session.getStorage()).getIdGen().getLastId() > mark);
    }
  }

  /** An older synch clear cannot leave the later close timestamp unprotected (CN-1). */
  @Test
  public void synchOverlappingForcedShutdownRecoversCloseTimestamp() throws Exception {
    var mark = crashChild("overlappingClear");
    assertTrue("close work must re-mark after the overlapping clear", isDirty());
    try (var manager = manager(); var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue(((AbstractStorage) session.getStorage()).getIdGen().getLastId() > mark);
    }
  }

  /** Final floor publication waits for a writer admitted between close and the second pause. */
  @Test
  public void finalCloseFloorWaitsForWriter() throws Exception {
    var mark = crashChild("finalWriter");
    assertTrue(readStartupFloor() >= mark);
    try (var manager = manager(); var session = manager.open(DATABASE, ADMIN, ADMIN)) {
      assertTrue(((AbstractStorage) session.getStorage()).getIdGen().getLastId() > mark);
    }
  }

  /** One storage's test hooks cannot be consumed by another storage's writes or checkpoints. */
  @Test
  public void floorAndTimestampHooksBelongToTheirStorage() throws Exception {
    try (var manager = manager()) {
      manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      manager.create(DATABASE + "Other", DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      try (var first = manager.open(DATABASE, ADMIN, ADMIN);
          var other = manager.open(DATABASE + "Other", ADMIN, ADMIN)) {
        var storage = (AbstractStorage) first.getStorage();
        var otherStorage = (AbstractStorage) other.getStorage();
        hookedStorage = storage;
        var floors = new AtomicInteger();
        var timestamps = new AtomicInteger();
        var freezes = new AtomicInteger();
        storage.setCheckpointFloorActionForTesting(ignored -> floors.incrementAndGet());
        assertThrows(IllegalStateException.class,
            () -> storage.setCheckpointFloorActionForTesting(ignored -> floors.incrementAndGet()));
        storage.getAtomicOperationsManager()
            .setBeforeTimestampActionForTesting(timestamps::incrementAndGet);
        storage.getAtomicOperationsManager()
            .setFreezeRegisteredActionForTesting(freezes::incrementAndGet);
        otherStorage.getAtomicOperationsManager().executeInsideAtomicOperation(operation -> {
          // The other storage must not claim the installed timestamp hook.
        });
        otherStorage.synch();
        assertEquals(0, timestamps.get());
        assertEquals(0, floors.get());
        assertEquals(0, freezes.get());
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(operation -> {
          // This storage consumes only its own timestamp hook.
        });
        storage.synch();
        assertEquals(1, timestamps.get());
        assertEquals(1, floors.get());
        assertEquals(1, freezes.get());
      }
    }
  }

  /** A close with index histogram work and its later atomic operation must not wait on itself. */
  @Test(timeout = 60_000)
  public void forcedShutdownReleasesPauseForCloseTimeAtomicOperation() throws Exception {
    var manager = manager();
    manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    var session = manager.open(DATABASE, ADMIN, ADMIN);
    var storage = (AbstractStorage) session.getStorage();
    hookedStorage = storage;
    var closeAtomicCompleted = new AtomicInteger();
    var finalFloorReached = new AtomicInteger();
    var shutdownRemarked = new AtomicInteger();
    storage.setAfterShutdownRemarkActionForTesting(shutdownRemarked::incrementAndGet);
    assertThrows(IllegalStateException.class,
        () -> storage.setAfterShutdownRemarkActionForTesting(() -> {
        }));
    storage.setAfterCloseAtomicActionForTesting(ignored -> closeAtomicCompleted.incrementAndGet());
    ((DiskStorage) storage).setBeforeFinalFloorActionForTesting(finalFloorReached::incrementAndGet);
    session.getMetadata().getSchema().createClass("Indexed");
    session.command("CREATE PROPERTY Indexed.value STRING");
    session.command("CREATE INDEX Indexed.value ON Indexed (value) NOTUNIQUE");
    session.begin();
    ((EntityImpl) session.newEntity("Indexed")).setProperty("value", "histogram");
    session.commit();
    storage.close(session, true);
    assertEquals(1, closeAtomicCompleted.get());
    assertEquals(1, shutdownRemarked.get());
    assertEquals(1, finalFloorReached.get());
    session.close();
    manager.close();
  }

  /** Failed floor publication keeps both recovery evidence and the caller-visible error. */
  @Test
  public void floorFailureKeepsWalAndDirtyIndicationAndAllowsRetry() throws Exception {
    try (var manager = manager()) {
      manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
      try (var session = manager.open(DATABASE, ADMIN, ADMIN)) {
        var storage = (AbstractStorage) session.getStorage();
        session.getMetadata().getSchema().createClass("Work");
        session.begin();
        ((EntityImpl) session.newEntity("Work")).setProperty("value", 1);
        session.commit();
        storage.getWALInstance().appendNewSegment();
        var recoveryBegin = storage.getWALInstance().begin();
        assertTrue("the WAL must hold recovery records", recoveryBegin != null);
        assertTrue("writes must require recovery", isDirty());
        var afterFloor = new AtomicInteger();
        hookedStorage = storage;
        storage.setCheckpointFloorActionForTesting(ignored -> afterFloor.incrementAndGet());
        ((DiskStorage) storage)
            .failNextCheckpointFloorSaveForTesting(new IOException("injected floor failure"));
        var failure = assertThrows(StorageException.class, storage::synch);
        assertTrue(failure.getMessage().contains("checkpoint"));
        assertEquals("the checkpoint must stop before reaching the WAL cut", 0, afterFloor.get());
        assertEquals("a failed checkpoint must retain the original WAL recovery segment",
            recoveryBegin, storage.getWALInstance().begin());
        assertTrue("a failed floor save must not clear the indication", isDirty());
        var mark = storage.getIdGen().getLastId();
        storage.synch();
        assertEquals("a later checkpoint must retry the save", 1, afterFloor.get());
        assertTrue(java.nio.ByteBuffer.wrap(
            Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl"))).getLong(13) >= mark);
      }
    }
  }

  /** A failed write that changed memory must not suppress a retry or lower a saved floor. */
  @Test
  public void metadataFloorRetriesAfterFailureAndNeverDecreases() throws Exception {
    var path = root.resolve("dirty.fl");
    var backup = root.resolve("dirty.flb");
    var metadata = new StorageStartupMetadata(path, backup);
    metadata.create("test");
    try {
      metadata.publishLastTxIdFloor(10);
      Files.createDirectory(backup);
      Files.createFile(backup.resolve("prevent-deletion"));
      assertThrows(IOException.class, () -> metadata.publishLastTxIdFloor(20));
      assertEquals(20, metadata.getLastTxId());
      Files.delete(backup.resolve("prevent-deletion"));
      Files.delete(backup);
      metadata.publishLastTxIdFloor(20);
      metadata.publishLastTxIdFloor(5);
    } finally {
      metadata.close();
    }
    var reopened = new StorageStartupMetadata(path, backup);
    reopened.open("ignored");
    try {
      assertEquals(20, reopened.getLastTxId());
    } finally {
      reopened.close();
    }
  }

  private boolean isDirty() throws IOException {
    return Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl"))[12] != 0;
  }

  private long readStartupFloor() throws IOException {
    var metadata = new StorageStartupMetadata(
        root.resolve(DATABASE).resolve("dirty.fl"),
        root.resolve(DATABASE).resolve("dirty.flb"));
    metadata.open("ignored");
    try {
      return metadata.getLastTxId();
    } finally {
      metadata.close();
    }
  }

  private YouTrackDBImpl manager() {
    return manager(root);
  }

  private static YouTrackDBImpl manager(Path root) {
    // Background keep-single-segment checkpoints must not cut the recovery segment under test.
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
        DiskStorageCheckpointFloorTest.class.getName(), mode, root.toString(), mark.toString())
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

  /** Performs one test scenario in a separate process, without normal manager shutdown. */
  public static void main(String[] args) throws Exception {
    var root = Path.of(args[1]);
    var handshake = Path.of(args[2]);
    var manager = manager(root);
    manager.create(DATABASE, DatabaseType.DISK, ADMIN, ADMIN, ADMIN);
    var session = manager.open(DATABASE, ADMIN, ADMIN);
    var storage = (AbstractStorage) session.getStorage();
    session.getMetadata().getSchema().createClass("Work");
    for (var index = 0; index < 300; index++) {
      session.begin();
      ((EntityImpl) session.newEntity("Work")).setProperty("value", index);
      session.commit();
    }
    final long mark;
    if (args[0].equals("synch")) {
      mark = storage.getIdGen().getLastId();
      storage.synch();
    } else if (args[0].equals("closeAtomicCrash")) {
      storage.setAfterCloseAtomicActionForTesting(ignored -> {
        try {
          writeMark(handshake, storage.getIdGen().getLastId());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        Runtime.getRuntime().halt(0);
      });
      storage.close(session, true);
      throw new AssertionError("close-time crash hook was not reached");
    } else if (args[0].equals("cacheCloseFailure")) {
      // A path-scoped spy fails only this storage's cache teardown after the real cache closes.
      var cache = spy(storage.getReadCache());
      doAnswer(invocation -> {
        invocation.callRealMethod();
        throw new IOException("injected cache close failure");
      }).when(cache).closeStorage(storage.getWriteCache());
      Field cacheField = AbstractStorage.class.getDeclaredField("readCache");
      cacheField.setAccessible(true);
      cacheField.set(storage, cache);
      storage.setAfterCloseAtomicActionForTesting(ignored -> {
        try {
          writeMark(handshake, storage.getIdGen().getLastId());
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      });
      var failure = assertThrows(StorageException.class, () -> storage.close(session, true));
      assertTrue(failure.getMessage().contains("Error during closing of disk cache"));
      assertTrue(Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl"))[12] != 0);
      Runtime.getRuntime().halt(0);
      throw new AssertionError("halt returned");
    } else if (args[0].equals("overlappingClear")) {
      var synchAtFloor = new CountDownLatch(1);
      var releaseSynch = new CountDownLatch(1);
      var shutdownRemarked = new CountDownLatch(1);
      storage.setCheckpointFloorActionForTesting(ignored -> {
        synchAtFloor.countDown();
        awaitRelease(releaseSynch);
      });
      var synch = CompletableFuture.runAsync(storage::synch);
      assertTrue("synch did not reach its floor read", synchAtFloor.await(30, TimeUnit.SECONDS));
      storage.setAfterShutdownRemarkActionForTesting(shutdownRemarked::countDown);
      storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(() -> {
        try {
          assertTrue("the close timestamp must follow a durable re-mark",
              Files.readAllBytes(root.resolve(DATABASE).resolve("dirty.fl"))[12] != 0);
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      });
      storage.setAfterCloseAtomicActionForTesting(ignored -> {
        try {
          writeMark(handshake, storage.getIdGen().getLastId());
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
        Runtime.getRuntime().halt(0);
      });
      var shutdown = CompletableFuture.runAsync(() -> storage.close(session, true));
      try {
        assertTrue("forced shutdown did not re-mark after its checkpoint",
            shutdownRemarked.await(30, TimeUnit.SECONDS));
      } finally {
        releaseSynch.countDown();
      }
      synch.get(30, TimeUnit.SECONDS);
      shutdown.get(30, TimeUnit.SECONDS);
      throw new AssertionError("close-time crash hook was not reached");
    } else if (args[0].equals("forced")) {
      var managerOperations = storage.getAtomicOperationsManager();
      var writerEntered = new CountDownLatch(1);
      var releaseWriter = new CountDownLatch(1);
      var stageReached = new CountDownLatch(1);
      var freezeRegistered = new AtomicBoolean();
      var floorReached = new AtomicBoolean();
      managerOperations.setBeforeTimestampActionForTesting(() -> {
        writerEntered.countDown();
        awaitRelease(releaseWriter);
      });
      var write = writeOnce(storage);
      assertTrue("writer did not enter the checkpoint window",
          writerEntered.await(30, TimeUnit.SECONDS));
      managerOperations.setFreezeRegisteredActionForTesting(() -> {
        freezeRegistered.set(true);
        stageReached.countDown();
      });
      storage.setCheckpointFloorActionForTesting(ignored -> {
        floorReached.set(true);
        stageReached.countDown();
      });
      var shutdown = CompletableFuture.runAsync(() -> storage.close(session, true));
      try {
        assertTrue("shutdown reached neither freeze nor floor",
            stageReached.await(30, TimeUnit.SECONDS));
        assertTrue("shutdown must register a pause before its floor", freezeRegistered.get());
        assertFalse("checkpoint reached its floor before draining the writer", floorReached.get());
        assertFalse("freeze must still be draining the admitted writer", shutdown.isDone());
      } finally {
        releaseWriter.countDown();
      }
      write.get(30, TimeUnit.SECONDS);
      mark = storage.getIdGen().getLastId();
      shutdown.get(30, TimeUnit.SECONDS);
      assertTrue("shutdown must reach the floor save", floorReached.get());
    } else if (args[0].equals("finalWriter")) {
      var managerOperations = storage.getAtomicOperationsManager();
      var writerEntered = new CountDownLatch(1);
      var releaseWriter = new CountDownLatch(1);
      var stageReached = new CountDownLatch(1);
      var freezeRegistered = new AtomicBoolean();
      var finalFloorReached = new AtomicBoolean();
      var write = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>>();
      ((DiskStorage) storage).setBeforeFinalFloorActionForTesting(() -> {
        finalFloorReached.set(true);
        stageReached.countDown();
      });
      storage.setAfterCloseAtomicActionForTesting(ignored -> {
        managerOperations.setBeforeTimestampActionForTesting(() -> {
          writerEntered.countDown();
          awaitRelease(releaseWriter);
        });
        write.set(writeOnce(storage));
        try {
          if (!writerEntered.await(30, TimeUnit.SECONDS)) {
            throw new AssertionError("writer did not enter between the two pauses");
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
        managerOperations.setFreezeRegisteredActionForTesting(() -> {
          freezeRegistered.set(true);
          stageReached.countDown();
        });
      });
      var shutdown = CompletableFuture.runAsync(() -> storage.close(session, true));
      try {
        assertTrue("shutdown reached neither second freeze nor final floor",
            stageReached.await(30, TimeUnit.SECONDS));
        assertTrue("the second pause must register before the final floor",
            freezeRegistered.get());
        assertFalse("final floor must wait for the writer", finalFloorReached.get());
        assertFalse("second pause must still be draining the writer", shutdown.isDone());
      } finally {
        releaseWriter.countDown();
      }
      write.get().get(30, TimeUnit.SECONDS);
      mark = storage.getIdGen().getLastId();
      shutdown.get(30, TimeUnit.SECONDS);
      assertTrue("final floor must complete after the writer", finalFloorReached.get());
    } else {
      throw new IllegalArgumentException(args[0]);
    }
    writeMark(handshake, mark);
    Runtime.getRuntime().halt(0);
  }

  private static CompletableFuture<Void> writeOnce(AbstractStorage storage) {
    return CompletableFuture.runAsync(() -> {
      try {
        storage.getAtomicOperationsManager().executeInsideAtomicOperation(operation -> {
          // The freezer-admitted writer claims its timestamp before shutdown continues.
        });
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private static void awaitRelease(CountDownLatch release) {
    try {
      if (!release.await(30, TimeUnit.SECONDS)) {
        throw new AssertionError("writer was never released");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void writeMark(Path handshake, long mark) throws IOException {
    Files.writeString(handshake, Long.toString(mark), StandardOpenOption.CREATE_NEW);
    try (var channel = FileChannel.open(handshake, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }
}
