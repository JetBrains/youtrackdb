package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeSingleValueEntryPointV3#setPagesSize(int)}.
 * Captures the pages size and replays the mutation during crash recovery.
 */
public final class BTreeSVEntryPointV3SetPagesSizeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_SET_PAGES_SIZE_OP;

  private int pages;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVEntryPointV3SetPagesSizeOp() {
  }

  public BTreeSVEntryPointV3SetPagesSizeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int pages) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.pages = pages;
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new CellBTreeSingleValueEntryPointV3<>(page.getCacheEntry());
    entryPoint.setPagesSize(pages);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getPages() {
    return pages;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(pages);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    pages = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVEntryPointV3SetPagesSizeOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return pages == that.pages;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + pages;
    return result;
  }

  @Override
  public String toString() {
    return toString("pages=" + pages);
  }
}
