package com.jetbrains.youtrack.db.internal.core.sql.parser;

import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Assert;
import org.junit.Test;

public class StatementCacheTest extends DbTestBase {

  @Test
  public void testInIsNotAReservedWord() {
    var cache = new StatementCache(2);
    cache.getCached("select from foo", session);
    cache.getCached("select from bar", session);
    cache.getCached("select from baz", session);

    Assert.assertTrue(cache.contains("select from bar"));
    Assert.assertTrue(cache.contains("select from baz"));
    Assert.assertFalse(cache.contains("select from foo"));

    cache.getCached("select from bar", session);
    cache.getCached("select from foo", session);

    Assert.assertTrue(cache.contains("select from bar"));
    Assert.assertTrue(cache.contains("select from foo"));
    Assert.assertFalse(cache.contains("select from baz"));
  }
}
