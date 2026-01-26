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
/// Supports both Start and Streaming execution modes to allow chaining: g.command("BEGIN").command("INSERT")
public class YTDBCommandService implements Service<Object, Object> {

  public static final String NAME = "command";
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

    @Override
    public String getName() {
      return NAME;
    }

    @Override
    public Set<Type> getSupportedTypes() {
      // Support both Start and Streaming to allow chaining: g.command("BEGIN").command("INSERT")
      return Set.of(Type.Start, Type.Streaming);
    }

    @Override
    public Map<Type, Set<TraverserRequirement>> getRequirementsByType() {
      // Return empty requirements for all types - no special requirements needed
      return Map.of(Type.Start, Set.of(), Type.Streaming, Set.of());
    }

    @Override
    public Service<Object, Object> createService(boolean isStart, Map params) {
      // Handle both formats:
      // 1. From YTDBGraphTraversalSourceDSL.command(): Map.of("command", "...", "args", ...)
      // 2. From Gremlin customService: Map.of("args", List.of(...)) where first arg is the command string
      
      final String command;
      final Map<?, ?> commandParams;

      // Try format 1: direct "command" key (from YTDBGraphTraversalSourceDSL.command())
      final Object commandParam = params != null ? params.get(COMMAND) : null;
      if (commandParam instanceof String c) {
        command = c;
        // Get arguments from "args" key
        if (params.get(ARGUMENTS) instanceof Map<?, ?> m) {
          commandParams = m;
        } else if (params.get(ARGUMENTS) == null) {
          commandParams = Map.of();
        } else {
          throw new IllegalArgumentException("Command parameter '" + ARGUMENTS + "' value '" + params.get(ARGUMENTS) + "' is not a Map");
        }
      } else {
        // Try format 2: from Gremlin customService - args are in "args" as a List
        // First element of args list should be the command string
        final Object argsParam = params != null ? params.get(ARGUMENTS) : null;
        if (argsParam instanceof java.util.List<?> argsList && !argsList.isEmpty()) {
          final Object firstArg = argsList.get(0);
          if (firstArg instanceof String cmd) {
            command = cmd;
            // Remaining args (if any) become commandParams
            if (argsList.size() > 1) {
              final Map<Object, Object> paramsMap = new java.util.LinkedHashMap<>();
              for (int i = 1; i < argsList.size(); i++) {
                paramsMap.put("arg" + (i - 1), argsList.get(i));
              }
              commandParams = paramsMap;
            } else {
              commandParams = Map.of();
            }
          } else {
            throw new IllegalArgumentException("When using Gremlin customService format, first argument in 'args' must be a String (the command), but got: " + firstArg);
          }
        } else if (commandParam == null && (argsParam == null || (argsParam instanceof java.util.List && ((java.util.List<?>) argsParam).isEmpty()))) {
          // Empty params - this can happen during getRequirements() call before actual execution
          // In this case, we can't determine the command, so throw a more helpful error
          // but allow empty params for requirements checking (return minimal service)
          if (params == null || params.isEmpty()) {
            // This is likely a getRequirements() call - return a minimal service
            command = "";
            commandParams = Map.of();
          } else {
            throw new IllegalArgumentException("Command parameter '" + COMMAND + "' is missing or null. " +
                "Expected either: 1) Map with 'command' key, or 2) Map with 'args' key containing List with command as first element. " +
                "Got params: " + params);
          }
        } else {
          throw new IllegalArgumentException("Command parameter '" + COMMAND + "' is missing or null. " +
              "Expected either: 1) Map with 'command' key, or 2) Map with 'args' key containing List with command as first element. " +
              "Got params: " + params);
        }
      }

      Service.Type type = isStart ? Service.Type.Start : Service.Type.Streaming;
      return new YTDBCommandService(command, commandParams, type);
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
    // If command is empty (from getRequirements() call), don't execute
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
