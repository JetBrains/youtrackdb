package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

/**
 * Golden plan-string regression tests for {@link GqlMatchStatement}.
 *
 * <p>Captures {@code plan.prettyPrint(0, 2)} for three representative shapes that exercise the
 * shared MATCH IR builder path:
 *
 * <ol>
 *   <li>single-node anonymous query
 *   <li>multi-property AND filter (single node, multiple property equality conditions joined
 *       under SQLAndBlock)
 *   <li>multi-filter map (multiple match filters, each with its own where clause)
 * </ol>
 *
 * <p>The snapshots fail loudly if any future refactor of {@code GqlMatchStatement.buildPlan},
 * {@code MatchPatternBuilder}, or the planner shifts the resulting plan tree shape. They pin
 * the structural-equivalence contract for these representative inputs: same step types in the
 * same order, same alias bindings, equivalent {@code prettyPrint(0, 2)} output.
 */
@SuppressWarnings("resource")
public class GqlMatchStatementPlanGoldenTest extends GraphBaseTest {

  private GqlExecutionContext createCtx() {
    var gi = (YTDBGraphInternal) graph;
    var tx = gi.tx();
    tx.readWrite();
    return new GqlExecutionContext(tx.getDatabaseSession());
  }

  // ── 1) single-node anonymous query ──

  @Test
  public void plan_singleNodeAnonymous_matchesGolden() {
    graph.addVertex(T.label, "GoldenMatchA", "k", "v");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode(null, "GoldenMatchA");
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var actual = plan.prettyPrint(0, 2);
      Assert.assertEquals(SINGLE_NODE_ANON_GOLDEN, actual);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── 2) multi-property AND filter ──

  @Test
  public void plan_multiPropertyAndFilter_matchesGolden() {
    graph.addVertex(T.label, "GoldenMatchB", "firstName", "Karl", "age", 30);
    graph.tx().commit();

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("firstName", "Karl");
    properties.put("age", 30L);
    var filter = SQLMatchFilter.fromGqlNode("a", "GoldenMatchB");
    filter.setFilter(GqlMatchStatement.buildWhereClause(properties));
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var actual = plan.prettyPrint(0, 2);
      Assert.assertEquals(MULTI_PROPERTY_AND_GOLDEN, actual);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── 3) multi-filter map (two named match filters) ──

