package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * End-to-end integration tests for the static-RID-in-WHERE promotion
 * (YTDB-629). When a MATCH node carries a literal or parameter {@code @rid}
 * equality or static {@code @rid IN [...]} in its WHERE clause,
 * {@link MatchExecutionPlanner#promoteStaticRidsFromFilters} lifts it into
 * {@code aliasPinnedRids}. That has two observable effects
 * in the plan:
 *
 * <ol>
 *   <li>the pinned alias is fetched by {@code FETCH FROM RIDs} instead of a
 *       class scan with an {@code @rid} post-filter;
 *   <li>{@link MatchExecutionPlanner#estimateRootEntries} collapses the pinned
 *       alias's estimate to the list size (or 1 for equality), which can flip
 *       root selection toward it.
 * </ol>
 *
 * <p>The two effects do not always coincide. In a small graph where the pinned
 * alias already sits on the smallest class, root selection picks it with or
 * without promotion (its class count alone wins), so only effect (1) is
 * observable; {@link #multiHopStaticRidInWhere_pinnedAliasFetchedByRid} and
 * {@link #multiHopStaticRidListInWhere_pinnedAliasFetchedByRid},
 * {@link #multiHopStaticRidListInWhere_returnsMatchingPath}, and
 * {@link #multiHopStaticRidListInWhere_parameterRidList_returnsMatchingPathAndFetchesByRid}
 * pin that case. Effect (2) needs a pinned alias on a larger class competing with a smaller
 * one; {@link #staticRid_winsRootSelectionOverSmallerClass} and
 * {@link #staticRidList_winsRootSelectionOverSmallerClass} pin that.
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
        .toList().getFirst().getProperty("rid").toString();
  }

  /** Returns the RID value object (not its string form) of a named record. */
  private Object ridObjectOf(String clazz, String name) {
    return session.query(
        "SELECT @rid as rid FROM " + clazz + " WHERE name = '" + name + "'")
        .toList().getFirst().getProperty("rid");
  }

  /**
   * Extracts the root alias of a MATCH plan: the alias printed on the first
   * non-blank line after the {@code + SET} marker emitted by
   * {@link MatchFirstStep}. The root determines which node the traversal starts
   * from, so this is the cleanest signal that promotion changed root selection.
   */
  private static String rootAlias(String plan) {
    var marker = "+ SET";
    var i = plan.indexOf(marker);
    if (i < 0) {
      return "";
    }
    for (var line : plan.substring(i + marker.length()).split("\n")) {
      var trimmed = line.trim();
      if (!trimmed.isEmpty()) {
        return trimmed;
      }
    }
    return "";
  }

  /**
   * Extracts the {@code + PREFETCH <alias>} block for one alias: the substring
   * from that marker up to the next top-level step ({@code + PREFETCH},
   * {@code + SET}, or {@code + MATCH}). Small aliases (estimate below the
   * prefetch threshold) are eagerly loaded by a {@link MatchPrefetchStep}, so a
   * pinned alias's RID fetch shows up here rather than in the root sub-plan.
   * Returns the empty string when the alias is not prefetched.
   */
  private static String prefetchBlock(String plan, String alias) {
    var marker = "+ PREFETCH " + alias + "\n";
    var start = plan.indexOf(marker);
    if (start < 0) {
      return "";
    }
    var from = start + marker.length();
    var end = plan.length();
    for (var next : new String[] {"+ PREFETCH ", "+ SET", "+ MATCH"}) {
      var idx = plan.indexOf(next, from);
      if (idx >= 0 && idx < end) {
        end = idx;
      }
    }
    return plan.substring(start, end);
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
    assertEquals("alice", result.getFirst().getProperty("pName"));
    assertEquals("bob", result.getFirst().getProperty("mName"));
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
    var missingRid = "-1:0";

    var result = session.query(
        "EXPLAIN MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid = " + missingRid + ")}"
            + " RETURN p.name")
        .toList();

    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    var cBlock = prefetchBlock(plan, "c");
    assertFalse("pinned Comment 'c' should be prefetched, got:\n" + plan,
        cBlock.isEmpty());
    assertTrue("'c' prefetch should fetch by RID, got:\n" + plan,
        cBlock.contains("FETCH FROM RIDs"));
    assertFalse("'c' prefetch should not fall back to a class scan, got:\n" + plan,
        cBlock.contains("FETCH FROM CLASS"));
    session.commit();
  }

  /**
   * The fix proper for this chain: the pinned Comment is fetched by RID rather
   * than scanned. EXPLAIN must show the {@code c} prefetch sourced from
   * {@code FETCH FROM RIDs} with no {@code FETCH FROM CLASS} fallback inside that
   * block. Without promotion the {@code c} prefetch is {@code FETCH FROM CLASS
   * Comment} plus an {@code @rid} post-filter (verified by toggling the promoter
   * off during development).
   *
   * <p>Root selection is deliberately not asserted here: Comment (3 rows) is
   * already the smallest class in this chain, so the root is {@code c} with or
   * without promotion. The root-selection flip is covered by
   * {@link #staticRid_winsRootSelectionOverSmallerClass}.
   */
  @Test
  public void multiHopStaticRidInWhere_pinnedAliasFetchedByRid() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "EXPLAIN MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid = " + c1 + ")}"
            + " RETURN p.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    var cBlock = prefetchBlock(plan, "c");
    assertFalse("pinned Comment 'c' should be prefetched, got:\n" + plan,
        cBlock.isEmpty());
    assertTrue("'c' prefetch should fetch by RID, got:\n" + plan,
        cBlock.contains("FETCH FROM RIDs"));
    assertFalse("'c' prefetch should not fall back to a class scan, got:\n" + plan,
        cBlock.contains("FETCH FROM CLASS"));
    session.commit();
  }

  /**
   * Parameter-bound RID ({@code @rid = :rid}) drives the same promotion as a
   * literal. This is the only end-to-end exercise of the parameter path through
   * {@link com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid#setExpression}
   * and {@code SQLRid.toRecordId}, which resolves the bound value at execution
   * time; the unit tests only verify that the alias reaches {@code aliasPinnedRids}.
   * Asserts both correct results (one path) and that {@code c} is fetched by RID.
   */
  @Test
  public void multiHopStaticRidInWhere_parameterRid_returnsMatchingPathAndFetchesByRid() {
    session.begin();
    Map<Object, Object> params = new HashMap<>();
    params.put("rid", ridObjectOf("Comment", "c1"));
    var query =
        "MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid = :rid)}"
            + " RETURN p.name as pName, m.name as mName";

    var result = session.query(query, params).toList();
    assertEquals(1, result.size());
    assertEquals("alice", result.getFirst().getProperty("pName"));
    assertEquals("bob", result.getFirst().getProperty("mName"));

    var explain = session.query("EXPLAIN " + query, params).toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("parameter @rid should be promoted to a RID fetch, got:\n" + plan,
        prefetchBlock(plan, "c").contains("FETCH FROM RIDs"));
    session.commit();
  }

  /**
   * Production-style {@code @rid IN [#a, #b]} on a MATCH node promotes to a
   * multi-RID fetch instead of scanning the class and filtering in memory.
   */
  @Test
  public void staticRidList_inWhere_fetchesByRid() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var c2 = ridOf("Comment", "c2");
    var query =
        "MATCH {class: Comment, as: c, where: (@rid in [" + c1 + ", " + c2 + "])}"
            + " RETURN c.name as name ORDER BY name";

    var result = session.query(query).toList();
    assertEquals(2, result.size());
    assertEquals("c1", result.get(0).getProperty("name"));
    assertEquals("c2", result.get(1).getProperty("name"));

    var explain = session.query("EXPLAIN " + query).toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("@rid IN list should prefetch via FETCH FROM RIDs, got:\n" + plan,
        prefetchBlock(plan, "c").contains("FETCH FROM RIDs"));
    assertFalse("@rid IN list should not class-scan Comment, got:\n" + plan,
        prefetchBlock(plan, "c").contains("FETCH FROM CLASS"));
    session.commit();
  }

  /**
   * Multi-hop chain with {@code @rid IN [c1]} returns the single matching path.
   */
  @Test
  public void multiHopStaticRidListInWhere_returnsMatchingPath() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid in [" + c1 + "])}"
            + " RETURN p.name as pName, m.name as mName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("alice", result.getFirst().getProperty("pName"));
    assertEquals("bob", result.getFirst().getProperty("mName"));
    session.commit();
  }

  /**
   * Multi-hop chain with a non-existent RID in the IN list returns no rows and
   * still fetches the Comment alias by RID rather than class scan.
   */
  @Test
  public void multiHopStaticRidListInWhere_nonExistentRid_returnsEmpty() {
    session.begin();
    var missingRid = "-1:0";

    var query =
        "MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid in [" + missingRid + "])}"
            + " RETURN p.name";

    var result = session.query(query).toList();
    assertTrue("non-existent RID in IN list must match no paths", result.isEmpty());

    var explain = session.query("EXPLAIN " + query).toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    var cBlock = prefetchBlock(plan, "c");
    assertTrue("'c' prefetch should fetch by RID, got:\n" + plan,
        cBlock.contains("FETCH FROM RIDs"));
    assertFalse("'c' prefetch should not class-scan Comment, got:\n" + plan,
        cBlock.contains("FETCH FROM CLASS"));
    session.commit();
  }

  /**
   * Parameter-bound RID list ({@code @rid IN :rids}) drives the same promotion
   * as literals through the multi-hop chain.
   */
  @Test
  public void multiHopStaticRidListInWhere_parameterRidList_returnsMatchingPathAndFetchesByRid() {
    session.begin();
    Map<Object, Object> params = new HashMap<>();
    params.put("rids", List.of(ridObjectOf("Comment", "c1")));
    var query =
        "MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid in :rids)}"
            + " RETURN p.name as pName, m.name as mName";

    var result = session.query(query, params).toList();
    assertEquals(1, result.size());
    assertEquals("alice", result.getFirst().getProperty("pName"));
    assertEquals("bob", result.getFirst().getProperty("mName"));

    var explain = session.query("EXPLAIN " + query, params).toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("parameter @rid IN list should prefetch by RID, got:\n" + plan,
        prefetchBlock(plan, "c").contains("FETCH FROM RIDs"));
    session.commit();
  }

  /**
   * Multi-hop chain with {@code @rid IN [#c1]} on the Comment alias. The pinned
   * Comment must be prefetched via {@code FETCH FROM RIDs}, not a class scan.
   */
  @Test
  public void multiHopStaticRidListInWhere_pinnedAliasFetchedByRid() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "EXPLAIN MATCH {class: Person, as: p}.out('Knows'){class: Person, as: m}"
            + ".out('Likes'){class: Comment, as: c, where: (@rid in [" + c1 + "])}"
            + " RETURN p.name")
        .toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    var cBlock = prefetchBlock(plan, "c");
    assertFalse("pinned Comment 'c' should be prefetched, got:\n" + plan,
        cBlock.isEmpty());
    assertTrue("'c' prefetch should fetch by RID, got:\n" + plan,
        cBlock.contains("FETCH FROM RIDs"));
    assertFalse("'c' prefetch should not fall back to a class scan, got:\n" + plan,
        cBlock.contains("FETCH FROM CLASS"));
    session.commit();
  }

  /**
   * Root-selection flip with a singleton {@code @rid IN [rid]} list — same
   * estimate collapse (1) as {@link #staticRid_winsRootSelectionOverSmallerClass}.
   */
  @Test
  public void staticRidList_winsRootSelectionOverSmallerClass() {
    session.execute("CREATE class Big extends V").close();
    session.execute("CREATE property Big.name STRING").close();
    session.execute("CREATE class Small extends V").close();
    session.execute("CREATE property Small.name STRING").close();
    session.execute("CREATE class Rel extends E").close();

    session.begin();
    for (var i = 0; i < 40; i++) {
      session.execute("CREATE VERTEX Big set name = 'b" + i + "'").close();
    }
    for (var i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX Small set name = 's" + i + "'").close();
    }
    session.execute(
        "CREATE EDGE Rel FROM (SELECT FROM Big WHERE name = 'b0')"
            + " TO (SELECT FROM Small WHERE name = 's0')")
        .close();
    session.commit();

    session.begin();
    var b0 = ridOf("Big", "b0");
    var explain = session.query(
        "EXPLAIN MATCH {class: Big, as: b, where: (@rid in [" + b0 + "])}"
            + ".out('Rel'){class: Small, as: s} RETURN b.name")
        .toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertEquals(
        "IN-list promotion must flip the root to pinned Big alias 'b', got:\n" + plan,
        "b", rootAlias(plan));
    assertTrue("pinned Big alias should be fetched by RID, got:\n" + plan,
        prefetchBlock(plan, "b").contains("FETCH FROM RIDs"));

    var result = session.query(
        "MATCH {class: Big, as: b, where: (@rid in [" + b0 + "])}"
            + ".out('Rel'){class: Small, as: s} RETURN s.name as sName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("s0", result.getFirst().getProperty("sName"));
    session.commit();
  }

  /**
   * Root-selection flip with a two-RID {@code @rid IN [rid0, rid1]} list: estimate
   * collapses to 2, still below Small's biased estimate of 4.
   */
  @Test
  public void staticRidListTwoRids_winsRootSelectionOverSmallerClass() {
    session.execute("CREATE class Big extends V").close();
    session.execute("CREATE property Big.name STRING").close();
    session.execute("CREATE class Small extends V").close();
    session.execute("CREATE property Small.name STRING").close();
    session.execute("CREATE class Rel extends E").close();

    session.begin();
    for (var i = 0; i < 40; i++) {
      session.execute("CREATE VERTEX Big set name = 'b" + i + "'").close();
    }
    for (var i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX Small set name = 's" + i + "'").close();
    }
    session.execute(
        "CREATE EDGE Rel FROM (SELECT FROM Big WHERE name = 'b0')"
            + " TO (SELECT FROM Small WHERE name = 's0')")
        .close();
    session.commit();

    session.begin();
    var b0 = ridOf("Big", "b0");
    var b1 = ridOf("Big", "b1");
    var explain = session.query(
        "EXPLAIN MATCH {class: Big, as: b, where: (@rid in [" + b0 + ", " + b1 + "])}"
            + ".out('Rel'){class: Small, as: s} RETURN b.name")
        .toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertEquals(
        "two-RID IN list (estimate=2) must flip root to Big alias 'b', got:\n" + plan,
        "b", rootAlias(plan));
    assertTrue("pinned Big alias should be fetched by RID, got:\n" + plan,
        prefetchBlock(plan, "b").contains("FETCH FROM RIDs"));

    var result = session.query(
        "MATCH {class: Big, as: b, where: (@rid in [" + b0 + ", " + b1 + "])}"
            + ".out('Rel'){class: Small, as: s} RETURN s.name as sName ORDER BY sName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("s0", result.getFirst().getProperty("sName"));
    session.commit();
  }

  /**
   * Root-selection flip, the optimization's stated purpose. The cost model in
   * {@link MatchExecutionPlanner#estimateRootEntries} gives an unpinned class
   * under the prefetch threshold an estimate of {@code count / 2} and a pinned
   * alias an estimate of 1. Big (40 rows) estimates to 20; Small (3 rows)
   * estimates to 4. By class size alone Small is the cheaper root, so without
   * promotion the planner roots at Small and traverses backward to Big. Pinning
   * Big by {@code @rid} collapses its estimate to 1, so promotion flips the root
   * to Big. Asserting the root is {@code b} proves promotion changed the root
   * choice, not merely the fetch method.
   */
  @Test
  public void staticRid_winsRootSelectionOverSmallerClass() {
    session.execute("CREATE class Big extends V").close();
    session.execute("CREATE property Big.name STRING").close();
    session.execute("CREATE class Small extends V").close();
    session.execute("CREATE property Small.name STRING").close();
    session.execute("CREATE class Rel extends E").close();

    session.begin();
    for (var i = 0; i < 40; i++) {
      session.execute("CREATE VERTEX Big set name = 'b" + i + "'").close();
    }
    for (var i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX Small set name = 's" + i + "'").close();
    }
    // One edge so the pinned Big has a path to a Small.
    session.execute(
        "CREATE EDGE Rel FROM (SELECT FROM Big WHERE name = 'b0')"
            + " TO (SELECT FROM Small WHERE name = 's0')")
        .close();
    session.commit();

    session.begin();
    var b0 = ridOf("Big", "b0");
    var explain = session.query(
        "EXPLAIN MATCH {class: Big, as: b, where: (@rid = " + b0 + ")}"
            + ".out('Rel'){class: Small, as: s} RETURN b.name")
        .toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertEquals(
        "promotion must flip the root to the pinned Big alias 'b', got:\n" + plan,
        "b", rootAlias(plan));
    assertTrue("pinned Big alias should be fetched by RID, got:\n" + plan,
        prefetchBlock(plan, "b").contains("FETCH FROM RIDs"));

    // Correctness: the single Rel edge from the pinned Big reaches s0.
    var result = session.query(
        "MATCH {class: Big, as: b, where: (@rid = " + b0 + ")}"
            + ".out('Rel'){class: Small, as: s} RETURN s.name as sName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("s0", result.getFirst().getProperty("sName"));
    session.commit();
  }

  /**
   * Negative case: a {@code @rid = $matched.p.@rid} back-ref is a runtime
   * correlation, not a static RID, so the promoter must skip it and the plan
   * must contain no {@code FETCH FROM RIDs}. The self-Knows pattern (m must equal
   * p) matches nothing in this graph because no Person Knows itself, which also
   * confirms the back-ref is still applied as a post-fetch filter rather than
   * silently dropped.
   */
  @Test
  public void backRefRidEquality_isNotPromoted_noRidFetchInPlan() {
    session.begin();
    var query =
        "MATCH {class: Person, as: p}.out('Knows')"
            + "{class: Person, as: m, where: (@rid = $matched.p.@rid)} RETURN p.name as pName";

    var explain = session.query("EXPLAIN " + query).toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse("back-ref @rid must not be promoted to a RID fetch, got:\n" + plan,
        plan.contains("FETCH FROM RIDs"));

    var result = session.query(query).toList();
    assertTrue("no Person Knows itself, so the back-ref matches nothing, got: "
        + result.size(),
        result.isEmpty());
    session.commit();
  }

  /**
   * Negative case: {@code @rid IN $matched.p.@rid} is a runtime correlation, not a
   * static list, so the promoter must skip it and the plan must contain no
   * {@code FETCH FROM RIDs}. Mirrors {@link #backRefRidEquality_isNotPromoted_noRidFetchInPlan}.
   */
  @Test
  public void backRefRidInList_isNotPromoted_noRidFetchInPlan() {
    session.begin();
    var query =
        "MATCH {class: Person, as: p}.out('Knows')"
            + "{class: Person, as: m, where: (@rid in $matched.p.@rid)} RETURN p.name as pName";

    var explain = session.query("EXPLAIN " + query).toList();
    String plan = explain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertFalse("back-ref @rid IN must not be promoted to a RID fetch, got:\n" + plan,
        plan.contains("FETCH FROM RIDs"));

    var result = session.query(query).toList();
    assertTrue("no Person Knows itself, so the back-ref IN matches nothing, got: "
        + result.size(),
        result.isEmpty());
    session.commit();
  }

  /**
   * Pre-existing RID slot wins (the YTDB-629 promoter guard). A node may carry
   * both an explicit {@code rid:} slot and a WHERE {@code @rid} equality. The
   * parser slot populates {@code aliasPinnedRids} first, so the promoter skips the
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

  /**
   * OR correctness, end to end: {@code where: (@rid = #c1 OR name = 'c2')} must
   * return both the pinned record (c1) and the name-matched record (c2). The
   * promoter must not fire for a disjunction; if it wrongly pinned the root to
   * #c1 alone, the c2 row would be lost. Two distinct names confirm the OR
   * branch survives.
   */
  @Test
  public void singleNodeRidOrName_returnsBothBranches() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "MATCH {class: Comment, as: c, where: (@rid = " + c1 + " OR name = 'c2')}"
            + " RETURN c.name as cName")
        .toList();
    var names = result.stream()
        .map(r -> (String) r.getProperty("cName"))
        .collect(java.util.stream.Collectors.toSet());
    assertEquals("OR must match both the pinned RID and the named record, got: " + names,
        java.util.Set.of("c1", "c2"), names);
    session.commit();
  }

  /**
   * OR correctness for IN lists: {@code where: (@rid in [#c1] OR name = 'c2')}
   * must return both the pinned record (c1) and the name-matched record (c2).
   * If the promoter wrongly pinned the root to the IN list alone, c2 would be lost.
   */
  @Test
  public void singleNodeRidListOrName_returnsBothBranches() {
    session.begin();
    var c1 = ridOf("Comment", "c1");
    var result = session.query(
        "MATCH {class: Comment, as: c, where: (@rid in [" + c1 + "] OR name = 'c2')}"
            + " RETURN c.name as cName")
        .toList();
    var names = result.stream()
        .map(r -> (String) r.getProperty("cName"))
        .collect(java.util.stream.Collectors.toSet());
    assertEquals("OR must match both the pinned RID list and the named record, got: " + names,
        java.util.Set.of("c1", "c2"), names);
    session.commit();
  }

  /**
   * Exercises the scenario where two different aliases both carry a static {@code @rid}
   * in their WHERE clauses. The promoter must successfully process both aliases,
   * dropping both estimates to 1, letting the planner's stable sort tie-break
   * determine the root choice and traversal direction.
   */
  @Test
  public void dualStaticRidInWhere_bothPromoted_plannerTieBreakDecidesRoot() {
    session.begin();
    var alice = ridOf("Person", "alice");
    var bob = ridOf("Person", "bob");

    var query = "EXPLAIN MATCH {class: Person, as: p, where: (@rid = " + alice + ")}"
        + ".out('Knows'){class: Person, as: m, where: (@rid = " + bob + ")}"
        + " RETURN p.name";

    var result = session.query(query).toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    var pBlock = prefetchBlock(plan, "p");
    var mBlock = prefetchBlock(plan, "m");

    assertTrue("Alias 'p' must be optimized to FETCH FROM RIDs, got:\n" + plan,
        pBlock.contains("FETCH FROM RIDs"));
    assertTrue("Alias 'm' must be optimized to FETCH FROM RIDs, got:\n" + plan,
        mBlock.contains("FETCH FROM RIDs"));

    assertFalse("Alias 'p' must not fallback to class scan", pBlock.contains("FETCH FROM CLASS"));
    assertFalse("Alias 'm' must not fallback to class scan", mBlock.contains("FETCH FROM CLASS"));

    assertEquals("Planner stable sort tie-break must select 'p' as the root node, got:\n" + plan,
        "p", rootAlias(plan));

    session.commit();
  }

  /**
   * Two aliases with singleton {@code @rid IN [rid]} lists — same estimate collapse
   * (1 per alias) as {@link #dualStaticRidInWhere_bothPromoted_plannerTieBreakDecidesRoot}.
   */
  @Test
  public void dualStaticRidListInWhere_bothPromoted_plannerTieBreakDecidesRoot() {
    session.begin();
    var alice = ridOf("Person", "alice");
    var bob = ridOf("Person", "bob");

    var query = "EXPLAIN MATCH {class: Person, as: p, where: (@rid in [" + alice + "])}"
        + ".out('Knows'){class: Person, as: m, where: (@rid in [" + bob + "])}"
        + " RETURN p.name";

    var result = session.query(query).toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    var pBlock = prefetchBlock(plan, "p");
    var mBlock = prefetchBlock(plan, "m");

    assertTrue("Alias 'p' must prefetch via FETCH FROM RIDs, got:\n" + plan,
        pBlock.contains("FETCH FROM RIDs"));
    assertTrue("Alias 'm' must prefetch via FETCH FROM RIDs, got:\n" + plan,
        mBlock.contains("FETCH FROM RIDs"));
    assertFalse("Alias 'p' must not class-scan Person", pBlock.contains("FETCH FROM CLASS"));
    assertFalse("Alias 'm' must not class-scan Person", mBlock.contains("FETCH FROM CLASS"));
    assertEquals(
        "Planner stable sort tie-break must select 'p' as root for IN lists too, got:\n" + plan,
        "p", rootAlias(plan));

    var rows = session.query(
        "MATCH {class: Person, as: p, where: (@rid in [" + alice + "])}"
            + ".out('Knows'){class: Person, as: m, where: (@rid in [" + bob + "])}"
            + " RETURN p.name as pName, m.name as mName")
        .toList();
    assertEquals(1, rows.size());
    assertEquals("alice", rows.getFirst().getProperty("pName"));
    assertEquals("bob", rows.getFirst().getProperty("mName"));
    session.commit();
  }

  /**
   * The discriminating plan-shape test for the size-1 {@code @rid IN [#x]} to
   * {@code setLeftRid} routing on the REVERSE traversal path. This is the case the
   * reviewer flagged: post the aliasRids single-map consolidation, a size-1 IN list
   * flows through {@link MatchExecutionPlanner#singletonPinnedRid} into
   * {@link EdgeTraversal#setLeftRid}, and when the pinned node is the SOURCE
   * ({@code edge.out}) of an edge scheduled in reverse, that RID becomes the reverse
   * traverser's target constraint ({@link MatchReverseEdgeTraverser#targetRid}).
   *
   * <p>Why both ends must be pinned: a single size-1 pin always wins root selection
   * (estimate collapses to 1), so the planner roots at it, prefetches it by RID, and
   * traverses AWAY from it forward — the reverse routing never fires and stays
   * hidden. Only when BOTH ends of an edge carry a size-1 pin can the planner root
   * on one end and reverse-traverse into the other pinned end, populating the
   * reverse edge's {@code leftRid} from the (pinned) {@code edge.out}.
   *
   * <p>The graph here is a triangle {@code a --Knows--> b --Ride--> t --Knows--> a}
   * with both {@code a} and {@code t} pinned by singleton {@code @rid IN}. The
   * planner roots at {@code a}, walks {@code a->b->t} forward, then closes the cycle
   * with the consistency-check edge {@code {t}.out('Knows'){a}} scheduled in REVERSE.
   * Because {@code t} (the closing edge's {@code edge.out}) is pinned, the closing
   * reverse step carries {@code [leftRid=<t's RID>]} in the plan.
   *
   * <p>Discrimination: EXPLAIN asserts the {@code <----} closing step shows
   * {@code leftRid=<t's RID>}. A broken {@code size() == 1} predicate in
   * {@code singletonPinnedRid} drops that annotation (verified during development by
   * mutating the predicate to {@code size() == 2}). Result-correctness alone cannot
   * catch the mutation: {@code t} is still prefetched by RID (the prefetch uses the
   * full pinned list, not {@code singletonPinnedRid}) and the retained WHERE
   * {@code leftFilter} still validates the target, so the rows stay correct — the
   * plan-shape {@code leftRid} annotation is the sole discriminating signal.
   */
  @Test
  public void reverseEdgeIntoBothEndsPinned_leftRidPopulatedInPlan() {
    // Inline triangle fixture: a --Knows--> b --Ride--> t --Knows--> a.
    // 'Ride' is a fresh edge class so the middle hop is distinct from the two
    // Knows hops; the closing Knows edge (t -> a) is what gets scheduled reverse.
    session.execute("CREATE class Ride extends E").close();
    session.begin();
    knows("carol", "dave"); // a=carol -> b=dave (Knows already exists as a class)
    ride("dave", "erin"); // b=dave -> t=erin (Ride)
    knows("erin", "carol"); // t=erin -> a=carol (Knows) closes the triangle
    session.commit();

    session.begin();
    var a = ridOf("Person", "carol");
    var t = ridOf("Person", "erin");
    // Pin BOTH a (root) and t (reverse target). t's RID must appear as leftRid on
    // the closing reverse edge {t}.out('Knows'){a}.
    var query =
        "EXPLAIN MATCH {class: Person, as: a, where: (@rid in [" + a + "])}"
            + ".out('Knows'){class: Person, as: b}"
            + ".out('Ride'){class: Person, as: t, where: (@rid in [" + t + "])}"
            + ".out('Knows'){class: Person, as: a} RETURN a.name";
    var result = session.query(query).toList();
    assertEquals(1, result.size());
    String plan = result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    // Discriminating assertion: the reverse closing step carries t's pinned RID as
    // leftRid. This is populated ONLY via singletonPinnedRid(size-1 list) ->
    // setLeftRid on the reverse edge; a broken size()==1 predicate drops it.
    assertTrue(
        "reverse closing edge must carry [leftRid=" + t + "], got:\n" + plan,
        plan.contains("<----") && plan.contains("[leftRid=" + t + "]"));

    // Sanity: t is still prefetched by RID (the effect that masks the mutation at
    // the result level), confirming leftRid — not the prefetch — is the signal.
    assertTrue("pinned 't' should be prefetched by RID, got:\n" + plan,
        prefetchBlock(plan, "t").contains("FETCH FROM RIDs"));
    session.commit();
  }

  /** Creates a Ride edge between two named Persons (fresh edge class for the triangle). */
  private void ride(String from, String to) {
    session.execute(
        "CREATE EDGE Ride FROM (SELECT FROM Person WHERE name = '" + from + "')"
            + " TO (SELECT FROM Person WHERE name = '" + to + "')")
        .close();
  }
}
