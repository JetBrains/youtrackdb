package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2AddAllLeafEntriesOp.LeafEntryData;
import com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2.BTreeMVBucketV2AddAllLeafEntriesOp.RidData;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#shrink} on a leaf bucket. Captures the
 * retained entries (indices 0..newSize-1) as structured leaf data. Redo resets the page
 * (freePointer to MAX_PAGE_SIZE_BYTES, size to 0) and re-adds the retained entries.
 */
public final class BTreeMVBucketV2ShrinkLeafEntriesOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_SHRINK_LEAF_ENTRIES_OP;

  private List<LeafEntryData> retainedEntries;

  public BTreeMVBucketV2ShrinkLeafEntriesOp() {
  }

  public BTreeMVBucketV2ShrinkLeafEntriesOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      List<LeafEntryData> retainedEntries) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.retainedEntries = retainedEntries;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var leafEntries =
        new ArrayList<CellBTreeMultiValueV2Bucket.LeafEntry>(retainedEntries.size());
    for (var entry : retainedEntries) {
      var rids = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.RID>(
          entry.values.size());
      for (var ridData : entry.values) {
        rids.add(new RecordId(ridData.collectionId(), ridData.collectionPosition()));
      }
      leafEntries.add(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              entry.key, entry.mId, rids, entry.entriesCount));
    }
    bucket.resetAndAddAll(leafEntries);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public List<LeafEntryData> getRetainedEntries() {
    return retainedEntries;
  }

  @Override
  public int serializedSize() {
    var size = super.serializedSize() + Integer.BYTES; // entry count
    for (var entry : retainedEntries) {
      size += Integer.BYTES + entry.key.length; // keyLen + key
      size += Long.BYTES; // mId
      size += Integer.BYTES; // entriesCount
      size += Integer.BYTES; // values count
      size += entry.values.size() * (Short.BYTES + Long.BYTES); // RIDs
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
      buffer.putLong(entry.mId);
      buffer.putInt(entry.entriesCount);
      buffer.putInt(entry.values.size());
      for (var rid : entry.values) {
        buffer.putShort(rid.collectionId());
        buffer.putLong(rid.collectionPosition());
      }
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
      var mId = buffer.getLong();
      var entriesCount = buffer.getInt();
      var valuesCount = buffer.getInt();
      var values = new ArrayList<RidData>(valuesCount);
      for (var j = 0; j < valuesCount; j++) {
        values.add(new RidData(buffer.getShort(), buffer.getLong()));
      }
      retainedEntries.add(new LeafEntryData(key, mId, entriesCount, values));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2ShrinkLeafEntriesOp that)) {
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
        "retainedLeafEntries="
            + (retainedEntries != null ? retainedEntries.size() : "null"));
  }
}
