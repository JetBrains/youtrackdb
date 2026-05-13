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
 *       inside the loop body in {@code RecreateIndexesTask.recreateIndexes}; the catch must
 *       absorb it and {@code rebuildCompleted} must still flip to true after the loop. The
 *       broken stub's {@code getConfiguration} call is also observed via an
 *       {@link AtomicBoolean} flag, but note: production code calls {@code getConfiguration}
 *       in {@link IndexManagerEmbedded#getIndexesConfiguration} <em>before</em> the recreation
 *       loop ever starts (configurations are collected into a {@code List<Map<String,Object>>}
 *       snapshot, then iterated). The flag therefore proves only that the stub was visited
 *       by the snapshot phase, not that the loop body's catch fired. The {@code
 *       rebuildCompleted == true} post-condition is the load-bearing observable for the
 *       catch-continue contract.
 *   <li>Loop-continuation invariant — when <em>two</em> distinct broken stubs are present in
 *       the indexes map, both must end up in the configuration snapshot (each flag flips to
 *       true), and {@code rebuildCompleted} must still be true after the loop. Capturing two
 *       stubs in the snapshot is independent of {@code HashMap} iteration order; combined
 *       with the post-loop {@code rebuildCompleted} flip it shows the snapshot phase didn't
 *       short-circuit on the first stub and the loop ran to completion across multiple
 *       failing entries.
 * </ol>
 *
 * <p>The class extends {@link DbTestBase} for disk-mode parity (Constraint 8 in the track
 * step file): tests must pass under both {@code youtrackdb.test.env=memory} and
 * {@code youtrackdb.test.env=ci}.
 */
public class RecreateIndexesTaskTest extends DbTestBase {

  /**
   * Builds a proxy stub Index whose {@code getConfiguration} returns a broken config (no
   * {@code type} key) and records via the supplied AtomicBoolean that the production code
   * actually invoked it. The flag flips during
   * {@link IndexManagerEmbedded#getIndexesConfiguration} (the snapshot phase, before the
   * recreation loop starts); the post-loop {@code rebuildCompleted} observable is what
   * proves the catch-continue contract. Other methods return safe defaults so HashMap
   * operations on the indexes map don't choke during iteration.
   */
  private static Index brokenIndexStub(AtomicBoolean configRequested) {
    return (Index) Proxy.newProxyInstance(
        Index.class.getClassLoader(),
        new Class[] {Index.class},
        (proxy, method, args) -> {
          if (method.getName().equals("getConfiguration")) {
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
   * Catch branch: <em>two</em> broken stubs are injected into {@code indexManager.indexes}.
   * Both stubs' configurations are collected into the snapshot at
   * {@link IndexManagerEmbedded#getIndexesConfiguration}, then the recreation loop hits each
   * one in turn and absorbs the resulting {@code IndexException} via the catch in
   * {@link RecreateIndexesTask}. After {@code run()}:
   *
   * <ul>
   *   <li>both stubs' AtomicBoolean flags must be true — the snapshot phase visited both
   *       (independent of {@code HashMap} iteration order);</li>
   *   <li>{@code rebuildCompleted} must be true — the loop ran to completion across both
   *       failing entries (the catch did not abort the loop).</li>
   * </ul>
   *
   * <p>An empty catch body that swallowed the exception silently would still pass; an
   * empty catch body that re-threw, or a missing catch, would leave
   * {@code rebuildCompleted == false} (the assignment at
   * {@code RecreateIndexesTask.recreateIndexes} only runs after the for-loop exits
   * normally). That is the invariant under test.
   */
  @Test
  public void run_twoBrokenStubs_loopVisitsBothPastFirstFailure() {
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

    // Inject two broken Index stubs; each fires its own AtomicBoolean when its
    // getConfiguration is called by the snapshot phase. Each stub also produces a Map with
    // a missing 'type' key that triggers IndexException at recreation time, exercising the
    // catch-continue branch in the loop body.
    var firstFlag = new AtomicBoolean(false);
    var secondFlag = new AtomicBoolean(false);
    indexManager.indexes.put("__broken_first__", brokenIndexStub(firstFlag));
    indexManager.indexes.put("__broken_second__", brokenIndexStub(secondFlag));

    indexManager.rebuildCompleted = false;

    var task = new RecreateIndexesTask(indexManager, sharedContext);
    try {
      task.run();

      assertTrue("first broken stub must have been visited in the configuration snapshot",
          firstFlag.get());
      assertTrue("second broken stub must have been visited in the configuration snapshot",
          secondFlag.get());
      assertTrue(
          "rebuildCompleted must be true: the recreation loop ran to completion across both"
              + " failing entries (catch absorbed each IndexException without aborting)",
          indexManager.rebuildCompleted);
      // The real index should still be present.
      assertTrue("Good index must still be registered after partial rebuild",
          indexManager.existsIndex("PersonRIT2.name"));
    } finally {
      // Clean the broken stubs out of indexManager.indexes so subsequent tests don't see them.
      indexManager.indexes.remove("__broken_first__");
      indexManager.indexes.remove("__broken_second__");
    }
  }

  /**
   * Loop-continuation invariant with a real schema-defined good index: <em>two</em> broken
   * stubs are injected alongside a previously dropped-and-re-injected schema-defined good
   * index. After {@code run()}:
   *
   * <ul>
   *   <li>both broken stubs' flags must be true — the snapshot phase visited both before
   *       the recreation loop started, regardless of {@code HashMap} iteration order;</li>
   *   <li>{@code rebuildCompleted} must be true — the loop did not abort on either failing
   *       entry;</li>
   *   <li>the schema-defined good index must still be registered — it survived the broken
   *       siblings.</li>
   * </ul>
   *
   * <p>Two stubs (rather than one) make this invariant order-independent: at least one of
   * the broken stubs is guaranteed to be processed by the loop before the good index in
   * <em>some</em> run, so over enough fixture re-runs an aborting loop would be exposed.
   * Within a single run the same insight applies to the snapshot phase: both flags being
   * true proves the snapshot-build iterator did not short-circuit on the first failure.
   */
  @Test
  public void run_twoBrokenStubsBeforeGoodIndex_goodIndexStillProcessed() {
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

    // Drop the good index from the indexes map, then re-inject both it and two broken stubs
    // around it. Iteration order on HashMap is unspecified — having two broken stubs covers
    // the two possible "broken before good" / "broken after good" arrangements (and any mix
    // of HashMap bucket ordering). What we test is that the snapshot collects all entries
    // and the loop completes regardless.
    indexManager.indexes.remove("LateIndex.name");

    var firstFlag = new AtomicBoolean(false);
    var secondFlag = new AtomicBoolean(false);
    indexManager.indexes.put("__broken_first__", brokenIndexStub(firstFlag));
    indexManager.indexes.put("LateIndex.name", goodIndex);
    indexManager.indexes.put("__broken_second__", brokenIndexStub(secondFlag));

    indexManager.rebuildCompleted = false;

    var task = new RecreateIndexesTask(indexManager, sharedContext);
    try {
      task.run();

      assertTrue("first broken stub's getConfiguration must have been invoked",
          firstFlag.get());
      assertTrue("second broken stub's getConfiguration must have been invoked",
          secondFlag.get());
      assertTrue("rebuildCompleted must be true after both stubs failed in the loop",
          indexManager.rebuildCompleted);
      assertTrue("the good schema-defined index must survive the broken stubs' failures",
          indexManager.existsIndex("LateIndex.name"));
    } finally {
      indexManager.indexes.remove("__broken_first__");
      indexManager.indexes.remove("__broken_second__");
    }
  }
}
