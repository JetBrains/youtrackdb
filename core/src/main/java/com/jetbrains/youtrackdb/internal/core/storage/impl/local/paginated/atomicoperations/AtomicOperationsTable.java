package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations;

import com.jetbrains.youtrackdb.internal.common.concur.collection.CASObjectArray;
import com.jetbrains.youtrackdb.internal.common.concur.lock.ScalableRWLock;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicLong;

/// A concurrent transaction tracking table for implementing Snapshot Isolation (SI).
///
/// This class maintains the lifecycle state of atomic operations (transactions) and provides
/// visibility decisions for concurrent read operations. It is a core component of the SI
/// implementation, enabling readers to determine which record versions are visible based on
/// transaction timestamps.
///
/// ## Functionality
///
/// - **Lifecycle tracking**: Tracks operations through state transitions:
///   `NOT_STARTED → IN_PROGRESS → COMMITTED → PERSISTED` or
///   `NOT_STARTED → IN_PROGRESS → ROLLED_BACK`
/// - **Snapshot visibility**: Creates consistent snapshots of in-progress transactions
///   for determining record version visibility
/// - **WAL segment management**: Identifies earliest segments with active or unpersisted
///   operations to support write-ahead log truncation
/// - **Memory management**: Periodic compaction removes completed entries
///
/// ## Design
///
/// The table uses a **segmented structure** where each segment covers a contiguous range
/// of timestamps. This enables O(1) index calculation: `itemIndex = operationTs - tsOffset`.
///
/// **Concurrency model**:
/// - Individual status updates use lock-free CAS operations within segments
/// - Shared lock is held during reads and status changes (allows concurrency)
/// - Exclusive lock is acquired only during structural changes (compaction)
///
/// ## Thread Safety
///
/// This class is thread-safe. All public methods can be called concurrently from multiple
/// threads. The implementation uses a combination of [ScalableRWLock] for structural
/// protection and [CASObjectArray] for lock-free element updates.
///
/// @see AtomicOperationStatus
/// @see AtomicOperationsSnapshot
public class AtomicOperationsTable {

  /// Sentinel placeholder for uninitialized operation slots.
  ///
  /// Used as the expected value in CAS operations when starting a new operation.
  /// Represents an operation that has not yet been registered in the table.
  private static final OperationInformation ATOMIC_OPERATION_STATUS_PLACE_HOLDER =
      new OperationInformation(AtomicOperationStatus.NOT_STARTED, -1, -1);

  /// Base timestamps for each table segment.
  ///
  /// Each element represents the starting timestamp for the corresponding segment in
  /// [#tables]. The index of an operation within a segment is calculated as:
  /// `operationTs - tsOffsets[segmentIndex]`.
  ///
  /// Segments cover contiguous, non-overlapping timestamp ranges:
  /// - `[tsOffsets[0], tsOffsets[1])` → `tables[0]`
  /// - `[tsOffsets[1], tsOffsets[2])` → `tables[1]`
  /// - `[tsOffsets[n], ∞)` → `tables[n]`
  private long[] tsOffsets;

  /// Array of lock-free arrays storing operation information.
  ///
  /// Each [CASObjectArray] represents a segment of the table, containing
  /// [OperationInformation] records for operations within the timestamp range
  /// defined by the corresponding entry in [#tsOffsets].
  private CASObjectArray<OperationInformation>[] tables;

  /// Reader-writer lock protecting structural changes to the table.
  ///
  /// **Shared lock** is acquired for:
  /// - Reading operation status
  /// - Changing operation status (CAS within segments)
  /// - Creating snapshots
  ///
  /// **Exclusive lock** is acquired only during [#compactTable()] to prevent
  /// concurrent access while segments are being restructured.
  private final ScalableRWLock compactionLock = new ScalableRWLock();

  /// Number of operations between automatic compaction attempts.
  ///
  /// When `operationsStarted > lastCompactionOperation + tableCompactionInterval`,
  /// compaction is triggered on the next status change operation.
  private final int tableCompactionInterval;

  /// Counter of total operations started.
  ///
  /// Incremented each time a new operation enters [AtomicOperationStatus#IN_PROGRESS]
  /// state. Used together with [#lastCompactionOperation] to determine when
  /// compaction should be triggered.
  private final AtomicLong operationsStarted = new AtomicLong();

  /// Value of [#operationsStarted] at the time of the last compaction.
  ///
  /// Compaction is triggered when
  /// `operationsStarted.get() > lastCompactionOperation + tableCompactionInterval`.
  private volatile long lastCompactionOperation;

