package com.jetbrains.youtrackdb.internal.common.function;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;

public interface TxConsumer {

  void accept(final AtomicOperation atomicOperation) throws Exception;
}
