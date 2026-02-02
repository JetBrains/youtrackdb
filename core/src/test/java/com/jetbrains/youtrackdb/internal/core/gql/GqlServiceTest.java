package com.jetbrains.youtrackdb.internal.core.gql;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
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
  public void testGqlMatchReturnsEmptyWhenNoVertices() {
    var g = graph.traversal();

    // Execute the full flow - should return no results when class doesn't exist or is empty
    var results = g.gql("MATCH (a:Person)").toList();

    assertEquals("Should return 0 results when no Person vertices exist", 0, results.size());
  }

  @Test
  public void testGqlServiceWithParameters() {
    var g = graph.traversal();

    // Verify we can call gql() with parameters
    var traversal = g.gql("MATCH (a:Person) WHERE a.name = $name", java.util.Map.of("name", "John"));
    assertNotNull("gql() with parameters should return a traversal", traversal);
  }

  @Test
  public void testMatchSingleNode() {
    var g = graph.traversal();

    // Setup: create a Person vertex
    g.addV("Person").property("name", "Alice").iterate();
    g.tx().commit();

    // Execute GQL query
    var results = g.gql("MATCH (a:Person)").toList();

    assertEquals("Should find 1 person", 1, results.size());
    assertTrue("Result should contain 'a' binding", results.get(0).containsKey("a"));
    assertTrue("Binding 'a' should be a Vertex", Vertex.class.isInstance(results.get(0).get("a")));
  }

  @Test
  public void testMatchMultipleNodes() {
    var g = graph.traversal();

    // Setup: create multiple Person vertices
    g.addV("Person").property("name", "Alice").iterate();
    g.addV("Person").property("name", "Bob").iterate();
    g.addV("Person").property("name", "Charlie").iterate();
    g.tx().commit();

    // Execute GQL query
    var results = g.gql("MATCH (a:Person)").toList();

    assertEquals("Should find 3 persons", 3, results.size());
    for (var result : results) {
      assertTrue("Each result should contain 'a' binding", result.containsKey("a"));
      assertTrue("Binding 'a' should be a Vertex", Vertex.class.isInstance(result.get("a")));
    }
  }

  @Test
  public void testMatchWithSelect() {
    var g = graph.traversal();

    // Setup
    g.addV("Person").property("name", "Bob").iterate();
    g.tx().commit();

    // Execute GQL query and select the variable
    var vertices = g.gql("MATCH (a:Person)").select("a").toList();

    assertEquals("Should find 1 vertex", 1, vertices.size());
    assertTrue("Selected value should be a Vertex", Vertex.class.isInstance(vertices.get(0)));
  }

  @Test
  public void testMatchWithSelectAndValues() {
    var g = graph.traversal();

    // Setup
    g.addV("Person").property("name", "Alice").iterate();
    g.tx().commit();

    // Execute GQL query, select the variable, and get its name property
    var names = g.gql("MATCH (a:Person)").select("a").values("name").toList();

    assertEquals("Should find 1 name", 1, names.size());
    assertEquals("Name should be Alice", "Alice", names.get(0));
  }

  @Test
  public void testMatchDifferentAlias() {
    var g = graph.traversal();

    // Setup
    g.addV("Person").property("name", "Test").iterate();
    g.tx().commit();

    // Execute GQL query with different alias
    var results = g.gql("MATCH (person:Person)").toList();

    assertEquals("Should find 1 person", 1, results.size());
    assertTrue("Result should contain 'person' binding", results.get(0).containsKey("person"));
  }
}
