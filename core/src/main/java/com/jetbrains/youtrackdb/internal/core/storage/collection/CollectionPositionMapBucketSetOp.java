package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.collection.CollectionPositionMapBucket.PositionEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CollectionPositionMapBucket#set(int, PositionEntry)}.
 * Captures index and the full PositionEntry fields (pageIndex, recordPosition, recordVersion).
 */
public final class CollectionPositionMapBucketSetOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.POSITION_MAP_BUCKET_SET_OP;

  private int index;
  private long entryPageIndex;
  private int recordPosition;
  private long recordVersion;

  public CollectionPositionMapBucketSetOp() {
  }

  public CollectionPositionMapBucketSetOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, long entryPageIndex, int recordPosition, long recordVersion) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.entryPageIndex = entryPageIndex;
    this.recordPosition = recordPosition;
    this.recordVersion = recordVersion;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CollectionPositionMapBucket(page.getCacheEntry());
    bucket.set(index, new PositionEntry(entryPageIndex, recordPosition, recordVersion));
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public long getEntryPageIndex() {
    return entryPageIndex;
  }

  public int getRecordPosition() {
    return recordPosition;
  }

  public long getRecordVersion() {
    return recordVersion;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // index
        + Long.BYTES // entryPageIndex
        + Integer.BYTES // recordPosition
        + Long.BYTES; // recordVersion
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putLong(entryPageIndex);
    buffer.putInt(recordPosition);
    buffer.putLong(recordVersion);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    entryPageIndex = buffer.getLong();
    recordPosition = buffer.getInt();
    recordVersion = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CollectionPositionMapBucketSetOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && entryPageIndex == that.entryPageIndex
        && recordPosition == that.recordPosition
        && recordVersion == that.recordVersion;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + (int) (entryPageIndex ^ (entryPageIndex >>> 32));
    result = 31 * result + recordPosition;
    result = 31 * result + (int) (recordVersion ^ (recordVersion >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index + ", entryPageIndex=" + entryPageIndex
        + ", recordPosition=" + recordPosition + ", recordVersion=" + recordVersion);
  }
}
