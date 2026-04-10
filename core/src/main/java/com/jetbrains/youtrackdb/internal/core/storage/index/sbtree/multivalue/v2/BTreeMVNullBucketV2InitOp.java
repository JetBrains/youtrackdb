package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2NullBucket#init(long)}. Sets mId, embedded
 * RIDs size to 0, and total size to 0.
 */
public final class BTreeMVNullBucketV2InitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INIT_OP;

  private long mId;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVNullBucketV2InitOp() {
  }

  public BTreeMVNullBucketV2InitOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long mId) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.mId = mId;
  }

  @Override
  public void redo(DurablePage page) {
    var nullBucket = new CellBTreeMultiValueV2NullBucket(page.getCacheEntry());
    nullBucket.init(mId);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getMId() {
    return mId;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(mId);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    mId = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVNullBucketV2InitOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return mId == that.mId;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (int) (mId ^ (mId >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("mId=" + mId);
  }
}
