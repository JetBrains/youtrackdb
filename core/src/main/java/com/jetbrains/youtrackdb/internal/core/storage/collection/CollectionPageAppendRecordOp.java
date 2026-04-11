package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Logical WAL record for {@link CollectionPage#appendRecord(long, byte[], int,
 * it.unimi.dsi.fastutil.ints.IntSet)}. Captures the record version, record bytes, the
 * allocated index, and the actual byte offset ({@code entryPosition}) where the entry
 * was written on the page. During redo, {@link CollectionPage#appendRecordAtPosition}
 * writes directly to the captured position, bypassing non-deterministic hole-finding,
 * defragmentation, and space checking — ensuring the page layout is faithfully
 * reproduced during recovery.
 */
public final class CollectionPageAppendRecordOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.COLLECTION_PAGE_APPEND_RECORD_OP;

  private long recordVersion;
  private byte[] record;
  private int allocatedIndex;
  private int entryPosition;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public CollectionPageAppendRecordOp() {
  }

  public CollectionPageAppendRecordOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long recordVersion, byte[] record,
      int allocatedIndex, int entryPosition) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.recordVersion = recordVersion;
    this.record = record;
    this.allocatedIndex = allocatedIndex;
    this.entryPosition = entryPosition;
  }

  @Override
  public void redo(DurablePage page) {
    var collectionPage = new CollectionPage(page.getCacheEntry());
    collectionPage.appendRecordAtPosition(
        recordVersion, record, entryPosition, allocatedIndex);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getRecordVersion() {
    return recordVersion;
  }

  public byte[] getRecord() {
    return record;
  }

  public int getAllocatedIndex() {
    return allocatedIndex;
  }

  public int getEntryPosition() {
    return entryPosition;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + Long.BYTES // recordVersion
        + Integer.BYTES // record.length
        + record.length // record bytes
        + Integer.BYTES // allocatedIndex
        + Integer.BYTES; // entryPosition
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(recordVersion);
    buffer.putInt(record.length);
    buffer.put(record);
    buffer.putInt(allocatedIndex);
    buffer.putInt(entryPosition);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    recordVersion = buffer.getLong();
    var length = buffer.getInt();
    record = new byte[length];
    buffer.get(record);
    allocatedIndex = buffer.getInt();
    entryPosition = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CollectionPageAppendRecordOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return recordVersion == that.recordVersion
        && allocatedIndex == that.allocatedIndex
        && entryPosition == that.entryPosition
        && Arrays.equals(record, that.record);
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (int) (recordVersion ^ (recordVersion >>> 32));
    result = 31 * result + Arrays.hashCode(record);
    result = 31 * result + allocatedIndex;
    result = 31 * result + entryPosition;
    return result;
  }

  @Override
  public String toString() {
    return toString("recordVersion=" + recordVersion
        + ", recordLen=" + (record != null ? record.length : "null")
        + ", allocatedIndex=" + allocatedIndex
        + ", entryPosition=" + entryPosition);
  }
}
