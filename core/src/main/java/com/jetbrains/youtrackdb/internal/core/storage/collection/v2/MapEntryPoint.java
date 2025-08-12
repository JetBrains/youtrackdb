package com.jetbrains.youtrackdb.internal.core.storage.collection.v2;

import com.jetbrains.youtrackdb.internal.core.storage.cache.CacheEntry;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.base.DurablePage;

final class MapEntryPoint extends DurablePage {

  private static final int FILE_SIZE_OFFSET = NEXT_FREE_POSITION;

  MapEntryPoint(CacheEntry cacheEntry) {
    super(cacheEntry);
  }

  int getFileSize() {
    return getIntValue(FILE_SIZE_OFFSET);
  }

  void setFileSize(int size) {
    setIntValue(FILE_SIZE_OFFSET, size);
  }
}
