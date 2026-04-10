package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2EntryPoint#setPagesSize(int)}.
 * Captures the pages size and replays the mutation during crash recovery.
 */
public final class BTreeMVEntryPointV2SetPagesSizeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_PAGES_SIZE_OP;

  private int pages;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVEntryPointV2SetPagesSizeOp() {
  }

  public BTreeMVEntryPointV2SetPagesSizeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int pages) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.pages = pages;
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(page.getCacheEntry());
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
    if (!(o instanceof BTreeMVEntryPointV2SetPagesSizeOp that)) {
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
