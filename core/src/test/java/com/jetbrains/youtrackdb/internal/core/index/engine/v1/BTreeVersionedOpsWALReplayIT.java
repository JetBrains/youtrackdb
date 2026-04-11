package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.io.File;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Crash-recovery tests for versioned put/remove atomicity.
 *
 * <p>Verifies that the two-step mutations in {@code doVersionedPut()} (remove old +
 * put new) and {@code doVersionedRemove()} (remove + put tombstone) survive crash
 * (forceDatabaseClose) + WAL replay. Both mutations use the same
 * {@code atomicOperation}, making them atomic at the WAL level.
 *
 * <p>Each test creates a DISK database, performs indexed operations, simulates a
 * non-graceful shutdown, reopens (triggering WAL replay), and verifies that the
 * index state is consistent.
 */
@Category(SequentialTest.class)
public class BTreeVersionedOpsWALReplayIT {

  private static final String ADMIN = "admin";
  private static final String ADMIN_PWD = DbTestBase.ADMIN_PASSWORD;

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

  /**
   * doVersionedPut atomicity: insert → update → crash → reopen.
   * After WAL replay, the updated record must be accessible via the index
   * (never in a half-updated state where the old key is removed but the
   * new key is missing).
   */
  @Test
  public void versionedPut_survivesCrashRecovery() {
    var dbName = "walPut";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.createClassIfNotExist("PutTest");
    var cls = session.getClass("PutTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PutTest.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert initial record
    session.begin();
    var entity = session.newEntity("PutTest");
    entity.setProperty("name", "Alice");
    session.commit();

    // Update the record — triggers doVersionedPut (remove old + put new)
    session.begin();
    var tx = session.getActiveTransaction();
    entity = tx.load(entity);
    entity.setProperty("name", "Bob");
    session.commit();

    // Simulate crash — no graceful flush
    session.activateOnCurrentThread();
    ytdb.internal.forceDatabaseClose(dbName);

    // Reopen — WAL replay
    session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    // Verify: "Bob" is accessible via index
    session.begin();
    try {
      var rs = session.query("SELECT FROM PutTest WHERE name = 'Bob'");
      assertTrue("Updated record must be accessible via index after crash recovery",
          rs.hasNext());
      var result = rs.next();
      assertEquals("Bob", result.getProperty("name"));
      rs.close();

      // "Alice" must not be in the index (was replaced)
      var rsOld = session.query("SELECT FROM PutTest WHERE name = 'Alice'");
      assertTrue("Old key 'Alice' must not be accessible after update + crash recovery",
          !rsOld.hasNext());
      rsOld.close();
    } finally {
      session.rollback();
    }

    session.close();
    ytdb.drop(dbName);
  }

  /**
   * doVersionedRemove atomicity: insert → delete → crash → reopen.
   * After WAL replay, the tombstone must be properly present — the record
   * must not be accessible via the index.
   */
  @Test
  public void versionedRemove_survivesCrashRecovery() {
    var dbName = "walRemove";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.createClassIfNotExist("RemTest");
    var cls = session.getClass("RemTest");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("RemTest.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    // Insert initial record
    session.begin();
    session.newEntity("RemTest").setProperty("val", "X");
    session.commit();

    // Delete — triggers doVersionedRemove
    session.begin();
    session.command("DELETE FROM RemTest WHERE val = 'X'");
    session.commit();

    // Simulate crash
    session.activateOnCurrentThread();
    ytdb.internal.forceDatabaseClose(dbName);

    // Reopen — WAL replay
    session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    // Verify: "X" must not be in the index (was deleted)
    session.begin();
    try {
      var rs = session.query("SELECT FROM RemTest WHERE val = 'X'");
      assertTrue(
          "Deleted record must not be accessible via index after crash recovery",
          !rs.hasNext());
      rs.close();

      // Verify total count is 0
      var countRs = session.query("SELECT count(*) as cnt FROM RemTest");
      var count = (Long) countRs.next().getProperty("cnt");
      countRs.close();
      assertEquals("Total count must be 0 after delete + crash recovery",
          0L, count.longValue());
    } finally {
      session.rollback();
    }

    session.close();
    ytdb.drop(dbName);
  }
}
