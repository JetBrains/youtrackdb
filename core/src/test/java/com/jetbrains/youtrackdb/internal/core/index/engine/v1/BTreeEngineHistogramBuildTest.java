package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
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
import com.jetbrains.youtrackdb.internal.core.id.TombstoneRID;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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
        deltas.containsKey(0));
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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
        deltas.containsKey(0));
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
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

    var delta = f.op.getOrCreateIndexCountDeltas().getDeltas().get(0);
    assertNotNull(delta);
    assertEquals(-1, delta.getTotalDelta());
    assertEquals(0, delta.getNullDelta());
  }

  // ═══════════════════════════════════════════════════════════════════════
  // clear() resets both counters
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  public void singleValue_clear_resetsBothCountersToZero() throws IOException {
    // clear() must reset both approximateIndexEntriesCount and
    // approximateNullCount to 0.
    var f = new SingleValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    assertEquals(0, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // Persisted count on entry point page must also be reset
    verify(f.sbTree).setApproximateEntriesCount(f.op, 0L);
  }

  @Test
  public void multiValue_clear_resetsBothCountersToZero() throws IOException {
    // clear() must reset both counters to 0 for multi-value engine.
    var f = new MultiValueFixture();
    f.engine.addToApproximateEntriesCount(10);
    f.engine.addToApproximateNullCount(3);

    f.engine.clear(f.storage, f.op);

    assertEquals(0, f.engine.getTotalCount(f.op));
    assertEquals(0, f.engine.getNullCount(f.op));
    // Persisted counts on both entry point pages must also be reset
    verify(f.svTree).setApproximateEntriesCount(f.op, 0L);
    verify(f.nullTree).setApproximateEntriesCount(f.op, 0L);
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
