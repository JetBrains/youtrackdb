package com.jetbrains.youtrackdb.internal.core.storage.ridbag.ridbagbtree;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link Bucket#switchBucketType()}. No parameters — toggles the
 * leaf/non-leaf flag.
 */
public final class RidbagBucketSwitchBucketTypeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.RIDBAG_BUCKET_SWITCH_BUCKET_TYPE_OP;

  public RidbagBucketSwitchBucketTypeOp() {
  }

  public RidbagBucketSwitchBucketTypeOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new Bucket(page.getCacheEntry());
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
