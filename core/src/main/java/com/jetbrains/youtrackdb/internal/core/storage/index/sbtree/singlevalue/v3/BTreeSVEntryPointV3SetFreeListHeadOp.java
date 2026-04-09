package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeSingleValueEntryPointV3#setFreeListHead(int)}.
 * Captures the free list head page index and replays the mutation during crash recovery.
 */
public final class BTreeSVEntryPointV3SetFreeListHeadOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_FREE_LIST_HEAD_OP;

  private int freeListHead;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVEntryPointV3SetFreeListHeadOp() {
  }

  public BTreeSVEntryPointV3SetFreeListHeadOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int freeListHead) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.freeListHead = freeListHead;
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new CellBTreeSingleValueEntryPointV3<>(page.getCacheEntry());
    entryPoint.setFreeListHead(freeListHead);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getFreeListHead() {
    return freeListHead;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(freeListHead);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    freeListHead = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVEntryPointV3SetFreeListHeadOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return freeListHead == that.freeListHead;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + freeListHead;
    return result;
  }

  @Override
  public String toString() {
    return toString("freeListHead=" + freeListHead);
  }
}
