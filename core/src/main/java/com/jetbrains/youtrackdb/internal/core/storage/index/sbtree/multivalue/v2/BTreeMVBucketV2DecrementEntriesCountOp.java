package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#decrementEntriesCount(int)}. Captures
 * the entry index and replays the entries count decrement during crash recovery.
 */
public final class BTreeMVBucketV2DecrementEntriesCountOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_MV_BUCKET_V2_DECREMENT_ENTRIES_COUNT_OP;

  private int entryIndex;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2DecrementEntriesCountOp() {
  }

  public BTreeMVBucketV2DecrementEntriesCountOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int entryIndex) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    assert entryIndex >= 0 : "entryIndex must be non-negative, got " + entryIndex;
    this.entryIndex = entryIndex;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
    bucket.decrementEntriesCount(entryIndex);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getEntryIndex() {
    return entryIndex;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(entryIndex);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    entryIndex = buffer.getInt();
    assert entryIndex >= 0
        : "Deserialized entryIndex must be non-negative, got " + entryIndex;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVBucketV2DecrementEntriesCountOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return entryIndex == that.entryIndex;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + entryIndex;
    return result;
  }

  @Override
  public String toString() {
    return toString("entryIndex=" + entryIndex);
  }
}
