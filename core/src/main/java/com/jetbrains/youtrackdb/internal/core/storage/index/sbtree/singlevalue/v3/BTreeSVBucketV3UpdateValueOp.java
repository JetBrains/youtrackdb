package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#updateValue(int, byte[], int)}.
 * Captures the entry index, new value bytes, and key length, and replays the value update
 * during crash recovery.
 */
public final class BTreeSVBucketV3UpdateValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_UPDATE_VALUE_OP;

  private int index;
  private byte[] value;
  private int keyLength;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVBucketV3UpdateValueOp() {
  }

  public BTreeSVBucketV3UpdateValueOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int index, byte[] value, int keyLength) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.value = value;
    this.keyLength = keyLength;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    bucket.updateValue(index, value, keyLength);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public byte[] getValue() {
    return value;
  }

  public int getKeyLength() {
    return keyLength;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES + Integer.BYTES + value.length + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(value.length);
    buffer.put(value);
    buffer.putInt(keyLength);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    var valueLen = buffer.getInt();
    value = new byte[valueLen];
    buffer.get(value);
    keyLength = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3UpdateValueOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && keyLength == that.keyLength
        && Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + Arrays.hashCode(value);
    result = 31 * result + keyLength;
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", valueLen=" + (value != null ? value.length : "null")
        + ", keyLength=" + keyLength);
  }
}
