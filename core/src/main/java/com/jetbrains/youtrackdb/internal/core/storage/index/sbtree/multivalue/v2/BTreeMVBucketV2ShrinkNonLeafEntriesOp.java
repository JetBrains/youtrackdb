package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2AddAllNonLeafEntriesOp.NonLeafEntryData;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#shrink} on a non-leaf bucket. Captures
 * the retained entries (indices 0..newSize-1) as structured non-leaf data. Redo resets the page
 * (freePointer to MAX_PAGE_SIZE_BYTES, size to 0) and re-adds the retained entries.
 */
public final class BTreeMVBucketV2ShrinkNonLeafEntriesOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_NON_LEAF_ENTRIES_OP;

  private List<NonLeafEntryData> retainedEntries;

  public BTreeMVBucketV2ShrinkNonLeafEntriesOp() {
  }

  public BTreeMVBucketV2ShrinkNonLeafEntriesOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      List<NonLeafEntryData> retainedEntries) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.retainedEntries = retainedEntries;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var nonLeafEntries =
        new ArrayList<CellBTreeMultiValueV2Bucket.NonLeafEntry>(retainedEntries.size());
    for (var entry : retainedEntries) {
      nonLeafEntries.add(
          new CellBTreeMultiValueV2Bucket.NonLeafEntry(
              entry.key, entry.leftChild, entry.rightChild));
    }
    bucket.resetAndAddAll(nonLeafEntries);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public List<NonLeafEntryData> getRetainedEntries() {
    return retainedEntries;
  }

  @Override
  public int serializedSize() {
    var size = super.serializedSize() + Integer.BYTES; // entry count
    for (var entry : retainedEntries) {
      size += Integer.BYTES + entry.key.length; // keyLen + key
      size += Integer.BYTES; // leftChild
      size += Integer.BYTES; // rightChild
    }
    return size;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(retainedEntries.size());
    for (var entry : retainedEntries) {
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
    retainedEntries = new ArrayList<>(count);
    for (var i = 0; i < count; i++) {
      var keyLen = buffer.getInt();
      var key = new byte[keyLen];
      buffer.get(key);
      var leftChild = buffer.getInt();
      var rightChild = buffer.getInt();
      retainedEntries.add(new NonLeafEntryData(key, leftChild, rightChild));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2ShrinkNonLeafEntriesOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    if (retainedEntries.size() != that.retainedEntries.size()) {
      return false;
    }
    for (var i = 0; i < retainedEntries.size(); i++) {
      if (!retainedEntries.get(i).equals(that.retainedEntries.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + retainedEntries.size();
    for (var entry : retainedEntries) {
      result = 31 * result + entry.hashCode();
    }
    return result;
  }

  @Override
  public String toString() {
    return toString(
        "retainedNonLeafEntries="
            + (retainedEntries != null ? retainedEntries.size() : "null"));
  }
}
