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
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YqlStatementCache;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * The exhaustive invariant and cache-vs-fresh equivalence matrix for the tx-result cache, deliberately
 * kept separate from the wiring smoke suite so it can churn independently of the session-integration
 * proofs.
 *
 * <p>The equivalence assertions are the correctness floor: every cacheable scenario is run twice
 * end-to-end through the live {@code query()} path — once with the flag off (a fresh uncached execution,
 * the source of truth) and once with the flag on (forcing a cache populate, then a post-mutation second
 * query served through the delta-merged view) — and the two outputs are compared as ordered FIELD
 * value lists (RIDs legitimately differ between two independent executions, so the value sequence,
 * not the identity, is the observable guaranted). Comparing against a parallel uncached run
 * rather than hand-rolled expected lists keeps the suite honest as the merge logic evolves: a change
 * that breaks the merge breaks the comparison, never silently agrees with a stale literal.
 *
 * <p>The RECORD equivalence cases span CREATED / UPDATED / DELETED record mutations crossed with the
 * two populate orderings (mutation staged before the cache entry is populated vs after), and the
 * K0_NONE version-gate case covers the pure-read hit plus the post-mutation invalidation. The
 * remaining tests pin the lifecycle invariants (eventual clear, stream lifetime, idempotent
 * clear, view-snapshot isolation, schema-stable class filter, LRU-pressure pinning) and the
 * load-bearing {@code ORDER BY} + {@code LIMIT} classify-ordering guard.
 *
 * <p>Run with {@code -ea}: owner-thread rests on {@code assertOnOwningThread} and the
 * schema-DDL canary is a Java {@code assert}; both are disabled without assertions, so they protect
 * tests, not production.
 */
@Category(SequentialTest.class)
public class TxResultCacheInvariantsTest extends DbTestBase {

  private static final String CLASS_NAME = "InvRec";
  private static final String FIELD = "n";

  /** A WHERE-filtered SELECT (matches FIELD >= 0); the negative value -1 is the not-matching probe. */
  private static final String FILTER_SQL =
      "SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " >= 0";

