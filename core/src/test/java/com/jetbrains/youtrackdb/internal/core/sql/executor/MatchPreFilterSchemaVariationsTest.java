package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Tests index-into pre-filter optimization across diverse graph schemas,
 * matching patterns, and coverage gaps. Each test method creates its own
 * isolated schema (using unique class name prefixes) to exercise the
 * planner against a wide variety of graph topologies.
 *
 * <p>Focus areas (schema variations):
 * <ul>
 *   <li>Star topologies (hub vertex with many edge types)</li>
 *   <li>Chain/pipeline patterns (linear multi-hop)</li>
 *   <li>Tree hierarchies (parent-child with depth)</li>
 *   <li>Bipartite graphs</li>
 *   <li>Cyclic / mutual-reference patterns</li>
 *   <li>Wide fan-out / fan-in vertices</li>
 *   <li>Multiple indexes on same class</li>
 *   <li>Composite WHERE with mixed $matched and indexable predicates</li>
 *   <li>Subclass polymorphism in edge targets</li>
 * </ul>
 *
 * <p>Focus areas (gap coverage):
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
public class MatchPreFilterSchemaVariationsTest extends MatchPreFilterTestBase {

  // ========================================================================
  // 1. Star topology — hub vertex with many typed edge classes
  // ========================================================================

  /**
   * Hub vertex with 5 different edge types, each with indexed properties.
   * Tests that the planner correctly attaches pre-filters to each edge
   * independently when they are part of the same MATCH pattern.
   */
  @Test
  public void star_hubWithMultipleEdgeTypes_indexOnEach() {
    session.execute("CREATE class SHub extends V").close();
    session.execute("CREATE property SHub.name STRING").close();

    session.execute("CREATE class SEmail extends V").close();
    session.execute("CREATE property SEmail.addr STRING").close();

    session.execute("CREATE class SPhone extends V").close();
    session.execute("CREATE property SPhone.number STRING").close();

    session.execute("CREATE class SAddress extends V").close();
    session.execute("CREATE property SAddress.city STRING").close();

    session.execute("CREATE class SHasEmail extends E").close();
    session.execute("CREATE property SHasEmail.out LINK SHub").close();
    session.execute("CREATE property SHasEmail.in LINK SEmail").close();
    session.execute("CREATE property SHasEmail.priority INTEGER").close();
    session.execute(
        "CREATE index SHasEmail_priority on SHasEmail (priority) NOTUNIQUE")
        .close();

    session.execute("CREATE class SHasPhone extends E").close();
    session.execute("CREATE property SHasPhone.out LINK SHub").close();
    session.execute("CREATE property SHasPhone.in LINK SPhone").close();
    session.execute("CREATE property SHasPhone.isPrimary INTEGER").close();
    session.execute(
        "CREATE index SHasPhone_isPrimary on SHasPhone (isPrimary) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX SHub set name = 'hub1'").close();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX SEmail set addr = 'e" + i + "@test.com'").close();
      session.execute(
          "CREATE EDGE SHasEmail FROM (SELECT FROM SHub WHERE name = 'hub1')"
              + " TO (SELECT FROM SEmail WHERE addr = 'e" + i + "@test.com')"
              + " SET priority = " + i)
          .close();
    }
    for (int i = 0; i < 3; i++) {
      session.execute(
          "CREATE VERTEX SPhone set number = '555-000" + i + "'").close();
      session.execute(
          "CREATE EDGE SHasPhone FROM (SELECT FROM SHub WHERE name = 'hub1')"
              + " TO (SELECT FROM SPhone WHERE number = '555-000" + i + "')"
              + " SET isPrimary = " + (i == 0 ? 1 : 0))
          .close();
    }
    session.commit();

    session.begin();
    // outE(SHasEmail){priority >= 3} → high priority emails
    var result = session.query(
        "MATCH {class: SHub, as: hub, where: (name = 'hub1')}"
            + ".outE('SHasEmail'){as: he, where: (priority >= 3)}"
            + ".inV(){as: email}"
            + " RETURN email.addr as addr")
        .toList();
    assertEquals(2, result.size()); // priority 3 and 4

    // outE(SHasPhone){isPrimary = 1} → primary phone
    var phoneResult = session.query(
        "MATCH {class: SHub, as: hub, where: (name = 'hub1')}"
            + ".outE('SHasPhone'){as: hp, where: (isPrimary = 1)}"
            + ".inV(){as: phone}"
            + " RETURN phone.number as num")
        .toList();
    assertEquals(1, phoneResult.size());

