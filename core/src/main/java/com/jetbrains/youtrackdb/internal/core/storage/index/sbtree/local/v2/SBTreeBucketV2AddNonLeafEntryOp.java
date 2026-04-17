package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link SBTreeBucketV2#addNonLeafEntry(int, byte[], long, long,
 * boolean)}. Captures index, key, left/right child pointers (long, T2), and updateNeighbours
 * flag. Registered only on the success path (T7).
 */
public final class SBTreeBucketV2AddNonLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP;

  private int index;
  private byte[] key;
  private long leftChild;
  private long rightChild;
  private boolean updateNeighbours;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public SBTreeBucketV2AddNonLeafEntryOp() {
  }

  public SBTreeBucketV2AddNonLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, byte[] key, long leftChild, long rightChild, boolean updateNeighbours) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.key = key;
    this.leftChild = leftChild;
    this.rightChild = rightChild;
    this.updateNeighbours = updateNeighbours;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new SBTreeBucketV2<>(page.getCacheEntry());
    var result = bucket.addNonLeafEntry(index, key, leftChild, rightChild, updateNeighbours);
    assert result : "addNonLeafEntry failed during redo — inconsistent page state";
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public byte[] getKey() {
    return key;
  }

  public long getLeftChild() {
    return leftChild;
  }

  public long getRightChild() {
    return rightChild;
  }

  public boolean isUpdateNeighbours() {
    return updateNeighbours;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // index
        + Integer.BYTES + key.length // key length + key
        + Long.BYTES // leftChild (T2: long, 8 bytes)
        + Long.BYTES // rightChild (T2: long, 8 bytes)
        + Byte.BYTES; // updateNeighbours
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(key.length);
    buffer.put(key);
    buffer.putLong(leftChild);
    buffer.putLong(rightChild);
    buffer.put((byte) (updateNeighbours ? 1 : 0));
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    var keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);
    leftChild = buffer.getLong();
    rightChild = buffer.getLong();
    updateNeighbours = buffer.get() != 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SBTreeBucketV2AddNonLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && Arrays.equals(key, that.key)
        && leftChild == that.leftChild
        && rightChild == that.rightChild
        && updateNeighbours == that.updateNeighbours;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + Arrays.hashCode(key);
    result = 31 * result + (int) (leftChild ^ (leftChild >>> 32));
    result = 31 * result + (int) (rightChild ^ (rightChild >>> 32));
    result = 31 * result + (updateNeighbours ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", keyLen=" + (key != null ? key.length : "null")
        + ", leftChild=" + leftChild
        + ", rightChild=" + rightChild
        + ", updateNeighbours=" + updateNeighbours);
  }
}
