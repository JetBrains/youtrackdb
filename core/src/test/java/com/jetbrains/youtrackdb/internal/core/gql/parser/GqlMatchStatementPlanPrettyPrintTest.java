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
 * {@link GqlMatchStatement} execution-plan {@code prettyPrint(0, 2)} regression tests.
 *
 * <p>Same convention as {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.ExpandStepPrettyPrintTest}: pin rendered
 * plan-tree output so refactors of {@code buildPlan}, {@link
 * com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchPatternBuilder}, or the
 * MATCH planner fail loudly when step order or alias bindings shift. Result-only GQL tests do
 * not cover plan shape.
 */
@SuppressWarnings("resource")
public class GqlMatchStatementPlanPrettyPrintTest extends GraphBaseTest {

  private GqlExecutionContext createCtx() {
    var gi = (YTDBGraphInternal) graph;
    var tx = gi.tx();
    tx.readWrite();
    return new GqlExecutionContext(tx.getDatabaseSession());
  }

  @Test
  public void prettyPrint_singleNodeAnonymous() {
    graph.addVertex(T.label, "PlanMatchA", "k", "v");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode(null, "PlanMatchA");
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      Assert.assertEquals(EXPECTED_SINGLE_NODE_ANON, plan.prettyPrint(0, 2));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void prettyPrint_multiPropertyAndFilter() {
    graph.addVertex(T.label, "PlanMatchB", "firstName", "Karl", "age", 30);
    graph.tx().commit();

    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("firstName", "Karl");
    properties.put("age", 30L);
    var filter = SQLMatchFilter.fromGqlNode("a", "PlanMatchB");
    filter.setFilter(GqlMatchStatement.buildWhereClause(properties));
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      Assert.assertEquals(EXPECTED_MULTI_PROPERTY_AND, plan.prettyPrint(0, 2));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void prettyPrint_multiFilterMap_cartesianProduct() {
    graph.addVertex(T.label, "PlanMatchC", "k", "v1");
    graph.addVertex(T.label, "PlanMatchC", "k", "v2");
    graph.tx().commit();

    var filterA = SQLMatchFilter.fromGqlNode("a", "PlanMatchC");
    filterA.setFilter(GqlMatchStatement.buildWhereClause(Map.of("k", "v1")));
    var filterB = SQLMatchFilter.fromGqlNode("b", "PlanMatchC");
    filterB.setFilter(GqlMatchStatement.buildWhereClause(Map.of("k", "v2")));
    var statement = new GqlMatchStatement(List.of(filterA, filterB));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var actual = plan.prettyPrint(0, 2);
      // Two disjoint patterns produce a CARTESIAN PRODUCT join. Prefetch order under the
      // product is not deterministic, so assert alias-scoped filter bindings via regex.
      Assert.assertTrue(
          "alias 'a' must prefetch PlanMatchC and filter on k = \"v1\": " + actual,
          containsAliasFilterBinding(actual, "a", "v1"));
      Assert.assertTrue(
          "alias 'b' must prefetch PlanMatchC and filter on k = \"v2\": " + actual,
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

  @Test
  public void aliasFilterBinding_acceptsCorrectPrefetchOrder() {
    var planAFirst =
        "+ PREFETCH a\n  + FETCH FROM CLASS PlanMatchC\n  + FILTER ITEMS WHERE \n    k = \"v1\"\n"
            + "+ PREFETCH b\n  + FETCH FROM CLASS PlanMatchC\n  + FILTER ITEMS WHERE \n    k = \"v2\"\n"
            + "+ CARTESIAN PRODUCT\n";
    Assert.assertTrue(containsAliasFilterBinding(planAFirst, "a", "v1"));
    Assert.assertTrue(containsAliasFilterBinding(planAFirst, "b", "v2"));

    var planBFirst =
        "+ PREFETCH b\n  + FETCH FROM CLASS PlanMatchC\n  + FILTER ITEMS WHERE \n    k = \"v2\"\n"
            + "+ PREFETCH a\n  + FETCH FROM CLASS PlanMatchC\n  + FILTER ITEMS WHERE \n    k = \"v1\"\n"
            + "+ CARTESIAN PRODUCT\n";
    Assert.assertTrue(containsAliasFilterBinding(planBFirst, "a", "v1"));
    Assert.assertTrue(containsAliasFilterBinding(planBFirst, "b", "v2"));
  }

  @Test
  public void aliasFilterBinding_rejectsRotatedBindings() {
    var rotated =
        "+ PREFETCH a\n  + FETCH FROM CLASS PlanMatchC\n  + FILTER ITEMS WHERE \n    k = \"v2\"\n"
            + "+ PREFETCH b\n  + FETCH FROM CLASS PlanMatchC\n  + FILTER ITEMS WHERE \n    k = \"v1\"\n"
            + "+ CARTESIAN PRODUCT\n";
    Assert.assertFalse(containsAliasFilterBinding(rotated, "a", "v1"));
    Assert.assertFalse(containsAliasFilterBinding(rotated, "b", "v2"));
  }

  @Test
  public void prettyPrint_singlePropertyWhereClause_keepsAndBlockWrapping() {
    graph.addVertex(T.label, "PlanMatchD", "k", "x");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "PlanMatchD");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("k", "x")));
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      Assert.assertEquals(EXPECTED_SINGLE_PROPERTY_WHERE, plan.prettyPrint(0, 2));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  private static boolean containsAliasFilterBinding(String plan, String alias, String value) {
    var gap = "(?:(?!\\+ PREFETCH ).)*?";
    var pattern =
        java.util.regex.Pattern.compile(
            "\\+ PREFETCH " + java.util.regex.Pattern.quote(alias) + "\\b"
                + gap + "\\+ FETCH FROM CLASS PlanMatchC"
                + gap + "k = \"" + java.util.regex.Pattern.quote(value) + "\""
                + ".*?(?:\\+ PREFETCH |\\+ CARTESIAN PRODUCT)",
            java.util.regex.Pattern.DOTALL);
    return pattern.matcher(plan).find();
  }

  // Expected strings from runtime prettyPrint(0, 2). Trailing whitespace on lines such as
  // "+ SET " and "  + FILTER ITEMS WHERE " is part of the renderer output.

  private static final String EXPECTED_SINGLE_NODE_ANON =
      "+ PREFETCH $c0\n"
          + "  + FETCH FROM CLASS PlanMatchA\n"
          + "\n"
          + "+ SET \n"
          + "   $c0\n"
          + "+ CALCULATE PROJECTIONS\n"
          + "  ";

  private static final String EXPECTED_MULTI_PROPERTY_AND =
      "+ PREFETCH a\n"
          + "  + FETCH FROM CLASS PlanMatchB\n"
          + "\n"
          + "  + FILTER ITEMS WHERE \n"
          + "    firstName = \"Karl\" AND age = 30\n"
          + "+ SET \n"
          + "   a\n"
          + "+ CALCULATE PROJECTIONS\n"
          + "  ";

  private static final String EXPECTED_SINGLE_PROPERTY_WHERE =
      "+ PREFETCH a\n"
          + "  + FETCH FROM CLASS PlanMatchD\n"
          + "\n"
          + "  + FILTER ITEMS WHERE \n"
          + "    k = \"x\"\n"
          + "+ SET \n"
          + "   a\n"
          + "+ CALCULATE PROJECTIONS\n"
          + "  ";
}
