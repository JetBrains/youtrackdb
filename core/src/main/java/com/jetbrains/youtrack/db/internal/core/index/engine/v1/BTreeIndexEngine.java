package com.jetbrains.youtrack.db.internal.core.index.engine.v1;

import com.jetbrains.youtrack.db.internal.core.index.engine.V1IndexEngine;

public interface BTreeIndexEngine extends V1IndexEngine {

  int VERSION = 4;
}
