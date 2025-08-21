package com.jetbrains.youtrackdb.internal.core.index.multivalue;

import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import java.util.Collection;

public final class MultiValuesTransformer implements IndexEngineValuesTransformer {

  public static final MultiValuesTransformer INSTANCE = new MultiValuesTransformer();

  @Override
  public Collection<RID> transformFromValue(Object value) {
    //noinspection unchecked
    return (Collection<RID>) value;
  }
}
