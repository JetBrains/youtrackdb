package com.jetbrains.youtrackdb.internal.core.tx;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import org.apache.tinkerpop.gremlin.process.traversal.P;
import org.junit.Test;

/**
 * Snapshot-isolation tests for BTreeSingleValueIndexEngine using single-sided range predicates
 * with UNIQUE indexes via Gremlin traversals.
 *
 * <p>These tests mirror {@link SnapshotIsolationIndexesNotUniqueSingleSidedRangeTest} but use
 * {@code INDEX_TYPE.UNIQUE} to exercise {@code BTreeSingleValueIndexEngine.iterateEntriesBetween}
 * with one-sided ranges (null from/to).
 */
public class SnapshotIsolationIndexesUniqueSingleSidedRangeTest
    extends SnapshotIsolationIndexesTestBase {

  // ==========================================================================
  //  P.gt()  —  major scan (exclusive)
  // ==========================================================================

  @Test
  public void gtNoPhantom_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

    var graph = openGraph();
    graph.tx().begin();
    graph.addV("Userr").property("age", 10).next();
    graph.addV("Userr").property("age", 20).next();
    graph.addV("Userr").property("age", 30).next();
    graph.tx().commit();

    var snap = openGraph();
    snap.tx().begin();
    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());

    graph.tx().begin();
    graph.addV("Userr").property("age", 25).next();
    graph.tx().commit();

    assertEquals(2, snap.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    snap.tx().commit();

    var fresh = openGraph();
    fresh.tx().begin();
    assertEquals(3, fresh.V().hasLabel("Userr").has("age", P.gt(15)).toList().size());
    fresh.tx().commit();

    fresh.close();
    snap.close();
    graph.close();
  }

  @Test
  public void gtNoVisibilityForDelete_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  @Test
  public void gtNoVisibilityForUpdateIntoRange_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  @Test
  public void gtNoVisibilityForUpdateOutOfRange_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  @Test
  public void gteNoPhantomAtBoundary_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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
    graph.addV("Userr").property("age", 25).next();
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

  @Test
  public void gteABAUpdate_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  // ==========================================================================
  //  P.lt()  —  minor scan (exclusive)
  // ==========================================================================

  @Test
  public void ltNoPhantom_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  @Test
  public void ltNoVisibilityForDelete_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  @Test
  public void ltNoVisibilityForUpdateIntoRange_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  @Test
  public void ltNoVisibilityForUpdateOutOfRange_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  @Test
  public void lteNoPhantomAtBoundary_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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
    graph.addV("Userr").property("age", 15).next();
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

  @Test
  public void lteNoVisibilityForTXBeforeAnyData_UNIQUEIndex() {
    SchemaClass schema = db.createVertexClass("Userr");
    schema.createProperty("age", PropertyType.INTEGER);
    schema.createIndex("IndexAge", INDEX_TYPE.UNIQUE, "age");

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

  // ==========================================================================

}
