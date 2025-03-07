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

package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrack.db.internal.core.storage.impl.local.CheckpointRequestListener;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationMetadata;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @since 6/25/14
 */
public class MemoryWriteAheadLog extends AbstractWriteAheadLog {

  private final AtomicInteger nextPosition = new AtomicInteger();
  private final AtomicInteger nextOperationId = new AtomicInteger();

  @Override
  public LogSequenceNumber begin() {
    throw new UnsupportedOperationException("Operation not supported for in memory storage.");
  }

  @Override
  public LogSequenceNumber end() {
    return new LogSequenceNumber(-1, -1);
  }

  @Override
  public void flush() {
  }

  @Override
  public LogSequenceNumber logAtomicOperationStartRecord(
      boolean isRollbackSupported, long unitId) {
    return log(new AtomicUnitStartRecord(isRollbackSupported, unitId));
  }

  public LogSequenceNumber logAtomicOperationStartRecord(
      final boolean isRollbackSupported, final long unitId, byte[] metadata) {
    final AtomicUnitStartMetadataRecord record =
        new AtomicUnitStartMetadataRecord(isRollbackSupported, unitId, metadata);
    return log(record);
  }

  @Override
  public LogSequenceNumber logAtomicOperationEndRecord(
      long operationUnitId,
      boolean rollback,
      LogSequenceNumber startLsn,
      Map<String, AtomicOperationMetadata<?>> atomicOperationMetadata) {
    return log(new AtomicUnitEndRecord(operationUnitId, rollback, atomicOperationMetadata));
  }

  @Override
  public LogSequenceNumber log(WriteableWALRecord record) {
    final LogSequenceNumber lsn = new LogSequenceNumber(0, nextPosition.incrementAndGet());
    record.setLsn(lsn);

    return lsn;
  }

  @Override
  public void close() throws IOException {
  }

  @Override
  public void close(boolean flush) throws IOException {
  }

  @Override
  public void delete() throws IOException {
  }

  @Override
  public List<WriteableWALRecord> read(LogSequenceNumber lsn, int limit) throws IOException {
    throw new UnsupportedOperationException("Operation not supported for in memory storage.");
  }

  @Override
  public List<WriteableWALRecord> next(LogSequenceNumber lsn, int limit) {
    throw new UnsupportedOperationException("Operation not supported for in memory storage.");
  }

  @Override
  public LogSequenceNumber getFlushedLsn() {
    throw new UnsupportedOperationException("Operation not supported for in memory storage.");
  }

  @Override
  public boolean cutTill(LogSequenceNumber lsn) {
    return false;
  }

  @Override
  public void addCheckpointListener(CheckpointRequestListener listener) {
  }

  @Override
  public void removeCheckpointListener(CheckpointRequestListener listener) {
  }

  @Override
  public void moveLsnAfter(LogSequenceNumber lsn) {
  }

  @Override
  public void addCutTillLimit(LogSequenceNumber lsn) {
  }

  @Override
  public void removeCutTillLimit(LogSequenceNumber lsn) {
  }

  @Override
  public File[] nonActiveSegments(long fromSegment) {
    return new File[0];
  }

  @Override
  public long[] nonActiveSegments() {
    return new long[0];
  }

  @Override
  public long activeSegment() {
    return 0;
  }

  @Override
  public LogSequenceNumber begin(long segmentId) {
    throw new UnsupportedOperationException("Operation not supported for in memory storage.");
  }

  @Override
  public boolean cutAllSegmentsSmallerThan(long segmentId) {
    return false;
  }

  @Override
  public void addEventAt(LogSequenceNumber lsn, Runnable event) {
    event.run();
  }

  @Override
  public boolean appendNewSegment() {
    return false;
  }
}
