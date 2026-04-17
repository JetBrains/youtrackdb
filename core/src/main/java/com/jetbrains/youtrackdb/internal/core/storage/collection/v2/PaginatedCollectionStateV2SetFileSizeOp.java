package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link PaginatedCollectionStateV2#setFileSize(int)}. Captures the
 * file size (number of allocated data pages) and replays the mutation during crash recovery.
 */
public final class PaginatedCollectionStateV2SetFileSizeOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.PAGINATED_COLLECTION_STATE_V2_SET_FILE_SIZE_OP;

  private int size;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public PaginatedCollectionStateV2SetFileSizeOp() {
  }

  public PaginatedCollectionStateV2SetFileSizeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int size) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.size = size;
  }

  @Override
  public void redo(DurablePage page) {
    // Construct the concrete page type from the underlying cache entry.
    // During recovery, changes == null so setFileSize writes directly to the buffer.
    // The instanceof CacheEntryChanges check in setFileSize will be false,
    // so no PageOperation is re-registered (D4 redo suppression).
    var statePage = new PaginatedCollectionStateV2(page.getCacheEntry());
    statePage.setFileSize(size);
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
    if (!(o instanceof PaginatedCollectionStateV2SetFileSizeOp that)) {
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
