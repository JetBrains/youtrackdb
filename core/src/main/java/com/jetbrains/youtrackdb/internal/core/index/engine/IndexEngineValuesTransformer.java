package com.jetbrains.youtrackdb.internal.core.index.engine;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import java.util.Collection;

public interface IndexEngineValuesTransformer {

  Collection<RID> transformFromValue(Object value);
}
