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
 * Regression for the pure-delta-encoded {@code clear()} on the multi-value
 * (NOTUNIQUE) index engine, exercised through the main commit path. The
 * transaction is marked {@code cleared = true} via the public
 * {@code FrontendTransaction.addIndexEntry(... OPERATION.CLEAR ...)} API.
 * {@link com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage}'s
 * {@code commitIndexes} method dispatches to {@code doClearIndex} when that
 * flag is set, under the per-engine exclusive lock acquired by
 * {@code lockIndexes}. A pushed {@link RecordSerializationOperation} throws a
 * wrapped {@link java.io.IOException} from inside the inner try of
 * {@code AbstractStorage.commit}'s commit phase, routing the failure through
 * the pre-{@code endTxCommit} catch at
 * {@code AbstractStorage.java:2335} (whose clause catches
 * {@code IOException | RuntimeException | AssertionError}) and into rollback.
 *
 * <p>The contract pinned here: after the rolled-back commit, both the
 * in-memory ({@code approximateIndexEntriesCount}, {@code approximateNullCount})
 * and persisted ({@code svTree} entry-point page, {@code nullTree}
 * entry-point page) counter sides stay at the preparatory-commit values. The
 * persisted side is read by closing and reopening the database. The reopen's
 * {@code load()} recalibrates the in-memory counters from the persisted
 * entry-point pages, so post-restart equality with the pre-rollback reads
 * proves the persisted side matches the preparatory-commit values bit for
 * bit. Under today's pure-delta encoding the apply hook in
 * {@code endAtomicOperation} skips its apply step when the lifecycle gate
 * observes a non-null {@code error}, so the in-memory side never advances
 * past pre-clear; the persist hook similarly skips on rollback so the
 * persisted entry-point pages never receive the {@code -currentTotal} /
 * {@code -currentNull} delta.
 *
 * <p>Note on the failure-injection seam. Pushing a
 * {@link RecordSerializationOperation} throws before {@code commitIndexes}
 * runs. The push fires at {@code executeOperations} on the line that
 * precedes the {@code commitIndexes} call inside the same inner try, so the
 * engine's {@code clear} method never executes inside the failed
 * transaction. The contract under test is the cleared-flag-plus-rollback
 * survival path: even when the transaction is tagged for a clear, a
 * pre-{@code commitIndexes} failure must not corrupt the engine counters.
 * The API-path counterpart that exercises the apply hook gate after a
 * successful {@code clear} call lives in {@code ClearIndexApiRollbackTest}.
 */
public class BTreeMultiValueIndexEngineClearRollbackTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    // DISK type so the persisted-side assertion can read the entry-point
    // pages via a close-and-reopen cycle. MEMORY would zero the BTree on
    // restart and the post-rollback persisted check would degenerate.
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
   * {@code cleared = true} must leave the multi-value engine's counter quad
   * at the preparatory-commit values on all four sides: in-memory
   * {@code totalCount}, in-memory {@code nullCount}, persisted {@code svTree}
   * entry-point page, persisted {@code nullTree} entry-point page. The
   * preparatory transaction commits 3 non-null plus 1 null entry (total 4,
   * null 1). The failing transaction (a) marks the index for clear via
   * {@code OPERATION.CLEAR}, (b) queues additional puts, and (c) pushes a
   * {@code RecordSerializationOperation} that throws a wrapped IOException
   * to drive rollback. Post-rollback in-memory reads must report (4, 1); the
   * close-and-reopen reads (which recalibrate from the persisted entry-point
   * pages via {@code load()}) must also report (4, 1). A regression where
   * the rollback path failed to skip the apply hook would advance the
   * in-memory side past pre-clear; a regression where the persist hook ran
   * on the rollback path would move the persisted side away from the
   * preparatory-commit values, and the close-and-reopen assertion would
   * catch that.
   */
  @Test
  public void multiValueCommitPathClearRollback_countersStayAtPreClearValues()
      throws Exception {
    db.createClassIfNotExist("MvClearRb");
    var cls = db.getClass("MvClearRb");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("MvClearRb.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");
    var indexName = "MvClearRb.tag";

    // Preparatory commit: 3 non-null + 1 null entry. Counters land at
    // (total = 4, null = 1) on both sides. The null-key entity is the
    // newEntity() with no "tag" property; the index records it under the
    // null key in the nullTree.
    db.begin();
    for (int i = 0; i < 3; i++) {
      db.newEntity("MvClearRb").setProperty("tag", "tag_" + i);
    }
    db.newEntity("MvClearRb");
    db.commit();

    var engine = getBTreeIndexEngine(db, indexName);
    long[] preClear = readCountsViaEngine(engine);
    assertEquals("Preparatory total count", 4L, preClear[0]);
    assertEquals("Preparatory null count", 1L, preClear[1]);

    // Failing transaction: mark the index for clear plus a fresh put, then
    // queue a record-serialization operation that throws a wrapped
    // IOException from inside the inner try of the commit phase. The
    // pre-endTxCommit catch at AbstractStorage.java:2335 catches the
    // RuntimeException-wrapped IOException (its clause already covers
    // IOException | RuntimeException | AssertionError) and routes the
    // failure through rollback. commitIndexes (where the cleared flag
    // would trigger doClearIndex) never runs because the push throws
    // first. The contract under test is that the cleared-flag
    // transaction's rollback leaves both counter sides at the
    // preparatory values.
    db.begin();
    var index = db.getSharedContext().getIndexManager().getIndex(indexName);
    var tx = db.getTransactionInternal();
    tx.addIndexEntry(index, indexName, OPERATION.CLEAR, null, null);
    db.newEntity("MvClearRb").setProperty("tag", "doomed");

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
        .as("Caught throwable must not be an AssertionError. The pre-endTxCommit"
            + " catch wraps IOException as StorageException and lets RuntimeException"
            + " through; an escaping AssertionError would signal the legacy"
            + " underflow cascade re-emerged.")
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
    // the in-memory counters from the persisted entry-point pages, so
    // equality with the pre-rollback reads proves the persist hook also
    // skipped its writes on the rollback path. A regression where the
    // persist hook landed the -currentTotal / -currentNull delta on the
    // rollback path would show up here as 0/0 or some other drifted quad.
    db.close();
    db = youTrackDB.open(dbName, "admin", DbTestBase.ADMIN_PASSWORD);
    var enginePostRestart = getBTreeIndexEngine(db, indexName);
    long[] postRestart = readCountsViaEngine(enginePostRestart);
    assertEquals(
        "Persisted total count (svTree EP) must equal the preparatory value"
            + " after the rolled-back commit",
        preClear[0], postRestart[0]);
    assertEquals(
        "Persisted null count (nullTree EP) must equal the preparatory value"
            + " after the rolled-back commit",
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
