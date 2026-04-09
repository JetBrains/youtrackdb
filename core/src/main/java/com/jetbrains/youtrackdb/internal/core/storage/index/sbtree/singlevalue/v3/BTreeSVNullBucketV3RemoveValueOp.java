package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.singlevalue.v3;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CellBTreeSingleValueV3NullBucket#removeValue()}. Clears the
 * presence flag to 0, indicating no value is stored.
 */
public final class BTreeSVNullBucketV3RemoveValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.BTREE_SV_NULL_BUCKET_V3_REMOVE_VALUE_OP;

  /** No-arg constructor for reflection-based deserialization by WALRecordsFactory. */
  public BTreeSVNullBucketV3RemoveValueOp() {
  }

  public BTreeSVNullBucketV3RemoveValueOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var nullBucket = new CellBTreeSingleValueV3NullBucket(page.getCacheEntry());
    nullBucket.removeValue();
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
