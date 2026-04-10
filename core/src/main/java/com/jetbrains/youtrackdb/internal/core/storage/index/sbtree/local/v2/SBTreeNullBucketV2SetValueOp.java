package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link SBTreeNullBucketV2#setValue(byte[],
 * com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer)}.
 * Captures the serialized value bytes. The BinarySerializer parameter is unused (T5).
 */
public final class SBTreeNullBucketV2SetValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_NULL_BUCKET_V2_SET_VALUE_OP;

  private byte[] value;

  public SBTreeNullBucketV2SetValueOp() {
  }

  public SBTreeNullBucketV2SetValueOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, byte[] value) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.value = value;
  }

  @Override
  public void redo(DurablePage page) {
    // BinarySerializer param is unused by setValue — pass null (T5)
    var nullBucket = new SBTreeNullBucketV2<>(page.getCacheEntry());
    nullBucket.setValue(value, null);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public byte[] getValue() {
    return value;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + value.length;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(value.length);
    buffer.put(value);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    var len = buffer.getInt();
    value = new byte[len];
    buffer.get(value);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SBTreeNullBucketV2SetValueOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return Arrays.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + Arrays.hashCode(value);
    return result;
  }

  @Override
  public String toString() {
    return toString("valueLen=" + (value != null ? value.length : "null"));
  }
}
