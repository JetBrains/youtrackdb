package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;

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
