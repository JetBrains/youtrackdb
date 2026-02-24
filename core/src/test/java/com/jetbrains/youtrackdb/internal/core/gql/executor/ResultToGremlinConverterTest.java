package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBStatefulEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBVertexImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for ResultToGremlinConverter covering every branch:
 * - null args → NPE
 * - empty result → UOE
 * - single binding: null, vertex Entity, vertex RID, edge Entity, edge RID, non-entity
 * - projection: two vertices, vertex+edge, null value, non-entity, edge via RID, vertex via RID
 * - property value verification to kill mutations that swap or omit return values
 */
public class ResultToGremlinConverterTest extends GraphBaseTest {

  private DatabaseSessionEmbedded openSession() {
    var tx = ((YTDBGraphInternal) graph).tx();
    tx.readWrite();
    return tx.getDatabaseSession();
  }

  private YTDBGraphInternal gi() {
    return (YTDBGraphInternal) graph;
  }

  // ── Null argument guards ──

  @Test(expected = NullPointerException.class)
  public void toGremlin_nullResult_throwsNPE() {
    var session = openSession();
    ResultToGremlinConverter.toGremlin(null, gi(), session);
  }

  @Test(expected = NullPointerException.class)
  public void toGremlin_nullGraph_throwsNPE() {
    var session = openSession();
    var result = new ResultInternal(session, Map.of("a", "x"));
    ResultToGremlinConverter.toGremlin(result, null, session);
  }

  @Test(expected = NullPointerException.class)
  public void toGremlin_nullSession_throwsNPE() {
    var session = openSession();
    var result = new ResultInternal(session, Map.of("a", "x"));
    ResultToGremlinConverter.toGremlin(result, gi(), null);
  }

  // ── Empty result ──

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_emptyPropertyNames_throwsUOE() {
    var session = openSession();
    var result = new ResultInternal(session, Map.of());
    ResultToGremlinConverter.toGremlin(result, gi(), session);
  }

  // ── Single binding: null value ──

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_singleNullValue_throwsUOE() {
    var session = openSession();
    var map = new HashMap<String, Object>();
    map.put("a", null);
    var result = new ResultInternal(session, map);
    ResultToGremlinConverter.toGremlin(result, gi(), session);
  }

  // ── Single binding: vertex as Entity ──

