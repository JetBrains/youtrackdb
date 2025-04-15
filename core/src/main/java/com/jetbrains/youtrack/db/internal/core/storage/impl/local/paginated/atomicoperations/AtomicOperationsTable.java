package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrack.db.internal.common.concur.collection.CASObjectArray;
import com.jetbrains.youtrack.db.internal.common.concur.lock.ScalableRWLock;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

public class AtomicOperationsTable {

  private static final OperationInformation ATOMIC_OPERATION_STATUS_PLACE_HOLDER =
      new OperationInformation(AtomicOperationStatus.NOT_STARTED, -1, -1);

  private long[] idOffsets;
  private CASObjectArray<OperationInformation>[] tables;

  private final ScalableRWLock compactionLock = new ScalableRWLock();

  private final int tableCompactionInterval;

  private final AtomicLong operationsStarted = new AtomicLong();
  private volatile long lastCompactionOperation;

  public AtomicOperationsTable(final int tableCompactionInterval, final long idOffset) {
    this.tableCompactionInterval = tableCompactionInterval;
    this.idOffsets = new long[]{idOffset};
    //noinspection unchecked
    tables = new CASObjectArray[]{new CASObjectArray<>()};
  }

  public void startOperation(final long operationId, final long segment) {
    changeOperationStatus(operationId, null, AtomicOperationStatus.IN_PROGRESS, segment);
  }

  public void commitOperation(final long operationId) {
    changeOperationStatus(
        operationId, AtomicOperationStatus.IN_PROGRESS, AtomicOperationStatus.COMMITTED, -1);
  }

  public void rollbackOperation(final long operationId) {
    changeOperationStatus(
        operationId, AtomicOperationStatus.IN_PROGRESS, AtomicOperationStatus.ROLLED_BACK, -1);
  }

  public void persistOperation(final long operationId) {
    changeOperationStatus(
        operationId, AtomicOperationStatus.COMMITTED, AtomicOperationStatus.PERSISTED, -1);
  }

  public long getSegmentEarliestOperationInProgress() {
    compactionLock.sharedLock();
    try {
      for (final var table : tables) {
        final var size = table.size();
        for (var i = 0; i < size; i++) {
          final var operationInformation = table.get(i);
          if (operationInformation.status == AtomicOperationStatus.IN_PROGRESS) {
            return operationInformation.segment;
          }
        }
      }
    } finally {
      compactionLock.sharedUnlock();
    }

    return -1;
  }

  public long getSegmentEarliestNotPersistedOperation() {
    compactionLock.sharedLock();
    try {
      for (final var table : tables) {
        final var size = table.size();
        for (var i = 0; i < size; i++) {
          final var operationInformation = table.get(i);
          if (operationInformation.status == AtomicOperationStatus.IN_PROGRESS
              || operationInformation.status == AtomicOperationStatus.COMMITTED) {
            return operationInformation.segment;
          }
        }
      }
    } finally {
      compactionLock.sharedUnlock();
    }

    return -1;
  }

  private void changeOperationStatus(
      final long operationId,
      final AtomicOperationStatus expectedStatus,
      final AtomicOperationStatus newStatus,
      final long segment) {
    if (operationsStarted.get() > lastCompactionOperation + tableCompactionInterval) {
      compactTable();
    }

    compactionLock.sharedLock();
    try {
      if (segment >= 0 && newStatus != AtomicOperationStatus.IN_PROGRESS) {
        throw new IllegalStateException(
            "Invalid status of atomic operation, expected " + AtomicOperationStatus.IN_PROGRESS);
      }

      if (newStatus == AtomicOperationStatus.IN_PROGRESS && segment < 0) {
        throw new IllegalStateException(
            "Invalid value of transaction segment for newly started operation");
      }

      var currentIndex = 0;
      var currentOffset = idOffsets[0];
      var nextOffset = idOffsets.length > 1 ? idOffsets[1] : Long.MAX_VALUE;

      while (true) {
        if (currentOffset <= operationId && operationId < nextOffset) {
          final var itemIndex = (int) (operationId - currentOffset);
          if (itemIndex < 0) {
            throw new IllegalStateException("Invalid state of table of atomic operations");
          }

          final var table = tables[currentIndex];
          if (newStatus == AtomicOperationStatus.IN_PROGRESS) {
            table.set(
                itemIndex,
                new OperationInformation(AtomicOperationStatus.IN_PROGRESS, segment, operationId),
                ATOMIC_OPERATION_STATUS_PLACE_HOLDER);
            operationsStarted.incrementAndGet();
          } else {
            final var currentInformation = table.get(itemIndex);
            if (currentInformation.operationId != operationId) {
              throw new IllegalStateException(
                  "Invalid operation id, expected "
                      + currentInformation.operationId
                      + " but found "
                      + operationId);
            }
            if (currentInformation.status != expectedStatus) {
              throw new IllegalStateException(
                  "Invalid state of table of atomic operations, incorrect expected state "
                      + currentInformation.status
                      + " for upcoming state "
                      + newStatus
                      + " . Expected state was "
                      + expectedStatus
                      + " .");
            }

            if (!table.compareAndSet(
                itemIndex,
                currentInformation,
                new OperationInformation(newStatus, currentInformation.segment, operationId))) {
              throw new IllegalStateException("Invalid state of table of atomic operations");
            }
          }

          break;
        } else {
          currentIndex++;
          if (currentIndex >= idOffsets.length) {
            throw new IllegalStateException(
                "Invalid state of table of atomic operations, entry for the transaction with id "
                    + operationId
                    + " can not be found");
          }

          currentOffset = idOffsets[currentIndex];
          nextOffset =
              idOffsets.length > currentIndex + 1 ? idOffsets[currentIndex + 1] : Long.MAX_VALUE;
        }
      }
    } finally {
      compactionLock.sharedUnlock();
    }
  }

