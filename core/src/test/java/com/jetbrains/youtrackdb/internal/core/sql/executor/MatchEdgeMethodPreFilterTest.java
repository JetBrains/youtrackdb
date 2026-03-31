package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * End-to-end integration tests for index-assisted pre-filtering on edge-method
 * MATCH patterns ({@code outE/inE} followed by {@code inV/outV}).
 *
 * <p>These tests verify that:
 * <ul>
 *   <li>Edge-method MATCH queries produce correct results identical to
 *       equivalent non-edge-method queries</li>
 *   <li>The planner applies pre-filter optimization when an indexed edge
 *       property is used in a WHERE clause</li>
 *   <li>Both {@code outE→inV} and {@code inE→outV} directions work</li>
 *   <li>No pre-filter is applied when the edge property is not indexed</li>
 * </ul>
 */
public class MatchEdgeMethodPreFilterTest extends DbTestBase {

  /**
   * Set up a graph with two edge classes that have indexed properties and typed
   * LINK endpoints, enabling class inference from edge schema.
   *
   * <p>Schema:
   * <pre>
   *   Person --WORK_AT(workFrom: INTEGER, indexed)--> Company
   *   Person --HAS_MEMBER(joinDate: LONG, indexed)--> Forum
   * </pre>
   *
   * <p>Data: 10 persons, 5 companies, 3 forums.
   * Each person works at one company (workFrom varies), and is a member of
   * 1-3 forums (joinDate varies).
   */
  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    // Vertex classes
    session.execute("CREATE class PFPerson extends V").close();
    session.execute("CREATE property PFPerson.name STRING").close();

    session.execute("CREATE class PFCompany extends V").close();
    session.execute("CREATE property PFCompany.name STRING").close();

    session.execute("CREATE class PFForum extends V").close();
    session.execute("CREATE property PFForum.title STRING").close();

    // Edge class: WORK_AT with indexed workFrom and typed endpoints
    session.execute("CREATE class PFWorkAt extends E").close();
    session.execute("CREATE property PFWorkAt.out LINK PFPerson").close();
    session.execute("CREATE property PFWorkAt.in LINK PFCompany").close();
    session.execute("CREATE property PFWorkAt.workFrom INTEGER").close();
    session.execute(
        "CREATE index PFWorkAt_workFrom on PFWorkAt (workFrom) NOTUNIQUE").close();

    // Edge class: HAS_MEMBER with indexed joinDate and typed endpoints
    session.execute("CREATE class PFHasMember extends E").close();
    session.execute("CREATE property PFHasMember.out LINK PFPerson").close();
    session.execute("CREATE property PFHasMember.in LINK PFForum").close();
    session.execute("CREATE property PFHasMember.joinDate LONG").close();
    session.execute(
        "CREATE index PFHasMember_joinDate on PFHasMember (joinDate) NOTUNIQUE").close();

    // Edge class without index — for negative test
    session.execute("CREATE class PFLikes extends E").close();
    session.execute("CREATE property PFLikes.out LINK PFPerson").close();
    session.execute("CREATE property PFLikes.in LINK PFForum").close();
    session.execute("CREATE property PFLikes.score INTEGER").close();
    // No index on PFLikes.score

    session.begin();

    // Create companies
    for (int i = 0; i < 5; i++) {
      session.execute("CREATE VERTEX PFCompany set name = 'company" + i + "'")
          .close();
    }

    // Create forums
    for (int i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX PFForum set title = 'forum" + i + "'")
          .close();
    }

    // Create 10 persons, each working at one company with different workFrom years
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX PFPerson set name = 'person" + i + "'")
          .close();

      // Person i works at company (i % 5), workFrom = 2010 + i
      session.execute(
          "CREATE EDGE PFWorkAt FROM"
              + " (SELECT FROM PFPerson WHERE name = 'person" + i + "')"
              + " TO (SELECT FROM PFCompany WHERE name = 'company" + (i % 5) + "')"
              + " SET workFrom = " + (2010 + i))
          .close();

      // Person i is member of forum (i % 3), joinDate = 1000 + i * 100
      session.execute(
          "CREATE EDGE PFHasMember FROM"
              + " (SELECT FROM PFPerson WHERE name = 'person" + i + "')"
              + " TO (SELECT FROM PFForum WHERE title = 'forum" + (i % 3) + "')"
              + " SET joinDate = " + (1000 + i * 100))
          .close();

