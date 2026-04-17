package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for
 * {@link CellBTreeMultiValueV2Bucket#removeNonLeafEntry(int, byte[], int)}. Captures the entry
 * index, key bytes, and prevChild for neighbor child-pointer adjustment. Unconditional — the
 * method always mutates.
 */
public final class BTreeMVBucketV2RemoveNonLeafEntryOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_REMOVE_NON_LEAF_ENTRY_OP;

  private int entryIndex;
  private byte[] key;
  private int prevChild;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2RemoveNonLeafEntryOp() {
  }

  public BTreeMVBucketV2RemoveNonLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int entryIndex, byte[] key, int prevChild) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    assert entryIndex >= 0 : "entryIndex must be non-negative, got " + entryIndex;
    this.entryIndex = entryIndex;
    this.key = key;
    this.prevChild = prevChild;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    bucket.removeNonLeafEntry(entryIndex, key, prevChild);
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

  public int getPrevChild() {
    return prevChild;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // entryIndex
        + Integer.BYTES + key.length // key length + key
        + Integer.BYTES; // prevChild
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
    buffer.putInt(key.length);
    buffer.put(key);
    buffer.putInt(prevChild);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    assert entryIndex >= 0
        : "Deserialized entryIndex must be non-negative, got " + entryIndex;
    var keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);
    prevChild = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2RemoveNonLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex
        && prevChild == that.prevChild
        && Arrays.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    result = 31 * result + Arrays.hashCode(key);
    result = 31 * result + prevChild;
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex
        + ", keyLen=" + (key != null ? key.length : "null")
        + ", prevChild=" + prevChild);
  }
}
