package com.jetbrains.youtrackdb.internal.core.gql.parser;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Test;

/**
 * Unit tests for {@link GqlMatchStatement#createExecutionPlan} {@code prettyPrint(0, 2)} output.
 */
@SuppressWarnings("resource")
public class GqlMatchStatementPlanPrettyPrintTest extends GraphBaseTest {

  private GqlExecutionContext createCtx() {
    var gi = (YTDBGraphInternal) graph;
    var tx = gi.tx();
    tx.readWrite();
    return new GqlExecutionContext(tx.getDatabaseSession());
  }

  // =========================================================================
  // Single anonymous node — no property filter
  // =========================================================================

  @Test
  public void prettyPrint_singleNodeAnonymous() {
    graph.addVertex(T.label, "PlanMatchA", "k", "v");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode(null, "PlanMatchA");
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var output = statement.createExecutionPlan(ctx, false).prettyPrint(0, 2);

      assertThat(output).contains("+ PREFETCH $c0");
      assertThat(output).contains("+ FETCH FROM CLASS PlanMatchA");
      assertThat(output).contains("+ SET");
      assertThat(output).contains("$c0");
      assertThat(output).contains("+ CALCULATE PROJECTIONS");
      assertThat(output).doesNotContain("+ FILTER ITEMS WHERE");
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // =========================================================================
  // Named alias with multi-property WHERE filter
  // =========================================================================

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
      var output = statement.createExecutionPlan(ctx, false).prettyPrint(0, 2);

      assertThat(output).contains("+ PREFETCH a");
      assertThat(output).contains("+ FETCH FROM CLASS PlanMatchB");
      assertThat(output).contains("+ FILTER ITEMS WHERE");
      assertThat(output).contains("firstName = \"Karl\" AND age = 30");
      assertThat(output).contains("+ SET");
      assertThat(output).contains("a");
      assertThat(output).contains("+ CALCULATE PROJECTIONS");
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // =========================================================================
  // Single-property WHERE — no spurious AND conjunct
  // =========================================================================

  @Test
  public void prettyPrint_singlePropertyWhereClause() {
    graph.addVertex(T.label, "PlanMatchD", "k", "x");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "PlanMatchD");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("k", "x")));
    var statement = new GqlMatchStatement(List.of(filter));

    var ctx = createCtx();
    try {
      var output = statement.createExecutionPlan(ctx, false).prettyPrint(0, 2);

      assertThat(output).contains("+ PREFETCH a");
      assertThat(output).contains("+ FETCH FROM CLASS PlanMatchD");
      assertThat(output).contains("+ FILTER ITEMS WHERE");
      assertThat(output).contains("k = \"x\"");
      assertThat(output).contains("+ CALCULATE PROJECTIONS");
      // buildWhereClause wraps a lone property without an SQLAndBlock; the rendered
      // filter must not pick up a second conjunct from an over-eager AND merge.
      assertThat(output).doesNotContain(" AND ");
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // =========================================================================
  // Cartesian product — alias-scoped filter bindings (prefetch order varies)
  // =========================================================================

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
      var output = statement.createExecutionPlan(ctx, false).prettyPrint(0, 2);

      assertThat(output).contains("+ CARTESIAN PRODUCT");
      assertThat(output).contains("+ CALCULATE PROJECTIONS");

      var blockA = prefetchBlock(output, "a");
      assertThat(blockA)
          .as("prefetch block for alias 'a' in plan:\n%s", output)
          .contains("+ FETCH FROM CLASS PlanMatchC")
          .contains("k = \"v1\"")
          .doesNotContain("k = \"v2\"");

      var blockB = prefetchBlock(output, "b");
      assertThat(blockB)
          .as("prefetch block for alias 'b' in plan:\n%s", output)
          .contains("+ FETCH FROM CLASS PlanMatchC")
          .contains("k = \"v2\"")
          .doesNotContain("k = \"v1\"");
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  /**
   * Returns the {@code + PREFETCH <alias>} subtree up to (but not including) the next sibling
   * prefetch or {@code + CARTESIAN PRODUCT}. Prefetch order under a cartesian join is not fixed,
   * so assertions pin each alias block in isolation.
   */
  private static String prefetchBlock(String plan, String alias) {
    var marker = "+ PREFETCH " + alias;
    var blockStart = plan.indexOf(marker);
    if (blockStart < 0) {
      return "";
    }
    var afterAlias = blockStart + marker.length();
    if (afterAlias < plan.length() && !Character.isWhitespace(plan.charAt(afterAlias))) {
      return "";
    }

    var nextPrefetch = plan.indexOf("+ PREFETCH ", afterAlias);
    var cartesian = plan.indexOf("+ CARTESIAN PRODUCT", afterAlias);
    var blockEnd = plan.length();
    if (nextPrefetch >= 0) {
      blockEnd = nextPrefetch;
    }
    if (cartesian >= 0 && cartesian < blockEnd) {
      blockEnd = cartesian;
    }
    return plan.substring(blockStart, blockEnd);
  }
}
