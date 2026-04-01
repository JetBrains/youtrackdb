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
    session.execute("CREATE class HAS_TYPE extends E").close();

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
    String plan = (String) result.get(0).getProperty("executionPlanAsString");
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
    assertEquals("tag1", result.get(0).getProperty("tagName"));
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
    assertEquals("tag1", result.get(0).getProperty("tagName"));
    session.commit();
  }
}