  /// An immutable snapshot of the atomic operations table state at a point in time.
  ///
  /// This record captures the set of in-progress transactions and provides visibility
  /// checking for Snapshot Isolation. A reader uses this snapshot to determine which
  /// record versions are visible based on their timestamps.
  ///
  /// ## Visibility Rules
  ///
  /// For a record with timestamp `recordTs`:
  /// - `recordTs < minActiveOperationTs` → **Visible** (committed before snapshot)
  /// - `recordTs >= maxActiveOperationTs` → **Not visible** (future or in-progress)
  /// - `recordTs == minActiveOperationTs` → **Not visible** (snapshot boundary)
  /// - `recordTs` in `inProgressTxs` → **Not visible** (concurrent uncommitted)
  /// - Otherwise → **Visible** (committed between min and max)
  ///
  /// @param minActiveOperationTs the minimum timestamp among all in-progress operations,
  ///                             or `currentTimestamp + 1` if no operations are active
  /// @param maxActiveOperationTs the maximum timestamp among all in-progress operations,
  ///                             or `currentTimestamp + 1` if no operations are active
  /// @param inProgressTxs        set of timestamps for all currently in-progress transactions
  public record AtomicOperationsSnapshot(long minActiveOperationTs,
                                         long maxActiveOperationTs,
                                         LongOpenHashSet inProgressTxs) {

    /// Determines whether a record version with the given timestamp is visible to this snapshot.
    ///
    /// This method implements Snapshot Isolation visibility rules. A record is visible if
    /// it was committed before the snapshot was taken and is not part of a concurrent
    /// in-progress transaction.
    ///
    /// Note: Rolled-back entries are kept in the system and handled separately;
    /// this method does not filter them out.
    ///
    /// @param recordTs the timestamp of the record version to check
    /// @return `true` if the record version is visible to this snapshot, `false` otherwise
    public boolean isEntryVisible(long recordTs) {
      //TX is for sure committed, we do keep rolled-back entries
      if (recordTs < minActiveOperationTs) {
        return true;
      }
      //TS is in progress or in the future, so not visible
      if (recordTs >= maxActiveOperationTs) {
        return false;
      }
      //TX is in progress so not visible
      if (minActiveOperationTs == recordTs) {
        return false;
      }
      //TX is in progress so not visible
      return !inProgressTxs.contains(recordTs);
    }
  }

  /// Creates a new atomic operations table.
  ///
  /// @param tableCompactionInterval the number of operations between automatic compaction
  ///                                attempts; higher values reduce compaction overhead but
  ///                                may increase memory usage
  /// @param tsOffset                the initial timestamp offset; operations with timestamps
  ///                                starting from this value can be registered in the table
  public AtomicOperationsTable(final int tableCompactionInterval, final long tsOffset) {
    this.tableCompactionInterval = tableCompactionInterval;
    this.tsOffsets = new long[]{tsOffset};
    //noinspection unchecked
    tables = new CASObjectArray[]{new CASObjectArray<>()};
  }

  /// Creates an immutable snapshot of the current atomic operations table state.
  ///
  /// This method scans all table segments to identify in-progress operations and
  /// captures their timestamps. The resulting snapshot can be used by readers to
  /// determine record visibility under Snapshot Isolation.
  ///
  /// If no operations are currently in progress, the snapshot is configured such that:
  /// - All records with `timestamp <= currentTimestamp` are visible
  /// - All records with `timestamp > currentTimestamp` are not visible
  ///
  /// This method acquires a shared lock and can be called concurrently with other
  /// read operations and status changes, but will block during compaction.
  ///
  /// @param currentTimestamp the current transaction's timestamp, used as a boundary when
  ///                  no other operations are in progress
  /// @return an immutable snapshot containing the min/max active timestamps and
  ///         the set of all in-progress transaction timestamps
  public AtomicOperationsSnapshot snapshotAtomicOperationTableState(long currentTimestamp) {
    var minOp = Long.MAX_VALUE;
    var maxOp = Long.MIN_VALUE;

    var inProgressTs = new LongOpenHashSet();

    compactionLock.sharedLock();
    try {
      for (final var table : tables) {
        final var size = table.size();
        for (var i = 0; i < size; i++) {
          final var operationInformation = table.get(i);

          if (operationInformation.status == AtomicOperationStatus.IN_PROGRESS) {
            inProgressTs.add(operationInformation.operationTs);
            if (operationInformation.operationTs < minOp) {
              minOp = operationInformation.operationTs;
            }
            if (operationInformation.operationTs > maxOp) {
              maxOp = operationInformation.operationTs;
            }
          }
        }
      }

      //There are no active operations, so any operation above currentTimestamp is invisible,
      // and any operation below or equal is visible, we always check if an entry version equals currentTimestamp
      // so a thread be able to read our onw writes.
      if (maxOp == Long.MIN_VALUE) {
        maxOp = currentTimestamp + 1;
        minOp = currentTimestamp + 1;
      }

      return new AtomicOperationsSnapshot(minOp, maxOp, inProgressTs);
    } finally {
      compactionLock.sharedUnlock();
    }
  }

