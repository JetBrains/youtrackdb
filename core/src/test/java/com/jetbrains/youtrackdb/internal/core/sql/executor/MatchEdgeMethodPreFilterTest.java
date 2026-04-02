package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
 *       property is used in a WHERE clause (via EXPLAIN plan inspection)</li>
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
   *   Person --LIKES(score: INTEGER, NOT indexed)--> Forum
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
   * produces correct results, checking both person names and person→company pairs.
   *
   * Pattern: Person -outE('PFWorkAt'){where: workFrom < 2015}-> inV() as company
   * Expected: persons 0-4 (workFrom 2010-2014), each at their assigned company
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

    // Verify person→company pairs (not just person names)
    Set<String> pairs = new HashSet<>();
    for (var r : edgeResult) {
      pairs.add(r.getProperty("p.name") + "->" + r.getProperty("c.name"));
    }
    Set<String> expectedPairs = new HashSet<>();
    for (int i = 0; i < 5; i++) {
      expectedPairs.add("person" + i + "->company" + (i % 5));
    }
    assertEquals(expectedPairs, pairs);

    session.commit();
  }

  /**
   * Verify that an inE→outV MATCH query with an indexed edge property filter
   * produces correct results, checking person→forum pairs.
   *
   * Pattern: Forum -inE('PFHasMember'){where: joinDate >= 1500}-> outV() as person
   * Expected: persons 5-9 (joinDate = 1000 + i*100, so >= 1500 means i >= 5)
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

    // Verify person→forum pairs
    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("p.name") + "->" + r.getProperty("f.title"));
    }
    Set<String> expectedPairs = new HashSet<>();
    for (int i = 5; i < 10; i++) {
      expectedPairs.add("person" + i + "->forum" + (i % 3));
    }
    assertEquals(expectedPairs, pairs);

    session.commit();
  }

  // ---- Direction tests ----

  /**
   * Verify that both outE→inV and inE→outV directions produce correct results
   * for the same logical relationship: person5 works at company0 with workFrom=2015.
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
   * outE('PFWorkAt') and uses the PFWorkAt_workFrom index as an
   * intersection pre-filter on the edge traversal step. Inferred classes
   * do not become prefetch roots (to avoid reversing while-traversal
   * direction), but the index is still used as a pre-filter.
   */
  @Test
  public void testExplainShowsEdgeIndexUsage() {
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

    // The plan should use the PFWorkAt_workFrom index as an intersection
    // pre-filter on the edge traversal step
    assertTrue(
        "Plan should show intersection pre-filter using PFWorkAt_workFrom "
            + "index, but plan was:\n" + plan,
        plan.contains("intersection:"));

    session.commit();
  }

  /**
   * Verify via EXPLAIN that the planner infers the vertex class for the inV()
   * target from the edge schema's LINK property, even without an explicit
   * class: constraint. The inferred class is used for collection-ID filtering
   * (shown as a class filter step in the plan) and the edge index is used as
   * an intersection pre-filter.
   */
  @Test
  public void testExplainInfersVertexClassFromEdgeSchema() {
    session.begin();

    // No explicit class: on the company alias — planner should infer it
    // from PFWorkAt.in LINK PFCompany
    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){where: (workFrom < 2015)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";

    // Run the query and verify correct results (inference correctness)
    var result = session.query(query).toList();
    assertEquals(5, result.size());

    // Verify EXPLAIN plan shows the vertex alias and edge index pre-filter
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "Vertex alias {c} should appear in plan, but plan was:\n" + plan,
        plan.contains("{c}"));
    // The edge index should be used as intersection pre-filter
    assertTrue(
        "Plan should show intersection pre-filter, but plan was:\n" + plan,
        plan.contains("intersection:"));

    session.commit();
  }

  // ---- Negative test: no pre-filter without index ----

  /**
   * Verify that an edge class without an indexed property on the WHERE clause
   * still produces correct results but does NOT trigger pre-filtering. The
   * EXPLAIN plan must not contain an index intersection descriptor for PFLikes.
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
    Set<String> expectedNames = new HashSet<>();
    for (int i = 5; i < 10; i++) {
      expectedNames.add("person" + i);
    }
    assertEquals(expectedNames, personNames);

    // Verify EXPLAIN does NOT show index intersection for the non-indexed edge
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse(
        "Plan should NOT contain any intersection descriptor for non-indexed"
            + " PFLikes, but plan was:\n" + plan,
        plan.contains("(intersection:"));

    session.commit();
  }

  // ---- Boundary and edge cases ----

  /**
   * Verify that an outE→inV query returns zero results when the WHERE clause
   * matches no edges (workFrom > 2999 matches nobody in the dataset).
   */
  @Test
  public void testOutEInVReturnsEmptyWhenNoEdgesMatch() {
    session.begin();

    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){where: (workFrom > 2999)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var result = session.query(query).toList();

    assertEquals(0, result.size());

    session.commit();
  }

  /**
   * Verify that a vertex with no outgoing edges of the traversed type
   * produces zero results without errors, even when the pre-filter is active.
   * Exercises the empty link bag path in applyPreFilter (size=0).
   */
  @Test
  public void testOutEInVWithIsolatedVertexProducesEmptyResult() {
    session.begin();

    // Create an isolated person with no PFWorkAt edges
    session.execute("CREATE VERTEX PFPerson set name = 'loner'").close();

    var query =
        "MATCH {class: PFPerson, as: p, where: (name = 'loner')}"
            + ".outE('PFWorkAt'){where: (workFrom < 2015)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var result = session.query(query).toList();

    assertEquals(0, result.size());

    session.commit();
  }

  /**
   * Verify correctness when exactly one edge matches a range predicate,
   * exercising a single-entry RID set in the pre-filter.
   * workFrom = 2019 for person9, so >= 2019 matches exactly one edge.
   */
  @Test
  public void testOutEInVSingleMatchRangePredicate() {
    session.begin();

    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){where: (workFrom >= 2019)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("person9", result.getFirst().getProperty("p.name"));
    assertEquals("company4", result.getFirst().getProperty("c.name"));

    session.commit();
  }

  /**
   * Verify that outE() without an edge class argument still returns correct
   * results (traverses all edge types). Class inference cannot apply, so
   * this exercises the null-currentEdgeClass path in addAliases.
   */
  @Test
  public void testOutEWithoutClassArgument() {
    session.begin();

    // outE() without class name: traverses all edge types from PFPerson.
    // Only PFWorkAt edges have the workFrom property; person5 has workFrom=2015.
    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE(){where: (workFrom = 2015)}"
            + ".inV(){as: target}"
            + " RETURN p.name, target.name";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("person5", result.getFirst().getProperty("p.name"));
    assertEquals("company0", result.getFirst().getProperty("target.name"));

    session.commit();
  }

  /**
   * Verify that multiple edges of the same class between the same vertex pair
   * are all returned when they match the WHERE clause. Exercises that the
   * pre-filter operates on edge RIDs, not vertex RIDs.
   */
  @Test
  public void testMultipleEdgesBetweenSameVertexPair() {
    session.begin();

    // person0 already works at company0 with workFrom=2010.
    // Add a second PFWorkAt edge from person0 to company0 with workFrom=2011.
    session.execute(
        "CREATE EDGE PFWorkAt FROM"
            + " (SELECT FROM PFPerson WHERE name = 'person0')"
            + " TO (SELECT FROM PFCompany WHERE name = 'company0')"
            + " SET workFrom = 2011")
        .close();

    var query =
        "MATCH {class: PFPerson, as: p, where: (name = 'person0')}"
            + ".outE('PFWorkAt'){where: (workFrom <= 2011)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var result = session.query(query).toList();

    // Should return 2 results: both edges match workFrom <= 2011
    assertEquals(2, result.size());
    for (var r : result) {
      assertEquals("person0", r.getProperty("p.name"));
      assertEquals("company0", r.getProperty("c.name"));
    }

    session.commit();
  }

  /**
   * Verify correctness when the WHERE clause matches all edges (workFrom >= 2010
   * matches all 10 edges). Pre-filter should either be skipped (ratio too high)
   * or produce correct results regardless.
   */
  @Test
  public void testOutEInVWhereMatchesAllEdges() {
    session.begin();

    var query =
        "MATCH {class: PFPerson, as: p}"
            + ".outE('PFWorkAt'){where: (workFrom >= 2010)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var result = session.query(query).toList();

    assertEquals(10, result.size());

    // Verify all 10 person→company pairs
    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("p.name") + "->" + r.getProperty("c.name"));
    }
    Set<String> expectedPairs = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      expectedPairs.add("person" + i + "->company" + (i % 5));
    }
    assertEquals(expectedPairs, pairs);

    session.commit();
  }

  /**
   * Verify that bothE() with an indexed edge property still produces correct
   * results via unfiltered traversal. bothE() is intentionally out of scope
   * for pre-filtering (returns ChainedIterable, not PreFilterableLinkBagIterable)
   * and silently degrades to no-op. This test documents the intentional
   * degradation and guards against regressions.
   */
  @Test
  public void testBothEDegradesToNoPreFilterButReturnsCorrectResults() {
    session.begin();

    // bothE('PFWorkAt') from company0 should find persons working there.
    // company0 has persons 0 and 5 (workFrom 2010, 2015).
    // Filter workFrom < 2015 -> only person0 matches.
    var query =
        "MATCH {class: PFCompany, as: c, where: (name = 'company0')}"
            + ".bothE('PFWorkAt'){where: (workFrom < 2015)}"
            + ".outV(){as: p}"
            + " RETURN p.name";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("person0", result.getFirst().getProperty("p.name"));

    // EXPLAIN should NOT show intersection descriptor for bothE
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertFalse(
        "bothE should NOT trigger pre-filter intersection, but plan was:\n"
            + plan,
        plan.contains("(intersection:"));

    session.commit();
  }
}
