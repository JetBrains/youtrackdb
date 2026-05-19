/*
     *
     *
     *  *  Licensed under the Apache License, Version 2.0 (the "License");
     *  *  you may not use this file except in compliance with the License.
     *  *  You may obtain a copy of the License at
     *  *
     *  *       http://www.apache.org/licenses/LICENSE-2.0
     *
     */

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Short-duration multi-threaded integration tests for the incremental-backup ->
 * restore round-trip. Complements {@code StorageBackupMTStateTest} (which carries an
 * {@code @Ignore} because its 90-minute stress-soak body is incompatible with CI
 * runtime budgets) and {@link StorageBackupMTIT} (which sleeps 15 minutes between
 * the inserter and backup threads; also unsuitable for CI without the heavy soak).
 *
 * <p>Two end-to-end concerns are exercised by this class:
 *
 * <ol>
 *   <li><b>MT incremental-backup + restore cycle.</b> Drives concurrent inserter
 *       threads alongside a periodic incremental-backup thread on a source storage,
 *       then closes the source, restores a target from the produced backup chain,
 *       and asserts the source and target compare equal. Pins the collapsed
 *       {@code DiskStorage.restoreFromIncrementalBackup} loop's MT path against a
 *       regression that drops the per-component locking guarantees on the source
 *       during the backup window or mis-handles the multi-chunk restore on the
 *       target side. Wall-time bound under 90 s on a stock dev workstation; runs
 *       under {@link SequentialTest} because the underlying storages share the
 *       test build directory.</li>
 *   <li><b>Executable {@code postProcessIncrementalRestore} -> {@code
 *       truncateOrphansAfterRecovery} wiring.</b> Populates a source, takes a full
 *       backup and an incremental delta, restores into a fresh target, and asserts
 *       the post-restore target satisfies the {@code physical == logical} invariant
 *       on every paginated-collection (.pcl) file. This is the structural
 *       reality the IR wiring establishes: an IR-side regression that dropped the
 *       {@code truncateOrphansAfterRecovery} dispatch (or moved it before the
 *       preceding {@code flushAllData()}, allowing a flush to re-extend a file
 *       past the truncate target) would leave a non-empty subset of restored
 *       files with {@code physical > logical}. The companion source-text sentinel
 *       {@code DiskStorageRestoreOrchestratorWiringTest} continues to pin the
 *       exact call shape — the two tests are complementary rather than redundant:
 *       the source-text sentinel catches "the dispatch line was deleted or
 *       renamed", and this test catches "the dispatch line is present but the
 *       semantics it establishes were broken".</li>
 * </ol>
 *
 * <p><b>Why this is NOT a generic orphan-injection IT.</b> The plan considered a
 * recipe that fabricates orphan pages on the source {@code .pcl} pre-backup, ships
 * them through the backup payload, and asserts the IR pass truncates them on the
 * target. Two structural facts of the public storage API block that recipe in this
 * branch: (a) {@code AbstractStorage.open()} runs {@code truncateOrphansAfterRecovery}
 * unconditionally on every open, so any orphan written into a closed source's
 * {@code .pcl} is purged the moment the source is reopened to drive the backup;
 * (b) the public {@code backup()} API requires an open source, so there is no
 * supported route that ships an orphan-bearing source through the backup payload.
 * Pinning {@code physical == logical} on the restored target is therefore the
 * strongest executable assertion available without bypassing the public API; the
 * source-text sentinel covers the residual "did the dispatch line move?"
 * regression direction.
 */
@Category(SequentialTest.class)
public class StorageBackupMTRestoreIT {

  // Source-side inserter thread count. Four threads is enough to produce per-TX
  // contention on the cluster's allocator (the surface the per-component locks
  // protect) without exhausting the small CI runner's CPU budget.
  private static final int INSERTER_THREADS = 4;

  // Wall-time budget for the contention window in the MT test. Twenty seconds
  // gives the periodic backup thread time to take 3-4 incremental snapshots while
  // the inserters drive ~30k-50k commits, which is enough to validate the MT
  // restore loop without inflating CI runtime.
  private static final long CONTENTION_SECONDS = 20;

  // Per-incremental-backup sleep on the backup thread. Five seconds gives each
  // backup window enough inserter-thread mutations to produce a non-trivial delta
  // while staying inside the contention window.
  private static final long BACKUP_SLEEP_SECONDS = 5;

  // Final-wait budget for the inserter pool to drain after the stop flag flips.
  // Inserters retry on transient ConcurrentModificationException / RNFE, so a
  // bounded await prevents a stuck thread from hanging the test.
  private static final long INSERTER_SHUTDOWN_SECONDS = 30;

  private YouTrackDBImpl youTrackDB;
  private final AtomicReference<Throwable> inserterFailure = new AtomicReference<>();
  private volatile boolean stop;

