package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDelta;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Mockito-driven pin tests for the single-value {@code clear()} mixed-mode
 * encoding contract. SV mirror of
 * {@link BTreeMultiValueIndexEngineClearMixedModeTest}, with one tree
 * ({@code sbTree}) carrying both null and non-null entries. The four
 * mechanical assertions covered here:
 *
 * <ul>
 *   <li>The single-tree persisted-side write fires exactly once with
 *       {@code (op, 0L)}.
 *   <li>The holder records
 *       {@code (inMemAdjustTotal = -currentTotal, inMemAdjustNull =
 *       -currentNull, totalDelta = 0, nullDelta = 0)} post-{@code clear()}
 *       but pre-commit.
 *   <li>In-memory {@code AtomicLong} accessors return pre-{@code clear()}
 *       values during the atomic op. Hook B advances them post-commit, not
 *       inline.
 *   <li>{@code verifyNoMoreInteractions} on the {@code sbTree} Mockito stub
 *       locks the production interaction set to the writes and reads named
 *       above.
 * </ul>
 *
 * <p>SV-specific subtlety. {@code persistCountDelta} on the single-value
 * engine ignored {@code nullDelta} under the prior pure-delta encoding
 * because the single tree stores nulls and non-nulls together; the
 * persisted side moved by {@code totalDelta} alone. Mixed-mode replaces
 * that path entirely on the persisted side with an absolute zero write, and
 * the in-mem null-counter delta now rides the {@code accumulateInMemRecalibration}
 * accumulator. Hook A's {@code persistCountDelta} is a no-op for this seam
 * because {@code clear()} writes nothing to {@code getTotalDelta() /
 * getNullDelta()}.
 *
 * <p>Concurrency-contract caveat. Like the sibling tests in
 * {@link BTreeEngineHistogramBuildTest}, the Mockito fixture short-circuits
 * {@code doClearTree}: the mock tree returns {@code 0L} from {@code size()}
 * by default, so the while-loop body never runs and the per-tree exclusive
 * lock cited in the production comment of
 * {@code BTreeSingleValueIndexEngine.clear()} is not acquired here. These
 * tests pin the mechanical contract only. The lock-window race is exercised
 * by {@code BTreeSingleValueIndexEngineClearRollbackTest}.
 *
 * <p>Where to add new pin tests. This class is the canonical home for
 * mixed-mode {@code clear()} pin tests on the SV engine: mechanical holder
 * shape, persisted-write {@code times(1)} precision, write/in-mem ordering,
 * axis isolation, and the boundary configurations (empty engine, null-only
 * engine, clear-then-put composition). {@link BTreeEngineHistogramBuildTest}
 * retains the historical {@code singleValue_clear_*} suite migrated from
 * the pure-delta era for legacy continuity; new pin tests for the
 * mixed-mode {@code clear()} contract belong here rather than there.
 */
public class BTreeSingleValueIndexEngineClearMixedModeTest {

  /**
   * Pre-{@code clear()} state has {@code currentTotal = 10} and
   * {@code currentNull = 3}. The four-field holder shape pins the mixed-mode
   * contract on the single-tree engine. The persisted-side
   * {@code setApproximateEntriesCount} write carries the collapse to the EP
   * page. The in-mem-side delta advances {@code inMemAdjustTotal} /
   * {@code inMemAdjustNull}. Hook A's {@code persistCountDelta} sees nothing
   * on this seam ({@code totalDelta / nullDelta} stay zero). Hook B sums
   * {@code getTotalDelta() + getInMemAdjustTotal()} when calling the engine
   * mutators post-commit.
   */
  @Test
  public void singleValueClear_recordsHolderShape_andPersistedWrite()
      throws IOException {
    var f = new BTreeEngineTestFixtures.SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    // Holder-row pin. The persist-axis pair (totalDelta, nullDelta) stays
    // at zero because mixed-mode does NOT feed Hook A on the clear() seam;
    // the in-mem-axis pair carries the collapse for Hook B's post-commit
    // sum.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(
        "clear() must allocate an IndexCountDelta row on the holder for this engine",
        delta);
    assertEquals(
        "totalDelta must stay zero on the clear() seam under mixed-mode"
            + " (Hook A's persistCountDelta is a no-op here)",
        0L,
        delta.getTotalDelta());
    assertEquals(
        "nullDelta must stay zero on the clear() seam under mixed-mode."
            + " The in-mem null-counter delta rides accumulateInMemRecalibration"
            + " on the inMemAdjustNull axis instead.",
        0L,
        delta.getNullDelta());
    assertEquals(
        "inMemAdjustTotal must carry -currentTotal so Hook B's sum collapses"
            + " the in-memory total counter to zero post-commit",
        -10L,
        delta.getInMemAdjustTotal());
    assertEquals(
        "inMemAdjustNull must carry -currentNull so Hook B's sum collapses"
            + " the in-memory null counter to zero post-commit",
        -3L,
        delta.getInMemAdjustNull());