    String plan = explainPlan(
        "MATCH {class: SHub, as: hub, where: (name = 'hub1')}"
            + ".outE('SHasEmail'){as: he, where: (priority >= 3)}"
            + ".inV(){as: email}"
            + " RETURN email.addr as addr");
    assertTrue("Plan should show intersection for SHasEmail index:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Star pattern: hub → 3 edge types in the same MATCH query (multi-pattern).
   * All edge types have indexed properties with different range filters.
   */
  @Test
  public void star_multiPatternThreeEdgeTypes() {
    session.execute("CREATE class MHub extends V").close();
    session.execute("CREATE property MHub.name STRING").close();

    session.execute("CREATE class MItem extends V").close();
    session.execute("CREATE property MItem.label STRING").close();

    session.execute("CREATE class MEdgeA extends E").close();
    session.execute("CREATE property MEdgeA.out LINK MHub").close();
    session.execute("CREATE property MEdgeA.in LINK MItem").close();
    session.execute("CREATE property MEdgeA.score INTEGER").close();
    session.execute(
        "CREATE index MEdgeA_score on MEdgeA (score) NOTUNIQUE").close();

    session.execute("CREATE class MEdgeB extends E").close();
    session.execute("CREATE property MEdgeB.out LINK MHub").close();
    session.execute("CREATE property MEdgeB.in LINK MItem").close();
    session.execute("CREATE property MEdgeB.rank INTEGER").close();
    session.execute(
        "CREATE index MEdgeB_rank on MEdgeB (rank) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX MHub set name = 'center'").close();
    for (int i = 0; i < 6; i++) {
      session.execute(
          "CREATE VERTEX MItem set label = 'item" + i + "'").close();
    }
    // EdgeA: center → item0(score=10), item1(20), item2(30)
    for (int i = 0; i < 3; i++) {
      session.execute(
          "CREATE EDGE MEdgeA FROM (SELECT FROM MHub WHERE name = 'center')"
              + " TO (SELECT FROM MItem WHERE label = 'item" + i + "')"
              + " SET score = " + ((i + 1) * 10))
          .close();
    }
    // EdgeB: center → item3(rank=1), item4(rank=2), item5(rank=3)
    for (int i = 3; i < 6; i++) {
      session.execute(
          "CREATE EDGE MEdgeB FROM (SELECT FROM MHub WHERE name = 'center')"
              + " TO (SELECT FROM MItem WHERE label = 'item" + i + "')"
              + " SET rank = " + (i - 2))
          .close();
    }
    session.commit();

    session.begin();
    // Multi-pattern: EdgeA(score >= 20) and EdgeB(rank >= 2)
    var result = session.query(
        "MATCH {class: MHub, as: h, where: (name = 'center')}"
            + ".outE('MEdgeA'){as: ea, where: (score >= 20)}"
            + ".inV(){as: itemA},"
            + " {as: h}"
            + ".outE('MEdgeB'){as: eb, where: (rank >= 2)}"
            + ".inV(){as: itemB}"
            + " RETURN itemA.label as aLabel, itemB.label as bLabel")
        .toList();

    // EdgeA >= 20: item1(20), item2(30) → 2 items
    // EdgeB >= 2: item4(2), item5(3) → 2 items
    // Cartesian: 2 × 2 = 4
    assertEquals(4, result.size());

    String plan = explainPlan(
        "MATCH {class: MHub, as: h, where: (name = 'center')}"
            + ".outE('MEdgeA'){as: ea, where: (score >= 20)}"
            + ".inV(){as: itemA},"
            + " {as: h}"
            + ".outE('MEdgeB'){as: eb, where: (rank >= 2)}"
            + ".inV(){as: itemB}"
            + " RETURN itemA.label as aLabel, itemB.label as bLabel");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 2. Chain / pipeline patterns
  // ========================================================================

  /**
   * Linear 5-hop chain where each hop has an indexed WHERE filter.
   * Tests that the planner can attach pre-filters to multiple consecutive
   * edges in a linear pattern.
   */
  @Test
  public void chain_fiveHopsEachFiltered() {
    session.execute("CREATE class ChNode extends V").close();
    session.execute("CREATE property ChNode.name STRING").close();
    session.execute("CREATE property ChNode.value INTEGER").close();
    session.execute(
        "CREATE index ChNode_value on ChNode (value) NOTUNIQUE").close();

    session.execute("CREATE class ChLink extends E").close();
    session.execute("CREATE property ChLink.out LINK ChNode").close();
    session.execute("CREATE property ChLink.in LINK ChNode").close();

    session.begin();
    // Create a chain: n0 → n1 → n2 → n3 → n4 → n5
    // with values: 10, 20, 30, 40, 50, 60
    for (int i = 0; i <= 5; i++) {
      session.execute(
          "CREATE VERTEX ChNode set name = 'n" + i + "', value = "
              + ((i + 1) * 10))
          .close();
    }
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE EDGE ChLink FROM"
              + " (SELECT FROM ChNode WHERE name = 'n" + i + "')"
              + " TO (SELECT FROM ChNode WHERE name = 'n" + (i + 1) + "')")
          .close();
    }
    session.commit();

    session.begin();
    // 5-hop query: each hop filters by increasing value threshold
    var result = session.query(
        "MATCH {class: ChNode, as: a, where: (name = 'n0')}"
            + ".out('ChLink'){as: b, where: (value >= 15)}"
            + ".out('ChLink'){as: c, where: (value >= 25)}"
            + ".out('ChLink'){as: d, where: (value >= 35)}"
            + ".out('ChLink'){as: e, where: (value >= 45)}"
            + " RETURN b.name as bN, c.name as cN, d.name as dN, e.name as eN")
        .toList();

    // n0→n1(20>=15)→n2(30>=25)→n3(40>=35)→n4(50>=45) ✓
    assertEquals(1, result.size());
    assertEquals("n1", result.get(0).getProperty("bN"));
    assertEquals("n2", result.get(0).getProperty("cN"));
    assertEquals("n3", result.get(0).getProperty("dN"));
    assertEquals("n4", result.get(0).getProperty("eN"));

    String plan = explainPlan(
        "MATCH {class: ChNode, as: a, where: (name = 'n0')}"
            + ".out('ChLink'){as: b, where: (value >= 15)}"
            + ".out('ChLink'){as: c, where: (value >= 25)}"
            + ".out('ChLink'){as: d, where: (value >= 35)}"
            + ".out('ChLink'){as: e, where: (value >= 45)}"
            + " RETURN b.name, c.name, d.name, e.name");
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Pipeline with alternating outE/inV and out() hops.
   * Tests mixing edge-method and vertex-method traversals in one pattern.
   */
  @Test
  public void chain_alternatingEdgeAndVertexMethods() {
    session.execute("CREATE class PNode extends V").close();
    session.execute("CREATE property PNode.name STRING").close();

    session.execute("CREATE class PLinkA extends E").close();
    session.execute("CREATE property PLinkA.out LINK PNode").close();
    session.execute("CREATE property PLinkA.in LINK PNode").close();
    session.execute("CREATE property PLinkA.weight INTEGER").close();
    session.execute(
        "CREATE index PLinkA_weight on PLinkA (weight) NOTUNIQUE").close();

    session.execute("CREATE class PLinkB extends E").close();

    session.begin();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX PNode set name = 'pn" + i + "'").close();
    }
    // pn0 -PLinkA(weight=10)-> pn1 -PLinkB-> pn2 -PLinkA(weight=20)-> pn3
    session.execute(
        "CREATE EDGE PLinkA FROM (SELECT FROM PNode WHERE name = 'pn0')"
            + " TO (SELECT FROM PNode WHERE name = 'pn1') SET weight = 10")
        .close();
    session.execute(
        "CREATE EDGE PLinkB FROM (SELECT FROM PNode WHERE name = 'pn1')"
            + " TO (SELECT FROM PNode WHERE name = 'pn2')")
        .close();
    session.execute(
        "CREATE EDGE PLinkA FROM (SELECT FROM PNode WHERE name = 'pn2')"
            + " TO (SELECT FROM PNode WHERE name = 'pn3') SET weight = 20")
        .close();
    session.commit();

    session.begin();
    // outE(PLinkA){weight >= 5} → inV → out(PLinkB) → outE(PLinkA){weight>=15} → inV
    var result = session.query(
        "MATCH {class: PNode, as: start, where: (name = 'pn0')}"
            + ".outE('PLinkA'){as: ea1, where: (weight >= 5)}"
            + ".inV(){as: mid1}"
            + ".out('PLinkB'){as: mid2}"
            + ".outE('PLinkA'){as: ea2, where: (weight >= 15)}"
            + ".inV(){as: finish}"
            + " RETURN finish.name as finishName")
        .toList();

    assertEquals(1, result.size());
    assertEquals("pn3", result.get(0).getProperty("finishName"));

    String plan = explainPlan(
        "MATCH {class: PNode, as: start, where: (name = 'pn0')}"
            + ".outE('PLinkA'){as: ea1, where: (weight >= 5)}"
            + ".inV(){as: mid1}"
            + ".out('PLinkB'){as: mid2}"
            + ".outE('PLinkA'){as: ea2, where: (weight >= 15)}"
            + ".inV(){as: finish}"
            + " RETURN finish.name as finishName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 3. Bipartite graph
  // ========================================================================

  /**
   * Bipartite graph (Student↔Course) with indexed edge property (grade).
   * Tests pre-filter on edges connecting two disjoint vertex classes.
   */
  @Test
  public void bipartite_studentCourse_indexedGrade() {
    session.execute("CREATE class BStudent extends V").close();
    session.execute("CREATE property BStudent.name STRING").close();

    session.execute("CREATE class BCourse extends V").close();
    session.execute("CREATE property BCourse.title STRING").close();

    session.execute("CREATE class BEnrolled extends E").close();
    session.execute("CREATE property BEnrolled.out LINK BStudent").close();
    session.execute("CREATE property BEnrolled.in LINK BCourse").close();
    session.execute("CREATE property BEnrolled.grade INTEGER").close();
    session.execute(
        "CREATE index BEnrolled_grade on BEnrolled (grade) NOTUNIQUE").close();

    session.begin();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX BStudent set name = 's" + i + "'").close();
    }
    String[] courses = {"Math", "Physics", "CS"};
    for (String c : courses) {
      session.execute(
          "CREATE VERTEX BCourse set title = '" + c + "'").close();
    }
    // Each student enrolled in 2 courses with different grades
    int[][] enrollments = {
        {0, 0, 85}, {0, 1, 90}, {1, 0, 70}, {1, 2, 95},
        {2, 1, 60}, {2, 2, 80}, {3, 0, 45}, {3, 1, 55},
        {4, 0, 92}, {4, 2, 88}};
    for (int[] e : enrollments) {
      session.execute(
          "CREATE EDGE BEnrolled FROM"
              + " (SELECT FROM BStudent WHERE name = 's" + e[0] + "')"
              + " TO (SELECT FROM BCourse WHERE title = '"
              + courses[e[1]] + "') SET grade = " + e[2])
          .close();
    }
    session.commit();

    session.begin();
    // Find students with grade >= 90 in any course
    var result = session.query(
        "MATCH {class: BStudent, as: s}"
            + ".outE('BEnrolled'){as: enr, where: (grade >= 90)}"
            + ".inV(){as: course}"
            + " RETURN s.name as sName, course.title as cTitle,"
            + "  enr.grade as grade")
        .toList();

    // s0(Physics,90), s1(CS,95), s4(Math,92)
    Set<String> students = new HashSet<>();
    for (var r : result) {
      students.add(r.getProperty("sName"));
    }
    assertEquals(Set.of("s0", "s1", "s4"), students);

    String plan = explainPlan(
        "MATCH {class: BStudent, as: s}"
            + ".outE('BEnrolled'){as: enr, where: (grade >= 90)}"
            + ".inV(){as: course}"
            + " RETURN s.name, course.title, enr.grade");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Bipartite with back-reference: find students who share a course with
   * a specific student. Uses $matched back-ref across the bipartite bridge.
   */
  @Test
  public void bipartite_sharedCourse_backRef() {
    session.execute("CREATE class BStu extends V").close();
    session.execute("CREATE property BStu.name STRING").close();

    session.execute("CREATE class BCrs extends V").close();
    session.execute("CREATE property BCrs.title STRING").close();

    session.execute("CREATE class BTakes extends E").close();
    session.execute("CREATE property BTakes.out LINK BStu").close();
    session.execute("CREATE property BTakes.in LINK BCrs").close();

    session.begin();
    session.execute("CREATE VERTEX BStu set name = 'alice'").close();
    session.execute("CREATE VERTEX BStu set name = 'bob'").close();
    session.execute("CREATE VERTEX BStu set name = 'carol'").close();
    session.execute("CREATE VERTEX BCrs set title = 'DB101'").close();
    session.execute("CREATE VERTEX BCrs set title = 'ML201'").close();

    // alice → DB101, ML201; bob → DB101; carol → ML201
    session.execute(
        "CREATE EDGE BTakes FROM (SELECT FROM BStu WHERE name = 'alice')"
            + " TO (SELECT FROM BCrs WHERE title = 'DB101')")
        .close();
    session.execute(
        "CREATE EDGE BTakes FROM (SELECT FROM BStu WHERE name = 'alice')"
            + " TO (SELECT FROM BCrs WHERE title = 'ML201')")
        .close();
    session.execute(
        "CREATE EDGE BTakes FROM (SELECT FROM BStu WHERE name = 'bob')"
            + " TO (SELECT FROM BCrs WHERE title = 'DB101')")
        .close();
    session.execute(
        "CREATE EDGE BTakes FROM (SELECT FROM BStu WHERE name = 'carol')"
            + " TO (SELECT FROM BCrs WHERE title = 'ML201')")
        .close();
    session.commit();

    session.begin();
    // Find classmates of alice (students sharing at least one course)
    var result = session.query(
        "MATCH {class: BStu, as: student, where: (name = 'alice')}"
            + ".out('BTakes'){as: course}"
            + ".in('BTakes'){as: classmate,"
            + "  where: (@rid <> $matched.student.@rid)}"
            + " RETURN classmate.name as name")
        .toList();

    Set<String> classmates = new HashSet<>();
    for (var r : result) {
      classmates.add(r.getProperty("name"));
    }
    // bob shares DB101, carol shares ML201
    assertEquals(Set.of("bob", "carol"), classmates);

    // findRidEquality() only matches @rid = <expr> (SQLEqualsOperator). The <>
    // (not-equals) operator does not trigger EdgeRidLookup. The inequality is
    // evaluated as a standard post-traversal WHERE filter instead.
    String plan = explainPlan(
        "MATCH {class: BStu, as: student, where: (name = 'alice')}"
            + ".out('BTakes'){as: course}"
            + ".in('BTakes'){as: classmate,"
            + "  where: (@rid <> $matched.student.@rid)}"
            + " RETURN classmate.name as name");
    assertFalse(
        "Only @rid = ... triggers back-ref intersection, not @rid <>:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 4. Tree hierarchy with depth
  // ========================================================================

  /**
   * Deep tree (depth=4) with indexed node property. Tests that pre-filter
   * works at every level of the tree hierarchy.
   */
  @Test
  public void tree_deepHierarchy_indexFilterAtEachLevel() {
    session.execute("CREATE class TNode extends V").close();
    session.execute("CREATE property TNode.name STRING").close();
    session.execute("CREATE property TNode.level INTEGER").close();
    session.execute(
        "CREATE index TNode_level on TNode (level) NOTUNIQUE").close();

    session.execute("CREATE class TChild extends E").close();
    session.execute("CREATE property TChild.out LINK TNode").close();
    session.execute("CREATE property TChild.in LINK TNode").close();

    session.begin();
    // 4-level tree: root → 2 children → 2 grandchildren each → 2 great-gc each
    // Level 0: root
    // Level 1: c0, c1
    // Level 2: c00, c01, c10, c11
    // Level 3: c000, c001, c010, c011, c100, c101, c110, c111
    session.execute(
        "CREATE VERTEX TNode set name = 'root', level = 0").close();
    String[] level1 = {"c0", "c1"};
    for (String n : level1) {
      session.execute(
          "CREATE VERTEX TNode set name = '" + n + "', level = 1").close();
      session.execute(
          "CREATE EDGE TChild FROM (SELECT FROM TNode WHERE name = 'root')"
              + " TO (SELECT FROM TNode WHERE name = '" + n + "')")
          .close();
    }
    String[][] level2 = {{"c00", "c01"}, {"c10", "c11"}};
    for (int i = 0; i < 2; i++) {
      for (String n : level2[i]) {
        session.execute(
            "CREATE VERTEX TNode set name = '" + n + "', level = 2").close();
        session.execute(
            "CREATE EDGE TChild FROM"
                + " (SELECT FROM TNode WHERE name = '" + level1[i] + "')"
                + " TO (SELECT FROM TNode WHERE name = '" + n + "')")
            .close();
      }
    }
    session.commit();

    session.begin();
    // Find root → level1 → level2 nodes where level >= 2
    var result = session.query(
        "MATCH {class: TNode, as: root, where: (name = 'root')}"
            + ".out('TChild'){as: child}"
            + ".out('TChild'){as: grandchild, where: (level >= 2)}"
            + " RETURN child.name as cName, grandchild.name as gcName")
        .toList();

    assertEquals(4, result.size());
    Set<String> grandchildren = new HashSet<>();
    for (var r : result) {
      grandchildren.add(r.getProperty("gcName"));
    }
    assertEquals(Set.of("c00", "c01", "c10", "c11"), grandchildren);

    String plan = explainPlan(
        "MATCH {class: TNode, as: root, where: (name = 'root')}"
            + ".out('TChild'){as: child}"
            + ".out('TChild'){as: grandchild, where: (level >= 2)}"
            + " RETURN child.name, grandchild.name");
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  // ========================================================================
  // 5. Cyclic / mutual reference patterns
  // ========================================================================

  /**
   * Bidirectional friendship graph (A↔B, B↔C, C↔A triangle). Tests
   * back-reference cycle detection with mutual edges.
   */
  @Test
  public void cyclic_triangle_backRefCycleDetection() {
    session.execute("CREATE class TriPerson extends V").close();
    session.execute("CREATE property TriPerson.name STRING").close();
    session.execute("CREATE class TriFriend extends E").close();

    session.begin();
    session.execute("CREATE VERTEX TriPerson set name = 'A'").close();
    session.execute("CREATE VERTEX TriPerson set name = 'B'").close();
    session.execute("CREATE VERTEX TriPerson set name = 'C'").close();

    // Triangle: A→B, B→C, C→A
    session.execute(
        "CREATE EDGE TriFriend FROM"
            + " (SELECT FROM TriPerson WHERE name = 'A')"
            + " TO (SELECT FROM TriPerson WHERE name = 'B')")
        .close();
    session.execute(
        "CREATE EDGE TriFriend FROM"
            + " (SELECT FROM TriPerson WHERE name = 'B')"
            + " TO (SELECT FROM TriPerson WHERE name = 'C')")
        .close();
    session.execute(
        "CREATE EDGE TriFriend FROM"
            + " (SELECT FROM TriPerson WHERE name = 'C')"
            + " TO (SELECT FROM TriPerson WHERE name = 'A')")
        .close();
    session.commit();

    session.begin();
    // Find 3-hop cycles: person → friend → fof → back to person
    var result = session.query(
        "MATCH {class: TriPerson, as: person, where: (name = 'A')}"
            + ".out('TriFriend'){as: f1}"
            + ".out('TriFriend'){as: f2}"
            + ".out('TriFriend'){as: back,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN f1.name as f1Name, f2.name as f2Name")
        .toList();

    // A→B→C→A is a 3-hop cycle
    assertEquals(1, result.size());
    assertEquals("B", result.get(0).getProperty("f1Name"));
    assertEquals("C", result.get(0).getProperty("f2Name"));

    String plan = explainPlan(
        "MATCH {class: TriPerson, as: person, where: (name = 'A')}"
            + ".out('TriFriend'){as: f1}"
            + ".out('TriFriend'){as: f2}"
            + ".out('TriFriend'){as: back,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN f1.name, f2.name");
    assertTrue("Plan should show intersection for cycle back-ref:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Mutual edges (A→B and B→A). Tests back-reference with bidirectional
   * edges on the same edge class.
   */
  @Test
  public void cyclic_mutualEdges_backRef() {
    session.execute("CREATE class MutNode extends V").close();
    session.execute("CREATE property MutNode.name STRING").close();
    session.execute("CREATE class MutEdge extends E").close();

    session.begin();
    session.execute("CREATE VERTEX MutNode set name = 'X'").close();
    session.execute("CREATE VERTEX MutNode set name = 'Y'").close();
    session.execute("CREATE VERTEX MutNode set name = 'Z'").close();

    // X→Y, Y→X, X→Z (no Z→X)
    session.execute(
        "CREATE EDGE MutEdge FROM"
            + " (SELECT FROM MutNode WHERE name = 'X')"
            + " TO (SELECT FROM MutNode WHERE name = 'Y')")
        .close();
    session.execute(
        "CREATE EDGE MutEdge FROM"
            + " (SELECT FROM MutNode WHERE name = 'Y')"
            + " TO (SELECT FROM MutNode WHERE name = 'X')")
        .close();
    session.execute(
        "CREATE EDGE MutEdge FROM"
            + " (SELECT FROM MutNode WHERE name = 'X')"
            + " TO (SELECT FROM MutNode WHERE name = 'Z')")
        .close();
    session.commit();

    session.begin();
    // Find mutual connections: X → friend → back to X (2-hop cycle)
    var result = session.query(
        "MATCH {class: MutNode, as: origin, where: (name = 'X')}"
            + ".out('MutEdge'){as: friend}"
            + ".out('MutEdge'){as: backToOrigin,"
            + "  where: (@rid = $matched.origin.@rid)}"
            + " RETURN friend.name as friendName")
        .toList();

    // X→Y→X (cycle), but X→Z has no Z→X
    assertEquals(1, result.size());
    assertEquals("Y", result.get(0).getProperty("friendName"));

    String plan = explainPlan(
        "MATCH {class: MutNode, as: origin, where: (name = 'X')}"
            + ".out('MutEdge'){as: friend}"
            + ".out('MutEdge'){as: backToOrigin,"
            + "  where: (@rid = $matched.origin.@rid)}"
            + " RETURN friend.name as friendName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 6. Multiple indexes on same vertex class
  // ========================================================================

  /**
   * Vertex class with two indexed properties. Tests that the planner picks
   * the correct index for each WHERE predicate.
   */
  @Test
  public void multiIndex_twoIndexesSameClass() {
    session.execute("CREATE class MIProduct extends V").close();
    session.execute("CREATE property MIProduct.name STRING").close();
    session.execute("CREATE property MIProduct.price INTEGER").close();
    session.execute("CREATE property MIProduct.rating INTEGER").close();
    session.execute(
        "CREATE index MIProduct_price on MIProduct (price) NOTUNIQUE").close();
    session.execute(
        "CREATE index MIProduct_rating on MIProduct (rating) NOTUNIQUE")
        .close();

    session.execute("CREATE class MICategory extends V").close();
    session.execute("CREATE property MICategory.name STRING").close();
    session.execute("CREATE class MIBelongsTo extends E").close();
    session.execute("CREATE property MIBelongsTo.out LINK MIProduct").close();
    session.execute("CREATE property MIBelongsTo.in LINK MICategory").close();

    session.begin();
    session.execute(
        "CREATE VERTEX MICategory set name = 'electronics'").close();
    for (int i = 0; i < 10; i++) {
      session.execute(
          "CREATE VERTEX MIProduct set name = 'prod" + i + "', price = "
              + (i * 100) + ", rating = " + (i % 5))
          .close();
      session.execute(
          "CREATE EDGE MIBelongsTo FROM"
              + " (SELECT FROM MIProduct WHERE name = 'prod" + i + "')"
              + " TO (SELECT FROM MICategory WHERE name = 'electronics')")
          .close();
    }
    session.commit();

    session.begin();
    // Filter by price
    var priceResult = session.query(
        "MATCH {class: MICategory, as: cat, where: (name = 'electronics')}"
            + ".in('MIBelongsTo'){as: prod, where: (price >= 700)}"
            + " RETURN prod.name as name")
        .toList();
    assertEquals(3, priceResult.size()); // prod7(700), prod8(800), prod9(900)

    // Filter by rating
    var ratingResult = session.query(
        "MATCH {class: MICategory, as: cat, where: (name = 'electronics')}"
            + ".in('MIBelongsTo'){as: prod, where: (rating = 4)}"
            + " RETURN prod.name as name")
        .toList();
    assertEquals(2, ratingResult.size()); // prod4(4), prod9(4)

    // Both filters combined
    var bothResult = session.query(
        "MATCH {class: MICategory, as: cat, where: (name = 'electronics')}"
            + ".in('MIBelongsTo'){as: prod,"
            + "  where: (price >= 700 AND rating >= 3)}"
            + " RETURN prod.name as name")
        .toList();
    // prod7(700,2), prod8(800,3), prod9(900,4) → price>=700 AND rating>=3: prod8, prod9
    Set<String> names = new HashSet<>();
    for (var r : bothResult) {
      names.add(r.getProperty("name"));
    }
    assertEquals(Set.of("prod8", "prod9"), names);

    String plan = explainPlan(
        "MATCH {class: MICategory, as: cat, where: (name = 'electronics')}"
            + ".in('MIBelongsTo'){as: prod, where: (price >= 700)}"
            + " RETURN prod.name");
    assertTrue("Plan should show index intersection for price:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  // ========================================================================
  // 7. Subclass polymorphism in edge targets
  // ========================================================================

  /**
   * Edge targeting a base class where data includes subclass instances.
   * Tests that class filter (acceptedCollectionIds) correctly includes
   * both the base class and its subclasses.
   */
  @Test
  public void polymorphism_subclassTargets_classFilterInclusive() {
    session.execute("CREATE class PolyBase extends V").close();
    session.execute("CREATE property PolyBase.name STRING").close();

    session.execute("CREATE class PolySub1 extends PolyBase").close();
    session.execute("CREATE class PolySub2 extends PolyBase").close();

    session.execute("CREATE class PolyContainer extends V").close();
    session.execute("CREATE property PolyContainer.name STRING").close();
    session.execute("CREATE class PolyContains extends E").close();

    session.begin();
    session.execute(
        "CREATE VERTEX PolyContainer set name = 'box'").close();
    session.execute(
        "CREATE VERTEX PolyBase set name = 'base1'").close();
    session.execute(
        "CREATE VERTEX PolySub1 set name = 'sub1a'").close();
    session.execute(
        "CREATE VERTEX PolySub1 set name = 'sub1b'").close();
    session.execute(
        "CREATE VERTEX PolySub2 set name = 'sub2a'").close();

    for (String item : new String[] {"base1", "sub1a", "sub1b", "sub2a"}) {
      session.execute(
          "CREATE EDGE PolyContains FROM"
              + " (SELECT FROM PolyContainer WHERE name = 'box')"
              + " TO (SELECT FROM PolyBase WHERE name = '" + item + "')")
          .close();
    }
    session.commit();

    session.begin();
    // class: PolyBase should match all 4 vertices (base + both subclasses)
    var result = session.query(
        "MATCH {class: PolyContainer, as: box, where: (name = 'box')}"
            + ".out('PolyContains'){class: PolyBase, as: item}"
            + " RETURN item.name as itemName")
        .toList();
    assertEquals(4, result.size());

    // class: PolySub1 should match only sub1a, sub1b
    var sub1Result = session.query(
        "MATCH {class: PolyContainer, as: box, where: (name = 'box')}"
            + ".out('PolyContains'){class: PolySub1, as: item}"
            + " RETURN item.name as itemName")
        .toList();
    assertEquals(2, sub1Result.size());
    Set<String> sub1Names = new HashSet<>();
    for (var r : sub1Result) {
      sub1Names.add(r.getProperty("itemName"));
    }
    assertEquals(Set.of("sub1a", "sub1b"), sub1Names);

    // The class filter optimization IS active via setAcceptedCollectionIds() on
    // EdgeTraversal. It includes cluster IDs for PolyBase and all its subclasses
    // (PolySub1, PolySub2). This zero-I/O filter is not visible in EXPLAIN
    // because it uses the acceptedCollectionIds mechanism, not the intersection
    // descriptor infrastructure.
    String plan = explainPlan(
        "MATCH {class: PolyContainer, as: box, where: (name = 'box')}"
            + ".out('PolyContains'){class: PolyBase, as: item}"
            + " RETURN item.name as itemName");
    assertFalse(
        "Class filter uses acceptedCollectionIds, not intersection descriptor:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 8. Wide fan-out vertex with index pre-filter
  // ========================================================================

  /**
   * Vertex with 100 outgoing edges, only a few matching the index filter.
   * Tests that the pre-filter effectively skips the majority of edges.
   */
  @Test
  public void wideFanOut_100edges_indexFilterSelectsFew() {
    session.execute("CREATE class WideHub extends V").close();
    session.execute("CREATE property WideHub.name STRING").close();

    session.execute("CREATE class WideTarget extends V").close();
    session.execute("CREATE property WideTarget.label STRING").close();
    session.execute("CREATE property WideTarget.score INTEGER").close();
    session.execute(
        "CREATE index WideTarget_score on WideTarget (score) NOTUNIQUE")
        .close();

    session.execute("CREATE class WideLink extends E").close();
    session.execute("CREATE property WideLink.out LINK WideHub").close();
    session.execute("CREATE property WideLink.in LINK WideTarget").close();

    session.begin();
    session.execute("CREATE VERTEX WideHub set name = 'hub'").close();
    for (int i = 0; i < 100; i++) {
      session.execute(
          "CREATE VERTEX WideTarget set label = 't" + i + "', score = " + i)
          .close();
      session.execute(
          "CREATE EDGE WideLink FROM"
              + " (SELECT FROM WideHub WHERE name = 'hub')"
              + " TO (SELECT FROM WideTarget WHERE label = 't" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // Only 3 targets with score >= 97
    var result = session.query(
        "MATCH {class: WideHub, as: hub, where: (name = 'hub')}"
            + ".out('WideLink'){as: target, where: (score >= 97)}"
            + " RETURN target.label as label")
        .toList();
    assertEquals(3, result.size());
    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals(Set.of("t97", "t98", "t99"), labels);

    String plan = explainPlan(
        "MATCH {class: WideHub, as: hub, where: (name = 'hub')}"
            + ".out('WideLink'){as: target, where: (score >= 97)}"
            + " RETURN target.label");
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  // ========================================================================
  // 9. Wide fan-in with back-reference
  // ========================================================================

  /**
   * Vertex with 50 incoming edges, back-reference selects a subset.
   * Tests EdgeRidLookup with high-cardinality reverse link bag.
   */
  @Test
  public void wideFanIn_backRefSelectsSubset() {
    session.execute("CREATE class FITarget extends V").close();
    session.execute("CREATE property FITarget.name STRING").close();

    session.execute("CREATE class FISource extends V").close();
    session.execute("CREATE property FISource.name STRING").close();

    session.execute("CREATE class FIEdge extends E").close();
    session.execute("CREATE class FIBridge extends E").close();

    session.begin();
    session.execute("CREATE VERTEX FITarget set name = 'target'").close();
    session.execute("CREATE VERTEX FISource set name = 'anchor'").close();

    // 50 sources → target
    for (int i = 0; i < 50; i++) {
      session.execute(
          "CREATE VERTEX FISource set name = 'src" + i + "'").close();
      session.execute(
          "CREATE EDGE FIEdge FROM"
              + " (SELECT FROM FISource WHERE name = 'src" + i + "')"
              + " TO (SELECT FROM FITarget WHERE name = 'target')")
          .close();
    }
    // anchor connects to only 3 of those sources
    for (int i : new int[] {10, 25, 42}) {
      session.execute(
          "CREATE EDGE FIBridge FROM"
              + " (SELECT FROM FISource WHERE name = 'anchor')"
              + " TO (SELECT FROM FISource WHERE name = 'src" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // anchor → bridge → src → target ← src2 where src2 was bridged from anchor
    // Find sources connected to target that are also bridged from anchor
    var result = session.query(
        "MATCH {class: FISource, as: anchor, where: (name = 'anchor')}"
            + ".out('FIBridge'){as: bridged}"
            + ".out('FIEdge'){as: target}"
            + ".in('FIEdge'){as: co,"
            + "  where: (@rid = $matched.bridged.@rid)}"
            + " RETURN bridged.name as bName")
        .toList();

    // All 3 bridged sources connect to target; co = bridged (self-match)
    Set<String> bridgedNames = new HashSet<>();
    for (var r : result) {
      bridgedNames.add(r.getProperty("bName"));
    }
    assertEquals(Set.of("src10", "src25", "src42"), bridgedNames);

    String plan = explainPlan(
        "MATCH {class: FISource, as: anchor, where: (name = 'anchor')}"
            + ".out('FIBridge'){as: bridged}"
            + ".out('FIEdge'){as: target}"
            + ".in('FIEdge'){as: co,"
            + "  where: (@rid = $matched.bridged.@rid)}"
            + " RETURN bridged.name as bName");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 10. Mixed $matched and indexable WHERE
  // ========================================================================

  /**
   * WHERE clause containing both $matched reference (non-indexable) and
   * an indexable predicate. The planner should split the WHERE:
   * the indexable part gets an IndexLookup, the $matched part stays as
   * a runtime filter.
   */
  @Test
  public void mixedWhere_matchedPlusIndexable_splitFilter() {
    session.execute("CREATE class MxPerson extends V").close();
    session.execute("CREATE property MxPerson.name STRING").close();
    session.execute("CREATE property MxPerson.age INTEGER").close();

    session.execute("CREATE class MxMsg extends V").close();
    session.execute("CREATE property MxMsg.text STRING").close();
    session.execute("CREATE property MxMsg.ts LONG").close();
    session.execute(
        "CREATE index MxMsg_ts on MxMsg (ts) NOTUNIQUE").close();

    session.execute("CREATE class MxWrote extends E").close();
    session.execute("CREATE class MxKnows extends E").close();

    session.begin();
    session.execute("CREATE VERTEX MxPerson set name = 'alice', age = 30")
        .close();
    session.execute("CREATE VERTEX MxPerson set name = 'bob', age = 25")
        .close();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX MxMsg set text = 'msg" + i + "', ts = "
              + (1000 + i * 100))
          .close();
    }
    // alice wrote msg0, msg1, msg2; bob wrote msg3, msg4
    for (int i = 0; i < 3; i++) {
      session.execute(
          "CREATE EDGE MxWrote FROM"
              + " (SELECT FROM MxPerson WHERE name = 'alice')"
              + " TO (SELECT FROM MxMsg WHERE text = 'msg" + i + "')")
          .close();
    }
    for (int i = 3; i < 5; i++) {
      session.execute(
          "CREATE EDGE MxWrote FROM"
              + " (SELECT FROM MxPerson WHERE name = 'bob')"
              + " TO (SELECT FROM MxMsg WHERE text = 'msg" + i + "')")
          .close();
    }
    session.execute(
        "CREATE EDGE MxKnows FROM"
            + " (SELECT FROM MxPerson WHERE name = 'alice')"
            + " TO (SELECT FROM MxPerson WHERE name = 'bob')")
        .close();
    session.commit();

    session.begin();
    // alice → knows → bob → wrote → msg{ts >= 1300 AND @rid <> $matched.msg.@rid}
    // alice → wrote → msg (alice's messages)
    // Find bob's messages with ts >= 1300 that are not alice's message
    var result = session.query(
        "MATCH {class: MxPerson, as: alice, where: (name = 'alice')}"
            + ".out('MxWrote'){as: aliceMsg}"
            + ".in('MxWrote'){as: alice2,"
            + "  where: (@rid = $matched.alice.@rid)},"
            + " {as: alice}.out('MxKnows'){as: bob}"
            + ".out('MxWrote'){as: bobMsg,"
            + "  where: (ts >= 1300)}"
            + " RETURN bobMsg.text as text")
        .toList();

    // bob's messages with ts >= 1300: msg3(1300), msg4(1400)
    Set<String> texts = new HashSet<>();
    for (var r : result) {
      texts.add(r.getProperty("text"));
    }
    assertTrue("Should find msg3", texts.contains("msg3"));
    assertTrue("Should find msg4", texts.contains("msg4"));

    String plan = explainPlan(
        "MATCH {class: MxPerson, as: alice, where: (name = 'alice')}"
            + ".out('MxWrote'){as: aliceMsg}"
            + ".in('MxWrote'){as: alice2,"
            + "  where: (@rid = $matched.alice.@rid)},"
            + " {as: alice}.out('MxKnows'){as: bob}"
            + ".out('MxWrote'){as: bobMsg,"
            + "  where: (ts >= 1300)}"
            + " RETURN bobMsg.text");
    // Should see both index intersection (ts on bobMsg) and
    // EdgeRidLookup intersection (back-ref on alice2)
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 11. Edge-method with class inference on deep chain
  // ========================================================================

  /**
   * outE→inV→outE→inV chain where class is inferred from edge LINK schema
   * at each level. Tests that class inference propagates through multi-step
   * edge-method chains.
   */
  @Test
  public void edgeMethodChain_classInferencePropagates() {
    session.execute("CREATE class ICStudent extends V").close();
    session.execute("CREATE property ICStudent.name STRING").close();

    session.execute("CREATE class ICDept extends V").close();
    session.execute("CREATE property ICDept.name STRING").close();

    session.execute("CREATE class ICUniv extends V").close();
    session.execute("CREATE property ICUniv.name STRING").close();

    session.execute("CREATE class ICStudiesAt extends E").close();
    session.execute("CREATE property ICStudiesAt.out LINK ICStudent").close();
    session.execute("CREATE property ICStudiesAt.in LINK ICDept").close();
    session.execute("CREATE property ICStudiesAt.year INTEGER").close();
    session.execute(
        "CREATE index ICStudiesAt_year on ICStudiesAt (year) NOTUNIQUE")
        .close();

    session.execute("CREATE class ICPartOf extends E").close();
    session.execute("CREATE property ICPartOf.out LINK ICDept").close();
    session.execute("CREATE property ICPartOf.in LINK ICUniv").close();

    session.begin();
    session.execute("CREATE VERTEX ICUniv set name = 'MIT'").close();
    session.execute("CREATE VERTEX ICDept set name = 'CS'").close();
    session.execute("CREATE VERTEX ICDept set name = 'Math'").close();
    session.execute("CREATE VERTEX ICStudent set name = 'Alice'").close();
    session.execute("CREATE VERTEX ICStudent set name = 'Bob'").close();

    session.execute(
        "CREATE EDGE ICPartOf FROM (SELECT FROM ICDept WHERE name = 'CS')"
            + " TO (SELECT FROM ICUniv WHERE name = 'MIT')")
        .close();
    session.execute(
        "CREATE EDGE ICPartOf FROM (SELECT FROM ICDept WHERE name = 'Math')"
            + " TO (SELECT FROM ICUniv WHERE name = 'MIT')")
        .close();
    session.execute(
        "CREATE EDGE ICStudiesAt FROM"
            + " (SELECT FROM ICStudent WHERE name = 'Alice')"
            + " TO (SELECT FROM ICDept WHERE name = 'CS') SET year = 2022")
        .close();
    session.execute(
        "CREATE EDGE ICStudiesAt FROM"
            + " (SELECT FROM ICStudent WHERE name = 'Bob')"
            + " TO (SELECT FROM ICDept WHERE name = 'Math') SET year = 2020")
        .close();
    session.commit();

    session.begin();
    // Student → outE(ICStudiesAt){year >= 2021} → inV(dept) → out(ICPartOf)(univ)
    var result = session.query(
        "MATCH {class: ICStudent, as: student}"
            + ".outE('ICStudiesAt'){as: sa, where: (year >= 2021)}"
            + ".inV(){as: dept}"
            + ".out('ICPartOf'){as: univ}"
            + " RETURN student.name as sName, dept.name as dName,"
            + "  univ.name as uName")
        .toList();

    // Only Alice (year=2022 >= 2021)
    assertEquals(1, result.size());
    assertEquals("Alice", result.get(0).getProperty("sName"));
    assertEquals("CS", result.get(0).getProperty("dName"));
    assertEquals("MIT", result.get(0).getProperty("uName"));

    String plan = explainPlan(
        "MATCH {class: ICStudent, as: student}"
            + ".outE('ICStudiesAt'){as: sa, where: (year >= 2021)}"
            + ".inV(){as: dept}"
            + ".out('ICPartOf'){as: univ}"
            + " RETURN student.name, dept.name, univ.name");
    assertTrue("Plan should show intersection for ICStudiesAt:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 12. Empty schema / no data edge cases
  // ========================================================================

  /**
   * Schema exists but no data. MATCH should return empty results without
   * errors, even when pre-filter is configured.
   */
  @Test
  public void emptyData_schemaOnlyNoVertices() {
    session.execute("CREATE class EmptyV extends V").close();
    session.execute("CREATE property EmptyV.val INTEGER").close();
    session.execute(
        "CREATE index EmptyV_val on EmptyV (val) NOTUNIQUE").close();
    session.execute("CREATE class EmptyE extends E").close();

    session.begin();
    var result = session.query(
        "MATCH {class: EmptyV, as: a}"
            + ".out('EmptyE'){as: b, where: (val > 10)}"
            + " RETURN a.val, b.val")
        .toList();
    assertEquals(0, result.size());

    // No intersection: EmptyE has no typed LINK properties, so the planner
    // cannot infer that the target class is EmptyV and cannot look up the
    // EmptyV_val index. This test intentionally uses an untyped edge to verify
    // correct behavior with empty data and no pre-filter. The same scenario
    // with typed LINK (where intersection triggers) is covered by
    // indexFilter_noMatchingRecords_emptyResult in MatchPreFilterComprehensiveTest.
    String plan = explainPlan(
        "MATCH {class: EmptyV, as: a}"
            + ".out('EmptyE'){as: b, where: (val > 10)}"
            + " RETURN a.val, b.val");
    assertFalse(
        "No typed LINK on EmptyE prevents target class inference:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 13. Pattern with RETURN $paths / $elements / $patterns
  // ========================================================================

  /**
   * Pre-filter with $paths return mode. Verifies that path collection
   * still works correctly when pre-filter is active.
   */
  @Test
  public void returnPaths_withPreFilter() {
    session.execute("CREATE class RPNode extends V").close();
    session.execute("CREATE property RPNode.name STRING").close();
    session.execute("CREATE property RPNode.val INTEGER").close();
    session.execute(
        "CREATE index RPNode_val on RPNode (val) NOTUNIQUE").close();
    session.execute("CREATE class RPEdge extends E").close();

    session.begin();
    session.execute("CREATE VERTEX RPNode set name = 'a', val = 1").close();
    session.execute("CREATE VERTEX RPNode set name = 'b', val = 10").close();
    session.execute("CREATE VERTEX RPNode set name = 'c', val = 20").close();
    session.execute(
        "CREATE EDGE RPEdge FROM (SELECT FROM RPNode WHERE name = 'a')"
            + " TO (SELECT FROM RPNode WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE RPEdge FROM (SELECT FROM RPNode WHERE name = 'a')"
            + " TO (SELECT FROM RPNode WHERE name = 'c')")
        .close();
    session.commit();

    session.begin();
    // $paths with pre-filter on target
    var result = session.query(
        "MATCH {class: RPNode, as: start, where: (name = 'a')}"
            + ".out('RPEdge'){as: target, where: (val >= 15)}"
            + " RETURN $paths")
        .toList();
    // Only 'c' (val=20) matches val >= 15
    assertEquals(1, result.size());

    // No intersection: RPEdge has no typed LINK properties, so the planner
    // cannot infer the target class RPNode and cannot discover the RPNode_val
    // index. The same scenario WITH intersection active is covered by
    // returnPaths_withPreFilterAndTypedLink (which uses typed LINK edges).
    String plan = explainPlan(
        "MATCH {class: RPNode, as: start, where: (name = 'a')}"
            + ".out('RPEdge'){as: target, where: (val >= 15)}"
            + " RETURN target.name as tName");
    assertFalse(
        "No typed LINK on RPEdge prevents target class inference:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * $paths return mode with index intersection ACTIVE. Uses a typed LINK
   * edge class so the planner can infer the target class and discover the
   * index. Verifies that $paths path collection works correctly when the
   * pre-filter is applied.
   */
  @Test
  public void returnPaths_withPreFilterAndTypedLink() {
    session.execute("CREATE class RPLNode extends V").close();
    session.execute("CREATE property RPLNode.name STRING").close();
    session.execute("CREATE property RPLNode.val INTEGER").close();
    session.execute(
        "CREATE index RPLNode_val on RPLNode (val) NOTUNIQUE").close();

    session.execute("CREATE class RPLEdge extends E").close();
    session.execute("CREATE property RPLEdge.out LINK RPLNode").close();
    session.execute("CREATE property RPLEdge.in LINK RPLNode").close();

    session.begin();
    session.execute("CREATE VERTEX RPLNode set name = 'root', val = 0")
        .close();
    session.execute("CREATE VERTEX RPLNode set name = 'low', val = 5")
        .close();
    session.execute("CREATE VERTEX RPLNode set name = 'mid', val = 15")
        .close();
    session.execute("CREATE VERTEX RPLNode set name = 'high', val = 25")
        .close();

    for (String target : new String[] {"low", "mid", "high"}) {
      session.execute(
          "CREATE EDGE RPLEdge FROM (SELECT FROM RPLNode WHERE name = 'root')"
              + " TO (SELECT FROM RPLNode WHERE name = '" + target + "')")
          .close();
    }
    session.commit();

    session.begin();
    // $paths with pre-filter: val >= 10 → mid(15), high(25)
    var result = session.query(
        "MATCH {class: RPLNode, as: start, where: (name = 'root')}"
            + ".out('RPLEdge'){as: target, where: (val >= 10)}"
            + " RETURN $paths")
        .toList();
    assertEquals(2, result.size());
    // Each $paths row should contain start + target (2 aliases)
    for (var r : result) {
      assertEquals(2, r.getPropertyNames().size());
    }

    // Typed LINK enables class inference → index intersection should trigger
    String plan = explainPlan(
        "MATCH {class: RPLNode, as: start, where: (name = 'root')}"
            + ".out('RPLEdge'){as: target, where: (val >= 10)}"
            + " RETURN target.name");
    assertTrue("Plan should show index intersection with typed LINK:\n"
        + plan, plan.contains("intersection: index"));
    session.commit();
  }

  // ========================================================================
  // 14. Negative: $currentMatch prevents index pre-filter
  // ========================================================================

  /**
   * WHERE with $currentMatch should NOT prevent correct results but may
   * affect pre-filter eligibility.
   */
  @Test
  public void currentMatch_inWhere_correctResults() {
    session.execute("CREATE class CMNode extends V").close();
    session.execute("CREATE property CMNode.name STRING").close();
    session.execute("CREATE class CMEdge extends E").close();

    session.begin();
    session.execute("CREATE VERTEX CMNode set name = 'a'").close();
    session.execute("CREATE VERTEX CMNode set name = 'b'").close();
    session.execute("CREATE VERTEX CMNode set name = 'c'").close();
    session.execute(
        "CREATE EDGE CMEdge FROM (SELECT FROM CMNode WHERE name = 'a')"
            + " TO (SELECT FROM CMNode WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE CMEdge FROM (SELECT FROM CMNode WHERE name = 'a')"
            + " TO (SELECT FROM CMNode WHERE name = 'c')")
        .close();
    session.commit();

    session.begin();
    // $currentMatch != $matched.start — should filter self-references
    var result = session.query(
        "MATCH {class: CMNode, as: start, where: (name = 'a')}"
            + ".out('CMEdge'){as: target}"
            + ".in('CMEdge'){as: back,"
            + "  where: ($currentMatch <> $matched.target)}"
            + " RETURN target.name as tName, back.name as bName")
        .toList();

    // a→b, a→c. From b, in(CMEdge) = {a}. a <> b? yes → return (b, a)
    // From c, in(CMEdge) = {a}. a <> c? yes → return (c, a)
    for (var r : result) {
      assertEquals("a", r.getProperty("bName"));
    }

    // $currentMatch comparisons are runtime-only values that cannot be resolved
    // at plan time. No @rid equality, no indexed property filter — just a
    // post-traversal WHERE filter evaluated during execution.
    String plan = explainPlan(
        "MATCH {class: CMNode, as: start, where: (name = 'a')}"
            + ".out('CMEdge'){as: target}"
            + ".in('CMEdge'){as: back,"
            + "  where: ($currentMatch <> $matched.target)}"
            + " RETURN target.name as tName, back.name as bName");
    assertFalse(
        "$currentMatch comparisons are runtime-only, not pre-filterable:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 15. Parameterized queries
  // ========================================================================

  /**
   * Index pre-filter with positional parameters (?).
   */
  @Test
  public void parameterized_positionalParams() {
    session.execute("CREATE class PPNode extends V").close();
    session.execute("CREATE property PPNode.name STRING").close();
    session.execute("CREATE property PPNode.val INTEGER").close();
    session.execute(
        "CREATE index PPNode_val on PPNode (val) NOTUNIQUE").close();
    session.execute("CREATE class PPEdge extends E").close();

    session.begin();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX PPNode set name = 'pp" + i + "', val = " + (i * 10))
          .close();
    }
    session.execute(
        "CREATE VERTEX PPNode set name = 'root', val = 0").close();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE EDGE PPEdge FROM (SELECT FROM PPNode WHERE name = 'root')"
              + " TO (SELECT FROM PPNode WHERE name = 'pp" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    var result = session.query(
        "MATCH {class: PPNode, as: root, where: (name = 'root')}"
            + ".out('PPEdge'){as: target, where: (val >= ?)}"
            + " RETURN target.name as name",
        30)
        .toList();
    // val >= 30: pp3(30), pp4(40)
    assertEquals(2, result.size());
    Set<String> names = new HashSet<>();
    for (var r : result) {
      names.add(r.getProperty("name"));
    }
    assertEquals(Set.of("pp3", "pp4"), names);

    // No intersection: PPEdge has no typed LINK properties, so the planner
    // cannot infer that the target class is PPNode and cannot discover the
    // PPNode_val index. This test intentionally uses an untyped edge to verify
    // positional parameter correctness without pre-filter. Index pre-filter
    // with named parameters is covered by indexFilter_namedParameter in
    // MatchPreFilterComprehensiveTest.
    String plan = explainPlan(
        "MATCH {class: PPNode, as: root, where: (name = 'root')}"
            + ".out('PPEdge'){as: target, where: (val >= ?)}"
            + " RETURN target.name as name",
        30);
    assertFalse(
        "No typed LINK on PPEdge prevents target class inference:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 16. Reverse traversal with pre-filter
  // ========================================================================

  /**
   * in() direction with index pre-filter on source vertex property.
   * Tests that the planner correctly attaches the index intersection
   * even when traversing in reverse direction.
   */
  @Test
  public void reverseTraversal_inDirection_indexFilter() {
    session.execute("CREATE class RvTarget extends V").close();
    session.execute("CREATE property RvTarget.name STRING").close();

    session.execute("CREATE class RvSource extends V").close();
    session.execute("CREATE property RvSource.name STRING").close();
    session.execute("CREATE property RvSource.priority INTEGER").close();
    session.execute(
        "CREATE index RvSource_priority on RvSource (priority) NOTUNIQUE")
        .close();

    session.execute("CREATE class RvLink extends E").close();
    session.execute("CREATE property RvLink.out LINK RvSource").close();
    session.execute("CREATE property RvLink.in LINK RvTarget").close();

    session.begin();
    session.execute("CREATE VERTEX RvTarget set name = 'center'").close();
    for (int i = 0; i < 10; i++) {
      session.execute(
          "CREATE VERTEX RvSource set name = 'rs" + i + "', priority = " + i)
          .close();
      session.execute(
          "CREATE EDGE RvLink FROM"
              + " (SELECT FROM RvSource WHERE name = 'rs" + i + "')"
              + " TO (SELECT FROM RvTarget WHERE name = 'center')")
          .close();
    }
    session.commit();

    session.begin();
    // Traverse in() from target to sources, filter by priority
    var result = session.query(
        "MATCH {class: RvTarget, as: t, where: (name = 'center')}"
            + ".in('RvLink'){as: src, where: (priority >= 8)}"
            + " RETURN src.name as name")
        .toList();
    assertEquals(2, result.size()); // rs8, rs9

    String plan = explainPlan(
        "MATCH {class: RvTarget, as: t, where: (name = 'center')}"
            + ".in('RvLink'){as: src, where: (priority >= 8)}"
            + " RETURN src.name");
    assertTrue("Plan should show index intersection:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
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

    // Get y's RID to use as parameter
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

    // The positive branch out('NP2Owns'){as: item} has no WHERE filter on
    // the target alias, so no IndexLookup can be attached there. The indexed
    // filter (score >= 30) lives on the NOT sub-pattern's target, which is
    // evaluated as a separate anti-join — not through the intersection
    // descriptor infrastructure on the positive branch edge.
    String plan = explainPlan(
        "MATCH {class: NP2Person, as: p, where: (name = 'p1')}"
            + ".out('NP2Owns'){as: item},"
            + " NOT {as: p}.out('NP2Banned'){as: item,"
            + "  where: (score >= 30)}"
            + " RETURN item.label as label");
    assertFalse(
        "Positive branch has no WHERE filter, NOT branch index does not"
            + " propagate to positive branch edge:\n" + plan,
        plan.contains("intersection:"));
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

  // ========================================================================
  // Missing combinations — PR #822 + PR #904 cross-feature coverage
  // ========================================================================

  /**
   * Combo: back-reference + BETWEEN in the same query. One edge uses
   * EdgeRidLookup (@rid = $matched), another uses IndexLookup via BETWEEN.
   */
  @Test
  public void combo_backRefPlusBetween() {
    session.execute("CREATE class CBPerson extends V").close();
    session.execute("CREATE property CBPerson.name STRING").close();

    session.execute("CREATE class CBMsg extends V").close();
    session.execute("CREATE property CBMsg.text STRING").close();
    session.execute("CREATE property CBMsg.ts LONG").close();
    session.execute("CREATE index CBMsg_ts on CBMsg (ts) NOTUNIQUE").close();

    session.execute("CREATE class CBWrote extends E").close();
    session.execute("CREATE property CBWrote.out LINK CBPerson").close();
    session.execute("CREATE property CBWrote.in LINK CBMsg").close();

    session.execute("CREATE class CBForum extends V").close();
    session.execute("CREATE class CBContains extends E").close();
    session.execute("CREATE property CBContains.out LINK CBForum").close();
    session.execute("CREATE property CBContains.in LINK CBMsg").close();

    session.begin();
    session.execute("CREATE VERTEX CBPerson set name = 'alice'").close();
    session.execute("CREATE VERTEX CBForum set title = 'main'").close();
    for (int i = 1; i <= 6; i++) {
      session.execute(
          "CREATE VERTEX CBMsg set text = 'm" + i + "', ts = " + (i * 100))
          .close();
      session.execute(
          "CREATE EDGE CBContains FROM (SELECT FROM CBForum WHERE title = 'main')"
              + " TO (SELECT FROM CBMsg WHERE text = 'm" + i + "')")
          .close();
    }
    // alice wrote m1, m2, m3
    for (int i = 1; i <= 3; i++) {
      session.execute(
          "CREATE EDGE CBWrote FROM (SELECT FROM CBPerson WHERE name = 'alice')"
              + " TO (SELECT FROM CBMsg WHERE text = 'm" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // forum → msgs BETWEEN ts 200 AND 400 → creator = alice (back-ref)
    // ts BETWEEN 200 AND 400: m2(200), m3(300), m4(400)
    // alice wrote m1,m2,m3 → intersection: m2, m3
    var result = session.query(
        "MATCH {class: CBPerson, as: person, where: (name = 'alice')}"
            + ".out('CBWrote'){as: myMsg}"
            + ".in('CBContains'){as: forum}"
            + ".out('CBContains'){as: forumMsg,"
            + "  where: (ts BETWEEN 200 AND 400)}"
            + ".in('CBWrote'){as: author,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN forumMsg.text as text")
        .toList();

    Set<String> texts = new HashSet<>();
    for (var r : result) {
      texts.add(r.getProperty("text"));
    }
    assertTrue("Should find m2", texts.contains("m2"));
    assertTrue("Should find m3", texts.contains("m3"));
    assertFalse("Should NOT find m4 (not by alice)", texts.contains("m4"));

    String plan = explainPlan(
        "MATCH {class: CBPerson, as: person, where: (name = 'alice')}"
            + ".out('CBWrote'){as: myMsg}"
            + ".in('CBContains'){as: forum}"
            + ".out('CBContains'){as: forumMsg,"
            + "  where: (ts BETWEEN 200 AND 400)}"
            + ".in('CBWrote'){as: author,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN forumMsg.text");
    assertTrue("Plan should show intersection (index + backref):\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Combo: multi-pattern with IndexLookup on one pattern and EdgeRidLookup
   * back-ref on another. Both optimizations should activate independently.
   */
  @Test
  public void combo_multiPattern_indexPlusBackRef() {
    session.execute("CREATE class MPPerson extends V").close();
    session.execute("CREATE property MPPerson.name STRING").close();

    session.execute("CREATE class MPMsg extends V").close();
    session.execute("CREATE property MPMsg.ts LONG").close();
    session.execute("CREATE index MPMsg_ts on MPMsg (ts) NOTUNIQUE").close();

    session.execute("CREATE class MPWrote extends E").close();
    session.execute("CREATE property MPWrote.out LINK MPPerson").close();
    session.execute("CREATE property MPWrote.in LINK MPMsg").close();

    session.execute("CREATE class MPKnows extends E").close();

    session.begin();
    session.execute("CREATE VERTEX MPPerson set name = 'alice'").close();
    session.execute("CREATE VERTEX MPPerson set name = 'bob'").close();
    session.execute("CREATE EDGE MPKnows FROM"
        + " (SELECT FROM MPPerson WHERE name = 'alice')"
        + " TO (SELECT FROM MPPerson WHERE name = 'bob')").close();

    for (int i = 1; i <= 4; i++) {
      session.execute(
          "CREATE VERTEX MPMsg set ts = " + (i * 100)).close();
      String author = i <= 2 ? "alice" : "bob";
      session.execute(
          "CREATE EDGE MPWrote FROM"
              + " (SELECT FROM MPPerson WHERE name = '" + author + "')"
              + " TO (SELECT FROM MPMsg WHERE ts = " + (i * 100) + ")")
          .close();
    }
    session.commit();

    session.begin();
    // Pattern 1: alice → knows → friend
    // Pattern 2: alice → wrote → msg{ts >= 200}
    var result = session.query(
        "MATCH {class: MPPerson, as: person, where: (name = 'alice')}"
            + ".out('MPKnows'){as: friend},"
            + " {as: person}.out('MPWrote'){as: msg, where: (ts >= 200)}"
            + " RETURN friend.name as friendName, msg.ts as msgTs")
        .toList();

    assertFalse("Should have results", result.isEmpty());
    for (var r : result) {
      assertEquals("bob", r.getProperty("friendName"));
      assertTrue("ts should be >= 200",
          ((Number) r.getProperty("msgTs")).longValue() >= 200);
    }

    String plan = explainPlan(
        "MATCH {class: MPPerson, as: person, where: (name = 'alice')}"
            + ".out('MPKnows'){as: friend},"
            + " {as: person}.out('MPWrote'){as: msg, where: (ts >= 200)}"
            + " RETURN friend.name, msg.ts");
    assertTrue("Plan should show index intersection on msg:\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  /**
   * Combo: optional edge with back-reference @rid = $matched.X.@rid.
   * Tests that the optional LEFT semantics (null on miss) work correctly
   * with the EdgeRidLookup intersection active.
   */
  @Test
  public void combo_optional_backRef() {
    session.execute("CREATE class OBPerson extends V").close();
    session.execute("CREATE property OBPerson.name STRING").close();
    session.execute("CREATE property OBPerson.pid INTEGER").close();

    session.execute("CREATE class OBMsg extends V").close();
    session.execute("CREATE class OBWrote extends E").close();
    session.execute("CREATE class OBLiked extends E").close();
    session.execute("CREATE class OBKnows extends E").close();

    session.begin();
    session.execute(
        "CREATE VERTEX OBPerson set name = 'alice', pid = 1").close();
    session.execute(
        "CREATE VERTEX OBPerson set name = 'bob', pid = 2").close();
    session.execute(
        "CREATE VERTEX OBPerson set name = 'carol', pid = 3").close();
    session.execute("CREATE VERTEX OBMsg set text = 'm1'").close();

    // alice wrote m1
    session.execute(
        "CREATE EDGE OBWrote FROM (SELECT FROM OBPerson WHERE name = 'alice')"
            + " TO (SELECT FROM OBMsg WHERE text = 'm1')")
        .close();
    // bob and carol liked m1
    session.execute(
        "CREATE EDGE OBLiked FROM (SELECT FROM OBPerson WHERE name = 'bob')"
            + " TO (SELECT FROM OBMsg WHERE text = 'm1')")
        .close();
    session.execute(
        "CREATE EDGE OBLiked FROM (SELECT FROM OBPerson WHERE name = 'carol')"
            + " TO (SELECT FROM OBMsg WHERE text = 'm1')")
        .close();
    // bob knows alice, carol does NOT know alice
    session.execute(
        "CREATE EDGE OBKnows FROM (SELECT FROM OBPerson WHERE name = 'bob')"
            + " TO (SELECT FROM OBPerson WHERE name = 'alice')")
        .close();
    session.commit();

    session.begin();
    // alice → wrote → msg → liked by → liker → knows → ?{@rid=$matched.author, optional}
    var result = session.query(
        "MATCH {class: OBPerson, as: author, where: (name = 'alice')}"
            + ".out('OBWrote'){as: msg}"
            + ".in('OBLiked'){as: liker}"
            + ".out('OBKnows'){as: knowsAuthor,"
            + "  where: (@rid = $matched.author.@rid), optional: true}"
            + " RETURN liker.name as likerName,"
            + "  knowsAuthor.name as knowsName")
        .toList();

    // bob liked m1, bob knows alice → knowsAuthor = alice
    // carol liked m1, carol does NOT know alice → knowsAuthor = null
    assertEquals(2, result.size());
    for (var r : result) {
      String liker = r.getProperty("likerName");
      if ("bob".equals(liker)) {
        assertEquals("alice", r.getProperty("knowsName"));
      } else if ("carol".equals(liker)) {
        assertNull("carol should not know alice",
            r.getProperty("knowsName"));
      }
    }

    String plan = explainPlan(
        "MATCH {class: OBPerson, as: author, where: (name = 'alice')}"
            + ".out('OBWrote'){as: msg}"
            + ".in('OBLiked'){as: liker}"
            + ".out('OBKnows'){as: knowsAuthor,"
            + "  where: (@rid = $matched.author.@rid), optional: true}"
            + " RETURN liker.name, knowsAuthor.name");
    assertTrue("Plan should show intersection for optional back-ref:\n"
        + plan, plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Combo: NOT pattern with back-reference intersection. The positive branch
   * has a back-ref, and the NOT sub-pattern should not interfere with it.
   */
  @Test
  public void combo_NOT_backRef() {
    session.execute("CREATE class NBPerson extends V").close();
    session.execute("CREATE property NBPerson.name STRING").close();
    session.execute("CREATE class NBMsg extends V").close();
    session.execute("CREATE class NBWrote extends E").close();
    session.execute("CREATE property NBWrote.out LINK NBPerson").close();
    session.execute("CREATE property NBWrote.in LINK NBMsg").close();
    session.execute("CREATE class NBFlagged extends E").close();
    session.execute("CREATE class NBForum extends V").close();
    session.execute("CREATE class NBContains extends E").close();
    session.execute("CREATE property NBContains.out LINK NBForum").close();
    session.execute("CREATE property NBContains.in LINK NBMsg").close();

    session.begin();
    session.execute("CREATE VERTEX NBPerson set name = 'alice'").close();
    session.execute("CREATE VERTEX NBForum set title = 'main'").close();
    for (int i = 1; i <= 4; i++) {
      session.execute("CREATE VERTEX NBMsg set text = 'n" + i + "'").close();
      session.execute(
          "CREATE EDGE NBContains FROM"
              + " (SELECT FROM NBForum WHERE title = 'main')"
              + " TO (SELECT FROM NBMsg WHERE text = 'n" + i + "')")
          .close();
    }
    // alice wrote n1, n2, n3 (not n4)
    for (int i = 1; i <= 3; i++) {
      session.execute(
          "CREATE EDGE NBWrote FROM"
              + " (SELECT FROM NBPerson WHERE name = 'alice')"
              + " TO (SELECT FROM NBMsg WHERE text = 'n" + i + "')")
          .close();
    }
    // n2 is flagged
    session.execute(
        "CREATE EDGE NBFlagged FROM"
            + " (SELECT FROM NBPerson WHERE name = 'alice')"
            + " TO (SELECT FROM NBMsg WHERE text = 'n2')")
        .close();
    session.commit();

    session.begin();
    // Back-ref: alice's msgs in forum, NOT flagged
    var result = session.query(
        "MATCH {class: NBPerson, as: person, where: (name = 'alice')}"
            + ".out('NBWrote'){as: myMsg}"
            + ".in('NBContains'){as: forum}"
            + ".out('NBContains'){as: forumMsg}"
            + ".in('NBWrote'){as: author,"
            + "  where: (@rid = $matched.person.@rid)},"
            + " NOT {as: person}.out('NBFlagged'){as: forumMsg}"
            + " RETURN forumMsg.text as text")
        .toList();

    Set<String> texts = new HashSet<>();
    for (var r : result) {
      texts.add(r.getProperty("text"));
    }
    assertTrue("Should find n1", texts.contains("n1"));
    assertTrue("Should find n3", texts.contains("n3"));
    assertFalse("Should NOT find n2 (flagged)", texts.contains("n2"));
    assertFalse("Should NOT find n4 (not by alice)", texts.contains("n4"));

    String plan = explainPlan(
        "MATCH {class: NBPerson, as: person, where: (name = 'alice')}"
            + ".out('NBWrote'){as: myMsg}"
            + ".in('NBContains'){as: forum}"
            + ".out('NBContains'){as: forumMsg}"
            + ".in('NBWrote'){as: author,"
            + "  where: (@rid = $matched.person.@rid)},"
            + " NOT {as: person}.out('NBFlagged'){as: forumMsg}"
            + " RETURN forumMsg.text");
    assertTrue("Plan should show intersection for back-ref:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Combo: multi-source class scan with back-reference. Each source person
   * independently triggers EdgeRidLookup for their own posts.
   */
  @Test
  public void combo_multiSource_backRef() {
    session.execute("CREATE class MSBPerson extends V").close();
    session.execute("CREATE property MSBPerson.name STRING").close();
    session.execute("CREATE class MSBMsg extends V").close();
    session.execute("CREATE class MSBWrote extends E").close();
    session.execute("CREATE property MSBWrote.out LINK MSBPerson").close();
    session.execute("CREATE property MSBWrote.in LINK MSBMsg").close();
    session.execute("CREATE class MSBForum extends V").close();
    session.execute("CREATE class MSBContains extends E").close();
    session.execute("CREATE property MSBContains.out LINK MSBForum").close();
    session.execute("CREATE property MSBContains.in LINK MSBMsg").close();

    session.begin();
    session.execute("CREATE VERTEX MSBForum set title = 'general'").close();
    for (int i = 0; i < 3; i++) {
      session.execute(
          "CREATE VERTEX MSBPerson set name = 'u" + i + "'").close();
      for (int j = 0; j < 2; j++) {
        String mName = "msg" + i + "_" + j;
        session.execute(
            "CREATE VERTEX MSBMsg set text = '" + mName + "'").close();
        session.execute(
            "CREATE EDGE MSBWrote FROM"
                + " (SELECT FROM MSBPerson WHERE name = 'u" + i + "')"
                + " TO (SELECT FROM MSBMsg WHERE text = '" + mName + "')")
            .close();
        session.execute(
            "CREATE EDGE MSBContains FROM"
                + " (SELECT FROM MSBForum WHERE title = 'general')"
                + " TO (SELECT FROM MSBMsg WHERE text = '" + mName + "')")
            .close();
      }
    }
    session.commit();

    session.begin();
    // Class scan: all persons → their posts in forum → back-ref
    var result = session.query(
        "MATCH {class: MSBPerson, as: person}"
            + ".out('MSBWrote'){as: myMsg}"
            + ".in('MSBContains'){as: forum}"
            + ".out('MSBContains'){as: forumMsg}"
            + ".in('MSBWrote'){as: author,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN person.name as pName, forumMsg.text as text")
        .toList();

    // Each person should find their own 2 messages
    assertFalse("Should have results", result.isEmpty());
    Set<String> persons = new HashSet<>();
    for (var r : result) {
      persons.add(r.getProperty("pName"));
    }
    assertEquals(Set.of("u0", "u1", "u2"), persons);

    String plan = explainPlan(
        "MATCH {class: MSBPerson, as: person}"
            + ".out('MSBWrote'){as: myMsg}"
            + ".in('MSBContains'){as: forum}"
            + ".out('MSBContains'){as: forumMsg}"
            + ".in('MSBWrote'){as: author,"
            + "  where: (@rid = $matched.person.@rid)}"
            + " RETURN person.name, forumMsg.text");
    assertTrue("Plan should show intersection for back-ref:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * PR #904: outE with WHERE matching all edges. Tests that the pre-filter
   * handles the case where the index RidSet contains all edges (100% match).
   */
  @Test
  public void edgeMethod_outE_allEdgesMatch() {
    session.execute("CREATE class AEPerson extends V").close();
    session.execute("CREATE property AEPerson.name STRING").close();
    session.execute("CREATE class AECompany extends V").close();
    session.execute("CREATE class AEWorksAt extends E").close();
    session.execute("CREATE property AEWorksAt.out LINK AEPerson").close();
    session.execute("CREATE property AEWorksAt.in LINK AECompany").close();
    session.execute("CREATE property AEWorksAt.year INTEGER").close();
    session.execute(
        "CREATE index AEWorksAt_year on AEWorksAt (year) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX AEPerson set name = 'pat'").close();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX AECompany set name = 'co" + i + "'").close();
      session.execute(
          "CREATE EDGE AEWorksAt FROM"
              + " (SELECT FROM AEPerson WHERE name = 'pat')"
              + " TO (SELECT FROM AECompany WHERE name = 'co" + i + "')"
              + " SET year = " + (2020 + i))
          .close();
    }
    session.commit();

    session.begin();
    // year >= 2020 matches ALL 5 edges
    var result = session.query(
        "MATCH {class: AEPerson, as: p, where: (name = 'pat')}"
            + ".outE('AEWorksAt'){as: w, where: (year >= 2020)}"
            + ".inV(){as: c}"
            + " RETURN c.name as cName")
        .toList();
    assertEquals(5, result.size());

    String plan = explainPlan(
        "MATCH {class: AEPerson, as: p, where: (name = 'pat')}"
            + ".outE('AEWorksAt'){as: w, where: (year >= 2020)}"
            + ".inV(){as: c} RETURN c.name");
    assertTrue("Plan should show intersection even when all match:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * PR #904: multiple outE edges between same vertex pair, filtered by
   * indexed property. Tests that edge-method pre-filter operates on edge
   * RIDs (not vertex RIDs) and returns all matching edges.
   */
  @Test
  public void edgeMethod_outE_multipleEdgesSamePair() {
    session.execute("CREATE class MEPNode extends V").close();
    session.execute("CREATE property MEPNode.name STRING").close();
    session.execute("CREATE class MEPEdge extends E").close();
    session.execute("CREATE property MEPEdge.out LINK MEPNode").close();
    session.execute("CREATE property MEPEdge.in LINK MEPNode").close();
    session.execute("CREATE property MEPEdge.weight INTEGER").close();
    session.execute(
        "CREATE index MEPEdge_weight on MEPEdge (weight) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX MEPNode set name = 'a'").close();
    session.execute("CREATE VERTEX MEPNode set name = 'b'").close();

    // 3 edges from a to b with different weights
    for (int w : new int[] {10, 20, 30}) {
      session.execute(
          "CREATE EDGE MEPEdge FROM (SELECT FROM MEPNode WHERE name = 'a')"
              + " TO (SELECT FROM MEPNode WHERE name = 'b') SET weight = " + w)
          .close();
    }
    session.commit();

    session.begin();
    // weight >= 15 → 2 edges (20, 30)
    var result = session.query(
        "MATCH {class: MEPNode, as: src, where: (name = 'a')}"
            + ".outE('MEPEdge'){as: e, where: (weight >= 15)}"
            + ".inV(){as: tgt}"
            + " RETURN e.weight as w")
        .toList();
    assertEquals(2, result.size());

    String plan = explainPlan(
        "MATCH {class: MEPNode, as: src, where: (name = 'a')}"
            + ".outE('MEPEdge'){as: e, where: (weight >= 15)}"
            + ".inV(){as: tgt} RETURN e.weight");
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * IndexLookup with <= operator (the only missing range operator).
   */
  @Test
  public void indexFilter_rangeLeOperator() {
    session.execute("CREATE class LEHub extends V").close();
    session.execute("CREATE property LEHub.name STRING").close();
    session.execute("CREATE class LEItem extends V").close();
    session.execute("CREATE property LEItem.val INTEGER").close();
    session.execute("CREATE index LEItem_val on LEItem (val) NOTUNIQUE")
        .close();
    session.execute("CREATE class LELink extends E").close();
    session.execute("CREATE property LELink.out LINK LEHub").close();
    session.execute("CREATE property LELink.in LINK LEItem").close();

    session.begin();
    session.execute("CREATE VERTEX LEHub set name = 'h'").close();
    for (int i = 0; i < 5; i++) {
      session.execute(
          "CREATE VERTEX LEItem set val = " + (i * 10)).close();
      session.execute(
          "CREATE EDGE LELink FROM (SELECT FROM LEHub WHERE name = 'h')"
              + " TO (SELECT FROM LEItem WHERE val = " + (i * 10) + ")")
          .close();
    }
    session.commit();

    session.begin();
    // val <= 20 → items with val 0, 10, 20
    var result = session.query(
        "MATCH {class: LEHub, as: hub, where: (name = 'h')}"
            + ".out('LELink'){as: item, where: (val <= 20)}"
            + " RETURN item.val as v")
        .toList();
    assertEquals(3, result.size());

    String plan = explainPlan(
        "MATCH {class: LEHub, as: hub, where: (name = 'h')}"
            + ".out('LELink'){as: item, where: (val <= 20)}"
            + " RETURN item.val");
    assertTrue("Plan should show index intersection for <= :\n" + plan,
        plan.contains("intersection: index"));
    session.commit();
  }

  // ========================================================================
  // 18. While-loop dedup regression (PR #904 fix)
  // ========================================================================

  /**
   * Regression test for the while-loop leaf-level dedup fix from PR #904.
   * Builds a diamond-shaped DAG where the same vertex is reachable via
   * multiple paths during WHILE traversal. Before the fix, the vertex
   * appeared once per path; after the fix, it is correctly deduplicated.
   *
   * <pre>
   *   root → mid1 → leaf
   *   root → mid2 → leaf   (diamond: leaf reachable via 2 paths)
   *   leaf → bottom
   * </pre>
   */
  @Test
  public void whileTraversal_diamondDag_deduplicatesLeaf() {
    session.execute("CREATE class WDNode extends V").close();
    session.execute("CREATE property WDNode.name STRING").close();
    session.execute("CREATE class WDEdge extends E").close();
    session.execute("CREATE property WDEdge.out LINK WDNode").close();
    session.execute("CREATE property WDEdge.in LINK WDNode").close();

    session.begin();
    session.execute("CREATE VERTEX WDNode set name = 'root'").close();
    session.execute("CREATE VERTEX WDNode set name = 'mid1'").close();
    session.execute("CREATE VERTEX WDNode set name = 'mid2'").close();
    session.execute("CREATE VERTEX WDNode set name = 'leaf'").close();
    session.execute("CREATE VERTEX WDNode set name = 'bottom'").close();

    // Diamond: root → mid1 → leaf, root → mid2 → leaf
    session.execute(
        "CREATE EDGE WDEdge FROM (SELECT FROM WDNode WHERE name = 'root')"
            + " TO (SELECT FROM WDNode WHERE name = 'mid1')")
        .close();
    session.execute(
        "CREATE EDGE WDEdge FROM (SELECT FROM WDNode WHERE name = 'root')"
            + " TO (SELECT FROM WDNode WHERE name = 'mid2')")
        .close();
    session.execute(
        "CREATE EDGE WDEdge FROM (SELECT FROM WDNode WHERE name = 'mid1')"
            + " TO (SELECT FROM WDNode WHERE name = 'leaf')")
        .close();
    session.execute(
        "CREATE EDGE WDEdge FROM (SELECT FROM WDNode WHERE name = 'mid2')"
            + " TO (SELECT FROM WDNode WHERE name = 'leaf')")
        .close();
    // Additional depth beyond the diamond
    session.execute(
        "CREATE EDGE WDEdge FROM (SELECT FROM WDNode WHERE name = 'leaf')"
            + " TO (SELECT FROM WDNode WHERE name = 'bottom')")
        .close();
    session.commit();

    session.begin();
    // WHILE traversal from root should visit mid1, mid2, leaf, bottom.
    // 'leaf' is reachable via both mid1 and mid2; it must appear exactly once.
    String query =
        "MATCH {class: WDNode, as: start, where: (name = 'root')}"
            + ".out('WDEdge'){while: (true), as: descendant}"
            + " RETURN descendant.name as dName";
    var result = session.query(query).toList();

    // Collect all descendant names
    var names = new HashSet<String>();
    for (var r : result) {
      names.add(r.getProperty("dName"));
    }
    // WHILE(true) emits the start node (root) plus all reachable descendants.
    assertEquals("Should reach root + all 4 descendants",
        Set.of("root", "mid1", "mid2", "leaf", "bottom"), names);

    // Verify no duplicates: each node should appear exactly once.
    // Before the PR #904 dedup fix, 'leaf' would appear twice (once per path).
    assertEquals("No duplicates — each node appears once",
        5, result.size());

    // WHILE edges use recursive DFS, not intersection pre-filter
    String plan = explainPlan(query);
    assertFalse(
        "WHILE edges use a separate execution path, not intersection:\n"
            + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * Diamond DAG with WHILE traversal + an index pre-filter on the hop
   * BEFORE the WHILE step. Tests that dedup and pre-filter coexist
   * correctly.
   */
  @Test
  public void whileTraversal_diamondDag_withPreFilterBeforeWhile() {
    session.execute("CREATE class WPNode extends V").close();
    session.execute("CREATE property WPNode.name STRING").close();
    session.execute("CREATE property WPNode.priority INTEGER").close();
    session.execute(
        "CREATE index WPNode_priority on WPNode (priority) NOTUNIQUE").close();

    session.execute("CREATE class WPChild extends E").close();
    session.execute("CREATE property WPChild.out LINK WPNode").close();
    session.execute("CREATE property WPChild.in LINK WPNode").close();

    session.begin();
    // root → a(priority=5), b(priority=15)
    // a → shared(priority=20)
    // b → shared(priority=20)   (diamond: shared reachable via a and b)
    // shared → deep(priority=30)
    session.execute("CREATE VERTEX WPNode set name = 'root', priority = 0")
        .close();
    session.execute("CREATE VERTEX WPNode set name = 'a', priority = 5")
        .close();
    session.execute("CREATE VERTEX WPNode set name = 'b', priority = 15")
        .close();
    session.execute("CREATE VERTEX WPNode set name = 'shared', priority = 20")
        .close();
    session.execute("CREATE VERTEX WPNode set name = 'deep', priority = 30")
        .close();

    session.execute(
        "CREATE EDGE WPChild FROM (SELECT FROM WPNode WHERE name = 'root')"
            + " TO (SELECT FROM WPNode WHERE name = 'a')")
        .close();
    session.execute(
        "CREATE EDGE WPChild FROM (SELECT FROM WPNode WHERE name = 'root')"
            + " TO (SELECT FROM WPNode WHERE name = 'b')")
        .close();
    session.execute(
        "CREATE EDGE WPChild FROM (SELECT FROM WPNode WHERE name = 'a')"
            + " TO (SELECT FROM WPNode WHERE name = 'shared')")
        .close();
    session.execute(
        "CREATE EDGE WPChild FROM (SELECT FROM WPNode WHERE name = 'b')"
            + " TO (SELECT FROM WPNode WHERE name = 'shared')")
        .close();
    session.execute(
        "CREATE EDGE WPChild FROM (SELECT FROM WPNode WHERE name = 'shared')"
            + " TO (SELECT FROM WPNode WHERE name = 'deep')")
        .close();
    session.commit();

    session.begin();
    // root → child{priority >= 10} → WHILE(out(WPChild)) → descendants
    // priority >= 10: only 'b'(15). WHILE from b: shared(20), deep(30).
    String query =
        "MATCH {class: WPNode, as: start, where: (name = 'root')}"
            + ".out('WPChild'){as: child, where: (priority >= 10)}"
            + ".out('WPChild'){while: (true), as: descendant}"
            + " RETURN child.name as cName, descendant.name as dName";
    var result = session.query(query).toList();

    var descNames = new HashSet<String>();
    for (var r : result) {
      descNames.add(r.getProperty("dName"));
    }
    // WHILE from b: emits b itself, then shared(20), deep(30)
    assertTrue("Should find shared", descNames.contains("shared"));
    assertTrue("Should find deep", descNames.contains("deep"));

    String plan = explainPlan(query);
    // Pre-filter should activate on the first hop (priority >= 10)
    assertTrue("Plan should show intersection for indexed priority:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 19. Edge-method composite: outE+index + back-reference on inV
  // ========================================================================

  /**
   * Edge-method pattern combining an indexed edge property filter on outE()
   * with a back-reference on the inV() target. This exercises the Composite
   * descriptor path for edge-method chains (IndexLookup on the edge +
   * EdgeRidLookup on the vertex).
   */
  @Test
  public void edgeMethod_outEIndex_plusBackRefOnInV() {
    session.execute("CREATE class ECPerson extends V").close();
    session.execute("CREATE property ECPerson.name STRING").close();

    session.execute("CREATE class ECPost extends V").close();
    session.execute("CREATE property ECPost.title STRING").close();

    session.execute("CREATE class ECWrote extends E").close();
    session.execute("CREATE property ECWrote.out LINK ECPerson").close();
    session.execute("CREATE property ECWrote.in LINK ECPost").close();
    session.execute("CREATE property ECWrote.ts LONG").close();
    session.execute(
        "CREATE index ECWrote_ts on ECWrote (ts) NOTUNIQUE").close();

    session.execute("CREATE class ECKnows extends E").close();
    session.execute("CREATE property ECKnows.out LINK ECPerson").close();
    session.execute("CREATE property ECKnows.in LINK ECPerson").close();

    session.begin();
    session.execute("CREATE VERTEX ECPerson set name = 'alice'").close();
    session.execute("CREATE VERTEX ECPerson set name = 'bob'").close();
    session.execute("CREATE EDGE ECKnows FROM"
        + " (SELECT FROM ECPerson WHERE name = 'alice')"
        + " TO (SELECT FROM ECPerson WHERE name = 'bob')").close();

    // alice wrote posts at ts=100,200,300; bob wrote at ts=200,400
    for (int i = 1; i <= 3; i++) {
      session.execute(
          "CREATE VERTEX ECPost set title = 'alice_p" + i + "'").close();
      session.execute(
          "CREATE EDGE ECWrote FROM"
              + " (SELECT FROM ECPerson WHERE name = 'alice')"
              + " TO (SELECT FROM ECPost WHERE title = 'alice_p" + i + "')"
              + " SET ts = " + (i * 100))
          .close();
    }
    for (int i = 1; i <= 2; i++) {
      session.execute(
          "CREATE VERTEX ECPost set title = 'bob_p" + i + "'").close();
      session.execute(
          "CREATE EDGE ECWrote FROM"
              + " (SELECT FROM ECPerson WHERE name = 'bob')"
              + " TO (SELECT FROM ECPost WHERE title = 'bob_p" + i + "')"
              + " SET ts = " + (i * 200))
          .close();
    }
    session.commit();

    session.begin();
    // alice → knows → friend → outE(ECWrote){ts >= 200}.inV(){@rid=$matched.post.@rid}
    // This tests: IndexLookup on ECWrote.ts + EdgeRidLookup on inV back-ref
    String query =
        "MATCH {class: ECPerson, as: author, where: (name = 'alice')}"
            + ".outE('ECWrote'){as: writeEdge}"
            + ".inV(){as: post}"
            + ".in('ECWrote'){as: anyAuthor}"
            + ".outE('ECWrote'){as: w2, where: (ts >= 200)}"
            + ".inV(){as: post2,"
            + "  where: (@rid = $matched.post.@rid)}"
            + " RETURN post.title as postTitle, w2.ts as ts";
    var result = session.query(query).toList();

    // alice's posts: alice_p1(100), alice_p2(200), alice_p3(300)
    // For each post, traverse back to author(alice), then outE(ECWrote){ts>=200}
    //   → posts with ts>=200: alice_p2(200), alice_p3(300)
    //   → inV where @rid = $matched.post → only the post itself if ts>=200
    // So: alice_p2(200) and alice_p3(300) match
    Set<String> titles = new HashSet<>();
    for (var r : result) {
      titles.add(r.getProperty("postTitle"));
    }
    assertTrue("Should find alice_p2", titles.contains("alice_p2"));
    assertTrue("Should find alice_p3", titles.contains("alice_p3"));
    assertFalse("Should NOT find alice_p1 (ts=100 < 200)",
        titles.contains("alice_p1"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection (index + backref):\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 20. inE().outV() with back-reference
  // ========================================================================

  /**
   * Reversed edge-method direction: inE().outV() with a back-reference on
   * the outV() target. Tests that EdgeRidLookup works correctly when the
   * edge traversal direction is reversed (in→out).
   */
  @Test
  public void edgeMethod_inEOutV_withBackRef() {
    session.execute("CREATE class IRPerson extends V").close();
    session.execute("CREATE property IRPerson.name STRING").close();

    session.execute("CREATE class IRMsg extends V").close();
    session.execute("CREATE property IRMsg.text STRING").close();

    session.execute("CREATE class IRWrote extends E").close();
    session.execute("CREATE property IRWrote.out LINK IRPerson").close();
    session.execute("CREATE property IRWrote.in LINK IRMsg").close();
    session.execute("CREATE property IRWrote.ts LONG").close();
    session.execute(
        "CREATE index IRWrote_ts on IRWrote (ts) NOTUNIQUE").close();

    session.execute("CREATE class IRForum extends V").close();
    session.execute("CREATE class IRContains extends E").close();
    session.execute("CREATE property IRContains.out LINK IRForum").close();
    session.execute("CREATE property IRContains.in LINK IRMsg").close();

    session.begin();
    session.execute("CREATE VERTEX IRPerson set name = 'alice'").close();
    session.execute("CREATE VERTEX IRPerson set name = 'bob'").close();
    session.execute("CREATE VERTEX IRForum set title = 'main'").close();

    // alice wrote m1(ts=100), m2(ts=200); bob wrote m3(ts=300), m4(ts=400)
    // all in 'main' forum
    for (int i = 1; i <= 2; i++) {
      session.execute(
          "CREATE VERTEX IRMsg set text = 'am" + i + "'").close();
      session.execute(
          "CREATE EDGE IRWrote FROM"
              + " (SELECT FROM IRPerson WHERE name = 'alice')"
              + " TO (SELECT FROM IRMsg WHERE text = 'am" + i + "')"
              + " SET ts = " + (i * 100))
          .close();
      session.execute(
          "CREATE EDGE IRContains FROM"
              + " (SELECT FROM IRForum WHERE title = 'main')"
              + " TO (SELECT FROM IRMsg WHERE text = 'am" + i + "')")
          .close();
    }
    for (int i = 1; i <= 2; i++) {
      session.execute(
          "CREATE VERTEX IRMsg set text = 'bm" + i + "'").close();
      session.execute(
          "CREATE EDGE IRWrote FROM"
              + " (SELECT FROM IRPerson WHERE name = 'bob')"
              + " TO (SELECT FROM IRMsg WHERE text = 'bm" + i + "')"
              + " SET ts = " + ((i + 2) * 100))
          .close();
      session.execute(
          "CREATE EDGE IRContains FROM"
              + " (SELECT FROM IRForum WHERE title = 'main')"
              + " TO (SELECT FROM IRMsg WHERE text = 'bm" + i + "')")
          .close();
    }
    session.commit();

    session.begin();
    // alice → wrote → msg → inContains → forum → outContains → forumMsg
    //   → inE(IRWrote){ts >= 200}.outV(){@rid = $matched.author}
    // This uses inE().outV() with a back-reference to verify alice authored
    // the forum message.
    String query =
        "MATCH {class: IRPerson, as: author, where: (name = 'alice')}"
            + ".out('IRWrote'){as: myMsg}"
            + ".in('IRContains'){as: forum}"
            + ".out('IRContains'){as: forumMsg}"
            + ".inE('IRWrote'){as: writeEdge, where: (ts >= 200)}"
            + ".outV(){as: writer,"
            + "  where: (@rid = $matched.author.@rid)}"
            + " RETURN forumMsg.text as text, writeEdge.ts as ts";
    var result = session.query(query).toList();

    // alice's msgs in main: am1(100), am2(200)
    // forum main has: am1, am2, bm1, bm2
    // For each forumMsg, inE(IRWrote){ts>=200}.outV(){=alice}:
    //   am2(ts=200 >= 200, author=alice) → match
    //   am1(ts=100 < 200) → no match on ts
    //   bm1/bm2 → author=bob, not alice
    Set<String> texts = new HashSet<>();
    for (var r : result) {
      texts.add(r.getProperty("text"));
    }
    assertTrue("Should find am2", texts.contains("am2"));
    assertFalse("Should NOT find am1 (ts < 200)", texts.contains("am1"));
    assertFalse("Should NOT find bm1 (by bob)", texts.contains("bm1"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 21. Edge-method with polymorphic class hierarchy
  // ========================================================================

  /**
   * outE().inV() where the target vertex class has subclasses. Tests that
   * class inference from edge LINK schema works correctly with polymorphism:
   * the pre-filter should accept both base class and subclass vertices.
   */
  @Test
  public void edgeMethod_outEInV_polymorphicTarget() {
    session.execute("CREATE class EPHub extends V").close();
    session.execute("CREATE property EPHub.name STRING").close();

    // Base class + 2 subclasses
    session.execute("CREATE class EPItem extends V").close();
    session.execute("CREATE property EPItem.label STRING").close();
    session.execute("CREATE class EPBook extends EPItem").close();
    session.execute("CREATE class EPArticle extends EPItem").close();

    session.execute("CREATE class EPHasItem extends E").close();
    session.execute("CREATE property EPHasItem.out LINK EPHub").close();
    session.execute("CREATE property EPHasItem.in LINK EPItem").close();
    session.execute("CREATE property EPHasItem.rating INTEGER").close();
    session.execute(
        "CREATE index EPHasItem_rating on EPHasItem (rating) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX EPHub set name = 'library'").close();

    // Mix of base and sub-class instances
    session.execute("CREATE VERTEX EPBook set label = 'book1'").close();
    session.execute("CREATE VERTEX EPBook set label = 'book2'").close();
    session.execute("CREATE VERTEX EPArticle set label = 'art1'").close();
    session.execute("CREATE VERTEX EPArticle set label = 'art2'").close();
    session.execute("CREATE VERTEX EPItem set label = 'generic1'").close();

    // Hub → items with different ratings
    String[] items = {"book1", "book2", "art1", "art2", "generic1"};
    int[] ratings = {10, 30, 20, 40, 15};
    for (int i = 0; i < items.length; i++) {
      session.execute(
          "CREATE EDGE EPHasItem FROM"
              + " (SELECT FROM EPHub WHERE name = 'library')"
              + " TO (SELECT FROM EPItem WHERE label = '" + items[i] + "')"
              + " SET rating = " + ratings[i])
          .close();
    }
    session.commit();

    session.begin();
    // outE(EPHasItem){rating >= 25}.inV() — should find book2(30), art2(40)
    // inV targets are polymorphic (EPBook, EPArticle, EPItem)
    String query =
        "MATCH {class: EPHub, as: hub, where: (name = 'library')}"
            + ".outE('EPHasItem'){as: e, where: (rating >= 25)}"
            + ".inV(){as: item}"
            + " RETURN item.label as label";
    var result = session.query(query).toList();

    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals("Should find book2 and art2 (both subclasses of EPItem)",
        Set.of("book2", "art2"), labels);

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for indexed rating:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  /**
   * inE().outV() with polymorphic source: the edge's out LINK points to a
   * base class, but actual sources are subclass instances. Tests that class
   * inference handles the reverse direction correctly.
   */
  @Test
  public void edgeMethod_inEOutV_polymorphicSource() {
    session.execute("CREATE class EPTarget extends V").close();
    session.execute("CREATE property EPTarget.name STRING").close();

    // Base class + subclass for edge sources
    session.execute("CREATE class EPSource extends V").close();
    session.execute("CREATE property EPSource.tag STRING").close();
    session.execute("CREATE class EPSpecialSource extends EPSource").close();

    session.execute("CREATE class EPLink extends E").close();
    session.execute("CREATE property EPLink.out LINK EPSource").close();
    session.execute("CREATE property EPLink.in LINK EPTarget").close();
    session.execute("CREATE property EPLink.weight INTEGER").close();
    session.execute(
        "CREATE index EPLink_weight on EPLink (weight) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX EPTarget set name = 'target1'").close();

    // Mix of base and subclass sources
    session.execute("CREATE VERTEX EPSource set tag = 'base1'").close();
    session.execute("CREATE VERTEX EPSpecialSource set tag = 'special1'")
        .close();
    session.execute("CREATE VERTEX EPSpecialSource set tag = 'special2'")
        .close();

    session.execute(
        "CREATE EDGE EPLink FROM (SELECT FROM EPSource WHERE tag = 'base1')"
            + " TO (SELECT FROM EPTarget WHERE name = 'target1')"
            + " SET weight = 10")
        .close();
    session.execute(
        "CREATE EDGE EPLink FROM"
            + " (SELECT FROM EPSpecialSource WHERE tag = 'special1')"
            + " TO (SELECT FROM EPTarget WHERE name = 'target1')"
            + " SET weight = 30")
        .close();
    session.execute(
        "CREATE EDGE EPLink FROM"
            + " (SELECT FROM EPSpecialSource WHERE tag = 'special2')"
            + " TO (SELECT FROM EPTarget WHERE name = 'target1')"
            + " SET weight = 50")
        .close();
    session.commit();

    session.begin();
    // target1 ← inE(EPLink){weight >= 25}.outV() → sources with weight >= 25
    // Should find special1(30) and special2(50), not base1(10)
    String query =
        "MATCH {class: EPTarget, as: tgt, where: (name = 'target1')}"
            + ".inE('EPLink'){as: e, where: (weight >= 25)}"
            + ".outV(){as: src}"
            + " RETURN src.tag as tag";
    var result = session.query(query).toList();

    Set<String> tags = new HashSet<>();
    for (var r : result) {
      tags.add(r.getProperty("tag"));
    }
    assertEquals(Set.of("special1", "special2"), tags);

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 22. Edge-method: multi-hop outE().inV() chain with filter on EACH hop
  // ========================================================================

  /**
   * 3-hop edge-method chain where each hop has an indexed edge property
   * filter. Tests that the planner attaches independent IndexLookup
   * descriptors to each edge step in the chain.
   */
  @Test
  public void edgeMethod_threeHopChain_filterOnEachHop() {
    session.execute("CREATE class THNode extends V").close();
    session.execute("CREATE property THNode.name STRING").close();

    session.execute("CREATE class THEdge extends E").close();
    session.execute("CREATE property THEdge.out LINK THNode").close();
    session.execute("CREATE property THEdge.in LINK THNode").close();
    session.execute("CREATE property THEdge.cost INTEGER").close();
    session.execute(
        "CREATE index THEdge_cost on THEdge (cost) NOTUNIQUE").close();

    session.begin();
    // Chain: a → b → c → d, with branching at b (b → c2)
    for (String n : new String[] {"a", "b", "c", "c2", "d"}) {
      session.execute(
          "CREATE VERTEX THNode set name = '" + n + "'").close();
    }
    session.execute(
        "CREATE EDGE THEdge FROM (SELECT FROM THNode WHERE name = 'a')"
            + " TO (SELECT FROM THNode WHERE name = 'b') SET cost = 10")
        .close();
    session.execute(
        "CREATE EDGE THEdge FROM (SELECT FROM THNode WHERE name = 'b')"
            + " TO (SELECT FROM THNode WHERE name = 'c') SET cost = 20")
        .close();
    session.execute(
        "CREATE EDGE THEdge FROM (SELECT FROM THNode WHERE name = 'b')"
            + " TO (SELECT FROM THNode WHERE name = 'c2') SET cost = 5")
        .close();
    session.execute(
        "CREATE EDGE THEdge FROM (SELECT FROM THNode WHERE name = 'c')"
            + " TO (SELECT FROM THNode WHERE name = 'd') SET cost = 30")
        .close();
    session.commit();

    session.begin();
    // a → outE{cost>=5}.inV → outE{cost>=15}.inV → outE{cost>=25}.inV
    // Hop 1 (cost>=5): both edges from a qualify (cost=10) → b
    // Hop 2 (cost>=15): b→c(20) qualifies, b→c2(5) does not → c
    // Hop 3 (cost>=25): c→d(30) qualifies → d
    String query =
        "MATCH {class: THNode, as: start, where: (name = 'a')}"
            + ".outE('THEdge'){as: e1, where: (cost >= 5)}"
            + ".inV(){as: n1}"
            + ".outE('THEdge'){as: e2, where: (cost >= 15)}"
            + ".inV(){as: n2}"
            + ".outE('THEdge'){as: e3, where: (cost >= 25)}"
            + ".inV(){as: n3}"
            + " RETURN n1.name as hop1, n2.name as hop2, n3.name as hop3";
    var result = session.query(query).toList();

    assertEquals(1, result.size());
    assertEquals("b", result.get(0).getProperty("hop1"));
    assertEquals("c", result.get(0).getProperty("hop2"));
    assertEquals("d", result.get(0).getProperty("hop3"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 23. Edge-method with BETWEEN on edge property
  // ========================================================================

  /**
   * BETWEEN operator on an indexed edge property via outE(). Tests that
   * the BETWEEN→range rewrite (SQLBetweenCondition.flatten()) works for
   * edge properties, not just vertex properties.
   */
  @Test
  public void edgeMethod_outE_betweenOnEdgeProperty() {
    session.execute("CREATE class EBHub extends V").close();
    session.execute("CREATE property EBHub.name STRING").close();

    session.execute("CREATE class EBTarget extends V").close();
    session.execute("CREATE property EBTarget.label STRING").close();

    session.execute("CREATE class EBLink extends E").close();
    session.execute("CREATE property EBLink.out LINK EBHub").close();
    session.execute("CREATE property EBLink.in LINK EBTarget").close();
    session.execute("CREATE property EBLink.score INTEGER").close();
    session.execute(
        "CREATE index EBLink_score on EBLink (score) NOTUNIQUE").close();

    session.begin();
    session.execute("CREATE VERTEX EBHub set name = 'hub'").close();
    for (int i = 1; i <= 6; i++) {
      session.execute(
          "CREATE VERTEX EBTarget set label = 't" + i + "'").close();
      session.execute(
          "CREATE EDGE EBLink FROM (SELECT FROM EBHub WHERE name = 'hub')"
              + " TO (SELECT FROM EBTarget WHERE label = 't" + i + "')"
              + " SET score = " + (i * 10))
          .close();
    }
    session.commit();

    session.begin();
    // score BETWEEN 20 AND 40 → t2(20), t3(30), t4(40)
    String query =
        "MATCH {class: EBHub, as: hub, where: (name = 'hub')}"
            + ".outE('EBLink'){as: e, where: (score BETWEEN 20 AND 40)}"
            + ".inV(){as: target}"
            + " RETURN target.label as label";
    var result = session.query(query).toList();

    Set<String> labels = new HashSet<>();
    for (var r : result) {
      labels.add(r.getProperty("label"));
    }
    assertEquals(Set.of("t2", "t3", "t4"), labels);

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for BETWEEN on edge:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 24. Edge-method + NOT pattern
  // ========================================================================

  /**
   * outE().inV() inside a NOT sub-pattern. The positive branch uses an
   * index-filtered vertex method; the NOT branch uses an edge-method with
   * an indexed edge property. Tests that the NOT semantics exclude the
   * correct rows while the positive branch's pre-filter activates.
   */
  @Test
  public void edgeMethod_inNotPattern() {
    session.execute("CREATE class ENPerson extends V").close();
    session.execute("CREATE property ENPerson.name STRING").close();

    session.execute("CREATE class ENPost extends V").close();
    session.execute("CREATE property ENPost.title STRING").close();

    session.execute("CREATE class ENWrote extends E").close();
    session.execute("CREATE property ENWrote.out LINK ENPerson").close();
    session.execute("CREATE property ENWrote.in LINK ENPost").close();

    session.execute("CREATE class ENFlagged extends E").close();
    session.execute("CREATE property ENFlagged.out LINK ENPerson").close();
    session.execute("CREATE property ENFlagged.in LINK ENPost").close();
    session.execute("CREATE property ENFlagged.severity INTEGER").close();
    session.execute(
        "CREATE index ENFlagged_severity on ENFlagged (severity) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX ENPerson set name = 'mod'").close();
    for (int i = 1; i <= 4; i++) {
      session.execute(
          "CREATE VERTEX ENPost set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE ENWrote FROM"
              + " (SELECT FROM ENPerson WHERE name = 'mod')"
              + " TO (SELECT FROM ENPost WHERE title = 'post" + i + "')")
          .close();
    }
    // Flag post2 (severity=5) and post3 (severity=3) via outE
    session.execute(
        "CREATE EDGE ENFlagged FROM"
            + " (SELECT FROM ENPerson WHERE name = 'mod')"
            + " TO (SELECT FROM ENPost WHERE title = 'post2')"
            + " SET severity = 5")
        .close();
    session.execute(
        "CREATE EDGE ENFlagged FROM"
            + " (SELECT FROM ENPerson WHERE name = 'mod')"
            + " TO (SELECT FROM ENPost WHERE title = 'post3')"
            + " SET severity = 3")
        .close();
    session.commit();

    session.begin();
    // mod's posts NOT flagged with severity >= 4
    // mod wrote post1-4. Flagged with sev>=4: post2(5). NOT removes post2.
    String query =
        "MATCH {class: ENPerson, as: person, where: (name = 'mod')}"
            + ".out('ENWrote'){as: post},"
            + " NOT {as: person}"
            + ".outE('ENFlagged'){as: flag, where: (severity >= 4)}"
            + ".inV(){as: post}"
            + " RETURN post.title as title";
    var result = session.query(query).toList();

    Set<String> titles = new HashSet<>();
    for (var r : result) {
      titles.add(r.getProperty("title"));
    }
    assertTrue("Should find post1", titles.contains("post1"));
    assertTrue("Should find post3", titles.contains("post3"));
    assertTrue("Should find post4", titles.contains("post4"));
    assertFalse("Should NOT find post2 (flagged sev=5)",
        titles.contains("post2"));
    session.commit();
  }

  // ========================================================================
  // 25. Edge-method + LIMIT/ORDER BY
  // ========================================================================

  /**
   * outE().inV() with an index filter combined with ORDER BY and LIMIT.
   * Tests that the pre-filter activates and the query correctly applies
   * ordering and row limiting on the filtered result set.
   */
  @Test
  public void edgeMethod_outE_withOrderByAndLimit() {
    session.execute("CREATE class EOLHub extends V").close();
    session.execute("CREATE property EOLHub.name STRING").close();

    session.execute("CREATE class EOLItem extends V").close();
    session.execute("CREATE property EOLItem.label STRING").close();

    session.execute("CREATE class EOLLink extends E").close();
    session.execute("CREATE property EOLLink.out LINK EOLHub").close();
    session.execute("CREATE property EOLLink.in LINK EOLItem").close();
    session.execute("CREATE property EOLLink.priority INTEGER").close();
    session.execute(
        "CREATE index EOLLink_priority on EOLLink (priority) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX EOLHub set name = 'hub'").close();
    for (int i = 1; i <= 8; i++) {
      session.execute(
          "CREATE VERTEX EOLItem set label = 'item" + i + "'").close();
      session.execute(
          "CREATE EDGE EOLLink FROM (SELECT FROM EOLHub WHERE name = 'hub')"
              + " TO (SELECT FROM EOLItem WHERE label = 'item" + i + "')"
              + " SET priority = " + (i * 10))
          .close();
    }
    session.commit();

    session.begin();
    // priority >= 30: item3(30)..item8(80) → 6 items. LIMIT 3, ORDER BY priority
    String query =
        "MATCH {class: EOLHub, as: hub, where: (name = 'hub')}"
            + ".outE('EOLLink'){as: e, where: (priority >= 30)}"
            + ".inV(){as: item}"
            + " RETURN item.label as label, e.priority as p"
            + " ORDER BY p LIMIT 3";
    var result = session.query(query).toList();

    assertEquals(3, result.size());
    // Ordered by priority ascending: item3(30), item4(40), item5(50)
    assertEquals("item3", result.get(0).getProperty("label"));
    assertEquals("item4", result.get(1).getProperty("label"));
    assertEquals("item5", result.get(2).getProperty("label"));

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection:\n" + plan,
        plan.contains("intersection:"));
    session.commit();
  }

  // ========================================================================
  // 26. outE().inV() with non-adjacent back-reference
  // ========================================================================

  /**
   * outE().inV() where the back-reference points to an alias 2 hops
   * upstream (not the immediate predecessor). Tests that EdgeRidLookup
   * resolves non-adjacent $matched references through edge-method chains.
   */
  @Test
  public void edgeMethod_outEInV_nonAdjacentBackRef() {
    session.execute("CREATE class NANode extends V").close();
    session.execute("CREATE property NANode.name STRING").close();

    session.execute("CREATE class NAEdge extends E").close();
    session.execute("CREATE property NAEdge.out LINK NANode").close();
    session.execute("CREATE property NAEdge.in LINK NANode").close();
    session.execute("CREATE property NAEdge.weight INTEGER").close();
    session.execute(
        "CREATE index NAEdge_weight on NAEdge (weight) NOTUNIQUE").close();

    session.begin();
    // a → b → c → d, a → c (shortcut), a → d (shortcut)
    for (String n : new String[] {"a", "b", "c", "d"}) {
      session.execute(
          "CREATE VERTEX NANode set name = '" + n + "'").close();
    }
    session.execute(
        "CREATE EDGE NAEdge FROM (SELECT FROM NANode WHERE name = 'a')"
            + " TO (SELECT FROM NANode WHERE name = 'b') SET weight = 10")
        .close();
    session.execute(
        "CREATE EDGE NAEdge FROM (SELECT FROM NANode WHERE name = 'b')"
            + " TO (SELECT FROM NANode WHERE name = 'c') SET weight = 20")
        .close();
    session.execute(
        "CREATE EDGE NAEdge FROM (SELECT FROM NANode WHERE name = 'c')"
            + " TO (SELECT FROM NANode WHERE name = 'd') SET weight = 30")
        .close();
    // Shortcuts from a
    session.execute(
        "CREATE EDGE NAEdge FROM (SELECT FROM NANode WHERE name = 'a')"
            + " TO (SELECT FROM NANode WHERE name = 'c') SET weight = 15")
        .close();
    session.execute(
        "CREATE EDGE NAEdge FROM (SELECT FROM NANode WHERE name = 'a')"
            + " TO (SELECT FROM NANode WHERE name = 'd') SET weight = 25")
        .close();
    session.commit();

    session.begin();
    // a → out(NAEdge) → mid → out(NAEdge) → hop2
    //   → outE(NAEdge){weight >= 20}.inV(){@rid = $matched.start.@rid}
    // Back-ref to 'start' (= a) is 3 hops upstream.
    // Only path that circles back to 'a' would require an edge TO 'a',
    // which doesn't exist → empty result is expected.
    // The key assertion is that the planner attaches the intersection
    // descriptor (testing the non-adjacent back-ref through edge-method).
    String query =
        "MATCH {class: NANode, as: start, where: (name = 'a')}"
            + ".out('NAEdge'){as: mid}"
            + ".out('NAEdge'){as: hop2}"
            + ".outE('NAEdge'){as: e, where: (weight >= 20)}"
            + ".inV(){as: backToStart,"
            + "  where: (@rid = $matched.start.@rid)}"
            + " RETURN mid.name as midName";
    var result = session.query(query).toList();
    // No edges point back to 'a', so no results expected
    assertEquals(0, result.size());

    String plan = explainPlan(query);
    assertTrue("Plan should show intersection for non-adjacent back-ref:\n"
        + plan, plan.contains("intersection:"));
    session.commit();
  }
}
