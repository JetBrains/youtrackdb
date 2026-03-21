package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot-isolation tests that directly call {@code Index.streamEntriesMajor} and
 * {@code Index.streamEntriesMinor} to exercise
 * {@code BTreeMultiValueIndexEngine.iterateEntriesMajor/Minor} with NOTUNIQUE indexes.
 *
 * <p>Mirrors {@link SnapshotIsolationIndexesUniqueMajorMinorTest} but uses NOTUNIQUE indexes,
 * which route to {@code BTreeMultiValueIndexEngine} instead of
 * {@code BTreeSingleValueIndexEngine}.
 */
public class SnapshotIsolationIndexesNotUniqueMajorMinorTest {

  private static final String INDEX_NAME = "IndexAge";

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);

    var schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex(INDEX_NAME, INDEX_TYPE.NOTUNIQUE, "age");
  }

  // ==========================================================================
  //  streamEntriesMajor  —  tests for iterateEntriesMajor
  // ==========================================================================

  /**
   * Phantom insert above the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: major(15, exclusive) → 2
   *   Concurrent: insert age=25
   *   Snapshot: still 2
   *   Fresh: 3
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
   * Update that moves a record OUT OF the major-range is invisible.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: major(15, exclusive) → 2
   *   Concurrent: update 20 → 5
   *   Snapshot: still 2
   *   Fresh: 1
   * </pre>
   */
  @Test
  public void majorNoVisibilityForUpdate() {
    insertVertices(db, 10, 20, 30);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(2, majorCount(snap, 15, false));

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
   * Update that moves a record INTO the major-range is invisible.
   * <pre>
   *   Initial: age = {5, 10, 30}
   *   Snapshot: major(15, exclusive) → 1
   *   Concurrent: update 5 → 20
   *   Snapshot: still 1
   *   Fresh: 2
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
   *   Snapshot: major(20, inclusive) → 2
   *   Concurrent: insert age=25
   *   Snapshot: still 2
   *   Fresh: 3
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
   *   Snapshot: still 2
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
   *   update 30 → 5
   *   Snapshot1: still 2, Snapshot2: still 3
   *   Fresh: 2  (20, 25)
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

  /**
   * Duplicate values with major scan: partial update of duplicates.
   * <pre>
   *   Initial: age = {20, 20, 20, 20}
   *   Snapshot: major(15, exclusive) → 4
   *   Concurrent: update two of them to 5 and 10
   *   Snapshot: still 4
   *   Fresh: 2
   * </pre>
   */
  @Test
  public void majorDuplicateValuesPartialUpdate() {
    insertVertices(db, 20, 20, 20, 20);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(4, majorCount(snap, 15, false));

    // Update two of the four age=20 records
    var tx = db.begin();
    try (var rs = tx.query("SELECT FROM Userr WHERE age = ? LIMIT 1", 20)) {
      if (rs.hasNext()) {
        var entity = rs.next().asEntity();
        entity.setProperty("age", 5);
      }
    }
    try (var rs = tx.query("SELECT FROM Userr WHERE age = ? LIMIT 1", 20)) {
      if (rs.hasNext()) {
        var entity = rs.next().asEntity();
        entity.setProperty("age", 10);
      }
    }
    tx.commit();

    assertEquals(4, majorCount(snap, 15, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, majorCount(fresh, 15, false));
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
   *   Snapshot: minor(25, exclusive) → 2
   *   Concurrent: insert age=15
   *   Snapshot: still 2
   *   Fresh: 3
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
   *   Snapshot: minor(25, exclusive) → 2
   *   Concurrent: update 10 → 40
   *   Snapshot: still 2
   *   Fresh: 1
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
   *   Snapshot: minor(25, exclusive) → 2
   *   Concurrent: update 50 → 15
   *   Snapshot: still 2
   *   Fresh: 3
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
   *   Snapshot: minor(20, inclusive) → 2
   *   Concurrent: insert age=15
   *   Snapshot: still 2
   *   Fresh: 3
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
   *   Snapshot: minor(25, exclusive) → 2
   *   TX1: update 10 → 40
   *   TX2: update 40 → 10
   *   Snapshot: still 2
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
   *   Snapshot: still 0
   *   Fresh: 2
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
   *   Snapshot: still 2
   *   Fresh: 5  (1, 10, 12, 20, 22)
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

  /**
   * Duplicate values with minor scan: partial update of duplicates.
   * <pre>
   *   Initial: age = {20, 20, 20, 20}
   *   Snapshot: minor(25, exclusive) → 4
   *   Concurrent: update two of them to 30 and 40
   *   Snapshot: still 4
   *   Fresh: 2
   * </pre>
   */
  @Test
  public void minorDuplicateValuesPartialUpdate() {
    insertVertices(db, 20, 20, 20, 20);

    var snap = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    snap.begin();
    assertEquals(4, minorCount(snap, 25, false));

    var tx = db.begin();
    try (var rs = tx.query("SELECT FROM Userr WHERE age = ? LIMIT 1", 20)) {
      if (rs.hasNext()) {
        var entity = rs.next().asEntity();
        entity.setProperty("age", 30);
      }
    }
    try (var rs = tx.query("SELECT FROM Userr WHERE age = ? LIMIT 1", 20)) {
      if (rs.hasNext()) {
        var entity = rs.next().asEntity();
        entity.setProperty("age", 40);
      }
    }
    tx.commit();

    assertEquals(4, minorCount(snap, 25, false));
    snap.commit();
    snap.close();

    var fresh = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
    fresh.begin();
    assertEquals(2, minorCount(fresh, 25, false));
    fresh.commit();
    fresh.close();
  }

  // ==========================================================================
  //  Helpers
  // ==========================================================================

  private void insertVertices(DatabaseSessionEmbedded session, int... ages) {
    for (int age : ages) {
      var tx = session.begin();
      var v = tx.newVertex("Userr");
      v.setProperty("age", age);
      tx.commit();
    }
  }

  private void updateVertex(DatabaseSessionEmbedded session, int oldAge, int newAge) {
    var tx = session.begin();
    tx.command("UPDATE Userr SET age = ? WHERE age = ? LIMIT 1", newAge, oldAge);
    tx.commit();
  }

  private long majorCount(DatabaseSessionEmbedded session, Object fromKey, boolean inclusive) {
    Index index = session.getIndex(INDEX_NAME);
    try (var stream = index.streamEntriesMajor(
        session, new CompositeKey(fromKey), inclusive, true)) {
      return stream.count();
    }
  }

  private long minorCount(DatabaseSessionEmbedded session, Object toKey, boolean inclusive) {
    Index index = session.getIndex(INDEX_NAME);
    try (var stream = index.streamEntriesMinor(
        session, new CompositeKey(toKey), inclusive, true)) {
      return stream.count();
    }
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }
}
