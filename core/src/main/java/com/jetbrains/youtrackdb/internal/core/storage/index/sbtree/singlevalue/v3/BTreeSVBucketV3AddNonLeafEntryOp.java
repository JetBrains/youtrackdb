package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#addNonLeafEntry(int, int, int,
 * byte[])}. Captures the insertion index, child pointers, and serialized key.
 */
public final class BTreeSVBucketV3AddNonLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_NON_LEAF_ENTRY_OP;

  private int index;
  private int leftChildIndex;
  private int newRightChildIndex;
  private byte[] key;

  public BTreeSVBucketV3AddNonLeafEntryOp() {
  }

  public BTreeSVBucketV3AddNonLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, int leftChildIndex, int newRightChildIndex, byte[] key) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.leftChildIndex = leftChildIndex;
    this.newRightChildIndex = newRightChildIndex;
    this.key = key;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    var result = bucket.addNonLeafEntry(index, leftChildIndex, newRightChildIndex, key);
    assert result : "addNonLeafEntry failed during redo — inconsistent page state";
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public int getLeftChildIndex() {
    return leftChildIndex;
  }

  public int getNewRightChildIndex() {
    return newRightChildIndex;
  }

  public byte[] getKey() {
    return key;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // index
        + Integer.BYTES // leftChildIndex
        + Integer.BYTES // newRightChildIndex
        + Integer.BYTES + key.length; // key length + key
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(leftChildIndex);
    buffer.putInt(newRightChildIndex);
    buffer.putInt(key.length);
    buffer.put(key);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    leftChildIndex = buffer.getInt();
    newRightChildIndex = buffer.getInt();
    var keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3AddNonLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && leftChildIndex == that.leftChildIndex
        && newRightChildIndex == that.newRightChildIndex
        && Arrays.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + leftChildIndex;
    result = 31 * result + newRightChildIndex;
    result = 31 * result + Arrays.hashCode(key);
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", leftChild=" + leftChildIndex
        + ", rightChild=" + newRightChildIndex
        + ", keyLen=" + (key != null ? key.length : "null"));
  }
}
