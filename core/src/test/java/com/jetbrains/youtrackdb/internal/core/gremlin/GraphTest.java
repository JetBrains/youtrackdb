package com.jetbrains.youtrackdb.internal.core.gremlin;

import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.Lists;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBEdge;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertex;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Direction;
import org.apache.tinkerpop.gremlin.structure.Graph;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Transaction.CLOSE_BEHAVIOR;
import org.apache.tinkerpop.gremlin.structure.VertexProperty.Cardinality;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class GraphTest extends GraphBaseTest {

  public static final String TEST_VALUE = "SomeValue";

  @Test
  public void testGraphTransactions() {
    Object id;
    try (var tx = graph.tx()) {
      tx.onClose(CLOSE_BEHAVIOR.COMMIT);
      var vertex = graph.addVertex();
      id = vertex.id();
      vertex.property("test", TEST_VALUE);
    }
    assertNotNull("A vertex should have been created in the first transaction", id);

    // Modify property and rollback
    try (var tx = graph.tx()) {
      tx.onClose(CLOSE_BEHAVIOR.ROLLBACK);
      var vertex = graph.vertices(id).next();
      assertNotNull(vertex);
      vertex.property("test", "changed");
    }

    try (var tx = graph.tx()) {
      tx.onClose(CLOSE_BEHAVIOR.ROLLBACK);
      var vertex = graph.vertices(id).next();
      assertNotNull(vertex);
      vertex.property("test", "changed");
    }

    var vertex = graph.vertices(id).next();
    Assert.assertEquals(
        "The property value should not have been changed.", TEST_VALUE, vertex.value("test"));

    // 1. Modify property rollback, 2. Modify property commit
    try (var tx = graph.tx()) {
      tx.onClose(CLOSE_BEHAVIOR.ROLLBACK);
      vertex = graph.vertices(id).next();
      assertNotNull(vertex);
      vertex.property("test", "changed");
    }

    try (var tx = graph.tx()) {
      tx.onClose(CLOSE_BEHAVIOR.COMMIT);
      vertex = graph.vertices(id).next();
      assertNotNull(vertex);
      vertex.property("test", "changed");
    }

    vertex = graph.vertices(id).next();
    Assert.assertEquals(
        "The property value should not have been changed.", "changed", vertex.value("test"));

  }


  @Test
  public void testGraph() {
    performBasicTests(graph);
  }


  @Test
  public void testUnprefixedLabelGraph() throws Exception {
    performBasicTests(graph);

    var vertex = graph.addVertex("VERTEX_LABEL");
    Assert.assertEquals("VERTEX_LABEL", vertex.label());

    try {
      graph.addVertex("EDGE_LABEL");
      Assert.fail("must throw unable to create different super class");
    } catch (IllegalArgumentException e) {
      assertTrue(
          e.getMessage().startsWith("Class EDGE_LABEL is not a vertex type"));
    }

    graph.close();
  }

  protected static void performBasicTests(Graph graph) {
    var vertex = graph.addVertex();
    assertNotNull(vertex);
    assertNotNull(vertex.id());

    vertex.property("test", TEST_VALUE);
    Assert.assertEquals(TEST_VALUE, vertex.value("test"));

    Property<String> property = vertex.property("test");
    assertNotNull(property);
    assertTrue(property.isPresent());
    Assert.assertEquals(TEST_VALUE, property.value());
    property.remove();
    assertFalse(property.isPresent());

    // Create test vertices for edge
    var vertexA = graph.addVertex();
    var vertexB = graph.addVertex();
    var edge = vertexA.addEdge("EDGE_LABEL", vertexB);
    Assert.assertEquals("EDGE_LABEL", edge.label());

    // Test edge properties
    assertNotNull(edge.property("test", TEST_VALUE));
    Property<String> edgeProperty = edge.property("test");
    assertNotNull(edgeProperty);
    assertTrue(edgeProperty.isPresent());
    Assert.assertEquals(TEST_VALUE, edgeProperty.value());
    edgeProperty.remove();
    assertFalse(edgeProperty.isPresent());

    edge.property("test", TEST_VALUE);
    Assert.assertEquals(TEST_VALUE, edge.value("test"));

    // Check vertices of edge
    var out = edge.outVertex();
    assertNotNull(out);
    Assert.assertEquals(vertexA.id(), out.id());

    var in = edge.inVertex();
    assertNotNull(in);
    Assert.assertEquals(vertexB.id(), in.id());
  }

  @Test
  public void testMetaProperties() {
    var v1 = graph.addVertex();
    var prop =
        v1.property(
            Cardinality.single, "key", "value", "meta_key", "meta_value", "meta_key_2",
            "meta_value_2");

    var keysValues =
        StreamUtils.asStream(prop.properties())
            .collect(toMap(Property::key, p -> (String) p.value()));
    assertThat(keysValues, Matchers.hasEntry("meta_key", "meta_value"));
    assertThat(keysValues, Matchers.hasEntry("meta_key_2", "meta_value_2"));

    Map<String, Property<?>> props =
        StreamUtils.asStream(prop.properties()).collect(toMap(Property::key, p -> p));

    props.get("meta_key_2").remove();

    keysValues =
        StreamUtils.asStream(prop.properties())
            .collect(toMap(Property::key, p -> (String) p.value()));
    assertThat(keysValues, Matchers.hasEntry("meta_key", "meta_value"));
    assertThat(keysValues, Matchers.not(Matchers.hasEntry("meta_key_2", "meta_value_2")));

    props.get("meta_key").remove();

    keysValues =
        StreamUtils.asStream(prop.properties())
            .collect(toMap(Property::key, p -> (String) p.value()));
    assertThat(keysValues, Matchers.not(Matchers.hasEntry("meta_key", "meta_value")));
    assertThat(keysValues, Matchers.not(Matchers.hasEntry("meta_key_2", "meta_value_2")));
  }

  @Test
  public void removeVertex() {
    var v1 = graph.addVertex();
    var v2 = graph.addVertex();
    v1.addEdge("label1", v2);
    v2.addEdge("label2", v1);

    assertThat(Lists.newArrayList(v2.edges(Direction.IN, "label1")), Matchers.hasSize(1));
    assertThat(Lists.newArrayList(v2.edges(Direction.OUT, "label2")), Matchers.hasSize(1));

    v1.remove();

    assertThat(Lists.newArrayList(v2.edges(Direction.IN, "label1")), Matchers.hasSize(0));
    assertThat(Lists.newArrayList(v2.edges(Direction.OUT, "label2")), Matchers.hasSize(0));
  }


  @Test
  public void testHasAndRemoveProperty() {
    final var propName = "name_hasAndRemoveProperty";
    final var value = "value";

    final var vertex = graph.addVertex();
    final var anotherVertex = graph.addVertex();
    final var edge = vertex.addEdge("EDGE_LABEL", anotherVertex);

    vertex.property(propName, value);
    edge.property(propName, value);

    assertEquals(value, vertex.property(propName).value());
    assertEquals(value, edge.property(propName).value());

    assertTrue(vertex.hasProperty(propName));
    assertTrue(edge.hasProperty(propName));
    graph.tx().commit();

    final var vertex1 = ((YTDBVertex) graph.vertices(vertex.id()).next());
    final var edge1 = ((YTDBEdge) graph.edges(edge.id()).next());
    assertEquals(value, vertex1.property(propName).value());
    assertEquals(value, edge1.property(propName).value());

    assertTrue(vertex1.hasProperty(propName));
    assertTrue(edge1.hasProperty(propName));

    vertex1.removeProperty(propName);
    edge1.removeProperty(propName);

    assertFalse(vertex1.hasProperty(propName));
    assertFalse(vertex1.property(propName).isPresent());
    assertFalse(edge1.hasProperty(propName));
    assertFalse(edge1.property(propName).isPresent());

    // calling it once again doesn't throw any error
    vertex1.removeProperty(propName);
    edge1.removeProperty(propName);

    graph.tx().commit();

    final var vertex2 = ((YTDBVertex) graph.vertices(vertex.id()).next());
    final var edge2 = ((YTDBEdge) graph.edges(edge.id()).next());
    assertFalse(vertex2.hasProperty(propName));
    assertFalse(edge2.hasProperty(propName));

    // calling it once again doesn't throw any error
    vertex2.removeProperty(propName);
    edge2.removeProperty(propName);
    graph.tx().commit();
  }
}