    // Negative pin. The engine-level AtomicLong accessors must report
    // pre-clear() values during the atomic op. Hook B is the sole writer
    // to these counters under the consolidated lifecycle; clear() must NOT
    // mutate them inline. A regression that brought back the legacy inline
    // writes (or routed the in-mem-side delta through
    // addToApproximate{Entries,Null}Count directly) would trip the two
    // assertions below.
    assertEquals(
        "In-mem total count must read pre-clear() value (10) during the"
            + " atomic op. Hook B advances it post-commit, not inline.",
        10L,
        f.engine.getTotalCount(f.op));
    assertEquals(
        "In-mem null count must read pre-clear() value (3) during the"
            + " atomic op. Hook B advances it post-commit, not inline.",
        3L,
        f.engine.getNullCount(f.op));

    // Single-tree persisted-side verify with times(1) precision. The
    // sbTree receives exactly one setApproximateEntriesCount(op, 0L) write.
    // A regression that issued a second matching call (for example, by
    // adding a per-axis write) would slip past plain verify (at-least-once);
    // times(1) catches it.
    verify(f.sbTree, times(1)).setApproximateEntriesCount(f.op, 0L);
    // Negative pin on the sibling delta-path mutator. The SV engine has no
    // sibling tree, so this assertion is the only catch-mechanism for a
    // regression that replaced the absolute write with
    // addToApproximateEntriesCount(op, -currentTotal) or added it alongside
    // the absolute write.
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    // Acknowledge the read-side interactions clear() performs on the tree
    // before the inline absolute write: doClearTree's iterate-and-remove
    // loop guard (size() returns 0 on Mockito's fixture, so the loop body
    // is skipped) and the post-doClearTree firstKey() read inside the
    // postcondition assert. verifyNoMoreInteractions below locks the full
    // interaction set on the sbTree mock.
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Pins the SV {@code clear()} seam's in-stack statement ordering: the
   * persisted-side absolute zero write fires BEFORE the in-mem accumulator
   * advances. The pin is documentary: it locks the source-line order for
   * symmetric audit across both engines and across the {@code clear()} and
   * {@code buildInitialHistogram} seams. Hook A's {@code persistCountDelta}
   * is a no-op on the {@code clear()} seam, and the persisted-side write
   * {@code setApproximateEntriesCount(op, 0L)} is absolute (it does not
   * read prior state), so a swap would not break crash-safety or any
   * drift-healing contract. The order-agnostic plain verify calls in
   * {@link #singleValueClear_recordsHolderShape_andPersistedWrite} would
   * not catch the swap, hence this pin.
   *
   * <p>Cross-thread visibility ordering is enforced separately by the
   * atomic-op lifecycle and the per-engine commit-path lock that
   * {@code lockIndexes} acquires, not by this test.
   *
   * <p>This is the SV-shaped mirror of the equivalent MV pin in
   * {@link BTreeMultiValueIndexEngineClearMixedModeTest}. On the single
   * tree the swap detection collapses to one persisted-side write, so the
   * test captures the holder state on that one call.
   */
  @Test
  public void singleValueClear_persistedWriteFiresBeforeInMemAccumulator()
      throws IOException {
    var f = new BTreeEngineTestFixtures.SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    // Capture the holder's inMemAdjust state at the moment
    // setApproximateEntriesCount(op, 0L) fires. If the production advanced
    // the accumulator first, this capture would observe -10 / -3 instead
    // of 0 / 0 and the assertions below would trip.
    AtomicReference<Long> inMemAdjustTotalAtPersistTime = new AtomicReference<>();
    AtomicReference<Long> inMemAdjustNullAtPersistTime = new AtomicReference<>();
    doAnswer(
        inv -> {
          var d =
              f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
          inMemAdjustTotalAtPersistTime.set(
              d == null ? 0L : d.getInMemAdjustTotal());
          inMemAdjustNullAtPersistTime.set(
              d == null ? 0L : d.getInMemAdjustNull());
          return null;
        })
        .when(f.sbTree)
        .setApproximateEntriesCount(f.op, 0L);

    f.engine.clear(f.storage, f.op);

    // At the moment of the persisted-side write, the in-mem accumulator
    // must NOT have advanced on either axis.
    assertNotNull(
        "sbTree.setApproximateEntriesCount(op, 0L) must have fired during clear()",
        inMemAdjustTotalAtPersistTime.get());
    assertEquals(
        "Persisted sbTree write must fire BEFORE accumulateInMemRecalibration"
            + " advances the holder. A regression that swapped the two statements"
            + " would observe -10 here instead of 0.",
        0L,
        inMemAdjustTotalAtPersistTime.get().longValue());
    assertEquals(
        "Same ordering rule on the null axis at the sbTree write boundary.",
        0L,
        inMemAdjustNullAtPersistTime.get().longValue());

    // After clear() returns, the in-mem accumulator IS advanced with both
    // axes carrying the collapse delta.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());

    // Interaction lockdown symmetric with the holder-shape test. Plain
    // verify is at-least-once and would let a stray duplicate write slip;
    // times(1) and verifyNoMoreInteractions catch that.
    verify(f.sbTree, times(1)).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Pins axis isolation across the two accumulator surfaces on the
   * single-value engine. Prior null-key put accumulations
   * ({@code IndexCountDelta.accumulate} with {@code sign=+1, isNullKey=true})
   * advance only the {@code totalDelta} / {@code nullDelta} axes; a
   * subsequent {@code clear()} of a (10, 3)-seeded engine advances only the
   * {@code inMemAdjust} axes. The two axis pairs are independent on the
   * holder, and Hook B sums them at apply time. A regression that routed
   * {@code clear()}'s in-mem-side delta through {@code accumulate} or
   * {@code accumulateClearOrRecalibrate} (which advance the persist-axis
   * fields) instead of {@code accumulateInMemRecalibration} would trip the
   * holder-shape assertions below.
   */
  @Test
  public void singleValueClear_keepsPriorPutDeltasOnTheirOwnAxis()
      throws IOException {
    var f = new BTreeEngineTestFixtures.SingleValueFixture();
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, true);
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, true);
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    // Prior put deltas stay on the persist-axis pair.
    assertEquals(+2L, delta.getTotalDelta());
    assertEquals(+2L, delta.getNullDelta());
    // The clear() collapse lands on the in-mem-axis pair only.
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
    // In-mem AtomicLongs must NOT move inside clear(). The
    // IndexCountDelta.accumulate calls above touch the holder only, so the
    // engine-level counters see the seeded values (10, 3); Hook B's apply
    // is the sole writer on these.
    assertEquals(10L, f.engine.getTotalCount(f.op));
    assertEquals(3L, f.engine.getNullCount(f.op));
    // The persisted-side absolute zero write still fires unconditionally;
    // the prior put deltas neither suppress it nor add a delta-path write.
    // times(1) plus verifyNoMoreInteractions locks the sbTree interaction
    // set symmetric with the holder-shape test.
    verify(f.sbTree, times(1)).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Empty-engine boundary. clear() on an engine whose in-mem counters were
   * never seeded records (0, 0) on both inMemAdjust axes and still fires
   * the single-tree absolute zero write unconditionally. Pins the
   * holder-shape degenerate case and the unconditional persisted-side
   * write contract.
   */
  @Test
  public void singleValueClear_onEmptyEngine_recordsZeroInMemAdjustAndAbsoluteWrite()
      throws IOException {
    var f = new BTreeEngineTestFixtures.SingleValueFixture();

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(0L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    assertEquals(0L, f.engine.getTotalCount(f.op));
    assertEquals(0L, f.engine.getNullCount(f.op));
    verify(f.sbTree, times(1)).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Null-only-engine upper-edge boundary. When every entry is a null key,
   * the in-mem-side delta has equal magnitudes on both axes. The (10, 3)
   * mix in the other tests cannot detect an accidental axis swap because
   * the magnitudes differ; this boundary is the only one that catches it.
   * Hook A's persistCountDelta is a no-op on the clear() seam regardless,
   * so the single-tree absolute zero write carries the persisted-side
   * collapse for both axes.
   */
  @Test
  public void singleValueClear_nullOnlyEngine_recordsEqualInMemAdjustOnBothAxes()
      throws IOException {
    var f = new BTreeEngineTestFixtures.SingleValueFixture();
    f.engine.addToApproximateEntriesCount(7);
    f.engine.addToApproximateNullCount(7);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(-7L, delta.getInMemAdjustTotal());
    assertEquals(-7L, delta.getInMemAdjustNull());
    assertEquals(7L, f.engine.getTotalCount(f.op));
    assertEquals(7L, f.engine.getNullCount(f.op));
    verify(f.sbTree, times(1)).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Clear-then-put composition pin. Reproduces the production order from
   * commitIndexes: a doClearIndex dispatch followed by put accumulations on
   * the same atomic op. The holder ends with the clear() collapse on the
   * inMemAdjust axes and the put deltas on the totalDelta / nullDelta axes;
   * Hook B's per-axis sum (AbstractStorage.java:2538-2541) computes
   * total = +2 + (-10) = -8 and null = +1 + (-3) = -2, which the engine
   * mutators clamp at zero downstream. Counterpart to
   * singleValueClear_keepsPriorPutDeltasOnTheirOwnAxis, which covers the
   * reverse (put-then-clear) order.
   */
  @Test
  public void singleValueClear_followedByPutAccumulation_composesAdditively()
      throws IOException {
    var f = new BTreeEngineTestFixtures.SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, false);
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, true);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(
        "Post-clear put on a non-null key advances totalDelta only.",
        +2L, delta.getTotalDelta());
    assertEquals(
        "Post-clear put on a null key advances nullDelta by one.",
        +1L, delta.getNullDelta());
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
    // Hook B's per-axis sum at AbstractStorage.java:2538-2541 computes
    // total = +2 + (-10) = -8 and null = +1 + (-3) = -2; the engine
    // mutators clamp at zero downstream.
  }
}
