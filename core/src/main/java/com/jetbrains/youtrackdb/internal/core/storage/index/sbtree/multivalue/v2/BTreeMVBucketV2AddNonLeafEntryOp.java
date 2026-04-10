package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for the success path of
 * {@link CellBTreeMultiValueV2Bucket#addNonLeafEntry(int, byte[], int, int, boolean)}. Captures
 * the insertion index, serialized key, left/right child pointers, and updateNeighbors flag.
 * Registered only when the entry fits (success), not on overflow.
 */
public final class BTreeMVBucketV2AddNonLeafEntryOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_NON_LEAF_ENTRY_OP;

  private int index;
  private byte[] serializedKey;
  private int leftChild;
  private int rightChild;
  private boolean updateNeighbors;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2AddNonLeafEntryOp() {
  }

  public BTreeMVBucketV2AddNonLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      int index, byte[] serializedKey, int leftChild, int rightChild,
      boolean updateNeighbors) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.serializedKey = serializedKey;
    this.leftChild = leftChild;
    this.rightChild = rightChild;
    this.updateNeighbors = updateNeighbors;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var result = bucket.addNonLeafEntry(index, serializedKey, leftChild, rightChild,
        updateNeighbors);
    assert result : "addNonLeafEntry failed during redo — inconsistent page state";
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public byte[] getSerializedKey() {
    return serializedKey;
  }

  public int getLeftChild() {
    return leftChild;
  }

  public int getRightChild() {
    return rightChild;
  }

  public boolean isUpdateNeighbors() {
    return updateNeighbors;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES // index
        + Integer.BYTES + serializedKey.length // key length + key
        + Integer.BYTES // leftChild
        + Integer.BYTES // rightChild
        + Byte.BYTES; // updateNeighbors
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(serializedKey.length);
    buffer.put(serializedKey);
    buffer.putInt(leftChild);
    buffer.putInt(rightChild);
    buffer.put((byte) (updateNeighbors ? 1 : 0));
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    var keyLen = buffer.getInt();
    serializedKey = new byte[keyLen];
    buffer.get(serializedKey);
    leftChild = buffer.getInt();
    rightChild = buffer.getInt();
    updateNeighbors = buffer.get() != 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2AddNonLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && leftChild == that.leftChild
        && rightChild == that.rightChild
        && updateNeighbors == that.updateNeighbors
        && Arrays.equals(serializedKey, that.serializedKey);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + Arrays.hashCode(serializedKey);
    result = 31 * result + leftChild;
    result = 31 * result + rightChild;
    result = 31 * result + (updateNeighbors ? 1 : 0);
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index
        + ", keyLen=" + (serializedKey != null ? serializedKey.length : "null")
        + ", leftChild=" + leftChild
        + ", rightChild=" + rightChild
        + ", updateNeighbors=" + updateNeighbors);
  }
}
