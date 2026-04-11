package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link Bucket#addNonLeafEntry(int, int, int, byte[], boolean)}.
 * Conditional: registered only when addNonLeafEntry returns true. Child pointers are int (4 bytes).
 */
public final class RidbagBucketAddNonLeafEntryOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.RIDBAG_BUCKET_ADD_NON_LEAF_ENTRY_OP;

  private int index;
  private int leftChild;
  private int rightChild;
  private byte[] key;
  private boolean updateNeighbors;

  public RidbagBucketAddNonLeafEntryOp() {
  }

  public RidbagBucketAddNonLeafEntryOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn,
      int index, int leftChild, int rightChild, byte[] key,
      boolean updateNeighbors) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.index = index;
    this.leftChild = leftChild;
    this.rightChild = rightChild;
    this.key = key;
    this.updateNeighbors = updateNeighbors;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new Bucket(page.getCacheEntry());
    assert bucket.addNonLeafEntry(index, leftChild, rightChild, key, updateNeighbors);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getIndex() {
    return index;
  }

  public int getLeftChild() {
    return leftChild;
  }

  public int getRightChild() {
    return rightChild;
  }

  public byte[] getKey() {
    return key;
  }

  public boolean isUpdateNeighbors() {
    return updateNeighbors;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Integer.BYTES * 3 // index, leftChild, rightChild
        + Integer.BYTES + key.length // key length + data
        + Byte.BYTES; // updateNeighbors
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(index);
    buffer.putInt(leftChild);
    buffer.putInt(rightChild);
    buffer.putInt(key.length);
    buffer.put(key);
    buffer.put((byte) (updateNeighbors ? 1 : 0));
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    index = buffer.getInt();
    leftChild = buffer.getInt();
    rightChild = buffer.getInt();
    int keyLen = buffer.getInt();
    key = new byte[keyLen];
    buffer.get(key);
    updateNeighbors = buffer.get() != 0;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof RidbagBucketAddNonLeafEntryOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return index == that.index
        && leftChild == that.leftChild
        && rightChild == that.rightChild
        && updateNeighbors == that.updateNeighbors
        && Arrays.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + index;
    result = 31 * result + leftChild;
    result = 31 * result + rightChild;
    result = 31 * result + Arrays.hashCode(key);
    result = 31 * result + Boolean.hashCode(updateNeighbors);
    return result;
  }

  @Override
  public String toString() {
    return toString("index=" + index + ", left=" + leftChild + ", right=" + rightChild
        + ", keyLen=" + (key != null ? key.length : "null")
        + ", updateNeighbors=" + updateNeighbors);
  }
}
