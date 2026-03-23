package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot-isolation tests for {@code Index.size()} on both UNIQUE
 * ({@code BTreeSingleValueIndexEngine}) and NOTUNIQUE
 * ({@code BTreeMultiValueIndexEngine}) indexes.
 *
 * <p>After SI changes, the BTree stores versioned entries including tombstones.
 * {@code size()} must return only the count of visible entries, not raw BTree size.
 */
public class SnapshotIsolationIndexesSizeTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  // ==========================================================================
  //  NOTUNIQUE  —  BTreeMultiValueIndexEngine.size()
  // ==========================================================================

  /**
   * After insert, size reflects the number of inserted entries.
   */
  @Test
  public void sizeAfterInsert_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After delete, size decreases.
   */
  @Test
  public void sizeAfterDelete_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting all records, size is 0.
   */
  @Test
  public void sizeAfterDeleteAll_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(0, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After update, size stays the same (one entry removed, one added).
   */
  @Test
  public void sizeAfterUpdate_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Qux' WHERE name = 'Foo'");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * Snapshot sees original size, not the size after concurrent insert.
   */
  @Test
  public void sizeSnapshotNoPhantom_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVertices(db, "Baz");

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size, not the size after concurrent delete.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDelete_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size after concurrent update (size unchanged).
   */
  @Test
  public void sizeSnapshotNoVisibilityForUpdate_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  UNIQUE  —  BTreeSingleValueIndexEngine.size()
  // ==========================================================================

  /**
   * After insert, size reflects the number of inserted entries.
   */
  @Test
  public void sizeAfterInsert_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After delete, size decreases.
   */
  @Test
  public void sizeAfterDelete_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo'");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting all records, size is 0.
   */
  @Test
  public void sizeAfterDeleteAll_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(0, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After update, size stays the same.
   */
  @Test
  public void sizeAfterUpdate_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Qux' WHERE name = 'Foo'");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * Snapshot sees original size, not the size after concurrent insert.
   */
  @Test
  public void sizeSnapshotNoPhantom_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVertices(db, "Baz");

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size, not the size after concurrent delete.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDelete_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar", "Baz");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo'");
    tx.commit();

    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size after concurrent update.
   */
  @Test
  public void sizeSnapshotNoVisibilityForUpdate_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: size stays the same for snapshot and fresh session.
   */
  @Test
  public void sizeABAUpdate_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Foo' WHERE name = 'Baz'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: size stays the same for snapshot and fresh session (NOTUNIQUE).
   */
  @Test
  public void sizeABAUpdate_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo'");
    tx.commit();

    tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Foo' WHERE name = 'Baz'");
    tx.commit();

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  NOTUNIQUE  —  size() includes null keys
  // ==========================================================================

  /**
   * size() must count both null-keyed and non-null-keyed entries.
   */
  @Test
  public void sizeIncludesNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    // Insert 2 non-null and 1 null-keyed vertex
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * size() must count multiple null-keyed entries in a NOTUNIQUE index.
   */
  @Test
  public void sizeIncludesMultipleNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 3);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(4, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * size() returns correct value when all entries have null keys.
   */
  @Test
  public void sizeAllNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVerticesWithNullKey(db, 3);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting a null-keyed record, size decreases.
   */
  @Test
  public void sizeAfterDeleteNullKey_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 2);

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL LIMIT 1");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * Snapshot sees original size when a null-keyed record is inserted concurrently.
   */
  @Test
  public void sizeSnapshotNoPhantomNullKey_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 1);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVerticesWithNullKey(db, 1);

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size when a null-keyed record is deleted concurrently.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDeleteNullKey_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 2);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL LIMIT 1");
    tx.commit();

    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  UNIQUE  —  size() includes null keys
  // ==========================================================================

  /**
   * size() must count both null-keyed and non-null-keyed entries.
   */
  @Test
  public void sizeIncludesNullKeys_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * After deleting a null-keyed record, size decreases.
   */
  @Test
  public void sizeAfterDeleteNullKey_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL");
    tx.commit();

    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * Snapshot sees original size when a null-keyed record is inserted concurrently.
   */
  @Test
  public void sizeSnapshotNoPhantomNullKey_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, getIndexSize(snap));

    insertVerticesWithNullKey(db, 1);

    assertEquals(2, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot sees original size when a null-keyed record is deleted concurrently.
   */
  @Test
  public void sizeSnapshotNoVisibilityForDeleteNullKey_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo", "Bar");
    insertVerticesWithNullKey(db, 1);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, getIndexSize(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name IS NULL");
    tx.commit();

    assertEquals(3, getIndexSize(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, getIndexSize(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  Index drop/recreate clears null-keyed entries (exercises doClearTree)
  // ==========================================================================

  /**
   * Dropping and recreating a UNIQUE index must clear null-keyed entries.
   * This exercises doClearTree/delete which must remove CompositeKey(null, version)
   * entries from the sbTree.
   */
  @Test
  public void dropAndRecreateIndex_clearsNullKeys_UNIQUE() {
    createSchema(INDEX_TYPE.UNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 1);

    // Verify 2 entries before drop
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();

    // Drop and recreate the index
    db.execute("drop index IndexName");
    db.getMetadata().getSchema().getClass("Userr")
        .createIndex("IndexName", INDEX_TYPE.UNIQUE, "name");

    // Size must reflect only existing records (re-indexed on create)
    session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(2, getIndexSize(session));
    session.commit();
    session.close();
  }

  /**
   * Dropping and recreating a NOTUNIQUE index must clear null-keyed entries
   * from both svTree and nullTree.
   */
  @Test
  public void dropAndRecreateIndex_clearsNullKeys_NOTUNIQUE() {
    createSchema(INDEX_TYPE.NOTUNIQUE);
    insertVertices(db, "Foo");
    insertVerticesWithNullKey(db, 2);

    // Verify 3 entries before drop
    var session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();

    // Drop and recreate the index
    db.execute("drop index IndexName");
    db.getMetadata().getSchema().getClass("Userr")
        .createIndex("IndexName", INDEX_TYPE.NOTUNIQUE, "name");

    // Size must reflect only existing records (re-indexed on create)
    session = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    session.begin();
    assertEquals(3, getIndexSize(session));
    session.commit();
    session.close();
  }

  // ==========================================================================
  //  Helpers
  // ==========================================================================

  private void createSchema(INDEX_TYPE indexType) {
    var schema = db.createVertexClass("Userr");
    schema.createProperty("name", PropertyType.STRING);
    schema.createIndex("IndexName", indexType, "name");
  }

  private void insertVertices(DatabaseSessionEmbedded session, String... names) {
    for (String name : names) {
      var tx = session.begin();
      var v = tx.newVertex("Userr");
      v.setProperty("name", name);
      tx.commit();
    }
  }

  private void insertVerticesWithNullKey(DatabaseSessionEmbedded session, int count) {
    for (int i = 0; i < count; i++) {
      var tx = session.begin();
      tx.newVertex("Userr");
      // name not set → null key in the index
      tx.commit();
    }
  }

  private long getIndexSize(DatabaseSessionEmbedded session) {
    Index index = session.getIndex("IndexName");
    return index.size(session);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }
}
