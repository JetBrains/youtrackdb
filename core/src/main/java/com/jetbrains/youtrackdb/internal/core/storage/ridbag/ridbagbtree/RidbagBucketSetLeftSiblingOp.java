package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link Bucket#setLeftSibling(long)}. Captures the new left sibling
 * page index.
 */
public final class RidbagBucketSetLeftSiblingOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.RIDBAG_BUCKET_SET_LEFT_SIBLING_OP;

  private long pageIdx;

  public RidbagBucketSetLeftSiblingOp() {
  }

  public RidbagBucketSetLeftSiblingOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long pageIdx) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.pageIdx = pageIdx;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new Bucket(page.getCacheEntry());
    bucket.setLeftSibling(pageIdx);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getPageIdx() {
    return pageIdx;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(pageIdx);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    pageIdx = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RidbagBucketSetLeftSiblingOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return pageIdx == that.pageIdx;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + Long.hashCode(pageIdx);
    return result;
  }

  @Override
  public String toString() {
    return toString("pageIdx=" + pageIdx);
  }
}
