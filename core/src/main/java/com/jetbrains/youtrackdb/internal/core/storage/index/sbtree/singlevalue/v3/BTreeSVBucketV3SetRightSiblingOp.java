package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#setRightSibling(long)}.
 * Captures the sibling page index and replays the mutation during crash recovery.
 */
public final class BTreeSVBucketV3SetRightSiblingOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_SET_RIGHT_SIBLING_OP;

  private long siblingPageIndex;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVBucketV3SetRightSiblingOp() {
  }

  public BTreeSVBucketV3SetRightSiblingOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long siblingPageIndex) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.siblingPageIndex = siblingPageIndex;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    bucket.setRightSibling(siblingPageIndex);
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
    if (!(o instanceof BTreeSVBucketV3SetRightSiblingOp that)) {
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
