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

import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * @since 26.04.13
 */
public class UpdatePageRecord extends AbstractPageWALRecord {

  private WALChanges changes;

  private LogSequenceNumber initialLsn;

  @SuppressWarnings("WeakerAccess")
  public UpdatePageRecord() {
  }

  public UpdatePageRecord(
      final long pageIndex,
      final long fileId,
      final long operationUnitId,
      final WALChanges changes,
      final LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId);

    this.changes = changes;
    this.initialLsn = initialLsn;
  }

  public WALChanges getChanges() {
    return changes;
  }

  public LogSequenceNumber getInitialLsn() {
    return initialLsn;
  }

  @Override
  public int serializedSize() {
    var serializedSize = super.serializedSize();
    serializedSize += changes.serializedSize();

    serializedSize += Integer.BYTES + Long.BYTES;

    return serializedSize;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);

    changes.toStream(buffer);
    buffer.putLong(initialLsn.getSegment());
    buffer.putInt(initialLsn.getPosition());
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);

    changes = new WALPageChangesPortion();
    changes.fromStream(buffer);

    final var segment = buffer.getLong();
    final var position = buffer.getInt();
    initialLsn = new LogSequenceNumber(segment, position);
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    final var that = (UpdatePageRecord) o;

    if (logSequenceNumber == null && that.logSequenceNumber == null) {
      return true;
    }
    if (logSequenceNumber == null) {
      return false;
    }

    if (that.logSequenceNumber == null) {
      return false;
    }

    return Objects.equals(logSequenceNumber, that.logSequenceNumber);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (logSequenceNumber != null ? logSequenceNumber.hashCode() : 0);
    return result;
  }

  @Override
  public int getId() {
    return WALRecordTypes.UPDATE_PAGE_RECORD;
  }
}
