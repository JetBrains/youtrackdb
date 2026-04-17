package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeSingleValueV3NullBucket#setValue(
 * com.jetbrains.youtrackdb.internal.core.db.record.record.RID)}. Captures the collection ID
 * and collection position to replay the mutation during crash recovery.
 */
public final class BTreeSVNullBucketV3SetValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_SET_VALUE_OP;

  private short collectionId;
  private long collectionPosition;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVNullBucketV3SetValueOp() {
  }

  public BTreeSVNullBucketV3SetValueOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, short collectionId, long collectionPosition) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  @Override
  public void redo(DurablePage page) {
    var nullBucket = new CellBTreeSingleValueV3NullBucket(page.getCacheEntry());
    nullBucket.setValue(new RecordId(collectionId, collectionPosition));
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public short getCollectionId() {
    return collectionId;
  }

  public long getCollectionPosition() {
    return collectionPosition;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Short.BYTES + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putShort(collectionId);
    buffer.putLong(collectionPosition);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    collectionId = buffer.getShort();
    collectionPosition = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVNullBucketV3SetValueOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return collectionId == that.collectionId
        && collectionPosition == that.collectionPosition;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + collectionId;
    result = 31 * result + (int) (collectionPosition ^ (collectionPosition >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("collectionId=" + collectionId
        + ", collectionPosition=" + collectionPosition);
  }
}
