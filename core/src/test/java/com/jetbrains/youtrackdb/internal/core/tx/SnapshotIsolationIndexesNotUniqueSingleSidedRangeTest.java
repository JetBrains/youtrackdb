package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.junit.Test;

/**
 * Snapshot-isolation tests for BTree index using single-sided range predicates.
 * <p>
 * Each test uses ONLY one predicate family to ensure the query hits
 * {@code iterateEntriesMajor} or {@code iterateEntriesMinor} in
 * {@code BTreeMultiValueIndexEngine}, NOT the combined
 * {@code iterateEntriesBetween} path.
 * <ul>
 *   <li>{@code P.gt()} / {@code P.gte()} → major scan</li>
 *   <li>{@code P.lt()} / {@code P.lte()} → minor scan</li>
 * </ul>
 */
public class SnapshotIsolationIndexesNotUniqueSingleSidedRangeTest
    extends SnapshotIsolationIndexesTestBase {

  // ==========================================================================
  //  P.gt()  —  major scan (exclusive)
  // ==========================================================================

  /**
   * Phantom insert above the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.gt(15) → 2  (20, 30)
   *   Concurrent: insert age=25
   *   Snapshot: P.gt(15) → still 2
   *   Fresh TX: P.gt(15) → 3
   * </pre>
   */
  @Test
  public void gtNoPhantom_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    // Snapshot
    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());

    // Concurrent insert
    graph.tx().begin();
    graph.addV("Userr").property("age", 25).next();
    graph.tx().commit();

    // Snapshot unchanged
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    snap.tx().commit();

    // Fresh TX sees 3
    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(3, fresh.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Delete of a record above the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.gt(15) → 2  (20, 30)
   *   Concurrent: delete age=20
   *   Snapshot: P.gt(15) → still 2
   *   Fresh TX: P.gt(15) → 1
   * </pre>
   */
  @Test
  public void gtNoVisibilityForDelete_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());

    graph.tx().begin();
    graph.V(id20).drop().iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(1, fresh.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Update that moves a record INTO the gt-range is invisible to the snapshot.
   * <pre>
   *   Initial: age = {5, 10, 30}
   *   Snapshot: P.gt(15) → 1  (30)
   *   Concurrent: update 5 → 20 (now above threshold)
   *   Snapshot: P.gt(15) → still 1
   *   Fresh TX: P.gt(15) → 2
   * </pre>
   */
  @Test
  public void gtNoVisibilityForUpdateIntoRange_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    var v5 = graph.addV("Userr").property("age", 5).next();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 30).next();
    var id5 = v5.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(1, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());

    graph.tx().begin();
    graph.V(id5).property("age", 20).iterate();
    graph.tx().commit();

    assertEquals(1, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(2, fresh.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Update that moves a record OUT OF the gt-range is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.gt(15) → 2  (20, 30)
   *   Concurrent: update 20 → 5 (now below threshold)
   *   Snapshot: P.gt(15) → still 2
   *   Fresh TX: P.gt(15) → 1
   * </pre>
   */
  @Test
  public void gtNoVisibilityForUpdateOutOfRange_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());

    graph.tx().begin();
    graph.V(id20).property("age", 5).iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(1, fresh.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  // ==========================================================================
  //  P.gte()  —  major scan (inclusive)
  // ==========================================================================

  /**
   * Phantom insert at the boundary is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.gte(20) → 2  (20, 30)
   *   Concurrent: insert age=20 (duplicate at boundary)
   *   Snapshot: P.gte(20) → still 2
   *   Fresh TX: P.gte(20) → 3
   * </pre>
   */
  @Test
  public void gteNoPhantomAtBoundary_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gte(20)).toList().size());

    graph.tx().begin();
    graph.addV("Userr").property("age", 20).next();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gte(20)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(3, fresh.V().hasLabel("Userr").has("age", P.gte(20)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Delete of a boundary record is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.gte(20) → 2  (20, 30)
   *   Concurrent: delete age=20
   *   Snapshot: P.gte(20) → still 2
   *   Fresh TX: P.gte(20) → 1
   * </pre>
   */
  @Test
  public void gteNoVisibilityForDeleteAtBoundary_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gte(20)).toList().size());

    graph.tx().begin();
    graph.V(id20).drop().iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gte(20)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(1, fresh.V().hasLabel("Userr").has("age", P.gte(20)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Two snapshots started at different times see different gte results.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot1: P.gte(15) → 2
   *   Concurrent: insert age=25
   *   Snapshot2: P.gte(15) → 3
   *   Concurrent: delete age=30
   *   Snapshot1: still 2
   *   Snapshot2: still 3
   *   Fresh TX: P.gte(15) → 2  (20, 25)
   * </pre>
   */
  @Test
  public void gteTwoSnapshotsSeeDifferentStates_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    var v30 = graph.addV("Userr").property("age", 30).next();
    var id30 = v30.id();
    graph.tx().commit();

    // Snapshot1
    var snap1 = openGraph();
    snap1.tx().begin();
    assertEquals(2, snap1.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());

    // Insert
    graph.tx().begin();
    graph.addV("Userr").property("age", 25).next();
    graph.tx().commit();

    // Snapshot2
    var snap2 = openGraph();
    snap2.tx().begin();
    assertEquals(3, snap2.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());

    // Delete
    graph.tx().begin();
    graph.V(id30).drop().iterate();
    graph.tx().commit();

    assertEquals(2, snap1.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());
    assertEquals(3, snap2.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());
    snap1.tx().commit();
    snap2.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(2, fresh.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap1.close();
    snap2.close();
    graph.close();
  }

  // ==========================================================================
  //  P.lt()  —  minor scan (exclusive)
  // ==========================================================================

  /**
   * Phantom insert below the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.lt(25) → 2  (10, 20)
   *   Concurrent: insert age=15
   *   Snapshot: P.lt(25) → still 2
   *   Fresh TX: P.lt(25) → 3
   * </pre>
   */
  @Test
  public void ltNoPhantom_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());

    graph.tx().begin();
    graph.addV("Userr").property("age", 15).next();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(3, fresh.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Delete of a record below the threshold is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.lt(25) → 2  (10, 20)
   *   Concurrent: delete age=10
   *   Snapshot: P.lt(25) → still 2
   *   Fresh TX: P.lt(25) → 1
   * </pre>
   */
  @Test
  public void ltNoVisibilityForDelete_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    var v10 = graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id10 = v10.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());

    graph.tx().begin();
    graph.V(id10).drop().iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(1, fresh.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Update that moves a record INTO the lt-range is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 50}
   *   Snapshot: P.lt(25) → 2  (10, 20)
   *   Concurrent: update 50 → 15 (now below threshold)
   *   Snapshot: P.lt(25) → still 2
   *   Fresh TX: P.lt(25) → 3
   * </pre>
   */
  @Test
  public void ltNoVisibilityForUpdateIntoRange_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    var v50 = graph.addV("Userr").property("age", 50).next();
    var id50 = v50.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());

    graph.tx().begin();
    graph.V(id50).property("age", 15).iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(3, fresh.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Update that moves a record OUT OF the lt-range is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.lt(25) → 2  (10, 20)
   *   Concurrent: update 10 → 40 (now above threshold)
   *   Snapshot: P.lt(25) → still 2
   *   Fresh TX: P.lt(25) → 1
   * </pre>
   */
  @Test
  public void ltNoVisibilityForUpdateOutOfRange_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    var v10 = graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id10 = v10.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());

    graph.tx().begin();
    graph.V(id10).property("age", 40).iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(1, fresh.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  // ==========================================================================
  //  P.lte()  —  minor scan (inclusive)
  // ==========================================================================

  /**
   * Phantom insert at the boundary is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.lte(20) → 2  (10, 20)
   *   Concurrent: insert age=20 (duplicate at boundary)
   *   Snapshot: P.lte(20) → still 2
   *   Fresh TX: P.lte(20) → 3
   * </pre>
   */
  @Test
  public void lteNoPhantomAtBoundary_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lte(20)).toList().size());

    graph.tx().begin();
    graph.addV("Userr").property("age", 20).next();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lte(20)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(3, fresh.V().hasLabel("Userr").has("age", P.lte(20)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Delete of a boundary record is invisible to the snapshot.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.lte(20) → 2  (10, 20)
   *   Concurrent: delete age=20
   *   Snapshot: P.lte(20) → still 2
   *   Fresh TX: P.lte(20) → 1
   * </pre>
   */
  @Test
  public void lteNoVisibilityForDeleteAtBoundary_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lte(20)).toList().size());

    graph.tx().begin();
    graph.V(id20).drop().iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lte(20)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(1, fresh.V().hasLabel("Userr").has("age", P.lte(20)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Two snapshots started at different times see different lte results.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot1: P.lte(25) → 2
   *   Concurrent: insert age=15
   *   Snapshot2: P.lte(25) → 3
   *   Concurrent: delete age=10
   *   Snapshot1: still 2
   *   Snapshot2: still 3
   *   Fresh TX: P.lte(25) → 2  (15, 20)
   * </pre>
   */
  @Test
  public void lteTwoSnapshotsSeeDifferentStates_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    var v10 = graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id10 = v10.id();
    graph.tx().commit();

    var snap1 = openGraph();
    snap1.tx().begin();
    assertEquals(2, snap1.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());

    graph.tx().begin();
    graph.addV("Userr").property("age", 15).next();
    graph.tx().commit();

    var snap2 = openGraph();
    snap2.tx().begin();
    assertEquals(3, snap2.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());

    graph.tx().begin();
    graph.V(id10).drop().iterate();
    graph.tx().commit();

    assertEquals(2, snap1.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());
    assertEquals(3, snap2.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());
    snap1.tx().commit();
    snap2.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(2, fresh.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap1.close();
    snap2.close();
    graph.close();
  }

  /**
   * Snapshot started on an empty DB with lte query. Concurrent TX inserts data.
   * <pre>
   *   Snapshot (empty): P.lte(25) → 0
   *   Concurrent: insert age = {10, 20, 30}
   *   Snapshot: P.lte(25) → still 0
   *   Fresh TX: P.lte(25) → 2
   * </pre>
   */
  @Test
  public void lteNoVisibilityForTXBeforeAnyData_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(0, snap.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    assertEquals(0, snap.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(2, fresh.V().hasLabel("Userr").has("age", P.lte(25)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * ABA update pattern with P.gte(): move out then back in.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.gte(15) → 2  (20, 30)
   *   Concurrent TX1: update 20 → 5  (out)
   *   Concurrent TX2: update 5 → 20  (back in)
   *   Snapshot: P.gte(15) → still 2
   * </pre>
   */
  @Test
  public void gteABAUpdate_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());

    graph.tx().begin();
    graph.V(id20).property("age", 5).iterate();
    graph.tx().commit();

    graph.tx().begin();
    graph.V(id20).property("age", 20).iterate();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(2, fresh.V().hasLabel("Userr").has("age", P.gte(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Bulk inserts with P.lt(): many records inserted both inside and outside.
   * <pre>
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.lt(25) → 2  (10, 20)
   *   Concurrent: insert {1, 12, 22, 32, 50}
   *   Snapshot: P.lt(25) → still 2
   *   Fresh TX: P.lt(25) → 5  (1, 10, 12, 20, 22)
   * </pre>
   */
  @Test
  public void ltNoVisibilityForBulkInserts_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());

    graph.tx().begin();
    graph.addV("Userr").property("age", 1).next();
    graph.addV("Userr").property("age", 12).next();
    graph.addV("Userr").property("age", 22).next();
    graph.addV("Userr").property("age", 32).next();
    graph.addV("Userr").property("age", 50).next();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(5, fresh.V().hasLabel("Userr").has("age", P.lt(25)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  /**
   * Duplicate values with P.gt(): concurrent partial update of duplicates.
   * <pre>
   *   Initial: age = {20, 20, 20, 20}
   *   Snapshot: P.gt(15) → 4
   *   Concurrent: update two of them to 5 and 10 (below threshold)
   *   Snapshot: P.gt(15) → still 4
   *   Fresh TX: P.gt(15) → 2
   * </pre>
   */
  @Test
  public void gtDuplicateValuesPartialUpdate_NOTUNIQUEIndex() throws Exception {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    var v1 = graph.addV("Userr").property("age", 20).next();
    var v2 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 20).next();
    var id1 = v1.id();
    var id2 = v2.id();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(4, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());

    graph.tx().begin();
    graph.V(id1).property("age", 5).iterate();
    graph.V(id2).property("age", 10).iterate();
    graph.tx().commit();

    assertEquals(4, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(2, fresh.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  // ==========================================================================

}
