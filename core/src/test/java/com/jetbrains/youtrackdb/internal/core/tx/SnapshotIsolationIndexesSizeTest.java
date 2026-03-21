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
