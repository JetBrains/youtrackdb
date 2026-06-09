package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * Verifies that {@link CacheKey} identifies two queries with the same statement and the same
 * parameters as the same key, and treats different statements or different parameters as distinct
 * keys.
 */
public class CacheKeyTest extends DbTestBase {

  /**
   * The key correctness floor for the tx-result cache: a query re-parsed after the parser-level
   * statement cache evicted it must still hit the same {@link CacheKey}. This drives the {@link
   * CacheKey#equals} structural (deep) path rather than the instance-identity fast-path. The test
   * forces eviction by overflowing a size-1 statement cache, re-parses the original text to obtain
   * a distinct {@code SQLStatement} instance, and asserts the two keys are equal and hash equally.
   */
  @Test
  public void deepEqualsAfterStatementCacheEvictionAndReparse() {
    var cache = new YqlStatementCache(1);
    var text = "select from OUser where name = 'x'";

    var firstParse = cache.getCached(text, session);
    // Overflow the size-1 cache so the original instance is evicted.
    cache.getCached("select from OUser where name = 'evictor'", session);
    Assert.assertFalse("Original text should have been evicted from the size-1 cache",
        cache.contains(text));

    var reparse = cache.getCached(text, session);
    Assert.assertNotSame(
        "Re-parse after eviction must yield a distinct SQLStatement instance so the deep-equals"
            + " path is exercised, not the identity fast-path",
        firstParse, reparse);

    var keyFromFirstParse = CacheKey.forArgs(firstParse, null);
    var keyFromReparse = CacheKey.forArgs(reparse, null);

    Assert.assertEquals(
        "Re-parsed identical text must produce an equal CacheKey via structural statement equality",
        keyFromFirstParse, keyFromReparse);
    Assert.assertEquals(
        "Equal keys must hash equally so they collide in the cache's HashMap",
        keyFromFirstParse.hashCode(), keyFromReparse.hashCode());
  }

  /**
   * The identity fast-path: identical query text returns the same cached statement instance, so two
   * keys built from it are equal without any structural walk. Confirms the common same-text lookup
   * path classifies as a hit.
   */
  @Test
  public void identityFastPathOnSameStatementInstance() {
    var text = "select from OUser";
    var first = YqlStatementCache.get(text, session);
    var second = YqlStatementCache.get(text, session);
    Assert.assertSame("Same text should return the same cached instance", first, second);

    var k1 = CacheKey.forArgs(first, null);
    var k2 = CacheKey.forArgs(second, null);
    Assert.assertEquals(k1, k2);
    Assert.assertEquals(k1.hashCode(), k2.hashCode());
  }

  /**
   * Same statement but different positional arguments must be distinct keys, so a parameterised
   * query issued with different bindings does not collide in the cache.
   */
  @Test
  public void differentPositionalArgsAreDistinctKeys() {
    var stmt = YqlStatementCache.get("select from OUser where name = ?", session);
    var keyA = CacheKey.forArgs(stmt, new Object[] {"alice"});
    var keyB = CacheKey.forArgs(stmt, new Object[] {"bob"});
    Assert.assertNotEquals(keyA, keyB);
  }

  /**
   * Same statement with equal positional arguments must be equal keys regardless of array identity,
   * proving the parameter compare is by value, not by reference, and that the defensive copy does
   * not change equality.
   */
  @Test
  public void equalPositionalArgsAreEqualKeys() {
    var stmt = YqlStatementCache.get("select from OUser where name = ?", session);
    var keyA = CacheKey.forArgs(stmt, new Object[] {"alice"});
    var keyB = CacheKey.forArgs(stmt, new Object[] {"alice"});
    Assert.assertEquals(keyA, keyB);
    Assert.assertEquals(keyA.hashCode(), keyB.hashCode());
  }

  /**
   * A positional call and a map call keyed by the equivalent {@link Integer} indices must produce
   * the same key, because both entry points normalise to an index-keyed parameter map.
   */
  @Test
  public void positionalArgsAndIndexKeyedMapNormaliseToSameKey() {
    var stmt = YqlStatementCache.get("select from OUser where name = ?", session);
    var fromArgs = CacheKey.forArgs(stmt, new Object[] {"alice"});
    Map<Object, Object> indexMap = new HashMap<>();
    indexMap.put(0, "alice");
    var fromMap = CacheKey.forParams(stmt, indexMap);
    Assert.assertEquals(fromArgs, fromMap);
    Assert.assertEquals(fromArgs.hashCode(), fromMap.hashCode());
  }

  /**
   * Mutating the caller's parameter map after the key is built must not change the stored key, so a
   * later caller mutation cannot retroactively alias two distinct queries onto one cache entry.
   */
  @Test
  public void callerMapMutationDoesNotAffectStoredKey() {
    var stmt = YqlStatementCache.get("select from OUser where name = ?", session);
    Map<Object, Object> params = new HashMap<>();
    params.put(0, "alice");
    var key = CacheKey.forParams(stmt, params);

    var sameValueKey = CacheKey.forParams(stmt, Map.of(0, "alice"));
    Assert.assertEquals(key, sameValueKey);

    // Mutate the original map; the stored key must keep its copied bindings.
    params.put(0, "bob");
    Assert.assertEquals(
        "Defensive copy must keep the original binding after the caller's map is mutated",
        key, sameValueKey);
  }

  /**
   * Different statements (different query text) must be distinct keys even with identical (empty)
   * parameters, so two unrelated queries never share a cache entry.
   */
  @Test
  public void differentStatementsAreDistinctKeys() {
    var stmtA = YqlStatementCache.get("select from OUser", session);
    var stmtB = YqlStatementCache.get("select from ORole", session);
    var keyA = CacheKey.forArgs(stmtA, null);
    var keyB = CacheKey.forArgs(stmtB, null);
    Assert.assertNotEquals(keyA, keyB);
  }
}
