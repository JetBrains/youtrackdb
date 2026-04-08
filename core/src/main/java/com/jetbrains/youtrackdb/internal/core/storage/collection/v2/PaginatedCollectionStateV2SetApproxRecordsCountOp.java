package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link PaginatedCollectionStateV2#setApproximateRecordsCount(long)}.
 * Captures the approximate record count and replays the mutation during crash recovery.
 */
public final class PaginatedCollectionStateV2SetApproxRecordsCountOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_APPROX_RECORDS_COUNT_OP;

  private long count;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public PaginatedCollectionStateV2SetApproxRecordsCountOp() {
  }

  public PaginatedCollectionStateV2SetApproxRecordsCountOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long count) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.count = count;
  }

  @Override
  public void redo(DurablePage page) {
    var statePage = new PaginatedCollectionStateV2(page.getCacheEntry());
    statePage.setApproximateRecordsCount(count);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getCount() {
    return count;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(count);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    count = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PaginatedCollectionStateV2SetApproxRecordsCountOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return count == that.count;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (int) (count ^ (count >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("count=" + count);
  }
}
