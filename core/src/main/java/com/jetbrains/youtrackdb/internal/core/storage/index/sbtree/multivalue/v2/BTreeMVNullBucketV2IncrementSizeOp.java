package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2NullBucket#incrementSize()}.
 * Increments the total size counter by 1.
 */
public final class BTreeMVNullBucketV2IncrementSizeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_NULL_BUCKET_V2_INCREMENT_SIZE_OP;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVNullBucketV2IncrementSizeOp() {
  }

  public BTreeMVNullBucketV2IncrementSizeOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var nullBucket = new CellBTreeMultiValueV2NullBucket(page.getCacheEntry());
    nullBucket.incrementSize();
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
