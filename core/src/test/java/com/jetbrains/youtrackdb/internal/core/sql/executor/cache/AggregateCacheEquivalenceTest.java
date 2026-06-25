package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end cache-vs-fresh equivalence for the {@code AGGREGATE_*} cache path: every
 * cacheable aggregate scenario runs twice through the live {@code query()} path — once with the cache
 * off (a fresh uncached execution, the source of truth) and once with it on (forcing a side-tap
 * populate, then a post-mutation second query served through the replayed {@link AggregateState} view)
 * — and the two scalars are compared. Comparing against a parallel uncached run rather than a literal
 * keeps the suite honest as the replay logic evolves.
 *
 * <p>The tappable shape is {@code SELECT <agg>(price) FROM C WHERE active = true}: a non-indexed WHERE
 * forces the planner to build a real {@code AggregateProjectionCalculationStep} the side-tap can splice
 * above, unlike the hardwired bare/indexed {@code COUNT(*)} forms. Per-kind cases (COUNT / SUM / AVG /
 * MIN / MAX) cross the four mutation patterns and the collapse case; separate cases pin the
 * hardwired-COUNT(*) fallback, the {@code count(*) + 1} and {@code count(distinct(prop))} K0_NONE
 * routings, the contributor-cap overflow, a cache hit on repeat, and the no-exception-leak fallback.
 *
 * <p>Run with {@code -ea}: the side-tap's null-RID guard and the buildForAggregate non-null-state
 * assert are Java {@code assert}s that protect tests, not production.
 */
@Category(SequentialTest.class)
public class AggregateCacheEquivalenceTest extends DbTestBase {

  private static final String CLASS_NAME = "AggRec";
  private static final String VALUE = "price";
  private static final String FLAG = "active";

  private boolean previousEnabled;

  @Before
  public void enableSchema() {
    previousEnabled = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty(VALUE, PropertyType.INTEGER);
    cls.createProperty(FLAG, PropertyType.BOOLEAN);
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

  /** A tappable aggregate over the matching (active = true) records. */
  private static String aggSql(String agg) {
    return "SELECT " + agg + " FROM " + CLASS_NAME + " WHERE " + FLAG + " = true";
  }

  /**
   * Clears every committed record of CLASS_NAME in its own tx with the cache forced off, so the flag-off
   * and flag-on halves of a pair start from identical committed state and the clearing DELETE never
   * touches the cache under test.
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

  /** Commits one matching (active=true) record with the given value, returning its persistent RID. */
  private RID commitMatching(int value) {
    return commit(value, true);
  }

  private RID commit(int value, boolean active) {
    session.begin();
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(VALUE, value);
    e.setProperty(FLAG, active);
    var rid = ((RecordAbstract) e).getIdentity();
    session.commit();
    return rid;
  }

  /** The single scalar value the aggregate query emits (its lone projection), or null on empty. */
  private static Object scalar(ResultSet rs) {
    try (rs) {
      if (!rs.hasNext()) {
        return null;
      }
      Result row = rs.next();
      var names = row.getPropertyNames();
      return row.getProperty(names.iterator().next());
    }
  }

  /**
   * Seeds a fixed committed set of matching records, opens a tx, populates the cache with one aggregate
   * query, applies {@code mutation} after populate, then captures the second query's scalar. Driven once
   * per flag state; comparing the two captured scalars is the equivalence test.
   */
  private Object runScenario(boolean cacheEnabled, String agg, int[] matchingSeed,
      Consumer<FrontendTransactionImpl> mutation) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    for (var v : matchingSeed) {
      commitMatching(v);
    }
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = aggSql(agg);

    session.begin();
    scalar(session.query(sql)); // populate (flag on) or plain execution (flag off)
    mutation.accept(tx());
    var result = scalar(session.query(sql)); // delta-replayed view (flag on) or fresh (flag off)
    session.rollback();
    return result;
  }

  private void assertEquivalent(String agg, int[] matchingSeed,
      Consumer<FrontendTransactionImpl> mutation) {
    var fresh = runScenario(false, agg, matchingSeed, mutation);
    var cached = runScenario(true, agg, matchingSeed, mutation);
    assertEquals(
        "Cached aggregate replay must equal a fresh uncached execution at the same moment for "
            + agg,
        fresh, cached);
  }

