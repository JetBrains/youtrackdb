package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for the in-place branch of {@link Bucket#updateValue(int, byte[], int,
 * com.jetbrains.youtrackdb.internal.core.serialization.serializer.binary.BinarySerializerFactory)}.
 * Conditional: registered only when valueSize == value.length (in-place path). The else-branch
 * delegates to removeLeafEntry + addLeafEntry which register their own ops.
 */
public final class RidbagBucketUpdateValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.RIDBAG_BUCKET_UPDATE_VALUE_OP;

  private int index;
  private byte[] value;
  private int keySize;

  public RidbagBucketUpdateValueOp() {
  }

  public RidbagBucketUpdateValueOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int index, byte[] value, int keySize) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.value = value;
    this.keySize = keySize;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new Bucket(page.getCacheEntry());
    bucket.updateValueInPlace(index, value, keySize);
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
        + Integer.BYTES + value.length // value length + data
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
    int valLen = buffer.getInt();
    value = new byte[valLen];
    buffer.get(value);
    keySize = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RidbagBucketUpdateValueOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && keySize == that.keySize
        && Arrays.equals(value, that.value);
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
        + ", valLen=" + (value != null ? value.length : "null")
        + ", keySize=" + keySize);
  }
}
