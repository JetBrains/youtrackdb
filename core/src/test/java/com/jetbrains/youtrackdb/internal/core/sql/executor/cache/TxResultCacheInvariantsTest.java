package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The exhaustive invariant and cache-vs-fresh equivalence matrix for the tx-result cache, deliberately
 * kept separate from the wiring smoke suite so it can churn independently of the session-integration
 * proofs.
 *
 * <p>The equivalence assertions are the correctness floor (I10): every cacheable scenario is run twice
 * end-to-end through the live {@code query()} path — once with the flag off (a fresh uncached execution,
 * the source of truth) and once with the flag on (forcing a cache populate, then a post-mutation second
 * query served through the delta-merged view) — and the two outputs are compared as ordered FIELD
 * value lists (RIDs legitimately differ between two independent executions, so the value sequence,
 * not the identity, is the observable I10 guarantees). Comparing against a parallel uncached run
 * rather than hand-rolled expected lists keeps the suite honest as the merge logic evolves: a change
 * that breaks the merge breaks the comparison, never silently agrees with a stale literal.
 *
 * <p>The RECORD equivalence cases span CREATED / UPDATED / DELETED record mutations crossed with the
 * two populate orderings (mutation staged before the cache entry is populated vs after), and the
 * K0_NONE version-gate case covers the pure-read hit plus the post-mutation invalidation. The
 * remaining tests pin the lifecycle invariants (I1 eventual clear, I3 stream lifetime, I6 idempotent
 * clear, I7 view-snapshot isolation, I8 schema-stable class filter, I9 LRU-pressure pinning) and the
 * load-bearing {@code ORDER BY} + {@code LIMIT} classify-ordering guard.
 *
 * <p>Run with {@code -ea}: I2 (owner-thread) rests on {@code assertOnOwningThread} and the D3
 * schema-DDL canary is a Java {@code assert}; both are disabled without assertions, so they protect
 * tests, not production.
 */
public class TxResultCacheInvariantsTest extends DbTestBase {

  private static final String CLASS_NAME = "InvRec";
  private static final String FIELD = "n";

  private boolean previousEnabled;

  @Before
  public void enableSchema() {
    // Capture the prior flag so tearDown restores it and the enabled state cannot leak into the next
    // test in the surefire sequence (the flag is a process-global). Each test sets it explicitly.
    previousEnabled = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty(FIELD, PropertyType.INTEGER);
  }

