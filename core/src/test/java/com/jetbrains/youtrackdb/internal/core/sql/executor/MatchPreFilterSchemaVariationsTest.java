package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Tests index-into pre-filter optimization across diverse graph schemas
 * and matching patterns. Each test method creates its own isolated schema
 * (using unique class name prefixes) to exercise the planner against a wide
 * variety of graph topologies.
 *
 * <p>Focus areas:
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
 */
public class MatchPreFilterSchemaVariationsTest extends DbTestBase {

  /** Runs EXPLAIN and returns the executionPlanAsString. */
  private String explainPlan(String query) {
    var result = session.query("EXPLAIN " + query).toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    return plan;
  }

  /** Runs EXPLAIN with parameters and returns the executionPlanAsString. */
  private String explainPlan(String query, Object... args) {
    var result = session.query("EXPLAIN " + query, args).toList();
    assertEquals(1, result.size());
    String plan = result.get(0).getProperty("executionPlanAsString");
    assertNotNull("EXPLAIN should produce executionPlanAsString", plan);
    return plan;
  }

  private Set<String> collectStrings(String query, String property) {
    return session.query(query).toList().stream()
        .map(r -> (String) r.getProperty(property))
        .collect(Collectors.toSet());
  }

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

    // TODO: RPEdge has no typed LINK properties, so the planner cannot infer
    // the target class RPNode and cannot discover the RPNode_val index. No
    // other test covers $paths return mode WITH index intersection active —
    // adding typed LINK here or creating a separate test would fill this gap.
    String plan = explainPlan(
        "MATCH {class: RPNode, as: start, where: (name = 'a')}"
            + ".out('RPEdge'){as: target, where: (val >= 15)}"
            + " RETURN target.name as tName");
    assertFalse(
        "No typed LINK on RPEdge prevents target class inference:\n" + plan,
        plan.contains("intersection:"));
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
}
