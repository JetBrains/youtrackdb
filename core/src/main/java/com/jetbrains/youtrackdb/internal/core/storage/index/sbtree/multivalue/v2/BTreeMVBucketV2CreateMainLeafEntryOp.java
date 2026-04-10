package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#createMainLeafEntry(int, byte[],
 * com.jetbrains.youtrackdb.internal.core.db.record.record.RID, long)}. Captures the insertion
 * index, serialized key, RID components (collectionId/collectionPosition — -1/-1 when value is
 * null), and mId.
 */
public final class BTreeMVBucketV2CreateMainLeafEntryOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_CREATE_MAIN_LEAF_ENTRY_OP;

  private int index;
  private byte[] serializedKey;
  private short collectionId;
  private long collectionPosition;
  private long mId;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2CreateMainLeafEntryOp() {
  }

  public BTreeMVBucketV2CreateMainLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, byte[] serializedKey, short collectionId, long collectionPosition, long mId) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.serializedKey = serializedKey;
    this.collectionId = collectionId;
    this.collectionPosition = collectionPosition;
    this.mId = mId;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var rid = collectionId >= 0 ? new RecordId(collectionId, collectionPosition) : null;
    var result = bucket.createMainLeafEntry(index, serializedKey, rid, mId);
    assert result : "createMainLeafEntry failed during redo — inconsistent page state";
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public byte[] getSerializedKey() {
    return serializedKey;
  }

  public short getCollectionId() {
    return collectionId;
  }

  public long getCollectionPosition() {
    return collectionPosition;
  }

  public long getMId() {
    return mId;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // index
        + Integer.BYTES + serializedKey.length // key length + key
        + Short.BYTES // collectionId
        + Long.BYTES // collectionPosition
        + Long.BYTES; // mId
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(serializedKey.length);
    buffer.put(serializedKey);
    buffer.putShort(collectionId);
    buffer.putLong(collectionPosition);
    buffer.putLong(mId);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    var keyLen = buffer.getInt();
    serializedKey = new byte[keyLen];
    buffer.get(serializedKey);
    collectionId = buffer.getShort();
    collectionPosition = buffer.getLong();
    mId = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2CreateMainLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && collectionId == that.collectionId
        && collectionPosition == that.collectionPosition
        && mId == that.mId
        && Arrays.equals(serializedKey, that.serializedKey);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + Arrays.hashCode(serializedKey);
    result = 31 * result + collectionId;
    result = 31 * result + (int) (collectionPosition ^ (collectionPosition >>> 32));
    result = 31 * result + (int) (mId ^ (mId >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", keyLen=" + (serializedKey != null ? serializedKey.length : "null")
        + ", collectionId=" + collectionId
        + ", mId=" + mId);
  }
}
