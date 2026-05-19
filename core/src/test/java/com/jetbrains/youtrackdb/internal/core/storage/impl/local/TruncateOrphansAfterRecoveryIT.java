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
import com.jetbrains.youtrackdb.internal.core.storage.cache.WriteCache;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.fs.File;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * <p>Four scenarios are exercised here against a real {@code WOWCache} + {@code DiskStorage}:
 *
 * <ol>
 *   <li><b>Positive (primary) — deterministic orphan fabrication on a {@code .pcl} file.</b>
 *       Open a storage, populate, close. Then directly extend a paginated-collection's
 *       {@code .pcl} file with N orphan pages carrying the WOWCache magic stamp +
 *       LSN {@code (-1, -1)} (mirroring the byte layout
 *       {@code EnsurePageIsValidInFileTask.writeValidPageInFile} produces). Reopen; assert
 *       the file size shrinks back to {@code (epLogicalCounter + 1) * pageSize} and a
 *       subsequent transaction commits without an {@code IllegalStateException}.</li>
 *   <li><b>Positive — deterministic orphan fabrication on a {@code .cpm} file.</b> Mirrors
 *       the {@code .pcl} test but fabricates orphans on the embedded position-map file.
 *       Pins the PCV2 sibling-truncation hook that internally drives the embedded
 *       {@code CollectionPositionMapV2.verifyAndTruncateOrphans} call.</li>
 *   <li><b>Negative — clean shutdown / no-op.</b> Open, populate, close cleanly, reopen.
 *       Assert no file shrunk (the per-component pre-flight makes the pass a no-op).</li>
 *   <li><b>Index engine variant.</b> Same fabrication shape against a BTree index engine's
 *       {@code .cbt} file (single-value) — pins the engine-side dispatch through the
 *       orchestrator's {@code instanceof BTreeSingleValueIndexEngine} filter.</li>
 * </ol>
 *
 * <p>The orphan fabrication uses {@link RandomAccessFile} after the DB is closed. We must
 * fabricate the pages with valid magic + checksum bytes so that subsequent reads under
 * {@code checksumMode=StoreAndThrow} (or even the default StoreAndVerify) see clean empty
 * pages and not a checksum-corruption error. {@code MAGIC_NUMBER_WITHOUT_CHECKSUM} +
 * {@code checksumMode=Off} is the simplest combination — chosen here to keep the test
 * deterministic and not depend on CRC bytes.
 *
 * <p>This test runs in the {@link SequentialTest} category because it manipulates raw
 * storage files and cannot tolerate parallel JVMs touching the same build directory.
 */
@Category(SequentialTest.class)
public class TruncateOrphansAfterRecoveryIT {

  // 4 orphan pages past the logical horizon — large enough to make the truncate visible
  // in raw file-size accounting, small enough to keep the test fast.
  private static final int ORPHAN_PAGE_COUNT = 4;

  // Mirrors WOWCache.MAGIC_NUMBER_WITHOUT_CHECKSUM (see WOWCache.java around line 3905,
  // EnsurePageIsValidInFileTask.writeValidPageInFile). If WOWCache's constant changes,
  // this fabrication will no longer match the production write path and the recovery
  // pass's load-on-reopen behaviour would diverge — kept duplicated here intentionally so
  // a future change there surfaces as a test failure.
  private static final long MAGIC_NUMBER_WITHOUT_CHECKSUM = 0xEF30BCAFL;

  private YouTrackDBImpl youTrackDB;

  // ---------------------------------------------------------------------------
  // Positive (primary): deterministic orphan fabrication on a paginated collection
  // ---------------------------------------------------------------------------

