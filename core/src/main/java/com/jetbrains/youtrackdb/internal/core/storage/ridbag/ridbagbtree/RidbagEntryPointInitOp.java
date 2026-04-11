package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link EntryPoint#init()}. No parameters — sets treeSize to 0
 * and pagesSize to 1.
 */
public final class RidbagEntryPointInitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.RIDBAG_ENTRY_POINT_INIT_OP;

  public RidbagEntryPointInitOp() {
  }

  public RidbagEntryPointInitOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var entryPoint = new EntryPoint(page.getCacheEntry());
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
