/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.index.engine;

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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.ChecksumMode;
import com.jetbrains.youtrackdb.internal.core.storage.cache.local.WOWCache;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
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
 * Integration test pinning the HLL-spill page-1 discriminator across a clean
 * close + reopen cycle on the real disk engine.
 *
 * <p>Mirrors {@code TruncateOrphansAfterRecoveryIT}'s fabrication pattern:
 * the test opens a fresh disk-mode storage, builds an {@code IndexHistogramManager}
 * statistics file ({@code .ixs}) via real production calls (class + NOTUNIQUE
 * String index + sufficient data + {@code ANALYZE INDEX}), closes the storage
 * cleanly so the {@code .ixs} file is persisted, then externally extends the
 * {@code .ixs} file with one magic-stamped page so that on reopen
 * {@code AtomicOperation.filledUpTo(fileId)} returns at least 2 pages. This
 * forces the post-replay path through the existing-page branch of the
 * discriminator at {@code IndexHistogramManager.writeSnapshotToPage} /
 * {@code flushSnapshotToPage}, where
 * {@code op.filledUpTo(fileId) > 1 ? loadPageForWrite(fileId, 1, …) :
 * allocatePageForWrite(fileId, 1)} selects the {@code loadPageForWrite} arm.
 * The follow-up workload then drives more inserts plus another
 * {@code ANALYZE INDEX} so the rebalance path issues a fresh
 * {@code writeSnapshotToPage} call against the now-{@code > 1} file size, and
 * the test asserts that no {@link IllegalStateException} surfaces — the
 * cache-layer fail-fast in {@code WOWCache.loadOrAdd} would otherwise reject
 * any allocator that targets a pageIndex below the committed file size.
 *
 * <p>The {@code IndexHistogramManager} is deliberately excluded from the
 * recovery-time orphan-truncation pass (no entry point, no
 * {@code verifyAndTruncateOrphans} hook), so the fabricated page persists
 * across reopen and the discriminator is forced to take the existing-page arm.
 *
 * <p>Marked {@link SequentialTest} because it manipulates raw storage files
 * and cannot tolerate parallel JVMs touching the same build directory.
 */
@Category(SequentialTest.class)
public class IndexHistogramSpillRecoveryIT {

  /** Mirrors {@code WOWCache.MAGIC_NUMBER_WITHOUT_CHECKSUM}; see the same constant in
   * {@code TruncateOrphansAfterRecoveryIT}. Duplicated intentionally — a future change to
   * the production constant should surface here as a clean test failure. */
  private static final long MAGIC_NUMBER_WITHOUT_CHECKSUM = 0xEF30BCAFL;

  /** Class name for the indexed entity. */
  private static final String CLASS_NAME = "HistogramSpillTarget";

  /** Property name carrying the indexed (NOTUNIQUE) String key. */
  private static final String INDEX_KEY = "k";

  /** Index name registered on {@link #CLASS_NAME}.{@link #INDEX_KEY}. */
  private static final String INDEX_NAME = CLASS_NAME + "." + INDEX_KEY;

  private YouTrackDBImpl youTrackDB;

