package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.WalTestUtils;
import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Crash-recovery tests for versioned put/remove atomicity.
 *
 * <p>Verifies that the two-step mutations in {@code doVersionedPut()} (remove old +
 * put new) and {@code doVersionedRemove()} (remove + put tombstone) survive a crash
 * and WAL replay. Both mutations use the same {@code atomicOperation}.
 *
 * <p>Each test persists a baseline, protects mutations from periodic page writes and WAL
 * cleanup, then copies the open storage after flushing only the WAL. Opening the copy under
 * a distinct database name must run recovery and restore index and SQL results that differ
 * from the baseline. Closing the source normally after the copy cannot alter the snapshot.
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
    ytdb = newManager();
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
  public void versionedPut_survivesCrashRecovery() throws Exception {
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

    var rid = entity.getIdentity();
    var source = persistBaselineAndReopen(session, dbName);
    WalTestUtils.withWalProtection(source, () -> {
      // Only the WAL contains the Alice-to-Bob update when the open storage is copied.
      source.begin();
      source.getActiveTransaction().loadEntity(rid).setProperty("name", "Bob");
      source.commit();
      copyCrash(source, dbName);
    });
    session = closeSourceAndOpenCopy(source, dbName);
    assertRecoveryRan(session);
    assertIndexAndSqlRids(session, "PutTest.name", "Bob",
        "SELECT FROM PutTest WHERE name = 'Bob'", Set.of(rid));
    assertIndexAndSqlRids(session, "PutTest.name", "Alice",
        "SELECT FROM PutTest WHERE name = 'Alice'", Set.of());

    // Verify: "Bob" is accessible via SQL
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
      assertFalse("Old key 'Alice' must not be accessible after update + crash recovery",
          rsOld.hasNext());
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
  public void versionedRemove_survivesCrashRecovery() throws Exception {
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

    var source = persistBaselineAndReopen(session, dbName);
    WalTestUtils.withWalProtection(source, () -> {
      source.begin();
      source.command("DELETE FROM RemTest WHERE val = 'X'");
      source.commit();
      copyCrash(source, dbName);
    });
    session = closeSourceAndOpenCopy(source, dbName);
    assertRecoveryRan(session);
    assertIndexAndSqlRids(session, "RemTest.val", "X",
        "SELECT FROM RemTest WHERE val = 'X'", Set.of());

    // Verify: "X" must not be in SQL results (was deleted)
    session.begin();
    try {
      var rs = session.query("SELECT FROM RemTest WHERE val = 'X'");
      assertFalse(
          "Deleted record must not be accessible via index after crash recovery",
          rs.hasNext());
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
  public void multiValue_versionedPut_survivesCrashRecovery() throws Exception {
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

    var source = persistBaselineAndReopen(session, dbName);
    var sharedRids = new HashSet<RID>();
    var nullRids = new HashSet<RID>();
    WalTestUtils.withWalProtection(source, () -> {
      source.begin();
      for (int i = 0; i < 2; i++) {
        source.newEntity("MvPutTest").setProperty("tag", "shared");
      }
      for (int i = 0; i < 2; i++) {
        // A missing tag exercises the separate null-key tree.
        source.newEntity("MvPutTest");
      }
      source.commit();
      sharedRids.addAll(queryRids(source, "SELECT FROM MvPutTest WHERE tag = 'shared'"));
      nullRids.addAll(queryRids(source, "SELECT FROM MvPutTest WHERE tag IS NULL"));
      assertEquals(5, sharedRids.size());
      assertEquals(2, nullRids.size());
      copyCrash(source, dbName);
    });
    session = closeSourceAndOpenCopy(source, dbName);
    assertRecoveryRan(session);
    assertIndexAndSqlRids(session, "MvPutTest.tag", "shared",
        "SELECT FROM MvPutTest WHERE tag = 'shared'", sharedRids);
    assertIndexAndSqlRids(session, "MvPutTest.tag", null,
        "SELECT FROM MvPutTest WHERE tag IS NULL", nullRids);

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
  public void multiValue_versionedRemove_survivesCrashRecovery() throws Exception {
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

    var survivingRids = queryRids(session, "SELECT FROM MvRemTest WHERE seq IN [1, 2]");
    assertEquals(2, survivingRids.size());
    var source = persistBaselineAndReopen(session, dbName);
    WalTestUtils.withWalProtection(source, () -> {
      source.begin();
      source.command("DELETE FROM MvRemTest WHERE val = 'same' AND seq = 0 LIMIT 1");
      source.commit();
      copyCrash(source, dbName);
    });
    session = closeSourceAndOpenCopy(source, dbName);
    assertRecoveryRan(session);
    assertIndexAndSqlRids(session, "MvRemTest.val", "same",
        "SELECT FROM MvRemTest WHERE val = 'same'", survivingRids);

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
   * The persisted baseline is empty. The files are copied while TX2 is still active.
   * TX2's inserts exist only in the memory of the source session.
   * The copy never contains them.
   * After WAL replay, only TX1's records must be visible. TX2 checks transaction visibility,
   * not a crash inside an atomic WAL unit.
   */
  @Test
  public void uncommittedTransaction_notVisibleAfterCrashRecovery() throws Exception {
    var dbName = "crash_uncommitted";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.createClassIfNotExist("UncommitTest");
    var cls = session.getClass("UncommitTest");
    cls.createProperty("key", PropertyType.STRING);
    cls.createIndex("UncommitTest.key", SchemaClass.INDEX_TYPE.UNIQUE, "key");

    // Persist an empty baseline so that skipping replay also loses the committed records.
    var source = persistBaselineAndReopen(session, dbName);
    var committedRids = new HashMap<String, Set<RID>>();
    WalTestUtils.withWalProtection(source, () -> {
      source.begin();
      for (int i = 1; i <= 5; i++) {
        source.newEntity("UncommitTest").setProperty("key", "committed-" + i);
      }
      source.commit();
      for (int i = 1; i <= 5; i++) {
        var key = "committed-" + i;
        var rids = queryRids(source, "SELECT FROM UncommitTest WHERE key = '" + key + "'");
        assertEquals(1, rids.size());
        committedRids.put(key, rids);
      }

      source.begin();
      for (int i = 1; i <= 5; i++) {
        source.newEntity("UncommitTest").setProperty("key", "uncommitted-" + i);
      }
      // Copy before rolling TX2 back on the source.
      copyCrash(source, dbName);
    });
    session = closeSourceAndOpenCopy(source, dbName);
    assertRecoveryRan(session);
    for (int i = 1; i <= 5; i++) {
      var key = "committed-" + i;
      assertIndexAndSqlRids(session, "UncommitTest.key", key,
          "SELECT FROM UncommitTest WHERE key = '" + key + "'", committedRids.get(key));
      var uncommittedKey = "uncommitted-" + i;
      assertIndexAndSqlRids(session, "UncommitTest.key", uncommittedKey,
          "SELECT FROM UncommitTest WHERE key = '" + uncommittedKey + "'", Set.of());
    }

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

  /**
   * Tombstone resurrection crash recovery: insert → delete → re-insert → crash.
   *
   * <p>The resurrection path in doVersionedPut accumulates +1 when
   * overwriting a TombstoneRID. After WAL replay, the final state must show
   * the final inserted identity as visible through both the index and SQL,
   * and the total record count must be exactly 1 (not 0 or 2).
   */
  @Test
  public void resurrection_overTombstone_survivesCrashRecovery() throws Exception {
    var dbName = "crash_resurrection";
    ytdb.create(dbName, DatabaseType.DISK, ADMIN, ADMIN_PWD, "admin");

    var session = (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);

    session.createClassIfNotExist("ResTest");
    var cls = session.getClass("ResTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("ResTest.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Keep even the first Alice out of the baseline. Replay must recover the final identity.
    var source = persistBaselineAndReopen(session, dbName);
    var finalRids = new HashSet<RID>();
    WalTestUtils.withWalProtection(source, () -> {
      source.begin();
      source.newEntity("ResTest").setProperty("name", "Alice");
      source.commit();

      source.begin();
      source.command("DELETE FROM ResTest WHERE name = 'Alice'");
      source.commit();

      source.begin();
      var resurrected = source.newEntity("ResTest");
      resurrected.setProperty("name", "Alice");
      source.commit();
      finalRids.add(resurrected.getIdentity());
      copyCrash(source, dbName);
    });
    session = closeSourceAndOpenCopy(source, dbName);
    assertRecoveryRan(session);
    assertIndexAndSqlRids(session, "ResTest.name", "Alice",
        "SELECT FROM ResTest WHERE name = 'Alice'", finalRids);

    session.begin();
    try {
      // Verify: "Alice" is accessible via index
      var rs = session.query("SELECT FROM ResTest WHERE name = 'Alice'");
      assertTrue(
          "Resurrected 'Alice' must be accessible via index after crash recovery",
          rs.hasNext());
      var result = rs.next();
      assertEquals("Alice", result.getProperty("name"));
      assertFalse("Only one 'Alice' record should exist", rs.hasNext());
      rs.close();

      // Verify total count is 1 (insert +1, delete -1, resurrection +1 = net +1)
      var countRs = session.query("SELECT count(*) as cnt FROM ResTest");
      var count = (Long) countRs.next().getProperty("cnt");
      countRs.close();
      assertEquals(
          "Total count must be 1 after insert/delete/resurrection + crash recovery",
          1L, count.longValue());
    } finally {
      session.rollback();
    }

    session.close();
    ytdb.drop(dbName);
  }

  private YouTrackDBImpl newManager() {
    // The tiny workloads fit in cache. These limits keep non-periodic flush paths idle too.
    var config = YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL,
            100000000)
        .addGlobalConfigurationParameter(GlobalConfiguration.DISK_CACHE_SIZE, 256)
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_MAX_SEGMENT_SIZE, 256)
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_MAX_SIZE, 1024)
        .build();
    return (YouTrackDBImpl) YourTracks.instance(testDir, config.toApacheConfiguration());
  }

  private DatabaseSessionEmbedded persistBaselineAndReopen(
      DatabaseSessionEmbedded session, String dbName) {
    // A session close alone leaves storage open. Closing the manager persists the baseline pages.
    session.close();
    ytdb.close();
    ytdb = newManager();
    return (DatabaseSessionEmbedded) ytdb.open(dbName, ADMIN, ADMIN_PWD);
  }

  private void copyCrash(DatabaseSessionEmbedded source, String dbName) throws Exception {
    var storage = (DiskStorage) source.getStorage();
    storage.getWALInstance().flush();
    WalTestUtils.copyDirtyStorage(Path.of(source.getURL().substring("disk:".length())),
        Path.of(testDir).resolve(dbName + "_recovered"));
  }

  private DatabaseSessionEmbedded closeSourceAndOpenCopy(
      DatabaseSessionEmbedded source, String dbName) {
    if (source.isTxActive()) {
      source.rollback();
    }
    source.close();
    ytdb.close();
    ytdb = newManager();
    return (DatabaseSessionEmbedded) ytdb.open(dbName + "_recovered", ADMIN, ADMIN_PWD);
  }

  private static void assertRecoveryRan(DatabaseSessionEmbedded session) {
    assertTrue("The copied dirty storage must replay its write-ahead log",
        ((DiskStorage) session.getStorage()).wereDataRestoredAfterOpen());
  }

  private static Set<RID> queryRids(DatabaseSessionEmbedded session, String query) {
    session.begin();
    try (var rows = session.query(query)) {
      return rows.stream().map(row -> row.getIdentity()).collect(Collectors.toSet());
    } finally {
      session.rollback();
    }
  }

  private static void assertIndexAndSqlRids(DatabaseSessionEmbedded session, String indexName,
      Object key, String query, Set<RID> expected) {
    session.begin();
    try {
      var index = session.getSharedContext().getIndexManager().getIndex(indexName);
      try (var rids = index.getRids(session, key)) {
        var actual = rids.toList();
        assertEquals("Recovered index " + indexName + " key " + key + " must have exact RIDs",
            expected, new HashSet<>(actual));
        assertEquals("Recovered index must not contain duplicate RIDs", expected.size(),
            actual.size());
      }
    } finally {
      session.rollback();
    }
    assertEquals("Recovered SQL results must have exact RIDs: " + query,
        expected, queryRids(session, query));
  }
}
