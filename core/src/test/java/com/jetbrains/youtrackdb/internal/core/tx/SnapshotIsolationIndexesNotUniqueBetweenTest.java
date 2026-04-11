
package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.gremlin.YTDBGraphTraversalSource;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Snapshot-isolation tests for {@code P.between()} range queries with NOTUNIQUE indexes.
 * Exercises {@code BTreeMultiValueIndexEngine.iterateEntriesBetween} via Gremlin traversals.
 * Mirrors {@link SnapshotIsolationIndexesUniqueBetweenTest} for the NOTUNIQUE engine path.
 */
public class SnapshotIsolationIndexesNotUniqueBetweenTest {
  //
  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB = DbTestBase.createYTDBManagerAndDb("test", DatabaseType.MEMORY, getClass());
    db = youTrackDB.open("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  // ==========================================================================
  // P.between() range-query snapshot isolation tests with NOTUNIQUE index
  // ==========================================================================

  /*
   * Scenario: snapshot TX does a range scan with P.between(). A concurrent TX
   * inserts a new record whose value falls inside the range (phantom).
   * The snapshot must NOT see the phantom.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → 3
   *   Concurrent: insert age=25 (phantom candidate)
   *   Snapshot: P.between(5, 35) → still 3
   *   Fresh TX: P.between(5, 35) → 4
   */
  @Test
  public void rangeQueryNoPhantom_NOTUNIQUEIndex() throws Exception {

    // Schema with NOTUNIQUE index on INTEGER property
    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    // Seed data: 10, 20, 30, 40
    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.addV("Userr").property("age", 40).next();
    graph.tx().commit();

    // Snapshot TX: range [5, 35)
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5))
        .has("age", P.lt(35))
        .toList();
    assertEquals(3, before.size());

    // Concurrent TX: insert phantom age=25 (inside the range)
    graph.tx().begin();
    graph.addV("Userr").property("age", 25).next();
    graph.tx().commit();

    // Snapshot must still see 3
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX sees 4
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(4, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX does a range scan. A concurrent TX updates a record
   * so its value moves INTO the range from outside.
   *
   *   Initial: age = {10, 20, 50}
   *   Snapshot: P.between(5, 35) → 2 (10, 20)
   *   Concurrent: update 50 → 15 (now inside range)
   *   Snapshot: P.between(5, 35) → still 2
   *   Fresh TX: P.between(5, 35) → 3
   */
  @Test
  public void rangeQueryNoVisibilityForUpdateIntoRange_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    var outside = graph.addV("Userr").property("age", 50).next();
    var outsideId = outside.id();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(2, before.size());

    // Concurrent TX: move record from outside range into range
    graph.tx().begin();
    graph.V(outsideId).property("age", 15).iterate();
    graph.tx().commit();

    // Snapshot still sees 2
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(2, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX sees 3
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX does a range scan. A concurrent TX updates a record
   * so its value moves OUT OF the range.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → 3
   *   Concurrent: update 20 → 50 (now outside range)
   *   Snapshot: P.between(5, 35) → still 3
   *   Fresh TX: P.between(5, 35) → 2
   */
  @Test
  public void rangeQueryNoVisibilityForUpdateOutOfRange_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var middle = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var middleId = middle.id();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, before.size());

    // Concurrent TX: move record out of range
    graph.tx().begin();
    graph.V(middleId).property("age", 50).iterate();
    graph.tx().commit();

    // Snapshot still sees 3
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX sees 2
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(2, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX does a range scan. A concurrent TX deletes a record
   * that is inside the range.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → 3
   *   Concurrent: delete record with age=20
   *   Snapshot: P.between(5, 35) → still 3
   *   Fresh TX: P.between(5, 35) → 2
   */
  @Test
  public void rangeQueryNoVisibilityForDelete_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var toDelete = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var deleteId = toDelete.id();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, before.size());

    // Concurrent TX: delete from inside range
    graph.tx().begin();
    graph.V(deleteId).drop().iterate();
    graph.tx().commit();

    // Snapshot still sees 3
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX sees 2
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(2, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX does a range scan. A concurrent TX performs mixed
   * operations: inserts a phantom, updates one record out, deletes another.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → 3
   *   Concurrent: insert age=25, update 30→50, delete age=10
   *   Snapshot: P.between(5, 35) → still 3
   *   Fresh TX: P.between(5, 35) → 2  (20 + 25)
   */
  @Test
  public void rangeQueryNoVisibilityForMixedChanges_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    var v10 = graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    var v30 = graph.addV("Userr").property("age", 30).next();
    var id10 = v10.id();
    var id30 = v30.id();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, before.size());

    // Concurrent TX: insert phantom, update one out of range, delete one
    graph.tx().begin();
    graph.addV("Userr").property("age", 25).next(); // phantom into range
    graph.V(id30).property("age", 50).iterate(); // move out of range
    graph.V(id10).drop().iterate(); // delete from range
    graph.tx().commit();

    // Snapshot still sees original 3
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX: 20 (untouched) + 25 (new) = 2
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(2, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: two snapshots started at different points see different range
   * query results, despite further concurrent modifications.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot1: P.between(5, 35) → 3
   *   Concurrent TX1: insert age=15
   *   Snapshot2: P.between(5, 35) → 4
   *   Concurrent TX2: delete age=20
   *   Snapshot1: still 3
   *   Snapshot2: still 4
   *   Fresh TX: P.between(5, 35) → 3  (10, 15, 30)
   */
  @Test
  public void rangeQueryTwoSnapshotsSeeDifferentStates_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    // Snapshot1: sees 3
    var snapshot1 = openGraph();
    snapshot1.tx().begin();
    var snap1Before = snapshot1
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, snap1Before.size());

    // Concurrent TX1: insert age=15
    graph.tx().begin();
    graph.addV("Userr").property("age", 15).next();
    graph.tx().commit();

    // Snapshot2: started after insert, sees 4
    var snapshot2 = openGraph();
    snapshot2.tx().begin();
    var snap2Before = snapshot2
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(4, snap2Before.size());

    // Concurrent TX2: delete age=20
    graph.tx().begin();
    graph.V(id20).drop().iterate();
    graph.tx().commit();

    // Snapshot1 still sees 3
    var snap1After = snapshot1
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, snap1After.size());

