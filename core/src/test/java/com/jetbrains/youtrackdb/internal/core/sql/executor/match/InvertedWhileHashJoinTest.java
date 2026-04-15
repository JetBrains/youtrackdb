package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Integration tests for the inverted-WHILE hash join optimization. Tests use a
 * class hierarchy graph that mirrors the LDBC IC12 pattern:
 *
 * <pre>
 *   TagClass hierarchy (IS_SUBCLASS_OF edges):
 *     RootClass
 *       ├─ MiddleClass
 *       │    └─ LeafClass
 *       └─ OtherClass
 *
 *   Tags:
 *     tag1 --HAS_TYPE--> LeafClass      (reachable from RootClass)
 *     tag2 --HAS_TYPE--> OtherClass     (reachable from RootClass)
 *     tag3 --HAS_TYPE--> UnrelatedClass (NOT reachable from RootClass)
 * </pre>
 */
public class InvertedWhileHashJoinTest extends DbTestBase {

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.execute("CREATE class TagClass extends V").close();
    session.execute("CREATE class Tag extends V").close();
    session.execute("CREATE class IS_SUBCLASS_OF extends E").close();
    session.execute("CREATE PROPERTY IS_SUBCLASS_OF.out LINK TagClass").close();
    session.execute("CREATE PROPERTY IS_SUBCLASS_OF.in LINK TagClass").close();
    session.execute("CREATE class HAS_TYPE extends E").close();
    session.execute("CREATE PROPERTY HAS_TYPE.out LINK Tag").close();
    session.execute("CREATE PROPERTY HAS_TYPE.in LINK TagClass").close();

    session.begin();
    // Class hierarchy: LeafClass -> MiddleClass -> RootClass, OtherClass -> RootClass
    session.execute("CREATE VERTEX TagClass set name = 'RootClass'").close();
    session.execute("CREATE VERTEX TagClass set name = 'MiddleClass'").close();
    session.execute("CREATE VERTEX TagClass set name = 'LeafClass'").close();
    session.execute("CREATE VERTEX TagClass set name = 'OtherClass'").close();
    session.execute("CREATE VERTEX TagClass set name = 'UnrelatedClass'").close();

    // IS_SUBCLASS_OF edges: child -> parent (out direction = toward parent)
    session.execute(
        "CREATE EDGE IS_SUBCLASS_OF from (select from TagClass where name='LeafClass')"
            + " to (select from TagClass where name='MiddleClass')")
        .close();
    session.execute(
        "CREATE EDGE IS_SUBCLASS_OF from (select from TagClass where name='MiddleClass')"
            + " to (select from TagClass where name='RootClass')")
        .close();
    session.execute(
        "CREATE EDGE IS_SUBCLASS_OF from (select from TagClass where name='OtherClass')"
            + " to (select from TagClass where name='RootClass')")
        .close();

    // Tags with HAS_TYPE edges
    session.execute("CREATE VERTEX Tag set name = 'tag1'").close();
    session.execute("CREATE VERTEX Tag set name = 'tag2'").close();
    session.execute("CREATE VERTEX Tag set name = 'tag3'").close();

