package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for ResultSet behavior including stream access and empty result set handling.
 */
public class ResultSetTest extends DbTestBase {

  @Test
  public void testResultStream() {
    var rs = new InternalResultSet(session);
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(session);
      item.setProperty("i", i);
      rs.add(item);
    }
    var result =
        rs.stream().map(x -> (int) x.getProperty("i")).reduce(Integer::sum);
    Assert.assertTrue(result.isPresent());
    Assert.assertEquals(45, result.get().intValue());
  }

  @Test(expected = IllegalStateException.class)
  public void testResultEmptyVertexStream() {
    var rs = new InternalResultSet(session);
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(session);
      item.setProperty("i", i);
      rs.add(item);
    }
    @SuppressWarnings("unused")
    var unused = rs.vertexStream().map(x -> (int) x.getProperty("i")).reduce(Integer::sum);
  }

  @Test(expected = IllegalStateException.class)
  public void testResultEdgeVertexStream() {
    var rs = new InternalResultSet(session);
    for (var i = 0; i < 10; i++) {
      var item = new ResultInternal(session);
      item.setProperty("i", i);
      rs.add(item);
    }
    @SuppressWarnings("unused")
    var unused = rs.vertexStream().map(x -> (int) x.getProperty("i")).reduce(Integer::sum);
  }

  @Test
  public void testResultSetAutoClose() {
    // Without result set auto close, the following code will cause OOM

    // disable warning
    withOverriddenConfig(GlobalConfiguration.QUERY_RESULT_SET_OPEN_WARNING_THRESHOLD, 100,
        session -> {

          final var clazz = session.getSchema().createClass("ResultSetTest_testAutoClose");
          for (var i = 0; i < 10; i++) {
            final var name = "foo" + i;
            session.executeInTx(tx -> tx.newEntity(clazz).setString("name", name));
          }

          session.executeInTx(tx -> {
            for (var i = 0; i < 5_000_000; i++) {
              tx.query("SELECT FROM " + clazz.getName());
            }
          });
        });
  }

  @Test
  public void testMultipleResultSetsNonTxMode() {
    session.command("CREATE CLASS XYZ;");

    final var rs1 = session.query("SELECT FROM XYZ;");
    final var rs2 = session.query("SELECT FROM XYZ;");

    rs1.close();

    rs2.toList();

  }

  /** Verify findFirstEntityOrNull throws NoSuchElementException on empty result set. */
  @Test(expected = NoSuchElementException.class)
  public void testFindFirstEntityOrNullThrowsOnEmpty() {
    session.command("CREATE CLASS EmptyResultSetTest;");
    try (var rs = session.query("SELECT FROM EmptyResultSetTest")) {
      rs.findFirstEntityOrNull(e -> e.getProperty("x"));
    }
  }

  /** Verify findFirstVertexOrNull throws NoSuchElementException on empty result set. */
  @Test(expected = NoSuchElementException.class)
  public void testFindFirstVertexOrNullThrowsOnEmpty() {
    session.createVertexClass("EmptyVertexTest");
    try (var rs = session.query("SELECT FROM EmptyVertexTest")) {
      rs.findFirstVertexOrNull(v -> v.getProperty("x"));
    }
  }

  /** Verify findFirstEdgeOrNull throws NoSuchElementException on empty result set. */
  @Test(expected = NoSuchElementException.class)
  public void testFindFirstEdgeOrNullThrowsOnEmpty() {
    session.createVertexClass("EmptyEdgeTestV");
    session.createEdgeClass("EmptyEdgeTestE");
    try (var rs = session.query("SELECT FROM EmptyEdgeTestE")) {
      rs.findFirstEdgeOrNull(e -> null);
    }
  }

  /**
   * Verify entityStream() Spliterator: exercises tryAdvance (true and false paths),
   * trySplit, and estimateSize directly to ensure full JaCoCo coverage of the
   * anonymous Spliterator class in the default interface method.
   */
  @Test
  public void testEntityStreamSpliterator() {
    session.createVertexClass("EntityStreamV");
    session.executeInTx(tx -> {
      tx.newVertex("EntityStreamV").setString("name", "a");
      tx.newVertex("EntityStreamV").setString("name", "b");
    });

    try (var rs = session.query("SELECT FROM EntityStreamV ORDER BY name")) {
      // Get the Spliterator directly to exercise all its methods
      Spliterator<Entity> spliterator = rs.entityStream().spliterator();

      // estimateSize on initial Spliterator
      Assert.assertEquals(Long.MAX_VALUE, spliterator.estimateSize());

      // trySplit should return null (not splittable)
      Assert.assertNull(spliterator.trySplit());

      // tryAdvance should return true for each element
      var entities = new ArrayList<Entity>();
      while (spliterator.tryAdvance(entities::add)) {
        // collecting elements
      }
      Assert.assertEquals(2, entities.size());
      Assert.assertEquals("a", entities.get(0).getString("name"));

      // tryAdvance after exhaustion should return false (this is the key coverage line)
      Assert.assertFalse(spliterator.tryAdvance(e -> Assert.fail("Should not be called")));
    }
  }

  /**
   * Verify vertexStream() Spliterator: exercises tryAdvance, trySplit, estimateSize directly.
   */
  @Test
  public void testVertexStreamSpliterator() {
    session.createVertexClass("VertexStreamV");
    session.executeInTx(tx -> {
      tx.newVertex("VertexStreamV").setString("name", "x");
      tx.newVertex("VertexStreamV").setString("name", "y");
    });

    try (var rs = session.query("SELECT FROM VertexStreamV ORDER BY name")) {
      Spliterator<Vertex> spliterator = rs.vertexStream().spliterator();

      Assert.assertEquals(Long.MAX_VALUE, spliterator.estimateSize());
      Assert.assertNull(spliterator.trySplit());

      var vertices = new ArrayList<Vertex>();
      while (spliterator.tryAdvance(vertices::add)) {
        // collecting
      }
      Assert.assertEquals(2, vertices.size());
      Assert.assertFalse(spliterator.tryAdvance(v -> Assert.fail("Should not be called")));
    }
  }

  /**
   * Verify edgeStream() Spliterator: exercises tryAdvance, trySplit, estimateSize directly.
   */
  @Test
  public void testEdgeStreamSpliterator() {
    session.createVertexClass("EdgeStreamV");
    session.createEdgeClass("EdgeStreamE");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("EdgeStreamV");
      var v2 = tx.newVertex("EdgeStreamV");
      v1.addEdge(v2, "EdgeStreamE").setString("label", "e1");
    });

    try (var rs = session.query("SELECT FROM EdgeStreamE")) {
      Spliterator<Edge> spliterator = rs.edgeStream().spliterator();

      Assert.assertEquals(Long.MAX_VALUE, spliterator.estimateSize());
      Assert.assertNull(spliterator.trySplit());

      var edges = new ArrayList<Edge>();
      while (spliterator.tryAdvance(edges::add)) {
        // collecting
      }
      Assert.assertEquals(1, edges.size());
      Assert.assertEquals("e1", edges.get(0).getString("label"));
      Assert.assertFalse(spliterator.tryAdvance(e -> Assert.fail("Should not be called")));
    }
  }

  /**
   * Verify ridStream() Spliterator: exercises tryAdvance, trySplit, estimateSize directly.
   */
  @Test
  public void testRidStreamSpliterator() {
    session.createVertexClass("RidStreamV");
    session.executeInTx(tx -> {
      tx.newVertex("RidStreamV");
      tx.newVertex("RidStreamV");
    });

    try (var rs = session.query("SELECT FROM RidStreamV")) {
      Spliterator<RID> spliterator = rs.ridStream().spliterator();

      Assert.assertEquals(Long.MAX_VALUE, spliterator.estimateSize());
      Assert.assertNull(spliterator.trySplit());

      var rids = new ArrayList<RID>();
      while (spliterator.tryAdvance(rids::add)) {
        // collecting
      }
      Assert.assertEquals(2, rids.size());
      for (var rid : rids) {
        Assert.assertTrue(rid.isPersistent());
      }
      Assert.assertFalse(spliterator.tryAdvance(r -> Assert.fail("Should not be called")));
    }
  }

  /**
   * Verify shortestPath function works with BOTH direction and edge type filter.
   * This exercises the getVerticesAndEdges BOTH path and the type-filtered BFS expansion
   * in SQLFunctionShortestPath.
   */
  @Test
  public void testShortestPathBothDirectionWithEdgeType() {
    session.createVertexClass("SPV");
    session.createEdgeClass("SPEdge");

    // Create a chain: v1 --SPEdge--> v2 --SPEdge--> v3
    var rids = session.computeInTx(tx -> {
      var v1 = tx.newVertex("SPV");
      v1.setString("name", "a");
      var v2 = tx.newVertex("SPV");
      v2.setString("name", "b");
      var v3 = tx.newVertex("SPV");
      v3.setString("name", "c");
      v1.addEdge(v2, "SPEdge");
      v2.addEdge(v3, "SPEdge");
      return new RID[] {v1.getIdentity(), v3.getIdentity()};
    });

    // shortestPath with BOTH direction and 'SPEdge' type filter
    try (var rs = session.query(
        "SELECT shortestPath(?, ?, 'BOTH', 'SPEdge') as path", rids[0], rids[1])) {
      Assert.assertTrue(rs.hasNext());
      var result = rs.next();
      @SuppressWarnings("unchecked")
      var path = (List<RID>) result.getProperty("path");
      Assert.assertNotNull(path);
      // Path should be v1 → v2 → v3 (3 vertices)
      Assert.assertEquals(3, path.size());
    }
  }

  /**
   * Verify out() SQL function on an edge record returns null (no traversal).
   * This covers the SQLFunctionMove.v2v "isEdge" branch returning null.
   */
  @Test
  public void testOutFunctionOnEdgeReturnsEmpty() {
    session.createVertexClass("OutFuncV");
    session.createEdgeClass("OutFuncE");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("OutFuncV");
      var v2 = tx.newVertex("OutFuncV");
      v1.addEdge(v2, "OutFuncE");
    });

    // out() on an edge record should return empty
    try (var rs = session.query(
        "SELECT out() as neighbors FROM OutFuncE")) {
      while (rs.hasNext()) {
        var result = rs.next();
        // out() on an edge returns null/empty
        var neighbors = result.getProperty("neighbors");
        // The result should be null or empty for edge records
        if (neighbors != null) {
          Assert.assertFalse("out() on edge should be empty",
              ((Iterable<?>) neighbors).iterator().hasNext());
        }
      }
    }
  }

  /**
   * Verify outE() SQL function on an edge record returns null.
   * This covers the SQLFunctionMove.v2e "else return null" branch (line 89).
   */
  @Test
  public void testOutEFunctionOnEdgeReturnsEmpty() {
    session.createVertexClass("OutEFuncV");
    session.createEdgeClass("OutEFuncE");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("OutEFuncV");
      var v2 = tx.newVertex("OutEFuncV");
      v1.addEdge(v2, "OutEFuncE");
    });

    // outE() on an edge record should return null (edges have no outgoing edges)
    try (var rs = session.query(
        "SELECT outE() as edges FROM OutEFuncE")) {
      while (rs.hasNext()) {
        var result = rs.next();
        var edges = result.getProperty("edges");
        // outE() on an edge should be null or empty
        Assert.assertTrue("outE() on edge should be null or empty",
            edges == null || !((Iterable<?>) edges).iterator().hasNext());
      }
    }
  }

  /**
   * Verify inE() SQL function works correctly on vertices.
   * This exercises the v2e method in SQLFunctionMove and edge iteration on the IN direction.
   */
  @Test
  public void testInEFunctionOnVertex() {
    session.createVertexClass("InEV");
    session.createEdgeClass("InEEdge");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("InEV");
      v1.setString("name", "target");
      var v2 = tx.newVertex("InEV");
      v2.setString("name", "source");
      v2.addEdge(v1, "InEEdge");
    });

    // inE() on a vertex should return incoming edges
    try (var rs = session.query(
        "SELECT inE('InEEdge') as edges FROM InEV WHERE name = 'target'")) {
      Assert.assertTrue(rs.hasNext());
      var result = rs.next();
      var edges = result.getProperty("edges");
      Assert.assertNotNull(edges);
    }
  }

  /**
   * Verify ResultInternal.asEdge() returns an edge when the result wraps an edge RID,
   * and asEdgeOrNull() returns null for a non-edge result.
   */
  @Test
  public void testResultInternalAsEdge() {
    session.createVertexClass("AsEdgeV");
    session.createEdgeClass("AsEdgeE");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("AsEdgeV");
      var v2 = tx.newVertex("AsEdgeV");
      v1.addEdge(v2, "AsEdgeE").setString("val", "test");
    });

    // Query edges — result wraps edge identity, asEdge() must load it
    try (var rs = session.query("SELECT FROM AsEdgeE")) {
      Assert.assertTrue(rs.hasNext());
      var result = rs.next();
      Edge edge = result.asEdge();
      Assert.assertNotNull(edge);
      Assert.assertEquals("test", edge.getString("val"));
    }

    // Query vertices — asEdgeOrNull() must return null
    try (var rs = session.query("SELECT FROM AsEdgeV")) {
      Assert.assertTrue(rs.hasNext());
      var result = rs.next();
      Assert.assertNull(result.asEdgeOrNull());
    }
  }

  /**
   * Verify ResultInternal.asEdge() works when wrapping a raw edge RID (not a loaded Edge).
   * This exercises the "isEdge() → asEntity().asEdge()" path in ResultInternal.
   */
  @Test
  public void testResultInternalAsEdgeFromRid() {
    session.createVertexClass("AsEdgeRidV");
    session.createEdgeClass("AsEdgeRidE");

    var edgeRid = session.computeInTx(tx -> {
      var v1 = tx.newVertex("AsEdgeRidV");
      var v2 = tx.newVertex("AsEdgeRidV");
      return v1.addEdge(v2, "AsEdgeRidE").getIdentity();
    });

    session.executeInTx(tx -> {
      // Create ResultInternal wrapping just the RID (not a loaded Edge)
      var result = new ResultInternal(session, edgeRid);

      // asEdge() should load the edge via isEdge() check
      Edge edge = result.asEdge();
      Assert.assertNotNull(edge);
      Assert.assertEquals(edgeRid, edge.getIdentity());

      // asEdgeOrNull() should also work
      Edge edgeOrNull = result.asEdgeOrNull();
      Assert.assertNotNull(edgeOrNull);
    });
  }

  /**
   * Verify MATCH query with edge class filter exercises FetchEdgesFromToVerticesStep
   * matchesClass and FetchEdgesToVerticesStep edges methods.
   */
  @Test
  public void testMatchEdgeQuery() {
    session.createVertexClass("MatchV");
    session.createEdgeClass("MatchE");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("MatchV");
      v1.setString("name", "a");
      var v2 = tx.newVertex("MatchV");
      v2.setString("name", "b");
      v1.addEdge(v2, "MatchE");
    });

    // MATCH with edge class — exercises FetchEdgesFromToVerticesStep with class filter
    try (var rs = session.query(
        "MATCH {class: MatchV, as: a, where: (name = 'a')} -MatchE-> "
            + "{class: MatchV, as: b} RETURN $pathElements")) {
      int count = 0;
      while (rs.hasNext()) {
        rs.next();
        count++;
      }
      Assert.assertTrue("MATCH should return path elements", count > 0);
    }
  }

  /**
   * Verify shortestPath function works with OUT direction and edge type filter.
   * This exercises the non-BOTH getVerticesAndEdges path and the type-filtered BFS,
   * covering lines 309, 377, 445 in SQLFunctionShortestPath.
   */
  @Test
  public void testShortestPathOutDirectionWithEdgeType() {
    session.createVertexClass("SPOutV");
    session.createEdgeClass("SPOutEdge");

    // Create a longer chain: v1 --SPOutEdge--> v2 --SPOutEdge--> v3 --SPOutEdge--> v4
    // Longer path ensures BFS expands both left and right queues with neighbor iteration
    var rids = session.computeInTx(tx -> {
      var v1 = tx.newVertex("SPOutV");
      var v2 = tx.newVertex("SPOutV");
      var v3 = tx.newVertex("SPOutV");
      var v4 = tx.newVertex("SPOutV");
      v1.addEdge(v2, "SPOutEdge");
      v2.addEdge(v3, "SPOutEdge");
      v3.addEdge(v4, "SPOutEdge");
      return new RID[] {v1.getIdentity(), v4.getIdentity()};
    });

    // shortestPath with OUT direction and 'SPOutEdge' type filter, using RID literals
    session.executeInTx(tx -> {
      try (var rs = tx.query(
          "SELECT shortestPath(" + rids[0] + ", " + rids[1]
              + ", 'OUT', 'SPOutEdge') as path")) {
        Assert.assertTrue(rs.hasNext());
        var result = rs.next();
        @SuppressWarnings("unchecked")
        var path = (List<RID>) result.getProperty("path");
        Assert.assertNotNull("shortestPath should return a non-null path", path);
        // Path: v1 → v2 → v3 → v4 (4 vertices)
        Assert.assertEquals(4, path.size());
      }
    });
  }

  /**
   * Verify out() SQL function on a plain entity (non-vertex, non-edge) returns null.
   * This covers the SQLFunctionMove.v2v "else return null" branch for plain entities (line 89).
   */
  @Test
  public void testOutFunctionOnPlainEntityReturnsNull() {
    session.command("CREATE CLASS PlainOutFuncCls;");
    session.executeInTx(tx -> tx.newEntity("PlainOutFuncCls").setString("name", "x"));

    try (var rs = session.query("SELECT out() as neighbors FROM PlainOutFuncCls")) {
      while (rs.hasNext()) {
        var result = rs.next();
        var neighbors = result.getProperty("neighbors");
        // out() on a plain entity should be null — it's not a vertex
        Assert.assertNull("out() on plain entity should be null", neighbors);
      }
    }
  }

  /**
   * Verify outE() SQL function on a plain entity returns null.
   * This covers the SQLFunctionMove.v2e non-vertex branch for plain entities.
   */
  @Test
  public void testOutEFunctionOnPlainEntityReturnsNull() {
    session.command("CREATE CLASS PlainOutEFuncCls;");
    session.executeInTx(tx -> tx.newEntity("PlainOutEFuncCls").setString("name", "y"));

    try (var rs = session.query("SELECT outE() as edges FROM PlainOutEFuncCls")) {
      while (rs.hasNext()) {
        var result = rs.next();
        var edges = result.getProperty("edges");
        Assert.assertNull("outE() on plain entity should be null", edges);
      }
    }
  }

  /**
   * Verify ResultInternal.asEdgeOrNull() resolves to an edge when the result wraps a raw
   * edge RID, without calling asEdge() first. This ensures the isEdge() → asEntity() path
   * in asEdgeOrNull (line 990) is exercised independently.
   */
  @Test
  public void testResultInternalAsEdgeOrNullOnlyFromRid() {
    session.createVertexClass("AsEdgeOrNullV");
    session.createEdgeClass("AsEdgeOrNullE");

    var edgeRid = session.computeInTx(tx -> {
      var v1 = tx.newVertex("AsEdgeOrNullV");
      var v2 = tx.newVertex("AsEdgeOrNullV");
      return v1.addEdge(v2, "AsEdgeOrNullE").getIdentity();
    });

    session.executeInTx(tx -> {
      // Create a fresh ResultInternal wrapping only the RID — call ONLY asEdgeOrNull
      var result = new ResultInternal(session, edgeRid);
      Edge edgeOrNull = result.asEdgeOrNull();
      Assert.assertNotNull("asEdgeOrNull() on edge RID should return edge", edgeOrNull);
      Assert.assertEquals(edgeRid, edgeOrNull.getIdentity());
    });
  }

  /**
   * Verify astar() function with edge weight properties.
   * This exercises the getDistance weight-reading branches in SQLFunctionAstar.
   */
  @Test
  public void testAstarWithWeightProperty() {
    session.createVertexClass("AstarV");
    session.createEdgeClass("AstarE");

    // Create a graph: v1 --AstarE(weight=1.5)--> v2 --AstarE(weight=2.0)--> v3
    var rids = session.computeInTx(tx -> {
      var v1 = tx.newVertex("AstarV");
      v1.setFloat("lat", 0.0f);
      v1.setFloat("lon", 0.0f);
      var v2 = tx.newVertex("AstarV");
      v2.setFloat("lat", 1.0f);
      v2.setFloat("lon", 0.0f);
      var v3 = tx.newVertex("AstarV");
      v3.setFloat("lat", 2.0f);
      v3.setFloat("lon", 0.0f);

      v1.addEdge(v2, "AstarE").setFloat("weight", 1.5f);
      v2.addEdge(v3, "AstarE").setFloat("weight", 2.0f);
      return new RID[] {v1.getIdentity(), v3.getIdentity()};
    });

    // astar() with weight field and heuristic function
    session.executeInTx(tx -> {
      try (var rs = tx.query(
          "SELECT astar(" + rids[0] + ", " + rids[1]
              + ", 'AstarE', {weightFieldName: 'weight'}) as path")) {
        Assert.assertTrue(rs.hasNext());
        var result = rs.next();
        @SuppressWarnings("unchecked")
        var path = (List<RID>) result.getProperty("path");
        Assert.assertNotNull("astar should return a non-null path", path);
        Assert.assertTrue("Path should have at least 2 vertices", path.size() >= 2);
      }
    });
  }

}
