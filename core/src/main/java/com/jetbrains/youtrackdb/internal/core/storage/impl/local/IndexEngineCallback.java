package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import javax.annotation.Nullable;

/**
 * @since 9/4/2015
 */
public interface IndexEngineCallback<T> {

  @Nullable
  T callEngine(BaseIndexEngine engine);
}
