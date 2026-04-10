package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2NullBucket#addValue(
 * com.jetbrains.youtrackdb.internal.core.id.RID)} when the value is added to the embedded list
 * (embeddedSize &lt; EMBEDDED_RIDS_BOUNDARY). Not registered when threshold is exceeded.
 */
public final class BTreeMVNullBucketV2AddValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_ADD_VALUE_OP;

  private short collectionId;
  private long collectionPosition;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVNullBucketV2AddValueOp() {
  }

  public BTreeMVNullBucketV2AddValueOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, short collectionId, long collectionPosition) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  @Override
  public void redo(DurablePage page) {
    var nullBucket = new CellBTreeMultiValueV2NullBucket(page.getCacheEntry());
    var result = nullBucket.addValue(new RecordId(collectionId, collectionPosition));
    assert result == -1 : "addValue failed during redo — embedded list full, got mId=" + result;
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
    if (!(o instanceof BTreeMVNullBucketV2AddValueOp that)) {
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
