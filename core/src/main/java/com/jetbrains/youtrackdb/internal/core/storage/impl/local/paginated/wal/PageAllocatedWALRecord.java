package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

/** Declares a page made physically present by an atomic unit, including gap-filled pages. */
public final class PageAllocatedWALRecord extends AbstractPageWALRecord {

  public PageAllocatedWALRecord() {
  }

  public PageAllocatedWALRecord(long pageIndex, long fileId, long operationUnitId) {
    super(pageIndex, fileId, operationUnitId);
  }

  @Override
  public int getId() {
    return WALRecordTypes.PAGE_ALLOCATED_WAL_RECORD;
  }
}