  public void compactTable() {
    compactionLock.exclusiveLock();
    try {
      final var tablesToRemove = new ArrayDeque<Integer>(tables.length);

      var tablesAreFull = true;
      var maxId = Long.MIN_VALUE;

      for (var tableIndex = 0; tableIndex < tables.length; tableIndex++) {
        final var table = tables[tableIndex];
        final var idOffset = idOffsets[tableIndex];

        final var newTable = new CASObjectArray<OperationInformation>();
        final var tableSize = table.size();
        var addition = false;

        long newIdOffset = -1;
        for (var i = 0; i < tableSize; i++) {
          final var operationInformation = table.get(i);
          if (!addition) {
            if (operationInformation.status == AtomicOperationStatus.IN_PROGRESS
                || operationInformation.status == AtomicOperationStatus.NOT_STARTED
                || operationInformation.status == AtomicOperationStatus.COMMITTED) {
              addition = true;

              newIdOffset = i + idOffset;
              newTable.add(operationInformation);
            }
          } else {
            newTable.add(operationInformation);
          }

          if (maxId < idOffset + i) {
            maxId = i;
          }
        }

        if (newIdOffset < 0) {
          newIdOffset = idOffset + tableSize;
        }

        this.tables[tableIndex] = newTable;
        this.idOffsets[tableIndex] = newIdOffset;

        if (newTable.size() == 0) {
          tablesToRemove.push(tableIndex);
        } else {
          tablesAreFull =
              (tablesAreFull || tableIndex == 0) && newTable.size() == tableCompactionInterval;
        }
      }

      if (!tablesToRemove.isEmpty() && tables.length > 1) {
        if (tablesToRemove.size() == tables.length) {
          this.idOffsets = new long[]{maxId + 1};
          //noinspection unchecked
          this.tables = new CASObjectArray[]{tables[0]};
        } else {
          //noinspection unchecked
          CASObjectArray<OperationInformation>[] newTables =
              new CASObjectArray[this.tables.length - tablesToRemove.size()];
          var newIdOffsets = new long[this.idOffsets.length - tablesToRemove.size()];

          var firstSrcIndex = 0;
          var firstDestIndex = 0;

          for (final int tableIndex : tablesToRemove) {
            final var len = tableIndex - firstSrcIndex;
            if (len > 0) {
              System.arraycopy(this.tables, firstSrcIndex, newTables, firstDestIndex, len);
              System.arraycopy(this.idOffsets, firstSrcIndex, newIdOffsets, firstDestIndex, len);
              firstDestIndex += len;
            }
            firstSrcIndex = tableIndex + 1;
          }

          this.tables = newTables;
          this.idOffsets = newIdOffsets;
        }
      }

      lastCompactionOperation = operationsStarted.get();
    } finally {
      compactionLock.exclusiveUnlock();
    }
  }

  private static final class OperationInformation {

    private final AtomicOperationStatus status;
    private final long segment;
    private final long operationId;

    private OperationInformation(AtomicOperationStatus status, long segment, long operationId) {
      this.status = status;
      this.segment = segment;
      this.operationId = operationId;
    }
  }
}
