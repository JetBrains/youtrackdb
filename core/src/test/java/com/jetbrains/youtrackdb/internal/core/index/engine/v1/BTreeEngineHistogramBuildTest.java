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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    // Given a single-value engine with 3 non-null keys and 1 null entry
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(4);
    f.engine.addToApproximateNullCount(1);
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

    // approxTotal = 4, exactNullCount = 1
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(4L), eq(1L), eq(1));
    // Counters must be recalibrated from the exact scan
    assertEquals(4, f.engine.getTotalCount(f.op));
    assertEquals(1, f.engine.getNullCount(f.op));
    // Count must be persisted to the B-tree entry point page
    verify(f.sbTree).setApproximateEntriesCount(f.op, 4L);
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

    // Counters must be recalibrated: exactTotal = 2 (scanned) + 1 (null) = 3
    assertEquals(3, f.engine.getTotalCount(f.op));
    assertEquals(1, f.engine.getNullCount(f.op));
    verify(f.sbTree).setApproximateEntriesCount(f.op, 3L);
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

    // Counters recalibrated: exactTotal = 3 (scanned) + 2 (exactNull) = 5
    assertEquals(5, f.engine.getTotalCount(f.op));
    // Null count recalibrated from approx 5 to exact 2
    assertEquals(2, f.engine.getNullCount(f.op));
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

    // Counters recalibrated: exactTotal = 0 (scanned) + 1 (null) = 1
    assertEquals(1, f.engine.getTotalCount(f.op));
    assertEquals(1, f.engine.getNullCount(f.op));
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

    // Counters recalibrated: exactTotal = 0 (scanned) + 3 (exactNull) = 3
    assertEquals(3, f.engine.getTotalCount(f.op));
    assertEquals(3, f.engine.getNullCount(f.op));
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

    // Counters must reflect 3 live entries, not 5 total
    assertEquals(3, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
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

    // 2 live entries (RecordId + SnapshotMarkerRID), tombstone excluded
    assertEquals(2, f.engine.getTotalCount(f.op));
  }

  /**
   * When the B-tree is completely empty (firstKey returns null),
   * rawKeyStreamForHistogram returns Stream.empty(). This exercises the
   * early-return branch at the top of the method.
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
    assertEquals(0, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
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

    // All entries filtered out, exact total = 0 non-null + 0 null = 0
    assertEquals(0, f.engine.getTotalCount(f.op));
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
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(3);
    f.engine.addToApproximateNullCount(1);
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

    // approxTotal = 3, approxNull = 1
    verify(f.manager).buildHistogram(
        eq(f.op), any(), eq(3L), eq(1L), eq(1));
    // Counters must be recalibrated from the exact scan
    assertEquals(3, f.engine.getTotalCount(f.op));
    assertEquals(1, f.engine.getNullCount(f.op));
    // Non-null count persisted to svTree entry point page
    verify(f.svTree).setApproximateEntriesCount(f.op, 2L);
    // Null count persisted to nullTree entry point page
    verify(f.nullTree).setApproximateEntriesCount(f.op, 1L);
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

    // Counters: 2 non-null live + 0 null = 2 total
    assertEquals(2, f.engine.getTotalCount(f.op));
    verify(f.svTree).setApproximateEntriesCount(f.op, 2L);
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
  // clear() — pure-delta encoding (MV and SV)
  // ═══════════════════════════════════════════════════════════════════════

  /**
   * Pure-delta contract for the single-value engine's clear(): the snapshot
   * read of the in-memory counters happens after doClearTree() acquires the
   * per-tree exclusive lock, the resulting -current delta is accumulated on
   * the atomic op, and neither the in-memory AtomicLong counters nor the
   * persisted EP page is mutated inside clear() itself. Both sides advance
   * later: the persisted EP page via persistCountDelta before commitChanges
   * (totalDelta only; the single-value engine intentionally ignores
   * nullDelta because nulls live in the same tree as non-null keys), the
   * in-memory AtomicLongs via the apply hook before lock release.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_recordsNegativeDeltaWithoutInMemoryMutation()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    // In-memory counters must NOT move inside clear() — they are advanced by
    // the apply hook after commitChanges, so a rolled-back clear leaves them
    // intact.
    assertEquals(10, f.engine.getTotalCount(f.op));
    assertEquals(3, f.engine.getNullCount(f.op));
    // The collapse is encoded as a negative delta on the atomic op.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-10L, delta.getTotalDelta());
    assertEquals(-3L, delta.getNullDelta());
    // The persisted EP page must not be touched directly inside clear() via
    // either persisted-side mutator on CellBTreeSingleValue. persistCountDelta
    // owns the EP-page write at Hook A; setApproximateEntriesCount and the
    // sibling addToApproximateEntriesCount both write the EP slot, so both
    // are pinned negative here. A future regression that bypasses the delta
    // holder by calling sbTree.addToApproximateEntriesCount(op, -currentTotal)
    // inside clear() would slip past a setApproximateEntriesCount-only check.
    verify(f.sbTree, never()).setApproximateEntriesCount(any(), anyLong());
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the zero-zero boundary of the snapshot delta and of the accumulator's
   * sign-aligned magnitude assert on the single-value engine: clear() on an
   * engine whose in-memory counters were never seeded must record an additive
   * (0, 0) delta on the holder and must not touch the persisted EP page via
   * either persisted-side mutator on the single tree.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_onEmptyEngine_accumulatesZeroDelta()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(0, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // Both persisted-side mutators are pinned negative (see the longer note
    // in singleValue_clear_recordsNegativeDeltaWithoutInMemoryMutation).
    verify(f.sbTree, never()).setApproximateEntriesCount(any(), anyLong());
    verify(f.sbTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the |nullDelta| == |totalDelta| boundary of the accumulator's
   * sign-aligned magnitude assert on the single-value engine: when every entry
   * is a null key, the accumulated delta has equal magnitudes on both axes.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_nullOnlyEngine_accumulatesEqualDeltas()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(7);
    f.engine.addToApproximateNullCount(7);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-7L, delta.getTotalDelta());
    assertEquals(-7L, delta.getNullDelta());
  }

  /**
   * Pins the nullDelta == 0 boundary of the accumulator's sign-aligned
   * magnitude assert on the single-value engine: when no entry is a null key,
   * only the total delta is non-zero and the assert's one-zero clause covers
   * the configuration.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_nonNullOnlyEngine_accumulatesZeroNullDelta()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(5);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-5L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
  }

  /**
   * Pins additive composition across the two accumulate overloads on the
   * single-value engine: two prior null-key put accumulations (short-form
   * sign=+1, isNullKey=true) followed by a clear of a (10, 3)-seeded engine
   * must leave the holder at (-10 + 2, -3 + 2) == (-8, -1).
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_composesWithPriorPutDeltas()
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
    assertEquals(-8L, delta.getTotalDelta());
    assertEquals(-1L, delta.getNullDelta());
  }

  /**
   * Pins the single-value-specific persistCountDelta contract: clear()
   * accumulates a non-zero nullDelta on the holder when the engine had any
   * null entries, but persistCountDelta deliberately discards nullDelta on
   * apply because the SV engine stores nulls and non-nulls in the same tree.
   *
   * <p>The test drives both halves of the contract: clear() records the full
   * (-10, -3) delta on the accumulator, and a direct persistCountDelta call
   * with that delta produces exactly one persisted-side write —
   * addToApproximateEntriesCount(op, -10) on the single tree — and no
   * sibling "null-tree" write (the SV engine has no separate null tree). A
   * future refactor that accidentally makes persistCountDelta honour
   * nullDelta would fail this test even though the accumulator-only tests
   * above stay green.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree
   * exclusive lock cited in the production comment is NOT acquired during
   * this test because the Mockito fixture short-circuits doClearTree().
   */
  @Test
  public void singleValue_clear_persistCountDeltaIgnoresNullDelta_butAccumulatorRecordsIt()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-10L, delta.getTotalDelta());
    assertEquals(-3L, delta.getNullDelta());

    // Drive persistCountDelta with the recorded (totalDelta, nullDelta) and
    // verify the SV engine's persisted-side write pattern. Per the SV
    // engine's persistCountDelta implementation, only totalDelta is written
    // to sbTree.addToApproximateEntriesCount and nullDelta is silently
    // dropped (the single tree holds both null and non-null entries).
    f.engine.persistCountDelta(
        f.op, delta.getTotalDelta(), delta.getNullDelta());

    verify(f.sbTree).addToApproximateEntriesCount(f.op, -10L);
    // No other persisted-side mutation on the single tree (setApproximate-
    // EntriesCount is the buildInitialHistogram path, not the delta path).
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
   * Pure-delta contract for the multi-value engine's clear(): the snapshot
   * read of the in-memory counters happens after clearSVTree() acquires the
   * per-tree exclusive lock, the resulting -current delta is accumulated on
   * the atomic op, and neither the in-memory AtomicLong counters nor the
   * persisted EP pages are mutated inside clear() itself. Both sides advance
   * later: the persisted EP pages via persistCountDelta before commitChanges,
   * the in-memory AtomicLongs via the apply hook before lock release.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_recordsNegativeDeltaWithoutInMemoryMutation()
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
    // The collapse is encoded as a negative delta on the atomic op; the
    // engine id of MultiValueFixture is 0.
    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-10L, delta.getTotalDelta());
    assertEquals(-3L, delta.getNullDelta());
    // The persisted EP pages must not be touched directly inside clear() via
    // either persisted-side mutator on the underlying trees. persistCountDelta
    // owns the EP-page write at Hook A; setApproximateEntriesCount and the
    // sibling addToApproximateEntriesCount both write the EP slot, so both are
    // pinned negative here. A future regression that bypasses the delta holder
    // by calling svTree.addToApproximateEntriesCount(op, -currentTotal)
    // directly inside clear() would slip past a setApproximateEntriesCount-only
    // check.
    verify(f.svTree, never()).setApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).setApproximateEntriesCount(any(), anyLong());
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the zero-zero boundary of the snapshot delta and of the accumulator's
   * sign-aligned magnitude assert: clear() on an engine whose in-memory
   * counters were never seeded must record an additive (0, 0) delta on the
   * holder and must not touch either persisted-side mutator on either tree.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_onEmptyEngine_accumulatesZeroDelta()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(0L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    assertEquals(0, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // Both persisted-side mutators are pinned negative (see the longer note
    // in multiValue_clear_recordsNegativeDeltaWithoutInMemoryMutation).
    verify(f.svTree, never()).setApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).setApproximateEntriesCount(any(), anyLong());
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the |nullDelta| == |totalDelta| boundary of the accumulator's
   * sign-aligned magnitude assert: when every entry is a null key, the
   * accumulated delta has equal magnitudes on both axes.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_nullOnlyEngine_accumulatesEqualDeltas()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(7);
    f.engine.addToApproximateNullCount(7);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-7L, delta.getTotalDelta());
    assertEquals(-7L, delta.getNullDelta());
    // Both persisted-side mutators are pinned negative (see the longer note
    // in multiValue_clear_recordsNegativeDeltaWithoutInMemoryMutation).
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the nullDelta == 0 boundary of the accumulator's sign-aligned
   * magnitude assert: when no entry is a null key, only the total delta is
   * non-zero and the assert's one-zero clause covers the configuration.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_nonNullOnlyEngine_accumulatesZeroNullDelta()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(5);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-5L, delta.getTotalDelta());
    assertEquals(0L, delta.getNullDelta());
    // Both persisted-side mutators are pinned negative (see the longer note
    // in multiValue_clear_recordsNegativeDeltaWithoutInMemoryMutation).
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins additive composition across the two accumulate overloads: two prior
   * null-key put accumulations (short-form sign=+1, isNullKey=true) followed
   * by a clear of a (10, 3)-seeded engine must leave the holder at
   * (-10 + 2, -3 + 2) == (-8, -1).
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_composesWithPriorPutDeltas()
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
    assertEquals(-8L, delta.getTotalDelta());
    assertEquals(-1L, delta.getNullDelta());
    // Both persisted-side mutators are pinned negative (see the longer note
    // in multiValue_clear_recordsNegativeDeltaWithoutInMemoryMutation).
    verify(f.svTree, never()).addToApproximateEntriesCount(any(), anyLong());
    verify(f.nullTree, never()).addToApproximateEntriesCount(any(), anyLong());
  }

  /**
   * Pins the multi-value persistCountDelta contract: clear() accumulates the
   * full (-10, -3) delta on the holder, and a direct persistCountDelta call
   * with that delta produces a two-tree split: nonNullDelta = totalDelta -
   * nullDelta = -10 - (-3) = -7 to svTree, and nullDelta = -3 to nullTree.
   * A future refactor that swaps the two trees or drops one of the writes
   * would not be caught by the accumulator-only tests above.
   *
   * <p>See {@link #CLEAR_CONCURRENCY_CONTRACT_NOTE} — the per-tree exclusive
   * lock cited in the production comment is NOT acquired during this test
   * because the Mockito fixture short-circuits clearSVTree().
   */
  @Test
  public void multiValue_clear_persistCountDeltaSplitsAcrossBothTrees()
      throws IOException {
    assertNotNull(CLEAR_CONCURRENCY_CONTRACT_NOTE);
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(f.engine.getId());
    assertNotNull(delta);
    assertEquals(-10L, delta.getTotalDelta());
    assertEquals(-3L, delta.getNullDelta());

    // Drive persistCountDelta with the recorded delta. The MV engine splits:
    // nonNullDelta = totalDelta - nullDelta = -10 - (-3) = -7 to svTree;
    // nullDelta = -3 to nullTree.
    f.engine.persistCountDelta(f.op, delta.getTotalDelta(), delta.getNullDelta());

    verify(f.svTree).addToApproximateEntriesCount(f.op, -7L);
    verify(f.nullTree).addToApproximateEntriesCount(f.op, -3L);
    // No setApproximateEntriesCount call on either tree — that mutator is the
    // buildInitialHistogram path, not the delta path.
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
