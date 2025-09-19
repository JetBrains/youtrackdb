package com.jetbrains.youtrackdb.api.gremlin.embedded;

import com.jetbrains.youtrackdb.api.gremlin.YTDBGraph;
import org.apache.tinkerpop.gremlin.structure.Element;

public interface YTDBElement extends Element {

  @Override
  YTDBGraph graph();

  /// Check if a property exists in this {@code YTDBElement} for a given key.
  boolean hasProperty(String key);

  /// Remove a property from this {@code YTDBElement} for a given key.
  /// @return `true` if the property existed and was removed, `false` otherwise.
  boolean removeProperty(String key);
}
