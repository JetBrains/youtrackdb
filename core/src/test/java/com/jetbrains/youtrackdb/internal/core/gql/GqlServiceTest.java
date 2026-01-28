package com.jetbrains.youtrackdb.internal.core.gql;

import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
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
  public void testGqlServiceThrowsNotImplemented() {
    var g = graph.traversal();

    // Currently, executing the traversal should throw UnsupportedOperationException
    var exception = assertThrows(
        UnsupportedOperationException.class,
        () -> g.gql("MATCH (a:Person)").toList()
    );

    assertTrue(
        "Exception message should mention the query",
        exception.getMessage().contains("MATCH (a:Person)")
    );
  }

  @Test
  public void testGqlServiceWithParameters() {
    var g = graph.traversal();

    // Verify we can call gql() with parameters
    var traversal = g.gql("MATCH (a:Person) WHERE a.name = $name", java.util.Map.of("name", "John"));
    assertNotNull("gql() with parameters should return a traversal", traversal);
  }


  /*
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
  }
  */
}
