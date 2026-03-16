package com.jetbrains.youtrackdb.internal.core.sql.parser;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Assert;
import org.junit.Test;

public class YqlStatementCacheTest extends DbTestBase {

  @Test
  public void testLRUEvictionLogic() {
    // Initialize cache with a size of 2 to easily test eviction
    var cache = new YqlStatementCache(2);
    cache.getCached("select from foo", session);
    cache.getCached("select from bar", session);

    // Adding a third item should evict the oldest one (foo)
    cache.getCached("select from baz", session);

    Assert.assertTrue(cache.contains("select from bar"));
    Assert.assertTrue(cache.contains("select from baz"));
    Assert.assertFalse(cache.contains("select from foo"));

    // Accessing (bar) to make it the most recently used
    cache.getCached("select from bar", session);

    // Adding (qux) should now evict (baz)
    cache.getCached("select from qux", session);

    Assert.assertTrue(cache.contains("select from bar"));
    Assert.assertTrue(cache.contains("select from qux"));
    Assert.assertFalse(cache.contains("select from baz"));
  }

  @Test
  public void testCacheIntegrationWithSession() {
    // Retrieve using the static YqlStatementCache.get method (uses SharedContext cache)
    var query = "select from OUser";
    var first = YqlStatementCache.get(query, session);
    var second = YqlStatementCache.get(query, session);

    // SQLStatement is an immutable parsed AST, safe to share — should be same instance
    Assert.assertNotNull(first);
    Assert.assertSame("Statement should be retrieved from the SharedContext cache",
        first, second);
  }

  @Test
  public void testClearRemovesAllEntries() {
    var cache = new YqlStatementCache(10);

    cache.getCached("select from foo", session);
    cache.getCached("select from bar", session);
    cache.getCached("select from baz", session);

    Assert.assertTrue(cache.contains("select from foo"));
    Assert.assertTrue(cache.contains("select from bar"));
    Assert.assertTrue(cache.contains("select from baz"));

    cache.clear();

    Assert.assertFalse("Cache should be empty after clear()",
        cache.contains("select from foo"));
    Assert.assertFalse("Cache should be empty after clear()",
        cache.contains("select from bar"));
    Assert.assertFalse("Cache should be empty after clear()",
        cache.contains("select from baz"));
  }

  @Test
  public void testDisabledCacheWhenSizeIsZero() {
    var cache = new YqlStatementCache(0);

    var stmt1 = cache.getCached("select from foo", session);
    Assert.assertNotNull(stmt1);

    // With size=0, nothing should be cached
    Assert.assertFalse("Cache with size 0 should not contain any entries",
        cache.contains("select from foo"));

    var stmt2 = cache.getCached("select from foo", session);
    Assert.assertNotNull(stmt2);

    // Should parse again (not same instance)
    Assert.assertNotSame("With disabled cache, should parse again", stmt1, stmt2);
  }

  @Test
  public void testConcurrentAccess() throws InterruptedException {
    var cache = new YqlStatementCache(50);
    var threadCount = 10;
    var queriesPerThread = 20;
    var latch = new CountDownLatch(threadCount);
    var errors = new AtomicInteger(0);

    for (var t = 0; t < threadCount; t++) {
      final var threadId = t;
      var thread = new Thread(() -> {
        try {
          for (var i = 0; i < queriesPerThread; i++) {
            var query = "select from V where id = " + threadId + " and x = " + i;
            var stmt = cache.getCached(query, session);
            Assert.assertNotNull(stmt);

            // Verify contains() works
            Assert.assertTrue(cache.contains(query));

            // Get again — should be same instance (immutable AST)
            var stmt2 = cache.getCached(query, session);
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

  @Test
  public void get_withNullSession_parsesDirectly() {
    var stmt = YqlStatementCache.get("select from V", null);
    Assert.assertNotNull(stmt);
  }

  @Test
  public void clear_onDisabledCache_doesNotThrow() {
    var cache = new YqlStatementCache(0);
    cache.clear();
  }

  @Test
  public void getCached_returnsSameInstanceOnRepeat() {
    var cache = new YqlStatementCache(10);
    var first = cache.getCached("select from V", session);
    var second = cache.getCached("select from V", session);
    Assert.assertSame(first, second);
  }

  @Test
  public void contains_missingKey_returnsFalse() {
    var cache = new YqlStatementCache(10);
    Assert.assertFalse(cache.contains("select from NoSuchClass"));
  }
}
