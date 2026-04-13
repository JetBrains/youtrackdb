package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot-isolation tests for {@code BTreeMultiValueIndexEngine.stream()},
 * {@code descStream()}, and {@code keyStream()} with NOTUNIQUE indexes.
 *
 * <p>{@code stream()} and {@code descStream()} are exercised via
 * {@code Index.stream(session)} / {@code Index.descStream(session)}, which is the same
 * path triggered by SQL {@code SELECT FROM index:IndexName} (full index scan without
 * WHERE). {@code keyStream()} is not reachable from SQL/Gremlin and is tested via the
 * Java API directly.
 */
public class SnapshotIsolationIndexesNotUniqueStreamTest
    extends SnapshotIsolationIndexesTestBase {

  private static final String INDEX_NAME = "IndexName";

  @Override
  @Before
  public void setUp() {
    super.setUp();

    var schema = db.createVertexClass("Userr");
    schema.createProperty("name", PropertyType.STRING);
    schema.createIndex(INDEX_NAME, INDEX_TYPE.NOTUNIQUE, "name");
  }

  // ==========================================================================
  //  stream()  —  full forward scan
  // ==========================================================================

  /**
   * Phantom insert is invisible to the snapshot via stream().
   * <pre>
   *   Initial: {Foo, Foo, Foo}
   *   Snapshot: stream() → 3
   *   Concurrent: insert Foo
   *   Snapshot: stream() → still 3
   *   Fresh: stream() → 4
   * </pre>
   */
  @Test
  public void streamNoPhantom() {
    insertVertices(db, "Foo", "Foo", "Foo");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, streamCount(snap));

    insertVertices(db, "Foo");

    assertEquals(3, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(4, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update is invisible to the snapshot via stream() — total count stays the same.
   * <pre>
   *   Initial: {Foo, Foo, Foo}
   *   Snapshot: stream() → 3
   *   Concurrent: update one Foo→Bar
   *   Snapshot: stream() → still 3
   *   Fresh: stream() → 3
   * </pre>
   */
  @Test
  public void streamNoVisibilityForUpdate() {
    insertVertices(db, "Foo", "Foo", "Foo");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, streamCount(snap));

    // update one Foo→Bar (uses LIMIT 1 to update just one)
    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Bar' WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    assertEquals(3, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete is invisible to the snapshot via stream().
   * <pre>
   *   Initial: {Foo, Foo, Bar}
   *   Snapshot: stream() → 3
   *   Concurrent: delete one Foo
   *   Snapshot: stream() → still 3
   *   Fresh: stream() → 2
   * </pre>
   */
  @Test
  public void streamNoVisibilityForDelete() {
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, streamCount(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    assertEquals(3, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * TX started before any data sees nothing via stream().
   * <pre>
   *   Snapshot (empty): stream() → 0
   *   Concurrent: insert {Foo, Foo, Bar}
   *   Snapshot: stream() → still 0
   *   Fresh: stream() → 3
   * </pre>
   */
  @Test
  public void streamNoVisibilityBeforeAnyData() {
    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(0, streamCount(snap));

    insertVertices(db, "Foo", "Foo", "Bar");

    assertEquals(0, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: stream() count unchanged for snapshot.
   */
  @Test
  public void streamABAUpdate() {
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, streamCount(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Foo' WHERE name = 'Baz' LIMIT 1");
    tx.commit();

    assertEquals(3, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete all records — invisible to snapshot via stream().
   * <pre>
   *   Initial: {Foo, Foo, Bar}
   *   Snapshot: stream() → 3
   *   Concurrent: delete all
   *   Snapshot: stream() → still 3
   *   Fresh: stream() → 0
   * </pre>
   */
  @Test
  public void streamNoVisibilityForDeleteAll() {
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, streamCount(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr");
    tx.commit();

    assertEquals(3, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(0, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  descStream()  —  full reverse scan
  // ==========================================================================

  /**
   * Phantom insert is invisible to the snapshot via descStream().
   */
  @Test
  public void descStreamNoPhantom() {
    insertVertices(db, "Foo", "Foo", "Foo");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, descStreamCount(snap));

    insertVertices(db, "Foo");

    assertEquals(3, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(4, descStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update is invisible to the snapshot via descStream().
   */
  @Test
  public void descStreamNoVisibilityForUpdate() {
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, descStreamCount(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    assertEquals(3, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, descStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete is invisible to the snapshot via descStream().
   */
  @Test
  public void descStreamNoVisibilityForDelete() {
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, descStreamCount(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    assertEquals(3, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, descStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * TX started before any data sees nothing via descStream().
   */
  @Test
  public void descStreamNoVisibilityBeforeAnyData() {
    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(0, descStreamCount(snap));

    insertVertices(db, "Foo", "Foo", "Bar");

    assertEquals(0, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, descStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  keyStream()  —  not reachable from SQL/Gremlin, tested via Java API
  // ==========================================================================

  /**
   * Phantom insert is invisible to the snapshot via keyStream().
   * Note: for NOTUNIQUE indexes with duplicate keys, keyStream returns one entry
   * per (key, RID) combination in the underlying single-value tree.
   */
  @Test
  public void keyStreamNoPhantom() {
    insertVertices(db, "Foo", "Foo", "Foo");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, keyStreamCount(snap));

    insertVertices(db, "Foo");

    assertEquals(3, keyStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(4, keyStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete is invisible to the snapshot via keyStream().
   */
  @Test
  public void keyStreamNoVisibilityForDelete() {
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, keyStreamCount(snap));

    var tx = db.begin();
    tx.command("DELETE VERTEX Userr WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    assertEquals(3, keyStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, keyStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update is invisible to the snapshot via keyStream().
   */
  @Test
  public void keyStreamNoVisibilityForUpdate() {
    insertVertices(db, "Foo", "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(3, keyStreamCount(snap));

    var tx = db.begin();
    tx.command("UPDATE Userr SET name = 'Baz' WHERE name = 'Foo' LIMIT 1");
    tx.commit();

    assertEquals(3, keyStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, keyStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  Helpers
  // ==========================================================================

  private void insertVertices(DatabaseSessionEmbedded session, String... names) {
    for (String name : names) {
      var tx = session.begin();
      var v = tx.newVertex("Userr");
      v.setProperty("name", name);
      tx.commit();
    }
  }

  private long streamCount(DatabaseSessionEmbedded session) {
    Index index = session.getIndex(INDEX_NAME);
    try (var stream = index.stream(session)) {
      return stream.count();
    }
  }

  private long descStreamCount(DatabaseSessionEmbedded session) {
    Index index = session.getIndex(INDEX_NAME);
    try (var stream = index.descStream(session)) {
      return stream.count();
    }
  }

  private long keyStreamCount(DatabaseSessionEmbedded session) {
    Index index = session.getIndex(INDEX_NAME);
    var tx = session.getActiveTransaction();
    try (var stream = index.keyStream(tx.getAtomicOperation())) {
      return stream.count();
    }
  }

}
