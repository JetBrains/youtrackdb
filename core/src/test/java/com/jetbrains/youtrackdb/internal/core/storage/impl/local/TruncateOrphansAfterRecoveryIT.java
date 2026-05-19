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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.commons.io.FileUtils;
import org.junit.After;
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
 * <p>Four file shapes are exercised:
 *
 * <ol>
 *   <li><b>{@code .pcl}</b> — a paginated-collection cluster file.</li>
 *   <li><b>{@code .cpm}</b> — a {@code CollectionPositionMapV2} embedded position-map file.
 *       Pins the PCV2 sibling-truncation hook that internally drives the embedded
 *       {@code CollectionPositionMapV2.verifyAndTruncateOrphans} call.</li>
 *   <li><b>Clean shutdown / no-op.</b> Open, populate, close cleanly, reopen. Asserts no
 *       file shrunk (the per-component pre-flight makes the pass a no-op).</li>
 *   <li><b>{@code .cbt}</b> — a BTree single-value index engine's primary data file. Pins
 *       the engine-side dispatch through the orchestrator's
 *       {@code instanceof BTreeSingleValueIndexEngine} filter.</li>
 * </ol>
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
    java.nio.file.Path storagePath;
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
      var pclFileName = findFileName(wowCache, "orphans", ".pcl");
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
    java.nio.file.Path storagePath;
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
      var cpmFileName = findFileName(wowCache, "cpmorphans", ".cpm");
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

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenUnderChecksumOff() throws Exception {
    runCleanShutdownScenario(ChecksumMode.Off);
  }

  /** See {@link #runCleanShutdownScenario}. */
  @Test
  public void noOpOnCleanShutdownReopenUnderStoreAndThrow() throws Exception {
    runCleanShutdownScenario(ChecksumMode.StoreAndThrow);
  }

  /**
   * Scenario body shared by the two clean-shutdown variants. Asserts the recovery pass
   * is a strict no-op after a clean close-reopen cycle: the per-component pre-flight
   * skips the shrink dispatch, so the file size must be identical across the boundary.
   * Strict equality catches both the over-shrink direction (a buggy pass that drops live
   * pages) and the no-op direction (the desired clean-case behaviour) — a relaxation to
   * {@code >=} would make the latter unfalsifiable.
   */
  private void runCleanShutdownScenario(ChecksumMode checksumMode) throws Exception {
    var config = makeConfig(checksumMode);
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String nativeFileName;
    java.nio.file.Path storagePath;
    try (var session = youTrackDB.open(TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      session.getMetadata().getSchema().createClass("CleanShutdown");
      session.executeInTx(transaction -> {
        for (var i = 0; i < 20; i++) {
          var entity = transaction.newEntity("CleanShutdown");
          entity.setProperty("value", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = findFileName(wowCache, "cleanshutdown", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var pclPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeReopen = pclPath.length();

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      sizeImmediatelyAfterReopen = pclPath.length();

      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("CleanShutdown");
        entity.setProperty("value", "post-reopen");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("clean reopen must leave the file size identical to the pre-reopen size under"
            + " checksumMode=" + checksumMode)
        .isEqualTo(sizeBeforeReopen);
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
    java.nio.file.Path storagePath;
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
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a configuration that selects the given checksum mode and disables the double
   * write log. The recovery pass itself is checksum-agnostic — it only reads the EP page
   * (which is valid because the production stack wrote it), then dispatches a shrink
   * that doesn't read the orphan pages at all — so the mode parameter is the lever for
   * "did the pass accidentally start reading orphan bodies?" regression detection.
   */
  private static BaseConfiguration makeConfig(ChecksumMode checksumMode) {
    var config = new BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(), checksumMode.name());
    config.setProperty(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG.getKey(), false);
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
   * Looks up a file name by class-name prefix and extension. Mirrors the helper pattern
   * already used in {@code StorageTestIT}.
   */
  private static String findFileName(
      WriteCache cache, String prefix, String extension) {
    return cache.files().keySet().stream()
        .filter(name -> name.startsWith(prefix + "_") && name.endsWith(extension))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "No " + extension + " file found for class prefix '" + prefix + "'"));
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