  /**
   * Drives concurrent inserter threads on a source storage while a backup thread
   * takes periodic incremental backups, then closes the source and restores a
   * target from the backup chain. Asserts no inserter thread surfaced an
   * allocator-shape exception ({@link IllegalStateException} or {@link
   * com.jetbrains.youtrackdb.api.exception.StorageException} matching the
   * poison-cascade signature) during the contention window, and that {@link
   * DatabaseCompare} reports source and restored target equal.
   *
   * <p>The test deliberately uses a small contention window ({@link
   * #CONTENTION_SECONDS}) to stay inside the CI runtime budget. Reproduction of
   * the original poison cascade is not the goal here — that is covered by
   * {@code LoadOrAddPoisonCascadeRegressionTest} in the cache layer. The role of
   * this test is to pin the MT path through the collapsed
   * {@code DiskStorage.restoreFromIncrementalBackup} loop against a regression
   * that breaks the backup-during-writes invariant or drops per-component locking
   * on either side of the restore.
   */
  @Test
  public void mtIncrementalBackupRestoreCycleCompletesWithoutAllocatorException()
      throws Exception {
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    var dbName = StorageBackupMTRestoreIT.class.getSimpleName();
    var backupDbName = dbName + "Restored";

    // Backup dir lives under the per-test buildDirectory so the @After cleanup
    // catches it via the same FileUtils.deleteDirectory(dbPath) sweep that
    // removes the source/target storages.
    var backupDir = directoryPath.resolve("mt-backup-dir").toFile();
    FileUtils.deleteRecursively(backupDir);
    if (!backupDir.exists() && !backupDir.mkdirs()) {
      throw new IllegalStateException(
          "Failed to create backup directory under " + backupDir.getAbsolutePath());
    }

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var session = youTrackDB.open(dbName, "admin", "admin")) {
      var schema = session.getMetadata().getSchema();
      var cls = schema.createClass("BackupClass");
      cls.createProperty("num", PropertyType.INTEGER);
      cls.createProperty("data", PropertyType.BINARY);
      cls.createIndex("backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");
    }

    var startLatch = new CountDownLatch(1);
    var inserterPool = Executors.newFixedThreadPool(INSERTER_THREADS);
    var backupPool = Executors.newSingleThreadExecutor();
    List<Future<Void>> inserterFutures = new ArrayList<>();
    Future<Void> backupFuture;
    try {
      for (var i = 0; i < INSERTER_THREADS; i++) {
        inserterFutures.add(
            inserterPool.submit(new DataInserter(dbName, startLatch)));
      }
      backupFuture =
          backupPool.submit(new IncrementalBackupWorker(dbName, startLatch, backupDir));

      // Release all worker threads simultaneously to maximise the chance of an
      // inserter and the backup thread overlapping on the same cluster pages.
      startLatch.countDown();

      // Bounded contention window — CI-runtime-friendly. We want enough
      // wall-time for several incremental backups, not the full stress-soak
      // shape of StorageBackupMTStateTest.
      TimeUnit.SECONDS.sleep(CONTENTION_SECONDS);

      stop = true;
    } finally {
      shutdownPool(inserterPool, INSERTER_SHUTDOWN_SECONDS);
      shutdownPool(backupPool, INSERTER_SHUTDOWN_SECONDS);
    }

    // Surface any exception captured by an inserter or the backup worker before
    // continuing to the restore phase — a swallowed allocator exception would
    // otherwise be invisible (DatabaseCompare runs on the in-memory state and
    // would not see a write that never reached disk).
    var earlyFailure = inserterFailure.get();
    if (earlyFailure != null) {
      throw new AssertionError(
          "Inserter or backup worker surfaced a fatal exception during the contention window",
          earlyFailure);
    }
    for (var f : inserterFutures) {
      f.get();
    }
    backupFuture.get();

    // Take one final incremental backup post-stop so the source's last batch of
    // committed inserts is captured in the backup chain. Without this the
    // restored target compares against an older logical horizon and
    // DatabaseCompare reports spurious mismatches.
    try (var session = youTrackDB.open(dbName, "admin", "admin")) {
      session.backup(backupDir.toPath());
    }