  /** The same filter under an ORDER BY (a blocking sort: membership frozen at populate). */
  private static final String FILTER_ORDERED_SQL = FILTER_SQL + " ORDER BY " + FIELD + " ASC";

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
   * must be identical. The value sequence captures exactly what cache-vs-fresh equivalence requires — a
   * missing or extra row
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
  // RECORD cache-vs-fresh equivalence, mutation AFTER populate
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
   * A CREATE matching the query when the populated result was EMPTY (zero seeded rows): the merge must
   * emit exactly the one injected row from an empty cached prefix, matching a fresh execution. This
   * drives {@code computeNextRecord} with {@code cacheHead == null} from the very first iteration, a
   * distinct entry into the merge loop from the populated-prefix cases the other tests cover.
   */
  @Test
  public void recordEquivalence_createIntoEmptyResultAfterPopulate() {
    assertEquivalentPostPopulate(0,
        "SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " >= 0 ORDER BY " + FIELD + " ASC",
        t -> {
          var e = session.newEntity(CLASS_NAME);
          e.setProperty(FIELD, 5);
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
  // Live stream tail driven after a partial-drain populate
  // ===========================================================================

  /**
   * Pulls up to {@code pullCount} rows from {@code rs} and then closes it WITHOUT draining the rest.
   * With the cache flag on this is the load-bearing setup the fully drained scenarios never reach: the
   * view's close releases the pin and the re-entrancy guard but never closes the entry's shared stream,
   * so the entry stays cached with a live, partially-pulled stream tail.
   */
  private static void drainPartial(ResultSet rs, int pullCount) {
    try (rs) {
      for (var i = 0; i < pullCount && rs.hasNext(); i++) {
        rs.next();
      }
    }
  }

  /**
   * The FIELD values sorted ascending. The partial-drain scenarios use an unordered SELECT (no ORDER
   * BY) on purpose: an ORDER BY forces a blocking sort that drains the source at the first row,
   * exhausting the stream before the mutation and erasing the live tail under test. An unordered query
   * does not contract a row order, so the cache-on and fresh outputs may legitimately differ in order;
   * sorting normalises that while preserving cardinality, so a row emitted twice — the duplication these
   * tests guard against — still surfaces as a multiset mismatch.
   */
  private static List<Object> sortedValues(List<Object> values) {
    var copy = new ArrayList<>(values);
    copy.sort((a, b) -> Integer.compare((Integer) a, (Integer) b));
    return copy;
  }

  /** Asserts the partial-drain populate left exactly one cached entry with a still-live stream. */
  private void assertPartialDrainLeftLiveEntry() {
    var cache = tx().getQueryResultCache();
    assertNotNull("the partial-drain populate must leave a cache entry", cache);
    assertEquals("the partially drained entry stays cached after the view closes", 1, cache.size());
    assertFalse("the partial drain must leave the entry's stream live (not exhausted)",
        cache.entriesForTest().iterator().next().isExhausted());
  }

  /** Asserts the second query was served from cache, so it drove the live stream tail under test. */
  private void assertSecondQueryWasCacheHit() {
    assertTrue("the re-query must be served from cache (a hit), driving the live stream tail",
        tx().getQueryResultCache().getMetrics().getHits() >= 1);
  }

  /** The single live cache entry (valid with the flag on, after exactly one populate). */
  private CachedEntry theCachedEntry() {
    return tx().getQueryResultCache().entriesForTest().iterator().next();
  }

  /** The staged op type for {@code rid} in the current transaction, or {@code -1} if none is staged. */
  private byte stagedOpType(RID rid) {
    for (var op : tx().getRecordOperationsInternal()) {
      if (rid.equals(op.record.getIdentity())) {
        return op.type;
      }
    }
    return (byte) -1;
  }

  // ===========================================================================
  // One end-to-end equivalence case per DeltaBuilder dispatch row
  // ===========================================================================
  // The DeltaBuilder switch dispatches on (op type, cached-at-build, matches-WHERE-after). Every row is
  // already covered at the unit level (DeltaBuilderTest for the (skipSet, injectList) it produces;
  // CachedResultSetViewTest for the merge over a synthetic stream). These cases close the end-to-end gap:
  // a real query() populate, a real staged mutation, a real second query(), compared to a fresh uncached
  // execution. cached=true rows fully drain the populate (the record is in the materialized prefix);
  // cached=false rows partially drain then close it, leaving a live stream tail, and run under both an
  // ORDER BY query (a blocking sort: membership frozen at populate) and a plain query (a lazy scan),
  // which stream the tail differently. Each cache-on run asserts the staged (op type, cached-at-build)
  // preconditions so the case provably exercises its intended row rather than passing by coincidence.
  // (CREATED/false/true, UPDATED/true/true, DELETED/true are covered by the cases above; the no-ORDER-BY
  // CREATED/false/true and UPDATED/false/true live-tail cases are covered by the partial-drain tests
  // above this section.)

  /**
   * CREATED / cached=true / matches. A record created IN the transaction before the populate (so it
   * lands in the materialized cached prefix) whose post-populate UPDATE collapses into the still-CREATED
   * op with a re-stamped version. Post-update value 15 still matches WHERE, so the delta skips the cached
   * copy and injects the moved row. Asserts the staged op stayed CREATED and the record is
   * cached-at-build, then checks the cache-on second query equals a fresh uncached execution.
   */
  @Test
  public void recordEquivalence_collapsedCreateStillMatchingAfterPopulate() {
    var fresh = runCollapsedCreate(false, 15);
    var cached = runCollapsedCreate(true, 15);
    assertEquals("collapsed CREATE that still matches must skip+inject to match a fresh execution",
        fresh, cached);
  }

  /**
   * Stages a collapsed pre-populate CREATE and applies a post-populate UPDATE to {@code postValue}.
   * Two committed records (10, 20) make the result non-trivial; the in-tx create (value 5) lands in the
   * cached prefix on the full drain. The post-populate {@code addRecordOperation(UPDATED)} collapses the
   * op (it stays CREATED) and re-stamps its version past the populate version. With {@code postValue >=
   * 0} the record still matches WHERE; with {@code postValue < 0} it leaves the filter.
   */
  private List<Object> runCollapsedCreate(boolean cacheEnabled, int postValue) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitRec(10);
    commitRec(20);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);

    session.begin();
    var x = session.newEntity(CLASS_NAME);
    x.setProperty(FIELD, 5); // matches WHERE >= 0, so the populate pulls it into the cached prefix
    drainRows(session.query(FILTER_ORDERED_SQL)); // full drain → x is cached-at-build
    var xRid = ((RecordAbstract) x).getIdentity();
    // Post-populate update collapses into the CREATED op (stays CREATED) and re-stamps the version.
    x.setProperty(FIELD, postValue);
    tx().addRecordOperation((RecordAbstract) x, RecordOperation.UPDATED);
    if (cacheEnabled) {
      assertEquals("the collapsed op must remain CREATED", RecordOperation.CREATED,
          stagedOpType(xRid));
      assertTrue("the pre-populate create must be cached-at-build",
          theCachedEntry().getCachedRids().contains(xRid));
    }
    var rows = drainRows(session.query(FILTER_ORDERED_SQL));
    if (cacheEnabled) {
      assertSecondQueryWasCacheHit();
    }
    session.rollback();
    return rows;
  }

  /**
   * CREATED / cached=true / does NOT match. Same collapsed pre-populate create, but the post-populate
   * UPDATE drives it out of WHERE (value -1), so the delta skips the cached copy with no inject. The
   * cache-on second query must drop the row exactly as a fresh execution does.
   */
  @Test
  public void recordEquivalence_collapsedCreateDrivenOutOfWhereAfterPopulate() {
    var fresh = runCollapsedCreate(false, -1);
    var cached = runCollapsedCreate(true, -1);
    assertEquals("collapsed CREATE driven out of WHERE must skip with no inject, matching fresh",
        fresh, cached);
  }

  /**
   * UPDATED / cached=true / does NOT match. A record committed before the transaction is pulled into
   * the cached prefix by the full drain, then a post-populate UPDATE drives it out of WHERE (value -1):
   * the delta skips the cached copy with no inject. The complement (UPDATED/true/matches) is covered by
   * {@code recordEquivalence_updateAfterPopulate}.
   */
  @Test
  public void recordEquivalence_cachedUpdateDrivenOutOfWhereAfterPopulate() {
    var fresh = runCachedUpdate(false, -1);
    var cached = runCachedUpdate(true, -1);
    assertEquals(
        "UPDATE that leaves WHERE must skip the cached copy with no inject, matching fresh",
        fresh, cached);
  }

  /**
   * Full-drain UPDATE of a record committed before the transaction (so it is cached-at-build). Two more
   * committed records (10, 20) keep the result non-trivial. After the full-drain populate the target is
   * updated to {@code postValue} and staged UPDATED; the cache-on run asserts the op is UPDATED and the
   * target is cached-at-build. {@code postValue >= 0} still matches WHERE; {@code postValue < 0} leaves
   * it.
   */
  private List<Object> runCachedUpdate(boolean cacheEnabled, int postValue) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    var targetRid = commitRec(5);
    commitRec(10);
    commitRec(20);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);

