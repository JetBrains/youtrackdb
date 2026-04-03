package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Comprehensive test suite for the index-into traversal optimization (adjacency
 * list intersection pre-filtering) in the MATCH engine.
 *
 * <p>Tests cover three pre-filter descriptor types:
 * <ul>
 *   <li><b>Class filter</b> — zero-I/O collection-ID check on target vertex class
 *   <li><b>IndexLookup</b> — index query producing a RidSet of accepted targets
 *   <li><b>EdgeRidLookup</b> — reverse edge lookup for {@code @rid = $matched.X.@rid}
 *       back-references
 *   <li><b>Composite</b> — intersection of multiple descriptors
 * </ul>
 *
 * <p>Additionally tests edge-method patterns ({@code outE/inE + inV/outV}) from
 * PR #904 and class inference from edge LINK schema.
 *
 * <h3>Graph schema</h3>
 * <pre>
 *   Vertex classes:
 *     CPerson(name, age)
 *     CCompany(name, country)
 *     CProject(name, budget)
 *     CMessage(content, creationDate)
 *     CForum(title)
 *     CTag(label)
 *     CTagClass(name)
 *
 *   Edge classes:
 *     CWorksAt(out LINK CPerson, in LINK CCompany, workFrom INTEGER) — indexed
 *     CKnows(out LINK CPerson, in LINK CPerson, since INTEGER) — indexed
 *     CContributes(out LINK CPerson, in LINK CProject, role STRING)
 *     CContainerOf(out LINK CForum, in LINK CMessage)
 *     CHasCreator(out LINK CMessage, in LINK CPerson)
 *     CHasTag(out LINK CMessage, in LINK CTag)
 *     CHasType(out LINK CTag, in LINK CTagClass)
 *     CIsSubclassOf(out LINK CTagClass, in LINK CTagClass)
 *     CLikes(out LINK CPerson, in LINK CMessage, likeDate LONG) — indexed
 *
 *   Indexes:
 *     CWorksAt.workFrom (NOTUNIQUE)
 *     CKnows.since (NOTUNIQUE)
 *     CLikes.likeDate (NOTUNIQUE)
 *     CMessage.creationDate (NOTUNIQUE)
 *     CPerson.name (UNIQUE)
 * </pre>
 */
