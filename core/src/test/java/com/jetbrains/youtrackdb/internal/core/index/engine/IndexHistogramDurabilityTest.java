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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.IndexEngineTestSupport;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.cache.CachePointer;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AtomicOperationIdGen;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsTable;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.StorageComponent;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Durability tests for index histogram statistics using DISK databases.
 *
 * <p>Covers three scenarios:
 * <ul>
 *   <li><b>Clean restart</b>: graceful close + reopen preserves all
 *       histogram state (counters, boundaries, frequencies, MCV)</li>
 *   <li><b>Simulated crash</b>: forceDatabaseClose (no flush) + reopen
 *       triggers WAL recovery; histogram may lose a few mutations but
 *       ANALYZE INDEX restores exact state</li>
 *   <li><b>Backup/restore</b>: .ixs files are included in the backup;
 *       restored database has accurate statistics (either preserved
 *       from backup or rebuilt via ANALYZE)</li>
 * </ul>
 *
 * <p>Each test creates its own DISK database and cleans up after itself.
 * Does NOT extend {@link DbTestBase} because we need explicit control
 * over the database lifecycle (close, reopen, forceDatabaseClose).
 */
public class IndexHistogramDurabilityTest {

  private static final String ADMIN = "admin";
  private static final String ADMIN_PWD = "adminpwd";

  private String testDir;
  private YouTrackDBImpl ytdb;
  private final List<Worker> workers = new ArrayList<>();

