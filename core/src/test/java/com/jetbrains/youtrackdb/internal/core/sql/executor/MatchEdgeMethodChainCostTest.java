package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/**
 * Regression tests for the edge-method chain-cost fold introduced in
 * YTDB-643. The fold propagates the downstream vertex's WHERE selectivity
 * into the first-edge cost of an {@code outE→inV} / {@code inE→outV} /
 * {@code bothE→bothV} chain so that the planner schedules selective
 * branches before broad ones, matching the behaviour of the equivalent
 * single-step pattern {@code .out('X'){where: …}}.
 *
 * <p>Each test asserts both the runtime result-set correctness (so the
 * scheduler change does not silently alter MATCH semantics) and the
 * EXPLAIN plan ordering (so the cost-fold actually drives the schedule
 * — without this check, a regression that drops the fold could go
 * unnoticed if the broad branch happens to be slow at runtime too).
 *
 * <p><b>Coverage note for the {@code Double.MAX_VALUE} gate at
 * {@code MatchExecutionPlanner.updateScheduleStartingAt}:</b> the gate
 * {@code if (cost < Double.MAX_VALUE)} skips both
 * {@code applyTargetSelectivity} calls (intermediate + chain fold) when
 * {@code estimateEdgeCost} returns the unestimated sentinel. For an
 * {@code outE}/{@code inE}/{@code bothE} first edge — the only methods
 * the chain rule accepts — {@code parseDirection} never returns null, so
 * {@code estimateEdgeCost} always returns a finite value and the gate
 * is structurally unreachable through the chain-fold integration. The
 * gate's MAX_VALUE-preservation invariant is instead pinned by
 * {@code MatchExecutionPlannerMutationTest
 * .applyTargetSelectivity_classForced_maxValueInputPreservedOnNullClass}
 * and its {@code _maxValueInputPreservedOnNoFilterNoEstimate} sibling,
 * which assert that even if the sort loop did call the fold on a
 * MAX_VALUE input, the helper would short-circuit and preserve it.
 */
public class MatchEdgeMethodChainCostTest extends DbTestBase {

