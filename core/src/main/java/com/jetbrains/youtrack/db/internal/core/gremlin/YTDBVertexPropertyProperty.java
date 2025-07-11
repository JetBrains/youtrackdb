package com.jetbrains.youtrack.db.internal.core.gremlin;

import java.util.NoSuchElementException;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.util.ElementHelper;
import org.apache.tinkerpop.gremlin.structure.util.StringFactory;

public final class YTDBVertexPropertyProperty<U> implements
    org.apache.tinkerpop.gremlin.structure.Property<U> {

  private final String key;
  private final U value;
  private final YTDBVertexPropertyImpl<?> source;
  private boolean removed = false;

  public YTDBVertexPropertyProperty(
      String key, U value, YTDBVertexPropertyImpl<?> ytdbVertexProperty) {
    this.key = key;
    this.value = value;
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

  @Override
  public boolean isPresent() {
    return !removed;
  }

  @Override
  public Element element() {
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
