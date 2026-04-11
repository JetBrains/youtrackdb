package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

  /**
   * NOTUNIQUE (multi-value) crash recovery for versionedPut.
   *
   * <p>Multi-value indexes use two separate B-trees (svTree for non-null entries,
   * nullTree for null entries) with a different key format ([userKey, RID, version]
   * vs single-value's [userKey, version]). A crash between svTree and nullTree
   * mutations could leave them inconsistent. WAL replay must correctly restore
   * the multi-value key format including embedded RID elements.
   *
   * <p>Inserts multiple records with the same key (multi-value scenario) and
   * some with null key, then simulates crash and verifies all committed data
   * survives WAL replay.
   */
  @Test
  public void multiValue_versionedPut_survivesCrashRecovery() {
    var dbName = "crash_mv_put";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.createClassIfNotExist("MvPutTest");
    var cls = session.getClass("MvPutTest");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("MvPutTest.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    // Insert first batch — 3 records with the same key
    session.begin();
    for (int i = 0; i < 3; i++) {
      session.newEntity("MvPutTest").setProperty("tag", "shared");
    }
    session.commit();

    // Insert second batch — 2 more with same key + 2 with null key
    session.begin();
    for (int i = 0; i < 2; i++) {
      session.newEntity("MvPutTest").setProperty("tag", "shared");
    }
    for (int i = 0; i < 2; i++) {
      // tag is null — will go into nullTree
      session.newEntity("MvPutTest");
    }
    session.commit();

    // Simulate crash — no graceful flush
    session.activateOnCurrentThread();
    ytdb.internal.forceDatabaseClose(dbName);

    // Reopen — WAL replay
    session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.begin();
    try {
      // Verify: all 5 records with key "shared" are accessible via index
      var rs = session.query("SELECT FROM MvPutTest WHERE tag = 'shared'");
      int sharedCount = 0;
      while (rs.hasNext()) {
        var result = rs.next();
        assertEquals("shared", result.getProperty("tag"));
        sharedCount++;
      }
      rs.close();
      assertEquals(
          "All 5 records with key 'shared' must survive crash recovery",
          5, sharedCount);

      // Verify: null-keyed records are accessible
      var nullRs = session.query("SELECT FROM MvPutTest WHERE tag IS NULL");
      int nullCount = 0;
      while (nullRs.hasNext()) {
        nullRs.next();
        nullCount++;
      }
      nullRs.close();
      assertEquals(
          "Both null-keyed records must survive crash recovery",
          2, nullCount);

      // Verify total count
      var countRs = session.query("SELECT count(*) as cnt FROM MvPutTest");
      var totalCount = (Long) countRs.next().getProperty("cnt");
      countRs.close();
      assertEquals("Total record count must be 7 after crash recovery",
          7L, totalCount.longValue());
    } finally {
      session.rollback();
    }

    session.close();
    ytdb.drop(dbName);
  }

  /**
   * NOTUNIQUE (multi-value) crash recovery for versionedRemove.
   *
   * <p>Inserts multiple records with the same key, deletes one record,
   * commits, then simulates crash. After WAL replay, the deleted record
   * must not appear in index results, while the remaining records must
   * still be accessible.
   */
  @Test
  public void multiValue_versionedRemove_survivesCrashRecovery() {
    var dbName = "crash_mv_rm";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.createClassIfNotExist("MvRemTest");
    var cls = session.getClass("MvRemTest");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("MvRemTest.val", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    // Insert 3 records with the same key, each with a unique "seq" to identify them
    session.begin();
    for (int i = 0; i < 3; i++) {
      var entity = session.newEntity("MvRemTest");
      entity.setProperty("val", "same");
      entity.setProperty("seq", i);
    }
    session.commit();

    // Delete one record (seq=0)
    session.begin();
    session.command("DELETE FROM MvRemTest WHERE val = 'same' AND seq = 0 LIMIT 1");
    session.commit();

    // Simulate crash
    session.activateOnCurrentThread();
    ytdb.internal.forceDatabaseClose(dbName);

    // Reopen — WAL replay
    session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.begin();
    try {
      // Verify: only 2 records remain in the index for key "same"
      var rs = session.query("SELECT FROM MvRemTest WHERE val = 'same'");
      int count = 0;
      while (rs.hasNext()) {
        var result = rs.next();
        int seq = result.getProperty("seq");
        assertTrue(
            "Deleted record (seq=0) must not appear after crash recovery",
            seq == 1 || seq == 2);
        count++;
      }
      rs.close();

      assertEquals(
          "Only 2 surviving records should be in index after delete + crash recovery",
          2, count);

      // Verify total count is 2
      var countRs = session.query("SELECT count(*) as cnt FROM MvRemTest");
      var totalCount = (Long) countRs.next().getProperty("cnt");
      countRs.close();
      assertEquals("Total count must be 2 after delete + crash recovery",
          2L, totalCount.longValue());
    } finally {
      session.rollback();
    }

    session.close();
    ytdb.drop(dbName);
  }

  /**
   * Crash with an uncommitted transaction: only committed data should survive.
   *
   * <p>TX1 commits 5 records. TX2 inserts 5 more but does NOT commit.
   * After crash (forceDatabaseClose) and WAL replay, only TX1's records
   * must be visible — the uncommitted TX2 mutations must be rolled back.
   */
  @Test
  public void uncommittedTransaction_notVisibleAfterCrashRecovery() {
    var dbName = "crash_uncommitted";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.createClassIfNotExist("UncommitTest");
    var cls = session.getClass("UncommitTest");
    cls.createProperty("key", PropertyType.STRING);
    cls.createIndex("UncommitTest.key", SchemaClass.INDEX_TYPE.UNIQUE, "key");

    // TX1: insert 5 committed records
    session.begin();
    for (int i = 1; i <= 5; i++) {
      session.newEntity("UncommitTest").setProperty("key", "committed-" + i);
    }
    session.commit();

    // TX2: insert 5 uncommitted records — do NOT commit
    session.begin();
    for (int i = 1; i <= 5; i++) {
      session.newEntity("UncommitTest").setProperty("key", "uncommitted-" + i);
    }
    // No commit — TX2 is still active

    // Simulate crash with uncommitted TX2
    session.activateOnCurrentThread();
    ytdb.internal.forceDatabaseClose(dbName);

    // Reopen — WAL replay should only recover TX1
    session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.begin();
    try {
      // Verify: all 5 committed records are visible
      for (int i = 1; i <= 5; i++) {
        var rs = session.query(
            "SELECT FROM UncommitTest WHERE key = 'committed-" + i + "'");
        assertTrue("Committed record 'committed-" + i
            + "' must be visible after crash recovery", rs.hasNext());
        rs.close();
      }

      // Verify: none of the uncommitted records are visible
      for (int i = 1; i <= 5; i++) {
        var rs = session.query(
            "SELECT FROM UncommitTest WHERE key = 'uncommitted-" + i + "'");
        assertFalse("Uncommitted record 'uncommitted-" + i
            + "' must NOT be visible after crash recovery", rs.hasNext());
        rs.close();
      }

      // Verify total count is 5 (only committed)
      var countRs = session.query("SELECT count(*) as cnt FROM UncommitTest");
      var count = (Long) countRs.next().getProperty("cnt");
      countRs.close();
      assertEquals("Total count must be 5 (uncommitted TX rolled back)",
          5L, count.longValue());
    } finally {
      session.rollback();
    }

    session.close();
    ytdb.drop(dbName);
  }
}
