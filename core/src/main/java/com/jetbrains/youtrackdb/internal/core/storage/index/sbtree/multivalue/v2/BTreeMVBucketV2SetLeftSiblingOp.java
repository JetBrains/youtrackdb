package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#setLeftSibling(long)}. Captures the
 * sibling page index and replays the mutation during crash recovery.
 */
public final class BTreeMVBucketV2SetLeftSiblingOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_BUCKET_V2_SET_LEFT_SIBLING_OP;

  private long siblingPageIndex;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2SetLeftSiblingOp() {
  }

  public BTreeMVBucketV2SetLeftSiblingOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long siblingPageIndex) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.siblingPageIndex = siblingPageIndex;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
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
    if (!(o instanceof BTreeMVBucketV2SetLeftSiblingOp that)) {
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
