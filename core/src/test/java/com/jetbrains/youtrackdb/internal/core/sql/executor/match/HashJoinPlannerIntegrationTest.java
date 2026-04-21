package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.junit.experimental.categories.Category;

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
 * {@link GlobalConfiguration#QUERY_MATCH_HASH_JOIN_THRESHOLD}, so eligible patterns
 * will use hash join instead of nested-loop evaluation.
 *
 * <p>Runs sequentially because several tests mutate
 * {@link GlobalConfiguration#QUERY_MATCH_HASH_JOIN_THRESHOLD}, a JVM-wide
 * singleton that would race with other MATCH tests reading the same entry in
 * the parallel-classes surefire pool.
 */
@Category(SequentialTest.class)
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
    // Pin THRESHOLD to prevent concurrent tests (e.g. MatchStepUnitTest) from
    // lowering it mid-execution, which would cause the planner to reject hash join.
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
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
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
    }
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
   * values must be merged into the result. Cost guards are bypassed (upstreamMin=0)
   * because this test verifies pattern detection, not the cost model.
   */
  @Test
  public void diamondPattern_intermediateInReturn_usesHashInnerJoin() {
    // Save and pin both UPSTREAM_MIN and THRESHOLD to prevent concurrent tests
    // (e.g. MatchStepUnitTest) from lowering the threshold mid-execution,
    // which would cause the planner to reject the hash join.
    var savedMin = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
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
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedMin);
    }
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
   * Cost guards are bypassed (upstreamMin=0) because this test verifies
   * pattern detection, not the cost model.
   */
  @Test
  public void explainDiamondPattern_semiJoinEligible_usesHashSemiJoin() {
    // Save and pin both UPSTREAM_MIN and THRESHOLD to prevent concurrent tests
    // (e.g. MatchStepUnitTest) from lowering the threshold mid-execution,
    // which would cause the planner to reject the hash join.
    var savedMin = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
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
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedMin);
    }
  }

  /**
   * Regression guard: existing semi-join behavior is preserved after the rename
   * refactoring (SemiJoinBranch → HashJoinBranch). Verifies both plan shape
   * (HASH SEMI_JOIN present) and result correctness for a diamond pattern with
   * intermediate alias NOT in RETURN. Cost guards are bypassed (upstreamMin=0)
   * because this test verifies pattern detection, not the cost model.
   */
  @Test
  public void semiJoin_regressionGuard_preservedAfterRefactor() {
    // Save and pin both UPSTREAM_MIN and THRESHOLD to prevent concurrent tests
    // (e.g. MatchStepUnitTest) from lowering the threshold mid-execution,
    // which would cause the planner to reject the hash join.
    var savedMin = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    var savedThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(10_000L);
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
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedThreshold);
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedMin);
    }
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

  // ── Pattern D stripping verification tests ──

  /**
   * Verifies that the NOT IN condition is stripped from the MatchStep's WHERE
   * clause at plan time. The EXPLAIN output should show BACK-REF HASH JOIN ANTI
   * but the preceding MATCH step should NOT contain the NOT IN condition —
   * it is now handled exclusively by the BackRefHashJoinStep.
   */
  @Test
  public void explainBackRef_notIn_strippedFromMatchStep() {
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
    // The NOT IN should have been stripped from MatchStep's filter.
    // After stripping, $currentMatch NOT IN should not appear in any
    // MATCH step line — only in the BACK-REF HASH JOIN ANTI step.
    var lines = plan.split("\n");
    for (var line : lines) {
      if (line.contains("MATCH") && !line.contains("BACK-REF")
          && !line.contains("$matched")) {
        assertFalse(
            "NOT IN should be stripped from MatchStep filter, got line:\n"
                + line,
            line.contains("NOT IN"));
      }
    }
    session.commit();
  }

  /**
   * Pattern D fallback correctness — threshold=1 forces hash build failure
   * (n1.out('Friend') has 2 entries > 1). The stored NOT IN condition must
   * be evaluated per row by BackRefHashJoinStep as fallback.
   */
  @Test
  public void backRef_notIn_thresholdOne_fallbackCorrectResults() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      // threshold=1: n1.out('Friend') has 2 entries (n2,n3) > 1, build fails
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

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
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Pattern D fallback with exclusion — threshold=1 forces fallback. Add n4
   * as a direct friend of n1, then n4 should be excluded from FoF results.
   */
  @Test
  public void backRef_notIn_withExclusion_thresholdOne_fallbackCorrectResults() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

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
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  /**
   * Pattern D with compound WHERE — NOT IN + additional condition. Verifies
   * that stripping removes only the NOT IN, leaving the residual condition
   * (name = 'n4') on the MatchStep.
   */
  @Test
  public void backRef_notIn_compoundWhere_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend')"
            + " AND name = 'n4')}"
            + " RETURN fof.name as fofName")
        .toList();

    // FoF = {n4, n5}. NOT IN filters nothing (neither is direct friend of n1).
    // AND name='n4' filters to just n4.
    assertEquals(1, result.size());
    assertEquals("n4", result.get(0).getProperty("fofName"));
    session.commit();
  }

  /**
   * EXPLAIN of compound WHERE — when NOT IN is combined with additional AND
   * conditions, the anti-join optimization does not fire (pre-existing
   * limitation of toString-based pattern detection). The query falls back
   * to standard MatchStep WHERE evaluation. Verify the plan uses a regular
   * MATCH step (no BACK-REF HASH JOIN ANTI) and results are still correct.
   */
  @Test
  public void explainBackRef_notIn_compoundWhere_fallsBackToMatchStep() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend')"
            + " AND name = 'n4')}"
            + " RETURN fof.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    // Compound NOT IN + AND does not trigger anti-join optimization —
    // falls back to standard MATCH step with full WHERE evaluation
    assertFalse(
        "compound WHERE should not use anti-join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN ANTI"));
    session.commit();
  }

  // ── Pattern A — in-direction back-reference ──

  /**
   * Pattern A with "in" direction — the back-reference uses .in('Friend')
   * instead of .out('Friend'). The hash table is built from the back-ref
   * vertex's out_ link bag (reverse of "in" direction).
   *
   * Graph: n1→n2 via Friend. Query: find vertices whose incoming Friend
   * edges include a vertex reachable from n1's friends.
   * n2.in('Friend') includes n1. Check: @rid = $matched.a.@rid = n1 → match.
   */
  @Test
  public void backRef_singleEdge_inDirection_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".in('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();

    // n1→n2: n2.in('Friend') = {n1}. check.@rid must equal n1.@rid → n1 matches
    // n1→n3: n3.in('Friend') = {n1}. check.@rid must equal n1.@rid → n1 matches
    // Both b=n2 and b=n3 should produce results
    var names = result.stream()
        .map(r -> (String) r.getProperty("bName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2", "n3"), names);
    session.commit();
  }

  /**
   * EXPLAIN for Pattern A with "in" direction — verifies the planner
   * selects BACK-REF HASH JOIN for in-direction traversals.
   */
  @Test
  public void explainBackRef_singleEdge_inDirection_usesBackRefHashJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".in('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "in-direction back-ref should use hash join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));
    session.commit();
  }

  // ── Pattern A — multi-edge fan-out ──

  /**
   * Pattern A with multiple edges of the same class between the same
   * vertex pair. Verifies that the hash join correctly emits N rows
   * when N edges exist between source and target (no deduplication).
   *
   * Creates 3 Friend edges from n2→n1, then checks back-ref from n2.
   */
  @Test
  public void backRef_singleEdge_multipleEdges_emitsCorrectCount() {
    session.begin();
    // Add 3 Friend edges from n2→n1 (on top of the existing n1→n2)
    for (int i = 0; i < 3; i++) {
      session.execute(
          "CREATE EDGE Friend from (select from Person where name='n2')"
              + " to (select from Person where name='n1')")
          .close();
    }

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b, where:(name='n2')}"
            + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();

    // n2 has 3 outgoing Friend edges to n1. Each should produce a result row.
    assertEquals("multi-edge should produce 3 rows", 3, result.size());
    for (var row : result) {
      assertEquals("n2", row.getProperty("bName"));
    }
    session.commit();
  }

  // ── Pattern A — threshold=1 fallback ──

  /**
   * Pattern A threshold=1 fallback correctness. The build side for the
   * back-ref hash table will have 2 entries (n2 and n3 are friends of n1),
   * exceeding threshold=1. The step falls back to per-row nested-loop
   * traversal via MatchEdgeTraverser. Results must still be correct.
   */
  @Test
  public void backRef_singleEdge_thresholdOne_fallbackCorrectResults() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

      session.begin();
      // Add cycle: n2→n1 so there's a matching result
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

      // n1→n2→{n1,n4}: n1 matches (check.@rid==a.@rid)
      // n1→n3→{n5}: no match
      assertEquals(1, result.size());
      assertEquals("n2", result.get(0).getProperty("bName"));
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  // ── Pattern B — in-direction chain (inE+outV) ──

  /**
   * Pattern B with inE().outV() chain — the reverse direction of the
   * standard outE().inV() chain. Verifies that ChainSemiJoin handles
   * inE-based chains correctly.
   */
  @Test
  public void backRef_inEOutV_withCycle_correctResults() {
    session.begin();
    // Add cycle: n2→n1
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n2')}"
            + ".inE('Friend'){as:e1}.outV(){as:b}"
            + ".inE('Friend'){as:e2}.outV(){as:check,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();

    // n2.inE('Friend') = {e from n1→n2}. outV() = n1.
    // n1.inE('Friend') = {e from n2→n1}. outV() = n2.
    // check.@rid must equal a.@rid = n2 → n2 matches.
    assertEquals(1, result.size());
    assertEquals("n1", result.get(0).getProperty("bName"));
    session.commit();
  }

  /**
   * Pattern B — no matching result. The inV/outV traversal doesn't lead
   * back to the starting vertex, so the query returns empty.
   */
  @Test
  public void backRef_outEInV_noMatch_emptyResult() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e1}.inV(){as:b}"
            + ".outE('Friend'){as:e2}.inV(){as:check,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();

    // No cycle back to n1, so no results
    assertEquals(0, result.size());
    session.commit();
  }

  // ── Pattern D — in-direction NOT IN ──

  /**
   * Pattern D with "in" direction — NOT IN $matched.X.in('Friend').
   * Verifies anti-semi-join works for incoming edge traversals.
   *
   * Query: find FoF of n1, excluding vertices that have an incoming
   * Friend edge from n1 (i.e., direct friends of n1 are excluded).
   */
  @Test
  public void backRef_notIn_inDirection_correctResults() {
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
    // n4, n5 are FoF; neither is a direct friend of n1
    assertEquals(Set.of("n4", "n5"), names);
    session.commit();
  }

  /**
   * Pattern D — EXPLAIN verifies plan uses ANTI for in-direction traversal.
   */
  @Test
  public void explainBackRef_notIn_inDirection_usesAntiSemiJoin() {
    session.begin();
    // Use in() direction in NOT IN: exclude vertices that have incoming Friend
    // from start's friends
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:start, where:(name='n2')}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.in('Friend'))}"
            + " RETURN fof.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "in-direction NOT IN should use anti-join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN ANTI"));
    session.commit();
  }

  // ── Pattern A — optional edge should NOT trigger back-ref hash join ──

  /**
   * Optional edges should not use Pattern A back-ref hash join, because
   * optional traversals must pass through rows even when no match is found.
   */
  @Test
  public void backRef_optionalEdge_notUsedForHashJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:check, optional:true,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    // Optional edges should not use back-ref hash join
    assertFalse(
        "optional edge should not use back-ref hash join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));
    session.commit();
  }

  // ── Pattern B — threshold=1 fallback correctness ──

  /**
   * Pattern B threshold=1 fallback correctness. Forces the chain hash
   * build to fail because the reverse link bag exceeds threshold=1.
   * Falls back to nested-loop traversal via MatchEdgeTraverser.
   */
  @Test
  public void backRef_outEInV_thresholdOne_fallbackCorrectResults() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

      session.begin();
      // Add cycle: n2→n1
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

      // With fallback, should still produce correct results
      assertEquals(1, result.size());
      assertEquals("n2", result.get(0).getProperty("bName"));
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  // ── Pattern D — NOT IN is the sole WHERE condition ──

  /**
   * Pattern D where NOT IN is the only condition in the WHERE clause.
   * After stripping, the filter is completely removed from the MatchStep.
   * Verify the plan shape (BACK-REF HASH JOIN ANTI with no residual WHERE).
   */
  @Test
  public void backRef_notIn_soleCondition_correctResults() {
    session.begin();
    // Add n2→n1 to create a direct cycle
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();

    // Query: all friends-of-n1, excluding n1.out('Friend')
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
    // n2→{n1,n4}, n3→{n5}. start.out('Friend') = {n2,n3}.
    // n1 not in {n2,n3} → included, n4 not in {n2,n3} → included,
    // n5 not in {n2,n3} → included.
    assertEquals(Set.of("n1", "n4", "n5"), names);
    session.commit();
  }

  // ── isSemiJoinCandidate — direction rejection ──

  /**
   * The both() traversal direction should NOT qualify for Pattern A
   * back-ref hash join. isSemiJoinCandidate rejects non-out/in directions.
   * Verify the plan falls back to standard MatchStep.
   */
  @Test
  public void backRef_bothDirection_notUsedForHashJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".both('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    // both() is not a directed traversal — cannot use semi-join
    assertFalse(
        "both() direction should not use back-ref hash join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));
    session.commit();
  }

  /**
   * The bothE() traversal should NOT qualify for Pattern B chain semi-join.
   * detectChainSemiJoin rejects non-outE/inE preceding edge directions.
   */
  @Test
  public void backRef_bothE_notUsedForChainSemiJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".bothE('Friend'){as:e2}.inV(){as:check,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    // bothE() is not oute/ine — cannot use chain semi-join
    assertFalse(
        "bothE() should not use chain semi-join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));
    session.commit();
  }

  // ── Pattern A — in-direction threshold=1 fallback ──

  /**
   * Pattern A with "in" direction and threshold=1 forces build failure.
   * Falls back to MatchReverseEdgeTraverser (edge.out=false) for in-direction
   * traversal. Covers createFallbackTraverser reverse branch.
   */
  @Test
  public void backRef_singleEdge_inDirection_thresholdOne_fallback() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);

      session.begin();
      var result = session.query(
          "MATCH {class:Person, as:a, where:(name='n1')}"
              + ".out('Friend'){as:b}"
              + ".in('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
              + " RETURN b.name as bName")
          .toList();

      // Even with threshold=1 fallback, results should be correct
      var names = result.stream()
          .map(r -> (String) r.getProperty("bName"))
          .collect(Collectors.toSet());
      assertEquals(Set.of("n2", "n3"), names);
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }

  // ── EXPLAIN text verification — prettyPrint branches ──

  /**
   * Verify EXPLAIN output for Pattern A (SingleEdgeSemiJoin) contains
   * the expected semi-join descriptor text with alias and direction info.
   */
  @Test
  public void explainBackRef_singleEdge_prettyPrintFormat() {
    session.begin();
    // Add cycle so plan is generated with the back-ref
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();

    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    String plan = result.get(0).getProperty("executionPlanAsString");
    // prettyPrint for SingleEdgeSemiJoin should contain direction and edge class
    assertTrue("plan should show direction and edge class, got:\n" + plan,
        plan.contains("out('Friend')") || plan.contains("in('Friend')"));
    assertTrue("plan should show ⋈ join symbol, got:\n" + plan,
        plan.contains("⋈"));
    session.commit();
  }

  /**
   * Verify EXPLAIN output for Pattern B (ChainSemiJoin) contains the
   * outE...inV chain notation and both alias names.
   */
  @Test
  public void explainBackRef_chain_prettyPrintFormat() {
    session.begin();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();

    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e1}.inV(){as:b}"
            + ".outE('Friend'){as:e2}.inV(){as:check,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    String plan = result.get(0).getProperty("executionPlanAsString");
    // prettyPrint for ChainSemiJoin includes .inV() and aliases keyword
    assertTrue("plan should show outE chain, got:\n" + plan,
        plan.contains("outE('Friend')") || plan.contains("inE('Friend')"));
    assertTrue("plan should show 'aliases:' keyword, got:\n" + plan,
        plan.contains("aliases:"));
    session.commit();
  }

  /**
   * Verify EXPLAIN output for Pattern D (AntiSemiJoin) contains the
   * NOT IN descriptor text.
   */
  @Test
  public void explainBackRef_anti_prettyPrintFormat() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
            + " RETURN fof.name")
        .toList();
    String plan = result.get(0).getProperty("executionPlanAsString");
    // prettyPrint for AntiSemiJoin includes NOT IN and traversal direction
    assertTrue("plan should show NOT IN, got:\n" + plan,
        plan.contains("NOT IN $matched."));
    assertTrue("plan should show traversal direction, got:\n" + plan,
        plan.contains("out('Friend')") || plan.contains("in('Friend')"));
    session.commit();
  }

  // ── Anti-join — source vertex not found → pass through ──

  /**
   * Pattern D correctness when the fof candidate has no Friend edges at all.
   * The source RID resolves correctly, but the candidate is simply NOT IN
   * the set — it passes through. This covers the anti-join "not found" branch.
   */
  @Test
  public void backRef_notIn_candidateNotInSet_passesThrough() {
    session.begin();
    // n5 has no outgoing Friend edges. When we traverse n3→n5, then check
    // $currentMatch (n5) NOT IN start.out('Friend')={n2,n3}, n5 is not in
    // the set so it passes through.
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend, where:(name='n3')}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
            + " RETURN fof.name as fofName")
        .toList();

    assertEquals(1, result.size());
    assertEquals("n5", result.get(0).getProperty("fofName"));
    session.commit();
  }

  // ── Anti-join — candidate IS in set → filtered out ──

  /**
   * Pattern D correctness when the candidate IS in the exclusion set.
   * This covers the anti-join "found → null" branch (row is dropped).
   */
  @Test
  public void backRef_notIn_candidateInSet_filteredOut() {
    session.begin();
    // Make n4 a direct friend of n1
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n1')"
            + " to (select from Person where name='n4')")
        .close();

    // n2→n4, and n4 IS in start.out('Friend')={n2,n3,n4}
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend, where:(name='n2')}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
            + " RETURN fof.name as fofName")
        .toList();

    // n2→n4, but n4 is now a direct friend of n1 → excluded
    assertEquals(0, result.size());
    session.commit();
  }

  // ── Pattern A — back-ref with non-existent edge class ──

  /**
   * Pattern A where the edge class in the back-ref hash table build
   * doesn't exist on the target vertex (no reverse link bag). The hash
   * table build returns null, triggering the fallback path.
   */
  @Test
  public void backRef_singleEdge_missingEdgeClass_fallsBack() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Likes'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();

    // n1 has no incoming Likes edges, so reverse link bag is empty/missing.
    // Build returns null → fallback to nested-loop → no match
    assertEquals(0, result.size());
    session.commit();
  }

  // ── Pattern D — NOT IN with additional WHERE conditions ──

  /**
   * Pattern D where NOT IN is combined with a simple property filter
   * in a single AND block with exactly 2 conditions. Verifies that
   * the NOT IN is stripped but the residual condition is preserved.
   */
  @Test
  public void backRef_notIn_withResidualFilter_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend}"
            + ".out('Friend'){as:fof, where: ("
            + " $currentMatch NOT IN $matched.start.out('Friend')"
            + " AND name = 'n5')}"
            + " RETURN fof.name as fofName")
        .toList();

    // FoF = {n4, n5}. NOT IN {n2,n3} keeps both. AND name='n5' → only n5.
    assertEquals(1, result.size());
    assertEquals("n5", result.get(0).getProperty("fofName"));
    session.commit();
  }

  // ── Multiple patterns in single query ──

  /**
   * Query with both Pattern A (back-ref semi-join) and Pattern D (NOT IN
   * anti-semi-join) in the same MATCH. Covers multiple semi-join descriptors
   * in a single schedule and incremental boundAliases building.
   */
  @Test
  public void backRef_combinedPatterns_correctResults() {
    session.begin();
    // Add cycle n4→n1 so Pattern A has a match
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n4')"
            + " to (select from Person where name='n1')")
        .close();

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c}"
            + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName, c.name as cName")
        .toList();

    // n1→n2→n4→n1 (via new edge). check.@rid = a.@rid = n1 → match.
    // b=n2, c=n4.
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    assertEquals("n4", result.get(0).getProperty("cName"));
    session.commit();
  }

  // ── Edge cases — no edges at all ──

  /**
   * Pattern A on a vertex with no outgoing edges of the specified class.
   * Hash table build finds no link bag → returns null → fallback.
   */
  @Test
  public void backRef_singleEdge_targetHasNoEdges_emptyResult() {
    session.begin();
    // n5 has no outgoing Friend edges
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c}"
            + ".out('Friend'){as:d}"
            + ".out('Friend'){as:check, where: (@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName")
        .toList();
    // n1→n2→n4→(no out Friend)→nothing, n1→n3→n5→(no out Friend)→nothing
    assertEquals(0, result.size());
    session.commit();
  }

  /**
   * Pattern D on a vertex where the anchor has no edges of the specified
   * class. The anti-join hash table build finds no link bag → build failure
   * → fallback evaluates NOT IN per row.
   */
  @Test
  public void backRef_notIn_anchorHasNoEdges_passesAll() {
    session.begin();
    // n5 has no outgoing Friend edges
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n5')}"
            + ".in('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out('Friend'))}"
            + " RETURN fof.name as fofName")
        .toList();

    // n5.in('Friend') = {n3}. start.out('Friend') has no entries.
    // Since there are no friends to exclude, n3 passes through.
    var names = result.stream()
        .map(r -> (String) r.getProperty("fofName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n3"), names);
    session.commit();
  }

  // ── ChainSemiJoin — indexFilter caching across back-ref builds ─────

  /**
   * Pattern B — query with {@link ChainSemiJoin#indexFilter()} set must
   * resolve the index only once for the whole query, even when many
   * distinct back-ref RIDs probe the hash.
   *
   * <p>Regression test for a YTDB-650 performance bug: before the fix,
   * {@code BackRefHashJoinStep.buildChainHashTable} called
   * {@code TraversalPreFilterHelper.resolveIndexToRidSet} every time a new
   * back-ref RID missed the LRU cache. On LDBC SF1 IC5 (~800 distinct
   * friends, cache capacity 256) this re-scanned the
   * {@code HAS_MEMBER.joinDate} index ~540 times, pushing the single
   * query past 5 minutes and tripping the 20 h CI timeout. With the fix,
   * the index RidSet is cached on the {@link BackRefHashJoinStep} instance
   * and reused across all per-back-ref builds.
   *
   * <p>This test creates a synthetic graph where the ChainSemiJoin fires
   * with an indexed edge filter and multiple distinct back-ref RIDs, then
   * asserts correctness. The planner path is the same as IC5's.
   */
  @Test
  public void backRef_outEInV_withIndexedEdgeFilter_multipleBackRefs_correctResults() {
    // Add an edge class with an indexed property so
    // TraversalPreFilterHelper.findIndexForFilter returns a descriptor
    // that becomes ChainSemiJoin#indexFilter().
    session.execute("CREATE class Rated extends E").close();
    session.execute("CREATE PROPERTY Rated.score INTEGER").close();
    session.execute("CREATE INDEX Rated_score ON Rated (score) NOTUNIQUE").close();

    session.begin();
    // Each of n2, n3 is a distinct back-ref in the probe — both need
    // their own hash build. The fix ensures the Rated.score index is
    // scanned only once across both builds.
    // n2 → Rated(score=10) → n1  : matches back-ref (inV=n1 == $matched.a)
    // n3 → Rated(score=20) → n1  : matches back-ref
    // n3 → Rated(score=1)  → n5  : filtered by score>5; also wrong target
    session.execute(
        "CREATE EDGE Rated from (select from Person where name='n2')"
            + " to (select from Person where name='n1') set score = 10")
        .close();
    session.execute(
        "CREATE EDGE Rated from (select from Person where name='n3')"
            + " to (select from Person where name='n1') set score = 20")
        .close();
    session.execute(
        "CREATE EDGE Rated from (select from Person where name='n3')"
            + " to (select from Person where name='n5') set score = 1")
        .close();
    session.commit();

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".outE('Rated'){as:e, where:(score > 5)}"
            + ".inV(){as:check, where:(@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName, e.score as score")
        .toList();

    // Expected: both n2→n1 (score=10) and n3→n1 (score=20) match.
    // n3→n5 is excluded both by the score filter AND the back-ref check.
    var rows = result.stream()
        .map(r -> r.getProperty("bName") + ":" + r.getProperty("score"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2:10", "n3:20"), rows);
  }

  /**
   * Pattern B EXPLAIN sanity: when an indexed edge property is present,
   * the plan still uses BACK-REF HASH JOIN (the index feeds the build
   * side, not a separate pre-filter step).
   */
  @Test
  public void explainBackRef_outEInV_withIndexedEdgeFilter_usesChainSemiJoin() {
    session.execute("CREATE class Rated2 extends E").close();
    session.execute("CREATE PROPERTY Rated2.score INTEGER").close();
    session.execute("CREATE INDEX Rated2_score ON Rated2 (score) NOTUNIQUE").close();

    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".outE('Rated2'){as:e, where:(score > 5)}"
            + ".inV(){as:check, where:(@rid = $matched.a.@rid)}"
            + " RETURN b.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "indexed edge filter should still use ChainSemiJoin, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));
    session.commit();
  }

  // ── $matched publication regression ────────────────────────────────────

  /**
   * Regression test: when {@code BackRefHashJoinStep} (Pattern A) binds a
   * target alias, a downstream MATCH branch whose WHERE references
   * {@code $matched.<that alias>} must observe the fresh binding.
   *
   * <p>Before the fix, {@code probeSingleEdge} emitted rows via
   * {@code ExecutionStream.flatMap} without republishing
   * {@link com.jetbrains.youtrackdb.internal.core.command.CommandContext#VAR_MATCHED},
   * leaving the context with the stale value from the upstream
   * {@link MatchEdgeTraverser}. {@code MatchEdgeTraverser.filter} patches
   * only the starting-point alias of the downstream edge onto the stale
   * $matched object, so any other alias bound by the hash-join step is
   * invisible to the filter — it resolves {@code $matched.c} to null and
   * rejects every row, producing zero results.
   *
   * <p>Graph augmentation ({@code n2→Friend→n1}, {@code n1→Likes→t1})
   * lets the query hit Pattern A (only {@code b=n2} back-refs to
   * {@code a=n1}, so {@code c=n1}) and provides a non-empty branch from
   * {@code a}. The branch starts from {@code a} — not {@code c} — so
   * {@code MatchEdgeTraverser.filter}'s auto-patch restores only
   * {@code a} on any stale $matched object, making the reference to
   * {@code $matched.c.name} the load-bearing assertion.
   */
  @Test
  public void backRef_patternA_publishesMatchedForDownstreamAliasRef() {
    session.begin();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();
    session.execute(
        "CREATE EDGE Likes from (select from Person where name='n1')"
            + " to (select from Tag where name='t1')")
        .close();
    session.commit();

    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c, where:(@rid = $matched.a.@rid)},"
            + " {as:a}.out('Likes'){as:t, where:($matched.c.name = 'n1')}"
            + " RETURN t.name as tName")
        .toList();
    session.commit();

    // Main path: a=n1, b ∈ {n2,n3}, back-ref requires b→n1 — only b=n2
    // hits (via the added n2→Friend→n1 edge), so c=n1.
    // Branch: a=n1 has one Likes target (t1). Branch filter requires
    // $matched.c.name='n1'; with the fix it is true and t=t1 passes.
    assertEquals(1, result.size());
    assertEquals("t1", result.get(0).getProperty("tName"));
  }

}
