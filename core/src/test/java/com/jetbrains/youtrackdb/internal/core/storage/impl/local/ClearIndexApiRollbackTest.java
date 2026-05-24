package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Regression scaffold for the {@code clearIndex} API path under rollback.
 * The {@code clearIndex} entry point bypasses the main commit path and runs
 * inside its own standalone atomic operation via {@code
 * AtomicOperationsManager.executeInsideAtomicOperation}. Today's {@code
 * clear()} implementation on both {@link
 * com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine}
 * and {@link
 * com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine}
 * writes the in-memory {@code AtomicLong} counters directly inside the
 * atomic op, so a rollback after a successful clear (forced at WAL flush)
 * leaves the in-memory side at zero while the persisted side reverts. The
 * next decrement underflows the engine-mutator clamp-and-error path.
 *
 * <p>The fix lives in the pure-delta-encoding work that converts {@code
 * clear()} on both engines to record a delta on the atomic op rather than
 * writing to {@code AtomicLong}s in place. After that conversion lands, the
 * consolidated lifecycle gate in {@link
 * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager#endAtomicOperation}
 * skips the apply hook on rollback (the {@code currentError == null}
 * gate fails), so the in-memory side stays at its pre-clear values and the
 * divergence is structurally impossible.
 *
 * <p>This test is annotated {@link Ignore} because the {@code clear()}
 * pure-delta encoding is a follow-up change. When that change lands its
 * implementer flips the {@link Ignore} annotation off and the body
 * exercises the consolidated lifecycle gate's rollback gate on the
 * standalone atomic op. The test must compile against the current API
 * surface so the follow-up only has to remove the annotation and verify.
 */
@Ignore("Standalone-atomic-op clearIndex rollback regression — requires the"
    + " clear() pure-delta encoding follow-up to be wired before this can"
    + " pass; the follow-up's implementer flips this annotation off as"
    + " part of its regression-test step.")
public class ClearIndexApiRollbackTest {

  // Per-test database name with a UUID suffix avoids OEngine.getStorage(name)
  // collisions when this test runs in parallel under surefire fork-per-class.
  private final String dbName = "test-" + UUID.randomUUID();
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
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
   * Standalone-atomic-op {@code clearIndex} rollback: populate the index,
   * invoke the {@code clearIndex} API, force a rollback at WAL flush, and
   * assert that both the in-memory and persisted counter sides stay at
   * their pre-clear values. Without the pure-delta encoding fix the
   * in-memory side reads zero (set inside the rolled-back atomic op) while
   * the persisted side reverts via WAL — the divergence the follow-up
   * track closes.
   *
   * <p>The body is a minimal scaffold: it sets up the index and reads
   * counters via the public engine accessors. The follow-up implementer
   * adds the {@code clearIndex} invocation and the WAL-flush-rollback
   * injection (today the {@code executeInsideAtomicOperation} wrapper has
   * no public hook for that, so the injection is part of the same
   * follow-up).
   */
  @Test
  public void clearIndexApiRollback_countersStayAtPreClearValues() throws Exception {
    db.createClassIfNotExist("ClearRb");
    var cls = db.getClass("ClearRb");
    cls.createProperty("tag", PropertyType.STRING);
    cls.createIndex("ClearRb.tag", SchemaClass.INDEX_TYPE.NOTUNIQUE, "tag");

    // Preparatory commit: 3 non-null + 1 null entry.
    db.begin();
    for (int i = 0; i < 3; i++) {
      db.newEntity("ClearRb").setProperty("tag", "tag_" + i);
    }
    db.newEntity("ClearRb");
    db.commit();

    var engine = getBTreeIndexEngine(db, "ClearRb.tag");
    long[] preClearCounts = new long[2];
    db.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          preClearCounts[0] = engine.getTotalCount(atomicOp);
          preClearCounts[1] = engine.getNullCount(atomicOp);
        });
    assertEquals("Preparatory total count", 4L, preClearCounts[0]);
    assertEquals("Preparatory null count", 1L, preClearCounts[1]);

    // Follow-up implementer wires: clearIndex invocation + forced WAL-flush
    // rollback. After that, the assertions below pin the contract.
    //
    // The fail() below is a scaffolding tripwire. Without the clearIndex
    // call and the forced rollback, the post-clear read below trivially
    // matches the pre-clear read (two reads of an untouched AtomicLong),
    // so the assertions would pass on a body that does not exercise the
    // contract under test. Removing the @Ignore annotation without first
    // wiring the clearIndex invocation and the rollback injection would
    // land a passing-but-meaningless test that names a contract it does
    // not verify. The follow-up implementer that fills in the body must
    // also remove this fail() in the same edit.
    fail("Scaffold body incomplete: the clearIndex invocation and forced"
        + " rollback injection are still missing. The follow-up that"
        + " un-@Ignores this test must wire both before removing the"
        + " annotation, otherwise the post-clear read trivially matches"
        + " the pre-clear read and the assertions verify nothing.");

    long[] postClearCounts = new long[2];
    db.getStorage().getAtomicOperationsManager()
        .executeInsideAtomicOperation(atomicOp -> {
          postClearCounts[0] = engine.getTotalCount(atomicOp);
          postClearCounts[1] = engine.getNullCount(atomicOp);
        });
    assertEquals(
        "Total count must stay at pre-clear value after rollback",
        preClearCounts[0], postClearCounts[0]);
    assertEquals(
        "Null count must stay at pre-clear value after rollback",
        preClearCounts[1], postClearCounts[1]);
  }

  /**
   * Resolves the named index to its {@link BTreeIndexEngine}. Mirrors the
   * helper in {@link MainCommitCounterSyncTest} — the engine is not exposed
   * through the public API.
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
