package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for {@link RecreateIndexesTask}, the component responsible for rebuilding indexes
 * after a crash. Tests exercise the happy path (indexes rebuilt successfully) and the
 * catch branch (a broken index config causes an error but the task continues with the
 * remaining indexes and still marks rebuild as complete).
 */
public class RecreateIndexesTaskTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test",
        com.jetbrains.youtrackdb.api.DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  /**
   * Happy path: a DB with one UNIQUE index is set up, then RecreateIndexesTask is constructed
   * directly and invoked via run(). After run(), rebuildCompleted must be true and the index
   * should still be queryable via the index manager.
   */
  @Test
  public void run_withExistingIndex_rebuildCompletedIsTrue() {
    // Schema setup must happen outside a transaction.
    SchemaClass cls = db.createVertexClass("PersonRIT");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonRIT.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert a committed record so the index has data.
    db.begin();
    var entity = db.newVertex("PersonRIT");
    entity.setProperty("name", "Alice");
    db.commit();

    var sharedContext = db.getSharedContext();
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
   * the remaining (healthy) indexes, and still set rebuildCompleted = true.
   *
   * <p>A broken Index stub is injected into the index manager's internal map so that
   * getIndexesConfiguration returns one broken config alongside the real ones.
   */
  @Test
  public void run_withOneBrokenIndexConfig_taskContinuesAndRebuildCompleted() {
    // Schema setup must happen outside a transaction.
    SchemaClass cls = db.createVertexClass("PersonRIT2");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonRIT2.name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert a committed record so the real index has data.
    db.begin();
    var entity = db.newVertex("PersonRIT2");
    entity.setProperty("name", "Bob");
    db.commit();

    var sharedContext = db.getSharedContext();
    var indexManager = (IndexManagerEmbedded) sharedContext.getIndexManager();

    // Inject a broken Index stub whose getConfiguration returns a map with type=null.
    // IndexException is a RuntimeException (via BaseException), so the catch in
    // RecreateIndexesTask.recreateIndexes() will absorb it and increment errors.
    Index brokenIndex =
        (Index) Proxy.newProxyInstance(
            Index.class.getClassLoader(),
            new Class[] {Index.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getConfiguration")) {
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
    // indexManager.indexes is package-accessible from this package.
    indexManager.indexes.put("__broken__", brokenIndex);

    indexManager.rebuildCompleted = false;

    var task = new RecreateIndexesTask(indexManager, sharedContext);
    task.run();

    assertTrue("rebuildCompleted must be set to true even when one index config is broken",
        indexManager.rebuildCompleted);
    // The real index should still be present.
    assertTrue("Good index must still be registered after partial rebuild",
        indexManager.existsIndex("PersonRIT2.name"));
  }
}
