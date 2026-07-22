package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.ApplyTier;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;
import java.nio.ByteBuffer;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2EntryPoint#setEntryId(long)}.
 * Captures the entry ID and replays the mutation during crash recovery.
 */
public final class BTreeMVEntryPointV2SetEntryIdOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_SET_ENTRY_ID_OP;

  private long id;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVEntryPointV2SetEntryIdOp() {
  }

  public BTreeMVEntryPointV2SetEntryIdOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn, long id) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
    assert id >= 0 : "entry id must be non-negative, was: " + id;
    this.id = id;
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(page.getCacheEntry());
    entryPoint.setEntryId(id);
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  public long getEntryId() {
    return id;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + Long.BYTES;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    super.serializeToByteBuffer(buffer);
    buffer.putLong(id);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    super.deserializeFromByteBuffer(buffer);
    id = buffer.getLong();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof BTreeMVEntryPointV2SetEntryIdOp that)) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    return id == that.id;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (int) (id ^ (id >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("id=" + id);
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
