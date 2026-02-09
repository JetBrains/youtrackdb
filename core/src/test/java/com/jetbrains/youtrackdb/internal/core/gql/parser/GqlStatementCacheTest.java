package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

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
    Assert.assertNotNull(first);
    Assert.assertSame("GQL Statement should be retrieved from the SharedContext cache", first,
        second);
  }
}