package com.jetbrains.youtrackdb.internal.core.gql.parser;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class GqlStatementCacheTest extends DbTestBase {

  @Test
  public void testLRUEvictionLogic() {
    var cache = new GqlStatementCache(2);

    cache.getCached("MATCH (a:Person)");
    cache.getCached("MATCH (b:Company)");

    cache.getCached("MATCH (c:Product)");

    Assert.assertTrue(cache.contains("MATCH (b:Company)"));
    Assert.assertTrue(cache.contains("MATCH (c:Product)"));
    Assert.assertFalse("Pierwsze zapytanie powinno zostać usunięte", cache.contains("MATCH (a:Person)"));

    cache.getCached("MATCH (b:Company)");

    cache.getCached("MATCH (d:Location)");

    Assert.assertTrue(cache.contains("MATCH (b:Company)"));
    Assert.assertTrue(cache.contains("MATCH (d:Location)"));
    Assert.assertFalse("Zapytanie (c:Product) powinno wypaść, bo (b:Company) zostało odświeżone",
        cache.contains("MATCH (c:Product)"));
  }

  @Test
  public void testCacheIntegrationWithSession() {
    var query = "MATCH (n:User) WHERE n.id = 1";

    // 1. Pobieramy przez statyczną metodę GqlStatementCache.get
    // To wewnętrznie odwoła się do session.getSharedContext().getGqlStatementCache()
    var first = GqlStatementCache.get(query, session);

    // 2. Pobieramy drugi raz
    var second = GqlStatementCache.get(query, session);

    // 3. Sprawdzamy czy to dokładnie ta sama instancja (referencja)
    Assert.assertNotNull(first);
    Assert.assertSame("GQL Statement powinien być pobrany z cache'u SharedContext", first, second);
  }
}