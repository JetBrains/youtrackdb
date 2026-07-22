package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.db.tool.DatabaseCompare;
import com.jetbrains.youtrackdb.internal.core.exception.ModificationOperationProhibitedException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.configuration2.BaseConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Short-duration multi-threaded integration tests for the incremental-backup ->
 * restore round-trip. Complements {@code StorageBackupMTStateTest} (which carries an
 * {@code @Ignore} because its 90-minute stress-soak body is incompatible with CI
 * runtime budgets) and {@link StorageBackupMTIT} (which sleeps 15 minutes between
 * the inserter and backup threads; also unsuitable for CI without the heavy soak).
 *
 * <p>Three end-to-end concerns are exercised by this class:
 *
 * <ol>
 *   <li><b>MT incremental-backup + restore cycle.</b> Drives concurrent inserter
 *       threads alongside a periodic incremental-backup thread on a source storage,
 *       then closes the source, restores a target from the produced backup chain,
 *       and asserts the source and target compare equal. Pins the collapsed
 *       {@code DiskStorage.restoreFromIncrementalBackup} loop's MT path against a
 *       regression that drops the per-component locking guarantees on the source
 *       during the backup window or mis-handles the multi-chunk restore on the
 *       target side. Positive evidence: the inserter threads must collectively
 *       exercise the file-extending allocator branch on {@link WOWCache#loadOrAdd}
 *       (via {@link WOWCache#getLoadOrAddExtendBranchInvocationsForTest()}) and the backup
 *       worker must complete more than one incremental backup before stop is
 *       observed — so the test fails loud if a future change re-routes inserts off
 *       the allocator path or shortens the contention window. Wall-time bound under
 *       90 s on a stock dev workstation; runs under {@link SequentialTest} because
 *       the underlying storages share the test build directory.</li>
 *   <li><b>Source-anchored physical-page equivalence on restored target.</b>
 *       Populates a source, takes a full backup and an incremental delta, captures
 *       each source-side {@code .pcl} file's
 *       {@link WOWCache#physicalSizeForBackupSnapshot} BEFORE the source closes,
 *       restores into a fresh target, and asserts the post-restore target carries
 *       the same physical-page count per file as the source. The source-side
 *       capture is the strengthening lever: a regression that left orphan pages on
 *       the target would surface as target {@code physicalPages > source physicalPages}
 *       on at least one file. The companion source-text sentinel
 *       {@code DiskStorageRestoreOrchestratorWiringTest} remains the load-bearing
 *       pin on the IR-side call shape; the executable structural assertion on the
 *       open-side dispatch lives in the next test method, which fabricates orphans
 *       on the target side and reopens to drive the {@code AbstractStorage.open()}
 *       dispatch of {@code truncateOrphansAfterRecovery}.</li>
 *   <li><b>Open-side wiring of {@code truncateOrphansAfterRecovery} via
 *       target-side fabrication.</b> Builds a clean source, takes a backup,
 *       restores into a target, closes the target, fabricates orphan pages on the
 *       target's {@code .pcl} file via {@link RandomAccessFile#setLength(long)},
 *       deletes the clean-shutdown markers so the reopen takes the WAL-replay
 *       (dirty) path the open-time pass is gated on, reopens the target, and asserts
 *       the file shrank back to its pre-fabrication size. Both
 *       {@code AbstractStorage.open()} and
 *       {@code DiskStorage.postProcessIncrementalRestore} invoke the same
 *       {@code executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)}
 *       call, so a regression that dropped the recovery-time orphan truncation
 *       would surface identically on both paths. This is the executable
 *       structural pin the source-text sentinel only approximates.</li>
 * </ol>
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
  // while staying inside the contention window. Sliced into 1-second checks of
  // the stop flag inside IncrementalBackupWorker.call so a late stop signal does
  // not extend CI runtime by up to five seconds.
  private static final long BACKUP_SLEEP_SECONDS = 5;

  // Final-wait budget for the inserter pool to drain after the stop flag flips.
  // Inserters retry on transient ConcurrentModificationException / RNFE, so a
  // bounded await prevents a stuck thread from hanging the test.
  private static final long INSERTER_SHUTDOWN_SECONDS = 30;

  // Number of orphan pages fabricated on the target's .pcl in the open-side
  // wiring test. Four pages is large enough that the shrink is unambiguously
  // observable in raw file-size accounting, small enough to keep the test fast.
  private static final int ORPHAN_PAGE_COUNT = 4;

  private YouTrackDBImpl youTrackDB;
  private final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
  private volatile boolean stop;

  /**
   * Defensive pre-clean: if a prior run crashed before {@link #after} fired (JVM kill, OOM,
   * OS reboot), the next run's {@code youTrackDB.create(dbName, ...)} would throw
   * "Database already exists". Mirrors the pre-clean pattern in
   * {@code ProductionAllocatorConcurrencyMTTest.setUp} and {@code LoadOrAddPoisonCascadeRegressionTest.setUp}.
   */
  @Before
  public void cleanupBeforeRun() throws Exception {
    var path = DbTestBase.getBaseDirectoryPath(getClass());
    if (java.nio.file.Files.exists(path)) {
      FileUtils.deleteRecursively(path.toFile());
    }
  }

  /**
   * Drives concurrent inserter threads on a source storage while a backup thread
   * takes periodic incremental backups, then closes the source and restores a
   * target from the backup chain. Asserts no inserter thread surfaced any
   * non-retry exception (the retried {@link ModificationOperationProhibitedException}
   * is the only known transient during the backup write-suspension window), the
   * backup worker took more than one snapshot, the file-extending allocator
   * branch on {@link WOWCache#loadOrAdd} ran at least once per inserter, and
   * {@link DatabaseCompare} reports source and restored target equal.
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
    // catches it via the same FileUtils.deleteRecursively(dbPath) sweep that
    // removes the source/target storages.
    var backupDir = directoryPath.resolve("mt-backup-dir").toFile();
    FileUtils.deleteRecursively(backupDir);
    if (!backupDir.exists() && !backupDir.mkdirs()) {
      throw new IllegalStateException(
          "Failed to create backup directory under " + backupDir.getAbsolutePath());
    }

    var config = makeConfig(ChecksumMode.StoreAndThrow);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var session = openSession(dbName, config)) {
      createBackupSchema(session);
    }

    // Snapshot the file-extending allocator counter on the source's WOWCache
    // before the contention window opens; the delta after pool shutdown must be
    // >= INSERTER_THREADS so a regression that bypasses the allocator path
    // (e.g., re-routes inserts through pre-allocated pages only) fails loud
    // instead of "passing" the absence-of-symptom comparison below.
    var extendBranchBefore = readExtendBranchInvocations(dbName, config);

    var completedBackups = new AtomicInteger();
    var startLatch = new CountDownLatch(1);
    var inserterPool = Executors.newFixedThreadPool(INSERTER_THREADS);
    var backupPool = Executors.newSingleThreadExecutor();
    List<Future<Void>> inserterFutures = new ArrayList<>();
    Future<Void> backupFuture;
    try {
      for (var i = 0; i < INSERTER_THREADS; i++) {
        inserterFutures.add(
            inserterPool.submit(new DataInserter(dbName, config, startLatch)));
      }
      backupFuture =
          backupPool.submit(
              new IncrementalBackupWorker(dbName, config, startLatch, backupDir, completedBackups));

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
    var earlyFailure = workerFailure.get();
    if (earlyFailure != null) {
      throw new AssertionError(
          "Inserter or backup worker surfaced a fatal exception during the contention window",
          earlyFailure);
    }
    // Bounded waits on every Future.get so a stuck worker on a regression surfaces as
    // a TimeoutException + clear AssertionError rather than hanging the test forever.
    for (var f : inserterFutures) {
      f.get(INSERTER_SHUTDOWN_SECONDS, TimeUnit.SECONDS);
    }
    backupFuture.get(INSERTER_SHUTDOWN_SECONDS, TimeUnit.SECONDS);

    // Positive-evidence assertions: the contention window actually exercised the
    // file-extending allocator branch and the backup worker actually completed
    // more than one snapshot. A failure here means the test "passed" the
    // absence-of-symptom comparison below by skipping the contended code paths
    // entirely (e.g., a regression that re-routes inserts or cuts the contention
    // window short).
    var extendBranchAfter = readExtendBranchInvocations(dbName, config);
    var extendDelta = extendBranchAfter - extendBranchBefore;
    // Scale the floor with the contention window so the assertion catches a regression
    // that cut contention to a tiny slice of the wall-time budget (e.g., backup thread
    // blocking inserters for 19 of 20 seconds would still pass an INSERTER_THREADS-only
    // floor by 1-2 orders of magnitude). Five extends/sec across the pool is a
    // conservative floor for CI runners — on a healthy run the observed delta is
    // typically in the hundreds-to-thousands range.
    var extendFloor = CONTENTION_SECONDS * 5L;
    assertThat(extendDelta)
        .as("WOWCache.loadOrAdd file-extending branch must run at least %d times during"
            + " the %d-second contention window (observed %d); a smaller delta means"
            + " the inserts did not meaningfully exercise the allocator path the MT pins"
            + " target — e.g., a regression that re-routes inserts off the allocator or"
            + " serialises the inserters with the backup thread for most of the window",
            extendFloor, CONTENTION_SECONDS, extendDelta)
        .isGreaterThanOrEqualTo(extendFloor);
    // Two completed backups (rather than one) is the minimum that proves the
    // backup worker ran the periodic-snapshot loop at least once mid-contention
    // plus the final post-stop snapshot below. A single completed backup could
    // mean the backup thread started, took one snapshot, and never re-entered
    // the loop.
    assertThat(completedBackups.get())
        .as("backup worker must complete more than one incremental backup during"
            + " the contention window (>= 1 mid-contention plus the final"
            + " post-stop snapshot)")
        .isGreaterThanOrEqualTo(2);

    // Take one final incremental backup post-stop so the source's last batch of
    // committed inserts is captured in the backup chain. Without this the
    // restored target compares against an older logical horizon and
    // DatabaseCompare reports spurious mismatches.
    try (var session = openSession(dbName, config)) {
      session.backup(backupDir.toPath());
    }

    // Close source so the restore can create the target alongside it.
    youTrackDB.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

    try (var sourceSession = openSession(dbName, config);
        var targetSession = openSession(backupDbName, config)) {
      var compare = new DatabaseCompare(sourceSession, targetSession, System.out::println);
      assertThat(compare.compare())
          .as("source and restored target must compare equal after MT incremental"
              + " backup + restore cycle")
          .isTrue();
    }
  }

  /**
   * Source-anchored consistency check on a restored target: drives a single-threaded
   * incremental-backup -> restore round-trip, captures the SOURCE's per-{@code .pcl}
   * physical-page count before the source closes, then asserts the restored target
   * carries an identical per-file physical-page footprint.
   *
   * <p>The source-side capture is the strengthening lever: the prior
   * {@code onDiskBytes == HEADER + physical * pageSize} shape was tautological because
   * both sides traced back to the target's on-disk file size. By comparing against the
   * source's pre-close {@link WOWCache#physicalSizeForBackupSnapshot} (read on the
   * open source, before the backup loop closes), a regression that left orphan pages
   * on the target would surface as a target {@code physicalSize > source physicalSize}
   * mismatch on at least one file. The companion
   * {@link #truncateOrphansAfterRecoveryFiresOnReopenAfterTargetSideFabrication()}
   * remains the executable structural pin on the open-side
   * {@code truncateOrphansAfterRecovery} dispatch via target-side fabrication; this
   * test pins the happy-path source-to-target equivalence.
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

    var config = makeConfig(ChecksumMode.StoreAndThrow);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    // Captured before the source closes so we have a non-tautological reference for
    // the post-restore target. Map: file name -> physical page count on the source's
    // WOWCache after the second backup completed (its final on-disk state).
    java.util.Map<String, Long> sourcePclPhysicalPages;
    try (var session = openSession(dbName, config)) {
      createBackupSchema(session);

      var random = new Random();
      // 200 commits is enough to grow the cluster .pcl file past the EP-and-root
      // bootstrap footprint, so the smoke-check assertion runs on a real number
      // of pages rather than the two-page floor of a brand-new cluster.
      for (var i = 0; i < 200; i++) {
        session.executeInTx(transaction -> {
          var data = new byte[16];
          random.nextBytes(data);
          var entity = transaction.newEntity("BackupClass");
          entity.setProperty("num", random.nextInt());
          entity.setProperty("data", data);
        });
      }

      // Full backup first, then a delta. The IR path exercises here is the
      // tail of DiskStorage.restoreFromBackup over the combined chain — driving
      // both a full and an incremental chunk exercises the loop's
      // multi-iteration shape (which is the surface the collapsed
      // restoreFromIncrementalBackup loop covers).
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

      // Capture source-side per-.pcl physical sizes AFTER the final backup and
      // BEFORE the source closes. These are the reference values the restored
      // target must match (each .pcl on the target must carry the same physical
      // page count as the source had at backup time).
      var sourceStorage = (DiskStorage) session.getStorage();
      var sourceWowCache = (WOWCache) sourceStorage.getWriteCache();
      sourcePclPhysicalPages = sourceWowCache.files().entrySet().stream()
          .filter(e -> e.getKey().endsWith(".pcl"))
          .collect(java.util.stream.Collectors.toMap(
              java.util.Map.Entry::getKey,
              e -> sourceWowCache.physicalSizeForBackupSnapshot(
                  sourceWowCache.fileIdByName(e.getKey()))));
    }
    youTrackDB.close();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

    try (var targetSession = openSession(backupDbName, config)) {
      var storage = (DiskStorage) targetSession.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pageSize = wowCache.pageSize();
      var storagePath = storage.getStoragePath();
      var pclEntries = wowCache.files().entrySet().stream()
          .filter(e -> e.getKey().endsWith(".pcl"))
          .toList();
      assertThat(pclEntries)
          .as("restored target must contain at least one .pcl file; an empty list"
              + " indicates a fundamental restore failure that would mask the"
              + " source-to-target comparison below")
          .isNotEmpty();

      // Workload-size floor: at least one .pcl must have grown well past the
      // EP+root bootstrap (two pages). Without this the source-to-target assertion
      // is trivially satisfied on every brand-new cluster file, hiding any
      // regression in workload sizing.
      var maxPhysicalPages = pclEntries.stream()
          .mapToLong(e -> wowCache.physicalSizeForBackupSnapshot(
              wowCache.fileIdByName(e.getKey())))
          .max()
          .orElse(0);
      assertThat(maxPhysicalPages)
          .as("workload must grow at least one .pcl past the EP+root bootstrap"
              + " footprint; a small max means the source-to-target assertion below"
              + " is trivially satisfied on a near-empty cluster")
          .isGreaterThan(2L);

      for (var entry : pclEntries) {
        var fileName = entry.getKey();
        var fileId = wowCache.fileIdByName(fileName);
        var physicalPages = wowCache.physicalSizeForBackupSnapshot(fileId);
        var nativeFileName = wowCache.nativeFileNameById(fileId);
        var onDiskBytes = storagePath.resolve(nativeFileName).toFile().length();
        // First: page-alignment + header-size accounting check on the target's own
        // file (catches a non-page-aligned partial write during restore).
        var expectedOnDiskBytes =
            (long) com.jetbrains.youtrackdb.internal.core.storage.fs.File.HEADER_SIZE
                + (long) physicalPages * pageSize;
        assertThat(onDiskBytes)
            .as("post-restore .pcl file %s on-disk size must match"
                + " HEADER_SIZE + physicalPages * pageSize; a mismatch would"
                + " indicate a non-page-aligned write reached disk during the"
                + " restore loop",
                fileName, physicalPages, expectedOnDiskBytes)
            .isEqualTo(expectedOnDiskBytes);

        // Second (load-bearing): the target's physical-page count must match the
        // source's pre-close snapshot for this file. Regression that left orphan
        // pages on the target would surface here as target > source mismatch.
        var sourcePhysicalPages = sourcePclPhysicalPages.get(fileName);
        assertThat(sourcePhysicalPages)
            .as("source had a .pcl file '%s' that the restored target is missing",
                fileName)
            .isNotNull();
        assertThat(physicalPages)
            .as("post-restore target .pcl file %s physical page count must equal the"
                + " source's pre-close physical page count (%d) — a larger target"
                + " value indicates orphan pages were left on disk by a regression in"
                + " the IR-side recovery dispatch; a smaller value indicates the"
                + " restore loop missed pages that were on the source",
                fileName, sourcePhysicalPages)
            .isEqualTo(sourcePhysicalPages);
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

  /**
   * Executable structural pin on {@code AbstractStorage.open()}'s dispatch of
   * {@code executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)}.
   * Builds a clean source, takes a backup, restores into a fresh target, closes
   * the target, then fabricates orphan pages directly on one of the target's
   * {@code .pcl} files via {@link RandomAccessFile#setLength(long)} (the
   * production-equivalent shape for "crash after {@code AsyncFile.allocateSpace}
   * but before {@code EnsurePageIsValidInFileTask} ran"). Deletes the target's
   * clean-shutdown markers ({@code dirty.fl} / {@code dirty.flb}) so the reopen takes
   * the WAL-replay (dirty) recovery path, then reopens via
   * {@link YourTracks#instance(java.nio.file.Path, org.apache.commons.configuration2.Configuration)}
   * + {@code open()}; the open-time recovery pass must shrink the file back to
   * its pre-fabrication size, and a follow-up TX must complete without
   * {@link IllegalStateException}.
   *
   * <p>The open-time pass is gated on {@code wereDataRestoredAfterOpen} (set only when
   * the open replayed the WAL), so the marker deletion is what keeps this scenario on
   * the path that still runs the pass; a clean reopen would skip it and leave the orphan
   * tail in place.
   *
   * <p>Both {@code AbstractStorage.open()} and
   * {@code DiskStorage.postProcessIncrementalRestore} invoke the same
   * {@code executeInsideAtomicOperation(this::truncateOrphansAfterRecovery)}
   * call shape, so a regression that dropped the dispatch surfaces identically
   * on both paths. The source-text sentinel
   * {@code DiskStorageRestoreOrchestratorWiringTest} pins the IR-side call
   * shape; this test pins the open-side semantic effect.
   */
  @Test
  public void truncateOrphansAfterRecoveryFiresOnReopenAfterTargetSideFabrication()
      throws Exception {
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    var dbName = StorageBackupMTRestoreIT.class.getSimpleName();
    var backupDbName = dbName + "RestoredFab";

    var backupDir = directoryPath.resolve("open-fabrication-backup-dir").toFile();
    FileUtils.deleteRecursively(backupDir);
    if (!backupDir.exists() && !backupDir.mkdirs()) {
      throw new IllegalStateException(
          "Failed to create backup directory under " + backupDir.getAbsolutePath());
    }

    var config = makeConfig(ChecksumMode.StoreAndThrow);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(dbName, DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var session = openSession(dbName, config)) {
      createBackupSchema(session);

      var random = new Random();
      // 200 commits is enough to grow the cluster .pcl past the EP-and-root
      // bootstrap so the fabrication site is past the recovery pass's logical
      // horizon by more than one page.
      for (var i = 0; i < 200; i++) {
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

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.restore(backupDbName, backupDir.getAbsolutePath());

    // Locate the largest BackupClass .pcl on the restored target. Selecting by
    // physical-page count guarantees the chosen cluster carries the inserted
    // workload (a freshly-created class gets 8 default clusters from the
    // schema layer's polymorphic-id allocation, only one of which holds the
    // workload; an unused-cluster .pcl with logicalPages==0 would trip the
    // recovery-pass corruption-guard branch and skip truncation, leaving the
    // assertion vacuous). The native file name and physical layout are read
    // while the storage is open; the target is then closed so the fabrication
    // runs against a quiesced file.
    int pageSize;
    String nativeFileName;
    java.nio.file.Path storagePath;
    try (var targetSession = openSession(backupDbName, config)) {
      var storage = (DiskStorage) targetSession.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
      // Collection names are counter-generated (c_<n>) with no class-name component since the
      // base-keyed-engine-files rename, so BackupClass's .pcl files are resolved through its
      // collection ids rather than matched by class-name prefix.
      var backupClass = targetSession.getMetadata().getSchema().getClass("BackupClass");
      assertThat(backupClass)
          .as("restored target must contain the BackupClass schema class; its absence indicates"
              + " a fundamental restore failure")
          .isNotNull();
      var classPclNames = new java.util.HashSet<String>();
      for (var collectionId : backupClass.getCollectionIds()) {
        classPclNames.add(targetSession.getCollectionNameById(collectionId) + ".pcl");
      }
      var largestPcl = wowCache.files().keySet().stream()
          .filter(classPclNames::contains)
          .max((a, b) -> Long.compare(
              wowCache.physicalSizeForBackupSnapshot(wowCache.fileIdByName(a)),
              wowCache.physicalSizeForBackupSnapshot(wowCache.fileIdByName(b))))
          .orElseThrow(
              () -> new AssertionError(
                  "restored target must contain at least one BackupClass .pcl file;"
                      + " an empty list indicates a fundamental restore failure"));
      nativeFileName = wowCache.nativeFileNameById(wowCache.fileIdByName(largestPcl));
    }
    youTrackDB.close();

    var pclFile = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = pclFile.length();
    try (var raf = new RandomAccessFile(pclFile, "rw")) {
      // Zero-byte fabrication: extends the file by ORPHAN_PAGE_COUNT pages
      // without writing magic bytes. Mirrors the production crash window
      // between AsyncFile.allocateSpace and EnsurePageIsValidInFileTask. The
      // recovery pass reads only the EP page and dispatches a shrink, so the
      // missing magic stamp is invisible to the pass under any ChecksumMode.
      raf.setLength(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);
    }
    long sizeAfterFabrication = pclFile.length();
    assertThat(sizeAfterFabrication)
        .as("post-fabrication .pcl file size must grow by exactly"
            + " ORPHAN_PAGE_COUNT * pageSize; a smaller growth means the"
            + " fabrication did not run as expected and the assertion below"
            + " would be vacuous")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    // Force the reopen onto the WAL-replay (dirty) recovery path so the open-time
    // orphan pass runs. The restore + graceful close left the target's dirty.fl /
    // dirty.flb clean-shutdown markers in place, which would make this reopen clean
    // (wereDataRestoredAfterOpen == false); the open-time pass is gated on that flag,
    // so on a clean reopen it would not run and the shrink assertion below would never
    // see the orphan tail removed. Deleting the markers reproduces the on-disk signal
    // the WAL-replay harness in LocalPaginatedStorageRestoreFromWALIT relies on: the
    // next open re-creates the marker as dirty and replays the WAL. The data files were
    // fully flushed by the graceful close, so the replay does not touch the fabricated
    // orphan tail; the gated recovery pass is what truncates it.
    java.nio.file.Files.deleteIfExists(storagePath.resolve("dirty.fl"));
    java.nio.file.Files.deleteIfExists(storagePath.resolve("dirty.flb"));
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var targetSession = openSession(backupDbName, config)) {
      // Capture file size BEFORE any non-recovery TX runs — a TX can grow the
      // file by a page, which would mask a missing or partial truncate.
      sizeImmediatelyAfterReopen = pclFile.length();

      // Health check: the first non-recovery TX must not surface
      // IllegalStateException from the physical > logical shape the
      // fabrication just produced.
      targetSession.executeInTx(transaction -> {
        var entity = transaction.newEntity("BackupClass");
        entity.setProperty("num", 7);
        entity.setProperty("data", new byte[16]);
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("AbstractStorage.open() must shrink the .pcl file back to its"
            + " pre-fabrication size via truncateOrphansAfterRecovery; a larger"
            + " size means the open-side recovery dispatch was dropped or"
            + " reordered after a flush that re-extended the file")
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a configuration that selects {@code checksumMode} and disables the
   * double write log. {@link ChecksumMode#StoreAndThrow} is the production-CI
   * default for this branch; under {@code Off} the magic-check leg of the bug
   * the IT class targets would be masked.
   */
  private static BaseConfiguration makeConfig(ChecksumMode checksumMode) {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(), checksumMode.name());
    config.setProperty(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG.getKey(), false);
    return config;
  }

  /**
   * Opens a session against the named DB with the test's checksum-mode config
   * propagated at the session layer. Mirrors the pattern used by
   * {@code TruncateOrphansAfterRecoveryIT}: passing the same configuration at
   * both the {@link YourTracks#instance} and {@code open} layers guards against
   * a session-level default silently overriding the instance-level checksum
   * mode.
   */
  private DatabaseSessionEmbedded openSession(String dbName, BaseConfiguration config) {
    return youTrackDB.open(
        dbName, "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build());
  }

  /**
   * Creates the {@code BackupClass} schema with an integer property, a binary
   * property, and a NOTUNIQUE index on the integer. Shared across the three
   * test scenarios so a future schema tweak applies uniformly.
   */
  private static void createBackupSchema(DatabaseSessionEmbedded session) {
    var schema = session.getMetadata().getSchema();
    var cls = schema.createClass("BackupClass");
    cls.createProperty("num", PropertyType.INTEGER);
    cls.createProperty("data", PropertyType.BINARY);
    cls.createIndex("backupIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "num");
  }

  /**
   * Reads the file-extending allocator-branch counter from the source's
   * {@link WOWCache}. Used by the MT test to capture before/after snapshots
   * around the contention window so a regression that bypasses the allocator
   * path fails loud on the delta assertion.
   */
  private long readExtendBranchInvocations(String dbName, BaseConfiguration config) {
    try (var session = openSession(dbName, config)) {
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      return wowCache.getLoadOrAddExtendBranchInvocationsForTest();
    }
  }

  /**
   * Inserter worker for the MT test. Inserts random {@code BackupClass} entities
   * in a tight loop, retrying transient
   * {@link ModificationOperationProhibitedException} during backup write windows.
   * On any non-retry exception the worker captures the cause into the shared
   * {@link #workerFailure} reference and rethrows; the main thread surfaces it
   * after the contention window closes.
   */
  private final class DataInserter implements Callable<Void> {
    private final String dbName;
    private final BaseConfiguration config;
    private final CountDownLatch startLatch;

    DataInserter(String dbName, BaseConfiguration config, CountDownLatch startLatch) {
      this.dbName = dbName;
      this.config = config;
      this.startLatch = startLatch;
    }

    @Override
    public Void call() throws Exception {
      startLatch.await();
      var random = new Random();
      try (var session = openSession(dbName, config)) {
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
            workerFailure.compareAndSet(null, t);
            throw t;
          }
        }
      }
      return null;
    }
  }

  /**
   * Backup worker for the MT test. Takes a fresh incremental backup every
   * {@link #BACKUP_SLEEP_SECONDS} (sliced into 1-second stop-flag checks so a
   * late stop signal does not extend CI runtime by up to five seconds) until
   * the stop flag flips. Increments {@code completedBackups} after every
   * successful {@code session.backup} call; captures any exception into the
   * shared {@link #workerFailure} reference using the same shape as the
   * inserter worker.
   */
  private final class IncrementalBackupWorker implements Callable<Void> {
    private final String dbName;
    private final BaseConfiguration config;
    private final CountDownLatch startLatch;
    private final File backupDir;
    private final AtomicInteger completedBackups;

    IncrementalBackupWorker(
        String dbName,
        BaseConfiguration config,
        CountDownLatch startLatch,
        File backupDir,
        AtomicInteger completedBackups) {
      this.dbName = dbName;
      this.config = config;
      this.startLatch = startLatch;
      this.backupDir = backupDir;
      this.completedBackups = completedBackups;
    }

    @Override
    public Void call() throws Exception {
      startLatch.await();
      try (var session = openSession(dbName, config)) {
        while (!stop) {
          try {
            session.backup(backupDir.toPath());
            completedBackups.incrementAndGet();
          } catch (Throwable t) {
            workerFailure.compareAndSet(null, t);
            throw t;
          }
          // Slice the inter-backup sleep into 1-second stop-flag checks so a
          // late stop signal does not block this worker for up to five
          // seconds; bounded shutdown latency keeps CI runtime predictable.
          for (var s = 0; s < BACKUP_SLEEP_SECONDS && !stop; s++) {
            try {
              TimeUnit.SECONDS.sleep(1);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
              return null;
            }
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
      FileUtils.deleteRecursively(new File(dbPath));
    }
  }
}
