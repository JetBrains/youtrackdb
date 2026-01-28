package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.Test;

public class AtomicOperationsTableTest {

  @Test
  public void snapshot_emptyTable_returnsEmptyAndMaxPersistedDefault() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    AtomicOperationsTable.AtomicOperationTableState snapshot =
        table.snapshotAtomicOperationTableState();

    // Default value is 0 because maxPersistedOperationId is a volatile long with default 0.
    assertEquals(0L, snapshot.maxPersistedOperationId());
    assertNotNull(snapshot.inProgressOpList());
    assertTrue(snapshot.inProgressOpList().isEmpty());
  }

  @Test
  public void snapshot_examplePattern_returnsLastPersistedAndInProgressBeforeIt() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    // Persist ops 1..5 to establish: minPersisted = maxPersisted = 5
    persistRange(table, 1, 5);

    // Build your pattern (scaled down):
    // PERSISTED 5
    // IN_PROGRESS 6
    // PERSISTED 7
    // IN_PROGRESS 8
    // PERSISTED 9
    // IN_PROGRESS 10  (should be ignored, because it's after last persisted)
    startInProgress(table, 6, 1);
    persistOne(table, 7, 1);
    startInProgress(table, 8, 1);
    persistOne(table, 9, 1);
    startInProgress(table, 10, 1);

    AtomicOperationsTable.AtomicOperationTableState snapshot =
        table.snapshotAtomicOperationTableState();

    // "Latest persisted from end" in your logic = maxPersistedOperationId
    assertEquals(9L, snapshot.maxPersistedOperationId());

    // Must include only IN_PROGRESS < 9 in [minPersisted, maxPersisted) = [5,9)
    // => 6 and 8 (not 10)
    assertEquals(List.of(6L, 8L), snapshot.inProgressOpList());
  }

  @Test
  public void snapshot_whenMaxEqualsMin_returnsEmptyList() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    // Persist 1..5 => min=max=5
    persistRange(table, 1, 5);

    AtomicOperationsTable.AtomicOperationTableState snapshot =
        table.snapshotAtomicOperationTableState();

    assertEquals(5L, snapshot.maxPersistedOperationId());
    assertTrue(snapshot.inProgressOpList().isEmpty());
  }

  @Test
  public void snapshot_afterGapResolved_minMovesForward_andSnapshotChanges() {
    AtomicOperationsTable table = new AtomicOperationsTable(1000, 0);

    // Persist 1..5 => min=max=5
    persistRange(table, 1, 5);

    // Create state:
    // PERSISTED 5, 7, 9
    // IN_PROGRESS 6, 8
    // => minPersisted should be 5, maxPersisted should be 9
    startInProgress(table, 6, 1);
    persistOne(table, 7, 1);
    startInProgress(table, 8, 1);
    persistOne(table, 9, 1);

    AtomicOperationsTable.AtomicOperationTableState before =
        table.snapshotAtomicOperationTableState();

    assertEquals(9L, before.maxPersistedOperationId());
    assertEquals(List.of(6L, 8L), before.inProgressOpList());

    // Now resolve the "gap" at 6: commit+persist it.
    // Your persistOperation() logic should then advance minPersistedOperationId
    // over already-terminal ops (6 and 7) until it hits op 8 which is still IN_PROGRESS.
    commitAndPersistExisting(table, 6);

    AtomicOperationsTable.AtomicOperationTableState after =
        table.snapshotAtomicOperationTableState();

    // maxPersisted remains 9 (already persisted)
    assertEquals(9L, after.maxPersistedOperationId());

    // Now minPersisted should have jumped to 8, so snapshot scans [8,9) and returns [8]
    assertEquals(List.of(8L), after.inProgressOpList());
  }

  // -----------------------
  // Helpers
  // -----------------------

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