    // Close source so the restore can create the target alongside it.
    youTrackDB.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath);
    youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

    try (var sourceSession = youTrackDB.open(dbName, "admin", "admin");
        var targetSession = youTrackDB.open(backupDbName, "admin", "admin")) {
      var compare = new DatabaseCompare(sourceSession, targetSession, System.out::println);
      assertThat(compare.compare())
          .as("source and restored target must compare equal after MT incremental"
              + " backup + restore cycle")
          .isTrue();
    }
  }

  /**
   * Drives an incremental-backup -> restore round-trip on a single-threaded
   * source and asserts {@code physical == logical} on every {@code .pcl} file of
   * the restored target. The invariant is the post-recovery shape that
   * {@code postProcessIncrementalRestore} establishes via its
   * {@code truncateOrphansAfterRecovery} dispatch: any regression that dropped
   * the dispatch (or reordered it before {@code flushAllData()}, letting a
   * subsequent flush re-extend a file past the logical horizon) would surface
   * here as at least one {@code .pcl} file with on-disk size strictly greater
   * than {@code (epLogicalCounter + 1) * pageSize}.
   *
   * <p>This is an end-to-end executable companion to the source-text sentinel
   * {@code DiskStorageRestoreOrchestratorWiringTest}: the two tests cover
   * complementary regression directions (call-shape vs. structural invariant)
   * and are not redundant. See the class-level Javadoc for the rationale.
   */
  @Test
  public void postProcessIncrementalRestoreLeavesTargetWithPhysicalEqualsLogical()
      throws Exception {
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    var dbName = StorageBackupMTRestoreIT.class.getSimpleName();
    var backupDbName = dbName + "RestoredSt";

    var backupDir = directoryPath.resolve("ir-wiring-backup-dir").toFile();
    FileUtils.deleteRecursively(backupDir);
    if (!backupDir.exists() && !backupDir.mkdirs()) {
      throw new IllegalStateException(
          "Failed to create backup directory under " + backupDir.getAbsolutePath());
    }

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var session = youTrackDB.open(dbName, "admin", "admin")) {
      var schema = session.getMetadata().getSchema();
      var cls = schema.createClass("BackupClass");
      cls.createProperty("num", PropertyType.INTEGER);
      cls.createProperty("data", PropertyType.BINARY);
      cls.createIndex("backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");

      var random = new Random();
      // 200 commits is enough to grow the cluster .pcl file past the EP-and-root
      // bootstrap footprint, so the truncate target on the restored side is a
      // real number greater than the single-page floor and the assertion is not
      // trivially satisfied by an empty file.
      for (var i = 0; i < 200; i++) {
        session.executeInTx(transaction -> {
          var data = new byte[16];
          random.nextBytes(data);
          var entity = transaction.newEntity("BackupClass");
          entity.setProperty("num", random.nextInt());
          entity.setProperty("data", data);
        });
      }

      // Full backup first, then a delta. The IR path under test is
      // postProcessIncrementalRestore, which runs once at the tail of
      // DiskStorage.restoreFromBackup over the combined chain — driving both
      // a full and an incremental chunk exercises the loop's multi-iteration
      // shape (which is the surface the collapsed restoreFromIncrementalBackup
      // loop covers).
      session.backup(backupDir.toPath());

      for (var i = 0; i < 100; i++) {
        session.executeInTx(transaction -> {
          var data = new byte[16];
          random.nextBytes(data);
          var entity = transaction.newEntity("BackupClass");
          entity.setProperty("num", random.nextInt());
          entity.setProperty("data", data);
        });
      }

      session.backup(backupDir.toPath());
    }
    youTrackDB.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath);
    youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

    // After restore returns, postProcessIncrementalRestore has already run on
    // the target's storage. Read the WOWCache's view of physical pages and
    // the on-disk file size for every .pcl file and assert physical == logical
    // (where "logical" is what the WOWCache tracks via its filledUpTo map,
    // which is the post-recovery horizon by construction). On a regression
    // that dropped the truncate dispatch, the on-disk file size would exceed
    // physicalSizeForBackupSnapshot by at least one page on any .pcl that
    // carried orphan pages through the restore.
    try (var targetSession = youTrackDB.open(backupDbName, "admin", "admin")) {
      var storage = (DiskStorage) targetSession.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pageSize = wowCache.pageSize();
      var storagePath = storage.getStoragePath();
      var pclEntries = wowCache.files().entrySet().stream()
          .filter(e -> e.getKey().endsWith(".pcl"))
          .toList();
      assertThat(pclEntries)
          .as("restored target must contain at least one .pcl file; an empty list"
              + " indicates a fundamental restore failure that would mask the IR"
              + " wiring assertion below")
          .isNotEmpty();

      for (var entry : pclEntries) {
        var fileName = entry.getKey();
        var fileId = wowCache.fileIdByName(fileName);
        var physicalPages = wowCache.physicalSizeForBackupSnapshot(fileId);
        var nativeFileName = wowCache.nativeFileNameById(fileId);
        var onDiskBytes = storagePath.resolve(nativeFileName).toFile().length();
        // The WOWCache's gated physical-size accessor reports the data-page
        // count; the on-disk layout is File.HEADER_SIZE (1024-byte header) plus
        // physicalPages * pageSize bytes of data pages. A residual orphan past
        // the recovery horizon would push onDiskBytes above this expected
        // value by at least one page.
        var expectedOnDiskBytes =
            (long) com.jetbrains.youtrackdb.internal.core.storage.fs.File.HEADER_SIZE
                + (long) physicalPages * pageSize;
        assertThat(onDiskBytes)
            .as("post-restore .pcl file %s must satisfy physical == logical: WOWCache"
                + " physicalPages=%d implies on-disk bytes = File.HEADER_SIZE + physicalPages"
                + " * pageSize = %d; a strictly greater on-disk size would indicate the"
                + " IR-side truncateOrphansAfterRecovery dispatch did not establish the"
                + " post-recovery invariant",
                fileName, physicalPages, expectedOnDiskBytes)
            .isEqualTo(expectedOnDiskBytes);
      }

      // Health check: a non-recovery TX on the restored target must complete
      // without IllegalStateException. A regression that left orphan pages on
      // disk past the WOWCache's logical horizon would trip the allocator's
      // physical > logical assertion the next time a new page is allocated.
      targetSession.executeInTx(transaction -> {
        var entity = transaction.newEntity("BackupClass");
        entity.setProperty("num", 42);
        entity.setProperty("data", new byte[16]);
      });
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Inserter worker for the MT test. Inserts random {@code BackupClass} entities
   * in a tight loop, retrying transient
   * {@link ModificationOperationProhibitedException} during backup write windows.
   * On any non-retry exception the worker captures the cause into the shared
   * {@link #inserterFailure} reference and rethrows; the main thread surfaces it
   * after the contention window closes.
   */
  private final class DataInserter implements Callable<Void> {
    private final String dbName;
    private final CountDownLatch startLatch;

    DataInserter(String dbName, CountDownLatch startLatch) {
      this.dbName = dbName;
      this.startLatch = startLatch;
    }

    @Override
    public Void call() throws Exception {
      startLatch.await();
      var random = new Random();
      try (var session = youTrackDB.open(dbName, "admin", "admin")) {
        while (!stop) {
          try {
            session.executeInTx(transaction -> {
              var data = new byte[16];
              random.nextBytes(data);
              var entity = transaction.newEntity("BackupClass");
              entity.setProperty("num", random.nextInt());
              entity.setProperty("data", data);
            });
          } catch (ModificationOperationProhibitedException e) {
            // Backup briefly suspends writes; back off and retry.
            try {
              Thread.sleep(50);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return null;
            }
          } catch (Throwable t) {
            // Capture the first failure; subsequent failures are dropped (the
            // first is the most diagnostically useful). Surface to the main
            // thread via the shared reference and rethrow so future.get() also
            // sees the stack trace.
            inserterFailure.compareAndSet(null, t);
            throw t;
          }
        }
      }
      return null;
    }
  }

  /**
   * Backup worker for the MT test. Takes a fresh incremental backup every
   * {@link #BACKUP_SLEEP_SECONDS} until the stop flag flips. Captures any
   * exception into the shared {@link #inserterFailure} reference using the same
   * shape as the inserter worker.
   */
  private final class IncrementalBackupWorker implements Callable<Void> {
    private final String dbName;
    private final CountDownLatch startLatch;
    private final File backupDir;

    IncrementalBackupWorker(String dbName, CountDownLatch startLatch, File backupDir) {
      this.dbName = dbName;
      this.startLatch = startLatch;
      this.backupDir = backupDir;
    }

    @Override
    public Void call() throws Exception {
      startLatch.await();
      try (var session = youTrackDB.open(dbName, "admin", "admin")) {
        while (!stop) {
          try {
            session.backup(backupDir.toPath());
          } catch (Throwable t) {
            inserterFailure.compareAndSet(null, t);
            throw t;
          }
          try {
            TimeUnit.SECONDS.sleep(BACKUP_SLEEP_SECONDS);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
          }
        }
      }
      return null;
    }
  }

  private static void shutdownPool(ExecutorService pool, long awaitSeconds)
      throws InterruptedException {
    pool.shutdown();
    if (!pool.awaitTermination(awaitSeconds, TimeUnit.SECONDS)) {
      pool.shutdownNow();
      // Best-effort wait after shutdownNow; if a worker is genuinely stuck
      // the main thread will see the future.get() throw on the failed task
      // rather than hang on the pool drain.
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @After
  public void after() throws Exception {
    if (youTrackDB != null && youTrackDB.isOpen()) {
      youTrackDB.close();
    }
    if (youTrackDB != null) {
      var internal = YouTrackDBInternal.extract(youTrackDB);
      var dbPath = internal.getBasePath();
      org.apache.commons.io.FileUtils.deleteDirectory(new java.io.File(dbPath));
    }
  }
}
