package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBElement;
import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// TinkerPop service that allows running any YouTrackDB non-idempotent command via GraphTraversal.
///
/// This is a `Start` service, meaning that it is not allowed to be used mid-traversal. It always
/// produces an empty result.
public class YTDBCommandService<E extends YTDBElement> implements Service<E, E> {

  public static final String NAME = "ytdbCommand";
  public static final String COMMAND = "command";
  public static final String ARGUMENTS = "args";

  private final String command;
  private final Map<?, ?> commandParams;

  public YTDBCommandService(String command, Map<?, ?> commandParams) {
    this.command = command;
    this.commandParams = commandParams;
  }

  public static class Factory<E extends YTDBElement> implements ServiceFactory<E, E> {

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      return Set.of(Type.Start);
    }

    @Override
    public Service<E, E> createService(boolean isStart, Map params) {
      if (!isStart) {
        throw new UnsupportedOperationException(Exceptions.cannotUseMidTraversal);
      }

      final String command;
      final Map<?, ?> commandParams;

      if (params.get(COMMAND) instanceof String c) {
        command = c;
      } else {
        throw new IllegalArgumentException(params.get(COMMAND) + " is not a String");
      }

      if (params.get(ARGUMENTS) instanceof Map<?, ?> m) {
        commandParams = m;
      } else if (params.get(ARGUMENTS) == null) {
        commandParams = Map.of();
      } else {
        throw new IllegalArgumentException(params.get(ARGUMENTS) + " is not a Map");
      }

      return new YTDBCommandService<>(command, commandParams);
    }
  }

  @Override
  public Type getType() {
    return Type.Start;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return Set.of();
  }

  @Override
  public CloseableIterator<E> execute(ServiceCallContext ctx, Map params) {

    final var graph = (((Admin<?, ?>) ctx.getTraversal()))
        .getGraph()
        .orElseThrow(() -> new IllegalStateException("Graph is not available"));

    (((YTDBGraphInternal) graph)).executeCommand(command, commandParams);

    return CloseableIterator.empty();
  }
}