  @Test
  public void toGremlin_singleVertexEntity_returnsVertexWithProperties() {
    var session = openSession();
    var v = graph.traversal().addV("ConvV").property("name", "Ent").next();
    var rawVertex = ((YTDBVertexImpl) v).getRawEntity();
    var result = new ResultInternal(session, Map.of("a", rawVertex));

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Vertex);
    Assert.assertEquals("ConvV", ((Vertex) out).label());
    Assert.assertEquals("Ent", ((Vertex) out).value("name"));
  }

  // ── Single binding: vertex as RID (Identifiable) ──

  @Test
  public void toGremlin_singleVertexRid_loadsAndReturnsVertex() {
    var session = openSession();
    var v = graph.traversal().addV("ConvV").property("name", "Rid").next();
    var rid = ((YTDBVertexImpl) v).getRawEntity().getIdentity();
    var result = new ResultInternal(session, Map.of("a", rid));

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Vertex);
    Assert.assertEquals("Rid", ((Vertex) out).value("name"));
  }

  // ── Single binding: edge as Entity ──

  @Test
  public void toGremlin_singleEdgeEntity_returnsEdgeWithLabel() {
    var session = openSession();
    var v1 = graph.traversal().addV("ConvV").next();
    var v2 = graph.traversal().addV("ConvV").next();
    var e = graph.traversal().addE("ConvE").from(v1).to(v2).next();
    var rawEdge = ((YTDBStatefulEdgeImpl) e).getRawEntity();
    var result = new ResultInternal(session, Map.of("e", rawEdge));

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Edge);
    Assert.assertEquals("ConvE", ((Edge) out).label());
  }

  // ── Single binding: edge as RID ──

  @Test
  public void toGremlin_singleEdgeRid_loadsAndReturnsEdge() {
    var session = openSession();
    var v1 = graph.traversal().addV("ConvV").next();
    var v2 = graph.traversal().addV("ConvV").next();
    var e = graph.traversal().addE("ConvRidE").from(v1).to(v2).next();
    var edgeRid = ((YTDBStatefulEdgeImpl) e).getRawEntity().getIdentity();
    var result = new ResultInternal(session, Map.of("e", edgeRid));

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Edge);
    Assert.assertEquals("ConvRidE", ((Edge) out).label());
  }

  // ── Single binding: non-entity ──

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_singleStringValue_throwsUOE() {
    var session = openSession();
    var result = new ResultInternal(session, Map.of("a", "not a vertex"));
    ResultToGremlinConverter.toGremlin(result, gi(), session);
  }

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_singleIntegerValue_throwsUOE() {
    var session = openSession();
    var result = new ResultInternal(session, Map.of("a", 42));
    ResultToGremlinConverter.toGremlin(result, gi(), session);
  }

  // ── Projection: two vertices with value verification ──

  @SuppressWarnings("unchecked")
  @Test
  public void toGremlin_projectionTwoVertices_returnsMapWithCorrectValues() {
    var session = openSession();
    var v1 = graph.traversal().addV("ConvV").property("name", "PA").next();
    var v2 = graph.traversal().addV("ConvV").property("name", "PB").next();
    var raw1 = ((YTDBVertexImpl) v1).getRawEntity();
    var raw2 = ((YTDBVertexImpl) v2).getRawEntity();
    var props = new LinkedHashMap<String, Object>();
    props.put("a", raw1);
    props.put("b", raw2);
    var result = new ResultInternal(session, props);

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Map);
    var map = (Map<String, Object>) out;
    Assert.assertEquals(2, map.size());
    Assert.assertEquals("PA", ((Vertex) map.get("a")).value("name"));
    Assert.assertEquals("PB", ((Vertex) map.get("b")).value("name"));
  }

  // ── Projection: vertex + edge ──

  @SuppressWarnings("unchecked")
  @Test
  public void toGremlin_projectionVertexAndEdge_returnsMapWithBothTypes() {
    var session = openSession();
    var v1 = graph.traversal().addV("ConvV").property("name", "MixV").next();
    var v2 = graph.traversal().addV("ConvV").next();
    var e = graph.traversal().addE("MixE").from(v1).to(v2).next();
    var rawV = ((YTDBVertexImpl) v1).getRawEntity();
    var rawE = ((YTDBStatefulEdgeImpl) e).getRawEntity();
    var result = new ResultInternal(session, Map.of("v", rawV, "e", rawE));

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Map);
    var map = (Map<String, Object>) out;
    Assert.assertEquals("MixV", ((Vertex) map.get("v")).value("name"));
    Assert.assertEquals("MixE", ((Edge) map.get("e")).label());
  }

  // ── Projection: null value in multi-binding ──

  @SuppressWarnings("unchecked")
  @Test
  public void toGremlin_projectionWithNullValue_includesNullInMap() {
    var session = openSession();
    var v = graph.traversal().addV("ConvV").property("name", "NP").next();
    var raw = ((YTDBVertexImpl) v).getRawEntity();
    var map = new HashMap<String, Object>();
    map.put("a", raw);
    map.put("b", null);
    var result = new ResultInternal(session, map);

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Map);
    var outMap = (Map<String, Object>) out;
    Assert.assertEquals(2, outMap.size());
    Assert.assertTrue(outMap.get("a") instanceof Vertex);
    Assert.assertNull(outMap.get("b"));
  }

  // ── Projection: non-entity value ──

  @Test(expected = UnsupportedOperationException.class)
  public void toGremlin_projectionWithStringValue_throwsUOE() {
    var session = openSession();
    var v = graph.traversal().addV("ConvV").next();
    var raw = ((YTDBVertexImpl) v).getRawEntity();
    var result = new ResultInternal(session, Map.of("a", raw, "b", "plainString"));
    ResultToGremlinConverter.toGremlin(result, gi(), session);
  }

  // ── Projection: edge via RID ──

  @SuppressWarnings("unchecked")
  @Test
  public void toGremlin_projectionWithEdgeRid_loadsEdgeInMap() {
    var session = openSession();
    var v1 = graph.traversal().addV("ConvV").next();
    var v2 = graph.traversal().addV("ConvV").next();
    var e = graph.traversal().addE("PRidE").from(v1).to(v2).next();
    var edgeRid = ((YTDBStatefulEdgeImpl) e).getRawEntity().getIdentity();
    var rawV = ((YTDBVertexImpl) v1).getRawEntity();
    var result = new ResultInternal(session, Map.of("v", rawV, "e", edgeRid));

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Map);
    var map = (Map<String, Object>) out;
    Assert.assertTrue(map.get("e") instanceof Edge);
    Assert.assertEquals("PRidE", ((Edge) map.get("e")).label());
  }

  // ── Projection: vertex via RID ──

  @SuppressWarnings("unchecked")
  @Test
  public void toGremlin_projectionWithVertexRid_loadsVertexInMap() {
    var session = openSession();
    var v1 = graph.traversal().addV("ConvV").property("name", "R1").next();
    var v2 = graph.traversal().addV("ConvV").property("name", "R2").next();
    var rid1 = ((YTDBVertexImpl) v1).getRawEntity().getIdentity();
    var rid2 = ((YTDBVertexImpl) v2).getRawEntity().getIdentity();
    var result = new ResultInternal(session, Map.of("a", rid1, "b", rid2));

    var out = ResultToGremlinConverter.toGremlin(result, gi(), session);

    Assert.assertTrue(out instanceof Map);
    var map = (Map<String, Object>) out;
    Assert.assertEquals("R1", ((Vertex) map.get("a")).value("name"));
    Assert.assertEquals("R2", ((Vertex) map.get("b")).value("name"));
  }
}
