package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the vertex-to-edge dispatchers ({@link SQLFunctionOutE}, {@link SQLFunctionInE},
 * {@link SQLFunctionBothE}) and the edge-to-vertex dispatchers ({@link SQLFunctionOutV},
 * {@link SQLFunctionInV}, {@link SQLFunctionBothV}).
 *
 * <p>The tests use a canonical 3-vertex directed graph (A --knows--> B --knows--> C) and check each
 * dispatcher returns the expected edges or endpoints. They also pin metadata
 * (name / min / max / syntax) and verify the {@code propertyNamesForIndexCandidates} contract: for
 * OutV / InV / BothV the candidates are the literal {@link Edge#DIRECTION_OUT} /
 * {@link Edge#DIRECTION_IN} system property names; for OutE / InE / BothE they delegate to
 * {@link SQLGraphNavigationFunction#propertiesForV2ENavigation}.
 */
public class SQLFunctionGraphEdgeVariantsTest extends DbTestBase {

  private Vertex a;
  private Vertex b;
  private Vertex c;
  private Identifiable edgeAB;
  private Identifiable edgeBC;

  @Before
  public void setUpGraph() {
    session.createEdgeClass("knows");

    session.begin();
    a = session.newVertex();
    a.setProperty("name", "A");
    b = session.newVertex();
    b.setProperty("name", "B");
    c = session.newVertex();
    c.setProperty("name", "C");

    edgeAB = session.newEdge(a, b, "knows").getIdentity();
    edgeBC = session.newEdge(b, c, "knows").getIdentity();
    session.commit();
  }

  private BasicCommandContext ctx() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);
    return context;
  }

  private static List<Identifiable> collect(Object result) {
    var out = new ArrayList<Identifiable>();
    if (result == null) {
      return out;
    }
    if (result instanceof Iterable<?> it) {
      for (var o : it) {
        if (o instanceof Identifiable id) {
          out.add(id);
        }
      }
    } else if (result instanceof java.util.Iterator<?> iter) {
      while (iter.hasNext()) {
        var o = iter.next();
        if (o instanceof Identifiable id) {
          out.add(id);
        }
      }
    }
    return out;
  }

  @Test
  public void outEReturnsOutgoingEdges() {
    // A has one outgoing edge to B.
    var fn = new SQLFunctionOutE();
    session.begin();
    var src = session.getActiveTransaction().loadEntity(a);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var edges = collect(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(1, edges.size());
    assertEquals(edgeAB.getIdentity(), edges.get(0));
  }

  @Test
  public void inEReturnsIncomingEdges() {
    var fn = new SQLFunctionInE();
    session.begin();
    var src = session.getActiveTransaction().loadEntity(c);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var edges = collect(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(1, edges.size());
    assertEquals(edgeBC.getIdentity(), edges.get(0));
  }

  @Test
  public void bothEReturnsAllAdjacentEdges() {
    // B has one incoming (edgeAB) and one outgoing (edgeBC).
    var fn = new SQLFunctionBothE();
    session.begin();
    var src = session.getActiveTransaction().loadEntity(b);
    var result = fn.execute(src, null, null, new Object[0], ctx());
    var edges = collect(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(2, edges.size());
    assertTrue(edges.contains(edgeAB.getIdentity()));
    assertTrue(edges.contains(edgeBC.getIdentity()));
  }

  @Test
  public void outVOnEdgeReturnsSourceVertex() {
    // Apply outV directly on an edge — should return the OUT endpoint (A).
    var fn = new SQLFunctionOutV();
    session.begin();
    var result = fn.execute(edgeAB, null, null, new Object[0], ctx());
    // Capture the identity inside the transaction — after commit the entity is
    // detached.
    com.jetbrains.youtrackdb.internal.core.db.record.record.RID id = null;
    if (result instanceof Identifiable idf) {
      id = idf.getIdentity();
    }
    session.commit();

    assertNotNull("outV should return a vertex-like result for an edge", result);
    assertNotNull(id);
    assertEquals(a.getIdentity(), id);
  }

  @Test
  public void inVOnEdgeReturnsTargetVertex() {
    var fn = new SQLFunctionInV();
    session.begin();
    var result = fn.execute(edgeAB, null, null, new Object[0], ctx());
    com.jetbrains.youtrackdb.internal.core.db.record.record.RID id = null;
    if (result instanceof Identifiable idf) {
      id = idf.getIdentity();
    }
    session.commit();

    assertNotNull("inV should return a vertex-like result for an edge", result);
    assertNotNull(id);
    assertEquals(b.getIdentity(), id);
  }

  @Test
  public void bothVOnEdgeReturnsBothEndpoints() {
    // bothV on an edge returns a list/collection containing both endpoints.
    var fn = new SQLFunctionBothV();
    session.begin();
    var result = fn.execute(edgeAB, null, null, new Object[0], ctx());
    var ids = collect(result).stream().map(Identifiable::getIdentity).toList();
    session.commit();

    assertEquals(2, ids.size());
    assertTrue(ids.contains(a.getIdentity()));
    assertTrue(ids.contains(b.getIdentity()));
  }

  @Test
  public void outEMetadata() {
    var fn = new SQLFunctionOutE();
    assertEquals("outE", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void inEMetadata() {
    var fn = new SQLFunctionInE();
    assertEquals("inE", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void bothEMetadata() {
    var fn = new SQLFunctionBothE();
    assertEquals("bothE", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void outVMetadata() {
    var fn = new SQLFunctionOutV();
    assertEquals("outV", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void inVMetadata() {
    var fn = new SQLFunctionInV();
    assertEquals("inV", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void bothVMetadata() {
    var fn = new SQLFunctionBothV();
    assertEquals("bothV", fn.getName(session));
    assertEquals(0, fn.getMinParams());
    assertEquals(-1, fn.getMaxParams(session));
  }

  @Test
  public void outVIndexCandidatesIsOutDirectionMarker() {
    // OutV is edge→vertex; the only property that can substitute for navigation
    // is the OUT system-property name ("out").
    var fn = new SQLFunctionOutV();
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"anything"}, classE(), false, session);
    assertNotNull(candidates);
    assertEquals(List.of(Edge.DIRECTION_OUT), new ArrayList<>(candidates));
  }

  @Test
  public void inVIndexCandidatesIsInDirectionMarker() {
    var fn = new SQLFunctionInV();
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"anything"}, classE(), false, session);
    assertNotNull(candidates);
    assertEquals(List.of(Edge.DIRECTION_IN), new ArrayList<>(candidates));
  }

  @Test
  public void bothVIndexCandidatesIsBothDirectionMarkers() {
    var fn = new SQLFunctionBothV();
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"anything"}, classE(), false, session);
    assertNotNull(candidates);
    // Order is IN then OUT per the implementation — pin the order to catch
    // accidental reorderings that could affect downstream index-candidate
    // evaluation.
    assertEquals(List.of(Edge.DIRECTION_IN, Edge.DIRECTION_OUT), new ArrayList<>(candidates));
  }

  @Test
  public void outEIndexCandidatesForVertexLabelKnownEnumeratesEdgeProperty() {
    // For vertex classes the helper returns the concrete edge LinkBag property
    // names reachable for the requested direction/labels. "knows" is a
    // registered edge class, so VertexEntityImpl.getAllPossibleEdgePropertyNames
    // must surface at least one entry whose name carries "knows" — this is the
    // property that stores outgoing edges of type knows on a vertex record.
    //
    // A non-null-only assertion would still pass if a regression returned an
    // empty list (e.g., getAllPossibleEdgePropertyNames silently returning []),
    // so we pin the presence of the "knows" token in the candidate list.
    var fn = new SQLFunctionOutE();
    var vertexClass = session.getMetadata().getImmutableSchemaSnapshot().getClass(
        SchemaClass.VERTEX_CLASS_NAME);
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"knows"}, vertexClass, false, session);

    assertNotNull("Vertex types must yield a non-null candidate list", candidates);
    assertTrue(
        "Expected at least one candidate whose name references the 'knows' edge class; got: "
            + candidates,
        candidates.stream().anyMatch(n -> n != null && n.contains("knows")));
  }

  @Test
  public void outENonVertexSchemaReturnsNull() {
    // Non-vertex schema class must yield null candidates — documented contract
    // from SQLGraphNavigationFunction.propertiesForV2ENavigation.
    var fn = new SQLFunctionOutE();
    var edgeClass = classE();
    // Direction doesn't matter; the outer helper returns null when class is
    // NOT a vertex type.
    var candidates = fn.propertyNamesForIndexCandidates(
        new String[] {"knows"}, edgeClass, false, session);
    // Use assertNull directly — previously the test used `assertTrue(candidates == null)`
    // which loses the "expected null but got X" message when it fails.
    org.junit.Assert.assertNull("Expected null for edge class input", candidates);
  }

  private SchemaClass classE() {
    return session.getMetadata().getImmutableSchemaSnapshot().getClass("E");
  }
}
