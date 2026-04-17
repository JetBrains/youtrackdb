package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#updateKey}. Captures the entry
 * index, new key bytes, and the old key size (needed for the page compaction path when key
 * sizes differ). The old key size is captured at registration time via
 * {@code getObjectSizeInDirectMemory} before the mutation is applied. Redo calls
 * {@link CellBTreeSingleValueBucketV3#updateKeyWithOldKeySize(int, byte[], int)} which
 * replicates updateKey's logic without a serializer. Per T5-4/R3/R11.
 */
public final class BTreeSVBucketV3UpdateKeyOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_UPDATE_KEY_OP;

  private int entryIndex;
  private byte[] newKey;
  private int oldKeySize;

  public BTreeSVBucketV3UpdateKeyOp() {
  }

  public BTreeSVBucketV3UpdateKeyOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int entryIndex, byte[] newKey, int oldKeySize) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.entryIndex = entryIndex;
    this.newKey = newKey;
    this.oldKeySize = oldKeySize;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    var result = bucket.updateKeyWithOldKeySize(entryIndex, newKey, oldKeySize);
    assert result : "updateKeyWithOldKeySize failed during redo — inconsistent page state";
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getEntryIndex() {
    return entryIndex;
  }

  public byte[] getNewKey() {
    return newKey;
  }

  public int getOldKeySize() {
    return oldKeySize;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // entryIndex
        + Integer.BYTES + newKey.length // key length + key
        + Integer.BYTES; // oldKeySize
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
    buffer.putInt(newKey.length);
    buffer.put(newKey);
    buffer.putInt(oldKeySize);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    var keyLen = buffer.getInt();
    newKey = new byte[keyLen];
    buffer.get(newKey);
    oldKeySize = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3UpdateKeyOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex
        && oldKeySize == that.oldKeySize
        && Arrays.equals(newKey, that.newKey);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + Arrays.hashCode(newKey);
    result = 31 * result + oldKeySize;
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex
        + ", newKeyLen=" + (newKey != null ? newKey.length : "null")
        + ", oldKeySize=" + oldKeySize);
  }
}