    // Snapshot2 still sees 4
    var snap2After = snapshot2
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(4, snap2After.size());

    snapshot1.tx().commit();
    snapshot2.tx().commit();

    // Fresh TX: 10, 15, 30 = 3
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshot1.close();
    snapshot2.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX reads a range. A concurrent TX performs ABA on a value
   * (moves it out of range, then back in). The snapshot must be unaffected.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → 3
   *   Concurrent TX1: update 20 → 50 (out of range)
   *   Concurrent TX2: update 50 → 20 (back in range)
   *   Snapshot: P.between(5, 35) → still 3
   */
  @Test
  public void rangeQueryABAUpdate_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, before.size());

    // Concurrent TX1: move 20 out of range → 50
    graph.tx().begin();
    graph.V(id20).property("age", 50).iterate();
    graph.tx().commit();

    // Concurrent TX2: move it back → 20
    graph.tx().begin();
    graph.V(id20).property("age", 20).iterate();
    graph.tx().commit();

    // Snapshot still sees original 3
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX also sees 3 (back to original state)
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX reads with P.between(). Concurrent TX inserts many
   * records both inside and outside the range. Snapshot sees only its original set.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → 3
   *   Concurrent: insert age = {1, 12, 22, 32, 50}
   *   Snapshot: P.between(5, 35) → still 3
   *   Fresh TX: P.between(5, 35) → 5  (10, 12, 20, 22, 30)
   */
  @Test
  public void rangeQueryNoVisibilityForBulkInserts_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, before.size());

    // Concurrent TX: bulk insert inside and outside range
    graph.tx().begin();
    graph.addV("Userr").property("age", 1).next(); // outside [5, 35)
    graph.addV("Userr").property("age", 12).next(); // inside
    graph.addV("Userr").property("age", 22).next(); // inside
    graph.addV("Userr").property("age", 32).next(); // inside
    graph.addV("Userr").property("age", 50).next(); // outside
    graph.tx().commit();

    // Snapshot still sees 3
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX: 10, 12, 20, 22, 30, 32 = 6 in [5, 35)
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(6, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX with a range query started BEFORE any data exists.
   * A concurrent TX inserts records. The snapshot must see 0.
   *
   *   Snapshot (empty DB): P.between(5, 35) → 0
   *   Concurrent: insert age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → still 0
   *   Fresh TX: P.between(5, 35) → 3
   */
  @Test
  public void rangeQueryNoVisibilityForTXBeforeAnyData_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    // Snapshot started on empty DB
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(0, before.size());

    // Concurrent TX: insert data
    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    // Snapshot still sees 0
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(0, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX sees 3
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX with a range query. Concurrent TX updates a record
   * that is inside the range so it stays inside the range but with a different
   * value. The snapshot must still see the original value.
   *
   *   Initial: age = {10, 20, 30}
   *   Snapshot: P.between(5, 35) → 3
   *   Concurrent: update 20 → 25 (still in range)
   *   Snapshot: P.between(5, 35) → still 3
   *   Fresh TX: P.between(5, 35) → 3 (10, 25, 30)
   */
  @Test
  public void rangeQueryNoVisibilityForUpdateWithinRange_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    var v20 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    var id20 = v20.id();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, before.size());

    // Also verify the exact value 20 is present
    var exact20 = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", 20)
        .toList();
    assertEquals(1, exact20.size());

    // Concurrent TX: update 20 → 25 (stays in range)
    graph.tx().begin();
    graph.V(id20).property("age", 25).iterate();
    graph.tx().commit();

    // Snapshot: range count unchanged
    var afterRange = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, afterRange.size());

    // Snapshot: still sees age=20, does NOT see age=25
    var afterExact20 = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", 20)
        .toList();
    assertEquals(1, afterExact20.size());

    var afterExact25 = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", 25)
        .toList();
    assertEquals(0, afterExact25.size());

    snapshotGraph.tx().commit();

    // Fresh TX sees updated state
    var newGraph = openGraph();
    newGraph.tx().begin();
    var freshRange = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(5)).has("age", P.lt(35))
        .toList();
    assertEquals(3, freshRange.size());

    var fresh20 = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", 20)
        .toList();
    assertEquals(0, fresh20.size());

    var fresh25 = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", 25)
        .toList();
    assertEquals(1, fresh25.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  /*
   * Scenario: snapshot TX reads with P.between() on duplicate values.
   * A concurrent TX modifies some duplicates. Snapshot is unaffected.
   *
   *   Initial: age = {20, 20, 20, 20}
   *   Snapshot: P.between(15, 25) → 4
   *   Concurrent: update two of them to 50 and 10
   *   Snapshot: P.between(15, 25) → still 4
   *   Fresh TX: P.between(15, 25) → 2
   */
  @Test
  public void rangeQueryDuplicateValuesPartialUpdate_NOTUNIQUEIndex() throws Exception {

    SchemaClass userSchema = db.createVertexClass("Userr");
    userSchema.createProperty("age", PropertyType.INTEGER);
    userSchema.createIndex("IndexAge", INDEX_TYPE.NOTUNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    var v1 = graph.addV("Userr").property("age", 20).next();
    var v2 = graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 20).next();
    var id1 = v1.id();
    var id2 = v2.id();
    graph.tx().commit();

    // Snapshot TX
    var snapshotGraph = openGraph();
    snapshotGraph.tx().begin();
    var before = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(15)).has("age", P.lt(25))
        .toList();
    assertEquals(4, before.size());

    // Concurrent TX: update two duplicates out of range
    graph.tx().begin();
    graph.V(id1).property("age", 50).iterate();
    graph.V(id2).property("age", 10).iterate();
    graph.tx().commit();

    // Snapshot still sees 4
    var after = snapshotGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(15)).has("age", P.lt(25))
        .toList();
    assertEquals(4, after.size());
    snapshotGraph.tx().commit();

    // Fresh TX: only 2 remain in range
    var newGraph = openGraph();
    newGraph.tx().begin();
    var all = newGraph
        .V()
        .hasLabel("Userr")
        .has("age", P.gte(15)).has("age", P.lt(25))
        .toList();
    assertEquals(2, all.size());
    newGraph.tx().commit();

    newGraph.close();
    snapshotGraph.close();
    graph.close();
  }

  private YTDBGraphTraversalSource openGraph() {
    return youTrackDB.openTraversal("test", "admin", DbTestBase.ADMIN_PASSWORD);
  }

  @After
  public void after() {
    db.close();
    youTrackDB.close();
  }
}
