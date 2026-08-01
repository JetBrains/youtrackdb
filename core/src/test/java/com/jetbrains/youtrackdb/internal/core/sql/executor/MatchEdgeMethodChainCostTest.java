package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlExecutionPlanCache;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Regression tests for the edge-method chain-cost fold. The fold propagates
 * the downstream vertex's WHERE selectivity
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
 * invariant that a MAX_VALUE cost survives the fold untouched is pinned
 * at unit level instead, by driving the class-forced selectivity helper
 * with a MAX_VALUE input and a target that has no class, no filter and
 * no estimate, then asserting it short-circuits and returns the input.
 *
 * <p>{@code @Category(SequentialTest.class)} because several tests write
 * {@code QUERY_MATCH_CHAIN_FOLD_MAX_HOPS}, which is JVM-global state. The
 * {@code default-test} surefire execution runs classes four at a time
 * (see {@code core/pom.xml}), so without the category a concurrently
 * running class that plans an {@code outE→inV} MATCH would observe this
 * class's knob writes — including the window where the fold is disabled.
 */
@Category(SequentialTest.class)
public class MatchEdgeMethodChainCostTest extends DbTestBase {

  private int savedChainFoldMaxHops;

  @Before
  public void saveChainFoldDefault() {
    // Pin int (not Object) so a later setValue with a wrong type would fail
    // type-checking up front rather than silently corrupting the restore.
    savedChainFoldMaxHops =
        GlobalConfiguration.QUERY_MATCH_CHAIN_FOLD_MAX_HOPS.getValueAsInteger();
  }

  @After
  public void restoreChainFoldDefault() {
    GlobalConfiguration.QUERY_MATCH_CHAIN_FOLD_MAX_HOPS
        .setValue(savedChainFoldMaxHops);
  }

  /**
   * Sets the chain-fold knob and evicts cached execution plans.
   *
   * <p>The eviction is mandatory, not hygiene. {@link YqlExecutionPlanCache}
   * keys on the statement text and is invalidated by schema, index, function
   * and storage-configuration updates — never by a {@code GlobalConfiguration}
   * write. A test that runs one query text under two knob values gets the
   * first plan handed back both times without this call, and the second
   * assertion then measures the first knob's schedule.
   *
   * <p>Only tests need this. Production configuration is start-time only —
   * nothing on the server or SQL surface writes {@code GlobalConfiguration}
   * on a live instance — and a restart empties the cache anyway.
   */
  private void setChainFoldMaxHops(int maxHops) {
    GlobalConfiguration.QUERY_MATCH_CHAIN_FOLD_MAX_HOPS.setValue(maxHops);
    YqlExecutionPlanCache.instance(session).invalidate();
  }

  /**
   * Finds the position of an alias step in an EXPLAIN plan. Today the
   * planner renders aliases as <code>{alias}</code>, but the regex also
   * accepts a comma terminator so a future format addition (e.g.
   * <code>{alias,index=...}</code>) does not silently break ordering
   * assertions. Use in place of a literal substring search on
   * <code>{alias}</code>.
   *
   * @return -1 when the alias does not appear, otherwise the position of
   *         its opening brace
   */
  private static int aliasStepPosition(String plan, String alias) {
    var pattern = java.util.regex.Pattern.compile(
        "\\{" + java.util.regex.Pattern.quote(alias) + "[,}]");
    var matcher = pattern.matcher(plan);
    return matcher.find() ? matcher.start() : -1;
  }

