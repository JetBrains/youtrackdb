package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * End-to-end SQL-level integration tests verifying that hash join optimizations
 * (anti-join for NOT patterns, semi-join and inner-join for secondary branches)
 * in MATCH queries produce correct results and are selected by the planner when
 * eligible.
 *
 * <p>Test graph structure (created in {@link #beforeTest}):
 * <pre>
 *   Person(n1) --Friend--> Person(n2) --Friend--> Person(n4)
 *   Person(n1) --Friend--> Person(n3) --Friend--> Person(n5)
 *   Person(n3) --Likes--> Tag(t1)
 *   Person(n2) --Likes--> Tag(t1)
 *   Person(n2) --Likes--> Tag(t2)
 *   Person(n4) --Likes--> Tag(t1)
 * </pre>
 *
 * <p>The Person class has small cardinality (5 records), well below
 * {@link MatchExecutionPlanner#HASH_JOIN_THRESHOLD}, so eligible patterns will use
 * hash join instead of nested-loop evaluation.
 */
public class HashJoinPlannerIntegrationTest extends DbTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.execute("CREATE class Person extends V").close();
    session.execute("CREATE class Tag extends V").close();
    session.execute("CREATE class Friend extends E").close();
    session.execute("CREATE class Likes extends E").close();

    session.begin();
    session.execute("CREATE VERTEX Person set name = 'n1'").close();
    session.execute("CREATE VERTEX Person set name = 'n2'").close();
    session.execute("CREATE VERTEX Person set name = 'n3'").close();
    session.execute("CREATE VERTEX Person set name = 'n4'").close();
    session.execute("CREATE VERTEX Person set name = 'n5'").close();

    session.execute("CREATE VERTEX Tag set name = 't1'").close();
    session.execute("CREATE VERTEX Tag set name = 't2'").close();

    // Friend edges: n1→n2, n1→n3, n2→n4, n3→n5
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n1')"
            + " to (select from Person where name='n2')")
        .close();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n1')"
            + " to (select from Person where name='n3')")
        .close();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n4')")
        .close();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n3')"
            + " to (select from Person where name='n5')")
        .close();

    // Likes edges: n3→t1, n2→t1, n2→t2, n4→t1
    session.execute(
        "CREATE EDGE Likes from (select from Person where name='n3')"
            + " to (select from Tag where name='t1')")
        .close();
    session.execute(
        "CREATE EDGE Likes from (select from Person where name='n2')"
            + " to (select from Tag where name='t1')")
        .close();
    session.execute(
        "CREATE EDGE Likes from (select from Person where name='n2')"
            + " to (select from Tag where name='t2')")
        .close();
    session.execute(
        "CREATE EDGE Likes from (select from Person where name='n4')"
            + " to (select from Tag where name='t1')")
        .close();
    session.commit();
  }

  // ── Plan shape tests ────────────────────────────────────────────────────

  /**
   * Verifies that an eligible NOT pattern (single shared alias, small cardinality,
   * no $matched) produces a plan with HashJoinMatchStep (HASH ANTI_JOIN).
   */
  @Test
  public void explainNotPattern_eligible_usesHashAntiJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " NOT {as:a}.out('Friend'){as:b, where:(name='n3')}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should use hash anti-join, got:\n" + plan,
        plan.contains("HASH ANTI_JOIN"));
    assertFalse("plan should NOT use nested-loop NOT step, got:\n" + plan,
        plan.contains("+ NOT ("));
    session.commit();
  }

  /**
   * Verifies that a NOT pattern with $matched reference falls back to nested-loop
   * (FilterNotMatchPatternStep), not hash join.
   */
  @Test
  public void explainNotPattern_matchedDependency_usesNestedLoop() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a}.out('Friend'){as:b},"
            + " NOT {as:b}.out('Likes'){where:($matched.a IS NOT null)}"
            + " RETURN a.name, b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should use nested-loop NOT step, got:\n" + plan,
        plan.contains("+ NOT ("));
    assertFalse("plan should NOT use hash anti-join, got:\n" + plan,
        plan.contains("HASH ANTI_JOIN"));
    session.commit();
  }

  // ── Correctness tests ──────────────────────────────────────────────────

  /**
   * Single shared alias: NOT pattern shares only the origin alias 'a'.
   * Query finds friends of n1 who are NOT friends with n3.
   * n1's friends are {n2, n3}. n3 is friends with {n5}. So n2 is not a friend
   * of n3, only n5 is. But the NOT checks {as:a}.out('Friend') which is about
   * a being friend with b where b=n3 — so this removes n3 from results.
   */
  @Test
  public void notPattern_singleSharedAlias_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " NOT {as:a}.out('Friend'){as:b, where:(name='n3')}"
            + " RETURN b.name as bName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Composite shared aliases: NOT pattern shares both 'friend' and 'tag' aliases
   * with the positive pattern (similar to IC4 structure).
   *
   * Positive pattern: Person(n1) → Friend → friend → Likes → tag
   *   n1→n2→{t1,t2}, n1→n3→{t1} → results: (n2,t1), (n2,t2), (n3,t1)
   *
   * NOT pattern: NOT {as:friend}.out('Friend'){}.out('Likes'){as:tag}
   *   n2→n4→t1 → (n2,t1) in NOT set
   *   n3→n5→(no Likes) → nothing
   * Anti-join removes (n2,t1) from positive results → (n2,t2), (n3,t1) remain.
   */
  @Test
  public void notPattern_compositeSharedAliases_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:p, where:(name='n1')}"
            + ".out('Friend'){as:friend}.out('Likes'){as:tag},"
            + " NOT {as:friend}.out('Friend'){}.out('Likes'){as:tag}"
            + " RETURN friend.name as fname, tag.name as tname")
        .toList();

    // NOT set = {(n2,t1)} → removed from positive results
    // Remaining: (n2,t2), (n3,t1)
    assertEquals(2, result.size());
    var names = result.stream()
        .map(r -> r.getProperty("fname") + ":" + r.getProperty("tname"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2:t2", "n3:t1"), names);
    session.commit();
  }

  /**
   * Empty NOT result: the NOT pattern matches zero records, so all upstream
   * rows pass through (hash set is empty → no filtering).
   */
  @Test
  public void notPattern_emptyBuildSide_allRowsPass() {
    session.begin();
    // n1 has no outgoing Likes edges to Person vertices, so build side is empty
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " NOT {as:a}.out('Likes'){as:b}"
            + " RETURN b.name as bName")
        .toList();
    // n1 has no Likes edges, so NOT build side is empty → n2 and n3 pass
    assertEquals(2, result.size());
    var names = result.stream()
        .map(r -> (String) r.getProperty("bName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2", "n3"), names);
    session.commit();
  }

  /**
   * All upstream filtered: NOT pattern matches all upstream rows, so no results.
   * n1's friends are {n2, n3}. NOT checks {as:a}.out('Friend'){as:b} — which
   * produces the same set, so all rows are filtered out.
   */
  @Test
  public void notPattern_allFiltered_noResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " NOT {as:a}.out('Friend'){as:b}"
            + " RETURN b.name as bName")
        .toList();
    assertEquals(0, result.size());
    session.commit();
  }

  // ── Semi-join tests ───────────────────────────────────────────────────

  /**
   * Diamond pattern correctness: two MATCH clauses share aliases 'a' and 't',
   * creating a diamond a→b→t, a→c→t. Only a and t are in RETURN, so one
   * branch's intermediate alias is not referenced downstream.
   *
   * Expected distinct (a,t) values: {(n1,t1), (n1,t2)} — both reachable
   * via at least one b and one c. The semi-join collapses the Cartesian
   * product over c, but the distinct value set is preserved.
   */
  @Test
  public void diamondPattern_semiJoinEligible_correctDistinctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name as aName, t.name as tName")
        .toList();

    assertFalse("diamond query should return results", result.isEmpty());
    var names = result.stream()
        .map(r -> r.getProperty("aName") + ":" + r.getProperty("tName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n1:t1", "n1:t2"), names);
    session.commit();
  }

  /**
   * Diamond pattern with intermediate alias in RETURN — should use INNER_JOIN
   * because the intermediate alias is needed downstream and the build-side row
   * values must be merged into the result.
   */
  @Test
  public void diamondPattern_intermediateInReturn_usesHashInnerJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name, b.name, c.name, t.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should use HASH INNER_JOIN when intermediate is in RETURN, got:\n"
        + plan, plan.contains("HASH INNER_JOIN"));
    session.commit();
  }

  /**
   * Diamond pattern with intermediate alias in RETURN — correctness test.
   * All four aliases (a, b, c, t) must appear in the result rows with correct
   * values, including the intermediate 'c' merged from the build side.
   */
  @Test
  public void diamondPattern_innerJoin_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name as aName, b.name as bName, c.name as cName,"
            + " t.name as tName")
        .toList();

    assertFalse("inner join query should return results", result.isEmpty());
    for (var row : result) {
      assertNotNull("a.name missing", row.getProperty("aName"));
      assertNotNull("b.name missing", row.getProperty("bName"));
      assertNotNull("c.name missing", row.getProperty("cName"));
      assertNotNull("t.name missing", row.getProperty("tName"));
    }
    session.commit();
  }

  /**
   * Diamond pattern EXPLAIN: when only shared aliases are in RETURN,
   * the planner should use hash semi-join for the secondary branch.
   */
  @Test
  public void explainDiamondPattern_semiJoinEligible_usesHashSemiJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name, t.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should use hash semi-join, got:\n" + plan,
        plan.contains("HASH SEMI_JOIN"));
    session.commit();
  }

  /**
   * Regression guard: existing semi-join behavior is preserved after the rename
   * refactoring (SemiJoinBranch → HashJoinBranch). Verifies both plan shape
   * (HASH SEMI_JOIN present) and result correctness for a diamond pattern with
   * intermediate alias NOT in RETURN.
   */
  @Test
  public void semiJoin_regressionGuard_preservedAfterRefactor() {
    session.begin();
    // Verify plan shape: semi-join should still be selected after refactoring
    var explainResult = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name as aName, t.name as tName")
        .toList();
    assertEquals(1, explainResult.size());
    String plan = explainResult.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should use hash semi-join after refactor, got:\n" + plan,
        plan.contains("HASH SEMI_JOIN"));

    // Verify correctness
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name as aName, t.name as tName")
        .toList();
    assertFalse("semi-join query should return results", result.isEmpty());
    var names = result.stream()
        .map(r -> r.getProperty("aName") + ":" + r.getProperty("tName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n1:t1", "n1:t2"), names);
    session.commit();
  }

  // ── Runtime fallback tests (threshold exceeded → nested-loop) ─────────

  /**
   * Forces the runtime threshold to 1, so any build set with >1 entry
   * exceeds it and triggers the nestedLoopProbe fallback path in
   * HashJoinMatchStep. Verifies that ANTI_JOIN still produces correct
   * results when falling back to per-row nested-loop evaluation.
   *
   * The build side for this NOT pattern produces 2 entries (n2 and n3,
   * friends of n1), which exceeds the threshold of 1.
   */
  @Test
  public void runtimeFallback_antiJoin_correctResults() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      // Threshold=1: any build set with >1 entry triggers nested-loop fallback
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

      session.begin();
      var result = session.query(
          "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
              + " NOT {as:a}.out('Friend'){as:b, where:(name='n3')}"
              + " RETURN b.name as bName")
          .toList();
      // NOT removes n3 from {n2, n3} → only n2 remains
      assertEquals(1, result.size());
      assertEquals("n2", result.get(0).getProperty("bName"));
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Forces the runtime threshold to 1 to trigger the nestedLoopProbe
   * fallback for SEMI_JOIN. Diamond pattern with intermediates NOT in
   * RETURN — planner selects SEMI_JOIN, but at runtime the build set
   * exceeds threshold=1 and falls back to nested-loop.
   */
  @Test
  public void runtimeFallback_semiJoin_correctResults() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

      session.begin();
      var result = session.query(
          "MATCH {class:Person, as:a, where:(name='n1')}"
              + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
              + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
              + " RETURN a.name as aName, t.name as tName")
          .toList();

      assertFalse("semi-join fallback should still return results",
          result.isEmpty());
      var names = result.stream()
          .map(r -> r.getProperty("aName") + ":" + r.getProperty("tName"))
          .collect(Collectors.toSet());
      assertEquals(Set.of("n1:t1", "n1:t2"), names);
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Forces the runtime threshold to 1 to trigger the nestedLoopInnerJoin
   * fallback for INNER_JOIN. Diamond pattern with intermediate alias 'c'
   * in RETURN — planner selects INNER_JOIN, but at runtime the build map
   * exceeds threshold=1 and falls back to nested-loop.
   */
  @Test
  public void runtimeFallback_innerJoin_correctResults() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

      session.begin();
      var result = session.query(
          "MATCH {class:Person, as:a, where:(name='n1')}"
              + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
              + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
              + " RETURN a.name as aName, b.name as bName,"
              + " c.name as cName, t.name as tName")
          .toList();

      assertFalse("inner-join fallback should still return results",
          result.isEmpty());
      // Every row must have all four aliases populated
      for (var row : result) {
        assertNotNull("a.name missing", row.getProperty("aName"));
        assertNotNull("b.name missing", row.getProperty("bName"));
        assertNotNull("c.name missing", row.getProperty("cName"));
        assertNotNull("t.name missing", row.getProperty("tName"));
      }
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Setting the threshold to 0 should disable hash join optimization entirely.
   * The planner should fall back to nested-loop (FilterNotMatchPatternStep)
   * for NOT patterns, showing "+ NOT (" instead of "HASH ANTI_JOIN" in EXPLAIN.
   */
  @Test
  public void thresholdZero_disablesHashJoin() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);

      session.begin();
      var result = session.query(
          "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
              + ".out('Friend'){as:b},"
              + " NOT {as:a}.out('Friend'){as:b, where:(name='n3')}"
              + " RETURN b.name")
          .toList();
      assertEquals(1, result.size());
      String plan = result.get(0).getProperty("executionPlanAsString");
      assertNotNull(plan);
      assertFalse("threshold=0 should disable hash join, got:\n" + plan,
          plan.contains("HASH ANTI_JOIN"));
      assertTrue("threshold=0 should use nested-loop NOT step, got:\n" + plan,
          plan.contains("+ NOT ("));
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  // ── Back-reference hash join tests (Pattern A) ─────────────────────────

  /**
   * Pattern A — single-edge back-reference semi-join. The query traverses
   * from person n1 to friends, then checks each friend via a back-reference
   * against n1's known friends. This is the simplest back-reference pattern:
   * {@code .out('Friend'){target, where: (@rid = $matched.a.@rid)}}.
   *
   * <p>Expected: EXPLAIN shows BACK-REF HASH JOIN instead of a regular MatchStep
   * with EdgeRidLookup intersection.
   */
  @Test
  public void explainBackRef_singleEdge_usesBackRefHashJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c}"
            + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "plan should use back-ref hash join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));
    session.commit();
  }

  /**
   * Pattern A — correctness test. Creates a cycle: n1→n2→n4, and checks
   * if traversing n4.out('Friend') reaches n1 via back-reference.
   * Since n4 has no outgoing Friend edges to n1 in our graph, the query
   * should return no results.
   */
  @Test
  public void backRef_singleEdge_noMatch_emptyResult() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c}"
            + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();
    // n1→n2→n4→(no outgoing Friend to n1), n1→n3→n5→(no outgoing Friend to n1)
    assertEquals(0, result.size());
    session.commit();
  }

  /**
   * Pattern A — correctness with a cycle that DOES match. Creates a
   * Friend edge from n2 back to n1, forming a cycle n1→n2→n1.
   * The back-ref check should find n1 via n2.out('Friend').
   */
  @Test
  public void backRef_singleEdge_withCycle_findsMatch() {
    session.begin();
    // Add a cycle: n2→n1
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();

    // n1→n2→(check: n2.out('Friend') includes {n1,n4}), @rid=$matched.a.@rid
    // means check.@rid must equal n1.@rid. n2.out('Friend') contains n1
    // (the edge we just added) and n4. Only n1 matches → b=n2 is a result.
    // n1→n3→(check: n3.out('Friend') includes {n5}), no match → filtered out.
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Pattern A — threshold=0 disables back-ref hash join. The planner should
   * fall back to standard MatchStep with EdgeRidLookup intersection.
   */
  @Test
  public void backRef_thresholdZero_fallsBackToMatchStep() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);

      session.begin();
      var result = session.query(
          "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
              + ".out('Friend'){as:b}"
              + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
              + " RETURN b.name")
          .toList();
      assertEquals(1, result.size());
      String plan = result.get(0).getProperty("executionPlanAsString");
      assertNotNull(plan);
      assertFalse(
          "threshold=0 should disable back-ref hash join, got:\n" + plan,
          plan.contains("BACK-REF HASH JOIN"));
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  // ── Back-reference hash join tests (Pattern B — outE+inV chain) ─────

  /**
   * Pattern B — outE('E').inV() chain with back-reference on the .inV()
   * target should use ChainSemiJoin, collapsing two edges into one step.
   * EXPLAIN should show BACK-REF HASH JOIN with both aliases.
   */
  @Test
  public void explainBackRef_outEInV_usesChainSemiJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e}.inV(){as:b}"
            + ".outE('Friend'){as:e2}.inV(){as:check,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "outE+inV chain should use back-ref hash join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));
    session.commit();
  }

  /**
   * Pattern B — correctness test. Uses the same Person/Friend graph with
   * outE().inV() traversal. Creates a cycle n2→n1 to have a matching result.
   */
  @Test
  public void backRef_outEInV_withCycle_correctResults() {
    session.begin();
    // Add a cycle: n2→n1
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e1}.inV(){as:b}"
            + ".outE('Friend'){as:e2}.inV(){as:check,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();

    // n1→(e1)→n2→(e2)→n1 matches (check.@rid == a.@rid == n1)
    // n1→(e1)→n3→(e2)→n5 doesn't match (n5 != n1)
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Pattern B — correctness test with fan-out. When multiple edges from
   * the source vertex match, the chain semi-join should emit one row per
   * matching edge (intermediate alias gets each edge record).
   */
  @Test
  public void backRef_outEInV_fanOut_multipleEdges() {
    session.begin();
    // Add two edges from n2 back to n1 to test fan-out
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e1}.inV(){as:b}"
            + ".outE('Friend'){as:e2}.inV(){as:check,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName, e2 as edge2")
        .toList();

    // n2 has 2 outgoing Friend edges to n1. Each should produce a result row.
    // b=n2 with 2 different e2 edge records.
    assertTrue("fan-out should produce multiple rows, got " + result.size(),
        result.size() >= 2);
    for (var row : result) {
      assertEquals("n2", row.getProperty("bName"));
      assertNotNull("edge2 should be populated", row.getProperty("edge2"));
    }
    session.commit();
  }

  /**
   * Pattern B — threshold=0 disables chain semi-join. Falls back to regular
   * MatchStep for both the outE and inV edges.
   */
  @Test
  public void backRef_outEInV_thresholdZero_fallsBack() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);

      session.begin();
      var result = session.query(
          "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
              + ".outE('Friend'){as:e}.inV(){as:b}"
              + ".outE('Friend'){as:e2}.inV(){as:check,"
              + " where: (@rid = $matched.a.@rid)}"
              + " RETURN b.name")
          .toList();
      assertEquals(1, result.size());
      String plan = result.get(0).getProperty("executionPlanAsString");
      assertNotNull(plan);
      assertFalse(
          "threshold=0 should disable chain semi-join, got:\n" + plan,
          plan.contains("BACK-REF HASH JOIN"));
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  // ── Back-reference hash join tests (Pattern D — NOT IN anti-join) ──

  /**
   * Pattern D — IC10-style NOT IN exclusion. The query finds friends-of-friends
   * of n1, excluding direct friends of n1.
   *
   * Graph: n1→n2, n1→n3, n2→n4, n3→n5
   * n1.out('Friend') = {n2, n3}
   * n1.out('Friend').out('Friend') = {n4, n5}
   * After NOT IN n1.out('Friend'): n4 and n5 remain (they are NOT direct friends)
   */
  @Test
  public void explainBackRef_notIn_usesAntiSemiJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
            + " RETURN fof.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "plan should use BACK-REF HASH JOIN ANTI, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN ANTI"));
    session.commit();
  }

  /**
   * Pattern D — correctness test. Friends-of-friends of n1, excluding
   * direct friends. n1's direct friends are {n2, n3}. FoF are {n4, n5}.
   * Neither n4 nor n5 is a direct friend of n1, so both should appear.
   */
  @Test
  public void backRef_notIn_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
            + " RETURN fof.name as fofName")
        .toList();

    var names = result.stream()
        .map(r -> (String) r.getProperty("fofName"))
        .collect(Collectors.toSet());
    // n2→n4, n3→n5. Neither n4 nor n5 is in n1.out('Friend')={n2,n3}
    assertEquals(Set.of("n4", "n5"), names);
    session.commit();
  }

  /**
   * Pattern D — correctness with exclusion. Add n4 as a direct friend of n1,
   * then n4 should be excluded from FoF results because it IS in
   * n1.out('Friend').
   */
  @Test
  public void backRef_notIn_withExclusion_correctResults() {
    session.begin();
    // Make n4 a direct friend of n1
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n1')"
            + " to (select from Person where name='n4')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
            + " RETURN fof.name as fofName")
        .toList();

    var names = result.stream()
        .map(r -> (String) r.getProperty("fofName"))
        .collect(Collectors.toSet());
    // n4 is now a direct friend of n1, so it's excluded. Only n5 remains.
    assertEquals(Set.of("n5"), names);
    session.commit();
  }

  /**
   * Pattern D — threshold=0 disables anti-semi-join. Falls back to standard
   * WHERE evaluation with NOT IN.
   */
  @Test
  public void backRef_notIn_thresholdZero_fallsBack() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(0L);

      session.begin();
      var result = session.query(
          "EXPLAIN MATCH {class:Person, as:start, where:(name='n1')}"
              + ".out('Friend'){as:friend}"
              + ".out('Friend'){as:fof,"
              + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
              + " RETURN fof.name")
          .toList();
      assertEquals(1, result.size());
      String plan = result.get(0).getProperty("executionPlanAsString");
      assertNotNull(plan);
      assertFalse(
          "threshold=0 should disable anti-semi-join, got:\n" + plan,
          plan.contains("BACK-REF HASH JOIN ANTI"));
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

}
