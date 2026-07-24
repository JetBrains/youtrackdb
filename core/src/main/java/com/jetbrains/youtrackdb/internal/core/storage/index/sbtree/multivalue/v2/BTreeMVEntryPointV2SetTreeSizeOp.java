package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.ApplyTier;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2EntryPoint#setTreeSize(long)}.
 * Captures the tree size and replays the mutation during crash recovery.
 */
public final class BTreeMVEntryPointV2SetTreeSizeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_TREE_SIZE_OP;

  private long size;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVEntryPointV2SetTreeSizeOp() {
  }

  public BTreeMVEntryPointV2SetTreeSizeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long size) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    assert size >= 0 : "tree size must be non-negative, was: " + size;
    this.size = size;
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(page.getCacheEntry());
    entryPoint.setTreeSize(size);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getSize() {
    return size;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(size);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    size = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVEntryPointV2SetTreeSizeOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return size == that.size;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (int) (size ^ (size >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("size=" + size);
  }

  /**
   * {@link ApplyTier#UNORDERED}: Legacy-dead operation of the sbtree multivalue v2 format: no live
   * tree implementation exists (BTreeMultiValueIndexEngine wraps BTree v3; the page wrappers are
   * referenced outside the package only by the WAL registry), so this op is never produced. It is
   * registered solely so recovery can deserialize historical WAL records; any commit containing it
   * takes the epoch-bracket fallback.
   */
  @Override
  public ApplyTier applyTier() {
    return ApplyTier.UNORDERED;
  }
}
