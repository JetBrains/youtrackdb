package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CollectionPositionMapBucket#init()}.
 */
public final class CollectionPositionMapBucketInitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.POSITION_MAP_BUCKET_INIT_OP;

  public CollectionPositionMapBucketInitOp() {
  }

  public CollectionPositionMapBucketInitOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    new CollectionPositionMapBucket(page.getCacheEntry()).init();
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }
}
