package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.StorageCollection;
import com.jetbrains.youtrackdb.internal.core.storage.cache.ReadCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration tests for the recovery-time orphan-truncation pass orchestrated by
 * {@code AbstractStorage.truncateOrphansAfterRecovery()}. The pass exists to restore the
 * {@code logical <= physical} invariant on entry-point-equipped storage components after
 * a partial-flush crash: WAL replay restores the logical EP counter, but a crash mid-flush
 * can leave physical pages on disk beyond that counter. Without this pass, the first
 * non-recovery transaction observes a {@code physical > logical} shape and the
 * allocator-only AOBT contract surfaces an {@code IllegalStateException}.
 *
 * <p>Each scenario is exercised under a 2-dimensional matrix:
 *
 * <ul>
 *   <li><b>{@link ChecksumMode}</b> — {@link ChecksumMode#Off} and
 *       {@link ChecksumMode#StoreAndThrow}. The StoreAndThrow axis pins the production-CI
 *       default and proves the recovery pass never reads orphan-page bodies (which would
 *       trip a checksum mismatch under StoreAndThrow). The {@code MAGIC_NUMBER_WITHOUT_CHECKSUM}
 *       stamp the magic-stamped helper writes is accepted by
 *       {@code WOWCache.verifyMagicChecksumAndDecryptPage} without a CRC comparison, so the
 *       helper transfers unchanged across the two modes — the value of the matrix is to fail
 *       loud if a future change introduces an eager orphan-page read pre-shrink.</li>
 *   <li><b>Orphan fabrication shape</b> — magic-stamped and zero-byte. The magic-stamped
 *       fabrication mirrors {@code EnsurePageIsValidInFileTask.writeValidPageInFile} (the
 *       byte layout that survives "WAL flushed, in-memory gap-fill stamped, JVM crashed
 *       before the next periodic flush"). The zero-byte fabrication mirrors a strictly
 *       earlier crash window: {@code AsyncFile.allocateSpace} extended the physical file
 *       counter but the {@code EnsurePageIsValidInFileTask} never ran, so the trailing
 *       bytes on disk are filesystem-zeroed. The recovery pass only reads the EP page
 *       (which is valid because the production stack wrote it), then dispatches a shrink
 *       that doesn't touch the orphan tail, so both shapes are equivalent for the pass —
 *       the second shape is here to prove the pass survives a missing magic stamp without
 *       a load attempt.</li>
 * </ul>
 *
 * <p>Six file shapes are exercised, mapping onto <b>four independent dispatch surfaces
 * plus one sibling-hook surface</b>:
 *
 * <ul>
 *   <li><b>Top-level orchestrator dispatch</b> covers four file shapes via four
 *       independent code paths inside
 *       {@code AbstractStorage.truncateOrphansAfterRecovery}:
 *       <ul>
 *         <li><b>{@code .pcl}</b> — a paginated-collection cluster file (PCV2's own
 *             {@code verifyAndTruncateOrphans}).</li>
 *         <li><b>{@code .cbt}</b> — a BTree single-value index engine's primary data
 *             file. Pins the engine-side dispatch through the orchestrator's
 *             {@code instanceof BTreeSingleValueIndexEngine} filter.</li>
 *         <li><b>{@code .grb}</b> — a {@code SharedLinkBagBTree} file (one per
 *             paginated collection, named {@code global_collection_<id>.grb}). Pins
 *             Group 3 of the orchestrator, where
 *             {@code LinkCollectionsBTreeManagerShared.verifyAndTruncateAllOrphans}
 *             iterates the manager's private {@code fileIdBTreeMap} — the only
 *             recovery-pass target with no public iteration accessor on the
 *             manager.</li>
 *         <li><b>Multi-value engine null sub-tree {@code <index>$null.cbt}</b> — the
 *             nullTree data file inside a {@code BTreeMultiValueIndexEngine}. Pins
 *             the previously IT-uncovered nullTree leg of
 *             {@code BTreeMultiValueIndexEngine.verifyAndTruncateOrphans} (the svTree
 *             leg is already pinned by the {@code .cbt} scenarios above).</li>
 *       </ul>
 *   </li>
 *   <li><b>Sibling-hook dispatch</b> covers one additional file shape via
 *       {@code PaginatedCollectionV2.verifyAndTruncateOrphansSiblings}, which the
 *       PCV2 entry-point owner invokes internally:
 *       <ul>
 *         <li><b>{@code .cpm}</b> — a {@code CollectionPositionMapV2} embedded
 *             position-map file. The {@code .cpm} scenarios pin this siblings-hook
 *             delegation specifically; they are NOT an independent top-level
 *             dispatch path — there is no orchestrator-direct CPMV2 branch. A
 *             regression that broke the siblings-hook delegation would surface here
 *             without affecting the {@code .pcl} assertion.</li>
 *       </ul>
 *   </li>
 *   <li><b>Clean shutdown / no-op</b> matrix exercises the same dispatch surfaces
 *       above (across {@code .pcl}, {@code .cbt}, {@code .grb}, and
 *       {@code $null.cbt}) but on a clean shutdown rather than a fabrication
 *       scenario — the per-component pre-flight must make the pass a strict no-op
 *       on every file shape, catching an over-shrink regression that would drop
 *       live pages on every reopen.</li>
 * </ul>
 *
 * <p>The orphan fabrication uses {@link RandomAccessFile} after the DB is closed. The
 * magic-stamped helper writes {@link #MAGIC_NUMBER_WITHOUT_CHECKSUM} + LSN {@code (-1, -1)}
 * (the production gap-fill byte layout); the zero-byte helper extends the file via
 * {@link RandomAccessFile#setLength(long)} so the trailing bytes are filesystem-zeroed.
 *
 * <p>This test runs in the {@link SequentialTest} category because it manipulates raw
 * storage files and cannot tolerate parallel JVMs touching the same build directory.
 */
@Category(SequentialTest.class)
public class TruncateOrphansAfterRecoveryIT {

  // 4 orphan pages past the logical horizon — large enough to make the truncate visible
  // in raw file-size accounting, small enough to keep the test fast.
  private static final int ORPHAN_PAGE_COUNT = 4;

  // Mirrors WOWCache.MAGIC_NUMBER_WITHOUT_CHECKSUM (see WOWCache.java around line 259 and
  // EnsurePageIsValidInFileTask.writeValidPageInFile). If WOWCache's constant changes,
  // this fabrication will no longer match the production write path and the recovery
  // pass's load-on-reopen behaviour would diverge — kept duplicated here intentionally so
  // a future change there surfaces as a test failure.
  private static final long MAGIC_NUMBER_WITHOUT_CHECKSUM = 0xEF30BCAFL;

  // Growth workload shared by cleanReopenAfterRollbackSkipsPassAndLeavesNoOrphan and its
  // positive control committedAllocationGrowsPclSoRollbackAssertionIsNonVacuous. A fresh
  // cluster's .pcl starts at a single data page, so a few small rows fit inside it and a
  // commit would not grow the file. This workload writes GROWTH_ROW_COUNT rows each carrying
  // a 256-char payload (~0.5 MB raw), forcing the cluster to span several data pages so a
  // committed run physically extends the .pcl and persists every row -- which is what makes
  // the rolled-back variant's zero-committed-rows assertion non-vacuous.
  private static final int GROWTH_ROW_COUNT = 2000;
  private static final String GROWTH_PAYLOAD = "x".repeat(256);

  /**
   * Inserts {@link #GROWTH_ROW_COUNT} rows of {@link #GROWTH_PAYLOAD} into {@code className}
   * inside the supplied transaction. Sized to grow the cluster's {@code .pcl} past its
   * initial data page so a committed run physically extends the file and persists every row
   * (the positive control), and a rolled-back run demonstrably discards those rows (the
   * rollback-zero-footprint corroboration).
   */
  private static void insertGrowthWorkload(final Transaction transaction, final String className) {
    for (var i = 0; i < GROWTH_ROW_COUNT; i++) {
      var entity = transaction.newEntity(className);
      entity.setProperty("value", "row-" + i + "-" + GROWTH_PAYLOAD);
    }
  }

  /**
   * Fabrication shape for orphan-tail pages appended to a closed storage file. Two
   * implementations exist: {@link #magicStampedOrphanFabricator()} mirrors the production
   * "gap-fill page" byte layout ({@link #MAGIC_NUMBER_WITHOUT_CHECKSUM} + LSN {@code (-1,
   * -1)}); {@link #zeroByteOrphanFabricator()} mirrors a strictly earlier crash window
   * where {@code AsyncFile.allocateSpace} extended the physical file before
   * {@code EnsurePageIsValidInFileTask} ran, so the trailing bytes are filesystem-zeroed.
   */
  @FunctionalInterface
  private interface OrphanFabricator {
    void fabricate(java.io.File file, int pageSize, int orphanPages) throws java.io.IOException;
  }

  private YouTrackDBImpl youTrackDB;

  /**
   * Defensive pre-clean: if a prior run crashed before {@link #after} fired (JVM kill, OOM,
   * OS reboot), the next run's {@code youTrackDB.create(dbName, ...)} would throw
   * "Database already exists". Mirrors the pre-clean pattern in
   * {@code ProductionAllocatorConcurrencyMTTest.setUp}.
   */
  @Before
  public void cleanupBeforeRun() throws Exception {
    var path = DbTestBase.getBaseDirectoryPath(getClass());
    if (Files.exists(path)) {
      FileUtils.deleteDirectory(path.toFile());
    }
  }

  // ---------------------------------------------------------------------------
  // .pcl (paginated collection) — full ChecksumMode × fabrication-shape matrix
  // ---------------------------------------------------------------------------

  /**
   * Magic-stamped orphan tail on a {@code .pcl} file under {@link ChecksumMode#Off}.
   * The recovery pass must truncate the orphans so the post-reopen file size equals the
   * logical horizon ({@code (epLogicalCounter + 1) * pageSize}) and a subsequent TX must
   * complete without throwing.
   */
  @Test
  public void truncatesOrphansOnPaginatedCollectionFileMagicStampedUnderChecksumOff()
      throws Exception {
    runPaginatedCollectionScenario(ChecksumMode.Off, magicStampedOrphanFabricator());
  }

  /**
   * Magic-stamped orphan tail on a {@code .pcl} file under {@link ChecksumMode#StoreAndThrow}.
   * Pins the recovery pass under the production-CI checksum default: the pass must NOT
   * read orphan-page bodies (which would trip a checksum mismatch and surface a
   * {@code StorageException} under StoreAndThrow); a clean truncate + a non-throwing
   * post-recovery TX is the expected outcome.
   */
  @Test
  public void truncatesOrphansOnPaginatedCollectionFileMagicStampedUnderStoreAndThrow()
      throws Exception {
    runPaginatedCollectionScenario(ChecksumMode.StoreAndThrow, magicStampedOrphanFabricator());
  }

  /**
   * Zero-byte orphan tail on a {@code .pcl} file under {@link ChecksumMode#Off}. Mirrors
   * the crash window between {@code AsyncFile.allocateSpace} and
   * {@code EnsurePageIsValidInFileTask} — the file grows but the trailing pages are
   * filesystem-zeroed (no magic stamp). The recovery pass must still truncate cleanly
   * because it never reads the orphan bodies.
   */
  @Test
  public void truncatesOrphansOnPaginatedCollectionFileZeroByteUnderChecksumOff()
      throws Exception {
    runPaginatedCollectionScenario(ChecksumMode.Off, zeroByteOrphanFabricator());
  }

  /**
   * Zero-byte orphan tail on a {@code .pcl} file under {@link ChecksumMode#StoreAndThrow}.
   * The strictest combination: the pass must truncate without reading the all-zero
   * orphan bodies (a zero magic stamp would otherwise be rejected by
   * {@code WOWCache.verifyMagicChecksumAndDecryptPage}).
   */
  @Test
  public void truncatesOrphansOnPaginatedCollectionFileZeroByteUnderStoreAndThrow()
      throws Exception {
    runPaginatedCollectionScenario(ChecksumMode.StoreAndThrow, zeroByteOrphanFabricator());
  }

  /**
   * Scenario body shared by the four {@code .pcl} variants. Opens a storage under the
   * given {@code checksumMode}, populates a class so its {@code .pcl} file has multiple
   * pages, closes, externally extends the {@code .pcl} file with
   * {@link #ORPHAN_PAGE_COUNT} orphan pages using the given {@code fabricator}, reopens,
   * captures the file size BEFORE any non-recovery TX runs, then drives a follow-up TX
   * to prove the storage remains usable. Asserts the captured file size equals the
   * pre-fabrication size — the orphans must have been truncated by the recovery pass
   * before the post-reopen TX could observe them.
   */
  private void runPaginatedCollectionScenario(
      ChecksumMode checksumMode, OrphanFabricator fabricator) throws Exception {
    var config = makeConfig(checksumMode);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    int pageSize;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("Orphans");
      session.executeInTx(transaction -> {
        for (var i = 0; i < 50; i++) {
          var entity = transaction.newEntity("Orphans");
          entity.setProperty("value", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = collectionFileName(session, wowCache, "Orphans", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var pclPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = pclPath.length();
    fabricator.fabricate(pclPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = pclPath.length();
    assertThat(sizeAfterFabrication)
        .as(".pcl file size must grow by exactly ORPHAN_PAGE_COUNT * pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    forceDirtyReopen(storagePath);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Capture file size BEFORE any non-recovery TX runs — a TX can grow the file by a
      // page, which would mask a missing or partial truncate.
      sizeImmediatelyAfterReopen = pclPath.length();

      // Health check: the first non-recovery TX must not surface IllegalStateException
      // from the physical > logical shape the fabrication just produced, and (under
      // StoreAndThrow) must not surface a checksum-mismatch StorageException from any
      // accidental load of an orphan page.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("Orphans");
        entity.setProperty("value", "post-recovery");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must have truncated the orphan tail back to the logical horizon"
            + " under checksumMode=" + checksumMode)
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // .cpm (CollectionPositionMapV2) — full ChecksumMode × fabrication-shape matrix
  // ---------------------------------------------------------------------------

  /** See {@link #runPositionMapScenario}. */
  @Test
  public void truncatesOrphansOnCollectionPositionMapFileMagicStampedUnderChecksumOff()
      throws Exception {
    runPositionMapScenario(ChecksumMode.Off, magicStampedOrphanFabricator());
  }

  /** See {@link #runPositionMapScenario}. */
  @Test
  public void truncatesOrphansOnCollectionPositionMapFileMagicStampedUnderStoreAndThrow()
      throws Exception {
    runPositionMapScenario(ChecksumMode.StoreAndThrow, magicStampedOrphanFabricator());
  }

  /** See {@link #runPositionMapScenario}. */
  @Test
  public void truncatesOrphansOnCollectionPositionMapFileZeroByteUnderChecksumOff()
      throws Exception {
    runPositionMapScenario(ChecksumMode.Off, zeroByteOrphanFabricator());
  }

  /** See {@link #runPositionMapScenario}. */
  @Test
  public void truncatesOrphansOnCollectionPositionMapFileZeroByteUnderStoreAndThrow()
      throws Exception {
    runPositionMapScenario(ChecksumMode.StoreAndThrow, zeroByteOrphanFabricator());
  }

  /**
   * Scenario body shared by the four {@code .cpm} variants. Drives the position-map half
   * of the PCV2 sibling-truncation hook: opens a storage under {@code checksumMode},
   * allocates many records to force the position map to grow well past its single-page
   * bootstrap, closes, fabricates orphan pages on the {@code .cpm} file, reopens, and
   * asserts the orphans are truncated back to the position map's logical horizon.
   */
  private void runPositionMapScenario(
      ChecksumMode checksumMode, OrphanFabricator fabricator) throws Exception {
    var config = makeConfig(checksumMode);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    int pageSize;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("CpmOrphans");
      session.executeInTx(transaction -> {
        for (var i = 0; i < 200; i++) {
          var entity = transaction.newEntity("CpmOrphans");
          entity.setProperty("value", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var cpmFileName = collectionFileName(session, wowCache, "CpmOrphans", ".cpm");
      var fileId = wowCache.fileIdByName(cpmFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var cpmPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = cpmPath.length();
    fabricator.fabricate(cpmPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = cpmPath.length();
    assertThat(sizeAfterFabrication)
        .as(".cpm file size must grow by exactly ORPHAN_PAGE_COUNT * pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    forceDirtyReopen(storagePath);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = cpmPath.length();

      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("CpmOrphans");
        entity.setProperty("value", "post-recovery");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must truncate the .cpm orphan tail back to the position map's"
            + " logical horizon under checksumMode=" + checksumMode)
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // Clean shutdown / no-op — ChecksumMode matrix only (no fabrication axis)
  // ---------------------------------------------------------------------------

  /**
   * File-shape selector for {@link #runCleanShutdownScenario}. Each entry picks a single
   * native file off the storage's WOWCache that the no-op assertion should verify is
   * unchanged across a clean reopen.
   */
  @FunctionalInterface
  private interface CleanShutdownFileSelector {
    /**
     * Returns the cache-layer file name (NOT the native file name) of the file to assert. The
     * session is supplied so a selector can resolve a class's counter-generated collection file
     * through the schema; extension-keyed selectors ignore it.
     */
    String select(DatabaseSessionEmbedded session, WOWCache cache);
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForPclUnderChecksumOff() throws Exception {
    runCleanShutdownScenario(ChecksumMode.Off, selectClusterPcl());
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForPclUnderStoreAndThrow() throws Exception {
    runCleanShutdownScenario(ChecksumMode.StoreAndThrow, selectClusterPcl());
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForCbtUnderChecksumOff() throws Exception {
    runCleanShutdownScenario(ChecksumMode.Off, selectIndexCbt());
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForCbtUnderStoreAndThrow() throws Exception {
    runCleanShutdownScenario(ChecksumMode.StoreAndThrow, selectIndexCbt());
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForGrbUnderChecksumOff() throws Exception {
    runCleanShutdownScenario(ChecksumMode.Off, selectAnyGrb());
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForGrbUnderStoreAndThrow() throws Exception {
    runCleanShutdownScenario(ChecksumMode.StoreAndThrow, selectAnyGrb());
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForNullCbtUnderChecksumOff() throws Exception {
    runCleanShutdownScenario(ChecksumMode.Off, selectAnyNullCbt());
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenForNullCbtUnderStoreAndThrow() throws Exception {
    runCleanShutdownScenario(ChecksumMode.StoreAndThrow, selectAnyNullCbt());
  }

  /**
   * Scenario body shared by the clean-shutdown variants. Asserts the recovery pass
   * is a strict no-op after a clean close-reopen cycle: the per-component pre-flight
   * skips the shrink dispatch, so the file size must be identical across the boundary.
   * Strict equality catches both the over-shrink direction (a buggy pass that drops
   * live pages — the regression class this matrix guards against) and the no-op
   * direction (the desired clean-case behaviour) — a relaxation to {@code >=} would
   * make the latter unfalsifiable.
   *
   * <p>The {@code fileSelector} picks the native file the assertion runs against. The
   * setup body provisions a schema rich enough for every selector to find its file:
   * one class with a NOTUNIQUE String index produces {@code .pcl} (cluster),
   * {@code .cpm} (position map), {@code .grb} (per-cluster SLBB), and the index's
   * sub-files ({@code <index>.cbt} for the svTree leg and
   * {@code <index>$null.cbt} for the nullTree leg — created by inserting at least
   * one null-keyed entity).
   */
  private void runCleanShutdownScenario(
      ChecksumMode checksumMode, CleanShutdownFileSelector fileSelector) throws Exception {
    var config = makeConfig(checksumMode);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Schema rich enough that every file-shape selector finds its file:
      //   .pcl  — class's primary cluster
      //   .cpm  — sibling of every .pcl (CollectionPositionMapV2)
      //   .grb  — per-cluster SharedLinkBagBTree, created at addCollection()
      //   .cbt  — svTree leg of the NOTUNIQUE index
      //   $null.cbt — nullTree leg, created when at least one null-keyed entity exists
      var schema = session.getMetadata().getSchema();
      var clazz = schema.createClass("CleanShutdown");
      clazz.createProperty("key",
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING);
      clazz.createIndex("CleanShutdown.key",
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE,
          "key");
      session.executeInTx(transaction -> {
        // 20 non-null-keyed entities populate the svTree.
        for (var i = 0; i < 20; i++) {
          var entity = transaction.newEntity("CleanShutdown");
          entity.setProperty("key", "k-" + i);
        }
        // Two null-keyed entities populate the nullTree, so the
        // <index>$null.cbt file actually exists on disk.
        for (var i = 0; i < 2; i++) {
          var entity = transaction.newEntity("CleanShutdown");
          // Don't set "key" — the entity routes through the nullTree on commit.
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var selectedFileName = fileSelector.select(session, wowCache);
      var fileId = wowCache.fileIdByName(selectedFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var selectedPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeReopen = selectedPath.length();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = selectedPath.length();

      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("CleanShutdown");
        entity.setProperty("key", "post-reopen");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("clean reopen must leave the file '%s' size identical to the pre-reopen"
            + " size under checksumMode=%s; an over-shrink here would mean the"
            + " per-component pre-flight dropped live pages on a clean shutdown",
            nativeFileName, checksumMode)
        .isEqualTo(sizeBeforeReopen);
  }

  /** Selector: the {@code CleanShutdown} class's primary {@code .pcl} cluster. */
  private static CleanShutdownFileSelector selectClusterPcl() {
    return (session, cache) -> collectionFileName(session, cache, "CleanShutdown", ".pcl");
  }

  /**
   * Selector: the {@code CleanShutdown.key} index's primary svTree {@code .cbt} data file
   * (the NOTUNIQUE index produces both this and the {@code $null.cbt} nullTree).
   */
  private static CleanShutdownFileSelector selectIndexCbt() {
    return (session, cache) -> cache.files().keySet().stream()
        .filter(name -> name.endsWith(".cbt") && !name.endsWith("$null.cbt"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "No svTree .cbt file in the cache; clean-shutdown setup did not produce"
                + " the expected index file shape"));
  }

  /** Selector: any per-cluster {@code SharedLinkBagBTree} ({@code .grb}) file. */
  private static CleanShutdownFileSelector selectAnyGrb() {
    return (session, cache) -> cache.files().keySet().stream()
        .filter(name -> name.startsWith("global_collection_") && name.endsWith(".grb"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "No .grb file in the cache; clean-shutdown setup did not produce a SLBB"));
  }

  /**
   * Selector: the multi-value engine's nullTree {@code $null.cbt} data file. Present only
   * when the NOTUNIQUE index has at least one null-keyed entity (the setup body inserts
   * two so this file exists reliably).
   */
  private static CleanShutdownFileSelector selectAnyNullCbt() {
    return (session, cache) -> cache.files().keySet().stream()
        .filter(name -> name.endsWith("$null.cbt"))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "No <index>$null.cbt file in the cache; clean-shutdown setup did not"
                + " produce a null-keyed entity to populate the nullTree"));
  }

  // ---------------------------------------------------------------------------
  // .cbt (BTree single-value index engine) — full matrix
  // ---------------------------------------------------------------------------

  /** See {@link #runIndexEngineScenario}. */
  @Test
  public void truncatesOrphansOnIndexEngineFileMagicStampedUnderChecksumOff() throws Exception {
    runIndexEngineScenario(ChecksumMode.Off, magicStampedOrphanFabricator());
  }

  /** See {@link #runIndexEngineScenario}. */
  @Test
  public void truncatesOrphansOnIndexEngineFileMagicStampedUnderStoreAndThrow() throws Exception {
    runIndexEngineScenario(ChecksumMode.StoreAndThrow, magicStampedOrphanFabricator());
  }

  /** See {@link #runIndexEngineScenario}. */
  @Test
  public void truncatesOrphansOnIndexEngineFileZeroByteUnderChecksumOff() throws Exception {
    runIndexEngineScenario(ChecksumMode.Off, zeroByteOrphanFabricator());
  }

  /** See {@link #runIndexEngineScenario}. */
  @Test
  public void truncatesOrphansOnIndexEngineFileZeroByteUnderStoreAndThrow() throws Exception {
    runIndexEngineScenario(ChecksumMode.StoreAndThrow, zeroByteOrphanFabricator());
  }

  /**
   * Scenario body shared by the four {@code .cbt} variants. Pins the orchestrator's
   * {@code instanceof BTreeSingleValueIndexEngine} dispatch through the engine-side
   * {@code verifyAndTruncateOrphans} wrapper: opens a storage with a single unique
   * index, populates enough entities to force the index file past one page, closes,
   * fabricates orphan pages on the {@code .cbt} file, reopens, and asserts the orphan
   * tail is truncated.
   */
  private void runIndexEngineScenario(
      ChecksumMode checksumMode, OrphanFabricator fabricator) throws Exception {
    var config = makeConfig(checksumMode);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    int pageSize;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      var schema = session.getMetadata().getSchema();
      var clazz = schema.createClass("Indexed");
      clazz.createProperty("key",
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING);
      clazz.createIndex("Indexed.key",
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.UNIQUE,
          "key");
      session.executeInTx(transaction -> {
        for (var i = 0; i < 200; i++) {
          var entity = transaction.newEntity("Indexed");
          entity.setProperty("key", "k-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var cbtFileName = wowCache.files().keySet().stream()
          .filter(name -> name.endsWith(".cbt"))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "No .cbt file in the file map; index appears not to have allocated"
                  + " a single-value B-tree on disk"));
      var fileId = wowCache.fileIdByName(cbtFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var cbtPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = cbtPath.length();
    fabricator.fabricate(cbtPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = cbtPath.length();
    assertThat(sizeAfterFabrication)
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    forceDirtyReopen(storagePath);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = cbtPath.length();

      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("Indexed");
        entity.setProperty("key", "post-recovery");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must truncate the orphan tail back to the index's logical horizon"
            + " under checksumMode=" + checksumMode)
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // SharedLinkBagBTree (.grb) — full ChecksumMode × fabrication-shape matrix
  //
  // Every paginated collection creates one SLBB file at addCollection() time
  // (see AbstractStorage.doAddCollection -> linkCollectionsBTreeManager.createComponent),
  // named "global_collection_<id>.grb". The freshly-created file holds two pages — the
  // entry point at index 0 (pagesSize=1) and the root bucket at index 1 — so the logical
  // horizon enforced by the recovery pass is targetBytes = (1 + 1) * pageSize.
  // The dispatch path under test is AbstractStorage.truncateOrphansAfterRecovery's
  // Group 3 — linkCollectionsBTreeManager.verifyAndTruncateAllOrphans() iterates the
  // manager's private fileIdBTreeMap, so this scenario pins that iteration delegate.
  // ---------------------------------------------------------------------------

  /** See {@link #runSharedLinkBagBTreeScenario}. */
  @Test
  public void truncatesOrphansOnSharedLinkBagBTreeFileMagicStampedUnderChecksumOff()
      throws Exception {
    runSharedLinkBagBTreeScenario(ChecksumMode.Off, magicStampedOrphanFabricator());
  }

  /** See {@link #runSharedLinkBagBTreeScenario}. */
  @Test
  public void truncatesOrphansOnSharedLinkBagBTreeFileMagicStampedUnderStoreAndThrow()
      throws Exception {
    runSharedLinkBagBTreeScenario(ChecksumMode.StoreAndThrow, magicStampedOrphanFabricator());
  }

  /** See {@link #runSharedLinkBagBTreeScenario}. */
  @Test
  public void truncatesOrphansOnSharedLinkBagBTreeFileZeroByteUnderChecksumOff()
      throws Exception {
    runSharedLinkBagBTreeScenario(ChecksumMode.Off, zeroByteOrphanFabricator());
  }

  /** See {@link #runSharedLinkBagBTreeScenario}. */
  @Test
  public void truncatesOrphansOnSharedLinkBagBTreeFileZeroByteUnderStoreAndThrow()
      throws Exception {
    runSharedLinkBagBTreeScenario(ChecksumMode.StoreAndThrow, zeroByteOrphanFabricator());
  }

  /**
   * Scenario body shared by the four {@code .grb} variants. Pins Group 3 of the
   * orphan-truncation orchestrator — the {@code LinkCollectionsBTreeManagerShared}
   * iteration delegate that fans out over every loaded {@link
   * com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree.SharedLinkBagBTree}.
   *
   * <p>Opens a storage under the given {@code checksumMode}, creates a class (which
   * implicitly registers a paginated collection and the associated SLBB), closes,
   * fabricates orphan pages on the SLBB's {@code .grb} file, reopens, and asserts the
   * orphans are truncated back to the SLBB's logical horizon. The SLBB is the only
   * recovery-pass target that exposes no public iteration accessor on the manager, so
   * its dispatch coverage rests on this scenario alone.
   */
  private void runSharedLinkBagBTreeScenario(
      ChecksumMode checksumMode, OrphanFabricator fabricator) throws Exception {
    var config = makeConfig(checksumMode);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    int pageSize;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Creating a class allocates a cluster, which in turn creates the per-cluster SLBB
      // via AbstractStorage.doAddCollection. No edges / link-bags are needed here — the
      // pristine SLBB carries the EP-and-root two-page footprint the recovery pass
      // dispatches against, which is exactly the surface we want to exercise.
      session.getMetadata().getSchema().createClass("GrbOrphans");
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      // SLBB files use a fixed prefix unrelated to the class name — see
      // LinkCollectionsBTreeManagerShared.FILE_NAME_PREFIX = "global_collection_" and
      // FILE_EXTENSION = ".grb". Picking the first match is sufficient: the per-collection
      // truncate is independent and the manager iterates internally in undefined order.
      var grbFileName = wowCache.files().keySet().stream()
          .filter(name -> name.startsWith("global_collection_") && name.endsWith(".grb"))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "No .grb file in the file map; SLBB component appears not to have been"
                  + " created for the new cluster"));
      var fileId = wowCache.fileIdByName(grbFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var grbPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = grbPath.length();
    fabricator.fabricate(grbPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = grbPath.length();
    assertThat(sizeAfterFabrication)
        .as(".grb file size must grow by exactly ORPHAN_PAGE_COUNT * pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    forceDirtyReopen(storagePath);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = grbPath.length();

      // Health check: a non-recovery TX on the same cluster must complete without the
      // physical>logical IllegalStateException the fabrication just produced, and (under
      // StoreAndThrow) without a checksum-mismatch StorageException from any accidental
      // load of an orphan page on the .grb file.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("GrbOrphans");
        entity.setProperty("value", "post-recovery");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must truncate the .grb orphan tail back to the SLBB's logical horizon"
            + " under checksumMode=" + checksumMode)
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // Multi-value B-tree null-key sub-tree — full ChecksumMode × fabrication-shape matrix
  //
  // A NOTUNIQUE index over a String property routes through BTreeMultiValueIndexEngine,
  // which holds two CellBTreeSingleValue trees: svTree for non-null keys (covered by the
  // .cbt scenarios above) and nullTree for null-keyed entries. The nullTree's primary
  // data file is named "<index>$null.cbt" (NULL_TREE_SUFFIX = "$null"; data extension is
  // DATA_FILE_EXTENSION = ".cbt"). The companion null-bucket file ".nbt" is a single page
  // by construction and never grows — the page-extension surface is the nullTree's .cbt.
  //
  // The dispatch path under test is AbstractStorage.truncateOrphansAfterRecovery's Group
  // 2 BTreeMultiValueIndexEngine branch, which fans out to nullTree.verifyAndTruncateOrphans
  // (see BTreeMultiValueIndexEngine.java:355-357). The svTree leg is already pinned by
  // the .cbt scenarios above; this scenario pins the previously-unexercised nullTree leg.
  // ---------------------------------------------------------------------------

  /** See {@link #runMultiValueNullSubTreeScenario}. */
  @Test
  public void truncatesOrphansOnMultiValueNullSubTreeFileMagicStampedUnderChecksumOff()
      throws Exception {
    runMultiValueNullSubTreeScenario(ChecksumMode.Off, magicStampedOrphanFabricator());
  }

  /** See {@link #runMultiValueNullSubTreeScenario}. */
  @Test
  public void truncatesOrphansOnMultiValueNullSubTreeFileMagicStampedUnderStoreAndThrow()
      throws Exception {
    runMultiValueNullSubTreeScenario(ChecksumMode.StoreAndThrow, magicStampedOrphanFabricator());
  }

  /** See {@link #runMultiValueNullSubTreeScenario}. */
  @Test
  public void truncatesOrphansOnMultiValueNullSubTreeFileZeroByteUnderChecksumOff()
      throws Exception {
    runMultiValueNullSubTreeScenario(ChecksumMode.Off, zeroByteOrphanFabricator());
  }

  /** See {@link #runMultiValueNullSubTreeScenario}. */
  @Test
  public void truncatesOrphansOnMultiValueNullSubTreeFileZeroByteUnderStoreAndThrow()
      throws Exception {
    runMultiValueNullSubTreeScenario(ChecksumMode.StoreAndThrow, zeroByteOrphanFabricator());
  }

  /**
   * Scenario body shared by the four multi-value-engine null-sub-tree variants. Pins the
   * nullTree leg of {@code BTreeMultiValueIndexEngine.verifyAndTruncateOrphans} —
   * previously exercised at the unit level by mocks only (no IT touches a real
   * {@code <index>$null.cbt} file with a physical-orphan tail). Opens a storage under
   * the given {@code checksumMode}, creates a NOTUNIQUE index over a String property,
   * inserts entities with {@code null} for that property until the nullTree's
   * {@code .cbt} file grows past its EP-and-root two-page footprint (so a truncate is
   * actually exercised), closes, fabricates orphans on that file, reopens, and asserts
   * the orphan tail is truncated back to the nullTree's logical horizon.
   */
  private void runMultiValueNullSubTreeScenario(
      ChecksumMode checksumMode, OrphanFabricator fabricator) throws Exception {
    var config = makeConfig(checksumMode);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    int pageSize;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      var schema = session.getMetadata().getSchema();
      var clazz = schema.createClass("NullIndexed");
      clazz.createProperty("key",
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType.STRING);
      // NOTUNIQUE routes through BTreeMultiValueIndexEngine, which keeps a separate
      // nullTree for null-keyed entries (BTreeMultiValueIndexEngine.java:43,82-85).
      clazz.createIndex("NullIndexed.key",
          com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE.NOTUNIQUE,
          "key");
      // 800 null-keyed entities is empirically enough to force the nullTree's .cbt file
      // past one data page in addition to its EP — a single leaf bucket holds far fewer
      // than 800 entries with the [LINK, LONG, LONG] composite-key layout that the null
      // sub-tree uses (BTreeMultiValueIndexEngine.java:120-123 pins keySize=2 with a LINK
      // + LONG layout, plus the auto-appended timestamp). The exact split count is
      // page-size dependent, so we err on the high side; the assertion that the orphans
      // grew the file guards against accidental short-circuit reductions.
      session.executeInTx(transaction -> {
        for (var i = 0; i < 800; i++) {
          var entity = transaction.newEntity("NullIndexed");
          // Don't set the "key" property — the index key is null, so the row routes
          // through nullTree on commit.
          entity.setProperty("payload", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      // The nullTree's data file is "<index>$null.cbt"; pick the one that ends with the
      // composite "$null.cbt" suffix so it does not collide with the svTree's plain
      // "<index>.cbt" sibling (which the .cbt scenarios above already pin).
      var nullCbtFileName = wowCache.files().keySet().stream()
          .filter(name -> name.endsWith("$null.cbt"))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "No <index>$null.cbt file in the file map; the multi-value engine's null"
                  + " sub-tree appears not to have been created on disk"));
      var fileId = wowCache.fileIdByName(nullCbtFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var nullCbtPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = nullCbtPath.length();
    fabricator.fabricate(nullCbtPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = nullCbtPath.length();
    assertThat(sizeAfterFabrication)
        .as("<index>$null.cbt file size must grow by exactly ORPHAN_PAGE_COUNT * pageSize"
            + " after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    forceDirtyReopen(storagePath);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = nullCbtPath.length();

      // Health check: a follow-up null-keyed insert must complete without the
      // physical>logical IllegalStateException or (under StoreAndThrow) a checksum
      // mismatch from any accidental load of an orphan page on the null sub-tree.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("NullIndexed");
        entity.setProperty("payload", "post-recovery");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must truncate the <index>$null.cbt orphan tail back to the null"
            + " sub-tree's logical horizon under checksumMode=" + checksumMode)
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a configuration that selects the given checksum mode, disables the double
   * write log, and pins one collection per class. The recovery pass itself is
   * checksum-agnostic: it only reads the EP page (which is valid because the production
   * stack wrote it), then dispatches a shrink that does not read the orphan pages at all,
   * so the mode parameter is the lever for "did the pass accidentally start reading orphan
   * bodies?" regression detection.
   *
   * <p>The single-collection pin ({@link GlobalConfiguration#CLASS_COLLECTIONS_COUNT} = 1)
   * is load-bearing for the {@code .pcl} and {@code .cpm} scenarios. At the default count
   * (8) a class is backed by eight collections, and the {@code "default"} collection
   * selection strategy routes every inserted record to {@code collectionIds[0]}, leaving
   * the other seven {@code .pcl}/{@code .cpm} files empty (state-page {@code fileSize == 0},
   * one physical page). The scenario helpers select their target file with
   * {@code files().keySet().stream()...findFirst()}, whose order is the file map's hash
   * order rather than the insertion target, so they could land on an empty cluster. An
   * orphan tail fabricated on a logical-empty file trips the recovery pass's intentional
   * corruption guard ({@code logicalPages == 0 && physical > 1 page} skips the truncate
   * with a WARN) instead of the truncate path the size assertion expects, producing a
   * flaky failure that depends only on which collection the hash order surfaced first.
   * Pinning a single collection guarantees the selected file carries the inserted data
   * ({@code logicalPages >= 1}), so the truncate path is the one under test.
   *
   * <p>The index and SLBB scenarios ({@code .cbt}, {@code $null.cbt}, {@code .grb}) were
   * never exposed to this race, even though they use the same {@code findFirst()} selection
   * pattern: an index engine is one structure per class (not multiplied by the collection
   * count), and its data file always carries real entries, so {@code logicalPages >= 1}; a
   * SLBB's EP {@code init()} seeds {@code pagesSize} at 1, so even a record-free SLBB reports
   * {@code logicalPages == 1} and never matches the {@code logicalPages == 0} guard clause.
   * Only PCV2 and CPMV2 EPs seed their counter at 0, which is the legitimate empty-cluster
   * state the guard is designed to leave untouched. For those scenarios the pin is therefore
   * harmless but still useful: with one collection per class there is exactly one matching
   * file, so the {@code findFirst()} selection is deterministic rather than hash-order
   * dependent.
   */
  private static BaseConfiguration makeConfig(ChecksumMode checksumMode) {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(), checksumMode.name());
    config.setProperty(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG.getKey(), false);
    config.setProperty(GlobalConfiguration.CLASS_COLLECTIONS_COUNT.getKey(), 1);
    return config;
  }

  /**
   * Returns a fabricator that extends the file with {@code orphanPages} pages carrying
   * the WOWCache "without-checksum" magic stamp and LSN {@code (-1, -1)} — exactly the
   * byte layout {@code EnsurePageIsValidInFileTask.writeValidPageInFile} produces for a
   * gap-fill on a fresh page allocation. The pages look "valid but blank" to subsequent
   * reads, so the recovery pass's downstream loads (if any) under any checksum mode
   * would not trip on a corruption check.
   *
   * <p>The file layout is: {@link File#HEADER_SIZE} bytes of header, then {@code N} data
   * pages of {@code pageSize} bytes each. The fabrication appends to the end of the file
   * with a fresh {@link RandomAccessFile} write.
   */
  private static OrphanFabricator magicStampedOrphanFabricator() {
    return (file, pageSize, orphanPages) -> {
      var page = ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());

      DurablePage.setLogSequenceNumberForPage(page, new LogSequenceNumber(-1, -1));
      page.putLong(0, MAGIC_NUMBER_WITHOUT_CHECKSUM);

      try (var raf = new RandomAccessFile(file, "rw")) {
        raf.seek(raf.length());
        for (var i = 0; i < orphanPages; i++) {
          page.position(0);
          var bytes = new byte[pageSize];
          page.get(bytes);
          raf.write(bytes);
        }
      }
    };
  }

  /**
   * Returns a fabricator that extends the file by {@code orphanPages * pageSize} bytes
   * via {@link RandomAccessFile#setLength(long)}. The trailing bytes are
   * filesystem-zeroed — no magic stamp, no LSN, no checksum. This mirrors a strictly
   * earlier crash window than the magic-stamped helper: {@code AsyncFile.allocateSpace}
   * extended the physical file counter but {@code EnsurePageIsValidInFileTask} never
   * ran. The recovery pass only reads the EP page and then dispatches a shrink that
   * doesn't load the tail, so the missing magic stamp is invisible to it — failing this
   * direction would mean a future change introduced an eager orphan-page read pre-shrink.
   */
  private static OrphanFabricator zeroByteOrphanFabricator() {
    return (file, pageSize, orphanPages) -> {
      try (var raf = new RandomAccessFile(file, "rw")) {
        raf.setLength(raf.length() + (long) orphanPages * pageSize);
      }
    };
  }

  /**
   * Resolves the exact write-cache file name of {@code className}'s primary collection with the
   * given extension. Collection names are counter-generated ({@code c_<n>}) with no class-name
   * component since the base-keyed-engine-files rename made a class rename file-inert, so the file
   * must be resolved through the class's collection id rather than matched by class-name prefix
   * (the old helper's shape). Asserts the file is present in the write cache so a missing file
   * still fails as loudly as the old prefix scan did. These scenarios run with
   * CLASS_COLLECTIONS_COUNT=1 ({@code makeConfig}), so the class's single collection id is
   * deterministic. Mirrors the helper in {@code StorageTestIT}.
   */
  private static String collectionFileName(
      DatabaseSessionEmbedded session, WriteCache cache, String className, String extension) {
    var clazz = session.getMetadata().getSchema().getClass(className);
    assertThat(clazz)
        .as("class '%s' must exist to resolve its collection file", className)
        .isNotNull();
    var fileName = session.getCollectionNameById(clazz.getCollectionIds()[0]) + extension;
    assertThat(cache.files().keySet())
        .as("the %s file of class '%s' must be present in the write cache under its"
            + " counter-generated collection name", extension, className)
        .contains(fileName);
    return fileName;
  }

  /**
   * Forces the next open of the storage at {@code storagePath} onto the WAL-replay
   * (dirty) recovery path by deleting the {@code dirty.fl} / {@code dirty.flb} clean-shutdown
   * markers a graceful close left behind.
   *
   * <p>These scenarios fabricate an orphan tail on a gracefully-closed file and then reopen.
   * A graceful close clears the dirty markers, so without this step the reopen is clean
   * ({@code wereDataRestoredAfterOpen == false}). The open-time orphan pass is gated on that
   * flag, so on a clean reopen it does not run and the truncate assertions below would never
   * see the orphan tail removed. Deleting the markers reproduces the same on-disk signal the
   * WAL-replay harness in {@code LocalPaginatedStorageRestoreFromWALIT} relies on (it copies
   * every storage file except {@code dirty.fl}): on the next open
   * {@code DiskStorage.checkIfStorageDirty} finds no marker, re-creates it as dirty, and
   * {@code recoverIfNeeded} replays the WAL and sets {@code wereDataRestoredAfterOpen}. The
   * data files were fully flushed by the graceful close, so the replay redoes already-applied
   * page operations and does not touch the fabricated orphan tail; the gated recovery pass is
   * what truncates it.
   *
   * <p>{@code dirty.flb} is the backup copy {@code StorageStartupMetadata} consults when the
   * primary marker is missing, so both are removed to keep the dirty signal unambiguous.
   */
  private static void forceDirtyReopen(Path storagePath) throws IOException {
    Files.deleteIfExists(storagePath.resolve("dirty.fl"));
    Files.deleteIfExists(storagePath.resolve("dirty.flb"));
  }

  // ===========================================================================
  // Open-time dispatch gate (YTDB-1039): the recovery-time orphan pass runs only
  // when this open replayed the WAL (a crash reopen). A gracefully-closed disk
  // database that replays no WAL skips the pass entirely, so reopen cost no longer
  // scales with collection count. The scenarios below pin both directions of that
  // gate. The observation hook is the package-private dispatch counter on
  // AbstractStorage (orphanTruncationDispatchCountForTests): file size alone cannot
  // tell "pass skipped" from "pass ran and truncated nothing", so the counter is the
  // load-bearing assertion; the public wereDataRestoredAfterOpen() getter (the gate's
  // own predicate) is asserted alongside as a corroborating signal.
  // ===========================================================================

  /**
   * Crash (WAL-replay) reopen with a real physical orphan: the pass MUST run and MUST
   * re-establish the {@code logical <= physical} invariant. Fabricates a magic-stamped
   * orphan tail on a {@code .pcl} file, forces a dirty reopen, then asserts (1) the open
   * replayed the WAL ({@code wereDataRestoredAfterOpen() == true}), (2) the orphan pass was
   * dispatched at least once on the reopened storage instance, and (3) the orphan tail was
   * truncated back to the logical horizon (physical {@code ==} the pre-fabrication size).
   * This is the positive leg of the gate: dirty reopen implies the pass runs and repairs.
   */
  @Test
  public void dirtyReopenRunsPassAndReestablishesLogicalHorizon() throws Exception {
    var config = makeConfig(ChecksumMode.StoreAndThrow);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    int pageSize;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("DirtyReopen");
      session.executeInTx(transaction -> {
        for (var i = 0; i < 50; i++) {
          var entity = transaction.newEntity("DirtyReopen");
          entity.setProperty("value", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = collectionFileName(session, wowCache, "DirtyReopen", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var pclPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = pclPath.length();
    magicStampedOrphanFabricator().fabricate(pclPath, pageSize, ORPHAN_PAGE_COUNT);
    assertThat(pclPath.length())
        .as(".pcl file must grow by ORPHAN_PAGE_COUNT * pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    forceDirtyReopen(storagePath);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    boolean walReplayed;
    int dispatchCount;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = pclPath.length();
      var storage = (DiskStorage) session.getStorage();
      walReplayed = storage.wereDataRestoredAfterOpen();
      dispatchCount = ((AbstractStorage) storage).orphanTruncationDispatchCountForTests();
    }

    assertThat(walReplayed)
        .as("a marker-deleted reopen must take the WAL-replay recovery path"
            + " (wereDataRestoredAfterOpen == true)")
        .isTrue();
    assertThat(dispatchCount)
        .as("a dirty reopen must dispatch the orphan-truncation pass at least once")
        .isGreaterThanOrEqualTo(1);
    assertThat(sizeImmediatelyAfterReopen)
        .as("the pass must truncate the orphan tail back to the logical horizon")
        .isEqualTo(sizeBeforeFabrication);
  }

  /**
   * Empty-WAL dirty reopen boundary: a crash reopen of an orphan-FREE database must STILL
   * dispatch the pass. Pins "dirty implies the pass runs" independent of whether any orphan
   * is present, so a future change that gated the dispatch on orphan presence (rather than
   * on WAL replay) would fail here. Creates a database, closes gracefully, deletes the
   * clean-shutdown markers to force a WAL replay (no fabrication), reopens, and asserts the
   * pass was dispatched, the open replayed the WAL, and the file is unchanged
   * (physical {@code ==} logical - there was no orphan to truncate).
   */
  @Test
  public void dirtyReopenWithoutOrphanStillRunsPass() throws Exception {
    var config = makeConfig(ChecksumMode.StoreAndThrow);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("EmptyWalDirty");
      session.executeInTx(transaction -> {
        for (var i = 0; i < 20; i++) {
          var entity = transaction.newEntity("EmptyWalDirty");
          entity.setProperty("value", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = collectionFileName(session, wowCache, "EmptyWalDirty", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var pclPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeReopen = pclPath.length();

    // No fabrication: the database is orphan-free. Forcing a dirty reopen exercises the
    // "dirty implies pass runs" boundary on a clean-but-replayed storage.
    forceDirtyReopen(storagePath);
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    boolean walReplayed;
    int dispatchCount;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = pclPath.length();
      var storage = (DiskStorage) session.getStorage();
      walReplayed = storage.wereDataRestoredAfterOpen();
      dispatchCount = ((AbstractStorage) storage).orphanTruncationDispatchCountForTests();
    }

    assertThat(walReplayed)
        .as("a marker-deleted reopen must take the WAL-replay recovery path"
            + " (wereDataRestoredAfterOpen == true) even with no orphan present")
        .isTrue();
    assertThat(dispatchCount)
        .as("the pass must be dispatched on a dirty reopen regardless of orphan presence")
        .isGreaterThanOrEqualTo(1);
    assertThat(sizeImmediatelyAfterReopen)
        .as("an orphan-free dirty reopen must leave the .pcl file size unchanged")
        .isEqualTo(sizeBeforeReopen);
  }

  /**
   * Clean reopen of a rolled-back session: no orphan exists AND the pass is skipped. Runs an
   * {@code executeInTx} that allocates rows then throws (forcing a rollback), closes
   * gracefully, then reopens WITHOUT deleting the clean-shutdown markers (a genuine clean
   * reopen). Asserts (1) the open did NOT replay the WAL
   * ({@code wereDataRestoredAfterOpen() == false}), (2) the orphan pass was NOT dispatched
   * (dispatch counter {@code == 0} on the reopened storage instance), (3) no orphan
   * exists (the {@code .pcl} file size is unchanged across the reopen boundary), and (4) the
   * rolled-back transaction's inserts were discarded (the reopened database holds zero
   * {@code RolledBack} rows). The dispatch counter is the load-bearing assertion: it
   * distinguishes "pass skipped" from "pass ran and found nothing", which the file size alone
   * cannot.
   */
  @Test
  public void cleanReopenAfterRollbackSkipsPassAndLeavesNoOrphan() throws Exception {
    var config = makeConfig(ChecksumMode.StoreAndThrow);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("RolledBack");
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = collectionFileName(session, wowCache, "RolledBack", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      storagePath = storage.getStoragePath();

      // Allocate-then-rollback: the consumer inserts many rows (forcing the cluster to
      // grow), then throws. executeInTx rolls the transaction back, so the physical-apply
      // path (which runs only inside commitChanges) never executes and the rolled-back op
      // leaves no committed rows (the load-bearing premise of the open-time gate,
      // YTDB-1039). The throw is expected; we swallow it and continue. The primary proof of
      // the rollback-zero-footprint premise is the unit test
      // EndAtomicOperationRollbackSkipsCommitTest; this IT corroborates it at the logical
      // level (the rolled-back rows are absent after a clean reopen, asserted below).
      //
      // We deliberately do NOT assert the physical .pcl size is flat after the rollback. A
      // fresh cluster eagerly allocates its first data page when the first row is inserted,
      // and WOWCache may flush that page to disk on its background thread at any point during
      // the doomed TX. The rollback restores the logical horizon but does not physically
      // truncate an already-flushed page (a tolerated physical orphan; see
      // cleanReopenDoesNotRepairPreExistingPhysicalOrphan). File.length() after the rollback
      // therefore depends on background-flush timing, not on the rollback outcome, so it
      // raced the old assertion on macOS arm / JDK 21 (observed 9216 vs the 1024 baseline).
      // The logical row count is the deterministic invariant the rollback actually
      // guarantees.
      try {
        session.executeInTx(transaction -> {
          insertGrowthWorkload(transaction, "RolledBack");
          throw new IllegalStateException("intentional rollback trigger");
        });
        org.junit.Assert.fail("executeInTx should have propagated the rollback trigger");
      } catch (final IllegalStateException expected) {
        // expected - the transaction rolled back.
      }
    }
    youTrackDB.close();

    var pclPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeReopen = pclPath.length();

    // Genuine clean reopen: do NOT call forceDirtyReopen - the graceful close left the
    // clean-shutdown markers in place, so this open replays no WAL and the gate skips the
    // pass.
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    boolean walReplayed;
    int dispatchCount;
    long rolledBackRowCount;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = pclPath.length();
      var storage = (DiskStorage) session.getStorage();
      walReplayed = storage.wereDataRestoredAfterOpen();
      dispatchCount = ((AbstractStorage) storage).orphanTruncationDispatchCountForTests();
      // The deterministic rollback guarantee: the doomed TX's inserts were discarded, so the
      // cleanly-reopened database holds zero RolledBack rows. Read after the reopen so the
      // check is end-to-end (the discard survived close and reopen). The class itself was
      // created OUTSIDE the doomed TX, so it must still exist on reopen; assert that first so
      // a zero count can never be produced by an absent class (which would make the row-count
      // assertion vacuous). The non-vacuousness of the count != 0 direction is pinned by
      // committedAllocationGrowsPclSoRollbackAssertionIsNonVacuous, which runs the same
      // count(*) query against a COMMITTED population and asserts it reads GROWTH_ROW_COUNT.
      assertThat(session.getMetadata().getSchema().getClass("RolledBack"))
          .as("the RolledBack class is created outside the doomed TX and must survive reopen")
          .isNotNull();
      try (var rs = session.query("SELECT count(*) as cnt FROM RolledBack")) {
        final long cnt = rs.next().getProperty("cnt");
        rolledBackRowCount = cnt;
      }
    }

    assertThat(walReplayed)
        .as("a gracefully-closed reopen must NOT replay the WAL")
        .isFalse();
    assertThat(dispatchCount)
        .as("the orphan-truncation pass must be SKIPPED on a clean reopen")
        .isZero();
    assertThat(sizeImmediatelyAfterReopen)
        .as("the clean reopen must be a physical no-op (.pcl size unchanged across"
            + " close->reopen); this does NOT assert the doomed TX left no flushed page -- a"
            + " tolerated physical orphan is allowed, see"
            + " cleanReopenDoesNotRepairPreExistingPhysicalOrphan")
        .isEqualTo(sizeBeforeReopen);
    assertThat(rolledBackRowCount)
        .as("the rolled-back transaction must leave no committed rows -- the rollback"
            + " discarded its would-be footprint (the deterministic invariant; the physical"
            + " .pcl size is background-flush-timing dependent, see the doomed-TX comment)")
        .isZero();
  }

  /**
   * Positive control for {@link #cleanReopenAfterRollbackSkipsPassAndLeavesNoOrphan}: the
   * SAME growth workload, when COMMITTED, physically grows the {@code .pcl} file. Without
   * this, the rolled-back variant's zero-row assertion could not distinguish "the rollback
   * discarded a real would-be footprint" from "this workload never allocates anything at
   * all": a workload that persisted nothing would make the rollback assertion vacuously
   * true. The shared {@link #insertGrowthWorkload} writes enough payload to span several
   * data pages, so this control proves the committed workload genuinely allocates a
   * substantial footprint, and the rolled-back variant's zero committed rows therefore
   * reflect a discarded real allocation, not the absence of any work.
   */
  @Test
  public void committedAllocationGrowsPclSoRollbackAssertionIsNonVacuous() throws Exception {
    var config = makeConfig(ChecksumMode.StoreAndThrow);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("Committed");
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = collectionFileName(session, wowCache, "Committed", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      var nativeFileName = wowCache.nativeFileNameById(fileId);
      var pclPath = storage.getStoragePath().resolve(nativeFileName).toFile();
      long sizeBeforeCommit = pclPath.length();

      // The same growth workload the rolled-back variant uses, but committed: executeInTx
      // returns normally, so the physical-apply path inside commitChanges runs and the
      // cluster's .pcl grows past its initial page.
      session.executeInTx(transaction -> insertGrowthWorkload(transaction, "Committed"));

      // commitChanges writes the new data pages into the WOWCache but does NOT physically
      // extend the .pcl on disk synchronously - the dirty pages are flushed lazily by the
      // background commit thread (or at checkpoint/close). File.length() reads the on-disk
      // size, so without an explicit flush the observed growth depends on background-flush
      // timing, which varies by platform/JDK (it raced behind the assertion on macOS arm /
      // JDK 25, where the file was still at its pre-commit size). Force the file's buffered
      // pages out synchronously so the physical size deterministically reflects the
      // committed allocation before we measure it.
      wowCache.flush(fileId);

      assertThat(pclPath.length())
          .as("the committed growth workload must physically grow the .pcl -- otherwise the"
              + " rolled-back variant's zero-row assertion would be vacuous")
          .isGreaterThan(sizeBeforeCommit);

      // Close the logical loop: the SAME count(*) query the rolled-back variant relies on
      // must read GROWTH_ROW_COUNT against a committed population. This proves the query can
      // return non-zero, so the rolled-back variant's count(*) == 0 is a real discard signal,
      // not a query that structurally always yields zero.
      try (var rs = session.query("SELECT count(*) as cnt FROM Committed")) {
        final long committedRowCount = rs.next().getProperty("cnt");
        assertThat(committedRowCount)
            .as("the committed growth workload must persist every row -- pins the non-vacuous"
                + " (count != 0) direction of the rolled-back variant's row-count assertion")
            .isEqualTo(GROWTH_ROW_COUNT);
      }
    }
  }

  /**
   * The defining behavior of the open-time gate (YTDB-1039): a CLEAN reopen does NOT repair a
   * pre-existing physical orphan. This is the dual of the migrated fabrication scenarios
   * (which delete the clean-shutdown markers to force a dirty reopen): here we fabricate a
   * magic-stamped orphan tail on a gracefully-closed {@code .pcl} file and reopen WITHOUT
   * deleting the markers, so the open replays no WAL and the pass is skipped. The fabricated
   * orphan tail therefore SURVIVES - the file stays at the fabricated size, NOT shrunk back
   * to the logical horizon. This pins the gate's contract and fails if a future change
   * re-runs the pass on the clean path or misclassifies a clean reopen as dirty.
   *
   * <p>A fabricated-orphan-on-clean-close is not a state the production stack can actually
   * produce (a rolled-back op leaves zero footprint and a graceful close flushes nothing
   * past the logical horizon); the fabrication is purely a probe for "did the pass run on
   * the clean path?" The companion {@link #cleanReopenAfterRollbackSkipsPassAndLeavesNoOrphan}
   * scenario covers the realistic clean-close shape (rollback leaves no orphan); this one
   * isolates the gate's skip decision from orphan presence.
   */
  @Test
  public void cleanReopenDoesNotRepairPreExistingPhysicalOrphan() throws Exception {
    var config = makeConfig(ChecksumMode.StoreAndThrow);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    int pageSize;
    Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("SurvivingOrphan");
      session.executeInTx(transaction -> {
        for (var i = 0; i < 50; i++) {
          var entity = transaction.newEntity("SurvivingOrphan");
          entity.setProperty("value", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = collectionFileName(session, wowCache, "SurvivingOrphan", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var pclPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = pclPath.length();
    magicStampedOrphanFabricator().fabricate(pclPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = pclPath.length();
    assertThat(sizeAfterFabrication)
        .as(".pcl file must grow by ORPHAN_PAGE_COUNT * pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    // Clean reopen: do NOT delete the clean-shutdown markers. The open replays no WAL, so
    // the gate skips the pass and the fabricated orphan tail must remain on disk.
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    boolean walReplayed;
    int dispatchCount;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Capture file size BEFORE any non-recovery TX runs. We deliberately do NOT run a
      // follow-up TX here: with the orphan tail still present, the first non-recovery TX
      // would observe the physical > logical shape and surface an IllegalStateException
      // (which is exactly the state the dirty-path pass exists to prevent). The point of
      // this scenario is only that the clean reopen left the orphan in place.
      sizeImmediatelyAfterReopen = pclPath.length();
      var storage = (DiskStorage) session.getStorage();
      walReplayed = storage.wereDataRestoredAfterOpen();
      dispatchCount = ((AbstractStorage) storage).orphanTruncationDispatchCountForTests();
    }

    assertThat(walReplayed)
        .as("a gracefully-closed reopen must NOT replay the WAL")
        .isFalse();
    assertThat(dispatchCount)
        .as("the orphan-truncation pass must be SKIPPED on a clean reopen")
        .isZero();
    assertThat(sizeImmediatelyAfterReopen)
        .as("a clean reopen must NOT truncate a pre-existing physical orphan tail -"
            + " the fabricated tail must survive at the fabricated size")
        .isEqualTo(sizeAfterFabrication);
  }

  /**
   * WARN-on-truncate-failure: a transient physical-truncate failure on an otherwise readable
   * component must be logged as a WARN and must not abort the rest of the recovery pass. The
   * open-time gate (YTDB-1039) drops the cross-clean-cycle retry of such a transient failure;
   * the accepted bound is that the failure is operator-visible (a WARN at the reopen where it
   * occurred) and re-armed by any later crash. This test pins that visibility.
   *
   * <p>The failure is injected at the {@code readCache.shrinkFile} layer (the call
   * {@code StorageComponent.verifyAndTruncateOrphans} makes, backed by
   * {@code WOWCache.shrinkFile} -> {@code AsyncFile.shrink}): a {@link PaginatedCollectionV2}
   * whose {@code verifyAndTruncateOrphans} throws an {@link IOException} stands in for a real
   * shrink that failed mid-truncate. The orchestrator
   * {@code AbstractStorage.truncateOrphansAfterRecovery} is exercised directly via a
   * {@code CALLS_REAL_METHODS} Mockito instance (the same harness shape as the sibling
   * orchestrator unit test) so the real catch-and-WARN branch executes; a JUL handler
   * attached to the root logger captures the emitted WARN record. The DB-less harness is the
   * least invasive way to make a {@code shrinkFile} failure surface deterministically - a
   * real {@code AsyncFile.shrink} cannot be forced to throw transiently on a healthy file.
   *
   * <p>This DB-less Mockito test rides in the IT (rather than the unit harness) so it
   * resolves the integration-test module's SLF4J-to-JUL binding (slf4j-jdk14): the
   * orchestrator routes its WARN through {@code LogManager} -> SLF4J, and only the JUL
   * binding lets the {@link CapturingHandler} on the root logger observe it. It mirrors the
   * sibling unit harness {@code AbstractStorageTruncateOrphansAfterRecoveryTest}, which
   * drives the same orchestrator method on a {@code CALLS_REAL_METHODS} mock.
   */
  @Test
  public void warnLoggedWhenTruncateFailsDuringRecoveryPass() throws Exception {
    var rootLogger = Logger.getLogger("");
    var captured = new CapturingHandler();
    rootLogger.addHandler(captured);
    var previousLevel = rootLogger.getLevel();
    rootLogger.setLevel(Level.ALL);
    try {
      var storage =
          mock(AbstractStorage.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
      var readCache = mock(ReadCache.class);
      var writeCache = mock(WriteCache.class);
      var atomicOperation = mock(AtomicOperation.class);
      var manager =
          mock(
              com.jetbrains.youtrackdb.internal.core.storage.ridbag.LinkCollectionsBTreeManagerShared.class);
      installPrivateField(storage, "readCache", readCache);
      installPrivateField(storage, "writeCache", writeCache);
      installPrivateField(storage, "linkCollectionsBTreeManager", manager);

      // A collection whose primary truncate fails: verifyAndTruncateOrphans throwing an
      // IOException models the readCache.shrinkFile -> AsyncFile.shrink failure path, which
      // surfaces out of the per-component verifyAndTruncateOrphans the same way. The
      // orchestrator's catch-and-WARN treats both verifyAndTruncateOrphans throw sites
      // identically (an entry-point-read failure vs a transient truncate IOException), so
      // stubbing the method to throw directly is representative of a real shrinkFile failure
      // -- which cannot be forced transiently on a healthy file.
      var failingCollection = mock(PaginatedCollectionV2.class);
      doThrow(new IOException("simulated transient shrinkFile failure"))
          .when(failingCollection)
          .verifyAndTruncateOrphans(any(), any(), any());
      when(failingCollection.getName()).thenReturn("survivingorphan_0");
      when(failingCollection.getFileId()).thenReturn(7L);

      @SuppressWarnings("unchecked")
      List<StorageCollection> collections =
          (List<StorageCollection>) readPrivateField(
              storage, "collections");
      collections.clear();
      collections.add(failingCollection);

      // The orchestrator also iterates indexEngines after the collections group; touch it
      // so the bare mock's null field is replaced with an empty list (no engines needed -
      // the WARN we assert originates from the Group 1 collection failure).
      readPrivateField(storage, "indexEngines");

      storage.truncateOrphansAfterRecovery(atomicOperation);

      var sawTruncateWarn =
          captured.records().stream()
              .anyMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue()
                  && r.getMessage() != null
                  && r.getMessage().contains("Orphan-truncation skipped"));
      assertThat(sawTruncateWarn)
          .as("a transient truncate failure must be logged as a WARN so the dropped"
              + " cross-cycle retry stays operator-visible")
          .isTrue();
      // The asserted WARN substring is intentionally tied to the production WARN format in
      // AbstractStorage.truncateOrphansAfterRecovery; if that message text changes, this
      // substring must be updated together.

      // Best-effort continue-on-failure: the swallowed Group 1 (PaginatedCollectionV2)
      // failure must NOT abort the orchestrator. Group 3
      // (linkCollectionsBTreeManager.verifyAndTruncateAllOrphans) runs after the collections
      // group, so verifying it was still dispatched proves the orchestrator continued past
      // the per-component failure -- the contract that justifies dropping the cross-cycle
      // retry.
      verify(manager, times(1)).verifyAndTruncateAllOrphans(any(), any(), any());
    } finally {
      rootLogger.removeHandler(captured);
      // previousLevel may be null (the root logger had no explicit level); that is
      // intentional -- setLevel(null) restores inherited-level behavior, the exact state we
      // captured. Do not default it to a non-null level.
      rootLogger.setLevel(previousLevel);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers for the WARN-on-truncate-failure orchestrator harness
  // ---------------------------------------------------------------------------

  /** Installs a value into a private {@link AbstractStorage} field via reflection. */
  private static void installPrivateField(AbstractStorage storage, String name, Object value)
      throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(storage, value);
  }

  /**
   * Reads a private {@link AbstractStorage} field via reflection, instantiating a mutable
   * stand-in for the {@code collections} list when the bare mock left it null (mirrors the
   * sibling orchestrator unit test's helper).
   */
  private static Object readPrivateField(AbstractStorage storage, String name) throws Exception {
    var field = AbstractStorage.class.getDeclaredField(name);
    field.setAccessible(true);
    var value = field.get(storage);
    if (value == null && name.equals("collections")) {
      value = new CopyOnWriteArrayList<StorageCollection>();
      field.set(storage, value);
    } else if (value == null && name.equals("indexEngines")) {
      value =
          new java.util.ArrayList<BaseIndexEngine>();
      field.set(storage, value);
    }
    return value;
  }

  /**
   * A {@link Handler} that records every {@link LogRecord} it receives into a bounded queue
   * so a test can assert that a particular WARN was emitted. Bounded so a runaway producer
   * cannot exhaust memory; the recovery pass emits at most a handful of records.
   */
  private static final class CapturingHandler extends Handler {
    private final BlockingQueue<LogRecord> records = new ArrayBlockingQueue<>(256);

    @Override
    public void publish(final LogRecord record) {
      records.offer(record);
    }

    @Override
    public void flush() {
      // No buffering - records are enqueued in publish().
    }

    @Override
    public void close() {
      records.clear();
    }

    List<LogRecord> records() {
      return List.copyOf(records);
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
      FileUtils.deleteDirectory(new java.io.File(dbPath));
    }
  }
}
