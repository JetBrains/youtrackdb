package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.GqlExecutionPlanCache;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

/**
 * Tests for GqlMatchStatement.createExecutionPlan: useCache false skips cache;
 * originalStatement null skips cache lookup and put; with cache yields plan.
 */
public class GqlMatchStatementTest extends GraphBaseTest {

  @Test
  public void createExecutionPlan_withUseCacheFalse_createsPlanWithoutUsingCache() {
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
      Assert.assertNotNull(plan1);
      Assert.assertNotNull(plan2);
      Assert.assertNotSame(plan1, plan2);
    } finally {
      tx.commit();
    }
  }

  @Test
  public void createExecutionPlan_withOriginalStatementNull_createsPlanButDoesNotUseCache() {
    var patterns = List.of(new GqlMatchVisitor.NodePattern("n", "OUser"));
    var statement = new GqlMatchStatement(patterns);
    // Do not set originalStatement so cache path is skipped

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

  @Test
  public void createExecutionPlan_withUseCacheTrue_andCached_returnsCachedCopy() {
    var query = "MATCH (n:OUser)";
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
}