  // The four mutation patterns, expressed against the tappable shape. CREATE and DELETE use the
  // proven addRecordOperation staging; an UPDATE that breaks WHERE flips active=false; a value UPDATE
  // changes price while staying matched. A loaded-record DELETE/UPDATE uses addRecordOperation because
  // session.delete(session.load(rid)) on a record committed before the tx throws "not bound to current
  // session" on the re-query (the addRecordOperation pattern is the proven one from the RECORD suite).

  /** F->T add of a new matching record after populate. */
  private Consumer<FrontendTransactionImpl> createMatching(int value) {
    return t -> {
      var e = session.newEntity(CLASS_NAME);
      e.setProperty(VALUE, value);
      e.setProperty(FLAG, true);
    };
  }

  /** T->F drop: a WHERE-breaking UPDATE (active=false) on a pre-committed matching record. */
  private Consumer<FrontendTransactionImpl> breakWhere(RID rid) {
    return t -> {
      Entity rec = session.load(rid);
      rec.setProperty(FLAG, false);
      t.addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
    };
  }

  /** T->T value change: change price while staying matched. */
  private Consumer<FrontendTransactionImpl> changeValue(RID rid, int newValue) {
    return t -> {
      Entity rec = session.load(rid);
      rec.setProperty(VALUE, newValue);
      t.addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
    };
  }

  /** DELETE of a pre-committed matching record. */
  private Consumer<FrontendTransactionImpl> deleteRecord(RID rid) {
    return t -> {
      Entity rec = session.load(rid);
      t.addRecordOperation((RecordAbstract) rec, RecordOperation.DELETED);
    };
  }

  // ===========================================================================
  // Per-kind cache-vs-fresh equivalence — CREATE / DELETE / WHERE-break UPDATE / value UPDATE
  // ===========================================================================

  @Test
  public void countEquivalence_createAfterPopulate() {
    assertEquivalent("count(*)", new int[] {10, 20, 30}, createMatching(40));
  }

  @Test
  public void countEquivalence_deleteAfterPopulate() {
    var fresh = runWithTarget(false, "count(*)", this::deleteRecord);
    var cached = runWithTarget(true, "count(*)", this::deleteRecord);
    assertEquals("COUNT after a DELETE must match fresh", fresh, cached);
  }

  @Test
  public void countEquivalence_whereBreakUpdateAfterPopulate() {
    var fresh = runWithTarget(false, "count(*)", this::breakWhere);
    var cached = runWithTarget(true, "count(*)", this::breakWhere);
    assertEquals("COUNT after a WHERE-breaking UPDATE must match fresh", fresh, cached);
  }

  @Test
  public void sumEquivalence_createAfterPopulate() {
    assertEquivalent("sum(" + VALUE + ")", new int[] {10, 20, 30}, createMatching(40));
  }

  @Test
  public void sumEquivalence_valueUpdateAfterPopulate() {
    var fresh = runWithTarget(false, "sum(" + VALUE + ")", rid -> changeValue(rid, 999));
    var cached = runWithTarget(true, "sum(" + VALUE + ")", rid -> changeValue(rid, 999));
    assertEquals("SUM after a value UPDATE must match fresh (full re-fold)", fresh, cached);
  }

  @Test
  public void sumEquivalence_whereBreakUpdateAfterPopulate() {
    var fresh = runWithTarget(false, "sum(" + VALUE + ")", this::breakWhere);
    var cached = runWithTarget(true, "sum(" + VALUE + ")", this::breakWhere);
    assertEquals("SUM after a WHERE-breaking UPDATE must drop the contributor", fresh, cached);
  }

  @Test
  public void avgEquivalence_createAfterPopulate() {
    assertEquivalent("avg(" + VALUE + ")", new int[] {10, 20, 30}, createMatching(40));
  }

  @Test
  public void avgEquivalence_valueUpdateAfterPopulate() {
    var fresh = runWithTarget(false, "avg(" + VALUE + ")", rid -> changeValue(rid, 100));
    var cached = runWithTarget(true, "avg(" + VALUE + ")", rid -> changeValue(rid, 100));
    assertEquals("AVG after a value UPDATE must match fresh (integer truncation)", fresh, cached);
  }

