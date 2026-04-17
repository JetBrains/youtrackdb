package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CollectionPositionMapBucket#updateVersion(int, long)}.
 * Captures index and recordVersion.
 */
public final class CollectionPositionMapBucketUpdateVersionOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.POSITION_MAP_BUCKET_UPDATE_VERSION_OP;

  private int index;
  private long recordVersion;

  public CollectionPositionMapBucketUpdateVersionOp() {
  }

  public CollectionPositionMapBucketUpdateVersionOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int index, long recordVersion) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.recordVersion = recordVersion;
  }

  @Override
  public void redo(DurablePage page) {
    new CollectionPositionMapBucket(page.getCacheEntry()).updateVersion(index, recordVersion);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public long getRecordVersion() {
    return recordVersion;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putLong(recordVersion);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    recordVersion = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CollectionPositionMapBucketUpdateVersionOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index && recordVersion == that.recordVersion;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + (int) (recordVersion ^ (recordVersion >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index + ", recordVersion=" + recordVersion);
  }
}
