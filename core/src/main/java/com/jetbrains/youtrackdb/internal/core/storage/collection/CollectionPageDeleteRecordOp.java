package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CollectionPage#deleteRecord(int, boolean)}.
 * Captures the position and preserveFreeListPointer flag. The returned record
 * bytes are discarded during redo — only the structural page mutation matters.
 */
public final class CollectionPageDeleteRecordOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.COLLECTION_PAGE_DELETE_RECORD_OP;

  private int position;
  private boolean preserveFreeListPointer;

  public CollectionPageDeleteRecordOp() {
  }

  public CollectionPageDeleteRecordOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int position, boolean preserveFreeListPointer) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.position = position;
    this.preserveFreeListPointer = preserveFreeListPointer;
  }

  @Override
  public void redo(DurablePage page) {
    var collectionPage = new CollectionPage(page.getCacheEntry());
    collectionPage.deleteRecord(position, preserveFreeListPointer);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getPosition() {
    return position;
  }

  public boolean isPreserveFreeListPointer() {
    return preserveFreeListPointer;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + Byte.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(position);
    buffer.put(preserveFreeListPointer ? (byte) 1 : (byte) 0);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    position = buffer.getInt();
    preserveFreeListPointer = buffer.get() != 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CollectionPageDeleteRecordOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return position == that.position
        && preserveFreeListPointer == that.preserveFreeListPointer;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + position;
    result = 31 * result + (preserveFreeListPointer ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return toString("position=" + position
        + ", preserveFreeListPointer=" + preserveFreeListPointer);
  }
}
