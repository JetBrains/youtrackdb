package com.jetbrains.youtrackdb.internal.core.index.lifecycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import java.util.UUID;
import org.junit.Test;

/** Covers lifecycle transition legality and durable value invariants. */
public class IndexBuildStateTest {

  /** Exactly the four approved state changes are legal. */
  @Test
  public void onlyApprovedLifecycleTransitionsAreLegal() {
    for (var current : IndexLifecycle.values()) {
      for (var replacement : IndexLifecycle.values()) {
        var expected =
            (current == IndexLifecycle.EXISTS && replacement == IndexLifecycle.MAINTAINED)
                || (current == IndexLifecycle.MAINTAINED
                    && replacement == IndexLifecycle.USABLE)
                || (current == IndexLifecycle.MAINTAINED
                    && replacement == IndexLifecycle.INVALID)
                || (current == IndexLifecycle.INVALID
                    && replacement == IndexLifecycle.MAINTAINED);
        assertTrue(expected == IndexBuildState.isLegalTransition(current, replacement));
      }
    }
  }

  /** Initial durable state contains no storage or lineage identity. */
  @Test
  public void initialStateStartsWithoutCompletedWork() {
    var state = IndexBuildState.initial(new RecordId(7, 9));

    assertTrue(state.lifecycle() == IndexLifecycle.EXISTS);
    assertTrue(state.failure() == IndexBuildFailure.NONE);
    assertTrue(state.completedUnits() == 0);
    assertFalse(state.suspended());
  }

  /** Compare-and-set publishes one complete newer snapshot. */
  @Test
  public void compareAndSetPublishesNewerSnapshot() {
    var current =
        new IndexLifecycleSnapshot(state(IndexLifecycle.EXISTS, IndexBuildFailure.NONE), 1);
    var replacement =
        new IndexLifecycleSnapshot(state(IndexLifecycle.MAINTAINED, IndexBuildFailure.NONE), 2);
    var cell = new IndexLifecycleCell(current);

    assertTrue(cell.compareAndSet(current, replacement));
    assertSame(replacement, cell.snapshot());
  }

  /** The expected snapshot controls publication when another update already won. */
  @Test
  public void compareAndSetRejectsStaleExpectedSnapshot() {
    var current =
        new IndexLifecycleSnapshot(state(IndexLifecycle.EXISTS, IndexBuildFailure.NONE), 1);
    var next =
        new IndexLifecycleSnapshot(state(IndexLifecycle.MAINTAINED, IndexBuildFailure.NONE), 2);
    var cell = new IndexLifecycleCell(current);
    assertTrue(cell.compareAndSet(current, next));

    assertFalse(cell.compareAndSet(current, next));
    assertSame(next, cell.snapshot());
  }

  /** An unsupported durable format reports the rejected version. */
  @Test
  public void formatVersionMustBeCurrent() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> state(2, new RecordId(7, 9), null, null, 0));

    assertEquals("Unsupported index build state format 2", failure.getMessage());
  }

  /** A transient descriptor identity is rejected before publication. */
  @Test
  public void descriptorIdentityMustBePersistent() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> state(1, new RecordId(-1, -1), null, null, 0));

    assertEquals("The index descriptor identity must be persistent", failure.getMessage());
  }

  /** A negative owner epoch is rejected before publication. */
  @Test
  public void ownerEpochCannotBeNegative() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> state(1, new RecordId(7, 9), -1L, null, 0));

    assertEquals("The owner epoch cannot be negative", failure.getMessage());
  }

  /** A negative completion cut is rejected before publication. */
  @Test
  public void completionCutCannotBeNegative() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> state(1, new RecordId(7, 9), null, -1L, 0));

    assertEquals("The completion cut cannot be negative", failure.getMessage());
  }

  /** A negative completed-unit count is rejected before publication. */
  @Test
  public void completedUnitsCannotBeNegative() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> state(1, new RecordId(7, 9), null, null, -1));

    assertEquals("Completed units cannot be negative", failure.getMessage());
  }

  /** An older snapshot cannot replace a newer expected snapshot. */
  @Test
  public void compareAndSetRejectsOlderReplacementVersion() {
    var expected =
        new IndexLifecycleSnapshot(state(IndexLifecycle.EXISTS, IndexBuildFailure.NONE), 2);
    var replacement =
        new IndexLifecycleSnapshot(state(IndexLifecycle.MAINTAINED, IndexBuildFailure.NONE), 1);
    var cell = new IndexLifecycleCell(expected);

    var failure = assertThrows(
        IllegalArgumentException.class, () -> cell.compareAndSet(expected, replacement));
    assertEquals("A lifecycle snapshot cannot move to an older version", failure.getMessage());
    assertSame(expected, cell.snapshot());
  }

  /** A retired cell rejects compare-and-set publication. */
  @Test
  public void compareAndSetRejectsRetiredCell() {
    var expected =
        new IndexLifecycleSnapshot(state(IndexLifecycle.EXISTS, IndexBuildFailure.NONE), 1);
    var replacement =
        new IndexLifecycleSnapshot(state(IndexLifecycle.MAINTAINED, IndexBuildFailure.NONE), 2);
    var cell = new IndexLifecycleCell(expected);
    cell.retire();

    var failure = assertThrows(
        IllegalStateException.class, () -> cell.compareAndSet(expected, replacement));
    assertEquals(
        "The lifecycle cell belongs to a retired storage lineage", failure.getMessage());
  }

  /** A snapshot rejects a negative durable record version. */
  @Test
  public void snapshotRecordVersionCannotBeNegative() {
    var failure = assertThrows(
        IllegalArgumentException.class,
        () -> new IndexLifecycleSnapshot(
            state(IndexLifecycle.EXISTS, IndexBuildFailure.NONE), -1));

    assertEquals("The record version cannot be negative", failure.getMessage());
  }

  /** An invalid state requires a machine-readable failure reason. */
  @Test
  public void invalidStateRequiresFailureCode() {
    assertThrows(
        IllegalArgumentException.class,
        () -> state(IndexLifecycle.INVALID, IndexBuildFailure.NONE));
  }

  /** Missing-state quarantine belongs only to an invalid lifecycle. */
  @Test
  public void missingStateFailureRequiresInvalidLifecycle() {
    assertThrows(
        IllegalArgumentException.class,
        () -> state(IndexLifecycle.EXISTS, IndexBuildFailure.INDEX_BUILD_STATE_MISSING));
  }

  private static IndexBuildState state(IndexLifecycle lifecycle, IndexBuildFailure failure) {
    return state(1, new RecordId(7, 9), null, null, 0, lifecycle, failure);
  }

  private static IndexBuildState state(
      int formatVersion, RecordId descriptor, Long ownerEpoch, Long completionCut,
      long completedUnits) {
    return state(
        formatVersion,
        descriptor,
        ownerEpoch,
        completionCut,
        completedUnits,
        IndexLifecycle.EXISTS,
        IndexBuildFailure.NONE);
  }

  private static IndexBuildState state(
      int formatVersion,
      RecordId descriptor,
      Long ownerEpoch,
      Long completionCut,
      long completedUnits,
      IndexLifecycle lifecycle,
      IndexBuildFailure failure) {
    return new IndexBuildState(
        formatVersion,
        descriptor,
        lifecycle,
        UUID.randomUUID(),
        ownerEpoch,
        completionCut,
        completedUnits,
        false,
        failure,
        null);
  }
}