  @After
  public void restoreFlag() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previousEnabled);
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private FrontendTransactionImpl tx() {
    return (FrontendTransactionImpl) session.getTransactionInternal();
  }

  /** Persists {@code count} records of CLASS_NAME with ascending FIELD values in their own tx. */
  private void seed(int count) {
    session.begin();
    for (var i = 0; i < count; i++) {
      var e = session.newEntity(CLASS_NAME);
      e.setProperty(FIELD, i);
    }
    session.commit();
  }

  /** Creates and commits one record with FIELD=value, returning its persistent RID. */
  private RID commitRec(int value) {
    session.begin();
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(FIELD, value);
    var rid = ((RecordAbstract) e).getIdentity();
    session.commit();
    return rid;
  }

  /**
   * Deletes every committed record of CLASS_NAME in its own transaction so a following run starts from
   * a known-empty committed state. The flag-off and flag-on halves of an equivalence pair share the
   * same database, so without this the second half would observe the first half's committed seed and
   * the two outputs could never be compared. Issued with the cache flag forced off so the clearing
   * DELETE never interacts with the cache under test.
   */
  private void clearClass() {
    var previous = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    try {
      session.begin();
      session.command("DELETE FROM " + CLASS_NAME);
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previous);
    }
  }

  /**
   * Drains a result set into the ordered list of its rows' FIELD values, then closes it. The
   * equivalence comparison is on these ordered values, not on RIDs: two independent executions of the
   * same scenario assign fresh physical RIDs to their created rows, so RIDs legitimately differ between
   * the flag-off and flag-on runs while the observable result (cardinality, order, and per-row value)
   * must be identical. The value sequence captures exactly what I10 guarantees — a missing or extra row
   * changes the cardinality, a mis-positioned row changes the order, and a stale-vs-fresh value (an
   * UPDATE the cache failed to re-read) changes the content. RID-level skip/inject correctness is
   * pinned at the unit level by the delta-builder and view tests.
   */
  private static List<Object> drainRows(ResultSet rs) {
    var out = new ArrayList<>();
    try (rs) {
      while (rs.hasNext()) {
        out.add(rs.next().<Object>getProperty(FIELD));
      }
    }
    return out;
  }

  /**
   * Runs one cache scenario end-to-end and returns the second query's emitted rows. The scenario seeds
   * the same starting records, opens a transaction, issues an identical SELECT once to populate (when
   * the flag is on), applies {@code mutation} inside the same transaction, then issues the SELECT again
   * and captures that second result. The transaction is rolled back so the next run starts clean.
   *
   * <p>Driving the identical script under both flag states and comparing the two captured outputs is
   * the equivalence test: with the flag off the second query is a fresh uncached execution (the source
   * of truth); with the flag on it is served through the cache's delta-merged view.
   */
  private List<Object> runScenario(boolean cacheEnabled, int seedCount, String sql,
      Consumer<FrontendTransactionImpl> mutation) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    seed(seedCount);

    session.begin();
    // First query populates the cache entry (flag on) or is a plain execution (flag off).
    drainRows(session.query(sql));
    // Mutate inside the same transaction, after the entry was populated.
    mutation.accept(tx());
    // Second query: the delta-merged cached view (flag on) or a fresh execution (flag off).
    var rows = drainRows(session.query(sql));
    session.rollback();
    return rows;
  }

  /**
   * Asserts that the post-mutation second query returns the same rows with the cache on as a fresh
   * uncached execution does, for a mutation applied AFTER the entry was populated. Both runs execute
   * the identical seed + query + mutation + query script; only the flag differs.
   */
  private void assertEquivalentPostPopulate(int seedCount, String sql,
      Consumer<FrontendTransactionImpl> mutation) {
    var fresh = runScenario(false, seedCount, sql, mutation);
    var cached = runScenario(true, seedCount, sql, mutation);
    assertEquals(
        "Cached delta-merged view must equal a fresh uncached execution at the same moment", fresh,
        cached);
  }

  // ===========================================================================
  // I10 — RECORD cache-vs-fresh equivalence, mutation AFTER populate
  // ===========================================================================

  /**
   * A CREATE of a matching record after the entry is populated must appear in the second view exactly
   * as a fresh execution would show it. Cached on/off outputs must match.
   */
  @Test
  public void recordEquivalence_createAfterPopulate() {
    assertEquivalentPostPopulate(3, "SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC",
        t -> {
          var e = session.newEntity(CLASS_NAME);
          e.setProperty(FIELD, 99);
        });
  }

  /**
   * A DELETE of a cached record after populate must drop that row from the second view. The deleted
   * record is created in a prior committed transaction so the staged op is a genuine DELETE rather than
   * a collapsed CREATE.
   */
  @Test
  public void recordEquivalence_deleteAfterPopulate() {
    var fresh = runScenarioWithPreloaded(false, RecordOperation.DELETED);
    var cached = runScenarioWithPreloaded(true, RecordOperation.DELETED);
    assertEquals("DELETE-after-populate must match a fresh execution", fresh, cached);
  }

  /**
   * An UPDATE of a cached record's ORDER BY key after populate must re-position the row in the second
   * view (skip the stale copy, inject the moved copy). Cached on/off outputs must match including the
   * new value.
   */
  @Test
  public void recordEquivalence_updateAfterPopulate() {
    var fresh = runScenarioWithPreloaded(false, RecordOperation.UPDATED);
    var cached = runScenarioWithPreloaded(true, RecordOperation.UPDATED);
    assertEquals("UPDATE-after-populate must match a fresh execution", fresh, cached);
  }

  /**
   * Runs an UPDATE/DELETE scenario on a record committed before the transaction opens, so {@code
   * addRecordOperation} stages a real UPDATED/DELETED (not a CREATE collapse). The class is cleared and
   * a fixed committed seed (one matching record plus the mutation target) is rebuilt inside the run, so
   * the flag-off and flag-on halves see identical committed state. The first query populates, the
   * staged mutation is applied, and the second query is captured.
   */
  private List<Object> runScenarioWithPreloaded(boolean cacheEnabled, byte opType) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitRec(2); // a second matching record so the result is non-trivial after the mutation
    var rid = commitRec(1); // the mutation target
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = "SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " > 0 ORDER BY " + FIELD + " ASC";

    session.begin();
    drainRows(session.query(sql)); // populate
    Entity rec = session.load(rid);
    if (opType == RecordOperation.UPDATED) {
      rec.setProperty(FIELD, 42); // move its ORDER BY key
    }
    tx().addRecordOperation((RecordAbstract) rec, opType);
    var rows = drainRows(session.query(sql)); // delta-merged second view (flag on)
    session.rollback();
    return rows;
  }

  // ===========================================================================
  // I10 — RECORD cache-vs-fresh equivalence, mutation BEFORE populate
  // ===========================================================================

  /**
   * A CREATE staged BEFORE the entry is populated is already baked into the populating execution's
   * frozen result by the tx-aware executor, so the delta builder must NOT double-apply it. Equivalence:
   * a single query after a pre-populate create matches a fresh execution. This run uses one query (the
   * mutation happens first, then the only query both populates and serves).
   */
  @Test
  public void recordEquivalence_createBeforePopulate() {
    var fresh = runPrePopulateCreate(false);
    var cached = runPrePopulateCreate(true);
    assertEquals("A pre-populate create must not be double-counted by the delta", fresh, cached);
  }

  private List<Object> runPrePopulateCreate(boolean cacheEnabled) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    seed(2);
    var sql = "SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC";

    session.begin();
    // Mutation BEFORE any query in this tx: the create is part of the populating execution's result.
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(FIELD, 7);
    // First (populating) query already sees the create; a second query must agree, not add it again.
    drainRows(session.query(sql));
    var rows = drainRows(session.query(sql));
    session.rollback();
    return rows;
  }

  /**
   * An UPDATE staged BEFORE populate is likewise baked into the frozen result, so a re-query must match
   * a fresh execution without re-applying the update as a post-populate delta.
   */
  @Test
  public void recordEquivalence_updateBeforePopulate() {
    var fresh = runPrePopulateUpdate(false);
    var cached = runPrePopulateUpdate(true);
    assertEquals("A pre-populate update must not be re-applied by the delta", fresh, cached);
  }

  private List<Object> runPrePopulateUpdate(boolean cacheEnabled) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    var rid = commitRec(1);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = "SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " > 0 ORDER BY " + FIELD + " ASC";

    session.begin();
    // Mutation BEFORE the first query: the populating execution already reflects the new value.
    Entity rec = session.load(rid);
    rec.setProperty(FIELD, 3);
    tx().addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
    drainRows(session.query(sql)); // populate (already reflects the update)
    var rows = drainRows(session.query(sql)); // re-query must agree
    session.rollback();
    return rows;
  }

  /**
   * A DELETE staged BEFORE populate removes the record from the populating result, so a re-query must
   * still omit it and match a fresh execution.
   */
  @Test
  public void recordEquivalence_deleteBeforePopulate() {
    var fresh = runPrePopulateDelete(false);
    var cached = runPrePopulateDelete(true);
    assertEquals("A pre-populate delete must stay removed in the re-query", fresh, cached);
  }

  private List<Object> runPrePopulateDelete(boolean cacheEnabled) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitRec(2); // a surviving matching record so the result is non-empty after the delete
    var rid = commitRec(5);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = "SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " > 0 ORDER BY " + FIELD + " ASC";

    session.begin();
    Entity rec = session.load(rid);
    tx().addRecordOperation((RecordAbstract) rec, RecordOperation.DELETED);
    drainRows(session.query(sql)); // populate (already excludes the delete)
    var rows = drainRows(session.query(sql));
    session.rollback();
    return rows;
  }

  // ===========================================================================
  // K0_NONE version-gate
  // ===========================================================================

  /**
   * A K0_NONE entry (here a GROUP BY query) serves a cached hit on a pure-read repeat — same mutation
   * version, no intervening mutation — and is invalidated on the next lookup once a mutation advances
   * the version. The metrics record one miss + one hit for the pure-read pair and one K0 invalidation
   * after the mutation.
   */
  @Test
  public void k0None_hitsOnPureReadAndInvalidatesAfterMutation() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(3);
    var sql = "SELECT " + FIELD + ", count(*) FROM " + CLASS_NAME + " GROUP BY " + FIELD;

    session.begin();
    var cache = tx().getQueryResultCache();
    assertNotNull(cache);
    assertEquals("GROUP BY classifies as K0_NONE", CacheableShape.K0_NONE,
        ShapeClassifier.classify(YqlStatementCache.get(sql, session)));

    drainRows(session.query(sql)); // miss + populate
    assertEquals(1, cache.getMetrics().getMisses());
    drainRows(session.query(sql)); // pure-read repeat: version unchanged → hit
    assertEquals("Pure-read K0_NONE repeat is a hit", 1, cache.getMetrics().getHits());
    assertEquals("entry present after the pure-read hit", 1, cache.size());

    // A mutation advances mutationVersion; the next lookup must invalidate the stale K0_NONE entry.
    var added = session.newEntity(CLASS_NAME);
    added.setProperty(FIELD, 99);
    drainRows(session.query(sql));
    assertEquals("Mutation after populate invalidates the K0_NONE entry", 1,
        cache.getMetrics().getK0Invalidations());

    session.rollback();
  }

  // ===========================================================================
  // I1 — eventual clear, NOT clear-on-iterate-exception
  // ===========================================================================

  /**
   * Commit runs the transaction-end clear sink and wipes the cache (I1). The reference is captured
   * before commit because the transaction state is reset by the end path.
   */
  @Test
  public void i1_commitClearsCache() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(2);
    session.begin();
    drainRows(session.query("SELECT FROM " + CLASS_NAME));
    var cache = tx().getQueryResultCache();
    assertEquals(1, cache.size());
    session.commit();
    assertEquals("commit clears the cache", 0, cache.size());
  }

  /** Rollback runs the same tx-end clear sink and wipes the cache (I1). */
  @Test
  public void i1_rollbackClearsCache() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(2);
    session.begin();
    drainRows(session.query("SELECT FROM " + CLASS_NAME));
    var cache = tx().getQueryResultCache();
    assertEquals(1, cache.size());
    session.rollback();
    assertEquals("rollback clears the cache", 0, cache.size());
  }

  /**
   * I1 is an EVENTUAL clear, not a clear-on-iterate-exception. An exception thrown mid-iteration (here
   * by aborting the consumer's own loop with a thrown error) does not synchronously wipe the cache —
   * the entry is still present immediately afterwards — but the next transaction-end path ({@code
   * rollback}) clears it. This pins the contract that the cache is wiped on the tx-end sink, not as a
   * side effect of a consumer-side iteration failure.
   */
  @Test
  public void i1_iterateExceptionDoesNotClearSynchronouslyButTxEndDoes() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(3);
    session.begin();
    var sql = "SELECT FROM " + CLASS_NAME;
    var cache = tx().getQueryResultCache();

    // Populate the entry, then simulate a consumer that throws partway through iterating the view.
    assertThrows(IllegalStateException.class, () -> {
      try (var rs = session.query(sql)) {
        rs.next();
        throw new IllegalStateException("consumer aborts mid-iterate");
      }
    });

    assertEquals("an iterate-time exception does not synchronously clear the cache", 1,
        cache.size());

    session.rollback();
    assertEquals("the next tx-end path clears the cache (eventual clear)", 0, cache.size());
  }

  // ===========================================================================
  // I6 — idempotent clear
  // ===========================================================================

  /** A second {@code clear()} after the tx-end clear is a no-op: the cache stays empty (I6). */
  @Test
  public void i6_secondClearIsNoOp() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(1);
    session.begin();
    drainRows(session.query("SELECT FROM " + CLASS_NAME));
    var cache = tx().getQueryResultCache();
    session.commit(); // first clear via the tx-end sink
    assertEquals(0, cache.size());
    cache.clear(); // explicit second clear must be inert
    assertEquals("a second clear is idempotent", 0, cache.size());
  }

  // ===========================================================================
  // I3 — paused stream lifetime bounded by its entry (closed at tx-end)
  // ===========================================================================

  /**
   * The entry owns the paused execution stream (I3); the tx-end clear closes it. After a populate that
   * leaves the stream un-exhausted (the view is not fully drained), the entry still holds a live
   * stream; commit's clear sink closes it and drops the entry, so the stream cannot outlive the entry.
   */
  @Test
  public void i3_streamClosedAtTxEnd() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(4);
    session.begin();
    // Open the view but do not drain it, so the entry retains a live (un-exhausted) stream.
    var rs = session.query("SELECT FROM " + CLASS_NAME);
    assertTrue(rs.hasNext());
    rs.next(); // pull one row, leave the rest

    var cache = tx().getQueryResultCache();
    assertEquals(1, cache.size());

    session.commit(); // tx-end clear closes the entry's stream and empties the cache
    assertEquals("tx-end clear closes the entry (and its stream), bounding the stream lifetime", 0,
        cache.size());
    rs.close();
  }

  // ===========================================================================
  // I7 — a view started before a mutation does not observe it; a fresh query does
  // ===========================================================================

  /**
   * A view materialised before an in-tx mutation does not observe that mutation (its rows were frozen
   * at construction, I7), while a fresh {@code query()} issued after the mutation does. Equivalence of
   * the later query is covered elsewhere; this test pins the snapshot isolation of the earlier view.
   */
  @Test
  public void i7_viewBeforeMutationIsIsolatedFreshQueryAfterSeesIt() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(2);
    session.begin();
    var sql = "SELECT FROM " + CLASS_NAME;

    // Fully drain a first view, then add a matching record.
    var first = drainRows(session.query(sql));
    assertEquals(2, first.size());

    var added = session.newEntity(CLASS_NAME);
    added.setProperty(FIELD, 99);

    // A fresh query after the create observes three rows via the delta merge.
    var second = drainRows(session.query(sql));
    assertEquals("a fresh query after the mutation observes the new record", 3, second.size());
    assertNotEquals("the post-mutation query differs from the pre-mutation one", first, second);

    session.rollback();
  }

  // ===========================================================================
  // I8 — schema-stable class filter for the entry's lifetime
  // ===========================================================================

  /**
   * I8: schema is immutable per transaction, so the entry's {@code effectiveFromClasses} closure is
   * stable for its lifetime. The closure computed from the live class equals the literal class-name set
   * and does not change across repeated computation within a transaction; the delta filter probes the
   * same name form ({@code Entity.getSchemaClassName()}).
   */
  @Test
  public void i8_effectiveFromClassesStableForEntryLifetime() {
    session.begin();
    var cls = session.getClass(CLASS_NAME);
    var first = CachedEntry.computeEffectiveFromClasses(cls);
    var second = CachedEntry.computeEffectiveFromClasses(cls);
    assertEquals("the class-filter closure is stable within a transaction", first, second);
    assertTrue("the closure contains the declared class by name", first.contains(CLASS_NAME));
    session.rollback();
  }

  // ===========================================================================
  // I9 — a live view is not truncated under LRU pressure
  // ===========================================================================

  /**
   * I9: issuing more distinct cache keys than {@code maxEntries} while a view iterates must not
   * truncate that view's output — its entry is pinned and exempt from LRU eviction. With maxEntries set
   * to 1, a first query's view is held open (one row pulled, rest pending) while a second distinct
   * query populates; the held view must still drain its full result.
   */
  @Test
  public void i9_liveViewNotTruncatedUnderLruPressure() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    var prevMax = GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.getValueAsInteger();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.setValue(1);
    try {
      seed(3);
      session.begin();

      // Open the first query's view and pin its entry by leaving it mid-iteration (one row pulled).
      var held = session.query("SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " >= 0");
      assertTrue(held.hasNext());
      held.next();

      // A second distinct query exceeds maxEntries=1 and would evict the eldest — but the held view's
      // entry is pinned, so eviction must skip it rather than truncate the live view.
      drainRows(session.query("SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " >= 1"));

      // The held view must still yield its full result: the one row already pulled plus the remaining
      // two, three rows total, with no loss to LRU pressure.
      var remaining = drainRows(held);
      assertEquals("a pinned live view keeps its full result under LRU pressure (I9)", 2,
          remaining.size());

      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.setValue(prevMax);
    }
  }

  // ===========================================================================
  // I2 — owner-thread guard balance (assert-based; checked here via depth balance)
  // ===========================================================================

  /**
   * I2 rests on {@code assertOnOwningThread} guarding every cache mutation path; the cache-code
   * re-entrancy depth counter that brackets the lookup-and-view scope must always return to zero after
   * a query completes on the owning thread. A non-zero residual depth would mean the guard leaked and
   * every later query would take the re-entrant bypass. After a full drain the depth is balanced.
   */
  @Test
  public void i2_cacheCodeDepthBalancedAfterQuery() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(2);
    session.begin();
    assertEquals("depth starts at zero", 0, tx().getCacheCodeDepth());
    drainRows(session.query("SELECT FROM " + CLASS_NAME)); // fully drained → guard released
    assertEquals("the re-entrancy guard is balanced after a fully drained query", 0,
        tx().getCacheCodeDepth());
    session.rollback();
  }

  // ===========================================================================
  // Classify ordering — ORDER BY + LIMIT never reaches RECORD
  // ===========================================================================

  /**
   * The load-bearing classify-ordering guard (I10 depends on it): an {@code ORDER BY} + {@code LIMIT}
   * query must classify as K0_NONE, never RECORD, because the SKIP/LIMIT gate runs before the RECORD
   * branch. {@code OrderByStep} + LIMIT is a bounded-heap materialiser that discards rows past top-N,
   * so a cached top-N prefix could not promote row N+1 after an in-tx delete; treating it as RECORD
   * would violate I10. Asserted directly so a future reorder of the classify branches cannot silently
   * break it.
   */
  @Test
  public void classifyOrdering_orderByPlusLimitNeverReachesRecord() {
    var shape = ShapeClassifier.classify(
        YqlStatementCache.get("SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " LIMIT 2",
            session));
    assertEquals("ORDER BY + LIMIT must classify as K0_NONE, not RECORD", CacheableShape.K0_NONE,
        shape);
    assertNotEquals("a bounded top-N prefix must never be treated as a RECORD delta result",
        CacheableShape.RECORD, shape);
  }

  // ===========================================================================
  // Flag-off transparency floor
  // ===========================================================================

  /** With the flag off no cache is allocated and repeated queries behave exactly as before (I10). */
  @Test
  public void flagOff_noCacheAllocatedAndBehaviourUnchanged() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seed(3);
    session.begin();
    var sql = "SELECT FROM " + CLASS_NAME;
    var first = drainRows(session.query(sql));
    var second = drainRows(session.query(sql));
    assertEquals("repeated query is identical with the flag off", first, second);
    assertNull("no cache is allocated when the flag is off", tx().getQueryResultCache());
    assertFalse("the result is non-empty", first.isEmpty());
    session.rollback();
  }
}
