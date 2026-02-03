package com.jetbrains.youtrackdb.internal.core.gql;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.junit.Test;

import static org.junit.Assert.*;

/// Integration test for GQL service.
///
/// This test tracks progress of GQL implementation.
/// As we implement more features, we update the expected behavior.
public class GqlServiceTest extends GraphBaseTest {

  @Test
  public void testGqlServiceIsRegistered() {
    // Verify that g.gql() method exists and returns a traversal
    var g = graph.traversal();

    var traversal = g.gql("MATCH (a:Person)");
    assertNotNull("gql() should return a traversal", traversal);
  }

  @Test
  public void testGqlMatchThrowsWhenClassNotFound() {
    var g = graph.traversal();

    // When querying a class that doesn't exist, should throw CommandExecutionException
    try {
      g.gql("MATCH (a:NonExistentClass)").toList();
      fail("Should throw exception when class doesn't exist");
    } catch (CommandExecutionException e) {
      assertTrue("Exception message should mention class name",
          e.getMessage().contains("NonExistentClass"));
      assertTrue("Exception message should mention 'not found'",
          e.getMessage().contains("not found"));
    }
  }

  @Test
  public void testGqlMatchWithoutAliasReturnsVertexDirectly() {
    var g = graph.traversal();

    // Setup
    g.addV("Person").property("name", "Alice").iterate();
    g.tx().commit();

    // MATCH (:Person) without alias returns vertex directly (no side effects/bindings)
    var results = g.gql("MATCH (:Person)").toList();

    assertEquals("Should find 1 person", 1, results.size());
    assertTrue("Result should be a Vertex directly (no Map wrapper)",
        results.getFirst() instanceof Vertex);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testGqlMatchWithoutLabelMatchesAllVertices() {
    var g = graph.traversal();

    // Setup: create vertices of different classes
    g.addV("Person").property("name", "Alice").iterate();
    g.addV("Work").property("title", "Engineer").iterate();
    g.addV("Location").property("city", "NYC").iterate();
    g.tx().commit();

    // MATCH (a) without label should match all vertices (base class V)
    // With alias - returns Map with bindings
    var results = g.gql("MATCH (a)").toList();

    assertEquals("Should find all 3 vertices", 3, results.size());
    for (var result : results) {
      var map = (Map<String, Object>) result;
      assertTrue("Result should contain 'a' binding", map.containsKey("a"));
      assertTrue("Binding 'a' should be a Vertex", map.get("a") instanceof Vertex);
    }
  }

  @Test
  public void testGqlMatchReturnsEmptyWhenClassExistsButEmpty() {
    var g = graph.traversal();

    // Create a Person vertex to ensure class exists, then delete it
    g.addV("Person").property("name", "temp").iterate();
    g.V().hasLabel("Person").drop().iterate();
    g.tx().commit();

    // Class exists but has no vertices - should return empty result
    var results = g.gql("MATCH (a:Person)").toList();

    assertEquals("Should return 0 results when Person class is empty", 0,
        results.size());
  }

  @Test
  public void testGqlServiceWithParameters() {
    var g = graph.traversal();

    // Verify we can call gql() with parameters
    var traversal = g.gql(
        "MATCH (a:Person) WHERE a.name = $name", java.util.Map.of("name", "John"));
    assertNotNull("gql() with parameters should return a traversal", traversal);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMatchSingleNode() {
    var g = graph.traversal();

    // Setup: create a Person vertex
    g.addV("Person").property("name", "Alice").iterate();
    g.tx().commit();

    var results = g.gql("MATCH (a:Person)").toList();

    assertEquals("Should find 1 person", 1, results.size());
    var map = (Map<String, Object>) results.getFirst();
    assertTrue("Result should contain 'a' binding", map.containsKey("a"));
    assertTrue("Binding 'a' should be a Vertex", map.get("a") instanceof Vertex);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMatchMultipleNodes() {
    var g = graph.traversal();

    // Setup: create multiple Person vertices
    g.addV("Person").property("name", "Alice").iterate();
    g.addV("Person").property("name", "Bob").iterate();
    g.addV("Person").property("name", "Charlie").iterate();
    g.tx().commit();

    var results = g.gql("MATCH (a:Person)").toList();

    assertEquals("Should find 3 persons", 3, results.size());
    for (var result : results) {
      var map = (Map<String, Object>) result;
      assertTrue("Each result should contain 'a' binding", map.containsKey("a"));
      assertTrue("Binding 'a' should be a Vertex", map.get("a") instanceof Vertex);
    }
  }

  @Test
  public void testMatchWithSelect() {
    var g = graph.traversal();

    // Setup
    g.addV("Person").property("name", "Bob").iterate();
    g.tx().commit();

    var vertices = g.gql("MATCH (a:Person)").select("a").toList();

    assertEquals("Should find 1 vertex", 1, vertices.size());
    assertTrue("Selected value should be a Vertex", vertices.getFirst() instanceof Vertex);
  }

  @Test
  public void testMatchWithSelectAndValues() {
    var g = graph.traversal();

    // Setup
    g.addV("Person").property("name", "Alice").iterate();
    g.tx().commit();

    var names = g.gql("MATCH (a:Person)").select("a")
        .values("name").toList();

    assertEquals("Should find 1 name", 1, names.size());
    assertEquals("Name should be Alice", "Alice", names.getFirst());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void testMatchDifferentAlias() {
    var g = graph.traversal();

    // Setup
    g.addV("Person").property("name", "Test").iterate();
    g.tx().commit();

    var results = g.gql("MATCH (person:Person)").toList();

    assertEquals("Should find 1 person", 1, results.size());
    var map = (Map<String, Object>) results.getFirst();
    assertTrue("Result should contain 'person' binding", map.containsKey("person"));
  }
}
