package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.SnapshotMarkerRID;
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexCountDelta;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * Tests for initial histogram build and migration paths (Step 6).
 *
 * <p>Verifies that buildInitialHistogram() correctly obtains the sorted key
 * stream, total count, and null count from the B-tree internals and delegates
 * to the histogram manager. Also covers bulk-load suppression behavior.
 */
public class BTreeEngineHistogramBuildTest {

  /**
   * Concurrency-contract note shared by every clear() unit test below for
   * both the single-value and multi-value engines.
   *
   * <p>Both Mockito-based fixtures (SingleValueFixture and MultiValueFixture)
   * short-circuit the inner tree-clearing helper: the mock trees return
   * Mockito's primitive default 0L from size(), so the iterate-and-remove
   * while-loop body inside doClearTree() (SV) and clearSVTree() (MV) never
   * executes. tree.remove → executeInsideComponentOperation →
   * acquireExclusiveLockTillOperationComplete is never reached, and the
   * per-tree exclusive lock that the production comment in
   * BTreeSingleValueIndexEngine.clear() and BTreeMultiValueIndexEngine.clear()
   * calls "load-bearing" is NOT acquired during these tests.
   *
   * <p>That makes these tests valid pins for the pure-delta accumulator
   * contract (negative delta recorded, in-memory AtomicLongs untouched,
   * persisted EP page not written directly) but they say nothing about the
   * lock-window race against a concurrent commit's apply hook on the same
   * engine. That race is exercised by the commit-path rollback regression
   * tests on both engines (BTreeMultiValueIndexEngineClearRollbackTest,
   * BTreeSingleValueIndexEngineClearRollbackTest) and is out of scope here.
   */
  private static final String CLEAR_CONCURRENCY_CONTRACT_NOTE =
      "Mockito fixtures short-circuit doClearTree()/clearSVTree(); the"
          + " per-tree exclusive lock claimed in the production comment is NOT"
          + " acquired here. The lock-window race is exercised by the"
          + " commit-path rollback regression tests, not by this unit test.";

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value: buildInitialHistogram()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_buildInitialHistogram_delegatesToManager()
      throws IOException {
    // Given a single-value engine with 3 non-null keys and 1 null entry.
    // Pre-state is diverged from the scan target so the recorded recalibration
    // delta is non-zero — a delta of (0, 0) would let a sign-flip or argument-
    // swap regression in accumulateInMemRecalibration slip through unnoticed.
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(7);
    f.engine.addToApproximateNullCount(2);
    var nullRid = new RecordId(1, 1);
    // countNulls calls get(null) which uses getVisible() (direct leaf-page lookup).
    // visibilityFilteredKeyStream uses firstKey + iterateEntriesMajor + visibilityFilter.
    // Use thenAnswer to return fresh streams on each call.
    when(f.sbTree.getVisible(any(), any(), any())).thenReturn(nullRid);
    var firstKey = new CompositeKey(null, 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(null, 0L), nullRid),
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L), new RecordId(2, 2)),
            new RawPair<>(new CompositeKey("c", 0L), new RecordId(2, 3))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram mock returns exact non-null count (3) for recalibration
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(3L);

    f.engine.buildInitialHistogram(f.op);

    // Delegation pin: approxTotal=7, exactNullCount=1 (pre-state read before scan;
    // exactNullCount is the live null-count scan, not the pre-state approxNull=2).
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(7L), eq(1L), eq(1));
    // Recalibration is post-commit via Hook B. exactTotal = 3 + 1 = 4,
    // currentTotal = 7 → inMemAdjustTotal = -3; exactNullCount = 1,
    // currentNull = 2 → inMemAdjustNull = -1.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-3L, delta.getInMemAdjustTotal());
    assertEquals(-1L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: buildInitialHistogram must not mutate the in-mem
    // AtomicLongs inline. The advance is gated on Hook B, which the
    // Mockito fixture does not drive. Pre-state was (7, 2).
    assertEquals("in-mem total stays at pre-state", 7L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 2L, f.engine.getNullCount(f.op));
    // Count must be persisted to the B-tree entry point page at the exact total
    verify(f.sbTree).setApproximateEntriesCount(f.op, 4L);
    // Precision pin: exactly one absolute write per recalibration; a regression
    // that issued a stale debug call alongside the target write would surface here.
    verify(f.sbTree, times(1)).setApproximateEntriesCount(any(), anyLong());
  }

  @Test
  public void singleValue_buildInitialHistogram_noNullEntry()
      throws IOException {
    // Given a single-value engine with 2 non-null keys and no null entry
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(2);
    when(f.sbTree.iterateEntriesBetween(any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    var firstKey = new CompositeKey("a", 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L), new RecordId(2, 2))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(2L);

    f.engine.buildInitialHistogram(f.op);

    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(2L), eq(0L), eq(1));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 2L);
  }

  /**
   * When the approximate count diverges from the actual data (e.g. after
   * rolled-back transactions), buildHistogram's return value must recalibrate
   * the counters to match the exact scan result.
   */
  @Test
  public void singleValue_buildInitialHistogram_approxDiverged_recalibrates()
      throws IOException {
    // Given: approximate counters say 10 entries, but actual data has 3
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(2);
    // countNulls calls get(null) which uses getVisible() (direct leaf-page lookup).
    when(f.sbTree.getVisible(any(), any(), any())).thenReturn(new RecordId(1, 1));
    var firstKey = new CompositeKey(null, 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(null, 0L), new RecordId(1, 1)),
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L), new RecordId(2, 2))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram scans 2 non-null keys and returns the exact count
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(2L);

    f.engine.buildInitialHistogram(f.op);

    // Counters recalibrated: exactTotal = 2 (scanned) + 1 (exactNull) = 3.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (10, 2); target is (3, 1);
    // expected deltas are (-7, -1).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-7L, delta.getInMemAdjustTotal());
    assertEquals(-1L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (10, 2) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 10L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 2L, f.engine.getNullCount(f.op));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 3L);
    // Precision pin: exactly one absolute write per recalibration.
    verify(f.sbTree, times(1)).setApproximateEntriesCount(any(), anyLong());
  }

  /**
   * When approximate count diverges for multi-value, buildHistogram recalibrates
   * the non-null counter and the null tree scan recalibrates the null counter.
   */
  @Test
  public void multiValue_buildInitialHistogram_approxDiverged_recalibrates()
      throws IOException {
    // Given: approximate counters say 20 entries (5 null), but actual sv data has 3
    // and actual null tree has 2 visible entries (drifted from approx 5)
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(20);
    f.engine.addToApproximateNullCount(5);
    var firstKey = new CompositeKey("a", new RecordId(2, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", new RecordId(2, 1), 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", new RecordId(2, 2), 0L), new RecordId(2, 2)),
            new RawPair<>(new CompositeKey("c", new RecordId(2, 3), 0L), new RecordId(2, 3))));
    // Null tree has 2 visible entries (recalibrated from approx 5)
    var nullFirstKey = new CompositeKey(new RecordId(3, 1), 0L);
    when(f.nullTree.firstKey(f.op)).thenReturn(nullFirstKey);
    when(f.nullTree.iterateEntriesMajor(eq(nullFirstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(new RecordId(3, 1), 0L), new RecordId(3, 1)),
            new RawPair<>(new CompositeKey(new RecordId(3, 2), 0L), new RecordId(3, 2))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram scans 3 non-null keys and returns the exact count
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(3L);

    f.engine.buildInitialHistogram(f.op);

    // Counters recalibrated: exactTotal = 3 (scanned) + 2 (exactNull) = 5.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (20, 5); target is (5, 2);
    // expected deltas are (-15, -3).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-15L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (20, 5) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 20L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 5L, f.engine.getNullCount(f.op));
    verify(f.svTree).setApproximateEntriesCount(f.op, 3L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 2L);
  }

  /**
   * When buildHistogram returns 0 (empty non-null stream), counters must be
   * recalibrated to reflect only the null count.
   */
  @Test
  public void singleValue_buildInitialHistogram_emptyNonNull_recalibratesToNullOnly()
      throws IOException {
    // Given: approximate counters say 5 entries, but only 1 null exists
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(5);
    f.engine.addToApproximateNullCount(1);
    var nullRid = new RecordId(1, 1);
    // countNulls calls get(null) which uses getVisible() (direct leaf-page lookup).
    when(f.sbTree.getVisible(any(), any(), any())).thenReturn(nullRid);
    // firstKey returns null-key entry; iterateEntriesMajor returns only the null
    var firstKey = new CompositeKey(null, 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(null, 0L), nullRid)));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram returns 0 — no non-null keys found
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(0L);

    f.engine.buildInitialHistogram(f.op);

    // Counters recalibrated: exactTotal = 0 (scanned) + 1 (exactNull) = 1.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (5, 1); target is (1, 1);
    // expected deltas are (-4, 0).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-4L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (5, 1) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 5L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 1L, f.engine.getNullCount(f.op));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 1L);
  }

  /**
   * When buildHistogram returns 0 for multi-value (empty svTree), counters
   * must be recalibrated to reflect only the exact null count.
   */
  @Test
  public void multiValue_buildInitialHistogram_emptyNonNull_recalibratesToNullOnly()
      throws IOException {
    // Given: approximate counters say 10 entries (3 null), but svTree is empty
    // and null tree has exactly 3 visible entries
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);
    // svTree has a firstKey but iterateEntriesMajor returns empty after
    // visibility filtering (all entries are phantoms or tombstones)
    var firstKey = new CompositeKey("a", new RecordId(1, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    // Null tree has 3 visible entries
    var nullFirstKey = new CompositeKey(new RecordId(4, 1), 0L);
    when(f.nullTree.firstKey(f.op)).thenReturn(nullFirstKey);
    when(f.nullTree.iterateEntriesMajor(eq(nullFirstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(new RecordId(4, 1), 0L), new RecordId(4, 1)),
            new RawPair<>(new CompositeKey(new RecordId(4, 2), 0L), new RecordId(4, 2)),
            new RawPair<>(new CompositeKey(new RecordId(4, 3), 0L), new RecordId(4, 3))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram returns 0 — no non-null keys found
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(0L);

    f.engine.buildInitialHistogram(f.op);

    // Counters recalibrated: exactTotal = 0 (scanned) + 3 (exactNull) = 3.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (10, 3); target is (3, 3);
    // expected deltas are (-7, 0).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-7L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (10, 3) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 10L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 3L, f.engine.getNullCount(f.op));
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 3L);
  }

  @Test
  public void singleValue_buildInitialHistogram_noManager_isNoOp()
      throws IOException {
    // Given a single-value engine without a histogram manager
    var f = new SingleValueFixture();
    f.engine.setHistogramManager(null);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then no exception and no interaction with B-tree
    verify(f.sbTree, never()).size(any());
  }

  @Test
  public void singleValue_buildInitialHistogram_compositeKeys()
      throws IOException {
    // Given a single-value engine with composite keys (2 fields), 2 entries
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(2);
    // No null entry
    when(f.sbTree.iterateEntriesBetween(any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    var firstKey = new CompositeKey("a", "x", 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", "x", 0L), new RecordId(1, 1)),
            new RawPair<>(new CompositeKey("b", "y", 0L), new RecordId(1, 2))));
    when(f.manager.getKeyFieldCount()).thenReturn(2);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(2L);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then keyFieldCount=2 is passed, approxTotal=2, nullCount=0
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(2L), eq(0L), eq(2));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value: rawKeyStreamForHistogram — TombstoneRID / SnapshotMarkerRID
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * rawKeyStreamForHistogram must filter out TombstoneRID entries (logically
   * deleted keys) but pass through live RecordId entries. This verifies the
   * core filter predicate: {@code !(pair.second() instanceof TombstoneRID)}.
   */
  @Test
  public void singleValue_buildInitialHistogram_filtersTombstoneRID()
      throws IOException {
    // Given: B-tree contains 3 live entries and 2 tombstones
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(5);
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    var firstKey = new CompositeKey("a", 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L),
                new TombstoneRID(new RecordId(2, 2))),
            new RawPair<>(new CompositeKey("c", 0L), new RecordId(2, 3)),
            new RawPair<>(new CompositeKey("d", 0L),
                new TombstoneRID(new RecordId(2, 4))),
            new RawPair<>(new CompositeKey("e", 0L), new RecordId(2, 5))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram receives stream with 3 live keys (tombstones filtered)
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(3L);

    f.engine.buildInitialHistogram(f.op);

    // Counters: 3 non-null live + 0 null = 3 total.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (5, 0); target is (3, 0);
    // expected deltas are (-2, 0).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-2L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (5, 0) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 5L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 0L, f.engine.getNullCount(f.op));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 3L);
    // Precision pin: exactly one absolute write per recalibration.
    verify(f.sbTree, times(1)).setApproximateEntriesCount(any(), anyLong());
  }

  /**
   * SnapshotMarkerRID entries represent re-inserted/updated keys — they are
   * live and must NOT be filtered by rawKeyStreamForHistogram. This test
   * verifies that SnapshotMarkerRID passes through the filter while
   * TombstoneRID is excluded.
   */
  @Test
  public void singleValue_buildInitialHistogram_countsSnapshotMarkerRID()
      throws IOException {
    // Given: B-tree has 1 RecordId, 1 SnapshotMarkerRID (live), 1 TombstoneRID
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(3);
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    var firstKey = new CompositeKey("a", 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L),
                new SnapshotMarkerRID(new RecordId(2, 2))),
            new RawPair<>(new CompositeKey("c", 0L),
                new TombstoneRID(new RecordId(2, 3)))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram receives 2 keys: "a" (RecordId) + "b" (SnapshotMarkerRID)
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(2L);

    f.engine.buildInitialHistogram(f.op);

    // 2 live entries (RecordId + SnapshotMarkerRID), tombstone excluded.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (3, 0); target is (2, 0);
    // expected deltas are (-1, 0).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-1L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (3, 0) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 3L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 0L, f.engine.getNullCount(f.op));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 2L);
  }

  /**
   * When the B-tree is completely empty (firstKey returns null),
   * rawKeyStreamForHistogram returns Stream.empty(). This exercises the
   * early-return branch at the top of the method.
   *
   * <p>The pre-state is also (0, 0), so the recorded recalibration delta is
   * (0, 0). The test pins the holder row's existence (any path through
   * accumulateInMemRecalibration creates the row even when both deltas are
   * zero) and the zero values themselves; that combination guards against a
   * regression where the recalibration call was skipped entirely and the
   * holder row never materialised.
   */
  @Test
  public void singleValue_buildInitialHistogram_completelyEmptyTree()
      throws IOException {
    // Given: completely empty B-tree (firstKey returns null)
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(0);
    // sbTree.firstKey returns null by default (Mockito default for Object)
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(0L);

    f.engine.buildInitialHistogram(f.op);

    verify(f.manager).buildHistogram(eq(f.op), any(), eq(0L), eq(0L), eq(1));
    // Holder-inspection pattern: the recalibration call is the only mutator
    // of inMemAdjust* on this path, so a (0, 0) row materialises even when
    // both deltas are zero. The per-put accumulators must stay at zero.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull(
        "recalibration must register a per-engine delta row even when zero",
        delta);
    assertEquals(0L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (0, 0) untouched inline; the zero-delta
    // recalibration still does not mutate the AtomicLongs.
    assertEquals("in-mem total stays at pre-state", 0L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 0L, f.engine.getNullCount(f.op));
    // Persisted side still writes the absolute target inline; the WAL-tracked
    // call lands at 0 because that is the exact post-rebuild count.
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
  }

  /**
   * When all non-null B-tree entries are TombstoneRID (mass deletion without
   * compaction), the raw stream produces zero elements after filtering.
   */
  @Test
  public void singleValue_buildInitialHistogram_allTombstones_returnsZero()
      throws IOException {
    // Given: all non-null entries are tombstones
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(3);
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    var firstKey = new CompositeKey("a", 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", 0L),
                new TombstoneRID(new RecordId(2, 1))),
            new RawPair<>(new CompositeKey("b", 0L),
                new TombstoneRID(new RecordId(2, 2))),
            new RawPair<>(new CompositeKey("c", 0L),
                new TombstoneRID(new RecordId(2, 3)))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(0L);

    f.engine.buildInitialHistogram(f.op);

    // All entries filtered out, exact total = 0 non-null + 0 null = 0.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (3, 0); target is (0, 0);
    // expected deltas are (-3, 0).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-3L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (3, 0) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 3L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 0L, f.engine.getNullCount(f.op));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
  }

  /**
   * Pins the zero-delta no-op holder-row contract on the single-value engine.
   * When the in-memory snapshot already matches the scan target (no drift),
   * buildInitialHistogram still routes through accumulateInMemRecalibration
   * with (0, 0), and the holder row materialises with both inMem fields at
   * zero. A future refactor that elides the recalibration call when the
   * delta is zero would slip past the divergent-pre-state pins above but
   * fail this one.
   */
  @Test
  public void singleValue_buildInitialHistogram_zeroDelta_recordsNoOpHolderRow()
      throws IOException {
    // Given: pre-state matches the scan target exactly, so the recorded
    // recalibration delta is (0, 0). Pre-state (4, 1) matches the scan
    // (3 non-null + 1 null = 4 total, 1 null).
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(4);
    f.engine.addToApproximateNullCount(1);
    var nullRid = new RecordId(1, 1);
    when(f.sbTree.getVisible(any(), any(), any())).thenReturn(nullRid);
    var firstKey = new CompositeKey(null, 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(null, 0L), nullRid),
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", 0L), new RecordId(2, 2)),
            new RawPair<>(new CompositeKey("c", 0L), new RecordId(2, 3))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(3L);

    f.engine.buildInitialHistogram(f.op);

    // Holder row must materialise with (0, 0) on both inMem axes.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull(
        "recalibration must register a per-engine delta row even when zero",
        delta);
    assertEquals(0L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // The per-put accumulators stay zero on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (4, 1) untouched inline; the zero-delta
    // recalibration still does not mutate the AtomicLongs.
    assertEquals("in-mem total stays at pre-state", 4L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 1L, f.engine.getNullCount(f.op));
    // Persisted side still writes the absolute target inline at 4.
    verify(f.sbTree).setApproximateEntriesCount(f.op, 4L);
  }

  /**
   * Sign-opposed counter-example pin on the single-value engine. Pre-state
   * has more in-mem null than scan-exact null while the in-mem total is
   * higher than the scan total, so totalDelta and nullDelta carry opposite
   * signs. accumulateInMemRecalibration has no precondition (unlike
   * accumulateClearOrRecalibrate) and must accept this shape; a regression
   * that routed the recalibration through the long-form clear accumulator
   * would trip the sign-alignment assert and fail this test.
   *
   * <p>SV-specific worked arithmetic: the SV {@code countNulls} returns at
   * most 1 (a unique index holds at most one null key). The sign-opposed
   * shape is driven by an over-counted in-mem total against the same scan.
   * Pre-state (100, 0): total=100 (drift), null=0. The snapshot invariant
   * {@code currentNull <= currentTotal} holds. Scanned: 20 non-null, 1
   * null. exactTotal = 21. totalDelta = -79; nullDelta = +1. The signs
   * oppose; {@code accumulateClearOrRecalibrate} would fail its
   * sign-alignment clause, while {@code accumulateInMemRecalibration}
   * accepts the shape without complaint.
   */
  @Test
  public void singleValue_buildInitialHistogram_signOpposedDeltas_recordedWithoutPrecondition()
      throws IOException {
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(100);
    var nullRid = new RecordId(1, 1);
    when(f.sbTree.getVisible(any(), any(), any())).thenReturn(nullRid);
    var firstKey = new CompositeKey(null, 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> {
          var entries = new java.util.ArrayList<RawPair<Object, RID>>();
          entries.add(new RawPair<>(new CompositeKey(null, 0L), nullRid));
          for (int i = 0; i < 20; i++) {
            entries.add(new RawPair<>(
                new CompositeKey("k" + i, 0L), new RecordId(2, i + 1)));
          }
          return entries.stream();
        });
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(20L);

    f.engine.buildInitialHistogram(f.op);

    // exactTotal = 20 + 1 = 21. Pre-state (100, 0). totalDelta = -79,
    // nullDelta = +1. Both recorded verbatim through the no-precondition
    // accumulator path.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull(
        "recalibration must register a per-engine delta row for sign-opposed deltas",
        delta);
    assertEquals(-79L, delta.getInMemAdjustTotal());
    assertEquals(1L, delta.getInMemAdjustNull());
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (100, 0) untouched inline; the sign-opposed
    // recalibration delta is recorded on the holder, not on the AtomicLongs.
    assertEquals("in-mem total stays at pre-state", 100L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 0L, f.engine.getNullCount(f.op));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 21L);
  }

  /**
   * Pins the snapshot-invariant assert message on the single-value engine's
   * buildInitialHistogram path. When the in-memory non-null counter
   * (currentTotal) is smaller than the in-memory null counter (currentNull),
   * the assert fires and the message carries the engine identity
   * (engine=test-sv) plus the drifted payload (currentTotal=2 currentNull=5).
   * Mirrors the existing clear() counterpart; a refactor that drops the
   * engine name from the buildInitialHistogram assert message would slip
   * past the clear-side test but fail this one.
   *
   * <p>The test is gated on assertion-status because the production check is
   * a Java {@code assert} statement; in production runs with -ea off the
   * snapshot-invariant violation falls through silently to the accumulator,
   * which has no precondition and would accept the drifted snapshot.
   */
  @Test
  public void singleValue_buildInitialHistogram_assertsSnapshotInvariant_currentNullExceedsTotal()
      throws IOException {
    assumeTrue(BTreeSingleValueIndexEngine.class.desiredAssertionStatus());
    var f = new SingleValueFixture();
    // Seed drift: null > total. Set total=2 and null=5 directly via the
    // public addTo helpers; currentNull (5) > currentTotal (2) violates the
    // snapshot invariant captured at the recalibration site.
    f.engine.addToApproximateEntriesCount(2);
    f.engine.addToApproximateNullCount(5);
    var nullRid = new RecordId(1, 1);
    when(f.sbTree.getVisible(any(), any(), any())).thenReturn(nullRid);
    var firstKey = new CompositeKey(null, 0L);
    when(f.sbTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.sbTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(null, 0L), nullRid),
            new RawPair<>(new CompositeKey("a", 0L), new RecordId(2, 1))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(1L);

    try {
      f.engine.buildInitialHistogram(f.op);
      fail("expected AssertionError because currentNull > currentTotal");
    } catch (AssertionError expected) {
      var msg = expected.getMessage();
      assertNotNull("assert must carry a message", msg);
      assertTrue(
          "assert message must carry engine identity, got: " + msg,
          msg.contains("engine=test-sv"));
      assertTrue(
          "assert message must carry the drifted snapshot, got: " + msg,
          msg.contains("currentTotal=2") && msg.contains("currentNull=5"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value: getNullCount() / getTotalCount() — O(1) counter reads
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_getNullCount_reflectsCounterValue() {
    // getNullCount() is O(1): reads approximateNullCount counter directly.
    var f = new SingleValueFixture();
    f.engine.addToApproximateNullCount(1);
    assertEquals(1, f.engine.getNullCount(f.op));
  }

  @Test
  public void singleValue_getNullCount_initial_returnsZero() {
    var f = new SingleValueFixture();
    assertEquals(0, f.engine.getNullCount(f.op));
  }

  @Test
  public void singleValue_getTotalCount_reflectsCounterValue() {
    // getTotalCount() is O(1): reads approximateIndexEntriesCount directly.
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(3);
    assertEquals(3, f.engine.getTotalCount(f.op));
  }

  @Test
  public void singleValue_getTotalCount_initial_returnsZero() {
    var f = new SingleValueFixture();
    assertEquals(0, f.engine.getTotalCount(f.op));
  }

  @Test
  public void singleValue_addToApproximateEntriesCount_negativeDelta() {
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateEntriesCount(-3);
    assertEquals(7, f.engine.getTotalCount(f.op));
  }

  @Test
  public void singleValue_addToApproximateNullCount_accumulatesDeltas() {
    var f = new SingleValueFixture();
    f.engine.addToApproximateNullCount(2);
    f.engine.addToApproximateNullCount(1);
    assertEquals(3, f.engine.getNullCount(f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value: buildInitialHistogram()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_buildInitialHistogram_delegatesToManager()
      throws IOException {
    // Given a multi-value engine with 2 sv entries and 1 null entry.
    // Pre-state is diverged from the scan target so the recorded recalibration
    // delta is non-zero — a delta of (0, 0) would let a sign-flip or argument-
    // swap regression in accumulateInMemRecalibration slip through unnoticed.
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(7);
    f.engine.addToApproximateNullCount(2);
    var firstKey = new CompositeKey("a", new RecordId(2, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", new RecordId(2, 1), 0L), new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", new RecordId(2, 2), 0L), new RecordId(2, 2))));
    // Null tree has 1 visible entry
    var nullFirstKey = new CompositeKey(new RecordId(3, 1), 0L);
    when(f.nullTree.firstKey(f.op)).thenReturn(nullFirstKey);
    when(f.nullTree.iterateEntriesMajor(eq(nullFirstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(new RecordId(3, 1), 0L), new RecordId(3, 1))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(2L);

    f.engine.buildInitialHistogram(f.op);

    // Delegation pin: approxTotal=7, approxNull=2 (pre-state read before scan).
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(7L), eq(2L), eq(1));
    // Recalibration is post-commit via Hook B. targetTotal = 2 + 1 = 3,
    // currentTotal = 7 → inMemAdjustTotal = -4; exactNullCount = 1,
    // currentNull = 2 → inMemAdjustNull = -1.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-4L, delta.getInMemAdjustTotal());
    assertEquals(-1L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (7, 2) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 7L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 2L, f.engine.getNullCount(f.op));
    // Non-null count persisted to svTree entry point page
    verify(f.svTree).setApproximateEntriesCount(f.op, 2L);
    // Null count persisted to nullTree entry point page
    verify(f.nullTree).setApproximateEntriesCount(f.op, 1L);
    // Precision pin: exactly one absolute write per tree per recalibration.
    verify(f.svTree, times(1)).setApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, times(1)).setApproximateEntriesCount(any(), anyLong());
  }

  @Test
  public void multiValue_buildInitialHistogram_noNullEntries()
      throws IOException {
    // Given a multi-value engine with 2 sv entries and no null entries
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(2);
    var firstKey = new CompositeKey("x", new RecordId(1, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("x", new RecordId(1, 1), 0L), new RecordId(1, 1)),
            new RawPair<>(new CompositeKey("y", new RecordId(1, 2), 0L), new RecordId(1, 2))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(2L);

    f.engine.buildInitialHistogram(f.op);

    // approxTotal = 2, approxNull = 0
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(2L), eq(0L), eq(1));
    verify(f.svTree).setApproximateEntriesCount(f.op, 2L);
    // Null tree is empty (firstKey returns null by default), so exact null count = 0
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
  }

  /**
   * Multi-value rawKeyStreamForHistogram must filter out TombstoneRID entries
   * while counting live entries, mirroring the single-value behavior.
   */
  @Test
  public void multiValue_buildInitialHistogram_filtersTombstoneRID()
      throws IOException {
    // Given: svTree has 2 live entries and 1 tombstone
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(3);
    var firstKey = new CompositeKey("a", new RecordId(2, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", new RecordId(2, 1), 0L),
                new RecordId(2, 1)),
            new RawPair<>(new CompositeKey("b", new RecordId(2, 2), 0L),
                new TombstoneRID(new RecordId(2, 2))),
            new RawPair<>(new CompositeKey("c", new RecordId(2, 3), 0L),
                new RecordId(2, 3))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    // buildHistogram receives 2 live keys (tombstone filtered out)
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(2L);

    f.engine.buildInitialHistogram(f.op);

    // Counters: 2 non-null live + 0 null = 2 total.
    // In-mem-side recalibration is now post-commit via Hook B (see
    // IndexCountDelta.accumulateInMemRecalibration); the holder records the
    // delta during the atomic op and Hook B applies it after commitChanges.
    // The Mockito fixture does not drive the AOM lifecycle, so we inspect
    // the recorded delta directly. Pre-state was (3, 0); target is (2, 0);
    // expected deltas are (-1, 0).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-1L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // The per-put accumulators must remain untouched on the build path.
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (3, 0) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 3L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 0L, f.engine.getNullCount(f.op));
    verify(f.svTree).setApproximateEntriesCount(f.op, 2L);
    // Pin the null-tree absolute write at zero (empty null tree).
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
    // Precision pin: exactly one absolute write per tree per recalibration.
    verify(f.svTree, times(1)).setApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, times(1)).setApproximateEntriesCount(any(), anyLong());
  }

  @Test
  public void multiValue_buildInitialHistogram_noManager_isNoOp()
      throws IOException {
    // Given a multi-value engine without a histogram manager
    var f = new MultiValueFixture();
    f.engine.setHistogramManager(null);

    // When buildInitialHistogram is called
    f.engine.buildInitialHistogram(f.op);

    // Then no interaction with trees
    verify(f.svTree, never()).size(any());
  }

  /**
   * MV one-tree-empty drift shape pin. Drifted pre-state where the in-mem
   * null counter exceeds the actual null-tree visible count, the svTree is
   * empty, and the snapshot invariant {@code currentNull <= currentTotal}
   * holds at the read site. Pre-state (10, 8): total=10 (over-counted),
   * null=8 (over-counted). The svTree scan returns zero rows; the null-tree
   * scan returns 1. exactTotal = 0 + 1 = 1. totalDelta = -9; nullDelta = -7.
   * Pins that the null-only drift is healed through the in-mem accumulator
   * even when the svTree contributes nothing, and that both persisted-side
   * absolute writes still happen.
   */
  @Test
  public void multiValue_buildInitialHistogram_oneTreeEmpty_driftShapePin()
      throws IOException {
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(8);
    // svTree is completely empty — firstKey returns null by default.
    // Null tree has exactly 1 visible entry.
    var nullFirstKey = new CompositeKey(new RecordId(3, 1), 0L);
    when(f.nullTree.firstKey(f.op)).thenReturn(nullFirstKey);
    when(f.nullTree.iterateEntriesMajor(eq(nullFirstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey(new RecordId(3, 1), 0L), new RecordId(3, 1))));
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(0L);

    f.engine.buildInitialHistogram(f.op);

    // Pre-state (10, 8); target (1, 1); deltas (-9, -7).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas()
        .get(f.engine.getId());
    assertNotNull("recalibration must register a per-engine delta", delta);
    assertEquals(-9L, delta.getInMemAdjustTotal());
    assertEquals(-7L, delta.getInMemAdjustNull());
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Negative pin: pre-state (10, 8) untouched inline; Hook B applies later.
    assertEquals("in-mem total stays at pre-state", 10L, f.engine.getTotalCount(f.op));
    assertEquals("in-mem null stays at pre-state", 8L, f.engine.getNullCount(f.op));
    // Both persisted-side absolute writes land regardless of drift shape.
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 1L);
  }

  /**
   * Pins the snapshot-invariant assert message on the multi-value engine's
   * buildInitialHistogram path. When the in-memory non-null counter
   * (currentTotal) is smaller than the in-memory null counter (currentNull),
   * the assert fires and the message carries the engine identity
   * (engine=test-mv) plus the drifted payload (currentTotal=2 currentNull=5).
   * Mirrors the existing clear() counterpart; a refactor that drops the
   * engine name from the buildInitialHistogram assert message would slip
   * past the clear-side test but fail this one.
   *
   * <p>The test is gated on assertion-status because the production check is
   * a Java {@code assert} statement; in production runs with -ea off the
   * snapshot-invariant violation falls through silently to the accumulator,
   * which has no precondition and would accept the drifted snapshot.
   */
  @Test
  public void multiValue_buildInitialHistogram_assertsSnapshotInvariant_currentNullExceedsTotal()
      throws IOException {
    assumeTrue(BTreeMultiValueIndexEngine.class.desiredAssertionStatus());
    var f = new MultiValueFixture();
    // Seed drift: null > total. The two AtomicLongs are independent at the
    // fixture level, so set total=2 and null=5 directly via the public addTo
    // helpers; currentNull (5) > currentTotal (2) violates the snapshot
    // invariant captured at the recalibration site.
    f.engine.addToApproximateEntriesCount(2);
    f.engine.addToApproximateNullCount(5);
    // The MV body scans the svTree first, then the null tree. The assert
    // fires after both scans complete and the in-mem counters are read.
    var firstKey = new CompositeKey("a", new RecordId(2, 1), 0L);
    when(f.svTree.firstKey(f.op)).thenReturn(firstKey);
    when(f.svTree.iterateEntriesMajor(eq(firstKey), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(new CompositeKey("a", new RecordId(2, 1), 0L),
                new RecordId(2, 1))));
    // Null tree is empty (firstKey returns null by default), so the
    // exactNullCount path short-circuits to zero.
    when(f.manager.getKeyFieldCount()).thenReturn(1);
    when(f.manager.buildHistogram(any(), any(), anyLong(), anyLong(), anyInt()))
        .thenReturn(1L);

    try {
      f.engine.buildInitialHistogram(f.op);
      fail("expected AssertionError because currentNull > currentTotal");
    } catch (AssertionError expected) {
      var msg = expected.getMessage();
      assertNotNull("assert must carry a message", msg);
      assertTrue(
          "assert message must carry engine identity, got: " + msg,
          msg.contains("engine=test-mv"));
      assertTrue(
          "assert message must carry the drifted snapshot, got: " + msg,
          msg.contains("currentTotal=2") && msg.contains("currentNull=5"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value: getNullCount() / getTotalCount() — O(1) counter reads
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_getNullCount_reflectsCounterValue() {
    // getNullCount() is O(1): reads approximateNullCount counter directly.
    var f = new MultiValueFixture();
    f.engine.addToApproximateNullCount(2);
    assertEquals(2, f.engine.getNullCount(f.op));
  }

  @Test
  public void multiValue_getTotalCount_reflectsCounterValue() {
    // getTotalCount() is O(1): reads approximateIndexEntriesCount directly.
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(3);
    assertEquals(3, f.engine.getTotalCount(f.op));
  }

  @Test
  public void multiValue_countersAreIndependent() {
    // Total and null counters are independent — setting one does not affect
    // the other. This mirrors the commit path where applyIndexCountDeltas
    // calls both addTo methods separately.
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(5);
    f.engine.addToApproximateNullCount(2);
    assertEquals(5, f.engine.getTotalCount(f.op));
    assertEquals(2, f.engine.getNullCount(f.op));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Single-value: delta accumulation in put/remove
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_put_newEntry_accumulatesTotalDeltaPlusOne()
      throws IOException {
    // Inserting a new non-null key must produce totalDelta=+1, nullDelta=0.
    var f = new SingleValueFixture();
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.put(f.op, "key", new RecordId(1, 1));

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(1, delta.getTotalDelta());
    assertEquals(0, delta.getNullDelta());
  }

  @Test
  public void singleValue_put_nullKey_accumulatesBothDeltas()
      throws IOException {
    // Inserting a null key must produce totalDelta=+1, nullDelta=+1.
    var f = new SingleValueFixture();
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.put(f.op, null, new RecordId(1, 1));

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(1, delta.getTotalDelta());
    assertEquals(1, delta.getNullDelta());
  }

  @Test
  public void singleValue_remove_accumulatesTotalDeltaMinusOne()
      throws IOException {
    // Removing a live entry must produce totalDelta=-1.
    var f = new SingleValueFixture();
    var existingKey = new CompositeKey("k", 0L);
    var rid = new RecordId(1, 1);
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(existingKey, rid)));
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.remove(f.op, "k");

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-1, delta.getTotalDelta());
    assertEquals(0, delta.getNullDelta());
  }

  @Test
  public void singleValue_put_overTombstone_accumulatesDelta()
      throws IOException {
    // Re-inserting over a tombstoned entry must produce totalDelta=+1.
    var f = new SingleValueFixture();
    var tombstoneKey = new CompositeKey("k", 0L);
    var tombstone = new TombstoneRID(new RecordId(1, 1));
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(tombstoneKey, tombstone)));
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.put(f.op, "k", new RecordId(2, 1));

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(1, delta.getTotalDelta());
  }

  @Test
  public void singleValue_put_updateLiveEntry_noDelta() throws IOException {
    // Replacing a live RecordId (not tombstone) must not change delta.
    var f = new SingleValueFixture();
    var liveKey = new CompositeKey("k", 0L);
    var liveRid = new RecordId(2, 1);
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(liveKey, liveRid)));
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.put(f.op, "k", new RecordId(3, 1));

    var deltas = f.op.getOrCreateIndexCountDeltas().getDeltas();
    assertFalse("No delta should be created when replacing a live entry",
        deltas.containsKey(f.engine.getId()));
  }

  @Test
  public void singleValue_remove_nullKey_decrementsBothDeltas()
      throws IOException {
    // Removing a null-key entry must decrement both totalDelta and nullDelta.
    var f = new SingleValueFixture();
    var existingKey = new CompositeKey(null, 0L);
    var rid = new RecordId(1, 1);
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(existingKey, rid)));
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.remove(f.op, null);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-1, delta.getTotalDelta());
    assertEquals(-1, delta.getNullDelta());
  }

  @Test
  public void singleValue_validatedPut_newEntry_accumulatesTotalDelta()
      throws IOException {
    // validatedPut for a brand-new key must produce totalDelta=+1.
    var f = new SingleValueFixture();
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.validatedPut(f.op, "key", new RecordId(1, 1), null);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(1, delta.getTotalDelta());
    assertEquals(0, delta.getNullDelta());
  }

  @Test
  public void singleValue_validatedPut_overTombstone_accumulatesDelta()
      throws IOException {
    // validatedPut over a tombstoned entry must produce totalDelta=+1.
    var f = new SingleValueFixture();
    var tombstoneKey = new CompositeKey("k", 0L);
    var tombstone = new TombstoneRID(new RecordId(1, 1));
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(
            new RawPair<>(tombstoneKey, tombstone)));
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.validatedPut(f.op, "k", new RecordId(2, 1), null);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(1, delta.getTotalDelta());
  }

  @Test
  public void singleValue_validatedPut_overLiveEntry_noDelta()
      throws IOException {
    // Replacing a live entry via validatedPut must not change the count.
    var f = new SingleValueFixture();
    var liveKey = new CompositeKey("k", 0L);
    var liveRid = new RecordId(2, 1);
    when(f.sbTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(liveKey, liveRid)));
    when(f.sbTree.put(any(), any(), any())).thenReturn(true);

    f.engine.validatedPut(f.op, "k", new RecordId(3, 1), null);

    var deltas = f.op.getOrCreateIndexCountDeltas().getDeltas();
    assertFalse("No delta should be created when replacing a live entry",
        deltas.containsKey(f.engine.getId()));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Multi-value: delta accumulation in put/remove
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void multiValue_put_nullKey_accumulatesBothDeltas()
      throws IOException {
    // Inserting a null key in multi-value engine must produce both deltas.
    var f = new MultiValueFixture();
    when(f.op.getCommitTs()).thenReturn(1L);
    when(f.nullTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    when(f.nullTree.put(any(), any(), any())).thenReturn(true);

    f.engine.put(f.op, null, new RecordId(1, 1));

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(1, delta.getTotalDelta());
    assertEquals(1, delta.getNullDelta());
  }

  @Test
  public void multiValue_put_nonNullKey_accumulatesTotalOnly()
      throws IOException {
    // Inserting a non-null key must only increment totalDelta.
    var f = new MultiValueFixture();
    when(f.op.getCommitTs()).thenReturn(1L);
    when(f.svTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.empty());
    when(f.svTree.put(any(), any(), any())).thenReturn(true);

    f.engine.put(f.op, "key", new RecordId(1, 1));

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(1, delta.getTotalDelta());
    assertEquals(0, delta.getNullDelta());
  }

  @Test
  public void multiValue_remove_nullKey_decrementsBothDeltas()
      throws IOException {
    // Removing a null key entry must decrement both deltas.
    var f = new MultiValueFixture();
    when(f.op.getCommitTs()).thenReturn(2L);
    var rid = new RecordId(1, 1);
    var existingKey = new CompositeKey(rid, 0L);
    when(f.nullTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(existingKey, rid)));
    when(f.nullTree.put(any(), any(), any())).thenReturn(true);

    f.engine.remove(f.op, null, rid);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-1, delta.getTotalDelta());
    assertEquals(-1, delta.getNullDelta());
  }

  @Test
  public void multiValue_remove_nonNullKey_decrementsTotalOnly()
      throws IOException {
    // Removing a non-null key entry must decrement totalDelta only.
    var f = new MultiValueFixture();
    when(f.op.getCommitTs()).thenReturn(2L);
    var rid = new RecordId(2, 1);
    var existingKey = new CompositeKey("key", rid, 0L);
    when(f.svTree.iterateEntriesBetween(
        any(), eq(true), any(), eq(true), eq(true), any()))
        .thenAnswer(inv -> Stream.of(new RawPair<>(existingKey, rid)));
    when(f.svTree.put(any(), any(), any())).thenReturn(true);

    f.engine.remove(f.op, "key", rid);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-1, delta.getTotalDelta());
    assertEquals(0, delta.getNullDelta());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // clear() — mixed-mode encoding (MV and SV)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Mixed-mode contract for the single-value engine's clear(): the snapshot
   * read of the in-memory counters happens after doClearTree() acquires the
   * per-tree exclusive lock; the persisted side gets one inline absolute zero
   * write on the single tree, WAL-tracked through the AOM lifecycle; the
   * in-memory side gets an inMemAdjust delta on the atomic op consumed by
   * Hook B's applyIndexCountDeltas post-commit. The in-memory AtomicLong
   * counters do NOT move inside clear() itself — they advance later in the
   * apply hook, before lock release.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_recordsNegativeInMemAdjustWithoutInMemoryMutation()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    // In-memory counters must NOT move inside clear(); they are advanced by
    // the apply hook after commitChanges, so a rolled-back clear leaves them
    // intact.
    assertEquals(10, f.engine.getTotalCount(f.op));
    assertEquals(3, f.engine.getNullCount(f.op));
    // The collapse is encoded as a negative in-mem-only delta on the atomic
    // op (Hook A's persistCountDelta is a no-op for this seam — the persisted
    // side is fed by the inline setApproximateEntriesCount write below).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
    // The persisted side is fed by one inline absolute zero write on the
    // single tree. The sibling addToApproximateEntriesCount mutator is the
    // delta path and must stay untouched on the clear() seam — a future
    // regression that added an extra sbTree.addToApproximateEntriesCount(op,
    // -currentTotal) call alongside the absolute write (or replaced the
    // absolute write with the delta-path write) would land on the delta-path
    // mutator and trip the assertion below.
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    // Acknowledge the two read-side interactions the production clear()
    // performs before the inline absolute write: doClearTree's while-loop
    // guard (sbTree.size(op) returns 0 on the Mockito fixture, so the loop
    // body is skipped) and the firstKey(op) read inside the post-doClearTree
    // assert. verifyNoMoreInteractions below then locks the full interaction
    // set on the sbTree mock to {size, firstKey, setApproximateEntriesCount}.
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Pins the zero-zero boundary on the in-memory-side delta and the
   * unconditional persisted-side absolute write: clear() on an engine whose
   * in-memory counters were never seeded records (0, 0) on the inMemAdjust
   * axes and still fires the single-tree setApproximateEntriesCount(op, 0)
   * write — the persisted-side absolute write is unconditional under the
   * mixed-mode encoding.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_onEmptyEngine_recordsZeroInMemAdjustAndAbsoluteWrite()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(0L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    assertEquals(0, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // The persisted-side setApproximateEntriesCount write is unconditional
    // under mixed-mode (zero seed still drives the heal-the-drift contract);
    // the sibling addToApproximateEntriesCount delta-path mutator stays
    // untouched.
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    // Acknowledge the two read-side interactions the production clear()
    // performs before the inline absolute write: doClearTree's while-loop
    // guard (sbTree.size(op) returns 0 on the Mockito fixture, so the loop
    // body is skipped) and the firstKey(op) read inside the post-doClearTree
    // assert. verifyNoMoreInteractions below then locks the full interaction
    // set on the sbTree mock to {size, firstKey, setApproximateEntriesCount}.
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Pins the |inMemAdjustNull| == |inMemAdjustTotal| configuration on the
   * single-value engine: when every entry is a null key, the in-mem-side
   * delta has equal magnitudes on both axes (the accumulateInMemRecalibration
   * method imposes no precondition, but the snapshot invariant
   * currentNull <= currentTotal still holds).
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_nullOnlyEngine_recordsEqualInMemAdjustOnBothAxes()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(7);
    f.engine.addToApproximateNullCount(7);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(-7L, delta.getInMemAdjustTotal());
    assertEquals(-7L, delta.getInMemAdjustNull());
    // In-mem AtomicLongs must NOT move inside clear(); Hook B advances them
    // post-commit.
    assertEquals(7, f.engine.getTotalCount(f.op));
    assertEquals(7, f.engine.getNullCount(f.op));
    // The persisted-side absolute zero write is unconditional under
    // mixed-mode; the sibling delta-path mutator stays untouched on the
    // clear() seam.
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    // Acknowledge the two read-side interactions the production clear()
    // performs before the inline absolute write: doClearTree's while-loop
    // guard (sbTree.size(op) returns 0 on the Mockito fixture, so the loop
    // body is skipped) and the firstKey(op) read inside the post-doClearTree
    // assert. verifyNoMoreInteractions below then locks the full interaction
    // set on the sbTree mock to {size, firstKey, setApproximateEntriesCount}.
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Pins the inMemAdjustNull == 0 configuration on the single-value engine:
   * when no entry is a null key, only the total-axis in-mem-side delta is
   * non-zero.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_nonNullOnlyEngine_recordsZeroInMemAdjustNull()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(5);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(-5L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // In-mem AtomicLongs must NOT move inside clear(); Hook B advances them
    // post-commit.
    assertEquals(5, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // The persisted-side absolute zero write is unconditional under
    // mixed-mode regardless of null/non-null distribution.
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    // Acknowledge the two read-side interactions the production clear()
    // performs before the inline absolute write: doClearTree's while-loop
    // guard (sbTree.size(op) returns 0 on the Mockito fixture, so the loop
    // body is skipped) and the firstKey(op) read inside the post-doClearTree
    // assert. verifyNoMoreInteractions below then locks the full interaction
    // set on the sbTree mock to {size, firstKey, setApproximateEntriesCount}.
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Pins axis isolation across the two accumulator surfaces on the
   * single-value engine: prior null-key put accumulations (short-form
   * sign=+1, isNullKey=true) advance only the totalDelta / nullDelta axes; a
   * subsequent clear() of a (10, 3)-seeded engine advances only the
   * inMemAdjust axes. The two axis pairs are independent on the holder and
   * Hook B sums them at apply time.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_keepsPriorPutDeltasOnTheirOwnAxis()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, true);
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, true);
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    // Prior put deltas stay on the totalDelta / nullDelta axis.
    assertEquals(+2L, delta.getTotalDelta());
    assertEquals(+2L, delta.getNullDelta());
    // The clear() collapse lands on the inMemAdjust axis only.
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
    // In-mem AtomicLongs must NOT move inside clear(); Hook B advances them
    // post-commit. IndexCountDelta.accumulate above touches the holder
    // only, so the engine-level counters see the seeded values (10, 3).
    assertEquals(10, f.engine.getTotalCount(f.op));
    assertEquals(3, f.engine.getNullCount(f.op));
    // The persisted-side absolute zero write is unconditional under
    // mixed-mode.
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
    // The sibling delta-path mutator stays untouched on the clear() seam
    // under mixed-mode.
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
    // Acknowledge the two read-side interactions the production clear()
    // performs before the inline absolute write: doClearTree's while-loop
    // guard (sbTree.size(op) returns 0 on the Mockito fixture, so the loop
    // body is skipped) and the firstKey(op) read inside the post-doClearTree
    // assert. verifyNoMoreInteractions below then locks the full interaction
    // set on the sbTree mock to {size, firstKey, setApproximateEntriesCount}.
    verify(f.sbTree).size(any());
    verify(f.sbTree).firstKey(any());
    verifyNoMoreInteractions(f.sbTree);
  }

  /**
   * Pins the SV clear() seam's write ordering: the persisted-side absolute
   * zero write must fire BEFORE the in-mem accumulator advances. Hooks A and
   * B downstream rely on this ordering — the persisted side is the
   * heal-the-drift anchor and the in-mem side rides post-commit apply. A
   * regression that swapped the two statements would land the in-mem-side
   * delta into the holder before the persisted side landed its zero write,
   * breaking the heal-the-drift contract; the order-agnostic plain verify
   * calls in the sibling tests would not catch that swap.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_persistedWriteFiresBeforeInMemAccumulator()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    // Capture the holder's inMemAdjust state at the moment
    // setApproximateEntriesCount(op, 0L) fires. If the production code
    // advanced the accumulator first, this capture would observe -10 / -3
    // instead of 0 / 0 and the assertions below would trip.
    AtomicReference<Long> inMemAdjustTotalAtPersistTime = new AtomicReference<>();
    AtomicReference<Long> inMemAdjustNullAtPersistTime = new AtomicReference<>();
    doAnswer(
        inv -> {
          var deltas = f.op.getOrCreateIndexCountDeltas().getDeltas();
          var d = deltas.get(f.engine.getId());
          inMemAdjustTotalAtPersistTime.set(d == null ? 0L : d.getInMemAdjustTotal());
          inMemAdjustNullAtPersistTime.set(d == null ? 0L : d.getInMemAdjustNull());
          return null;
        })
        .when(f.sbTree)
        .setApproximateEntriesCount(f.op, 0L);

    f.engine.clear(f.storage, f.op);

    // At the moment of the persisted-side write, the in-mem accumulator must
    // not have advanced yet on either axis.
    assertNotNull(
        "setApproximateEntriesCount(op, 0L) must have fired during clear()",
        inMemAdjustTotalAtPersistTime.get());
    assertEquals(
        "Persisted-side setApproximateEntriesCount(op, 0L) must fire BEFORE"
            + " IndexCountDelta.accumulateInMemRecalibration advances the holder;"
            + " a regression that swapped the two statements would land the"
            + " in-mem-side delta into the holder before the persisted side"
            + " landed its zero write, breaking the heal-the-drift contract.",
        0L,
        inMemAdjustTotalAtPersistTime.get().longValue());
    assertEquals(
        "Same ordering rule on the null axis: inMemAdjustNull must still be 0"
            + " when the persisted-side write fires.",
        0L,
        inMemAdjustNullAtPersistTime.get().longValue());

    // After clear() returns, the in-mem accumulator IS advanced.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
  }

  /**
   * Pins the single-value persistCountDelta one-tree-only mechanics directly:
   * a (-10, -3) delta on the persist-side holder fields produces exactly one
   * persisted-side write — addToApproximateEntriesCount(op, -10) on the
   * single tree — and nullDelta is silently dropped because the SV engine
   * holds nulls and non-nulls in the same tree. clear() under the mixed-mode
   * encoding no longer feeds these fields (the persisted side runs via
   * inline setApproximateEntriesCount); persistCountDelta remains the
   * delta-path for put/remove deltas, and the one-tree-only mechanics still
   * matter for that path. A future refactor that accidentally honoured
   * nullDelta by issuing a second addToApproximateEntriesCount(op, nullDelta)
   * write on the single tree would fail this test (the SV engine has no
   * sibling tree, so the precision pin below is the only catch-mechanism for
   * that mutation — plain verify is at-least-once and would not preclude
   * additional matching calls).
   */
  @Test
  public void singleValue_persistCountDelta_dropsNullDeltaOnSingleTree()
      throws IOException {
    var f = new SingleValueFixture();

    // Drive persistCountDelta directly with the same (-10, -3) shape clear()
    // produced under the prior pure-delta encoding. The SV engine writes
    // only totalDelta to sbTree.addToApproximateEntriesCount and silently
    // drops nullDelta (the single tree holds both null and non-null
    // entries).
    f.engine.persistCountDelta(f.op, -10L, -3L);

    verify(f.sbTree).addToApproximateEntriesCount(f.op, -10L);
    // Pin "exactly one delta-path write" — nullDelta must not produce a
    // second addToApproximateEntriesCount call on the single tree. Without
    // this, a regression that issued addToApproximateEntriesCount(op,
    // nullDelta) as a second write would slip past the contract verify
    // above (Mockito's at-least-once matching does not preclude additional
    // matching calls).
    verify(f.sbTree, times(1)).addToApproximateEntriesCount(any(), anyLong());
    // No setApproximateEntriesCount call from persistCountDelta — that
    // mutator is the buildInitialHistogram / clear() inline path, not the
    // delta path.
    verify(f.sbTree, never()).setApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the snapshot-invariant assert message added in production at
   * BTreeSingleValueIndexEngine.clear(): when the in-memory non-null counter
   * (currentTotal) is smaller than the in-memory null counter (currentNull),
   * the assert fires and the message carries the engine identity (engine=...)
   * plus the drifted payload (currentTotal=2 currentNull=5) so the CI stack
   * trace points at the right engine immediately.
   *
   * <p>This is the failure-side counterpart to the four pass-side boundary
   * tests above. A future refactor that drops the engine name from the
   * assert message would slip past those tests but fail this one.
   *
   * <p>The test is gated on assertion-status because the production check
   * is a Java {@code assert} statement; in production runs with -ea off the
   * snapshot-invariant violation falls through silently to the accumulator,
   * which has its own runtime check.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_assertsSnapshotInvariant_currentNullExceedsTotal()
      throws IOException {
    assumeTrue(BTreeSingleValueIndexEngine.class.desiredAssertionStatus());
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    // Seed drift: null > total. The two AtomicLongs are independent at the
    // fixture level, so we set total=2 and null=5 directly via the public
    // addTo helpers — currentNull (5) > currentTotal (2) violates the
    // snapshot invariant.
    f.engine.addToApproximateEntriesCount(2);
    f.engine.addToApproximateNullCount(5);

    try {
      f.engine.clear(f.storage, f.op);
      fail("expected AssertionError because currentNull > currentTotal");
    } catch (AssertionError expected) {
      var msg = expected.getMessage();
      assertNotNull("assert must carry a message", msg);
      assertTrue(
          "assert message must carry engine identity, got: " + msg,
          msg.contains("engine=test-sv"));
      assertTrue(
          "assert message must carry the drifted snapshot, got: " + msg,
          msg.contains("currentTotal=2") && msg.contains("currentNull=5"));
    }
  }

  /**
   * Mixed-mode contract for the multi-value engine's clear(): the snapshot
   * read of the in-memory counters happens after clearSVTree() acquires the
   * per-tree exclusive lock; the persisted side gets two inline absolute
   * zero writes (one per tree) WAL-tracked through the AOM lifecycle; the
   * in-memory side gets an inMemAdjust delta on the atomic op consumed by
   * Hook B's applyIndexCountDeltas post-commit. The in-memory AtomicLong
   * counters do NOT move inside clear() itself — they advance later in the
   * apply hook, before lock release.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_recordsNegativeInMemAdjustWithoutInMemoryMutation()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    // In-memory counters must NOT move inside clear(); they are advanced by
    // the apply hook after commitChanges, so a rolled-back clear leaves them
    // intact.
    assertEquals(10, f.engine.getTotalCount(f.op));
    assertEquals(3, f.engine.getNullCount(f.op));
    // The collapse is encoded as a negative in-mem-only delta on the atomic
    // op (Hook A's persistCountDelta is a no-op for this seam — the persisted
    // side is fed by the inline setApproximateEntriesCount writes below).
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
    // The persisted side is fed by two inline absolute zero writes (one per
    // tree). The sibling addToApproximateEntriesCount mutator is the delta
    // path and must stay untouched on the clear() seam — a future regression
    // that bypassed the inMemAdjust accumulator by calling
    // svTree.addToApproximateEntriesCount(op, -currentTotal) directly inside
    // clear() would land both the delta-path and the absolute-path writes,
    // which the assertion below would catch.
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the zero-zero boundary on the in-memory-side delta and the
   * unconditional persisted-side absolute writes: clear() on an engine whose
   * in-memory counters were never seeded records (0, 0) on the inMemAdjust
   * axes and still fires the two per-tree setApproximateEntriesCount(op, 0)
   * writes — the persisted-side absolute writes are unconditional under the
   * mixed-mode encoding.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_onEmptyEngine_recordsZeroInMemAdjustAndAbsoluteWrites()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(0L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    assertEquals(0, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // Per-tree setApproximateEntriesCount writes are unconditional under
    // mixed-mode (zero seed still drives the heal-the-drift contract); the
    // sibling addToApproximateEntriesCount delta-path mutator stays
    // untouched.
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the |inMemAdjustNull| == |inMemAdjustTotal| configuration: when
   * every entry is a null key, the in-mem-side delta has equal magnitudes on
   * both axes (the accumulateInMemRecalibration method imposes no precondition,
   * but the snapshot invariant currentNull <= currentTotal still holds).
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_nullOnlyEngine_recordsEqualInMemAdjustOnBothAxes()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(7);
    f.engine.addToApproximateNullCount(7);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(-7L, delta.getInMemAdjustTotal());
    assertEquals(-7L, delta.getInMemAdjustNull());
    // In-mem AtomicLongs must NOT move inside clear(); Hook B advances them
    // post-commit.
    assertEquals(7, f.engine.getTotalCount(f.op));
    assertEquals(7, f.engine.getNullCount(f.op));
    // Per-tree persisted-side absolute zero writes are unconditional under
    // mixed-mode.
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
    // The sibling addToApproximateEntriesCount delta-path mutator stays
    // untouched on the clear() seam under mixed-mode (the persisted side is
    // fed by setApproximateEntriesCount, not addToApproximateEntriesCount).
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the inMemAdjustNull == 0 configuration: when no entry is a null key,
   * only the total-axis in-mem-side delta is non-zero.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_nonNullOnlyEngine_recordsZeroInMemAdjustNull()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(5);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(-5L, delta.getInMemAdjustTotal());
    assertEquals(0L, delta.getInMemAdjustNull());
    // In-mem AtomicLongs must NOT move inside clear(); Hook B advances them
    // post-commit.
    assertEquals(5, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // Per-tree persisted-side absolute zero writes are unconditional under
    // mixed-mode regardless of null/non-null distribution.
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
    // The sibling addToApproximateEntriesCount delta-path mutator stays
    // untouched on the clear() seam under mixed-mode.
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins axis isolation across the two accumulator surfaces: prior null-key
   * put accumulations (short-form sign=+1, isNullKey=true) advance only the
   * totalDelta / nullDelta axes; a subsequent clear() of a (10, 3)-seeded
   * engine advances only the inMemAdjust axes. The two axis pairs are
   * independent on the holder and Hook B sums them at apply time.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_keepsPriorPutDeltasOnTheirOwnAxis()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, true);
    IndexCountDelta.accumulate(f.op, f.engine.getId(), +1, true);
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    // Prior put deltas stay on the totalDelta / nullDelta axis.
    assertEquals(+2L, delta.getTotalDelta());
    assertEquals(+2L, delta.getNullDelta());
    // The clear() collapse lands on the inMemAdjust axis only.
    assertEquals(-10L, delta.getInMemAdjustTotal());
    assertEquals(-3L, delta.getInMemAdjustNull());
    // In-mem AtomicLongs must NOT move inside clear(); Hook B advances them
    // post-commit. IndexCountDelta.accumulate above touches the holder
    // only, so the engine-level counters see the seeded values (10, 3).
    assertEquals(10, f.engine.getTotalCount(f.op));
    assertEquals(3, f.engine.getNullCount(f.op));
    // Per-tree persisted-side absolute zero writes are unconditional under
    // mixed-mode.
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
    // The sibling addToApproximateEntriesCount delta-path mutator stays
    // untouched on the clear() seam under mixed-mode.
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the multi-value persistCountDelta two-tree split mechanics directly:
   * a (-10, -3) delta on the persist-side holder fields produces
   * nonNullDelta = totalDelta - nullDelta = -10 - (-3) = -7 to svTree and
   * nullDelta = -3 to nullTree. clear() under the mixed-mode encoding no
   * longer feeds these fields (the persisted side runs via inline
   * setApproximateEntriesCount); persistCountDelta remains the delta-path
   * for put/remove deltas, and the split mechanics still matter for that
   * path. A future refactor that swapped the two trees or dropped one of
   * the writes would fail this test.
   */
  @Test
  public void multiValue_persistCountDelta_splitsAcrossBothTreesByNullDelta()
      throws IOException {
    var f = new MultiValueFixture();

    // Drive persistCountDelta directly with the same (-10, -3) shape clear()
    // produced under the prior pure-delta encoding. The MV engine splits:
    // nonNullDelta = totalDelta - nullDelta = -10 - (-3) = -7 to svTree;
    // nullDelta = -3 to nullTree.
    f.engine.persistCountDelta(f.op, -10L, -3L);

    verify(f.svTree).addToApproximateEntriesCount(f.op, -7L);
    verify(f.nullTree).addToApproximateEntriesCount(f.op, -3L);
    // No setApproximateEntriesCount call on either tree from
    // persistCountDelta — that mutator is the buildInitialHistogram /
    // clear() inline path, not the delta path.
    verify(f.svTree, never()).setApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).setApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the snapshot-invariant assert message added in production at
   * BTreeMultiValueIndexEngine.clear(): when the in-memory non-null counter
   * (currentTotal) is smaller than the in-memory null counter (currentNull),
   * the assert fires and the message carries the engine identity
   * (engine=test-mv) plus the drifted payload (currentTotal=2 currentNull=5)
   * so the CI stack trace points at the right engine immediately.
   *
   * <p>This is the failure-side counterpart to the five pass-side MV clear
   * tests above. A future refactor that drops the engine name from the assert
   * message would slip past those tests but fail this one.
   *
   * <p>The test is gated on assertion-status because the production check is
   * a Java {@code assert} statement; in production runs with -ea off the
   * snapshot-invariant violation falls through silently to the accumulator,
   * which has its own runtime check.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_assertsSnapshotInvariant_currentNullExceedsTotal()
      throws IOException {
    assumeTrue(BTreeMultiValueIndexEngine.class.desiredAssertionStatus());
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    // Seed drift: null > total. The two AtomicLongs are independent at the
    // fixture level, so set total=2 and null=5 directly via the public addTo
    // helpers — currentNull (5) > currentTotal (2) violates the snapshot
    // invariant.
    f.engine.addToApproximateEntriesCount(2);
    f.engine.addToApproximateNullCount(5);

    try {
      f.engine.clear(f.storage, f.op);
      fail("expected AssertionError because currentNull > currentTotal");
    } catch (AssertionError expected) {
      var msg = expected.getMessage();
      assertNotNull("assert must carry a message", msg);
      assertTrue(
          "assert message must carry engine identity, got: " + msg,
          msg.contains("engine=test-mv"));
      assertTrue(
          "assert message must carry the drifted snapshot, got: " + msg,
          msg.contains("currentTotal=2") && msg.contains("currentNull=5"));
    }
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Bulk loading suppression
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_bulkLoading_suppressesOnPut() throws IOException {
    // Given a single-value engine with bulk loading enabled
    var f = new SingleValueFixture();
    f.manager.setBulkLoading(true);
    when(f.sbTree.put(any(), eq(new CompositeKey("key", 1L)), any(RID.class))).thenReturn(true);

    // The actual onPut call on the real manager would be a no-op,
    // but we're testing that the engine delegates to the manager
    // which respects bulkLoading internally
    f.engine.put(f.op, "key", new RecordId(1, 1));

    // Engine always calls onPut — the manager's bulkLoading flag
    // controls whether it actually accumulates deltas
    verify(f.manager).onPut(f.op, "key", true, true);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // IndexHistogramManager: getKeyFieldCount()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramManager_getKeyFieldCount_defaultIs1() {
    var mgr = createRealManager();
    assertEquals(1, mgr.getKeyFieldCount());
  }

  @Test
  public void histogramManager_getKeyFieldCount_reflectsSetValue() {
    var mgr = createRealManager();
    mgr.setKeyFieldCount(3);
    assertEquals(3, mgr.getKeyFieldCount());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // IndexHistogramManager: statsFileExists()
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void histogramManager_statsFileExists_delegatesToAtomicOperation() {
    var storage = BTreeEngineTestFixtures.createMockStorage();

    var mgr = new IndexHistogramManager(
        storage, "test-idx", 0, true,
        new java.util.concurrent.ConcurrentHashMap<>(),
        mock(com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer.class),
        BinarySerializerFactory.create(
            BinarySerializerFactory.currentBinaryFormatVersion()),
        (byte) 0);

    // statsFileExists delegates to atomicOperation.isFileExists(fullName)
    var atomicOp = mock(AtomicOperation.class);
    when(atomicOp.isFileExists("test-idx.ixs")).thenReturn(true);
    assertTrue(mgr.statsFileExists(atomicOp));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // Fixtures and helpers
  // ═══════════════════════════════════════════════════════════════════════

  /** Creates a CompositeKey with the given key and a dummy RID (multi-value pattern). */
  private static CompositeKey makeComposite(Object key) {
    var ck = new CompositeKey(key);
    ck.addKey(new RecordId(1, 1));
    return ck;
  }

  private static IndexHistogramManager createRealManager() {
    var storage = BTreeEngineTestFixtures.createMockStorage();
    return new IndexHistogramManager(
        storage, "test-idx", 0, true,
        new java.util.concurrent.ConcurrentHashMap<>(),
        mock(com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer.class),
        BinarySerializerFactory.create(
            BinarySerializerFactory.currentBinaryFormatVersion()),
        (byte) 0);
  }

  private static class SingleValueFixture
      extends BTreeEngineTestFixtures.SingleValueFixture {
  }

  private static class MultiValueFixture
      extends BTreeEngineTestFixtures.MultiValueFixture {
  }
}
