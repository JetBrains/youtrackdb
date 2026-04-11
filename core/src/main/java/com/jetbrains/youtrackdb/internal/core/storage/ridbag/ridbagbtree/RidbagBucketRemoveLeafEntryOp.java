package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link Bucket#removeLeafEntry(int, int, int)}. Unconditional.
 */
public final class RidbagBucketRemoveLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.RIDBAG_BUCKET_REMOVE_LEAF_ENTRY_OP;

  private int entryIndex;
  private int keySize;
  private int valueSize;

  public RidbagBucketRemoveLeafEntryOp() {
  }

  public RidbagBucketRemoveLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int entryIndex, int keySize, int valueSize) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.entryIndex = entryIndex;
    this.keySize = keySize;
    this.valueSize = valueSize;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new Bucket(page.getCacheEntry());
    bucket.removeLeafEntry(entryIndex, keySize, valueSize);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getEntryIndex() {
    return entryIndex;
  }

  public int getKeySize() {
    return keySize;
  }

  public int getValueSize() {
    return valueSize;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES * 3;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
    buffer.putInt(keySize);
    buffer.putInt(valueSize);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    keySize = buffer.getInt();
    valueSize = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RidbagBucketRemoveLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex
        && keySize == that.keySize
        && valueSize == that.valueSize;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + keySize;
    result = 31 * result + valueSize;
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex
        + ", keySize=" + keySize + ", valueSize=" + valueSize);
  }
}
