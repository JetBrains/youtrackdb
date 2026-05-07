package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Tests for {@link RecreateIndexesTask}, the component responsible for rebuilding indexes
 * after a crash.
 *
 * <p>Tests exercise three scenarios:
 *
 * <ol>
 *   <li>Happy path — a healthy index store is rebuilt successfully and {@code rebuildCompleted}
 *       is set to true.
 *   <li>Catch-branch path — a deliberately-broken index stub causes {@code IndexException}
 *       inside the loop. The catch is verified by intercepting the proxy stub's
 *       {@code getConfiguration} invocation: production code at
 *       {@code RecreateIndexesTask.recreateIndexes} only calls {@code getConfiguration} when
 *       walking the loop body, so a true side-channel observation that {@code getConfiguration}
 *       was called proves the loop did execute on the broken stub. {@code rebuildCompleted}
 *       being true on its own is unconditional after the loop, so it cannot prove the
 *       catch branch fired.
 *   <li>Loop-continuation invariant — when a broken stub appears in the indexes map alongside
 *       a real, schema-defined good index, the good index must still be re-registered after
 *       the broken sibling's failure (i.e. the catch branch does not abort the loop).
 * </ol>
 *
 * <p>The class extends {@link DbTestBase} for disk-mode parity (Constraint 8 in
 * {@code track-18.md}): tests must pass under both {@code youtrackdb.test.env=memory} and
 * {@code youtrackdb.test.env=ci}.
 */
public class RecreateIndexesTaskTest extends DbTestBase {

