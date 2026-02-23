package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.HashMap;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for ResultToGremlinConverter: empty result throws, single null throws, single vertex/edge
 * returns Gremlin element, projection returns Map, non-entity value throws, Identifiable loaded
 * via session.
 */
public class ResultToGremlinConverterTest extends GraphBaseTest {

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_emptyPropertyNames_throws() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var result = new ResultInternal(session, Map.of());
    ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_singleNullValue_throws() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var map = new HashMap<String, Object>();
    map.put("a", null);
    var result = new ResultInternal(session, map);
    ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
  }

  @Test
  public void toGremlin_singleVertex_returnsGremlinVertex() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v = graph.traversal().addV("V").property("name", "X").next();
    var rawVertex = ((YTDBVertexImpl) v).getRawEntity();
    var result = new ResultInternal(session, Map.of("a", rawVertex));
    var out = ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
    Assert.assertTrue(out instanceof Vertex);
    Assert.assertEquals("X", ((Vertex) out).value("name"));
  }

  @Test
  public void toGremlin_projectionTwoKeys_returnsMap() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v1 = graph.traversal().addV("V").property("name", "A").next();
    var v2 = graph.traversal().addV("V").property("name", "B").next();
    var raw1 = ((YTDBVertexImpl) v1).getRawEntity();
    var raw2 = ((YTDBVertexImpl) v2).getRawEntity();
    var result = new ResultInternal(session, Map.of("a", raw1, "b", raw2));
    var out = ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
    Assert.assertTrue(out instanceof Map);
    @SuppressWarnings("unchecked")
    var map = (Map<String, Object>) out;
    Assert.assertEquals(2, map.size());
    Assert.assertTrue(map.get("a") instanceof Vertex);
    Assert.assertTrue(map.get("b") instanceof Vertex);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_singleValueNotVertexOrEdge_throws() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var result = new ResultInternal(session, Map.of("a", "not a vertex"));
    ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
  }

  @Test
  public void toGremlin_projectionWithNullValue_includesNull() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v = graph.traversal().addV("V").next();
    var raw = ((YTDBVertexImpl) v).getRawEntity();
    var map = new HashMap<String, Object>();
    map.put("a", raw);
    map.put("b", null);
    var result = new ResultInternal(session, map);
    var out = ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
    Assert.assertTrue(out instanceof Map);
    @SuppressWarnings("unchecked")
    var outMap = (Map<String, Object>) out;
    Assert.assertEquals(2, outMap.size());
    Assert.assertTrue(outMap.get("a") instanceof Vertex);
    Assert.assertNull(outMap.get("b"));
  }

  @Test
  public void toGremlin_singleIdentifiable_loadsViaSessionAndReturnsVertex() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v = graph.traversal().addV("V").property("name", "RID").next();
    var rid = ((YTDBVertexImpl) v).getRawEntity().getIdentity();
    var result = new ResultInternal(session, Map.of("a", rid));
    var out = ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
    Assert.assertTrue(out instanceof Vertex);
    Assert.assertEquals("RID", ((Vertex) out).value("name"));
  }

  @Test
  public void toGremlin_singleEdge_returnsGremlinEdge() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v1 = graph.traversal().addV("V").next();
    var v2 = graph.traversal().addV("V").next();
    var e = graph.traversal().addE("E").from(v1).to(v2).next();
    var edgeRid = ((YTDBStatefulEdgeImpl) e).getRawEntity().getIdentity();
    var result = new ResultInternal(session, Map.of("e", edgeRid));
    var out = ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
    Assert.assertTrue(out instanceof org.apache.tinkerpop.gremlin.structure.Edge);
  }

  @Test
  public void toGremlin_projectionWithEdge_returnsMapWithEdge() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v1 = graph.traversal().addV("V").next();
    var v2 = graph.traversal().addV("V").next();
    var e = graph.traversal().addE("E").from(v1).to(v2).next();
    var rawEdge = ((YTDBStatefulEdgeImpl) e).getRawEntity();
    var rawV = ((YTDBVertexImpl) v1).getRawEntity();
    var result = new ResultInternal(session, Map.of("v", rawV, "e", rawEdge));
    var out = ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
    Assert.assertTrue(out instanceof Map);
    @SuppressWarnings("unchecked")
    var map = (Map<String, Object>) out;
    Assert.assertTrue(map.get("v") instanceof org.apache.tinkerpop.gremlin.structure.Vertex);
    Assert.assertTrue(map.get("e") instanceof org.apache.tinkerpop.gremlin.structure.Edge);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_projectionWithNonEntityValue_throws() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v = graph.traversal().addV("V").next();
    var raw = ((YTDBVertexImpl) v).getRawEntity();
    var result = new ResultInternal(session, Map.of("a", raw, "b", "plainString"));
    ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
  }

  @Test
  public void toGremlin_projectionWithEdgeIdentifiable_loadsAndReturnsEdge() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    var v1 = graph.traversal().addV("V").next();
    var v2 = graph.traversal().addV("V").next();
    var e = graph.traversal().addE("E").from(v1).to(v2).next();
    var edgeRid = ((YTDBStatefulEdgeImpl) e).getRawEntity().getIdentity();
    var rawV = ((YTDBVertexImpl) v1).getRawEntity();
    var result = new ResultInternal(session, Map.of("v", rawV, "e", edgeRid));
    var out = ResultToGremlinConverter.toGremlin(result, (YTDBGraphInternal) graph, session);
    Assert.assertTrue(out instanceof Map);
    @SuppressWarnings("unchecked")
    var map = (Map<String, Object>) out;
    Assert.assertTrue(map.get("e") instanceof org.apache.tinkerpop.gremlin.structure.Edge);
  }
}
