package com.jetbrains.youtrackdb.internal.core.storage.memory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.tx.Transaction;
import org.junit.Test;

/**
 * Regression tests for YTDB-527: dropping a memory database must not corrupt the YouTrackDB
 * instance state, even if cache entries are not fully released at the time of deletion.
 *
 * <p>Previously, {@code MemoryFile.clear()} threw {@code IllegalStateException} when it found
 * unreleased cache entries. The exception propagated through {@code doShutdownOnDelete()} and
 * prevented {@code drop()} from cleaning up internal maps, poisoning the instance for all
 * subsequent operations.
 */
public class MemoryStorageDropTest {

  /**
   * Verifies that dropping a memory database and then creating a new one with the same name
   * succeeds. This is the basic scenario that was broken by the cascading failure.
   */
  @Test
  public void testDropAndRecreateMemoryDatabase() {
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      ytdb.create("dropRecreateTest", DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
              PredefinedLocalRole.ADMIN));
      assertTrue(ytdb.exists("dropRecreateTest"));

      // Insert some data so there are cache pages in use
      var session = ytdb.open("dropRecreateTest", "admin", DbTestBase.ADMIN_PASSWORD);
      session.executeInTx(Transaction::newEntity);
      session.close();

      // Drop the database
      ytdb.drop("dropRecreateTest");
      assertFalse(ytdb.exists("dropRecreateTest"));

      // Re-create with the same name — this must succeed
      ytdb.create("dropRecreateTest", DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
              PredefinedLocalRole.ADMIN));
      assertTrue(ytdb.exists("dropRecreateTest"));

      // Verify the new database is usable
      session = ytdb.open("dropRecreateTest", "admin", DbTestBase.ADMIN_PASSWORD);
      session.executeInTx(Transaction::newEntity);
      session.close();

      ytdb.drop("dropRecreateTest");
    }
  }

  /**
   * Verifies that dropping one database does not affect the ability to use another database
   * on the same YouTrackDB instance.
   */
  @Test
  public void testDropDoesNotCorruptOtherDatabases() {
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      ytdb.create("dbA", DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
              PredefinedLocalRole.ADMIN));
      ytdb.create("dbB", DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
              PredefinedLocalRole.ADMIN));

      // Use both databases
      var sessionA = ytdb.open("dbA", "admin", DbTestBase.ADMIN_PASSWORD);
      sessionA.executeInTx(Transaction::newEntity);
      sessionA.close();

      var sessionB = ytdb.open("dbB", "admin", DbTestBase.ADMIN_PASSWORD);
      sessionB.executeInTx(Transaction::newEntity);
      sessionB.close();

      // Drop database A
      ytdb.drop("dbA");
      assertFalse(ytdb.exists("dbA"));

      // Database B must still be fully functional
      assertTrue(ytdb.exists("dbB"));
      sessionB = ytdb.open("dbB", "admin", DbTestBase.ADMIN_PASSWORD);
      sessionB.executeInTx(Transaction::newEntity);
      sessionB.close();

      ytdb.drop("dbB");
    }
  }

  /**
   * Verifies that the YouTrackDB instance remains usable after multiple rapid create-drop cycles.
   * This exercises the cleanup path repeatedly to expose any state leaks.
   */
  @Test
  public void testRepeatedCreateDropCycles() {
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      for (int i = 0; i < 10; i++) {
        var dbName = "cycleDb" + i;
        ytdb.create(dbName, DatabaseType.MEMORY,
            new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD,
                PredefinedLocalRole.ADMIN));

        var session = ytdb.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
        session.executeInTx(Transaction::newEntity);
        session.close();

        ytdb.drop(dbName);
        assertFalse("Database should not exist after drop: " + dbName,
            ytdb.exists(dbName));
      }
    }
  }
}
