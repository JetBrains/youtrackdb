package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.local.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link SBTreeNullBucketV2#removeValue(
 * com.jetbrains.youtrackdb.internal.common.serialization.types.BinarySerializer)}.
 * No parameters — simply clears the presence flag. The BinarySerializer parameter is
 * unused (T5).
 */
public final class SBTreeNullBucketV2RemoveValueOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.SBTREE_NULL_BUCKET_V2_REMOVE_VALUE_OP;

  public SBTreeNullBucketV2RemoveValueOp() {
  }

  public SBTreeNullBucketV2RemoveValueOp(
      long pageIndex, long fileId, long operationUnitId,
      LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    // BinarySerializer param is unused by removeValue — pass null (T5)
    var nullBucket = new SBTreeNullBucketV2<>(page.getCacheEntry());
    nullBucket.removeValue(null);
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
