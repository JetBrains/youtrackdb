package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CollectionPage#setRecordVersion(int, int)}.
 * Captures position and version. The boolean return value is discarded during redo.
 */
public final class CollectionPageSetRecordVersionOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.COLLECTION_PAGE_SET_RECORD_VERSION_OP;

  private int position;
  private int version;

  public CollectionPageSetRecordVersionOp() {
  }

  public CollectionPageSetRecordVersionOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int position, int version) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.position = position;
    this.version = version;
  }

  @Override
  public void redo(DurablePage page) {
    var collectionPage = new CollectionPage(page.getCacheEntry());
    collectionPage.setRecordVersion(position, version);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getPosition() {
    return position;
  }

  public int getVersion() {
    return version;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(position);
    buffer.putInt(version);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    position = buffer.getInt();
    version = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CollectionPageSetRecordVersionOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return position == that.position && version == that.version;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + position;
    result = 31 * result + version;
    return result;
  }

  @Override
  public String toString() {
    return toString("position=" + position + ", version=" + version);
  }
}
