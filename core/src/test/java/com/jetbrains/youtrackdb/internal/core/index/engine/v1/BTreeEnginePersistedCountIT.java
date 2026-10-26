package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.config.YouTrackDBConfig;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.disk.DiskStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.WalTestUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.io.FileUtils;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TestRule;
import org.junit.runners.model.Statement;

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

  private final List<Path> failedCrashCopies = new ArrayList<>();

  @Rule
  public final TestRule crashCopyCleanup = (base, description) -> new Statement() {
    @Override
    public void evaluate() throws Throwable {
      Throwable failure = null;
      try {
        // The base statement includes @After, which shuts down the database manager.
        base.evaluate();
      } catch (Throwable primary) {
        failure = primary;
      }
      for (var path : failedCrashCopies) {
        failure = cleanup(failure, () -> FileUtils.deleteDirectory(path.toFile()));
      }
      if (failure != null) {
        throw failure;
      }
    }
  };

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
  // YTDB-953: single-value null-count recalibration on load
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Regression for YTDB-953. Prior to the fix, BTreeSingleValueIndexEngine.load()
   * unconditionally set the in-memory approximateNullCount to 0, ignoring whether
   * the single tree actually held a persisted visible null entry. After a restart
   * of an index that contained one null-key entry, getNullCount() returned 0
   * instead of 1 until the next buildInitialHistogram(). The fix recalibrates
   * the counter from a direct null-key lookup at load time.
   *
   * <p>The test creates a UNIQUE index, commits a single entity with the indexed
   * property unset (yielding a null key in the index), closes and reopens the
   * database, then asserts that getNullCount() reports 1 from in-memory state
   * read by load() — without going through buildInitialHistogram().
   */
  @Test
  public void singleValue_nullCountSurvivesRestart() throws Exception {
    session.createClassIfNotExist("SVNullTest");
    var cls = session.getClass("SVNullTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("SVNullTest.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert a single entity with no "name" property → one null-key entry in
    // the index. Single-value UNIQUE caps at one entry per key, so the null
    // range holds at most this one row.
    session.begin();
    session.newEntity("SVNullTest");
    session.commit();

    // Close and reopen to force load() to repopulate the in-memory counters
    // from on-disk state.
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    var engine = getBTreeIndexEngine(session, "SVNullTest.name");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Null count must be recalibrated to 1 on load() from persisted state",
              1, engine.getNullCount(atomicOp));
          assertEquals(
              "Total count must still reflect the single persisted entry",
              1, engine.getTotalCount(atomicOp));
        });
  }

  /**
   * End-to-end regression for YTDB-953 — the underflow cascade. Before the fix,
   * load() forced approximateNullCount to 0 even when the on-disk tree held a
   * visible null entry. AbstractStorage.applyIndexCountDeltas still feeds
   * nullDelta polymorphically into addToApproximateNullCount(), so the first
   * REMOVE of the persisted null entry after restart accumulated nullDelta=-1,
   * driving the in-memory counter from 0 to -1 and tripping the
   * "In-memory approximateNullCount underflow" assert. The escaping AssertionError
   * was caught by AbstractStorage.commit()'s outer catch(Error), poisoning the
   * storage via setInError.
   *
   * <p>The test reproduces the cascade trigger: create a single-value index with
   * a persisted null entry, restart, remove the entity holding the null key,
   * commit. With the fix, the commit succeeds and getNullCount() reads 0.
   * Without the fix, the assert fires inside commit() (assertions are enabled
   * in surefire by the {@code <argLine>} configuration).
   */
  @Test
  public void singleValue_removeNullAfterRestart_noUnderflow() throws Exception {
    session.createClassIfNotExist("SVNullRemoveTest");
    var cls = session.getClass("SVNullRemoveTest");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex(
        "SVNullRemoveTest.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Persist one null-key entry.
    session.begin();
    session.newEntity("SVNullRemoveTest");
    session.commit();

    // Restart so load() repopulates the in-memory counters.
    session.close();
    session = (DatabaseSessionEmbedded) youTrackDB.open(databaseName, adminUser, adminPassword);

    // Remove the only entity → index REMOVE on the null key → commit
    // accumulates nullDelta=-1 and feeds it through applyIndexCountDeltas.
    // Pre-fix: the in-memory counter goes 0 → -1 and the underflow assert
    // fires inside commit(). Post-fix: the counter goes 1 → 0 cleanly.
    session.begin();
    session.command("DELETE FROM SVNullRemoveTest");
    session.commit();

    var engine = getBTreeIndexEngine(session, "SVNullRemoveTest.name");
    session.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Null count must read 0 after the persisted null entry is removed",
              0, engine.getNullCount(atomicOp));
          assertEquals(
              "Total count must read 0 after the only entry is removed",
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
  // Crash recovery: protected open-storage copy + WAL replay
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Persist an empty single-value index, insert 100 records under WAL protection, and copy
   * the open storage. Recovery must restore count 100 and exact index and SQL identities.
   * Skipping page redo leaves the empty baseline with count 0.
   */
  @Test
  public void singleValue_countSurvivesCrashRecovery() throws Exception {
    var crashDbName = "crash_count_insert";
    try (var crash = new CrashDatabase(crashDbName)) {
      var crashSession = crash.current;

      crashSession.createClassIfNotExist("CrashSV");
      var cls = crashSession.getClass("CrashSV");
      cls.createProperty("name", PropertyType.STRING);
      cls.createIndex("CrashSV.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

      var source = crash.persistBaselineAndReopen();
      var expectedRids = new HashMap<Object, Set<RID>>();
      WalTestUtils.withWalProtection(source, () -> {
        source.begin();
        for (int i = 0; i < 100; i++) {
          source.newEntity("CrashSV").setProperty("name", "entry_" + i);
        }
        source.commit();
        expectedRids.putAll(queryRidsByKey(source, "CrashSV", "name"));
        copyCrash(source, crashDbName);
      });
      crashSession = crash.openCopy();
      assertRecoveryRan(crashSession);

      var engine = getBTreeIndexEngine(crashSession, "CrashSV.name");
      crashSession.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            assertEquals(
                "Approximate count must be 100 after crash recovery",
                100, engine.getTotalCount(atomicOp));
          });
      assertIndexAndSqlRids(crashSession, "CrashSV", "name", "entry_", 100, expectedRids);
    }
  }

  /**
   * Persist 100 records, delete 50 under WAL protection, and recover an open-storage copy.
   * The count must be exactly 50, not the baseline's 100. Direct index reads and SQL must
   * contain only the surviving identities.
   */
  @Test
  public void singleValue_insertAndDelete_countSurvivesCrashRecovery()
      throws Exception {
    var crashDbName = "crash_count_del";
    try (var crash = new CrashDatabase(crashDbName)) {
      var crashSession = crash.current;

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

      var source = crash.persistBaselineAndReopen();
      var expectedRids = new HashMap<Object, Set<RID>>();
      WalTestUtils.withWalProtection(source, () -> {
        source.begin();
        for (int i = 0; i < 50; i++) {
          source.command("DELETE FROM CrashDel WHERE val = 'v" + i + "'");
        }
        source.commit();
        expectedRids.putAll(queryRidsByKey(source, "CrashDel", "val"));
        copyCrash(source, crashDbName);
      });
      crashSession = crash.openCopy();
      assertRecoveryRan(crashSession);

      var engine = getBTreeIndexEngine(crashSession, "CrashDel.val");
      crashSession.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            // Delta is persisted inside the same atomic operation as the B-tree
            // mutation (persistCountDelta). WAL replay replays the full operation
            // atomically, so the count must be exact.
            assertEquals(
                "Count must be 50 after delete + crash recovery",
                50, engine.getTotalCount(atomicOp));
          });

      assertIndexAndSqlRids(crashSession, "CrashDel", "val", "v", 100, expectedRids);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value crash recovery: protected open-storage copy + WAL replay
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Persist an empty multi-value index. Insert 50 non-null and 10 null records under WAL
   * protection, then recover an open-storage copy. Both trees must recover their counts
   * and exact identities. Skipping page redo leaves total and null counts at 0.
   * This scenario copies completed atomic operations, not a partial atomic WAL unit.
   */
  @Test
  public void multiValue_countsSurviveCrashRecovery() throws Exception {
    var crashDbName = "crash_mv_count";
    try (var crash = new CrashDatabase(crashDbName)) {
      var crashSession = crash.current;

      crashSession.createClassIfNotExist("CrashMV");
      var cls = crashSession.getClass("CrashMV");
      cls.createProperty("tag", PropertyType.STRING);
      cls.createIndex("CrashMV.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

      var source = crash.persistBaselineAndReopen();
      var expectedRids = new HashMap<Object, Set<RID>>();
      WalTestUtils.withWalProtection(source, () -> {
        source.begin();
        for (int i = 0; i < 50; i++) {
          source.newEntity("CrashMV").setProperty("tag", "tag_" + i);
        }
        for (int i = 0; i < 10; i++) {
          // A missing tag exercises the separate null-key tree.
          source.newEntity("CrashMV");
        }
        source.commit();
        expectedRids.putAll(queryRidsByKey(source, "CrashMV", "tag"));
        copyCrash(source, crashDbName);
      });
      crashSession = crash.openCopy();
      assertRecoveryRan(crashSession);

      var engine = getBTreeIndexEngine(crashSession, "CrashMV.tag");
      crashSession.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            assertEquals(
                "Total count must be 60 after crash recovery",
                60, engine.getTotalCount(atomicOp));
            assertEquals(
                "Null count must be 10 after crash recovery",
                10, engine.getNullCount(atomicOp));
          });

      assertIndexAndSqlRids(crashSession, "CrashMV", "tag", "tag_", 50, expectedRids);
    }
  }

  /**
   * Persist 30 non-null and 5 null records. Delete 10 non-null and 2 null records under
   * WAL protection, then recover an open-storage copy. Counts must be total 23 and null 3,
   * not the baseline's 35 and 5. Index and SQL reads must return exact surviving identities.
   */
  @Test
  public void multiValue_insertAndDelete_countSurviveCrashRecovery()
      throws Exception {
    var crashDbName = "crash_mv_del";
    try (var crash = new CrashDatabase(crashDbName)) {
      var crashSession = crash.current;

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

      var source = crash.persistBaselineAndReopen();
      var expectedRids = new HashMap<Object, Set<RID>>();
      WalTestUtils.withWalProtection(source, () -> {
        source.begin();
        for (int i = 0; i < 10; i++) {
          source.command("DELETE FROM CrashMvDel WHERE val = 'v" + i + "'");
        }
        source.commit();

        source.begin();
        source.command("DELETE FROM CrashMvDel WHERE val IS NULL LIMIT 2");
        source.commit();
        // Capture the actual survivors because LIMIT does not specify which null RIDs are deleted.
        expectedRids.putAll(queryRidsByKey(source, "CrashMvDel", "val"));
        copyCrash(source, crashDbName);
      });
      crashSession = crash.openCopy();
      assertRecoveryRan(crashSession);

      var engine = getBTreeIndexEngine(crashSession, "CrashMvDel.val");
      crashSession.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            // Delta is persisted inside the same atomic operation as the B-tree
            // mutation (persistCountDelta). WAL replay replays the full operation
            // atomically, so counts must be exact.
            // 30 + 5 - 10 - 2 = 23 total
            assertEquals(
                "Total count must be 23 after insert+delete + crash recovery",
                23, engine.getTotalCount(atomicOp));
            // 5 - 2 = 3 null
            assertEquals(
                "Null count must be 3 after delete + crash recovery",
                3, engine.getNullCount(atomicOp));
          });

      assertIndexAndSqlRids(crashSession, "CrashMvDel", "val", "v", 30, expectedRids);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // REBUILD INDEX crash recovery: metadata entity survives WAL replay
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Persist an empty index, then insert 100 records and rebuild under WAL protection.
   * Recover an open-storage copy and verify visible metadata, count 100, and exact identities
   * through direct index reads and SQL. The empty baseline cannot satisfy these outcomes.
   * Recovery must restore the rebuilt engine and its metadata registration together.
   *
   * <p>This test exposes YTDB-1464. Re-enable it when that issue is fixed.
   */
  @Test
  @Ignore("YTDB-1464: WAL recovery fails after a crash that follows REBUILD INDEX "
      + "(https://youtrack.jetbrains.com/issue/YTDB-1464)")
  public void singleValue_rebuildIndex_metadataSurvivesCrashRecovery()
      throws Exception {
    var crashDbName = "crash_rebuild_meta";
    try (var crash = new CrashDatabase(crashDbName)) {
      var crashSession = crash.current;
      crashSession.createClassIfNotExist("RebuildCrash");
      var cls = crashSession.getClass("RebuildCrash");
      cls.createProperty("name", PropertyType.STRING);
      cls.createIndex("RebuildCrash.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

      var source = crash.persistBaselineAndReopen();
      var expectedRids = new HashMap<Object, Set<RID>>();
      WalTestUtils.withWalProtection(source, () -> {
        source.begin();
        for (int i = 0; i < 100; i++) {
          source.newEntity("RebuildCrash").setProperty("name", "entry_" + i);
        }
        source.commit();
        source.command("REBUILD INDEX RebuildCrash.name");
        expectedRids.putAll(queryRidsByKey(source, "RebuildCrash", "name"));
        copyCrash(source, crashDbName);
      });
      crashSession = crash.openCopy();
      assertRecoveryRan(crashSession);

      // The rebuilt index must be registered in the recovered metadata.
      var idx = crashSession.getSharedContext().getIndexManager()
          .getIndex("RebuildCrash.name");
      assertNotNull(
          "Index metadata must survive REBUILD INDEX + crash recovery",
          idx);

      // Verify count matches actual record count
      var engine = getBTreeIndexEngine(crashSession, "RebuildCrash.name");
      crashSession.getStorage().getAtomicOperationsManager()
          .executeInsideAtomicOperation(atomicOp -> {
            assertEquals(
                "Count after rebuild + crash recovery must match record count",
                100, engine.getTotalCount(atomicOp));
          });

      assertIndexAndSqlRids(crashSession, "RebuildCrash", "name", "entry_", 100, expectedRids);
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Helpers
  // ═══════════════════════════════════════════════════════════════════════

  private static YouTrackDBConfig crashConfig() {
    // The tiny workloads fit in cache. Keep non-periodic flush paths idle too.
    return YouTrackDBConfig.builder()
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_FUZZY_CHECKPOINT_INTERVAL,
            100000000)
        .addGlobalConfigurationParameter(GlobalConfiguration.DISK_CACHE_SIZE, 256)
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_MAX_SEGMENT_SIZE, 256)
        .addGlobalConfigurationParameter(GlobalConfiguration.WAL_MAX_SIZE, 1024)
        .build();
  }

  private final class CrashDatabase implements AutoCloseable {

    private final String dbName;
    private DatabaseSessionEmbedded current;
    private boolean copyOpened;

    private CrashDatabase(String dbName) {
      this.dbName = dbName;
      youTrackDB.create(dbName, DatabaseType.DISK, crashConfig(),
          adminUser, adminPassword, "admin");
      current = youTrackDB.open(dbName, adminUser, adminPassword, crashConfig());
    }

    private DatabaseSessionEmbedded persistBaselineAndReopen() {
      // A session close alone leaves storage open. forceDatabaseClose performs a normal
      // storage shutdown that flushes baseline pages. It is not the simulated crash.
      current.close();
      youTrackDB.internal.forceDatabaseClose(dbName);
      current = youTrackDB.open(dbName, adminUser, adminPassword, crashConfig());
      return current;
    }

    private DatabaseSessionEmbedded openCopy() {
      if (current.isTxActive()) {
        current.rollback();
      }
      current.close();
      // Closing the source normally cannot change the already copied files.
      youTrackDB.internal.forceDatabaseClose(dbName);
      current = youTrackDB.open(dbName + "_recovered", adminUser, adminPassword, crashConfig());
      copyOpened = true;
      return current;
    }

    @Override
    public void close() throws Exception {
      Throwable failure = cleanup(null, () -> {
        if (!current.isClosed()) {
          current.activateOnCurrentThread();
          if (current.isTxActive()) {
            current.rollback();
          }
          current.close();
        }
      });
      failure = cleanup(failure, () -> youTrackDB.drop(dbName));
      if (copyOpened) {
        failure = cleanup(failure, () -> youTrackDB.drop(dbName + "_recovered"));
      } else {
        // Drop opens storage. A failed recovery must not be retried during cleanup.
        // Delete its files only after the manager releases all storage resources.
        failedCrashCopies.add(Path.of(dbPath).resolve(dbName + "_recovered"));
      }
      if (failure instanceof Exception exception) {
        throw exception;
      }
      if (failure instanceof Error error) {
        throw error;
      }
    }
  }

  private static Throwable cleanup(Throwable failure, WalTestUtils.ThrowingRunnable action) {
    try {
      action.run();
    } catch (Exception | Error cleanupFailure) {
      if (failure == null) {
        return cleanupFailure;
      }
      failure.addSuppressed(cleanupFailure);
    }
    return failure;
  }

  private void copyCrash(DatabaseSessionEmbedded source, String dbName) throws Exception {
    ((DiskStorage) source.getStorage()).getWALInstance().flush();
    WalTestUtils.copyDirtyStorage(Path.of(source.getURL().substring("disk:".length())),
        Path.of(dbPath).resolve(dbName + "_recovered"));
  }

  private static void assertRecoveryRan(DatabaseSessionEmbedded recovered) {
    assertTrue("The copied dirty storage must replay its write-ahead log",
        ((DiskStorage) recovered.getStorage()).wereDataRestoredAfterOpen());
  }

  private static Map<Object, Set<RID>> queryRidsByKey(DatabaseSessionEmbedded source,
      String className, String property) {
    var rids = new HashMap<Object, Set<RID>>();
    source.begin();
    try (var rows = source.query("SELECT FROM " + className)) {
      rows.stream().forEach(row -> rids.computeIfAbsent(row.getProperty(property),
          key -> new HashSet<>()).add(row.getIdentity()));
      return rids;
    } finally {
      source.rollback();
    }
  }

  private static void assertIndexAndSqlRids(DatabaseSessionEmbedded recovered, String className,
      String property, String prefix, int keyCount, Map<Object, Set<RID>> expectedRids) {
    // Check every inserted key, including removed keys, and the separate null-key range.
    for (int i = 0; i < keyCount; i++) {
      var key = prefix + i;
      assertIndexAndSqlRids(recovered, className + "." + property, key,
          "SELECT FROM " + className + " WHERE " + property + " = '" + key + "'",
          expectedRids.getOrDefault(key, Set.of()));
    }
    assertIndexAndSqlRids(recovered, className + "." + property, null,
        "SELECT FROM " + className + " WHERE " + property + " IS NULL",
        expectedRids.getOrDefault(null, Set.of()));
  }

  private static void assertIndexAndSqlRids(DatabaseSessionEmbedded recovered, String indexName,
      Object key, String query, Set<RID> expected) {
    recovered.begin();
    try {
      var index = recovered.getSharedContext().getIndexManager().getIndex(indexName);
      try (var rids = index.getRids(recovered, key)) {
        var actual = rids.toList();
        assertEquals("Recovered index " + indexName + " key " + key + " must have exact RIDs",
            expected, new HashSet<>(actual));
        assertEquals("Recovered index must not contain duplicate RIDs", expected.size(),
            actual.size());
      }
    } finally {
      recovered.rollback();
    }
    recovered.begin();
    try (var rows = recovered.query(query)) {
      assertEquals("Recovered SQL results must have exact RIDs: " + query, expected,
          rows.stream().map(row -> row.getIdentity()).collect(Collectors.toSet()));
    } finally {
      recovered.rollback();
    }
  }

  /**
   * Gets the BTreeIndexEngine for the named index by reading the indexId
   * from the IndexAbstract and looking up the engine in AbstractStorage.
   * Uses reflection because the engine is not exposed through the public API.
   */
  private static BTreeIndexEngine getBTreeIndexEngine(
      DatabaseSessionEmbedded session, String indexName) {
    try {
      var idx = session.getSharedContext().getIndexManager().getIndex(indexName);
      int indexId = com.jetbrains.youtrackdb.internal.core.index.IndexEngineTestSupport
          .externalIdentifier(idx);
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
