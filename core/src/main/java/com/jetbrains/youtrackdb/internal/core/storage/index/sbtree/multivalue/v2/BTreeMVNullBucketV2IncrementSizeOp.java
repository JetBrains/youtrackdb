package com.jetbrains.youtrackdb.internal.core.storage.index.sbtree.multivalue.v2;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.ApplyTier;
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

  /**
   * {@link ApplyTier#UNORDERED}: Legacy-dead operation of the sbtree multivalue v2 format: no live
   * tree implementation exists (BTreeMultiValueIndexEngine wraps BTree v3; the page wrappers are
   * referenced outside the package only by the WAL registry), so this op is never produced. It is
   * registered solely so recovery can deserialize historical WAL records; any commit containing it
   * takes the epoch-bracket fallback.
   */
  @Override
  public ApplyTier applyTier() {
    return ApplyTier.UNORDERED;
  }
}
