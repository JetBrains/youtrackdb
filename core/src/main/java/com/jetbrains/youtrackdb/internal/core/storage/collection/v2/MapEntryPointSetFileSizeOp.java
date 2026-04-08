package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link MapEntryPoint#setFileSize(int)}. Captures the file size
 * (number of bucket pages in use) and replays the mutation during crash recovery.
 */
public final class MapEntryPointSetFileSizeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.MAP_ENTRY_POINT_SET_FILE_SIZE_OP;

  private int size;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public MapEntryPointSetFileSizeOp() {
  }

  public MapEntryPointSetFileSizeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int size) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.size = size;
  }

  @Override
  public void redo(DurablePage page) {
    // During redo, changes == null so setFileSize writes directly to the buffer.
    // The instanceof CacheEntryChanges check will be false — D4 redo suppression.
    var entryPoint = new MapEntryPoint(page.getCacheEntry());
    entryPoint.setFileSize(size);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getSize() {
    return size;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(size);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    size = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MapEntryPointSetFileSizeOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return size == that.size;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + size;
    return result;
  }

  @Override
  public String toString() {
    return toString("size=" + size);
  }
}
