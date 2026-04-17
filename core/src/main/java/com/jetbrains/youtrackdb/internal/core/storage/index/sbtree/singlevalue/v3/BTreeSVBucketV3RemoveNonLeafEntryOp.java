package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#removeNonLeafEntry(int, byte[],
 * boolean)}. Captures the entry index, serialized key, and the removeLeftChildPointer flag.
 * Registration happens only in the byte[] overload (not the serializer convenience overload)
 * to avoid double-registration per T5-5/R1/R15.
 */
public final class BTreeSVBucketV3RemoveNonLeafEntryOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_SV_BUCKET_V3_REMOVE_NON_LEAF_ENTRY_OP;

  private int entryIndex;
  private byte[] key;
  private boolean removeLeftChildPointer;

  public BTreeSVBucketV3RemoveNonLeafEntryOp() {
  }

  public BTreeSVBucketV3RemoveNonLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int entryIndex, byte[] key, boolean removeLeftChildPointer) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.entryIndex = entryIndex;
    this.key = key;
    this.removeLeftChildPointer = removeLeftChildPointer;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    bucket.removeNonLeafEntry(entryIndex, key, removeLeftChildPointer);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getEntryIndex() {
    return entryIndex;
  }

  public byte[] getKey() {
    return key;
  }

  public boolean isRemoveLeftChildPointer() {
    return removeLeftChildPointer;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // entryIndex
        + Integer.BYTES + key.length // key length + key
        + Byte.BYTES; // removeLeftChildPointer
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
    buffer.putInt(key.length);
    buffer.put(key);
    buffer.put((byte) (removeLeftChildPointer ? 1 : 0));
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    var keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);
    removeLeftChildPointer = buffer.get() != 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3RemoveNonLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex
        && removeLeftChildPointer == that.removeLeftChildPointer
        && Arrays.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + Arrays.hashCode(key);
    result = 31 * result + (removeLeftChildPointer ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex
        + ", keyLen=" + (key != null ? key.length : "null")
        + ", removeLeft=" + removeLeftChildPointer);
  }
}