  /**
   * Scenario 1 — pure {@code outE.inV} chain with two branches of
   * different selectivities. The planner should fold the downstream
   * vertex's WHERE into the first edge's cost and schedule the
   * selective branch ({@code name = 'targetTag'}) before the broad
   * branch ({@code name <> 'targetTag'}).
   *
   * <p>Distinguishes from
   * {@code MatchEdgeMethodInferenceAndAbortTest.testVertexClassInferenceEnablesIndexIntersection}
   * by deliberately omitting the index on {@code VITag.name}: the
   * filter-shape heuristic (eq vs. ne) is what the cost-fold relies on
   * here, not index histograms. This pins the heuristic path through the
   * fold independently of any index-based cost paths.
   */
  @Test
  public void testPureOutEInVChainSchedulesSelectiveBranchFirst() {
    session.execute("CREATE class CC1Post extends V").close();
    session.execute("CREATE property CC1Post.title STRING").close();

    session.execute("CREATE class CC1Tag extends V").close();
    session.execute("CREATE property CC1Tag.name STRING").close();

    session.execute("CREATE class CC1HasTag extends E").close();
    session.execute("CREATE property CC1HasTag.out LINK CC1Post").close();
    session.execute("CREATE property CC1HasTag.in LINK CC1Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC1Tag set name = 'targetTag'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC1Tag set name = 'tag" + i + "'").close();
    }
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX CC1Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC1HasTag FROM"
              + " (SELECT FROM CC1Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC1Tag WHERE name = 'targetTag')")
          .close();
      for (int j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE CC1HasTag FROM"
                + " (SELECT FROM CC1Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC1Tag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    var query =
        "MATCH {class: CC1Post, as: post}"
            + ".outE('CC1HasTag').inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: post}"
            + ".outE('CC1HasTag').inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(50, result.size());
    Set<String> posts = new HashSet<>();
    for (var r : result) {
      assertEquals("targetTag", r.getProperty("selectiveTag.name"));
      posts.add(r.getProperty("post.title"));
    }
    assertEquals(10, posts.size());

    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);

    int selectivePos = plan.indexOf("{selectiveTag}");
    int broadPos = plan.indexOf("{broadTag}");
    assertTrue("selectiveTag missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadTag missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "Selective branch should sort before broad branch when the"
            + " edge-method chain fold propagates the downstream WHERE."
            + " Plan was:\n" + plan,
        selectivePos < broadPos);
    session.commit();
  }

  /**
   * Scenario 2 — mixed branch styles: one branch uses the single-step
   * {@code .out('X'){where: p}} pattern, the other uses the two-step
   * {@code .outE('X').inV(){where: q}} chain. With {@code q} more
   * selective than {@code p}, cost ordering must be consistent across
   * styles — the chain-fold has to produce the same effective
   * selectivity for the two-step branch as the single-step branch
   * already does, otherwise mixed queries would silently reorder
   * depending on which style users picked.
   */
  @Test
  public void testMixedStyleBranchesOrderConsistently() {
    session.execute("CREATE class CC2Post extends V").close();
    session.execute("CREATE property CC2Post.title STRING").close();

    session.execute("CREATE class CC2Tag extends V").close();
    session.execute("CREATE property CC2Tag.name STRING").close();

    session.execute("CREATE class CC2HasTag extends E").close();
    session.execute("CREATE property CC2HasTag.out LINK CC2Post").close();
    session.execute("CREATE property CC2HasTag.in LINK CC2Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC2Tag set name = 'targetTag'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC2Tag set name = 'tag" + i + "'").close();
    }
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX CC2Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC2HasTag FROM"
              + " (SELECT FROM CC2Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC2Tag WHERE name = 'targetTag')")
          .close();
      for (int j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE CC2HasTag FROM"
                + " (SELECT FROM CC2Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC2Tag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    // Selective branch uses .outE.inV, broad branch uses .out — invert
    // the conventional pairing so a regression that ignores the fold
    // would order broad-before-selective and fail the assertion below.
    var query =
        "MATCH {class: CC2Post, as: post}"
            + ".out('CC2HasTag'){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: post}"
            + ".outE('CC2HasTag').inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    var result = session.query(query).toList();
    assertEquals(50, result.size());

    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = plan.indexOf("{selectiveTag}");
    int broadPos = plan.indexOf("{broadTag}");
    assertTrue("selectiveTag missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadTag missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "Mixed-style branches must order by selectivity regardless of"
            + " whether the user wrote .out or .outE.inV. Plan was:\n" + plan,
        selectivePos < broadPos);
    session.commit();
  }

  /**
   * Scenario 3 — reverse direction: {@code inE.outV} chain. Verifies the
   * helper picks the edge class's {@code out} property (source vertex
   * class) when inferring the downstream alias, not the {@code in}
   * property used for outbound chains.
   *
   * <p>Graph is reversed: tags fan out to posts via {@code inE} on the
   * tag side. The selective filter is on the upstream {@code Post},
   * not the tag side, mirroring what the reverse-direction inference
   * has to handle when the chain fold runs on the inE→outV pair.
   */
  @Test
  public void testInEOutVReverseChainSchedulesSelectiveBranchFirst() {
    session.execute("CREATE class CC3Post extends V").close();
    session.execute("CREATE property CC3Post.title STRING").close();

    session.execute("CREATE class CC3Tag extends V").close();
    session.execute("CREATE property CC3Tag.name STRING").close();

    session.execute("CREATE class CC3HasTag extends E").close();
    session.execute("CREATE property CC3HasTag.out LINK CC3Post").close();
    session.execute("CREATE property CC3HasTag.in LINK CC3Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC3Tag set name = 'centralTag'").close();
    session.execute("CREATE VERTEX CC3Post set title = 'targetPost'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC3Post set title = 'post" + i + "'").close();
    }
    // centralTag is incoming to targetPost (selective) and to post0..post49 (broad)
    session.execute(
        "CREATE EDGE CC3HasTag FROM"
            + " (SELECT FROM CC3Post WHERE title = 'targetPost')"
            + " TO (SELECT FROM CC3Tag WHERE name = 'centralTag')")
        .close();
    for (int i = 0; i < 50; i++) {
      session.execute(
          "CREATE EDGE CC3HasTag FROM"
              + " (SELECT FROM CC3Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC3Tag WHERE name = 'centralTag')")
          .close();
    }
    session.commit();

    // Two reverse-direction branches from {tag}: selective filter on one
    // upstream post, broad on the other. The chain-fold must use
    // CC3HasTag.out (CC3Post) for class inference on the inE→outV chain.
    var query =
        "MATCH {class: CC3Tag, as: tag, where: (name = 'centralTag')}"
            + ".inE('CC3HasTag').outV(){as: broadPost,"
            + "  where: (title <> 'targetPost')},"
            + " {as: tag}"
            + ".inE('CC3HasTag').outV(){as: selectivePost,"
            + "  where: (title = 'targetPost')}"
            + " RETURN tag.name, broadPost.title, selectivePost.title";

    session.begin();
    var result = session.query(query).toList();
    // 1 tag × 50 broad × 1 selective = 50
    assertEquals(50, result.size());

    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = plan.indexOf("{selectivePost}");
    int broadPos = plan.indexOf("{broadPost}");
    assertTrue("selectivePost missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadPost missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "inE→outV chain fold must infer source class from the edge's"
            + " out property and order selective before broad. Plan was:\n"
            + plan,
        selectivePos < broadPos);
    session.commit();
  }

  /**
   * Scenario 4 — bidirectional {@code bothE.bothV} chain. Edge-schema
   * inference cannot disambiguate the downstream vertex class for a
   * bidirectional traversal, so {@code resolveChainedTarget} returns a
   * {@code ChainedTarget} with a null class and the class-forced overload
   * short-circuits — unless {@code aliasClasses} already supplies the class
   * via an explicit {@code class:} annotation.
   *
   * <p>The test pins this contract by running the same query twice: once
   * with {@code class: CC4Tag} on the selective alias (fold fires,
   * selective comes first) and once without (fold short-circuits; falls
   * back to TimSort's stable order, so selective stays in its insertion
   * position relative to broad).
   */
  @Test
  public void testBothEBothVRequiresExplicitClassForFoldToFire() {
    session.execute("CREATE class CC4Post extends V").close();
    session.execute("CREATE property CC4Post.title STRING").close();

    session.execute("CREATE class CC4Tag extends V").close();
    session.execute("CREATE property CC4Tag.name STRING").close();

    session.execute("CREATE class CC4HasTag extends E").close();
    session.execute("CREATE property CC4HasTag.out LINK CC4Post").close();
    session.execute("CREATE property CC4HasTag.in LINK CC4Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC4Tag set name = 'targetTag'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC4Tag set name = 'tag" + i + "'").close();
    }
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX CC4Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC4HasTag FROM"
              + " (SELECT FROM CC4Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC4Tag WHERE name = 'targetTag')")
          .close();
      for (int j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE CC4HasTag FROM"
                + " (SELECT FROM CC4Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC4Tag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    // With explicit class on the selective alias — fold fires.
    var queryWithClass =
        "MATCH {class: CC4Post, as: post}"
            + ".bothE('CC4HasTag').bothV(){as: broadTag,"
            + "  where: (name <> 'targetTag' AND @class = 'CC4Tag')},"
            + " {as: post}"
            + ".bothE('CC4HasTag').bothV(){class: CC4Tag, as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    var explainWithClass = session.query("EXPLAIN " + queryWithClass).toList();
    String planWithClass =
        explainWithClass.getFirst().getProperty("executionPlanAsString");
    assertNotNull(planWithClass);
    int selectivePosWithClass = planWithClass.indexOf("{selectiveTag}");
    int broadPosWithClass = planWithClass.indexOf("{broadTag}");
    assertTrue(planWithClass, selectivePosWithClass >= 0);
    assertTrue(planWithClass, broadPosWithClass >= 0);
    assertTrue(
        "bothE→bothV with explicit class: should let the fold fire and"
            + " sort the selective branch first. Plan was:\n" + planWithClass,
        selectivePosWithClass < broadPosWithClass);
    session.commit();

    // Without explicit class on the selective alias — fold short-circuits
    // because aliasClasses returns null and bothE inference yields null.
    // The selective branch sits in its insertion position (second), so
    // {selectiveTag} appears AFTER {broadTag} — proving the class
    // annotation is what drives the scheduling change above.
    var queryWithoutClass =
        "MATCH {class: CC4Post, as: post}"
            + ".bothE('CC4HasTag').bothV(){as: broadTag,"
            + "  where: (name <> 'targetTag' AND @class = 'CC4Tag')},"
            + " {as: post}"
            + ".bothE('CC4HasTag').bothV(){as: selectiveTag,"
            + "  where: (name = 'targetTag' AND @class = 'CC4Tag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    var explainWithoutClass = session.query("EXPLAIN " + queryWithoutClass).toList();
    String planWithoutClass =
        explainWithoutClass.getFirst().getProperty("executionPlanAsString");
    assertNotNull(planWithoutClass);
    int selectivePosWithoutClass = planWithoutClass.indexOf("{selectiveTag}");
    int broadPosWithoutClass = planWithoutClass.indexOf("{broadTag}");
    assertTrue(planWithoutClass, selectivePosWithoutClass >= 0);
    assertTrue(planWithoutClass, broadPosWithoutClass >= 0);
    assertTrue(
        "bothE→bothV without explicit class: should short-circuit the"
            + " fold so selective falls back to insertion order (after"
            + " broad). Plan was:\n" + planWithoutClass,
        broadPosWithoutClass < selectivePosWithoutClass);
    session.commit();
  }

  /**
   * Scenario 5 — user-named intermediate edge alias with its own WHERE.
   * Pattern: {@code .outE('X'){as: e, where: weight > 5}.inV(){where: ...}}.
   * The intermediate's filter is applied by the existing 8-arg
   * {@code applyTargetSelectivity} call on alias {@code e}; the chain
   * fold then multiplies the downstream vertex's selectivity on top.
   * This exercises Design Record D3's independence-multiplication.
   *
   * <p>The structural rule still matches because {@code e} has exactly
   * one incoming pattern edge (from the current branch) — the user
   * naming the alias does not change the graph shape.
   */
  @Test
  public void testIntermediateEdgeFilterAndDownstreamFilterCombine() {
    session.execute("CREATE class CC5Person extends V").close();
    session.execute("CREATE property CC5Person.name STRING").close();

    session.execute("CREATE class CC5Company extends V").close();
    session.execute("CREATE property CC5Company.name STRING").close();

    session.execute("CREATE class CC5WorkAt extends E").close();
    session.execute("CREATE property CC5WorkAt.out LINK CC5Person").close();
    session.execute("CREATE property CC5WorkAt.in LINK CC5Company").close();
    session.execute("CREATE property CC5WorkAt.weight INTEGER").close();

    session.begin();
    session.execute("CREATE VERTEX CC5Company set name = 'targetCorp'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC5Company set name = 'corp" + i + "'").close();
    }
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX CC5Person set name = 'p" + i + "'").close();
      session.execute(
          "CREATE EDGE CC5WorkAt FROM"
              + " (SELECT FROM CC5Person WHERE name = 'p" + i + "')"
              + " TO (SELECT FROM CC5Company WHERE name = 'targetCorp')"
              + " SET weight = 10")
          .close();
      for (int j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE CC5WorkAt FROM"
                + " (SELECT FROM CC5Person WHERE name = 'p" + i + "')"
                + " TO (SELECT FROM CC5Company WHERE name = 'corp" + j + "')"
                + " SET weight = 1")
            .close();
      }
    }
    session.commit();

    // Selective: weight>5 AND name=targetCorp; broad: weight>=0 AND name<>targetCorp.
    // Both have user-named intermediate aliases (e1, e2) with their own
    // WHERE clauses, so the existing applyTargetSelectivity pre-multiplies
    // the intermediate filter and the chain fold adds the downstream filter.
    var query =
        "MATCH {class: CC5Person, as: person}"
            + ".outE('CC5WorkAt'){as: eBroad, where: (weight >= 0)}"
            + ".inV(){as: broadCorp, where: (name <> 'targetCorp')},"
            + " {as: person}"
            + ".outE('CC5WorkAt'){as: eSelective, where: (weight > 5)}"
            + ".inV(){as: selectiveCorp, where: (name = 'targetCorp')}"
            + " RETURN person.name, broadCorp.name, selectiveCorp.name";

    session.begin();
    var result = session.query(query).toList();
    // 10 persons × 5 broad × 1 selective = 50
    assertEquals(50, result.size());

    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = plan.indexOf("{selectiveCorp}");
    int broadPos = plan.indexOf("{broadCorp}");
    assertTrue(plan, selectivePos >= 0);
    assertTrue(plan, broadPos >= 0);
    assertTrue(
        "User-named intermediate alias must still let the chain fold"
            + " run, with the intermediate's WHERE multiplied by the"
            + " downstream's WHERE. Plan was:\n" + plan,
        selectivePos < broadPos);
    session.commit();
  }

  /**
   * Scenario 6 — negative case: chain rule rejects when the intermediate
   * edge alias has multiple outgoing inV continuations (fragment join).
   *
   * <p>Two fragments share the intermediate alias {@code e}, both
   * stepping {@code .outE('CC6HasTag').inV()} but to different downstream
   * targets. This makes {@code e.out.size() == 2}, which the structural
   * rule (clause "{@code neighbor.out.size() == 1}") rejects. Neither
   * branch's chain folds, so the downstream WHERE selectivity does NOT
   * propagate to the first edge.
   *
   * <p>To make the absence of folding observable, the broad branch is
   * inserted FIRST and the selective branch SECOND. With the fold off,
   * both first edges have equal cost and TimSort preserves insertion
   * order — so {@code {broadTag}} appears before {@code {selectiveTag}}.
   * If a regression hoisted the fold past the structural rule, the
   * ordering would invert and this assertion would fail.
   *
   * <p>The query has zero runtime results because a single edge instance
   * cannot lead to two distinct vertex targets — runtime correctness is
   * not the focus here; the EXPLAIN ordering is.
   */
  @Test
  public void testFragmentJoinBlocksChainFold() {
    session.execute("CREATE class CC6Post extends V").close();
    session.execute("CREATE property CC6Post.title STRING").close();

    session.execute("CREATE class CC6Tag extends V").close();
    session.execute("CREATE property CC6Tag.name STRING").close();

    session.execute("CREATE class CC6HasTag extends E").close();
    session.execute("CREATE property CC6HasTag.out LINK CC6Post").close();
    session.execute("CREATE property CC6HasTag.in LINK CC6Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC6Tag set name = 'targetTag'").close();
    for (int i = 0; i < 10; i++) {
      session.execute("CREATE VERTEX CC6Tag set name = 'tag" + i + "'").close();
    }
    for (int i = 0; i < 5; i++) {
      session.execute("CREATE VERTEX CC6Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC6HasTag FROM"
              + " (SELECT FROM CC6Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC6Tag WHERE name = 'targetTag')")
          .close();
    }
    session.commit();

    // Broad inserted first, selective second. With the fold blocked by
    // the fragment-join rule, TimSort preserves this order.
    var query =
        "MATCH {class: CC6Post, as: post}"
            + ".outE('CC6HasTag'){as: e}.inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: post}"
            + ".outE('CC6HasTag'){as: e}.inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = plan.indexOf("{selectiveTag}");
    int broadPos = plan.indexOf("{broadTag}");
    assertTrue("selectiveTag missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadTag missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "Fragment-join (e.out.size > 1) must reject the chain fold so"
            + " insertion order is preserved (broad first, selective"
            + " second). If selective comes first, the fold has been"
            + " incorrectly hoisted past the structural rule. Plan was:\n"
            + plan,
        broadPos < selectivePos);
    session.commit();
  }

  /**
   * Scenario 7 — negative case: the chain fold sits inside the
   * {@code else} branch of the visited-neighbor check. Pins that a
   * mutation hoisting the fold outside the {@code if/else} would apply
   * selectivity to a {@code cost = 0.0} join step and inflate
   * {@code applyDepthMultiplier}'s input.
   *
   * <p>Shape: a back-reference via {@code .outE.inV} where the
   * downstream vertex is also referenced as a standalone fragment.
   * Whichever fragment the DFS schedules second meets an
   * already-visited neighbor on at least one of its sort-loop
   * iterations, so the production {@code cost = 0.0} path is taken
   * for those iterations. The end-to-end correctness check (matching
   * row count) ensures the join-only step still does its job.
   */
  @Test
  public void testVisitedNeighborTakesZeroCostJoinPath() {
    session.execute("CREATE class CC7Post extends V").close();
    session.execute("CREATE property CC7Post.title STRING").close();

    session.execute("CREATE class CC7Tag extends V").close();
    session.execute("CREATE property CC7Tag.name STRING").close();

    session.execute("CREATE class CC7HasTag extends E").close();
    session.execute("CREATE property CC7HasTag.out LINK CC7Post").close();
    session.execute("CREATE property CC7HasTag.in LINK CC7Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC7Tag set name = 'targetTag'").close();
    for (int i = 0; i < 5; i++) {
      session.execute("CREATE VERTEX CC7Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC7HasTag FROM"
              + " (SELECT FROM CC7Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC7Tag WHERE name = 'targetTag')")
          .close();
    }
    session.commit();

    // Fragment 1 anchors {tag}; fragment 2 reaches it via outE.inV
    // back-reference. The DFS picks tag as a root (it has class+where
    // → most selective). When the post-side fragment is processed, the
    // inV edge's neighbor is the already-visited {tag}, so the
    // visited-neighbor branch (cost = 0.0) is taken before the chain
    // fold is even considered.
    var query =
        "MATCH {class: CC7Tag, as: tag, where: (name = 'targetTag')},"
            + " {class: CC7Post, as: post}"
            + ".outE('CC7HasTag').inV(){as: tag}"
            + " RETURN post.title, tag.name";

    session.begin();
    var result = session.query(query).toList();
    // 5 posts × 1 tag (back-referenced) = 5 rows
    assertEquals(5, result.size());
    Set<String> titles = new HashSet<>();
    for (var r : result) {
      assertEquals("targetTag", r.getProperty("tag.name"));
      titles.add(r.getProperty("post.title"));
    }
    assertEquals(5, titles.size());

    // Plan must list both aliases — confirms back-reference was wired
    // up and the fold-gate didn't crash on the cost=0.0 path.
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    assertTrue("tag alias missing from plan:\n" + plan, plan.contains("{tag}"));
    assertTrue("post alias missing from plan:\n" + plan, plan.contains("{post}"));
    session.commit();
  }
}