  /**
   * Pure {@code outE.inV} chain with two branches of
   * different selectivities. The planner should fold the downstream
   * vertex's WHERE into the first edge's cost and schedule the
   * selective branch ({@code name = 'targetTag'}) before the broad
   * branch ({@code name <> 'targetTag'}).
   *
   * <p>Deliberately omits an index on the tag name, unlike the sibling
   * test that pins index-intersection attachment: the
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

    int selectivePos = aliasStepPosition(plan, "selectiveTag");
    int broadPos = aliasStepPosition(plan, "broadTag");
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
   * Mixed branch styles: one branch uses the single-step
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
    int selectivePos = aliasStepPosition(plan, "selectiveTag");
    int broadPos = aliasStepPosition(plan, "broadTag");
    assertTrue("selectiveTag missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadTag missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "Mixed-style branches must order by selectivity regardless of"
            + " whether the user wrote .out or .outE.inV. Plan was:\n" + plan,
        selectivePos < broadPos);
    session.commit();
  }

  /**
   * Reverse direction: {@code inE.outV} chain. Verifies the
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
    int selectivePos = aliasStepPosition(plan, "selectivePost");
    int broadPos = aliasStepPosition(plan, "broadPost");
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
   * Bidirectional {@code bothE.bothV} chain. Edge-schema
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
    // The two query forms express the same class constraint two ways
    // ({class: CC4Tag} vs. @class = 'CC4Tag' in the WHERE), so they must
    // return the same rows. Captured here and compared after the second
    // form runs — only the plan ordering is allowed to differ.
    int rowsWithClass = session.query(queryWithClass).toList().size();
    assertTrue(
        "Fixture must produce rows, otherwise the row-count parity check"
            + " below is vacuous",
        rowsWithClass > 0);

    var explainWithClass = session.query("EXPLAIN " + queryWithClass).toList();
    String planWithClass =
        explainWithClass.getFirst().getProperty("executionPlanAsString");
    assertNotNull(planWithClass);
    int selectivePosWithClass = aliasStepPosition(planWithClass, "selectiveTag");
    int broadPosWithClass = aliasStepPosition(planWithClass, "broadTag");
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
    assertEquals(
        "Expressing the class constraint in the WHERE instead of the"
            + " {class:} annotation must not change the result set — the"
            + " fold only reorders the schedule",
        rowsWithClass, session.query(queryWithoutClass).toList().size());

    var explainWithoutClass = session.query("EXPLAIN " + queryWithoutClass).toList();
    String planWithoutClass =
        explainWithoutClass.getFirst().getProperty("executionPlanAsString");
    assertNotNull(planWithoutClass);
    int selectivePosWithoutClass = aliasStepPosition(planWithoutClass, "selectiveTag");
    int broadPosWithoutClass = aliasStepPosition(planWithoutClass, "broadTag");
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
   * User-named intermediate edge alias with its own WHERE.
   * Pattern: {@code .outE('X'){as: e, where: weight > 5}.inV(){where: ...}}.
   * The intermediate's filter is applied by the existing
   * {@code applyTargetSelectivity} call on alias {@code e}; the chain
   * fold then multiplies the downstream vertex's selectivity on top.
   * The two filters multiply, treated as independent.
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
    int selectivePos = aliasStepPosition(plan, "selectiveCorp");
    int broadPos = aliasStepPosition(plan, "broadCorp");
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
   * Negative case: chain rule rejects when the intermediate
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
   * <p><b>Positive control.</b> "Broad first" is also what you get from a
   * globally dead fold, so the same graph is queried a second time without
   * the shared {@code {as: e}}. That form is fold-eligible and must order
   * selective-first. Only the pair of assertions distinguishes "the
   * fragment-join rule rejected this chain" from "the fold does nothing
   * any more".
   *
   * <p>Runtime rows separate the two forms as well: the shared-alias query
   * returns zero rows (one edge instance cannot reach two distinct tags)
   * while the control returns 5 posts × 2 broad tags × 1 selective tag.
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
      // Two broad tags per post so the positive control below has rows to
      // return; the shared-alias query must still return none.
      for (int j = 0; j < 2; j++) {
        session.execute(
            "CREATE EDGE CC6HasTag FROM"
                + " (SELECT FROM CC6Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC6Tag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    // Broad inserted first, selective second. With the fold blocked by
    // the fragment-join rule, TimSort preserves this order.
    var sharedAliasQuery =
        "MATCH {class: CC6Post, as: post}"
            + ".outE('CC6HasTag'){as: e}.inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: post}"
            + ".outE('CC6HasTag'){as: e}.inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    assertEquals(
        "Sharing {as: e} across both fragments pins one edge instance to two"
            + " distinct tags, which no row can satisfy",
        0, session.query(sharedAliasQuery).toList().size());

    var explainResult = session.query("EXPLAIN " + sharedAliasQuery).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = aliasStepPosition(plan, "selectiveTag");
    int broadPos = aliasStepPosition(plan, "broadTag");
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

    // Positive control: same graph, same insertion order, same selectivity
    // split — only the shared {as: e} is gone, so the chain is fold-eligible
    // and the ordering must invert. Without this the assertion above would
    // also hold for a fold that never fires at all.
    var controlQuery =
        "MATCH {class: CC6Post, as: post}"
            + ".outE('CC6HasTag').inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: post}"
            + ".outE('CC6HasTag').inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN post.title, broadTag.name, selectiveTag.name";

    session.begin();
    // 5 posts × 2 broad tags × 1 selective tag
    assertEquals(10, session.query(controlQuery).toList().size());

    var controlExplain = session.query("EXPLAIN " + controlQuery).toList();
    String controlPlan =
        controlExplain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(controlPlan);
    int controlSelectivePos = aliasStepPosition(controlPlan, "selectiveTag");
    int controlBroadPos = aliasStepPosition(controlPlan, "broadTag");
    assertTrue(
        "selectiveTag missing from control plan:\n" + controlPlan,
        controlSelectivePos >= 0);
    assertTrue(
        "broadTag missing from control plan:\n" + controlPlan,
        controlBroadPos >= 0);
    assertTrue(
        "Without the shared {as: e} the same chain must fold and order"
            + " selective first. If this fails the fold is dead and the"
            + " fragment-join assertion above proves nothing. Plan was:\n"
            + controlPlan,
        controlSelectivePos < controlBroadPos);
    session.commit();
  }

  /**
   * Negative case: the chain fold sits inside the
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
    // Use aliasStepPosition rather than a literal "{tag}" substring so a
    // future plan-format addition (e.g. "{tag,index=…}") does not turn
    // these into silent false negatives.
    assertTrue(
        "tag alias missing from plan:\n" + plan,
        aliasStepPosition(plan, "tag") >= 0);
    assertTrue(
        "post alias missing from plan:\n" + plan,
        aliasStepPosition(plan, "post") >= 0);
    session.commit();
  }

  /**
   * Multi-hop chain fold. Two competing two-hop chains from
   * {@code person} share identical first-hop fan-out and identical
   * intermediate vertices (no WHERE on intermediates). The selectivity
   * difference lives ONLY on the FINAL vertex (2 hops away from
   * {@code post}). Single-hop fold cannot see the final vertex's WHERE
   * because it only looks one hop ahead — so the two branches would tie
   * on cost and TimSort would preserve insertion order. Multi-hop fold
   * must walk the full chain and sort the selective branch first.
   *
   * <p>Insertion order is broad-first, selective-second. With the
   * multi-hop fold enabled (default), the selective branch must end up
   * scheduled before the broad one — proving the walk reached the final
   * vertex's WHERE through two consecutive (outE, inV) sub-chains.
   */
  @Test
  public void testMultiHopChainFoldSchedulesSelectiveBranchFirst() {
    session.execute("CREATE class CC9Person extends V").close();
    session.execute("CREATE property CC9Person.name STRING").close();

    session.execute("CREATE class CC9Post extends V").close();
    session.execute("CREATE property CC9Post.title STRING").close();

    session.execute("CREATE class CC9Tag extends V").close();
    session.execute("CREATE property CC9Tag.name STRING").close();

    session.execute("CREATE class CC9Wrote extends E").close();
    session.execute("CREATE property CC9Wrote.out LINK CC9Person").close();
    session.execute("CREATE property CC9Wrote.in LINK CC9Post").close();

    session.execute("CREATE class CC9HasTag extends E").close();
    session.execute("CREATE property CC9HasTag.out LINK CC9Post").close();
    session.execute("CREATE property CC9HasTag.in LINK CC9Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC9Tag set name = 'targetTag'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC9Tag set name = 'tag" + i + "'").close();
    }
    for (int i = 0; i < 5; i++) {
      session.execute("CREATE VERTEX CC9Person set name = 'person" + i + "'").close();
    }
    for (int p = 0; p < 5; p++) {
      for (int q = 0; q < 3; q++) {
        var postTitle = "p" + p + "_post" + q;
        session.execute(
            "CREATE VERTEX CC9Post set title = '" + postTitle + "'").close();
        session.execute(
            "CREATE EDGE CC9Wrote FROM"
                + " (SELECT FROM CC9Person WHERE name = 'person" + p + "')"
                + " TO (SELECT FROM CC9Post WHERE title = '" + postTitle + "')")
            .close();
        session.execute(
            "CREATE EDGE CC9HasTag FROM"
                + " (SELECT FROM CC9Post WHERE title = '" + postTitle + "')"
                + " TO (SELECT FROM CC9Tag WHERE name = 'targetTag')")
            .close();
        for (int t = 0; t < 5; t++) {
          session.execute(
              "CREATE EDGE CC9HasTag FROM"
                  + " (SELECT FROM CC9Post WHERE title = '" + postTitle + "')"
                  + " TO (SELECT FROM CC9Tag WHERE name = 'tag" + t + "')")
              .close();
        }
      }
    }
    session.commit();

