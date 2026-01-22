package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraph;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

/// Service returns [java.util.UUID] of the current database. This identification is used during
/// backup and restore of changes of the database.
public class YTDBGraphUuidService implements Service<String, String> {

  public static final String NAME = "ytdbGraphUuid";

  @Override
  public Type getType() {
    return Type.Start;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return Set.of();
  }

  @Override
  public CloseableIterator<String> execute(ServiceCallContext ctx, Map params) {
    final var graph = (((Admin<?, ?>) ctx.getTraversal()))
        .getGraph()
        .orElseThrow(() -> new IllegalStateException("Graph is not available"));

    var uuid = (((YTDBGraph) graph)).uuid();
    return CloseableIterator.of(IteratorUtils.of(uuid.toString()));
  }

  public static class Factory implements ServiceFactory<String, String> {

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      return Set.of(Type.Start);
    }

    @Override
    public Service<String, String> createService(boolean isStart, Map params) {
      if (!isStart) {
        throw new UnsupportedOperationException(Exceptions.cannotUseMidTraversal);
      }

      return new YTDBGraphUuidService();
    }
  }
}
