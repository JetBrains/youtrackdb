package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link Bucket#addLeafEntry(int, byte[], byte[])}. Conditional:
 * registered only when addLeafEntry returns true (space was available).
 */
public final class RidbagBucketAddLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.RIDBAG_BUCKET_ADD_LEAF_ENTRY_OP;

  private int index;
  private byte[] serializedKey;
  private byte[] serializedValue;

  public RidbagBucketAddLeafEntryOp() {
  }

  public RidbagBucketAddLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn,
      int index, byte[] serializedKey, byte[] serializedValue) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.serializedKey = serializedKey;
    this.serializedValue = serializedValue;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new Bucket(page.getCacheEntry());
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
        + Integer.BYTES + serializedKey.length
        + Integer.BYTES + serializedValue.length;
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
    int keyLen = buffer.getInt();
    serializedKey = new byte[keyLen];
    buffer.get(serializedKey);
    int valLen = buffer.getInt();
    serializedValue = new byte[valLen];
    buffer.get(serializedValue);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RidbagBucketAddLeafEntryOp that)) {
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
        + ", valLen=" + (serializedValue != null ? serializedValue.length : "null"));
  }
}
