package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CellBTreeSingleValueEntryPointV3#init()}. Sets tree size to 0,
 * pages size to 1, and free list head to -1.
 */
public final class BTreeSVEntryPointV3InitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_ENTRY_POINT_V3_INIT_OP;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVEntryPointV3InitOp() {
  }

  public BTreeSVEntryPointV3InitOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new CellBTreeSingleValueEntryPointV3<>(page.getCacheEntry());
    entryPoint.init();
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }

  @Override
  public String toString() {
    return toString("");
  }
}
