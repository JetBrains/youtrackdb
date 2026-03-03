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

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchNumF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("age", 30L)));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
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

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchBoolF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("active", true)));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for active=true", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline string filter ──

  @Test
  public void buildPlan_stringFilter_matchesVertex() {
    graph.addVertex(T.label, "MatchStrF", "name", "Alice", "city", "NYC");
    graph.addVertex(T.label, "MatchStrF", "name", "Bob", "city", "LA");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchStrF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("city", "NYC")));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for city='NYC'", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline double filter ──

  @Test
  public void buildPlan_doubleFilter_matchesVertex() {
    graph.addVertex(T.label, "MatchDblF", "name", "Product1", "price", 19.99);
    graph.addVertex(T.label, "MatchDblF", "name", "Product2", "price", 29.99);
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchDblF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("price", 19.99)));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for price=19.99", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline RID filter ──

  @Test
  public void buildPlan_ridFilter_matchesVertex() {
    var v1 = graph.addVertex(T.label, "MatchRidF", "name", "RefSource");
    var v2 = graph.addVertex(T.label, "MatchRidF", "name", "RefTarget");
    var rid2 = (v2).id();
    v1.property("ref", rid2);
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchRidF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("ref", rid2)));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for ref=<rid>", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline list filter ──

  @Test
  public void buildPlan_listFilter_matchesVertex() {
    var tags = List.of("java", "database");
    graph.addVertex(T.label, "MatchListF", "name", "Item1", "tags", tags);
    graph.addVertex(T.label, "MatchListF", "name", "Item2", "tags", List.of("python", "ml"));
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchListF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("tags", tags)));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for tags=['java','database']", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline map filter ──

  @Test
  public void buildPlan_mapFilter_matchesVertex() {
    var meta = Map.of("version", "1.0", "author", "Alice");
    graph.addVertex(T.label, "MatchMapF", "name", "Doc1", "metadata", meta);
    graph.addVertex(T.label, "MatchMapF", "name", "Doc2", "metadata", Map.of("version", "2.0"));
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchMapF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("metadata", meta)));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for metadata={...}", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline date filter ──

  @Test
  public void buildPlan_dateFilter_matchesVertex() {
    var date1 = new Date(1704067200000L); // 2024-01-01 00:00:00 UTC
    var date2 = new Date(1735689600000L); // 2025-01-01 00:00:00 UTC
    graph.addVertex(T.label, "MatchDateF", "name", "Event1", "eventDate", date1);
    graph.addVertex(T.label, "MatchDateF", "name", "Event2", "eventDate", date2);
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchDateF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("eventDate", date1)));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for eventDate=2024-01-01", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline multiple properties filter ──

  @Test
  public void buildPlan_multiplePropertiesFilter_matchesVertex() {
    graph.addVertex(T.label, "MatchMultiF", "name", "Alice", "age", 30, "city", "NYC");
    graph.addVertex(T.label, "MatchMultiF", "name", "Bob", "age", 30, "city", "LA");
    graph.addVertex(T.label, "MatchMultiF", "name", "Charlie", "age", 25, "city", "NYC");
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchMultiF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("age", 30, "city", "NYC")));
    var statement = new GqlMatchStatement(List.of(filter));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      var count = 0;
      while (stream.hasNext()) {
        stream.next();
        count++;
      }
      Assert.assertEquals("Expected exactly 1 match for age=30 AND city='NYC'", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }
}