    session.begin();
    drainRows(session.query(FILTER_ORDERED_SQL)); // full drain → the target is cached-at-build
    Entity rec = session.load(targetRid);
    rec.setProperty(FIELD, postValue);
    tx().addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
    if (cacheEnabled) {
      assertEquals("staged op must be UPDATED", RecordOperation.UPDATED, stagedOpType(targetRid));
      assertTrue("the cached record must be cached-at-build",
          theCachedEntry().getCachedRids().contains(targetRid));
    }
    var rows = drainRows(session.query(FILTER_ORDERED_SQL));
    if (cacheEnabled) {
      assertSecondQueryWasCacheHit();
    }
    session.rollback();
    return rows;
  }

  // --- cached=false rows: partial-drain populate, live stream tail, both stream modes ---

  /**
   * Stages a cached=false dispatch row end to end and returns the second query's rows. A committed body
   * (values 0..3) plus, for UPDATE/DELETE, a committed target record (value 4). The populate is opened
   * and closed WITHOUT iterating, so the entry caches a fully un-pulled (live) stream and every committed
   * record is cached-at-build=false regardless of the storage scan order. The mutation is then applied to
   * a record outside the (empty) cached prefix: CREATE adds a fresh record, UPDATE re-values the target,
   * DELETE removes it. The cache-on run asserts the staged op type and that the mutated record is NOT
   * cached-at-build, then that the second query was a cache hit (so it drove the live tail).
   */
  private List<Object> runPartialDrainCachedFalse(boolean cacheEnabled, String sql, byte opType,
      int value) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seed(4); // committed body: values 0,1,2,3
    // For UPDATE/DELETE a committed target record (value 4); CREATE needs none.
    var tailRid = (opType == RecordOperation.CREATED) ? null : commitRec(4);
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);

    session.begin();
    // Open the populate and close it without iterating: the entry caches a fully un-pulled (live)
    // stream, so every committed record is cached-at-build=false regardless of the storage scan order
    // (pulling a fixed prefix and assuming a specific record falls in the tail is scan-order dependent
    // and flaky). The second query then drives the entire result through the live tail.
    drainPartial(session.query(sql), 0);
    if (cacheEnabled) {
      assertPartialDrainLeftLiveEntry();
    }
    RID rid;
    switch (opType) {
      case RecordOperation.CREATED -> {
        var e = session.newEntity(CLASS_NAME);
        e.setProperty(FIELD, value);
        rid = ((RecordAbstract) e).getIdentity();
      }
      case RecordOperation.UPDATED -> {
        Entity rec = session.load(tailRid);
        rec.setProperty(FIELD, value);
        tx().addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
        rid = tailRid;
      }
      case RecordOperation.DELETED -> {
        Entity rec = session.load(tailRid);
        tx().addRecordOperation((RecordAbstract) rec, RecordOperation.DELETED);
        rid = tailRid;
      }
      default -> throw new IllegalArgumentException("unexpected op type " + opType);
    }
    if (cacheEnabled) {
      assertEquals("staged op type must match the dispatch row under test", opType,
          stagedOpType(rid));
      assertFalse("the mutated record must be un-pulled (cached-at-build=false)",
          theCachedEntry().getCachedRids().contains(rid));
    }
    var rows = drainRows(session.query(sql));
    if (cacheEnabled) {
      assertSecondQueryWasCacheHit();
    }
    session.rollback();
    return rows;
  }

  /**
   * Runs the cache-off and cache-on halves of a cached=false partial-drain row and compares them. Under
   * ORDER BY the row order is contractual, so the lists are compared directly; without it the order is
   * not contracted (the merge drains injects first), so the sorted multisets are compared — which still
   * catches a duplicated or dropped row.
   */
  private void assertPartialDrainEquivalent(String sql, byte opType, int value, String msg) {
    var fresh = runPartialDrainCachedFalse(false, sql, opType, value);
    var cached = runPartialDrainCachedFalse(true, sql, opType, value);
    if (sql.contains("ORDER BY")) {
      assertEquals(msg, fresh, cached);
    } else {
      assertEquals(msg, sortedValues(fresh), sortedValues(cached));
    }
  }

  /** CREATED / cached=false / matches, ORDER BY (the lazy-scan no-ORDER-BY case is covered above). */
  @Test
  public void recordEquivalence_createMatchTail_ordered() {
    assertPartialDrainEquivalent(FILTER_ORDERED_SQL, RecordOperation.CREATED, 99,
        "a matching post-populate CREATE must be injected once, matching fresh (ORDER BY)");
  }

  /** CREATED / cached=false / does NOT match: a true post-populate create that fails WHERE is a no-op. */
  @Test
  public void recordEquivalence_createNoMatchTail_unordered() {
    assertPartialDrainEquivalent(FILTER_SQL, RecordOperation.CREATED, -1,
        "a post-populate CREATE that fails WHERE is a no-op, matching fresh (unordered)");
  }

  @Test
  public void recordEquivalence_createNoMatchTail_ordered() {
    assertPartialDrainEquivalent(FILTER_ORDERED_SQL, RecordOperation.CREATED, -1,
        "a post-populate CREATE that fails WHERE is a no-op, matching fresh (ORDER BY)");
  }

  /** UPDATED / cached=false / matches, ORDER BY (the lazy-scan no-ORDER-BY case is covered above). */
  @Test
  public void recordEquivalence_updateMatchTail_ordered() {
    assertPartialDrainEquivalent(FILTER_ORDERED_SQL, RecordOperation.UPDATED, 99,
        "a matching UPDATE of an un-pulled tail record must skip+inject, matching fresh (ORDER BY)");
  }

  /** UPDATED / cached=false / does NOT match: skip the stale tail copy, no inject. */
  @Test
  public void recordEquivalence_updateNoMatchTail_unordered() {
    assertPartialDrainEquivalent(FILTER_SQL, RecordOperation.UPDATED, -1,
        "an UPDATE of an un-pulled tail record that fails WHERE must skip with no inject (unordered)");
  }

  @Test
  public void recordEquivalence_updateNoMatchTail_ordered() {
    assertPartialDrainEquivalent(FILTER_ORDERED_SQL, RecordOperation.UPDATED, -1,
        "an UPDATE of an un-pulled tail record that fails WHERE must skip with no inject (ORDER BY)");
  }

  /** DELETED / cached=false: a delete of an un-pulled tail record; skip when the tail surfaces it. */
  @Test
  public void recordEquivalence_deleteTail_unordered() {
    assertPartialDrainEquivalent(FILTER_SQL, RecordOperation.DELETED, 0,
        "a DELETE of an un-pulled tail record must skip it from the live tail, matching fresh"
            + " (unordered)");
  }

  @Test
  public void recordEquivalence_deleteTail_ordered() {
    assertPartialDrainEquivalent(FILTER_ORDERED_SQL, RecordOperation.DELETED, 0,
        "a DELETE of an un-pulled tail record must skip it from the live tail, matching fresh"
            + " (ORDER BY)");
  }

  /**
   * Runs a CREATE partial-drain scenario and returns the second query's rows. The first query is pulled
   * only {@code pullCount} rows deep then closed (not drained), so with the flag on the entry stays
   * cached with a live, partially-pulled stream. A matching record is created after that partial
   * populate, then the second query is issued in full: with the flag on it is a cache HIT that replays
   * the partial prefix and then drives the still-live stream tail through the delta merge; with the flag
   * off it is a fresh uncached execution. The cache-on run asserts a live tail and a hit, so the
   * comparison cannot pass vacuously by silently falling back to uncached execution.
   */
  private List<Object> runPartialDrainCreate(boolean cacheEnabled, int seedCount, int pullCount) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    seed(seedCount);
    var sql = "SELECT FROM " + CLASS_NAME;

    session.begin();
    drainPartial(session.query(sql), pullCount);
    if (cacheEnabled) {
      assertPartialDrainLeftLiveEntry();
    }
    // True post-populate CREATE of a matching record: the delta injects it with no skip.
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(FIELD, 99);
    var rows = drainRows(session.query(sql));
    if (cacheEnabled) {
      assertSecondQueryWasCacheHit();
    }
    session.rollback();
    return rows;
  }

  /**
   * Runs an UPDATE-of-an-un-pulled-record partial-drain scenario and returns the second query's rows.
   * The populate is opened and closed without iterating, so the entry caches a fully un-pulled (live)
   * stream and the committed target is cached-at-build=false regardless of the storage scan order. It is
   * then updated, so the delta sees it as UPDATED with cached-at-build=false and emits skip + inject.
   * Same live-tail setup and same cache-hit assertions as {@link #runPartialDrainCreate}.
   */
  private List<Object> runPartialDrainUpdateTail(boolean cacheEnabled) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seed(4); // committed values 0,1,2,3
    var tailRid = commitRec(4); // a committed target; the empty prefix guarantees it stays un-pulled
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = "SELECT FROM " + CLASS_NAME;

    session.begin();
    // Open the populate and close it without iterating, so the entry keeps a fully un-pulled live stream
    // and the target is cached-at-build=false regardless of the storage scan order. The second query
    // drives the whole result through the live tail.
    drainPartial(session.query(sql), 0);
    if (cacheEnabled) {
      assertPartialDrainLeftLiveEntry();
    }
    // UPDATE the un-pulled record (cached-at-build=false): the delta skips the stale copy the live
    // stream tail still holds and injects the post-update copy.
    Entity rec = session.load(tailRid);
    rec.setProperty(FIELD, 99);
    tx().addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
    var rows = drainRows(session.query(sql));
    if (cacheEnabled) {
      assertSecondQueryWasCacheHit();
    }
    session.rollback();
    return rows;
  }

  /**
   * Verifies the DeltaBuilder's "temp RID never streamed" claim end to end. A true post-populate CREATE
   * is injected with NO skip, on the premise that the new record's RID is never reachable by the entry's
   * storage stream, so the inject cannot collide with a streamed copy. This is the only test that drives
   * a real, still-live stream tail AFTER the create: the populating query is pulled two rows deep and
   * closed (leaving the entry cached with a live stream), the matching record is created, then the full
   * re-query replays the prefix and drives the live tail through the merge. If the transaction-aware
   * iterator surfaced the new record on that tail pull, the inject would emit it a second time and the
   * cached value would appear twice; the sorted-multiset comparison to a fresh uncached execution
   * catches that duplication. A pass confirms the claim holds, closing the coverage gap the
   * builder-only, synthetic-stream, and fully-drained equivalence tests all leave open.
   */
  @Test
  public void recordEquivalence_createAfterPartialDrainDrivesLiveStreamTail() {
    var fresh = runPartialDrainCreate(false, 5, 2);
    var cached = runPartialDrainCreate(true, 5, 2);
    assertEquals(
        "A post-populate CREATE injected without a skip must appear exactly once after the live"
            + " stream tail is driven, matching a fresh execution",
        sortedValues(fresh), sortedValues(cached));
  }

  /**
   * The mirror case: an UPDATE of a record living in the un-pulled stream tail (UPDATED,
   * cached-at-build=false, still matching). The DeltaBuilder emits skip + inject, the skip relying on the
   * live stream tail surfacing the record so its stale copy is suppressed by RID and only the injected
   * post-update copy survives. Same partial-drain setup as the CREATE case. If the skip failed to
   * suppress the streamed copy, the cached output would carry both the streamed and the injected
   * version; the sorted-multiset comparison to a fresh execution catches that.
   */
  @Test
  public void recordEquivalence_updateTailRecordAfterPartialDrainDrivesLiveStreamTail() {
    var fresh = runPartialDrainUpdateTail(false);
    var cached = runPartialDrainUpdateTail(true);
    assertEquals(
        "An UPDATE of an un-pulled tail record must appear once with the new value after the live"
            + " stream tail is driven, matching a fresh execution",
        sortedValues(fresh), sortedValues(cached));
  }

  // ===========================================================================
  // RECORD cache-vs-fresh equivalence, mutation BEFORE populate
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

  /**
   * Regression for the strike-threshold-crossing empty-result bug: on the very execution where a
   * K0_NONE entry's repeated version-gate invalidation crosses {@code k0NoneInvalidationThreshold}, the
   * key is routed non-cacheable inside {@code lookup} and control falls through to the populate branch
   * in the same {@code serveThroughCache} call. {@code cache.put} then refuses to store the
   * non-cacheable key and closes the freshly built entry's stream, so without a re-check the view would
   * replay an EMPTY result. The threshold-crossing query must instead return the full fresh result.
   * Threshold is set to 2 so the third query (after the second post-populate mutation) is the crossing
   * one. The GROUP BY result must hold one row per distinct FIELD value across the seed plus both
   * in-tx creates, not zero rows.
   */
  @Test
  public void k0None_thresholdCrossingQueryReturnsFullResultNotEmpty() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    var prevThreshold =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD
            .getValueAsInteger();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD.setValue(2);
    try {
      seed(3); // FIELD {0, 1, 2}
      var sql = "SELECT " + FIELD + ", count(*) FROM " + CLASS_NAME + " GROUP BY " + FIELD;

      session.begin();
      var cache = tx().getQueryResultCache();
      assertEquals("GROUP BY classifies as K0_NONE", CacheableShape.K0_NONE,
          ShapeClassifier.classify(YqlStatementCache.get(sql, session)));

      drainRows(session.query(sql)); // miss + populate at v0

      // First post-populate mutation: query re-invalidates (strike 1 < 2) and re-populates at v1.
      var a = session.newEntity(CLASS_NAME);
      a.setProperty(FIELD, 10);
      assertEquals("strike-1 invalidation still returns the full result", Set.of(0, 1, 2, 10),
          new HashSet<>(drainRows(session.query(sql))));

      // Second post-populate mutation: the next query crosses the threshold (strike 2 >= 2) and routes
      // the key non-cacheable on this very call. The crossing query must still return every group.
      var b = session.newEntity(CLASS_NAME);
      b.setProperty(FIELD, 20);
      var crossing = new HashSet<>(drainRows(session.query(sql)));
      assertEquals("the threshold-crossing K0_NONE query must return the full result, not empty",
          Set.of(0, 1, 2, 10, 20), crossing);
      assertEquals("both post-populate mutations invalidated the stale K0_NONE entry", 2,
          cache.getMetrics().getK0Invalidations());

      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD
          .setValue(prevThreshold);
    }
  }

  // ===========================================================================
  // Eventual clear, NOT clear-on-iterate-exception
  // ===========================================================================

  /**
   * Commit runs the transaction-end clear sink and wipes the cache. The reference is captured
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

  /** Rollback runs the same tx-end clear sink and wipes the cache. */
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
   * The tx-end clear is EVENTUAL, not a clear-on-iterate-exception. An exception thrown mid-iteration (here
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
  // Idempotent clear
  // ===========================================================================

  /** A second {@code clear()} after the tx-end clear is a no-op: the cache stays empty. */
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
  // Paused stream lifetime bounded by its entry (closed at tx-end)
  // ===========================================================================

  /**
   * The entry owns the paused execution stream; the tx-end clear closes it. After a populate that
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
  // A view started before a mutation does not observe it; a fresh query does
  // ===========================================================================

  /**
   * A view materialised before an in-tx mutation must not observe that mutation — its rows are
   * frozen at construction. The earlier test fully drained and closed the first view before mutating,
   * so no open view ever spanned the mutation and the assertions only re-checked the delta-merge of
   * a fresh post-mutation query. This version holds view A OPEN across the mutation: it pulls one row,
   * a CREATE is staged while A is still iterating, and A must finish emitting exactly its pre-mutation
   * rows (0, 1) without ever surfacing the new value 99. A separate fresh view B opened after the
   * mutation must see 99 through the delta merge. A regression that made an open view re-read
   * {@code entry.results} / re-apply a freshly-rebuilt delta on each {@code next()} would leak 99 into
   * A and fail the first assertion.
   */
  @Test
  public void i7_openViewDoesNotObserveLaterMutation() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(2); // committed rows with FIELD 0, 1
    session.begin();
    var sql = "SELECT FROM " + CLASS_NAME + " ORDER BY " + FIELD + " ASC";

    // Open view A and pull ONE row, leaving it mid-iteration so its snapshot was taken before mutating.
    var aValues = new ArrayList<>();
    var a = session.query(sql);
    assertTrue(a.hasNext());
    aValues.add(a.next().<Object>getProperty(FIELD));

    // Mutate inside the same tx while A is still open and un-exhausted.
    var added = session.newEntity(CLASS_NAME);
    added.setProperty(FIELD, 99);

    // A must finish with exactly its pre-mutation rows — it must NOT pick up the new value 99.
    while (a.hasNext()) {
      aValues.add(a.next().<Object>getProperty(FIELD));
    }
    a.close();
    assertEquals("an open view must not observe a mutation made after it was built",
        List.of(0, 1), aValues);

    // A fresh view B opened after the mutation DOES observe it through the delta merge.
    var b = drainRows(session.query(sql));
    assertEquals("a fresh query after the mutation observes the new record via the delta merge",
        List.of(0, 1, 99), b);
    assertNotEquals("the post-mutation view differs from the isolated pre-mutation one", aValues,
        b);

    session.rollback();
  }

  // ===========================================================================
  // Schema-stable class filter for the entry's lifetime
  // ===========================================================================

  /**
   * I8: the entry's {@code effectiveFromClasses} closure, computed once when the entry is populated,
   * stays valid for the entry's whole lifetime even if the schema later gains a subclass of the queried
   * class. {@code computeEffectiveFromClasses} reads the live schema on every call (the queried class
   * plus every current subclass, each by name), so a fresh call made after a subclass is added genuinely
   * sees the new subclass — that freshness is what makes the snapshot-stability claim falsifiable here
   * rather than tautological.
   *
   * <p>Schema changes in this engine are not transactional: {@code createClass} is rejected while a
   * transaction is active and otherwise mutates the live schema immediately. The invariant is therefore
   * about a populate-time snapshot resisting a schema change that lands later in the entry's lifetime
   * (between the populate and a later observation), not about DDL inside one open transaction. The test
   * captures the populate-time snapshot, adds a real subclass {@code CLASS_NAME + "Sub"} of the queried
   * class against the live schema, then contrasts two reads of the same pure function: a FRESH
   * {@code computeEffectiveFromClasses} DOES contain the new subclass (proving the schema change is live
   * and observable), WHILE the populate-time snapshot does NOT. If the once-computed closure had leaked
   * the later subclass — i.e. if it were a live view rather than a frozen snapshot — the snapshot
   * assertion would fail. That contrast is the I8 guarantee: a populate-time class filter resists a real,
   * observable later schema change.
   */
  @Test
  public void i8_effectiveFromClassesStableAcrossMidTxSchemaChange() {
    var cls = session.getClass(CLASS_NAME);
    // The closure as it is at populate time, with no subclass present yet. This immutable snapshot stands
    // in for the entry's once-computed effectiveFromClasses captured when the entry was populated.
    var atPopulate = CachedEntry.computeEffectiveFromClasses(cls);
    assertTrue("the closure contains the declared class by name", atPopulate.contains(CLASS_NAME));
    assertFalse("no subclass exists at populate time",
        atPopulate.contains(CLASS_NAME + "Sub"));

    // Add a subclass of the QUERIED class against the live schema (DDL is non-transactional and rejected
    // inside an open tx, so this runs with no active transaction). computeEffectiveFromClasses includes
    // the queried class plus all its subclasses, so a fresh call after this DDL must see "Sub".
    session.createClass(CLASS_NAME + "Sub", CLASS_NAME);

    // Freshness check: a NEW compute call reads the live schema and DOES contain the just-added subclass.
    // This proves the schema change is genuinely live and observable, not a no-op — without it the
    // snapshot-stability assertion below would be vacuous.
    var fresh = CachedEntry.computeEffectiveFromClasses(session.getClass(CLASS_NAME));
    assertTrue("a fresh closure observes the live schema and includes the just-added subclass",
        fresh.contains(CLASS_NAME + "Sub"));

    // Stability check: the populate-time snapshot did NOT absorb the later subclass. The contrast with
    // the fresh closure above is what makes this falsifiable — if the once-computed closure were a live
    // view rather than a frozen snapshot, it would now contain "Sub" and this assertion would fail.
    assertTrue("the captured closure still contains the declared class",
        atPopulate.contains(CLASS_NAME));
    assertFalse("the captured closure must not absorb a subclass added after it was computed",
        atPopulate.contains(CLASS_NAME + "Sub"));
    assertEquals(
        "the captured closure holds exactly the declared class, unchanged by the live schema change",
        List.of(CLASS_NAME), List.copyOf(atPopulate));
  }

  // ===========================================================================
  // A live view is not truncated under LRU pressure
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
      var firstPulled = held.next().<Object>getProperty(FIELD);

      // A second distinct query exceeds maxEntries=1 and would evict the eldest — but the held view's
      // entry is pinned, so eviction must skip it rather than truncate the live view.
      drainRows(session.query("SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " >= 1"));

      // The held view must still yield its full result: the one row already pulled plus the remaining
      // two, three rows total, with no loss to LRU pressure.
      var remaining = drainRows(held);
      assertEquals("a pinned live view keeps its full result under LRU pressure)", 2,
          remaining.size());
      // Assert content, not just count: the pulled row plus the remaining two must be exactly the
      // seeded set {0, 1, 2} — eviction pressure must not drop a real row or replay a duplicate of the
      // already-pulled one. Compared as a set because the query carries no ORDER BY.
      var allSeen = new HashSet<>(remaining);
      allSeen.add(firstPulled);
      assertEquals("the pinned view yields exactly the seeded set across the pull boundary",
          Set.of(0, 1, 2), allSeen);

      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_ENTRIES.setValue(prevMax);
    }
  }

  // ===========================================================================
  // Owner-thread guard balance (assert-based; checked here via depth balance)
  // ===========================================================================

  /**
   * I2 rests on {@code assertOnOwningThread} guarding every cache mutation path; the cache-code
   * re-entrancy depth counter must always return to zero after a query completes on the owning thread.
   * A non-zero residual depth would mean the guard leaked and every later query would take the
   * re-entrant bypass. The guard now brackets only the synchronous lookup/build scope plus each per-row
   * {@code computeNext()}, so the depth is balanced as soon as query() returns and stays balanced after
   * a full drain; this case asserts the post-drain balance.
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
   * The load-bearing classify-ordering guard: an {@code ORDER BY} + {@code LIMIT}
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
  // Per-entry record cap (maxRecordsPerEntry) overflow
  // ===========================================================================

  /**
   * A populate that crosses {@code maxRecordsPerEntry} overflows the entry: it is removed from the
   * cache, its key is routed to the non-cacheable set, and an overflow is counted, while the consuming
   * view still receives every row. With the cap set to 2 and four matching records, draining the view
   * pulls all four rows (the consumer loses nothing), but the over-cap entry is dropped (cache empty),
   * an overflow is counted, and a second identical query stays uncached (no entry re-created) because
   * the key is now non-cacheable. The boundary is cap+1: caching the third row is what triggers the
   * overflow.
   */
  @Test
  public void perEntryRecordCapOverflowEvictsEntryAndStaysUncached() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    var prevCap =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.getValueAsInteger();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(2);
    try {
      seed(4);
      session.begin();
      var sql = "SELECT FROM " + CLASS_NAME;
      var cache = tx().getQueryResultCache();

      // Draining the view materialises all four rows; the consumer must still receive every one even
      // though the entry overflows its 2-record cap partway through the pull.
      var rows = drainRows(session.query(sql));
      assertEquals("the consumer still receives every row past the cap", 4, rows.size());

      assertEquals("an over-cap entry is removed from the cache", 0, cache.size());
      assertEquals("crossing the per-entry cap counts exactly one overflow", 1,
          cache.getMetrics().getOverflows());

      // A second identical query must stay uncached: the key was routed to the non-cacheable set, so
      // no entry is re-created and the cache remains empty.
      var second = drainRows(session.query(sql));
      assertEquals("the re-query returns the same rows uncached", rows, second);
      assertEquals("an overflowed key does not re-populate the cache", 0, cache.size());

      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(prevCap);
    }
  }

  /**
   * Boundary check: an entry whose result count equals the cap exactly is cached normally (no
   * overflow). With the cap at 3 and three matching records, the entry holds all three rows and stays
   * in the cache after a full drain, and no overflow is counted. This pins the cap-vs-cap+1 boundary
   * against the overflow test above.
   */
  @Test
  public void perEntryRecordCountEqualToCapIsCachedWithoutOverflow() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    var prevCap =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.getValueAsInteger();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(3);
    try {
      seed(3);
      session.begin();
      var sql = "SELECT FROM " + CLASS_NAME;
      var cache = tx().getQueryResultCache();

      var rows = drainRows(session.query(sql));
      assertEquals("all rows at the cap are emitted", 3, rows.size());
      assertEquals("an at-cap entry survives in the cache", 1, cache.size());
      assertEquals("an at-cap entry counts no overflow", 0, cache.getMetrics().getOverflows());

      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(prevCap);
    }
  }

  // ===========================================================================
  // Cross-thread cache-code guard release (best-effort pool-shutdown contract)
  // ===========================================================================

  /**
   * Pins the two cache-code depth primitives directly: {@code exitCacheCodeUnchecked()} must NOT assert
   * the owning thread (the floored decrement is harmless off-thread), while the synchronous
   * {@code exitCacheCode()} must. After a query has set the owning thread, a foreign unchecked exit must
   * not throw and a foreign checked exit must trip the owner assert. The view no longer holds the guard
   * across its lifetime — it brackets each {@code computeNext()} on the owning thread (see
   * {@code CachedResultSetView.hasNext}) — so no production path takes the unchecked exit cross-thread
   * today; the primitive and this guard are retained for any future cross-thread cleanup. Run with
   * {@code -ea} (the suite's mandated mode).
   */
  @Test
  public void crossThreadGuardReleaseUsesUncheckedExitAndDoesNotAssert() throws Exception {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(3);
    session.begin();
    try {
      // A query on the owning thread sets storageTxThreadId and leaves the guard balanced.
      drainRows(session.query("SELECT FROM " + CLASS_NAME));
      var tx = tx();

      // The unchecked exit is the path the view's releasePin() now takes. From a foreign thread it must
      // not trip assertOnOwningThread. Enter on the owning thread so there is depth to decrement.
      tx.enterCacheCode();
      var uncheckedError = new AtomicReference<Throwable>();
      var uncheckedThread = new Thread(() -> {
        try {
          tx.exitCacheCodeUnchecked();
        } catch (Throwable t) {
          uncheckedError.set(t);
        }
      });
      uncheckedThread.start();
      uncheckedThread.join();
      assertNull(
          "the unchecked guard exit must not assert the owning thread (cross-thread shutdown)",
          uncheckedError.get());

      // The checked exit (the synchronous session path) still asserts the owning thread off-thread.
      tx.enterCacheCode();
      var checkedError = new AtomicReference<Throwable>();
      var checkedThread = new Thread(() -> {
        try {
          tx.exitCacheCode();
        } catch (Throwable t) {
          checkedError.set(t);
        }
      });
      checkedThread.start();
      checkedThread.join();
      assertTrue("the checked guard exit must still trip the owner assert off-thread under -ea",
          checkedError.get() instanceof AssertionError);
    } finally {
      session.rollback();
    }
  }

  /**
   * The documented cross-thread sink, exercised end-to-end: a pool-shutdown thread running
   * {@code QueryResultCache.clear()} after the owning thread populated an entry must not throw, must
   * drain the cache, and must be idempotent on a second clear() (I6). {@code clear()} is the one method
   * the cache's own Javadoc allows from a foreign thread; the depth-primitive test above pins only the
   * guard's assert contract, not this state-touching path. With the cache fields non-volatile this test
   * documents the best-effort contract and guards against a regression that breaks the idempotent,
   * null-safe cross-thread clear (it does not prove memory visibility under a real race; that would be a
   * production-side fix — marking the fields volatile or publishing through a barrier).
   */
  @Test
  public void crossThreadClearAfterPopulateDrainsAndIsIdempotent() throws Exception {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(3);
    session.begin();
    try {
      var cache = tx().getQueryResultCache();
      // Owning thread populates an entry.
      drainRows(session.query("SELECT FROM " + CLASS_NAME));
      assertEquals("the owning-thread query populated one entry", 1, cache.size());

      // A foreign (pool-shutdown) thread runs clear() twice: it must not throw and must be idempotent.
      var error = new AtomicReference<Throwable>();
      var shutdown = new Thread(() -> {
        try {
          cache.clear();
          cache.clear();
        } catch (Throwable t) {
          error.set(t);
        }
      });
      shutdown.start();
      shutdown.join();

      assertNull("cross-thread clear() must not throw", error.get());
      assertEquals("cross-thread clear() drained the cache", 0, cache.size());
    } finally {
      session.rollback();
    }
  }

  // ===========================================================================
  // TRUNCATE CLASS inside a SQL script invalidates a sibling query()'s cache
  // ===========================================================================

  /**
   * A {@code TRUNCATE CLASS} embedded in a {@code command(script)} must invalidate the tx-result cache
   * the same way the direct command path does, so a sibling {@code query()} that populated an entry
   * earlier in the same transaction cannot serve stale rows for records the truncate removed. Without
   * the per-statement script invalidation hook the second query would return the pre-truncate rows,
   * a result-cardinality change from merely enabling the cache. Here a query populates, a script
   * truncates the class, and a second identical query must observe zero rows.
   */
  @Test
  public void scriptTruncateClassInvalidatesSiblingQueryCache() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    seed(3);
    session.begin();
    try {
      var sql = "SELECT FROM " + CLASS_NAME;
      var before = drainRows(session.query(sql));
      assertEquals("the populated query observes the seeded rows", 3, before.size());
      assertEquals("the query populated a cache entry", 1, tx().getQueryResultCache().size());

      // TRUNCATE CLASS run through the SQL script executor (not the single-statement command path)
      // removes the stored records without flowing through the mutation log; the script path must
      // still invalidate the cache so the next query is not served stale.
      session.executeSQLScript("TRUNCATE CLASS " + CLASS_NAME + ";");

      var after = drainRows(session.query(sql));
      assertTrue("a query after a script TRUNCATE must not return the truncated rows",
          after.isEmpty());
    } finally {
      session.rollback();
    }
  }

  // ===========================================================================
  // Flag-off transparency floor
  // ===========================================================================

  /** With the flag off no cache is allocated and repeated queries behave exactly as before. */
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
    // Pin the actual flag-off result, not merely that it is self-consistent and non-empty: the floor
    // must return exactly the seeded set {0, 1, 2}. Compared as a set because the query has no ORDER BY.
    assertEquals("the flag-off result is exactly the seeded set", Set.of(0, 1, 2),
        new HashSet<>(first));
    session.rollback();
  }
}
