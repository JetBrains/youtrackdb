package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link SBTreeBucketV2#switchBucketType()}. No parameters — toggles
 * the leaf/non-leaf flag on an empty bucket.
 */
public final class SBTreeBucketV2SwitchBucketTypeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_BUCKET_V2_SWITCH_BUCKET_TYPE_OP;

  public SBTreeBucketV2SwitchBucketTypeOp() {
  }

  public SBTreeBucketV2SwitchBucketTypeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new SBTreeBucketV2<>(page.getCacheEntry());
    bucket.switchBucketType();
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
