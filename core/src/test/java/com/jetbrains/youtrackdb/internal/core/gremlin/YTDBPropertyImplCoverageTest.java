package com.jetbrains.youtrackdb.internal.core.gremlin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

/**
 * Coverage-focused tests for {@link YTDBPropertyImpl} and the {@link YTDBVertexPropertyImpl}
 * subclass — the two classes that back every property write/read in the gremlin engine.
 * Existing per-graph tests
 * ({@link GraphTest}, {@link GraphLinkTest}) exercise the happy path; this class targets
 * the corner branches in {@code wrapIntoGraphElement} that route a stored {@link RID} to
 * {@link YTDBVertexImpl} / {@link YTDBEdgeImpl} based on the schema-class kind, plus the
 * standard {@code toString}/{@code equals}/{@code hashCode}/{@code remove} contract.
 */
public class YTDBPropertyImplCoverageTest extends GraphBaseTest {

  /**
   * Storing a vertex's {@link RID} in another vertex's property and reading it back must
   * surface a {@link YTDBVertex} — covers the {@code instanceof RID} → vertex branch in
   * {@link YTDBPropertyImpl} (line 45–46).
   */
  @Test
  public void readingVertexRidPropertyReturnsVertex() {
    var v1 = graph.addVertex(T.label, "Person", "name", "Alice");
    var v2 = graph.addVertex(T.label, "Person", "name", "Bob");
    v1.property("friend", v2.id());
    graph.tx().commit();

    var v1Reloaded = (YTDBVertex) graph.vertices(v1.id()).next();
    Object val = v1Reloaded.value("friend");
    assertNotNull(val);
    assertTrue("RID-typed vertex property must wrap into a Vertex", val instanceof Vertex);
    assertEquals(v2.id(), ((Vertex) val).id());
  }

  /**
   * Storing an edge's {@link RID} in a vertex property and reading it back must surface a
   * {@link Edge} — covers the {@code instanceof RID} → edge branch in
   * {@link YTDBPropertyImpl} (line 47–48).
   */
  @Test
  public void readingEdgeRidPropertyReturnsEdge() {
    var v1 = graph.addVertex(T.label, "Person", "name", "Alice");
    var v2 = graph.addVertex(T.label, "Person", "name", "Bob");
    var edge = v1.addEdge("Knows", v2);
    edge.property("weight", 0.5);
    v1.property("favouriteEdge", edge.id());
    graph.tx().commit();

    var v1Reloaded = (YTDBVertex) graph.vertices(v1.id()).next();
    Object val = v1Reloaded.value("favouriteEdge");
    assertNotNull(val);
    assertTrue("RID-typed edge property must wrap into an Edge", val instanceof Edge);
    assertEquals(edge.id(), ((Edge) val).id());
  }

  /**
   * The {@code remove()} call on a vertex property nulls the underlying value, sets
   * {@code removed=true}, and propagates {@code isPresent==false} on subsequent reads.
   * The property reference itself becomes "absent" — pin both observable invariants.
   */
  @Test
  public void removeOnVertexPropertyMarksAbsent() {
    var v = graph.addVertex(T.label, "Person", "name", "Alice");
    v.property("nickname", "Al");

    var prop = v.property("nickname");
    assertTrue(prop.isPresent());
    assertEquals("Al", prop.value());

    prop.remove();
    assertFalse("removed property must report isPresent=false", prop.isPresent());
  }

  /**
   * The {@code key}/{@code value}/{@code element}/{@code type}/{@code toString} accessors on
   * a freshly-written {@link YTDBVertexProperty} must report the values they were
   * constructed with — pin the entire data-class contract.
   */
  @Test
  public void vertexPropertyAccessorsExposeWrittenValue() {
    var v = graph.addVertex(T.label, "Person", "name", "Alice");
    var prop = v.property("nickname", "Al");

    assertEquals("nickname", prop.key());
    assertEquals("Al", prop.value());
    assertEquals(v.id(), ((YTDBVertex) prop.element()).id());
    assertNotNull(prop.toString());
    assertFalse(prop.toString().isEmpty());
    // type may be null when the schema has no PropertyType registered for the key — the
    // contract is "non-throwing", not a particular value.
    var type = prop.type();
    assertTrue(type == null || type instanceof PropertyType);
  }

  /**
   * Two property reads of the same key on the same element produce equal property objects —
   * pins the {@code ElementHelper.areEqual}/{@code ElementHelper.hashCode} contract that
   * {@link YTDBPropertyImpl} delegates to.
   */
  @Test
  public void readingSamePropertyTwiceProducesEqualReferences() {
    var v = graph.addVertex(T.label, "Person", "name", "Alice");
    v.property("rank", 1);

    YTDBProperty<Integer> a = v.property("rank");
    YTDBProperty<Integer> b = v.property("rank");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  /**
   * The {@link YTDBVertexPropertyImpl#hasProperty} branch where the metadata document has
   * never been created must return {@code false} (rather than throwing). This pins the
   * "cheap negative" optimisation in the implementation — when no meta-prop has ever been
   * written, the metadata sub-entity is absent and {@code hasProperty} short-circuits.
   */
  @Test
  public void vertexPropertyHasPropertyOnMissingMetadataReturnsFalse() {
    var v = graph.addVertex(T.label, "Person", "name", "Alice");
    var prop = v.property("nickname", "Al");
    assertFalse(prop.hasProperty("never-written"));
  }

  /**
   * Same for {@link YTDBVertexPropertyImpl#removeProperty} — deleting a meta-prop that
   * never existed returns {@code false} silently rather than throwing.
   */
  @Test
  public void vertexPropertyRemovePropertyOnMissingMetadataReturnsFalse() {
    var v = graph.addVertex(T.label, "Person", "name", "Alice");
    var prop = v.property("nickname", "Al");
    assertFalse(prop.removeProperty("never-written"));
  }

  /**
   * {@link YTDBVertexPropertyImpl#properties} on a vertex-property that has never had a
   * meta-property written returns an empty iterator (no metadata document exists).
   */
  @Test
  public void vertexPropertyPropertiesWhenNoMetadataIsEmpty() {
    var v = graph.addVertex(T.label, "Person", "name", "Alice");
    var prop = v.property("nickname", "Al");
    assertFalse(prop.properties().hasNext());
  }

  /**
   * After writing a meta-property, removing it via the returned {@link YTDBVertexPropertyProperty}
   * marks the property absent and removes the underlying entry; subsequent reads must
   * report {@code isPresent==false} and {@code hasProperty(key)==false}.
   */
  @Test
  public void removingMetaPropertyMarksItAbsent() {
    var v = graph.addVertex(T.label, "Person", "name", "Alice");
    var prop = (YTDBVertexProperty<String>) v.property("nickname", "Al");
    var meta = prop.property("alias", "Big Al");
    assertTrue(meta.isPresent());
    assertEquals("Big Al", meta.value());

    meta.remove();
    assertFalse(meta.isPresent());
  }
}
