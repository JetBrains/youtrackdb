package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link SBTreeBucketV2#updateValue(int, byte[], int)}. Captures the
 * entry index, new value bytes, and key size (used to skip past the key in the page layout).
 */
public final class SBTreeBucketV2UpdateValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_BUCKET_V2_UPDATE_VALUE_OP;

  private int index;
  private byte[] value;
  private int keySize;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public SBTreeBucketV2UpdateValueOp() {
  }

  public SBTreeBucketV2UpdateValueOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, byte[] value, int keySize) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.value = value;
    this.keySize = keySize;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new SBTreeBucketV2<>(page.getCacheEntry());
    bucket.updateValue(index, value, keySize);
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

  public int getKeySize() {
    return keySize;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // index
        + Integer.BYTES + value.length // value length + value
        + Integer.BYTES; // keySize
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(value.length);
    buffer.put(value);
    buffer.putInt(keySize);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    var valueLen = buffer.getInt();
    value = new byte[valueLen];
    buffer.get(value);
    keySize = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SBTreeBucketV2UpdateValueOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && Arrays.equals(value, that.value)
        && keySize == that.keySize;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + Arrays.hashCode(value);
    result = 31 * result + keySize;
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", valueLen=" + (value != null ? value.length : "null")
        + ", keySize=" + keySize);
  }
}
