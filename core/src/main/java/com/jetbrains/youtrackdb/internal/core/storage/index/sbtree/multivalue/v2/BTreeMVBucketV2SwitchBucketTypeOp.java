package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CellBTreeMultiValueV2Bucket#switchBucketType()}. Toggles the
 * leaf/non-leaf flag on an empty bucket.
 */
public final class BTreeMVBucketV2SwitchBucketTypeOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_MV_BUCKET_V2_SWITCH_BUCKET_TYPE_OP;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeMVBucketV2SwitchBucketTypeOp() {
  }

  public BTreeMVBucketV2SwitchBucketTypeOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var bucket = new CellBTreeMultiValueV2Bucket<>(page.getCacheEntry());
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
