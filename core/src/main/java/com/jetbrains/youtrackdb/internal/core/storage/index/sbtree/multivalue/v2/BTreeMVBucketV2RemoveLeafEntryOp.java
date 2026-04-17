package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for the success paths of
 * {@link CellBTreeMultiValueV2Bucket#removeLeafEntry(int,
 * com.jetbrains.youtrackdb.internal.core.db.record.record.RID)}. Captures the entry index and
 * RID components. Registered only when the removal succeeds (result >= 0), not on not-found (-1).
 */
public final class BTreeMVBucketV2RemoveLeafEntryOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_LEAF_ENTRY_OP;

  private int entryIndex;
  private short collectionId;
  private long collectionPosition;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2RemoveLeafEntryOp() {
  }

  public BTreeMVBucketV2RemoveLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int entryIndex, short collectionId, long collectionPosition) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    assert entryIndex >= 0 : "entryIndex must be non-negative, got " + entryIndex;
    this.entryIndex = entryIndex;
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var result =
        bucket.removeLeafEntry(entryIndex, new RecordId(collectionId, collectionPosition));
    assert result >= 0 : "removeLeafEntry failed during redo, got " + result;
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getEntryIndex() {
    return entryIndex;
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
    buffer.putInt(entryIndex);
    buffer.putShort(collectionId);
    buffer.putLong(collectionPosition);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    assert entryIndex >= 0
        : "Deserialized entryIndex must be non-negative, got " + entryIndex;
    collectionId = buffer.getShort();
    collectionPosition = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2RemoveLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex
        && collectionId == that.collectionId
        && collectionPosition == that.collectionPosition;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + collectionId;
    result = 31 * result + (int) (collectionPosition ^ (collectionPosition >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex
        + ", collectionId=" + collectionId
        + ", collectionPosition=" + collectionPosition);
  }
}
