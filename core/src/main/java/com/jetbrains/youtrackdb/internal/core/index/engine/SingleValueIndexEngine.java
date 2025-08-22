package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;

public interface SingleValueIndexEngine extends V1IndexEngine {

  boolean validatedPut(
      AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator);

  boolean remove(AtomicOperation atomicOperation, Object key) throws IOException;

  @Override
  default boolean isMultiValue() {
    return false;
  }
}
