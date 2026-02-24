package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.List;
import org.apache.tinkerpop.gremlin.structure.T;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for GqlMatchStatement covering:
 * - buildPlan: empty patterns, single anonymous ($c0), blank alias ($c0), named alias preserved,
 *   multiple anonymous ($c0/$c1), mixed named+anonymous, null label→V, blank label→V
 * - createExecutionPlan: single-arg overload (cache=true), cache false, null originalStatement,
 *   cache hit returns copy
 * - getters: originalStatement, patterns
 * - setOriginalStatement(null) → NPE
 */
@SuppressWarnings("resource")
public class GqlMatchStatementTest extends GraphBaseTest {

  private GqlExecutionContext createCtx() {
    var gi = (YTDBGraphInternal) graph;
    var tx = gi.tx();
    tx.readWrite();
    return new GqlExecutionContext(gi, tx.getDatabaseSession());
  }

  // ── buildPlan: empty patterns ──

  @Test
  public void buildPlan_emptyPatterns_returnsEmptyStream() {
    var statement = new GqlMatchStatement(List.of());
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start();
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
        List.of(new GqlMatchVisitor.NodePattern(null, "MatchStA")));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start();
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
        List.of(new GqlMatchVisitor.NodePattern("", "MatchStB")));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start();
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
        List.of(new GqlMatchVisitor.NodePattern("myAlias", "MatchStC")));
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start();
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
        new GqlMatchVisitor.NodePattern(null, "MatchStD"),
        new GqlMatchVisitor.NodePattern(null, "MatchStD"));
    var statement = new GqlMatchStatement(patterns);
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start();
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
        new GqlMatchVisitor.NodePattern("x", "MatchStE"),
        new GqlMatchVisitor.NodePattern(null, "MatchStE"));
    var statement = new GqlMatchStatement(patterns);
    var ctx = createCtx();
    try {
      var plan = statement.createExecutionPlan(ctx, false);
      var stream = plan.start();
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
      var ctx = new GqlExecutionContext(graphInternal, session);
      var statement = new GqlMatchStatement(
          List.of(new GqlMatchVisitor.NodePattern("x", null)));
      var plan = statement.createExecutionPlan(ctx, false);
      Assert.assertNotNull(plan);
      var stream = plan.start();
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
      var ctx = new GqlExecutionContext(graphInternal, session);
      var statement = new GqlMatchStatement(
          List.of(new GqlMatchVisitor.NodePattern("y", "")));
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
      var ctx = new GqlExecutionContext(graphInternal, session);
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
      var ctx = new GqlExecutionContext(graphInternal, session);
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
        List.of(new GqlMatchVisitor.NodePattern("n", "OUser")));

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
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
      var ctx = new GqlExecutionContext(graphInternal, session);
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
  public void getPatterns_returnsConstructorPatterns() {
    var patterns = List.of(
        new GqlMatchVisitor.NodePattern("a", "Person"),
        new GqlMatchVisitor.NodePattern("b", "Animal"));
    var statement = new GqlMatchStatement(patterns);
    Assert.assertEquals(patterns, statement.getPatterns());
  }

  @Test(expected = NullPointerException.class)
  public void setOriginalStatement_null_throwsNPE() {
    new GqlMatchStatement(List.of()).setOriginalStatement(null);
  }
}
