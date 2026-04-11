package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot-isolation tests that directly call {@code Index.streamEntriesMajor} and
 * {@code Index.streamEntriesMinor} to exercise
 * {@code BTreeSingleValueIndexEngine.iterateEntriesMajor/Minor} with UNIQUE indexes.
 *
 * <p>SQL {@code WHERE age > X} goes through {@code iterateEntriesBetween} (with a null
 * bound), which is tested by {@link SnapshotIsolationIndexesUniqueSingleSidedRangeTest}.
 * The standalone {@code iterateEntriesMajor/Minor} methods are a different code path that
 * these tests target.
 */
public class SnapshotIsolationIndexesUniqueMajorMinorTest
    extends SnapshotIsolationIndexesTestBase {

  private static final String INDEX_NAME = "IndexAge";

  @Override
  @Before
  public void setUp() {
    super.setUp();

    var schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex(INDEX_NAME, INDEX_TYPE.UNIQUE, "age");
  }

  // ==========================================================================
  //  streamEntriesMajor  —  tests for iterateEntriesMajor
  // ==========================================================================

  /**
   * Phantom insert above the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: major(15, exclusive) → 2  (20, 30)
   *   Concurrent: insert age=25
   *   Snapshot: major(15, exclusive) → still 2
   *   Fresh: major(15, exclusive) → 3
   * </pre>
   */
  @Test
  public void majorNoPhantom() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, majorCount(snap, 15, false));

    insertVertices(db, 25);

    assertEquals(2, majorCount(snap, 15, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, majorCount(fresh, 15, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Delete above the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: major(15, exclusive) → 2
   *   Concurrent: update age=20 → age=99 (effectively "delete" from range perspective,
   *               but we use update so we can track the vertex)
   *   Snapshot: major(15, exclusive) → still 2
   *   Fresh: major(15, exclusive) → 2  (30, 99)
   * </pre>
   */
  @Test
  public void majorNoVisibilityForUpdate() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, majorCount(snap, 15, false));

    // Update 20 → 5 (moves it out of >15 range)
    updateVertex(db, 20, 5);

    assertEquals(2, majorCount(snap, 15, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(1, majorCount(fresh, 15, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update that moves a record INTO the major-range is invisible to the snapshot.
   * <pre>
   *   Initial: age = {5, 10, 30}
   *   Snapshot: major(15, exclusive) → 1  (30)
   *   Concurrent: update 5 → 20
   *   Snapshot: major(15, exclusive) → still 1
   *   Fresh: major(15, exclusive) → 2
   * </pre>
   */
  @Test
  public void majorNoVisibilityForUpdateIntoRange() {
    insertVertices(db, 5, 10, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(1, majorCount(snap, 15, false));

    updateVertex(db, 5, 20);

    assertEquals(1, majorCount(snap, 15, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, majorCount(fresh, 15, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Inclusive major: phantom at boundary is invisible.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: major(20, inclusive) → 2  (20, 30)
   *   Concurrent: insert age=25
   *   Snapshot: major(20, inclusive) → still 2
   *   Fresh: major(20, inclusive) → 3
   * </pre>
   */
  @Test
  public void majorInclusiveNoPhantom() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, majorCount(snap, 20, true));

    insertVertices(db, 25);

    assertEquals(2, majorCount(snap, 20, true));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, majorCount(fresh, 20, true));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: move out then back in.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: major(15, exclusive) → 2
   *   TX1: update 20 → 5
   *   TX2: update 5 → 20
   *   Snapshot: major(15, exclusive) → still 2
   * </pre>
   */
  @Test
  public void majorABAUpdate() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, majorCount(snap, 15, false));

    updateVertex(db, 20, 5);
    updateVertex(db, 5, 20);

    assertEquals(2, majorCount(snap, 15, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, majorCount(fresh, 15, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Two snapshots at different times see different major results.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot1: major(15, inclusive) → 2
   *   insert age=25
   *   Snapshot2: major(15, inclusive) → 3
   *   update 30 → 5 (remove from range)
   *   Snapshot1: still 2, Snapshot2: still 3
   *   Fresh: major(15, inclusive) → 2  (20, 25)
   * </pre>
   */
  @Test
  public void majorTwoSnapshots() {
    insertVertices(db, 10, 20, 30);

    var snap1 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap1.begin();
    assertEquals(2, majorCount(snap1, 15, true));

    insertVertices(db, 25);

    var snap2 = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap2.begin();
    assertEquals(3, majorCount(snap2, 15, true));

    updateVertex(db, 30, 5);

    assertEquals(2, majorCount(snap1, 15, true));
    assertEquals(3, majorCount(snap2, 15, true));
    snap1.commit();
    snap2.commit();
    snap1.close();
    snap2.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, majorCount(fresh, 15, true));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  streamEntriesMinor  —  tests for iterateEntriesMinor
  // ==========================================================================

  /**
   * Phantom insert below the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: minor(25, exclusive) → 2  (10, 20)
   *   Concurrent: insert age=15
   *   Snapshot: minor(25, exclusive) → still 2
   *   Fresh: minor(25, exclusive) → 3
   * </pre>
   */
  @Test
  public void minorNoPhantom() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, minorCount(snap, 25, false));

    insertVertices(db, 15);

    assertEquals(2, minorCount(snap, 25, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, minorCount(fresh, 25, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update that moves a record OUT OF the minor-range is invisible.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: minor(25, exclusive) → 2  (10, 20)
   *   Concurrent: update 10 → 40
   *   Snapshot: minor(25, exclusive) → still 2
   *   Fresh: minor(25, exclusive) → 1
   * </pre>
   */
  @Test
  public void minorNoVisibilityForUpdate() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, minorCount(snap, 25, false));

    updateVertex(db, 10, 40);

    assertEquals(2, minorCount(snap, 25, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(1, minorCount(fresh, 25, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Update that moves a record INTO the minor-range is invisible.
   * <pre>
   *   Initial: age = {10, 20, 50}
   *   Snapshot: minor(25, exclusive) → 2  (10, 20)
   *   Concurrent: update 50 → 15
   *   Snapshot: minor(25, exclusive) → still 2
   *   Fresh: minor(25, exclusive) → 3
   * </pre>
   */
  @Test
  public void minorNoVisibilityForUpdateIntoRange() {
    insertVertices(db, 10, 20, 50);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, minorCount(snap, 25, false));

    updateVertex(db, 50, 15);

    assertEquals(2, minorCount(snap, 25, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, minorCount(fresh, 25, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Inclusive minor: phantom at boundary is invisible.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: minor(20, inclusive) → 2  (10, 20)
   *   Concurrent: insert age=15
   *   Snapshot: minor(20, inclusive) → still 2
   *   Fresh: minor(20, inclusive) → 3
   * </pre>
   */
  @Test
  public void minorInclusiveNoPhantom() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, minorCount(snap, 20, true));

    insertVertices(db, 15);

    assertEquals(2, minorCount(snap, 20, true));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(3, minorCount(fresh, 20, true));
    fresh.commit();
    fresh.close();
  }

  /**
   * ABA update: move out then back in.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: minor(25, exclusive) → 2  (10, 20)
   *   TX1: update 10 → 40
   *   TX2: update 40 → 10
   *   Snapshot: minor(25, exclusive) → still 2
   * </pre>
   */
  @Test
  public void minorABAUpdate() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, minorCount(snap, 25, false));

    updateVertex(db, 10, 40);
    updateVertex(db, 40, 10);

    assertEquals(2, minorCount(snap, 25, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, minorCount(fresh, 25, false));
    fresh.commit();
    fresh.close();
  }

  /**
   * Snapshot started on empty DB sees nothing even after concurrent inserts.
   * <pre>
   *   Snapshot (empty): minor(25, inclusive) → 0
   *   Concurrent: insert {10, 20, 30}
   *   Snapshot: minor(25, inclusive) → still 0
   *   Fresh: minor(25, inclusive) → 2
   * </pre>
   */
  @Test
  public void minorNoVisibilityBeforeAnyData() {
    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(0, minorCount(snap, 25, true));

    insertVertices(db, 10, 20, 30);

    assertEquals(0, minorCount(snap, 25, true));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, minorCount(fresh, 25, true));
    fresh.commit();
    fresh.close();
  }

  /**
   * Bulk inserts: many records both inside and outside the range.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: minor(25, exclusive) → 2
   *   Concurrent: insert {1, 12, 22, 32, 50}
   *   Snapshot: minor(25, exclusive) → still 2
   *   Fresh: minor(25, exclusive) → 5  (1, 10, 12, 20, 22)
   * </pre>
   */
  @Test
  public void minorNoVisibilityForBulkInserts() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, minorCount(snap, 25, false));

    insertVertices(db, 1, 12, 22, 32, 50);

    assertEquals(2, minorCount(snap, 25, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(5, minorCount(fresh, 25, false));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  Helpers
  // ==========================================================================

  /**
   * Inserts vertices with the given age values, each in its own transaction.
   */
  private void insertVertices(DatabaseSessionEmbedded session, int... ages) {
    for (int age : ages) {
      var tx = session.begin();
      var v = tx.newVertex("Userr");
      v.setProperty("age", age);
      tx.commit();
    }
  }

  /**
   * Updates the vertex with {@code oldAge} to {@code newAge} using SQL in a transaction.
   */
  private void updateVertex(DatabaseSessionEmbedded session, int oldAge, int newAge) {
    var tx = session.begin();
    tx.command("UPDATE Userr SET age = ? WHERE age = ?", newAge, oldAge);
    tx.commit();
  }

  /**
   * Calls {@code Index.streamEntriesMajor} directly → routes to
   * {@code BTreeSingleValueIndexEngine.iterateEntriesMajor}.
   */
  private long majorCount(DatabaseSessionEmbedded session, Object fromKey, boolean inclusive) {
    Index index = session.getIndex(INDEX_NAME);
    try (var stream = index.streamEntriesMajor(
        session, new CompositeKey(fromKey), inclusive, true)) {
      return stream.count();
    }
  }

  /**
   * Calls {@code Index.streamEntriesMinor} directly → routes to
   * {@code BTreeSingleValueIndexEngine.iterateEntriesMinor}.
   */
  private long minorCount(DatabaseSessionEmbedded session, Object toKey, boolean inclusive) {
    Index index = session.getIndex(INDEX_NAME);
    try (var stream = index.streamEntriesMinor(
        session, new CompositeKey(toKey), inclusive, true)) {
      return stream.count();
    }
  }

}
