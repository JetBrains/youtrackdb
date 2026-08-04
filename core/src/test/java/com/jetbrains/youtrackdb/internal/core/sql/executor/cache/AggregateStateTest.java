package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordOperation;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies {@link AggregateState} in isolation: per-kind {@code observe}/{@code applyMutation}
 * transitions, the SUM/AVG storage-parity fold, AVG integer-truncation and BigDecimal HALF_UP
 * finalisation, MIN/MAX extremum transitions including the O(n) recompute, COUNT_DISTINCT bucket
 * lifecycle, the collapse case (a {@code CREATED}-typed op already a contributor dispatched
 * by membership, not op type), {@code copy} isolation, and the contributor cap overflow callback.
 *
 * <p>The replay path it exercises is the same {@link DeltaBuilder#buildForAggregate} drives: seed the
 * state by observing the pre-mutation records ("populate"), then replay a post-populate mutation and
 * assert the finalised scalar matches what a fresh aggregate over the post-mutation set returns. Each
 * test also pins the parity invariant directly against the storage primitive ({@link
 * PropertyTypeInternal#increment}) where the value, not just the count, is load-bearing.
 */
public class AggregateStateTest {

  private static final String CLASS_NAME = "AggRec";
  private static final String FIELD = "v";

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded db;

  @Before
  public void before() {
    youTrackDB =
        DbTestBase.createYTDBManagerAndDb(getClass().getSimpleName(), DatabaseType.MEMORY,
            getClass());
    db = youTrackDB.open(getClass().getSimpleName(), "admin", DbTestBase.ADMIN_PASSWORD);
    // Schema-less class: the FIELD property is left undeclared so a test can store Integer / Long /
    // Double / BigDecimal verbatim under the same name and exercise the cross-subtype fold and
    // comparison paths the storage functions take. A declared type would coerce every value to it.
    db.createClass(CLASS_NAME);
  }

  @After
  public void after() {
    // Roll back any still-open transaction before closing: a test that fails mid-body skips its own
    // terminal rollback, and closing a session with an active tx turns one clean assertion failure into
    // a noisier close/drop failure that masks the real cause.
    if (db.getTransactionInternal().isActive()) {
      db.rollback();
    }
    db.close();
    youTrackDB.drop(getClass().getSimpleName());
    youTrackDB.close();
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private FrontendTransactionImpl tx() {
    return (FrontendTransactionImpl) db.getTransactionInternal();
  }

  private CommandContext ctx() {
    return new BasicCommandContext(db);
  }

  private static SQLWhereClause parseWhere(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      return ((SQLSelectStatement) parser.parse()).getWhereClause();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse: " + selectSql, e);
    }
  }

  /** Creates an uncommitted record of CLASS_NAME with FIELD=value and returns it as an Entity. */
  private Entity newRec(Object value) {
    var e = db.newEntity(CLASS_NAME);
    e.setProperty(FIELD, value);
    return e;
  }

  /** Creates and commits a record, then reopens a fresh transaction and returns it reloaded. */
  private Entity committedRec(Object value) {
    db.begin();
    var e = db.newEntity(CLASS_NAME);
    e.setProperty(FIELD, value);
    var rid = ridOf(e);
    db.commit();
    db.begin();
    return db.load(rid);
  }

  private static RID ridOf(Entity e) {
    return ((RecordAbstract) e).getIdentity();
  }

  /** A result wrapping a record, mirroring what the side-tap forwards into {@code observe}. */
  private Result resultOf(Entity e) {
    return new ResultInternal(db, e);
  }

  private AggregateState state(CacheableShape kind, String propertyName) {
    return new AggregateState(kind, propertyName, kind.name());
  }

  /** Observes a list of records into a fresh state, simulating the populate-time side-tap. */
  private AggregateState populate(CacheableShape kind, String propertyName, List<Entity> records) {
    var s = state(kind, propertyName);
    for (var r : records) {
      s.observe(resultOf(r));
    }
    return s;
  }

  private Object scalarOf(AggregateState s) {
    return s.toResult(db).getProperty(kindAlias(s));
  }

  private static String kindAlias(AggregateState s) {
    return s.getKind().name();
  }

  // ===========================================================================
  // observe + toResult, per kind
  // ===========================================================================

  /** COUNT(*) observes membership only and its scalar is the contributor count as a long. */
  @Test
  public void countObserveProducesContributorCount() {
    db.begin();
    var s =
        populate(CacheableShape.AGGREGATE_COUNT, null, List.of(newRec(1), newRec(2), newRec(3)));
    assertEquals(3L, scalarOf(s));
    db.rollback();
  }

  /**
   * SUM folds each observed value through {@link PropertyTypeInternal#increment} from a verbatim first
   * seed, so the cached scalar equals the storage primitive applied in the same order.
   */
  @Test
  public void sumObserveMatchesIncrementFold() {
    db.begin();
    var s =
        populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(newRec(10), newRec(20), newRec(5)));
    // increment(increment(10, 20), 5) is the exact storage fold for SUM.
    var expected = PropertyTypeInternal.increment(PropertyTypeInternal.increment(10, 20), 5);
    assertEquals(expected, scalarOf(s));
    db.rollback();
  }

  /** SUM over an empty contributor set is 0 (an int), matching {@code SQLFunctionSum.getResult}. */
  @Test
  public void sumOverEmptySetIsZeroNotNull() {
    db.begin();
    var s = state(CacheableShape.AGGREGATE_SUM, FIELD);
    assertEquals(0, scalarOf(s));
    db.rollback();
  }

  /**
   * AVG over integer input truncates exactly as {@code computeAverage} (integer division): three
   * Integer values 10/20/5 sum to Integer 35, divided by 3 is 11, not 11.67.
   */
  @Test
  public void avgIntegerInputTruncates() {
    db.begin();
    var s =
        populate(CacheableShape.AGGREGATE_AVG, FIELD, List.of(newRec(10), newRec(20), newRec(5)));
    assertEquals(11, scalarOf(s)); // (35 / 3) integer truncation
    db.rollback();
  }

  /**
   * AVG over BigDecimal input rounds HALF_UP: 10 + 20 + 5 = 35 over 3 contributors is 11.666...,
   * which HALF_UP at the operands' scale yields 12 (scale 0). This pins the BigDecimal finalisation
   * branch distinct from integer truncation.
   */
  @Test
  public void avgBigDecimalInputRoundsHalfUp() {
    db.begin();
    var s =
        populate(
            CacheableShape.AGGREGATE_AVG,
            FIELD,
            List.of(
                newRec(new BigDecimal("10")),
                newRec(new BigDecimal("20")),
                newRec(new BigDecimal("5"))));
    // BigDecimal(35).divide(BigDecimal(3), HALF_UP) at scale 0 is 12.
    assertEquals(new BigDecimal("12"), scalarOf(s));
    db.rollback();
  }

  /** MIN over mixed values returns the smallest; MAX returns the largest. */
  @Test
  public void minAndMaxObserveExtremum() {
    db.begin();
    var recs = List.of(newRec(30), newRec(10), newRec(20));
    var min = populate(CacheableShape.AGGREGATE_MIN, FIELD, recs);
    var max = populate(CacheableShape.AGGREGATE_MAX, FIELD, recs);
    assertEquals(10, scalarOf(min));
    assertEquals(30, scalarOf(max));
    db.rollback();
  }

  /**
   * COUNT(DISTINCT) buckets by raw Object equality: two equal Integer values share a bucket, a third
   * distinct value adds one. The scalar is the live bucket count.
   */
  @Test
  public void countDistinctBucketsByValue() {
    db.begin();
    var s =
        populate(
            CacheableShape.AGGREGATE_COUNT_DISTINCT,
            FIELD,
            List.of(newRec(5), newRec(5), newRec(7)));
    assertEquals(2L, scalarOf(s)); // {5, 7}
    db.rollback();
  }

  /**
   * COUNT(DISTINCT) follows storage's {@code LinkedHashSet<Object>} semantics, so {@code Long(5)} and
   * {@code Integer(5)} are distinct buckets even though they are numerically equal.
   */
  @Test
  public void countDistinctTreatsCrossSubtypeValuesAsDistinct() {
    db.begin();
    var s =
        populate(
            CacheableShape.AGGREGATE_COUNT_DISTINCT,
            FIELD,
            List.of(newRec(5), newRec(5L)));
    assertEquals("Long(5) and Integer(5) are distinct buckets", 2L, scalarOf(s));
    db.rollback();
  }

  // ===========================================================================
  // SUM storage parity: mixed input, overflow, precision loss
  // ===========================================================================

  /**
   * SUM over mixed Long+Double input promotes to Double exactly as the storage fold does, so the
   * cached scalar type and value match {@code increment(Long, Double)} bit-for-bit.
   */
  @Test
  public void sumMixedLongDoublePromotesLikeStorage() {
    db.begin();
    var s =
        populate(
            CacheableShape.AGGREGATE_SUM, FIELD, List.of(newRec(3L), newRec(1.5d)));
    var expected = PropertyTypeInternal.increment(3L, 1.5d);
    assertEquals(expected, scalarOf(s));
    assertTrue("mixed Long+Double promotes to Double", scalarOf(s) instanceof Double);
    db.rollback();
  }

  /**
   * SUM at {@code Long.MAX_VALUE + 1} wraps exactly as the storage fold's Long arithmetic does: the
   * cache re-fold produces the identical wrapped value by construction (same primitive, same order).
   */
  @Test
  public void sumLongOverflowWrapsLikeStorage() {
    db.begin();
    var s =
        populate(
            CacheableShape.AGGREGATE_SUM, FIELD, List.of(newRec(Long.MAX_VALUE), newRec(1L)));
    var expected = PropertyTypeInternal.increment(Long.MAX_VALUE, 1L);
    assertEquals(expected, scalarOf(s));
    db.rollback();
  }

  /**
   * SUM that promotes a Long to Double at {@code 2^53 + 1} loses precision exactly as storage does:
   * both paths run the same {@code increment}, so the lossy result is identical by construction.
   */
  @Test
  public void sumLongToDoublePrecisionLossMatchesStorage() {
    db.begin();
    long big = (1L << 53) + 1;
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(newRec(big), newRec(1.0d)));
    var expected = PropertyTypeInternal.increment(big, 1.0d);
    assertEquals(expected, scalarOf(s));
    db.rollback();
  }

  /** Folds a value list through {@code increment} in list order, the deterministic storage scan-order fold. */
  private static Number scanOrderSumFold(List<? extends Number> values) {
    Number acc = null;
    for (var v : values) {
      acc = acc == null ? v : PropertyTypeInternal.increment(acc, v);
    }
    return acc;
  }

  /**
   * SUM over Double inputs must fold in observe (== storage scan) order, not in hash-bucket order.
   * IEEE-754 addition is not associative, so a fold permuted by the backing map's iteration order can
   * round to a different low-order bit than a fresh scan-order execution. With enough contributors
   * spanning many magnitudes (a large running total plus many tiny addends), the scan-order fold and a
   * sorted/permuted fold give measurably different doubles, so this test fails if {@code
   * contributingValues} ever reverts to an unordered map. The cached scalar must equal the scan-order
   * {@code increment} fold over the SAME input order bit-for-bit.
   */
  @Test
  public void sumDoubleFoldsInObserveOrderMatchingStorageScanOrder() {
    db.begin();
    // 1e16 first (so it dominates the running total), then 64 tiny addends each below the running
    // total's ULP: in scan order each tiny add rounds away to nothing; a permuted fold that sums the
    // tiny values together first preserves them, yielding a different double. The order is therefore
    // load-bearing for the result, not just the type.
    var values = new java.util.ArrayList<Double>();
    values.add(1.0e16d);
    for (var i = 0; i < 64; i++) {
      values.add(1.0d);
    }
    var recs = new java.util.ArrayList<Entity>();
    for (var v : values) {
      recs.add(newRec(v));
    }
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, recs);
    var expected = scanOrderSumFold(values);
    assertEquals("SUM double fold must match the scan-order increment fold bit-for-bit", expected,
        scalarOf(s));
    db.rollback();
  }

  /**
   * AVG over Double inputs divides a scan-order-folded sum, so the divided result is also order-
   * sensitive: an out-of-order fold feeds a different sum into {@code computeAverage}. The cached AVG
   * must equal a fresh scan-order fold divided by the contributor count, pinning that the observe-order
   * fold reaches the AVG finalisation too.
   */
  @Test
  public void avgDoubleFoldsInObserveOrderMatchingStorageScanOrder() {
    db.begin();
    var values = new java.util.ArrayList<Double>();
    values.add(1.0e16d);
    for (var i = 0; i < 64; i++) {
      values.add(1.0d);
    }
    var recs = new java.util.ArrayList<Entity>();
    for (var v : values) {
      recs.add(newRec(v));
    }
    var s = populate(CacheableShape.AGGREGATE_AVG, FIELD, recs);
    var expectedSum = scanOrderSumFold(values).doubleValue();
    assertEquals("AVG double must divide the scan-order sum", expectedSum / values.size(),
        (Double) scalarOf(s), 0.0d);
    db.rollback();
  }

  /**
   * SUM over mixed Integer inputs straddling the {@code Integer} overflow boundary is type-sensitive to
   * fold order: {@code PropertyTypeInternal.increment} promotes Integer+Integer to Long only when the
   * partial sum overflows, so whether an intermediate overflows (and thus whether the result type ends
   * up Integer or Long) depends on the partial-sum order. Folding in observe order must reproduce the
   * scan-order fold's type AND value exactly; an unordered fold could overflow at a different step and
   * disagree on both. Inputs chosen so the running scan-order total crosses {@code Integer.MAX_VALUE}
   * mid-fold.
   */
  @Test
  public void sumMixedIntOverflowFoldsInObserveOrderMatchingStorageScanOrder() {
    db.begin();
    // Scan-order partials: 2e9, then +2e9 overflows int (promotes to Long 4e9), then +(-1e9) and
    // +1 stay Long. A different fold order (e.g. summing the negative early) would keep the partial
    // in int range longer and could land on a different result type.
    var values = List.of(2_000_000_000, 2_000_000_000, -1_000_000_000, 1);
    var recs = new java.util.ArrayList<Entity>();
    for (var v : values) {
      recs.add(newRec(v));
    }
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, recs);
    var expected = scanOrderSumFold(values);
    var scalar = scalarOf(s);
    assertEquals("mixed-int SUM must match the scan-order fold value", expected, scalar);
    assertEquals("mixed-int SUM must match the scan-order fold type", expected.getClass(),
        scalar.getClass());
    db.rollback();
  }

  /**
   * The SUM/AVG re-fold is deferred to a single fold per build, not run once per replayed mutation
   * (the running scalar is never read between mutations). Replaying several SUM-affecting mutations
   * onto one state and reading the scalar once must produce the correct final fold. This pins that
   * intermediate folds are collapsed without changing the observable result.
   */
  @Test
  public void sumDeferredFoldProducesCorrectFinalScalarAfterManyMutations() {
    var a = committedRec(10);
    var b = committedRec(20);
    var c = committedRec(30);
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(a, b, c));
    // Three post-populate value changes; with deferral the scalar is folded once, on the read below.
    a.setProperty(FIELD, 1);
    s.applyMutation((RecordAbstract) a, RecordOperation.UPDATED, true);
    b.setProperty(FIELD, 2);
    s.applyMutation((RecordAbstract) b, RecordOperation.UPDATED, true);
    c.setProperty(FIELD, 3);
    s.applyMutation((RecordAbstract) c, RecordOperation.UPDATED, true);
    var expected = PropertyTypeInternal.increment(PropertyTypeInternal.increment(1, 2), 3);
    assertEquals("the single deferred fold yields the final scan-order fold", expected,
        scalarOf(s));
    db.rollback();
  }

  // ===========================================================================
  // applyMutation transitions, per kind
  // ===========================================================================

  /**
   * COUNT F&rarr;T: a post-populate CREATE that matches WHERE adds a contributor, so the scalar grows
   * by one. The transition is keyed on membership (the new RID is not yet contributing).
   */
  @Test
  public void countApplyMutationFtoTAddsContributor() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_COUNT, null, List.of(newRec(1)));
    var added = newRec(2);
    s.applyMutation((RecordAbstract) added, RecordOperation.CREATED, true);
    assertEquals(2L, scalarOf(s));
    db.rollback();
  }

  /** COUNT T&rarr;F: a DELETE of an existing contributor drops it, so the scalar shrinks by one. */
  @Test
  public void countApplyMutationTtoFOnDeleteDropsContributor() {
    var existing = committedRec(1);
    var s = populate(CacheableShape.AGGREGATE_COUNT, null, List.of(existing));
    s.applyMutation((RecordAbstract) existing, RecordOperation.DELETED, true);
    assertEquals(0L, scalarOf(s));
    db.rollback();
  }

  /**
   * COUNT T&rarr;F: an UPDATE that drives a contributor out of WHERE ({@code matchAfter=false}) drops
   * it even though the op is UPDATED, confirming {@code matchAfter}, not op type, drives the drop.
   */
  @Test
  public void countApplyMutationTtoFOnWhereFailDropsContributor() {
    var existing = committedRec(5);
    var s = populate(CacheableShape.AGGREGATE_COUNT, null, List.of(existing));
    s.applyMutation((RecordAbstract) existing, RecordOperation.UPDATED, false);
    assertEquals(0L, scalarOf(s));
    db.rollback();
  }

  /**
   * SUM T&rarr;T value change re-folds the whole contributor set: changing one value from 20 to 100
   * yields the same scalar a fresh fold of {10, 100} produces, via the full re-fold (no subtract).
   */
  @Test
  public void sumApplyMutationTtoTRefolds() {
    var a = committedRec(10);
    var b = committedRec(20);
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(a, b));
    b.setProperty(FIELD, 100);
    s.applyMutation((RecordAbstract) b, RecordOperation.UPDATED, true);
    var expected = PropertyTypeInternal.increment(10, 100);
    assertEquals(expected, scalarOf(s));
    db.rollback();
  }

  /** SUM T&rarr;F drop re-folds the remaining set, so removing 20 from {10, 20} leaves 10. */
  @Test
  public void sumApplyMutationTtoFRefoldsRemaining() {
    var a = committedRec(10);
    var b = committedRec(20);
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(a, b));
    s.applyMutation((RecordAbstract) b, RecordOperation.DELETED, true);
    assertEquals(10, scalarOf(s));
    db.rollback();
  }

  /**
   * MAX T&rarr;F drop of the extremum holder triggers the O(n) recompute: removing the 30-holder from
   * {30, 10, 20} re-derives 20 as the new max by scanning the remaining contributors.
   */
  @Test
  public void maxApplyMutationDropOfExtremumRecomputes() {
    var top = committedRec(30);
    var mid = committedRec(20);
    var low = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_MAX, FIELD, List.of(top, mid, low));
    assertEquals(30, scalarOf(s));
    s.applyMutation((RecordAbstract) top, RecordOperation.DELETED, true);
    assertEquals("dropping the holder recomputes the new max", 20, scalarOf(s));
    db.rollback();
  }

  /**
   * MAX T&rarr;T where the holder's value drops below a non-holder triggers the recompute path: the
   * 30-holder updated to 5, against {25, 5}, must re-derive 25 (the non-holder) as the new max.
   */
  @Test
  public void maxApplyMutationHolderDropsBelowNonHolderRecomputes() {
    var holder = committedRec(30);
    var other = committedRec(25);
    var s = populate(CacheableShape.AGGREGATE_MAX, FIELD, List.of(holder, other));
    assertEquals(30, scalarOf(s));
    holder.setProperty(FIELD, 5);
    s.applyMutation((RecordAbstract) holder, RecordOperation.UPDATED, true);
    assertEquals("holder losing extremum direction recomputes from remaining values", 25,
        scalarOf(s));
    db.rollback();
  }

  /**
   * MIN F&rarr;T where the new value beats the current extremum updates the scalar in O(1): adding 3
   * to {10, 20} makes 3 the new min.
   */
  @Test
  public void minApplyMutationNewValueBeatsExtremum() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_MIN, FIELD, List.of(newRec(10), newRec(20)));
    var added = newRec(3);
    s.applyMutation((RecordAbstract) added, RecordOperation.CREATED, true);
    assertEquals(3, scalarOf(s));
    db.rollback();
  }

  /**
   * COUNT(DISTINCT) T&rarr;T key change moves a RID between buckets: changing the only 5-contributor
   * to 7 empties the 5 bucket and grows the 7 bucket, so {5, 7} becomes {7} and the scalar drops to 1.
   */
  @Test
  public void countDistinctApplyMutationKeyChangeMovesBucket() {
    var five = committedRec(5);
    var seven = committedRec(7);
    var s = populate(CacheableShape.AGGREGATE_COUNT_DISTINCT, FIELD, List.of(five, seven));
    assertEquals(2L, scalarOf(s));
    five.setProperty(FIELD, 7);
    s.applyMutation((RecordAbstract) five, RecordOperation.UPDATED, true);
    assertEquals("the 5 bucket empties and merges into 7", 1L, scalarOf(s));
    db.rollback();
  }

  /**
   * COUNT(DISTINCT) T&rarr;F drop cleans up the bucket: deleting the only 7-contributor removes the 7
   * bucket entirely, dropping {5, 7} to {5}.
   */
  @Test
  public void countDistinctApplyMutationDropCleansBucket() {
    var five = committedRec(5);
    var seven = committedRec(7);
    var s = populate(CacheableShape.AGGREGATE_COUNT_DISTINCT, FIELD, List.of(five, seven));
    s.applyMutation((RecordAbstract) seven, RecordOperation.DELETED, true);
    assertEquals(1L, scalarOf(s));
    assertFalse("the emptied 7 bucket is removed",
        s.getDistinctBuckets().containsKey(7));
    db.rollback();
  }

  /**
   * COUNT(field) over a nullable property skips null values on populate,
   * increments when a null value becomes non-null, and decrements when
   * a non-null value becomes null.
   */
  @Test
  public void countFieldHandlesNullAndNonNullTransitions() {
    var withValue = committedRec(10);
    var withNull = committedRec(null);

    var s = populate(CacheableShape.AGGREGATE_COUNT, FIELD, List.of(withValue, withNull));
    assertEquals("COUNT(field) should ignore nulls on populate", 1L, scalarOf(s));

    withNull.setProperty(FIELD, 20);
    s.applyMutation((RecordAbstract) withNull, RecordOperation.UPDATED, true);
    assertEquals("COUNT(field) should increment when null becomes non-null", 2L, scalarOf(s));

    withNull.setProperty(FIELD, null);
    s.applyMutation((RecordAbstract) withNull, RecordOperation.UPDATED, true);
    assertEquals("COUNT(field) should decrement when non-null becomes null", 1L, scalarOf(s));
  }

  // ===========================================================================
  // Empty-set drain: the last contributor removed by replay, distinct from the
  // never-seeded empty path. MIN/MAX/AVG must drain to null; COUNT/COUNT_DISTINCT
  // to 0. Parity with a fresh aggregate over the emptied set (null/0).
  // ===========================================================================

  /**
   * MIN drained to empty returns null: deleting the sole contributor leaves {@code recomputeExtremum}
   * with no values, so {@code currentScalar} is null — the same result a fresh MIN over an empty set
   * gives ({@code SQLFunctionMin.getResult} returns null). This pins the drain transition (holder
   * dropped, rescan finds nothing), which is a distinct code path from the never-seeded empty MIN.
   */
  @Test
  public void minDrainsToNullWhenLastContributorDropped() {
    var only = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_MIN, FIELD, List.of(only));
    assertEquals(10, scalarOf(s));
    s.applyMutation((RecordAbstract) only, RecordOperation.DELETED, true);
    assertEquals("MIN over an emptied set is null, matching SQLFunctionMin", null, scalarOf(s));
    db.rollback();
  }

  /**
   * MAX drained to empty returns null, symmetric with MIN: the extremum-holder drop triggers
   * {@code recomputeExtremum} over an empty contributor set, leaving {@code currentScalar} null.
   */
  @Test
  public void maxDrainsToNullWhenLastContributorDropped() {
    var only = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_MAX, FIELD, List.of(only));
    assertEquals(10, scalarOf(s));
    s.applyMutation((RecordAbstract) only, RecordOperation.DELETED, true);
    assertEquals("MAX over an emptied set is null, matching SQLFunctionMax", null, scalarOf(s));
    db.rollback();
  }

  /**
   * AVG drained to empty returns null, never a divide-by-zero: dropping the sole contributor leaves
   * {@code count==0} with a null accumulator, so {@code computeAverage(null, 0)} returns null rather
   * than dividing by zero. Pins that the empty drain stays null-not-zero, the contract that keeps the
   * {@code total==0} integer-division guard load-bearing.
   */
  @Test
  public void avgDrainsToNullNotDivideByZero() {
    var only = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_AVG, FIELD, List.of(only));
    assertEquals(10, scalarOf(s));
    s.applyMutation((RecordAbstract) only, RecordOperation.DELETED, true);
    assertEquals("AVG over an emptied set is null, never a divide-by-zero", null, scalarOf(s));
    db.rollback();
  }

  /** COUNT drained to empty is 0L: deleting the sole contributor drops the count to zero. */
  @Test
  public void countDrainsToZeroWhenLastContributorDropped() {
    var only = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_COUNT, null, List.of(only));
    assertEquals(1L, scalarOf(s));
    s.applyMutation((RecordAbstract) only, RecordOperation.DELETED, true);
    assertEquals("COUNT over an emptied set is 0", 0L, scalarOf(s));
    db.rollback();
  }

  /**
   * COUNT(DISTINCT) drained to empty is 0L: deleting the sole contributor empties its bucket, leaving
   * no buckets, so the live bucket count is zero.
   */
  @Test
  public void countDistinctDrainsToZeroWhenLastContributorDropped() {
    var only = committedRec(10);
    var s = populate(CacheableShape.AGGREGATE_COUNT_DISTINCT, FIELD, List.of(only));
    assertEquals(1L, scalarOf(s));
    s.applyMutation((RecordAbstract) only, RecordOperation.DELETED, true);
    assertEquals("COUNT(DISTINCT) over an emptied set is 0", 0L, scalarOf(s));
    db.rollback();
  }

  // ===========================================================================
  // Null property value: a matching record whose aggregate property is null does
  // not contribute. observe skips it; an UPDATE that nulls the value drops it.
  // Mirrors storage, which skips null values for SUM/AVG/MIN/MAX/DISTINCT.
  // ===========================================================================

  /**
   * SUM skips a null-valued matching record at observe: storage's SUM never folds a null, and the cache
   * mirrors that early-return in {@code observe}, so {10, null, 20} sums to 30 — the null contributes
   * nothing rather than NPEing the {@code (Number) v} cast in the re-fold.
   */
  @Test
  public void sumSkipsNullPropertyOnObserve() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD,
        List.of(newRec(10), newRec(null), newRec(20)));
    assertEquals("a null-valued matching record does not contribute to SUM", 30, scalarOf(s));
    db.rollback();
  }

  /**
   * AVG skips a null-valued matching record at observe: the null is not counted, so AVG over {10, null,
   * 20} divides 30 by 2 (the two non-null contributors), not by 3. This pins that the null-skip excludes
   * the record from both the sum and the divisor, matching {@code SQLFunctionAverage}.
   */
  @Test
  public void avgSkipsNullPropertyOnObserve() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_AVG, FIELD,
        List.of(newRec(10), newRec(null), newRec(20)));
    assertEquals("a null-valued matching record is excluded from AVG sum and count", 15,
        scalarOf(s));
    db.rollback();
  }

  /**
   * SUM drops a contributor when a T&rarr;T UPDATE nulls its property: a record that still matches WHERE
   * but whose new aggregate value is null flips to non-contributing in {@code applyMutation} (the
   * null-new-value branch), so {10, 20} with 20 nulled re-folds to 10. A regression here would leave a
   * stale contributor and a silently wrong scalar.
   */
  @Test
  public void sumDropsContributorWhenUpdateNullsTheProperty() {
    var a = committedRec(10);
    var b = committedRec(20);
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(a, b));
    assertEquals(30, scalarOf(s));
    b.setProperty(FIELD, null);
    // Matches WHERE (matchAfter=true) but its new value is null: the null-new-value branch drops it.
    s.applyMutation((RecordAbstract) b, RecordOperation.UPDATED, true);
    assertEquals("a matching record whose value becomes null drops out of SUM", 10, scalarOf(s));
    db.rollback();
  }

  // ===========================================================================
  // MIN/MAX comparison across Number subtypes and over non-Number Comparable
  // values: beatsExtremum runs castComparableNumber only when both operands are
  // Number, else a raw Comparable.compareTo. Homogeneous Integer input exercises
  // neither branch, so the cast and the non-Number fallback are unguarded without
  // the cases below.
  // ===========================================================================

  /**
   * MAX compares Integer/Long/Double numerically, not by boxed type: with {5, 10L, 7.5}, the numeric
   * max is 10L, which {@code beatsExtremum} reaches through {@code castComparableNumber} (the both-
   * operands-Number branch). A comparison by {@code Number.equals}/hashCode or boxed-reference order
   * would pick the wrong extremum. Pins the cross-subtype comparison the increment-promotion world
   * these aggregates live in produces (a MAX over a column mixing Integer and Long).
   */
  @Test
  public void maxComparesAcrossNumberSubtypesNumerically() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_MAX, FIELD,
        List.of(newRec(5), newRec(10L), newRec(7.5d)));
    assertEquals("MAX compares Integer/Long/Double numerically, not by boxed type", 10L,
        scalarOf(s));
    db.rollback();
  }

  /**
   * MIN compares Integer/Long/Double numerically: with {5, 10L, 7.5}, the numeric min is 5 (Integer),
   * the cross-subtype mirror of the MAX case, exercising the {@code castComparableNumber} branch in the
   * MIN direction.
   */
  @Test
  public void minComparesAcrossNumberSubtypesNumerically() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_MIN, FIELD,
        List.of(newRec(5), newRec(10L), newRec(7.5d)));
    assertEquals("MIN compares Integer/Long/Double numerically, not by boxed type", 5, scalarOf(s));
    db.rollback();
  }

  /**
   * MIN over non-Number Comparable values (Strings) takes the raw {@code Comparable.compareTo}
   * fallback, never {@code castComparableNumber}: lexicographic min of {banana, apple, cherry} is
   * "apple". A {@code MIN(name)} over String ids is a routine shape, and this pins that the non-Number
   * fallback compares by natural order.
   */
  @Test
  public void minOverStringComparableValues() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_MIN, FIELD,
        List.of(newRec("banana"), newRec("apple"), newRec("cherry")));
    assertEquals("apple", scalarOf(s)); // non-Number Comparable path
    db.rollback();
  }

  /**
   * MAX over non-Number Comparable values (Strings) takes the raw {@code Comparable.compareTo}
   * fallback in the MAX direction: lexicographic max of {banana, apple, cherry} is "cherry".
   */
  @Test
  public void maxOverStringComparableValues() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_MAX, FIELD,
        List.of(newRec("banana"), newRec("apple"), newRec("cherry")));
    assertEquals("cherry", scalarOf(s)); // non-Number Comparable path
    db.rollback();
  }

  /**
   * AVG over negative integers truncates toward zero, not floor: Java integer division gives
   * {@code -35 / 3 == -11}, not -12. The existing positive cases pass under both floor and truncate-
   * toward-zero (35/3 == 11 either way); a negative input is what distinguishes the implemented rule.
   */
  @Test
  public void avgNegativeIntegerTruncatesTowardZero() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_AVG, FIELD,
        List.of(newRec(-10), newRec(-20), newRec(-5))); // -35 / 3
    assertEquals("integer AVG truncates toward zero, not floor", -11, scalarOf(s));
    db.rollback();
  }

  /**
   * AVG over BigDecimal sitting exactly on the HALF_UP round-half boundary rounds up: 5 over 2 is 2.5,
   * which HALF_UP at scale 0 rounds to 3 (HALF_EVEN would round to 2). The existing 35/3 case rounds to
   * 12 under both HALF_UP and HALF_EVEN, so only an exact .5 input distinguishes the implemented rule.
   */
  @Test
  public void avgBigDecimalOnExactHalfBoundaryRoundsHalfUp() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_AVG, FIELD,
        List.of(newRec(new BigDecimal("5")), newRec(new BigDecimal("0"))));
    // (5 + 0) / 2 = 2.5; HALF_UP at scale 0 is 3, HALF_EVEN would be 2.
    assertEquals(new BigDecimal("3"), scalarOf(s));
    db.rollback();
  }

  /** F&rarr;F: a non-contributor that still does not match is a no-op for every kind (SUM here). */
  @Test
  public void applyMutationFtoFIsNoOp() {
    db.begin();
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(newRec(10)));
    var nonMatching = newRec(99);
    s.applyMutation((RecordAbstract) nonMatching, RecordOperation.CREATED, false);
    assertEquals("a never-matching create changes nothing", 10, scalarOf(s));
    db.rollback();
  }

  // ===========================================================================
  // Collapse safety: membership, not op type
  // ===========================================================================

  /**
   * The collapse case: a pre-populate CREATE of a contributor whose post-populate UPDATE drives it
   * out of WHERE collapses to a {@code CREATED}-typed op. Because the record was already observed at
   * populate (it is in {@code contributingValues}), {@code was_contributing=true}; with {@code
   * matchAfter=false} it is a T&rarr;F drop. Keying on op type would misread {@code CREATED} as a new
   * record and no-op, leaving a stale contributor and a wrong MAX. This pins that membership, not op
   * type, drives the transition.
   */
  @Test
  public void collapseCreatedOpAlreadyContributingDropsOnWhereFail() {
    db.begin();
    var holder = newRec(50); // observed at populate, becomes the MAX holder
    var other = newRec(20);
    var s = populate(CacheableShape.AGGREGATE_MAX, FIELD, List.of(holder, other));
    assertEquals(50, scalarOf(s));

    // The collapsed op is typed CREATED but the record is already a contributor; matchAfter=false
    // (the post-populate update drove it out of WHERE). Membership dispatch must treat this as T->F.
    holder.setProperty(FIELD, -1);
    s.applyMutation((RecordAbstract) holder, RecordOperation.CREATED, false);
    assertEquals("a CREATED op on an existing contributor that fails WHERE drops it", 20,
        scalarOf(s));
    db.rollback();
  }

  /**
   * The collapse case for SUM: a {@code CREATED}-typed op on an already-contributing record whose
   * value changed must re-fold (T&rarr;T), not double-add. Updating the 10-contributor to 100 against
   * {10, 5} must yield a fold of {100, 5}, not {10, 5, 100}.
   */
  @Test
  public void collapseCreatedOpAlreadyContributingRefoldsOnValueChange() {
    db.begin();
    var a = newRec(10);
    var b = newRec(5);
    var s = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(a, b));

    a.setProperty(FIELD, 100);
    s.applyMutation((RecordAbstract) a, RecordOperation.CREATED, true);
    var expected = PropertyTypeInternal.increment(100, 5);
    assertEquals("a CREATED op on an existing contributor re-folds, never double-adds", expected,
        scalarOf(s));
    db.rollback();
  }

  // ===========================================================================
  // copy isolation
  // ===========================================================================

  /**
   * {@code copy} must produce an independent state: a mutation replayed on the copy must not change
   * the original's scalar, so the entry's seeded state survives per-view delta builds intact.
   */
  @Test
  public void copyIsolatesMutationsFromOriginal() {
    db.begin();
    var original = populate(CacheableShape.AGGREGATE_COUNT, null, List.of(newRec(1), newRec(2)));
    var copy = original.copy();

    var added = newRec(3);
    copy.applyMutation((RecordAbstract) added, RecordOperation.CREATED, true);

    assertEquals("the copy reflects the replayed mutation", 3L, scalarOf(copy));
    assertEquals("the original is untouched by the copy's mutation", 2L, scalarOf(original));
    db.rollback();
  }

  /**
   * COUNT_DISTINCT {@code copy} must deep-copy the per-bucket sets: removing a RID from a bucket on the
   * copy must not empty the original's bucket, since the buckets are independent mutable containers.
   */
  @Test
  public void copyDeepCopiesDistinctBuckets() {
    var shared = committedRec(5);
    var other = committedRec(7);
    var original =
        populate(CacheableShape.AGGREGATE_COUNT_DISTINCT, FIELD, List.of(shared, other));
    var copy = original.copy();

    copy.applyMutation((RecordAbstract) shared, RecordOperation.DELETED, true);

    assertEquals("the copy drops the 5 bucket", 1L, scalarOf(copy));
    assertEquals("the original keeps both buckets", 2L, scalarOf(original));
    db.rollback();
  }

  // ===========================================================================
  // memory cap overflow
  // ===========================================================================

  /**
   * A high-cardinality COUNT(DISTINCT) that crosses the contributor cap must fire the overflow
   * callback exactly once (the latch), so the cache can route the key non-cacheable. The cap is on the
   * contributor collection, not the single scalar row.
   */
  @Test
  public void contributorCapFiresOverflowCallbackOnce() {
    db.begin();
    var s = state(CacheableShape.AGGREGATE_COUNT_DISTINCT, FIELD);
    var fired = new AtomicInteger();
    s.setOverflowGuard(2, fired::incrementAndGet);

    s.observe(resultOf(newRec(1)));
    s.observe(resultOf(newRec(2)));
    assertEquals("at the cap, no overflow yet", 0, fired.get());
    assertFalse(s.isOverflowed());

    s.observe(resultOf(newRec(3))); // crosses the cap of 2
    assertEquals("crossing the cap fires the callback once", 1, fired.get());
    assertTrue(s.isOverflowed());

    s.observe(resultOf(newRec(4))); // already overflowed; must not re-fire
    assertEquals("the overflow callback is one-shot", 1, fired.get());
    db.rollback();
  }

  // ===========================================================================
  // buildForAggregate + view end-to-end
  // ===========================================================================

  /**
   * {@link DeltaBuilder#buildForAggregate} copies the entry's seeded state and replays a post-populate
   * mutation onto the copy, leaving the entry's state untouched. A SUM entry seeded with {10, 20} plus
   * a post-populate create of 5 (matching WHERE) must replay to 35 while the entry stays at 30.
   */
  @Test
  public void buildForAggregateReplaysOntoCopyLeavingEntryIntact() {
    db.begin();
    var a = newRec(10);
    var b = newRec(20);
    var seeded = populate(CacheableShape.AGGREGATE_SUM, FIELD, List.of(a, b));
    var where = parseWhere("SELECT FROM " + CLASS_NAME + " WHERE " + FIELD + " > 0");
    var entry =
        new CachedEntry(
            CacheableShape.AGGREGATE_SUM,
            java.util.Set.of(CLASS_NAME),
            where,
            null,
            null,
            null,
            null,
            tx().getMutationVersion());
    entry.setAggregateState(seeded);

    newRec(5); // post-populate CREATE, matches WHERE

    var delta = DeltaBuilder.buildForAggregate(entry, tx(), ctx());

    assertEquals("the replayed copy reflects the post-populate create", 35,
        delta.toResult(db).<Object>getProperty(seeded.getAlias()));
    assertEquals("the entry's seeded state is untouched by the build", 30,
        seeded.toResult(db).<Object>getProperty(seeded.getAlias()));
    db.rollback();
  }

  /**
   * The aggregate {@link CachedResultSetView} emits the replayed scalar exactly once: {@code hasNext}
   * is true before the single {@code next}, false after. This is the aggregate-shape contract (one row
   * per aggregate query) distinct from the record-shape sorted merge.
   */
  @Test
  public void aggregateViewEmitsSingleRowThenDrains() {
    db.begin();
    var seeded =
        populate(CacheableShape.AGGREGATE_COUNT, null, List.of(newRec(1), newRec(2), newRec(3)));
    var entry =
        new CachedEntry(
            CacheableShape.AGGREGATE_COUNT,
            java.util.Set.of(CLASS_NAME),
            null,
            null,
            null,
            null,
            null,
            tx().getMutationVersion());
    entry.setAggregateState(seeded);

    var delta = DeltaBuilder.buildForAggregate(entry, tx(), ctx());
    try (var view = CachedResultSetView.forAggregateState(entry, delta, db, tx(), null, ctx())) {
      assertTrue("the aggregate view has its single row", view.hasNext());
      var row = view.next();
      assertEquals(3L, row.<Object>getProperty(seeded.getAlias()));
      assertFalse("the aggregate view drains after one row", view.hasNext());
    }
    db.rollback();
  }

  /**
   * The aggregate view pins its entry while iterating and releases the pin on close, exactly like the
   * record/K0 view, so LRU eviction never truncates an in-flight aggregate consumer.
   */
  @Test
  public void aggregateViewPinsEntryAndReleasesOnClose() {
    db.begin();
    var seeded = populate(CacheableShape.AGGREGATE_COUNT, null, List.of(newRec(1)));
    var entry =
        new CachedEntry(
            CacheableShape.AGGREGATE_COUNT,
            java.util.Set.of(CLASS_NAME),
            null,
            null,
            null,
            null,
            null,
            tx().getMutationVersion());
    entry.setAggregateState(seeded);
    var delta = DeltaBuilder.buildForAggregate(entry, tx(), ctx());

    var view = CachedResultSetView.forAggregateState(entry, delta, db, tx(), null, ctx());
    assertEquals("the view pins the entry", 1, entry.getLiveViewCount());
    view.close();
    assertEquals("close releases the pin", 0, entry.getLiveViewCount());
    db.rollback();
  }
}