  @Test
  public void minEquivalence_deleteAfterPopulate() {
    var fresh = runWithTarget(false, "min(" + VALUE + ")", this::deleteRecord);
    var cached = runWithTarget(true, "min(" + VALUE + ")", this::deleteRecord);
    assertEquals("MIN after a DELETE must match fresh", fresh, cached);
  }

  @Test
  public void maxEquivalence_deleteAfterPopulate() {
    var fresh = runWithTarget(false, "max(" + VALUE + ")", this::deleteRecord);
    var cached = runWithTarget(true, "max(" + VALUE + ")", this::deleteRecord);
    assertEquals("MAX after a DELETE must match fresh", fresh, cached);
  }

  @Test
  public void minEquivalence_valueUpdateAfterPopulate() {
    var fresh = runWithTarget(false, "min(" + VALUE + ")", rid -> changeValue(rid, 999));
    var cached = runWithTarget(true, "min(" + VALUE + ")", rid -> changeValue(rid, 999));
    assertEquals("MIN after a value UPDATE must match fresh", fresh, cached);
  }

  @Test
  public void minEquivalence_whereBreakUpdateAfterPopulate() {
    var fresh = runWithTarget(false, "min(" + VALUE + ")", this::breakWhere);
    var cached = runWithTarget(true, "min(" + VALUE + ")", this::breakWhere);
    assertEquals("MIN after a WHERE-breaking UPDATE must drop the contributor", fresh, cached);
  }

  /**
   * MIN F&rarr;T where a CREATE introduces a value below the current minimum drives the O(1) beats-
   * extremum branch through the live splice path. The seeded set's min is 10; creating a matching 1
   * after populate must replay to a new min of 1, matching a fresh execution. Mirrors the MAX collapse/
   * create coverage so MIN has a live splice-path case beyond DELETE.
   */
  @Test
  public void minEquivalence_createBelowExtremumAfterPopulate() {
    var fresh = runScenario(false, "min(" + VALUE + ")", new int[] {10, 20, 30}, createMatching(1));
    var cached = runScenario(true, "min(" + VALUE + ")", new int[] {10, 20, 30}, createMatching(1));
    assertEquals("MIN after a below-extremum CREATE must replay to the new min, matching fresh",
        fresh, cached);
  }

  @Test
  public void maxEquivalence_valueUpdateBelowExtremumAfterPopulate() {
    // The MAX holder's value drops below a non-holder, forcing the O(n) recompute branch.
    var fresh = runWithTarget(false, "max(" + VALUE + ")", rid -> changeValue(rid, 1));
    var cached = runWithTarget(true, "max(" + VALUE + ")", rid -> changeValue(rid, 1));
    assertEquals("MAX after the holder drops below a non-holder must recompute to match fresh",
        fresh, cached);
  }

  /**
   * Runs a scenario whose mutation targets a specific pre-committed matching record (the highest-value
   * one, so MIN/MAX transitions are exercised). The target is committed last so it is the MAX holder.
   */
  private Object runWithTarget(boolean cacheEnabled, String agg,
      java.util.function.Function<RID, Consumer<FrontendTransactionImpl>> mutationFor) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitMatching(10);
    commitMatching(20);
    var target = commitMatching(30); // the extremum holder for MIN/MAX
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = aggSql(agg);

