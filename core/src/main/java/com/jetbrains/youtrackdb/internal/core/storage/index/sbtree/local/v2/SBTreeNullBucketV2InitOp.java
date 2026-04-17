package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link SBTreeNullBucketV2#init()}. No parameters — simply sets the
 * presence flag to 0.
 */
public final class SBTreeNullBucketV2InitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_NULL_BUCKET_V2_INIT_OP;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public SBTreeNullBucketV2InitOp() {
  }

  public SBTreeNullBucketV2InitOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var nullBucket = new SBTreeNullBucketV2<>(page.getCacheEntry());
    nullBucket.init();
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
