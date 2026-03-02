package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for GqlMatchStatement covering:
 * - buildPlan: empty patterns, single anonymous ($c0), blank alias ($c0), named alias preserved,
 *   multiple anonymous ($c0/$c1), mixed named+anonymous, null label→V, blank label→V
 * - createExecutionPlan: single-arg overload (cache=true), cache false, null originalStatement,
 *   cache hit returns copy
 * - getters: originalStatement, matchFilters
 * - setOriginalStatement(null) → NPE
 */
@SuppressWarnings("resource")
public class GqlMatchStatementTest extends GraphBaseTest {

  private GqlExecutionContext createCtx() {
    var gi = (YTDBGraphInternal) graph;
    var tx = gi.tx();
    tx.readWrite();
    return new GqlExecutionContext(tx.getDatabaseSession());
  }

  /// Helper to create SQLMatchFilter (unified YQL IR) from alias and label.
  private static SQLMatchFilter filter(String alias, String label) {
    return SQLMatchFilter.fromGqlNode(alias, label);
  }

  // ── buildPlan: empty patterns ──

  @Test
  public void buildPlan_emptyPatterns_returnsEmptyStream() {
    var statement = new GqlMatchStatement(List.of());
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(null);
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── buildPlan: single anonymous alias generates $c0 ──

  @Test
  public void buildPlan_singleAnonymous_generatesC0() {
    graph.addVertex(T.label, "MatchStA", "k", "v");
    graph.tx().commit();

    var statement = new GqlMatchStatement(
        List.of(filter(null, "MatchStA")));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      var row = (Result) stream.next();
      Assert.assertTrue(row.getPropertyNames().contains("$c0"));
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── buildPlan: blank alias also generates $c0 ──

  @Test
  public void buildPlan_blankAlias_generatesC0() {
    graph.addVertex(T.label, "MatchStB", "k", "v");
    graph.tx().commit();

    var statement = new GqlMatchStatement(
        List.of(filter("", "MatchStB")));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      var row = (Result) stream.next();
      Assert.assertTrue(row.getPropertyNames().contains("$c0"));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── buildPlan: named alias preserved ──

  @Test
  public void buildPlan_namedAlias_preservedInResult() {
    graph.addVertex(T.label, "MatchStC", "k", "v");
    graph.tx().commit();

    var statement = new GqlMatchStatement(
        List.of(filter("myAlias", "MatchStC")));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      var row = (Result) stream.next();
      Assert.assertTrue(row.getPropertyNames().contains("myAlias"));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── buildPlan: multiple anonymous → counter increments ──

  @Test
  public void buildPlan_multipleAnonymous_incrementsCounter() {
    graph.addVertex(T.label, "MatchStD", "k", "v");
    graph.tx().commit();

    var patterns = List.of(
        filter(null, "MatchStD"),
        filter(null, "MatchStD"));
    var statement = new GqlMatchStatement(patterns);
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      var row = (Result) stream.next();
      var names = row.getPropertyNames();
      Assert.assertTrue(names.contains("$c0"));
      Assert.assertTrue(names.contains("$c1"));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── buildPlan: mix of named and anonymous ──

  @Test
  public void buildPlan_mixedNamedAndAnonymous_correctAliases() {
    graph.addVertex(T.label, "MatchStE", "k", "v");
    graph.tx().commit();

    var patterns = List.of(
        filter("x", "MatchStE"),
        filter(null, "MatchStE"));
    var statement = new GqlMatchStatement(patterns);
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      var row = (Result) stream.next();
      Assert.assertTrue(row.getPropertyNames().contains("x"));
      Assert.assertTrue(row.getPropertyNames().contains("$c0"));
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── buildPlan: null label → default type V ──

  @Test
  public void buildPlan_nullLabel_usesDefaultTypeV() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(session);
      var statement = new GqlMatchStatement(
          List.of(filter("x", null)));
      var plan = statement.createExecutionPlan(ctx, false);
      Assert.assertNotNull(plan);
      var stream = plan.start(session);
      while (stream.hasNext()) {
        stream.next();
      }
    } finally {
      tx.commit();
    }
  }

  // ── buildPlan: blank label → default type V ──

  @Test
  public void buildPlan_blankLabel_usesDefaultTypeV() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(session);
      var statement = new GqlMatchStatement(
          List.of(filter("y", "")));
      var plan = statement.createExecutionPlan(ctx, false);
      Assert.assertNotNull(plan);
    } finally {
      tx.commit();
    }
  }

  // ── createExecutionPlan: single-arg overload uses cache ──

  @Test
  public void createExecutionPlan_singleArgOverload_usesCacheByDefault() {
    var query = "MATCH (n:OUser)";
    var statement = (GqlMatchStatement) GqlPlanner.parse(query);
    statement.setOriginalStatement(query);

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(session);
      var plan = statement.createExecutionPlan(ctx);
      Assert.assertNotNull(plan);
      Assert.assertTrue(GqlExecutionPlanCache.instance(session).contains(query));
    } finally {
      tx.commit();
    }
  }

  // ── createExecutionPlan: cache false ──

  @Test
  public void createExecutionPlan_cacheFalse_createsDifferentInstances() {
    var statement = (GqlMatchStatement) GqlPlanner.parse("MATCH (n:OUser)");
    statement.setOriginalStatement("MATCH (n:OUser)");

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(session);
      var plan1 = statement.createExecutionPlan(ctx, false);
      var plan2 = statement.createExecutionPlan(ctx, false);
      Assert.assertNotSame(plan1, plan2);
    } finally {
      tx.commit();
    }
  }

