package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link FreeSpaceMapPage#updatePageMaxFreeSpace(int, int)}. Captures
 * the leaf page index and the new free-space value. During redo, calls the same method which
 * deterministically propagates the update through the segment tree.
 */
public final class FreeSpaceMapPageUpdateOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.FREE_SPACE_MAP_PAGE_UPDATE_OP;

  private int fsmPageIndex;
  private int freeSpace;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public FreeSpaceMapPageUpdateOp() {
  }

  public FreeSpaceMapPageUpdateOp(
      long walPageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, int fsmPageIndex, int freeSpace) {
    super(walPageIndex, fileId, operationUnitId, initialLsn);
    assert fsmPageIndex >= 0 : "fsmPageIndex must be non-negative, got: " + fsmPageIndex;
    assert freeSpace >= 0 && freeSpace < 256
        : "freeSpace must be in [0, 255], got: " + freeSpace;
    this.fsmPageIndex = fsmPageIndex;
    this.freeSpace = freeSpace;
  }

  @Override
  public void redo(DurablePage page) {
    // During redo, changes == null so updatePageMaxFreeSpace writes directly to the buffer.
    // The instanceof CacheEntryChanges check will be false — D4 redo suppression.
    var fsmPage = new FreeSpaceMapPage(page.getCacheEntry());
    fsmPage.updatePageMaxFreeSpace(fsmPageIndex, freeSpace);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public int getFsmPageIndex() {
    return fsmPageIndex;
  }

  public int getFreeSpace() {
    return freeSpace;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Integer.BYTES + Integer.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putInt(fsmPageIndex);
    buffer.putInt(freeSpace);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    fsmPageIndex = buffer.getInt();
    freeSpace = buffer.getInt();
    assert fsmPageIndex >= 0
        : "Deserialized fsmPageIndex must be non-negative, got: " + fsmPageIndex;
    assert freeSpace >= 0 && freeSpace < 256
        : "Deserialized freeSpace must be in [0, 255], got: " + freeSpace;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof FreeSpaceMapPageUpdateOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return fsmPageIndex == that.fsmPageIndex && freeSpace == that.freeSpace;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + fsmPageIndex;
    result = 31 * result + freeSpace;
    return result;
  }

  @Override
  public String toString() {
    return toString("fsmPageIndex=" + fsmPageIndex + ", freeSpace=" + freeSpace);
  }
}
