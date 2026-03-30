package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
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
    assertTrue(names.contains("n2:t2"));
    assertTrue(names.contains("n3:t1"));
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
    assertTrue(names.contains("n2"));
    assertTrue(names.contains("n3"));
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
}
