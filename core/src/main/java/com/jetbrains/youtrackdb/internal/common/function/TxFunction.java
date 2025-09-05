package com.jetbrains.youtrackdb.internal.common.function;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import javax.annotation.Nullable;

public interface TxFunction<T> {

  @Nullable
  T accept(final AtomicOperation atomicOperation) throws Exception;
}
