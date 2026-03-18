package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import javax.annotation.Nonnull;

public interface SingleValueIndexEngine extends V1IndexEngine {

  /**
   * {@inheritDoc}
   *
   * @return {@code true} if a new key was inserted, {@code false} if an
   *     existing key was updated in-place or the validator rejected the
   *     operation (IGNORE).
   */
  boolean validatedPut(
      @Nonnull AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator);

  boolean remove(@Nonnull AtomicOperation atomicOperation, Object key) throws IOException;

  @Override
  default boolean isMultiValue() {
    return false;
  }
}