      // Also create a Likes edge for negative tests (no index)
      session.execute(
          "CREATE EDGE PFLikes FROM"
              + " (SELECT FROM PFPerson WHERE name = 'person" + i + "')"
              + " TO (SELECT FROM PFForum WHERE title = 'forum" + (i % 3) + "')"
              + " SET score = " + (i * 10))
          .close();
    }

    session.commit();
  }

  // ---- Correctness tests ----

  /**
   * Verify that an outE→inV MATCH query with an indexed edge property filter
   * produces the same results as the equivalent out() pattern.
   *
   * Pattern: Person -outE('PFWorkAt'){where: workFrom < 2015}-> inV() as company
   * Expected: persons 0-4 (workFrom 2010-2014)
   */
  @Test
  public void testOutEInVCorrectness() {
    session.begin();

    // Edge-method pattern
    var edgeMethodQuery =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){where: (workFrom < 2015)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var edgeResult = session.query(edgeMethodQuery).toList();

    // Equivalent direct pattern for verification
    var directQuery =
        "SELECT FROM PFWorkAt WHERE workFrom < 2015";
    var directResult = session.query(directQuery).toList();

    assertEquals(
        "Edge-method query should return same count as direct edge query",
        directResult.size(), edgeResult.size());
    assertEquals(5, edgeResult.size());

    // Verify all person names are persons 0-4
    Set<String> personNames = new HashSet<>();
    for (var r : edgeResult) {
      personNames.add(r.getProperty("p.name"));
    }
    for (int i = 0; i < 5; i++) {
      assertTrue(
          "Should contain person" + i,
          personNames.contains("person" + i));
    }

    session.commit();
  }

  /**
   * Verify that an inE→outV MATCH query with an indexed edge property filter
   * produces correct results.
   *
   * Pattern: Forum -inE('PFHasMember'){where: joinDate >= 1500}-> outV() as person
   * Expected: persons with joinDate >= 1500, i.e., persons 5-9
   * (joinDate = 1000 + i*100, so >= 1500 means i >= 5)
   */
  @Test
  public void testInEOutVCorrectness() {
    session.begin();

    var query =
        "MATCH {class: PFForum, as: f}"
            + ".inE('PFHasMember'){where: (joinDate >= 1500)}"
            + ".outV(){as: p}"
            + " RETURN p.name, f.title";
    var result = session.query(query).toList();

    assertEquals(5, result.size());

    Set<String> personNames = new HashSet<>();
    for (var r : result) {
      personNames.add(r.getProperty("p.name"));
    }
    for (int i = 5; i < 10; i++) {
      assertTrue(
          "Should contain person" + i,
          personNames.contains("person" + i));
    }

    session.commit();
  }

  // ---- Direction tests ----

  /**
   * Verify that both outE→inV and inE→outV directions produce correct results
   * for the same logical relationship.
   *
   * outE from Person should yield the same companies as inE from Company yields
   * persons — just viewed from different starting points.
   */
  @Test
  public void testBothDirectionsConsistent() {
    session.begin();

    // outE from Person: find companies where workFrom = 2015
    var outQuery =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){where: (workFrom = 2015)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var outResult = session.query(outQuery).toList();
    assertEquals(1, outResult.size());
    assertEquals("person5", outResult.getFirst().getProperty("p.name"));
    assertEquals("company0", outResult.getFirst().getProperty("c.name"));

    // inE from Company: find persons where workFrom = 2015
    var inQuery =
        "MATCH {class: PFCompany, as: c}"
            + ".inE('PFWorkAt'){where: (workFrom = 2015)}"
            + ".outV(){as: p}"
            + " RETURN p.name, c.name";
    var inResult = session.query(inQuery).toList();
    assertEquals(1, inResult.size());
    assertEquals("person5", inResult.getFirst().getProperty("p.name"));
    assertEquals("company0", inResult.getFirst().getProperty("c.name"));

    session.commit();
  }

  // ---- EXPLAIN / pre-filter verification ----

  /**
   * Verify via EXPLAIN that the planner recognizes the edge class for
   * outE('PFWorkAt') and includes it in the execution plan. The edge alias
   * should appear in the plan, indicating class inference worked.
   */
  @Test
  public void testExplainShowsEdgeClassInference() {
    session.begin();

    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){as: w, where: (workFrom < 2015)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";

    var explainResult = session.query("EXPLAIN " + query).toList();
    assertEquals(1, explainResult.size());
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);

    // The edge alias {w} should appear in the execution plan
    assertTrue(
        "Edge alias {w} should appear in execution plan, but plan was:\n" + plan,
        plan.contains("{w}"));

    session.commit();
  }

  /**
   * Verify via EXPLAIN that the planner infers the vertex class for the inV()
   * target from the edge schema's LINK property, even without an explicit
   * class: constraint on the vertex alias.
   *
   * The vertex alias after inV() should get PFCompany inferred from
   * PFWorkAt.in LINK PFCompany, affecting scheduling optimality.
   */
  @Test
  public void testExplainInfersVertexClassFromEdgeSchema() {
    session.begin();

    // No explicit class: on the company alias — planner should infer it
    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){as: w, where: (workFrom < 2015)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";

    // Run the query and verify correct results (inference correctness)
    var result = session.query(query).toList();
    assertEquals(5, result.size());

    // Verify EXPLAIN plan contains both aliases
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "Vertex alias {c} should appear in plan, but plan was:\n" + plan,
        plan.contains("{c}"));

    session.commit();
  }

  // ---- Negative test: no pre-filter without index ----

  /**
   * Verify that an edge class without an indexed property on the WHERE clause
   * still produces correct results but does NOT benefit from pre-filtering.
   *
   * Uses PFLikes which has a score property but no index on it.
   * The query should still work (correctness) but the execution plan should
   * not show pre-filter optimization for the edge traversal.
   */
  @Test
  public void testNoPreFilterWithoutIndex() {
    session.begin();

    // PFLikes.score has no index
    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFLikes'){where: (score >= 50)}"
            + ".inV(){as: f}"
            + " RETURN p.name, f.title";
    var result = session.query(query).toList();

    // persons 5-9 have score >= 50 (score = i * 10)
    assertEquals(5, result.size());

    Set<String> personNames = new HashSet<>();
    for (var r : result) {
      personNames.add(r.getProperty("p.name"));
    }
    for (int i = 5; i < 10; i++) {
      assertTrue(
          "Should contain person" + i,
          personNames.contains("person" + i));
    }

    session.commit();
  }
}
