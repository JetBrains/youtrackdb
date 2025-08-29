package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;

public interface MultiValueIndexEngine extends V1IndexEngine {

  boolean remove(AtomicOperation atomicOperation, Object key, RID value);

  @Override
  default boolean isMultiValue() {
    return true;
  }
}
