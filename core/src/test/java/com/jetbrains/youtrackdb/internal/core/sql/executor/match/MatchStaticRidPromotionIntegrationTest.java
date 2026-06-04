package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Test;

/**
 * End-to-end integration tests for the static-RID-in-WHERE promotion
 * (YTDB-629). When a MATCH node carries a literal or parameter {@code @rid}
 * equality in its WHERE clause,
 * {@link MatchExecutionPlanner#promoteStaticRidsFromFilters} lifts it into
 * {@code aliasRids}. This collapses that alias's root estimate to 1, so the
 * topological scheduler picks it as the cheapest root (the {@code FetchFromRids}
 * fast path) and reverses traversal direction along the rest of the chain.
 *
 * <p>Graph built in {@link #beforeTest}:
 * <pre>
 *   Person(alice) --Knows--> Person(bob)   --Likes--> Comment(c1)
 *   Person(alice) --Knows--> Person(carol) --Likes--> Comment(c2)
 *   Person(dave)  --Knows--> Person(erin)  --Likes--> Comment(c3)
 * </pre>
 *
 * <p>Only {@code alice -> bob -> c1} reaches {@code c1}, so a chain pinned to
 * {@code c1} matches exactly one path. The companion unit tests in
 * {@link PromoteStaticRidsFromFiltersTest} cover the promoter in isolation;
 * these tests verify the resulting plan shape and query results end to end.
 */
public class MatchStaticRidPromotionIntegrationTest extends DbTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.execute("CREATE class Person extends V").close();
    session.execute("CREATE property Person.name STRING").close();
    session.execute("CREATE class Comment extends V").close();
    session.execute("CREATE property Comment.name STRING").close();
    session.execute("CREATE class Knows extends E").close();
    session.execute("CREATE class Likes extends E").close();

    session.begin();
    for (var p : new String[] {"alice", "bob", "carol", "dave", "erin"}) {
      session.execute("CREATE VERTEX Person set name = '" + p + "'").close();
    }
    for (var c : new String[] {"c1", "c2", "c3"}) {
      session.execute("CREATE VERTEX Comment set name = '" + c + "'").close();
    }
    knows("alice", "bob");
    knows("alice", "carol");
    knows("dave", "erin");
    likes("bob", "c1");
    likes("carol", "c2");
    likes("erin", "c3");
    session.commit();
  }

  private void knows(String from, String to) {
    session.execute(
        "CREATE EDGE Knows FROM (SELECT FROM Person WHERE name = '" + from + "')"
            + " TO (SELECT FROM Person WHERE name = '" + to + "')")
        .close();
  }

  private void likes(String person, String comment) {
    session.execute(
        "CREATE EDGE Likes FROM (SELECT FROM Person WHERE name = '" + person + "')"
            + " TO (SELECT FROM Comment WHERE name = '" + comment + "')")
        .close();
  }

  /** Returns the RID string ({@code #cluster:position}) of a named record. */
  private String ridOf(String clazz, String name) {
    return session.query(
        "SELECT @rid as rid FROM " + clazz + " WHERE name = '" + name + "'")
        .toList().get(0).getProperty("rid").toString();
  }

  /**
   * Multi-hop chain rooted at a Comment pinned by a literal {@code @rid} in the
   * WHERE clause. Exactly one path (alice -> bob -> c1) reaches c1, so the query
   * returns a single row. Confirms the promotion preserves query results.
   */
  @Test
  public void multiHopStaticRidInWhere_returnsMatchingPath() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid = " + c1 + ")}"
            + " RETURN p.name as pName, m.name as mName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("alice", result.get(0).getProperty("pName"));
    assertEquals("bob", result.get(0).getProperty("mName"));
    session.commit();
  }

  /**
   * The same chain with a non-existent RID (valid cluster, impossible position)
   * returns no rows. Confirms the promoted root resolves to an empty RID set
   * rather than falling back to a Comment class scan that would still match by
   * class.
   */
  @Test
  public void multiHopStaticRidInWhere_nonExistentRid_returnsEmpty() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var cluster = c1.substring(0, c1.indexOf(':'));
    var missingRid = cluster + ":999999";
    var result = session.query(
        "MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid = " + missingRid + ")}"
            + " RETURN p.name as pName")
        .toList();
    assertTrue("expected no rows for a non-existent RID, got: " + result.size(),
        result.isEmpty());
    session.commit();
  }

  /**
   * The fix proper: EXPLAIN must show the pinned Comment promoted to the root
   * via {@code FETCH FROM RIDs}, with the chain traversal reversed ({@code
   * <----}) back toward Person. Without promotion the planner would scan a
   * Person class and traverse forward ({@code ---->}).
   */
  @Test
  public void multiHopStaticRidInWhere_planRootsAtFetchFromRids() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "EXPLAIN MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid = " + c1 + ")}"
            + " RETURN p.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should fetch the pinned Comment by RID, got:\n" + plan,
        plan.contains("FETCH FROM RIDs"));
    assertTrue("plan should reverse the chain traversal, got:\n" + plan,
        plan.contains("<----"));
    session.commit();
  }

  /**
   * Pre-existing RID slot wins (the YTDB-629 promoter guard). A node may carry
   * both an explicit {@code rid:} slot and a WHERE {@code @rid} equality. The
   * parser slot populates {@code aliasRids} first, so the promoter skips the
   * alias and never overwrites it; the WHERE term then applies as a post-fetch
   * filter. With slot {@code #c1} and a conflicting filter {@code #c2}
   * (c1 != c2), the record fetched by the slot fails the filter, so the query
   * returns no rows.
   */
  @Test
  public void ridSlotConflictingWithWhereRid_returnsEmpty() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var c2 = ridOf("Comment", "c2");
    var result = session.query(
        "MATCH {class: Comment, as: c, rid: " + c1 + ", where: (@rid = " + c2 + ")}"
            + " RETURN c.name as cName")
        .toList();
    assertTrue(
        "slot #c1 drives the fetch; WHERE @rid=#c2 must drop it, got: " + result.size(),
        result.isEmpty());
    session.commit();
  }

  /**
   * Companion to {@link #ridSlotConflictingWithWhereRid_returnsEmpty}: when the
   * {@code rid:} slot and the WHERE {@code @rid} agree, the record fetched by the
   * slot passes the post-fetch filter and the row is returned. Together the two
   * tests show the slot drives the fetch while the redundant WHERE term is still
   * evaluated.
   */
  @Test
  public void ridSlotAgreeingWithWhereRid_returnsRow() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "MATCH {class: Comment, as: c, rid: " + c1 + ", where: (@rid = " + c1 + ")}"
            + " RETURN c.name as cName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("c1", result.get(0).getProperty("cName"));
    session.commit();
  }
}