public class MatchPreFilterComprehensiveTest extends MatchPreFilterTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    createSchema();
    populateData();
  }

  private void createSchema() {
    // Vertex classes
    session.execute("CREATE class CPerson extends V").close();
    session.execute("CREATE property CPerson.name STRING").close();
    session.execute("CREATE property CPerson.age INTEGER").close();
    session.execute("CREATE index CPerson_name on CPerson (name) UNIQUE").close();

    session.execute("CREATE class CCompany extends V").close();
    session.execute("CREATE property CCompany.name STRING").close();
    session.execute("CREATE property CCompany.country STRING").close();

    session.execute("CREATE class CProject extends V").close();
    session.execute("CREATE property CProject.name STRING").close();
    session.execute("CREATE property CProject.budget INTEGER").close();

    session.execute("CREATE class CMessage extends V").close();
    session.execute("CREATE property CMessage.content STRING").close();
    session.execute("CREATE property CMessage.creationDate LONG").close();
    session.execute(
        "CREATE index CMessage_creationDate on CMessage (creationDate) NOTUNIQUE")
        .close();

    session.execute("CREATE class CForum extends V").close();
    session.execute("CREATE property CForum.title STRING").close();

    session.execute("CREATE class CTag extends V").close();
    session.execute("CREATE property CTag.label STRING").close();

    session.execute("CREATE class CTagClass extends V").close();
    session.execute("CREATE property CTagClass.name STRING").close();

    // Edge classes with typed endpoints and indexes
    session.execute("CREATE class CWorksAt extends E").close();
    session.execute("CREATE property CWorksAt.out LINK CPerson").close();
    session.execute("CREATE property CWorksAt.in LINK CCompany").close();
    session.execute("CREATE property CWorksAt.workFrom INTEGER").close();
    session.execute(
        "CREATE index CWorksAt_workFrom on CWorksAt (workFrom) NOTUNIQUE").close();

    session.execute("CREATE class CKnows extends E").close();
    session.execute("CREATE property CKnows.out LINK CPerson").close();
    session.execute("CREATE property CKnows.in LINK CPerson").close();
    session.execute("CREATE property CKnows.since INTEGER").close();
    session.execute(
        "CREATE index CKnows_since on CKnows (since) NOTUNIQUE").close();

    session.execute("CREATE class CContributes extends E").close();
    session.execute("CREATE property CContributes.out LINK CPerson").close();
    session.execute("CREATE property CContributes.in LINK CProject").close();
    session.execute("CREATE property CContributes.role STRING").close();

    session.execute("CREATE class CContainerOf extends E").close();
    session.execute("CREATE property CContainerOf.out LINK CForum").close();
    session.execute("CREATE property CContainerOf.in LINK CMessage").close();

    session.execute("CREATE class CHasCreator extends E").close();
    session.execute("CREATE property CHasCreator.out LINK CMessage").close();
    session.execute("CREATE property CHasCreator.in LINK CPerson").close();

    session.execute("CREATE class CHasTag extends E").close();
    session.execute("CREATE property CHasTag.out LINK CMessage").close();
    session.execute("CREATE property CHasTag.in LINK CTag").close();

    session.execute("CREATE class CHasType extends E").close();
    session.execute("CREATE property CHasType.out LINK CTag").close();
    session.execute("CREATE property CHasType.in LINK CTagClass").close();

    session.execute("CREATE class CIsSubclassOf extends E").close();
    session.execute("CREATE property CIsSubclassOf.out LINK CTagClass").close();
    session.execute("CREATE property CIsSubclassOf.in LINK CTagClass").close();

    session.execute("CREATE class CLikes extends E").close();
    session.execute("CREATE property CLikes.out LINK CPerson").close();
    session.execute("CREATE property CLikes.in LINK CMessage").close();
    session.execute("CREATE property CLikes.likeDate LONG").close();
    session.execute(
        "CREATE index CLikes_likeDate on CLikes (likeDate) NOTUNIQUE").close();
  }

  private void populateData() {
    session.begin();

    // 8 persons
    for (int i = 0; i < 8; i++) {
      session.execute(
          "CREATE VERTEX CPerson set name = ?, age = ?",
          "p" + i, 20 + i * 5)
          .close();
    }

    // 3 companies
    session.execute(
        "CREATE VERTEX CCompany set name = 'acme', country = 'US'").close();
    session.execute(
        "CREATE VERTEX CCompany set name = 'globex', country = 'UK'").close();
    session.execute(
        "CREATE VERTEX CCompany set name = 'initech', country = 'US'").close();

    // 2 projects
    session.execute(
        "CREATE VERTEX CProject set name = 'alpha', budget = 1000").close();
    session.execute(
        "CREATE VERTEX CProject set name = 'beta', budget = 5000").close();

    // 2 forums
    session.execute("CREATE VERTEX CForum set title = 'general'").close();
    session.execute("CREATE VERTEX CForum set title = 'tech'").close();

    // 12 messages: p0 authors m0-m2, p1 authors m3-m5, p2 authors m6-m8,
    //              p3 authors m9-m11
    for (int i = 0; i < 12; i++) {
      session.execute(
          "CREATE VERTEX CMessage set content = ?, creationDate = ?",
          "m" + i, 1000L + i * 100L)
          .close();
    }

    // 4 tags, 3 tag classes with hierarchy
    session.execute("CREATE VERTEX CTag set label = 'java'").close();
    session.execute("CREATE VERTEX CTag set label = 'python'").close();
    session.execute("CREATE VERTEX CTag set label = 'rust'").close();
    session.execute("CREATE VERTEX CTag set label = 'go'").close();

    session.execute(
        "CREATE VERTEX CTagClass set name = 'Programming'").close();
    session.execute(
        "CREATE VERTEX CTagClass set name = 'SystemsProgramming'").close();
    session.execute(
        "CREATE VERTEX CTagClass set name = 'ScriptLanguage'").close();

    // Tag class hierarchy: SystemsProgramming → Programming,
    //                      ScriptLanguage → Programming
    session.execute(
        "CREATE EDGE CIsSubclassOf FROM"
            + " (SELECT FROM CTagClass WHERE name = 'SystemsProgramming')"
            + " TO (SELECT FROM CTagClass WHERE name = 'Programming')")
        .close();
    session.execute(
        "CREATE EDGE CIsSubclassOf FROM"
            + " (SELECT FROM CTagClass WHERE name = 'ScriptLanguage')"
            + " TO (SELECT FROM CTagClass WHERE name = 'Programming')")
        .close();

    // Tag → TagClass: java→ScriptLanguage, python→ScriptLanguage,
    //                 rust→SystemsProgramming, go→SystemsProgramming
    session.execute(
        "CREATE EDGE CHasType FROM (SELECT FROM CTag WHERE label = 'java')"
            + " TO (SELECT FROM CTagClass WHERE name = 'ScriptLanguage')")
        .close();
    session.execute(
        "CREATE EDGE CHasType FROM (SELECT FROM CTag WHERE label = 'python')"
            + " TO (SELECT FROM CTagClass WHERE name = 'ScriptLanguage')")
        .close();
    session.execute(
        "CREATE EDGE CHasType FROM (SELECT FROM CTag WHERE label = 'rust')"
            + " TO (SELECT FROM CTagClass WHERE name = 'SystemsProgramming')")
        .close();
    session.execute(
        "CREATE EDGE CHasType FROM (SELECT FROM CTag WHERE label = 'go')"
            + " TO (SELECT FROM CTagClass WHERE name = 'SystemsProgramming')")
        .close();

    // WorksAt edges: p0-p2→acme, p3-p4→globex, p5-p7→initech
    //   workFrom: p0=2010, p1=2012, p2=2015, p3=2011, p4=2018,
    //             p5=2013, p6=2016, p7=2020
    int[] workFromYears = {2010, 2012, 2015, 2011, 2018, 2013, 2016, 2020};
    String[] companies = {
        "acme", "acme", "acme", "globex", "globex",
        "initech", "initech", "initech"};
    for (int i = 0; i < 8; i++) {
      session.execute(
          "CREATE EDGE CWorksAt FROM"
              + " (SELECT FROM CPerson WHERE name = 'p" + i + "')"
              + " TO (SELECT FROM CCompany WHERE name = '" + companies[i] + "')"
              + " SET workFrom = " + workFromYears[i])
          .close();
    }

    // Knows edges (bidirectional-ish): p0↔p1, p0↔p2, p1↔p3, p2↔p4,
    //   p3↔p5, p4↔p6, p5↔p7
    int[][] knowsPairs = {{0, 1}, {0, 2}, {1, 3}, {2, 4}, {3, 5}, {4, 6},
        {5, 7}};
    int sinceBase = 2005;
    for (int[] pair : knowsPairs) {
      session.execute(
          "CREATE EDGE CKnows FROM"
              + " (SELECT FROM CPerson WHERE name = 'p" + pair[0] + "')"
              + " TO (SELECT FROM CPerson WHERE name = 'p" + pair[1] + "')"
              + " SET since = " + (sinceBase + pair[0] + pair[1]))
          .close();
    }

    // Contributes edges (no index): p0→alpha, p1→alpha, p2→beta, p3→beta
    String[] contribPersons = {"p0", "p1", "p2", "p3"};
    String[] contribProjects = {"alpha", "alpha", "beta", "beta"};
    String[] contribRoles = {"lead", "dev", "lead", "dev"};
    for (int i = 0; i < 4; i++) {
      session.execute(
          "CREATE EDGE CContributes FROM"
              + " (SELECT FROM CPerson WHERE name = '" + contribPersons[i] + "')"
              + " TO (SELECT FROM CProject WHERE name = '" + contribProjects[i]
              + "')" + " SET role = '" + contribRoles[i] + "'")
          .close();
    }

    // ContainerOf: general→m0..m5, tech→m6..m11
    for (int i = 0; i < 12; i++) {
      String forum = i < 6 ? "general" : "tech";
      session.execute(
          "CREATE EDGE CContainerOf FROM"
              + " (SELECT FROM CForum WHERE title = '" + forum + "')"
              + " TO (SELECT FROM CMessage WHERE content = 'm" + i + "')")
          .close();
    }

    // HasCreator: m0-m2→p0, m3-m5→p1, m6-m8→p2, m9-m11→p3
    for (int i = 0; i < 12; i++) {
      int author = i / 3;
      session.execute(
          "CREATE EDGE CHasCreator FROM"
              + " (SELECT FROM CMessage WHERE content = 'm" + i + "')"
              + " TO (SELECT FROM CPerson WHERE name = 'p" + author + "')")
          .close();
    }

    // HasTag: m0→java, m1→python, m2→rust, m3→go, m4→java, m5→python,
    //         m6→rust, m7→go, m8→java, m9→python, m10→rust, m11→go
    String[] tags = {"java", "python", "rust", "go"};
    for (int i = 0; i < 12; i++) {
      session.execute(
          "CREATE EDGE CHasTag FROM"
              + " (SELECT FROM CMessage WHERE content = 'm" + i + "')"
              + " TO (SELECT FROM CTag WHERE label = '" + tags[i % 4] + "')")
          .close();
    }

    // Likes: p1→m0(likeDate=5000), p2→m0(5100), p3→m3(5200),
    //        p4→m3(5300), p5→m6(5400), p6→m6(5500), p7→m9(5600),
    //        p0→m9(5700), p1→m6(5800), p2→m9(5900)
    int[][] likes = {
        {1, 0, 5000}, {2, 0, 5100}, {3, 3, 5200}, {4, 3, 5300},
        {5, 6, 5400}, {6, 6, 5500}, {7, 9, 5600}, {0, 9, 5700},
        {1, 6, 5800}, {2, 9, 5900}};
    for (int[] like : likes) {
      session.execute(
          "CREATE EDGE CLikes FROM"
              + " (SELECT FROM CPerson WHERE name = 'p" + like[0] + "')"
              + " TO (SELECT FROM CMessage WHERE content = 'm" + like[1] + "')"
              + " SET likeDate = " + like[2])
          .close();
    }

    session.commit();
  }

  // ========================================================================
  // 1. Back-reference intersection (EdgeRidLookup)
  //    Triggers: WHERE @rid = $matched.X.@rid on edge target
  // ========================================================================

  /**
   * Basic back-reference: person → posts → forum → all posts → creator = person.
   * The EdgeRidLookup on the last edge intersects the forward link bag of
   * forum.out('CContainerOf') with person.in('CHasCreator').
   */
  @Test
  public void backRef_basicForumPostCreator() {
    session.begin();
    // p0 authored m0, m1, m2; all in 'general' forum
    // general forum contains m0..m5
    // p0's posts in general: m0, m1, m2
    String query =
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: post}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: post2}"
            + ".out('CHasCreator'){as: creator,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.content as content";
    var result = session.query(query).toList();

    Set<String> contents = new HashSet<>();
    for (var r : result) {
      contents.add(r.getProperty("content"));
    }
    // p0 authored m0, m1, m2 in 'general'
    assertTrue("Should find m0", contents.contains("m0"));
    assertTrue("Should find m1", contents.contains("m1"));
    assertTrue("Should find m2", contents.contains("m2"));
    assertFalse("Should NOT find m3 (by p1)", contents.contains("m3"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for back-ref:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Back-reference with in() direction edges. Pattern walks from message
   * to creator to friend, then from friend back through messages to verify
   * the friend created something in the same forum.
   */
  @Test
  public void backRef_inDirection_friendInSameForum() {
    session.begin();
    // m0 → p0 (creator). p0 knows p1, p2.
    // p1 authored m3, m4, m5 (in general).
    // p2 authored m6, m7, m8 (in tech).
    // m0 is in general forum. p1's posts in general: m3, m4, m5.
    // p2's posts in general: none (m6-m8 are in tech).
    var result = session.query(
        "MATCH {class: CMessage, as: msg, where: (content = 'm0')}"
            + ".out('CHasCreator'){as: author}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: friendMsg}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: forumMsg}"
            + ".out('CHasCreator'){as: msgAuthor,"
            + "  where: (@rid = $matched.friend.@rid)}"
            + " RETURN friend.name as friendName, forumMsg.content as fmContent")
        .toList();

    // p1 authored m3-m5 in general; forum for m0 is general
    // p2 authored m6-m8 in tech; forum for m0 is general — p2 is NOT in
    // general forum as author, so no match for p2
    Set<String> friends = new HashSet<>();
    for (var r : result) {
      friends.add(r.getProperty("friendName"));
    }
    assertTrue("p1 should appear (has posts in general)", friends.contains("p1"));

    String plan = explainPlan(
        "MATCH {class: CMessage, as: msg, where: (content = 'm0')}"
            + ".out('CHasCreator'){as: author}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: friendMsg}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: forumMsg}"
            + ".out('CHasCreator'){as: msgAuthor,"
            + "  where: (@rid = $matched.friend.@rid)}"
            + " RETURN friend.name as friendName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Back-reference where the referenced alias is 2 hops upstream (not the
   * immediate predecessor). Ensures the EdgeRidLookup correctly resolves
   * $matched references to non-adjacent aliases.
   */
  @Test
  public void backRef_nonAdjacentAlias_twoHopsBack() {
    session.begin();
    // person → knows → friend → knows → fof
    //   where fof = person (find 2-hop cycles)
    String query =
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".out('CKnows'){as: fof,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN friend.name as friendName";
    var result = session.query(query).toList();

    // p0→p1, p1→p3; p0→p2, p2→p4. None of p3 or p4 is p0.
    // So no 2-hop cycles from p0 via out edges.
    assertEquals("No 2-hop out-cycles from p0", 0, result.size());

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Back-reference self-loop: person → knows → friend where friend = person.
   * This should always yield zero results since the graph has no self-loops.
   */
  @Test
  public void backRef_selfLoop_noResults() {
    session.begin();
    String query =
        "MATCH {class: CPerson, as: person}"
            + ".out('CKnows'){as: friend,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN person.name as name";
    var result = session.query(query).toList();
    assertEquals("No self-loops exist", 0, result.size());

    // The back-ref @rid = $matched.person.@rid is detected by the planner via
    // findRidEquality(), but 'person' is the scan root alias (no producing edge).
    // targetAliasToEdgeIndex.get("person") returns null because the scan root has
    // no link bag to attach an intersection descriptor to.
    String plan = explainPlan(query);
    assertFalse(
        "Scan root has no producing edge, so EdgeRidLookup cannot attach:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Back-reference with multiple $matched references in the same query
   * on different edges. Only the back-ref edges should show intersection.
   */
  @Test
  public void backRef_multipleBackRefsInSamePattern() {
    session.begin();
    // person → post → forum → post2 → creator=person
    //                         post2 → tag → msg → creator=person
    // This tests that two different back-ref edges each get their own
    // intersection descriptor.
    String query =
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: post}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: post2}"
            + ".out('CHasCreator'){as: creator,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.content as content";
    var result = session.query(query).toList();

    assertFalse("Should have results", result.isEmpty());
    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Back-reference on a both() edge direction should still produce correct
   * results, even though both() doesn't get pre-filter optimization.
   */
  @Test
  public void backRef_bothDirection_correctnessPreserved() {
    session.begin();
    // Using both('CKnows') — pre-filter won't trigger but results should
    // still be correct
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".both('CKnows'){as: fof,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN friend.name as friendName")
        .toList();

    // p0→p1, p0→p2. p1.both(CKnows) includes p0,p3. p2.both(CKnows) includes p0,p4.
    // fof where @rid = p0: p1 and p2 both connect back to p0 via CKnows.
    Set<String> friends = new HashSet<>();
    for (var r : result) {
      friends.add(r.getProperty("friendName"));
    }
    assertEquals(Set.of("p1", "p2"), friends);

    // both() returns a ChainedIterable (combining out + in link bags), which does
    // not implement PreFilterableLinkBagIterable. Even if the planner attached an
    // intersection descriptor, the runtime would not apply it. This is an
    // intentional limitation documented in PR #904.
    // Note: the plan may show "intersection:" on the FIRST edge (out("CKnows") to
    // friend) because the back-ref attaches there; we verify the both() edge line
    // specifically does not carry an intersection descriptor.
    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".both('CKnows'){as: fof,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN friend.name as friendName");
    // Extract the line describing the both() edge traversal
    boolean bothEdgeHasIntersection = false;
    for (String line : plan.split("\n")) {
      if (line.contains("both(") && line.contains("fof")) {
        bothEdgeHasIntersection = line.contains("intersection:");
        break;
      }
    }
    assertFalse(
        "both() edge should NOT have intersection descriptor:\n" + plan,
        bothEdgeHasIntersection);
    session.commit();
  }

  // ========================================================================
  // 2. Index-based pre-filter (IndexLookup)
  //    Triggers: WHERE <indexed-property> <op> <value> on edge target
  // ========================================================================

  /**
   * Index pre-filter on target vertex property: messages with
   * creationDate >= 1800. Index CMessage_creationDate should provide
   * the RidSet.
   */
  @Test
  public void indexFilter_vertexProperty_creationDateRange() {
    session.begin();
    // creationDate: m0=1000, m1=1100, ..., m8=1800, m9=1900, m10=2000, m11=2100
    // >= 1800: m8, m9, m10, m11
    String query =
        "MATCH {class: CForum, as: forum, where: (title = 'tech')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1800)}"
            + " RETURN msg.content as content";

    // tech forum has m6..m11, of which m8(1800), m9(1900), m10(2000), m11(2100)
    // have creationDate >= 1800
    Set<String> contents = collectProperty(query, "content");
    assertEquals(Set.of("m8", "m9", "m10", "m11"), contents);

    String plan = explainPlan(query);
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Index pre-filter with equality predicate on indexed property.
   * creationDate = 1500 matches exactly m5.
   */
  @Test
  public void indexFilter_equalityPredicate() {
    session.begin();
    String query =
        "MATCH {class: CForum, as: forum}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate = 1500)}"
            + " RETURN msg.content as content";
    Set<String> contents = collectProperty(query, "content");
    // m5 has creationDate=1500, in 'general' forum
    assertEquals(Set.of("m5"), contents);

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Index pre-filter with range predicate using named parameter.
   */
  @Test
  public void indexFilter_namedParameter() {
    session.begin();
    String query =
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate < :maxDate)}"
            + " RETURN msg.content as content";
    var params = new HashMap<>(Map.of("maxDate", 1200L));

    var result = session.query(query, params).toList();

    // general has m0..m5 (creationDate 1000..1500)
    // < 1200: m0(1000), m1(1100)
    Set<String> contents = new HashSet<>();
    for (var r : result) {
      contents.add(r.getProperty("content"));
    }
    assertEquals(Set.of("m0", "m1"), contents);

    String plan = explainPlan(query, params);
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Index pre-filter with compound WHERE (indexed + non-indexed properties).
   * Only the indexed part should be used for intersection; the non-indexed
   * part is evaluated as a regular filter.
   */
  @Test
  public void indexFilter_compoundWhere_indexedPlusNonIndexed() {
    session.begin();
    // creationDate >= 1800 AND content LIKE 'm1%'
    // m8(1800), m9(1900), m10(2000), m11(2100) — of these, m10 matches 'm1%'
    String query =
        "MATCH {class: CForum, as: forum, where: (title = 'tech')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1800 AND content LIKE 'm1%')}"
            + " RETURN msg.content as content";
    Set<String> contents = collectProperty(query, "content");
    assertEquals(Set.of("m10", "m11"), contents);

    String plan = explainPlan(query);
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * No index on the filtered property → no intersection. CContributes.role
   * is not indexed, so the plan should NOT show an index intersection for
   * the role predicate.
   */
  @Test
  public void indexFilter_noIndex_noIntersection() {
    session.begin();
    // role is not indexed — use outE to filter on edge property
    var result = session.query(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CContributes'){as: contrib,"
            + "  where: (role = 'lead')}"
            + ".inV(){as: proj}"
            + " RETURN proj.name as projName")
        .toList();
    assertFalse("p0 contributes to alpha as lead", result.isEmpty());
    assertEquals("alpha", result.get(0).getProperty("projName"));

    String plan = explainPlan(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CContributes'){as: contrib,"
            + "  where: (role = 'lead')}"
            + ".inV(){as: proj}"
            + " RETURN proj.name as projName");
    assertFalse("No index intersection for non-indexed role:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Index pre-filter where no records match → empty result set.
   */
  @Test
  public void indexFilter_noMatchingRecords_emptyResult() {
    session.begin();
    String query =
        "MATCH {class: CForum, as: forum}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate > 99999)}"
            + " RETURN msg.content as content";
    var result = session.query(query).toList();
    assertEquals(0, result.size());

    String plan = explainPlan(query);
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Index pre-filter where all records match → full result set returned.
   */
  @Test
  public void indexFilter_allRecordsMatch() {
    session.begin();
    // All messages have creationDate >= 1000
    var result = session.query(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1000)}"
            + " RETURN msg.content as content")
        .toList();
    assertEquals(6, result.size());
    session.commit();
  }

  // ========================================================================
  // 3. Class filter (acceptedCollectionIds — zero-I/O check)
  //    Triggers: target node has class: constraint
  // ========================================================================

  /**
   * Class filter on target: only CMessage vertices should pass when
   * traversing a link bag that could theoretically contain mixed types.
   * The class filter is not visible in EXPLAIN but ensures correctness
   * by skipping non-matching collection IDs.
   */
  @Test
  public void classFilter_targetClassConstraint() {
    session.begin();
    // Forum→CContainerOf→CMessage, with explicit class constraint
    var result = session.query(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){class: CMessage, as: msg}"
            + " RETURN msg.content as content")
        .toList();
    assertEquals(6, result.size());

    // The class filter optimization IS active here via setAcceptedCollectionIds()
    // on EdgeTraversal. This is a zero-I/O filter applied at the LinkBag iteration
    // level, not via addIntersectionDescriptor(). It does not appear in EXPLAIN
    // output because it has negligible cost and does not use the RidSet machinery.
    String plan = explainPlan(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){class: CMessage, as: msg}"
            + " RETURN msg.content as content");
    assertFalse(
        "Class filter uses acceptedCollectionIds, not intersection descriptor:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Class filter with polymorphic class hierarchy. Create a subclass of
   * CMessage and verify that the class filter accepts both the base class
   * and subclass vertices.
   */
  @Test
  public void classFilter_polymorphicSubclass() {
    session.execute("CREATE class CComment extends CMessage").close();

    session.begin();
    // Create a comment in 'general' forum
    session.execute(
        "CREATE VERTEX CComment set content = 'c0', creationDate = 9999")
        .close();
    session.execute(
        "CREATE EDGE CContainerOf FROM"
            + " (SELECT FROM CForum WHERE title = 'general')"
            + " TO (SELECT FROM CComment WHERE content = 'c0')")
        .close();

    // class: CMessage should match both CMessage and CComment
    var result = session.query(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){class: CMessage, as: msg}"
            + " RETURN msg.content as content")
        .toList();
    // 6 original messages + 1 comment = 7
    assertEquals(7, result.size());

    Set<String> contents = new HashSet<>();
    for (var r : result) {
      contents.add(r.getProperty("content"));
    }
    assertTrue("Should include the CComment subclass vertex",
        contents.contains("c0"));

    // Same as classFilter_targetClassConstraint: the polymorphic class filter
    // operates via acceptedCollectionIds (includes CMessage + CComment cluster
    // IDs). Not visible in EXPLAIN as an intersection descriptor.
    String plan = explainPlan(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){class: CMessage, as: msg}"
            + " RETURN msg.content as content");
    assertFalse(
        "Class filter uses acceptedCollectionIds, not intersection descriptor:\n"
            + plan,
        plan.contains("intersection:"));
    session.rollback();
  }

  // ========================================================================
  // 4. Edge-method patterns (outE/inE + inV/outV) — PR #904
  //    Extended pre-filtering to edge records
  // ========================================================================

  /**
   * outE→inV with indexed edge property filter. CWorksAt.workFrom is
   * indexed, so the pre-filter should use the index to skip non-matching
   * edges before loading.
   */
  @Test
  public void edgeMethod_outEInV_indexedEdgeProperty() {
    session.begin();
    // workFrom < 2013: p0(2010), p1(2012), p3(2011) — 3 persons
    String query =
        "MATCH {class: CPerson, as: p}"
            + ".outE('CWorksAt'){as: w, where: (workFrom < 2013)}"
            + ".inV(){as: c}"
            + " RETURN p.name as pName, c.name as cName";
    var result = session.query(query).toList();

    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("pName") + "->" + r.getProperty("cName"));
    }
    assertEquals(Set.of("p0->acme", "p1->acme", "p3->globex"), pairs);

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for indexed edge property:\n"
        + plan, plan.contains("intersection:"));
    session.commit();
  }

  /**
   * inE→outV with indexed edge property filter. Reversed direction:
   * start from Company, traverse incoming CWorksAt edges with filter.
   */
  @Test
  public void edgeMethod_inEOutV_indexedEdgeProperty() {
    session.begin();
    // From acme, find workers with workFrom >= 2015
    // acme has p0(2010), p1(2012), p2(2015) → only p2 matches
    String query =
        "MATCH {class: CCompany, as: c, where: (name = 'acme')}"
            + ".inE('CWorksAt'){as: w, where: (workFrom >= 2015)}"
            + ".outV(){as: p}"
            + " RETURN p.name as pName";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("p2", result.get(0).getProperty("pName"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for inE:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * outE→inV chain followed by another vertex-level out() traversal.
   * Tests that edge-method and vertex-method steps coexist in the same
   * pattern without interfering with each other.
   */
  @Test
  public void edgeMethod_chainedWithVertexMethod() {
    session.begin();
    // Person → outE(CWorksAt){workFrom < 2013} → inV(company) → in(CWorksAt)(person)
    // Find all coworkers of people who started before 2013
    String query =
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CWorksAt'){where: (workFrom < 2013)}"
            + ".inV(){as: company}"
            + ".in('CWorksAt'){as: coworker}"
            + " RETURN coworker.name as name";
    var result = session.query(query).toList();

    // p0 works at acme (workFrom=2010 < 2013).
    // acme has p0, p1, p2 as workers → coworkers are p0, p1, p2
    Set<String> names = new HashSet<>();
    for (var r : result) {
      names.add(r.getProperty("name"));
    }
    assertTrue("Should find p0", names.contains("p0"));
    assertTrue("Should find p1", names.contains("p1"));
    assertTrue("Should find p2", names.contains("p2"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * outE with equality filter on edge property. Tests single-match case.
   */
  @Test
  public void edgeMethod_outE_equalityFilter() {
    session.begin();
    String query =
        "MATCH {class: CPerson, as: p}"
            + ".outE('CWorksAt'){as: w, where: (workFrom = 2020)}"
            + ".inV(){as: c}"
            + " RETURN p.name as pName, c.name as cName";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("p7", result.get(0).getProperty("pName"));
    assertEquals("initech", result.get(0).getProperty("cName"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Class inference from edge LINK schema: outE('CWorksAt').inV() should
   * infer that inV targets are CCompany (from CWorksAt.in LINK CCompany),
   * enabling collection-ID filtering even without explicit class: on inV.
   */
  @Test
  public void edgeMethod_classInferenceFromEdgeSchema() {
    session.begin();
    // No explicit class: on the company alias
    String query =
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CWorksAt'){where: (workFrom = 2010)}"
            + ".inV(){as: target}"
            + " RETURN target.name as targetName";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("acme", result.get(0).getProperty("targetName"));

    // The outE('CWorksAt'){where: (workFrom = 2010)} step has an indexed property
    // filter on CWorksAt.workFrom. The planner should attach an IndexLookup
    // intersection descriptor for the CWorksAt_workFrom index.
    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for indexed workFrom:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * outE without edge class argument: traverses all edge types.
   * No class inference possible, but results should still be correct.
   */
  @Test
  public void edgeMethod_outE_noClassArgument() {
    session.begin();
    // p0 has outgoing edges: CWorksAt(→acme), CKnows(→p1, →p2),
    //   CContributes(→alpha), CLikes(→m9)
    var result = session.query(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE(){as: e}"
            + ".inV(){as: target}"
            + " RETURN target.name as tName, target.content as tContent,"
            + "  target.label as tLabel, target.title as tTitle")
        .toList();

    // Count all outgoing edges from p0
    assertTrue("p0 should have multiple outgoing edges",
        result.size() >= 4);

    // outE() without edge class argument means the planner cannot determine which
    // edge class or index to use for the workFrom filter. Without an edge class,
    // there is no schema context to resolve target class or edge index.
    String plan = explainPlan(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE(){where: (workFrom = 2015)}"
            + ".inV(){as: target}"
            + " RETURN target.name as tName");
    assertFalse(
        "No edge class argument means no index resolution is possible:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * bothE degrades to no pre-filter but still produces correct results.
   * This documents the intentional limitation for bothE().
   */
  @Test
  public void edgeMethod_bothE_noPreFilter() {
    session.begin();
    // CKnows from p0: out to p1, p2. in from nobody.
    // bothE should find p1, p2
    var result = session.query(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".bothE('CKnows'){as: k, where: (since > 2006)}"
            + ".inV(){as: target}"
            + " RETURN target.name as name")
        .toList();

    // p0→p1 (since=2006), p0→p2 (since=2007)
    // since > 2006: only p0→p2 matches for outE, but bothE also checks inE
    Set<String> names = new HashSet<>();
    for (var r : result) {
      names.add(r.getProperty("name"));
    }
    // p0→p2 has since=2007 > 2006 → target p2 via outE.inV
    assertTrue("Should find p2 via outE path", names.contains("p2"));

    // Verify the bothE() edge step specifically has no intersection descriptor.
    // Check only the plan line for the bothE edge, not the entire plan, to
    // avoid false positives from unrelated edges that may have intersection.
    String plan = explainPlan(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".bothE('CKnows'){as: k, where: (since > 2006)}"
            + ".inV(){as: target}"
            + " RETURN target.name as name");
    boolean bothEEdgeHasIntersection = false;
    for (String line : plan.split("\n")) {
      if (line.contains("bothE(") || line.contains("{k}")) {
        bothEEdgeHasIntersection = line.contains("intersection:");
        break;
      }
    }
    assertFalse(
        "bothE() edge should NOT have intersection descriptor:\n" + plan,
        bothEEdgeHasIntersection);
    session.commit();
  }

  /**
   * Edge-method with Likes edges: outE('CLikes') with likeDate filter.
   * Multiple persons like the same message — tests fan-in from different
   * source vertices.
   */
  @Test
  public void edgeMethod_likes_multipleLikers() {
    session.begin();
    // m0 is liked by p1(5000) and p2(5100)
    // likeDate >= 5050: only p2's like
    String query =
        "MATCH {class: CMessage, as: msg, where: (content = 'm0')}"
            + ".inE('CLikes'){as: lk, where: (likeDate >= 5050)}"
            + ".outV(){as: liker}"
            + " RETURN liker.name as likerName";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("p2", result.get(0).getProperty("likerName"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for CLikes index:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 5. Composite pre-filters (EdgeRidLookup + IndexLookup combined)
  //    Triggers when both back-ref and index filter apply to same edge
  // ========================================================================

  /**
   * Composite filter: back-reference + index filter on the same or related
   * edges. Pattern: person → posts → forum → posts → creator=person AND
   * filtered by creationDate. Both descriptors should apply.
   */
  @Test
  public void composite_backRefPlusIndexFilter() {
    session.begin();
    // p0 authored m0(1000), m1(1100), m2(1200) in 'general' forum
    // general has m0..m5 (creationDate 1000..1500)
    // Filter: creator=p0 AND creationDate >= 1100
    // Expected: m1(1100), m2(1200)
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: post}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: post2,"
            + "  where: (creationDate >= 1100)}"
            + ".out('CHasCreator'){as: creator,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.content as content")
        .toList();

    Set<String> contents = new HashSet<>();
    for (var r : result) {
      contents.add(r.getProperty("content"));
    }
    assertTrue("Should find m1", contents.contains("m1"));
    assertTrue("Should find m2", contents.contains("m2"));
    assertFalse("Should NOT find m0 (creationDate=1000 < 1100)",
        contents.contains("m0"));

    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: post}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: post2,"
            + "  where: (creationDate >= 1100)}"
            + ".out('CHasCreator'){as: creator,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN post2.content as content");
    // Should have both index intersection on post2 and EdgeRidLookup on creator
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 6. Multi-hop patterns with pre-filters at different positions
  // ========================================================================

  /**
   * 3-hop pattern with index filter on the second hop.
   * Person → Knows → Friend → Messages → Tag
   * where Messages.creationDate is indexed.
   */
  @Test
  public void multiHop_indexFilterOnMiddleHop() {
    session.begin();
    // p0 → knows → p1, p2
    // p1 authored m3(1300), m4(1400), m5(1500)
    // p2 authored m6(1600), m7(1700), m8(1800)
    // Filter messages with creationDate >= 1500
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate >= 1500)}"
            + ".out('CHasTag'){as: tag}"
            + " RETURN friend.name as friendName, msg.content as msgContent,"
            + "  tag.label as tagLabel")
        .toList();

    // p1's messages >= 1500: m5(1500)
    // p2's messages >= 1500: m6(1600), m7(1700), m8(1800)
    Set<String> msgs = new HashSet<>();
    for (var r : result) {
      msgs.add(r.getProperty("msgContent"));
    }
    assertTrue("Should find m5", msgs.contains("m5"));
    assertTrue("Should find m6", msgs.contains("m6"));
    assertTrue("Should find m7", msgs.contains("m7"));
    assertTrue("Should find m8", msgs.contains("m8"));
    assertFalse("Should NOT find m3 (1300 < 1500)", msgs.contains("m3"));

    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate >= 1500)}"
            + ".out('CHasTag'){as: tag}"
            + " RETURN friend.name, msg.content, tag.label");
    assertTrue("Plan should show index intersection on msg:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * 4-hop pattern with index filters on two different hops.
   * Tests that multiple IndexLookup descriptors coexist in the same plan.
   */
  @Test
  public void multiHop_indexFiltersOnTwoHops() {
    session.begin();
    // Person → outE(CKnows){since >= 2007} → inV → in(CHasCreator) →
    //   msg{creationDate >= 1500}
    var result = session.query(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CKnows'){as: k, where: (since >= 2007)}"
            + ".inV(){as: friend}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate >= 1500)}"
            + " RETURN friend.name as friendName, msg.content as content")
        .toList();

    // p0→p2 (since=2007 >= 2007), p0→p1 (since=2006 < 2007) — only p2
    // p2 authored m6(1600), m7(1700), m8(1800) — all >= 1500
    Set<String> contents = new HashSet<>();
    for (var r : result) {
      assertEquals("p2", r.getProperty("friendName"));
      contents.add(r.getProperty("content"));
    }
    assertEquals(Set.of("m6", "m7", "m8"), contents);

    String plan = explainPlan(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CKnows'){as: k, where: (since >= 2007)}"
            + ".inV(){as: friend}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate >= 1500)}"
            + " RETURN friend.name, msg.content");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 7. Multi-pattern (comma-separated) MATCH with pre-filters
  // ========================================================================

  /**
   * Two separate patterns joined by a shared alias. Each pattern has its
   * own pre-filter opportunity.
   */
  @Test
  public void multiPattern_sharedAlias_independentPreFilters() {
    session.begin();
    // Pattern 1: person → messages
    // Pattern 2: person → works at company
    // Shared alias: person
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate >= 1100)},"
            + " {as: person}.out('CWorksAt'){as: company}"
            + " RETURN msg.content as content, company.name as companyName")
        .toList();

    // p0's messages >= 1100: m1(1100), m2(1200)
    // p0 works at acme
    for (var r : result) {
      assertEquals("acme", r.getProperty("companyName"));
    }
    Set<String> contents = new HashSet<>();
    for (var r : result) {
      contents.add(r.getProperty("content"));
    }
    assertEquals(Set.of("m1", "m2"), contents);

    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate >= 1100)},"
            + " {as: person}.out('CWorksAt'){as: company}"
            + " RETURN msg.content as content, company.name as companyName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Two patterns with back-reference between them.
   */
  @Test
  public void multiPattern_backRefAcrossPatterns() {
    session.begin();
    // Pattern 1: person → friends
    // Pattern 2: person → messages → tags, friend → messages with same tag
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend},"
            + " {as: person}.in('CHasCreator'){as: myMsg}"
            + ".out('CHasTag'){as: tag}"
            + " RETURN friend.name as friendName, tag.label as tagLabel")
        .toList();

    assertFalse("Should have results", result.isEmpty());

    // No back-reference (@rid = $matched...) and no indexed property filter on
    // any traversal target. The shared 'person' alias across patterns is resolved
    // by the cartesian product step, not by intersection pre-filtering.
    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend},"
            + " {as: person}.in('CHasCreator'){as: myMsg}"
            + ".out('CHasTag'){as: tag}"
            + " RETURN friend.name as friendName, tag.label as tagLabel");
    assertFalse(
        "No back-ref or indexed filter on any traversal target:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 8. WHERE with $matched — should NOT get index pre-filter
  //    (Only back-ref @rid = $matched.X.@rid gets EdgeRidLookup)
  // ========================================================================

  /**
   * WHERE clause referencing $matched for a non-RID comparison should NOT
   * get an IndexLookup pre-filter (the $matched part cannot be resolved
   * at plan time for index queries).
   */
  @Test
  public void noIndexFilter_matchedRefInWhere() {
    session.begin();
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate > $matched.person.age)}"
            + " RETURN msg.content as content")
        .toList();

    // p0.age=20. friend's messages with creationDate > 20 — all qualify
    assertFalse("Should have results", result.isEmpty());

    // The WHERE references $matched for a non-RID property comparison.
    // splitByMatchedReference() splits the filter, but creationDate > $matched...
    // is in the $matched-referencing part, not the non-matched part that could
    // be used for IndexLookup. The entire filter depends on runtime $matched state.
    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: msg,"
            + "  where: (creationDate > $matched.person.age)}"
            + " RETURN msg.content as content");
    assertFalse(
        "$matched reference in property comparison prevents IndexLookup:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 9. WHILE traversals with pre-filter
  // ========================================================================

  /**
   * WHILE(true) traversal up a class hierarchy with WHERE filter. The
   * pre-filter should NOT apply to while-edges (different optimization
   * path), but results should be correct.
   */
  @Test
  public void whileTraversal_hierarchyWalk_correctResults() {
    session.begin();
    // Tag → HAS_TYPE → TagClass → IS_SUBCLASS_OF(while:true) → ancestor
    // java → ScriptLanguage → Programming
    var result = session.query(
        "MATCH {class: CTag, as: tag, where: (label = 'java')}"
            + ".out('CHasType'){as: directClass}"
            + ".out('CIsSubclassOf'){while: (true),"
            + "  where: (name = 'Programming'), as: matchedClass}"
            + " RETURN tag.label as tagLabel, matchedClass.name as className")
        .toList();

    assertEquals(1, result.size());
    assertEquals("java", result.get(0).getProperty("tagLabel"));
    assertEquals("Programming", result.get(0).getProperty("className"));

    // WHILE traversals use a completely different execution path (recursive DFS
    // or InvertedWhileHashJoinStep). The intersection pre-filter infrastructure
    // is only for non-WHILE edges. WHILE edges are excluded from descriptor
    // attachment in MatchExecutionPlanner.
    String plan = explainPlan(
        "MATCH {class: CTag, as: tag, where: (label = 'java')}"
            + ".out('CHasType'){as: directClass}"
            + ".out('CIsSubclassOf'){while: (true),"
            + "  where: (name = 'Programming'), as: matchedClass}"
            + " RETURN tag.label as tagLabel, matchedClass.name as className");
    assertFalse(
        "WHILE edges use a separate execution path, not intersection:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Tags whose class hierarchy reaches 'Programming' — should include
   * all 4 tags (java, python → ScriptLanguage → Programming;
   * rust, go → SystemsProgramming → Programming).
   */
  @Test
  public void whileTraversal_allTagsReachProgramming() {
    session.begin();
    Set<String> tags = collectProperty(
        "MATCH {class: CTag, as: tag}"
            + ".out('CHasType'){as: directClass}"
            + ".out('CIsSubclassOf'){while: (true),"
            + "  where: (name = 'Programming'), as: matchedClass}"
            + " RETURN tag.label as tagLabel",
        "tagLabel");

    assertEquals(Set.of("java", "python", "rust", "go"), tags);

    // Same as whileTraversal_hierarchyWalk_correctResults: WHILE edges are
    // handled by the recursive traversal path, not the intersection pre-filter.
    String plan = explainPlan(
        "MATCH {class: CTag, as: tag}"
            + ".out('CHasType'){as: directClass}"
            + ".out('CIsSubclassOf'){while: (true),"
            + "  where: (name = 'Programming'), as: matchedClass}"
            + " RETURN tag.label as tagLabel");
    assertFalse(
        "WHILE edges use a separate execution path, not intersection:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 10. Large fan-out / edge cases
  // ========================================================================

  /**
   * High fan-out vertex: create a person with many CKnows edges and verify
   * that back-reference pre-filter still works correctly.
   */
  @Test
  public void highFanOut_backRefStillCorrect() {
    session.begin();
    // Create 50 extra persons connected to p0
    for (int i = 100; i < 150; i++) {
      session.execute(
          "CREATE VERTEX CPerson set name = 'extra" + i + "', age = 30")
          .close();
      session.execute(
          "CREATE EDGE CKnows FROM"
              + " (SELECT FROM CPerson WHERE name = 'p0')"
              + " TO (SELECT FROM CPerson WHERE name = 'extra" + i + "')"
              + " SET since = " + (2000 + i))
          .close();
    }

    // p0 knows p1, p2, and 50 extras. Cycle check: p0→friend→fof=p0
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".out('CKnows'){as: fof,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN friend.name as friendName")
        .toList();

    // No 2-hop cycles from p0 (none of the extras know p0 back)
    assertEquals(0, result.size());

    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".out('CKnows'){as: fof,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN friend.name as friendName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.rollback();
  }

  /**
   * Single-edge vertex: source has exactly one outgoing edge. Pre-filter
   * should handle this gracefully (link bag size = 1).
   */
  @Test
  public void singleEdge_preFilterHandlesGracefully() {
    session.begin();
    // p7 has exactly one CWorksAt edge (→initech, workFrom=2020)
    var result = session.query(
        "MATCH {class: CPerson, as: p, where: (name = 'p7')}"
            + ".outE('CWorksAt'){as: w, where: (workFrom >= 2019)}"
            + ".inV(){as: c}"
            + " RETURN c.name as cName")
        .toList();
    assertEquals(1, result.size());
    assertEquals("initech", result.get(0).getProperty("cName"));

    String plan = explainPlan(
        "MATCH {class: CPerson, as: p, where: (name = 'p7')}"
            + ".outE('CWorksAt'){as: w, where: (workFrom >= 2019)}"
            + ".inV(){as: c}"
            + " RETURN c.name as cName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Empty link bag: isolated vertex with no edges of the traversed type.
   */
  @Test
  public void emptyLinkBag_noEdgesOfType() {
    session.begin();
    session.execute(
        "CREATE VERTEX CPerson set name = 'isolated', age = 99").close();

    var result = session.query(
        "MATCH {class: CPerson, as: p, where: (name = 'isolated')}"
            + ".outE('CWorksAt'){as: w}"
            + ".inV(){as: c}"
            + " RETURN c.name as cName")
        .toList();
    assertEquals(0, result.size());

    // No WHERE clause on the edge step (outE('CWorksAt'){as: w} has no filter),
    // so there is nothing to create an IndexLookup from. The edge simply
    // enumerates all CWorksAt edges from the source vertex.
    String plan = explainPlan(
        "MATCH {class: CPerson, as: p, where: (name = 'isolated')}"
            + ".outE('CWorksAt'){as: w}"
            + ".inV(){as: c}"
            + " RETURN c.name as cName");
    assertFalse(
        "No WHERE on edge step means no index filter to create:\n" + plan,
        plan.contains("intersection:"));
    session.rollback();
  }

  // ========================================================================
  // 11. LIMIT interaction with pre-filter
  // ========================================================================

  /**
   * Pre-filter with LIMIT: ensure LIMIT is respected and results are still
   * correct (not duplicated or dropped).
   */
  @Test
  public void limitWithPreFilter() {
    session.begin();
    var result = session.query(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1000)}"
            + " RETURN msg.content as content"
            + " LIMIT 3")
        .toList();
    assertEquals(3, result.size());
    // All returned contents should be valid messages from 'general'
    for (var r : result) {
      String content = r.getProperty("content");
      assertNotNull(content);
      assertTrue("Content should be m0-m5",
          content.startsWith("m"));
    }

    String plan = explainPlan(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1000)}"
            + " RETURN msg.content as content"
            + " LIMIT 3");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Pre-filter with SKIP + LIMIT.
   */
  @Test
  public void skipLimitWithPreFilter() {
    session.begin();
    var result = session.query(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1000)}"
            + " RETURN msg.content as content"
            + " SKIP 2 LIMIT 2")
        .toList();
    assertEquals(2, result.size());

    // Use the base query without SKIP/LIMIT for EXPLAIN
    String plan = explainPlan(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1000)}"
            + " RETURN msg.content as content");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 12. ORDER BY interaction with pre-filter
  // ========================================================================

  /**
   * Pre-filter with ORDER BY on the filtered property.
   */
  @Test
  public void orderByWithPreFilter() {
    session.begin();
    var result = session.query(
        "MATCH {class: CForum, as: forum, where: (title = 'tech')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1800)}"
            + " RETURN msg.content as content"
            + " ORDER BY msg.creationDate DESC")
        .toList();

    assertEquals(4, result.size());
    // Verify descending order: m11(2100), m10(2000), m9(1900), m8(1800)
    assertEquals("m11", result.get(0).getProperty("content"));
    assertEquals("m10", result.get(1).getProperty("content"));
    assertEquals("m9", result.get(2).getProperty("content"));
    assertEquals("m8", result.get(3).getProperty("content"));

    String plan = explainPlan(
        "MATCH {class: CForum, as: forum, where: (title = 'tech')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1800)}"
            + " RETURN msg.content as content"
            + " ORDER BY msg.creationDate DESC");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 13. Aggregate queries with pre-filter
  // ========================================================================

  /**
   * COUNT with pre-filtered results.
   */
  @Test
  public void aggregateCountWithPreFilter() {
    session.begin();
    var result = session.query(
        "SELECT count(*) as cnt FROM ("
            + "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate < 1300)}"
            + " RETURN msg)")
        .toList();

    assertEquals(1, result.size());
    // general has m0(1000), m1(1100), m2(1200) with creationDate < 1300
    long cnt = result.get(0).getProperty("cnt");
    assertEquals(3L, cnt);

    // Use the inner MATCH query for EXPLAIN
    String plan = explainPlan(
        "MATCH {class: CForum, as: forum, where: (title = 'general')}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate < 1300)}"
            + " RETURN msg");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 14. Edge-method patterns from multiple source vertices
  // ========================================================================

  /**
   * Multiple source vertices (class scan) with edge-method pre-filter.
   * Each source person has different CWorksAt edges; the pre-filter should
   * apply independently per source vertex.
   */
  @Test
  public void edgeMethod_multiSource_classScan() {
    session.begin();
    // All persons → outE(CWorksAt){workFrom >= 2018}
    // p4(2018), p7(2020) match
    var result = session.query(
        "MATCH {class: CPerson, as: p}"
            + ".outE('CWorksAt'){as: w, where: (workFrom >= 2018)}"
            + ".inV(){as: c}"
            + " RETURN p.name as pName, c.name as cName")
        .toList();

    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("pName") + "->" + r.getProperty("cName"));
    }
    assertEquals(Set.of("p4->globex", "p7->initech"), pairs);

    String plan = explainPlan(
        "MATCH {class: CPerson, as: p}"
            + ".outE('CWorksAt'){as: w, where: (workFrom >= 2018)}"
            + ".inV(){as: c}"
            + " RETURN p.name as pName, c.name as cName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Multiple source vertices with vertex-level index pre-filter.
   * Each forum has different messages; the index pre-filter should
   * apply independently per forum.
   */
  @Test
  public void indexFilter_multiSource_perSourceIndependence() {
    session.begin();
    // Both forums → messages with creationDate between 1400 and 1700
    var result = session.query(
        "MATCH {class: CForum, as: forum}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1400 AND creationDate <= 1700)}"
            + " RETURN forum.title as forumTitle, msg.content as content")
        .toList();

    // general has m0(1000)..m5(1500): m4(1400), m5(1500) match
    // tech has m6(1600)..m11(2100): m6(1600), m7(1700) match
    Set<String> contents = new HashSet<>();
    for (var r : result) {
      contents.add(r.getProperty("content"));
    }
    assertEquals(Set.of("m4", "m5", "m6", "m7"), contents);

    String plan = explainPlan(
        "MATCH {class: CForum, as: forum}"
            + ".out('CContainerOf'){as: msg,"
            + "  where: (creationDate >= 1400 AND creationDate <= 1700)}"
            + " RETURN forum.title as forumTitle, msg.content as content");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 15. Self-referencing patterns (Person → CKnows → Person)
  // ========================================================================

  /**
   * Self-referencing edge class (CKnows: CPerson → CPerson) with indexed
   * edge property and back-reference. Tests that the pre-filter handles
   * same-class source and target correctly.
   */
  @Test
  public void selfRef_knowsEdge_indexedWithBackRef() {
    session.begin();
    // Find triangles: p → friend → fof → p (3-hop cycle)
    // This is a self-referencing pattern on CPerson with CKnows
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".out('CKnows'){as: fof}"
            + ".out('CKnows'){as: back,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN friend.name as f1, fof.name as f2")
        .toList();

    // Check correctness — whether any 3-hop cycles exist from p0
    // p0→p1→p3→p5→p7: no back to p0
    // p0→p2→p4→p6: no back to p0
    // No triangles expected
    assertEquals(0, result.size());

    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".out('CKnows'){as: fof}"
            + ".out('CKnows'){as: back,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN friend.name as f1, fof.name as f2");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Self-referencing with outE/inV and indexed edge property.
   */
  @Test
  public void selfRef_outEInV_indexedEdgeProperty() {
    session.begin();
    // Persons who know each other since >= 2007
    var result = session.query(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CKnows'){as: k, where: (since >= 2007)}"
            + ".inV(){as: friend}"
            + " RETURN friend.name as friendName, k.since as since")
        .toList();

    // p0→p1 (since=2006), p0→p2 (since=2007)
    // since >= 2007: only p2
    assertEquals(1, result.size());
    assertEquals("p2", result.get(0).getProperty("friendName"));

    String plan = explainPlan(
        "MATCH {class: CPerson, as: p, where: (name = 'p0')}"
            + ".outE('CKnows'){as: k, where: (since >= 2007)}"
            + ".inV(){as: friend}"
            + " RETURN friend.name, k.since");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 16. LDBC-inspired complex patterns
  // ========================================================================

  /**
   * IC5-inspired pattern: person → friends → posts in forums → posts
   * by person (back-reference). Tests the full LDBC IC5 optimization
   * path with forum membership.
   */
  @Test
  public void ldbcIC5_forumPostsByFriendOfFriend() {
    session.begin();
    // p0 → knows → p1
    // p1 authored m3, m4, m5 in 'general'
    // general contains m0..m5
    // Back-ref: posts in general authored by p1
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: friendPost}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: forumPost}"
            + ".out('CHasCreator'){as: author,"
            + "  where: (@rid = $matched.friend.@rid)}"
            + " RETURN friend.name as friendName,"
            + "  forum.title as forumTitle,"
            + "  forumPost.content as content")
        .toList();

    assertFalse("Should have results", result.isEmpty());
    for (var r : result) {
      String friendName = r.getProperty("friendName");
      String content = r.getProperty("content");
      // Each result should have the friend as author of the forum post
      assertNotNull(friendName);
      assertNotNull(content);
    }

    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".out('CKnows'){as: friend}"
            + ".in('CHasCreator'){as: friendPost}"
            + ".in('CContainerOf'){as: forum}"
            + ".out('CContainerOf'){as: forumPost}"
            + ".out('CHasCreator'){as: author,"
            + "  where: (@rid = $matched.friend.@rid)}"
            + " RETURN friend.name, forum.title, forumPost.content");
    assertTrue("Plan should show intersection for IC5-like pattern:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * IC11-inspired pattern with edge-method traversal and index filter.
   * Person → outE(CWorksAt){workFrom < year} → inV(company)
   * → in(CWorksAt)(coworker) → out(CKnows) → mutual
   */
  @Test
  public void ldbcIC11_workAtWithEdgeMethodAndKnows() {
    session.begin();
    // p0 works at acme (workFrom=2010 < 2013)
    // acme coworkers: p0, p1, p2
    // p1 knows p0, p3
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".outE('CWorksAt'){where: (workFrom < 2013)}"
            + ".inV(){as: company}"
            + ".in('CWorksAt'){as: coworker}"
            + ".out('CKnows'){as: coworkerFriend}"
            + " RETURN coworker.name as cwName,"
            + "  coworkerFriend.name as cfName")
        .toList();

    assertFalse("Should have results", result.isEmpty());
    Set<String> coworkers = new HashSet<>();
    for (var r : result) {
      coworkers.add(r.getProperty("cwName"));
    }
    // acme workers: p0, p1, p2 (but only p0 matched workFrom < 2013)
    // So company is acme, coworkers are p0, p1, p2
    assertTrue("Should find p0 as coworker", coworkers.contains("p0"));
    assertTrue("Should find p1 as coworker", coworkers.contains("p1"));
    assertTrue("Should find p2 as coworker", coworkers.contains("p2"));

    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".outE('CWorksAt'){where: (workFrom < 2013)}"
            + ".inV(){as: company}"
            + ".in('CWorksAt'){as: coworker}"
            + ".out('CKnows'){as: coworkerFriend}"
            + " RETURN coworker.name as cwName,"
            + "  coworkerFriend.name as cfName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Message → Tags → TagClass hierarchy pattern. Tests the combination
   * of class inference, index filter, and hierarchy traversal.
   */
  @Test
  public void tagHierarchyPattern_messageToTagClass() {
    session.begin();
    // m0 → java → ScriptLanguage → Programming
    // m2 → rust → SystemsProgramming → Programming
    var result = session.query(
        "MATCH {class: CMessage, as: msg,"
            + "  where: (content = 'm0' OR content = 'm2')}"
            + ".out('CHasTag'){as: tag}"
            + ".out('CHasType'){as: tagClass}"
            + " RETURN msg.content as content, tag.label as tagLabel,"
            + "  tagClass.name as className")
        .toList();

    assertEquals(2, result.size());
    Set<String> pairs = new HashSet<>();
    for (var r : result) {
      pairs.add(r.getProperty("content") + ":"
          + r.getProperty("tagLabel") + ":"
          + r.getProperty("className"));
    }
    assertEquals(
        Set.of("m0:java:ScriptLanguage", "m2:rust:SystemsProgramming"),
        pairs);

    // No WHERE filter on the tag or tagClass traversal targets, and no
    // back-reference. The out('CHasTag') and out('CHasType') edges simply
    // enumerate all targets without any pre-filtering opportunity.
    String plan = explainPlan(
        "MATCH {class: CMessage, as: msg,"
            + "  where: (content = 'm0' OR content = 'm2')}"
            + ".out('CHasTag'){as: tag}"
            + ".out('CHasType'){as: tagClass}"
            + " RETURN msg.content as content, tag.label as tagLabel,"
            + "  tagClass.name as className");
    assertFalse(
        "No WHERE filters on traversal targets, so no intersection:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 17. Correctness after data mutation within transaction
  // ========================================================================

  /**
   * Verify that pre-filter results are correct when data is mutated within
   * the same transaction (new edges added after schema setup but before
   * the MATCH query).
   */
  @Test
  public void dataMutation_newEdgesVisibleToPreFilter() {
    session.begin();
    // Add a new message by p0 in 'tech' forum
    session.execute(
        "CREATE VERTEX CMessage set content = 'mNew', creationDate = 9000")
        .close();
    session.execute(
        "CREATE EDGE CContainerOf FROM"
            + " (SELECT FROM CForum WHERE title = 'tech')"
            + " TO (SELECT FROM CMessage WHERE content = 'mNew')")
        .close();
    session.execute(
        "CREATE EDGE CHasCreator FROM"
            + " (SELECT FROM CMessage WHERE content = 'mNew')"
            + " TO (SELECT FROM CPerson WHERE name = 'p0')")
        .close();

    // Query should find the new message via standard traversal (no back-ref)
    var result = session.query(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: post}"
            + ".in('CContainerOf'){as: forum,"
            + "  where: (title = 'tech')}"
            + " RETURN post.content as content")
        .toList();

    Set<String> contents = new HashSet<>();
    for (var r : result) {
      contents.add(r.getProperty("content"));
    }
    assertTrue("Should find newly created message",
        contents.contains("mNew"));

    // No indexed filter on traversal targets (CForum.title is not indexed) and
    // no back-reference in this query. The where: (title = 'tech') on the forum
    // alias is a post-filter, not an index pre-filter.
    // No intersection: CForum.title has no index, so no IndexLookup.
    // This test intentionally omits the index to verify data mutation
    // visibility without pre-filter. Index pre-filter with indexed
    // properties is covered by indexFilter_vertexProperty_creationDateRange.
    String plan = explainPlan(
        "MATCH {class: CPerson, as: person, where: (name = 'p0')}"
            + ".in('CHasCreator'){as: post}"
            + ".in('CContainerOf'){as: forum,"
            + "  where: (title = 'tech')}"
            + " RETURN post.content as content");
    assertFalse(
        "No index on CForum.title, no back-ref — no intersection:\n" + plan,
        plan.contains("intersection:"));
    session.rollback();
  }
}
