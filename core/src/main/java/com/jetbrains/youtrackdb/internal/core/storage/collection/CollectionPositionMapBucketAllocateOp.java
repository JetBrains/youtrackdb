package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CollectionPositionMapBucket#allocate()}.
 * No extra params — allocate is deterministic (always allocates at index = current size).
 */
public final class CollectionPositionMapBucketAllocateOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.POSITION_MAP_BUCKET_ALLOCATE_OP;

  public CollectionPositionMapBucketAllocateOp() {
  }

  public CollectionPositionMapBucketAllocateOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    new CollectionPositionMapBucket(page.getCacheEntry()).allocate();
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }
}
