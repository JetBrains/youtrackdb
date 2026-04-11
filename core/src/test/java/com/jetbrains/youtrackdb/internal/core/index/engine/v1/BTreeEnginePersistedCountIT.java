package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration tests for persisted approximate index entries count.
 *
 * <p>Verifies that the APPROXIMATE_ENTRIES_COUNT field on BTree entry point
 * pages is correctly maintained through the full engine lifecycle: insert,
 * commit, restart, clear, buildInitialHistogram. Tests both single-value
 * and multi-value index engines through the database API.
 */
@Category(SequentialTest.class)
public class BTreeEnginePersistedCountIT extends DbTestBase {

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value index: count survives restart
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Insert entries into a single-value index, commit, close and reopen the
   * database, verify getTotalCount returns the correct value without scanning.
   */
  @Test
  public void singleValue_countSurvivesRestart() throws Exception {
    // Create schema with a unique (single-value) index
    session.createClassIfNotExist("SVTest");
    var cls = session.getClass("SVTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("SVTest.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert 5 entries and commit
    session.begin();
    for (int i = 0; i < 5; i++) {
      session.newEntity("SVTest").setProperty("name", "entry_" + i);
    }
    session.commit();

    // Close and reopen the database
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    // Verify count is correct after restart — should read from persisted
    // entry point page, not scan
    var engine = getBTreeIndexEngine(session, "SVTest.name");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Persisted count must survive restart",
              5, engine.getTotalCount(atomicOp));
        });
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Delta accumulation across multiple transactions
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Insert entries in TX1, commit, insert more in TX2, commit, remove some
   * in TX3, commit. Verify persisted count reflects cumulative delta after
   * restart.
   */
  @Test
  public void singleValue_deltaAccumulationAcrossTransactions() throws Exception {
    session.createClassIfNotExist("DeltaTest");
    var cls = session.getClass("DeltaTest");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("DeltaTest.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    // TX1: insert 3 entries
    session.begin();
    for (int i = 0; i < 3; i++) {
      session.newEntity("DeltaTest").setProperty("val", "v" + i);
    }
    session.commit();

    // TX2: insert 2 more
    session.begin();
    for (int i = 3; i < 5; i++) {
      session.newEntity("DeltaTest").setProperty("val", "v" + i);
    }
    session.commit();

    // TX3: remove 1 entry
    session.begin();
    session.command("DELETE FROM DeltaTest WHERE val = 'v0'");
    session.commit();

    // Restart and verify cumulative count: 3 + 2 - 1 = 4
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(session, "DeltaTest.val");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Count must reflect cumulative delta across transactions",
              4, engine.getTotalCount(atomicOp));
        });
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value index: both tree counts survive restart
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Insert both null and non-null entries into a multi-value (NOTUNIQUE)
   * index, commit, close and reopen, verify both getTotalCount and
   * getNullCount are correctly restored from persisted counts on both trees.
   */
  @Test
  public void multiValue_countsSurviveRestart() throws Exception {
    session.createClassIfNotExist("MVTest");
    var cls = session.getClass("MVTest");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("MVTest.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    // Insert 3 non-null + 2 null entries
    session.begin();
    for (int i = 0; i < 3; i++) {
      session.newEntity("MVTest").setProperty("tag", "t" + i);
    }
    for (int i = 0; i < 2; i++) {
      // No tag property → null key in index
      session.newEntity("MVTest");
    }
    session.commit();

    // Close and reopen
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(session, "MVTest.tag");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Total count must include both null and non-null entries",
              5, engine.getTotalCount(atomicOp));
          assertEquals(
              "Null count must be restored from nullTree's persisted count",
              2, engine.getNullCount(atomicOp));
        });
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Clear + re-insert
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Insert entries, commit, rebuild index (which clears + re-inserts),
   * commit, restart. Verify persisted count matches the re-inserted count.
   */
  @Test
  public void singleValue_clearAndRebuild_countsCorrectAfterRestart()
      throws Exception {
    session.createClassIfNotExist("ClearTest");
    var cls = session.getClass("ClearTest");
    cls.createProperty("key", PropertyType.STRING);
    cls.createIndex("ClearTest.key", SchemaClass.INDEX_TYPE.UNIQUE, "key");

    // Insert 5 entries
    session.begin();
    for (int i = 0; i < 5; i++) {
      session.newEntity("ClearTest").setProperty("key", "k" + i);
    }
    session.commit();

    // Rebuild index — this clears + re-populates from records
    session.command("REBUILD INDEX ClearTest.key");

    // Restart and verify count
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(session, "ClearTest.key");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Count after clear + rebuild must match record count",
              5, engine.getTotalCount(atomicOp));
        });
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Empty index: zero count survives restart
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Create an index with no entries, commit, close and reopen. Verify
   * that count is correctly 0 from the persisted entry point page.
   */
  @Test
  public void singleValue_emptyIndex_zeroCountSurvivesRestart()
      throws Exception {
    session.createClassIfNotExist("EmptyTest");
    var cls = session.getClass("EmptyTest");
    cls.createProperty("id", PropertyType.STRING);
    cls.createIndex("EmptyTest.id", SchemaClass.INDEX_TYPE.UNIQUE, "id");

    // No insertions — index is empty

    // Close and reopen
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(session, "EmptyTest.id");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Empty index count must be 0 after restart",
              0, engine.getTotalCount(atomicOp));
        });
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Rollback: persisted count unchanged
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Insert entries in TX1 and commit, then begin TX2 with more inserts and
   * rollback. Restart and verify that the persisted count reflects only TX1
   * — the rolled-back TX2 must not affect the persisted count.
   */
  @Test
  public void singleValue_rollbackDoesNotAffectPersistedCount()
      throws Exception {
    session.createClassIfNotExist("RBTest");
    var cls = session.getClass("RBTest");
    cls.createProperty("key", PropertyType.STRING);
    cls.createIndex("RBTest.key", SchemaClass.INDEX_TYPE.UNIQUE, "key");

    // TX1: insert 3 entries, commit
    session.begin();
    for (int i = 0; i < 3; i++) {
      session.newEntity("RBTest").setProperty("key", "k" + i);
    }
    session.commit();

    // TX2: insert 2 more, then rollback
    session.begin();
    session.newEntity("RBTest").setProperty("key", "k3");
    session.newEntity("RBTest").setProperty("key", "k4");
    session.rollback();

    // Restart and verify count reflects only TX1
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(session, "RBTest.key");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Rolled-back TX must not affect persisted count",
              3, engine.getTotalCount(atomicOp));
        });
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Crash recovery: forceDatabaseClose + WAL replay
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Insert 100 records into a DISK database, commit, call forceDatabaseClose()
   * (simulates non-graceful shutdown — no flush), reopen (triggers WAL replay),
   * and verify that the approximate entries count is correct.
   */
  @Test
  public void singleValue_countSurvivesCrashRecovery() throws Exception {
    var crashDbName = "crash_count_insert";
    youTrackDB.create(crashDbName, DatabaseType.DISK,
        adminUser, adminPassword, "admin");
    var crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    crashSession.createClassIfNotExist("CrashSV");
    var cls = crashSession.getClass("CrashSV");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("CrashSV.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    crashSession.begin();
    for (int i = 0; i < 100; i++) {
      crashSession.newEntity("CrashSV").setProperty("name", "entry_" + i);
    }
    crashSession.commit();

    // Simulate non-graceful shutdown — no flush
    crashSession.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(crashDbName);

    // Reopen — WAL replay restores the B-tree
    crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(crashSession, "CrashSV.name");
    var finalSession = crashSession;
    crashSession.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Approximate count must be 100 after crash recovery",
              100, engine.getTotalCount(atomicOp));
        });

    finalSession.close();
    youTrackDB.drop(crashDbName);
  }

  /**
   * Insert 100 records, commit, delete 50, commit, forceDatabaseClose, reopen,
   * verify approximate count is ~50.
   */
  @Test
  public void singleValue_insertAndDelete_countSurvivesCrashRecovery()
      throws Exception {
    var crashDbName = "crash_count_del";
    youTrackDB.create(crashDbName, DatabaseType.DISK,
        adminUser, adminPassword, "admin");
    var crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    crashSession.createClassIfNotExist("CrashDel");
    var cls = crashSession.getClass("CrashDel");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("CrashDel.val", SchemaClass.INDEX_TYPE.UNIQUE, "val");

    // Insert 100
    crashSession.begin();
    for (int i = 0; i < 100; i++) {
      crashSession.newEntity("CrashDel").setProperty("val", "v" + i);
    }
    crashSession.commit();

    // Delete 50
    crashSession.begin();
    for (int i = 0; i < 50; i++) {
      crashSession.command("DELETE FROM CrashDel WHERE val = 'v" + i + "'");
    }
    crashSession.commit();

    // Simulate non-graceful shutdown
    crashSession.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(crashDbName);

    // Reopen — WAL replay
    crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(crashSession, "CrashDel.val");
    var finalSession = crashSession;
    crashSession.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          long count = engine.getTotalCount(atomicOp);
          // Approximate count should be 50 after crash recovery.
          // Allow a small tolerance since it's an approximate count.
          assertTrue(
              "Approximate count must be ~50 after delete + crash recovery, got " + count,
              count >= 45 && count <= 55);
        });

    finalSession.close();
    youTrackDB.drop(crashDbName);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value crash recovery: forceDatabaseClose + WAL replay
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Multi-value crash recovery for persisted counts: insert non-null and null
   * entries, forceDatabaseClose (no flush), reopen (WAL replay), verify both
   * getTotalCount and getNullCount are correct.
   *
   * <p>persistCountDelta() for multi-value indexes issues TWO separate page
   * writes (one to svTree's entry point, one to nullTree's entry point). If
   * the process crashes after one write but before the other, WAL replay must
   * replay both atomically.
   */
  @Test
  public void multiValue_countsSurviveCrashRecovery() throws Exception {
    var crashDbName = "crash_mv_count";
    youTrackDB.create(crashDbName, DatabaseType.DISK,
        adminUser, adminPassword, "admin");
    var crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    crashSession.createClassIfNotExist("CrashMV");
    var cls = crashSession.getClass("CrashMV");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("CrashMV.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    // Insert 50 non-null entries + 10 null entries
    crashSession.begin();
    for (int i = 0; i < 50; i++) {
      crashSession.newEntity("CrashMV").setProperty("tag", "tag_" + i);
    }
    for (int i = 0; i < 10; i++) {
      // No tag property → null key in nullTree
      crashSession.newEntity("CrashMV");
    }
    crashSession.commit();

    // Simulate non-graceful shutdown — no flush
    crashSession.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(crashDbName);

    // Reopen — WAL replay restores both B-trees
    crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(crashSession, "CrashMV.tag");
    var finalSession = crashSession;
    crashSession.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Total count must be 60 after crash recovery",
              60, engine.getTotalCount(atomicOp));
          assertEquals(
              "Null count must be 10 after crash recovery",
              10, engine.getNullCount(atomicOp));
        });

    finalSession.close();
    youTrackDB.drop(crashDbName);
  }

  /**
   * Multi-value crash recovery after inserts and deletes: insert non-null
   * and null entries, delete some of each, forceDatabaseClose, reopen,
   * verify counts reflect the net state.
   */
  @Test
  public void multiValue_insertAndDelete_countSurviveCrashRecovery()
      throws Exception {
    var crashDbName = "crash_mv_del";
    youTrackDB.create(crashDbName, DatabaseType.DISK,
        adminUser, adminPassword, "admin");
    var crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    crashSession.createClassIfNotExist("CrashMvDel");
    var cls = crashSession.getClass("CrashMvDel");
    cls.createProperty("val", PropertyType.STRING);
    cls.createIndex("CrashMvDel.val", SchemaClass.INDEX_TYPE.NOTUNIQUE, "val");

    // Insert 30 non-null + 5 null entries
    crashSession.begin();
    for (int i = 0; i < 30; i++) {
      crashSession.newEntity("CrashMvDel").setProperty("val", "v" + i);
    }
    for (int i = 0; i < 5; i++) {
      crashSession.newEntity("CrashMvDel");
    }
    crashSession.commit();

    // Delete 10 non-null entries
    crashSession.begin();
    for (int i = 0; i < 10; i++) {
      crashSession.command("DELETE FROM CrashMvDel WHERE val = 'v" + i + "'");
    }
    crashSession.commit();

    // Delete 2 null entries
    crashSession.begin();
    crashSession.command("DELETE FROM CrashMvDel WHERE val IS NULL LIMIT 2");
    crashSession.commit();

    // Simulate non-graceful shutdown
    crashSession.activateOnCurrentThread();
    youTrackDB.internal.forceDatabaseClose(crashDbName);

    // Reopen — WAL replay
    crashSession =
        (DatabaseSessionEmbedded) youTrackDB.open(crashDbName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(crashSession, "CrashMvDel.val");
    var finalSession = crashSession;
    crashSession.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          // 30 + 5 - 10 - 2 = 23 total
          long totalCount = engine.getTotalCount(atomicOp);
          assertTrue(
              "Total count must be ~23 after insert+delete + crash recovery, got "
                  + totalCount,
              totalCount >= 20 && totalCount <= 26);
          // 5 - 2 = 3 null
          long nullCount = engine.getNullCount(atomicOp);
          assertTrue(
              "Null count must be ~3 after delete + crash recovery, got "
                  + nullCount,
              nullCount >= 2 && nullCount <= 4);
        });

    finalSession.close();
    youTrackDB.drop(crashDbName);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Gets the BTreeIndexEngine for the named index by reading the indexId
   * from the IndexAbstract and looking up the engine in AbstractStorage.
   * Uses reflection because the engine is not exposed through the public API.
   */
  private static BTreeIndexEngine getBTreeIndexEngine(
      DatabaseSessionEmbedded session, String indexName) {
    try {
      var idx = session.getSharedContext().getIndexManager().getIndex(indexName);
      var indexIdField = IndexAbstract.class.getDeclaredField("indexId");
      indexIdField.setAccessible(true);
      int indexId = indexIdField.getInt(idx);
      var storage = (AbstractStorage) session.getStorage();
      var getEngineMethod = AbstractStorage.class
          .getMethod("getIndexEngine", int.class);
      return (BTreeIndexEngine) getEngineMethod.invoke(storage, indexId);
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to get BTreeIndexEngine for " + indexName, e);
    }
  }
}
