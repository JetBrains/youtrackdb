package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#addAll(List)} on a leaf bucket.
 * Captures structured leaf entry data (key, mId, entriesCount, RID values) for each entry.
 */
public final class BTreeMVBucketV2AddAllLeafEntriesOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_ADD_ALL_LEAF_ENTRIES_OP;

  private List<LeafEntryData> entries;

  public BTreeMVBucketV2AddAllLeafEntriesOp() {
  }

  public BTreeMVBucketV2AddAllLeafEntriesOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn,
      List<LeafEntryData> entries) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.entries = entries;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    var leafEntries = new ArrayList<CellBTreeMultiValueV2Bucket.LeafEntry>(entries.size());
    for (var entry : entries) {
      var rids = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.RID>(
          entry.values.size());
      for (var ridData : entry.values) {
        rids.add(new RecordId(ridData.collectionId, ridData.collectionPosition));
      }
      leafEntries.add(
          new CellBTreeMultiValueV2Bucket.LeafEntry(
              entry.key, entry.mId, rids, entry.entriesCount));
    }
    bucket.addAll(leafEntries);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public List<LeafEntryData> getEntries() {
    return entries;
  }

  @Override
  public int serializedSize() {
    var size = super.serializedSize() + Integer.BYTES; // entry count
    for (var entry : entries) {
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
    buffer.putInt(entries.size());
    for (var entry : entries) {
      buffer.putInt(entry.key.length);
      buffer.put(entry.key);
      buffer.putLong(entry.mId);
      buffer.putInt(entry.entriesCount);
      buffer.putInt(entry.values.size());
      for (var rid : entry.values) {
        buffer.putShort(rid.collectionId);
        buffer.putLong(rid.collectionPosition);
      }
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
      var mId = buffer.getLong();
      var entriesCount = buffer.getInt();
      var valuesCount = buffer.getInt();
      var values = new ArrayList<RidData>(valuesCount);
      for (var j = 0; j < valuesCount; j++) {
        values.add(new RidData(buffer.getShort(), buffer.getLong()));
      }
      entries.add(new LeafEntryData(key, mId, entriesCount, values));
    }
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2AddAllLeafEntriesOp that)) {
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
    return toString("leafEntries=" + (entries != null ? entries.size() : "null"));
  }

  // --- Serializable data classes ---

  /**
   * Compact RID representation for serialization — avoids depending on RecordId during
   * deserialization.
   */
  public record RidData(short collectionId, long collectionPosition) {
  }

  /** Serializable leaf entry data capturing all fields needed for redo. */
  public static final class LeafEntryData {

    public final byte[] key;
    public final long mId;
    public final int entriesCount;
    public final List<RidData> values;

    public LeafEntryData(byte[] key, long mId, int entriesCount, List<RidData> values) {
      this.key = key;
      this.mId = mId;
      this.entriesCount = entriesCount;
      this.values = values;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof LeafEntryData that)) {
        return false;
      }
      return mId == that.mId
          && entriesCount == that.entriesCount
          && Arrays.equals(key, that.key)
          && values.equals(that.values);
    }

    @Override
    public int hashCode() {
      var result = Arrays.hashCode(key);
      result = 31 * result + (int) (mId ^ (mId >>> 32));
      result = 31 * result + entriesCount;
      result = 31 * result + values.hashCode();
      return result;
    }
  }
}
