package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
import java.util.function.BiConsumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end cache-vs-fresh equivalence for the {@code DISTINCT_VALUES} shape
 * ({@code SELECT distinct(prop)} and {@code SELECT DISTINCT prop}). A distinct value-set projection is
 * reconciled incrementally through the per-value RID buckets reused from {@code AGGREGATE_COUNT_DISTINCT}:
 * the view emits one row per distinct value, recalculated after an in-transaction mutation.
 *
 * <p>This shape was a latent correctness bug before the fix: such a projection classified as RECORD,
 * which stores the engine's already-deduped rows and, under a mutation, injects raw records without
 * deduping — diverging from a fresh execution. Each case here runs the same scenario twice through the
 * live {@code query()} path (cache off = the fresh source of truth, then cache on) and compares the row
 * sequences position-by-position, so a divergence in set OR order fails.
 */
@Category(SequentialTest.class)
public class DistinctValuesEquivalenceTest extends DbTestBase {

  private static final String CLASS_NAME = "DistVal";
  private static final String V = "v";
  private static final String FLAG = "active";

  // DISTINCT_VALUES is admitted only with a deterministic ORDER BY on the projected column, so the
  // cached bucket keys sort to match a fresh execution; an unordered distinct stays K0_NONE.
  private static final String FN_SQL =
      "SELECT distinct(" + V + ") FROM " + CLASS_NAME + " WHERE " + FLAG + " = true ORDER BY " + V;
  private static final String OP_SQL =
      "SELECT DISTINCT " + V + " FROM " + CLASS_NAME + " WHERE " + FLAG + " = true ORDER BY " + V;

  private boolean previousEnabled;

  @Before
  public void enableSchema() {
    previousEnabled = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    var cls = session.createClass(CLASS_NAME);
    cls.createProperty(V, PropertyType.INTEGER);
    cls.createProperty(FLAG, PropertyType.BOOLEAN);
  }

