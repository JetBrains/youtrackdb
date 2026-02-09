package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

public class GqlExecutionPlanCacheTest extends GraphBaseTest {

  @Test
  public void testMatchStatementUsesExecutionPlanCache() {
    var query = "MATCH (n:OUser)";

    var graphInternal = (YTDBGraphInternal) graph;

    var tx = graphInternal.tx();
    tx.readWrite();
    var session = tx.getDatabaseSession();
    Assert.assertNotNull(session);

    var ctx = new GqlExecutionContext(graphInternal, session);
    var cache = GqlExecutionPlanCache.instance(session);

    // 1) Cache should not contain this statement before the first planning
    Assert.assertFalse("Execution plan cache should not contain the query yet",
        cache.contains(query));

    // 2) Planning (this code path is expected to populate the execution plan cache)
    var statement = GqlPlanner.getStatement(query, session);
    var plan1 = statement.createExecutionPlan(ctx);
    Assert.assertNotNull(plan1);

    // 3) After planning, the query should be cached
    Assert.assertTrue("Execution plan cache should contain the query after the first planning",
        cache.contains(query));

    // 4) Getting from cache should return a plan
    var cachedPlan1 = GqlExecutionPlanCache.get(query, ctx, session);
    Assert.assertNotNull("Cache should return an execution plan", cachedPlan1);

    // 5) Repeated planning / cache reads should not return the same plan instance (copy-on-read)
    var plan2 = statement.createExecutionPlan(ctx);
    Assert.assertNotNull(plan2);

    var cachedPlan2 = GqlExecutionPlanCache.get(query, ctx, session);
    Assert.assertNotNull(cachedPlan2);

    Assert.assertNotSame("Cache should return copies of the plan (not the same instance)",
        cachedPlan1, cachedPlan2);

    Assert.assertNotSame("Repeated createExecutionPlan() calls should not return the same instance",
        plan1, plan2);
  }

  @Test
  public void testPlanIsCachedAndInvalidatedOnSchemaAndIndexChanges() throws InterruptedException {
    var query = "MATCH (n:OUser)";
    var graphInternal = (YTDBGraphInternal) graph;

    var statement = GqlPlanner.getStatement(query, null);

    // 1) Populate cache by planning
    {
      var tx = graphInternal.tx();
      tx.readWrite();
      try {
        var session = tx.getDatabaseSession();
        Assert.assertNotNull(session);

        var ctx = new GqlExecutionContext(graphInternal, session);
        var cache = GqlExecutionPlanCache.instance(session);

        Assert.assertFalse(cache.contains(query));

        var plan = statement.createExecutionPlan(ctx);
        Assert.assertNotNull(plan);

        Assert.assertTrue(cache.contains(query));
      } finally {
        tx.commit();
      }
    }

    // 2) Schema change -> should invalidate cache
    var className = "TestInvalidate_" + System.nanoTime();
    graphInternal.executeSchemaCode(schemaSession ->
        schemaSession.getMetadata().getSchema().createClass(className)
    );
    Thread.sleep(2);
    // Verify invalidation
    {
      var tx = graphInternal.tx();
      tx.readWrite();
      try {
        var session = tx.getDatabaseSession();
        Assert.assertNotNull(session);

        var cache = GqlExecutionPlanCache.instance(session);
        Assert.assertFalse("Cache should be invalidated after schema change",
            cache.contains(query));
      } finally {
        tx.commit();
      }
    }

    // 3) Cache again (new active session)
    {
      var tx = graphInternal.tx();
      tx.readWrite();
      try {
        var session = tx.getDatabaseSession();
        Assert.assertNotNull(session);

        var ctx = new GqlExecutionContext(graphInternal, session);
        var cache = GqlExecutionPlanCache.instance(session);

        var plan = statement.createExecutionPlan(ctx);
        Assert.assertNotNull(plan);

        Assert.assertTrue("Cache should contain the query again after planning", cache.contains(query));
      } finally {
        tx.commit();
      }
    }

    // 4) Index-related change (also NOT transactional) -> should invalidate cache
    var indexName = className + "_name_idx";
    graphInternal.executeSchemaCode(schemaSession -> {
      var clazz = schemaSession.getMetadata().getSchema().getClass(className);
      Assert.assertNotNull(clazz);

      clazz.createProperty("name", PropertyType.STRING);
      clazz.createIndex(indexName, SchemaClass.INDEX_TYPE.NOTUNIQUE, "name");
    });
    Thread.sleep(2);
    // Verify invalidation using a NEW active session (new tx)
    {
      var tx = graphInternal.tx();
      tx.readWrite();
      try {
        var session = tx.getDatabaseSession();
        Assert.assertNotNull(session);

        var cache = GqlExecutionPlanCache.instance(session);
        Assert.assertFalse("Cache should be invalidated after index change", cache.contains(query));
      } finally {
        tx.commit();
      }
    }
  }
}