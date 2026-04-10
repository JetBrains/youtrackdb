package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link SBTreeBucketV2#setLeftSibling(long)}. Captures the new left
 * sibling page index.
 */
public final class SBTreeBucketV2SetLeftSiblingOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_BUCKET_V2_SET_LEFT_SIBLING_OP;

  private long siblingPageIndex;

  public SBTreeBucketV2SetLeftSiblingOp() {
  }

  public SBTreeBucketV2SetLeftSiblingOp(
      long targetPageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long siblingPageIndex) {
    super(targetPageIndex, fileId, operationUnitId, initialLsn);
    this.siblingPageIndex = siblingPageIndex;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new SBTreeBucketV2<>(page.getCacheEntry());
    bucket.setLeftSibling(siblingPageIndex);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getSiblingPageIndex() {
    return siblingPageIndex;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(siblingPageIndex);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    siblingPageIndex = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SBTreeBucketV2SetLeftSiblingOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return siblingPageIndex == that.siblingPageIndex;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (int) (siblingPageIndex ^ (siblingPageIndex >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("siblingPageIndex=" + siblingPageIndex);
  }
}
