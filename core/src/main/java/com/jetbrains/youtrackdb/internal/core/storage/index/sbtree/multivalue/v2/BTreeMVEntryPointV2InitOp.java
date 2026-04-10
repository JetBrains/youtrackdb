package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2EntryPoint#init()}. Sets tree size to 0,
 * pages size to 1, and entry ID to 0.
 */
public final class BTreeMVEntryPointV2InitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_ENTRY_POINT_V2_INIT_OP;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVEntryPointV2InitOp() {
  }

  public BTreeMVEntryPointV2InitOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new CellBTreeMultiValueV2EntryPoint<>(page.getCacheEntry());
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
