package com.jetbrains.youtrackdb.api.gremlin.embedded;

import java.util.Iterator;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;

public interface YTDBElement extends Element {

  /// Check if a property exists in this {@code YTDBElement} for a given key.
  boolean hasProperty(String key);

  /// Remove a property from this {@code YTDBElement} for a given key.
  ///
  /// @return `true` if the property existed and was removed, `false` otherwise.
  boolean removeProperty(String key);

  @Override
  default <V> YTDBProperty<V> property(String key) {
    final Iterator<? extends Property<V>> iterator = this.properties(key);
    return iterator.hasNext() ? (YTDBProperty<V>) iterator.next() : YTDBProperty.empty();
  }

  @Override
  <V> YTDBProperty<V> property(String key, V value);
}
