package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChanges.OPERATION;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression for the pure-delta-encoded {@code clear()} on the single-value
 * (UNIQUE) index engine, exercised through the main commit path. The
 * scenario mirrors
 * {@link BTreeMultiValueIndexEngineClearRollbackTest} for the single-tree
 * case, where the single {@code sbTree} stores both null and non-null
 * entries and {@code persistCountDelta} ignores {@code nullDelta} because
 * the persisted side moves by {@code totalDelta} alone.
 *
 * <p>The transaction is marked {@code cleared = true} via the public
 * {@code FrontendTransaction.addIndexEntry(... OPERATION.CLEAR ...)} API.
 * A pushed {@link RecordSerializationOperation} throws a wrapped
 * {@link java.io.IOException} from inside {@code executeOperations}, which
 * fires before {@code commitIndexes} would dispatch to {@code doClearIndex}.
 * The throw routes through the pre-{@code endTxCommit} catch in
 * {@code AbstractStorage.commit} (whose clause covers
 * {@code IOException | RuntimeException | AssertionError}) and into rollback.
 *
 * <p>The contract pinned here is the weaker shape that the failure-injection
 * seam can actually verify: a rolled-back transaction tagged with
 * {@code OPERATION.CLEAR} plus queued puts leaves the in-memory and
 * persisted counter sides at their preparatory-commit values because the
 * failure fires inside {@code executeOperations} (the body that runs the
 * pushed {@code RecordSerializationOperation}) before {@code commitIndexes}
 * reaches the {@code if (changes.cleared)}
 * branch. {@code engine.clear()} never runs, no delta is accumulated on the
 * atomic op, and the consolidated-lifecycle hooks (apply and persist) have
 * nothing to skip. The persisted side is read by closing and reopening the
 * database; the reopen's {@code load()} recalibrates the in-memory counters
 * from the persisted entry-point page (including the null-key direct-lookup
 * at single-value load time landed for YTDB-953), so post-restart equality
 * with the pre-failure reads proves the persisted side matches the
 * preparatory-commit values.
 *
 * <p>The complementary coverage where {@code engine.clear()} actually runs
 * and is rolled back via the consolidated lifecycle apply gate
 * ({@code currentError == null}) lives in {@code ClearIndexApiRollbackTest},
 * which injects a throwing {@code IndexHistogramManager.resetOnClear} stub
 * to trigger rollback from inside {@code clear()}.
 *
 * <p>Note on the failure-injection seam. Pushing a
 * {@link RecordSerializationOperation} throws before {@code commitIndexes}
 * runs, so the engine's {@code clear} method never executes inside the
 * failed transaction.
 */
