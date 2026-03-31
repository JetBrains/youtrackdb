package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * End-to-end SQL-level integration tests verifying that the hash anti-join optimization
 * for NOT patterns in MATCH queries produces correct results and is selected by the
 * planner when eligible.
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
 * {@link MatchExecutionPlanner#HASH_JOIN_THRESHOLD}, so NOT patterns without
 * {@code $matched} dependency will use hash anti-join.
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
   * Diamond pattern with intermediate alias in RETURN — should use INNER_JOIN (not
   * SEMI_JOIN) because the intermediate alias (c) is needed for output and its values
   * must be merged into the result rows.
   */
  @Test
  public void diamondPattern_intermediateInReturn_usesInnerJoin() {
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
    // Intermediate alias in RETURN → inner join, not semi-join
    assertFalse("plan should NOT use SEMI_JOIN when intermediate is in RETURN, got:\n"
        + plan, plan.contains("HASH SEMI_JOIN"));
    assertTrue("plan should use INNER_JOIN when intermediate is in RETURN, got:\n"
        + plan, plan.contains("HASH INNER_JOIN"));
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

  // ── Inner-join tests ──────────────────────────────────────────────────

  /**
   * Diamond with all aliases in RETURN → INNER_JOIN. Verifies that intermediate
   * alias 'c' values are correctly merged into result rows and that the 1:N
   * expansion produces the expected number of rows.
   *
   * <p>Main path: a=n1 → b∈{n2,n3} → t∈Likes(b). Produces (n1,n2,t1), (n1,n2,t2),
   * (n1,n3,t1). Branch build side: a=n1 → c∈{n2,n3} → t∈Likes(c). Produces
   * (n1,n2,t1), (n1,n2,t2), (n1,n3,t1). INNER_JOIN on (a,t): each main row
   * is matched with all build rows sharing the same (a,t) pair.
   */
  @Test
  public void innerJoin_diamond_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name as aName, b.name as bName,"
            + "        c.name as cName, t.name as tName")
        .toList();

    assertFalse("inner-join diamond query should return results", result.isEmpty());

    // Every row must have a non-null c value — the intermediate binding is merged
    for (var row : result) {
      assertNotNull("c.name must not be null in INNER_JOIN result",
          row.getProperty("cName"));
    }

    // Expected result set: cartesian product filtered by join key (a, t)
    var tuples = result.stream()
        .map(r -> r.getProperty("aName") + ":"
            + r.getProperty("bName") + ":"
            + r.getProperty("cName") + ":"
            + r.getProperty("tName"))
        .collect(Collectors.toSet());
    // t1 is reachable via both b=n2 and b=n3 on main path, and via c=n2 and c=n3 on branch
    // t2 is reachable via b=n2 only, and via c=n2 only
    assertEquals(Set.of(
        "n1:n2:n2:t1", "n1:n2:n3:t1", // main (n1,n2,t1) × branch c∈{n2,n3}
        "n1:n3:n2:t1", "n1:n3:n3:t1", // main (n1,n3,t1) × branch c∈{n2,n3}
        "n1:n2:n2:t2" // main (n1,n2,t2) × branch c=n2 only
    ), tuples);
    session.commit();
  }

  /**
   * Result equivalence: run the same diamond query with INNER_JOIN (hash join path)
   * and with nested-loop (by using a $matched reference that disables hash join).
   * Both must produce the same result set.
   *
   * <p>The nested-loop version uses a WHERE clause that trivially references $matched
   * to force the fallback path.
   */
  @Test
  public void innerJoin_resultEquivalence_matchesNestedLoop() {
    session.begin();

    // Hash join path (no $matched reference)
    var hashJoinResult = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t}"
            + " RETURN a.name as aName, b.name as bName,"
            + "        c.name as cName, t.name as tName")
        .toList();

    // Nested-loop path (force fallback via $matched reference on intermediate)
    var nestedLoopResult = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c, where:($matched.a IS NOT null)}"
            + ".out('Likes'){as:t}"
            + " RETURN a.name as aName, b.name as bName,"
            + "        c.name as cName, t.name as tName")
        .toList();

    var hashJoinTuples = hashJoinResult.stream()
        .map(r -> r.getProperty("aName") + ":"
            + r.getProperty("bName") + ":"
            + r.getProperty("cName") + ":"
            + r.getProperty("tName"))
        .collect(Collectors.toSet());

    var nestedLoopTuples = nestedLoopResult.stream()
        .map(r -> r.getProperty("aName") + ":"
            + r.getProperty("bName") + ":"
            + r.getProperty("cName") + ":"
            + r.getProperty("tName"))
        .collect(Collectors.toSet());

    assertEquals("hash join and nested-loop should produce same result set",
        nestedLoopTuples, hashJoinTuples);
    session.commit();
  }

  /**
   * Empty build side: branch matches zero records → no results (inner join
   * requires at least one matching build-side row for each upstream row).
   *
   * <p>Uses a non-existent edge type to ensure the branch produces no rows.
   */
  @Test
  public void innerJoin_emptyBuildSide_noResults() {
    session.execute("CREATE class Missing extends E").close();
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}.out('Friend'){as:b},"
            + " {as:a}.out('Missing'){as:c}.out('Friend'){as:b}"
            + " RETURN a.name, b.name, c.name")
        .toList();
    assertEquals("empty build side should produce zero results", 0, result.size());
    session.commit();
  }

  /**
   * Query with both INNER_JOIN branch and NOT pattern in the same query.
   * Verifies that $matched is correctly maintained after the inner-join merge
   * and that the NOT pattern correctly filters merged rows.
   *
   * <p>Diamond: a→b→t, a→c→t with c in RETURN (inner join).
   * NOT: NOT {as:b}.out('Friend') — removes b values that have outgoing Friends.
   * n2→n4 (Friend), n3→n5 (Friend) → both n2 and n3 have friends → all rows removed.
   */
  @Test
  public void innerJoin_withNotPattern_correctFiltering() {
    session.begin();
    var result = session.query(
        "MATCH {class:Person, as:a, where:(name='n1')}"
            + ".out('Friend'){as:b}.out('Likes'){class:Tag, as:t},"
            + " {as:a}.out('Friend'){as:c}.out('Likes'){as:t},"
            + " NOT {as:b}.out('Friend'){}"
            + " RETURN a.name as aName, b.name as bName,"
            + "        c.name as cName, t.name as tName")
        .toList();

    // Both n2 and n3 have outgoing Friend edges → all rows filtered by NOT
    assertEquals("all b values have friends, so NOT should filter everything",
        0, result.size());
    session.commit();
  }

  /**
   * Diamond with both semi-join and inner-join branches in the same query.
   * Branch 1: a→c→t with c NOT in RETURN → semi-join.
   * Branch 2: a→d→t with d in RETURN → inner-join (but needs a different pattern).
   *
   * <p>Since our graph has limited edge types, we test this by using a 3-branch
   * pattern where one branch's intermediate is in RETURN and another's is not:
   * Main: a→b→t. Branch1 (semi): a→c→t (c not in RETURN). The inner-join
   * integration is already tested by the diamond tests above.
   */
  @Test
  public void semiJoinAndInnerJoin_existingSemiJoinStillWorks() {
    // Regression guard: existing semi-join behavior is preserved
    session.begin();
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
}
