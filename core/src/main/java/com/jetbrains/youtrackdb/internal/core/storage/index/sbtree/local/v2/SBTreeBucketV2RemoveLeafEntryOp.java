package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link SBTreeBucketV2#removeLeafEntry(int, byte[], byte[])}. Captures
 * the entry index, old raw key, and old raw value needed for the positional compaction logic.
 */
public final class SBTreeBucketV2RemoveLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_BUCKET_V2_REMOVE_LEAF_ENTRY_OP;

  private int entryIndex;
  private byte[] oldRawKey;
  private byte[] oldRawValue;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public SBTreeBucketV2RemoveLeafEntryOp() {
  }

  public SBTreeBucketV2RemoveLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int entryIndex, byte[] oldRawKey, byte[] oldRawValue) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.entryIndex = entryIndex;
    this.oldRawKey = oldRawKey;
    this.oldRawValue = oldRawValue;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new SBTreeBucketV2<>(page.getCacheEntry());
    bucket.removeLeafEntry(entryIndex, oldRawKey, oldRawValue);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getEntryIndex() {
    return entryIndex;
  }

  public byte[] getOldRawKey() {
    return oldRawKey;
  }

  public byte[] getOldRawValue() {
    return oldRawValue;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES
        + Integer.BYTES + oldRawKey.length
        + Integer.BYTES + oldRawValue.length;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
    buffer.putInt(oldRawKey.length);
    buffer.put(oldRawKey);
    buffer.putInt(oldRawValue.length);
    buffer.put(oldRawValue);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    var keyLen = buffer.getInt();
    oldRawKey = new byte[keyLen];
    buffer.get(oldRawKey);
    var valueLen = buffer.getInt();
    oldRawValue = new byte[valueLen];
    buffer.get(oldRawValue);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SBTreeBucketV2RemoveLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex
        && Arrays.equals(oldRawKey, that.oldRawKey)
        && Arrays.equals(oldRawValue, that.oldRawValue);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + Arrays.hashCode(oldRawKey);
    result = 31 * result + Arrays.hashCode(oldRawValue);
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex
        + ", keyLen=" + (oldRawKey != null ? oldRawKey.length : "null")
        + ", valueLen=" + (oldRawValue != null ? oldRawValue.length : "null"));
  }
}
