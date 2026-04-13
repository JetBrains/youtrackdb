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
 * Snapshot-isolation tests for {@code BTreeSingleValueIndexEngine.stream()},
 * {@code descStream()}, and {@code keyStream()} with UNIQUE indexes.
 *
 * <p>{@code stream()} and {@code descStream()} are exercised via
 * {@code Index.stream(session)} / {@code Index.descStream(session)}, which is the same
 * path triggered by SQL {@code SELECT FROM index:IndexName} (full index scan without
 * WHERE). {@code keyStream()} is not reachable from SQL/Gremlin and is tested via the
 * Java API directly.
 */
public class SnapshotIsolationIndexesUniqueStreamTest
    extends SnapshotIsolationIndexesTestBase {

  private static final String INDEX_NAME = "IndexName";

  @Override
  @Before
  public void setUp() {
    super.setUp();

    var schema = db.createVertexClass("Userr");
    schema.createProperty("name", PropertyType.STRING);
    schema.createIndex(INDEX_NAME, INDEX_TYPE.UNIQUE, "name");
  }

  // ==========================================================================
  //  stream()  —  full forward scan
  // ==========================================================================

  /**
   * Phantom insert is invisible to the snapshot via stream().
   * <pre>
   *   Initial: {Foo}
   *   Snapshot: stream() → 1
   *   Concurrent: insert Bar
   *   Snapshot: stream() → still 1
   *   Fresh: stream() → 2
   * </pre>
   */
  @Test
  public void streamNoPhantom() {
    insertVertices(db, "Foo");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(1, streamCount(snap));

    insertVertices(db, "Bar");

    assertEquals(1, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update is invisible to the snapshot via stream() — count stays the same
   * but the snapshot must not see marker/tombstone entries.
   * <pre>
   *   Initial: {Foo, Bar}
   *   Snapshot: stream() → 2
   *   Concurrent: update Foo→Baz
   *   Snapshot: stream() → still 2 (Foo, Bar)
   *   Fresh: stream() → 2 (Baz, Bar)
   * </pre>
   */
  @Test
  public void streamNoVisibilityForUpdate() {
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, streamCount(snap));

    updateVertex(db, "Foo", "Baz");

    assertEquals(2, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete is invisible to the snapshot via stream().
   * <pre>
   *   Initial: {Foo, Bar}
   *   Snapshot: stream() → 2
   *   Concurrent: delete Foo
   *   Snapshot: stream() → still 2
   *   Fresh: stream() → 1
   * </pre>
   */
  @Test
  public void streamNoVisibilityForDelete() {
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, streamCount(snap));

    deleteVertex(db, "Foo");

    assertEquals(2, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(1, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * TX started before any data sees nothing via stream().
   * <pre>
   *   Snapshot (empty): stream() → 0
   *   Concurrent: insert {Foo, Bar}
   *   Snapshot: stream() → still 0
   *   Fresh: stream() → 2
   * </pre>
   */
  @Test
  public void streamNoVisibilityBeforeAnyData() {
    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(0, streamCount(snap));

    insertVertices(db, "Foo", "Bar");

    assertEquals(0, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, streamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: stream() count unchanged for snapshot.
   */
  @Test
  public void streamABAUpdate() {
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, streamCount(snap));

    updateVertex(db, "Foo", "Baz");
    updateVertex(db, "Baz", "Foo");

    assertEquals(2, streamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, streamCount(fresh));
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
    insertVertices(db, "Foo");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(1, descStreamCount(snap));

    insertVertices(db, "Bar");

    assertEquals(1, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, descStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update is invisible to the snapshot via descStream().
   */
  @Test
  public void descStreamNoVisibilityForUpdate() {
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, descStreamCount(snap));

    updateVertex(db, "Foo", "Baz");

    assertEquals(2, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, descStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete is invisible to the snapshot via descStream().
   */
  @Test
  public void descStreamNoVisibilityForDelete() {
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, descStreamCount(snap));

    deleteVertex(db, "Foo");

    assertEquals(2, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(1, descStreamCount(fresh));
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

    insertVertices(db, "Foo", "Bar");

    assertEquals(0, descStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, descStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  keyStream()  —  not reachable from SQL/Gremlin, tested via Java API
  // ==========================================================================

  /**
   * Phantom insert is invisible to the snapshot via keyStream().
   */
  @Test
  public void keyStreamNoPhantom() {
    insertVertices(db, "Foo");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(1, keyStreamCount(snap));

    insertVertices(db, "Bar");

    assertEquals(1, keyStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, keyStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete is invisible to the snapshot via keyStream().
   */
  @Test
  public void keyStreamNoVisibilityForDelete() {
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, keyStreamCount(snap));

    deleteVertex(db, "Foo");

    assertEquals(2, keyStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(1, keyStreamCount(fresh));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update is invisible to the snapshot via keyStream().
   */
  @Test
  public void keyStreamNoVisibilityForUpdate() {
    insertVertices(db, "Foo", "Bar");

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, keyStreamCount(snap));

    updateVertex(db, "Foo", "Baz");

    assertEquals(2, keyStreamCount(snap));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, keyStreamCount(fresh));
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

  private void updateVertex(DatabaseSessionEmbedded session, String oldName, String newName) {
    var tx = session.begin();
    tx.command("UPDATE Userr SET name = ? WHERE name = ?", newName, oldName);
    tx.commit();
  }

  private void deleteVertex(DatabaseSessionEmbedded session, String name) {
    var tx = session.begin();
    tx.command("DELETE VERTEX Userr WHERE name = ?", name);
    tx.commit();
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
