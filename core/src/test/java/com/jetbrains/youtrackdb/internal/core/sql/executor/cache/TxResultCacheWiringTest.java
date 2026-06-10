package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end wiring tests for the tx-result cache on the live {@code query()} path. They prove the
 * session/transaction integration actually serves repeated identical SELECTs from the cache when the
 * feature flag is on, that a mutation between two identical queries is reflected in the second view,
 * that every transaction-end path clears the cache, that the cache field stays null and behaviour is
 * unchanged when the flag is off (the compatibility floor), and that a re-entrant {@code query()}
 * issued from inside the cache code path bypasses the cache.
 *
 * <p>The exhaustive invariant and cache-vs-fresh equivalence matrix (every invariant plus the RECORD
 * equivalence across the four mutation patterns) lands in the dedicated suite; this class is the
 * wiring smoke plus the flag-off and re-entrancy proofs. Run with {@code -ea} so the schema-DDL
 * canary and the owner-thread asserts are live.
 */
public class TxResultCacheWiringTest extends DbTestBase {

  private static final String CLASS_NAME = "WireRec";
  private static final String FIELD = "n";

  private boolean previousEnabled;

  @Before
  public void enableCacheAndSchema() {
    // Capture the prior flag value so the global is restored in tearDown and cannot leak the
    // enabled state into the next test in the surefire sequence. Individual tests flip it as needed.
    previousEnabled = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty(FIELD, PropertyType.INTEGER);
  }

  @After
  public void restoreFlag() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previousEnabled);
  }

  private FrontendTransactionImpl tx() {
    return (FrontendTransactionImpl) session.getTransactionInternal();
  }

  /** Creates and persists {@code count} records of CLASS_NAME with ascending FIELD values. */
  private void seed(int count) {
    session.begin();
    for (var i = 0; i < count; i++) {
      var e = session.newEntity(CLASS_NAME);
      e.setProperty(FIELD, i);
    }
    session.commit();
  }

  /** Runs a SELECT and returns its row count, fully consuming and closing the result set. */
  private long countQuery(String sql) {
    try (var rs = session.query(sql)) {
      return rs.stream().count();
    }
  }

  // ===========================================================================
  // Flag ON — wiring smoke
  // ===========================================================================

  /**
   * With the flag on, the same SELECT issued twice inside one transaction misses on the first call
   * (driving real execution and populating one entry) and hits on the second (served from the
   * cache), so the metrics show exactly one miss and one hit and the cache holds a single entry.
   */
  @Test
  public void flagOn_repeatedSelectHitsCacheSecondTime() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(3);

    session.begin();
    var sql = "SELECT FROM " + CLASS_NAME;

    var first = countQuery(sql);
    assertEquals("first query returns all seeded rows", 3, first);

    var cache = tx().getQueryResultCache();
    assertNotNull("cache must exist with the flag on", cache);
    assertEquals("one entry populated after the first query", 1, cache.size());
    assertEquals("first query is a miss", 1, cache.getMetrics().getMisses());
    assertEquals("first query is not a hit", 0, cache.getMetrics().getHits());

    var second = countQuery(sql);
    assertEquals("second query returns the same rows", 3, second);
    assertEquals("second query is served from cache (a hit)", 1, cache.getMetrics().getHits());
    assertEquals("no further miss on the second query", 1, cache.getMetrics().getMisses());
    assertEquals("still one entry", 1, cache.size());

    session.rollback();
  }

  /**
   * A {@code newEntity} that adds a matching record between two identical queries is reflected in the
   * second view: the second query returns one more row than the first, proving the delta build
   * merges the in-transaction CREATE into the cached result rather than replaying the stale frozen
   * set.
   */
  @Test
  public void flagOn_mutationBetweenQueriesReflectedInSecondView() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(2);

    session.begin();
    var sql = "SELECT FROM " + CLASS_NAME;

    var before = countQuery(sql);
    assertEquals(2, before);

    // Add a new matching record inside the same transaction, after the entry was populated.
    var added = session.newEntity(CLASS_NAME);
    added.setProperty(FIELD, 99);

    var after = countQuery(sql);
    assertEquals("second view reflects the in-tx CREATE via the delta build", 3, after);

    session.rollback();
  }

  /**
   * Commit runs the transaction-end clear sink, which wipes the cache. The cache reference is
   * captured before commit because the transaction's state is reset by the end path.
   */
  @Test
  public void flagOn_commitClearsCache() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(1);

    session.begin();
    countQuery("SELECT FROM " + CLASS_NAME);
    var cache = tx().getQueryResultCache();
    assertNotNull(cache);
    assertEquals("entry present before commit", 1, cache.size());

    session.commit();
    assertEquals("commit clears the cache", 0, cache.size());
  }

  /** Rollback runs the same transaction-end clear sink and wipes the cache. */
  @Test
  public void flagOn_rollbackClearsCache() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(1);

    session.begin();
    countQuery("SELECT FROM " + CLASS_NAME);
    var cache = tx().getQueryResultCache();
    assertNotNull(cache);
    assertEquals("entry present before rollback", 1, cache.size());

    session.rollback();
    assertEquals("rollback clears the cache", 0, cache.size());
  }

  /**
   * A re-entrant {@code query()} issued while a cache code path is already on the stack (simulated by
   * entering the cache scope directly) bypasses the cache: no entry is created and no lookup is
   * counted, because the re-entrancy depth guard short-circuits the gate to uncached execution.
   */
  @Test
  public void flagOn_reentrantQueryBypassesCache() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(2);

    session.begin();
    // Force the re-entrancy condition the session's own bracketing would create around a UDF in a
    // WHERE clause: depth > 0 means any query() must bypass the cache entirely.
    tx().enterCacheCode();
    try {
      var rows = countQuery("SELECT FROM " + CLASS_NAME);
      assertEquals("re-entrant query still returns correct rows uncached", 2, rows);
    } finally {
      tx().exitCacheCode();
    }

    var cache = tx().getQueryResultCache();
    assertNotNull(cache);
    assertEquals("re-entrant query created no cache entry", 0, cache.size());
    assertEquals("re-entrant query recorded no miss", 0, cache.getMetrics().getMisses());
    assertEquals("re-entrant query recorded no hit", 0, cache.getMetrics().getHits());

    session.rollback();
  }

  // ===========================================================================
  // Flag OFF — zero behaviour change
  // ===========================================================================

  /**
   * With the flag off the transaction never allocates a cache: {@code getQueryResultCache()} stays
   * null and two identical queries behave exactly as they did before the feature existed, each
   * returning the full result independently.
   */
  @Test
  public void flagOff_cacheFieldStaysNullAndBehaviourUnchanged() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seed(4);

    session.begin();
    var sql = "SELECT FROM " + CLASS_NAME;

    var first = countQuery(sql);
    var second = countQuery(sql);

    assertEquals("both queries return all rows", 4, first);
    assertEquals("repeated query is identical with the flag off", first, second);
    assertNull("no cache is allocated when the flag is off", tx().getQueryResultCache());

    session.rollback();
  }
}
