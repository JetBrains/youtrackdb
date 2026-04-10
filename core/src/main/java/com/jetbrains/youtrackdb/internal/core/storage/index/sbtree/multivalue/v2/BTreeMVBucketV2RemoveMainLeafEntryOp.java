package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#removeMainLeafEntry(int, int)}.
 * Captures the entry index and key size. Unconditional — the method always mutates.
 */
public final class BTreeMVBucketV2RemoveMainLeafEntryOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_MAIN_LEAF_ENTRY_OP;

  private int entryIndex;
  private int keySize;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2RemoveMainLeafEntryOp() {
  }

  public BTreeMVBucketV2RemoveMainLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int entryIndex, int keySize) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    assert entryIndex >= 0 : "entryIndex must be non-negative, got " + entryIndex;
    assert keySize > 0 : "keySize must be positive, got " + keySize;
    this.entryIndex = entryIndex;
    this.keySize = keySize;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    bucket.removeMainLeafEntry(entryIndex, keySize);
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

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
    buffer.putInt(keySize);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    assert entryIndex >= 0
        : "Deserialized entryIndex must be non-negative, got " + entryIndex;
    keySize = buffer.getInt();
    assert keySize > 0
        : "Deserialized keySize must be positive, got " + keySize;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2RemoveMainLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex && keySize == that.keySize;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + keySize;
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex + ", keySize=" + keySize);
  }
}
