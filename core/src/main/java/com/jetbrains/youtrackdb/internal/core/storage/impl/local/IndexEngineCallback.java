package com.jetbrains.youtrackdb.internal.core.storage.impl.local;

import com.jetbrains.youtrackdb.internal.core.index.engine.BaseIndexEngine;
import javax.annotation.Nullable;

/**
 * Callback interface for executing operations on an index engine.
 *
 * @since 9/4/2015
 */
public interface IndexEngineCallback<T> {

  @Nullable
  T callEngine(BaseIndexEngine engine);
}
