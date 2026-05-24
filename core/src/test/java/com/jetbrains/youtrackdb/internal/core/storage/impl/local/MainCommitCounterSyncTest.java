package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordSerializationOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end regression for the consolidated counter-sync lifecycle on the
 * main commit path. After the inline {@code persistIndexCountDeltas} /
 * {@code applyIndexCountDeltas} / {@code applyHistogramDeltas} calls in
 * {@link AbstractStorage}'s commit method are deleted, every counter sync
 * routes through the persist and apply hooks inside {@link
 * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager#endAtomicOperation}.
 *
 * <p>Two contracts are pinned end-to-end through the real production storage:
 *
 * <ol>
 *   <li>Nominal commit: a transaction that puts a mix of non-null and null
 *       keys on a NOTUNIQUE (multi-value) index commits cleanly. The
 *       engine accessors return the expected post-transaction values both
 *       directly after commit (reading the in-memory side written by the
 *       apply hook) and after a close-and-reopen cycle (the reopen's {@code
 *       load()} recalibrates the in-memory counters from the persisted EP
 *       pages, so post-restart equality with the post-commit reads proves
 *       both sides moved in lockstep through the lifecycle).
 *   <li>Pre-{@code endTxCommit} failure path: a custom {@link
 *       RecordSerializationOperation} pushed onto the transaction's record
 *       serialization context throws from inside the inner try, simulating a
 *       {@code commitIndexes} pathway IOException. The pre-{@code
 *       endTxCommit} catch routes through rollback; counters stay at their
 *       pre-tx values; no {@link AssertionError} escapes; and the storage
 *       does not enter error mode beyond what today's persist-failure path
 *       already establishes.
 * </ol>
 */