  @Before
  public void setUp() {
    testDir = DbTestBase.getBaseDirectoryPathStr(getClass());
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);
  }

  @After
  public void tearDown() throws Exception {
    for (var worker : workers) {
      worker.thread().join(TimeUnit.SECONDS.toMillis(10));
      // Closing storage could wait on the same lock cycle the test detected.
      assertFalse("Worker still holds storage resources: " + worker.thread().getName(),
          worker.thread().isAlive());
    }
    if (ytdb != null) {
      ytdb.close();
    }
    FileUtils.deleteDirectory(new File(testDir));
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 1: Clean restart preserves all histogram state
  // ═══════════════════════════════════════════════════════════════

  /**
   * Graceful close + reopen of a DISK database. All histogram state
   * must survive: totalCount, nullCount, distinctCount, bucketCount,
   * per-bucket frequencies, MCV value and frequency, nonNullCount.
   */
  @Test
  public void cleanRestart_preservesAllHistogramState() {
    var dbName = "cleanRestart";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    // Phase 1: populate, build histogram, record state
    long totalBefore;
    long nullBefore;
    long distinctBefore;
    int bucketsBefore;
    long nonNullBefore;
    long[] freqsBefore;
    Object mcvBefore;
    long mcvFreqBefore;

    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, 2000);
      session.execute("ANALYZE INDEX TestClassvalIdx").close();

      var manager = getHistogramManager(session, "TestClassvalIdx");
      var stats = manager.getStatistics();
      var histogram = manager.getHistogram();
      assertNotNull(histogram);

      totalBefore = stats.totalCount();
      nullBefore = stats.nullCount();
      distinctBefore = stats.distinctCount();
      bucketsBefore = histogram.bucketCount();
      nonNullBefore = histogram.nonNullCount();
      freqsBefore = histogram.frequencies().clone();
      mcvBefore = histogram.mcvValue();
      mcvFreqBefore = histogram.mcvFrequency();
    }
    // Session closed → graceful DB close

    // Phase 2: reopen and verify everything survived
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      var manager = getHistogramManager(session, "TestClassvalIdx");
      var stats = manager.getStatistics();
      var histogram = manager.getHistogram();
      assertNotNull("Histogram should survive clean restart",
          histogram);

      assertEquals("totalCount preserved",
          totalBefore, stats.totalCount());
      assertEquals("nullCount preserved",
          nullBefore, stats.nullCount());
      assertEquals("distinctCount preserved",
          distinctBefore, stats.distinctCount());
      assertEquals("bucketCount preserved",
          bucketsBefore, histogram.bucketCount());
      assertEquals("nonNullCount preserved",
          nonNullBefore, histogram.nonNullCount());

      // Per-bucket frequencies must be identical
      for (int i = 0; i < bucketsBefore; i++) {
        assertEquals("Bucket " + i + " frequency preserved",
            freqsBefore[i], histogram.frequencies()[i]);
      }

      // MCV preserved
      if (mcvBefore != null) {
        assertNotNull("MCV value preserved", histogram.mcvValue());
        assertEquals("MCV value preserved",
            mcvBefore, histogram.mcvValue());
        assertEquals("MCV frequency preserved",
            mcvFreqBefore, histogram.mcvFrequency());
      }

      // Cross-check: totalCount matches actual DB count
      long actualCount;
      try (var result = session.query(
          "SELECT count(*) as cnt FROM TestClass")) {
        actualCount =
            ((Number) result.next().getProperty("cnt")).longValue();
      }
      assertEquals("totalCount matches actual DB count",
          actualCount, stats.totalCount());
    }

    ytdb.drop(dbName);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 2: Clean restart preserves incremental deltas
  // ═══════════════════════════════════════════════════════════════

  /**
   * After ANALYZE, perform additional inserts (incremental deltas),
   * then gracefully close and reopen. The incremental counters
   * should be persisted and the histogram should reflect the post-
   * insert state.
   */
  @Test
  public void cleanRestart_preservesIncrementalDeltas() {
    var dbName = "cleanRestartIncr";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    long totalAfterInserts;
    long nonNullAfterInserts;

    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, 2000);
      session.execute("ANALYZE INDEX TestClassvalIdx").close();

      // Insert 500 more (incremental — no ANALYZE)
      session.begin();
      for (int i = 2000; i < 2500; i++) {
        session.newEntity("TestClass").setProperty("val", i);
      }
      session.commit();

      var manager = getHistogramManager(session, "TestClassvalIdx");
      totalAfterInserts = manager.getStatistics().totalCount();
      nonNullAfterInserts = manager.getHistogram().nonNullCount();
      assertEquals(2500L, totalAfterInserts);
      assertEquals(2500L, nonNullAfterInserts);
    }

    // Reopen and verify incremental state survived
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      var manager = getHistogramManager(session, "TestClassvalIdx");
      var stats = manager.getStatistics();
      var histogram = manager.getHistogram();
      assertNotNull(histogram);

      assertEquals("Incremental totalCount preserved across restart",
          totalAfterInserts, stats.totalCount());
      assertEquals("Incremental nonNullCount preserved across restart",
          nonNullAfterInserts, histogram.nonNullCount());

      // Cross-check with actual DB count
      long actualCount;
      try (var result = session.query(
          "SELECT count(*) as cnt FROM TestClass")) {
        actualCount =
            ((Number) result.next().getProperty("cnt")).longValue();
      }
      assertEquals("totalCount matches actual DB count",
          actualCount, stats.totalCount());
    }

    ytdb.drop(dbName);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 3: Simulated crash — forceDatabaseClose + recovery
  // ═══════════════════════════════════════════════════════════════

  /**
   * Simulates a non-graceful shutdown by calling forceDatabaseClose()
   * after inserting data without flushing. On reopen, the histogram
   * may have lost a few mutations (bounded by PERSIST_BATCH_SIZE).
   * After ANALYZE INDEX, the histogram is rebuilt from the B-tree
   * and matches the actual DB count exactly.
   */
  @Test
  public void simulatedCrash_analyzeRestoresExactState() {
    var dbName = "crashRecovery";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    // Phase 1: populate and build histogram
    var session = ytdb.open(dbName, ADMIN, ADMIN_PWD);
    createSchemaAndData(session, 2000);
    session.execute("ANALYZE INDEX TestClassvalIdx").close();

    long totalAfterAnalyze =
        getHistogramManager(session, "TestClassvalIdx")
            .getStatistics().totalCount();
    assertEquals(2000L, totalAfterAnalyze);

    // Phase 2: insert more data (these mutations may not be flushed
    // to the .ixs page before the crash)
    session.begin();
    for (int i = 2000; i < 2300; i++) {
      session.newEntity("TestClass").setProperty("val", i);
    }
    session.commit();

    // The B-tree has 2300 entries, but the .ixs page may still say 2000
    // (if PERSIST_BATCH_SIZE hasn't been reached). Force close without
    // giving the flush a chance to run. Do NOT close the session first
    // — session.close() would trigger a graceful flush, defeating the
    // crash simulation.
    session.activateOnCurrentThread();
    ytdb.internal.forceDatabaseClose(dbName);

    // Phase 3: reopen — WAL recovery restores the B-tree to 2300 entries
    session = ytdb.open(dbName, ADMIN, ADMIN_PWD);

    var manager = getHistogramManager(session, "TestClassvalIdx");
    var statsAfterCrash = manager.getStatistics();

    // The .ixs page was loaded from disk — totalCount may be stale.
    // After forceDatabaseClose, the batched persistence may not have
    // flushed the latest snapshot. The post-crash totalCount can be
    // anywhere from 0 (if no flush at all) to 2300 (fully flushed).
    assertTrue("Post-crash totalCount should be >= 0 and <= 2300,"
        + " got " + statsAfterCrash.totalCount(),
        statsAfterCrash.totalCount() >= 0
            && statsAfterCrash.totalCount() <= 2300);

    // Ground truth: the B-tree has all 2300 entries (WAL-recovered)
    long actualCount;
    try (var result = session.query(
        "SELECT count(*) as cnt FROM TestClass")) {
      actualCount =
          ((Number) result.next().getProperty("cnt")).longValue();
    }
    assertEquals("B-tree should have all 2300 entries after recovery",
        2300L, actualCount);

    // ANALYZE INDEX rebuilds the histogram from the recovered B-tree.
    // Verify via the SQL result set (independent of manager cache).
    try (var result = session.execute("ANALYZE INDEX TestClassvalIdx")) {
      assertTrue(result.hasNext());
      var row = result.next();
      long analyzedTotal =
          ((Number) row.getProperty("totalCount")).longValue();
      assertEquals(
          "ANALYZE result: totalCount should match actual DB",
          actualCount, analyzedTotal);
      assertTrue("ANALYZE result: bucketCount should be > 0",
          ((Number) row.getProperty("bucketCount")).intValue() > 0);
    }

    // Also verify via manager (re-fetch to get the updated CHM entry)
    manager = getHistogramManager(session, "TestClassvalIdx");
    var statsAfterAnalyze = manager.getStatistics();
    var histogramAfterAnalyze = manager.getHistogram();
    assertNotNull(histogramAfterAnalyze);

    assertEquals("After ANALYZE: totalCount should match actual DB",
        actualCount, statsAfterAnalyze.totalCount());
    assertEquals("After ANALYZE: nonNullCount should match actual DB",
        actualCount, histogramAfterAnalyze.nonNullCount());

    // Bucket frequencies must sum to nonNullCount
    long freqSum = 0;
    for (int i = 0; i < histogramAfterAnalyze.bucketCount(); i++) {
      freqSum += histogramAfterAnalyze.frequencies()[i];
    }
    assertEquals("Sum of frequencies should equal nonNullCount",
        histogramAfterAnalyze.nonNullCount(), freqSum);

    session.close();
    ytdb.drop(dbName);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 4: Backup/restore preserves histogram state
  // ═══════════════════════════════════════════════════════════════

  /**
   * Backs up a DISK database with a histogram, restores it to a new
   * database name, and verifies that the histogram state in the
   * restored database matches the original. Since .ixs files are
   * included in the backup, the histogram should survive as-is.
   */
  @Test
  public void backupRestore_histogramPreserved() throws Exception {
    var dbName = "backupSrc";
    var restoreName = "backupRestored";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    long totalBefore;
    int bucketsBefore;
    long nonNullBefore;

    var backupDir = new File(testDir, "backupDir");
    FileUtils.deleteDirectory(backupDir);
    assertTrue(backupDir.mkdirs());

    // Phase 1: populate and build histogram
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, 3000);
      session.execute("ANALYZE INDEX TestClassvalIdx").close();

      var manager = getHistogramManager(session, "TestClassvalIdx");
      totalBefore = manager.getStatistics().totalCount();
      bucketsBefore = manager.getHistogram().bucketCount();
      nonNullBefore = manager.getHistogram().nonNullCount();
    }

    // Backup using openTraversal (required for backup API)
    try (var traversal = ytdb.openTraversal(dbName, ADMIN, ADMIN_PWD)) {
      traversal.backup(backupDir.toPath());
    }

    // Phase 2: restore and verify
    ytdb.restore(restoreName, backupDir.getAbsolutePath());

    try (var session = ytdb.open(restoreName, ADMIN, ADMIN_PWD)) {
      var manager = getHistogramManager(session, "TestClassvalIdx");
      var stats = manager.getStatistics();

      // The histogram may or may not be present after restore
      // (depends on whether .ixs files are preserved in backup).
      // Either way, after ANALYZE the state should be exact.

      // First check if stats survived the restore directly
      if (stats != null && stats.totalCount() > 0) {
        assertEquals("Restored totalCount should match original",
            totalBefore, stats.totalCount());

        var histogram = manager.getHistogram();
        if (histogram != null) {
          assertEquals("Restored bucketCount should match",
              bucketsBefore, histogram.bucketCount());
          assertEquals("Restored nonNullCount should match",
              nonNullBefore, histogram.nonNullCount());
        }
      }

      // Cross-check: actual DB count in restored database
      long actualCount;
      try (var result = session.query(
          "SELECT count(*) as cnt FROM TestClass")) {
        actualCount =
            ((Number) result.next().getProperty("cnt")).longValue();
      }
      assertEquals("Restored DB should have same entry count",
          totalBefore, actualCount);

      // Rebuild via ANALYZE — verify via SQL result (independent
      // of manager cache, which may be stale after restore)
      try (var analyzeResult =
          session.execute("ANALYZE INDEX TestClassvalIdx")) {
        assertTrue(analyzeResult.hasNext());
        var row = analyzeResult.next();
        assertEquals("ANALYZE totalCount matches actual DB",
            actualCount,
            ((Number) row.getProperty("totalCount")).longValue());
        assertTrue("ANALYZE bucketCount > 0",
            ((Number) row.getProperty("bucketCount")).intValue() > 0);
      }

      // Re-fetch manager after ANALYZE updated the CHM
      manager = getHistogramManager(session, "TestClassvalIdx");
      var statsPost = manager.getStatistics();
      var histPost = manager.getHistogram();
      assertNotNull("Histogram should exist after ANALYZE on restore",
          histPost);
      assertEquals("Post-ANALYZE totalCount matches actual DB",
          actualCount, statsPost.totalCount());
      assertEquals("Post-ANALYZE nonNullCount matches actual DB",
          actualCount, histPost.nonNullCount());

      // Bucket frequencies must sum to nonNullCount
      long freqSum = 0;
      for (int i = 0; i < histPost.bucketCount(); i++) {
        freqSum += histPost.frequencies()[i];
      }
      assertEquals("Sum of frequencies should equal nonNullCount",
          histPost.nonNullCount(), freqSum);
    }

    ytdb.drop(restoreName);
    ytdb.drop(dbName);
  }

  // ═══════════════════════════════════════════════════════════════
  //  Test 5: Backup/restore with post-backup mutations
  // ═══════════════════════════════════════════════════════════════

  /**
   * After backup, inserts more data into the original DB, then
   * restores the backup. The restored DB should reflect the state
   * at backup time (not the post-backup mutations). ANALYZE on the
   * restored DB should match the backup-time entry count.
   */
  @Test
  public void backupRestore_reflectsBackupTimeState() throws Exception {
    var dbName = "backupTimeSrc";
    var restoreName = "backupTimeRestored";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var backupDir = new File(testDir, "backupTimeDir");
    FileUtils.deleteDirectory(backupDir);
    assertTrue(backupDir.mkdirs());

    long countAtBackup;

    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, 2000);
      session.execute("ANALYZE INDEX TestClassvalIdx").close();
      countAtBackup = 2000L;
    }

    // Backup at this point
    try (var traversal = ytdb.openTraversal(dbName, ADMIN, ADMIN_PWD)) {
      traversal.backup(backupDir.toPath());
    }

    // Insert more data AFTER the backup
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      session.begin();
      for (int i = 2000; i < 3500; i++) {
        session.newEntity("TestClass").setProperty("val", i);
      }
      session.commit();
      // Original DB now has 3500 entries
    }

    // Restore — should reflect the 2000-entry state
    ytdb.restore(restoreName, backupDir.getAbsolutePath());

    try (var session = ytdb.open(restoreName, ADMIN, ADMIN_PWD)) {
      long actualCount;
      try (var result = session.query(
          "SELECT count(*) as cnt FROM TestClass")) {
        actualCount =
            ((Number) result.next().getProperty("cnt")).longValue();
      }
      assertEquals("Restored DB should have backup-time count",
          countAtBackup, actualCount);

      // ANALYZE on restored DB — verify via SQL result first
      try (var analyzeResult =
          session.execute("ANALYZE INDEX TestClassvalIdx")) {
        assertTrue(analyzeResult.hasNext());
        var row = analyzeResult.next();
        assertEquals("ANALYZE totalCount matches backup-time count",
            countAtBackup,
            ((Number) row.getProperty("totalCount")).longValue());
      }

      // Re-fetch manager after ANALYZE
      var manager = getHistogramManager(session, "TestClassvalIdx");
      assertEquals("Post-ANALYZE totalCount matches backup-time count",
          countAtBackup, manager.getStatistics().totalCount());
      assertNotNull(manager.getHistogram());
      assertEquals("Post-ANALYZE nonNullCount matches backup-time count",
          countAtBackup, manager.getHistogram().nonNullCount());
    }

    ytdb.drop(restoreName);
    ytdb.drop(dbName);
  }

  /** A histogram save after a full checkpoint marks before its timestamp and retries a failure. */
  @Test
  public void histogramSaveMarksAfterCheckpointAndReportsFailedAttempt() throws Exception {
    var dbName = "indicatedHistogram";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, 2);
      var storage = (DiskStorage) session.getStorage();
      var histogram = getHistogramManager(session, "TestClassvalIdx");
      storage.synch();
      assertFalse(storage.readStartupMetadataForTesting()[12] != 0);

      histogram.setDirtyMutationsForTest(1);
      var failedWrite = new IOException("histogram indication rejected");
      storage.failNextRecoveryIndicationForTesting(failedWrite);
      var logged = new java.util.concurrent.atomic.AtomicBoolean();
      var handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
          if (record.getLevel().intValue() < Level.SEVERE.intValue()) {
            return;
          }
          for (var cause = record.getThrown(); cause != null; cause = cause.getCause()) {
            if (cause == failedWrite) {
              logged.set(true);
            }
          }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      };
      var logger = Logger.getLogger("");
      var before = storage.getIdGen().getLastId();
      logger.addHandler(handler);
      try {
        histogram.flushIfDirty();
      } finally {
        logger.removeHandler(handler);
      }
      assertTrue("background failure must be logged at ERROR", logged.get());
      assertEquals("failed histogram save takes no timestamp", before,
          storage.getIdGen().getLastId());
      assertFalse(storage.readStartupMetadataForTesting()[12] != 0);
      assertEquals("failed save must retain dirty work for retry", 1,
          histogram.getDirtyMutations());

      storage.getAtomicOperationsManager().setBeforeTimestampActionForTesting(() -> {
        try {
          assertTrue("histogram timestamp follows durable indication",
              storage.readStartupMetadataForTesting()[12] != 0);
        } catch (IOException failure) {
          throw new AssertionError(failure);
        }
      });
      histogram.flushIfDirtyOrFail();
      assertEquals(0, histogram.getDirtyMutations());
      assertTrue(storage.readStartupMetadataForTesting()[12] != 0);
      storage.checkErrorState();
    }
  }

  @Test
  public void analyzeKeepsFittedHistogramWhenWriteAdmissionFails() throws Exception {
    var dbName = "analyzeAdmissionFailure";
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(
            GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL, Integer.MAX_VALUE)
        .build();
    ytdb.create(dbName, DatabaseType.DISK, config, ADMIN, ADMIN_PWD, "admin");
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, 2);
      var storage = (DiskStorage) session.getStorage();
      var manager = getHistogramManager(session, "TestClassvalIdx");
      manager.setBackgroundExecutor(null);
      storage.synch();
      assertNull(manager.getHistogram());
      assertEquals(0, manager.getDirtyMutations());
      var beforeVersion = manager.getSnapshot().version();
      assertEquals(0, storage.readStartupMetadataForTesting()[12]);

      var failedWrite = new IOException("analyze indication rejected");
      storage.failNextRecoveryIndicationForTesting(failedWrite);
      var logged = new AtomicBoolean();
      var handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
          if (record.getLevel().intValue() >= Level.SEVERE.intValue()
              && record.getMessage().startsWith("Failed to persist rebalanced histogram")) {
            for (var cause = record.getThrown(); cause != null; cause = cause.getCause()) {
              if (cause == failedWrite) {
                logged.set(true);
              }
            }
          }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
      };
      var logger = Logger.getLogger("");
      int buckets;
      var publishedUnderLock = new AtomicBoolean();
      manager.setPrePublicationTestHook(() -> publishedUnderLock.set(manager.isExclusiveOwner()));
      logger.addHandler(handler);
      try (var result = session.execute("ANALYZE INDEX TestClassvalIdx")) {
        assertTrue(result.hasNext());
        buckets = ((Number) result.next().getProperty("bucketCount")).intValue();
      } finally {
        logger.removeHandler(handler);
        manager.setPrePublicationTestHook(null);
      }

      assertTrue("ANALYZE must report the fitted histogram", buckets > 0);
      assertTrue("The fallback must publish under the manager lock", publishedUnderLock.get());
      assertTrue("The admission failure must be logged at ERROR", logged.get());
      assertEquals(1, manager.getDirtyMutations());
      assertEquals(beforeVersion + 1, manager.getSnapshot().version());
      assertEquals(buckets, manager.getHistogram().bucketCount());
      assertEquals("Failure fallback must not write", 0,
          storage.readStartupMetadataForTesting()[12]);

      manager.flushIfDirtyOrFail();
      assertEquals(0, manager.getDirtyMutations());
    }

    ytdb.internal.forceDatabaseClose(dbName);
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      var histogram = getHistogramManager(session, "TestClassvalIdx").getHistogram();
      assertNotNull("The dirty retry must store the fitted histogram", histogram);
      assertEquals(2, histogram.nonNullCount());
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════

  @Test
  public void fillLocksTreesBeforeStatisticsWhileSameIndexCommitWaitsAndFlushes() throws Exception {
    overlap("fill", true, false, false, false);
  }

  @Test
  public void checkpointDrainsCommitWaitingForFillTreeLocks() throws Exception {
    overlap("fillCheckpoint", true, true, false, false);
  }

  @Test
  public void clearLocksBothPopulatedTreesBeforeSameIndexCommit() throws Exception {
    overlap("clearPopulated", false, false, false, false);
  }

  @Test
  public void clearLocksEmptyMainBeforePopulatedNullTreeWhileCommitWaits() throws Exception {
    overlap("clearNullOnly", false, false, true, false);
  }

  @Test
  public void dropOfNullOnlyIndexWaitsForSameIndexCommit() throws Exception {
    overlap("dropNullOnly", false, false, true, true);
  }

  @Test
  public void buildWaitsForWriteAdmissionWhileCheckpointDrainsCommitWaitingForStatistics()
      throws Exception {
    var dbName = "buildAdmission";
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(
            GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL, Integer.MAX_VALUE)
        .build();
    ytdb.create(dbName, DatabaseType.DISK, config, ADMIN, ADMIN_PWD, "admin");
    DiskStorage storage;
    BTreeMultiValueIndexEngine engine;
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, 2);
      storage = (DiskStorage) session.getStorage();
      engine = (BTreeMultiValueIndexEngine) IndexEngineTestSupport.engine(session,
          "TestClassvalIdx");
    }
    var ops = storage.getAtomicOperationsManager();
    var manager = engine.getHistogramManager();
    manager.setBackgroundExecutor(null);
    manager.flushIfDirtyOrFail();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var frozen = new CountDownLatch(1);
    var scanClosed = new CountDownLatch(1);
    var finishScan = new CountDownLatch(1);
    var publishedUnderLock = new AtomicBoolean();
    var commitThread = new AtomicReference<Thread>();
    var buildThread = new AtomicReference<Thread>();
    try {
      // Close the tree stream before the commit takes the tree locks.
      manager.setKeyStreamSupplier(op -> engine.rawKeyStreamForHistogram(op).onClose(() -> {
        scanClosed.countDown();
        await(finishScan, 45);
      }));
      manager.setPrePublicationTestHook(() -> {
        if (Thread.currentThread() == commitThread.get()) {
          manager.setDirtyMutationsForTest(
              GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE.getValueAsInteger());
        } else if (Thread.currentThread() == buildThread.get()) {
          publishedUnderLock.set(manager.isExclusiveOwner());
        }
      });
      var build = start("build", () -> {
        buildThread.set(Thread.currentThread());
        assertNotNull(manager.analyzeIndex());
        return null;
      });
      await(scanClosed);
      var holder = start("statistics holder", () -> {
        ops.executeInsideAtomicOperation(op -> {
          ops.acquireExclusiveLockTillOperationComplete(op, manager);
          assertTrue(manager.isExclusiveOwner());
          held.countDown();
          await(release, 45);
        });
        return null;
      });
      await(held);
      var commit = start("commit", () -> commitOne(dbName, commitThread, false, null));
      awaitStack(commit, "applyHistogramDeltas", "StorageComponent.lockExclusive",
          "LockSupport.park");
      assertEquals(0L, manager.getDirtyMutations());
      var beforeBuild = manager.getSnapshot();
      assertNotNull(beforeBuild);

      ops.setFreezeRegisteredActionForTesting(frozen::countDown);
      var checkpoint = start("checkpoint", () -> {
        storage.synch();
        return null;
      });
      await(frozen);
      awaitStack(checkpoint, "doSynch", "OperationsFreezer.freezeOperations", "Thread.yield");

      finishScan.countDown();
      awaitStack(build, "doRebalance", "OperationsFreezer.startOperation", "LockSupport.park");
      assertSame("A build parked for admission must not publish", beforeBuild,
          manager.getSnapshot());
      awaitStack(holder, "executeInsideAtomicOperation", "CountDownLatch.await",
          "LockSupport.park");

      release.countDown();
      holder.result().get(10, TimeUnit.SECONDS);
      commit.result().get(10, TimeUnit.SECONDS);
      checkpoint.result().get(10, TimeUnit.SECONDS);
      build.result().get(10, TimeUnit.SECONDS);
      assertTrue("The build must hold the manager lock at publication", publishedUnderLock.get());
      assertEquals(beforeBuild.version() + 1, manager.getSnapshot().version());
      storage.checkErrorState();
    } finally {
      finishScan.countDown();
      release.countDown();
      manager.setPrePublicationTestHook(null);
      manager.setKeyStreamSupplier(engine::rawKeyStreamForHistogram);
      ops.clearTestActions();
    }
  }

  @Test
  public void standaloneBuildKeepsStatisticsLockUntilItsWriteCommits() throws Exception {
    var storage = mock(AbstractStorage.class);
    when(storage.getIdGen()).thenReturn(new AtomicOperationIdGen());
    var wal = mock(WriteAheadLog.class);
    // Exercise real admission, lock registration and operation completion.
    // Stub the page write and commit steps to pause without disk I/O.
    when(storage.getWALInstance()).thenReturn(wal);
    var table = mock(AtomicOperationsTable.class);
    var ops = spy(new AtomicOperationsManager(storage, table));
    when(storage.getAtomicOperationsManager()).thenReturn(ops);

    var write = mock(AtomicOperation.class);
    when(write.getCommitTs()).thenReturn(1L);
    var lockedComponents = new ArrayList<StorageComponent>();
    when(write.lockedComponents()).thenReturn(lockedComponents);
    doAnswer(invocation -> {
      lockedComponents.add(invocation.getArgument(0));
      return null;
    }).when(write).addLockedComponent(any());
    doReturn(mock(AtomicOperation.class), write).when(ops).startAtomicOperation();

    var page = mock(CacheEntry.class);
    var pointer = mock(CachePointer.class);
    when(pointer.getBuffer()).thenReturn(
        ByteBuffer.allocateDirect(DurablePage.MAX_PAGE_SIZE_BYTES).order(ByteOrder.nativeOrder()));
    when(page.getCachePointer()).thenReturn(pointer);
    when(write.loadPageForWrite(42L, 0L, 1, true)).thenReturn(page);

    var serializer = IntegerSerializer.INSTANCE;
    var cache = new ConcurrentHashMap<Integer, HistogramSnapshot>();
    cache.put(0, new HistogramSnapshot(new IndexStatistics(2, 2, 0),
        null, 0, 2, 0, false, null, false));
    var manager = new IndexHistogramManager(storage, "build-lock-lifetime", 0, true, cache,
        serializer,
        BinarySerializerFactory.create(BinarySerializerFactory.currentBinaryFormatVersion()),
        serializer.getId());
    manager.setFileIdForTest(42L);
    manager.setKeyStreamSupplier(op -> Stream.of(1, 2));

    var committing = new CountDownLatch(1);
    var finishCommit = new CountDownLatch(1);
    var recorded = new CountDownLatch(1);
    var finishRecording = new CountDownLatch(1);
    var committed = new AtomicBoolean();
    // Pause after the component callback and again at the end of the commit.
    when(write.commitChanges(1L, wal)).thenAnswer(invocation -> {
      committing.countDown();
      await(finishCommit, 45);
      return null;
    });
    doAnswer(invocation -> {
      recorded.countDown();
      await(finishRecording, 45);
      committed.set(true);
      return null;
    }).when(table).commitOperation(1L);

    try {
      var build = start("build committing statistics", () -> {
        assertNotNull(manager.analyzeIndex());
        return null;
      });
      await(committing);
      assertNotNull(manager.getSnapshot().histogram());
      var contender = start("statistics lock contender", () -> {
        manager.lockExclusive();
        try {
          assertTrue("The contender must acquire the lock after the commit", committed.get());
        } finally {
          manager.unlockExclusive();
        }
        return null;
      });
      awaitStack(contender, "StorageComponent.lockExclusive", "ReentrantReadWriteLock",
          "LockSupport.park");
      finishCommit.countDown();
      await(recorded);
      awaitStack(contender, "StorageComponent.lockExclusive", "ReentrantReadWriteLock",
          "LockSupport.park");
      finishRecording.countDown();
      build.result().get(10, TimeUnit.SECONDS);
      contender.result().get(10, TimeUnit.SECONDS);
    } finally {
      finishCommit.countDown();
      finishRecording.countDown();
    }
  }

  private void overlap(String dbName, boolean fill, boolean checkpoint, boolean nullOnly,
      boolean drop) throws Exception {
    var indexName = "TestClassvalIdx";
    // Keep periodic checkpoints outside the bounded overlap window. The explicit
    // checkpoint in the freeze scenario still runs.
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(
            GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL, Integer.MAX_VALUE)
        .build();
    ytdb.create(dbName, DatabaseType.DISK, config, ADMIN, ADMIN_PWD, "admin");
    BTreeMultiValueIndexEngine engine;
    int indexId;
    DiskStorage storage;
    try (var session = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      createSchemaAndData(session, nullOnly ? 0 : 1);
      session.begin();
      session.newEntity("TestClass");
      session.commit();
      engine = (BTreeMultiValueIndexEngine) IndexEngineTestSupport.engine(session, indexName);
      indexId = IndexEngineTestSupport.externalIdentifier(session, indexName);
      storage = (DiskStorage) session.getStorage();
      engine.getHistogramManager().flushIfDirtyOrFail();
      assertEquals(nullOnly ? 1 : 2, engine.getTotalCount(null));
      assertEquals(1, engine.getNullCount(null));
    }
    var ops = storage.getAtomicOperationsManager();
    var manager = engine.getHistogramManager();
    var stem = AbstractStorage.indexEngineFileStem(engine.getFileBaseId());
    var trees = List.of(stem + BTreeMultiValueIndexEngine.DATA_FILE_EXTENSION,
        stem + AbstractStorage.NULL_TREE_SUFFIX + BTreeMultiValueIndexEngine.DATA_FILE_EXTENSION);
    var held = new AtomicReference<List<String>>();
    var holderReady = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var admission = new CountDownLatch(1);
    var flush = new CountDownLatch(1);
    var dropCommitCounts = new AtomicReference<List<Long>>();
    var holderOp = new AtomicReference<AtomicOperation>();
    var commitThread = new AtomicReference<Thread>();
    java.util.function.Consumer<AtomicOperation> pause = op -> {
      var names = new ArrayList<String>();
      op.lockedComponents().forEach(component -> names.add(component.getLockName()));
      held.set(names);
      holderReady.countDown();
      await(release);
    };
    var hooksActive = new AtomicBoolean(true);
    Worker holder;
    Worker contender;
    Worker sync = null;
    try {
      manager.setPrePublicationTestHook(() -> {
        if (Thread.currentThread() == commitThread.get()) {
          if (fill) {
            // A callback already in flight must not install an action after cleanup.
            synchronized (hooksActive) {
              if (!hooksActive.get()) {
                return;
              }
              // Arm the nested write from Hook B, not the outer commit's admission.
              manager.setDirtyMutationsForTest(
                  GlobalConfiguration.QUERY_STATS_PERSIST_BATCH_SIZE.getValueAsInteger());
              ops.setBeforeTimestampActionForTesting(() -> {
                if (Thread.currentThread() == commitThread.get()) {
                  flush.countDown();
                }
              });
            }
          } else if (drop) {
            // Hook B has applied the counts and the commit still excludes drop.
            dropCommitCounts.set(List.of(engine.getTotalCount(null), engine.getNullCount(null)));
          }
        } else if (holderOp.get() != null) {
          pause.accept(holderOp.get());
        }
      });
      if (drop) {
        holder = start("commit", () -> commitOne(dbName, commitThread, true, pause));
      } else {
        holder = start(fill ? "fill" : "clear", () -> {
          storage.callIndexEngine(true, indexId, ignored -> {
            try {
              ops.executeInsideAtomicOperation(op -> {
                holderOp.set(op);
                try {
                  if (fill) {
                    engine.buildInitialHistogram(op);
                  } else {
                    engine.clear(storage, op);
                  }
                } finally {
                  holderOp.set(null);
                }
              });
            } catch (IOException e) {
              throw new UncheckedIOException(e);
            }
            return null;
          });
          return null;
        });
      }
      await(holderReady);
      var expected = new ArrayList<>(trees);
      if (fill) {
        expected.add(manager.getLockName());
      }
      assertEquals(expected, held.get().stream().filter(expected::contains).toList());
      if (drop) {
        contender = start("drop", () -> {
          storage.deleteIndexEngine(indexId);
          return null;
        });
        awaitStack(contender, "deleteIndexEngine", "ScalableRWLock.exclusiveLock",
            "Thread.yield");
      } else {
        ops.setBeforeTimestampActionForTesting(admission::countDown);
        contender = start("commit", () -> commitOne(dbName, commitThread, false, null));
        await(admission);
        awaitStack(contender, "lockIndexes", "StorageComponent.lockExclusive",
            "LockSupport.park");
        if (checkpoint) {
          var frozen = new CountDownLatch(1);
          ops.setFreezeRegisteredActionForTesting(frozen::countDown);
          sync = start("checkpoint", () -> {
            storage.synch();
            return null;
          });
          await(frozen);
          awaitStack(sync, "doSynch", "OperationsFreezer.freezeOperations", "Thread.yield");
        }
      }
      release.countDown();
      holder.result().get(10, TimeUnit.SECONDS);
      contender.result().get(10, TimeUnit.SECONDS);
      if (sync != null) {
        sync.result().get(10, TimeUnit.SECONDS);
      }
      if (fill) {
        await(flush);
        assertEquals(0L, manager.getDirtyMutations());
      }
      if (drop) {
        assertEquals("Both committed entries must be null keys", List.of(2L, 2L),
            dropCommitCounts.get());
      }
      storage.checkErrorState();
    } finally {
      // Also release the holder if staging the overlap failed.
      release.countDown();
      synchronized (hooksActive) {
        hooksActive.set(false);
        manager.setPrePublicationTestHook(null);
        ops.clearTestActions();
      }
    }
  }

  private Void commitOne(String dbName, AtomicReference<Thread> thread,
      boolean nullKey, java.util.function.Consumer<AtomicOperation> afterIndexLocks) {
    thread.set(Thread.currentThread());
    try (var writer = ytdb.open(dbName, ADMIN, ADMIN_PWD)) {
      writer.begin();
      var entity = writer.newEntity("TestClass");
      if (!nullKey) {
        entity.setProperty("val", 10);
      }
      if (afterIndexLocks != null) {
        writer.getTransactionInternal().getRecordSerializationContext()
            .push((op, storage) -> afterIndexLocks.accept(op));
      }
      writer.commit();
    }
    return null;
  }

  private record Worker(Thread thread, FutureTask<Void> result) {
  }

  private Worker start(String name, Callable<Void> action) {
    var task = new FutureTask<>(action);
    var thread = new Thread(task, name);
    thread.setDaemon(true);
    var worker = new Worker(thread, task);
    workers.add(worker);
    thread.start();
    return worker;
  }

  private static void await(CountDownLatch latch) {
    await(latch, 10);
  }

  private static void await(CountDownLatch latch, long seconds) {
    try {
      assertTrue("Timed out waiting for test stage", latch.await(seconds, TimeUnit.SECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void awaitStack(Worker worker, String caller, String lock, String waitSite)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    do {
      var stack = Arrays.toString(worker.thread().getStackTrace());
      if (stack.contains(caller) && stack.contains(lock) && stack.contains(waitSite)) {
        return;
      }
      if (worker.result().isDone()) {
        worker.result().get(0, TimeUnit.SECONDS);
        break;
      }
      Thread.sleep(1);
    } while (System.nanoTime() < deadline);
    throw new AssertionError(
        "Did not see " + worker.thread().getName() + " waiting at " + waitSite);
  }

  /**
   * Creates a class with an indexed integer field and populates it
   * with sequential integer values {@code 0..rowCount-1}.
   */
  private void createSchemaAndData(
      DatabaseSessionEmbedded session, int rowCount) {
    var schema = session.getMetadata().getSchema();
    if (!schema.existsClass("TestClass")) {
      var clazz = schema.createClass("TestClass");
      clazz.createProperty("val", PropertyType.INTEGER);
      clazz.createIndex("TestClassvalIdx",
          SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");
    }

    session.begin();
    for (int i = 0; i < rowCount; i++) {
      session.newEntity("TestClass").setProperty("val", i);
    }
    session.commit();
  }

  /**
   * Retrieves the IndexHistogramManager for a named index.
   */
  private IndexHistogramManager getHistogramManager(
      DatabaseSessionEmbedded session, String indexName) {
    try {
      var idx = session.getSharedContext().getIndexManager()
          .getIndex(indexName);
      int indexId = com.jetbrains.youtrackdb.internal.core.index.IndexEngineTestSupport
          .externalIdentifier(idx);
      var storageField = IndexAbstract.class
          .getDeclaredField("storage");
      storageField.setAccessible(true);
      var storage = storageField.get(idx);
      var getEngineMethod = storage.getClass()
          .getMethod("getIndexEngine", int.class);
      var engine = (BTreeIndexEngine) getEngineMethod
          .invoke(storage, indexId);
      return engine.getHistogramManager();
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to get histogram manager for " + indexName, e);
    }
  }
}
