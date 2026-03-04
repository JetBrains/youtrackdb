package com.jetbrains.youtrackdb.internal.core.index.engine.v1;

import com.jetbrains.youtrackdb.internal.core.index.engine.IndexHistogramManager;
import com.jetbrains.youtrackdb.internal.core.index.engine.V1IndexEngine;
import javax.annotation.Nullable;

public interface BTreeIndexEngine extends V1IndexEngine {

  int VERSION = 4;

  /** Returns the histogram manager, or null if not yet initialized. */
  @Nullable
  IndexHistogramManager getHistogramManager();
}
