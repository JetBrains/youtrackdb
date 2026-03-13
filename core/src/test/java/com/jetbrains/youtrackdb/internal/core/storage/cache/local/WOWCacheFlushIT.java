package com.jetbrains.youtrackdb.internal.core.storage.cache.local;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.io.FileUtils;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression tests for the WOWCache flush path (YTDB-556). Verifies that
 * {@code executeFileFlush} does not skip pages, which previously caused data
 * loss when the shared {@code wowCacheFlushExecutor} encountered lock
 * contention from concurrent storages.
 *
 * <p>The root cause was {@code tryAcquireSharedLock()} + {@code continue} in
 * {@code executeFileFlush}: when another storage's commit held the exclusive
 * buffer lock on a page pointer, the page was silently skipped. Combined with
 * WAL truncation in {@code flushAllData()}, committed modifications were
 * permanently lost on clean shutdown. The fix replaced the non-blocking
 * {@code tryAcquireSharedLock()} with a blocking {@code acquireSharedLock()}.
 */
public class WOWCacheFlushIT {

  private YouTrackDBImpl memoryYtdb;

  @Before
  public void setUp() {
    // Create an in-memory database that shares the JVM-wide
    // wowCacheFlushExecutor with the disk databases created by the tests.
    // This is the condition that triggered the original bug.
    memoryYtdb = DbTestBase.createYTDBManagerAndDb(
        "memdb", DatabaseType.MEMORY, getClass());
  }

  @After
  public void tearDown() {
    memoryYtdb.close();
  }

  // Verifies that data written to a DISK database alongside an active
  // in-memory database survives a clean shutdown. The shared
  // wowCacheFlushExecutor could previously skip pages when another
  // storage's commit held the buffer lock, causing data loss after WAL
  // truncation.
  @Test
  public void diskDataSurvivesRestartWithConcurrentMemoryDatabase() {
    var dbPath = diskTestPath("flush-regression");

    // Phase 1: Write data to a DISK database while the MEMORY database
    // is active
    var diskYtdb = createDiskInstance(dbPath, "flushtest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "flushtest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.command("CREATE CLASS FlushRegressionTest");

      for (int i = 0; i < 50; i++) {
        session.begin();
        session.command("INSERT INTO FlushRegressionTest SET idx = " + i);
        session.commit();
      }

      // Create a property + index to generate additional small metadata
      // pages that are infrequently flushed — these were most likely to
      // be skipped.
      session.command("CREATE PROPERTY FlushRegressionTest.idx INTEGER");
      session.command("CREATE INDEX FlushRegressionTest.idx ON "
          + "FlushRegressionTest (idx) NOTUNIQUE");

      // Update records to create dirty pages across multiple collection
      // files
      for (int round = 1; round <= 3; round++) {
        session.begin();
        session.command("UPDATE FlushRegressionTest SET ver = " + round);
        session.commit();
      }
    }

    // Generate activity on the in-memory database to increase contention
    // on the shared flush executor
    try (var memSession = memoryYtdb.open(
        "memdb", "admin", DbTestBase.ADMIN_PASSWORD)) {
      memSession.command("CREATE CLASS FlushContentionHelper");
      for (int i = 0; i < 20; i++) {
        memSession.begin();
        memSession.command(
            "INSERT INTO FlushContentionHelper SET x = " + i);
        memSession.commit();
      }
    }

    diskYtdb.close();

    // Phase 2: Reopen and verify all data survived
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
        .instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "flushtest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.begin();
      int count = 0;
      try (var result = session.query(
          "SELECT FROM FlushRegressionTest")) {
        while (result.hasNext()) {
          var record = result.next();
          assertThat((int) record.getProperty("ver"))
              .as("record idx=" + record.getProperty("idx")
                  + " should have latest version")
              .isEqualTo(3);
          count++;
        }
      }
      session.commit();
      assertThat(count)
          .as("all 50 records should survive clean restart")
          .isEqualTo(50);
    } finally {
      diskYtdb.close();
    }
  }

  // Verifies that repeated restart cycles do not lose data. Each cycle
  // writes data, closes, and reopens — confirming that the flush path
  // consistently persists all pages across multiple shutdown/startup
  // sequences.
  @Test
  public void repeatedRestartCyclesPreserveData() {
    var dbPath = diskTestPath("multi-restart");

    var diskYtdb = createDiskInstance(dbPath, "multitest");
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "multitest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.command("CREATE CLASS MultiRestartTest");
    }
    diskYtdb.close();

    // Perform 3 write-restart cycles
    for (int cycle = 0; cycle < 3; cycle++) {
      diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
          .instance(dbPath);
      try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
          "multitest", "admin", DbTestBase.ADMIN_PASSWORD)) {
        for (int i = 0; i < 10; i++) {
          session.begin();
          session.command("INSERT INTO MultiRestartTest SET cycle = "
              + cycle + ", idx = " + i);
          session.commit();
        }
      }
      diskYtdb.close();
    }

    // Final verification: all 30 records (3 cycles x 10) should exist
    diskYtdb = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
        .instance(dbPath);
    try (var session = (DatabaseSessionEmbedded) diskYtdb.open(
        "multitest", "admin", DbTestBase.ADMIN_PASSWORD)) {
      session.begin();
      int count = 0;
      int[] cycleCounts = new int[3];
      try (var result = session.query("SELECT FROM MultiRestartTest")) {
        while (result.hasNext()) {
          var record = result.next();
          int c = record.getProperty("cycle");
          cycleCounts[c]++;
          count++;
        }
      }
      session.commit();
      assertThat(count)
          .as("all records from all cycles should survive")
          .isEqualTo(30);
      for (int c = 0; c < 3; c++) {
        assertThat(cycleCounts[c])
            .as("cycle " + c + " should have all 10 records")
            .isEqualTo(10);
      }
    } finally {
      diskYtdb.close();
    }
  }

  private static Path diskTestPath(String suffix) {
    var buildDir = Path.of(System.getProperty("buildDirectory", "./target"))
        .toAbsolutePath();
    return buildDir.resolve("wowcache-flush-tests")
        .resolve(suffix + "-" + System.nanoTime());
  }

  private static YouTrackDBImpl createDiskInstance(
      Path dbPath, String dbName) {
    FileUtils.deleteRecursively(dbPath.toFile());
    var instance = (YouTrackDBImpl) com.jetbrains.youtrackdb.api.YourTracks
        .instance(dbPath);
    instance.create(dbName, DatabaseType.DISK,
        new com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential(
            "admin", DbTestBase.ADMIN_PASSWORD,
            com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole.ADMIN));
    return instance;
  }
}