    session.execute(
        "CREATE EDGE HAS_TYPE from (select from Tag where name='tag1')"
            + " to (select from TagClass where name='LeafClass')")
        .close();
    session.execute(
        "CREATE EDGE HAS_TYPE from (select from Tag where name='tag2')"
            + " to (select from TagClass where name='OtherClass')")
        .close();
    session.execute(
        "CREATE EDGE HAS_TYPE from (select from Tag where name='tag3')"
            + " to (select from TagClass where name='UnrelatedClass')")
        .close();
    session.commit();
  }

  /**
   * EXPLAIN should show INVERTED WHILE HASH JOIN for the IS_SUBCLASS_OF edge.
   */
  @Test
  public void explain_whileEdge_usesInvertedWhileHashJoin() {
    session.begin();
    var result = session.query(
        "EXPLAIN MATCH {class:Tag, as:tag}"
            + ".out('HAS_TYPE'){as:directClass}"
            + ".out('IS_SUBCLASS_OF'){while: (true),"
            + " where: (name = 'RootClass'), as: matchedClass}"
            + " RETURN tag.name as tagName, matchedClass.name as className")
        .toList();
    assertEquals(1, result.size());
    String plan = (String) result.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("plan should use INVERTED WHILE HASH JOIN, got:\n" + plan,
        plan.contains("INVERTED WHILE HASH JOIN"));
    session.commit();
  }

  /**
   * Tags whose HAS_TYPE class is a descendant of RootClass should match.
   * tag1 (LeafClass → MiddleClass → RootClass) and tag2 (OtherClass → RootClass)
   * should be found. tag3 (UnrelatedClass) should be filtered out.
   */
  @Test
  public void whileHierarchy_correctResults() {
    session.begin();
    var result = session.query(
        "MATCH {class:Tag, as:tag}"
            + ".out('HAS_TYPE'){as:directClass}"
            + ".out('IS_SUBCLASS_OF'){while: (true),"
            + " where: (name = 'RootClass'), as: matchedClass}"
            + " RETURN tag.name as tagName, matchedClass.name as className")
        .toList();

    assertFalse("should return results", result.isEmpty());
    var tagNames = result.stream()
        .map(r -> (String) r.getProperty("tagName"))
        .collect(Collectors.toSet());
    // tag1 and tag2 are reachable from RootClass, tag3 is not
    assertEquals(Set.of("tag1", "tag2"), tagNames);
    // matchedClass should always be RootClass (the anchor)
    for (var row : result) {
      assertEquals("RootClass", row.getProperty("className"));
    }
    session.commit();
  }

  /**
   * Filter to MiddleClass — only tag1 (LeafClass → MiddleClass) should match.
   * tag2 (OtherClass → RootClass, NOT through MiddleClass) should be excluded.
   */
  @Test
  public void whileHierarchy_middleClassFilter() {
    session.begin();
    var result = session.query(
        "MATCH {class:Tag, as:tag}"
            + ".out('HAS_TYPE'){as:directClass}"
            + ".out('IS_SUBCLASS_OF'){while: (true),"
            + " where: (name = 'MiddleClass'), as: matchedClass}"
            + " RETURN tag.name as tagName")
        .toList();

    assertEquals(1, result.size());
    assertEquals("tag1", result.getFirst().getProperty("tagName"));
    session.commit();
  }

  /**
   * Anchor class not found — no rows should be returned.
   */
  @Test
  public void whileHierarchy_anchorNotFound_emptyResult() {
    session.begin();
    var result = session.query(
        "MATCH {class:Tag, as:tag}"
            + ".out('HAS_TYPE'){as:directClass}"
            + ".out('IS_SUBCLASS_OF'){while: (true),"
            + " where: (name = 'NonExistentClass'), as: matchedClass}"
            + " RETURN tag.name as tagName")
        .toList();

    assertTrue("no tag should match a non-existent class", result.isEmpty());
    session.commit();
  }

  /**
   * Direct class IS the anchor — single-level hierarchy should work.
   * Tag whose directClass name matches the anchor directly.
   */
  @Test
  public void whileHierarchy_directClassIsAnchor() {
    session.begin();
    var result = session.query(
        "MATCH {class:Tag, as:tag}"
            + ".out('HAS_TYPE'){as:directClass}"
            + ".out('IS_SUBCLASS_OF'){while: (true),"
            + " where: (name = 'LeafClass'), as: matchedClass}"
            + " RETURN tag.name as tagName")
        .toList();

    // tag1's directClass IS LeafClass → it IS the anchor itself
    assertEquals(1, result.size());
    assertEquals("tag1", result.getFirst().getProperty("tagName"));
    session.commit();
  }

  /**
   * Multiple anchor vertices with the same name — verifies the WHILE hash
   * join correctly traverses all matching anchor nodes. Creates a second
   * TagClass hierarchy (RootClass2 → MiddleClass2 → LeafClass2) with a
   * tag attached to LeafClass2, then queries for the common ancestor name
   * pattern that matches both RootClass and RootClass2.
   *
   * This exercises the multi-row build side of the inverted WHILE hash join
   * where more than one anchor vertex is found.
   */
  @Test
  public void whileHierarchy_multipleAnchors_bothHierarchiesTraversed() {
    session.begin();
    // Create a second hierarchy: LeafClass2 → MiddleClass2 → RootClass2
    session.execute("CREATE VERTEX TagClass set name = 'RootClass2'").close();
    session.execute("CREATE VERTEX TagClass set name = 'MiddleClass2'").close();
    session.execute("CREATE VERTEX TagClass set name = 'LeafClass2'").close();

    session.execute(
        "CREATE EDGE IS_SUBCLASS_OF"
            + " from (select from TagClass where name='LeafClass2')"
            + " to (select from TagClass where name='MiddleClass2')")
        .close();
    session.execute(
        "CREATE EDGE IS_SUBCLASS_OF"
            + " from (select from TagClass where name='MiddleClass2')"
            + " to (select from TagClass where name='RootClass2')")
        .close();

    // tag4 → LeafClass2 (reachable from RootClass2)
    session.execute("CREATE VERTEX Tag set name = 'tag4'").close();
    session.execute(
        "CREATE EDGE HAS_TYPE from (select from Tag where name='tag4')"
            + " to (select from TagClass where name='LeafClass2')")
        .close();

    // Query for tags whose type hierarchy reaches any class named like 'Root%'
    // Using name LIKE 'RootClass%' to match both RootClass and RootClass2
    var result = session.query(
        "MATCH {class:Tag, as:tag}"
            + ".out('HAS_TYPE'){as:directClass}"
            + ".out('IS_SUBCLASS_OF'){while: (true),"
            + " where: (name LIKE 'RootClass%'), as: matchedClass}"
            + " RETURN tag.name as tagName, matchedClass.name as className")
        .toList();

    assertFalse("should return results from both hierarchies",
        result.isEmpty());
    var tagNames = result.stream()
        .map(r -> (String) r.getProperty("tagName"))
        .collect(Collectors.toSet());
    // tag1 (LeafClass → MiddleClass → RootClass),
    // tag2 (OtherClass → RootClass),
    // tag4 (LeafClass2 → MiddleClass2 → RootClass2)
    assertTrue("tag1 should be found via RootClass", tagNames.contains("tag1"));
    assertTrue("tag2 should be found via RootClass", tagNames.contains("tag2"));
    assertTrue("tag4 should be found via RootClass2",
        tagNames.contains("tag4"));
    session.rollback();
  }

  /**
   * Regression test for unbounded anchor collection in findAnchorVertices.
   * With threshold=1, the anchor filter {@code name LIKE 'RootClass%'} matches
   * 2 anchors (RootClass and RootClass2), exceeding the threshold. The step
   * must fall back to per-row WHILE traversal.
   *
   * <p>Without fallback, only one anchor's hierarchy would be traversed,
   * missing tags from the other hierarchy. The test asserts tags from BOTH
   * hierarchies are present, so truncation without fallback would fail.
   */
  @Test
  public void whileHierarchy_anchorExceedsThreshold_fallsBackCorrectly() {
    var saved = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    try {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(1L);
      session.begin();
      // Create a second hierarchy: LeafClass2 → MiddleClass2 → RootClass2
      session.execute("CREATE VERTEX TagClass set name = 'RootClass2'").close();
      session.execute("CREATE VERTEX TagClass set name = 'MiddleClass2'").close();
      session.execute("CREATE VERTEX TagClass set name = 'LeafClass2'").close();

      session.execute(
          "CREATE EDGE IS_SUBCLASS_OF"
              + " from (select from TagClass where name='LeafClass2')"
              + " to (select from TagClass where name='MiddleClass2')")
          .close();
      session.execute(
          "CREATE EDGE IS_SUBCLASS_OF"
              + " from (select from TagClass where name='MiddleClass2')"
              + " to (select from TagClass where name='RootClass2')")
          .close();

      // tag4 → LeafClass2 (reachable from RootClass2 only)
      session.execute("CREATE VERTEX Tag set name = 'tag4'").close();
      session.execute(
          "CREATE EDGE HAS_TYPE from (select from Tag where name='tag4')"
              + " to (select from TagClass where name='LeafClass2')")
          .close();

      // Anchor filter matches RootClass AND RootClass2 — 2 anchors > threshold of 1
      var result = session.query(
          "MATCH {class:Tag, as:tag}"
              + ".out('HAS_TYPE'){as:directClass}"
              + ".out('IS_SUBCLASS_OF'){while: (true),"
              + " where: (name LIKE 'RootClass%'), as: matchedClass}"
              + " RETURN tag.name as tagName, matchedClass.name as className")
          .toList();

      // Must find tags from BOTH hierarchies — truncation at 1 anchor would
      // miss one hierarchy, causing at least one of these assertions to fail.
      var tagNames = result.stream()
          .map(r -> (String) r.getProperty("tagName"))
          .collect(Collectors.toSet());
      assertEquals(Set.of("tag1", "tag2", "tag4"), tagNames);
      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(saved);
    }
  }
}
