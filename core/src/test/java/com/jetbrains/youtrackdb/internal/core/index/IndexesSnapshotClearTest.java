package com.jetbrains.youtrackdb.internal.core.index;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that {@link IndexesSnapshot} is cleared when the underlying index engine's
 * {@code delete()} is invoked via DROP INDEX. Both {@code indexesSnapshot} and
 * {@code versionIndex} are cleared by {@link IndexesSnapshot#clear()}.
 */
public class IndexesSnapshotClearTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }

  // ========================================================================
  //  UNIQUE index (BTreeSingleValueIndexEngine)
  // ========================================================================

  /**
   * DROP INDEX calls delete() on the UNIQUE index engine.
   * After drop, the IndexesSnapshot for that index must be empty.
   */
  @Test
  public void uniqueIndex_dropIndex_clearsSnapshot() throws Exception {
    SchemaClass cls = db.createVertexClass("PersonUD");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonUD_name", SchemaClass.INDEX_TYPE.UNIQUE, "name");

    // Insert a record
    db.begin();
    var e1 = (EntityImpl) db.newVertex("PersonUD");
    e1.setProperty("name", "Alice");
    db.commit();

    // Update to populate the snapshot (old version tracked)
    db.begin();
    var tx = db.getActiveTransaction();
    e1 = tx.load(e1);
    e1.setProperty("name", "Bob");
    db.commit();

    var snapshot = getSnapshotForIndex("PersonUD_name");
    assertFalse(
        "Snapshot should have entries after update",
        snapshot.allEntries().isEmpty());

    // Drop index triggers delete() → indexesSnapshot.clear()
    db.execute("drop index PersonUD_name");

    assertTrue(
        "indexesSnapshot must be empty after drop index",
        snapshot.allEntries().isEmpty());
  }

  // ========================================================================
  //  NOT UNIQUE index (BTreeMultiValueIndexEngine)
  // ========================================================================

  /**
   * DROP INDEX calls delete() on the NOT UNIQUE index engine.
   * After drop, the IndexesSnapshot for that index must be empty.
   */
  @Test
  public void notUniqueIndex_dropIndex_clearsSnapshot() throws Exception {
    SchemaClass cls = db.createVertexClass("PersonNUD");
    cls.createProperty("name", PropertyType.STRING);
    cls.createIndex("PersonNUD_name", SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");

    // Insert a record
    db.begin();
    var e1 = (EntityImpl) db.newVertex("PersonNUD");
    e1.setProperty("name", "Alice");
    db.commit();

    // Delete to populate the snapshot (tombstone created)
    db.begin();
    var tx = db.getActiveTransaction();
    e1 = tx.load(e1);
    e1.delete();
    db.commit();

    var snapshot = getSnapshotForIndex("PersonNUD_name");
    assertFalse(
        "Snapshot should have entries after delete",
        snapshot.allEntries().isEmpty());

    // Drop index triggers delete() → indexesSnapshot.clear()
    db.execute("drop index PersonNUD_name");

    assertTrue(
        "indexesSnapshot must be empty after drop index",
        snapshot.allEntries().isEmpty());
  }

  // ========================================================================
  //  Helper
  // ========================================================================

  /**
   * Gets the IndexesSnapshot for a given index by resolving the external index ID
   * to the internal engine ID via {@code getIndexEngine().getId()}.
   */
  private IndexesSnapshot getSnapshotForIndex(String indexName)
      throws InvalidIndexEngineIdException {
    var index = (IndexAbstract) db.getSharedContext().getIndexManager().getIndex(indexName);
    var storage = db.getStorage();
    int internalId = storage.getIndexEngine(index.getIndexId()).getId();
    return storage.subIndexSnapshot(internalId);
  }
}