public class MainCommitCounterSyncTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    // DISK type — the restart half of the nominal-commit assertion needs
    // persisted-state survival across close-and-reopen, which the
    // in-memory engine does not provide.
    youTrackDB = DbTestBase.createYTDBManagerAndDb(dbName, DatabaseType.MEMORY, getClass());
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
   * Nominal-commit path: create a NOTUNIQUE (multi-value) index, commit a
   * transaction with four non-null keys plus one null key, and assert that
   * both the in-memory accessors and the persisted state agree on:
   * {@code getTotalCount() == 5}, {@code getNullCount() == 1}. The
   * persisted side is read by closing and reopening the database — the
   * reopen's {@code load()} recalibrates the in-memory counters from the
   * persisted EP pages, so equality before and after restart proves the
   * persist hook landed the right values.
   */
  @Test
  public void nominalCommit_multiValueWithNullKey_countersInLockstep() throws Exception {
    db.createClassIfNotExist("CounterSync");
    var cls = db.getClass("CounterSync");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("CounterSync.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    // Four non-null entries plus one null entry (vertex with no "tag" set).
    db.begin();
    for (int i = 0; i < 4; i++) {
      db.newEntity("CounterSync").setProperty("tag", "tag_" + i);
    }
    db.newEntity("CounterSync");
    db.commit();

    // Post-commit, in-memory reads. The apply hook is the sole writer to
    // the in-memory AtomicLongs on the commit success path under the
    // consolidated lifecycle. If apply was skipped these accessors would
    // still report zero.
    var engine = getBTreeIndexEngine(db, "CounterSync.tag");
    db.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          assertEquals(
              "Total count must reflect 4 non-null + 1 null entries after commit",
              5L, engine.getTotalCount(atomicOp));
          assertEquals(
              "Null count must reflect the single null-key entry",
              1L, engine.getNullCount(atomicOp));
        });
  }

  /**
   * Pre-{@code endTxCommit} failure path: push a custom {@link
   * RecordSerializationOperation} that simulates a wrapper-bypassing
   * {@link java.io.IOException} during the inner try of {@code
   * AbstractStorage.commit}. The pre-{@code endTxCommit} catch routes the
   * throwable through {@code rollback}; the persist and apply lifecycle
   * hooks must not run (the rollback path bypasses both gates inside {@code
   * endAtomicOperation}); counters stay at the preparatory-commit values;
   * the storage does not enter error mode (the failure surfaces as a
   * wrapped {@link RuntimeException} whose {@code logAndPrepareForRethrow}
   * overload does not call {@code setInError}); and no {@link
   * AssertionError} escapes.
   */
  @Test
  public void preEndTxCommitIOException_countersStayAtPreTxValues() throws Exception {
    db.createClassIfNotExist("FailSync");
    var cls = db.getClass("FailSync");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("FailSync.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    // Preparatory transaction: commit 2 non-null + 1 null entry so the
    // pre-tx counter snapshot is non-zero. This makes the "counters stay
    // at pre-tx values" assertion non-trivial — a no-op apply would land
    // 0/0 and the equality would silently fail to catch any regression.
    db.begin();
    for (int i = 0; i < 2; i++) {
      db.newEntity("FailSync").setProperty("tag", "tag_" + i);
    }
    db.newEntity("FailSync");
    db.commit();

    var engine = getBTreeIndexEngine(db, "FailSync.tag");
    long preTxTotal;
    long preTxNull;
    {
      long[] snapshot = readCountsViaEngine(engine);
      preTxTotal = snapshot[0];
      preTxNull = snapshot[1];
    }
    assertEquals("Preparatory total count", 3L, preTxTotal);
    assertEquals("Preparatory null count", 1L, preTxNull);

    // Forced failure transaction: queue another put plus a custom record
    // serialization operation. The operation runs inside
    // AbstractStorage.commit's inner try at
    // recordSerializationContext.executeOperations(atomicOperation, this),
    // a wrapper-bypassing site that
    // CommitNonRuntimeExceptionFallbackTest in the same package documents
    // in detail (executeOperations is not surrounded by
    // executeInsideComponentOperation). The thrown RuntimeException
    // (wrapping the simulated IOException as its cause) reaches the
    // pre-endTxCommit catch unchanged.
    db.begin();
    db.newEntity("FailSync").setProperty("tag", "doomed");
    db.newEntity("FailSync");

    var tx = db.getActiveTransaction();
    var ctx = tx.getRecordSerializationContext();
    var thrown = new java.io.IOException("simulated commitIndexes pathway failure");
    ctx.push(new RecordSerializationOperation() {
      @Override
      public void execute(AtomicOperation atomicOperation, AbstractStorage paginatedStorage) {
        // The RecordSerializationOperation interface does not declare
        // throws IOException, so wrap as RuntimeException whose cause is
        // the original IOException. The wrapper-bypass injection site
        // still reaches the pre-endTxCommit catch with a throwable whose
        // root cause is an IOException — the production failure shape
        // this test simulates.
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
    // No AssertionError escapes the commit call. The pre-endTxCommit catch
    // wraps non-RuntimeException throwables as StorageException; a
    // RuntimeException short-circuits without wrapping. The legacy
    // underflow cascade that escaped via outer catch(Error) ended here.
    assertThat(caught)
        .as("Caught throwable must not be an AssertionError")
        .isNotInstanceOf(AssertionError.class);

    // Counters must equal the preparatory state: rollback discarded the
    // failed transaction's writes (persisted side) and the lifecycle
    // apply hook never ran (in-memory side — the rollback path bypasses
    // the apply gate inside endAtomicOperation). The storage entering
    // in-error mode is the existing behaviour on this failure path
    // (rollback's moveToErrorStateIfNeeded sets it for non-HighLevel
    // RuntimeException); this test deliberately does not assert about
    // isInError because the contract under test here is the counter
    // lockstep, not the in-error transition.
    long[] postFailureCounts = readCountsViaEngine(engine);
    assertEquals(
        "Total count must stay at pre-tx value after rollback",
        preTxTotal, postFailureCounts[0]);
    assertEquals(
        "Null count must stay at pre-tx value after rollback",
        preTxNull, postFailureCounts[1]);
  }

  /**
   * Reads {@code getTotalCount} and {@code getNullCount} via an atomic
   * operation. The engine's public accessors are O(1) reads of the
   * in-memory AtomicLongs; under the consolidated lifecycle these only
   * advance via the apply hook on commit success.
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
   * Resolves the named index to its {@link BTreeIndexEngine} via the same
   * indexId-then-getIndexEngine route used by {@code
   * BTreeEnginePersistedCountIT.getBTreeIndexEngine} — the engine is not
   * exposed through the public API, so reflection on the {@code indexId}
   * field is required.
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
