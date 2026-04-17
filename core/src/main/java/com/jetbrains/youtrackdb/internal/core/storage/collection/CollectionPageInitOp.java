package com.jetbrains.youtrackdb.internal.core.storage.collection;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.PageOperation;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.WALRecordTypes;

/**
 * Logical WAL record for {@link CollectionPage#init()}. No additional parameters
 * beyond the base {@link PageOperation} fields — init always produces the same page state.
 */
public final class CollectionPageInitOp extends PageOperation {

  public static final int RECORD_ID = WALRecordTypes.COLLECTION_PAGE_INIT_OP;

  public CollectionPageInitOp() {
  }

  public CollectionPageInitOp(
      long pageIndex, long fileId, long operationUnitId, LogSequenceNumber initialLsn) {
    super(pageIndex, fileId, operationUnitId, initialLsn);
  }

  @Override
  public void redo(DurablePage page) {
    var collectionPage = new CollectionPage(page.getCacheEntry());
    collectionPage.init();
  }

  @Override
  public int getId() {
    return RECORD_ID;
  }
}
