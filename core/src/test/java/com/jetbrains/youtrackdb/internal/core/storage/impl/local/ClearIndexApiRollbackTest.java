package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.IndexAbstract;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeIndexEngine;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression for the {@code clearIndex} API path under rollback. The
 * {@code clearIndex} entry point bypasses the main commit path and runs
 * inside its own standalone atomic operation via {@code
 * AtomicOperationsManager.executeInsideAtomicOperation}. Under the
 * pure-delta encoding on both {@link
 * com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeMultiValueIndexEngine}
 * and {@link
 * com.jetbrains.youtrackdb.internal.core.index.engine.v1.BTreeSingleValueIndexEngine},
 * the {@code clear()} body records a negative delta on the atomic op rather
 * than writing the in-memory {@code AtomicLong} counters directly. The
 * consolidated lifecycle gate in {@link
 * com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationsManager#endAtomicOperation}
 * skips the apply hook on rollback (the {@code currentError == null} gate
 * fails), so the in-memory side stays at its pre-clear values and the
 * persist hook skips its writes on rollback as well.
 *
 * <p>Failure-injection seam. The test reflectively replaces the target
 * engine's {@code histogramManager} field with a stub whose
 * {@code resetOnClear(AtomicOperation)} throws {@link IOException}. The
 * throw routes through {@code engine.clear()}'s inner
 * {@code try/catch (IOException)} wrap (SV at lines 277-280) or the MV
 * histogram block's inline {@code try/catch (IOException)} (MV at lines
 * 317-324), which wraps as {@code IndexException} and escapes the
 * {@code executeInsideAtomicOperation} lambda, triggering rollback. The
 * apply hook's {@code currentError == null} gate then skips apply, so both
 * counter sides retain pre-clear values.
 */
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
   * Standalone-atomic-op {@code clearIndex} rollback on the multi-value
   * engine. The setup commits 3 non-null plus 1 null entry, then invokes
   * the {@code clearIndex} API with a stub {@link IndexHistogramManager}
   * whose {@code resetOnClear} throws {@link IOException}. The throw
   * propagates through the MV engine's inline IOException wrap as an
   * {@code IndexException} and escapes the
   * {@code executeInsideAtomicOperation} lambda, triggering rollback. The
   * apply hook's {@code currentError == null} gate then skips, so the
   * in-memory {@code AtomicLong} counters remain at their pre-clear
   * values. The test asserts both that the simulated failure surfaces and
   * that the in-memory counters report (4, 1) after rollback.
   *
   * <p>Persisted-side coverage. The database is opened in MEMORY mode, so
   * a close-and-reopen cycle would zero the entry-point page on restart
   * and the persisted check would degenerate. The corresponding persisted
   * assertion lives in {@code BTreeMultiValueIndexEngineClearRollbackTest}
   * (DISK mode), where the close-and-reopen cycle reads the persisted
   * entry-point page through {@code load()}.
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

    // Inject the throwing stub by reflectively replacing the engine's
    // private volatile histogramManager field. The stub's resetOnClear
    // throws IOException; MV's engine.clear() wraps it as IndexException
    // and propagates out of the executeInsideAtomicOperation lambda, which
    // routes the failure through the rollback path.
    var originalManager = swapHistogramManager(engine, makeThrowingHistogramStub());

    int indexId = getExternalIndexId(db, "ClearRb.tag");
    Throwable caught = null;
    try {
      db.getStorage().clearIndex(indexId);
    } catch (Throwable t) {
      caught = t;
    } finally {
      // Restore the real histogram manager so the @After teardown can
      // close the database without firing the throwing stub on close.
      swapHistogramManager(engine, originalManager);
    }
    assertThat(caught)
        .as("clearIndex must surface the simulated histogram-reset failure")
        .isNotNull();

    // Post-rollback in-memory check. The engine's getTotalCount and
    // getNullCount read AtomicLongs that the apply hook is the sole writer
    // for; on rollback the apply gate skips, so the values match the
    // preparatory commit.
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
   * Builds a Mockito stub of {@link IndexHistogramManager} whose
   * {@code resetOnClear} throws {@link IOException}. The
   * {@link IndexHistogramManager} class is concrete and its constructor
   * requires a fully-wired storage; Mockito bypasses the constructor by
   * default, so the stub only needs to define the single method the test
   * exercises. Every other method returns Mockito's primitive default
   * (no-op for {@code void}; zero / null / empty for typed returns),
   * which is sufficient because the engine's {@code clear()} body throws
   * before any other histogram-manager method runs.
   */
  private static IndexHistogramManager makeThrowingHistogramStub() throws IOException {
    var stub = mock(IndexHistogramManager.class);
    doThrow(new IOException(
        "simulated histogram resetOnClear failure for clearIndex rollback test"))
        .when(stub).resetOnClear(any(AtomicOperation.class));
    return stub;
  }

  /**
   * Reflectively swaps the engine's {@code histogramManager} field. The
   * field is {@code private volatile} on the engine implementation; the
   * test exercises a failure path that does not exist in the public API
   * surface, so reflection is the only available seam.
   *
   * @return the prior {@code histogramManager} so the caller can restore it
   *     in a finally block.
   */
  private static IndexHistogramManager swapHistogramManager(
      BTreeIndexEngine engine, IndexHistogramManager replacement) throws Exception {
    Field field = engine.getClass().getDeclaredField("histogramManager");
    field.setAccessible(true);
    var prior = (IndexHistogramManager) field.get(engine);
    field.set(engine, replacement);
    return prior;
  }

  /**
   * Returns the external (API-version-prefixed) index id used by
   * {@code AbstractStorage.clearIndex(int)}. The
   * {@code AbstractStorage.indexId} field on {@code IndexAbstract} already
   * holds the external value.
   */
  private static int getExternalIndexId(
      DatabaseSessionEmbedded session, String indexName) throws Exception {
    var idx = session.getSharedContext().getIndexManager().getIndex(indexName);
    var indexIdField = IndexAbstract.class.getDeclaredField("indexId");
    indexIdField.setAccessible(true);
    return indexIdField.getInt(idx);
  }

  /**
   * Resolves the named index to its {@link BTreeIndexEngine}. Mirrors the
   * helper in {@link MainCommitCounterSyncTest}; the engine is not exposed
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