  /**
   * End-to-end flow:
   * <ol>
   *   <li>open fresh disk storage, create class + NOTUNIQUE index, populate so {@code .ixs}
   *       holds a valid page-0 snapshot;</li>
   *   <li>close cleanly so the snapshot lands on disk;</li>
   *   <li>fabricate one magic-stamped orphan page on {@code .ixs} so the reopen sees
   *       {@code op.filledUpTo(fileId) > 1};</li>
   *   <li>reopen and drive more inserts plus {@code ANALYZE INDEX} so the IHM rebalance
   *       path issues another {@code writeSnapshotToPage} call;</li>
   *   <li>assert the follow-up operation does not surface {@link IllegalStateException}
   *       — the discriminator must select the {@code loadPageForWrite(fileId, 1, …)} arm
   *       when {@code hllOnPage1=true} on the new snapshot, and the allocator-only
   *       contract on the disk engine rejects any {@code allocatePageForWrite(fileId, 1)}
   *       at this file size.</li>
   * </ol>
   *
   * <p>The post-reopen workload exercises the IHM lifecycle even when the new snapshot
   * does not happen to set {@code hllOnPage1=true}, because every flush goes through the
   * same {@code writeSnapshotToPage} / {@code flushSnapshotToPage} bodies and a regression
   * that broke the discriminator (e.g., misreading {@code op.filledUpTo} or unconditionally
   * calling {@code allocatePageForWrite}) would fail loudly here when the post-replay file
   * already carries page 1.
   */
  @Test
  public void spillRecoveryFromFabricatedPage1DoesNotThrow() throws Exception {
    var config = makeConfig();
    var directoryPath = DbTestBase.getBaseDirectoryPath(getClass());
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    youTrackDB.create(IndexHistogramSpillRecoveryIT.class.getSimpleName(),
        DatabaseType.DISK,
        new LocalUserCredential("admin", "admin", PredefinedLocalRole.ADMIN));

    String ixsNativeFileName;
    int pageSize;
    java.nio.file.Path storagePath;
    try (var session = youTrackDB.open(IndexHistogramSpillRecoveryIT.class.getSimpleName(),
        "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      var schema = session.getMetadata().getSchema();
      var clazz = schema.createClass(CLASS_NAME);
      clazz.createProperty(INDEX_KEY, PropertyType.STRING);
      // NOTUNIQUE routes through BTreeMultiValueIndexEngine, which is the engine
      // shape that actually owns an IndexHistogramManager with an HLL sketch — the
      // single-value engines have no HLL on this code path and cannot reach the
      // page-1 discriminator branch.
      clazz.createIndex(INDEX_NAME, SchemaClass.INDEX_TYPE.NOTUNIQUE, INDEX_KEY);

      // Insert enough rows with distinct String keys to materialize a histogram
      // build on ANALYZE INDEX. The exact count is well above
      // QUERY_STATS_HISTOGRAM_MIN_SIZE (configured low in #makeConfig) so the
      // build is not a no-op. We do NOT depend on producing a page-1 spill in
      // the first session — the fabrication step below is what forces the
      // discriminator into the existing-page arm on reopen.
      session.executeInTx(transaction -> {
        for (var i = 0; i < 500; i++) {
          var entity = transaction.newEntity(CLASS_NAME);
          entity.setProperty(INDEX_KEY, "key-" + i);
        }
      });

      // Drive a histogram build / rebalance so the .ixs file picks up a valid
      // page-0 snapshot. Without this, the .ixs file might still hold only the
      // bootstrap empty snapshot and the fabrication would target a not-yet-
      // populated structural shape.
      try (var ignored = session.execute("ANALYZE INDEX " + INDEX_NAME)) {
        // The result-set side effect is irrelevant; we only need the side-effect
        // on disk (the rebalance writes through writeSnapshotToPage at the end).
      }

      var storage = (DiskStorage) session.getStorage();
      var wowCache = (WOWCache) storage.getWriteCache();
      // The IHM .ixs file is named after the index (see IndexHistogramManager
      // constructor: name = the index file's base name). The exact prefix is
      // schema-managed; pick the first .ixs file as the IHM's stats file.
      var ixsFileName = wowCache.files().keySet().stream()
          .filter(name -> name.endsWith(IndexHistogramManager.IXS_EXTENSION))
          .findFirst()
          .orElseThrow(() -> new AssertionError(
              "No .ixs file in the file map; the NOTUNIQUE histogram manager appears"
                  + " not to have created a stats file on disk"));
      var fileId = wowCache.fileIdByName(ixsFileName);
      ixsNativeFileName = wowCache.nativeFileNameById(fileId);
      pageSize = wowCache.pageSize();
      storagePath = storage.getStoragePath();
    }
    youTrackDB.close();

    // Fabricate one magic-stamped orphan page on the .ixs file. This mirrors the
    // production gap-fill page shape (see EnsurePageIsValidInFileTask.writeValidPageInFile):
    // magic stamp at offset 0 + LSN (-1, -1), zero payload. The post-reopen
    // filledUpTo(fileId) read will then report > 1, forcing the discriminator
    // into the loadPageForWrite(fileId, 1, …) arm on the next flush that has
    // hllOnPage1=true.
    var ixsPath = storagePath.resolve(ixsNativeFileName).toFile();
    long sizeBeforeFabrication = ixsPath.length();
    fabricateOrphanPage(ixsPath, pageSize);
    long sizeAfterFabrication = ixsPath.length();
    assertThat(sizeAfterFabrication)
        .as(".ixs file size must grow by exactly one pageSize after fabrication")
        .isEqualTo(sizeBeforeFabrication + pageSize);

    // Reopen and drive the follow-up workload. This is the load-bearing
    // assertion direction: the IHM open path reads the page-0 snapshot, and the
    // follow-up ANALYZE INDEX issues a fresh writeSnapshotToPage with
    // op.filledUpTo(fileId) > 1. The discriminator must not surface
    // IllegalStateException — neither from the cache-layer fail-fast on a
    // mis-routed allocator nor from the StorageComponent dispatch.
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(directoryPath, config);
    try (var session = youTrackDB.open(
        IndexHistogramSpillRecoveryIT.class.getSimpleName(),
        "admin", "admin",
        YouTrackDBConfig.builder().fromApacheConfiguration(config).build())) {
      // More inserts so the next ANALYZE INDEX has dirty mutations to flush.
      session.executeInTx(transaction -> {
        for (var i = 0; i < 500; i++) {
          var entity = transaction.newEntity(CLASS_NAME);
          entity.setProperty(INDEX_KEY, "post-recovery-" + i);
        }
      });

      // The second ANALYZE INDEX drives buildHistogram → writeSnapshotToPage.
      // If the discriminator code path is broken, this surfaces as an
      // IllegalStateException; the test's contract is that the call completes
      // cleanly.
      try (var ignored = session.execute("ANALYZE INDEX " + INDEX_NAME)) {
        // Side-effect-only consumption of the result set.
      }

      // Finalize the in-memory state by issuing another transactional insert.
      // Any path that left the storage in an inconsistent state from the
      // discriminator branch would surface here.
      session.executeInTx(transaction -> {
        var entity = transaction.newEntity(CLASS_NAME);
        entity.setProperty(INDEX_KEY, "final-sentinel");
      });
    }
  }

  /**
   * Builds a fresh disk-mode configuration. Enables a NOTUNIQUE-friendly
   * histogram footprint by lowering {@code QUERY_STATS_HISTOGRAM_MIN_SIZE} so a
   * 500-row workload reliably triggers a build. Selects
   * {@link ChecksumMode#StoreAndThrow} so the recovery + reopen path runs
   * under the production CI default and a stray load of a corrupt page would
   * surface a clean failure rather than silently masking the regression.
   */
  private static BaseConfiguration makeConfig() {
    var config = new BaseConfiguration();
    // Lower the histogram min size so the 500-row insert reliably triggers a build.
    config.setProperty(
        GlobalConfiguration.QUERY_STATS_HISTOGRAM_MIN_SIZE.getKey(), 10);
    // Enable disk-mode features that match the production stack: the StoreAndThrow
    // checksum mode pins the regression-detection lever — if a future change drops
    // an orphan-page load somewhere in the post-replay path, this matches the
    // production default and the load would trip a StorageException on a magic
    // mismatch rather than silently succeed.
    config.setProperty(
        GlobalConfiguration.STORAGE_CHECKSUM_MODE.getKey(), ChecksumMode.StoreAndThrow.name());
    // Disable the double-write log so the .ixs file's physical layout is not
    // perturbed by DWL replay — the fabrication step below depends on the file's
    // page-count semantics matching the cache layer's view.
    config.setProperty(GlobalConfiguration.STORAGE_USE_DOUBLE_WRITE_LOG.getKey(), false);
    return config;
  }

  /**
   * Appends one magic-stamped page to {@code file} via {@link RandomAccessFile}.
   * The byte layout mirrors {@code EnsurePageIsValidInFileTask.writeValidPageInFile}
   * (the production gap-fill shape): {@link #MAGIC_NUMBER_WITHOUT_CHECKSUM} at
   * offset 0 + LSN {@code (-1, -1)} placed by
   * {@link DurablePage#setLogSequenceNumberForPage(ByteBuffer, LogSequenceNumber)}.
   * The page payload is zeroed.
   */
  private static void fabricateOrphanPage(java.io.File file, int pageSize) throws Exception {
    var page = ByteBuffer.allocate(pageSize).order(ByteOrder.nativeOrder());
    DurablePage.setLogSequenceNumberForPage(page, new LogSequenceNumber(-1, -1));
    page.putLong(0, MAGIC_NUMBER_WITHOUT_CHECKSUM);
    try (var raf = new RandomAccessFile(file, "rw")) {
      raf.seek(raf.length());
      var bytes = new byte[pageSize];
      page.position(0);
      page.get(bytes);
      raf.write(bytes);
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