  /**
   * Open a storage, populate a class so its {@code .pcl} file has multiple pages, close.
   * Externally extend the {@code .pcl} file with {@link #ORPHAN_PAGE_COUNT} valid-stamped
   * orphan pages. Reopen the storage; the recovery-time orchestrator must truncate the
   * orphans so the post-reopen file size equals {@code (epLogicalCounter + 1) * pageSize}.
   * A subsequent transaction must complete without throwing.
   */
  @Test
  public void truncatesOrphansOnPaginatedCollectionFileAfterReopen() throws Exception {
    var config = makeConfigWithChecksumOff();
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
      // Capture storage references inside the open session — the path is published via
      // session.getStorage() (YouTrackDB has no public name-keyed accessor).
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      var pclFileName = findFileName(wowCache, "orphans", ".pcl");
      var fileId = wowCache.fileIdByName(pclFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    // Fabricate orphan pages directly on disk while the DB is closed. The .pcl native
    // file lives under storagePath; HEADER_SIZE precedes data pages.
    var pclPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = pclPath.length();
    fabricateOrphanPages(pclPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = pclPath.length();
    assertThat(sizeAfterFabrication)
        .as(".pcl file size must grow by exactly ORPHAN_PAGE_COUNT * pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    // Reopen — the recovery-time orchestrator should truncate the orphan tail before any
    // non-recovery TX runs.
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Capture the file size BEFORE any non-recovery TX runs. The TX can grow the file
      // by a page, which would mask a missing or partial truncate from the assertion.
      sizeImmediatelyAfterReopen = pclPath.length();

      // Health check: the storage must remain usable after the recovery pass. The first
      // non-recovery TX must not surface IllegalStateException from the physical > logical
      // shape the fabrication just produced.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("Orphans");
        entity.setProperty("value", "post-recovery");
      });
    }

    // The orchestrator must have shrunk the file back to its pre-fabrication size (the
    // logical horizon). Strict equality is asserted against the file size captured BEFORE
    // the post-reopen TX — measuring after the TX would tolerate an orphan that the TX
    // happens to reuse.
    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must have truncated the orphan tail back to the logical horizon")
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // Positive: deterministic orphan fabrication on a CollectionPositionMapV2 (.cpm) file
  // ---------------------------------------------------------------------------

  /**
   * The orchestrator dispatches an orphan-truncation pass on BOTH a
   * {@link com.jetbrains.youtrackdb.internal.core.storage.collection.v2.PaginatedCollectionV2}
   * and its embedded {@code CollectionPositionMapV2}. This test pins the {@code .cpm} half
   * of that dispatch: open a storage, populate the position map (allocating positions for
   * many entities forces the position map to grow), close. Fabricate orphan pages on the
   * {@code .cpm} file. Reopen; assert the {@code .cpm} file shrinks back to the position
   * map's logical horizon.
   */
  @Test
  public void truncatesOrphansOnCollectionPositionMapFileAfterReopen() throws Exception {
    var config = makeConfigWithChecksumOff();
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
      // Allocating many records forces the position map (.cpm) to grow well past the
      // single-page bootstrap so the orphan-truncation pass has something to do.
      session.executeInTx(transaction -> {
        for (var i = 0; i < 200; i++) {
          var entity = transaction.newEntity("CpmOrphans");
          entity.setProperty("value", "row-" + i);
        }
      });
      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      // The position map file (.cpm) lives next to the .pcl with the same class-name prefix.
      var cpmFileName = findFileName(wowCache, "cpmorphans", ".cpm");
      var fileId = wowCache.fileIdByName(cpmFileName);
      nativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    var cpmPath = storagePath.resolve(nativeFileName).toFile();
    long sizeBeforeFabrication = cpmPath.length();
    fabricateOrphanPages(cpmPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = cpmPath.length();
    assertThat(sizeAfterFabrication)
        .as(".cpm file size must grow by exactly ORPHAN_PAGE_COUNT * pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Capture .cpm size BEFORE the post-reopen TX so the assertion is not masked by the
      // TX extending the position map.
      sizeImmediatelyAfterReopen = cpmPath.length();

      // Health check: insert another entity, which routes through the position map.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("CpmOrphans");
        entity.setProperty("value", "post-recovery");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must truncate the .cpm orphan tail back to the position map's"
            + " logical horizon")
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // Negative: clean shutdown / no-op
  // ---------------------------------------------------------------------------

  /**
   * A storage that closes cleanly has {@code physical == logical} on every component.
   * The per-component pre-flight makes the recovery pass a no-op — file sizes must NOT
   * change across the close-reopen boundary.
   */
  @Test
  public void noOpOnCleanShutdownReopen() throws Exception {
    var config = makeConfigWithChecksumOff();
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

    // No fabrication. Just close + reopen.
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Capture file size BEFORE the post-reopen TX. The TX writes a new record and can
      // grow the file, which would let a buggy recovery-time shrink slip through unnoticed
      // if measured afterwards. Strict equality against the pre-reopen size only holds
      // when measured here.
      sizeImmediatelyAfterReopen = pclPath.length();

      // Health check: the storage must be usable after the no-op recovery pass.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("CleanShutdown");
        entity.setProperty("value", "post-reopen");
      });
    }

    // The clean-shutdown contract: the recovery pass must NOT shrink the file. Strict
    // equality against the pre-reopen size catches both the over-shrink direction (a buggy
    // pass that drops live pages) and the no-op direction (the desired clean-case
    // behaviour). The earlier >= relaxation made the latter unfalsifiable.
    assertThat(sizeImmediatelyAfterReopen)
        .as("clean reopen must leave the file size identical to the pre-reopen size")
        .isEqualTo(sizeBeforeReopen);
  }

