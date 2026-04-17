package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#removeLeafEntry(int, byte[])}.
 * Captures the entry index and serialized key (needed for entry size calculation during removal).
 */
public final class BTreeSVBucketV3RemoveLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_REMOVE_LEAF_ENTRY_OP;

  private int entryIndex;
  private byte[] key;

  public BTreeSVBucketV3RemoveLeafEntryOp() {
  }

  public BTreeSVBucketV3RemoveLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int entryIndex, byte[] key) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.entryIndex = entryIndex;
    this.key = key;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    bucket.removeLeafEntry(entryIndex, key);
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

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // entryIndex
        + Integer.BYTES + key.length; // key length + key
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
    buffer.putInt(key.length);
    buffer.put(key);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    var keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3RemoveLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex && Arrays.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + Arrays.hashCode(key);
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex
        + ", keyLen=" + (key != null ? key.length : "null"));
  }
}
