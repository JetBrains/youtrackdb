package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#addLeafEntry(int, byte[], byte[])}.
 * Captures the insertion index, serialized key, and serialized value.
 */
public final class BTreeSVBucketV3AddLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_LEAF_ENTRY_OP;

  private int index;
  private byte[] serializedKey;
  private byte[] serializedValue;

  public BTreeSVBucketV3AddLeafEntryOp() {
  }

  public BTreeSVBucketV3AddLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, byte[] serializedKey, byte[] serializedValue) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.serializedKey = serializedKey;
    this.serializedValue = serializedValue;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    var result = bucket.addLeafEntry(index, serializedKey, serializedValue);
    assert result : "addLeafEntry failed during redo — inconsistent page state";
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

  public byte[] getSerializedValue() {
    return serializedValue;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // index
        + Integer.BYTES + serializedKey.length // key length + key
        + Integer.BYTES + serializedValue.length; // value length + value
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(serializedKey.length);
    buffer.put(serializedKey);
    buffer.putInt(serializedValue.length);
    buffer.put(serializedValue);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    var keyLen = buffer.getInt();
    serializedKey = new byte[keyLen];
    buffer.get(serializedKey);
    var valueLen = buffer.getInt();
    serializedValue = new byte[valueLen];
    buffer.get(serializedValue);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3AddLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && Arrays.equals(serializedKey, that.serializedKey)
        && Arrays.equals(serializedValue, that.serializedValue);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + Arrays.hashCode(serializedKey);
    result = 31 * result + Arrays.hashCode(serializedValue);
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", keyLen=" + (serializedKey != null ? serializedKey.length : "null")
        + ", valueLen=" + (serializedValue != null ? serializedValue.length : "null"));
  }
}