    session.begin();
    scalar(session.query(sql));
    mutationFor.apply(target).accept(tx());
    var result = scalar(session.query(sql));
    session.rollback();
    return result;
  }

  // ===========================================================================
  // Collapse case — pre-populate CREATE of the holder + post-populate WHERE-break
  // ===========================================================================

  /**
   * A record created BEFORE the entry is populated (so its op stays typed CREATED but its version is
   * bumped past populate by a later in-tx UPDATE) whose UPDATE then breaks WHERE must be reconciled by
   * membership, not op type: the populate-time tap already observed it as a contributor, so the CREATED
   * op must be read as "drop the existing contributor", not "add a new one". MAX is used so a stale
   * contributor would visibly change the scalar.
   */
  @Test
  public void collapseCase_prePopulateCreateThenWhereBreak() {
    var fresh = runCollapse(false);
    var cached = runCollapse(true);
    assertEquals("a collapsed CREATE+UPDATE must reconcile by membership, matching fresh", fresh,
        cached);
  }

  /**
   * The SUM analogue of the collapse case driven end-to-end: a record created BEFORE the populating
   * query (so its op stays typed CREATED) whose value then changes in the same tx must re-fold by
   * membership, not double-add. Keying on op type would read the collapsed CREATED as a brand-new
   * contributor and add the changed value on top of the populate-time value, over-counting the sum.
   * SUM's collapse path is unit-tested in {@code AggregateStateTest} but had no live splice-path
   * coverage; this drives it through the splice/build/view pipeline and compares against fresh.
   */
  @Test
  public void collapseCase_prePopulateCreateThenValueChangeSum() {
    var fresh = runCollapseSumValueChange(false);
    var cached = runCollapseSumValueChange(true);
    assertEquals(
        "a collapsed CREATE+value-UPDATE for SUM must re-fold by membership, matching fresh",
        fresh, cached);
  }

  private Object runCollapseSumValueChange(boolean cacheEnabled) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitMatching(10); // a surviving matching record
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = aggSql("sum(" + VALUE + ")");

    session.begin();
    // CREATE a new matching record BEFORE the populating query, so the populate observes it (sum = 60).
    var created = session.newEntity(CLASS_NAME);
    created.setProperty(VALUE, 50);
    created.setProperty(FLAG, true);
    scalar(session.query(sql)); // populate; the created row contributes 50
    // Change its value in the same tx: the collapsed op stays CREATED but the record is already a
    // contributor, so the new value must replace the old (re-fold to 10 + 5), not double-add.
    created.setProperty(VALUE, 5);
    var result = scalar(session.query(sql)); // must re-fold; sum = 15, not 65
    session.rollback();
    return result;
  }

  private Object runCollapse(boolean cacheEnabled) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitMatching(10); // a surviving matching record
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = aggSql("max(" + VALUE + ")");

    session.begin();
    // CREATE a new high matching record BEFORE the first (populating) query, so the populate observes it.
    var created = session.newEntity(CLASS_NAME);
    created.setProperty(VALUE, 100);
    created.setProperty(FLAG, true);
    scalar(session.query(sql)); // populate; the created row is a contributor (max = 100)
    // Now break its WHERE in the same tx: the collapsed op stays CREATED but the record no longer matches.
    created.setProperty(FLAG, false);
    var result = scalar(session.query(sql)); // must drop it; max back to 10
    session.rollback();
    return result;
  }

  // ===========================================================================
  // Hardwired COUNT(*) fallback + arithmetic / distinct K0_NONE routings
  // ===========================================================================

  /**
   * Bare {@code COUNT(*) FROM C} classifies K0_NONE (hardwired, untappable): the aggregate splice is
   * never attempted, the K0 version gate serves it, and the scalar still matches a fresh execution. No
   * splice failure is counted (the shape never reaches the aggregate populate path).
   */
  @Test
  public void bareCountStarServedByK0NoneNotAggregatePath() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    clearClass();
    commitMatching(1);
    commitMatching(2);
    session.begin();
    var cache = tx().getQueryResultCache();
    var sql = "SELECT count(*) FROM " + CLASS_NAME;
    var first = scalar(session.query(sql));
    assertEquals("bare COUNT(*) is served and counts no splice failure", 0,
        cache.getMetrics().getSpliceFailures());
    var second = scalar(session.query(sql));
    assertEquals("bare COUNT(*) repeat matches the first read", first, second);
    assertEquals(2L, first);
    session.rollback();
  }

  /**
   * A single-field-indexed {@code COUNT(*) ... WHERE indexedField = ?} is hardwired to a
   * CountFromClassStep the side-tap cannot splice above, so the aggregate path falls back uncached and
   * counts exactly one splice failure, while the scalar still matches a fresh execution.
   */
  @Test
  public void indexedCountStarTakesSpliceFallback() {
    session.command(
        "CREATE INDEX " + CLASS_NAME + ".valueIdx ON " + CLASS_NAME + " (" + VALUE + ") NOTUNIQUE");
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    clearClass();
    commit(5, true);
    commit(5, true);
    commit(7, true);
    session.begin();
    var cache = tx().getQueryResultCache();
    var sql = "SELECT count(*) FROM " + CLASS_NAME + " WHERE " + VALUE + " = 5";
    var result = scalar(session.query(sql));
    assertEquals("the indexed COUNT(*) scalar still matches a fresh execution", 2L, result);
    assertEquals("the untappable indexed COUNT(*) shape counts one splice failure", 1,
        cache.getMetrics().getSpliceFailures());
    session.rollback();
  }

  /**
   * {@code count(*) + 1} (aggregate under arithmetic) classifies K0_NONE: the aggregate replay would
   * return the un-incremented scalar, so the K0 version gate serves it instead, and the cached value
   * matches a fresh execution exactly.
   */
  @Test
  public void aggregateUnderArithmeticServedByK0None() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    clearClass();
    commitMatching(1);
    commitMatching(2);
    session.begin();
    var sql = "SELECT count(*) + 1 AS c FROM " + CLASS_NAME + " WHERE " + FLAG + " = true";
    var first = scalar(session.query(sql));
    var second = scalar(session.query(sql));
    assertEquals("count(*) + 1 repeat matches the first read via the K0 gate", first, second);
    assertEquals("count(*) + 1 over two matching records is 3", 3L, first);
    session.rollback();
  }

  /**
   * {@code count(distinct(prop))} classifies K0_NONE (the engine has no native distinct-count and
   * computes it as a row count), so the K0 version gate keeps it correct across a CREATE / a
   * WHERE-breaking UPDATE / a DELETE: the cached scalar always equals a parallel fresh execution, which
   * returns the engine's row-count semantics.
   */
  @Test
  public void countDistinctServedByK0NoneMatchesEngineRowCount() {
    assertEquivalent("count(distinct(" + VALUE + "))", new int[] {10, 20, 30}, createMatching(40));
    var freshDel = runWithTarget(false, "count(distinct(" + VALUE + "))", this::deleteRecord);
    var cachedDel = runWithTarget(true, "count(distinct(" + VALUE + "))", this::deleteRecord);
    assertEquals("count(distinct) after a DELETE matches the engine row-count via the K0 gate",
        freshDel, cachedDel);
    var freshBreak = runWithTarget(false, "count(distinct(" + VALUE + "))", this::breakWhere);
    var cachedBreak = runWithTarget(true, "count(distinct(" + VALUE + "))", this::breakWhere);
    assertEquals("count(distinct) after a WHERE-breaking UPDATE matches the engine row-count",
        freshBreak, cachedBreak);
  }

  // ===========================================================================
  // COUNT(field) Regression Tests for Nullable Properties
  // ===========================================================================

  /**
   * Regression for COUNT(field) value-blind bug.
   * Verifies that during populate, rows where the target field is NULL are skipped,
   * matching the engine's fresh execution semantics.
   */
  @Test
  public void countFieldEquivalence_populatesIgnoringNullValues() {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitMatching(10);
    commitMatching(20);

    session.begin();
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(VALUE, null); // explicit NULL
    e.setProperty(FLAG, true);
    session.commit();

    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    var sql = "SELECT count(" + VALUE + ") FROM " + CLASS_NAME + " WHERE " + FLAG + " = true";

    session.begin();
    var result = scalar(session.query(sql));
    assertEquals("COUNT(field) must ignore null fields during populate", 2L,
        ((Number) result).longValue());
    session.rollback();
  }

  /**
   * Regression for COUNT(field) value-blind bug.
   * Verifies that a post-populate mutation changing a field from NULL to NON-NULL
   * correctly increments the replayed cached counter.
   */
  @Test
  public void countFieldEquivalence_nullToNonNullUpdateIncrementsCount() {
    var cached = runCountFieldMutationScenario(true, true, 99);
    var fresh = runCountFieldMutationScenario(false, true, 99);

    assertEquals("COUNT(field) cached replay must increment on null->non-null mutation", fresh,
        cached);
  }

  /**
   * Regression for COUNT(field) value-blind bug.
   * Verifies that a post-populate mutation changing a field from NON-NULL to NULL
   * correctly decrements the replayed cached counter.
   */
  @Test
  public void countFieldEquivalence_nonNullToNullUpdateDecrementsCount() {
    var cached = runCountFieldMutationScenario(true, false, null);
    var fresh = runCountFieldMutationScenario(false, false, null);

    assertEquals("COUNT(field) cached replay must decrement on non-null->null mutation", fresh,
        cached);
  }

  // ---- Specialized Helper for Null Field Mutation Scenarios ----

  /**
   * Drives the cache-on and cache-off pair specifically for custom null-value mutations.
   * Generates the target record INSIDE the scenario life-cycle to protect it from clearClass().
   */
  private Object runCountFieldMutationScenario(boolean cacheEnabled, boolean startAsNull,
      Object newValue) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    commitMatching(50); // baseline background row

    // 1. Generate the target record inside the scenario loop safely
    session.begin();
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(VALUE, startAsNull ? null : 10);
    e.setProperty(FLAG, true);
    var targetRid = ((RecordAbstract) e).getIdentity();
    session.commit();

    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = "SELECT count(" + VALUE + ") FROM " + CLASS_NAME + " WHERE " + FLAG + " = true";

    session.begin();
    scalar(session.query(sql)); // populate (cache enabled) or plain initial query (cache disabled)

    // 2. Apply the post-populate mutation log operation using the safely created RID
    Entity mutationRec = session.load(targetRid);
    mutationRec.setProperty(VALUE, newValue);
    tx().addRecordOperation((RecordAbstract) mutationRec, RecordOperation.UPDATED);

    var result = scalar(session.query(sql)); // replayed (on) vs fresh re-execution (off)
    session.rollback();
    return result;
  }

  // ===========================================================================
  // Contributor-cap overflow — high-cardinality SUM over a per-row distinct value
  // ===========================================================================

  /**
   * A populate that crosses the aggregate contributor cap routes the key non-cacheable: the over-cap
   * SUM is served once (its scalar still matches a fresh execution), an overflow is counted, no entry is
   * retained, and a second identical query stays uncached. Uses SUM over a high-cardinality value (each
   * matching record contributes a distinct value) so the AggregateState contributor collection is what
   * overflows. With the cap at 2 and three matching records, caching the third contributor overflows.
   */
  @Test
  public void contributorCapOverflowRoutesKeyNonCacheable() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    var prevCap =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.getValueAsInteger();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(2);
    try {
      clearClass();
      commitMatching(10);
      commitMatching(20);
      commitMatching(30);
      session.begin();
      var cache = tx().getQueryResultCache();
      var sql = aggSql("sum(" + VALUE + ")");

      var first = scalar(session.query(sql));
      assertEquals("the over-cap SUM is still computed correctly", 60, ((Number) first).intValue());
      assertEquals("crossing the contributor cap counts one overflow", 1,
          cache.getMetrics().getOverflows());
      assertEquals("an over-cap aggregate entry is not retained", 0, cache.size());

      var second = scalar(session.query(sql));
      assertEquals("the re-query returns the same scalar uncached", first, second);
      assertEquals("an overflowed key does not re-populate the cache", 0, cache.size());
      session.rollback();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY.setValue(prevCap);
    }
  }

  // ===========================================================================
  // Cache hit on repeat + no-exception-leak
  // ===========================================================================

  /**
   * A pure-read repeat of a tappable aggregate is a cache hit: the first query populates (one miss), the
   * second is served from the replayed view with no intervening mutation (one hit), and both scalars are
   * equal. This pins that the aggregate path actually caches rather than always re-executing.
   */
  @Test
  public void aggregateHitOnPureReadRepeat() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    clearClass();
    commitMatching(10);
    commitMatching(20);
    session.begin();
    var cache = tx().getQueryResultCache();
    var sql = aggSql("sum(" + VALUE + ")");

    var first = scalar(session.query(sql));
    assertEquals("first aggregate query is a miss", 1, cache.getMetrics().getMisses());
    assertEquals("the populate stored an aggregate entry", 1, cache.size());
    var second = scalar(session.query(sql));
    assertEquals("a pure-read aggregate repeat is a hit", 1, cache.getMetrics().getHits());
    assertEquals("the cached scalar equals the first read", first, second);
    assertEquals(30, ((Number) first).intValue());
    session.rollback();
  }

  /**
   * The whole per-kind matrix exercising the splice, eager drive, and replay must complete without any
   * exception leaking to the caller. This is the no-leak floor: every aggregate query above returned a
   * scalar (a thrown exception would have failed the test), and this case re-runs all five kinds in one
   * transaction to confirm the splice and fallback never throw.
   */
  @Test
  public void allKindsRunWithoutExceptionLeak() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    clearClass();
    commitMatching(10);
    commitMatching(20);
    session.begin();
    // The seeded set {10, 20} makes every scalar deterministic, so assert the actual value, not merely
    // non-null: a splice that returned a wrong-but-non-null scalar would otherwise slip through. Compare
    // via doubleValue() so the assertion is agnostic to the boxed numeric type (Integer/Long/Double).
    var expected = Map.of(
        "count(*)", 2.0,
        "sum(" + VALUE + ")", 30.0,
        "avg(" + VALUE + ")", 15.0,
        "min(" + VALUE + ")", 10.0,
        "max(" + VALUE + ")", 20.0);
    for (var agg : new String[] {"count(*)", "sum(" + VALUE + ")", "avg(" + VALUE + ")",
        "min(" + VALUE + ")", "max(" + VALUE + ")"}) {
      var s = scalar(session.query(aggSql(agg)));
      assertTrue(
          "every aggregate kind returns a non-null numeric scalar over a non-empty set: " + agg,
          s instanceof Number);
      assertEquals("aggregate " + agg + " must replay the correct scalar over {10, 20}",
          expected.get(agg), ((Number) s).doubleValue(), 0.0);
    }
    session.rollback();
  }

  /**
   * SUM / AVG / MIN / MAX over a single contributor that is DELETED after populate must emit ZERO rows,
   * matching a fresh execution. The other aggregate equivalence tests seed multiple records so the
   * contributor set never empties end-to-end; this drives the all-contributors-deleted transition
   * through the splice + build + view pipeline for every value-aggregate kind. A fresh SUM/AVG/MIN/MAX
   * over an empty input emits no row (only COUNT emits a single {@code 0}), so the cached aggregate view
   * must also emit zero rows rather than a single (null- or zero-scalar) row. Asserting the row COUNT is
   * load-bearing: before the {@code emitsNoRow} fix the cached view always emitted one row here,
   * diverging from a fresh execution's zero rows. COUNT is deliberately excluded — it emits one {@code 0}
   * row over an empty set, which the cache already matches.
   */
  @Test
  public void valueAggregatesEmitNoRowWhenAllContributorsDeleted() {
    for (var agg : new String[] {"sum(" + VALUE + ")", "avg(" + VALUE + ")", "min(" + VALUE + ")",
        "max(" + VALUE + ")"}) {
      var fresh = runSingleContributorDeleteRows(false, agg);
      var cached = runSingleContributorDeleteRows(true, agg);
      assertEquals("a fresh " + agg + " over the emptied set emits zero rows", 0, fresh.size());
      assertEquals("the cached " + agg + " replay must match fresh and also emit zero rows", fresh,
          cached);
    }
  }

  /**
   * Seeds exactly one matching record, populates the aggregate entry, deletes that sole contributor in
   * the same tx, then captures the second query's rows (one scalar value per row, null when the row
   * carries no scalar property). Driven once per flag state; comparing the two captured row lists is the
   * equivalence assertion.
   */
  private List<Object> runSingleContributorDeleteRows(boolean cacheEnabled, String agg) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    var only = commitMatching(10); // the single contributor
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);
    var sql = aggSql(agg);

    session.begin();
    scalar(session.query(sql)); // populate (scalar = 10)
    deleteRecord(only).accept(tx()); // delete the only contributor
    var rows = scalarRows(session.query(sql)); // value aggregate over an emptied set must replay to zero rows
    session.rollback();
    return rows;
  }

  /** Drains a result set into one scalar value per row (null when a row has no scalar property). */
  private static List<Object> scalarRows(ResultSet rs) {
    var out = new ArrayList<>();
    try (rs) {
      while (rs.hasNext()) {
        var row = rs.next();
        var names = row.getPropertyNames();
        out.add(names.isEmpty() ? null : row.getProperty(names.iterator().next()));
      }
    }
    return out;
  }
}
