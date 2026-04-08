/*
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import java.nio.ByteBuffer;

/**
 * Abstract base class for physiological (page-level logical) WAL records. Each concrete subclass
 * captures the parameters of a single mutation on a specific page (e.g., "add leaf entry at
 * index I"). During recovery, {@link #redo(DurablePage)} replays the mutation directly on the
 * page buffer.
 *
 * <p>Inherits {@code pageIndex}, {@code fileId}, and {@code operationUnitId} from
 * {@link AbstractPageWALRecord}. Adds {@code initialLsn} — the page LSN before this operation
 * was applied. Recovery uses it as a CAS check to detect unexpected page state (see D5 in the
 * architecture notes).
 *
 * <p>Serialization layout (after parent fields):
 * <pre>
 *   [8 bytes] initialLsn.segment (long)
 *   [4 bytes] initialLsn.position (int)
 *   ... subclass-specific fields ...
 * </pre>
 */
public abstract class PageOperation extends AbstractPageWALRecord {

  private LogSequenceNumber initialLsn;

  protected PageOperation() {
  }

  protected PageOperation(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId);
    this.initialLsn = initialLsn;
  }

  /**
   * Returns the page LSN captured before this operation was applied. Used during recovery as a
   * CAS check — if the on-disk page LSN does not match, recovery logs a warning (the page state
   * is unexpected, but recovery proceeds).
   */
  public LogSequenceNumber getInitialLsn() {
    return initialLsn;
  }

  /**
   * Replays this operation on the given page during crash recovery. The page is constructed with
   * {@code changes == null} (direct buffer access), so all mutations go straight to the page
   * buffer. Implementations must call the same DurablePage subclass methods used during normal
   * operation — single source of truth for page layout.
   *
   * @param page the page to apply this operation to, with direct buffer access (no WAL overlay)
   */
  public abstract void redo(DurablePage page);

  @Override
  public int serializedSize() {
    return super.serializedSize() + LongSerializer.LONG_SIZE + IntegerSerializer.INT_SIZE;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(initialLsn.getSegment());
    buffer.putInt(initialLsn.getPosition());
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    final var segment = buffer.getLong();
    final var position = buffer.getInt();
    initialLsn = new LogSequenceNumber(segment, position);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PageOperation that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    if (initialLsn == null) {
      return that.initialLsn == null;
    }
    return initialLsn.equals(that.initialLsn);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (initialLsn != null ? initialLsn.hashCode() : 0);
    return result;
  }

  @Override
  public String toString() {
    return toString("initialLsn=" + initialLsn);
  }
}
