package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Integration tests for class inference correctness and adaptive abort behavior
 * in edge-method MATCH patterns.
 *
 * <p>Class inference tests verify that the planner correctly resolves edge and
 * vertex classes from edge-method patterns, affecting scheduling order.
 *
 * <p>Adaptive abort tests verify that when the pre-filter configuration knobs
 * are set to restrictive values, the pre-filter is skipped and the query falls
 * back to unfiltered traversal while still producing correct results.
 *
 * <p>Marked as {@link SequentialTest} because the adaptive abort tests mutate
 * {@link GlobalConfiguration} settings.
 */
@Category(SequentialTest.class)
public class MatchEdgeMethodInferenceAndAbortTest extends DbTestBase {

  @After
  public void restoreDefaults() {
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(100_000);
    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.8);
    GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE.setValue(50);
  }

  // ---- Class inference: scheduling order ----

  /**
   * Verify that the planner infers the edge class for outE('WORK_AT') aliases
   * and uses index selectivity to schedule more selective aliases first.
   *
   * <p>Graph: 10 persons, 2 companies, 200 tags. Each person works at one
   * company (selective) and has 20 tags (broad). The query has two branches
   * from person: one selective (WORK_AT with WHERE) and one broad (HAS_TAG).
   * The planner should schedule the selective WORK_AT branch first.
   */
  @Test
  public void testEdgeAliasSchedulingOrder() {
    // Schema
    session.execute("CREATE class SOPerson extends V").close();
    session.execute("CREATE property SOPerson.name STRING").close();

    session.execute("CREATE class SOCompany extends V").close();
    session.execute("CREATE property SOCompany.name STRING").close();

    session.execute("CREATE class SOTag extends V").close();
    session.execute("CREATE property SOTag.name STRING").close();

    session.execute("CREATE class SOWorkAt extends E").close();
    session.execute("CREATE property SOWorkAt.out LINK SOPerson").close();
    session.execute("CREATE property SOWorkAt.in LINK SOCompany").close();
    session.execute("CREATE property SOWorkAt.workFrom INTEGER").close();
    session.execute(
        "CREATE index SOWorkAt_workFrom on SOWorkAt (workFrom) NOTUNIQUE")
        .close();

    session.execute("CREATE class SOHasTag extends E").close();
    session.execute("CREATE property SOHasTag.out LINK SOPerson").close();
    session.execute("CREATE property SOHasTag.in LINK SOTag").close();

    session.begin();
    // 2 companies
    session.execute("CREATE VERTEX SOCompany set name = 'corpA'").close();
    session.execute("CREATE VERTEX SOCompany set name = 'corpB'").close();

    // 200 tags
    for (int i = 0; i < 200; i++) {
      session.execute("CREATE VERTEX SOTag set name = 'tag" + i + "'").close();
    }

    // 10 persons: each works at one company and has 20 tags
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX SOPerson set name = 'person" + i + "'")
          .close();
      session.execute(
          "CREATE EDGE SOWorkAt FROM"
              + " (SELECT FROM SOPerson WHERE name = 'person" + i + "')"
              + " TO (SELECT FROM SOCompany WHERE name = '"
              + (i < 5 ? "corpA" : "corpB") + "')"
              + " SET workFrom = " + (2010 + i))
          .close();
      for (int j = 0; j < 20; j++) {
        session.execute(
            "CREATE EDGE SOHasTag FROM"
                + " (SELECT FROM SOPerson WHERE name = 'person" + i + "')"
                + " TO (SELECT FROM SOTag WHERE name = 'tag"
                + (i * 20 + j) + "')")
            .close();
      }
    }
    session.commit();

    // Query: two branches — selective WORK_AT and broad HAS_TAG
    session.begin();
    var query =
        "MATCH {class: SOPerson, as: person}"
            + ".outE('SOWorkAt'){as: workEdge, where: (workFrom = 2015)}"
            + ".inV(){as: company},"
            + " {as: person}"
            + ".out('SOHasTag'){as: tag}"
            + " RETURN person.name, company.name, tag.name";

    var explainResult = session.query("EXPLAIN " + query).toList();
    assertEquals(1, explainResult.size());
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    // The selective WORK_AT edge alias {workEdge} should appear before
    // the broad HAS_TAG alias {tag} in the plan
    int workEdgePos = plan.indexOf("{workEdge}");
    int tagPos = plan.indexOf("{tag}");
    assertTrue("workEdge alias should appear in plan", workEdgePos >= 0);
    assertTrue("tag alias should appear in plan", tagPos >= 0);
    assertTrue(
        "Selective workEdge should be scheduled before broad tag,"
            + " but plan was:\n" + plan,
        workEdgePos < tagPos);

    // Also verify the query produces correct results
    var result = session.query(query).toList();
    // person5 has workFrom=2015, and has 20 tags -> 20 result rows
    assertEquals(20, result.size());
    for (var r : result) {
      assertEquals("person5", r.getProperty("person.name"));
      assertEquals("corpB", r.getProperty("company.name"));
    }

    session.commit();
  }

  /**
   * Verify that the planner infers the vertex class for inV() from the edge
   * schema and applies index intersection, even without an explicit class:
   * constraint on the vertex alias. Similar to the existing
   * {@code testSelectivityInferredFromEdgeSchemaWithoutExplicitClass} but
   * using edge-method patterns (outE→inV instead of out).
   *
   * <p>Graph: posts with broad tags and a single selective tag. The selective
   * branch uses {@code name = 'targetTag'} which has an index on VITag.name.
   * The planner must: (1) infer VITag from VIHasTag.in LINK, and (2) use the
   * VITag_name index for intersection pre-filtering.
   */
  @Test
  public void testVertexClassInferenceEnablesIndexIntersection() {
    // Schema
    session.execute("CREATE class VIPost extends V").close();
    session.execute("CREATE property VIPost.title STRING").close();

    session.execute("CREATE class VITag extends V").close();
    session.execute("CREATE property VITag.name STRING").close();
    session.execute(
        "CREATE index VITag_name on VITag (name) NOTUNIQUE").close();

    session.execute("CREATE class VIHasTag extends E").close();
    session.execute("CREATE property VIHasTag.out LINK VIPost").close();
    session.execute("CREATE property VIHasTag.in LINK VITag").close();

    session.begin();
    // 1 selective tag + 50 broad tags
    session.execute("CREATE VERTEX VITag set name = 'targetTag'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX VITag set name = 'tag" + i + "'").close();
    }

    // 10 posts, each linked to targetTag and 5 other tags
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX VIPost set title = 'post" + i + "'")
          .close();
      session.execute(
          "CREATE EDGE VIHasTag FROM"
              + " (SELECT FROM VIPost WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM VITag WHERE name = 'targetTag')")
          .close();
      for (int j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE VIHasTag FROM"
                + " (SELECT FROM VIPost WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM VITag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    // No explicit class: on tag aliases — planner should infer VITag
    // from VIHasTag.in LINK VITag
    session.begin();
    var query =
        "MATCH {class: VIPost, as: post}"
            + ".outE('VIHasTag').inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: post}"
            + ".outE('VIHasTag').inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    // Verify correct results
    var result = session.query(query).toList();
    assertEquals(50, result.size());

    // Verify EXPLAIN shows index intersection on the selective tag alias,
    // proving the planner inferred VITag class and selected the index
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    // Both aliases should appear in the plan
    assertTrue(
        "selectiveTag should appear in plan, but plan was:\n" + plan,
        plan.contains("{selectiveTag}"));
    assertTrue(
        "broadTag should appear in plan, but plan was:\n" + plan,
        plan.contains("{broadTag}"));

    // The index intersection proves class inference worked end-to-end:
    // the planner inferred VITag from VIHasTag.in LINK, found the
    // VITag_name index, and attached it as an intersection descriptor
    assertTrue(
        "Plan should show index intersection for VITag_name (proves class"
            + " inference), but plan was:\n" + plan,
        plan.contains("(intersection: index VITag_name)"));

    session.commit();
  }

  // ---- Adaptive abort ----

  /**
   * Verify that when {@code QUERY_PREFILTER_MAX_RIDSET_SIZE} is set to 1,
   * the pre-filter is skipped (because the index returns more than 1 RID)
   * but the query still produces correct results via unfiltered scan.
   *
   * <p>With maxRidSetSize=1, any index lookup returning more than 1 RID
   * triggers the adaptive abort guard in {@code TraversalPreFilterHelper
   * .resolveIndexToRidSet()}, causing it to return null. The traverser
   * then falls back to loading all edges without filtering.
   */
  @Test
  public void testAdaptiveAbortSkipsPreFilterWithSmallRidSetCap() {
    // Schema with indexed edge property
    session.execute("CREATE class AAPerson extends V").close();
    session.execute("CREATE property AAPerson.name STRING").close();

    session.execute("CREATE class AACompany extends V").close();
    session.execute("CREATE property AACompany.name STRING").close();

    session.execute("CREATE class AAWorkAt extends E").close();
    session.execute("CREATE property AAWorkAt.out LINK AAPerson").close();
    session.execute("CREATE property AAWorkAt.in LINK AACompany").close();
    session.execute("CREATE property AAWorkAt.workFrom INTEGER").close();
    session.execute(
        "CREATE index AAWorkAt_workFrom on AAWorkAt (workFrom) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX AACompany set name = 'corp'").close();
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX AAPerson set name = 'emp" + i + "'")
          .close();
      session.execute(
          "CREATE EDGE AAWorkAt FROM"
              + " (SELECT FROM AAPerson WHERE name = 'emp" + i + "')"
              + " TO (SELECT FROM AACompany WHERE name = 'corp')"
              + " SET workFrom = " + (2010 + i))
          .close();
    }
    session.commit();

    // Override maxRidSetSize to 1 — any index lookup with > 1 result aborts
    GlobalConfiguration.QUERY_PREFILTER_MAX_RIDSET_SIZE.setValue(1);

    session.begin();
    var query =
        "MATCH {class: AAPerson, as: p}"
            + ".outE('AAWorkAt'){where: (workFrom < 2015)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var result = session.query(query).toList();

    // The query should still return correct results even without pre-filter
    assertEquals(5, result.size());

    Set<String> names = new HashSet<>();
    for (var r : result) {
      names.add(r.getProperty("p.name"));
    }
    for (int i = 0; i < 5; i++) {
      assertTrue(
          "Should contain emp" + i,
          names.contains("emp" + i));
    }

    session.commit();
  }

  /**
   * Verify that when {@code QUERY_PREFILTER_MIN_LINKBAG_SIZE} is set to a
   * very large value (larger than any link bag in the graph), the pre-filter
   * is skipped for all vertices but correct results are still produced.
   *
   * <p>This tests the min-linkbag-size guard: link bags smaller than the
   * threshold skip pre-filtering entirely because loading a few records
   * is cheaper than building a RidSet.
   */
  @Test
  public void testAdaptiveAbortSkipsPreFilterWithLargeMinLinkBagSize() {
    // Schema with indexed edge property
    session.execute("CREATE class MLPerson extends V").close();
    session.execute("CREATE property MLPerson.name STRING").close();

    session.execute("CREATE class MLForum extends V").close();
    session.execute("CREATE property MLForum.title STRING").close();

    session.execute("CREATE class MLHasMember extends E").close();
    session.execute("CREATE property MLHasMember.out LINK MLPerson").close();
    session.execute("CREATE property MLHasMember.in LINK MLForum").close();
    session.execute("CREATE property MLHasMember.joinDate LONG").close();
    session.execute(
        "CREATE index MLHasMember_joinDate on MLHasMember (joinDate) NOTUNIQUE")
        .close();

    session.begin();
    // 3 forums, each with several members
    for (int i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX MLForum set title = 'forum" + i + "'")
          .close();
    }
    for (int i = 0; i < 6; i++) {
      session.execute("CREATE VERTEX MLPerson set name = 'user" + i + "'")
          .close();
      session.execute(
          "CREATE EDGE MLHasMember FROM"
              + " (SELECT FROM MLPerson WHERE name = 'user" + i + "')"
              + " TO (SELECT FROM MLForum WHERE title = 'forum" + (i % 3) + "')"
              + " SET joinDate = " + (1000 + i * 100))
          .close();
    }
    session.commit();

    // Override minLinkBagSize to 999999 — all link bags are too small
    GlobalConfiguration.QUERY_PREFILTER_MIN_LINKBAG_SIZE.setValue(999999);

    session.begin();
    var query =
        "MATCH {class: MLForum, as: f}"
            + ".inE('MLHasMember'){where: (joinDate >= 1400)}"
            + ".outV(){as: p}"
            + " RETURN p.name, f.title";
    var result = session.query(query).toList();

    // user4 (1400) and user5 (1500) match
    assertEquals(2, result.size());

    Set<String> names = new HashSet<>();
    for (var r : result) {
      names.add(r.getProperty("p.name"));
    }
    assertEquals(Set.of("user4", "user5"), names);

    session.commit();
  }

  /**
   * Verify that when {@code QUERY_PREFILTER_MAX_SELECTIVITY_RATIO} is set
   * very low (e.g., 0.01), the pre-filter is skipped because the ratio of
   * matching RIDs to link bag size exceeds the threshold, but correct results
   * are still produced.
   */
  @Test
  public void testAdaptiveAbortSkipsPreFilterWithLowSelectivityRatio() {
    // Schema with indexed edge property
    session.execute("CREATE class SRPerson extends V").close();
    session.execute("CREATE property SRPerson.name STRING").close();

    session.execute("CREATE class SRCompany extends V").close();
    session.execute("CREATE property SRCompany.name STRING").close();

    session.execute("CREATE class SRWorkAt extends E").close();
    session.execute("CREATE property SRWorkAt.out LINK SRPerson").close();
    session.execute("CREATE property SRWorkAt.in LINK SRCompany").close();
    session.execute("CREATE property SRWorkAt.workFrom INTEGER").close();
    session.execute(
        "CREATE index SRWorkAt_workFrom on SRWorkAt (workFrom) NOTUNIQUE")
        .close();

    session.begin();
    session.execute("CREATE VERTEX SRCompany set name = 'megacorp'").close();
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX SRPerson set name = 'dev" + i + "'")
          .close();
      session.execute(
          "CREATE EDGE SRWorkAt FROM"
              + " (SELECT FROM SRPerson WHERE name = 'dev" + i + "')"
              + " TO (SELECT FROM SRCompany WHERE name = 'megacorp')"
              + " SET workFrom = " + (2010 + i))
          .close();
    }
    session.commit();

    // Override selectivity ratio to 0.01 — almost no filter is selective enough
    GlobalConfiguration.QUERY_PREFILTER_MAX_SELECTIVITY_RATIO.setValue(0.01);

    session.begin();
    var query =
        "MATCH {class: SRPerson, as: p}"
            + ".outE('SRWorkAt'){where: (workFrom < 2013)}"
            + ".inV(){as: c}"
            + " RETURN p.name, c.name";
    var result = session.query(query).toList();

    // dev0 (2010), dev1 (2011), dev2 (2012) match
    assertEquals(3, result.size());

    Set<String> names = new HashSet<>();
    for (var r : result) {
      names.add(r.getProperty("p.name"));
    }
    assertEquals(Set.of("dev0", "dev1", "dev2"), names);

    session.commit();
  }
}
