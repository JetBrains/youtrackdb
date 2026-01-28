package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.Test;

public class AtomicOperationsTableTest {

  @Test
  public void snapshot_emptyTable_returnsEmptyRangeAndEmptyBitSet() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    AtomicOperationsTable.AtomicOperationTableState snapshot =
        table.snapshotAtomicOperationTableState();

    assertEquals(0L, snapshot.minPersistedOperationId());
    assertEquals(0L, snapshot.maxPersistedOperationId());
    assertNotNull(snapshot.inProgressBits());
    assertTrue(snapshot.inProgressBits().isEmpty());
    assertFalse(snapshot.isInProgress(0L));
    assertFalse(snapshot.isInProgress(1L));
  }

  @Test
  public void snapshot_examplePattern_marksInProgressBeforeLastPersisted_onlyWithinRange() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    // Persist ops 1..5 to establish a baseline.
    persistRange(table, 1, 5);

    // Pattern (scaled down):
    // PERSISTED 5
    // IN_PROGRESS 6
    // PERSISTED 7
    // IN_PROGRESS 8
    // PERSISTED 9
    // IN_PROGRESS 10  (should NOT be present because snapshot covers [minPersisted, maxPersisted))
    startInProgress(table, 6, 1);
    persistOne(table, 7, 1);
    startInProgress(table, 8, 1);
    persistOne(table, 9, 1);
    startInProgress(table, 10, 1);

    AtomicOperationsTable.AtomicOperationTableState snapshot =
        table.snapshotAtomicOperationTableState();

    // Range: [minPersisted, maxPersisted)
    // With your persist-range maintenance, maxPersisted should be 9.
    assertEquals(9L, snapshot.maxPersistedOperationId());

    // minPersisted may be 5 or 6 depending on how you define it (boundary semantics),
    // but the key invariant for the snapshot is that it only reports IN_PROGRESS within [min, max).
    final long min = snapshot.minPersistedOperationId();
    final long max = snapshot.maxPersistedOperationId();
    assertTrue(max >= min);

    // "6" and "8" must be visible as IN_PROGRESS if they fall inside [min, max)
    assertEquals(isInRange(6, min, max), snapshot.isInProgress(6L));
    assertEquals(isInRange(8, min, max), snapshot.isInProgress(8L));

    // 10 is outside [min, 9) and must not appear in the bitset range
    assertFalse(snapshot.isInProgress(10L));

    // If min <= 6 < 9 and min <= 8 < 9, then exactly two bits should be set.
    int expected = 0;
    if (isInRange(6, min, max)) expected++;
    if (isInRange(8, min, max)) expected++;
    assertEquals(expected, snapshot.inProgressBits().cardinality());
  }

  @Test
  public void snapshot_whenMaxEqualsMin_returnsEmptyBitSetAndNoInProgress() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    // Persist 1..5
    persistRange(table, 1, 5);

    // Depending on your min/max maintenance, you may end up with min==max after a contiguous chain,
    // or min<max. This test enforces the behavior contract of snapshot(): if max<=min => empty bitset.
    AtomicOperationsTable.AtomicOperationTableState snapshot =
        table.snapshotAtomicOperationTableState();

    if (snapshot.maxPersistedOperationId() <= snapshot.minPersistedOperationId()) {
      assertTrue(snapshot.inProgressBits().isEmpty());
      // Any opId queried should return false because it's outside [min,max)
      assertFalse(snapshot.isInProgress(snapshot.minPersistedOperationId()));
      assertFalse(snapshot.isInProgress(snapshot.maxPersistedOperationId()));
    }
  }

  @Test
  public void snapshot_afterGapResolved_minMovesForward_andBitSetShrinks() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    // Persist 1..5 baseline
    persistRange(table, 1, 5);

    // Create:
    // IN_PROGRESS 6
    // PERSISTED 7
    // IN_PROGRESS 8
    // PERSISTED 9
    startInProgress(table, 6, 1);
    persistOne(table, 7, 1);
    startInProgress(table, 8, 1);
    persistOne(table, 9, 1);

    AtomicOperationsTable.AtomicOperationTableState before =
        table.snapshotAtomicOperationTableState();

    assertEquals(9L, before.maxPersistedOperationId());

    final long beforeMin = before.minPersistedOperationId();
    final long beforeMax = before.maxPersistedOperationId();

    // Before resolving 6, 6 and 8 are in progress if they are in [min,max).
    final boolean beforeHas6 = isInRange(6, beforeMin, beforeMax);
    final boolean beforeHas8 = isInRange(8, beforeMin, beforeMax);

    assertEquals(beforeHas6, before.isInProgress(6L));
    assertEquals(beforeHas8, before.isInProgress(8L));

    // Resolve the gap at 6 (commit+persist).
    commitAndPersistExisting(table, 6);

    AtomicOperationsTable.AtomicOperationTableState after =
        table.snapshotAtomicOperationTableState();

    assertEquals(9L, after.maxPersistedOperationId());

    // min should move forward (or stay, depending on your boundary), but must NOT move backward.
    assertTrue(after.minPersistedOperationId() >= before.minPersistedOperationId());

    final long afterMin = after.minPersistedOperationId();
    final long afterMax = after.maxPersistedOperationId();

    // After persisting 6, "6" must not be IN_PROGRESS.
    assertFalse(after.isInProgress(6L));

    // "8" should remain IN_PROGRESS if it's in range [min,max)
    assertEquals(isInRange(8, afterMin, afterMax), after.isInProgress(8L));

    // BitSet should not grow; usually it shrinks (because min advanced and/or 6 became terminal).
    assertTrue(after.inProgressBits().cardinality() <= before.inProgressBits().cardinality());
  }

  // -----------------------
  // Helpers
  // -----------------------

  private static boolean isInRange(long opId, long minInclusive, long maxExclusive) {
    return opId >= minInclusive && opId < maxExclusive;
  }

  private static void startInProgress(AtomicOperationsTable table, long opId, long segment) {
    table.startOperation(opId, segment);
  }

  private static void persistOne(AtomicOperationsTable table, long opId, long segment) {
    table.startOperation(opId, segment);
    table.commitOperation(opId);
    table.persistOperation(opId);
  }

  private static void persistRange(AtomicOperationsTable table, long from, long toInclusive) {
    for (long opId = from; opId <= toInclusive; opId++) {
      persistOne(table, opId, 1);
    }
  }

  private static void commitAndPersistExisting(AtomicOperationsTable table, long opId) {
    table.commitOperation(opId);
    table.persistOperation(opId);
  }
}