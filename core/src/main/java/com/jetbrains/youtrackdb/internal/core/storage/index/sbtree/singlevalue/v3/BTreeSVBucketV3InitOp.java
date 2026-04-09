package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#init(boolean)}. Captures the
 * isLeaf flag and replays the full bucket initialization during crash recovery.
 */
public final class BTreeSVBucketV3InitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_INIT_OP;

  private boolean isLeaf;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVBucketV3InitOp() {
  }

  public BTreeSVBucketV3InitOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, boolean isLeaf) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.isLeaf = isLeaf;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    bucket.init(isLeaf);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public boolean isLeaf() {
    return isLeaf;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Byte.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.put((byte) (isLeaf ? 1 : 0));
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    isLeaf = buffer.get() != 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3InitOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return isLeaf == that.isLeaf;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (isLeaf ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return toString("isLeaf=" + isLeaf);
  }
}
