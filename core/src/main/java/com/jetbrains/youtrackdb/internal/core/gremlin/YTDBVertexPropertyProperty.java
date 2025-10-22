package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBProperty;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBVertexProperty;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.NoSuchElementException;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBVertexPropertyProperty<U> implements YTDBProperty<U> {

  private final String key;
  private final U value;
  @Nullable
  private final PropertyType propertyType;
  private final YTDBVertexPropertyImpl<?> source;
  private boolean removed = false;

  public YTDBVertexPropertyProperty(
      String key,
      @Nullable U value,
      @Nullable PropertyType propertyType,
      YTDBVertexPropertyImpl<?> ytdbVertexProperty
  ) {
    this.key = key;
    this.value = value;
    this.propertyType = propertyType;
    this.source = ytdbVertexProperty;
  }

  @Override
  public String key() {
    return key;
  }

  @Override
  public U value() throws NoSuchElementException {
    return value;
  }

  @Nullable
  @Override
  public PropertyType type() {
    return propertyType;
  }

  @Override
  public boolean isPresent() {
    return !removed;
  }

  @Override
  public YTDBVertexProperty<?> element() {
    return source;
  }

  @Override
  public void remove() {
    source.removeMetadata(key);
    this.removed = true;
  }

  @Override
  public String toString() {
    return StringFactory.propertyString(this);
  }

  @SuppressWarnings("EqualsDoesntCheckParameterClass")
  @Override
  public boolean equals(final Object object) {
    return ElementHelper.areEqual(this, object);
  }

  @Override
  public int hashCode() {
    return ElementHelper.hashCode(this);
  }
}
