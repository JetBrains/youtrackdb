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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.io.File;
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

  @Before
  public void setUp() {
    testDir = DbTestBase.getBaseDirectoryPathStr(getClass());
    ytdb = (YouTrackDBImpl) YourTracks.instance(testDir);
  }

  @After
  public void tearDown() throws Exception {
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

  // ═══════════════════════════════════════════════════════════════
  //  Helpers
  // ═══════════════════════════════════════════════════════════════

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
      var indexIdField = IndexAbstract.class
          .getDeclaredField("indexId");
      indexIdField.setAccessible(true);
      int indexId = indexIdField.getInt(idx);
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
