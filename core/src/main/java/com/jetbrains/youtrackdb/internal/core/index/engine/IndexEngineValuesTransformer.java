package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.api.record.RID;
import java.util.Collection;

public interface IndexEngineValuesTransformer {

  Collection<RID> transformFromValue(Object value);
}
