package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link DirtyPageBitSetPage#clearBit(int)}. Captures the bit index
 * and replays the clear-bit mutation during crash recovery.
 */
public final class DirtyPageBitSetPageClearBitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.DIRTY_PAGE_BIT_SET_PAGE_CLEAR_BIT_OP;

  private int bitIndex;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public DirtyPageBitSetPageClearBitOp() {
  }

  public DirtyPageBitSetPageClearBitOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int bitIndex) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    assert bitIndex >= 0 : "bitIndex must be non-negative, got: " + bitIndex;
    this.bitIndex = bitIndex;
  }

  @Override
  public void redo(DurablePage page) {
    // During redo, changes == null — D4 redo suppression.
    var bitSetPage = new DirtyPageBitSetPage(page.getCacheEntry());
    bitSetPage.clearBit(bitIndex);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getBitIndex() {
    return bitIndex;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(bitIndex);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    bitIndex = buffer.getInt();
    assert bitIndex >= 0 : "Deserialized bitIndex must be non-negative, got: " + bitIndex;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof DirtyPageBitSetPageClearBitOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return bitIndex == that.bitIndex;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + bitIndex;
    return result;
  }

  @Override
  public String toString() {
    return toString("bitIndex=" + bitIndex);
  }
}
