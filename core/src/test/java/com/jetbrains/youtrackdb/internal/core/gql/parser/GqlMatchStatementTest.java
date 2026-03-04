package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Base64;
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
    var rid2 = v2.id();
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

  // ── End-to-end: inline BINARY filter ──

  @Test
  public void buildPlan_binaryFilter_matchesVertex() {
    var data1 = "Hello".getBytes();
    var data2 = "World".getBytes();
    graph.addVertex(T.label, "MatchBinF", "name", "File1", "data", data1);
    graph.addVertex(T.label, "MatchBinF", "name", "File2", "data", data2);
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchBinF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("data", data1)));
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
      Assert.assertEquals("Expected exactly 1 match for data=<binary>", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline DECIMAL filter ──

  @Test
  public void buildPlan_decimalFilter_matchesVertex() {
    var price1 = new java.math.BigDecimal("19.99");
    var price2 = new java.math.BigDecimal("29.99");
    graph.addVertex(T.label, "MatchDecF", "name", "Item1", "price", price1);
    graph.addVertex(T.label, "MatchDecF", "name", "Item2", "price", price2);
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchDecF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("price", price1)));
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
      Assert.assertEquals("Expected exactly 1 match for price=19.99 (BigDecimal)", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline Set (EMBEDDEDSET) filter ──

  @Test
  public void buildPlan_setFilter_matchesVertex() {
    var tags1 = java.util.Set.of("java", "database");
    var tags2 = java.util.Set.of("python", "ml");
    graph.addVertex(T.label, "MatchSetF", "name", "Item1", "tags", tags1);
    graph.addVertex(T.label, "MatchSetF", "name", "Item2", "tags", tags2);
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchSetF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("tags", tags1)));
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
      Assert.assertEquals("Expected exactly 1 match for tags={java,database}", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── End-to-end: inline Set of RIDs (LINKSET) filter ──

  @Test
  public void buildPlan_linksetFilter_matchesVertex() {
    var v1 = graph.addVertex(T.label, "MatchLinksetF", "name", "Target1");
    var v2 = graph.addVertex(T.label, "MatchLinksetF", "name", "Target2");
    var v3 = graph.addVertex(T.label, "MatchLinksetF", "name", "Target3");
    var rid1 = v1.id();
    var rid2 = v2.id();
    var rid3 = v3.id();

    var refs1 = java.util.Set.of(rid1, rid2);
    var refs2 = java.util.Set.of(rid3);
    var source1 = graph.addVertex(T.label, "MatchLinksetF", "name", "Source1");
    var source2 = graph.addVertex(T.label, "MatchLinksetF", "name", "Source2");
    source1.property("refs", refs1);
    source2.property("refs", refs2);
    graph.tx().commit();

    var filter = SQLMatchFilter.fromGqlNode("a", "MatchLinksetF");
    filter.setFilter(GqlMatchStatement.buildWhereClause(Map.of("refs", refs1)));
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
      Assert.assertEquals("Expected exactly 1 match for refs={rid1,rid2}", 1, count);
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ══════════════════════════════════════════════════════════════════
  // GQL Parser path tests — exercise GqlMatchVisitor.extractLiteralValue()
  // and helper methods through full GQL parsing pipeline
  // ══════════════════════════════════════════════════════════════════

  private static GqlMatchStatement parseMatch(String gql) {
    return (GqlMatchStatement) GqlPlanner.parse(gql);
  }

  private static SQLMatchFilter firstFilter(GqlMatchStatement stm) {
    Assert.assertNotNull(stm.getMatchFilters());
    Assert.assertFalse(stm.getMatchFilters().isEmpty());
    return stm.getMatchFilters().getFirst();
  }

  // ── Parser: string literal ──

  @Test
  public void parse_stringProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {name: 'Karl'})");
    var filter = firstFilter(stm);
    Assert.assertEquals("a", filter.getAlias());
    Assert.assertNotNull(filter.getFilter());
  }

  // ── Parser: integer literal ──

  @Test
  public void parse_integerProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {age: 42})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: double literal ──

  @Test
  public void parse_doubleProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {price: 3.14})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: negative number ──

  @Test
  public void parse_negativeNumber_createsFilter() {
    var stm = parseMatch("MATCH (a:V {temp: -5})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: boolean literal ──

  @Test
  public void parse_booleanTrue_createsFilter() {
    var stm = parseMatch("MATCH (a:V {active: true})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  @Test
  public void parse_booleanFalse_createsFilter() {
    var stm = parseMatch("MATCH (a:V {active: false})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: RID literal ──

  @Test
  public void parse_ridProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {link: #12:0})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: DATE temporal literal ──

  @Test
  public void parse_dateProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {d: DATE '2024-01-15'})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: TIMESTAMP temporal literal ──

  @Test
  public void parse_timestampProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {d: TIMESTAMP '2024-01-15 10:30:00'})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: TIME temporal literal ──

  @Test
  public void parse_timeProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {t: TIME '10:30:00'})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: BINARY literal ──

  @Test
  public void parse_binaryProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {data: BINARY 'SGVsbG8='})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: DECIMAL literal ──

  @Test
  public void parse_decimalProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {price: DECIMAL '123.456'})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: list literal ──

  @Test
  public void parse_listProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {tags: [1, 2, 3]})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  @Test
  public void parse_emptyList_createsFilter() {
    var stm = parseMatch("MATCH (a:V {tags: []})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  @Test
  public void parse_nestedList_createsFilter() {
    var stm = parseMatch("MATCH (a:V {data: [1, 'two', true]})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: map literal ──

  @Test
  public void parse_mapProperty_createsFilter() {
    var stm = parseMatch("MATCH (a:V {meta: {key: 'value'}})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  @Test
  public void parse_mapWithStringKey_createsFilter() {
    var stm = parseMatch("MATCH (a:V {meta: {'my key': 'value'}})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  @Test
  public void parse_emptyMap_createsFilter() {
    var stm = parseMatch("MATCH (a:V {meta: {}})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: multiple properties ──

  @Test
  public void parse_multipleProperties_createsFilter() {
    var stm = parseMatch("MATCH (a:V {name: 'Karl', age: 30, active: true})");
    Assert.assertNotNull(firstFilter(stm).getFilter());
  }

  // ── Parser: backtick-quoted label ──

  @Test
  public void parse_backtickLabel_stripsBackticks() {
    var stm = parseMatch("MATCH (a:`MyClass` {name: 'test'})");
    var filter = firstFilter(stm);
    Assert.assertEquals("MyClass", filter.getClassName(null));
  }

  @Test
  public void parse_backtickLabel_noProperties() {
    var stm = parseMatch("MATCH (a:`QuotedLabel`)");
    var filter = firstFilter(stm);
    Assert.assertEquals("QuotedLabel", filter.getClassName(null));
    Assert.assertNull(filter.getFilter());
  }

  // ── Parser: unsupported literal throws ──

  @Test(expected = IllegalArgumentException.class)
  public void parse_invalidTemporal_throws() {
    parseMatch("MATCH (a:V {d: DATE 'not-a-date'})");
  }

  // ══════════════════════════════════════════════════════════════════
  // End-to-end: parser → planner → execution for all literal types
  // ══════════════════════════════════════════════════════════════════

  @Test
  public void parseAndExecute_stringFilter_matchesVertex() {
    graph.addVertex(T.label, "PrsStrF", "name", "Alice");
    graph.addVertex(T.label, "PrsStrF", "name", "Bob");
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsStrF {name: 'Alice'})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_integerFilter_matchesVertex() {
    graph.addVertex(T.label, "PrsIntF", "name", "A", "age", 30);
    graph.addVertex(T.label, "PrsIntF", "name", "B", "age", 25);
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsIntF {age: 30})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_doubleFilter_matchesVertex() {
    graph.addVertex(T.label, "PrsDblF", "name", "A", "price", 3.14);
    graph.addVertex(T.label, "PrsDblF", "name", "B", "price", 2.71);
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsDblF {price: 3.14})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_booleanFilter_matchesVertex() {
    graph.addVertex(T.label, "PrsBoolF", "name", "Active", "active", true);
    graph.addVertex(T.label, "PrsBoolF", "name", "Inactive", "active", false);
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsBoolF {active: true})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_negativeNumberFilter_matchesVertex() {
    graph.addVertex(T.label, "PrsNegF", "name", "Cold", "temp", -10);
    graph.addVertex(T.label, "PrsNegF", "name", "Hot", "temp", 30);
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsNegF {temp: -10})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_dateFilter_matchesVertex() throws Exception {
    var targetDate = new SimpleDateFormat("yyyy-MM-dd").parse("2024-01-01");
    var otherDate = new SimpleDateFormat("yyyy-MM-dd").parse("2020-06-15");
    graph.addVertex(T.label, "PrsDateF", "name", "New", "created", targetDate);
    graph.addVertex(T.label, "PrsDateF", "name", "Old", "created", otherDate);
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsDateF {created: DATE '2024-01-01'})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_binaryFilter_matchesVertex() {
    var hello = "Hello".getBytes();
    var world = "World".getBytes();
    graph.addVertex(T.label, "PrsBinF", "name", "F1", "data", hello);
    graph.addVertex(T.label, "PrsBinF", "name", "F2", "data", world);
    graph.tx().commit();

    var base64 = Base64.getEncoder().encodeToString(hello);
    var stm = parseMatch("MATCH (a:PrsBinF {data: BINARY '" + base64 + "'})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_decimalFilter_matchesVertex() {
    graph.addVertex(T.label, "PrsDecF", "name", "A", "amount",
        new BigDecimal("123.456"));
    graph.addVertex(T.label, "PrsDecF", "name", "B", "amount",
        new BigDecimal("999.99"));
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsDecF {amount: DECIMAL '123.456'})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_listFilter_matchesVertex() {
    graph.addVertex(T.label, "PrsLstF", "name", "A", "tags",
        List.of("java", "db"));
    graph.addVertex(T.label, "PrsLstF", "name", "B", "tags",
        List.of("python"));
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsLstF {tags: ['java', 'db']})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_mapFilter_matchesVertex() {
    var meta1 = Map.of("v", "1.0");
    var meta2 = Map.of("v", "2.0");
    graph.addVertex(T.label, "PrsMapF", "name", "A", "meta", meta1);
    graph.addVertex(T.label, "PrsMapF", "name", "B", "meta", meta2);
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsMapF {meta: {v: '1.0'}})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_mapWithStringKey_matchesVertex() {
    var meta1 = Map.of("my key", "val1");
    var meta2 = Map.of("my key", "val2");
    graph.addVertex(T.label, "PrsMapSF", "name", "A", "meta", meta1);
    graph.addVertex(T.label, "PrsMapSF", "name", "B", "meta", meta2);
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:PrsMapSF {meta: {'my key': 'val1'}})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  @Test
  public void parseAndExecute_backtickLabel_matchesVertex() {
    graph.addVertex(T.label, "PrsBtF", "name", "X");
    graph.tx().commit();

    var stm = parseMatch("MATCH (a:`PrsBtF` {name: 'X'})");
    var ctx = createCtx();
    try {
      var plan = stm.createExecutionPlan(ctx, false);
      var stream = plan.start(ctx.session());
      Assert.assertTrue(stream.hasNext());
      stream.next();
      Assert.assertFalse(stream.hasNext());
    } finally {
      ((YTDBGraphInternal) graph).tx().commit();
    }
  }

  // ── toLiteral: unsupported type throws ──

  @Test(expected = IllegalArgumentException.class)
  public void buildWhereClause_unsupportedType_throws() {
    GqlMatchStatement.buildWhereClause(Map.of("x", new Object()));
  }
}
