package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.core.gql.planner.GqlPlanner;
import com.jetbrains.youtrackdb.internal.core.gremlin.GraphBaseTest;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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
    // GqlExecutionPlan is mutable (contains iterators, execution state), must be copied
    var plan2 = statement.createExecutionPlan(ctx);
    Assert.assertNotNull(plan2);

    var cachedPlan2 = GqlExecutionPlanCache.get(query, ctx, session);
    Assert.assertNotNull(cachedPlan2);

    Assert.assertNotSame("Cache should return copies of the plan (not the same instance)",
        cachedPlan1, cachedPlan2);

    Assert.assertNotSame("Repeated createExecutionPlan() calls should not return the same instance",
        plan1, plan2);

    tx.commit();
  }

  @Test
  public void testPlanIsCachedAndInvalidatedOnSchemaAndIndexChanges() {
    var query = "MATCH (n:OUser)";
    var graphInternal = (YTDBGraphInternal) graph;

    var statement = GqlPlanner.getStatement(query, null);

    long invalidationTimestamp;

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

        invalidationTimestamp = GqlExecutionPlanCache.getLastInvalidation(session);
      } finally {
        tx.commit();
      }
    }

    // 2) Schema change -> should invalidate cache
    var className = "TestInvalidate_" + System.nanoTime();
    graphInternal.executeSchemaCode(schemaSession ->
        schemaSession.getMetadata().getSchema().createClass(className)
    );

    // Verify invalidation using lastInvalidation timestamp
    {
      var tx = graphInternal.tx();
      tx.readWrite();
      try {
        var session = tx.getDatabaseSession();
        Assert.assertNotNull(session);

        var cache = GqlExecutionPlanCache.instance(session);
        Assert.assertFalse("Cache should be invalidated after schema change",
            cache.contains(query));

        var newInvalidationTimestamp = GqlExecutionPlanCache.getLastInvalidation(session);
        Assert.assertTrue("Invalidation timestamp should increase after schema change",
            newInvalidationTimestamp > invalidationTimestamp);

        invalidationTimestamp = newInvalidationTimestamp;
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

        Assert.assertTrue("Cache should contain the query again after planning",
            cache.contains(query));
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

    // Verify invalidation using lastInvalidation timestamp
    {
      var tx = graphInternal.tx();
      tx.readWrite();
      try {
        var session = tx.getDatabaseSession();
        Assert.assertNotNull(session);

        var cache = GqlExecutionPlanCache.instance(session);
        Assert.assertFalse("Cache should be invalidated after index change", cache.contains(query));

        var newInvalidationTimestamp = GqlExecutionPlanCache.getLastInvalidation(session);
        Assert.assertTrue("Invalidation timestamp should increase after index change",
            newInvalidationTimestamp > invalidationTimestamp);
      } finally {
        tx.commit();
      }
    }
  }

  @Test
  public void testLRUEvictionLogic() {
    var graphInternal = (YTDBGraphInternal) graph;

    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);

      var cache = new GqlExecutionPlanCache(2);

      var plan1 = GqlExecutionPlan.empty();
      var plan2 = GqlExecutionPlan.empty();

      cache.putInternal("MATCH (a:Person)", plan1);
      cache.putInternal("MATCH (b:Company)", plan2);

      Assert.assertTrue(cache.contains("MATCH (a:Person)"));
      Assert.assertTrue(cache.contains("MATCH (b:Company)"));

      var plan3 = GqlExecutionPlan.empty();
      cache.putInternal("MATCH (c:Product)", plan3);

      Assert.assertFalse("First query should have been evicted",
          cache.contains("MATCH (a:Person)"));
      Assert.assertTrue(cache.contains("MATCH (b:Company)"));
      Assert.assertTrue(cache.contains("MATCH (c:Product)"));

      cache.getInternal("MATCH (b:Company)", ctx);

      var plan4 = GqlExecutionPlan.empty();
      cache.putInternal("MATCH (d:Location)", plan4);

      Assert.assertTrue(cache.contains("MATCH (b:Company)"));
      Assert.assertTrue(cache.contains("MATCH (d:Location)"));
      Assert.assertFalse("Query (c:Product) should be evicted because (b:Company) was refreshed",
          cache.contains("MATCH (c:Product)"));
    } finally {
      tx.commit();
    }
  }

  @Test
  public void testDisabledCacheWhenSizeIsZero() {
    var graphInternal = (YTDBGraphInternal) graph;

    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);

      // Create cache with size 0 (disabled)
      var cache = new GqlExecutionPlanCache(0);

      var statement = GqlPlanner.getStatement("MATCH (n:OUser)", session);
      var plan = statement.createExecutionPlan(ctx);
      Assert.assertNotNull(plan);

      cache.putInternal("MATCH (n:OUser)", plan);

      // With size=0, nothing should be cached
      Assert.assertFalse("Cache with size 0 should not contain any entries",
          cache.contains("MATCH (n:OUser)"));

      var retrieved = cache.getInternal("MATCH (n:OUser)", ctx);
      Assert.assertNull("Cache with size 0 should return null", retrieved);
    } finally {
      tx.commit();
    }
  }

  @Test
  public void testConcurrentAccess() throws InterruptedException {
    var graphInternal = (YTDBGraphInternal) graph;
    var cache = new GqlExecutionPlanCache(50);
    var threadCount = 10;
    var plansPerThread = 20;
    var latch = new CountDownLatch(threadCount);
    var errors = new AtomicInteger(0);

    for (var t = 0; t < threadCount; t++) {
      final var threadId = t;
      var thread = new Thread(() -> {
        var tx = graphInternal.tx();
        tx.readWrite();
        try {
          var session = tx.getDatabaseSession();
          var ctx = new GqlExecutionContext(graphInternal, session);

          for (var i = 0; i < plansPerThread; i++) {
            var query = "MATCH (n:OUser) WHERE n.id = " + threadId + "_" + i;
            var plan = GqlExecutionPlan.empty();

            cache.putInternal(query, plan);
            Assert.assertTrue(cache.contains(query));

            var retrieved = cache.getInternal(query, ctx);
            Assert.assertNotNull(retrieved);
            Assert.assertNotSame("Should return copy, not same instance", plan, retrieved);
          }

          tx.commit();
        } catch (Exception e) {
          errors.incrementAndGet();
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });
      thread.start();
    }

    latch.await();
    Assert.assertEquals("No errors should occur during concurrent access", 0, errors.get());
  }

  @Test
  public void testGlobalConfigurationCacheSize() {
    GlobalConfiguration.STATEMENT_CACHE_SIZE.setValue(2);

    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);

      // Small capacity: after overfill the first entry should be evicted (Guava eviction is best-effort)
      var cache = new GqlExecutionPlanCache(
          GlobalConfiguration.STATEMENT_CACHE_SIZE.getValueAsInteger());

      var query0 = "MATCH (n:Type0)";
      var query1 = "MATCH (n:Type1)";
      var extraQuery = "MATCH (n:TypeExtra)";

      cache.putInternal(query0, GqlExecutionPlan.empty());
      Assert.assertTrue("First query should be in cache", cache.contains(query0));

      cache.putInternal(query1, GqlExecutionPlan.empty());
      Assert.assertTrue("Second query should be in cache", cache.contains(query1));

      cache.putInternal(extraQuery, GqlExecutionPlan.empty());

      // With capacity 2, one entry must have been evicted; extra must be present
      Assert.assertTrue("Extra query should be cached", cache.contains(extraQuery));
      // At least one of the first two entries should be evicted (Guava policy-dependent)
      var evicted = !cache.contains(query0) || !cache.contains(query1);
      Assert.assertTrue("One of the first two entries should have been evicted", evicted);
    } finally {
      tx.commit();
    }
  }

  @Test
  @SuppressWarnings("ConstantConditions")
  public void get_withNullStatement_returnsNull() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
      var plan = GqlExecutionPlanCache.get(null, ctx, session);
      Assert.assertNull(plan);
    } finally {
      tx.commit();
    }
  }

  @Test
  public void get_withNullDb_throws() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
      try {
        GqlExecutionPlanCache.get("MATCH (n:OUser)", ctx, null);
        Assert.fail("expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {
        Assert.assertTrue(e.getMessage().contains("DB cannot be null"));
      }
    } finally {
      tx.commit();
    }
  }

  @Test
  public void put_withNullStatement_doesNotCache() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var plan = GqlExecutionPlan.empty();
      GqlExecutionPlanCache.put(null, plan, session);
      var cache = GqlExecutionPlanCache.instance(session);
      Assert.assertFalse(cache.contains("MATCH (x)"));
    } finally {
      tx.commit();
    }
  }

  @Test
  public void put_withNullDb_throws() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var plan = GqlExecutionPlan.empty();
      try {
        GqlExecutionPlanCache.put("MATCH (n:OUser)", plan, null);
        Assert.fail("expected IllegalArgumentException");
      } catch (IllegalArgumentException e) {
        Assert.assertTrue(e.getMessage().contains("DB cannot be null"));
      }
    } finally {
      tx.commit();
    }
  }

  @Test
  public void putInternal_withNullStatement_doesNotStore() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
      var cache = new GqlExecutionPlanCache(10);
      var plan = GqlExecutionPlan.empty();
      cache.putInternal(null, plan);
      Assert.assertNull(cache.getInternal("any", ctx));
    } finally {
      tx.commit();
    }
  }

  @Test
  public void getInternal_withMissingKey_returnsNull() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
      var cache = new GqlExecutionPlanCache(10);
      cache.putInternal("MATCH (a:A)", GqlExecutionPlan.empty());
      var missing = cache.getInternal("MATCH (b:B)", ctx);
      Assert.assertNull(missing);
    } finally {
      tx.commit();
    }
  }

  @Test
  public void instance_withNullDb_throws() {
    try {
      GqlExecutionPlanCache.instance(null);
      Assert.fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().contains("DB cannot be null"));
    }
  }

  @Test
  public void invalidate_clearsCache() {
    var graphInternal = (YTDBGraphInternal) graph;
    var tx = graphInternal.tx();
    tx.readWrite();
    try {
      var session = tx.getDatabaseSession();
      var ctx = new GqlExecutionContext(graphInternal, session);
      var cache = new GqlExecutionPlanCache(5);
      var stmt = GqlPlanner.getStatement("MATCH (n:OUser)", session);
      cache.putInternal("MATCH (n:OUser)", stmt.createExecutionPlan(ctx));
      Assert.assertTrue(cache.contains("MATCH (n:OUser)"));
      cache.invalidate();
      Assert.assertFalse(cache.contains("MATCH (n:OUser)"));
    } finally {
      tx.commit();
    }
  }
}