  /// Registers a new operation as in-progress in the table.
  ///
  /// This method must be called when starting a new atomic operation. It records
  /// the operation's timestamp and the WAL segment where it began, making it
  /// visible to snapshot queries.
  ///
  /// @param operationTs the unique timestamp identifying this operation
  /// @param segment     the WAL segment index where this operation started (must be >= 0)
  /// @throws IllegalStateException if the segment is negative or the table slot is already occupied
  public void startOperation(final long operationTs, final long segment) {
    changeOperationStatus(operationTs, null, AtomicOperationStatus.IN_PROGRESS, segment);
  }

  /// Marks an in-progress operation as committed.
  ///
  /// This transitions the operation from `IN_PROGRESS` to `COMMITTED` state.
  /// The operation's changes are now logically visible to new snapshots, but
  /// the WAL segment cannot be truncated until the operation is persisted.
  ///
  /// @param operationTs the timestamp of the operation to commit
  /// @throws IllegalStateException if the operation is not in `IN_PROGRESS` state
  public void commitOperation(final long operationTs) {
    changeOperationStatus(
        operationTs, AtomicOperationStatus.IN_PROGRESS, AtomicOperationStatus.COMMITTED, -1);
  }

  /// Marks an in-progress operation as rolled back.
  ///
  /// This transitions the operation from `IN_PROGRESS` to `ROLLED_BACK` state.
  /// Rolled-back entries are retained in the table until compaction removes them.
  ///
  /// @param operationTs the timestamp of the operation to roll back
  /// @throws IllegalStateException if the operation is not in `IN_PROGRESS` state
  public void rollbackOperation(final long operationTs) {
    changeOperationStatus(
        operationTs, AtomicOperationStatus.IN_PROGRESS, AtomicOperationStatus.ROLLED_BACK, -1);
  }

  /// Marks a committed operation as persisted to durable storage.
  ///
  /// This transitions the operation from `COMMITTED` to `PERSISTED` state.
  /// Once persisted, the operation's WAL segment may be eligible for truncation
  /// (if no earlier operations are still pending).
  ///
  /// @param operationTs the timestamp of the operation to mark as persisted
  /// @throws IllegalStateException if the operation is not in `COMMITTED` state
  public void persistOperation(final long operationTs) {
    changeOperationStatus(
        operationTs, AtomicOperationStatus.COMMITTED, AtomicOperationStatus.PERSISTED, -1);
  }

  /// Returns the WAL segment of the earliest operation still in progress.
  ///
  /// This is used to determine which WAL segments must be retained for
  /// potential rollback of active transactions.
  ///
  /// @return the segment index of the earliest in-progress operation,
  ///         or `-1` if no operations are in progress
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

  /// Returns the WAL segment of the earliest operation not yet persisted.
  ///
  /// This includes operations in both `IN_PROGRESS` and `COMMITTED` states.
  /// Used to determine which WAL segments must be retained for crash recovery.
  ///
  /// @return the segment index of the earliest non-persisted operation,
  ///         or `-1` if all operations are persisted
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

