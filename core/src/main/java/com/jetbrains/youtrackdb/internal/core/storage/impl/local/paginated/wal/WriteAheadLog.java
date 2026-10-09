/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.CheckpointRequestListener;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.io.File;
import java.io.IOException;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Interface for the write-ahead log used to ensure crash recovery and data durability.
 *
 * @since 6/25/14
 */
public interface WriteAheadLog extends AutoCloseable {

  String MASTER_RECORD_EXTENSION = ".wmr";
  String WAL_SEGMENT_EXTENSION = ".wal";

  LogSequenceNumber begin();

  LogSequenceNumber begin(long segmentId) throws IOException;

  LogSequenceNumber end();

  void flush();

  LogSequenceNumber logAtomicOperationStartRecord(boolean isRollbackSupported, long unitId)
      throws IOException;

  LogSequenceNumber log(WriteableWALRecord record) throws IOException;

  @Override
  void close() throws IOException;

  void close(boolean flush) throws IOException;

  void delete() throws IOException;

  List<WriteableWALRecord> read(LogSequenceNumber lsn, int limit) throws IOException;

  List<WriteableWALRecord> next(LogSequenceNumber lsn, int limit) throws IOException;

  /**
   * Original segment IDs and exclusive canonical-file byte bounds, captured before this open writes.
   * A failed capture retains the ID with a zero bound and makes proof reading fail terminally.
   */
  PreOpenExtent preOpenExtent();

  record SegmentExtent(long segment, long bytes) {
  }

  record PreOpenExtent(List<SegmentExtent> segments) {
    public PreOpenExtent {
      segments = List.copyOf(segments);
    }
  }

  /**
   * Reads inclusively from a valid record start, including a preflight's RECORDS_OFFSET coverage.
   * Only the immutable pre-open extent participates. The single-threaded reader holds no cut
   * protection. Removal during the scan fails the proof. Each batch has at most maxRecords records,
   * each with encoded content and compressed expanded content bounded by maxRecordBytes.
   * Oversized content fails before allocation. Page-operation decoders outside the wal package may
   * still allocate from nested lengths inside checksum-valid records. A resulting Error latches
   * terminal failure before it propagates. Encryption admission errors also latch and propagate as
   * in the legacy reader. Channels are released after each batch. A caller must consume batches
   * until a terminal status. An empty batch is not proof of end.
   */
  ProofReader openProofReader(LogSequenceNumber coverage, int maxRecordBytes);

  interface ProofReader {
    ProofBatch next(int maxRecords);
  }

  /**
   * Only REACHED_END certifies completion. IO_ERROR also rejects a pre-open inventory with
   * multiple paths for one numeric segment ID, regardless of coverage or enumeration order.
   * Its cause identifies the ambiguous segment. Proof paths and bounds use the canonical filenames
   * used by recovery, regardless of enumerated spelling. Any canonical file missing at capture
   * gives MISSING_SEGMENT. Other capture failures give IO_ERROR with their cause. Both reject the
   * whole admitted inventory before emitting records, even below coverage. A file appearing later
   * cannot repair a capture failure. Successfully captured files that vanish give SEGMENT_VANISHED.
   * Constructor filename admission is independent of proof capture failures.
   */
  enum ProofStatus {
    MORE, REACHED_END, BROKEN_PAGE, MISSING_SEGMENT, SEGMENT_VANISHED, COVERAGE_NOT_RETAINED, INVALID_RECORD, INCOMPLETE_RECORD, RECORD_TOO_LARGE, IO_ERROR, UNAVAILABLE
  }

  /**
   * position identifies the terminal stop, or is null when proof reading is unavailable.
   * Missing coverage, including an empty disk extent, echoes the requested coverage position.
   * A zero-byte segment is a broken page at position (segment, 0).
   * readablePageFollows is meaningful only for BROKEN_PAGE. False means every later physical page
   * in the required extent was checked. Shutdown trust policy belongs to the caller, not this API.
   * IO_ERROR carries its cause. Terminal readers repeat their status with no further records.
   */
  record ProofBatch(List<WriteableWALRecord> records, ProofStatus status,
      @Nullable LogSequenceNumber position, boolean readablePageFollows,
      @Nullable IOException error) {
    public ProofBatch {
      records = List.copyOf(records);
    }
  }

  LogSequenceNumber getFlushedLsn();

