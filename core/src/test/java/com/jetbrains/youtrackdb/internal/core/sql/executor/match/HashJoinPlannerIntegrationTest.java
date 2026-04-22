package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
   * Enumerates the full row set so a regression that duplicates, drops, or
   * swaps rows (e.g. stale {@code c} binding from the build side) surfaces
   * directly. Graph: n1→{n2,n3}, n2→Likes→{t1,t2}, n3→Likes→{t1}. For each
   * (b,c)∈n1.Friends² row exists for every t in b.Likes∩c.Likes.
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

    // (b,c,t) combinations where t ∈ b.Likes ∩ c.Likes, with a pinned to n1:
    //   (n2,n2): t1, t2            (n2,n3): t1
    //   (n3,n2): t1                (n3,n3): t1
    // Total 5 rows, each with a=n1.
    var rows = result.stream()
        .map(r -> r.getProperty("aName") + "|" + r.getProperty("bName")
            + "|" + r.getProperty("cName") + "|" + r.getProperty("tName"))
        .collect(Collectors.toSet());
    assertEquals(
        Set.of(
            "n1|n2|n2|t1",
            "n1|n2|n2|t2",
            "n1|n2|n3|t1",
            "n1|n3|n2|t1",
            "n1|n3|n3|t1"),
        rows);
    assertEquals("no duplicate rows expected", 5, result.size());
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

      // Runtime fallback must produce the same logical rows as the
      // hash-join path — see diamondPattern_innerJoin_correctResults for
      // the derivation. If the nested-loop fallback duplicates, drops,
      // or swaps rows, the exact-set assertion flags it.
      var rows = result.stream()
          .map(r -> r.getProperty("aName") + "|" + r.getProperty("bName")
              + "|" + r.getProperty("cName") + "|" + r.getProperty("tName"))
          .collect(Collectors.toSet());
      assertEquals(
          Set.of(
              "n1|n2|n2|t1",
              "n1|n2|n2|t2",
              "n1|n2|n3|t1",
              "n1|n3|n2|t1",
              "n1|n3|n3|t1"),
          rows);
      assertEquals("no duplicate rows in fallback path",
          5, result.size());
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

    // Only b=n2 back-refs to n1 (via the two added n2→n1 edges). b=n3's
    // out('Friend') is {n5}, no back-ref. Fan-out must emit exactly 2 rows
    // — one per edge — both with b=n2 and distinct e2 edge identities.
    assertEquals("fan-out should emit one row per matching edge",
        2, result.size());
    var bNames = result.stream()
        .map(r -> (String) r.getProperty("bName"))
        .collect(Collectors.toList());
    assertEquals(List.of("n2", "n2"), bNames);
    var edgeIds = result.stream()
        .map(r -> {
          var e = r.getProperty("edge2");
          return e == null ? null : e.toString();
        })
        .collect(Collectors.toSet());
    assertEquals("both emitted edges must be distinct",
        2, edgeIds.size());
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
   * EXPLAIN of compound WHERE — a NOT IN combined with additional AND
   * conditions must now (post-fix) strip only the NOT IN and still fire
   * the anti-join optimization. The residual conjunct ({@code name='n4'})
   * stays on the preceding {@link MatchStep}, so the plan has both the
   * MATCH step (evaluating the residual) and the BACK-REF HASH JOIN ANTI
   * step (evaluating the NOT IN exclusion).
   *
   * <p>Regression: {@code detectNotInAntiJoin} previously iterated only
   * the top-level AND sub-blocks. With MATCH's double-nesting
   * ({@code AND[OR[AND[notIn, residual]]]}) the NOT IN sat two wrappers
   * deep, so compound WHERE fell back to standard MatchStep evaluation.
   * The fix descends through transparent single-element OR/AND wrappers
   * to reach the actual conjuncts list.
   */
  @Test
  public void explainBackRef_notIn_compoundWhere_usesAntiJoinWithResidual() {
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
    assertTrue(
        "compound WHERE should use anti-join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN ANTI"));
    // Residual evaluation is verified end-to-end by
    // backRef_notIn_compoundWhere_correctResults — it filters to n4 only,
    // which only works if the stripped WHERE (name='n4') still runs in
    // the preceding MatchStep. EXPLAIN does not print per-MatchStep
    // WHERE clauses, so we can't assert the residual text in the plan.
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
   * Pattern D with "in" direction — NOT IN {@code $matched.X.in('Friend')}.
   * Verifies anti-semi-join works when the NOT IN RHS traverses incoming
   * edges instead of outgoing ones.
   *
   * <p>Graph (reversed): {@code n4 <--Friend-- n2 <--Friend-- n1}. Starting
   * at {@code n4}, walk back twice via {@code .in('Friend')}:
   * {@code friend = n2}, then {@code fof = n1}. The NOT IN RHS
   * {@code $matched.start.in('Friend')} resolves to {@code n4}'s incoming
   * Friend neighbors = {n2}. Candidate {@code n1 NOT IN {n2}} → accepted.
   * Expected result: {@code {n1}}.
   */
  @Test
  public void backRef_notIn_inDirection_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n4')}"
            + ".in('Friend'){as:friend}"
            + ".in('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.in('Friend'))}"
            + " RETURN fof.name as fofName")
        .toList();

    var names = result.stream()
        .map(r -> (String) r.getProperty("fofName"))
        .collect(Collectors.toSet());
    // n1 is the only FoF via reverse Friend; n2 (direct in-neighbor) excluded
    assertEquals(Set.of("n1"), names);
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

  /**
   * Regression: Pattern B (outE+inV chain) must reject an optional
   * {@code .inV()} target. Before the fix, {@code detectChainSemiJoin}
   * had no optional guard (only Pattern A did), so the predecessor
   * {@code .outE('E')} got marked {@code consumed=true}; then
   * {@code addStepsFor} dispatched on the {@code isOptionalNode()} branch
   * before consulting {@code getSemiJoinDescriptor()}, producing a plan
   * where the predecessor's {@link MatchStep} was silently dropped and no
   * {@link BackRefHashJoinStep} was inserted — leaving the intermediate
   * alias unbound at runtime and collapsing all outE edges into a single
   * null-filled row.
   *
   * <p>Correct semantics: traverse every outE('Friend') from {@code a=n1}
   * (the graph has n1→n2 and n1→n3), emit one row per edge. The back-ref
   * filter rejects both targets so {@code check} is null under the
   * optional pass-through.
   */
  @Test
  public void backRef_optionalInV_patternB_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e}"
            + ".inV(){as:check, optional:true,"
            + " where: (@rid = $matched.a.@rid)}"
            + " RETURN a.name as aName, e.@rid as eRid,"
            + " check.name as checkName")
        .toList();
    session.commit();

    // Without the fix: outE dropped, only OptionalMatchStep runs, intermediate
    // alias e is unbound → the plan yields a single degenerate row (or none).
    // With the fix: two outE edges are each traversed, both have null check.
    assertEquals(2, result.size());
    for (var row : result) {
      assertEquals("n1", row.getProperty("aName"));
      assertNotNull("edge RID must be bound", row.getProperty("eRid"));
      assertNull(
          "check must be null under optional back-ref rejection",
          row.getProperty("checkName"));
    }
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

  /**
   * Regression: Pattern B (outE+inV chain) must apply the full edge WHERE
   * clause even when the index only covers it partially. Before the fix,
   * {@code BackRefHashJoinStep} filtered edges exclusively via
   * {@code indexRidSet.contains(edgeRid)} — which only checks indexable
   * terms. Non-indexable residual terms (e.g. {@code category='A'} when
   * the index is on {@code score}) were silently dropped because the
   * consumed predecessor's MatchStep (which would normally evaluate them)
   * was skipped.
   *
   * <p>The fix stores the full edge filter in {@link ChainSemiJoin} and
   * re-evaluates it on every loaded edge, keeping the index as a
   * zero-load pre-filter. Pattern B still applies — this is a correctness
   * fix, not a fallback — so the plan must show BACK-REF HASH JOIN.
   *
   * <p>Graph setup: two Rated5 edges both pass the indexable
   * {@code score > 5} but differ on {@code category}. The correct plan
   * returns only the edge matching both terms.
   */
  @Test
  public void backRef_outEInV_partialIndexCover_correctResults() {
    session.execute("CREATE class Rated5 extends E").close();
    session.execute("CREATE PROPERTY Rated5.score INTEGER").close();
    session.execute("CREATE PROPERTY Rated5.category STRING").close();
    // Deliberately index only score; category is unindexed so
    // {@code score > 5 AND category = 'A'} produces a partial cover.
    session.execute("CREATE INDEX Rated5_score ON Rated5 (score) NOTUNIQUE").close();

    session.begin();
    session.execute(
        "CREATE EDGE Rated5 from (select from Person where name='n2')"
            + " to (select from Person where name='n1')"
            + " set score = 10, category = 'A'")
        .close();
    session.execute(
        "CREATE EDGE Rated5 from (select from Person where name='n3')"
            + " to (select from Person where name='n1')"
            + " set score = 20, category = 'B'")
        .close();
    session.commit();

    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".outE('Rated5'){as:e, where:(score > 5 AND category = 'A')}"
            + ".inV(){as:check, where:(@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName, e.category as cat")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "partial index cover should still collapse the chain, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".outE('Rated5'){as:e, where:(score > 5 AND category = 'A')}"
            + ".inV(){as:check, where:(@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName, e.category as cat")
        .toList();
    session.commit();

    // Only the n2→n1 edge (score=10, category='A') satisfies both terms.
    // Without the fix, the n3→n1 edge (score=20, category='B') would also
    // slip through because the index matches score>5 but never checks
    // category — the chain semi-join would return 2 rows.
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    assertEquals("A", result.get(0).getProperty("cat"));
  }

  /**
   * Regression: Pattern B (outE+inV chain) must apply the edge WHERE clause
   * even when there is no index at all on the edge property. Before the
   * fix, the filter was stored only as an {@code IndexSearchDescriptor}
   * pre-filter; when no index existed, the descriptor was null and the
   * filter was silently dropped entirely.
   *
   * <p>Same setup as the partial-cover test but on an unindexed edge class.
   */
  @Test
  public void backRef_outEInV_noIndexCover_correctResults() {
    session.execute("CREATE class Rated6 extends E").close();
    session.execute("CREATE PROPERTY Rated6.score INTEGER").close();
    // Deliberately no index at all on Rated6.

    session.begin();
    session.execute(
        "CREATE EDGE Rated6 from (select from Person where name='n2')"
            + " to (select from Person where name='n1') set score = 10")
        .close();
    session.execute(
        "CREATE EDGE Rated6 from (select from Person where name='n3')"
            + " to (select from Person where name='n1') set score = 2")
        .close();
    session.commit();

    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".outE('Rated6'){as:e, where:(score > 5)}"
            + ".inV(){as:check, where:(@rid = $matched.a.@rid)}"
            + " RETURN b.name as bName, e.score as score")
        .toList();
    session.commit();

    // Only the n2→n1 edge (score=10) passes score > 5; the n3→n1 edge
    // (score=2) must be filtered out. Without the fix, both would enter
    // the hash table (no filter evaluator whatsoever).
    assertEquals(1, result.size());
    assertEquals("n2", result.get(0).getProperty("bName"));
    assertEquals(10, (int) result.get(0).getProperty("score"));
  }

  /**
   * Regression: Pattern A (single-edge back-ref) must apply residual WHERE
   * terms on the target alias. Before the fix, {@code probeSingleEdge}
   * emitted rows based on the hash lookup alone — the hash encodes only
   * the {@code @rid = $matched.X.@rid} equality — so any additional
   * constraints on the target vertex (e.g. {@code name != 'n1'}) were
   * silently dropped because Pattern A replaces the target's MatchStep.
   *
   * <p>Graph setup: a self-loop on n1 makes {@code a=n1, b=n1} a valid
   * back-ref candidate via {@code b.out('Friend')=n1=$matched.a}. The
   * target residual {@code name != 'n1'} must reject n1, so the correct
   * result is zero rows. Without the fix, one spurious row leaks through.
   */
  @Test
  public void backRef_patternA_residualTargetFilter_correctResults() {
    session.begin();
    // Self-loop so the back-ref @rid = $matched.a.@rid succeeds for b=n1.
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n1')"
            + " to (select from Person where name='n1')")
        .close();
    session.commit();

    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid AND name <> 'n1')}"
            + " RETURN c.name as cName")
        .toList();
    session.commit();

    // @rid = $matched.a.@rid picks c=n1, but name<>'n1' must reject it.
    // Without the residual filter the probe emits one row (c=n1) — wrong.
    assertEquals(0, result.size());
  }

  /**
   * Sanity companion to {@link #backRef_patternA_residualTargetFilter_correctResults}:
   * when the residual filter is satisfied, Pattern A still fires and the
   * row is emitted. Confirms the fix does not over-reject.
   */
  @Test
  public void backRef_patternA_residualTargetFilter_passing_correctResults() {
    session.begin();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n1')"
            + " to (select from Person where name='n1')")
        .close();
    session.commit();

    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid AND name = 'n1')}"
            + " RETURN c.name as cName")
        .toList();
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue(
        "residual-safe Pattern A should still collapse via hash join, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid AND name = 'n1')}"
            + " RETURN c.name as cName")
        .toList();
    session.commit();

    assertEquals(1, result.size());
    assertEquals("n1", result.get(0).getProperty("cName"));
  }

  /**
   * Regression: Pattern D detection must walk the AST instead of parsing
   * the RHS {@code toString()}. The previous regex-based detector accepted
   * {@code $matched.X.out(:edge)} by capturing the bare parameter name
   * (without the {@code :} prefix) as the edge-class string, which then
   * failed at runtime because the anti-join build scanned a non-existent
   * {@code out_:edge} link bag and passed every candidate through —
   * inverting the intended NOT IN semantics.
   *
   * <p>The query is crafted so the buggy path and the correct path yield
   * <strong>different</strong> row sets: each fof <em>is</em> one of
   * {@code n1.out('Friend')}. A correct per-row NOT IN excludes them
   * (zero rows); the old regex bug would build an empty hash on the
   * non-existent {@code :edgeClass} class, so every fof would survive
   * (two rows) — inverting the intended exclusion.
   */
  @Test
  public void backRef_notIn_boundParameterEdgeClass_correctResults() {
    session.begin();
    Map<String, Object> params = new HashMap<>();
    params.put("edgeClass", "Friend");
    // Fof traversal: n1.out('Friend') = {n2, n3}. The NOT IN RHS resolves
    // (at runtime, per-row) to n1.out('Friend') = {n2, n3} as well — so
    // both fof candidates are excluded. Correct result: zero rows.
    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.out(:edgeClass))}"
            + " RETURN fof.name as fofName",
        params)
        .toList();
    session.commit();

    // If Pattern D wrongly fired with edge class literally "edgeClass",
    // the hash build would be empty and NOT IN ∅ would pass every fof —
    // the result would be {n2, n3}. The correct result (per-row NOT IN
    // with the resolved Friend class) is the empty set.
    assertEquals("all fof candidates are in the exclusion set — must be "
        + "filtered out by correct per-row NOT IN", 0, result.size());
  }

  /**
   * Pattern A residual handling when the target WHERE contains a second
   * {@code @rid = <literal>} equality alongside the back-ref
   * {@code @rid = $matched.a.@rid}.
   *
   * <p>{@code findRidEquality} walks the atoms and returns the first match —
   * here the {@code $matched.a.@rid} one (atom order in the parser). The
   * planner enters the Pattern A path; {@code extractTargetResidual} strips
   * that atom and leaves {@code @rid = <literal>} as residual. The residual
   * has no {@code $matched}/{@code $currentMatch} reference, so Pattern A
   * is safe. At probe time, {@code BackRefHashJoinStep} re-evaluates the
   * residual per loaded target: only entities whose RID also equals the
   * literal pass.
   *
   * <p>Graph: {@code n1 --Friend--> n1} (self-loop), added to the shared
   * graph. The back-ref {@code @rid = $matched.a.@rid} selects {@code c=n1}.
   * The literal half {@code @rid = #<impossible>} can never hold, so the
   * residual filters everything out and the result is empty. Without the
   * residual, the probe would emit {@code c=n1}.
   */
  @Test
  public void backRef_patternA_compoundRidEquality_residualRejects() {
    session.begin();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n1')"
            + " to (select from Person where name='n1')")
        .close();
    session.commit();

    session.begin();
    // #9999:9999 is a RID that cannot exist in the test database (cluster
    // 9999 is never created). Using an impossible RID keeps the test
    // independent of cluster-id assignment across runs.
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid AND @rid = #9999:9999)}"
            + " RETURN c.name as cName")
        .toList();
    session.commit();

    // Pattern A picks c=n1 via $matched.a.@rid; literal residual
    // @rid=#9999:9999 rejects it. Correct result: zero rows.
    assertEquals(0, result.size());
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

  // ── Rejection-path branch coverage for MatchExecutionPlanner ───────────
  //
  // These tests each run EXPLAIN and assert the plan does NOT include
  // BACK-REF HASH JOIN. This is the load-bearing assertion: a result-only
  // check would pass even if the planner took the hash-join path and still
  // happened to return the right rows. Data-correctness assertions (where
  // they differentiate the paths) follow as a secondary safeguard.

  /**
   * Pattern A rejected — target WHERE is a multi-branch OR.
   * {@code extractTargetResidual.flattenConjunction} bails out on multi-branch
   * OR so the residual cannot be isolated, and the planner must fall back to
   * the standard {@code MatchStep} + {@code EdgeRidLookup} path. The plan
   * assertion is load-bearing: the row-set below would look identical if
   * Pattern A fired and dropped or preserved the OR branch differently, so
   * result correctness alone could mask a regression.
   */
  @Test
  public void backRef_patternA_rejectedOnMultiBranchOrTargetFilter() {
    session.begin();
    // Add cycle n2→n1 so that a real match for $matched.a.@rid exists.
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();
    session.commit();

    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid OR name = 'n4')}"
            + " RETURN b.name, c.name")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse(
        "multi-branch OR in target filter must not take BACK-REF HASH JOIN, "
            + "got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid OR name = 'n4')}"
            + " RETURN b.name as bName, c.name as cName")
        .toList();
    session.commit();

    // Under standard MatchStep: b=n2 → c=n1 (back-ref) or c=n4 (name='n4').
    // b=n3 → no match. Resulting set: { (n2,n1), (n2,n4) }.
    var rows = result.stream()
        .map(r -> r.getProperty("bName") + "->" + r.getProperty("cName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2->n1", "n2->n4"), rows);
  }

  /**
   * Pattern A rejected — residual term references {@code $matched.<alias>}.
   * The residual would be evaluated at hash-table build time (no per-row
   * {@code $matched} scope) so {@code extractTargetResidual} must return
   * UNSAFE and the planner falls back. The plan assertion is the primary
   * verification: if Pattern A applied the residual was semantically invalid
   * but might still have produced the correct rows by accident.
   */
  @Test
  public void backRef_patternA_rejectedWhenResidualReferencesMatched() {
    session.begin();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();
    session.commit();

    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid AND name = $matched.a.name)}"
            + " RETURN b.name, c.name")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse(
        "residual referencing $matched must not take BACK-REF HASH JOIN, "
            + "got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid AND name = $matched.a.name)}"
            + " RETURN b.name as bName, c.name as cName")
        .toList();
    session.commit();

    // $matched.a.name = 'n1'. Correct rows: c.name='n1' AND c≡a.
    // b=n2 back-refs to n1 → (n2, n1). b=n3 → no back-ref.
    var rows = result.stream()
        .map(r -> r.getProperty("bName") + "->" + r.getProperty("cName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2->n1"), rows);
  }

  /**
   * Pattern A rejected — residual contains {@code $currentMatch}.
   * {@link com.jetbrains.youtrackdb.internal.core.sql.executor.match
   * .MatchExecutionPlanner#refersToCurrentMatch} detects the reference
   * inside the residual and {@code extractTargetResidual} returns UNSAFE.
   *
   * <p>Uses a tautology ({@code $currentMatch.@rid IS NOT NULL}) rather
   * than a NOT-IN clause so Pattern D cannot fire as a fall-through —
   * the assertion must fail cleanly on Pattern A alone. A NOT-IN residual
   * would be picked up by {@code detectNotInAntiJoin} after Pattern A
   * rejection and emit an ANTI step, masking the branch under test.
   */
  @Test
  public void backRef_patternA_rejectedWhenResidualReferencesCurrentMatch() {
    session.begin();
    session.execute(
        "CREATE EDGE Friend from (select from Person where name='n2')"
            + " to (select from Person where name='n1')")
        .close();
    session.commit();

    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid"
            + " AND $currentMatch.@rid IS NOT NULL)}"
            + " RETURN b.name, c.name")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse(
        "residual referencing $currentMatch must not take BACK-REF HASH JOIN, "
            + "got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}"
            + ".out('Friend'){as:c,"
            + " where: (@rid = $matched.a.@rid"
            + " AND $currentMatch.@rid IS NOT NULL)}"
            + " RETURN b.name as bName, c.name as cName")
        .toList();
    session.commit();

    // $currentMatch.@rid IS NOT NULL is tautologically true, so only the
    // back-ref filters. b=n2 back-refs to n1 → (n2, n1); b=n3 → no match.
    var rows = result.stream()
        .map(r -> r.getProperty("bName") + "->" + r.getProperty("cName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2->n1"), rows);
  }

  /**
   * Pattern B rejected — intermediate edge WHERE references
   * {@code $matched.<alias>}. {@code detectChainSemiJoin} inspects the
   * intermediate filter's involved-aliases set and bails when it is
   * non-empty. The EXPLAIN assertion is the load-bearing check: both the
   * rejection path and a hypothetical (wrong) Pattern-B firing would yield
   * the same result count, so data correctness alone cannot distinguish
   * them. The query intentionally returns zero rows — the outgoing
   * Friend targets of n1 ({n2, n3}) cannot back-ref to n1 itself through
   * a single {@code .outE('Friend').inV()} step.
   */
  @Test
  public void backRef_patternB_rejectedWhenIntermediateFilterReferencesMatched() {
    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e, where: ($matched.a.@rid IS NOT NULL)}"
            + ".inV(){as:c, where: (@rid = $matched.a.@rid)}"
            + " RETURN c.name")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse(
        "Pattern B must not fire when intermediate filter references "
            + "$matched.<alias>, got:\n" + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".outE('Friend'){as:e, where: ($matched.a.@rid IS NOT NULL)}"
            + ".inV(){as:c, where: (@rid = $matched.a.@rid)}"
            + " RETURN c.name as cName")
        .toList();
    session.commit();

    // n1.outE('Friend').inV() = {n2, n3}, neither back-refs to n1. Zero rows.
    assertEquals(0, result.size());
  }

  /**
   * Pattern D NOT IN rejected — the RHS of NOT IN is a bare
   * {@code $matched.<alias>} with no trailing traversal call.
   * {@code extractMatchedTraversal} requires a second modifier ({@code .out}
   * or {@code .in}) and returns {@code null} when it is missing.
   *
   * <p>The plan assertion is the primary branch-coverage check. The data
   * assertion is a second safety net: with Pattern D rejected, per-row NOT
   * IN treats the single-vertex RHS as a singleton set; neither friend of
   * n1 ({@code n2}, {@code n3}) equals n1, so both rows pass. If a broken
   * planner tried to build a hash on the malformed RHS and dropped rows,
   * the returned set would shrink and the assertion would flag it.
   */
  @Test
  public void backRef_notIn_rejectedOnMalformedMatchedTraversalRhs() {
    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend,"
            + " where: ($currentMatch NOT IN $matched.start)}"
            + " RETURN friend.name")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse(
        "Pattern D must not fire when RHS has no traversal call, got:\n"
            + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:friend,"
            + " where: ($currentMatch NOT IN $matched.start)}"
            + " RETURN friend.name as fName")
        .toList();
    session.commit();

    var names = result.stream()
        .map(r -> (String) r.getProperty("fName"))
        .collect(Collectors.toSet());
    assertEquals(Set.of("n2", "n3"), names);
  }

  /**
   * Pattern D NOT IN rejected — the RHS traversal method is
   * {@code .both(...)}, which is not one of the accepted {@code .out/.in}
   * shapes. {@code extractMatchedTraversal} rejects anything but out/in.
   *
   * <p>The plan assertion is the primary branch-coverage check. The data
   * assertion complements it: {@code n1.both('Friend')} is {@code {n2, n3}}
   * and the fof set {@code n1.out('Friend')} is also {@code {n2, n3}}, so
   * every candidate is excluded by the per-row NOT IN and the query returns
   * zero rows. A regression that returned unexpected rows would still fail
   * the data check even if the plan check somehow missed the shift.
   */
  @Test
  public void backRef_notIn_rejectedOnUnacceptedTraversalMethod() {
    session.begin();
    var explain = session.query(
        "EXPLAIN MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.both('Friend'))}"
            + " RETURN fof.name")
        .toList();
    assertEquals(1, explain.size());
    String plan = explain.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse(
        "Pattern D must not fire for .both(...) traversal RHS, got:\n"
            + plan,
        plan.contains("BACK-REF HASH JOIN"));

    var result = session.query(
        "MATCH {class:Person, as:start, where:(name='n1')}"
            + ".out('Friend'){as:fof,"
            + " where: ($currentMatch NOT IN $matched.start.both('Friend'))}"
            + " RETURN fof.name as fName")
        .toList();
    session.commit();

    assertEquals(0, result.size());
  }
}
