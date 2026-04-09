package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeSingleValueBucketV3#setNextFreeListPage(int)}.
 * Captures the next free list page index and replays the mutation during crash recovery.
 */
public final class BTreeSVBucketV3SetNextFreeListPageOp extends PageOperation {

  public static final int RECORD_ID =
      WALRecordTypes.BTREE_SV_BUCKET_V3_SET_NEXT_FREE_LIST_PAGE_OP;

  private int nextFreeListPage;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVBucketV3SetNextFreeListPageOp() {
  }

  public BTreeSVBucketV3SetNextFreeListPageOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int nextFreeListPage) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    this.nextFreeListPage = nextFreeListPage;
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeSingleValueBucketV3<>(page.getCacheEntry());
    bucket.setNextFreeListPage(nextFreeListPage);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getNextFreeListPage() {
    return nextFreeListPage;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(nextFreeListPage);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    nextFreeListPage = buffer.getInt();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeSVBucketV3SetNextFreeListPageOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return nextFreeListPage == that.nextFreeListPage;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + nextFreeListPage;
    return result;
  }

  @Override
  public String toString() {
    return toString("nextFreeListPage=" + nextFreeListPage);
  }
}
