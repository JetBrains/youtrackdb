package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.lang.reflect.Method;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

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
@Category(SequentialTest.class)
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

  // ===========================================================================
  // Re-entrancy guard balance on the populate no-view branch
  // ===========================================================================

  /**
   * Guard-balance regression test for the populate fallback that builds no view. The cache gate
   * enters the tx re-entrancy guard around the lookup-and-populate scope and normally hands that
   * guard to the {@code CachedResultSetView} it returns, which releases it on close/exhaustion. But
   * the populate path has a fallback: when real execution does not return a {@code LocalResultSet}
   * (so the cache cannot lift its stream), it returns the unwrapped result and builds no view. On
   * that branch no view exists to release the guard, so the gate must release it itself in the
   * finally; the prior guard-handoff logic transferred ownership unconditionally and leaked the
   * depth bump for the rest of the transaction, silently disabling the cache.
   *
   * <p>SELECT/MATCH always return a {@code LocalResultSet} today, so this branch is unreachable
   * through normal SQL; the test reaches it by invoking the private {@code serveThroughCache} with a
   * deterministic, RECORD-classified SELECT statement whose execution is overridden to return a
   * plain non-{@code LocalResultSet} result set. It asserts the gate returns that unwrapped result
   * (no view built) and, critically, that {@code getCacheCodeDepth() == 0} once the call returns —
   * proving the guard was released exactly once on the no-view branch and is not leaked.
   */
  @Test
  public void populateNoViewBranchReleasesGuardExactlyOnce() throws Exception {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(1);

    session.begin();
    var tx = tx();
    assertNotNull("cache must exist with the flag on", tx.getQueryResultCache());
    assertEquals("guard starts balanced before the gated call", 0, tx.getCacheCodeDepth());

    // A bare SELECT statement with no projection/group-by/skip/limit classifies as RECORD and is
    // deterministic (empty AST), so it passes every gate check and reaches the populate path. Its
    // execution is overridden to return an InternalResultSet (a concrete ResultSet that is NOT a
    // LocalResultSet), forcing the populate no-view fallback branch.
    var nonLocalResult = new InternalResultSet(session);
    var statement = new NonLocalResultSelectStatement(nonLocalResult);

    var served = invokeServeThroughCache(session, statement, null);

    assertSame(
        "no-view branch returns the unwrapped non-LocalResultSet result, not a view",
        nonLocalResult,
        served);
    assertEquals(
        "re-entrancy guard is released exactly once on the no-view branch (no leak)",
        0,
        tx.getCacheCodeDepth());

    // A subsequent real query in the same transaction must still reach the cache: a leaked guard
    // would force every later query() onto the depth > 0 re-entrant bypass, leaving the cache dead.
    var cache = tx.getQueryResultCache();
    countQuery("SELECT FROM " + CLASS_NAME);
    assertEquals(
        "follow-up query still populates the cache, proving the guard did not leak",
        1,
        cache.size());

    session.rollback();
  }

  /** Reflectively invokes the private {@code serveThroughCache(SQLStatement, Object)} gate. */
  private static ResultSet invokeServeThroughCache(
      DatabaseSessionEmbedded session, SQLStatement statement, Object args) throws Exception {
    Method m =
        DatabaseSessionEmbedded.class.getDeclaredMethod(
            "serveThroughCache", SQLStatement.class, Object.class);
    m.setAccessible(true);
    return (ResultSet) m.invoke(session, statement, args);
  }

  /**
   * A SELECT statement whose execution returns a caller-supplied non-{@code LocalResultSet} result.
   * Empty AST keeps it RECORD-classified and deterministic so it passes the cache gate, while the
   * overridden {@code execute} drives the populate no-view fallback branch under test.
   */
  private static final class NonLocalResultSelectStatement extends SQLSelectStatement {

    private final ResultSet result;

    private NonLocalResultSelectStatement(ResultSet result) {
      super(-1);
      this.result = result;
    }

    @Override
    public boolean isIdempotent() {
      return true;
    }

    @Override
    public ResultSet execute(
        DatabaseSessionEmbedded session,
        Object[] args,
        CommandContext parentContext,
        boolean usePlanCache) {
      return result;
    }
  }
}