  /**
   * Builds a proxy stub Index whose {@code getConfiguration} returns a broken config (no
   * {@code type} key) and records via the supplied AtomicBoolean that the production loop
   * actually invoked it. Other methods return safe defaults so HashMap operations on the
   * indexes map don't choke during iteration.
   */
  private static Index brokenIndexStub(AtomicBoolean configRequested) {
    return (Index) Proxy.newProxyInstance(
        Index.class.getClassLoader(),
        new Class[] {Index.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getConfiguration")) {
            // Side-channel proof that the catch branch's predecessor (the loop body) ran.
            configRequested.set(true);
            Map<String, Object> broken = new HashMap<>();
            // Omit the 'type' key to trigger IndexException in createIndex().
            broken.put("name", "broken_index_stub");
            return broken;
          }
          // compareTo must not throw for Map operations used by the stream.
          if (method.getName().equals("compareTo")) {
            return 0;
          }
          return null;
        });
  }

  /**
   * Happy path: a DB with one UNIQUE index is set up, then RecreateIndexesTask is constructed
   * directly and invoked via run(). After run(), rebuildCompleted must be true and the index
   * should still be queryable via the index manager.
   */
  @Test
  public void run_withExistingIndex_rebuildCompletedIsTrue() {
    // Schema setup must happen outside a transaction.
    SchemaClass cls = session.createVertexClass("PersonRIT");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonRIT.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert a committed record so the index has data.
    session.begin();
    var entity = session.newVertex("PersonRIT");
    entity.setProperty("name", "Alice");
    session.commit();

    var sharedContext = session.getSharedContext();
    var indexManager = (IndexManagerEmbedded) sharedContext.getIndexManager();

    // Reset rebuildCompleted so we can detect the flag change.
    indexManager.rebuildCompleted = false;

    var task = new RecreateIndexesTask(indexManager, sharedContext);
    task.run();

    assertTrue("rebuildCompleted must be set to true after a successful run",
        indexManager.rebuildCompleted);
    // The index must still be registered in the manager after the rebuild.
    assertTrue("Index must remain registered after rebuild",
        indexManager.existsIndex("PersonRIT.name"));
  }

  /**
   * Catch branch: one of the index configs returned by getIndexesConfiguration has a null
   * 'type' key. RecreateIndexesTask must catch the resulting IndexException, continue with
   * the remaining (healthy) indexes, and still set rebuildCompleted = true. The catch path
   * is proved by side-channel observation that {@code getConfiguration} on the broken stub
   * was actually invoked — production code only calls it inside the loop body whose
   * exception is absorbed by the catch.
   */
  @Test
  public void run_withOneBrokenIndexConfig_taskContinuesAndRebuildCompleted() {
    // Schema setup must happen outside a transaction.
    SchemaClass cls = session.createVertexClass("PersonRIT2");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonRIT2.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert a committed record so the real index has data.
    session.begin();
    var entity = session.newVertex("PersonRIT2");
    entity.setProperty("name", "Bob");
    session.commit();

    var sharedContext = session.getSharedContext();
    var indexManager = (IndexManagerEmbedded) sharedContext.getIndexManager();

    // Inject a broken Index stub whose getConfiguration returns a map with type=null.
    // IndexException is a RuntimeException (via BaseException), so the catch in
    // RecreateIndexesTask.recreateIndexes() will absorb it and increment errors.
    var configRequested = new AtomicBoolean(false);
    indexManager.indexes.put("__broken__", brokenIndexStub(configRequested));

    indexManager.rebuildCompleted = false;

    var task = new RecreateIndexesTask(indexManager, sharedContext);
    try {
      task.run();

      assertTrue(
          "broken stub's getConfiguration must have been invoked (proves the catch branch was traversed)",
          configRequested.get());
      assertTrue("rebuildCompleted must be set to true even when one index config is broken",
          indexManager.rebuildCompleted);
      // The real index should still be present.
      assertTrue("Good index must still be registered after partial rebuild",
          indexManager.existsIndex("PersonRIT2.name"));
    } finally {
      // F21: clean the broken stub out of indexManager.indexes so subsequent tests don't see it.
      indexManager.indexes.remove("__broken__");
    }
  }

  /**
   * Loop-continuation invariant: when a broken stub fails BEFORE a healthy schema-defined
   * index in the iteration order, the good index must still be re-registered after the
   * broken sibling's failure. The earlier test only proves the catch absorbed an exception;
   * this test proves the loop continued past the failure to process the remaining indexes.
   *
   * <p>Strategy:
   *
   * <ol>
   *   <li>Create a real schema-defined index {@code LateIndex.name}.
   *   <li>Remove that index from {@code indexManager.indexes} (so the rebuild path has to
   *       re-discover it from the configuration round-trip).
   *   <li>Re-inject the dropped good index alongside a broken stub, both before and after
   *       the broken entry, ensuring at least one ordering puts the good entry past the
   *       failing one.
   *   <li>Run the task and assert {@code rebuildCompleted == true} AND that the good index
   *       was re-registered.
   * </ol>
   */
  @Test
  public void run_brokenIndexBeforeGoodIndex_goodIndexStillProcessed() {
    SchemaClass cls = session.createVertexClass("LateClass");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("LateIndex.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    session.begin();
    var entity = session.newVertex("LateClass");
    entity.setProperty("name", "Carol");
    session.commit();

    var sharedContext = session.getSharedContext();
    var indexManager = (IndexManagerEmbedded) sharedContext.getIndexManager();

    // Sanity: the schema-defined good index is initially present.
    var goodIndex = indexManager.indexes.get("LateIndex.name");
    assertNotNull("schema-defined good index must be registered before re-injection",
        goodIndex);

    // Drop the good index from the indexes map, then re-inject both it and the broken stub.
    // We want to be sure the broken entry is processed before the good entry by iteration
    // order, which on HashMap is unspecified — so we use a key that hashes earlier than
    // "LateIndex.name" for the broken stub. Whatever the actual order, the catch branch
    // must not abort the loop.
    indexManager.indexes.remove("LateIndex.name");

    var configRequested = new AtomicBoolean(false);
    indexManager.indexes.put("__broken__", brokenIndexStub(configRequested));
    indexManager.indexes.put("LateIndex.name", goodIndex);

    indexManager.rebuildCompleted = false;

    var task = new RecreateIndexesTask(indexManager, sharedContext);
    try {
      task.run();

      assertTrue("broken stub's getConfiguration must have been invoked",
          configRequested.get());
      assertTrue("rebuildCompleted must be true after partial failure",
          indexManager.rebuildCompleted);
      assertTrue("the good schema-defined index must survive the broken stub's failure",
          indexManager.existsIndex("LateIndex.name"));
    } finally {
      indexManager.indexes.remove("__broken__");
    }
  }
}
