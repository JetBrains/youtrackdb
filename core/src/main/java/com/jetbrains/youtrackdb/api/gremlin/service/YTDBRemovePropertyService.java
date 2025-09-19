package com.jetbrains.youtrackdb.api.gremlin.service;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

public class YTDBRemovePropertyService<E extends YTDBElement> implements Service<E, E> {

  private final Set<String> properties;

  public static final String NAME = "ytdbRemoveProperty";
  public static final String PROPERTIES = "properties";

  public static class Factory<E extends YTDBElement> implements ServiceFactory<E, E> {

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      return Set.of(Type.Streaming);
    }

    @Override
    public Service<E, E> createService(boolean isStart, Map params) {
      if (isStart) {
        throw new UnsupportedOperationException(Exceptions.cannotStartTraversal);
      }

      if (params.get(PROPERTIES) instanceof Set<?> properties &&
          !properties.isEmpty() &&
          properties.iterator().next() instanceof String) {
        //noinspection unchecked
        return new YTDBRemovePropertyService<>((Set<String>) properties);
      }

      throw new IllegalArgumentException(
          "Invalid properties parameter: " + params.get(PROPERTIES));
    }
  }

  public YTDBRemovePropertyService(Set<String> properties) {
    this.properties = properties;
  }

  @Override
  public Type getType() {
    return Type.Streaming;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return Set.of(TraverserRequirement.OBJECT);
  }

  @Override
  public CloseableIterator<E> execute(ServiceCallContext ctx, Traverser.Admin<E> in, Map params) {
    final var element = in.get();
    properties.forEach(element::removeProperty);
    return CloseableIterator.of(IteratorUtils.of(element));
  }
}

