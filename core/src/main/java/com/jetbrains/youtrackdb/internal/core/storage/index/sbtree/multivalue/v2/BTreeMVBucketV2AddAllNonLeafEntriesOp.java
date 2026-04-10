package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#addAll(List)} on a non-leaf bucket.
 * Captures structured non-leaf entry data (key, leftChild, rightChild) for each entry.
 */
public final class BTreeMVBucketV2AddAllNonLeafEntriesOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_NON_LEAF_ENTRIES_OP;

  private List<NonLeafEntryData> entries;

  public BTreeMVBucketV2AddAllNonLeafEntriesOp() {
  }

  public BTreeMVBucketV2AddAllNonLeafEntriesOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      List<NonLeafEntryData> entries) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.entries = entries;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var nonLeafEntries =
        new ArrayList<CellBTreeMultiValueV2Bucket.NonLeafEntry>(entries.size());
    for (var entry : entries) {
      nonLeafEntries.add(
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              entry.key, entry.leftChild, entry.rightChild));
    }
    bucket.addAll(nonLeafEntries);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public List<NonLeafEntryData> getEntries() {
    return entries;
  }

  @Override
  public int serializedSize() {
    var size = super.serializedSize() + Integer.BYTES; // entry count
    for (var entry : entries) {
      size += Integer.BYTES + entry.key.length; // keyLen + key
      size += Integer.BYTES; // leftChild
      size += Integer.BYTES; // rightChild
    }
    return size;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entries.size());
    for (var entry : entries) {
      buffer.putInt(entry.key.length);
      buffer.put(entry.key);
      buffer.putInt(entry.leftChild);
      buffer.putInt(entry.rightChild);
    }
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    var count = buffer.getInt();
    entries = new ArrayList<>(count);
    for (var i = 0; i < count; i++) {
      var keyLen = buffer.getInt();
      var key = new byte[keyLen];
      buffer.get(key);
      var leftChild = buffer.getInt();
      var rightChild = buffer.getInt();
      entries.add(new NonLeafEntryData(key, leftChild, rightChild));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2AddAllNonLeafEntriesOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    if (entries.size() != that.entries.size()) {
      return false;
    }
    for (var i = 0; i < entries.size(); i++) {
      if (!entries.get(i).equals(that.entries.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entries.size();
    for (var entry : entries) {
      result = 31 * result + entry.hashCode();
    }
    return result;
  }

  @Override
  public String toString() {
    return toString("nonLeafEntries=" + (entries != null ? entries.size() : "null"));
  }

  /** Serializable non-leaf entry data capturing all fields needed for redo. */
  public static final class NonLeafEntryData {

    public final byte[] key;
    public final int leftChild;
    public final int rightChild;

    public NonLeafEntryData(byte[] key, int leftChild, int rightChild) {
      this.key = key;
      this.leftChild = leftChild;
      this.rightChild = rightChild;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof NonLeafEntryData that)) {
        return false;
      }
      return leftChild == that.leftChild
          && rightChild == that.rightChild
          && Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode() {
      var result = Arrays.hashCode(key);
      result = 31 * result + leftChild;
      result = 31 * result + rightChild;
      return result;
    }
  }
}
