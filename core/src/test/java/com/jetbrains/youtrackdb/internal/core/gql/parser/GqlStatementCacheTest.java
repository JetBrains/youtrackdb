package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class GqlStatementCacheTest extends DbTestBase {

  @Test
  public void testLRUEvictionLogic() {
    // Initialize cache with a size of 2 to easily test eviction
    var cache = new GqlStatementCache(2);

    cache.getCached("MATCH (a:Person)");
    cache.getCached("MATCH (b:Company)");

    // Adding a third item should evict the oldest one (a:Person)
    cache.getCached("MATCH (c:Product)");

    Assert.assertTrue(cache.contains("MATCH (b:Company)"));
    Assert.assertTrue(cache.contains("MATCH (c:Product)"));
    Assert.assertFalse("The first query should have been evicted",
        cache.contains("MATCH (a:Person)"));

    // Accessing (b:Company) to make it the most recently used
    cache.getCached("MATCH (b:Company)");

    // Adding (d:Location) should now evict (c:Product)
    cache.getCached("MATCH (d:Location)");

    Assert.assertTrue(cache.contains("MATCH (b:Company)"));
    Assert.assertTrue(cache.contains("MATCH (d:Location)"));
    Assert.assertFalse("Query (c:Product) should be evicted because (b:Company) was refreshed",
        cache.contains("MATCH (c:Product)"));
  }

  @Test
  public void testCacheIntegrationWithSession() {
    var query = "MATCH (n:User) WHERE n.id = 1";

    // 1. Retrieve using the static GqlStatementCache.get method
    // This internally refers to session.getSharedContext().getGqlStatementCache()
    var first = GqlStatementCache.get(query, session);

    // 2. Retrieve a second time
    var second = GqlStatementCache.get(query, session);

    // 3. Verify it is exactly the same instance (reference)
    // GqlStatement is immutable (parsed AST), safe to share
    Assert.assertNotNull(first);
    Assert.assertSame("GQL Statement should be retrieved from the SharedContext cache", first,
        second);
  }

  @Test
  public void testClearRemovesAllEntries() {
    var cache = new GqlStatementCache(10);

    cache.getCached("MATCH (a:Person)");
    cache.getCached("MATCH (b:Company)");
    cache.getCached("MATCH (c:Product)");

    Assert.assertTrue(cache.contains("MATCH (a:Person)"));
    Assert.assertTrue(cache.contains("MATCH (b:Company)"));
    Assert.assertTrue(cache.contains("MATCH (c:Product)"));

    cache.clear();

    Assert.assertFalse("Cache should be empty after clear()", cache.contains("MATCH (a:Person)"));
    Assert.assertFalse("Cache should be empty after clear()", cache.contains("MATCH (b:Company)"));
    Assert.assertFalse("Cache should be empty after clear()", cache.contains("MATCH (c:Product)"));
  }

  @Test
  public void testDisabledCacheWhenSizeIsZero() {
    var cache = new GqlStatementCache(0);

    var stmt1 = cache.getCached("MATCH (a:Person)");
    Assert.assertNotNull(stmt1);

    // With size=0, nothing should be cached
    Assert.assertFalse("Cache with size 0 should not contain any entries",
        cache.contains("MATCH (a:Person)"));

    var stmt2 = cache.getCached("MATCH (a:Person)");
    Assert.assertNotNull(stmt2);

    // Should parse again (not same instance)
    Assert.assertNotSame("With disabled cache, should parse again", stmt1, stmt2);
  }

  @Test
  public void testConcurrentAccess() throws InterruptedException {
    var cache = new GqlStatementCache(50);
    var threadCount = 10;
    var queriesPerThread = 20;
    var latch = new CountDownLatch(threadCount);
    var errors = new AtomicInteger(0);

    for (var t = 0; t < threadCount; t++) {
      final var threadId = t;
      var thread = new Thread(() -> {
        try {
          for (var i = 0; i < queriesPerThread; i++) {
            var query = "MATCH (n:Type" + (i % 5) + ") WHERE n.id = " + threadId;
            var stmt = cache.getCached(query);
            Assert.assertNotNull(stmt);

            // Verify contains() works
            Assert.assertTrue(cache.contains(query));

            // Get again - should be same instance
            var stmt2 = cache.getCached(query);
            Assert.assertSame("Should return same cached instance", stmt, stmt2);
          }
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
}