public class BTreeSingleValueIndexEngineClearRollbackTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    // DISK type so the persisted-side assertion can read the entry-point
    // page via a close-and-reopen cycle.
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.DISK, getClass());
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    if (db != null && !db.isClosed()) {
      db.close();
    }
    if (youTrackDB != null) {
      youTrackDB.close();
    }
  }

  /**
   * Commit-path rollback of a transaction tagged with
   * {@code cleared = true} must leave the single-value engine's counter pair
   * at the preparatory-commit values on both sides: in-memory
   * ({@code totalCount}, {@code nullCount}) and persisted ({@code sbTree}
   * entry-point page). The preparatory transaction commits 3 non-null plus
   * 1 null entry (UNIQUE caps null at one row). The failing transaction
   * (a) marks the index for clear via {@code OPERATION.CLEAR}, (b) queues
   * an additional put, and (c) pushes a
   * {@code RecordSerializationOperation} that throws a wrapped IOException
   * to drive rollback.
   *
   * <p>The failure fires inside {@code executeOperations} before
   * {@code commitIndexes} reaches the cleared-flag branch, so
   * {@code engine.clear()} never runs. The test pins the survival contract:
   * a rolled-back cleared-flag transaction must not corrupt the engine
   * counters. Post-rollback in-memory reads must report (4, 1); the
   * close-and-reopen reads (which recalibrate from the persisted
   * entry-point page via {@code load()}) must also report (4, 1).
   *
   * <p>The single-value engine's {@code persistCountDelta} ignores
   * {@code nullDelta} because the single {@code sbTree} stores both null
   * and non-null entries (the persisted side moves by {@code totalDelta}
   * alone). The in-memory {@code approximateNullCount} is still advanced by
   * the apply hook on commit success and (correspondingly) NOT advanced on
   * rollback. The post-rollback null-count assertion below catches a
   * regression where the engine state itself was corrupted during
   * {@code executeOperations} on the cleared-flag path.
   */
  @Test
  public void singleValueCommitPathClearRollback_countersStayAtPreClearValues()
      throws Exception {
    db.createClassIfNotExist("SvClearRb");
    var cls = db.getClass("SvClearRb");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("SvClearRb.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");
    var indexName = "SvClearRb.name";

    // Preparatory commit: 3 non-null + 1 null entry. UNIQUE caps null at
    // one row, so the null-key newEntity() (no "name" property) records
    // the single null in the same sbTree as the non-null entries.
    db.begin();
    for (int i = 0; i < 3; i++) {
      db.newEntity("SvClearRb").setProperty("name", "name_" + i);
    }
    db.newEntity("SvClearRb");
    db.commit();

    var engine = getBTreeIndexEngine(db, indexName);
    long[] preClear = readCountsViaEngine(engine);
    assertEquals("Preparatory total count", 4L, preClear[0]);
    assertEquals("Preparatory null count", 1L, preClear[1]);

    // Failing transaction: mark the index for clear plus a fresh put, then
    // queue a record-serialization operation that throws a wrapped
    // IOException from inside the inner try of the commit phase. The
    // pre-endTxCommit catch routes the failure through rollback.
    // commitIndexes (where the cleared flag would trigger doClearIndex)
    // never runs because the push throws first. The contract under test is
    // that the cleared-flag transaction's rollback leaves both counter
    // sides at the preparatory values.
    db.begin();
    var index = db.getSharedContext().getIndexManager().getIndex(indexName);
    var tx = db.getTransactionInternal();
    tx.addIndexEntry(index, indexName, OPERATION.CLEAR, null, null);
    db.newEntity("SvClearRb").setProperty("name", "doomed");

    var thrown =
        new java.io.IOException("simulated commit-phase failure for clear-rollback test");
    tx.getRecordSerializationContext().push(new RecordSerializationOperation() {
      @Override
      public void execute(AtomicOperation atomicOperation, AbstractStorage paginatedStorage) {
        // The interface does not declare throws IOException, so wrap as
        // RuntimeException with the original IOException as its cause. The
        // pre-endTxCommit catch sees a RuntimeException and short-circuits
        // without wrapping; rollback runs through the finally branch.
        throw new RuntimeException(thrown);
      }
    });

    Throwable caught = null;
    try {
      db.commit();
    } catch (Throwable t) {
      caught = t;
    }
    assertThat(caught)
        .as("Commit must surface the simulated failure")
        .isNotNull();
    assertThat(caught)
        .as("Caught throwable must not be an AssertionError. An escaping"
            + " AssertionError would signal the legacy underflow cascade"
            + " re-emerged on the single-value path.")
        .isNotInstanceOf(AssertionError.class);

    // In-memory check via the engine accessors. The apply hook is the sole
    // writer to the in-memory AtomicLongs under the consolidated lifecycle,
    // so the rollback path bypassing the apply gate leaves these untouched.
    long[] postRollbackInMem = readCountsViaEngine(engine);
    assertEquals(
        "In-memory total count must stay at the preparatory value after rollback",
        preClear[0], postRollbackInMem[0]);
    assertEquals(
        "In-memory null count must stay at the preparatory value after rollback",
        preClear[1], postRollbackInMem[1]);

    // Persisted check via close-and-reopen. The reopen's load() recalibrates
    // the in-memory counters from the persisted entry-point page (and for
    // the null half via the direct null-key lookup at single-value load
    // time landed for YTDB-953). Equality with the pre-rollback reads
    // proves the persist hook skipped its writes on the rollback path.
    db.close();
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    var enginePostRestart = getBTreeIndexEngine(db, indexName);
    long[] postRestart = readCountsViaEngine(enginePostRestart);
    assertEquals(
        "Persisted total count (sbTree EP) must equal the preparatory value"
            + " after the rolled-back commit",
        preClear[0], postRestart[0]);
    assertEquals(
        "Recalibrated null count after restart must equal the preparatory value"
            + " (single-tree direct null-key lookup confirms the null entry"
            + " is still on disk, so the persist hook did not run during"
            + " rollback)",
        preClear[1], postRestart[1]);
  }

  /**
   * Reads {@code getTotalCount} and {@code getNullCount} from the engine via
   * an atomic-operation envelope. The accessors are O(1) reads of the
   * in-memory AtomicLongs.
   */
  private long[] readCountsViaEngine(BTreeIndexEngine engine) throws Exception {
    long[] out = new long[2];
    db.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          out[0] = engine.getTotalCount(atomicOp);
          out[1] = engine.getNullCount(atomicOp);
        });
    return out;
  }

  /**
   * Resolves the named index to its {@link BTreeIndexEngine} via reflection
   * on the {@code indexId} field. Same shape as the helper in
   * {@code MainCommitCounterSyncTest} and {@code ClearIndexApiRollbackTest}.
   * The engine is not exposed through the public API.
   */
  private static BTreeIndexEngine getBTreeIndexEngine(
      DatabaseSessionEmbedded session, String indexName) {
    try {
      var idx = session.getSharedContext().getIndexManager().getIndex(indexName);
      var indexIdField = IndexAbstract.class.getDeclaredField("indexId");
      indexIdField.setAccessible(true);
      int indexId = indexIdField.getInt(idx);
      var storage = (AbstractStorage) session.getStorage();
      var getEngineMethod = AbstractStorage.class.getMethod("getIndexEngine", int.class);
      return (BTreeIndexEngine) getEngineMethod.invoke(storage, indexId);
    } catch (Exception e) {
      throw new RuntimeException(
          "Failed to resolve BTreeIndexEngine for " + indexName, e);
    }
  }
}
