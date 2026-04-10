package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for the success path of
 * {@link CellBTreeMultiValueV2Bucket#appendNewLeafEntry(int,
 * com.jetbrains.youtrackdb.internal.core.db.record.record.RID)}. Captures the entry index and
 * RID components. Registered only on success (return -1), not on full (-2) or threshold (mId).
 */
public final class BTreeMVBucketV2AppendNewLeafEntryOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_APPEND_NEW_LEAF_ENTRY_OP;

  private int index;
  private short collectionId;
  private long collectionPosition;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2AppendNewLeafEntryOp() {
  }

  public BTreeMVBucketV2AppendNewLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, short collectionId, long collectionPosition) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var result = bucket.appendNewLeafEntry(index, new RecordId(collectionId, collectionPosition));
    assert result == -1 : "appendNewLeafEntry failed during redo, got " + result;
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public short getCollectionId() {
    return collectionId;
  }

  public long getCollectionPosition() {
    return collectionPosition;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + Short.BYTES + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putShort(collectionId);
    buffer.putLong(collectionPosition);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    collectionId = buffer.getShort();
    collectionPosition = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2AppendNewLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && collectionId == that.collectionId
        && collectionPosition == that.collectionPosition;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + collectionId;
    result = 31 * result + (int) (collectionPosition ^ (collectionPosition >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", collectionId=" + collectionId
        + ", collectionPosition=" + collectionPosition);
  }
}
