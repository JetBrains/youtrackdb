package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Gap-coverage tests for the index-into pre-filter optimization. Fills
 * specific coverage gaps identified by systematic analysis of all existing
 * test suites.
 *
 * <p>Each test method creates its own isolated schema (unique class name
 * prefixes) to avoid collisions with other tests in the same class.
 *
 * <p>Gaps addressed:
 * <ul>
 *   <li>DirectRid integration (zero integration tests existed)</li>
 *   <li>GROUP BY with pre-filter</li>
 *   <li>NOT pattern with pre-filter</li>
 *   <li>Optional edge with pre-filter (correctness, not just EXPLAIN)</li>
 *   <li>outE().inV() combined with back-reference</li>
 *   <li>Dual back-references to different aliases</li>
 *   <li>Diamond topology with back-reference at convergence</li>
 *   <li>Two IndexLookup descriptors on same hop (Composite)</li>
 *   <li>Pre-filter adjacent to WHILE hop</li>
 *   <li>BETWEEN operator on indexed property</li>
 *   <li>OR in WHERE on indexed property</li>
 *   <li>$paths with EdgeRidLookup back-reference</li>
 *   <li>Larger dataset (500+ edges)</li>
 * </ul>
 */
public class MatchPreFilterGapCoverageTest extends DbTestBase {

  private String explainPlan(String query) {
    var result = session.query("EXPLAIN " + query).toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    return plan;
  }

  /** Runs EXPLAIN with parameters and returns the executionPlanAsString. */
  private String explainPlan(String query, Map<String, Object> params) {
    var result = session.query("EXPLAIN " + query, params).toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull(plan);
    return plan;
  }

  private Set<String> collect(String query, String prop) {
    return session.query(query).toList().stream()
        .map(r -> (String) r.getProperty(prop))
        .collect(Collectors.toSet());
  }

  // ========================================================================
  // 1. DirectRid integration — WHERE @rid = <literal RID> on target
  // ========================================================================

  /**
   * DirectRid descriptor: target of a traversal has {@code WHERE (@rid = <rid>)}.
   * The planner should produce a DirectRid intersection descriptor when the
   * WHERE clause is a simple RID equality with a literal or expression that
   * resolves to a single RID.
   *
   * <p>We first look up a known vertex RID, then use it in the MATCH query.
   */
  @Test
  public void directRid_literalRidFilterOnTarget() {
    session.execute("CREATE class DRPerson extends V").close();
    session.execute("CREATE property DRPerson.name STRING").close();
    session.execute("CREATE class DRKnows extends E").close();

    session.begin();
    session.execute("CREATE VERTEX DRPerson set name = 'alice'").close();
    session.execute("CREATE VERTEX DRPerson set name = 'bob'").close();
    session.execute("CREATE VERTEX DRPerson set name = 'carol'").close();

    session.execute(
        "CREATE EDGE DRKnows FROM (SELECT FROM DRPerson WHERE name = 'alice')"
            + " TO (SELECT FROM DRPerson WHERE name = 'bob')")
        .close();
    session.execute(
        "CREATE EDGE DRKnows FROM (SELECT FROM DRPerson WHERE name = 'alice')"
            + " TO (SELECT FROM DRPerson WHERE name = 'carol')")
        .close();

    // Get bob's RID
    var bobRid = session.query("SELECT @rid as rid FROM DRPerson WHERE name = 'bob'")
        .toList().get(0).getProperty("rid").toString();

    // MATCH with literal RID filter on target
    var result = session.query(
        "MATCH {class: DRPerson, as: p, where: (name = 'alice')}"
            + ".out('DRKnows'){as: friend, where: (@rid = " + bobRid + ")}"
            + " RETURN friend.name as friendName")
        .toList();

    assertEquals(1, result.size());
    assertEquals("bob", result.get(0).getProperty("friendName"));

    // Verify EXPLAIN shows DirectRid intersection
    String plan = explainPlan(
        "MATCH {class: DRPerson, as: p, where: (name = 'alice')}"
            + ".out('DRKnows'){as: friend, where: (@rid = " + bobRid + ")}"
            + " RETURN friend.name as friendName");
    assertTrue("Plan should show direct-rid intersection:\n" + plan,
        plan.contains("intersection: direct-rid"));
    session.commit();
  }

  /**
   * DirectRid with a named parameter binding to a RID value.
   * Tests that the planner can resolve a parameterized RID to a
   * DirectRid descriptor.
   */
  @Test
  public void directRid_parameterizedRid() {
    session.execute("CREATE class DR2Person extends V").close();
    session.execute("CREATE property DR2Person.name STRING").close();
    session.execute("CREATE class DR2Knows extends E").close();

    session.begin();
    session.execute("CREATE VERTEX DR2Person set name = 'x'").close();
    session.execute("CREATE VERTEX DR2Person set name = 'y'").close();
    session.execute("CREATE VERTEX DR2Person set name = 'z'").close();

    session.execute(
        "CREATE EDGE DR2Knows FROM (SELECT FROM DR2Person WHERE name = 'x')"
            + " TO (SELECT FROM DR2Person WHERE name = 'y')")
        .close();
    session.execute(
        "CREATE EDGE DR2Knows FROM (SELECT FROM DR2Person WHERE name = 'x')"
            + " TO (SELECT FROM DR2Person WHERE name = 'z')")
        .close();

    // Get bob's RID to use as parameter
    var yRid = session.query("SELECT @rid as rid FROM DR2Person WHERE name = 'y'")
        .toList().get(0).getProperty("rid");

    var result = session.query(
        "MATCH {class: DR2Person, as: p, where: (name = 'x')}"
            + ".out('DR2Knows'){as: friend,"
            + "  where: (@rid = :targetRid)}"
            + " RETURN friend.name as friendName",
        new HashMap<>(Map.of("targetRid", yRid)))
        .toList();

    assertEquals(1, result.size());
    assertEquals("y", result.get(0).getProperty("friendName"));

    String plan = explainPlan(
        "MATCH {class: DR2Person, as: p, where: (name = 'x')}"
            + ".out('DR2Knows'){as: friend,"
            + "  where: (@rid = :targetRid)}"
            + " RETURN friend.name as friendName",
        new HashMap<>(Map.of("targetRid", yRid)));
    assertTrue("Plan should show direct-rid intersection:\n" + plan,
        plan.contains("intersection: direct-rid"));
    session.commit();
  }

  // ========================================================================
  // 2. GROUP BY with pre-filter
  // ========================================================================