  /// Changes the status of an operation in the table.
  ///
  /// This is the core method for all state transitions. It locates the correct
  /// table segment based on the operation timestamp and performs a CAS update
  /// to change the status atomically.
  ///
  /// May trigger compaction if the number of started operations exceeds the
  /// compaction threshold.
  ///
  /// @param operationTs    the timestamp of the operation to update
  /// @param expectedStatus the expected current status (null for new operations)
  /// @param newStatus      the new status to set
  /// @param segment        the WAL segment (only used when starting new operations)
  /// @throws IllegalStateException if the operation is not found, the expected status
  ///                               doesn't match, or invalid parameters are provided
  private void changeOperationStatus(
      final long operationTs,
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
      var currentOffset = tsOffsets[0];
      var nextOffset = tsOffsets.length > 1 ? tsOffsets[1] : Long.MAX_VALUE;

      while (true) {
        if (currentOffset <= operationTs && operationTs < nextOffset) {
          final var itemIndex = (int) (operationTs - currentOffset);
          if (itemIndex < 0) {
            throw new IllegalStateException("Invalid state of table of atomic operations");
          }

          final var table = tables[currentIndex];
          if (newStatus == AtomicOperationStatus.IN_PROGRESS) {
            table.set(
                itemIndex,
                new OperationInformation(AtomicOperationStatus.IN_PROGRESS, segment, operationTs),
                ATOMIC_OPERATION_STATUS_PLACE_HOLDER);
            operationsStarted.incrementAndGet();
          } else {
            final var currentInformation = table.get(itemIndex);
            if (currentInformation.operationTs != operationTs) {
              throw new IllegalStateException(
                  "Invalid operation TS, expected "
                      + currentInformation.operationTs
                      + " but found "
                      + operationTs);
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
                new OperationInformation(newStatus, currentInformation.segment, operationTs))) {
              throw new IllegalStateException("Invalid state of table of atomic operations");
            }
          }

          break;
        } else {
          currentIndex++;
          if (currentIndex >= tsOffsets.length) {
            throw new IllegalStateException(
                "Invalid state of table of atomic operations, entry for the transaction with TS "
                    + operationTs
                    + " can not be found");
          }

          currentOffset = tsOffsets[currentIndex];
          nextOffset =
              tsOffsets.length > currentIndex + 1 ? tsOffsets[currentIndex + 1] : Long.MAX_VALUE;
        }
      }
    } finally {
      compactionLock.sharedUnlock();
    }
  }

  /// Compacts the table by removing completed (persisted or rolled-back) entries.
  ///
  /// This method acquires an exclusive lock, blocking all other operations.
  /// It performs the following:
  ///
  /// 1. **Prunes completed entries**: Removes `PERSISTED` and `ROLLED_BACK` entries
  ///    from the front of each segment
  /// 2. **Updates offsets**: Adjusts `tsOffsets` to reflect new segment boundaries
  /// 3. **Removes empty segments**: Deletes segments that become empty after pruning
  ///    (always keeping at least one segment)
  ///
  /// Compaction is triggered automatically when
  /// `operationsStarted > lastCompactionOperation + tableCompactionInterval`.
  public void compactTable() {
    compactionLock.exclusiveLock();
    try {
      final var tablesToRemove = new ArrayDeque<Integer>(tables.length);

      var tablesAreFull = true;
      var maxId = Long.MIN_VALUE;

      for (var tableIndex = 0; tableIndex < tables.length; tableIndex++) {
        final var table = tables[tableIndex];
        final var tsOffset = tsOffsets[tableIndex];

        final var newTable = new CASObjectArray<OperationInformation>();
        final var tableSize = table.size();
        var addition = false;

        long newTsOffset = -1;
        for (var i = 0; i < tableSize; i++) {
          final var operationInformation = table.get(i);
          if (!addition) {
            if (operationInformation.status == AtomicOperationStatus.IN_PROGRESS
                || operationInformation.status == AtomicOperationStatus.NOT_STARTED
                || operationInformation.status == AtomicOperationStatus.COMMITTED) {
              addition = true;

              newTsOffset = i + tsOffset;
              newTable.add(operationInformation);
            }
          } else {
            newTable.add(operationInformation);
          }

          if (maxId < tsOffset + i) {
            maxId = i;
          }
        }

        if (newTsOffset < 0) {
          newTsOffset = tsOffset + tableSize;
        }

        this.tables[tableIndex] = newTable;
        this.tsOffsets[tableIndex] = newTsOffset;

        if (newTable.size() == 0) {
          tablesToRemove.push(tableIndex);
        } else {
          tablesAreFull =
              (tablesAreFull || tableIndex == 0) && newTable.size() == tableCompactionInterval;
        }
      }

      if (!tablesToRemove.isEmpty() && tables.length > 1) {
        if (tablesToRemove.size() == tables.length) {
          this.tsOffsets = new long[]{maxId + 1};
          //noinspection unchecked
          this.tables = new CASObjectArray[]{tables[0]};
        } else {
          //noinspection unchecked
          CASObjectArray<OperationInformation>[] newTables =
              new CASObjectArray[this.tables.length - tablesToRemove.size()];
          var newIdOffsets = new long[this.tsOffsets.length - tablesToRemove.size()];

          var firstSrcIndex = 0;
          var firstDestIndex = 0;

          for (final int tableIndex : tablesToRemove) {
            final var len = tableIndex - firstSrcIndex;
            if (len > 0) {
              System.arraycopy(this.tables, firstSrcIndex, newTables, firstDestIndex, len);
              System.arraycopy(this.tsOffsets, firstSrcIndex, newIdOffsets, firstDestIndex, len);
              firstDestIndex += len;
            }
            firstSrcIndex = tableIndex + 1;
          }

          this.tables = newTables;
          this.tsOffsets = newIdOffsets;
        }
      }

      lastCompactionOperation = operationsStarted.get();
    } finally {
      compactionLock.exclusiveUnlock();
    }
  }

  /// Immutable record holding the state of a single atomic operation.
  ///
  /// This is the unit of storage within each table segment. It captures all
  /// information needed to track an operation's lifecycle and determine its
  /// impact on WAL segment retention.
  ///
  /// @param status      the current lifecycle state of the operation
  /// @param segment     the WAL segment index where this operation started;
  ///                    used for determining which segments can be truncated
  /// @param operationTs the unique timestamp identifying this operation;
  ///                    serves as both an identifier and ordering key
  private record OperationInformation(AtomicOperationStatus status, long segment,
                                      long operationTs) {

  }
}
