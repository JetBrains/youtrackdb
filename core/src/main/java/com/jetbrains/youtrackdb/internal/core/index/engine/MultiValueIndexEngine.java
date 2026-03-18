package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import javax.annotation.Nonnull;

public interface MultiValueIndexEngine extends V1IndexEngine {

  boolean remove(@Nonnull AtomicOperation atomicOperation, Object key, RID value);

  @Override
  default boolean isMultiValue() {
    return true;
  }
}
