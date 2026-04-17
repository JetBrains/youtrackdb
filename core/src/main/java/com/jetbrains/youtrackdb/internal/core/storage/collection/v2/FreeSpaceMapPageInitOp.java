package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link FreeSpaceMapPage#init()}. Zeroes out the entire usable area
 * of the free space map page, resetting all segment-tree nodes to 0.
 */
public final class FreeSpaceMapPageInitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.FREE_SPACE_MAP_PAGE_INIT_OP;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public FreeSpaceMapPageInitOp() {
  }

  public FreeSpaceMapPageInitOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    // During redo, changes == null so init() writes directly to the buffer.
    // The instanceof CacheEntryChanges check will be false — D4 redo suppression.
    var fsmPage = new FreeSpaceMapPage(page.getCacheEntry());
    fsmPage.init();
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
