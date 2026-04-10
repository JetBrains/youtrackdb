package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link SBTreeBucketV2#setTreeSize(long)}. Captures the new tree size.
 */
public final class SBTreeBucketV2SetTreeSizeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_BUCKET_V2_SET_TREE_SIZE_OP;

  private long size;

  public SBTreeBucketV2SetTreeSizeOp() {
  }

  public SBTreeBucketV2SetTreeSizeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long size) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.size = size;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new SBTreeBucketV2<>(page.getCacheEntry());
    bucket.setTreeSize(size);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getSize() {
    return size;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(size);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    size = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SBTreeBucketV2SetTreeSizeOp that)) {
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
    result = 31 * result + (int) (size ^ (size >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("size=" + size);
  }
}
