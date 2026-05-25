package com.jetbrains.youtrackdb.internal.core.index.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import org.junit.Test;

/**
 * Tests for {@link IndexCountDeltaHolder} — per-transaction container for
 * index entry count deltas, stored directly on the AtomicOperation.
 *
 * <p>Verifies: lazy creation of per-engine deltas, identity stability on
 * repeated access, engine isolation, unmodifiable view semantics, and
 * rollback-safety (discarding the holder discards all accumulated deltas).
 */
public class IndexCountDeltaHolderTest {

  /**
   * getOrCreate for a new engine ID returns a fresh delta with zero fields.
   */
  @Test
  public void getOrCreateReturnsNewDeltaForNewEngine() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(42);

    assertNotNull(delta);
    assertEquals(0, delta.totalDelta);
    assertEquals(0, delta.nullDelta);
  }

  /**
   * Repeated getOrCreate with the same engine ID returns the same instance
   * (identity, not just equality), so mutations accumulate correctly.
   */
  @Test
  public void getOrCreateReturnsSameDeltaForSameEngine() {
    var holder = new IndexCountDeltaHolder();
    var delta1 = holder.getOrCreate(42);
    var delta2 = holder.getOrCreate(42);

    assertSame(delta1, delta2);
  }

  /**
   * Deltas for different engine IDs are independent — mutating one does not
   * affect the other.
   */
  @Test
  public void getOrCreateSeparatesDeltasByEngineId() {
    var holder = new IndexCountDeltaHolder();
    var delta1 = holder.getOrCreate(1);
    var delta2 = holder.getOrCreate(2);

    delta1.totalDelta = 10;
    delta1.nullDelta = 3;
    delta2.totalDelta = 20;
    delta2.nullDelta = 7;

    assertEquals(10, holder.getOrCreate(1).totalDelta);
    assertEquals(3, holder.getOrCreate(1).nullDelta);
    assertEquals(20, holder.getOrCreate(2).totalDelta);
    assertEquals(7, holder.getOrCreate(2).nullDelta);
  }

  /**
   * getDeltas returns a map containing the same delta instances that were
   * returned by getOrCreate — mutations made through getOrCreate are visible
   * during commit-time iteration over the map.
   */
  @Test
  public void getDeltasContainsSameInstancesAsGetOrCreate() {
    var holder = new IndexCountDeltaHolder();
    var delta1 = holder.getOrCreate(1);
    var delta2 = holder.getOrCreate(2);
    delta1.totalDelta = 11;
    delta2.nullDelta = 7;

    var map = holder.getDeltas();
    assertEquals(2, map.size());
    assertSame(delta1, map.get(1));
    assertSame(delta2, map.get(2));
    assertEquals(11, map.get(1).totalDelta);
    assertEquals(7, map.get(2).nullDelta);
    assertNull(map.get(3));
  }

  /**
   * getDeltas on a holder that was never accessed returns an empty map.
   */
  @Test
  public void getDeltasOnEmptyHolderReturnsEmptyMap() {
    var holder = new IndexCountDeltaHolder();
    var map = holder.getDeltas();
    assertNotNull(map);
    assertEquals(0, map.size());
  }

  /**
   * The holder is fully self-contained: accumulated deltas are exposed only
   * via getDeltas() and never pushed anywhere automatically. On rollback
   * the AtomicOperation (and its holder) is discarded — no external counter
   * is ever mutated. This test verifies the containment property.
   */
  @Test
  public void holderIsSelfContainedDeltasOnlyExposedViaGetDeltas() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(7);
    delta.totalDelta = 5;
    delta.nullDelta = 2;

    // The holder retains the values in isolation, accessible only via
    // the map view — the design contract is that nothing is applied
    // until AbstractStorage.applyIndexCountDeltas() on commit.
    var map = holder.getDeltas();
    assertEquals(1, map.size());
    assertSame(delta, map.get(7));
    assertEquals(5, map.get(7).totalDelta);
    assertEquals(2, map.get(7).nullDelta);
  }

  /**
   * Engine ID 0 is a plausible default/uninitialized value; it must still
   * produce a stable, independent bucket.
   */
  @Test
  public void getOrCreateWithEngineIdZero() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(0);

    assertNotNull(delta);
    assertEquals(0, delta.totalDelta);
    assertEquals(0, delta.nullDelta);
    assertSame(delta, holder.getOrCreate(0));
  }

  /**
   * Negative engine IDs are not assigned by DiskStorage but the API accepts
   * any int — verify the boundary works (mirrors HistogramDeltaHolderTest).
   */
  @Test
  public void negativeEngineIdWorksCorrectly() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(-1);

    assertNotNull(delta);
    delta.totalDelta = 42;
    assertEquals(42, holder.getOrCreate(-1).totalDelta);
  }

  /**
   * Accumulating both positive and negative deltas for the same engine
   * produces the correct net value.
   */
  @Test
  public void netDeltaAccumulatesCorrectly() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(1);

    // Simulate: 3 inserts, 1 remove, 1 null insert, 1 null remove
    delta.totalDelta += 3;
    delta.totalDelta -= 1;
    delta.nullDelta += 1;
    delta.nullDelta -= 1;

    assertEquals(2, delta.totalDelta);
    assertEquals(0, delta.nullDelta);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // IndexCountDelta.accumulate() static method tests
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * accumulate with isNullKey=false must increment totalDelta only.
   */
  @Test
  public void accumulate_nonNullKey_incrementsTotalOnly() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulate(atomicOp, 7, +1, false);
    IndexCountDelta.accumulate(atomicOp, 7, +1, false);

    var delta = holder.getOrCreate(7);
    assertEquals(2, delta.getTotalDelta());
    assertEquals(0, delta.getNullDelta());
  }

  /**
   * accumulate with isNullKey=true must increment both totalDelta and nullDelta.
   */
  @Test
  public void accumulate_nullKey_incrementsBothDeltas() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulate(atomicOp, 7, +1, true);

    var delta = holder.getOrCreate(7);
    assertEquals(1, delta.getTotalDelta());
    assertEquals(1, delta.getNullDelta());
  }

  /**
   * Mixed null/non-null inserts and removes produce correct net deltas.
   */
  @Test
  public void accumulate_mixedKeys_correctDeltas() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulate(atomicOp, 7, +1, false); // non-null insert
    IndexCountDelta.accumulate(atomicOp, 7, +1, true); // null insert
    IndexCountDelta.accumulate(atomicOp, 7, -1, false); // non-null remove

    var delta = holder.getOrCreate(7);
    assertEquals(1, delta.getTotalDelta()); // +1+1-1 = 1
    assertEquals(1, delta.getNullDelta()); // +1 = 1
  }

  /**
   * sign=0 is invalid — must trigger the assertion guard. Without assertions,
   * sign=0 silently adds nothing, which masks count drift in new code paths.
   */
  @Test
  public void accumulate_signZero_throwsAssertionError() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    var error = assertThrows(AssertionError.class,
        () -> IndexCountDelta.accumulate(atomicOp, 7, 0, false));
    assertTrue("Must mention sign",
        error.getMessage().contains("sign"));
  }

  /**
   * sign=2 is invalid — only +1 (insert) and -1 (remove) are valid.
   */
  @Test
  public void accumulate_signTwo_throwsAssertionError() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    var error = assertThrows(AssertionError.class,
        () -> IndexCountDelta.accumulate(atomicOp, 7, 2, false));
    assertTrue("Must mention sign",
        error.getMessage().contains("sign"));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // applied latch tests
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Latch contract at the source: {@link IndexCountDeltaHolder} starts in
   * the non-applied state, and {@link IndexCountDeltaHolder#setApplied()}
   * is idempotent. The lifecycle apply hook inside
   * {@code AtomicOperationsManager.endAtomicOperation} reads the latch in
   * the gate, and {@code AbstractStorage.applyIndexCountDeltas} sets it at
   * the top of the method (before the per-engine loop) so a partial-loop
   * throw still latches the holder. Mirrors the {@code persistedLatchIsIdempotent}
   * pattern on the persisted latch.
   */
  @Test
  public void appliedLatchIsIdempotent() {
    var holder = new IndexCountDeltaHolder();
    assertFalse("Holder starts in the non-applied state", holder.isApplied());
    holder.setApplied();
    assertTrue("Latch flips after setApplied()", holder.isApplied());
    holder.setApplied();
    assertTrue("setApplied() must be idempotent", holder.isApplied());
  }

  /**
   * Latch independence: setting {@link IndexCountDeltaHolder#setPersisted()}
   * must not flip the {@code applied} latch and vice versa. The two latches
   * gate two independent hook calls (persist before commit, apply after
   * commit) and must stay independent so the gates short-circuit
   * independently.
   */
  @Test
  public void appliedAndPersistedLatchesAreIndependent() {
    var holder = new IndexCountDeltaHolder();
    holder.setPersisted();
    assertTrue("Persisted latch flipped", holder.isPersisted());
    assertFalse("Applied latch must stay false when only persisted is set",
        holder.isApplied());

    var holder2 = new IndexCountDeltaHolder();
    holder2.setApplied();
    assertTrue("Applied latch flipped", holder2.isApplied());
    assertFalse("Persisted latch must stay false when only applied is set",
        holder2.isPersisted());
  }

  // ════════════════════════════════════════════════════════════════════════
  // IndexCountDelta.accumulateClearOrRecalibrate() static method tests
  // ════════════════════════════════════════════════════════════════════════

  /**
   * Band 1 (zero/zero): a no-op clear of an empty engine accumulates
   * (0, 0) and leaves the holder's deltas at zero. The assert
   * precondition (|nullDelta| <= |totalDelta|, sign-aligned) holds
   * trivially.
   */
  @Test
  public void accumulateClearOrRecalibrate_zeroZero_isNoOp() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, 0L, 0L);

    var delta = holder.getOrCreate(7);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
  }

  /**
   * Band 2 (both negative, sign-aligned): clear() on an engine with 100
   * total entries and 25 null entries passes (-100, -25). |nullDelta|
   * = 25 <= 100 = |totalDelta|; both share the negative sign.
   */
  @Test
  public void accumulateClearOrRecalibrate_bothNegativeAligned_appliesDelta() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, -100L, -25L);

    var delta = holder.getOrCreate(7);
    assertEquals(-100L, delta.getTotalDelta());
    assertEquals(-25L, delta.getNullDelta());
  }

  /**
   * Band 3 (both positive, sign-aligned): buildInitialHistogram
   * recalibration where the observed target exceeds the current
   * in-memory counters passes (+50, +10).
   */
  @Test
  public void accumulateClearOrRecalibrate_bothPositiveAligned_appliesDelta() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, 50L, 10L);

    var delta = holder.getOrCreate(7);
    assertEquals(50L, delta.getTotalDelta());
    assertEquals(10L, delta.getNullDelta());
  }

  /**
   * Band 4 (one zero, one non-zero): SV engine clear() of a tree
   * holding only non-null entries passes (-50, 0). The sign-alignment
   * clause exempts zero values, so (any, 0) and (0, any) are both
   * legal and apply cleanly.
   */
  @Test
  public void accumulateClearOrRecalibrate_oneZeroOneNonZero_appliesDelta() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, -50L, 0L);

    var delta = holder.getOrCreate(7);
    assertEquals(-50L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
  }

  /**
   * Additive composition: two successive accumulateClearOrRecalibrate
   * calls on the same (atomicOp, engineId) sum the deltas. This is the
   * algebraic property the Javadoc promises so a clear and a
   * post-clear set of puts compose inside one transaction.
   */
  @Test
  public void accumulateClearOrRecalibrate_repeatedCalls_sumDeltas() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    // Pre-existing put activity recorded via the short-form accumulator.
    IndexCountDelta.accumulate(atomicOp, 7, +1, false);
    IndexCountDelta.accumulate(atomicOp, 7, +1, true);
    // Then the engine-scope clear collapses the tree.
    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, -2L, -1L);
    // And further puts after the clear continue accumulating.
    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, 10L, 3L);

    var delta = holder.getOrCreate(7);
    // (+1 +1) + (-2) + (+10) = 10 on total; (+1) + (-1) + (+3) = 3 on null.
    assertEquals(10L, delta.getTotalDelta());
    assertEquals(3L, delta.getNullDelta());
  }

  /**
   * Per-engine isolation: deltas accumulated through the long-form
   * overload stay bucketed by engine id and do not bleed across engines.
   */
  @Test
  public void accumulateClearOrRecalibrate_perEngineIsolation() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 1, -7L, -2L);
    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 2, 5L, 1L);

    var delta1 = holder.getOrCreate(1);
    var delta2 = holder.getOrCreate(2);
    assertEquals(-7L, delta1.getTotalDelta());
    assertEquals(-2L, delta1.getNullDelta());
    assertEquals(5L, delta2.getTotalDelta());
    assertEquals(1L, delta2.getNullDelta());
  }

  /**
   * Assert trigger: |nullDelta| > |totalDelta| violates the magnitude
   * precondition. Without the assert, a structurally impossible delta
   * (more nulls than total entries) would silently corrupt the
   * null-fraction arithmetic on apply.
   */
  @Test
  public void accumulateClearOrRecalibrate_nullMagnitudeExceedsTotal_throwsAssertionError() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    var error = assertThrows(AssertionError.class,
        () -> IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, -5L, -10L));
    assertTrue("Message must mention totalDelta and nullDelta",
        error.getMessage().contains("totalDelta=-5")
            && error.getMessage().contains("nullDelta=-10"));
  }

  /**
   * Assert trigger: opposed signs (positive total, negative null or
   * vice versa) violate the sign-alignment precondition. The four
   * production callers never produce opposed-sign deltas; an opposed
   * sign at runtime means the caller computed the delta incorrectly.
   */
  @Test
  public void accumulateClearOrRecalibrate_opposedSigns_throwsAssertionError() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    var error1 = assertThrows(AssertionError.class,
        () -> IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, 10L, -3L));
    assertTrue("Positive total + negative null must trip the assert",
        error1.getMessage().contains("totalDelta=10")
            && error1.getMessage().contains("nullDelta=-3"));

    var error2 = assertThrows(AssertionError.class,
        () -> IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, -10L, 3L));
    assertTrue("Negative total + positive null must trip the assert",
        error2.getMessage().contains("totalDelta=-10")
            && error2.getMessage().contains("nullDelta=3"));
  }

  // ════════════════════════════════════════════════════════════════════════
  // IndexCountDelta.accumulateInMemRecalibration() static method tests
  // ════════════════════════════════════════════════════════════════════════

  /**
   * No-precondition contract: the recalibration accumulator accepts
   * sign-opposed deltas where the null-magnitude exceeds the total-magnitude.
   * The combination (currentTotal=100, currentNull=10, scannedNonNull=80,
   * exactNullCount=15) produces totalDelta=-5 and nullDelta=+5 from a
   * buildInitialHistogram recalibration; the same shape would trip
   * accumulateClearOrRecalibrate's sign-alignment+magnitude precondition,
   * which is why recalibration callers route through this method instead.
   */
  @Test
  public void accumulateInMemRecalibration_signOpposedDeltas_acceptedNoAssert() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 7, -5L, 5L);

    var delta = holder.getOrCreate(7);
    assertEquals(-5L, delta.getInMemAdjustTotal());
    assertEquals(5L, delta.getInMemAdjustNull());
  }

  /**
   * No-precondition contract: null-magnitude can exceed total-magnitude
   * without tripping an assert. Worked case where currentTotal under-counts
   * but currentNull over-counts (an organic drift shape) produces
   * totalDelta=+1 and nullDelta=-3.
   */
  @Test
  public void accumulateInMemRecalibration_nullMagnitudeExceedsTotal_acceptedNoAssert() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 7, 1L, -3L);

    var delta = holder.getOrCreate(7);
    assertEquals(1L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
  }

  /**
   * Additive composition: per-put accumulation, a clear/recalibrate long-form
   * accumulation, and an in-mem-only recalibration in the same atomic
   * operation land on separate field pairs. The per-put and clear-or-recal
   * deltas advance totalDelta/nullDelta; the recalibration delta advances
   * inMemAdjustTotal/inMemAdjustNull. Hook B sums the two pairs at apply
   * time; this test pins the in-holder separation so a regression that
   * mis-routes the in-mem-only delta into the persisted fields surfaces
   * immediately.
   */
  @Test
  public void accumulateInMemRecalibration_inMemOnlyFieldsIndependentFromPersistedFields() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    // Per-put activity feeds totalDelta/nullDelta.
    IndexCountDelta.accumulate(atomicOp, 7, +1, false);
    IndexCountDelta.accumulate(atomicOp, 7, +1, true);
    // A clear/recalibrate long-form also feeds totalDelta/nullDelta.
    IndexCountDelta.accumulateClearOrRecalibrate(atomicOp, 7, 8L, 3L);
    // An in-mem-only recalibration feeds inMemAdjustTotal/inMemAdjustNull only.
    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 7, 100L, 25L);

    var delta = holder.getOrCreate(7);
    // Persisted fields: +1+1+8 = 10 on total; +1+3 = 4 on null.
    assertEquals(10L, delta.getTotalDelta());
    assertEquals(4L, delta.getNullDelta());
    // In-mem-only fields: untouched by the per-put and long-form callers.
    assertEquals(100L, delta.getInMemAdjustTotal());
    assertEquals(25L, delta.getInMemAdjustNull());
  }

  /**
   * Additive composition across repeated recalibrations: two successive
   * accumulateInMemRecalibration calls on the same (atomicOp, engineId)
   * sum the in-mem-only fields. Mirrors the algebraic property of the
   * other long-form overload.
   */
  @Test
  public void accumulateInMemRecalibration_repeatedCalls_sumInMemFields() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 7, 20L, 5L);
    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 7, -3L, -2L);

    var delta = holder.getOrCreate(7);
    assertEquals(17L, delta.getInMemAdjustTotal());
    assertEquals(3L, delta.getInMemAdjustNull());
  }

  /**
   * Per-engine isolation: in-mem-only recalibration deltas stay bucketed
   * by engine id and do not bleed across engines, matching the contract of
   * the other accumulator overloads.
   */
  @Test
  public void accumulateInMemRecalibration_perEngineIsolation() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 1, -7L, -2L);
    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 2, 5L, 1L);

    var delta1 = holder.getOrCreate(1);
    var delta2 = holder.getOrCreate(2);
    assertEquals(-7L, delta1.getInMemAdjustTotal());
    assertEquals(-2L, delta1.getInMemAdjustNull());
    assertEquals(5L, delta2.getInMemAdjustTotal());
    assertEquals(1L, delta2.getInMemAdjustNull());
  }

  /**
   * Rollback discard contract: the recalibration delta lives on the holder,
   * which lives on the AtomicOperation. On rollback the AtomicOperation is
   * dropped, which discards the holder and every accumulated delta with it.
   * This test pins the containment property at the holder boundary — the
   * holder exposes the in-mem-only fields via getDeltas() only; no external
   * counter is mutated by the accumulator itself, so dropping the holder is
   * sufficient to drop the recalibration delta.
   */
  @Test
  public void accumulateInMemRecalibration_rolledBackHolderDiscardsDelta() {
    var holder = new IndexCountDeltaHolder();
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.getOrCreateIndexCountDeltas()).thenReturn(holder);

    IndexCountDelta.accumulateInMemRecalibration(atomicOp, 7, 50L, 12L);
    // The values are recorded only on the holder, accessible via getDeltas().
    var map = holder.getDeltas();
    assertEquals(1, map.size());
    var delta = map.get(7);
    assertEquals(50L, delta.getInMemAdjustTotal());
    assertEquals(12L, delta.getInMemAdjustNull());

    // Simulating rollback: drop the holder. A fresh holder, as the next
    // AtomicOperation would carry, starts at all-zeros.
    var freshHolder = new IndexCountDeltaHolder();
    var freshMap = freshHolder.getDeltas();
    assertEquals(0, freshMap.size());
    var freshDelta = freshHolder.getOrCreate(7);
    assertEquals(0L, freshDelta.getInMemAdjustTotal());
    assertEquals(0L, freshDelta.getInMemAdjustNull());
  }

  /**
   * Default values: a freshly-created IndexCountDelta has zero in-mem-only
   * fields. Pins the invariant that getOrCreate followed by no accumulator
   * call leaves Hook B's sum (totalDelta + inMemAdjustTotal) unchanged from
   * its pre-Track-4 behavior of using totalDelta alone.
   */
  @Test
  public void inMemAdjustFields_defaultToZero() {
    var holder = new IndexCountDeltaHolder();
    var delta = holder.getOrCreate(7);
    assertEquals(0L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
  }
}