  @After
  public void restoreFlag() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previousEnabled);
  }

  private FrontendTransactionImpl tx() {
    return (FrontendTransactionImpl) session.getTransactionInternal();
  }

  private void clearClass() {
    var prev = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    try {
      session.begin();
      session.command("DELETE FROM " + CLASS_NAME);
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(prev);
    }
  }

  private RID commit(int value, boolean active) {
    session.begin();
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(V, value);
    e.setProperty(FLAG, active);
    var rid = ((RecordAbstract) e).getIdentity();
    session.commit();
    return rid;
  }

  /** The single value column of each row, in order; an ORDER-BY-free distinct keeps first-occurrence. */
  private static List<Object> snapshot(ResultSet rs) {
    var out = new ArrayList<>();
    try (rs) {
      while (rs.hasNext()) {
        Result row = rs.next();
        Object value = row.getProperty(row.getPropertyNames().iterator().next());
        out.add(value);
      }
    }
    return out;
  }

  /**
   * Seeds a committed set with the cache off, opens a tx, populates the cache with the distinct query,
   * applies {@code mutation} (which receives the seeded RIDs), then captures the second query's rows.
   */
  private List<Object> run(boolean cacheOn, int[] seeds,
      BiConsumer<List<RID>, FrontendTransactionImpl> mutation, String sql) {
    clearClass();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    var rids = new ArrayList<RID>();
    for (var v : seeds) {
      rids.add(commit(v, true));
    }
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheOn);
    session.begin();
    snapshot(session.query(sql)); // populate (on) or plain execution (off)
    mutation.accept(rids, tx());
    var result = snapshot(session.query(sql)); // delta-replayed view (on) or fresh (off)
    session.rollback();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    return result;
  }

  private void assertEquivalent(int[] seeds,
      BiConsumer<List<RID>, FrontendTransactionImpl> mutation, String sql) {
    var fresh = run(false, seeds, mutation, sql);
    var cached = run(true, seeds, mutation, sql);
    assertEquals("cached distinct value set must equal a fresh execution for " + sql, fresh,
        cached);
  }

  // The mutation patterns, expressed against the single bound class. A loaded-record DELETE/UPDATE uses
  // addRecordOperation because session.delete(session.load(rid)) on a pre-tx record throws on re-query.

  private void createMatching(int value) {
    var e = session.newEntity(CLASS_NAME);
    e.setProperty(V, value);
    e.setProperty(FLAG, true);
  }

  private void deleteRecord(RID rid, FrontendTransactionImpl t) {
    Entity rec = session.load(rid);
    t.addRecordOperation((RecordAbstract) rec, RecordOperation.DELETED);
  }

  private void breakWhere(RID rid, FrontendTransactionImpl t) {
    Entity rec = session.load(rid);
    rec.setProperty(FLAG, false);
    t.addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
  }

  private void changeValue(RID rid, int newValue, FrontendTransactionImpl t) {
    Entity rec = session.load(rid);
    rec.setProperty(V, newValue);
    t.addRecordOperation((RecordAbstract) rec, RecordOperation.UPDATED);
  }

  // ===========================================================================
  // Function form: SELECT distinct(v)
  // ===========================================================================

  /** A CREATE with a brand-new value adds exactly one distinct value, at the tail. */
  @Test
  public void createNewValue_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 30}, (rids, t) -> createMatching(40), FN_SQL);
  }

  /** A CREATE with an already-present value leaves the distinct set unchanged. */
  @Test
  public void createDuplicateValue_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 30}, (rids, t) -> createMatching(20), FN_SQL);
  }

  /** Deleting one of two records sharing a value keeps the value (the other RID still contributes it). */
  @Test
  public void deleteOneOfTwoSharingValue_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 20}, (rids, t) -> deleteRecord(rids.get(1), t), FN_SQL);
  }

  /** Deleting the last contributor of a value drops that value from the distinct set. */
  @Test
  public void deleteLastContributor_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 30}, (rids, t) -> deleteRecord(rids.get(0), t), FN_SQL);
  }

  /** A WHERE-breaking UPDATE removes the record's contribution; its value drops if it was the last. */
  @Test
  public void whereBreakUpdate_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 30}, (rids, t) -> breakWhere(rids.get(0), t), FN_SQL);
  }

  /** A value UPDATE moves a contributor between buckets. */
  @Test
  public void valueUpdate_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 30}, (rids, t) -> changeValue(rids.get(0), 99, t), FN_SQL);
  }

  // ===========================================================================
  // Operator form: SELECT DISTINCT v
  // ===========================================================================

  @Test
  public void operatorForm_createNewValue_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 30}, (rids, t) -> createMatching(40), OP_SQL);
  }

  @Test
  public void operatorForm_deleteOneOfTwoSharingValue_matchesFresh() {
    assertEquivalent(new int[] {10, 20, 20}, (rids, t) -> deleteRecord(rids.get(1), t), OP_SQL);
  }

  // ===========================================================================
  // The splice actually fires (incremental path, not the uncached fallback)
  // ===========================================================================

  /**
   * Proves the incremental (DISTINCT_VALUES) path is taken, not the K0_NONE fallback: after a populate
   * and an in-tx CREATE of a new value, the re-query is served as a cache HIT (a K0_NONE entry would
   * invalidate on the mutation and re-execute as a MISS) with no splice failure, and it reflects the new
   * value. A no-mutation K0_NONE replay could not be distinguished by a hit alone, so the mutation is
   * essential: only a delta-reconcilable shape stays cached across it.
   */
  @Test
  public void distinctValuesIncrementalPathServesHitAcrossMutation() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);
    clearClass();
    commit(10, true);
    commit(20, true);
    commit(20, true);
    session.begin();
    var cache = tx().getQueryResultCache();
    assertNotNull(cache);
    var first = snapshot(session.query(FN_SQL)); // miss + populate
    assertEquals("distinct over [10,20,20] is two values", List.of(10, 20), first);
    createMatching(40); // in-tx CREATE of a new distinct value
    var second = snapshot(session.query(FN_SQL)); // delta-reconciled HIT (not a K0 re-execute)
    assertEquals("the incremental view reflects the new value, sorted", List.of(10, 20, 40),
        second);
    assertEquals("the re-query after a mutation was a cache hit, not a K0_NONE re-execute", 1,
        cache.getMetrics().getHits());
    assertEquals("the incremental tap fired with no splice failure", 0,
        cache.getMetrics().getSpliceFailures());
    session.rollback();
  }
}
