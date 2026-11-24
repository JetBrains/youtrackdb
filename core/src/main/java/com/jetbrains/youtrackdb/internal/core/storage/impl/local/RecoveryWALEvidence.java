package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import static com.jetbrains.youtrackdb.internal.core.storage.cache.AbstractWriteCache.extractFileId;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitEndRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.AtomicUnitStartRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.FileDeletedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.OperationUnitRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageAllocatedWALRecord;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WriteAheadLog.ProofStatus;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.CASWALPage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.EmptyWALRecord;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Committed file evidence from one retained WAL range, without page-file access.
 * The caller retains the WAL and replays only the returned inclusive bounds.
 */
record RecoveryWALEvidence(LogSequenceNumber start, LogSequenceNumber readableEnd,
    Set<Integer> pageZeroFiles, Map<Integer, LogSequenceNumber> deletions,
    Set<Long> partialLeadingUnits) {

  RecoveryWALEvidence {
    pageZeroFiles = Set.copyOf(pageZeroFiles);
    deletions = Map.copyOf(deletions);
    partialLeadingUnits = Set.copyOf(partialLeadingUnits);
  }

  /**
   * Reads the immutable pre-open extent from start. After an unclean shutdown a stop point
   * ends the range only if later segments contain no readable records with content.
   * maxRecordBytes has the same encoded and expanded size contract as the WAL proof reader.
   */
  static RecoveryWALEvidence collect(WriteAheadLog wal, LogSequenceNumber start,
      boolean uncleanShutdown, int maxRecordBytes) throws ReadFailure {
    var pending = new HashMap<Long, Unit>();
    var allocations = new HashSet<Integer>();
    var deletions = new HashMap<Integer, LogSequenceNumber>();
    var partial = new HashSet<Long>();
    var seenUnits = new HashSet<Long>();
    var beforeGap = new HashSet<Long>();
    LogSequenceNumber last = null;
    LogSequenceNumber stop = null;
    Long previousSegment = null;
    try {
      // Even unavailable coverage must go through the reader so capture errors retain their cause.
      var positions = new ArrayList<LogSequenceNumber>();
      positions.add(start);
      for (var segment : wal.preOpenExtent().segments()) {
        if (segment.segment() > start.getSegment()) {
          positions.add(new LogSequenceNumber(segment.segment(), CASWALPage.RECORDS_OFFSET));
        }
      }
      for (var coverage : positions) {
        if (previousSegment != null && coverage.getSegment() != previousSegment + 1) {
          beforeGap.addAll(seenUnits);
        }
        previousSegment = coverage.getSegment();
        var reader = wal.openSegmentProofReader(coverage, maxRecordBytes);
        while (true) {
          // One record preserves the exact consumed prefix even if a decoder throws from next().
          var batch = reader.next(1);
          for (var record : batch.records()) {
            var position = record.getLsn();
            if (stop != null) {
              if (!(record instanceof EmptyWALRecord)) {
                throw new ReadFailure(position, last,
                    new IOException("WAL record with content after stop point " + stop));
              }
              continue;
            }
            if (record instanceof OperationUnitRecord operation) {
              long id = operation.getOperationUnitId();
              if (beforeGap.contains(id)) {
                throw new ReadFailure(position, last,
                    new IOException("Atomic unit " + id + " crosses a WAL segment gap"));
              }
              seenUnits.add(id);
            }
            last = position;
            if (record instanceof AtomicUnitStartRecord begin) {
              var unit = new Unit();
              unit.started = true;
              pending.put(begin.getOperationUnitId(), unit);
            } else if (record instanceof AtomicUnitEndRecord finish) {
              var unit = pending.remove(finish.getOperationUnitId());
              if (unit == null || !unit.started) {
                partial.add(finish.getOperationUnitId());
              }
              if (unit != null) {
                if (unit.started) {
                  allocations.addAll(unit.allocations);
                }
                unit.deletions.forEach((id, lsn) -> deletions.merge(id, lsn,
                    (left, right) -> left.compareTo(right) >= 0 ? left : right));
              }
            } else if (record instanceof OperationUnitRecord operation) {
              var unit =
                  pending.computeIfAbsent(operation.getOperationUnitId(), ignored -> new Unit());
              if (record instanceof PageAllocatedWALRecord allocation
                  && allocation.getPageIndex() == 0) {
                unit.allocations.add(extractFileId(allocation.getFileId()));
              } else if (record instanceof FileDeletedWALRecord deletion) {
                unit.deletions.put(extractFileId(deletion.getFileId()), position);
              }
            }
          }
          // Always inspect the terminal status, including batches that also returned records.
          if (batch.status() == ProofStatus.REACHED_END) {
            break;
          }
          if (uncleanShutdown && (batch.status() == ProofStatus.INCOMPLETE_RECORD
              || (batch.status() == ProofStatus.BROKEN_PAGE && !batch.readablePageFollows()))) {
            if (stop == null) {
              stop = batch.position();
            }
            break;
          }
          if (batch.status() != ProofStatus.MORE) {
            var cause = reader.failureCause();
            if (cause == null) {
              cause = batch.error();
            }
            if (cause == null) {
              cause = new IOException("WAL evidence stopped with " + batch.status()
                  + " at " + batch.position());
            }
            throw new ReadFailure(batch.position(), last, cause);
          }
        }
      }
      return new RecoveryWALEvidence(start, last, allocations, deletions, partial);
    } catch (ReadFailure failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw new ReadFailure(null, last, failure);
    }
  }

  private static final class Unit {
    private boolean started;
    private final Set<Integer> allocations = new HashSet<>();
    private final Map<Integer, LogSequenceNumber> deletions = new HashMap<>();
  }

  /** No partial evidence escapes a failed scan. The original cause remains attached. */
  static final class ReadFailure extends IOException {
    private final LogSequenceNumber failedPosition;
    private final LogSequenceNumber lastReadablePosition;

    private ReadFailure(LogSequenceNumber failedPosition, LogSequenceNumber lastReadablePosition,
        Throwable cause) {
      super("Cannot read WAL evidence at " + failedPosition + ", last readable record "
          + lastReadablePosition, cause);
      this.failedPosition = failedPosition;
      this.lastReadablePosition = lastReadablePosition;
    }

    LogSequenceNumber failedPosition() {
      return failedPosition;
    }

    LogSequenceNumber lastReadablePosition() {
      return lastReadablePosition;
    }
  }
}