  @Test
  public void plan_multiFilterMap_matchesGolden() {
    graph.addVertex(T.label, "GoldenMatchC", "k", "v1");
    graph.addVertex(T.label, "GoldenMatchC", "k", "v2");
    graph.tx().commit();

    var filterA = SQLMatchFilter.fromGqlNode("a", "GoldenMatchC");
    filterA.setFilter(GqlMatchStatement.buildWhereClause(Map.of("k", "v1")));
    var filterB = SQLMatchFilter.fromGqlNode("b", "GoldenMatchC");
    filterB.setFilter(GqlMatchStatement.buildWhereClause(Map.of("k", "v2")));
    var statement = new GqlMatchStatement(List.of(filterA, filterB));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var actual = plan.prettyPrint(0, 2);
      // Two disjoint patterns produce a CARTESIAN PRODUCT join. The planner is free
      // to schedule a/b in either order under the cartesian product (cardinality
      // tiebreak is not deterministic across runs), so the assertions below verify
      // the per-alias filter binding via regex scoping rather than full-string
      // golden equality. A swap of filter→alias bindings (e.g., a planner refactor
      // that accidentally rotates filters by one) would fail the regex match below
      // even though every individual filter+class substring still appears.
      Assert.assertTrue(
          "alias 'a' must prefetch GoldenMatchC and filter on k = \"v1\": " + actual,
          containsAliasFilterBinding(actual, "a", "v1"));
      Assert.assertTrue(
          "alias 'b' must prefetch GoldenMatchC and filter on k = \"v2\": " + actual,
          containsAliasFilterBinding(actual, "b", "v2"));
      Assert.assertTrue(
          "two disjoint patterns must trigger CARTESIAN PRODUCT: " + actual,
          actual.contains("+ CARTESIAN PRODUCT"));
      Assert.assertTrue(
          "plan must end in CALCULATE PROJECTIONS: " + actual,
          actual.contains("+ CALCULATE PROJECTIONS"));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── containsAliasFilterBinding helper — its own correctness ──

  @Test
  public void containsAliasFilterBinding_acceptsCorrectlyBoundFilters() {
    // Synthetic plan with a→v1 / b→v2 in either prefetch order — the helper must
    // match each alias to its own filter regardless of which scheduling order the
    // planner picks for the cartesian product.
    var planAFirst =
        "+ PREFETCH a\n  + FETCH FROM CLASS GoldenMatchC\n  + FILTER ITEMS WHERE \n    k = \"v1\"\n"
            + "+ PREFETCH b\n  + FETCH FROM CLASS GoldenMatchC\n  + FILTER ITEMS WHERE \n    k = \"v2\"\n"
            + "+ CARTESIAN PRODUCT\n";
    Assert.assertTrue(containsAliasFilterBinding(planAFirst, "a", "v1"));
    Assert.assertTrue(containsAliasFilterBinding(planAFirst, "b", "v2"));

    var planBFirst =
        "+ PREFETCH b\n  + FETCH FROM CLASS GoldenMatchC\n  + FILTER ITEMS WHERE \n    k = \"v2\"\n"
            + "+ PREFETCH a\n  + FETCH FROM CLASS GoldenMatchC\n  + FILTER ITEMS WHERE \n    k = \"v1\"\n"
            + "+ CARTESIAN PRODUCT\n";
    Assert.assertTrue(containsAliasFilterBinding(planBFirst, "a", "v1"));
    Assert.assertTrue(containsAliasFilterBinding(planBFirst, "b", "v2"));
  }

  @Test
  public void containsAliasFilterBinding_rejectsRotatedAliasFilterBindings() {
    // The rotation case: alias 'a' carries the filter that should belong to 'b'
    // and vice versa. A plain ".*?" gap would let the helper match anyway because
    // lazy quantifiers can cross the next "+ PREFETCH " boundary; the tempered
    // greedy idiom locks the gap to the current prefetch block and rejects.
    // This is the bug-detection contract the helper exists to provide.
    var rotated =
        "+ PREFETCH a\n  + FETCH FROM CLASS GoldenMatchC\n  + FILTER ITEMS WHERE \n    k = \"v2\"\n"
            + "+ PREFETCH b\n  + FETCH FROM CLASS GoldenMatchC\n  + FILTER ITEMS WHERE \n    k = \"v1\"\n"
            + "+ CARTESIAN PRODUCT\n";
    Assert.assertFalse(
        "rotation: alias 'a' must NOT match v1 when v1 sits in b's prefetch block",
        containsAliasFilterBinding(rotated, "a", "v1"));
    Assert.assertFalse(
        "rotation: alias 'b' must NOT match v2 when v2 sits in a's prefetch block",
        containsAliasFilterBinding(rotated, "b", "v2"));
  }

  // ── 4) single-property where clause — pin the "always wrap in SQLAndBlock" invariant ──

  @Test
  public void plan_singlePropertyWhereClause_keepsAndBlockWrapping() {
    // GqlMatchStatement.buildWhereClause always wraps in SQLAndBlock — even for a
    // single-property map. MatchWhereBuilder.and would unwrap a lone operand for
    // parser parity, which would shift the plan tree shape. This golden pins the
    // historical wrapped shape so a future "simplification" of buildWhereClause
    // that switches to whereBuilder.and(...) fails loudly here rather than silently
    // changing plan structure across the 86 existing GQL tests (which check
    // results, not plan tree shape).
    graph.addVertex(T.label, "GoldenMatchD", "k", "x");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "GoldenMatchD");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("k", "x")));
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var actual = plan.prettyPrint(0, 2);
      Assert.assertEquals(SINGLE_PROPERTY_WHERE_GOLDEN, actual);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  /// Verifies that within the prefetch block scoped to `alias`, a `k = "value"`
  /// filter appears before the next `+ PREFETCH` or `+ CARTESIAN PRODUCT` boundary.
  /// Catches alias↔filter rotation bugs that flat substring matching would miss.
  ///
  /// The intermediate gaps use the tempered-greedy idiom `(?:(?!\+ PREFETCH ).)*?`
  /// rather than plain `.*?`. A bare `.*?` would lazily extend across the next
  /// `+ PREFETCH` block to find `k = "value"` in a different alias's scope —
  /// exactly the rotation case this helper claims to catch — producing a false
  /// positive. The negative lookahead anchors each gap to the current prefetch
  /// block; only the trailing match is unrestricted because it terminates on the
  /// `+ PREFETCH` / `+ CARTESIAN PRODUCT` boundary by construction.
  private static boolean containsAliasFilterBinding(String plan, String alias, String value) {
    var gap = "(?:(?!\\+ PREFETCH ).)*?";
    var pattern =
        java.util.regex.Pattern.compile(
            "\\+ PREFETCH " + java.util.regex.Pattern.quote(alias) + "\\b"
                + gap + "\\+ FETCH FROM CLASS GoldenMatchC"
                + gap + "k = \"" + java.util.regex.Pattern.quote(value) + "\""
                + ".*?(?:\\+ PREFETCH |\\+ CARTESIAN PRODUCT)",
            java.util.regex.Pattern.DOTALL);
    return pattern.matcher(plan).find();
  }

  // Goldens are baked from the runtime prettyPrint output. They include trailing
  // whitespace on certain lines (e.g. "+ SET ", "  + FILTER ITEMS WHERE ") that the
  // pretty-printer emits — preserved verbatim with explicit "\n" delimiters because
  // Java text blocks strip trailing whitespace per line.

  private static final String SINGLE_NODE_ANON_GOLDEN =
      "+ PREFETCH $c0\n"
          + "  + FETCH FROM CLASS GoldenMatchA\n"
          + "\n"
          + "+ SET \n"
          + "   $c0\n"
          + "+ CALCULATE PROJECTIONS\n"
          + "  ";

  private static final String MULTI_PROPERTY_AND_GOLDEN =
      "+ PREFETCH a\n"
          + "  + FETCH FROM CLASS GoldenMatchB\n"
          + "\n"
          + "  + FILTER ITEMS WHERE \n"
          + "    firstName = \"Karl\" AND age = 30\n"
          + "+ SET \n"
          + "   a\n"
          + "+ CALCULATE PROJECTIONS\n"
          + "  ";

  private static final String SINGLE_PROPERTY_WHERE_GOLDEN =
      "+ PREFETCH a\n"
          + "  + FETCH FROM CLASS GoldenMatchD\n"
          + "\n"
          + "  + FILTER ITEMS WHERE \n"
          + "    k = \"x\"\n"
          + "+ SET \n"
          + "   a\n"
          + "+ CALCULATE PROJECTIONS\n"
          + "  ";
}