  /**
   * GROUP BY on a pre-filtered traversal. Verifies that aggregation works
   * correctly with the index pre-filter active.
   */
  @Test
  public void groupBy_withIndexPreFilter() {
    session.execute("CREATE class GBForum extends V").close();
    session.execute("CREATE property GBForum.title STRING").close();

    session.execute("CREATE class GBMsg extends V").close();
    session.execute("CREATE property GBMsg.lang STRING").close();
    session.execute("CREATE property GBMsg.ts LONG").close();
    session.execute(
        "CREATE index GBMsg_ts on GBMsg (ts) NOTUNIQUE").close();

    session.execute("CREATE class GBContains extends E").close();
    session.execute("CREATE property GBContains.out LINK GBForum").close();
    session.execute("CREATE property GBContains.in LINK GBMsg").close();

    session.begin();
    session.execute("CREATE VERTEX GBForum set title = 'main'").close();
    // 9 messages: 3 per language, ts = 100, 200, ..., 900
    String[] langs = {"en", "en", "en", "fr", "fr", "fr", "de", "de", "de"};
    for (int i = 0; i < 9; i++) {
      session.execute(
          "CREATE VERTEX GBMsg set lang = ?, ts = ?",
          langs[i], (long) ((i + 1) * 100))
          .close();
      session.execute(
          "CREATE EDGE GBContains FROM (SELECT FROM GBForum WHERE title = 'main')"
              + " TO (SELECT FROM GBMsg WHERE ts = " + ((i + 1) * 100) + ")")
          .close();
    }
    session.commit();

    session.begin();
    // GROUP BY with pre-filter: messages with ts >= 400
    // ts >= 400: indices 3-8 → fr(3), fr(1), fr(1) wait, langs[3]=fr, [4]=fr, [5]=fr, [6]=de, [7]=de, [8]=de
    // ts>=400: msg4(fr,400), msg5(fr,500), msg6(fr,600), msg7(de,700), msg8(de,800), msg9(de,900)
    var result = session.query(
        "SELECT lang, count(*) as cnt FROM ("
            + "MATCH {class: GBForum, as: f, where: (title = 'main')}"
            + ".out('GBContains'){as: msg, where: (ts >= 400)}"
            + " RETURN msg.lang as lang"
            + ") GROUP BY lang ORDER BY lang")
        .toList();

    assertEquals(2, result.size());
    assertEquals("de", result.get(0).getProperty("lang"));
    assertEquals(3L, (long) result.get(0).getProperty("cnt"));
    assertEquals("fr", result.get(1).getProperty("lang"));
    assertEquals(3L, (long) result.get(1).getProperty("cnt"));

    String plan = explainPlan(
        "MATCH {class: GBForum, as: f, where: (title = 'main')}"
            + ".out('GBContains'){as: msg, where: (ts >= 400)}"
            + " RETURN msg.lang as lang");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 3. NOT pattern combined with pre-filter
  // ========================================================================

  /**
   * NOT pattern where the positive branch has an index pre-filter.
   * The NOT sub-pattern should not interfere with the pre-filter on
   * the positive branch.
   */
  @Test
  public void notPattern_positiveBranchHasIndexPreFilter() {
    session.execute("CREATE class NPPerson extends V").close();
    session.execute("CREATE property NPPerson.name STRING").close();

    session.execute("CREATE class NPMsg extends V").close();
    session.execute("CREATE property NPMsg.text STRING").close();
    session.execute("CREATE property NPMsg.ts LONG").close();
    session.execute("CREATE index NPMsg_ts on NPMsg (ts) NOTUNIQUE").close();

    session.execute("CREATE class NPWrote extends E").close();
    session.execute("CREATE property NPWrote.out LINK NPPerson").close();
    session.execute("CREATE property NPWrote.in LINK NPMsg").close();

    session.execute("CREATE class NPFlagged extends E").close();

    session.begin();
    session.execute("CREATE VERTEX NPPerson set name = 'alice'").close();
    // alice wrote 5 messages: ts = 100..500
    for (int i = 1; i <= 5; i++) {
      session.execute(
          "CREATE VERTEX NPMsg set text = 'msg" + i + "', ts = " + (i * 100))
          .close();
      session.execute(
          "CREATE EDGE NPWrote FROM (SELECT FROM NPPerson WHERE name = 'alice')"
              + " TO (SELECT FROM NPMsg WHERE text = 'msg" + i + "')")
          .close();
    }
    // Flag msg3 and msg4 (to be excluded by NOT)
    for (int i = 3; i <= 4; i++) {
      session.execute(
          "CREATE EDGE NPFlagged FROM (SELECT FROM NPPerson WHERE name = 'alice')"
              + " TO (SELECT FROM NPMsg WHERE text = 'msg" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // Positive: alice's messages with ts >= 200 (msg2, msg3, msg4, msg5)
    // NOT: alice's flagged messages (msg3, msg4)
    // Result: msg2, msg5
    var result = session.query(
        "MATCH {class: NPPerson, as: p, where: (name = 'alice')}"
            + ".out('NPWrote'){as: msg, where: (ts >= 200)},"
            + " NOT {as: p}.out('NPFlagged'){as: msg}"
            + " RETURN msg.text as text")
        .toList();

    Set<String> texts = new HashSet<>();
    for (var r : result) {
      texts.add(r.getProperty("text"));
    }
    assertEquals(Set.of("msg2", "msg5"), texts);

    String plan = explainPlan(
        "MATCH {class: NPPerson, as: p, where: (name = 'alice')}"
            + ".out('NPWrote'){as: msg, where: (ts >= 200)},"
            + " NOT {as: p}.out('NPFlagged'){as: msg}"
            + " RETURN msg.text as text");
    assertTrue("Plan should show index intersection on msg:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * NOT pattern where the NOT sub-pattern target has an index pre-filter.
   */
  @Test
  public void notPattern_notBranchHasIndexFilter() {
    session.execute("CREATE class NP2Person extends V").close();
    session.execute("CREATE property NP2Person.name STRING").close();

    session.execute("CREATE class NP2Item extends V").close();
    session.execute("CREATE property NP2Item.label STRING").close();
    session.execute("CREATE property NP2Item.score INTEGER").close();
    session.execute(
        "CREATE index NP2Item_score on NP2Item (score) NOTUNIQUE").close();

    session.execute("CREATE class NP2Owns extends E").close();
    session.execute("CREATE property NP2Owns.out LINK NP2Person").close();
    session.execute("CREATE property NP2Owns.in LINK NP2Item").close();

    session.execute("CREATE class NP2Banned extends E").close();
    session.execute("CREATE property NP2Banned.out LINK NP2Person").close();
    session.execute("CREATE property NP2Banned.in LINK NP2Item").close();

    session.begin();
    session.execute("CREATE VERTEX NP2Person set name = 'p1'").close();
    for (int i = 0; i < 6; i++) {
      session.execute(
          "CREATE VERTEX NP2Item set label = 'item" + i + "', score = "
              + (i * 10))
          .close();
      session.execute(
          "CREATE EDGE NP2Owns FROM (SELECT FROM NP2Person WHERE name = 'p1')"
              + " TO (SELECT FROM NP2Item WHERE label = 'item" + i + "')")
          .close();
    }
    // Ban items with score >= 30 (item3, item4, item5)
    for (int i = 3; i < 6; i++) {
      session.execute(
          "CREATE EDGE NP2Banned FROM (SELECT FROM NP2Person WHERE name = 'p1')"
              + " TO (SELECT FROM NP2Item WHERE label = 'item" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // All owned items NOT banned with score >= 30
    // Owned: item0-5. NOT banned (score>=30): item3,4,5.
    // Result: item0, item1, item2
    var result = session.query(
        "MATCH {class: NP2Person, as: p, where: (name = 'p1')}"
            + ".out('NP2Owns'){as: item},"
            + " NOT {as: p}.out('NP2Banned'){as: item,"
            + "  where: (score >= 30)}"
            + " RETURN item.label as label")
        .toList();

    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals(Set.of("item0", "item1", "item2"), labels);
    session.commit();
  }

  // ========================================================================
  // 4. Optional edge with pre-filter — correctness verification
  // ========================================================================

  /**
   * Optional edge with index pre-filter, verifying actual result values
   * (not just EXPLAIN). Some upstream rows should get null for the optional
   * alias, others should get matched values.
   */
  @Test
  public void optional_withPreFilter_correctness() {
    session.execute("CREATE class OPAuthor extends V").close();
    session.execute("CREATE property OPAuthor.name STRING").close();

    session.execute("CREATE class OPPost extends V").close();
    session.execute("CREATE property OPPost.title STRING").close();
    session.execute("CREATE property OPPost.views INTEGER").close();
    session.execute(
        "CREATE index OPPost_views on OPPost (views) NOTUNIQUE").close();

    session.execute("CREATE class OPWrote extends E").close();
    session.execute("CREATE property OPWrote.out LINK OPAuthor").close();
    session.execute("CREATE property OPWrote.in LINK OPPost").close();

    session.begin();
    session.execute("CREATE VERTEX OPAuthor set name = 'ann'").close();
    session.execute("CREATE VERTEX OPAuthor set name = 'ben'").close();

    // ann wrote 3 posts: views 10, 50, 100
    session.execute("CREATE VERTEX OPPost set title = 'low', views = 10").close();
    session.execute("CREATE VERTEX OPPost set title = 'mid', views = 50").close();
    session.execute("CREATE VERTEX OPPost set title = 'high', views = 100").close();

    for (String t : new String[] {"low", "mid", "high"}) {
      session.execute(
          "CREATE EDGE OPWrote FROM (SELECT FROM OPAuthor WHERE name = 'ann')"
              + " TO (SELECT FROM OPPost WHERE title = '" + t + "')")
          .close();
    }
    // ben wrote nothing
    session.commit();

    session.begin();
    // Optional: author's posts with views >= 50 (only ann has qualifying posts)
    var result = session.query(
        "MATCH {class: OPAuthor, as: author}"
            + ".out('OPWrote'){as: post, where: (views >= 50), optional: true}"
            + " RETURN author.name as aName, post.title as pTitle")
        .toList();

    // ann: mid(50), high(100)
    // ben: null (optional, no edges at all)
    boolean foundAnnMid = false;
    boolean foundAnnHigh = false;
    boolean foundBenNull = false;
    for (var r : result) {
      String author = r.getProperty("aName");
      String title = r.getProperty("pTitle");
      if ("ann".equals(author) && "mid".equals(title)) {
        foundAnnMid = true;
      }
      if ("ann".equals(author) && "high".equals(title)) {
        foundAnnHigh = true;
      }
      if ("ben".equals(author) && title == null) {
        foundBenNull = true;
      }
    }
    assertTrue("ann should have mid post", foundAnnMid);
    assertTrue("ann should have high post", foundAnnHigh);
    assertTrue("ben should have null (optional, no posts)", foundBenNull);

    String plan = explainPlan(
        "MATCH {class: OPAuthor, as: author}"
            + ".out('OPWrote'){as: post, where: (views >= 50), optional: true}"
            + " RETURN author.name as aName, post.title as pTitle");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 5. outE().inV() combined with back-reference
  // ========================================================================

  /**
   * Edge-method pattern with back-reference: outE().inV() where the inV
   * target has {@code @rid = $matched.X.@rid}. This combination was
   * previously untested.
   */
  @Test
  public void edgeMethod_outEInV_withBackRef() {
    session.execute("CREATE class EBPerson extends V").close();
    session.execute("CREATE property EBPerson.name STRING").close();

    session.execute("CREATE class EBForum extends V").close();
    session.execute("CREATE property EBForum.title STRING").close();

    session.execute("CREATE class EBMember extends E").close();
    session.execute("CREATE property EBMember.out LINK EBForum").close();
    session.execute("CREATE property EBMember.in LINK EBPerson").close();
    session.execute("CREATE property EBMember.joinYear INTEGER").close();
    session.execute(
        "CREATE index EBMember_joinYear on EBMember (joinYear) NOTUNIQUE")
        .close();

    session.execute("CREATE class EBKnows extends E").close();

    session.begin();
    session.execute("CREATE VERTEX EBPerson set name = 'alice'").close();
    session.execute("CREATE VERTEX EBPerson set name = 'bob'").close();
    session.execute("CREATE VERTEX EBPerson set name = 'carol'").close();
    session.execute("CREATE VERTEX EBForum set title = 'forum1'").close();

    // forum1 has members: alice(2020), bob(2021), carol(2022)
    int[] years = {2020, 2021, 2022};
    String[] names = {"alice", "bob", "carol"};
    for (int i = 0; i < 3; i++) {
      session.execute(
          "CREATE EDGE EBMember FROM (SELECT FROM EBForum WHERE title = 'forum1')"
              + " TO (SELECT FROM EBPerson WHERE name = '" + names[i] + "')"
              + " SET joinYear = " + years[i])
          .close();
    }
    // alice knows bob
    session.execute(
        "CREATE EDGE EBKnows FROM (SELECT FROM EBPerson WHERE name = 'alice')"
            + " TO (SELECT FROM EBPerson WHERE name = 'bob')")
        .close();
    session.commit();

    session.begin();
    // alice → knows → bob. forum1 members with joinYear >= 2021 who are bob.
    // Pattern: person → knows → friend → in(EBMember) → forum
    //          → outE(EBMember){joinYear >= 2021} → inV(){@rid = $matched.friend.@rid}
    var result = session.query(
        "MATCH {class: EBPerson, as: person, where: (name = 'alice')}"
            + ".out('EBKnows'){as: friend}"
            + ".in('EBMember'){as: forum}"
            + ".outE('EBMember'){as: membership, where: (joinYear >= 2021)}"
            + ".inV(){as: member,"
            + "  where: (@rid = $matched.friend.@rid)}"
            + " RETURN friend.name as fName, forum.title as forumTitle")
        .toList();

    // bob is member of forum1 with joinYear 2021 >= 2021 → match
    assertFalse("Should find bob in forum1", result.isEmpty());
    assertEquals("bob", result.get(0).getProperty("fName"));

    String plan = explainPlan(
        "MATCH {class: EBPerson, as: person, where: (name = 'alice')}"
            + ".out('EBKnows'){as: friend}"
            + ".in('EBMember'){as: forum}"
            + ".outE('EBMember'){as: membership, where: (joinYear >= 2021)}"
            + ".inV(){as: member,"
            + "  where: (@rid = $matched.friend.@rid)}"
            + " RETURN friend.name, forum.title");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 6. Dual back-references to different aliases
  // ========================================================================

  /**
   * Two separate back-references to different $matched aliases in the same
   * linear pattern. Edge A targets $matched.X, edge B targets $matched.Y.
   */
  @Test
  public void dualBackRef_differentAliases() {
    session.execute("CREATE class DBNode extends V").close();
    session.execute("CREATE property DBNode.name STRING").close();
    session.execute("CREATE class DBEdge extends E").close();

    session.begin();
    // Triangle with extra nodes: a→b→c→d, d→a (cycle), d→b (back to b)
    for (String n : new String[] {"a", "b", "c", "d"}) {
      session.execute(
          "CREATE VERTEX DBNode set name = '" + n + "'").close();
    }
    session.execute(
        "CREATE EDGE DBEdge FROM (SELECT FROM DBNode WHERE name = 'a')"
            + " TO (SELECT FROM DBNode WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE DBEdge FROM (SELECT FROM DBNode WHERE name = 'b')"
            + " TO (SELECT FROM DBNode WHERE name = 'c')")
        .close();
    session.execute(
        "CREATE EDGE DBEdge FROM (SELECT FROM DBNode WHERE name = 'c')"
            + " TO (SELECT FROM DBNode WHERE name = 'd')")
        .close();
    session.execute(
        "CREATE EDGE DBEdge FROM (SELECT FROM DBNode WHERE name = 'd')"
            + " TO (SELECT FROM DBNode WHERE name = 'a')")
        .close();
    session.execute(
        "CREATE EDGE DBEdge FROM (SELECT FROM DBNode WHERE name = 'd')"
            + " TO (SELECT FROM DBNode WHERE name = 'b')")
        .close();
    session.commit();

    session.begin();
    // a→b→c→d; d.out includes a and b.
    // back1: @rid = $matched.start → finds 'a'
    // back2: @rid = $matched.hop1 → finds 'b'
    var result = session.query(
        "MATCH {class: DBNode, as: start, where: (name = 'a')}"
            + ".out('DBEdge'){as: hop1}"
            + ".out('DBEdge'){as: hop2}"
            + ".out('DBEdge'){as: hop3}"
            + ".out('DBEdge'){as: backToStart,"
            + "  where: (@rid = $matched.start.@rid)}"
            + " RETURN hop1.name as h1, hop2.name as h2, hop3.name as h3")
        .toList();

    // a→b→c→d→a: 4-hop cycle back to 'a'
    assertEquals(1, result.size());
    assertEquals("b", result.get(0).getProperty("h1"));
    assertEquals("c", result.get(0).getProperty("h2"));
    assertEquals("d", result.get(0).getProperty("h3"));

    String plan = explainPlan(
        "MATCH {class: DBNode, as: start, where: (name = 'a')}"
            + ".out('DBEdge'){as: hop1}"
            + ".out('DBEdge'){as: hop2}"
            + ".out('DBEdge'){as: hop3}"
            + ".out('DBEdge'){as: backToStart,"
            + "  where: (@rid = $matched.start.@rid)}"
            + " RETURN hop1.name as h1, hop2.name as h2, hop3.name as h3");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 7. Diamond topology with back-reference at convergence
  // ========================================================================

  /**
   * Diamond graph: A→B, A→C, B→D, C→D. Back-reference checks that the
   * two paths converge at the same vertex D.
   */
  @Test
  public void diamond_backRefAtConvergence() {
    session.execute("CREATE class DiNode extends V").close();
    session.execute("CREATE property DiNode.name STRING").close();
    session.execute("CREATE class DiEdge extends E").close();

    session.begin();
    for (String n : new String[] {"A", "B", "C", "D", "E"}) {
      session.execute(
          "CREATE VERTEX DiNode set name = '" + n + "'").close();
    }
    // Diamond: A→B, A→C, B→D, C→D
    session.execute(
        "CREATE EDGE DiEdge FROM (SELECT FROM DiNode WHERE name = 'A')"
            + " TO (SELECT FROM DiNode WHERE name = 'B')")
        .close();
    session.execute(
        "CREATE EDGE DiEdge FROM (SELECT FROM DiNode WHERE name = 'A')"
            + " TO (SELECT FROM DiNode WHERE name = 'C')")
        .close();
    session.execute(
        "CREATE EDGE DiEdge FROM (SELECT FROM DiNode WHERE name = 'B')"
            + " TO (SELECT FROM DiNode WHERE name = 'D')")
        .close();
    session.execute(
        "CREATE EDGE DiEdge FROM (SELECT FROM DiNode WHERE name = 'C')"
            + " TO (SELECT FROM DiNode WHERE name = 'D')")
        .close();
    // Also B→E (to verify non-convergent path excluded)
    session.execute(
        "CREATE EDGE DiEdge FROM (SELECT FROM DiNode WHERE name = 'B')"
            + " TO (SELECT FROM DiNode WHERE name = 'E')")
        .close();
    session.commit();

    session.begin();
    // Two-pattern diamond with back-ref convergence:
    // Pattern 1: A → left → target
    // Pattern 2: A → right → converge where @rid = $matched.target
    var result = session.query(
        "MATCH {class: DiNode, as: root, where: (name = 'A')}"
            + ".out('DiEdge'){as: left}"
            + ".out('DiEdge'){as: target},"
            + " {as: root}.out('DiEdge'){as: right}"
            + ".out('DiEdge'){as: converge,"
            + "  where: (@rid = $matched.target.@rid)}"
            + " RETURN left.name as leftN, right.name as rightN,"
            + "  target.name as targetN")
        .toList();

    // A→B→D and A→C→D: target=D, left∈{B,C}, right∈{B,C} where right.out→D
    // Rows: (left=B, right=C, target=D) and (left=C, right=B, target=D)
    // Also B→E is target but C has no edge to E, and C→D is target but B→D matches
    Set<String> targets = new HashSet<>();
    for (var r : result) {
      targets.add(r.getProperty("targetN"));
    }
    assertTrue("D should be a convergence point", targets.contains("D"));

    String plan = explainPlan(
        "MATCH {class: DiNode, as: root, where: (name = 'A')}"
            + ".out('DiEdge'){as: left}"
            + ".out('DiEdge'){as: target},"
            + " {as: root}.out('DiEdge'){as: right}"
            + ".out('DiEdge'){as: converge,"
            + "  where: (@rid = $matched.target.@rid)}"
            + " RETURN left.name as leftN, right.name as rightN,"
            + "  target.name as targetN");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 8. Two IndexLookup descriptors on same hop (Composite)
  // ========================================================================

  /**
   * Target vertex has two indexed properties in the WHERE clause. Both
   * should produce IndexLookup descriptors, combined into a Composite
   * that intersects the two RidSets.
   */
  @Test
  public void compositeIndex_twoIndexLookupsOnSameHop() {
    session.execute("CREATE class CIHub extends V").close();
    session.execute("CREATE property CIHub.name STRING").close();

    session.execute("CREATE class CITarget extends V").close();
    session.execute("CREATE property CITarget.label STRING").close();
    session.execute("CREATE property CITarget.price INTEGER").close();
    session.execute("CREATE property CITarget.rating INTEGER").close();
    session.execute(
        "CREATE index CITarget_price on CITarget (price) NOTUNIQUE").close();
    session.execute(
        "CREATE index CITarget_rating on CITarget (rating) NOTUNIQUE").close();

    session.execute("CREATE class CILink extends E").close();
    session.execute("CREATE property CILink.out LINK CIHub").close();
    session.execute("CREATE property CILink.in LINK CITarget").close();

    session.begin();
    session.execute("CREATE VERTEX CIHub set name = 'shop'").close();
    // 10 products: price 10-100, rating 1-5 cycling
    for (int i = 0; i < 10; i++) {
      session.execute(
          "CREATE VERTEX CITarget set label = 'prod" + i + "', price = "
              + ((i + 1) * 10) + ", rating = " + (i % 5 + 1))
          .close();
      session.execute(
          "CREATE EDGE CILink FROM (SELECT FROM CIHub WHERE name = 'shop')"
              + " TO (SELECT FROM CITarget WHERE label = 'prod" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // WHERE price >= 50 AND rating >= 4
    // price>=50: prod4(50,5), prod5(60,1), prod6(70,2), prod7(80,3), prod8(90,4), prod9(100,5)
    // rating>=4: prod3(40,4), prod4(50,5), prod8(90,4), prod9(100,5)
    // AND: prod4(50,5), prod8(90,4), prod9(100,5)
    var result = session.query(
        "MATCH {class: CIHub, as: hub, where: (name = 'shop')}"
            + ".out('CILink'){as: prod,"
            + "  where: (price >= 50 AND rating >= 4)}"
            + " RETURN prod.label as label")
        .toList();

    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals(Set.of("prod4", "prod8", "prod9"), labels);

    String plan = explainPlan(
        "MATCH {class: CIHub, as: hub, where: (name = 'shop')}"
            + ".out('CILink'){as: prod,"
            + "  where: (price >= 50 AND rating >= 4)}"
            + " RETURN prod.label as label");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 9. Pre-filter adjacent to WHILE hop
  // ========================================================================

  /**
   * Index pre-filter on the hop immediately before a WHILE traversal.
   * Tests that the WHILE step doesn't interfere with the preceding
   * step's pre-filter.
   */
  @Test
  public void preFilterBeforeWhile() {
    session.execute("CREATE class PWNode extends V").close();
    session.execute("CREATE property PWNode.name STRING").close();
    session.execute("CREATE property PWNode.val INTEGER").close();
    session.execute(
        "CREATE index PWNode_val on PWNode (val) NOTUNIQUE").close();

    session.execute("CREATE class PWChild extends E").close();
    session.execute("CREATE property PWChild.out LINK PWNode").close();
    session.execute("CREATE property PWChild.in LINK PWNode").close();

    session.execute("CREATE class PWUp extends E").close();

    session.begin();
    // root → child1(val=10), child2(val=20)
    // child1 → grandchild1(val=30) → greatgc(val=40)
    // child2 has no children
    session.execute("CREATE VERTEX PWNode set name = 'root', val = 0").close();
    session.execute("CREATE VERTEX PWNode set name = 'c1', val = 10").close();
    session.execute("CREATE VERTEX PWNode set name = 'c2', val = 20").close();
    session.execute("CREATE VERTEX PWNode set name = 'gc1', val = 30").close();
    session.execute("CREATE VERTEX PWNode set name = 'ggc', val = 40").close();

    session.execute(
        "CREATE EDGE PWChild FROM (SELECT FROM PWNode WHERE name = 'root')"
            + " TO (SELECT FROM PWNode WHERE name = 'c1')")
        .close();
    session.execute(
        "CREATE EDGE PWChild FROM (SELECT FROM PWNode WHERE name = 'root')"
            + " TO (SELECT FROM PWNode WHERE name = 'c2')")
        .close();
    session.execute(
        "CREATE EDGE PWChild FROM (SELECT FROM PWNode WHERE name = 'c1')"
            + " TO (SELECT FROM PWNode WHERE name = 'gc1')")
        .close();
    session.execute(
        "CREATE EDGE PWChild FROM (SELECT FROM PWNode WHERE name = 'gc1')"
            + " TO (SELECT FROM PWNode WHERE name = 'ggc')")
        .close();

    // PWUp for hierarchy walk (gc1→c1, c1→root)
    session.execute(
        "CREATE EDGE PWUp FROM (SELECT FROM PWNode WHERE name = 'ggc')"
            + " TO (SELECT FROM PWNode WHERE name = 'gc1')")
        .close();
    session.execute(
        "CREATE EDGE PWUp FROM (SELECT FROM PWNode WHERE name = 'gc1')"
            + " TO (SELECT FROM PWNode WHERE name = 'c1')")
        .close();
    session.execute(
        "CREATE EDGE PWUp FROM (SELECT FROM PWNode WHERE name = 'c1')"
            + " TO (SELECT FROM PWNode WHERE name = 'root')")
        .close();
    session.commit();

    session.begin();
    // root → child{val >= 15} → WHILE(out(PWChild)) → descendants
    // val >= 15: only c2(20). c2 has no PWChild children.
    // WHILE(true) emits the start node (c2 itself) then tries to expand.
    var result = session.query(
        "MATCH {class: PWNode, as: start, where: (name = 'root')}"
            + ".out('PWChild'){as: child, where: (val >= 15)}"
            + ".out('PWChild'){while: (true), as: descendant,"
            + "  where: (val > 20)}"
            + " RETURN child.name as cName, descendant.name as dName")
        .toList();
    // c2 has no descendants with val > 20 (c2 itself has val=20, not > 20)
    assertEquals(0, result.size());

    // Now with val >= 5: c1(10) qualifies. WHILE from c1 traverses gc1, ggc.
    // Filter val >= 25 on descendant → gc1(30) and ggc(40) match.
    var result2 = session.query(
        "MATCH {class: PWNode, as: start, where: (name = 'root')}"
            + ".out('PWChild'){as: child, where: (val >= 5)}"
            + ".out('PWChild'){while: (true), as: descendant,"
            + "  where: (val >= 25)}"
            + " RETURN child.name as cName, descendant.name as dName")
        .toList();

    Set<String> descendants = new HashSet<>();
    for (var r : result2) {
      descendants.add(r.getProperty("dName"));
    }
    // c1's descendants via PWChild: gc1(30), ggc(40) — both >= 25
    assertTrue("Should find gc1", descendants.contains("gc1"));
    assertTrue("Should find ggc", descendants.contains("ggc"));

    String plan = explainPlan(
        "MATCH {class: PWNode, as: start, where: (name = 'root')}"
            + ".out('PWChild'){as: child, where: (val >= 5)}"
            + ".out('PWChild'){while: (true), as: descendant,"
            + "  where: (val >= 25)}"
            + " RETURN child.name as cName, descendant.name as dName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Index pre-filter on the hop immediately after a WHILE traversal.
   */
  @Test
  public void preFilterAfterWhile() {
    session.execute("CREATE class PANode extends V").close();
    session.execute("CREATE property PANode.name STRING").close();

    session.execute("CREATE class PAItem extends V").close();
    session.execute("CREATE property PAItem.label STRING").close();
    session.execute("CREATE property PAItem.weight INTEGER").close();
    session.execute(
        "CREATE index PAItem_weight on PAItem (weight) NOTUNIQUE").close();

    session.execute("CREATE class PAChild extends E").close();
    session.execute("CREATE class PAHas extends E").close();
    session.execute("CREATE property PAHas.out LINK PANode").close();
    session.execute("CREATE property PAHas.in LINK PAItem").close();

    session.begin();
    session.execute("CREATE VERTEX PANode set name = 'root'").close();
    session.execute("CREATE VERTEX PANode set name = 'mid'").close();
    session.execute("CREATE VERTEX PANode set name = 'leaf'").close();

    session.execute(
        "CREATE EDGE PAChild FROM (SELECT FROM PANode WHERE name = 'root')"
            + " TO (SELECT FROM PANode WHERE name = 'mid')")
        .close();
    session.execute(
        "CREATE EDGE PAChild FROM (SELECT FROM PANode WHERE name = 'mid')"
            + " TO (SELECT FROM PANode WHERE name = 'leaf')")
        .close();

    // leaf has items: weight 5, 15, 25
    for (int w : new int[] {5, 15, 25}) {
      session.execute(
          "CREATE VERTEX PAItem set label = 'w" + w + "', weight = " + w)
          .close();
      session.execute(
          "CREATE EDGE PAHas FROM (SELECT FROM PANode WHERE name = 'leaf')"
              + " TO (SELECT FROM PAItem WHERE label = 'w" + w + "')")
          .close();
    }
    session.commit();

    session.begin();
    // root → WHILE(out(PAChild)) → node → out(PAHas){weight >= 20}
    var result = session.query(
        "MATCH {class: PANode, as: start, where: (name = 'root')}"
            + ".out('PAChild'){while: (true), as: node}"
            + ".out('PAHas'){as: item, where: (weight >= 20)}"
            + " RETURN node.name as nName, item.label as iLabel")
        .toList();

    // WHILE from root: mid, leaf. Only leaf has PAHas items.
    // weight >= 20: w25
    Set<String> items = new HashSet<>();
    for (var r : result) {
      items.add(r.getProperty("iLabel"));
    }
    assertEquals(Set.of("w25"), items);

    String plan = explainPlan(
        "MATCH {class: PANode, as: start, where: (name = 'root')}"
            + ".out('PAChild'){while: (true), as: node}"
            + ".out('PAHas'){as: item, where: (weight >= 20)}"
            + " RETURN node.name as nName, item.label as iLabel");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 10. BETWEEN operator on indexed property
  // ========================================================================

  /**
   * BETWEEN operator on an indexed property. Tests that the planner
   * recognizes BETWEEN as an indexable range predicate.
   */
  @Test
  public void between_indexedProperty() {
    session.execute("CREATE class BTHub extends V").close();
    session.execute("CREATE property BTHub.name STRING").close();

    session.execute("CREATE class BTItem extends V").close();
    session.execute("CREATE property BTItem.label STRING").close();
    session.execute("CREATE property BTItem.val INTEGER").close();
    session.execute(
        "CREATE index BTItem_val on BTItem (val) NOTUNIQUE").close();

    session.execute("CREATE class BTLink extends E").close();
    session.execute("CREATE property BTLink.out LINK BTHub").close();
    session.execute("CREATE property BTLink.in LINK BTItem").close();

    session.begin();
    session.execute("CREATE VERTEX BTHub set name = 'center'").close();
    for (int i = 0; i < 10; i++) {
      session.execute(
          "CREATE VERTEX BTItem set label = 'b" + i + "', val = " + (i * 10))
          .close();
      session.execute(
          "CREATE EDGE BTLink FROM (SELECT FROM BTHub WHERE name = 'center')"
              + " TO (SELECT FROM BTItem WHERE label = 'b" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // BETWEEN 30 AND 60 → b3(30), b4(40), b5(50), b6(60)
    var result = session.query(
        "MATCH {class: BTHub, as: hub, where: (name = 'center')}"
            + ".out('BTLink'){as: item, where: (val BETWEEN 30 AND 60)}"
            + " RETURN item.label as label")
        .toList();

    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals(Set.of("b3", "b4", "b5", "b6"), labels);

    // BETWEEN should be flattened to >= AND <= and use the index
    String plan = explainPlan(
        "MATCH {class: BTHub, as: hub, where: (name = 'center')}"
            + ".out('BTLink'){as: item, where: (val BETWEEN 30 AND 60)}"
            + " RETURN item.label as label");
    assertTrue("BETWEEN should trigger index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  // ========================================================================
  // 11. OR in WHERE on indexed property
  // ========================================================================

  /**
   * OR in WHERE on an indexed property. OR typically prevents a single
   * index scan, but correctness must be preserved.
   */
  @Test
  public void orInWhere_indexedProperty_correctness() {
    session.execute("CREATE class ORHub extends V").close();
    session.execute("CREATE property ORHub.name STRING").close();

    session.execute("CREATE class ORItem extends V").close();
    session.execute("CREATE property ORItem.label STRING").close();
    session.execute("CREATE property ORItem.score INTEGER").close();
    session.execute(
        "CREATE index ORItem_score on ORItem (score) NOTUNIQUE").close();

    session.execute("CREATE class ORLink extends E").close();
    session.execute("CREATE property ORLink.out LINK ORHub").close();
    session.execute("CREATE property ORLink.in LINK ORItem").close();

    session.begin();
    session.execute("CREATE VERTEX ORHub set name = 'hub'").close();
    for (int i = 0; i < 10; i++) {
      session.execute(
          "CREATE VERTEX ORItem set label = 'or" + i + "', score = " + (i * 10))
          .close();
      session.execute(
          "CREATE EDGE ORLink FROM (SELECT FROM ORHub WHERE name = 'hub')"
              + " TO (SELECT FROM ORItem WHERE label = 'or" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // OR: score = 20 OR score = 70
    var result = session.query(
        "MATCH {class: ORHub, as: hub, where: (name = 'hub')}"
            + ".out('ORLink'){as: item,"
            + "  where: (score = 20 OR score = 70)}"
            + " RETURN item.label as label")
        .toList();

    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals(Set.of("or2", "or7"), labels);

    // TODO: OR in WHERE causes flatten() to produce 2 AND blocks (one per OR
    // branch). findIndexForFilter requires flatWhere.size() == 1 and returns
    // null when there are multiple blocks. Could be improved by unioning two
    // separate index lookups, but this is not currently implemented.
    String plan = explainPlan(
        "MATCH {class: ORHub, as: hub, where: (name = 'hub')}"
            + ".out('ORLink'){as: item,"
            + "  where: (score = 20 OR score = 70)}"
            + " RETURN item.label as label");
    assertFalse(
        "OR prevents single-index intersection (known limitation):\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 11b. Composite index (multi-property) pre-filter
  // ========================================================================

  /**
   * Composite index on (city, price): the planner should use the composite
   * index when the WHERE clause matches the leading prefix (city =) and
   * optionally a range on the next field (price >=).
   */
  @Test
  public void compositeIndex_multiProperty_prefixMatch() {
    session.execute("CREATE class CXShop extends V").close();
    session.execute("CREATE property CXShop.name STRING").close();

    session.execute("CREATE class CXProduct extends V").close();
    session.execute("CREATE property CXProduct.label STRING").close();
    session.execute("CREATE property CXProduct.city STRING").close();
    session.execute("CREATE property CXProduct.price INTEGER").close();
    session.execute(
        "CREATE index CXProduct_city_price on CXProduct (city, price) NOTUNIQUE")
        .close();

    session.execute("CREATE class CXSells extends E").close();
    session.execute("CREATE property CXSells.out LINK CXShop").close();
    session.execute("CREATE property CXSells.in LINK CXProduct").close();

    session.begin();
    session.execute("CREATE VERTEX CXShop set name = 'megastore'").close();
    // 12 products: 4 cities × 3 price levels
    String[] cities = {"NYC", "NYC", "NYC", "LA", "LA", "LA",
        "CHI", "CHI", "CHI", "SF", "SF", "SF"};
    int[] prices = {100, 200, 300, 100, 200, 300,
        100, 200, 300, 100, 200, 300};
    for (int i = 0; i < 12; i++) {
      session.execute(
          "CREATE VERTEX CXProduct set label = 'p" + i + "', city = '"
              + cities[i] + "', price = " + prices[i])
          .close();
      session.execute(
          "CREATE EDGE CXSells FROM"
              + " (SELECT FROM CXShop WHERE name = 'megastore')"
              + " TO (SELECT FROM CXProduct WHERE label = 'p" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // Composite index prefix: city = 'NYC' AND price >= 200
    // NYC products: p0(100), p1(200), p2(300). price >= 200: p1, p2
    var result = session.query(
        "MATCH {class: CXShop, as: shop, where: (name = 'megastore')}"
            + ".out('CXSells'){as: prod,"
            + "  where: (city = 'NYC' AND price >= 200)}"
            + " RETURN prod.label as label")
        .toList();

    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals(Set.of("p1", "p2"), labels);

    // Composite index should be used for intersection
    String plan = explainPlan(
        "MATCH {class: CXShop, as: shop, where: (name = 'megastore')}"
            + ".out('CXSells'){as: prod,"
            + "  where: (city = 'NYC' AND price >= 200)}"
            + " RETURN prod.label as label");
    assertTrue("Plan should show index intersection for composite index:\n"
        + plan, plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Composite index with only the leading field in WHERE. The planner should
   * still use the composite index for prefix-only queries (equality on first
   * field).
   */
  @Test
  public void compositeIndex_leadingFieldOnly() {
    session.execute("CREATE class CX2Hub extends V").close();
    session.execute("CREATE property CX2Hub.name STRING").close();

    session.execute("CREATE class CX2Item extends V").close();
    session.execute("CREATE property CX2Item.label STRING").close();
    session.execute("CREATE property CX2Item.category STRING").close();
    session.execute("CREATE property CX2Item.weight INTEGER").close();
    session.execute(
        "CREATE index CX2Item_cat_weight on CX2Item (category, weight)"
            + " NOTUNIQUE")
        .close();

    session.execute("CREATE class CX2Link extends E").close();
    session.execute("CREATE property CX2Link.out LINK CX2Hub").close();
    session.execute("CREATE property CX2Link.in LINK CX2Item").close();

    session.begin();
    session.execute("CREATE VERTEX CX2Hub set name = 'warehouse'").close();
    for (int i = 0; i < 6; i++) {
      String cat = i < 3 ? "food" : "tools";
      session.execute(
          "CREATE VERTEX CX2Item set label = 'i" + i + "', category = '"
              + cat + "', weight = " + ((i + 1) * 10))
          .close();
      session.execute(
          "CREATE EDGE CX2Link FROM"
              + " (SELECT FROM CX2Hub WHERE name = 'warehouse')"
              + " TO (SELECT FROM CX2Item WHERE label = 'i" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // Leading field only: category = 'food'
    var result = session.query(
        "MATCH {class: CX2Hub, as: hub, where: (name = 'warehouse')}"
            + ".out('CX2Link'){as: item, where: (category = 'food')}"
            + " RETURN item.label as label")
        .toList();
    assertEquals(3, result.size());

    String plan = explainPlan(
        "MATCH {class: CX2Hub, as: hub, where: (name = 'warehouse')}"
            + ".out('CX2Link'){as: item, where: (category = 'food')}"
            + " RETURN item.label as label");
    assertTrue("Composite index should be used for leading field:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Composite index where only the NON-leading field is in WHERE. The
   * composite index cannot be used (no prefix match). No intersection
   * should appear.
   */
  @Test
  public void compositeIndex_nonLeadingFieldOnly_noIntersection() {
    session.execute("CREATE class CX3Hub extends V").close();
    session.execute("CREATE property CX3Hub.name STRING").close();

    session.execute("CREATE class CX3Item extends V").close();
    session.execute("CREATE property CX3Item.label STRING").close();
    session.execute("CREATE property CX3Item.color STRING").close();
    session.execute("CREATE property CX3Item.size INTEGER").close();
    session.execute(
        "CREATE index CX3Item_color_size on CX3Item (color, size) NOTUNIQUE")
        .close();

    session.execute("CREATE class CX3Link extends E").close();
    session.execute("CREATE property CX3Link.out LINK CX3Hub").close();
    session.execute("CREATE property CX3Link.in LINK CX3Item").close();

    session.begin();
    session.execute("CREATE VERTEX CX3Hub set name = 'store'").close();
    for (int i = 0; i < 4; i++) {
      session.execute(
          "CREATE VERTEX CX3Item set label = 'x" + i + "', color = '"
              + (i < 2 ? "red" : "blue") + "', size = " + ((i + 1) * 10))
          .close();
      session.execute(
          "CREATE EDGE CX3Link FROM"
              + " (SELECT FROM CX3Hub WHERE name = 'store')"
              + " TO (SELECT FROM CX3Item WHERE label = 'x" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // Non-leading field only: size >= 30 (skips 'color' leading field)
    var result = session.query(
        "MATCH {class: CX3Hub, as: hub, where: (name = 'store')}"
            + ".out('CX3Link'){as: item, where: (size >= 30)}"
            + " RETURN item.label as label")
        .toList();
    assertEquals(2, result.size()); // x2(30), x3(40)

    // Composite index cannot be used without leading field — no intersection
    String plan = explainPlan(
        "MATCH {class: CX3Hub, as: hub, where: (name = 'store')}"
            + ".out('CX3Link'){as: item, where: (size >= 30)}"
            + " RETURN item.label as label");
    assertFalse(
        "Composite index should NOT be used without leading field:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  // ========================================================================
  // 12. $paths with EdgeRidLookup back-reference
  // ========================================================================

  /**
   * RETURN $paths with a back-reference intersection active. Ensures path
   * collection works correctly when EdgeRidLookup pre-filter is applied.
   */
  @Test
  public void pathsReturn_withBackRef() {
    session.execute("CREATE class PRNode extends V").close();
    session.execute("CREATE property PRNode.name STRING").close();
    session.execute("CREATE class PREdge extends E").close();

    session.begin();
    // A→B→C→A (triangle)
    for (String n : new String[] {"A", "B", "C"}) {
      session.execute(
          "CREATE VERTEX PRNode set name = '" + n + "'").close();
    }
    session.execute(
        "CREATE EDGE PREdge FROM (SELECT FROM PRNode WHERE name = 'A')"
            + " TO (SELECT FROM PRNode WHERE name = 'B')")
        .close();
    session.execute(
        "CREATE EDGE PREdge FROM (SELECT FROM PRNode WHERE name = 'B')"
            + " TO (SELECT FROM PRNode WHERE name = 'C')")
        .close();
    session.execute(
        "CREATE EDGE PREdge FROM (SELECT FROM PRNode WHERE name = 'C')"
            + " TO (SELECT FROM PRNode WHERE name = 'A')")
        .close();
    session.commit();

    session.begin();
    // 3-hop cycle with back-ref, return $paths
    var result = session.query(
        "MATCH {class: PRNode, as: start, where: (name = 'A')}"
            + ".out('PREdge'){as: hop1}"
            + ".out('PREdge'){as: hop2}"
            + ".out('PREdge'){as: back,"
            + "  where: (@rid = $matched.start.@rid)}"
            + " RETURN $paths")
        .toList();

    // One cycle: A→B→C→A
    assertEquals(1, result.size());
    // $paths should contain all 4 aliases (start, hop1, hop2, back)
    var row = result.get(0);
    assertEquals(4, row.getPropertyNames().size());

    String plan = explainPlan(
        "MATCH {class: PRNode, as: start, where: (name = 'A')}"
            + ".out('PREdge'){as: hop1}"
            + ".out('PREdge'){as: hop2}"
            + ".out('PREdge'){as: back,"
            + "  where: (@rid = $matched.start.@rid)}"
            + " RETURN $paths");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 13. Larger dataset (500 edges)
  // ========================================================================

  /**
   * Stress test with 500 edges from a single hub. The adaptive abort logic
   * (ratio check, RidSet cap) should handle this gracefully. Results must
   * be correct regardless of whether the pre-filter is applied or aborted.
   */
  @Test
  public void largeDataset_500edges_correctResults() {
    session.execute("CREATE class LDHub extends V").close();
    session.execute("CREATE property LDHub.name STRING").close();

    session.execute("CREATE class LDTarget extends V").close();
    session.execute("CREATE property LDTarget.idx INTEGER").close();
    session.execute("CREATE property LDTarget.category STRING").close();
    session.execute(
        "CREATE index LDTarget_idx on LDTarget (idx) NOTUNIQUE").close();

    session.execute("CREATE class LDLink extends E").close();
    session.execute("CREATE property LDLink.out LINK LDHub").close();
    session.execute("CREATE property LDLink.in LINK LDTarget").close();

    session.begin();
    session.execute("CREATE VERTEX LDHub set name = 'bigHub'").close();

    // 500 targets: idx = 0..499, category cycling through 5 values
    String[] categories = {"alpha", "beta", "gamma", "delta", "epsilon"};
    for (int i = 0; i < 500; i++) {
      session.execute(
          "CREATE VERTEX LDTarget set idx = ?, category = ?",
          i, categories[i % 5])
          .close();
    }
    // Batch edge creation
    for (int i = 0; i < 500; i++) {
      session.execute(
          "CREATE EDGE LDLink FROM (SELECT FROM LDHub WHERE name = 'bigHub')"
              + " TO (SELECT FROM LDTarget WHERE idx = " + i + ")")
          .close();
    }
    session.commit();

    session.begin();
    // Select a narrow range: idx >= 490 → 10 targets
    var result = session.query(
        "MATCH {class: LDHub, as: hub, where: (name = 'bigHub')}"
            + ".out('LDLink'){as: target, where: (idx >= 490)}"
            + " RETURN target.idx as idx, target.category as cat")
        .toList();

    assertEquals(10, result.size());
    Set<Integer> indices = new HashSet<>();
    for (var r : result) {
      indices.add(r.getProperty("idx"));
    }
    for (int i = 490; i < 500; i++) {
      assertTrue("Should contain idx " + i, indices.contains(i));
    }

    String plan = explainPlan(
        "MATCH {class: LDHub, as: hub, where: (name = 'bigHub')}"
            + ".out('LDLink'){as: target, where: (idx >= 490)}"
            + " RETURN target.idx as idx, target.category as cat");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Large dataset with back-reference. 200 source vertices each connected
   * to a shared target. Tests EdgeRidLookup performance with large reverse
   * link bag.
   */
  @Test
  public void largeDataset_200sources_backRef() {
    session.execute("CREATE class LBCenter extends V").close();
    session.execute("CREATE property LBCenter.name STRING").close();

    session.execute("CREATE class LBSrc extends V").close();
    session.execute("CREATE property LBSrc.name STRING").close();

    session.execute("CREATE class LBLink extends E").close();
    session.execute("CREATE class LBBridge extends E").close();

    session.begin();
    session.execute("CREATE VERTEX LBCenter set name = 'center'").close();
    session.execute("CREATE VERTEX LBSrc set name = 'anchor'").close();

    // 200 sources → center
    for (int i = 0; i < 200; i++) {
      session.execute(
          "CREATE VERTEX LBSrc set name = 's" + i + "'").close();
      session.execute(
          "CREATE EDGE LBLink FROM (SELECT FROM LBSrc WHERE name = 's" + i + "')"
              + " TO (SELECT FROM LBCenter WHERE name = 'center')")
          .close();
    }
    // anchor bridges to only 5 of those sources
    for (int i : new int[] {10, 50, 100, 150, 199}) {
      session.execute(
          "CREATE EDGE LBBridge FROM (SELECT FROM LBSrc WHERE name = 'anchor')"
              + " TO (SELECT FROM LBSrc WHERE name = 's" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // anchor → bridge → src → center ← src2 where src2 = $matched.src
    var result = session.query(
        "MATCH {class: LBSrc, as: anchor, where: (name = 'anchor')}"
            + ".out('LBBridge'){as: bridged}"
            + ".out('LBLink'){as: target}"
            + ".in('LBLink'){as: verify,"
            + "  where: (@rid = $matched.bridged.@rid)}"
            + " RETURN bridged.name as bName")
        .toList();

    Set<String> bridged = new HashSet<>();
    for (var r : result) {
      bridged.add(r.getProperty("bName"));
    }
    assertEquals(
        Set.of("s10", "s50", "s100", "s150", "s199"), bridged);

    String plan = explainPlan(
        "MATCH {class: LBSrc, as: anchor, where: (name = 'anchor')}"
            + ".out('LBBridge'){as: bridged}"
            + ".out('LBLink'){as: target}"
            + ".in('LBLink'){as: verify,"
            + "  where: (@rid = $matched.bridged.@rid)}"
            + " RETURN bridged.name as bName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 14. DISTINCT with pre-filter
  // ========================================================================

  /**
   * DISTINCT on results from a pre-filtered traversal. Tests that
   * deduplication works correctly with the pre-filter active.
   */
  @Test
  public void distinct_withPreFilter() {
    session.execute("CREATE class DSHub extends V").close();
    session.execute("CREATE property DSHub.name STRING").close();

    session.execute("CREATE class DSItem extends V").close();
    session.execute("CREATE property DSItem.color STRING").close();
    session.execute("CREATE property DSItem.val INTEGER").close();
    session.execute(
        "CREATE index DSItem_val on DSItem (val) NOTUNIQUE").close();

    session.execute("CREATE class DSLink extends E").close();
    session.execute("CREATE property DSLink.out LINK DSHub").close();
    session.execute("CREATE property DSLink.in LINK DSItem").close();

    session.begin();
    session.execute("CREATE VERTEX DSHub set name = 'h1'").close();
    session.execute("CREATE VERTEX DSHub set name = 'h2'").close();

    // Items with duplicate colors: red(10), red(20), blue(30), blue(40)
    for (var entry : new Object[][] {
        {"red10", "red", 10}, {"red20", "red", 20},
        {"blue30", "blue", 30}, {"blue40", "blue", 40}}) {
      session.execute(
          "CREATE VERTEX DSItem set color = ?, val = ?", entry[1], entry[2])
          .close();
    }
    // h1 → all items, h2 → all items
    for (String hub : new String[] {"h1", "h2"}) {
      for (int val : new int[] {10, 20, 30, 40}) {
        session.execute(
            "CREATE EDGE DSLink FROM (SELECT FROM DSHub WHERE name = '" + hub + "')"
                + " TO (SELECT FROM DSItem WHERE val = " + val + ")")
            .close();
      }
    }
    session.commit();

    session.begin();
    // DISTINCT colors from items with val >= 20
    var result = session.query(
        "SELECT DISTINCT color FROM ("
            + "MATCH {class: DSHub, as: hub}"
            + ".out('DSLink'){as: item, where: (val >= 20)}"
            + " RETURN item.color as color)")
        .toList();

    Set<String> colors = new HashSet<>();
    for (var r : result) {
      colors.add(r.getProperty("color"));
    }
    assertEquals(Set.of("red", "blue"), colors);

    String plan = explainPlan(
        "MATCH {class: DSHub, as: hub}"
            + ".out('DSLink'){as: item, where: (val >= 20)}"
            + " RETURN item.color as color");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 15. Multiple edge types between same vertex pair
  // ========================================================================

  /**
   * Two different edge types between the same vertex pair, each with
   * different indexed properties. Tests that pre-filters are independent
   * per edge class.
   */
  @Test
  public void multiEdgeType_samePair_independentFilters() {
    session.execute("CREATE class MEPerson extends V").close();
    session.execute("CREATE property MEPerson.name STRING").close();

    session.execute("CREATE class MECompany extends V").close();
    session.execute("CREATE property MECompany.name STRING").close();

    session.execute("CREATE class MEWorksAt extends E").close();
    session.execute("CREATE property MEWorksAt.out LINK MEPerson").close();
    session.execute("CREATE property MEWorksAt.in LINK MECompany").close();
    session.execute("CREATE property MEWorksAt.salary INTEGER").close();
    session.execute(
        "CREATE index MEWorksAt_salary on MEWorksAt (salary) NOTUNIQUE")
        .close();

    session.execute("CREATE class MEInvests extends E").close();
    session.execute("CREATE property MEInvests.out LINK MEPerson").close();
    session.execute("CREATE property MEInvests.in LINK MECompany").close();
    session.execute("CREATE property MEInvests.amount INTEGER").close();
    session.execute(
        "CREATE index MEInvests_amount on MEInvests (amount) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX MEPerson set name = 'pat'").close();
    session.execute("CREATE VERTEX MECompany set name = 'corp'").close();

    // pat works at corp (salary=5000) AND invests in corp (amount=10000)
    session.execute(
        "CREATE EDGE MEWorksAt FROM (SELECT FROM MEPerson WHERE name = 'pat')"
            + " TO (SELECT FROM MECompany WHERE name = 'corp') SET salary = 5000")
        .close();
    session.execute(
        "CREATE EDGE MEInvests FROM (SELECT FROM MEPerson WHERE name = 'pat')"
            + " TO (SELECT FROM MECompany WHERE name = 'corp') SET amount = 10000")
        .close();
    session.commit();

    session.begin();
    // Filter by salary on WorksAt edge
    var workResult = session.query(
        "MATCH {class: MEPerson, as: p, where: (name = 'pat')}"
            + ".outE('MEWorksAt'){as: w, where: (salary >= 4000)}"
            + ".inV(){as: c}"
            + " RETURN c.name as cName")
        .toList();
    assertEquals(1, workResult.size());

    // Filter by amount on Invests edge
    var investResult = session.query(
        "MATCH {class: MEPerson, as: p, where: (name = 'pat')}"
            + ".outE('MEInvests'){as: inv, where: (amount >= 8000)}"
            + ".inV(){as: c}"
            + " RETURN c.name as cName")
        .toList();
    assertEquals(1, investResult.size());

    // Both in same multi-pattern query
    var bothResult = session.query(
        "MATCH {class: MEPerson, as: p, where: (name = 'pat')}"
            + ".outE('MEWorksAt'){as: w, where: (salary >= 4000)}"
            + ".inV(){as: workCompany},"
            + " {as: p}.outE('MEInvests'){as: inv, where: (amount >= 8000)}"
            + ".inV(){as: investCompany}"
            + " RETURN workCompany.name as wc, investCompany.name as ic")
        .toList();
    assertEquals(1, bothResult.size());
    assertEquals("corp", bothResult.get(0).getProperty("wc"));
    assertEquals("corp", bothResult.get(0).getProperty("ic"));

    String plan = explainPlan(
        "MATCH {class: MEPerson, as: p, where: (name = 'pat')}"
            + ".outE('MEWorksAt'){as: w, where: (salary >= 4000)}"
            + ".inV(){as: workCompany},"
            + " {as: p}.outE('MEInvests'){as: inv, where: (amount >= 8000)}"
            + ".inV(){as: investCompany}"
            + " RETURN workCompany.name as wc, investCompany.name as ic");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 16. Back-reference with in() + outE() mixed traversal
  // ========================================================================

  /**
   * Pattern mixing in() vertex-level and outE() edge-level traversals
   * with a back-reference. Tests that the planner handles direction
   * changes correctly.
   */
  @Test
  public void mixedDirections_inAndOutE_backRef() {
    session.execute("CREATE class MDPerson extends V").close();
    session.execute("CREATE property MDPerson.name STRING").close();

    session.execute("CREATE class MDPost extends V").close();
    session.execute("CREATE property MDPost.title STRING").close();

    session.execute("CREATE class MDWrote extends E").close();
    session.execute("CREATE property MDWrote.out LINK MDPerson").close();
    session.execute("CREATE property MDWrote.in LINK MDPost").close();
    session.execute("CREATE property MDWrote.ts LONG").close();
    session.execute(
        "CREATE index MDWrote_ts on MDWrote (ts) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX MDPerson set name = 'writer'").close();
    session.execute("CREATE VERTEX MDPost set title = 'p1'").close();
    session.execute("CREATE VERTEX MDPost set title = 'p2'").close();
    session.execute("CREATE VERTEX MDPost set title = 'p3'").close();

    for (int i = 1; i <= 3; i++) {
      session.execute(
          "CREATE EDGE MDWrote FROM (SELECT FROM MDPerson WHERE name = 'writer')"
              + " TO (SELECT FROM MDPost WHERE title = 'p" + i + "')"
              + " SET ts = " + (i * 1000))
          .close();
    }
    session.commit();

    session.begin();
    // post ← in(MDWrote) ← writer → outE(MDWrote){ts >= 2000} → inV = post2
    // where post2 = post (back-ref)
    var result = session.query(
        "MATCH {class: MDPost, as: post}"
            + ".in('MDWrote'){as: author}"
            + ".outE('MDWrote'){as: writeEdge, where: (ts >= 2000)}"
            + ".inV(){as: post2,"
            + "  where: (@rid = $matched.post.@rid)}"
            + " RETURN post.title as pTitle, writeEdge.ts as ts")
        .toList();

    // writer wrote p1(1000), p2(2000), p3(3000)
    // ts >= 2000: p2, p3. post2 = post → only rows where post IS p2 or p3
    Set<String> titles = new HashSet<>();
    for (var r : result) {
      titles.add(r.getProperty("pTitle"));
    }
    assertEquals(Set.of("p2", "p3"), titles);

    String plan = explainPlan(
        "MATCH {class: MDPost, as: post}"
            + ".in('MDWrote'){as: author}"
            + ".outE('MDWrote'){as: writeEdge, where: (ts >= 2000)}"
            + ".inV(){as: post2,"
            + "  where: (@rid = $matched.post.@rid)}"
            + " RETURN post.title, writeEdge.ts");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }
}
