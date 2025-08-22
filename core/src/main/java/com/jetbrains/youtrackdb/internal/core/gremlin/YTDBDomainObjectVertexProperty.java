package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.YTDBVertexPropertyId;
import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBDomainObject;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import org.apache.commons.collections4.IteratorUtils;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

public class YTDBDomainObjectVertexProperty<T extends YTDBDomainObject, V> implements
    VertexProperty<V> {

  private final @Nonnull T domainObject;
  private final @Nonnull YTDBDomainObjectPToken<T> pToken;
  private YTDBVertexPropertyId id;

  public YTDBDomainObjectVertexProperty(@Nonnull T domainObject,
      @Nonnull YTDBDomainObjectPToken<T> pToken) {
    this.domainObject = domainObject;
    this.pToken = pToken;
  }

  @Override
  public String key() {
    return pToken.name();
  }

  @Override
  public V value() throws NoSuchElementException {
    //noinspection unchecked
    return (V) pToken.fetch(domainObject);
  }

  @Override
  public boolean isPresent() {
    return true;
  }

  @Override
  public Vertex element() {
    return domainObject;
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException(
        "Properties of domain objects can not be removed.");
  }

  @Override
  public Object id() {
    if (id == null) {
      id = new YTDBVertexPropertyId(domainObject.id(), pToken.name());
    }

    return id;
  }

  @Override
  public <R> Property<R> property(String key, R value) {
    throw Exceptions.metaPropertiesNotSupported();
  }

  @Override
  public <U> Iterator<Property<U>> properties(String... propertyKeys) {
    return IteratorUtils.emptyIterator();
  }
}
