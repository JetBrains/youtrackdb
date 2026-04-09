package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#addAll(List,
 * com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer)}.
 * Captures the raw entry byte arrays to be appended.
 */
public final class BTreeSVBucketV3AddAllOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_BUCKET_V3_ADD_ALL_OP;

  private List<byte[]> rawEntries;

  public BTreeSVBucketV3AddAllOp() {
  }

  public BTreeSVBucketV3AddAllOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      List<byte[]> rawEntries) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.rawEntries = rawEntries;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    bucket.addAll(rawEntries, null);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public List<byte[]> getRawEntries() {
    return rawEntries;
  }

  @Override
  public int serializedSize() {
    var size = super.serializedSize() + Integer.BYTES; // entry count
    for (var entry : rawEntries) {
      size += Integer.BYTES + entry.length; // length + bytes per entry
    }
    return size;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(rawEntries.size());
    for (var entry : rawEntries) {
      buffer.putInt(entry.length);
      buffer.put(entry);
    }
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    var count = buffer.getInt();
    rawEntries = new ArrayList<>(count);
    for (var i = 0; i < count; i++) {
      var len = buffer.getInt();
      var entry = new byte[len];
      buffer.get(entry);
      rawEntries.add(entry);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3AddAllOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    if (rawEntries.size() != that.rawEntries.size()) {
      return false;
    }
    for (var i = 0; i < rawEntries.size(); i++) {
      if (!Arrays.equals(rawEntries.get(i), that.rawEntries.get(i))) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + rawEntries.size();
    for (var entry : rawEntries) {
      result = 31 * result + Arrays.hashCode(entry);
    }
    return result;
  }

  @Override
  public String toString() {
    return toString("entries=" + (rawEntries != null ? rawEntries.size() : "null"));
  }
}