  // ---------------------------------------------------------------------------
  // Index engine variant: orphans on a .cbt file
  // ---------------------------------------------------------------------------

  /**
   * Same fabrication pattern as the primary test, but the orphan tail lives on a BTree
   * index engine's {@code .cbt} file. Pins the orchestrator's
   * {@code instanceof BTreeSingleValueIndexEngine} dispatch through the engine-side
   * {@code verifyAndTruncateOrphans} wrapper.
   */
  @Test
  public void truncatesOrphansOnIndexEngineFileAfterReopen() throws Exception {
    var config = makeConfigWithChecksumOff();
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
      // The Indexed.key single-value B-tree index engine uses .cbt for its primary data
      // file (BTreeSingleValueIndexEngine.DATA_FILE_EXTENSION). Filter on .cbt to pick
      // the index file; the test only creates one user index so this is unambiguous.
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
    fabricateOrphanPages(cbtPath, pageSize, ORPHAN_PAGE_COUNT);
    long sizeAfterFabrication = cbtPath.length();
    assertThat(sizeAfterFabrication)
        .isEqualTo(sizeBeforeFabrication + (long) ORPHAN_PAGE_COUNT * pageSize);

    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    long sizeImmediatelyAfterReopen;
    try (var session = youTrackDB.open(
        TruncateOrphansAfterRecoveryIT.class.getSimpleName(),
        "admin", "admin", YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // Capture .cbt size BEFORE the post-reopen TX so the assertion is not masked by an
      // insert that re-extends the index file.
      sizeImmediatelyAfterReopen = cbtPath.length();

      // Health check: an insert into the same indexed class must round-trip.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity("Indexed");
        entity.setProperty("key", "post-recovery");
      });
    }

    assertThat(sizeImmediatelyAfterReopen)
        .as("recovery pass must truncate the orphan tail back to the index's logical horizon")
        .isEqualTo(sizeBeforeFabrication);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds a configuration that disables checksum so the orphan fabrication (which writes
   * the {@code MAGIC_NUMBER_WITHOUT_CHECKSUM} stamp) is readable on reopen. The recovery
   * pass itself is checksum-agnostic — it only reads the EP page (which is valid because
   * the production stack wrote it), then dispatches a shrink that doesn't read the
   * orphan pages at all.
   */
  private static org.apache.commons.configuration2.BaseConfiguration makeConfigWithChecksumOff() {
    var config = new org.apache.commons.configuration2.BaseConfiguration();
    config.setProperty(GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(),
        com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode.Off.name());
    config.setProperty(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG.getKey(), false);
    return config;
  }

  /**
   * Extends the given file with {@code orphanPages} pages carrying the WOWCache
   * "without-checksum" magic stamp and LSN {@code (-1, -1)} — exactly the byte layout
   * {@code EnsurePageIsValidInFileTask.writeValidPageInFile} produces for a gap-fill on a
   * fresh page allocation. The pages look "valid but blank" to subsequent reads, which is
   * required so the recovery pass's downstream loads (if any) under
   * {@code checksumMode=Off} don't trip on a corruption check.
   *
   * <p>The file layout is: {@link File#HEADER_SIZE} bytes of header, then {@code N} data
   * pages of {@code pageSize} bytes each. The fabrication appends to the end of the file
   * with a fresh {@link RandomAccessFile} write.
   */
  private static void fabricateOrphanPages(java.io.File file, int pageSize, int orphanPages)
      throws java.io.IOException {
    var page = ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());

    // LSN slot lives at DurablePage.WAL_SEGMENT_OFFSET / WAL_POSITION_OFFSET. Mirror the
    // WOWCache.writeValidPageInFile shape: setLogSequenceNumberForPage(buffer, LSN(-1, -1))
    // then write the magic bytes at offset 0.
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