  /**
   * Cut WAL content till passed in value of LSN at maximum in many cases smaller portion of WAL may
   * be cut. If value of LSN is bigger than values provided in
   * {@link #addCutTillLimit(LogSequenceNumber)} then "protected" part of WAL will be preserved for
   * sure.
   *
   * @param lsn Maximum value of LSN till WAL will be cut.
   * @return <code>true</code> if some portion of WAL will be cut and <code>false</code> if WAL left
   * untouched.
   */
  boolean cutTill(LogSequenceNumber lsn) throws IOException;

  boolean cutAllSegmentsSmallerThan(long segmentId) throws IOException;

  /**
   * Predicts segment removal without changing WAL state. Reads the current segment, registered
   * retention limits, written-up-to position and retained segments under the cut's locks. Releases
   * those locks before returning. The removal decision uses the cut's same clamps and no-op test.
   * Coverage is the first retained record start, including when the requested segment is absent.
   * If no inventoried segment remains at or above the clamped boundary, coverage is the first
   * record start of the written-up-to segment, whose inventory publication can lag its writer.
   * Closed disk WALs and empty disk inventories throw IllegalStateException. A concurrent segment
   * inventory change can also throw java.util.NoSuchElementException. Both indicate an unavailable
   * prediction, not a no-op result. Memory WAL always predicts no removal and returns null coverage.
   *
   * <p>The caller fixes the returned effective boundary and passes that boundary, not the original
   * request, to its later cut. Changes to retention limits can then only lower that cut or make it
   * a no-op. Removing a limit must not raise the fixed boundary. This prediction does not reserve
   * segments or replace serialization between checkpoint capture, publication and cutting.
   */
  CutPreflight preflightCut(long segmentId) throws IOException;

  record CutPreflight(boolean removesSegments, long effectiveBoundary,
      @Nullable LogSequenceNumber coverageLsn) {
  }

  void addCheckpointListener(CheckpointRequestListener listener);

  void removeCheckpointListener(CheckpointRequestListener listener);

  /**
   * Next LSN generated by WAL will be bigger than passed in value.
   */
  @SuppressWarnings("unused")
  void moveLsnAfter(LogSequenceNumber lsn) throws IOException;

  /**
   * Adds LSN after which WAL log should be preserved. It is possible to add many such LSNs smallest
   * value among them will be used to limit value of LSN after which WAL may be cut.
   *
   * @param lsn LSN after which cut of the WAL is not allowed.
   * @see #removeCutTillLimit(LogSequenceNumber)
   * @see #cutTill(LogSequenceNumber)
   */
  void addCutTillLimit(LogSequenceNumber lsn);

  /**
   * Removes LSN after which WAL log should be preserved. It is possible to add many such LSNs
   * smallest value among them will be used to limit value of LSN after which WAL may be cut.
   *
   * @param lsn LSN after which cut of the WAL is not allowed.
   * @see #removeCutTillLimit(LogSequenceNumber)
   * @see #cutTill(LogSequenceNumber)
   */
  void removeCutTillLimit(LogSequenceNumber lsn);

  File[] nonActiveSegments(long fromSegment);

  long[] nonActiveSegments();

  long activeSegment();

  /**
   * Adds the event to fire when this write ahead log instances reaches the given LSN. The thread on
   * which the event will be fired is unspecified, the event may be even fired synchronously before
   * this method returns. Avoid running long tasks in the event handler since this may degrade the
   * performance of this write ahead log and/or its event managing component. The exact LSN, up to
   * which this write ahead log is actually grown, may differ from the event's LSN at the moment of
   * invocation. But it's guarantied that the write ahead log's LSN will be larger than or equal to
   * the event's LSN. In other words, the event invocation may be postponed, exact timings depend on
   * implementation details of this write ahead log.
   *
   * @param lsn   the LSN to fire at.
   * @param event the event to fire.
   */
  void addEventAt(LogSequenceNumber lsn, Runnable event);

  /**
   * Adds new segment so all subsequent log entries will be added to this new segment. New segment
   * can not be appended if:
   *
   * <ol>
   *   <li>WAL is empty
   *   <li>There last segment in WAL is empty.
   * </ol>
   * <p>
   * Despite of the fact that WAL segment will not be appended, method call still will reach its
   * main target, all subsequent log records will have segment number higher than previously logged
   * records. But to inform user that segment is not added result of success of failure of this
   * method will be returned.
   *
   * @return <code>true</code> if new segment is added, and <code>false</code> otherwise.
   */
  boolean appendNewSegment();
}
