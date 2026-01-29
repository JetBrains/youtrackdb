package com.jetbrains.youtrackdb.internal.core.gremlin.service;

import com.jetbrains.youtrackdb.internal.core.gremlin.YTDBGraphInternal;
import java.util.Map;
import java.util.Set;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal.Admin;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.traverser.TraverserRequirement;
import org.apache.tinkerpop.gremlin.structure.service.Service;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;
import org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils;

/// TinkerPop service that allows running any YouTrackDB non-idempotent command via GraphTraversal.
///
/// Supports both Start and Streaming execution modes to allow chaining: g.sqlCommand("BEGIN").sqlCommand("INSERT")
public class YTDBCommandService implements Service<Object, Object> {

  public static final String NAME = "command";
  public static final String SQL_COMMAND_NAME = "sqlCommand";
  public static final String COMMAND = "command";
  public static final String ARGUMENTS = "args";

  private final String command;
  private final Map<?, ?> commandParams;
  private final Service.Type type;

  public YTDBCommandService(String command, Map<?, ?> commandParams, Service.Type type) {
    this.command = command;
    this.commandParams = commandParams;
    this.type = type;
  }

  public static class Factory implements ServiceFactory<Object, Object> {

    private final String name;

    public Factory() {
      this(NAME);
    }

    protected Factory(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      // Support both Start and Streaming to allow chaining: g.sqlCommand("BEGIN").sqlCommand("INSERT")
      return Set.of(Type.Start, Type.Streaming);
    }

    @Override
    public Map<Type, Set<TraverserRequirement>> getRequirementsByType() {
      // Return empty requirements for all types - no special requirements needed
      return Map.of(Type.Start, Set.of(), Type.Streaming, Set.of());
    }

    @Override
    public Service<Object, Object> createService(boolean isStart, Map params) {
      final Map<?, ?> safeParams = (params == null) ? Map.of() : params;
      var finalCommand = "";
      Map<?, ?> finalCommandParams = Map.of();

      if (safeParams.get(COMMAND) instanceof String cmd) {
        finalCommand = cmd;
        if (safeParams.get(ARGUMENTS) instanceof Map<?, ?> m) {
          finalCommandParams = m;
        }
      }
      else if (safeParams.get(ARGUMENTS) instanceof java.util.List<?> argsList
          && !argsList.isEmpty()) {
        if (argsList.getFirst() instanceof String cmd) {
          finalCommand = cmd;
          if (argsList.size() > 1) {
            var map = new java.util.LinkedHashMap<Object, Object>();
            for (var i = 1; i + 1 < argsList.size(); i += 2) {
              map.put(argsList.get(i), argsList.get(i + 1));
            }
            finalCommandParams = map;
          }
        }
      }

      return new YTDBCommandService(finalCommand, finalCommandParams,
          isStart ? Type.Start : Type.Streaming);
    }
  }

  /// Factory for the sqlCommand service - an alias for command that can be used for chaining.
  public static class SqlCommandFactory extends Factory {
    public SqlCommandFactory() {
      super(SQL_COMMAND_NAME);
    }
  }

  @Override
  public Type getType() {
    return type;
  }

  @Override
  public Set<TraverserRequirement> getRequirements() {
    return Set.of();
  }

  @Override
  public CloseableIterator<Object> execute(ServiceCallContext ctx, Map params) {
    if (command.isEmpty()) {
      return CloseableIterator.of(IteratorUtils.of(Boolean.TRUE));
    }

    final var graph = (((Admin<?, ?>) ctx.getTraversal()))
        .getGraph()
        .orElseThrow(() -> new IllegalStateException("Graph is not available"));

    (((YTDBGraphInternal) graph)).executeCommand(command, commandParams);

    // Emit a single placeholder so chaining works (Streaming needs an input traverser).
    return CloseableIterator.of(IteratorUtils.of(Boolean.TRUE));
  }

  @Override
  public CloseableIterator<Object> execute(ServiceCallContext ctx, Traverser.Admin<Object> in,
      Map params) {
    // If command is empty (from getRequirements() call), don't execute
    if (command.isEmpty()) {
      return CloseableIterator.of(IteratorUtils.of(in.get()));
    }
    
    final var graph = (((Admin<?, ?>) ctx.getTraversal()))
        .getGraph()
        .orElseThrow(() -> new IllegalStateException("Graph is not available"));

    (((YTDBGraphInternal) graph)).executeCommand(command, commandParams);

    // Pass through the input traverser for Streaming mode
    return CloseableIterator.of(IteratorUtils.of(in.get()));
  }
}
