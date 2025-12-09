package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.util.stream.Stream;

public interface V1IndexEngine extends BaseIndexEngine {

  int API_VERSION = 1;

  void put(AtomicOperation atomicOperation, Object key, RID value);

  Stream<RID> get(Object key);

  @Override
  default int getEngineAPIVersion() {
    return API_VERSION;
  }

  boolean isMultiValue();
}
