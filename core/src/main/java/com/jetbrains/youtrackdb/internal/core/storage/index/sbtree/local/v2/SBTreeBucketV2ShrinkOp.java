package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Logical WAL record for {@link SBTreeBucketV2#shrink}. Captures the retained entries (indices
 * 0..newSize-1) as raw byte arrays. Redo resets the page (freePointer to MAX_PAGE_SIZE_BYTES, size
 * to 0) and re-appends the retained entries via {@link SBTreeBucketV2#resetAndAddAll}.
 */
public final class SBTreeBucketV2ShrinkOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_BUCKET_V2_SHRINK_OP;

  private List<byte[]> retainedEntries;

  public SBTreeBucketV2ShrinkOp() {
  }

  public SBTreeBucketV2ShrinkOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      List<byte[]> retainedEntries) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.retainedEntries = retainedEntries;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new SBTreeBucketV2<>(page.getCacheEntry());
    bucket.resetAndAddAll(retainedEntries);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public List<byte[]> getRetainedEntries() {
    return retainedEntries;
  }

  @Override
  public int serializedSize() {
    var size = super.serializedSize() + Integer.BYTES; // entry count
    for (var entry : retainedEntries) {
      size += Integer.BYTES + entry.length; // length + bytes per entry
    }
    return size;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(retainedEntries.size());
    for (var entry : retainedEntries) {
      buffer.putInt(entry.length);
      buffer.put(entry);
    }
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    var count = buffer.getInt();
    retainedEntries = new ArrayList<>(count);
    for (var i = 0; i < count; i++) {
      var len = buffer.getInt();
      var entry = new byte[len];
      buffer.get(entry);
      retainedEntries.add(entry);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof SBTreeBucketV2ShrinkOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    if (retainedEntries.size() != that.retainedEntries.size()) {
      return false;
    }
    for (var i = 0; i < retainedEntries.size(); i++) {
      if (!Arrays.equals(retainedEntries.get(i), that.retainedEntries.get(i))) {
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
      result = 31 * result + Arrays.hashCode(entry);
    }
    return result;
  }

  @Override
  public String toString() {
    return toString(
        "retainedEntries=" + (retainedEntries != null ? retainedEntries.size() : "null"));
  }
}