    // Two two-hop chains from {person}. WHERE differs only at the FINAL
    // vertex (tag), 2 hops from person. Insertion order: broad first.
    var query =
        "MATCH {class: CC9Person, as: person}"
            + ".outE('CC9Wrote').inV().outE('CC9HasTag').inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: person}"
            + ".outE('CC9Wrote').inV().outE('CC9HasTag').inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN person.name, broadTag.name, selectiveTag.name";

    session.begin();
    // The fold reorders the schedule; it must not change what MATCH returns.
    // 5 persons × (3 posts × 5 broad tags) × (3 posts × 1 selective tag).
    var result = session.query(query).toList();
    assertEquals(5 * (3 * 5) * (3 * 1), result.size());
    for (var r : result) {
      assertEquals("targetTag", r.getProperty("selectiveTag.name"));
    }

    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = aliasStepPosition(plan, "selectiveTag");
    int broadPos = aliasStepPosition(plan, "broadTag");
    assertTrue("selectiveTag missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadTag missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "Multi-hop fold should propagate the final vertex's WHERE back to"
            + " the first edge's cost, so selectiveTag (2 hops away) sorts"
            + " before broadTag despite insertion order. Plan was:\n" + plan,
        selectivePos < broadPos);
    session.commit();
  }

  /**
   * Knob {@code QUERY_MATCH_CHAIN_FOLD_MAX_HOPS = 1} restricts
   * the fold to the immediate downstream vertex. Same two-hop shape as the
   * multi-hop test above, with the selectivity hidden two hops in, but with
   * the knob set to 1
   * the fold cannot reach the final vertex. Both branches end up with
   * identical first-hop costs, TimSort preserves insertion order, and the
   * broad branch (inserted first) sorts before the selective branch.
   *
   * <p>Pins the downgrade semantics of the knob — operators can restrict
   * the fold to one hop in production without a code change if multi-hop
   * ever causes a regression. Note that {@code 1} is not the pre-fold
   * planner: the first hop's downstream vertex is still folded. Only
   * {@code 0} skips {@code applyChainFold} altogether.
   *
   * <p><b>Positive control.</b> The same query runs first at the default
   * knob, where it must order selective-first. Without that leg, the
   * knob=1 assertion would also pass against a fold that never fires.
   * Both legs assert the row count too, so a knob change that altered
   * MATCH semantics rather than just ordering would be caught.
   */
  @Test
  public void testMultiHopFoldDisabledByMaxHopsKnob() {
    session.execute("CREATE class CC10Person extends V").close();
    session.execute("CREATE property CC10Person.name STRING").close();

    session.execute("CREATE class CC10Post extends V").close();
    session.execute("CREATE property CC10Post.title STRING").close();

    session.execute("CREATE class CC10Tag extends V").close();
    session.execute("CREATE property CC10Tag.name STRING").close();

    session.execute("CREATE class CC10Wrote extends E").close();
    session.execute("CREATE property CC10Wrote.out LINK CC10Person").close();
    session.execute("CREATE property CC10Wrote.in LINK CC10Post").close();

    session.execute("CREATE class CC10HasTag extends E").close();
    session.execute("CREATE property CC10HasTag.out LINK CC10Post").close();
    session.execute("CREATE property CC10HasTag.in LINK CC10Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC10Tag set name = 'targetTag'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC10Tag set name = 'tag" + i + "'").close();
    }
    for (int i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX CC10Person set name = 'p" + i + "'").close();
      session.execute("CREATE VERTEX CC10Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC10Wrote FROM"
              + " (SELECT FROM CC10Person WHERE name = 'p" + i + "')"
              + " TO (SELECT FROM CC10Post WHERE title = 'post" + i + "')")
          .close();
      session.execute(
          "CREATE EDGE CC10HasTag FROM"
              + " (SELECT FROM CC10Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC10Tag WHERE name = 'targetTag')")
          .close();
      for (int j = 0; j < 5; j++) {
        session.execute(
            "CREATE EDGE CC10HasTag FROM"
                + " (SELECT FROM CC10Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC10Tag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    var query =
        "MATCH {class: CC10Person, as: person}"
            + ".outE('CC10Wrote').inV().outE('CC10HasTag').inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: person}"
            + ".outE('CC10Wrote').inV().outE('CC10HasTag').inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN person.name, broadTag.name, selectiveTag.name";
    // 3 persons × 1 post each × 5 broad tags × 1 selective tag
    int expectedRows = 3 * 5;

    // Positive control at the default knob: the multi-hop fold reaches the
    // final vertex, so selective must sort first. Establishes that the
    // ordering flip below is caused by the knob and not by a dead fold.
    session.begin();
    assertEquals(expectedRows, session.query(query).toList().size());
    var defaultExplain = session.query("EXPLAIN " + query).toList();
    String defaultPlan =
        defaultExplain.getFirst().getProperty("executionPlanAsString");
    assertNotNull(defaultPlan);
    int defaultSelectivePos = aliasStepPosition(defaultPlan, "selectiveTag");
    int defaultBroadPos = aliasStepPosition(defaultPlan, "broadTag");
    assertTrue(
        "selectiveTag missing from default-knob plan:\n" + defaultPlan,
        defaultSelectivePos >= 0);
    assertTrue(
        "broadTag missing from default-knob plan:\n" + defaultPlan,
        defaultBroadPos >= 0);
    assertTrue(
        "At the default knob the multi-hop fold must reach the final"
            + " vertex and order selective first. If this fails the fold is"
            + " dead and the knob=1 assertion below proves nothing. Plan"
            + " was:\n" + defaultPlan,
        defaultSelectivePos < defaultBroadPos);
    session.commit();

    // Restrict the fold to the immediate downstream vertex via the knob.
    setChainFoldMaxHops(1);

    session.begin();
    assertEquals(
        "Restricting the fold changes plan ordering only, never the result set",
        expectedRows, session.query(query).toList().size());
    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = aliasStepPosition(plan, "selectiveTag");
    int broadPos = aliasStepPosition(plan, "broadTag");
    assertTrue("selectiveTag missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadTag missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "With maxHops=1 the fold cannot see the final vertex's WHERE,"
            + " so both branches tie and TimSort preserves insertion"
            + " order (broad first). Plan was:\n" + plan,
        broadPos < selectivePos);
    session.commit();
  }

  /**
   * User-named intermediate edge alias with its own WHERE on
   * a SECOND hop of a multi-hop chain. The fold must propagate that filter
   * into the first edge's cost. Pins the contract that the second-hop
   * intermediate alias's class is supplied (via {@code extractEdgeClassName}
   * on its {@code outE} step) so {@code applyTargetSelectivity} can apply
   * the user's WHERE — without that class, the call would short-circuit
   * and the {@code as: e2, where: weight = 1} filter would be invisible to
   * the cost model.
   *
   * <p>Both branches share identical first-hop and identical downstream
   * vertex (no WHERE there). Selectivity difference lives ONLY in the
   * second-hop intermediate edge alias's WHERE on the {@code weight}
   * property. Without intermediate-alias class inference, both branches
   * would tie on cost; with it, the selective branch sorts first.
   */
  @Test
  public void testMultiHopIntermediateEdgeAliasFilterFoldsIntoCost() {
    session.execute("CREATE class CC11Person extends V").close();
    session.execute("CREATE property CC11Person.name STRING").close();

    session.execute("CREATE class CC11Post extends V").close();
    session.execute("CREATE property CC11Post.title STRING").close();

    session.execute("CREATE class CC11Tag extends V").close();
    session.execute("CREATE property CC11Tag.name STRING").close();

    session.execute("CREATE class CC11Wrote extends E").close();
    session.execute("CREATE property CC11Wrote.out LINK CC11Person").close();
    session.execute("CREATE property CC11Wrote.in LINK CC11Post").close();

    session.execute("CREATE class CC11HasTag extends E").close();
    session.execute("CREATE property CC11HasTag.out LINK CC11Post").close();
    session.execute("CREATE property CC11HasTag.in LINK CC11Tag").close();
    session.execute("CREATE property CC11HasTag.weight INTEGER").close();

    session.begin();
    session.execute("CREATE VERTEX CC11Tag set name = 'tag'").close();
    for (int i = 0; i < 3; i++) {
      session.execute("CREATE VERTEX CC11Person set name = 'p" + i + "'").close();
      session.execute("CREATE VERTEX CC11Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC11Wrote FROM"
              + " (SELECT FROM CC11Person WHERE name = 'p" + i + "')"
              + " TO (SELECT FROM CC11Post WHERE title = 'post" + i + "')")
          .close();
      // 1 selective edge (weight=1) + 5 broad edges (weight=10..14)
      session.execute(
          "CREATE EDGE CC11HasTag FROM"
              + " (SELECT FROM CC11Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC11Tag WHERE name = 'tag')"
              + " SET weight = 1")
          .close();
      for (int w = 10; w < 15; w++) {
        session.execute(
            "CREATE EDGE CC11HasTag FROM"
                + " (SELECT FROM CC11Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC11Tag WHERE name = 'tag')"
                + " SET weight = " + w)
            .close();
      }
    }
    session.commit();

    // Two two-hop chains. Diff is on the SECOND-hop intermediate edge alias's
    // weight filter. Insertion order: broad first.
    var query =
        "MATCH {class: CC11Person, as: person}"
            + ".outE('CC11Wrote').inV()"
            + ".outE('CC11HasTag'){as: eBroad, where: (weight >= 10)}"
            + ".inV(){as: broadTag},"
            + " {as: person}"
            + ".outE('CC11Wrote').inV()"
            + ".outE('CC11HasTag'){as: eSelective, where: (weight = 1)}"
            + ".inV(){as: selectiveTag}"
            + " RETURN person.name, broadTag.name, selectiveTag.name";

    session.begin();
    // 3 persons × 1 post × 5 weight>=10 edges × 1 weight=1 edge. Pins that
    // the intermediate-alias fold changed ordering only, not the result set.
    assertEquals(3 * 5 * 1, session.query(query).toList().size());

    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int selectivePos = aliasStepPosition(plan, "selectiveTag");
    int broadPos = aliasStepPosition(plan, "broadTag");
    assertTrue("selectiveTag missing from plan:\n" + plan, selectivePos >= 0);
    assertTrue("broadTag missing from plan:\n" + plan, broadPos >= 0);
    assertTrue(
        "Multi-hop fold must apply the user-named intermediate edge"
            + " alias's WHERE on hop 2 — without extractEdgeClassName, the"
            + " applyTargetSelectivity call short-circuits on null class"
            + " and the weight filter never propagates. Plan was:\n" + plan,
        selectivePos < broadPos);
    session.commit();
  }

  /**
   * Pathological knob values must not break the planner.
   * Pins the {@code MAX_CHAIN_FOLD_HOPS} clamp applied when the knob is
   * read in {@code getTopologicalSortedSchedule}. An unclamped
   * {@code Integer.MAX_VALUE} becomes the walk's {@code remainingHops}
   * loop bound, and any future bookkeeping derived from it (sizing,
   * counters) would then sit one arithmetic operation away from wrapping.
   * The clamp keeps that bound three orders of magnitude below the int
   * range.
   *
   * <p>The test runs a normal multi-hop chain query with the knob set to
   * extreme values: {@code Integer.MAX_VALUE} (clamped down, fold still
   * fires), {@code Integer.MIN_VALUE} (clamped to 0, fold fully off) and
   * a large but in-range value (no clamping). Each probe asserts both the
   * result set and the resulting plan ordering, so a clamp that silently
   * disabled or over-applied the fold would be caught rather than passing
   * on "the query did not throw".
   */
  @Test
  public void testExtremeKnobValuesDoNotBreakPlanner() {
    session.execute("CREATE class CC12Person extends V").close();
    session.execute("CREATE property CC12Person.name STRING").close();

    session.execute("CREATE class CC12Post extends V").close();
    session.execute("CREATE property CC12Post.title STRING").close();

    session.execute("CREATE class CC12Tag extends V").close();
    session.execute("CREATE property CC12Tag.name STRING").close();

    session.execute("CREATE class CC12Wrote extends E").close();
    session.execute("CREATE property CC12Wrote.out LINK CC12Person").close();
    session.execute("CREATE property CC12Wrote.in LINK CC12Post").close();

    session.execute("CREATE class CC12HasTag extends E").close();
    session.execute("CREATE property CC12HasTag.out LINK CC12Post").close();
    session.execute("CREATE property CC12HasTag.in LINK CC12Tag").close();

    session.begin();
    session.execute("CREATE VERTEX CC12Tag set name = 'targetTag'").close();
    for (int i = 0; i < 5; i++) {
      session.execute("CREATE VERTEX CC12Tag set name = 'tag" + i + "'").close();
    }
    for (int i = 0; i < 2; i++) {
      session.execute("CREATE VERTEX CC12Person set name = 'p" + i + "'").close();
      session.execute("CREATE VERTEX CC12Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC12Wrote FROM"
              + " (SELECT FROM CC12Person WHERE name = 'p" + i + "')"
              + " TO (SELECT FROM CC12Post WHERE title = 'post" + i + "')")
          .close();
      session.execute(
          "CREATE EDGE CC12HasTag FROM"
              + " (SELECT FROM CC12Post WHERE title = 'post" + i + "')"
              + " TO (SELECT FROM CC12Tag WHERE name = 'targetTag')")
          .close();
      for (int j = 0; j < 3; j++) {
        session.execute(
            "CREATE EDGE CC12HasTag FROM"
                + " (SELECT FROM CC12Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC12Tag WHERE name = 'tag" + j + "')")
            .close();
      }
    }
    session.commit();

    var query =
        "MATCH {class: CC12Person, as: person}"
            + ".outE('CC12Wrote').inV().outE('CC12HasTag').inV(){as: broadTag,"
            + "  where: (name <> 'targetTag')},"
            + " {as: person}"
            + ".outE('CC12Wrote').inV().outE('CC12HasTag').inV(){as: selectiveTag,"
            + "  where: (name = 'targetTag')}"
            + " RETURN person.name, broadTag.name, selectiveTag.name";

    // Probe 1: Integer.MAX_VALUE — overflow surface for any
    // arithmetic on remainingHops. Clamp must keep the planner alive
    // and the fold should still fire (clamp value of 1000 ≫ this 2-hop
    // chain's needs), so selective branch sorts first.
    setChainFoldMaxHops(Integer.MAX_VALUE);
    session.begin();
    var resultMax = session.query(query).toList();
    assertEquals(2 * 3, resultMax.size());
    var explainMax = session.query("EXPLAIN " + query).toList();
    String planMax = explainMax.getFirst().getProperty("executionPlanAsString");
    assertNotNull(planMax);
    int selectivePosMax = aliasStepPosition(planMax, "selectiveTag");
    int broadPosMax = aliasStepPosition(planMax, "broadTag");
    assertTrue("selectiveTag missing from plan:\n" + planMax, selectivePosMax >= 0);
    assertTrue("broadTag missing from plan:\n" + planMax, broadPosMax >= 0);
    assertTrue(
        "Integer.MAX_VALUE knob must be clamped to a sane upper bound; the"
            + " fold must still fire and order selective before broad. Plan was:\n"
            + planMax,
        selectivePosMax < broadPosMax);
    session.commit();

    // Probe 2: Integer.MIN_VALUE — clamps to 0, so the sort loop skips
    // applyChainFold entirely. The query must still run and return the
    // same rows, and the ordering must revert to insertion order (broad
    // first) — asserting only the row count here would let a clamp that
    // never disabled the fold pass unnoticed.
    setChainFoldMaxHops(Integer.MIN_VALUE);
    session.begin();
    var resultMin = session.query(query).toList();
    assertEquals(2 * 3, resultMin.size());
    var explainMin = session.query("EXPLAIN " + query).toList();
    String planMin = explainMin.getFirst().getProperty("executionPlanAsString");
    assertNotNull(planMin);
    int selectivePosMin = aliasStepPosition(planMin, "selectiveTag");
    int broadPosMin = aliasStepPosition(planMin, "broadTag");
    assertTrue("selectiveTag missing from plan:\n" + planMin, selectivePosMin >= 0);
    assertTrue("broadTag missing from plan:\n" + planMin, broadPosMin >= 0);
    assertTrue(
        "Integer.MIN_VALUE clamps to 0, which disables the fold, so both"
            + " branches tie and TimSort preserves insertion order (broad"
            + " first). Plan was:\n" + planMin,
        broadPosMin < selectivePosMin);
    session.commit();

    // Probe 3: a large in-range value — exercises the clamp's ceiling
    // path (input <= MAX_CHAIN_FOLD_HOPS, no clamping needed). Same
    // semantics as default knob; selective branch first.
    setChainFoldMaxHops(500);
    session.begin();
    var resultLarge = session.query(query).toList();
    assertEquals(2 * 3, resultLarge.size());
    var explainLarge = session.query("EXPLAIN " + query).toList();
    String planLarge =
        explainLarge.getFirst().getProperty("executionPlanAsString");
    assertNotNull(planLarge);
    int selectivePosLarge = aliasStepPosition(planLarge, "selectiveTag");
    int broadPosLarge = aliasStepPosition(planLarge, "broadTag");
    assertTrue(
        "selectiveTag missing from plan:\n" + planLarge, selectivePosLarge >= 0);
    assertTrue("broadTag missing from plan:\n" + planLarge, broadPosLarge >= 0);
    assertTrue(
        "Large but in-range knob (500) must not be clamped down and the"
            + " fold should fire normally. Plan was:\n" + planLarge,
        selectivePosLarge < broadPosLarge);
    session.commit();
  }

  /**
   * Three-hop chain, pinning the exact hop at which
   * {@code QUERY_MATCH_CHAIN_FOLD_MAX_HOPS} truncates the walk.
   *
   * <p>Shape: {@code person → Post → Tag → Category}, with the selectivity
   * living only on {@code Category}, three hops from the root. Reaching it
   * costs {@code applyChainFold} one first-hop fold plus two iterations of
   * {@code walkLinearChainExtension}, so the fold needs {@code maxHops >= 3}.
   * The test walks the boundary from both sides:
   *
   * <ul>
   *   <li>{@code maxHops = 2} — the walk stops at {@code Tag}, which carries
   *       no WHERE, so the branches tie and insertion order (broad first)
   *       survives;</li>
   *   <li>{@code maxHops = 3} — the walk reaches {@code Category} and the
   *       selective branch sorts first;</li>
   *   <li>default knob (10) — same as 3, confirming values above the chain
   *       length behave like the exact fit rather than over-applying.</li>
   * </ul>
   *
   * <p>The two-hop case only shows that {@code maxHops = 1} is too small,
   * which an off-by-one in the cap would survive. Testing
   * both sides of a boundary two hops deeper also forces the walk's loop to
   * iterate more than once, exercising the per-hop re-seeding of
   * {@code sourceClass} from the previous sub-chain's downstream class.
   */
  @Test
  public void testChainFoldStopsAtExactlyMaxHops() {
    session.execute("CREATE class CC13Person extends V").close();
    session.execute("CREATE property CC13Person.name STRING").close();

    session.execute("CREATE class CC13Post extends V").close();
    session.execute("CREATE property CC13Post.title STRING").close();

    session.execute("CREATE class CC13Tag extends V").close();
    session.execute("CREATE property CC13Tag.name STRING").close();

    session.execute("CREATE class CC13Category extends V").close();
    session.execute("CREATE property CC13Category.name STRING").close();

    session.execute("CREATE class CC13Wrote extends E").close();
    session.execute("CREATE property CC13Wrote.out LINK CC13Person").close();
    session.execute("CREATE property CC13Wrote.in LINK CC13Post").close();

    session.execute("CREATE class CC13HasTag extends E").close();
    session.execute("CREATE property CC13HasTag.out LINK CC13Post").close();
    session.execute("CREATE property CC13HasTag.in LINK CC13Tag").close();

    session.execute("CREATE class CC13InCat extends E").close();
    session.execute("CREATE property CC13InCat.out LINK CC13Tag").close();
    session.execute("CREATE property CC13InCat.in LINK CC13Category").close();

    session.begin();
    // One selective category plus 50 decoys, so the equality filter on
    // {name = 'targetCat'} is markedly more selective than its negation.
    session.execute("CREATE VERTEX CC13Category set name = 'targetCat'").close();
    for (int i = 0; i < 50; i++) {
      session.execute("CREATE VERTEX CC13Category set name = 'cat" + i + "'").close();
    }
    // tag0 leads to the selective category; tag1..tag3 to decoys.
    for (int t = 0; t < 4; t++) {
      session.execute("CREATE VERTEX CC13Tag set name = 'tag" + t + "'").close();
      var categoryName = t == 0 ? "targetCat" : "cat" + t;
      session.execute(
          "CREATE EDGE CC13InCat FROM"
              + " (SELECT FROM CC13Tag WHERE name = 'tag" + t + "')"
              + " TO (SELECT FROM CC13Category WHERE name = '" + categoryName + "')")
          .close();
    }
    for (int i = 0; i < 2; i++) {
      session.execute("CREATE VERTEX CC13Person set name = 'p" + i + "'").close();
      session.execute("CREATE VERTEX CC13Post set title = 'post" + i + "'").close();
      session.execute(
          "CREATE EDGE CC13Wrote FROM"
              + " (SELECT FROM CC13Person WHERE name = 'p" + i + "')"
              + " TO (SELECT FROM CC13Post WHERE title = 'post" + i + "')")
          .close();
      for (int t = 0; t < 4; t++) {
        session.execute(
            "CREATE EDGE CC13HasTag FROM"
                + " (SELECT FROM CC13Post WHERE title = 'post" + i + "')"
                + " TO (SELECT FROM CC13Tag WHERE name = 'tag" + t + "')")
            .close();
      }
    }
    session.commit();

    // Insertion order is broad-first, so "selective first" can only come
    // from the fold reaching the third hop.
    var query =
        "MATCH {class: CC13Person, as: person}"
            + ".outE('CC13Wrote').inV()"
            + ".outE('CC13HasTag').inV()"
            + ".outE('CC13InCat').inV(){as: broadCat,"
            + "  where: (name <> 'targetCat')},"
            + " {as: person}"
            + ".outE('CC13Wrote').inV()"
            + ".outE('CC13HasTag').inV()"
            + ".outE('CC13InCat').inV(){as: selectiveCat,"
            + "  where: (name = 'targetCat')}"
            + " RETURN person.name, broadCat.name, selectiveCat.name";
    // Per person: 1 post × 3 decoy tags for the broad branch × 1 tag0 for
    // the selective branch. Two persons.
    int expectedRows = 2 * 3 * 1;

    setChainFoldMaxHops(2);
    assertOrdering(
        query, expectedRows, "broadCat", "selectiveCat",
        "maxHops=2 truncates the walk at Tag, which carries no WHERE, so"
            + " the branches tie and insertion order survives");

    setChainFoldMaxHops(3);
    assertOrdering(
        query, expectedRows, "selectiveCat", "broadCat",
        "maxHops=3 is exactly enough to reach Category, so the selective"
            + " branch must sort first");

    setChainFoldMaxHops(10);
    assertOrdering(
        query, expectedRows, "selectiveCat", "broadCat",
        "a knob above the chain length must behave like the exact fit,"
            + " not fold further and reorder something else");
  }

  /**
   * Runs {@code query}, asserts its row count, then asserts that
   * {@code firstAlias} is scheduled before {@code secondAlias} in the
   * EXPLAIN plan. Wraps both in one transaction.
   *
   * @param reason appended to the ordering failure message to say which
   *               knob setting or structural rule the caller is pinning
   */
  private void assertOrdering(
      String query, int expectedRows, String firstAlias, String secondAlias,
      String reason) {
    session.begin();
    assertEquals(
        "Fold configuration must not change the result set (" + reason + ")",
        expectedRows, session.query(query).toList().size());

    var explainResult = session.query("EXPLAIN " + query).toList();
    String plan = explainResult.getFirst().getProperty("executionPlanAsString");
    assertNotNull(plan);
    int firstPos = aliasStepPosition(plan, firstAlias);
    int secondPos = aliasStepPosition(plan, secondAlias);
    assertTrue(firstAlias + " missing from plan:\n" + plan, firstPos >= 0);
    assertTrue(secondAlias + " missing from plan:\n" + plan, secondPos >= 0);
    assertTrue(
        firstAlias + " must be scheduled before " + secondAlias + " — "
            + reason + ". Plan was:\n" + plan,
        firstPos < secondPos);
    session.commit();
  }
}