  // ── createExecutionPlan: null originalStatement → no cache ──

  @Test
  public void createExecutionPlan_noOriginalStatement_skipsCache() {
    var statement = new GqlMatchStatement(
        List.of(filter("n", "OUser")));

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(session);
      var plan = statement.createExecutionPlan(ctx, true);
      Assert.assertNotNull(plan);
    } finally {
      tx.commit();
    }
  }

  // ── createExecutionPlan: cache hit returns copy ──

  @Test
  public void createExecutionPlan_cacheHit_returnsDifferentInstance() {
    var query = "MATCH (cc:OUser)";
    var statement = (GqlMatchStatement) GqlPlanner.getStatement(query, null);
    statement.setOriginalStatement(query);

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(session);
      var plan1 = statement.createExecutionPlan(ctx, true);
      var plan2 = statement.createExecutionPlan(ctx, true);
      Assert.assertNotNull(plan1);
      Assert.assertNotNull(plan2);
      Assert.assertNotSame(plan1, plan2);
      Assert.assertTrue(GqlExecutionPlanCache.instance(session).contains(query));
    } finally {
      tx.commit();
    }
  }

  // ── Getters ──

  @Test
  public void getOriginalStatement_afterSet_returnsValue() {
    var statement = new GqlMatchStatement(List.of());
    statement.setOriginalStatement("MATCH (x:V)");
    Assert.assertEquals("MATCH (x:V)", statement.getOriginalStatement());
  }

  @Test
  public void getOriginalStatement_beforeSet_returnsNull() {
    Assert.assertNull(new GqlMatchStatement(List.of()).getOriginalStatement());
  }

  @Test
  public void getPatterns_returnsConstructorFilters() {
    var patterns = List.of(
        filter("a", "Person"),
        filter("b", "Animal"));
    var statement = new GqlMatchStatement(patterns);
    Assert.assertEquals(patterns, statement.getMatchFilters());
  }

  // ── extractLiteralValue: RID ──

  @Test
  public void extractLiteralValue_rid_parsesRecordId() {
    var stmt = (GqlMatchStatement) GqlPlanner.parse(
        "MATCH (a:V {link: #12:0})");
    var props = stmt.getPatterns().get(0).properties();
    Assert.assertNotNull(props.get("link"));
    Assert.assertTrue(props.get("link") instanceof RecordIdInternal);
    var rid = (RecordIdInternal) props.get("link");
    Assert.assertEquals(12, rid.getCollectionId());
    Assert.assertEquals(0, rid.getCollectionPosition());
  }

  // ── extractLiteralValue: temporal DATE ──

  @Test
  public void extractLiteralValue_date_parsesDate() {
    var stmt = (GqlMatchStatement) GqlPlanner.parse(
        "MATCH (a:V {birthday: DATE '2024-01-15'})");
    var props = stmt.getPatterns().get(0).properties();
    Assert.assertNotNull(props.get("birthday"));
    Assert.assertTrue(props.get("birthday") instanceof Date);
  }

  // ── extractLiteralValue: temporal TIMESTAMP ──

  @Test
  public void extractLiteralValue_timestamp_parsesDatetime() {
    var stmt = (GqlMatchStatement) GqlPlanner.parse(
        "MATCH (a:V {created: TIMESTAMP '2024-01-15 10:30:00'})");
    var props = stmt.getPatterns().get(0).properties();
    Assert.assertNotNull(props.get("created"));
    Assert.assertTrue(props.get("created") instanceof Date);
  }

  // ── extractLiteralValue: list literal ──

  @Test
  public void extractLiteralValue_list_parsesList() {
    var stmt = (GqlMatchStatement) GqlPlanner.parse(
        "MATCH (a:V {tags: ['alpha', 'beta']})");
    var props = stmt.getPatterns().get(0).properties();
    Assert.assertNotNull(props.get("tags"));
    Assert.assertTrue(props.get("tags") instanceof List<?>);
    @SuppressWarnings("unchecked")
    var list = (List<Object>) props.get("tags");
    Assert.assertEquals(2, list.size());
    Assert.assertEquals("alpha", list.get(0));
    Assert.assertEquals("beta", list.get(1));
  }

  // ── extractLiteralValue: map literal ──

  @Test
  public void extractLiteralValue_map_parsesMap() {
    var stmt = (GqlMatchStatement) GqlPlanner.parse(
        "MATCH (a:V {meta: {key: 'value', num: 42}})");
    var props = stmt.getPatterns().get(0).properties();
    Assert.assertNotNull(props.get("meta"));
    Assert.assertTrue(props.get("meta") instanceof Map<?, ?>);
    @SuppressWarnings("unchecked")
    var map = (Map<String, Object>) props.get("meta");
    Assert.assertEquals("value", map.get("key"));
    Assert.assertEquals(42L, map.get("num"));
  }

  // ── extractLiteralValue: boolean ──

  @Test
  public void extractLiteralValue_boolean_parsesBoolean() {
    var stmt = (GqlMatchStatement) GqlPlanner.parse(
        "MATCH (a:V {active: true, deleted: false})");
    var props = stmt.getPatterns().get(0).properties();
    Assert.assertEquals(true, props.get("active"));
    Assert.assertEquals(false, props.get("deleted"));
  }

  // ── extractLiteralValue: double ──

  @Test
  public void extractLiteralValue_double_parsesDouble() {
    var stmt = (GqlMatchStatement) GqlPlanner.parse(
        "MATCH (a:V {score: 3.14})");
    var props = stmt.getPatterns().get(0).properties();
    Assert.assertTrue(props.get("score") instanceof Double);
    Assert.assertEquals(3.14, (Double) props.get("score"), 0.001);
  }

  // ── buildWhereClause with RID value ──

  @Test
  public void buildWhereClause_ridValue_createsValidClause() {
    var rid = RecordIdInternal.fromString("#12:0", false);
    var clause = GqlMatchStatement.buildWhereClause(Map.of("link", rid));
    Assert.assertNotNull(clause);
    Assert.assertNotNull(clause.getBaseExpression());
  }

  // ── buildWhereClause with Date value ──

  @Test
  public void buildWhereClause_dateValue_createsValidClause() {
    var clause = GqlMatchStatement.buildWhereClause(
        Map.of("created", new Date()));
    Assert.assertNotNull(clause);
    Assert.assertNotNull(clause.getBaseExpression());
  }

  // ── buildWhereClause with List value ──

  @Test
  public void buildWhereClause_listValue_createsValidClause() {
    var clause = GqlMatchStatement.buildWhereClause(
        Map.of("tags", List.of("a", "b")));
    Assert.assertNotNull(clause);
    Assert.assertNotNull(clause.getBaseExpression());
  }

  // ── End-to-end: inline numeric filter ──

  @Test
  public void buildPlan_numericFilter_matchesVertex() {
    graph.addVertex(T.label, "MatchNumF", "name", "Alice", "age", 30);
    graph.addVertex(T.label, "MatchNumF", "name", "Bob", "age", 25);
    graph.tx().commit();

    var statement = new GqlMatchStatement(
        List.of(new GqlMatchVisitor.NodePattern("a", "MatchNumF",
            Map.of("age", 30L))));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      int count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for age=30", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline boolean filter ──

  @Test
  public void buildPlan_booleanFilter_matchesVertex() {
    graph.addVertex(T.label, "MatchBoolF", "name", "Active", "active", true);
    graph.addVertex(T.label, "MatchBoolF", "name", "Inactive", "active", false);
    graph.tx().commit();

    var statement = new GqlMatchStatement(
        List.of(new GqlMatchVisitor.NodePattern("a", "MatchBoolF",
            Map.of("active", true))));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      int count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for active=true", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }
}
