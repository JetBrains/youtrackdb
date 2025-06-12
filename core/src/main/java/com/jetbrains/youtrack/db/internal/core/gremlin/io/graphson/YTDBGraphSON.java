package com.jetbrains.youtrack.db.internal.core.gremlin.io.graphson;

import java.util.Map;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.io.graphson.TinkerPopJacksonModule;

/**
 * Created by Enrico Risa on 06/09/2017.
 */
public abstract class YTDBGraphSON extends TinkerPopJacksonModule {

  public YTDBGraphSON(String name) {
    super(name);
  }

  @SuppressWarnings("rawtypes")
  @Override
  @Nullable
  public Map<Class, String> getTypeDefinitions() {
    return null;
  }

  @Override
  public String getTypeNamespace() {
    return "youtrackdb";
  }
}
